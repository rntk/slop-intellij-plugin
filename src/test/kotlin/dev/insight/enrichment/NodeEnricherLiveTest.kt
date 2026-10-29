package dev.insight.enrichment

import dev.insight.decisions.DEFAULT_DECISIONS_URL
import dev.insight.decisions.DecisionsClient
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.SymbolKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Smoke test against a real Jev server; runs only with `INSIGHT_LIVE=1`. */
class NodeEnricherLiveTest {
    private val endpoint = System.getenv("INSIGHT_ENDPOINT") ?: DEFAULT_DECISIONS_URL

    private fun facts(id: Int, name: String, container: String, path: String, signature: String, code: String, doc: String? = null) =
        SymbolFacts(id, "$path:$container.$name", SymbolKind.FUNCTION, name, container, path, "kotlin", signature, code, doc,
            contentHash = NodeEnricher.sha256(signature + code + doc))

    @Test fun `classifies handcrafted symbols plausibly`() = runBlocking {
        assumeTrue(System.getenv("INSIGHT_LIVE") == "1")
        val symbols = listOf(
            facts(0, "loadConfig", "ConfigLoader", "src/main/kotlin/app/config/ConfigLoader.kt",
                "fun loadConfig(path: Path): AppConfig",
                """
                fun loadConfig(path: Path): AppConfig {
                    val props = Properties()
                    Files.newInputStream(path).use { props.load(it) }
                    val port = System.getenv("APP_PORT")?.toInt() ?: props.getProperty("port", "8080").toInt()
                    return AppConfig(port = port, dataDir = props.getProperty("dataDir") ?: error("dataDir is required"))
                }
                """.trimIndent(), "Reads application settings from a properties file, letting environment variables override them."),
            facts(1, "paintComponent", "GraphCanvas", "src/main/kotlin/app/ui/GraphCanvas.kt",
                "override fun paintComponent(g: Graphics)",
                """
                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    for (node in layout.nodes) {
                        g2.color = if (node == selected) JBColor.BLUE else JBColor.GRAY
                        g2.fillRoundRect(node.x, node.y, node.width, node.height, 8, 8)
                        g2.drawString(node.label, node.x + 4, node.y + 14)
                    }
                }
                """.trimIndent()),
            facts(2, "parseObject", "JsonParser", "src/main/kotlin/app/json/JsonParser.kt",
                "private fun parseObject(): Map<String, Any?>",
                """
                private fun parseObject(): Map<String, Any?> {
                    expect('{')
                    val result = LinkedHashMap<String, Any?>()
                    skipWhitespace()
                    if (peek() == '}') { pos++; return result }
                    while (true) {
                        val key = parseString()
                        skipWhitespace(); expect(':')
                        result[key] = parseValue()
                        skipWhitespace()
                        when (next()) { ',' -> continue; '}' -> return result; else -> throw JsonException("Expected , or } at ${'$'}pos") }
                    }
                }
                """.trimIndent()),
        )
        val nodes = symbols.map { CallGraphNode(it.id, it.name, it.container, it.path!!, 0, true, filePath = it.path) } +
            CallGraphNode(3, "main", "App", "src/main/kotlin/app/App.kt", 0, true)
        val graph = CallGraph(nodes, setOf(CallGraphEdge(3, 0)), false, projectGraph = true)
        val client = DecisionsClient("local", endpoint)
        val result = NodeEnricher(client::evaluate, concurrency = 3)
            .enrich(EnrichmentInput("graph-viewer", graph, symbols.associateBy { it.id }), symbols.map { it.id }, emptyList())

        println("requests=${result.requests} failures=${result.failures} inputTokens=${result.inputTokens}")
        symbols.forEach { symbol ->
            val a = result.annotations[symbol.id]
            println("${symbol.container}.${symbol.name}:")
            println("  concerns: " + a?.concerns?.entries?.sortedByDescending { it.value }?.joinToString { "%s=%.2f".format(it.key, it.value) })
            println("  phase=${a?.phase?.choice} layer=${a?.layer?.choice} importance=${a?.importance?.score?.let { "%.2f".format(it) }} " +
                "entry=${a?.entryPoint?.let { "%.2f".format(it) }} utility=${a?.utility?.let { "%.2f".format(it) }}")
        }
        assertEquals(0, result.failures)
        assertEquals(3, result.annotations.size)
        val config = result.annotations.getValue(0)
        val paint = result.annotations.getValue(1)
        val json = result.annotations.getValue(2)
        assertTrue(config.concerns.getValue("config") > 0.5)
        assertTrue(paint.concerns.getValue("ui") > 0.5)
        assertTrue(json.concerns.getValue("serialization") > 0.5)
        assertTrue(config.concerns.getValue("config") > paint.concerns.getValue("config"))
    }
}
