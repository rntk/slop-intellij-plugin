package dev.insight.entrypoints

import com.google.gson.JsonObject
import dev.insight.decisions.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DecisionsEntryPointFinderTest {
    private class Sources(val contents: Map<String, String>) : ProjectSnapshot {
        override suspend fun files() = contents.keys.sorted()
        override suspend fun identify(path: String) = contents[path]?.let { FileId(path, EntryPointFinder.digest(it)) }
    }

    /** [probability] receives the candidate object from state that the question refers to. */
    private fun response(request: DecisionRequest, kindConfidence: Double = 1.0, probability: (JsonObject) -> Double = { 0.9 }) =
        DecisionResponse("jev-test", request.questions.mapValues { (id, question) ->
            val candidate = candidates(request)[id.substringAfter('_').toInt()]
            when (question) {
                is DecisionQuestion.Noul -> DecisionAnswer.Noul(probability(candidate))
                is DecisionQuestion.Choice -> DecisionAnswer.Choice("APPLICATION", mapOf("APPLICATION" to 1.0), kindConfidence)
                else -> error("Unexpected question")
            }
        }, DecisionUsage(10, 5))

    private fun candidates(request: DecisionRequest) = request.state.asJsonObject["candidates"].asJsonArray.map { it.asJsonObject }
    private fun path(request: DecisionRequest) = request.state.asJsonObject["path"].asString

    private fun find(sources: Sources, hints: List<EntryPointHint> = emptyList(),
        evaluate: suspend (DecisionRequest) -> DecisionResponse = { response(it) }) =
        runBlocking { DecisionsEntryPointFinder(evaluate, sources, { sources.contents[it] }).find(hints) }

    @Test fun `judges source candidates independently and keeps real locations above threshold`() = runBlocking {
        val sources = Sources(mapOf("Main.kt" to "// launch\nfun main() {}\nfun helper() {}", "src/test/MainTest.kt" to "fun main() {}"))
        val requests = mutableListOf<DecisionRequest>()
        val result = DecisionsEntryPointFinder({ request ->
            requests += request
            response(request) { if (it["code"].asString.contains("fun helper")) 0.69 else 0.9 }
        }, sources, { sources.contents[it] }).find(emptyList())
        assertEquals(2, requests.size)
        val state = requests.first().state.asJsonObject
        assertEquals("Main.kt", state["path"].asString)
        assertTrue(state["source"].asString.contains("2: fun main()"))
        assertTrue(requests.all { it.questions.size == 2 })
        assertEquals(listOf(2, 3), requests.flatMap(::candidates).map { it["line"].asInt })
        // Candidate text is state; instructions only refer to it.
        assertFalse(requests.flatMap { it.questions.values }.any { it.instructions.asString.contains("fun ") })
        assertEquals(listOf("Main.kt"), result.filesRead)
        assertEquals(2, result.entryPoints.single().line)
        assertEquals("main", result.entryPoints.single().symbol)
        assertEquals(EntryPointKind.APPLICATION, result.entryPoints.single().kind)
        assertEquals(0.9, result.entryPoints.single().confidence, 0.0)
        assertFalse(result.cached)
    }

    @Test fun `uses graph hints and launch manifests without depending on an LLM`() = runBlocking {
        val sources = Sources(mapOf("Boot.kt" to "// framework\nval boot = Runnable { }", "package.json" to "{\"scripts\":{\"start\":\"node app.js\"}}"))
        val result = DecisionsEntryPointFinder({ response(it) }, sources, { sources.contents[it] }).find(
            listOf(EntryPointHint("Boot.kt", 2, "boot", "framework callback")),
        )
        assertEquals(listOf("Boot.kt", "package.json"), result.entryPoints.map { it.path })
        assertEquals("boot", result.entryPoints.first().symbol)
        assertEquals(2, result.requestCount)
    }

    @Test fun `reports bounded coverage and unreadable files and prioritizes graph hints`() = runBlocking {
        val sources = Sources(mapOf("A.kt" to "fun main() {}", "B.kt" to "fun main() {}", "C.kt" to "fun main() {}"))
        val read = mutableListOf<String>()
        val result = DecisionsEntryPointFinder({ response(it) }, sources, {
            read += it
            if (it == "A.kt") null else sources.contents[it]
        }, maxFiles = 2, concurrency = 1).find(listOf(EntryPointHint("C.kt", 1, "main", "start")))
        assertEquals(listOf("C.kt", "A.kt"), read)
        assertEquals(listOf("C.kt"), result.filesRead)
        assertTrue(result.summary.contains("1 files outside"))
        assertTrue(result.summary.contains("1 unreadable"))
    }

    @Test fun `empty project needs no requests and repeated runs reuse decisions`() = runBlocking {
        val empty = Sources(emptyMap())
        val result = DecisionsEntryPointFinder({ error("No candidates") }, empty, { null }).find(emptyList())
        assertEquals(0, result.requestCount)
        assertTrue(result.entryPoints.isEmpty())
        val sources = Sources(mapOf("Main.kt" to "fun main() {}"))
        var calls = 0
        val finder = DecisionsEntryPointFinder({ calls++; response(it) }, sources, { sources.contents[it] })
        val first = finder.find(emptyList())
        val second = finder.find(emptyList())
        assertEquals(1, calls)
        assertFalse(first.cached)
        assertTrue(second.cached)
        assertEquals(0, second.requestCount)
        assertEquals(first.entryPoints, second.entryPoints)
        assertEquals(listOf("Main.kt"), second.filesReused)
    }

    @Test fun `shared cache reuses unchanged files and invalidates source hints and manifests`() = runBlocking {
        val contents = linkedMapOf("Main.kt" to "fun main() {}", "Other.kt" to "fun other() {}",
            "package.json" to "{\"scripts\":{\"start\":\"node Main.kt\"}}")
        val cache = EntryPointDecisionCache()
        val calls = mutableListOf<String>()
        suspend fun run(hints: List<EntryPointHint> = emptyList()): EntryPointResult {
            val sources = Sources(contents)
            return DecisionsEntryPointFinder({ calls += path(it); response(it) }, sources,
                { contents[it] }, concurrency = 1, cache = cache).find(hints)
        }
        run()
        calls.clear()
        assertTrue(run().cached)
        assertTrue(calls.isEmpty())
        contents["Other.kt"] = "fun changed() {}"
        val partial = run()
        assertFalse(partial.cached)
        assertEquals(listOf("Other.kt"), calls)
        assertEquals(1, partial.requestCount)
        calls.clear()
        val hints = listOf(EntryPointHint("Main.kt", 1, "main", "framework callback"))
        run(hints)
        assertEquals(listOf("Main.kt"), calls)
        calls.clear()
        contents["package.json"] = "{\"scripts\":{\"start\":\"node Main.kt --server\"}}"
        run(hints)
        assertEquals(setOf("Main.kt", "package.json"), calls.toSet())
        calls.clear()
        cache.clear()
        run(hints)
        assertEquals(contents.keys, calls.toSet())
    }

    @Test fun `cached negative judgments are filtered with the current threshold`() = runBlocking {
        val sources = Sources(mapOf("Main.kt" to "fun main() {}"))
        val cache = EntryPointDecisionCache()
        var calls = 0
        suspend fun run(threshold: Double) = DecisionsEntryPointFinder({ calls++; response(it) { 0.6 } },
            sources, { sources.contents[it] }, threshold = threshold, cache = cache).find(emptyList())
        assertTrue(run(0.7).entryPoints.isEmpty())
        val second = run(0.5)
        assertTrue(second.cached)
        assertEquals(1, second.entryPoints.size)
        assertEquals(1, calls)
    }

    @Test fun `failed requests remain retryable with the same cache`() = runBlocking {
        val sources = Sources(mapOf("Main.kt" to "fun main() {}"))
        var calls = 0
        val finder = DecisionsEntryPointFinder({
            calls++
            if (calls == 1) throw DecisionApiException(500, "{}", null)
            response(it)
        }, sources, { sources.contents[it] })
        try {
            finder.find(emptyList())
            fail("Failure should propagate")
        } catch (_: EntryPointDecisionException) { }
        assertFalse(finder.find(emptyList()).cached)
        assertTrue(finder.find(emptyList()).cached)
        assertEquals(2, calls)
    }

    @Test fun `sends one candidate per request and includes threshold boundary with valid source locations`() = runBlocking {
        val sources = Sources(mapOf("Main.kt" to (1..23).joinToString("\n") { "fun entry$it() {}" }))
        val sizes = mutableListOf<Int>()
        val result = DecisionsEntryPointFinder({ request ->
            sizes += request.questions.size
            response(request) { 0.7 }
        }, sources, { sources.contents[it] }).find(listOf(EntryPointHint("Main.kt", 1000, "invalid", "stale hint")))
        assertEquals(23, sizes.size)
        assertTrue(sizes.all { it == 2 })
        assertEquals(46, sizes.sum())
        assertEquals(sizes.size, result.requestCount)
        assertEquals((1..23).toList(), result.entryPoints.map { it.line })
    }

    @Test fun `complete requests stay within budget for large Unicode source and graph hints`() = runBlocking {
        val longLine = "// " + "漢😀<&\\\"".repeat(300)
        val text = (1..23).joinToString("\n") { "fun entry$it() {}\n" + List(20) { longLine }.joinToString("\n") }
        val sources = Sources(mapOf("Main.kt" to text))
        val hints = (1..23).map { EntryPointHint("Main.kt", (it - 1) * 21 + 1, "Main.entry$it", "large <&漢😀 framework hint".repeat(300)) }
        val requests = mutableListOf<DecisionRequest>()
        val result = DecisionsEntryPointFinder({ request ->
            requests += request
            val body = DecisionJson.encode(request)
            assertTrue("Request has ${body.toByteArray(Charsets.UTF_8).size} bytes",
                body.toByteArray(Charsets.UTF_8).size <= 4000)
            assertEquals(1, candidates(request).size)
            assertEquals(2, request.questions.size)
            val source = request.state.asJsonObject["source"].asString
            candidates(request).forEach { candidate ->
                val line = candidate["line"].asInt
                assertTrue("Missing candidate $line", source.lineSequence().any { it.startsWith("$line: ") })
                assertTrue(candidate.has("graph_hint"))
            }
            assertFalse(source.contains('�'))
            response(request)
        }, sources, { sources.contents[it] }).find(hints)
        assertEquals(23, result.entryPoints.size)
        assertEquals(23, requests.size)
    }

    @Test fun `service failures and cancellation are propagated`() = runBlocking {
        val sources = Sources(mapOf("Main.kt" to "fun main() {}"))
        for (failure in listOf(IllegalStateException("API unavailable"), CancellationException("Cancelled"))) {
            try {
                DecisionsEntryPointFinder({ throw failure }, sources, { sources.contents[it] }).find(emptyList())
                fail("Failure should propagate")
            } catch (actual: Exception) {
                // Files are evaluated in child coroutines, which may rethrow a stack-trace-recovered copy.
                assertEquals(failure::class, actual::class)
                assertEquals(failure.message, actual.message)
            }
        }
    }

    @Test fun `HTTP failure reports the exact batch and structured server reason`() = runBlocking {
        val sources = Sources(mapOf("Main.kt" to "// source\nfun main() {}"))
        val failure = DecisionApiException(500, """{"error":{"message":"Failed to evaluate question: context exhausted"}}""", null)
        try {
            DecisionsEntryPointFinder({ throw failure }, sources, { sources.contents[it] }).find(emptyList())
            fail("Failure should propagate")
        } catch (actual: EntryPointDecisionException) {
            assertSame(failure, actual.cause)
            assertEquals("Main.kt", actual.path)
            assertEquals(listOf(2), actual.lines)
            assertEquals(2, actual.questionCount)
            assertTrue(actual.stateChars > 0)
            assertTrue(actual.message!!.contains("HTTP 500"))
            assertTrue(actual.message!!.contains("context exhausted"))
        }
    }

    @Test fun `HTTP diagnostics omit raw bodies and bound structured messages`() {
        val raw = EntryPointDecisionException("Main.kt", listOf(1), 2, 50,
            DecisionApiException(500, "raw-source-or-proxy-html", null))
        assertFalse(raw.message!!.contains("raw-source-or-proxy-html"))
        val detail = "x".repeat(500)
        val bounded = EntryPointDecisionException("Main.kt", listOf(1), 2, 50,
            DecisionApiException(500, """{"detail":"$detail"}""", null))
        assertTrue(bounded.message!!.endsWith("x".repeat(300)))
        assertFalse(bounded.message!!.contains("x".repeat(301)))
    }

    @Test fun `plain modules, comments, calls and control flow are not candidates`() {
        val requests = mutableListOf<DecisionRequest>()
        val sources = Sources(mapOf(
            "util.py" to "import os\n# def commented():\nx = os.getcwd()",
            "Flow.java" to "class Flow {\n  void work(int x) {\n    if (x > 0) {\n    } else if (x < 0) {\n    }\n" +
                "    for (int i = 0; i < x; i++) {\n    }\n    while (x > 0) {\n    }\n    new Thread(r) {\n    };\n" +
                "    return foo(x)\n  }\n}",
            "flow.kt" to "fun go() {\n  items.forEach(print) {\n  }\n  val y = compute(x) { it }\n  // the command line\n}",
            "package-lock.json" to "{\"command\": \"x\"}",
            "config.yaml" to "command: run",
        ))
        find(sources) { requests += it; response(it) }
        val judged = requests.flatMap { request -> candidates(request).map { path(request) to it["line"].asInt } }
        assertEquals(listOf("Flow.java" to 2, "flow.kt" to 1), judged.sortedBy { it.first })
    }

    @Test fun `scripts with a shebang or main guard are candidates`() {
        val requests = mutableListOf<DecisionRequest>()
        val sources = Sources(mapOf("run.sh" to "#!/bin/sh\necho hi", "tool.py" to "import sys\nif __name__ == \"__main__\":\n    sys.exit(0)"))
        find(sources) { requests += it; response(it) }
        assertEquals(mapOf("run.sh" to listOf(1), "tool.py" to listOf(2)),
            requests.associate { path(it) to candidates(it).map { c -> c["line"].asInt } })
    }

    @Test fun `registration annotations fold into the declaration they annotate`() {
        val requests = mutableListOf<DecisionRequest>()
        val sources = Sources(mapOf(
            "Api.kt" to "class Api {\n    @GetMapping(\"/items\")\n    // lists items\n    fun list(): List<Item> = items\n" +
                "    @Override public void run() {\n    }\n}",
            "app.py" to "@app.route(\"/\")\ndef index():\n    return \"ok\"",
        ))
        val result = find(sources) { requests += it; response(it) }
        assertEquals(listOf("Api.kt" to 4, "Api.kt" to 5, "app.py" to 2), result.entryPoints.map { it.path to it.line })
        assertEquals(listOf("list", "run", "index"), result.entryPoints.map { it.symbol })
        val list = requests.flatMap(::candidates).first { it["line"].asInt == 4 }
        assertEquals("@GetMapping(\"/items\")", list["annotations"].asString)
    }

    @Test fun `manifest registrations in other files are evidence for the registered source`() {
        val requests = mutableListOf<DecisionRequest>()
        val plugin = "src/main/resources/META-INF/plugin.xml"
        val factory = "src/main/kotlin/dev/demo/PanelFactory.kt"
        val other = "src/main/kotlin/dev/demo/Other.kt"
        val sources = Sources(mapOf(
            plugin to "<idea-plugin>\n  <extensions defaultExtensionNs=\"com.intellij\">\n" +
                "    <toolWindow id=\"X\" factoryClass=\"dev.demo.PanelFactory\"/>\n  </extensions>\n</idea-plugin>",
            factory to "package dev.demo\n\nclass PanelFactory : ToolWindowFactory {\n" +
                "    override fun createToolWindowContent(project: Project, window: ToolWindow) {}\n}",
            other to "package dev.demo\n\nfun other() {}",
        ))
        find(sources) { requests += it; response(it) }
        val byPath = requests.associateBy(::path)
        assertEquals(listOf(3), candidates(byPath.getValue(plugin)).map { it["line"].asInt })
        val registrations = byPath.getValue(factory).state.asJsonObject["registrations"].asJsonArray
        assertEquals(1, registrations.size())
        assertTrue(registrations[0].asString.startsWith("$plugin:3: <toolWindow"))
        assertEquals(0, byPath.getValue(other).state.asJsonObject["registrations"].asJsonArray.size())
    }

    @Test fun `stale graph hints follow the symbol or are dropped`() {
        val requests = mutableListOf<DecisionRequest>()
        val sources = Sources(mapOf("Main.kt" to "// moved\n\nfun helper() {}\nfun start() {}"))
        val result = find(sources, listOf(
            EntryPointHint("Main.kt", 1, "Main.start", "named like an entry point"),
            EntryPointHint("Main.kt", 3, "Main.gone", "removed since the graph was built"),
        )) { requests += it; response(it) }
        val judged = requests.flatMap(::candidates)
        assertEquals("named like an entry point", judged.single { it["line"].asInt == 4 }["graph_hint"].asString)
        assertEquals(1, judged.count { it.has("graph_hint") })
        assertTrue(result.summary.contains("1 graph hints no longer match"))
    }

    @Test fun `a candidate that cannot fit is skipped instead of failing the search`() = runBlocking {
        val sources = Sources(mapOf("Main.kt" to "fun main() {}"))
        var calls = 0
        val result = DecisionsEntryPointFinder({ calls++; response(it) }, sources, { sources.contents[it] }, maxRequestBytes = 100)
            .find(emptyList())
        assertEquals(0, calls)
        assertTrue(result.entryPoints.isEmpty())
        assertTrue(result.summary.contains("1 candidates too large"))
    }

    @Test fun `uncertain categories are flagged`() {
        val result = find(Sources(mapOf("Main.kt" to "fun main() {}"))) { response(it, kindConfidence = 0.3) }
        assertTrue(result.entryPoints.single().description.contains("category uncertain"))
    }
}
