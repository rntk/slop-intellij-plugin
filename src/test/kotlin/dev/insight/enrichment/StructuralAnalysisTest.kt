package dev.insight.enrichment

import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class StructuralAnalysisTest {
    @Test fun `betweenness, fan-in, fan-out and roots on a known graph`() {
        val nodes = (0..9).map { EnricherFakes.node(it, "n$it", inProject = it != 9) }
        val edges = listOf(0 to 1, 1 to 2, 2 to 3, 4 to 1, 5 to 6, 5 to 7, 6 to 8, 7 to 8, 3 to 9, 1 to 1)
            .map { (a, b) -> CallGraphEdge(a, b) }.toSet() + CallGraphEdge(3, 0, RelationKind.REFERENCE)
        val metrics = StructuralAnalysis.metrics(CallGraph(nodes, edges, false))
        assertEquals((0..8).toSet(), metrics.keys)
        // Shortest paths through 1: 0→2, 0→3, 4→2, 4→3; through 2: 0→3, 1→3, 4→3; through 6 and 7: half of 5→8 each.
        val expected = mapOf(0 to 0.0, 1 to 1.0, 2 to 0.75, 3 to 0.0, 4 to 0.0, 5 to 0.0, 6 to 0.125, 7 to 0.125, 8 to 0.0)
        expected.forEach { (id, value) -> assertEquals("node $id", value, metrics.getValue(id).betweenness, 1e-9) }
        assertEquals(StructuralMetrics(fanIn = 2, fanOut = 1, betweenness = 1.0, root = false), metrics[1])
        assertEquals("external callee and self call excluded", 0, metrics.getValue(3).fanOut)
        assertEquals(setOf(0, 4, 5), metrics.filterValues { it.root }.keys)
    }

    @Test fun `large graphs are sampled deterministically and fast`() {
        val random = Random(7)
        val nodes = (0 until 10_000).map { EnricherFakes.node(it, "n$it") }
        val edges = (0 until 30_000).map { CallGraphEdge(random.nextInt(10_000), random.nextInt(10_000)) }.toSet()
        val graph = CallGraph(nodes, edges, false)
        val start = System.nanoTime()
        val first = StructuralAnalysis.metrics(graph)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $millis ms", millis < 10_000)
        assertEquals(10_000, first.size)
        assertTrue(first.values.all { it.betweenness in 0.0..1.0 })
        assertEquals(1.0, first.values.maxOf { it.betweenness }, 1e-9)
        assertEquals(first, StructuralAnalysis.metrics(graph))
    }

    private fun facts(
        name: String = "run",
        path: String? = "src/main/kotlin/App.kt",
        kind: SymbolKind = SymbolKind.FUNCTION,
        signature: String = "fun $name()",
        code: String = "",
        annotations: List<String> = emptyList(),
        imports: List<String> = emptyList(),
    ) = SymbolFacts(0, "k", kind, name, "App", path, "kotlin", signature, code, null, annotations, imports, "h")

    @Test fun `test tags`() {
        assertEquals(emptySet<String>(), HeuristicRules.tags(facts()))
        listOf(
            facts(path = "src/test/kotlin/AppTest.kt"), facts(path = "pkg/app_test.go"), facts(path = "tests/conftest.py"),
            facts(path = "test_app.py"), facts(path = "web/app.spec.ts"), facts(path = "lib/AppTests.java"),
            facts(name = "testLoad"), facts(name = "test_load"), facts(annotations = listOf("Test")),
            facts(annotations = listOf("org.junit.jupiter.params.ParameterizedTest")), facts(annotations = listOf("pytest.fixture")),
            facts(annotations = listOf("pytest.mark.parametrize")),
        ).forEach { assertTrue(it.toString(), "test" in HeuristicRules.tags(it)) }
        listOf(facts(name = "testimony"), facts(path = "src/contest/Main.kt"), facts(path = "src/Testing.kt"))
            .forEach { assertFalse(it.toString(), "test" in HeuristicRules.tags(it)) }
    }

    @Test fun `entry point tags`() {
        assertEquals(setOf("entry:main"), HeuristicRules.tags(facts(name = "main")))
        assertFalse("entry:main" in HeuristicRules.tags(facts(name = "main", kind = SymbolKind.CLASS)))
        listOf(
            facts(annotations = listOf("GetMapping")), facts(annotations = listOf("org.springframework.web.bind.annotation.PostMapping")),
            facts(annotations = listOf("app.route")), facts(annotations = listOf("router.get")),
            facts(signature = "func handle(w http.ResponseWriter, r *http.Request)"),
        ).forEach { assertTrue(it.toString(), "entry:route" in HeuristicRules.tags(it)) }
        assertFalse("entry:route" in HeuristicRules.tags(facts(annotations = listOf("get", "Override"))))
        listOf(
            facts(name = "actionPerformed", signature = "override fun actionPerformed(e: AnActionEvent)"),
            facts(name = "mouseClicked", signature = "public void mouseClicked(MouseEvent e)", annotations = listOf("Override")),
            facts(annotations = listOf("EventListener")),
            facts(name = "OpenAction", kind = SymbolKind.CLASS, signature = "class OpenAction(private val x: Int) : AnAction()"),
            facts(name = "Clicker", kind = SymbolKind.CLASS, signature = "public class Clicker extends MouseAdapter"),
        ).forEach { assertTrue(it.toString(), "entry:listener" in HeuristicRules.tags(it)) }
        assertFalse("entry:listener" in HeuristicRules.tags(facts(name = "actionPerformed")))
        assertFalse("entry:listener" in HeuristicRules.tags(facts(name = "Plain", kind = SymbolKind.CLASS, signature = "class Plain : Runnable")))
    }

    @Test fun `import concerns need a mention in the symbol's own code`() {
        val imports = listOf("java.sql.Connection", "okhttp3.OkHttpClient", "com.google.gson.Gson", "org.slf4j.LoggerFactory",
            "kotlinx.coroutines.launch", "java.util.concurrent.*", "import requests", "from json import loads", "net/http")
        assertEquals(emptySet<String>(), HeuristicRules.tags(facts(imports = imports, code = "fun run() = 1 + 1")))
        assertEquals(setOf("concern:storage", "concern:network"),
            HeuristicRules.tags(facts(imports = imports, signature = "fun run(c: Connection)", code = "OkHttpClient().newCall(x)")))
        assertEquals(setOf("concern:serialization", "concern:logging", "concern:concurrency"),
            HeuristicRules.tags(facts(imports = imports, code = "launch { LoggerFactory.getLogger(x); Gson() }")))
        assertEquals(setOf("concern:network", "concern:serialization"),
            HeuristicRules.tags(facts(imports = imports, code = "loads(requests.get(u).text)")))
        assertEquals(setOf("concern:network"), HeuristicRules.tags(facts(imports = imports, code = "http.Get(u)")))
        assertFalse("partial word does not count", "concern:storage" in HeuristicRules.tags(facts(imports = imports, code = "ConnectionPool()")))
    }

    @Test fun `python imports as stored by the extractor`() {
        val imports = SymbolFactsExtractor.imports("from json import loads\nimport requests\nfrom threading import (Lock,\n    Thread as T)\n")
        assertEquals(listOf("json import loads", "requests", "threading import (Lock, Thread as T)"), imports)
        assertEquals(setOf("concern:serialization"), HeuristicRules.tags(facts(imports = imports, code = "return loads(text)")))
        assertEquals(setOf("concern:network"), HeuristicRules.tags(facts(imports = imports, code = "requests.get(u)")))
        assertEquals(setOf("concern:concurrency"), HeuristicRules.tags(facts(imports = imports, code = "with Lock(): pass")))
    }

    @Test fun `function names with parameter lists`() {
        assertEquals(setOf("entry:main"), HeuristicRules.tags(facts(name = "main(String[])")))
        assertTrue("entry:listener" in HeuristicRules.tags(
            facts(name = "actionPerformed(AnActionEvent)", signature = "override fun actionPerformed(e: AnActionEvent)")))
        assertTrue("test" in HeuristicRules.tags(facts(name = "testParse()")))
    }

    @Test fun `visibility is bounded, neutral without data and monotonic`() {
        assertEquals(0.5, visibility(null, null, null), 1e-9)
        fun annotation(importance: Double = 2.0, utility: Double? = null, test: Double? = null) =
            NodeAnnotation(importance = ScoreValue(importance, 4, 0.9), utility = utility, concerns = listOfNotNull(test?.let { "test" to it }).toMap())
        fun metrics(fanIn: Int = 2, betweenness: Double = 0.1) = StructuralMetrics(fanIn, 1, betweenness, fanIn == 0)
        val base = visibility(annotation(), metrics(), null)
        assertTrue(visibility(annotation(importance = 4.0), metrics(), null) > base)
        assertTrue(visibility(annotation(), metrics(fanIn = 10), null) > base)
        assertTrue(visibility(annotation(), metrics(betweenness = 0.9), null) > base)
        assertTrue(visibility(annotation(utility = 0.9), metrics(), null) < visibility(annotation(utility = 0.1), metrics(), null))
        assertTrue(visibility(annotation(test = 0.9), metrics(), null) < base)
        assertTrue(visibility(annotation(), metrics(), Heuristics(setOf("test"))) < visibility(annotation(test = 0.5), metrics(), null))
        assertEquals(1.0, visibility(annotation(importance = 4.0), metrics(fanIn = 500, betweenness = 1.0), null), 1e-9)
        assertEquals(0.0, visibility(annotation(importance = 0.0), metrics(fanIn = 0, betweenness = 0.0), null), 1e-9)
        val random = Random(1)
        repeat(200) {
            val value = visibility(annotation(random.nextDouble() * 4, random.nextDouble(), random.nextDouble()),
                metrics(random.nextInt(1000), random.nextDouble()), Heuristics(if (random.nextBoolean()) setOf("test") else emptySet()),
                VisibilityWeights(random.nextDouble(), random.nextDouble(), random.nextDouble(), random.nextDouble(), random.nextDouble()))
            assertTrue(value in 0.0..1.0)
        }
    }
}
