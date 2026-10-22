package dev.insight.entrypoints

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.CallGraphNode
import dev.insight.callgraph.SymbolKind
import dev.insight.chat.ChatTool
import dev.insight.chat.ToolInputException
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmTool
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class EntryPointFinderTest {
    private class FakeProject(val contents: MutableMap<String, String>) : ProjectSnapshot {
        val identified = mutableListOf<String>()
        override suspend fun files() = contents.keys.sorted()
        override suspend fun identify(path: String): FileId? {
            val clean = path.trim().removePrefix("./")
            identified += clean
            return contents[clean]?.let { FileId(clean, EntryPointFinder.digest(it), it.lines().size) }
        }
    }

    private class ReadTool(val project: FakeProject) : ChatTool {
        val reads = mutableListOf<String>()
        val argumentsRead = mutableListOf<JsonObject>()
        override val definition = LlmTool(EntryPointFinder.READ_TOOL, "read", JsonParser.parseString("""{"type":"object"}""").asJsonObject)
        override suspend fun call(arguments: JsonObject): String {
            val path = arguments["path"].asString.removePrefix("./")
            reads += path
            argumentsRead += arguments.deepCopy()
            return project.contents[path] ?: throw ToolInputException("No file $path")
        }
    }

    private class FakeModel(vararg script: Completion) {
        val remaining = ArrayDeque(script.toList())
        val requests = mutableListOf<CompletionRequest>()
        suspend fun complete(request: CompletionRequest): Completion { requests += request; return remaining.removeFirst() }
    }

    private fun read(vararg paths: String) = Completion("", toolCalls = paths.mapIndexed { i, p ->
        LlmToolCall("c$i", EntryPointFinder.READ_TOOL, JsonParser.parseString("""{"path":"$p"}""").asJsonObject)
    }, finishReason = FinishReason.TOOL_CALLS)

    private val answer = Completion("""
        ```json
        {"summary": "A CLI.", "entry_points": [
          {"path": "./src/Main.kt", "line": "3", "symbol": "main", "kind": "application", "title": "App", "description": "Starts it.", "confidence": 0.9},
          {"path": "missing/Ghost.kt", "line": 1, "symbol": "ghost", "kind": "cli", "title": "Ghost", "description": "", "confidence": 0.8},
          {"path": "scripts/run.sh", "line": 1, "symbol": "", "kind": "script", "title": "Runner", "description": "", "confidence": 0}],
         "files": [{"path": "src/Main.kt", "note": "Main function."}, {"path": "build.gradle.kts", "note": "Build only."}]}
        ```
    """.trimIndent())

    private fun setup() = FakeProject(mutableMapOf(
        "build.gradle.kts" to "plugins {}", "src/Main.kt" to "\n\nfun main() {}", "src/Util.kt" to "fun util() {}", "scripts/run.sh" to "echo",
    ))

    @Test fun `finds entry points, drops unknown files and records what was read`() = runBlocking {
        val project = setup()
        val tool = ReadTool(project)
        val model = FakeModel(read("build.gradle.kts", "./src/Main.kt"), answer)
        val cache = EntryPointCache()
        val result = EntryPointFinder(model::complete, project, listOf(tool), cache).find(emptyList())

        assertEquals(listOf("src/Main.kt", "scripts/run.sh"), result.entryPoints.map { it.path })
        val main = result.entryPoints.first()
        assertEquals(3, main.line)
        assertEquals(EntryPointKind.APPLICATION, main.kind)
        assertEquals(EntryPointKind.SCRIPT, result.entryPoints[1].kind)
        assertEquals(0.0, result.entryPoints[1].confidence, 0.0)
        assertEquals(listOf("build.gradle.kts", "src/Main.kt"), result.filesRead)
        assertEquals("A CLI.", result.summary)
        assertFalse(result.cached)
        assertEquals(listOf("Build only.", "Main function."), cache.notes().map { it.note })
        assertEquals(listOf("main"), cache.notes().single { it.path == "src/Main.kt" }.entryPoints.map { it.symbol })
        // The prompt carries the tree and the read limit.
        val first = model.requests.first().messages
        assertEquals(LlmRole.SYSTEM, first[0].role)
        assertTrue(first[0].content.contains("at most 20 files"))
        assertTrue(first[1].content.contains("scripts/\n  run.sh"))
    }

    @Test fun `reuses the whole answer while the files it rests on are unchanged`() = runBlocking {
        val project = setup()
        val cache = EntryPointCache()
        val model = FakeModel(read("src/Main.kt"), answer)
        EntryPointFinder(model::complete, project, listOf(ReadTool(project)), cache).find(emptyList())

        val again = EntryPointFinder(model::complete, project, listOf(ReadTool(project)), cache).find(emptyList())
        assertTrue(again.cached)
        assertEquals(0, again.requestCount)
        assertEquals(2, model.requests.size)

        // A file the answer did not use may change freely.
        project.contents["src/Util.kt"] = "fun util2() {}"
        assertTrue(EntryPointFinder(model::complete, project, listOf(ReadTool(project)), cache).find(emptyList()).cached)
    }

    @Test fun `after a change only changed files are read again and unchanged notes are passed on`() = runBlocking {
        val project = setup()
        val cache = EntryPointCache()
        EntryPointFinder(FakeModel(read("build.gradle.kts", "src/Main.kt"), answer)::complete, project, listOf(ReadTool(project)), cache)
            .find(emptyList())

        project.contents["src/Main.kt"] = "fun main(args: Array<String>) {}"
        val tool = ReadTool(project)
        val model = FakeModel(read("src/Main.kt"), answer)
        val result = EntryPointFinder(model::complete, project, listOf(tool), cache).find(emptyList())

        assertFalse(result.cached)
        assertEquals(listOf("src/Main.kt"), tool.reads)
        assertEquals(listOf("build.gradle.kts"), result.filesReused)
        val prompt = model.requests.first().messages[1].content
        assertTrue(prompt.contains("Prior findings for unchanged files:\n- build.gradle.kts: Build only."))
        assertTrue(prompt.contains("Changed since the earlier analysis:\n- src/Main.kt"))
        assertEquals(EntryPointFinder.digest("fun main(args: Array<String>) {}"), cache.notes().single { it.path == "src/Main.kt" }.hash)
    }

    @Test fun `enforces the file read limit`() = runBlocking {
        val project = setup()
        val tool = ReadTool(project)
        val model = FakeModel(read("src/Main.kt", "src/Util.kt", "src/Main.kt"), answer)
        val result = EntryPointFinder(model::complete, project, listOf(tool), EntryPointCache(), maxFileReads = 1).find(emptyList())
        assertEquals(listOf("src/Main.kt", "src/Main.kt"), tool.reads)
        assertEquals(listOf("src/Main.kt"), result.filesRead)
        val results = model.requests[1].messages.filter { it.role == LlmRole.TOOL }.map { it.content }
        assertTrue(results[1].contains("limit of 1 files is reached"))
    }

    @Test fun `converts a prose answer into JSON with one extra request`() = runBlocking {
        val project = setup()
        val model = FakeModel(Completion("The app starts in src/Main.kt line 3."), answer)
        val result = EntryPointFinder(model::complete, project, emptyList(), EntryPointCache()).find(emptyList())
        assertEquals(2, result.requestCount)
        assertNotNull(model.requests[1].responseFormat)
        assertEquals("src/Main.kt", result.entryPoints.first().path)
    }

    @Test fun `repairs parseable JSON with an invalid kind using a constrained request`() = runBlocking {
        val invalid = answer.copy(content = answer.content.replace("\"kind\": \"application\"", "\"kind\": \"frontend\""))
        val model = FakeModel(invalid, answer)
        val result = EntryPointFinder(model::complete, setup(), emptyList(), EntryPointCache()).find(emptyList())
        assertEquals(2, result.requestCount)
        assertEquals(EntryPointKind.APPLICATION, result.entryPoints.first().kind)
        assertEquals("json_schema", model.requests[1].responseFormat!!["type"].asString)
        assertTrue(model.requests[1].messages[1].content.contains("entry_points[0].kind"))
    }

    @Test fun `imperfect repair preserves entries and caches the usable analysis`() = runBlocking {
        val invalid = answer.copy(content = answer.content.replace("\"kind\": \"application\"", "\"kind\": \"frontend\""))
        val cache = EntryPointCache()
        val model = FakeModel(invalid, invalid)
        val finder = EntryPointFinder(model::complete, setup(), emptyList(), cache)
        val result = finder.find(emptyList())
        assertEquals(listOf("src/Main.kt", "scripts/run.sh"), result.entryPoints.map { it.path })
        assertEquals(EntryPointKind.APPLICATION, result.entryPoints.first().kind)
        assertTrue(finder.find(emptyList()).cached)
    }

    @Test fun `malformed entries and notes do not discard valid entries after repair`() = runBlocking {
        val json = JsonParser.parseString(answer.content.removePrefix("```json").removeSuffix("```")).asJsonObject
        json.getAsJsonArray("entry_points").add(JsonObject().apply { addProperty("path", "src/Util.kt"); addProperty("line", 1.5) })
        json.getAsJsonArray("entry_points").add("bad element")
        json.getAsJsonArray("files")[0].asJsonObject.remove("note")
        val incomplete = Completion(json.toString())
        val project = setup()
        val cache = EntryPointCache()
        val finder = EntryPointFinder(FakeModel(read("src/Main.kt"), incomplete, incomplete)::complete, project,
            listOf(ReadTool(project)), cache)
        val result = finder.find(emptyList())
        assertEquals(listOf("src/Main.kt", "scripts/run.sh"), result.entryPoints.map { it.path })
        assertTrue(finder.find(emptyList()).cached)
        assertEquals(listOf("main"), cache.notes().single().entryPoints.map { it.symbol })
    }

    @Test fun `accepts integral floating point lines without repair or rounding`() = runBlocking {
        val integral = answer.copy(content = answer.content.replace("\"line\": \"3\"", "\"line\": 3.0"))
        val model = FakeModel(integral)
        val result = EntryPointFinder(model::complete, setup(), emptyList(), EntryPointCache()).find(emptyList())
        assertEquals(3, result.entryPoints.first().line)
        assertEquals(1, model.requests.size)
    }

    @Test fun `a failed formatting request preserves the original parseable answer`() = runBlocking {
        val incomplete = answer.copy(content = answer.content.replace("\"kind\": \"application\"", "\"kind\": \"frontend\""))
        val model = FakeModel(incomplete, Completion("unparseable"), Completion("still unparseable"))
        val result = EntryPointFinder(model::complete, setup(), emptyList(), EntryPointCache()).find(emptyList())
        assertEquals(listOf("src/Main.kt", "scripts/run.sh"), result.entryPoints.map { it.path })
        assertEquals(3, result.requestCount)
    }

    @Test fun `an empty repair cannot erase valid original entries`() = runBlocking {
        val incomplete = answer.copy(content = answer.content.replace("\"kind\": \"application\"", "\"kind\": \"frontend\""))
        val model = FakeModel(incomplete, Completion("{}"))
        val result = EntryPointFinder(model::complete, setup(), emptyList(), EntryPointCache()).find(emptyList())
        assertEquals(listOf("src/Main.kt", "scripts/run.sh"), result.entryPoints.map { it.path })
        assertEquals("A CLI.", result.summary)
    }

    @Test fun `rejects an answer with no usable analysis`() = runBlocking {
        try {
            EntryPointFinder(FakeModel(Completion("{}"), Completion("{}"))::complete, setup(), emptyList(), EntryPointCache())
                .find(emptyList())
            fail("Expected unusable output to be rejected")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("no usable analysis"))
        }
    }

    @Test fun `caps source ranges and enforces a total line budget`() = runBlocking {
        val project = setup()
        val tool = ReadTool(project)
        val oversized = read("src/Main.kt").let { completion ->
            completion.copy(toolCalls = completion.toolCalls.map { it.copy(arguments = it.arguments.deepCopy().apply { addProperty("end_line", 400) }) })
        }
        val model = FakeModel(oversized, read("src/Main.kt"), read("src/Util.kt"), answer)
        EntryPointFinder(model::complete, project, listOf(tool), EntryPointCache(), maxSourceLines = 100).find(emptyList())
        assertEquals(listOf(80, 20), tool.argumentsRead.map { it["end_line"].asInt })
        assertTrue(model.requests[3].messages.last().content.contains("Source read budget reached"))
    }

    @Test fun `caps source characters even for very long lines`() = runBlocking {
        val project = setup().apply { contents["src/Util.kt"] = "x".repeat(500) }
        val tool = ReadTool(project)
        val model = FakeModel(read("src/Util.kt"), read("src/Main.kt"), answer)
        EntryPointFinder(model::complete, project, listOf(tool), EntryPointCache(), maxSourceChars = 100).find(emptyList())
        assertEquals(listOf("src/Util.kt"), tool.reads)
        val first = model.requests[1].messages.last().content
        assertTrue(first.contains("Source character budget reached"))
        assertFalse(first.contains("x".repeat(101)))
    }

    @Test fun `search results and negative matches invalidate the whole answer on source changes`() = runBlocking {
        val project = setup()
        val cache = EntryPointCache()
        val search = object : ChatTool {
            override val definition = LlmTool(EntryPointFinder.SEARCH_TOOL, "search", JsonObject())
            override suspend fun call(arguments: JsonObject) = if (project.contents["src/Util.kt"]!!.contains("bootstrap"))
                "src/Util.kt:1: fun bootstrap() {}" else "No matches for 'bootstrap'."
        }
        val searching = Completion("", toolCalls = listOf(LlmToolCall("search", EntryPointFinder.SEARCH_TOOL, JsonObject())))
        val model = FakeModel(searching, answer, answer)
        val finder = EntryPointFinder(model::complete, project, listOf(search), cache)
        finder.find(emptyList())
        assertTrue(finder.find(emptyList()).cached)
        project.contents["src/Util.kt"] = "fun bootstrap() {}"
        assertFalse(finder.find(emptyList()).cached)
        assertEquals(3, model.requests.size)
    }

    @Test fun `search cache replays bounded queries without hashing unrelated files`() = runBlocking {
        val project = setup().apply { (1..500).forEach { contents["docs/Doc$it.md"] = "documentation" } }
        val queries = mutableListOf<JsonObject>()
        val search = object : ChatTool {
            override val definition = LlmTool(EntryPointFinder.SEARCH_TOOL, "search", JsonObject())
            override suspend fun call(arguments: JsonObject): String {
                queries += arguments.deepCopy()
                return "src/Main.kt:3: fun main() {}"
            }
        }
        val searching = Completion("", toolCalls = listOf(LlmToolCall("search", EntryPointFinder.SEARCH_TOOL,
            JsonParser.parseString("""{"query":"main","glob":"*.kt","directory":"src","max_results":200}""").asJsonObject)))
        val model = FakeModel(searching, answer)
        val finder = EntryPointFinder(model::complete, project, listOf(search), EntryPointCache())
        finder.find(emptyList())
        project.contents["docs/Doc1.md"] = "edited documentation"
        assertTrue(finder.find(emptyList()).cached)
        assertEquals(2, queries.size)
        assertEquals(queries[0], queries[1])
        assertEquals(80, queries[0]["max_results"].asInt)
        assertFalse(project.identified.any { it.startsWith("docs/") || it == "src/Util.kt" })
    }

    @Test fun `search and read output share the source line budget`() = runBlocking {
        val project = setup()
        val queries = mutableListOf<JsonObject>()
        val search = object : ChatTool {
            override val definition = LlmTool(EntryPointFinder.SEARCH_TOOL, "search", JsonObject())
            override suspend fun call(arguments: JsonObject): String {
                queries += arguments.deepCopy()
                return (1..arguments["max_results"].asInt).joinToString("\n") { "src/Main.kt:$it: match" }
            }
        }
        val searching = Completion("", toolCalls = listOf(LlmToolCall("search", EntryPointFinder.SEARCH_TOOL, JsonObject())))
        val tool = ReadTool(project)
        val model = FakeModel(read("src/Main.kt"), searching, read("src/Util.kt"), answer)
        EntryPointFinder(model::complete, project, listOf(tool, search), EntryPointCache(), maxSourceLines = 85).find(emptyList())
        assertEquals(5, queries.single()["max_results"].asInt)
        assertEquals(listOf("src/Main.kt"), tool.reads)
        assertTrue(model.requests[3].messages.last().content.contains("Source read budget reached"))
    }

    @Test fun `search output consumes the shared character budget`() = runBlocking {
        val search = object : ChatTool {
            override val definition = LlmTool(EntryPointFinder.SEARCH_TOOL, "search", JsonObject())
            override suspend fun call(arguments: JsonObject) = "src/Main.kt:3: " + "x".repeat(500)
        }
        val searching = Completion("", toolCalls = listOf(LlmToolCall("search", EntryPointFinder.SEARCH_TOOL, JsonObject())))
        val tool = ReadTool(setup())
        val model = FakeModel(searching, read("src/Main.kt"), answer)
        EntryPointFinder(model::complete, tool.project, listOf(tool, search), EntryPointCache(), maxSourceChars = 100)
            .find(emptyList())
        assertTrue(tool.reads.isEmpty())
        assertTrue(model.requests[1].messages.last().content.contains("Source character budget reached"))
        assertFalse(model.requests[1].messages.last().content.contains("x".repeat(101)))
    }

    @Test fun `hint line and reach changes and ignored file additions preserve a cached result`() = runBlocking {
        val project = setup()
        val hints = listOf(EntryPointHint("src/Main.kt", 3, "main", "reaches 2 functions"))
        val finder = EntryPointFinder(FakeModel(answer)::complete, project, emptyList(), EntryPointCache())
        finder.find(hints)
        project.contents[".traverse-cache/new.json"] = "cache"
        assertTrue(finder.find(listOf(hints.single().copy(line = 4, reason = "reaches 3 functions"))).cached)
    }

    @Test fun `new visible launch files invalidate cached discovery`() = runBlocking {
        val project = setup()
        val finder = EntryPointFinder(FakeModel(answer, answer)::complete, project, emptyList(), EntryPointCache())
        finder.find(emptyList())
        project.contents["new-cli.sh"] = "python graph.py"
        assertFalse(finder.find(emptyList()).cached)
    }

    @Test fun `drops nonexistent line locations and preserves model priority order`() = runBlocking {
        val reversed = answer.copy(content = answer.content.replace("\"confidence\": 0.9", "\"confidence\": 0.1")
            .replace("\"confidence\": 0}", "\"confidence\": 0.99}"))
        val result = EntryPointFinder(FakeModel(reversed)::complete, setup(), emptyList(), EntryPointCache()).find(emptyList())
        assertEquals(listOf("src/Main.kt", "scripts/run.sh"), result.entryPoints.map { it.path })
        val outside = answer.copy(content = answer.content.replace("\"line\": \"3\"", "\"line\": 999"))
        val filtered = EntryPointFinder(FakeModel(outside)::complete, setup(), emptyList(), EntryPointCache()).find(emptyList())
        assertEquals(listOf("scripts/run.sh"), filtered.entryPoints.map { it.path })
    }

    @Test fun `tree drops cache and generated paths while retaining launch candidates`() {
        val paths = (1..100).map { ".traverse-cache/hash$it.json" } + listOf(
            ".idea/workspace.xml", "frontend/node_modules/react/index.js", "frontend/dist/main.js",
            "frontend/src/main.tsx", "frontend/package.json", "pyproject.toml", "Makefile", "scripts/check.sh",
        )
        val tree = FileTree.render(paths, 8_000)
        assertFalse(tree.contains("hash"))
        assertFalse(tree.contains(".idea"))
        assertFalse(tree.contains("node_modules"))
        assertFalse(tree.contains("dist/"))
        assertTrue(tree.contains("main.tsx"))
        assertTrue(tree.contains("package.json"))
        assertTrue(tree.contains("pyproject.toml"))
        assertTrue(tree.contains("Makefile"))
        assertTrue(tree.contains("check.sh"))
    }

    @Test fun `file tree summarizes large folders and keeps landmarks and root files`() {
        val paths = listOf("package.json", "README.md") + (1..40).map { "src/components/C$it.tsx" } + "src/components/index.ts" +
            "src/deep/a/b/c/d/x.ts"
        val full = FileTree.render(paths, 100_000)
        assertTrue(full.contains("  deep/a/b/c/d/\n    x.ts"))
        assertTrue(full.contains("C40.tsx"))
        val small = FileTree.render(paths, 200)
        assertTrue(small, small.length <= 200)
        assertTrue(small.contains("package.json"))
        assertTrue(small, small.contains("index.ts") || small.contains("components/ (41 files"))
        assertFalse(small.contains("C17.tsx"))
    }

    @Test fun `output names in source packages remain in the tree and hints`() {
        val paths = listOf("src/main/kotlin/com/acme/build/Main.kt", "internal/build/main.go", "src/dist/main.ts",
            "src/target/main.rs", "src/vendor/main.go", "src/coverage/main.py")
        paths.forEach { assertFalse(it, FileTree.isNoise(it)) }
        val tree = FileTree.render(paths, 8_000)
        assertTrue(tree.contains("Main.kt"))
        assertTrue(tree.contains("internal/build/"))
        val nodes = paths.mapIndexed { i, path ->
            CallGraphNode(i, "main", "", "$path:1", 0, true, SymbolKind.FUNCTION, "/p/$path")
        }
        val hints = EntryPointHints.fromGraph(CallGraph(nodes, emptySet(), false, projectGraph = true), { it.removePrefix("/p/") })
        assertEquals(paths.toSet(), hints.map { it.path }.toSet())
        listOf("build/Main.kt", "frontend/dist/main.js", "packages/ui/build/main.js", "apps/ui/coverage/main.js")
            .forEach { assertTrue(it, FileTree.isNoise(it)) }
    }

    @Test fun `graph hints name uncalled entry-like functions and big call roots, skipping tests`() {
        fun fn(id: Int, name: String, file: String, line: Int) =
            CallGraphNode(id, name, "", "${file.substringAfterLast('/')}:$line", 0, true, SymbolKind.FUNCTION, "/p/$file")
        val nodes = listOf(
            fn(0, "main", "src/Main.kt", 3), fn(1, "start", "src/Server.kt", 10), fn(2, "a", "src/A.kt", 1),
            fn(3, "b", "src/A.kt", 2), fn(4, "c", "src/A.kt", 3), fn(5, "d", "src/A.kt", 4), fn(6, "main", "src/test/T.kt", 1),
            fn(7, "handle", "src/A.kt", 9),
        )
        val edges = setOf(CallGraphEdge(0, 7), CallGraphEdge(1, 2), CallGraphEdge(2, 3), CallGraphEdge(3, 4), CallGraphEdge(4, 5))
        val hints = EntryPointHints.fromGraph(CallGraph(nodes, edges, false, projectGraph = true), { it.removePrefix("/p/") })
        assertEquals(listOf("src/Main.kt main", "src/Server.kt start"), hints.map { it.key })
        assertEquals("src/Main.kt:3 main: named like an entry point; nothing in the project calls it", hints[0].text)

        val renamed = nodes.toMutableList().apply { set(1, fn(1, "boot", "src/Server.kt", 10)) }
        val roots = EntryPointHints.fromGraph(CallGraph(renamed, edges, false, projectGraph = true), { it.removePrefix("/p/") })
        assertEquals("src/Server.kt:10 boot: nothing in the project calls it; it reaches 4 functions", roots.last().text)
    }
}
