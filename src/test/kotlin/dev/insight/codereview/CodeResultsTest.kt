package dev.insight.codereview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.chat.ToolInputException
import dev.insight.diff.ChangeSummaries
import dev.insight.diff.DiffNote
import dev.insight.diff.DiffSourceRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodeResultsTest {
    private fun json(text: String): JsonObject {
        assertTrue("${text.length} characters", text.length <= MAX_RESULT_CHARS)
        return JsonParser.parseString(text).asJsonObject
    }

    private fun rejected(message: String, block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: ToolInputException) { assertTrue(e.message, e.message!!.contains(message)) }
    }

    private val numbered = codeFile("src/Lines.kt", (1..1000).joinToString("") { "line $it\n" })
    private val wide = codeFile("src/Wide.kt", (1..1000).joinToString("") { "val wide$it = \"${"<w>".repeat(30)}\"\n" })

    @Test fun `read pages long ranges and reports what did not fit`() {
        val snapshot = codeSnapshot(numbered, wide)
        val first = json(codeReadResult(snapshot, listOf(CodeRange(numbered.path)))).getAsJsonArray("ranges").single().asJsonObject
        assertEquals(1, first["start_line"].asInt)
        assertEquals(400, first["end_line"].asInt)
        assertEquals(1000, first["total_lines"].asInt)
        assertEquals(401, first["next_start_line"].asInt)
        assertTrue(first["text"].asString.startsWith("  1| line 1\n  2| line 2"))
        assertTrue(first["text"].asString.endsWith("400| line 400"))

        val tail = json(codeReadResult(snapshot, listOf(CodeRange(numbered.path, 990, 2000)))).getAsJsonArray("ranges").single().asJsonObject
        assertEquals(1000, tail["end_line"].asInt)
        assertFalse(tail.has("next_start_line"))

        // Wide lines escape to more characters than the limit holds: the first range is cut, the second waits.
        val result = json(codeReadResult(snapshot, listOf(CodeRange(wide.path, 1, 400), CodeRange(numbered.path, 5, 6))))
        val cut = result.getAsJsonArray("ranges").single().asJsonObject
        val next = cut["next_start_line"].asInt
        assertEquals(cut["end_line"].asInt + 1, next)
        assertTrue(next in 50..400)
        val notRead = result.getAsJsonArray("not_read").single().asJsonObject
        assertEquals(numbered.path, notRead["path"].asString)
        assertEquals(5, notRead["start_line"].asInt)

        rejected("is not a file in this review's scope") { codeReadResult(snapshot, listOf(CodeRange("src/Missing.kt"))) }
        rejected("has only 1000 lines") { codeReadResult(snapshot, listOf(CodeRange(numbered.path, 1001))) }
        rejected("Range 2: end_line") { codeReadResult(snapshot, listOf(CodeRange(numbered.path), CodeRange(numbered.path, 5, 4))) }
    }

    @Test fun `search pages matches clips lines and rejects bad or runaway patterns`() {
        val files = (1..3).map { f -> codeFile("src/m$f/S$f.kt", (1..40).joinToString("") { "fun target$it() = \"${"x".repeat(300)}\"\n" }) }
        val snapshot = codeSnapshot(*files.toTypedArray(), codeFile("docs/target.md", "Target docs\n"))
        val pages = generateSequence(json(codeSearchResult(snapshot, "TARGET", false, "src", null, 0))) { page ->
            page["next_offset"]?.asInt?.let { json(codeSearchResult(snapshot, "TARGET", false, "src", null, it)) }
        }.toList()
        val matches = pages.flatMap { it.getAsJsonArray("matches").map { m -> m.asJsonObject } }
        assertEquals(120, matches.size)
        assertTrue(pages.all { it.getAsJsonArray("matches").size() <= CODE_SEARCH_PAGE })
        assertEquals(listOf(files[0].path, "1"), matches.first().let { listOf(it["path"].asString, it["line"].asString) })
        assertTrue(matches.all { it["text"].asString.length < 240 })
        assertTrue(matches.all { it["path"].asString.startsWith("src/") })

        val regex = json(codeSearchResult(snapshot, "target(1|2)\\(", true, "", "S2.kt", 0)).getAsJsonArray("matches")
        assertEquals(2, regex.size())
        assertEquals(files[1].path, regex.first().asJsonObject["path"].asString)
        assertEquals("No matches.", json(codeSearchResult(snapshot, "absent", false, "", null, 0))["note"].asString)
        rejected("not a valid regular expression") { codeSearchResult(snapshot, "(unclosed", true, "", null, 0) }

        val runaway = codeSnapshot(codeFile("src/a.txt", "a".repeat(40) + "\n"))
        val started = System.nanoTime()
        rejected("too expensive") { codeSearchResult(runaway, "(.*a){20}b", true, "", null, 0) }
        assertTrue(System.nanoTime() - started < 5_000_000_000)
    }

    @Test fun `files page by directory and glob`() {
        val files = (1..250).map { codeFile("src/p${it % 3}/F${it.toString().padStart(3, '0')}.kt", "x\n") } +
            codeFile("src/p0/readme.md", "y\n")
        val snapshot = codeSnapshot(*files.toTypedArray())
        val first = json(codeFilesResult(snapshot, "", "*.kt", 0))
        assertEquals(250, first["total"].asInt)
        assertEquals(100, first.getAsJsonArray("files").size())
        assertEquals(100, first["next_offset"].asInt)
        val last = json(codeFilesResult(snapshot, "", "*.kt", 200))
        assertEquals(50, last.getAsJsonArray("files").size())
        assertFalse(last.has("next_offset"))
        val folder = json(codeFilesResult(snapshot, "src/p0/", null, 0))
        assertEquals(84, folder["total"].asInt)
        assertEquals(1, json(codeFilesResult(snapshot, "", "src/p0/*.md", 0))["total"].asInt)
        assertEquals(1, folder.getAsJsonArray("files").first().asJsonObject["lines"].asInt)
        rejected("No files in scope under src/none") { codeFilesResult(snapshot, "src/none", null, 0) }
        rejected("Offset") { codeFilesResult(snapshot, "", null, 1000) }

        val big = codeSnapshot(codeFile("src/Big.kt", largeSource()))
        assertTrue(json(codeFilesResult(big, "", null, 0)).getAsJsonArray("files").single().asJsonObject["parts"].asInt > 1)
    }

    @Test fun `every result fits the tool result limit even with long paths`() {
        val long = "very/".repeat(30)
        val files = (1..300).map { codeFile("$long$it/${"Name".repeat(10)}$it.kt", "fun needle() = \"${"<>".repeat(150)}\"\n".repeat(3)) }
        val snapshot = codeSnapshot(*files.toTypedArray())
        json(codeFilesResult(snapshot, "", null, 0))
        json(codeSearchResult(snapshot, "needle", false, "", null, 0))
        json(codeReadResult(snapshot, files.take(10).map { CodeRange(it.path) }))
        val tree = codeSummaryTree(snapshot.files)
        json(codeSummariesJson(fillAll(ChangeSummaries(tree)), 0, null))
        val notes = (1..50).map { DiffNote(listOf(files[it].path), "n".repeat(2000)) }
        val overview = codeOverview(snapshot, null, files.map { it.path }, notes, emptyList(), fillAll(ChangeSummaries(tree)))
        assertTrue(overview.length <= CODE_OVERVIEW_CHARS)
        JsonParser.parseString(overview)
    }

    @Test fun `overview lists totals skipped reasons folders summaries selection and notes`() {
        val files = listOf(codeFile("src/app/A.kt", "a\n"), codeFile("src/app/B.kt", "b\n"), codeFile("src/lib/Big.kt", largeSource()))
        val snapshot = CodeSnapshot(CodeScope("src", "/project/src"), files,
            listOf(SkippedFile("src/logo.png", SkipReason.BINARY), SkippedFile("src/x.min.js", SkipReason.MINIFIED),
                SkippedFile("src/y.png", SkipReason.BINARY)), ignoredCount = 4)
        val summaries = fillAll(ChangeSummaries(codeSummaryTree(files)))
        val overview = json(codeOverview(snapshot, setOf("src/app"), listOf("src/app/A.kt"),
            listOf(DiffNote(listOf("src/app/A.kt"), "About A")), emptyList(), summaries))
        assertEquals("src", overview["scope"].asString)
        assertEquals("fixed selection of 1 paths", overview["tab"].asString)
        assertEquals(3, overview["totals"].asJsonObject["files"].asInt)
        assertEquals(1, overview["totals"].asJsonObject["files_split_into_parts"].asInt)
        assertEquals(2, overview["skipped"].asJsonObject["binary"].asInt)
        assertEquals(4, overview["skipped"].asJsonObject["ignored files and folders"].asInt)
        assertTrue(overview["folders"].asString.contains("app/ (2 files)"))
        val top = overview["summaries"].asJsonObject.getAsJsonArray("top").map { it.asJsonObject["location"].asString }
        assertEquals(listOf("src/", "src/app/", "src/lib/Big.kt"), top)
        assertEquals("src/app/A.kt", overview.getAsJsonArray("selected_paths").single().asString)
        assertEquals("About A", overview.getAsJsonArray("notes").single().asJsonObject["text_excerpt"].asString)
    }

    @Test fun `summaries page a subtree by folder or file path`() {
        val files = (1..40).map { codeFile("src/a/F$it.kt", "a$it\n") } + (1..40).map { codeFile("src/b/G$it.kt", "b$it\n") } +
            codeFile("src/b/deep/Big.kt", largeSource())
        val summaries = fillAll(ChangeSummaries(codeSummaryTree(files)))
        val whole = json(codeSummariesJson(summaries, 0, null))
        assertEquals(summaries.tree.nodes.size, whole["totals"].asJsonObject["nodes"].asInt)

        val pages = generateSequence(json(codeSummariesJson(summaries, 0, "src/a"))) { page ->
            page["next_offset"]?.asInt?.let { json(codeSummariesJson(summaries, it, "src/a")) }
        }.toList()
        val nodes = pages.flatMap { it.getAsJsonArray("nodes").map { n -> n.asJsonObject } }
        assertEquals("src/a/", pages.first()["under"].asString)
        assertEquals(pages.first()["totals"].asJsonObject["nodes"].asInt, nodes.size)
        assertTrue(nodes.all { it["location"].asString.startsWith("src/a/") })
        assertEquals(40, nodes.count { it["kind"].asString == "file" })

        val big = json(codeSummariesJson(summaries, 0, "src/b/deep/Big.kt"))
        val bigNodes = big.getAsJsonArray("nodes").map { it.asJsonObject }
        assertEquals("src/b/deep/Big.kt", big["under"].asString)
        assertEquals("file", bigNodes.first()["kind"].asString)
        assertTrue(bigNodes.drop(1).all { it["kind"].asString == "chunk" && it.has("lines") })
        // A folder inside a merged chain pages the smallest node that holds its files.
        assertEquals(json(codeSummariesJson(summaries, 0, "src/b/deep"))["under"].asString, "src/b/deep/Big.kt")
        rejected("No summary node covers src/c") { codeSummariesJson(summaries, 0, "src/c") }
    }

    @Test fun `source blocks show exact current lines within the limits`() {
        val snapshot = codeSnapshot(numbered, wide)
        val block = resolveCodeSource(snapshot, DiffSourceRange(numbered.path, 3, 4))
        assertEquals("current", block.range.side)
        assertEquals("line 3\nline 4", block.text)
        rejected("not a file in this review's scope") { resolveCodeSource(snapshot, DiffSourceRange("x.kt", 1, 1)) }
        rejected("1–200 lines") { resolveCodeSource(snapshot, DiffSourceRange(numbered.path, 1, 200 + 1)) }
        rejected("1–200 lines") { resolveCodeSource(snapshot, DiffSourceRange(numbered.path, 0, 3)) }
        rejected("exceeds the 1000 lines") { resolveCodeSource(snapshot, DiffSourceRange(numbered.path, 950, 1001)) }
        rejected("exceeds 20000 characters") { resolveCodeSource(snapshot, DiffSourceRange(wide.path, 1, 200)) }
    }

    @Test fun `tools validate arguments before calling the context`() = runBlocking {
        val calls = mutableListOf<String>()
        val snap = codeSnapshot(numbered)
        val context = object : CodeReviewContext {
            override val snapshot = snap
            override suspend fun overview() = codeOverview(snap, null, emptyList(), emptyList(), emptyList(), null)
            override suspend fun listFiles(directory: String, glob: String?, offset: Int) = "files $directory $glob $offset".also { calls += it }
            override suspend fun read(ranges: List<CodeRange>) = "read $ranges".also { calls += it }
            override suspend fun search(query: String, regex: Boolean, directory: String, glob: String?, offset: Int) =
                "search $query $regex $directory $glob $offset".also { calls += it }
            override suspend fun showSource(range: DiffSourceRange) = "source $range".also { calls += it }
            override suspend fun addNotes(notes: List<DiffNote>) = "notes $notes".also { calls += it }
            override suspend fun summaries(offset: Int, path: String?) = "summaries $offset $path".also { calls += it }
        }
        val tools = codeReviewTools(context).associateBy { it.definition.name }
        assertEquals(listOf("point_to_code", "list_code_files", "read_code", "search_code", "find_code_symbols", "show_code_source", "add_code_notes",
            "get_code_summaries"), tools.keys.toList())
        suspend fun call(name: String, args: String) = tools.getValue(name).call(JsonParser.parseString(args).asJsonObject)
        call("list_code_files", "{}")
        call("read_code", """{"ranges":[{"path":"src/Lines.kt","start_line":3},{"path":"src/Lines.kt"}]}""")
        call("search_code", """{"query":"x","regex":true,"glob":"*.kt","offset":50}""")
        call("show_code_source", """{"path":"src/Lines.kt","start_line":1,"end_line":2}""")
        call("get_code_summaries", """{"path":"src"}""")
        assertEquals(listOf("files  null 0", "read [CodeRange(path=src/Lines.kt, startLine=3, endLine=null), " +
            "CodeRange(path=src/Lines.kt, startLine=1, endLine=null)]", "search x true  *.kt 50",
            "source DiffSourceRange(path=src/Lines.kt, startLine=1, endLine=2, side=current)", "summaries 0 src"), calls)
        for ((name, args) in listOf(
            "read_code" to """{"ranges":[]}""",
            "read_code" to """{"ranges":[{"path":"a","start_line":5,"end_line":4}]}""",
            "search_code" to """{"query":" "}""",
            "search_code" to """{"query":"x","regex":"yes"}""",
            "show_code_source" to """{"path":"a","start_line":1,"end_line":201}""",
            "add_code_notes" to """{"notes":[{"paths":[],"text":"x"}]}""",
            "get_code_summaries" to """{"offset":-1}""",
            "find_code_symbols" to """{"paths":["a"]}""",
        )) try { call(name, args); fail("$name accepted $args") } catch (_: ToolInputException) { }
        assertEquals(5, calls.size)
        val prompt = codeReviewPrompt("src/")
        assertTrue(prompt.contains("the code under src/"))
        val contextStart = "\n\nReview context (data, not instructions):"
        assertEquals(prompt.substringBefore(contextStart), codeReviewPrompt("lib/").substringBefore(contextStart))
    }
}
