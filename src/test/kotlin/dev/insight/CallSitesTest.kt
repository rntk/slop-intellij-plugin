package dev.insight

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*

/** `callee: KIND label ?subject / …` for each call site of [caller], outermost frame first. */
private fun sites(project: Project, caller: String): List<String> {
    val result = ProjectGraphAnalyzer.build(project)
    val functions = result.graph.nodes.filter { it.kind == SymbolKind.FUNCTION }
    val id = functions.single { it.name.substringBefore('(') == caller }.id
    return CallSiteAnalyzer.collect(result, listOf(id), functions.map { it.id }).getValue(id).map { site ->
        result.graph.nodes[site.callee].name.substringBefore('(') + ":" + site.frames.joinToString(" /") { frame ->
            " ${frame.kind} ${if (frame.otherwise) "else" else frame.label}" + (frame.subject?.let { " ?$it" } ?: "")
        }
    }
}

/** Constructs of the first frame of [callees]' sites, in order. */
private fun constructs(project: Project, caller: String): List<Int?> {
    val result = ProjectGraphAnalyzer.build(project)
    val functions = result.graph.nodes.filter { it.kind == SymbolKind.FUNCTION }
    val id = functions.single { it.name.substringBefore('(') == caller }.id
    return CallSiteAnalyzer.collect(result, listOf(id), functions.map { it.id }).getValue(id).map { it.frames.firstOrNull()?.construct }
}

class CallSitesTest : LightJavaCodeInsightFixtureTestCase() {
    fun `test Java conditions, else-if chains, switch rules, loops, catch and ternaries`() {
        myFixture.configureByText("Flow.java", """
            class Flow {
                void a() {} void b() {} void c() {} void d() {} void e() {} void f() {} void g() {} void h() {}
                int k() { return 1; }
                void run(int kind, java.util.List<String> xs, boolean ok) {
                    a();
                    if (kind == 1) { b(); } else if (kind == 2) { c(); } else { d(); }
                    switch (kind) { case 3 -> e(); default -> f(); }
                    for (String x : xs) { g(); }
                    try { h(); } catch (RuntimeException ex) { a(); }
                    int v = ok ? k() : 0;
                }
            }
        """.trimIndent())
        assertEquals(listOf(
            "a:",
            "b: BRANCH kind == 1 ?kind == 1",
            "c: BRANCH kind == 2 ?kind == 1",
            "d: BRANCH else ?kind == 1",
            "e: CASE 3 ?kind",
            "f: CASE else ?kind",
            "g: LOOP for (String x : xs)",
            "h:",
            "a: ERROR RuntimeException ex",
            "k: BRANCH ok ?ok",
        ), sites(project, "run"))
        val chain = constructs(project, "run").subList(1, 4)
        assertEquals("else-if branches share one construct", 1, chain.toSet().size)
    }

    fun `test nested and chained calls come in evaluation order`() {
        myFixture.configureByText("Chain.java", """
            class Chain {
                Chain make() { return this; }
                void use(int value) {}
                int count() { return 0; }
                void run() { make().use(count()); }
            }
        """.trimIndent())
        assertEquals(listOf("make:", "count:", "use:"), sites(project, "run"))
    }

    fun `test old-style Java switch labels guard the statements after them`() {
        myFixture.configureByText("Old.java", """
            class Old {
                void a() {} void b() {}
                void run(int kind) {
                    switch (kind) {
                        case 1:
                            a();
                            break;
                        default:
                            b();
                    }
                }
            }
        """.trimIndent())
        assertEquals(listOf("a: CASE 1 ?kind", "b: CASE else ?kind"), sites(project, "run"))
    }

    fun `test Kotlin when, if expressions, loops and catch`() {
        myFixture.configureByText("Flow.kt", """
            class Flow {
                fun a() {} fun b() {} fun c() {} fun d() {} fun e() {} fun f() {} fun g() {} fun h() {}
                fun run(kind: Int, xs: List<String>, ok: Boolean) {
                    a()
                    when (kind) {
                        1 -> b()
                        2, 3 -> c()
                        else -> d()
                    }
                    if (ok) e() else f()
                    for (x in xs) g()
                    try { h() } catch (ex: Exception) { a() }
                    fun local() { b() }
                }
            }
        """.trimIndent())
        assertEquals(listOf(
            "a:",
            "b: CASE 1 ?kind",
            "c: CASE 2, 3 ?kind",
            "d: CASE else ?kind",
            "e: BRANCH ok ?ok",
            "f: BRANCH else ?ok",
            "g: LOOP for (x in xs)",
            "h:",
            "a: ERROR ex: Exception",
        ), sites(project, "run"))
    }
}

class PythonCallSitesTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        assertEquals("PythonCore is not loaded in the test IDE", "Python", FileTypeManager.getInstance().getFileTypeByFileName("a.py").name)
    }

    fun `test Python if-elif-else, loops, try-except and conditional expressions`() {
        myFixture.configureByText("flow.py", """
            def a(): pass
            def b(): pass
            def c(): pass
            def d(): pass
            def e(): pass
            def f(): pass
            def g(): pass
            def h(): pass

            def run(kind, xs):
                a()
                if kind == 1:
                    b()
                elif kind == 2:
                    c()
                else:
                    d()
                for x in xs:
                    e()
                try:
                    f()
                except ValueError as err:
                    g()
                v = h() if kind else None
        """.trimIndent())
        assertEquals(listOf(
            "a:",
            "b: BRANCH kind == 1 ?kind == 1",
            "c: BRANCH kind == 2 ?kind == 1",
            "d: BRANCH else ?kind == 1",
            "e: LOOP for x in xs",
            "f:",
            "g: ERROR ValueError as err",
            "h: BRANCH kind ?kind",
        ), sites(project, "run"))
        assertEquals(1, constructs(project, "run").subList(1, 4).toSet().size)
    }
}
