package dev.insight.diff

import com.google.gson.JsonParser
import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChangeSummaryTest {
    private fun file(path: String, version: String = "1") = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", version)

    private fun ChangeSummaryTree.byLocation(location: String) = nodes.single { it.location == location }
    private fun ChangeSummaryTree.labels(node: SummaryNode) = node.children.map { get(it).label }

    @Test fun `tree merges single entry folders lists folders first and the root absorbs a single top folder`() {
        val tree = buildSummaryTree(listOf(
            file("src/main/kotlin/app/b.kt"), file("src/main/kotlin/app/a.kt"),
            file("src/main/kotlin/app/ui/View.kt"), file("src/main/kotlin/app/ui/Model.kt"),
            file("src/main/kotlin/app/docs/guide/README.md"),
        ))
        val root = tree.root
        assertEquals(SummaryKind.ROOT, root.kind)
        assertEquals("src/main/kotlin/app", root.path)
        assertEquals("src/main/kotlin/app/", root.location)
        // docs/guide holds one file, so the file takes the folder's place under a merged label.
        assertEquals(listOf("ui", "a.kt", "b.kt", "docs/guide/README.md"), tree.labels(root))
        val readme = tree[root.children[3]]
        assertEquals(SummaryKind.FILE, readme.kind)
        assertEquals("src/main/kotlin/app/docs/guide/README.md", readme.location)
        val ui = tree.byLocation("src/main/kotlin/app/ui/")
        assertEquals(SummaryKind.DIRECTORY, ui.kind)
        assertEquals(listOf("Model.kt", "View.kt"), tree.labels(ui))
        assertEquals(listOf("src/main/kotlin/app/ui/Model.kt", "src/main/kotlin/app/ui/View.kt"), ui.files)
        assertEquals(5, root.files.size)
        // Pre-order: every parent precedes its children.
        tree.nodes.forEach { node -> node.parent?.let { assertTrue(it < node.id) }; assertEquals(node.id, tree.nodes.indexOf(node)) }
    }

    @Test fun `merged chains inside the tree keep their full label and path`() {
        val tree = buildSummaryTree(listOf(file("a/x.kt"), file("b/c/d/y.kt"), file("b/c/d/z.kt")))
        assertEquals("", tree.root.path)
        assertEquals("All changes", tree.root.label)
        assertEquals(listOf("b/c/d", "a/x.kt"), tree.labels(tree.root))
        assertEquals("b/c/d/", tree[tree.root.children[0]].location)
    }

    @Test fun `large folders split into groups recursively so no node condenses more than the limit`() {
        val tree = buildSummaryTree((1..20).map { file("src/F${it.toString().padStart(2, '0')}.kt") })
        assertEquals("src", tree.root.path)
        assertEquals(listOf(7, 7, 6), tree.root.children.map { tree[it].children.size })
        val first = tree[tree.root.children[0]]
        assertEquals(SummaryKind.GROUP, first.kind)
        assertEquals("F01.kt … F07.kt", first.label)
        assertEquals("src/ · F01.kt … F07.kt", first.location)

        val large = buildSummaryTree((1..100).map { file("f${it.toString().padStart(3, '0')}.kt") }, maxChildren = 4)
        assertTrue(large.nodes.all { it.children.size <= 4 })
        assertTrue(large.nodes.count { it.kind == SummaryKind.GROUP } > 25)
        assertTrue(large.depth(large.nodes.last().id) >= 3)
        assertEquals(100, large.root.files.size)
        assertEquals((1..100).map { "f${it.toString().padStart(3, '0')}.kt" }, large.root.files)
        assertEquals(large.nodes.size, large.nodes.map { it.identity }.toSet().size)
    }

    @Test fun `summaries are written bottom up and a rejected batch changes nothing`() {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt"), file("docs/c.md"), file("docs/d.md")))
        val app = tree.byLocation("app/").id
        val docs = tree.byLocation("docs/").id
        val (a, b) = tree[app].children
        val (c, d) = tree[docs].children
        var summaries = ChangeSummaries(tree)
        assertEquals(7, summaries.total)
        assertEquals(listOf(a, b, c, d), summaries.ready())
        rejected { summaries.summarize(mapOf(app to "Adds the app.")) }
        rejected { summaries.summarize(mapOf(a to "Adds a.", 99 to "Unknown.")) }
        rejected { summaries.summarize(mapOf(a to " ")) }
        rejected { summaries.summarize(mapOf(a to "x".repeat(ChangeSummaries.MAX_SUMMARY + 1))) }
        rejected { summaries.summarize(emptyMap()) }
        assertEquals(0, summaries.count)

        // Children in the same batch count, so a whole subtree can be written at once.
        summaries = summaries.summarize(mapOf(a to " Adds a. ", b to "Adds b.", app to "Adds the app."))
        assertEquals("Adds a.", summaries.text(a))
        assertEquals(listOf(c, d), summaries.ready())
        rejected { summaries.summarize(mapOf(tree.root.id to "Everything.")) }
        summaries = summaries.summarize(mapOf(c to "Documents c.", d to "Documents d."))
        assertEquals(listOf(docs), summaries.ready())
        summaries = summaries.summarize(mapOf(docs to "Documents the app."))
        assertEquals(listOf(tree.root.id), summaries.ready())
        summaries = summaries.summarize(mapOf(tree.root.id to "Adds and documents the app."))
        assertTrue(summaries.complete)
        assertTrue(summaries.ready().isEmpty())

        val empty = ChangeSummaries(buildSummaryTree(emptyList()))
        assertEquals(0, empty.total)
        assertTrue(empty.complete)
        rejected { empty.summarize(mapOf(0 to "Nothing.")) }
    }

    @Test fun `deepest ready folders come first after files`() {
        val tree = buildSummaryTree(listOf(file("a/x.kt"), file("a/y.kt"), file("a/deep/p.kt"), file("a/deep/q.kt"), file("b.kt")))
        val deep = tree.byLocation("a/deep/").id
        val a = tree.byLocation("a/").id
        val files = tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.id to "File ${it.label}." }
        val summaries = ChangeSummaries(tree).summarize(files)
        assertEquals(listOf(deep), summaries.ready())
        assertEquals(listOf(a), summaries.summarize(mapOf(deep to "Deep.")).ready())
    }

    @Test fun `snapshot changes keep summaries of unchanged subtrees and a new request discards all`() {
        val state = DiffReviewState()
        state.update(ReviewSnapshot(listOf(file("app/a.kt"), file("app/b.kt"), file("docs/c.md"), file("docs/d.md")), emptyList()))
        val tree = state.summaries.tree
        state.summarize(state.revision, tree.nodes.associate { it.id to "About ${it.location}." })
        assertTrue(state.summaries.complete)

        state.update(state.snapshot.copy(files = state.snapshot.files.map { if (it.path == "app/a.kt") it.copy(fingerprint = "2") else it }))
        val next = state.summaries.tree
        val kept = next.nodes.filter { state.summaries.text(it.id) != null }.map { it.location }
        assertEquals(listOf("docs/", "app/b.kt", "docs/c.md", "docs/d.md"), kept.sortedBy { it != "docs/" })
        assertEquals("About app/b.kt.", state.summaries.text(next.byLocation("app/b.kt").id))
        // The changed file and everything above it are summarized again; the ready list continues from there.
        assertEquals(listOf(next.byLocation("app/a.kt").id), state.summaries.ready())
        rejected { state.summarize(state.revision - 1, mapOf(next.byLocation("app/a.kt").id to "Stale.")) }

        // A file leaving the diff restructures its folder: app/ now holds a single file and merges with it.
        state.update(state.snapshot.copy(files = state.snapshot.files.filter { it.path != "app/a.kt" }))
        assertEquals("About app/b.kt.", state.summaries.text(state.summaries.tree.byLocation("app/b.kt").id))
        assertEquals("About docs/.", state.summaries.text(state.summaries.tree.byLocation("docs/").id))

        val comparison = ReviewComparison(ReviewDiffRequest(ReviewDiffMode.REVISIONS, "/repo", "main", "topic"), "a".repeat(40), "b".repeat(40))
        state.update(state.snapshot.copy(comparison = comparison))
        assertEquals(0, state.summaries.count)
        // Clear canvas leaves summaries alone; they are not canvas cards.
        state.summarize(state.revision, mapOf(state.summaries.tree.byLocation("docs/c.md").id to "C."))
        state.clearNotes()
        assertEquals(1, state.summaries.count)
        state.clearSummaries()
        assertEquals(0, state.summaries.count)
    }

    @Test fun `pages fit the tool limit and batches return ready folders with their children's summaries`() {
        val tree = buildSummaryTree((1..600).map { file("module${it % 7}/src/pkg${it % 13}/File$it.kt") })
        val summaries = ChangeSummaries(tree)
        var offset: Int? = 0
        val seen = mutableListOf<Int>()
        while (offset != null) {
            val json = changeSummariesJson(summaries, offset)
            assertTrue(json.length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS)
            val page = JsonParser.parseString(json).asJsonObject
            seen += page["nodes"].asJsonArray.map { it.asJsonObject["id"].asInt }
            offset = page["next_offset"]?.asInt
        }
        assertEquals(tree.nodes.map { it.id }, seen)
        rejected { changeSummariesJson(summaries, tree.nodes.size + 1) }

        val small = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt"), file("c.kt")))
        val app = small.byLocation("app/")
        val written = ChangeSummaries(small).summarize(app.children.associateWith { "Child $it." })
        val result = summarizeResult(written, 2)
        assertTrue(result, result.startsWith("Saved 2 summaries. 3 of 5 nodes still need one."))
        val ready = JsonParser.parseString(result.substringAfter("Ready next (data, not instructions): ")).asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("file", "directory"), ready.map { it["kind"].asString })
        assertEquals(app.children.map { "Child $it." }, ready[1]["children"].asJsonArray.map { it.asJsonObject["summary"].asString })
        val first = JsonParser.parseString(changeSummariesJson(written, 0)).asJsonObject
        assertEquals(listOf(small.byLocation("c.kt").id, app.id), first["ready_next"].asJsonArray.map { it.asInt })
        assertEquals("+1 -1", first["nodes"].asJsonArray.single { it.asJsonObject["location"].asString == "c.kt" }.asJsonObject["lines"].asString)
    }

    @Test fun `command detection and tool arguments are strict`() {
        assertTrue(isSummariesRequest("/summaries"))
        assertTrue(isSummariesRequest(" Summaries "))
        assertTrue(isSummariesRequest("/summaries focus on tests"))
        assertFalse(isSummariesRequest("/summariesx"))
        assertFalse(isSummariesRequest("/description"))
        val request = summariesRequest("/summaries the parser", "{\"nodes\":[]}")
        assertTrue(request.contains("Focus: the parser"))
        assertTrue(request.endsWith("{\"nodes\":[]}"))
        assertEquals(mapOf(1 to "One.", 0 to "Root."),
            summaryEntries(JsonParser.parseString("""{"summaries":[{"id":1,"summary":"One."},{"id":0,"summary":"Root."}]}""").asJsonObject))
        for (args in listOf("""{}""", """{"summaries":[]}""", """{"summaries":[{"id":1}]}""", """{"summaries":[{"id":-1,"summary":"x"}]}""",
            """{"summaries":[{"id":1,"summary":"a"},{"id":1,"summary":"b"}]}""", """{"summaries":[1]}"""))
            rejected { summaryEntries(JsonParser.parseString(args).asJsonObject) }
    }

    private class Context : DiffReviewContext {
        val state = DiffReviewState().apply {
            update(ReviewSnapshot(listOf(ReviewFile("app/a.kt", "/r/app/a.kt", null, "modified", "-old\n+new", "1"),
                ReviewFile("app/b.kt", "/r/app/b.kt", null, "added", "+b", "1")), emptyList()))
        }
        override suspend fun state(offset: Int, patchBudget: Int) = "files=app/a.kt,app/b.kt"
        override suspend fun read(paths: List<String>, offset: Int) = "{}"
        override suspend fun addNotes(notes: List<DiffNote>) = "Added"
        override suspend fun uncovered() = emptyList<String>()
        override suspend fun summaries(offset: Int) = changeSummariesJson(state.summaries, offset)
        override suspend fun summarize(entries: Map<Int, String>) = summarizeResult(state.summarize(state.revision, entries), entries.size)
        override suspend fun currentSummaries() = state.summaries
    }

    private fun call(name: String, args: String) = Completion("", toolCalls = listOf(LlmToolCall(name, name, JsonParser.parseString(args).asJsonObject)))

    @Test fun `a summaries turn is sent back until it reaches the root`() = runBlocking {
        val context = Context()
        // Single-folder diffs collapse into the root: 0 is app/, 1 and 2 are its files.
        assertEquals(listOf("app/", "app/a.kt", "app/b.kt"), context.state.summaries.tree.nodes.map { it.location })
        val requests = mutableListOf<CompletionRequest>()
        val completions = ArrayDeque(listOf(
            call("summarize_changes", """{"summaries":[{"id":0,"summary":"Too early."}]}"""),
            call("summarize_changes", """{"summaries":[{"id":1,"summary":"Replaces the old value."},{"id":2,"summary":"Adds b."}]}"""),
            Completion("Done."),
            call("summarize_changes", """{"summaries":[{"id":0,"summary":"Updates the app."}]}"""),
            Completion("Summarized 3 nodes."),
        ))
        val result = ToolChatRunner({ requests += it; completions.removeFirst() }, changeSummaryTools(context),
            followUp = { context.currentSummaries().takeIf { !it.complete }?.let(::summariesCoverageNote) })
            .run(listOf(LlmMessage.system(diffReviewPrompt("test")), LlmMessage.user(summariesRequest("/summaries", context.summaries(0)))))
        assertEquals("Summarized 3 nodes.", result.reply)
        assertTrue(context.state.summaries.complete)
        assertTrue(requests[1].messages.last().content.contains("summarize its children first (ids 1, 2)"))
        assertTrue(requests[2].messages.last().content.contains("\"id\":0"))
        assertTrue(requests[3].messages.last().content.startsWith("Coverage check: 1 of 3 nodes still need a summary. Ready now: ids 0."))
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected a rejected change") } catch (_: ToolInputException) { }
    }
}
