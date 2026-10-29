package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.CallGraphNode
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.llm.LlamaCppClient
import dev.insight.llm.LLAMA_CPP_DEFAULT_BASE_URL
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Duration

/** Runs the generative steps against a live llama.cpp server; set `INSIGHT_LIVE=1` (and optionally `INSIGHT_LLM_URL`). */
class EnrichmentLlmLiveTest {
    private class Declaration(val kind: SymbolKind, val name: String, val signature: String, val members: List<String> = emptyList())

    private val root = "src/main/kotlin/dev/insight"
    private val files = linkedMapOf(
        "$root/llm/LlamaCppClient.kt" to listOf(
            Declaration(SymbolKind.CLASS, "LlmApiException", "class LlmApiException(val statusCode: Int, val responseBody: String, val retryAfter: String?) : IOException"),
            Declaration(SymbolKind.FUNCTION, "chatCompletionsUri", "fun chatCompletionsUri(baseUrl: String): URI"),
            Declaration(SymbolKind.CLASS, "LlamaCppClient", "class LlamaCppClient(baseUrl: String, model: String = \"\", apiKey: String? = null, cachePrompt: Boolean = true, requestTimeout: Duration, maxRetries: Int = 2)",
                listOf("complete", "send", "retryDelay")),
        ),
        "$root/llm/LlmJson.kt" to listOf(
            Declaration(SymbolKind.CLASS, "LlmProtocolException", "class LlmProtocolException(message: String, cause: Throwable? = null) : IOException"),
            Declaration(SymbolKind.CLASS, "LlmJson", "internal object LlmJson", listOf("encode", "message", "tool", "toolChoice", "decode", "toolCall", "arguments", "usage")),
        ),
        "$root/chat/ChatService.kt" to listOf(
            Declaration(SymbolKind.CLASS, "ChatService", "class ChatService(private val project: Project, private val scope: CoroutineScope)",
                listOf("send", "stop", "clear", "subscribe")),
        ),
        "$root/chat/ToolChat.kt" to listOf(
            Declaration(SymbolKind.CLASS, "ChatTool", "interface ChatTool", listOf("call")),
            Declaration(SymbolKind.CLASS, "ToolCallEvent", "data class ToolCallEvent(val name: String, val arguments: JsonObject, val result: String, val failed: Boolean)"),
            Declaration(SymbolKind.CLASS, "ToolChatRunner", "class ToolChatRunner(complete: suspend (CompletionRequest) -> Completion, tools: List<ChatTool>, maxRounds: Int)", listOf("run")),
            Declaration(SymbolKind.FUNCTION, "compactHistory", "fun compactHistory(history: List<ChatMessage>, maxChars: Int = 24_000, maxMessages: Int = 20): List<LlmMessage>"),
        ),
        "$root/chat/ProjectTools.kt" to listOf(
            Declaration(SymbolKind.FUNCTION, "projectTools", "fun projectTools(project: Project): List<ChatTool>"),
            Declaration(SymbolKind.CLASS, "ProjectFiles", "internal class ProjectFiles(private val project: Project)", listOf("display", "resolve", "contentFiles", "text")),
            Declaration(SymbolKind.CLASS, "ListFilesTool", "private class ListFilesTool(private val files: ProjectFiles) : ProjectTool", listOf("call")),
            Declaration(SymbolKind.CLASS, "ReadFileTool", "private class ReadFileTool(private val files: ProjectFiles) : ProjectTool", listOf("call")),
            Declaration(SymbolKind.CLASS, "SearchTextTool", "private class SearchTextTool(private val files: ProjectFiles) : ProjectTool", listOf("call")),
        ),
        "$root/callgraph/CallGraphLayout.kt" to listOf(
            Declaration(SymbolKind.CLASS, "LayoutMetrics", "data class LayoutMetrics(val nodeWidth: Int, val nodeHeight: Int, val columnGap: Int, val rowGap: Int, val margin: Int)"),
            Declaration(SymbolKind.CLASS, "GraphLayout", "data class GraphLayout(val boxes: Map<Int, Rectangle>, val size: Dimension)"),
            Declaration(SymbolKind.FUNCTION, "layoutCallGraph", "fun layoutCallGraph(graph: CallGraph, metrics: LayoutMetrics): GraphLayout"),
        ),
        "$root/callgraph/CallGraphService.kt" to listOf(
            Declaration(SymbolKind.CLASS, "CallGraphRequest", "data class CallGraphRequest(val direction: CallDirection, val maxDepth: Int, val maxNodes: Int)"),
            Declaration(SymbolKind.CLASS, "CallGraphService", "class CallGraphService(private val project: Project, val scope: CoroutineScope)", listOf("buildProject", "build")),
        ),
        "$root/dashboard/Charts.kt" to listOf(
            Declaration(SymbolKind.CLASS, "Chart", "abstract class Chart : JPanel()", listOf("draw")),
            Declaration(SymbolKind.CLASS, "ActivityChart", "class ActivityChart(values: List<Int>) : Chart()", listOf("draw")),
            Declaration(SymbolKind.CLASS, "CoverageChart", "class CoverageChart : Chart()", listOf("draw")),
            Declaration(SymbolKind.CLASS, "ModuleGraph", "class ModuleGraph : Chart()", listOf("draw")),
        ),
    )

