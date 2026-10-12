package dev.insight.enrichment

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.SymbolKind

class SymbolFactsExtractorTest : LightJavaCodeInsightFixtureTestCase() {
    private val javaSource = """
        package sample;

        import java.util.List;
        import static java.util.Collections.emptyList;

        /** A friendly service. */
        @Deprecated
        public class Greeter {
            private final String prefix = "Hello";

            /**
             * Greets a person.
             *
             * @param name who to greet
             */
            @Override
            @SuppressWarnings("unchecked")
            public String greet(String name, int times) {
                return prefix + name + times;
            }

            void overload() {}
            void overload(int x) {}
            void same() {}
            void same() {}
        }
    """.trimIndent()

    private val kotlinSource = """

        import java.util.Locale

        /** Adds two numbers. */
        @Suppress("unused")
        fun add(a: Int, b: Int = 1): Int {
            return a + b
        }

        fun twice(a: Int) = a * 2

        class Box(val value: Int) {
            fun show(): String = "box"
        }
    """.trimIndent()

    private fun extract(budget: FactsBudget = FactsBudget()): Map<Int, SymbolFacts> {
        val result = ProjectGraphAnalyzer.build(project)
        return SymbolFactsExtractor.extract(project, result, budget)
    }

    private fun Map<Int, SymbolFacts>.find(kind: SymbolKind, name: String) =
        values.filter { it.kind == kind && it.name.startsWith(name) }.sortedBy { it.id }

    fun `test java method signature has no body or doc and keeps annotations and doc text`() {
        myFixture.addFileToProject("sample/Greeter.java", javaSource)
        val greet = extract().find(SymbolKind.FUNCTION, "greet").single()
        assertEquals("public String greet(String name, int times)", greet.signature)
        assertEquals(listOf("Override", "SuppressWarnings"), greet.annotations)
        assertEquals("Greets a person.\n\n@param name who to greet", greet.doc)
        assertFalse(greet.code.contains("Greets a person"))
        assertTrue(greet.code.contains("return prefix + name + times;"))
        assertTrue(greet.code.startsWith("@Override"))
        assertEquals("sample/Greeter.java", greet.path?.substringAfter("src/"))
        assertEquals("java", greet.language)
        assertEquals(listOf("java.util.List", "static java.util.Collections.emptyList"), greet.imports)
    }

    fun `test heuristics apply to extracted names that carry parameters`() {
        myFixture.addFileToProject("sample/App.java", """
            package sample;

            public class App {
                public static void main(String[] args) {}
            }

            abstract class Listener {
                abstract void actionPerformed(Object event);
            }

            class Clicker extends Listener {
                @Override
                void actionPerformed(Object event) {}
                void testHelper() {}
            }
        """.trimIndent())
        val facts = extract()
        val main = facts.find(SymbolKind.FUNCTION, "main").single()
        assertTrue(main.name, main.name.startsWith("main("))
        assertTrue("entry:main" in HeuristicRules.tags(main))
        val listener = facts.find(SymbolKind.FUNCTION, "actionPerformed").single { "Override" in it.annotations }
        assertTrue("entry:listener" in HeuristicRules.tags(listener))
        assertTrue("test" in HeuristicRules.tags(facts.find(SymbolKind.FUNCTION, "testHelper").single()))
    }

    fun `test java class outline lists members instead of full text`() {
        myFixture.addFileToProject("sample/Greeter.java", javaSource)
        val facts = extract()
        val greeter = facts.find(SymbolKind.CLASS, "Greeter").single()
        assertEquals("public class Greeter", greeter.signature)
        assertEquals(listOf("Deprecated"), greeter.annotations)
        assertEquals("A friendly service.", greeter.doc)
        val lines = greeter.code.lines()
        assertEquals("public class Greeter", lines.first())
        assertTrue(lines.any { it.trim() == "private final String prefix" })
        assertTrue(lines.any { it.trim() == "public String greet(String name, int times)" })
        assertFalse(greeter.code.contains("return"))
        val file = facts.find(SymbolKind.FILE, "Greeter.java").single()
        assertEquals("Greeter.java", file.signature)
        assertTrue(file.code.contains("public class Greeter"))
    }

