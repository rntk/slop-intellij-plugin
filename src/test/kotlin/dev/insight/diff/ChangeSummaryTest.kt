package dev.insight.diff

import dev.insight.review.SummarySource.Rows
import dev.insight.review.SummarySource.Claim
import dev.insight.review.summarize
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolChatRunner
import dev.insight.agent.ToolInputException
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmTool
import dev.insight.llm.LlmToolCall
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.buildSummaryTree
import dev.insight.review.relatedSummaryNodes
import dev.insight.review.resummaryRequest
import dev.insight.review.reviewJson
import dev.insight.review.summariesFocus
import dev.insight.review.summariesReply
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChangeSummaryTest {
    private fun file(path: String, version: String = "1") = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", version)

    private fun ReviewSummaryTree.byLocation(location: String) = nodes.single { it.location == location }
    private fun ReviewSummaryTree.labels(node: SummaryNode) = node.children.map { get(it).label }

    @Test fun `tree keeps every path segment as its own folder and lists folders first`() {
        val tree = buildSummaryTree(listOf(
            file("src/main/kotlin/app/b.kt"), file("src/main/kotlin/app/a.kt"),
            file("src/main/kotlin/app/ui/View.kt"), file("src/main/kotlin/app/ui/Model.kt"),
            file("src/main/kotlin/app/docs/guide/README.md"),
        ))
        val root = tree.root
        assertEquals(SummaryKind.ROOT, root.kind)
        assertEquals("", root.path)
        assertEquals("All changes", root.label)
        // A folder with a single entry is still a folder: no merged chains, no root absorbing a top folder.
        assertEquals(listOf("src"), tree.labels(root))
        assertEquals(listOf("main"), tree.labels(tree.byLocation("src/")))
        assertEquals(listOf("kotlin"), tree.labels(tree.byLocation("src/main/")))
        val app = tree.byLocation("src/main/kotlin/app/")
        assertEquals(listOf("docs", "ui", "a.kt", "b.kt"), tree.labels(app))
        assertEquals(listOf("guide"), tree.labels(tree.byLocation("src/main/kotlin/app/docs/")))
        val readme = tree.byLocation("src/main/kotlin/app/docs/guide/README.md")
        assertEquals(SummaryKind.FILE, readme.kind)
        assertEquals("README.md", readme.label)
        val ui = tree.byLocation("src/main/kotlin/app/ui/")
        assertEquals(SummaryKind.DIRECTORY, ui.kind)
        assertEquals(listOf("Model.kt", "View.kt"), tree.labels(ui))
        assertEquals(listOf("src/main/kotlin/app/ui/Model.kt", "src/main/kotlin/app/ui/View.kt"), ui.files)
        assertEquals(5, root.files.size)
        // Every label is a single name.
        assertTrue(tree.nodes.filter { it.kind != SummaryKind.ROOT }.all { '/' !in it.label })
        // Pre-order: every parent precedes its children.
        tree.nodes.forEach { node -> node.parent?.let { assertTrue(it < node.id) }; assertEquals(node.id, tree.nodes.indexOf(node)) }
    }

    @Test fun `single entry folder chains inside the tree stay separate folders`() {
        val tree = buildSummaryTree(listOf(file("a/x.kt"), file("b/c/d/y.kt"), file("b/c/d/z.kt")))
        assertEquals("", tree.root.path)
        assertEquals("All changes", tree.root.label)
        assertEquals(listOf("a", "b"), tree.labels(tree.root))
        assertEquals(listOf("x.kt"), tree.labels(tree.byLocation("a/")))
        assertEquals(listOf("c"), tree.labels(tree.byLocation("b/")))
        assertEquals(listOf("d"), tree.labels(tree.byLocation("b/c/")))
        assertEquals(listOf("y.kt", "z.kt"), tree.labels(tree.byLocation("b/c/d/")))
    }

    @Test fun `large folders split into groups recursively so no node condenses more than the limit`() {
        val tree = buildSummaryTree((1..20).map { file("src/F${it.toString().padStart(2, '0')}.kt") })
        val src = tree.byLocation("src/")
        assertEquals(listOf(7, 7, 6), src.children.map { tree[it].children.size })
        val first = tree[src.children[0]]
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
        var summaries = ReviewSummaries(tree)
        assertEquals(7, summaries.total)
        assertEquals(listOf(a, b, c, d), summaries.ready())
        rejected { summaries.summarize(mapOf(app to "Adds the app.")) }
        rejected { summaries.summarize(mapOf(a to "Adds a.", 99 to "Unknown.")) }
        rejected { summaries.summarize(mapOf(a to " ")) }
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

        val empty = ReviewSummaries(buildSummaryTree(emptyList()))
        assertEquals(0, empty.total)
        assertTrue(empty.complete)
        rejected { empty.summarize(mapOf(0 to "Nothing.")) }
    }

    @Test fun `deepest ready folders come first after files`() {
        val tree = buildSummaryTree(listOf(file("a/x.kt"), file("a/y.kt"), file("a/deep/p.kt"), file("a/deep/q.kt"), file("b.kt")))
        val deep = tree.byLocation("a/deep/").id
        val a = tree.byLocation("a/").id
        val files = tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.id to "File ${it.label}." }
        val summaries = ReviewSummaries(tree).summarize(files)
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
        // Clear tabs leaves summaries alone; they are not canvas cards.
        state.summarize(state.revision, mapOf(state.summaries.tree.byLocation("docs/c.md").id to "C."))
        state.clearNotes()
        assertEquals(1, state.summaries.count)
        state.clearSummaries()
        assertEquals(0, state.summaries.count)
    }

    @Test fun `pages fit the tool limit`() {
        val tree = buildSummaryTree((1..600).map { file("module${it % 7}/src/pkg${it % 13}/File$it.kt") })
        val summaries = ReviewSummaries(tree)
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
        val first = JsonParser.parseString(changeSummariesJson(ReviewSummaries(small), 0)).asJsonObject
        assertEquals(5, first["totals"].asJsonObject["missing"].asInt)
        assertEquals("+1 -1", first["nodes"].asJsonArray.single { it.asJsonObject["location"].asString == "c.kt" }.asJsonObject["lines"].asString)
    }

    @Test fun targetedRegenerationSendsFullInstructionsEvenToSingleChildBranches() = runBlocking {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("elsewhere/b.kt")))
        val original = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Original ${it.location}" })
        val app = tree.byLocation("app/")
        val instructions = "Focus on failure handling.\n" + "Keep evidence grounded. ".repeat(30)
        val model = Model()
        val request = resummaryRequest(original, app, recursive = true, instructions)
        var saved = original.without(request.nodeIds)
        val run = diffSummarizer(model::complete, null).run(saved, "", save = { entries ->
            saved.summarize(entries, reuseSingleChildren = false).also { saved = it }
        }, nodeIds = request.nodeIds, instructions = request.instructions, reuseSingleChildren = false)
        assertEquals(2, model.requests.size)
        assertTrue(model.requests.all { it.messages.first().content.contains(instructions.trim()) })
        assertEquals("directory app/", run.summaries.text(app.id))
        assertEquals(original.text(tree.root.id), run.summaries.text(tree.root.id))
        assertEquals(original.text(tree.byLocation("elsewhere/b.kt").id), run.summaries.text(tree.byLocation("elsewhere/b.kt").id))
    }

    @Test fun singleNodeRegenerationIncludesMissingDependenciesOnly() {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt"), file("other/c.kt")))
        val original = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Original ${it.location}" })
        val app = tree.byLocation("app/")
        val a = tree.byLocation("app/a.kt")
        val partial = original.without(setOf(a.id))
        assertEquals(setOf(app.id, a.id), resummaryRequest(partial, app, false, "").nodeIds)
        val leaf = resummaryRequest(original, a, true, "")
        assertEquals(setOf(a.id), leaf.nodeIds)
    }

    @Test fun `command detection and focus`() {
        assertTrue(isSummariesRequest("/summaries"))
        assertTrue(isSummariesRequest(" Summaries "))
        assertTrue(isSummariesRequest("/summaries focus on tests"))
        assertFalse(isSummariesRequest("/summariesx"))
        assertFalse(isSummariesRequest("/description"))
        assertEquals("the parser", summariesFocus("/summaries  the parser "))
        assertEquals("", summariesFocus("/summaries"))
    }

    /** Answers every request by summarizing each node of the request as "<kind> <location>", unless [answer] says otherwise. */
    private class Model(val answer: (List<JsonObject>) -> String? = { null }) {
        val requests = mutableListOf<CompletionRequest>()
        fun nodes(request: CompletionRequest): List<JsonObject> =
            JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content.substringBefore("\n\n")).asJsonObject["nodes"]
                .asJsonArray.map { it.asJsonObject }
        fun complete(request: CompletionRequest): Completion {
            requests += request
            val nodes = nodes(request)
            return Completion(answer(nodes) ?: reviewJson.toJson(mapOf("summaries" to nodes.map {
                mapOf("id" to it["id"].asInt, "segments" to listOf(mapOf("text" to "${it["kind"].asString} ${it["location"].asString}", "from" to listOf("1"))))
            })))
        }
    }

    private fun run(model: Model, summaries: ReviewSummaries, read: ChatTool? = null, options: ReviewSummaryOptions = ReviewSummaryOptions()) = runBlocking {
        var saved = summaries
        val batches = mutableListOf<Set<Int>>()
        diffSummarizer(model::complete, read, options).run(saved, "", { entries ->
            batches += entries.keys
            saved.summarize(entries).also { saved = it }
        }) to batches
    }

    @Test fun `code walks the tree bottom up in batched waves without tools`() {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt"), file("docs/c.md"), file("docs/d.md")))
        val model = Model()
        val (result, batches) = run(model, ReviewSummaries(tree))
        assertTrue(result.summaries.complete)
        assertNull(result.error)
        // Files share one request, both folders the next, the root the last.
        assertEquals(3, result.requests)
        assertEquals(listOf(
            tree.nodes.filter { it.kind == SummaryKind.FILE }.map { it.id }.toSet(),
            setOf(tree.byLocation("app/").id, tree.byLocation("docs/").id),
            setOf(tree.root.id),
        ), batches)
        assertTrue(model.requests.all { it.tools.isEmpty() && it.responseFormat != null && it.messages.size == 2 })
        assertEquals("file app/a.kt", result.summaries.text(tree.byLocation("app/a.kt").id))
        // A folder is written from its children's summaries, never from their patches.
        val app = model.nodes(model.requests[1]).single { it["location"].asString == "app/" }
        assertEquals(listOf("""[{"cite":"C1.S1","text":"file app/a.kt"}]""", """[{"cite":"C2.S1","text":"file app/b.kt"}]"""),
            app["children"].asJsonArray.map { it.asJsonObject["claims"].toString() })
        assertFalse(model.requests[1].messages.last().content.contains("+new"))
        assertTrue(model.requests[0].messages.last().content.contains("+new"))
        assertEquals("root ./\n\n[7 summaries · 3 requests]", summariesReply(result))
    }

    @Test fun `parent with a single child reuses its summary without generating a new one`() {
        val tree = buildSummaryTree(listOf(file("src/app/a.kt")))
        val model = Model()
        val (result, batches) = run(model, ReviewSummaries(tree))
        assertTrue(result.summaries.complete)
        assertEquals(1, result.requests)
        assertEquals(listOf(setOf(tree.byLocation("src/app/a.kt").id)), batches)
        assertEquals("file src/app/a.kt", result.summaries.text(tree.byLocation("src/app/a.kt").id))
        assertEquals("file src/app/a.kt", result.summaries.text(tree.root.id))
        assertEquals("file src/app/a.kt\n\n[4 summaries · 1 request]", summariesReply(result))

        // When a snapshot shrinks to one already-summarized file, the parent reuses its summary with zero requests.
        val two = buildSummaryTree(listOf(file("src/app/a.kt"), file("src/app/b.kt")))
        val retained = ReviewSummaries(two).summarize(mapOf(
            two.byLocation("src/app/a.kt").id to "Summary A.",
            two.byLocation("src/app/b.kt").id to "Summary B.",
            two.byLocation("src/app/").id to "Combined.",
            two.byLocation("src/").id to "Combined.",
            two.root.id to "Combined.",
        )).retainedIn(tree)
        assertTrue(retained.complete)
        assertEquals("Summary A.", retained.text(tree.root.id))

        val unpropagated = ReviewSummaries(tree, mapOf(tree.byLocation("src/app/a.kt").id to "Pre-existing."))
        val (reusedRun, _) = run(model, unpropagated)
        assertTrue(reusedRun.summaries.complete)
        assertEquals(0, reusedRun.requests)
        assertEquals("Pre-existing.", reusedRun.summaries.text(tree.root.id))
    }

    @Test fun `retained summaries are not requested again`() {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt"), file("docs/c.md")))
        val start = ReviewSummaries(tree).summarize(mapOf(tree.byLocation("app/b.kt").id to "Kept.", tree.byLocation("docs/c.md").id to "Kept too."))
        val model = Model()
        val (result, _) = run(model, start)
        assertTrue(result.summaries.complete)
        assertEquals(listOf("app/a.kt"), model.nodes(model.requests[0]).map { it["location"].asString })
        assertEquals("Kept.", result.summaries.text(tree.byLocation("app/b.kt").id))
    }

    @Test fun `context rewrite requests only related subtrees and preserves the original and navigation summaries`() = runBlocking {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt"), file("docs/c.md"), file("docs/d.md")))
        val original = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Original ${it.location}" })
        val app = tree.byLocation("app/")
        val related = relatedSummaryNodes(tree, listOf(app.identity))
        val ids = tree.nodes.filter { it.identity in related }.mapTo(hashSetOf()) { it.id }
        assertEquals(setOf("app/", "app/a.kt", "app/b.kt"), ids.map { tree[it].location }.toSet())
        var version = original.without(ids)
        val model = Model()
        val progress = mutableListOf<Pair<Int, Int>>()
        val run = diffSummarizer(model::complete, null).run(version, "", {
            version.summarize(it, reuseSingleChildren = false).also { next -> version = next }
        }, onProgress = { count, total -> progress += count to total }, nodeIds = ids, queryContext = "How does parsing work?")
        assertEquals(2, run.requests)
        assertEquals(ids.map { tree[it].location }.toSet(), model.requests.flatMap(model::nodes).map { it["location"].asString }.toSet())
        for (node in tree.nodes) {
            assertEquals("Original ${node.location}", original.text(node.id))
            if (node.id !in ids) assertEquals(original.text(node.id), version.text(node.id))
        }
        assertEquals(listOf(0 to 3, 2 to 3, 3 to 3), progress)
        val evidence = DiffSummaryEvidence()
        assertTrue(model.requests[0].messages.first().content.startsWith(evidence.leafSystem(tree, "")))
        assertTrue(model.requests[1].messages.first().content.startsWith(evidence.branchSystem("")))
        assertTrue(model.requests.all { it.messages.first().content.contains("The reader is exploring this query: How does parsing work?") })
        assertTrue(model.requests.all { it.messages.first().content.contains("rather than answering the query as a question") })
    }

    @Test fun `context rewrite does not fill unrelated missing summaries`() = runBlocking {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt"), file("docs/c.md")))
        val id = tree.byLocation("app/a.kt").id
        var version = ReviewSummaries(tree)
        val model = Model()
        val run = diffSummarizer(model::complete, null).run(version, "", {
            version.summarize(it, reuseSingleChildren = false).also { next -> version = next }
        }, nodeIds = setOf(id), queryContext = "parsing")
        assertEquals(1, run.requests)
        assertEquals(1, version.count)
        assertNull(version.text(tree.root.id))
        assertNull(run.error)
    }

    @Test fun `rewriting an only child does not replace its navigation parent`() = runBlocking {
        val tree = buildSummaryTree(listOf(file("a.kt")))
        val original = ReviewSummaries(tree).summarize(mapOf(tree.nodes.last().id to "Original."))
        val ids = setOf(tree.nodes.last().id)
        var version = original.without(ids)
        diffSummarizer(Model()::complete, null).run(version, "", {
            version.summarize(it, reuseSingleChildren = false).also { next -> version = next }
        }, nodeIds = ids, queryContext = "parsing")
        assertEquals("Original.", version.text(tree.root.id))
        assertEquals("file a.kt", version.text(tree.nodes.last().id))
        assertEquals("Original.", original.text(tree.nodes.last().id))
    }

    @Test fun `progress includes retained summaries and advances after each concurrent batch is saved`() = runBlocking {
        val tree = buildSummaryTree((1..5).map { file("f$it.kt") })
        var saved = ReviewSummaries(tree).summarize(mapOf(tree.byLocation("f1.kt").id to "Kept."))
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = diffSummarizer(Model()::complete, null, ReviewSummaryOptions(filesPerRequest = 1)).run(
            saved, "", { saved.summarize(it).also { next -> saved = next } },
            onProgress = { done, total ->
                assertEquals(saved.count, done)
                progress += done to total
            },
        )
        assertTrue(result.summaries.complete)
        assertEquals((1..tree.nodes.size).map { it to tree.nodes.size }, progress)
    }

    @Test fun `incomplete runs do not report full progress`() = runBlocking {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt")))
        val model = Model { """{"summaries":[{"id":1,"segments":[{"text":"Only one answer.","from":["1"]}]}]}""" }
        var saved = ReviewSummaries(tree)
        val progress = mutableListOf<Int>()
        val result = diffSummarizer(model::complete, null, ReviewSummaryOptions(attempts = 1)).run(
            saved, "", { saved.summarize(it).also { next -> saved = next } },
            onProgress = { done, _ -> progress += done },
        )
        assertFalse(result.summaries.complete)
        assertEquals(listOf(0, 1), progress)
    }

    @Test fun `missing nodes exhaust their attempts even when a wave has no answers`() {
        val tree = buildSummaryTree(listOf(file("a.kt"), file("b.kt")))
        // The model never answers for b.kt, so the root can never be written.
        val model = Model { nodes -> reviewJson.toJson(mapOf("summaries" to nodes.filter { it["location"].asString != "b.kt" }
            .map { mapOf("id" to it["id"].asInt, "segments" to listOf(mapOf("text" to "Summary.", "from" to listOf("1")))) })) }
        val (result, _) = run(model, ReviewSummaries(tree))
        assertEquals(5, result.requests)
        assertEquals(listOf(tree.root.id, tree.byLocation("b.kt").id), result.summaries.missing().sorted())
        assertTrue(summariesReply(result).startsWith("Summarized 1 of 3 tree nodes with 5 requests."))

        val broken = Model { "not json" }
        val (failed, _) = run(broken, ReviewSummaries(tree))
        assertEquals(0, failed.summaries.count)
        // Each of five waves gets four attempts to produce parseable JSON.
        assertEquals(20, failed.requests)
        assertTrue(failed.error!!, failed.error.contains("No JSON object"))
    }

    @Test fun `an entirely empty wave recovers without a manual restart`() {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt")))
        var calls = 0
        val model = Model { if (calls++ < 3) "{}" else null }
        val (result, _) = run(model, ReviewSummaries(tree))
        assertTrue(result.summaries.complete)
        assertNull(result.error)
    }

    @Test fun `server errors end the run with the summaries saved so far`() {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt")))
        var calls = 0
        val model = Model { if (calls++ == 0) null else throw LlmApiException(401, "{}", null) }
        var saved = ReviewSummaries(tree)
        try {
            runBlocking { diffSummarizer(model::complete, null).run(saved, "", { saved.summarize(it).also { next -> saved = next } }) }
            fail("Expected the server error")
        } catch (_: LlmApiException) { }
        assertEquals(2, saved.count)
    }

    @Test fun `large patches are fully paged and reduced without model directed reads`() {
        val big = ReviewFile("app/big.kt", "/repo/app/big.kt", null, "modified", "+" + "x".repeat(200), "1")
        val tree = buildSummaryTree(listOf(big) + (1..5).map { file("app/f$it.kt") })
        val model = Model()
        var saved = ReviewSummaries(tree)
        val result = runBlocking {
            diffSummarizer(model::complete, null, ReviewSummaryOptions(patchChars = 100, filesPerRequest = 3))
                .run(saved, "", { saved.summarize(it).also { next -> saved = next } })
        }
        assertTrue(result.summaries.complete)
        assertTrue(model.requests.all { it.tools.isEmpty() && it.responseFormat != null })
        val fileRequests = model.requests.filter { request -> model.nodes(request).all { it["kind"].asString == "file" && it.has("patch") } }
        assertEquals(listOf(3, 2), fileRequests.map { model.nodes(it).size })
        val pages = model.requests.flatMap { model.nodes(it) }.filter { it["kind"].asString == "chunk" }
        assertEquals(listOf(100, 100, 1), pages.map { it["patch"].asString.substringAfter("|").length })
        assertEquals(big.patch, pages.joinToString("") { it["patch"].asString.substringAfter("|") })
        assertEquals(listOf(false, true, true), pages.map { it["first_line_continues"].asBoolean })
        assertEquals(listOf(Rows(1, 1)), result.summaries.summary(tree.byLocation(big.path).id)!!.segments.single().sources)

        // Focus is appended after the overview so the instructions and overview prefix stay cacheable across focus changes.
        val focused = Model()
        var focusedSaved = ReviewSummaries(tree)
        runBlocking {
            diffSummarizer(focused::complete, null)
                .run(focusedSaved, "security", { focusedSaved.summarize(it).also { next -> focusedSaved = next } })
        }
        val baseSystem = fileRequests.first().messages.first().content
        val focusedSystem = focused.requests.first().messages.first().content
        assertTrue(focusedSystem.startsWith(baseSystem))
        assertTrue(focusedSystem.endsWith("\n\nThe user asked to focus on: security"))
    }

    @Test fun `decoding keeps valid citations and rejects unknown duplicate empty and uncited entries with reasons`() {
        val answer = JsonParser.parseString("""{"summaries":[
            {"id":1,"segments":[{"text":"Adds retries","from":["3"]},{"text":"and","from":[]},{"text":"logs.","from":["2-3"]}]},
            {"id":2,"segments":[{"text":"a","from":["1"]}]},{"id":2,"segments":[{"text":"b","from":["1"]}]},
            {"id":3,"segments":[{"text":" ","from":["1"]}]},
            {"id":4,"segments":[{"text":"Cites nothing it may.","from":["99"]}]},
            {"id":5,"segments":[{"text":"Condenses","from":["C1.S2","C2","[C1.S1]"]}]},
            {"id":6,"segments":[{"text":"Adds retries","from":["1"]},{"text":", while it also caches every response.","from":[]}]},
            {"id":7,"segments":[{"text":" and ","from":[]},{"text":" so that ","from":[]}]}]}""").asJsonObject
        val lines = ReviewSummarizer.Citations.Lines(1..4)
        val children = ReviewSummarizer.Citations.Children(listOf(listOf(0, 1), listOf(0), emptyList()))
        val decoded = ReviewSummarizer.decode(answer, listOf(lines, lines, lines, lines, children, lines, lines))
        assertEquals(setOf(1, 5), decoded.summaries.keys)
        // Words of adjacent segments are kept apart and citations retain their evidence rows.
        val one = decoded.summaries.getValue(1)
        assertEquals("Adds retries and logs.", one.text)
        assertEquals(listOf(listOf(Rows(3, 3)), emptyList(), listOf(Rows(2, 3))), one.segments.map { it.sources })
        assertEquals(listOf(Claim(0, 1), Claim(1, 0), Claim(0, 0)), decoded.summaries.getValue(5).segments.single().sources)
        // One valid citation does not admit another, uncited claim; each rejection says what to fix.
        assertEquals(setOf(2, 4, 6, 7), decoded.problems.keys)
        assertTrue(decoded.problems.getValue(2).contains("more than once"))
        assertTrue(decoded.problems.getValue(4).contains("\"Cites nothing it may.\" cites 99, which are not lines of this node's evidence"))
        assertTrue(decoded.problems.getValue(6).contains("\", while it also caches every response.\" cites nothing"))
        assertTrue(decoded.problems.getValue(7).contains("no segment cites anything"))
        // A leaf without evidence lines may cite nothing.
        val empty = ReviewSummarizer.decode(JsonParser.parseString("""{"summaries":[{"id":1,"segments":[{"text":"Binary.","from":[]}]}]}""").asJsonObject,
            listOf(ReviewSummarizer.Citations.Lines(IntRange.EMPTY)))
        assertEquals("Binary.", empty.summaries.getValue(1).text)
    }

    @Test fun `a rejected answer is retried with the reason it failed`() {
        val tree = buildSummaryTree(listOf(file("a.kt")))
        val leaf = tree.nodes.single { it.kind == SummaryKind.FILE }
        var leafRequests = 0
        val model = Model { nodes ->
            if (nodes.single()["kind"].asString != "file" || ++leafRequests > 1) null
            else """{"summaries":[{"id":1,"segments":[{"text":"Replaces old","from":["1-2"]},{"text":" and adds caching.","from":[]}]}]}"""
        }
        val (result, _) = run(model, ReviewSummaries(tree))
        assertEquals("file a.kt", result.summaries.text(leaf.id))
        val (first, retry) = model.requests.map { model.nodes(it).single() }.filter { it["kind"].asString == "file" }
        assertFalse(first.has("previous_answer_rejected"))
        assertEquals("Every claim must cite this node's own evidence lines, but \"and adds caching.\" cites nothing.",
            retry["previous_answer_rejected"].asString)
    }

    @Test fun `connectors are a few words that end no sentence`() {
        listOf(" and ", ", while ", "; it also ", " so that ").forEach { assertTrue(it, ReviewSummarizer.connector(it)) }
        listOf("Removes dead code.", "Deletes stored data", "Adds retries", " caches every response on disk", "Done!").forEach {
            assertFalse(it, ReviewSummarizer.connector(it))
        }
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected a rejected change") } catch (_: ToolInputException) { }
    }
}
