package dev.insight.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.codereview.CodeFile
import dev.insight.codereview.CodeRange
import dev.insight.codereview.CodeScope
import dev.insight.codereview.CodeSnapshot
import dev.insight.codereview.codeReadResult
import dev.insight.codereview.codeSearchResult
import dev.insight.diff.ReviewFile
import dev.insight.diff.diffReadResult
import dev.insight.llm.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FileTraceTest {
    private val ok: suspend (CompletionRequest) -> Completion = { Completion("Answer") }
    private fun request(text: String) = CompletionRequest(listOf(LlmMessage.tool("read", text)))
    private fun evidence(trace: FileTrace, index: Int = trace.snapshot().requests.lastIndex) = trace.snapshot().requests[index].evidence

    @Test fun `real numbered reads deduplicate overlapping ranges and track clipped lines precisely`() = runBlocking {
        val file = CodeFile("a.kt", "/a.kt", "one\ntwo\n" + "x".repeat(3000) + "\nlast\n")
        val snapshot = CodeSnapshot(CodeScope("", ""), listOf(file))
        val trace = FileTrace("Explain")
        trace.register(listOf(TraceFile(file.path, source = file.text)))
        val result = codeReadResult(snapshot, listOf(CodeRange(file.path, 1, 3), CodeRange(file.path, 2, 3)))
        trace.complete(request(result), ok)
        val document = trace.snapshot().documents.getValue(TraceKey(file.path, "Source"))
        val ranges = evidence(trace).getValue(document.key)
        assertEquals(2006, ranges.sumOf { it.count() })
        assertEquals("1–2; partial: 3", traceLines(document, ranges))
        assertTrue(traceCoverage(document, ranges), traceCoverage(document, ranges).startsWith("66.6%"))
        assertFalse(ranges.any { document.text.indexOf("last") in it })
    }

    @Test fun `real search results count only matching characters after whitespace trimming`() = runBlocking {
        val file = CodeFile("a.kt", "/a.kt", "  needle  \nother\n")
        val snapshot = CodeSnapshot(CodeScope("", ""), listOf(file))
        val trace = FileTrace("Find")
        trace.register(listOf(TraceFile(file.path, source = file.text)))
        trace.complete(request(codeSearchResult(snapshot, "needle", false, "", null, 0)), ok)
        assertEquals(listOf(2..7), evidence(trace).getValue(TraceKey(file.path, "Source")))
    }

    @Test fun `diff pagination maps only completely exposed lines to each source side`() = runBlocking {
        val patch = "@@ -2,2 +2,2 @@\n-old\n+new\n same\n"
        val file = ReviewFile("a.kt", "/a.kt", null, "modified", patch, "v1", "unseen\nold\nsame\ntail\n", "unseen\nnew\nsame\ntail\n")
        val trace = FileTrace("Review")
        trace.register(listOf(TraceFile(file.path, patch = patch, before = file.before, after = file.after)))
        trace.complete(request(diffReadResult(listOf(file), 0)), ok)
        val docs = trace.snapshot().documents
        assertEquals("2–3", traceLines(docs.getValue(TraceKey(file.path, "Before")), evidence(trace).getValue(TraceKey(file.path, "Before"))))
        assertEquals("2–3", traceLines(docs.getValue(TraceKey(file.path, "After")), evidence(trace).getValue(TraceKey(file.path, "After"))))
        assertTrue(traceCoverage(docs.getValue(TraceKey(file.path, "Diff")), evidence(trace).getValue(TraceKey(file.path, "Diff"))).startsWith("100.0%"))
        assertFalse(traceCoverage(docs.getValue(TraceKey(file.path, "After")), evidence(trace).getValue(TraceKey(file.path, "After"))).startsWith("100.0%"))
        val offset = patch.indexOf("+new") + 2
        trace.complete(request(diffReadResult(listOf(file), offset)), ok)
        assertEquals("3", traceLines(docs.getValue(TraceKey(file.path, "After")), evidence(trace).getValue(TraceKey(file.path, "After"))))
    }

    @Test fun `inline source evidence is recorded but summaries metadata and invented excerpts are not`() = runBlocking {
        val trace = FileTrace("Explain")
        trace.register(listOf(TraceFile("a.kt", source = "first\nsecond\nthird")))
        trace.complete(CompletionRequest(listOf(
            LlmMessage.assistant("""{"path":"a.kt","code":"first"}"""),
            LlmMessage.user("""{"nodes":[{"path":"a.kt","start_line":2,"code":"second"},{"path":"a.kt","summary":"third"},{"path":"a.kt","code":"invented"}]}""" + "\n[Context omitted]"),
        )), ok)
        assertEquals(listOf(6..11), evidence(trace).getValue(TraceKey("a.kt", "Source")))
    }

    @Test fun `bounded tool output and context eviction are measured at the completion boundary`() = runBlocking {
        val a = CodeFile("a.kt", "/a.kt", "a".repeat(2400))
        val b = CodeFile("b.kt", "/b.kt", "b".repeat(2400))
        val files = CodeSnapshot(CodeScope("", ""), listOf(a, b))
        val trace = FileTrace("Explain")
        withContext(FileTraceContext(trace)) {
            var count = 0
            val complete = tracedFileCompletion(files.files.map { TraceFile(it.path, source = it.text) }) {
                if (++count < 3) Completion("", toolCalls = listOf(LlmToolCall("call$count", "read_code", JsonObject().apply { addProperty("which", count) })))
                else Completion("Answer")
            }
            val tool = object : ChatTool {
                override val definition = LlmTool("read_code", "Read", JsonObject())
                override suspend fun call(arguments: JsonObject) = codeReadResult(files, listOf(CodeRange(if (arguments["which"].asInt == 1) a.path else b.path)))
            }
            ToolChatRunner(complete, listOf(tool), maxContextChars = 1800).run(listOf(LlmMessage.user("Explain")))
        }
        assertEquals(3, trace.snapshot().requests.size)
        assertEquals(setOf(TraceKey("a.kt", "Source")), evidence(trace, 1).keys)
        assertEquals(setOf(TraceKey("b.kt", "Source")), evidence(trace, 2).keys)
        val clipped = FileTrace("Truncated")
        clipped.register(listOf(TraceFile(a.path, source = a.text)))
        clipped.complete(request(codeReadResult(files, listOf(CodeRange(a.path))).take(100) + "\n[Truncated]"), ok)
        assertTrue(evidence(clipped).isEmpty())
        assertEquals(1, clipped.snapshot().requests.single().unparsed)
    }

    @Test fun `rejected and cancelled requests remain separate from accepted evidence and failed reads stay visible`() = runBlocking {
        val trace = FileTrace("Explain")
        trace.register(listOf(TraceFile("a.kt", source = "source")))
        val request = request("""{"ranges":[{"path":"a.kt","text":"1| source"}]}""")
        try { trace.complete(request) { throw LlmApiException(400, "context_length_exceeded", null) }; fail() } catch (_: LlmApiException) { }
        try { trace.complete(request) { throw CancellationException() }; fail() } catch (_: CancellationException) { }
        trace.complete(request, ok)
        trace.tool(ToolCallEvent("read_code", JsonParser.parseString("""{"ranges":[{"path":"missing.kt"}]}""").asJsonObject, "Error: missing", true))
        trace.tool(ToolCallEvent("read_code", JsonParser.parseString("""{"ranges":false}""").asJsonObject, "Error: invalid", true))
        assertEquals(listOf("Failed", "Cancelled", "Accepted"), trace.snapshot().requests.map { it.state })
        assertEquals(setOf("missing.kt"), trace.snapshot().reads.keys)
        assertTrue(trace.snapshot().reads.getValue("missing.kt").single().contains("Error: missing"))
    }

    @Test fun `concurrent requests have independent stable request numbers and contexts`() = runBlocking {
        val trace = FileTrace("Topics")
        withContext(FileTraceContext(trace)) {
            val complete = tracedFileCompletion(listOf(TraceFile("a", source = "aaa"), TraceFile("b", source = "bbb"))) { delay(5); Completion("Topic") }
            listOf("a" to "aaa", "b" to "bbb").map { (path, code) -> async(Dispatchers.Default) {
                complete(CompletionRequest(listOf(LlmMessage.user("""{"path":"$path","excerpt":"$code"}"""))))
            } }.awaitAll()
        }
        assertEquals(listOf(1, 2), trace.snapshot().requests.map { it.number })
        assertTrue(trace.snapshot().requests.all { it.state == "Accepted" && it.evidence.size == 1 })
        assertEquals(setOf("a", "b"), trace.snapshot().requests.flatMap { it.evidence.keys }.map { it.path }.toSet())
    }

    @Test fun `a read cancelled before returning is still recorded without counting source`() = runBlocking {
        val trace = FileTrace("Explain")
        val started = CompletableDeferred<Unit>()
        withContext(FileTraceContext(trace)) {
            val complete = tracedFileCompletion(listOf(TraceFile("a.kt", source = "secret"))) {
                Completion("", toolCalls = listOf(LlmToolCall("read", "read_code",
                    JsonParser.parseString("""{"ranges":[{"path":"a.kt"}]}""").asJsonObject)))
            }
            val tool = object : ChatTool {
                override val definition = LlmTool("read_code", "Read", JsonObject())
                override suspend fun call(arguments: JsonObject): String { started.complete(Unit); awaitCancellation() }
            }
            val job = launch { ToolChatRunner(complete, listOf(tool)).run(listOf(LlmMessage.user("Explain"))) }
            started.await()
            job.cancelAndJoin()
        }
        trace.finish()
        assertTrue(trace.snapshot().reads.getValue("a.kt").single().contains("no result before the turn ended"))
        assertTrue(trace.snapshot().requests.single().evidence.isEmpty())
    }

    @Test fun `almost complete content never rounds up to a claim of full coverage`() {
        val document = TraceDocument(TraceKey("a.kt", "Source"), "a".repeat(10_000))
        assertEquals(">99.9% (9999/10000)", traceCoverage(document, listOf(0..9998)))
    }
}
