package dev.insight.enrichment

import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.llm.Completion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CanvasPipelineTest {
    private val graph = CallGraph((0..3).map { CallGraphNode(it, "f$it", "", "", it, it != 3) },
        linkedSetOf(CallGraphEdge(0, 2), CallGraphEdge(0, 1), CallGraphEdge(2, 0), CallGraphEdge(1, 3)), false)
    private val sources = (0..2).associateWith { CanvasSource(it, "fun f$it() {\n    call()\n}", "/project/f.kt", "f.kt", "f$it", 10 + it * 5, "f.kt::f$it") }

    @Test fun `full physical source maps to original lines and traverses cycles once`() {
        val document = CanvasDocumentBuilder.build(graph, sources, listOf(0, 1, 2))
        assertEquals(listOf(0, 2, 1), document.lines.filter { it.header }.map { it.nodeId })
        assertEquals(listOf(10, 11, 12), document.lines.filter { !it.header && it.nodeId == 0 }.map { it.fileLine })
        assertEquals("    call()", document.lines[2].text)
        assertEquals(12, document.lines.size)
        assertTrue(document.lines.first().text.contains("calls: f2, f1"))
        assertEquals(3, CanvasDocumentBuilder.fallbackTopics(document).size)
        assertEquals(4, CanvasDocumentBuilder.build(graph, sources, listOf(1)).lines.size)
    }

    @Test fun `range repair clamps reversals trims overlaps fills gaps and normalizes labels`() {
        val topics = CanvasTopicParser.parse("Graph>Build nodes: 3-1\nGraph>Resolve edges: 2-4, 8-999\n graph > build nodes : 6", 10)
        val coverage = topics.flatMap { it.ranges.flatMap { range -> (range.start..range.end).toList() } }
        assertEquals((0..9).toList(), coverage.sorted())
        assertEquals(10, coverage.toSet().size)
        assertEquals(2, topics.size)
        assertEquals(listOf(CanvasRange(0, 3), CanvasRange(6, 7)), topics[0].ranges)
    }

    @Test fun `invalid responses fail without inventing topics`() {
        assertThrows(IllegalArgumentException::class.java) { CanvasTopicParser.parse("No ranges", 10) }
    }

    @Test fun `chunking retains every character and global markers for oversized lines`() {
        val body = "abc".repeat(100)
        val chunks = CanvasPipeline.taggedChunks(listOf("start", body, "end"), 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertTrue(chunks.last().contains("{2} end"))
        assertEquals(body, chunks.filter { it.startsWith("{1}") }.joinToString("") { it.substringAfter("} ") })
    }

    @Test fun `grouping and summaries cache by exact source and retain navigation`() = runBlocking {
        val document = CanvasDocumentBuilder.build(graph, sources, listOf(1))
        var requests = 0
        val pipeline = CanvasPipeline({ request ->
            requests++
            Completion(if (request.messages.first().content.contains("Partition"))
                """{"ranges":"Execution>Call worker: 0-3"}""" else """{"summary":"Invokes call()."}""")
        }, InMemoryEnrichmentCache())
        val data = pipeline.generate(document)
        assertEquals("Invokes call().", data.topics.single().summary)
        assertTrue(data.topics.single().inferred)
        assertEquals(document, data.document)
        assertEquals(0, data.failures)
        pipeline.generate(document)
        assertEquals(2, requests)
        pipeline.generate(document.copy(lines = document.lines.map { it.copy(text = it.text + " ") }))
        assertEquals(4, requests)
    }

    @Test fun `offline keeps structural topics while cancellation propagates`() = runBlocking {
        val document = CanvasDocumentBuilder.build(graph, sources)
        val data = CanvasPipeline({ throw java.io.IOException("offline") }, InMemoryEnrichmentCache()).generate(document)
        assertEquals(CanvasDocumentBuilder.fallbackTopics(document), data.topics)
        assertEquals(document, data.document)
        assertEquals(1, data.failures)
        try {
            CanvasPipeline({ throw CancellationException("stop") }, InMemoryEnrichmentCache()).generate(document)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("", CanvasPipeline.parseSummary("```text\nNO_SUMMARY.\n```"))
    }

    @Test fun `malformed model output is not cached and generation can recover`() = runBlocking {
        val document = CanvasDocumentBuilder.build(graph, sources, listOf(1))
        var broken = true
        var requests = 0
        val pipeline = CanvasPipeline({ request ->
            requests++
            Completion(if (broken) """{"ranges":"No ranges"}"""
                else if (request.messages.first().content.contains("Partition")) """{"ranges":"Execution>Call worker: 0-3"}"""
                else """{"summary":"Invokes call()."}""")
        }, InMemoryEnrichmentCache())
        assertFalse(pipeline.generate(document).topics.single().inferred)
        broken = false
        assertEquals("Invokes call().", pipeline.generate(document).topics.single().summary)
        assertEquals(3, requests)
    }
}
