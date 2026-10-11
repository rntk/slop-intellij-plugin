package dev.insight.enrichment

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.SymbolKind

/** Runs the extractor over this plugin's own call graph sources and prints one sample per kind. */
class SymbolFactsSelfTest : LightJavaCodeInsightFixtureTestCase() {
    override fun getTestDataPath() = System.getProperty("user.dir") + "/src/main/kotlin"

    fun `test extracts facts for the plugin's own sources`() {
        myFixture.copyDirectoryToProject("dev/insight/callgraph", "dev/insight/callgraph")
        val result = ProjectGraphAnalyzer.build(project)
        val facts = SymbolFactsExtractor.extract(project, result)
        val byKind = facts.values.groupBy { it.kind }
        println("SELF-TEST counts: " + byKind.mapValues { it.value.size } + " of ${result.graph.nodes.size} nodes")

        assertTrue(byKind.getValue(SymbolKind.FILE).size >= 10)
        assertTrue(byKind.getValue(SymbolKind.CLASS).size >= 20)
        assertTrue(byKind.getValue(SymbolKind.FUNCTION).size >= 100)
        assertTrue(byKind.getValue(SymbolKind.PROPERTY).size >= 20)
        assertEquals(facts.size, facts.values.map { it.key }.toSet().size)
        val limits = FactsBudget()
        for (fact in facts.values) {
            assertTrue(fact.key, fact.path != null && !fact.path.startsWith("/") && !fact.path.contains('\\'))
            assertTrue(fact.key, fact.signature.length <= limits.signatureChars && '\n' !in fact.signature)
            assertTrue(fact.key, fact.code.length <= limits.codeChars)
            assertTrue(fact.key, fact.doc == null || fact.doc.length <= limits.docChars)
            assertTrue(fact.key, fact.signature.isNotBlank())
            assertTrue(fact.key, fact.imports.size <= 40)
            if (fact.kind == SymbolKind.FUNCTION || fact.kind == SymbolKind.PROPERTY) assertTrue(fact.key, fact.code.isNotBlank())
        }
        val functions = byKind.getValue(SymbolKind.FUNCTION)
        println("SELF-TEST functions with a brace in signature: " + functions.filter { '{' in it.signature }.map { it.signature })

        val samples = listOf(
            byKind.getValue(SymbolKind.FILE).first { it.name == "ProjectGraphAnalyzer.kt" },
            byKind.getValue(SymbolKind.CLASS).first { it.name.startsWith("ProjectGraphAnalyzer") },
            functions.first { it.name.startsWith("build") && it.container.contains("ProjectGraphAnalyzer") },
            byKind.getValue(SymbolKind.PROPERTY).first { it.name.startsWith("supports") },
            byKind.getValue(SymbolKind.CLASS).first { it.annotations.isNotEmpty() },
            functions.first { it.doc != null },
        )
        samples.forEach { f ->
            println("SELF-TEST SAMPLE ${f.kind} key=${f.key}\n  path=${f.path} lang=${f.language} annotations=${f.annotations} imports=${f.imports.size}\n  signature=${f.signature}\n  doc=${f.doc}\n  code:\n${f.code.prependIndent("    ")}")
        }
    }
}