    private fun input(): EnrichmentInput {
        val nodes = mutableListOf<CallGraphNode>()
        val edges = linkedSetOf<CallGraphEdge>()
        val facts = mutableMapOf<Int, SymbolFacts>()
        fun add(kind: SymbolKind, name: String, container: String, path: String, signature: String, parent: Int?): Int {
            val id = nodes.size
            nodes += CallGraphNode(id, name, container, "", 0, true, kind, "/app/$path")
            facts[id] = SymbolFacts(id, "$path|$kind|$container|$name", kind, name, container, path, "kotlin", signature, "", contentHash = "$id")
            parent?.let { edges += CallGraphEdge(it, id, RelationKind.CONTAINS) }
            return id
        }
        for ((path, declarations) in files) {
            val file = add(SymbolKind.FILE, path.substringAfterLast('/'), "", path, "", null)
            for (declaration in declarations) {
                val id = add(declaration.kind, declaration.name, "", path, declaration.signature, file)
                declaration.members.forEach { add(SymbolKind.FUNCTION, it, declaration.name, path, "fun $it(...)", id) }
            }
        }
        fun find(container: String, name: String) = facts.values.first { it.container == container && it.name == name }.id
        // ChatService.send → compactHistory, ToolChatRunner.run → LlamaCppClient.complete → LlmJson.encode/decode; run → ReadFileTool.call.
        listOf(
            find("ChatService", "send") to find("", "compactHistory"),
            find("ChatService", "send") to find("ToolChatRunner", "run"),
            find("ToolChatRunner", "run") to find("LlamaCppClient", "complete"),
            find("ToolChatRunner", "run") to find("ReadFileTool", "call"),
            find("LlamaCppClient", "complete") to find("LlmJson", "encode"),
            find("LlamaCppClient", "complete") to find("LlmJson", "decode"),
        ).forEach { (from, to) -> edges += CallGraphEdge(from, to) }
        return EnrichmentInput("insight (IntelliJ plugin)", CallGraph(nodes, edges, false, projectGraph = true), facts)
    }

    @Test fun `labels groups, discovers domains and names a flow`() = runBlocking {
        assumeTrue(System.getenv("INSIGHT_LIVE") == "1")
        val client = LlamaCppClient(System.getenv("INSIGHT_LLM_URL") ?: LLAMA_CPP_DEFAULT_BASE_URL, requestTimeout = Duration.ofSeconds(180))
        val cache = InMemoryEnrichmentCache()
        val input = input()

        var started = System.currentTimeMillis()
        val labels = GroupLabeler(client::complete, cache).label(input)
        println("Labels (${labels.requests} requests, ${labels.failures} failures, ${System.currentTimeMillis() - started} ms):")
        labels.labels.values.sortedBy { it.path }.forEach { println("  ${it.path}: ${it.label} — ${it.summary}") }

        started = System.currentTimeMillis()
        val domains = TaxonomyDiscovery(client::complete, cache).discover(input, labels.labels)
        println("Domains (${System.currentTimeMillis() - started} ms):")
        domains.forEach { println("  ${it.id}: ${it.name} — ${it.description}") }

        started = System.currentTimeMillis()
        val send = input.facts.values.first { it.container == "ChatService" && it.name == "send" }.id
        val flows = FlowTracer(client::complete, cache).trace(input, mapOf(send to NodeAnnotation(entryPoint = 0.9)), emptyMap(), emptyMap())
        println("Flows (${flows.llmRequests} requests, ${flows.failures} failures, ${System.currentTimeMillis() - started} ms):")
        flows.flows.forEach { flow ->
            println("  ${flow.id} ${flow.name} — ${flow.description}")
            println("    " + flow.nodes.joinToString(" → ") { input.facts.getValue(it).let { f -> "${f.container}.${f.name}" } })
        }
    }
}
