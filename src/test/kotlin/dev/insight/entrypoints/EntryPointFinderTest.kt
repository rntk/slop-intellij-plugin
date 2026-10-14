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

class EntryPointFinderTest {
    private class FakeProject(val contents: MutableMap<String, String>) : ProjectSnapshot {
        override suspend fun files() = contents.keys.sorted()
        override suspend fun identify(path: String): FileId? {
            val clean = path.trim().removePrefix("./")
            return contents[clean]?.let { FileId(clean, EntryPointFinder.digest(it)) }
        }
    }

    private class ReadTool(val project: FakeProject) : ChatTool {
        val reads = mutableListOf<String>()
        override val definition = LlmTool(EntryPointFinder.READ_TOOL, "read", JsonParser.parseString("""{"type":"object"}""").asJsonObject)
        override suspend fun call(arguments: JsonObject): String {
            val path = arguments["path"].asString.removePrefix("./")
            reads += path
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
          {"path": "scripts/run.sh", "line": 1, "symbol": "", "kind": "nonsense", "title": "Runner", "description": "", "confidence": -1}],
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
        val result = EntryPointFinder(model::complete, project, listOf(tool), cache).find("demo", emptyList())

        assertEquals(listOf("src/Main.kt", "scripts/run.sh"), result.entryPoints.map { it.path })
        val main = result.entryPoints.first()
        assertEquals(3, main.line)
        assertEquals(EntryPointKind.APPLICATION, main.kind)
        assertEquals(EntryPointKind.OTHER, result.entryPoints[1].kind)
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
        EntryPointFinder(model::complete, project, listOf(ReadTool(project)), cache).find("demo", emptyList())

        val again = EntryPointFinder(model::complete, project, listOf(ReadTool(project)), cache).find("demo", emptyList())
        assertTrue(again.cached)
        assertEquals(0, again.requestCount)
        assertEquals(2, model.requests.size)

        // A file the answer did not use may change freely.
        project.contents["src/Util.kt"] = "fun util2() {}"
        assertTrue(EntryPointFinder(model::complete, project, listOf(ReadTool(project)), cache).find("demo", emptyList()).cached)
    }

    @Test fun `after a change only changed files are read again and unchanged notes are passed on`() = runBlocking {
        val project = setup()
        val cache = EntryPointCache()
        EntryPointFinder(FakeModel(read("build.gradle.kts", "src/Main.kt"), answer)::complete, project, listOf(ReadTool(project)), cache)
            .find("demo", emptyList())

        project.contents["src/Main.kt"] = "fun main(args: Array<String>) {}"
        val tool = ReadTool(project)
        val model = FakeModel(read("src/Main.kt"), answer)
        val result = EntryPointFinder(model::complete, project, listOf(tool), cache).find("demo", emptyList())

        assertFalse(result.cached)
        assertEquals(listOf("src/Main.kt"), tool.reads)
        assertEquals(listOf("build.gradle.kts"), result.filesReused)
        val prompt = model.requests.first().messages[1].content
        assertTrue(prompt.contains("Already examined, unchanged since then (do not read again):\n- build.gradle.kts: Build only."))
        assertTrue(prompt.contains("Changed since the earlier analysis (re-read if relevant):\n- src/Main.kt"))
        assertEquals(EntryPointFinder.digest("fun main(args: Array<String>) {}"), cache.notes().single { it.path == "src/Main.kt" }.hash)
    }

    @Test fun `enforces the file read limit`() = runBlocking {
        val project = setup()
        val tool = ReadTool(project)
        val model = FakeModel(read("src/Main.kt", "src/Util.kt", "src/Main.kt"), answer)
        val result = EntryPointFinder(model::complete, project, listOf(tool), EntryPointCache(), maxFileReads = 1).find("demo", emptyList())
        assertEquals(listOf("src/Main.kt", "src/Main.kt"), tool.reads)
        assertEquals(listOf("src/Main.kt"), result.filesRead)
        val results = model.requests[1].messages.filter { it.role == LlmRole.TOOL }.map { it.content }
        assertTrue(results[1].contains("limit of 1 files is reached"))
    }

    @Test fun `converts a prose answer into JSON with one extra request`() = runBlocking {
        val project = setup()
        val model = FakeModel(Completion("The app starts in src/Main.kt line 3."), answer)
        val result = EntryPointFinder(model::complete, project, emptyList(), EntryPointCache()).find("demo", emptyList())
        assertEquals(2, result.requestCount)
        assertNotNull(model.requests[1].responseFormat)
        assertEquals("src/Main.kt", result.entryPoints.first().path)
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