    fun `test kotlin function and class`() {
        myFixture.addFileToProject("sample/Sample.kt", kotlinSource)
        val facts = extract()
        val add = facts.find(SymbolKind.FUNCTION, "add").single()
        assertEquals("fun add(a: Int, b: Int = 1): Int", add.signature)
        assertEquals(listOf("Suppress"), add.annotations)
        assertEquals("Adds two numbers.", add.doc)
        assertEquals("kotlin", add.language)
        assertEquals(listOf("java.util.Locale"), add.imports)
        assertFalse(add.code.contains("Adds two"))
        assertTrue(add.code.contains("return a + b"))
        assertEquals("fun twice(a: Int)", facts.find(SymbolKind.FUNCTION, "twice").single().signature)
        val box = facts.find(SymbolKind.CLASS, "Box").single()
        assertEquals("class Box(val value: Int)", box.signature)
        assertTrue(box.code.contains("fun show(): String"))
        assertFalse(box.code.contains("\"box\""))
        assertEquals("val value: Int", facts.find(SymbolKind.PROPERTY, "value").single().signature)
    }

    fun `test only declarations are described`() {
        myFixture.addFileToProject("sample/Greeter.java", javaSource)
        val facts = extract()
        assertTrue(facts.values.all { it.kind in setOf(SymbolKind.FILE, SymbolKind.CLASS, SymbolKind.FUNCTION, SymbolKind.PROPERTY) })
        assertTrue(facts.values.none { it.path?.startsWith("/") == true && it.path.contains("temp:") })
        assertTrue(facts.values.all { it.contentHash.length == 64 })
    }

    fun `test code is truncated to the budget`() {
        myFixture.addFileToProject("sample/Greeter.java", javaSource)
        val greet = extract(FactsBudget(codeChars = 30, signatureChars = 20, docChars = 10)).find(SymbolKind.FUNCTION, "greet").single()
        assertEquals(30, greet.code.length)
        assertTrue(greet.code.endsWith("…"))
        assertEquals(20, greet.signature.length)
        assertTrue(greet.signature.endsWith("…"))
        assertEquals(10, greet.doc!!.length)
    }

    fun `test outline is limited to the entry budget`() {
        myFixture.addFileToProject("sample/Greeter.java", javaSource)
        val greeter = extract(FactsBudget(outlineEntries = 1)).find(SymbolKind.CLASS, "Greeter").single()
        assertEquals(3, greeter.code.lines().size)
        assertTrue(greeter.code.lines().last().trim().endsWith("more"))
    }

    fun `test keys are unique and stable and overloads get ordinals`() {
        myFixture.addFileToProject("sample/Greeter.java", javaSource)
        val first = extract()
        val second = extract()
        assertEquals(first.mapValues { it.value.key }, second.mapValues { it.value.key })
        assertEquals(first.size, first.values.map { it.key }.toSet().size)
        val overloads = first.find(SymbolKind.FUNCTION, "overload").map { it.key }
        assertEquals(2, overloads.size)
        assertTrue(overloads[0].endsWith("|overload()"))
        assertTrue(overloads[1].endsWith("|overload(int)"))
        val same = first.find(SymbolKind.FUNCTION, "same").sortedBy { it.id }.map { it.key }
        assertEquals(2, same.size)
        assertFalse(same[0].contains("#"))
        assertTrue(same[1], same[1].endsWith("|same()#1"))
        val greet = first.find(SymbolKind.FUNCTION, "greet").single()
        assertTrue(greet.key, greet.key.startsWith("${greet.path}|FUNCTION|"))
    }

    fun `test content hash changes with code but not with unrelated edits`() {
        val file = myFixture.addFileToProject("sample/Greeter.java", javaSource)
        val before = extract().find(SymbolKind.FUNCTION, "greet").single()
        assertEquals(before.contentHash, extract().find(SymbolKind.FUNCTION, "greet").single().contentHash)
        val edited = javaSource.replace("prefix + name + times", "prefix + name")
        myFixture.saveText(file.virtualFile, edited)
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val after = extract().find(SymbolKind.FUNCTION, "greet").single()
        assertEquals(before.signature, after.signature)
        assertNotSame(before.contentHash, after.contentHash)
        assertFalse(before.contentHash == after.contentHash)
        assertEquals(before.key, after.key)
        assertNotNull(file)
    }

    fun `test full source hash invalidates summaries when an edit is beyond the excerpt`() {
        val source = "class Store { void save() { " + "int count = 0;" + " ".repeat(120) + "count++; } }"
        val file = myFixture.addFileToProject("Store.java", source)
        val budget = FactsBudget(codeChars = 30)
        val before = extract(budget).find(SymbolKind.FUNCTION, "save").single()
        assertTrue(before.codeTruncated)
        myFixture.saveText(file.virtualFile, source.replace("count++;", "count--;"))
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val after = extract(budget).find(SymbolKind.FUNCTION, "save").single()
        assertEquals(before.code, after.code)
        assertEquals(before.contentHash, after.contentHash)
        assertFalse(before.sourceHash == after.sourceHash)
    }
}
