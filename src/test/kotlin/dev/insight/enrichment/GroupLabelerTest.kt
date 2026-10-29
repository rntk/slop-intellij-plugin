package dev.insight.enrichment

import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GroupLabelerTest {
    private val prompts = CopyOnWriteArrayList<String>()

    /** Labels every numbered item `[n] path` as "Label n" with summary "About <path>". */
    private val labeler: suspend (CompletionRequest) -> Completion = { request ->
        val user = request.messages.last().content
        prompts += user
        val items = Regex("""\[(\d+)] (\S+)""").findAll(user).map { it.groupValues[1] to it.groupValues[2] }.toList()
        Completion("```json\n{\"labels\":[" + items.joinToString(",") { (id, path) ->
            "{\"id\":$id,\"label\":\"Label of $path\",\"summary\":\"About $path.\"}"
        } + "]}\n```")
    }

    /** Files under `app/src`: two in `x`, one in `y/z` (so `y` is merged away), each with a class and a method. */
    private fun input(): EnrichmentInput {
        val paths = listOf("app/src/x/A.kt", "app/src/x/B.kt", "app/src/y/z/C.kt")
        val nodes = mutableListOf<CallGraphNode>()
        val edges = mutableSetOf<CallGraphEdge>()
        val facts = mutableMapOf<Int, SymbolFacts>()
        fun add(kind: SymbolKind, name: String, path: String, signature: String, parent: Int?): Int {
            val id = nodes.size
            nodes += CallGraphNode(id, name, "", "", 0, true, kind, "/repo/$path")
            facts[id] = SymbolFacts(id, "$path|$name", kind, name, "", path, "kotlin", signature, "", contentHash = "h$id")
            parent?.let { edges += CallGraphEdge(it, id, RelationKind.CONTAINS) }
            return id
        }
        for (path in paths) {
            val file = add(SymbolKind.FILE, path.substringAfterLast('/'), path, "", null)
            val type = path.substringAfterLast('/').removeSuffix(".kt")
            val cls = add(SymbolKind.CLASS, type, path, "class $type(val id: Int)", file)
            add(SymbolKind.FUNCTION, "run$type", path, "fun run$type(): Int", cls)
        }
        return EnrichmentInput("demo", CallGraph(nodes, edges, false, projectGraph = true), facts)
    }

    @Test fun `labels files from outlines, then directories from their children`() = runBlocking {
        val result = GroupLabeler(labeler, InMemoryEnrichmentCache()).label(input())
        assertEquals(setOf("app/src/x/A.kt", "app/src/x/B.kt", "app/src/y/z/C.kt", "app/src/x", "app/src/y/z"), result.labels.keys)
        assertEquals(GroupLabel("app/src/x/A.kt", "Label of app/src/x/A.kt", "About app/src/x/A.kt."), result.labels["app/src/x/A.kt"])
        assertEquals("Label of app/src/x/", result.labels.getValue("app/src/x").label)
        assertEquals(3, result.requests)
        assertEquals(0, result.failures)
        val filePrompt = prompts[0]
        assertTrue(filePrompt, "- class A(val id: Int) { runA }" in filePrompt)
        val directoryPrompt = prompts[2]
        assertTrue(directoryPrompt, "- A.kt: Label of app/src/x/A.kt — About app/src/x/A.kt." in directoryPrompt)
    }

    @Test fun `batches files and reuses cached batches`() = runBlocking {
        val cache = InMemoryEnrichmentCache()
        val first = GroupLabeler(labeler, cache, batchSize = 2).label(input())
        assertEquals(4, first.requests)
        val second = GroupLabeler(labeler, cache, batchSize = 2).label(input())
        assertEquals(0, second.requests)
        assertEquals(first.labels, second.labels)
    }

    @Test fun `a failing model leaves groups unlabeled without failing the run`() = runBlocking {
        val result = GroupLabeler({ throw IOException("down") }, InMemoryEnrichmentCache()).label(input())
        assertTrue(result.labels.isEmpty())
        assertEquals(3, result.failures)
        assertEquals(12, result.requests)
    }

    @Test fun `ignores unknown ids and blank labels`() = runBlocking {
        val result = GroupLabeler({ Completion("""{"labels":[{"id":7,"label":"X"},{"id":1,"label":" "},{"id":"2","label":"\"Ok.\""}]}""") },
            InMemoryEnrichmentCache()).label(input())
        assertEquals(mapOf("app/src/x/B.kt" to "Ok"), result.labels.mapValues { it.value.label })
    }
}
