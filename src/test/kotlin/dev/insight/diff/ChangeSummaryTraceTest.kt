package dev.insight.diff

import com.google.gson.JsonObject
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionJson
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChangeSummaryTraceTest {
    private fun file(path: String, version: String = "1") = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", version)

    /** Records every request and answers each Noul from the request's state and the question's instructions. */
    private class FakeDecisions(val answer: (state: JsonObject, instructions: JsonObject) -> Double) {
        val requests = mutableListOf<DecisionRequest>()
        val evaluate: suspend (DecisionRequest) -> DecisionResponse = { request ->
            requests += request
            response(request)
        }

        fun response(request: DecisionRequest) = DecisionResponse("jev-test", request.questions.mapValues { (_, question) ->
            DecisionAnswer.Noul(answer(request.state.asJsonObject, (question as DecisionQuestion.Noul).instructions.asJsonObject))
        }, DecisionUsage(1, 1))

        fun contextRequests() = requests.filter { it.state.asJsonObject.has("pointed_word") }
        fun matchRequests() = requests.filter { it.state.asJsonObject.has("query") }
        fun askedWords() = contextRequests().flatMap { request ->
            request.questions.values.map { (it.instructions.asJsonObject["candidate_word"].asJsonObject)["word"].asString }
        }
        fun askedLocations() = matchRequests().flatMap { request ->
            request.questions.values.map { (it.instructions.asJsonObject["candidate"].asJsonObject)["location"].asString }
        }
    }

    private val example = "deleted views: canvas, hierarchy, summaries"
    private fun word(state: JsonObject, instructions: JsonObject) = instructions["candidate_word"]?.asJsonObject?.get("word")?.asString
    private fun location(instructions: JsonObject) = instructions["candidate"]?.asJsonObject?.get("location")?.asString

    @Test fun `split keeps identifiers whole excludes punctuation and records offsets`() {
        val text = "Adds `ChangeSummaryModel.kt` and read_diff_files to /summaries, the user's view."
        val words = SummaryWord.split(text)
        assertEquals(listOf("Adds", "ChangeSummaryModel.kt", "and", "read_diff_files", "to", "summaries", "the", "user's", "view"), words.map { it.text })
        words.forEachIndexed { index, word ->
            assertEquals(index, word.index)
            assertEquals(word.text, text.substring(word.start, word.end))
        }
        assertEquals(listOf(true, true, false, true, false, true, false, true, true), words.map { it.meaningful })
        assertFalse(SummaryWord(0, 0, 3, "The").meaningful)
        assertTrue(SummaryWord.split("…, — !").isEmpty())
    }

    @Test fun `numbered marks every word and query narrows to the context`() {
        val words = SummaryWord.split(example)
        assertEquals("deleted[1] views[2]: canvas[3], hierarchy[4], summaries[5]", SummaryTracer.numbered(example, words))
        assertEquals("deleted views: hierarchy", SummaryTracer.query(example, listOf(words[0], words[1], words[3])))
        // Adjacent words keep the text between them; a skipped run takes the separator after the last kept word.
        assertEquals("views: canvas, hierarchy", SummaryTracer.query(example, listOf(words[1], words[2], words[3])))
        assertEquals("deleted summaries", SummaryTracer.query(example, listOf(words[0], words[4])))
        assertEquals("hierarchy", SummaryTracer.query(example, listOf(words[3])))
    }

    @Test fun `context keeps the pointed word and its own phrase but not its siblings`() = runBlocking {
        val fake = FakeDecisions { state, instructions -> if (word(state, instructions) in setOf("deleted", "views")) 0.9 else 0.1 }
        val tracer = SummaryTracer(fake.evaluate)
        val words = SummaryWord.split(example)
        val context = tracer.context(example, words, words[3])
        assertEquals(listOf("deleted", "views", "hierarchy"), context.map { it.text })
        assertEquals("deleted views: hierarchy", SummaryTracer.query(example, context))
        assertEquals(1, fake.requests.size)
        assertEquals(setOf("deleted", "views", "canvas", "summaries"), fake.askedWords().toSet())
        val state = fake.requests.single().state.asJsonObject
        assertEquals(example, state["summary"].asString)
        assertEquals(4, state["pointed_word"].asJsonObject["number"].asInt)
        assertEquals("hierarchy", state["pointed_word"].asJsonObject["word"].asString)

        // The same word of the same summary is not asked again.
        assertEquals(context, tracer.context(example, words, words[3]))
        assertEquals(1, fake.requests.size)
        // The pointed word is kept even when every other word is rejected.
        val none = SummaryTracer(FakeDecisions { _, _ -> 0.0 }.evaluate)
        assertEquals(listOf("hierarchy"), none.context(example, words, words[3]).map { it.text })
    }

    @Test fun `context reaches other phrases about the pointed thing and the query keeps them`() = runBlocking {
        val summary = "Introduces custom instruction handling across UI, core pipeline, background orchestrator, and runtime contracts, " +
            "adding UI controls, normalization, caching, and payload updates, with comprehensive tests."
        val wanted = setOf("Introduces", "custom", "instruction", "handling", "across", "adding", "UI", "controls")
        val fake = FakeDecisions { state, instructions -> if (word(state, instructions) in wanted) 0.9 else 0.1 }
        val words = SummaryWord.split(summary)
        val pointed = words.first { it.text == "UI" }
        val context = SummaryTracer(fake.evaluate).context(summary, words, pointed)
        assertEquals("Introduces custom instruction handling across UI, adding UI controls", SummaryTracer.query(summary, context))
        // The later "UI" is pointed out to the model as another mention of the same word.
        val mentions = fake.requests.single().state.asJsonObject["pointed_word"].asJsonObject["other_mentions"].asJsonArray
        assertEquals(listOf(words.last { it.text == "UI" }.index + 1), mentions.map { it.asInt })
    }

    @Test fun `query keeps filler words between kept words`() {
        val summary = "deleted the views of canvas and the hierarchy"
        val words = SummaryWord.split(summary)
        assertEquals("deleted the views of canvas", SummaryTracer.query(summary, words.filter { it.text in setOf("deleted", "views", "canvas") }))
        assertEquals("deleted the views hierarchy", SummaryTracer.query(summary, words.filter { it.text in setOf("deleted", "views", "hierarchy") }))
    }

    @Test fun `context never asks about stop words`() = runBlocking {
        val summary = "deleted the views of canvas and the hierarchy"
        val fake = FakeDecisions { _, _ -> 0.9 }
        val words = SummaryWord.split(summary)
        val context = SummaryTracer(fake.evaluate).context(summary, words, words.single { it.text == "hierarchy" })
        assertEquals(setOf("deleted", "views", "canvas"), fake.askedWords().toSet())
        assertEquals(listOf("deleted", "views", "canvas", "hierarchy"), context.map { it.text })
    }

    @Test fun `context questions are chunked into parallel requests`() = runBlocking {
        val bothStarted = CompletableDeferred<Unit>()
        val requests = mutableListOf<DecisionRequest>()
        val fake = FakeDecisions { state, instructions -> if (word(state, instructions) == "views") 0.9 else 0.1 }
        val tracer = SummaryTracer({ request ->
            requests += request
            // A sequential caller would never start the second request, and this would time out.
            if (requests.size == 2) bothStarted.complete(Unit) else bothStarted.await()
            fake.response(request)
        }, maxQuestions = 3)
        val words = SummaryWord.split(example)
        val context = withTimeout(5_000) { tracer.context(example, words, words[3]) }
        assertEquals(listOf(3, 1), requests.map { it.questions.size })
        assertEquals(listOf("views", "hierarchy"), context.map { it.text })
    }

    @Test fun `a long summary's context requests stay within the byte budget and carry the rules once`() = runBlocking {
        // The summary whose 32-question request exceeded the server's batch when every question repeated the rules.
        val summary = "Implemented a new ChangeSummaryTrace feature that imports JSON arrays, expands context to include all " +
            "mentions of the target word, adds a focus parameter for ranking, updates state JSON, and includes comprehensive " +
            "tests. Updated the diff‑review guide to explain the interactive word‑tracing workflow and reference the new components."
        val fake = FakeDecisions { _, _ -> 0.1 }
        val words = SummaryWord.split(summary)
        SummaryTracer(fake.evaluate).context(summary, words, words.single { it.text == "updates" })
        assertEquals(words.count { it.meaningful } - 1, fake.askedWords().size)
        fake.requests.forEach { request ->
            assertTrue(DecisionJson.encode(request).toByteArray().size <= SummaryTracer.MAX_REQUEST_BYTES)
            assertTrue(request.state.asJsonObject.has("query_rules"))
            request.questions.values.forEach { assertFalse((it as DecisionQuestion.Noul).instructions.asJsonObject.has("examples")) }
        }
    }

    @Test fun `batches split by bytes as well as by count and keep the question order`() {
        val state = JsonObject().apply { addProperty("summary", "x".repeat(200)) }
        fun question(n: Int) = DecisionQuestion.Noul(JsonObject().apply { addProperty("question", "q$n " + "y".repeat(100)) })
        val questions = (0 until 10).map(::question)
        val tracer = SummaryTracer({ error("unused") }, maxRequestBytes = 900)
        val batches = tracer.batches(state, questions)
        assertTrue(batches.size > 1)
        batches.forEach { assertTrue(DecisionJson.encode(it).toByteArray().size <= 900) }
        assertEquals(questions, batches.flatMap { it.questions.values })
        // A question larger than the budget on its own is still asked, alone.
        val huge = DecisionQuestion.Noul(JsonObject().apply { addProperty("question", "z".repeat(2000)) })
        assertEquals(listOf(1, 1), tracer.batches(state, listOf(questions[0], huge)).map { it.questions.size })
        assertEquals(listOf(4, 4, 2), SummaryTracer({ error("unused") }, maxQuestions = 4).batches(state, questions).map { it.questions.size })
    }

    @Test fun `context of a summary with at most one other meaningful word needs no request`() = runBlocking {
        val fake = FakeDecisions { _, _ -> 0.0 }
        val summary = "the deleted hierarchy"
        val words = SummaryWord.split(summary)
        assertEquals(listOf("deleted", "hierarchy"), SummaryTracer(fake.evaluate).context(summary, words, words[2]).map { it.text })
        assertTrue(fake.requests.isEmpty())
    }

    private fun node(id: Int, kind: SummaryKind, path: String, parent: Int?, children: List<Int>) =
        SummaryNode(id, kind, path, path.substringAfterLast('/'), parent, children, emptyList(), "key:$id", "${kind.id}:$path")

    @Test fun `candidates reach two levels below, skip repeated single children and unsummarized nodes`() {
        val tree = ChangeSummaryTree(listOf(
            node(0, SummaryKind.ROOT, "", null, listOf(1)),
            node(1, SummaryKind.DIRECTORY, "a", 0, listOf(2, 3)),
            node(2, SummaryKind.FILE, "a/f.kt", 1, emptyList()),
            node(3, SummaryKind.DIRECTORY, "a/b", 1, listOf(4, 5, 7)),
            node(4, SummaryKind.FILE, "a/b/g.kt", 3, emptyList()),
            node(5, SummaryKind.DIRECTORY, "a/b/c", 3, listOf(6)),
            node(6, SummaryKind.FILE, "a/b/c/h.kt", 5, emptyList()),
            node(7, SummaryKind.FILE, "a/b/i.kt", 3, emptyList()),
        ))
        // Node 1 repeats the root's summary; node 7 has none yet.
        val summaries = ChangeSummaries(tree, mapOf(0 to "root", 1 to "root", 2 to "f", 3 to "b", 4 to "g", 5 to "c", 6 to "h"))
        val tracer = SummaryTracer({ error("No request expected") })
        assertEquals(listOf(2 to 1, 3 to 1, 4 to 2, 5 to 2), tracer.candidates(summaries, 0).map { (node, level) -> node.id to level })
        assertEquals(listOf(4 to 1, 5 to 1, 6 to 2), tracer.candidates(summaries, 3).map { (node, level) -> node.id to level })
        assertTrue(tracer.candidates(summaries, 6).isEmpty())
        // A single child with its own summary is a level of its own.
        val distinct = ChangeSummaries(tree, mapOf(0 to "root", 1 to "a", 2 to "f", 3 to "b", 4 to "g"))
        assertEquals(listOf(1 to 1, 2 to 2, 3 to 2), tracer.candidates(distinct, 0).map { (node, level) -> node.id to level })
        assertEquals(listOf(1 to 1, 2 to 2, 3 to 2, 4 to 3), SummaryTracer({ error("unused") }, levels = 3)
            .candidates(distinct, 0).map { (node, level) -> node.id to level })
    }

    private fun viewsTree(): ChangeSummaries {
        val tree = buildSummaryTree(listOf(file("views/hierarchy.kt"), file("views/canvas.kt"), file("other.kt")))
        fun id(location: String) = tree.nodes.single { it.location == location }.id
        return ChangeSummaries(tree, mapOf(
            id("./") to example,
            id("views/") to "Removes the canvas and hierarchy views.",
            id("views/hierarchy.kt") to "Deletes the hierarchy view.",
            id("views/canvas.kt") to "Deletes the canvas view.",
            id("other.kt") to "Renames a setting.",
        ))
    }

    private fun matchAnswer(state: JsonObject, instructions: JsonObject): Double = when (location(instructions)) {
        null -> if (word(state, instructions) in setOf("deleted", "views")) 0.9 else 0.1
        "views/hierarchy.kt", "views/" -> 0.9
        "views/canvas.kt" -> 0.6
        else -> 0.2
    }

    private suspend fun SummaryTracer.trace(summaries: ChangeSummaries, id: Int, word: Int) =
        search(summaries, id, score(summaries, id, word))

    @Test fun `scoring publishes context without searching and search reuses the scored query`() = runBlocking {
        val summaries = viewsTree()
        val fake = FakeDecisions(::matchAnswer)
        val tracer = SummaryTracer(fake.evaluate)
        val context = tracer.score(summaries, 0, 3)
        assertEquals("deleted views: hierarchy", context.query)
        assertEquals(listOf("deleted", "views", "hierarchy"), context.context.map { it.text })
        assertEquals(1, fake.contextRequests().size)
        assertTrue("Scoring must never ask about candidate summaries", fake.matchRequests().isEmpty())

        fake.requests.clear()
        val trace = tracer.search(summaries, 0, context)
        assertEquals(context.query, trace.query)
        assertTrue("Search must reuse the scored words", fake.contextRequests().isEmpty())
        assertEquals(4, fake.askedLocations().size)
    }

    @Test fun `trace ranks summaries below by probability, deeper first on ties`() = runBlocking {
        val summaries = viewsTree()
        val fake = FakeDecisions(::matchAnswer)
        val trace = SummaryTracer(fake.evaluate).trace(summaries, 0, 3)
        assertEquals("root", trace.identity)
        assertEquals("hierarchy", trace.word.text)
        assertEquals("deleted views: hierarchy", trace.query)
        assertEquals(4, trace.candidates)
        val locations = trace.matches.map { summaries.tree[it.id].location }
        assertEquals(listOf("views/hierarchy.kt", "views/", "views/canvas.kt", "other.kt"), locations)
        assertEquals(listOf(2, 1, 2, 1), trace.matches.map { it.levelsBelow })
        assertEquals(trace.matches.map { summaries.tree[it.id].identity }, trace.matches.map { it.identity })
        assertEquals(listOf("views/hierarchy.kt", "views/", "views/canvas.kt"), trace.related.map { summaries.tree[it.id].location })
        assertEquals("views/hierarchy.kt", summaries.tree[trace.best!!.id].location)
        val match = fake.matchRequests().single().state.asJsonObject
        assertEquals("deleted views: hierarchy", match["query"].asString)
        assertEquals("hierarchy", match["query_focus"].asString)
        assertEquals(example, match["pointed_summary"].asJsonObject["summary"].asString)
    }

    @Test fun `trace without related summaries has no best match`() = runBlocking {
        val trace = SummaryTracer(FakeDecisions { _, _ -> 0.1 }.evaluate).trace(viewsTree(), 0, 3)
        assertEquals(4, trace.matches.size)
        assertTrue(trace.related.isEmpty())
        assertNull(trace.best)
    }

    @Test fun `tracing again asks only about candidates whose summary changed`() = runBlocking {
        val summaries = viewsTree()
        val fake = FakeDecisions(::matchAnswer)
        val tracer = SummaryTracer(fake.evaluate)
        tracer.trace(summaries, 0, 3)
        assertEquals(1, fake.contextRequests().size)
        assertEquals(4, fake.askedLocations().size)
        fake.requests.clear()

        val again = tracer.trace(summaries, 0, 3)
        assertTrue(fake.requests.isEmpty())
        assertEquals("views/hierarchy.kt", summaries.tree[again.best!!.id].location)

        val canvas = summaries.tree.nodes.single { it.location == "views/canvas.kt" }.id
        val texts = summaries.tree.nodes.associate { it.id to summaries.text(it.id)!! } + (canvas to "Deletes the canvas view and its tests.")
        tracer.trace(ChangeSummaries(summaries.tree, texts), 0, 3)
        assertTrue(fake.contextRequests().isEmpty())
        assertEquals(listOf("views/canvas.kt"), fake.askedLocations())
    }

    @Test fun `trace path opens down to every match and folds related branches with nothing related beneath`() {
        val tree = buildSummaryTree(listOf("a/x/One.kt", "a/x/Two.kt", "a/y/Three.kt", "a/y/Four.kt", "b/Five.kt", "b/Six.kt").map(::file))
        fun node(identity: String) = tree.nodes.single { it.identity == identity }
        val root = tree.root
        val a = node("directory:a")
        val x = node("directory:a/x")
        val y = node("directory:a/y")
        val three = node("file:a/y/Three.kt")
        val word = SummaryWord(0, 0, 1, "w")
        val trace = SummaryTrace(root.identity, "w", word, listOf(word), "w", listOf(
            SummaryMatch(x.id, x.identity, 0.9, 2), SummaryMatch(three.id, three.identity, 0.7, 3),
            SummaryMatch(a.id, a.identity, 0.6, 1), SummaryMatch(node("directory:b").id, "directory:b", 0.2, 1),
        ), 6)
        val path = tracePath(tree, trace)!!
        assertEquals(setOf(root.identity, a.identity, x.identity, y.identity, three.identity), path.path)
        // A related folder that leads to another match stays open; one with nothing related beneath folds to show its summary.
        assertEquals(setOf(root.identity, a.identity, y.identity), path.open)
        assertEquals(setOf(x.identity), path.fold)
        val marks = traceMarks(tree, trace)
        assertEquals(TraceMark.TRACED, marks[root.identity])
        assertEquals(TraceMark.BEST, marks[x.identity])
        assertEquals(TraceMark.RELATED, marks[a.identity])
        assertEquals(TraceMark.RELATED, marks[three.identity])
        assertEquals(TraceMark.PATH, marks[y.identity])
        assertFalse("Unrelated nodes are not marked", "directory:b" in marks)
    }

    private data class Source(override val path: String, override val text: String) : ReviewSource {
        override val absolutePath get() = "/repo/$path"
        override val fingerprint get() = text.hashCode().toString()
        override val facts get() = emptyList<String>()
    }

    @Test fun `patch lines open removed lines before and the rest after, or in the patch without both texts`() {
        val patch = "--- a/x.kt\n+++ b/x.kt\n@@ -3,3 +3,3 @@ fun a()\n keep\n-old\n+new\n\\ No newline at end of file\n@@ -10 +10,2 @@\n+added\n same\n"
        val lines = patchLines(patch)
        assertEquals(listOf(" 3", "-4", "+4", "+10", " 11"), lines.map { "${it.marker}${it.number}" })
        assertEquals(listOf(LineSide.AFTER, LineSide.BEFORE, LineSide.AFTER, LineSide.AFTER, LineSide.AFTER), lines.map { it.side })
        assertEquals(listOf(0, 0, 0, 1, 1), lines.map { it.hunk })
        assertEquals(listOf(false, true, true, true, false), lines.map { it.candidate })
        assertEquals(listOf(4, 5, 6, 9, 10), patchLines(patch, inPatch = true).map { it.number })
    }

    @Test fun `blocks break at gaps once big enough, at hunks and at the size limit`() {
        fun source(vararg text: String) = text.mapIndexed { index, line -> EvidenceLine(LineSide.AFTER, index + 1, line) }
        // "a" is too small to stand alone, so the blank line after it stays inside its block; the next blank line ends it.
        val blocks = lineBlocks(source("a", "", "b", "c", "", "d", "e"), minLines = 2, maxLines = 6)
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(6, 7)), blocks.map { block -> block.map { it.number } })
        val long = lineBlocks(source("a", "b", "c", "d", "e", "f"), maxLines = 4)
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(5, 6)), long.map { block -> block.map { it.number } })
        val diff = patchLines("@@ -1,2 +1,2 @@\n-a\n+b\n@@ -9 +9 @@\n-c\n+d\n")
        assertEquals(listOf(listOf("-1", "+1"), listOf("-9", "+9")), lineBlocks(diff).map { block -> block.map { "${it.marker}${it.number}" } })
    }

    @Test fun `a file summary searches the file's blocks and then the lines of the best block`() = runBlocking {
        val text = (listOf("package ui", "import x", "import y", "") + listOf("fun paint() {", "    drawBorder()", "    log()", "}") + listOf("", "fun other() {", "    other()", "}"))
            .joinToString("\n")
        val tree = buildSummaryTree(listOf(Source("ui/Paint.kt", text)))
        val node = tree.nodes.single { it.kind == SummaryKind.FILE }
        val summary = "Paints the canvas border."
        val summaries = ChangeSummaries(tree).summarize(tree.nodes.associate { it.id to summary })
        val fake = FakeDecisions { state, instructions ->
            when {
                instructions.has("candidate_lines") -> if ("drawBorder" in instructions["candidate_lines"].asString) 0.9 else 0.1
                instructions.has("candidate_line") -> if ("drawBorder" in instructions["candidate_line"].asString) 0.8 else 0.2
                else -> 0.9
            }
        }
        val tracer = SummaryTracer(fake.evaluate)
        val words = SummaryWord.split(summary)
        val context = tracer.score(summaries, node.id, words.indexOfFirst { it.text == "border" })
        fake.requests.clear()
        val trace = tracer.searchLines(summaries, node.id, context)
        assertEquals(listOf(listOf(1, 2, 3), listOf(5, 6, 7, 8), listOf(10, 11, 12)), trace.blocks.sortedBy { it.lines.first().number }.map { block -> block.lines.map { it.number } })
        assertEquals(listOf(5, 6, 7, 8), trace.best!!.lines.map { it.number })
        assertEquals(listOf(6), trace.after)
        assertTrue(trace.before.isEmpty())
        assertEquals("Paint.kt:6", trace.label)
        val (blocks, lines) = fake.requests.partition { request -> request.questions.values.any { it.instructions.asJsonObject.has("candidate_lines") } }
        assertEquals(3, blocks.flatMap { it.questions.values }.size)
        assertEquals("Only the best block's lines are asked about", 4, lines.flatMap { it.questions.values }.size)
        assertEquals("5| fun paint() {\n6|     drawBorder()\n7|     log()\n8| }", lines.first().state.asJsonObject["block"].asString)
        assertEquals(context.query, blocks.first().state.asJsonObject["query"].asString)
    }

    @Test fun `a file search without a related block opens nothing`() = runBlocking {
        val tree = buildSummaryTree(listOf(Source("a.kt", "val a = 1\n")))
        val node = tree.nodes.single { it.kind == SummaryKind.FILE }
        val summaries = ChangeSummaries(tree).summarize(tree.nodes.associate { it.id to "Sets the value." })
        val tracer = SummaryTracer(FakeDecisions { _, _ -> 0.1 }.evaluate)
        val trace = tracer.searchLines(summaries, node.id, tracer.score(summaries, node.id, 2))
        assertEquals(1, trace.blocks.size)
        assertNull(trace.best)
        assertTrue(trace.lines.isEmpty())
    }
}
