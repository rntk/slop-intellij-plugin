package dev.insight.diff

import dev.insight.review.summarize
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.chat.ChatTool
import dev.insight.chat.ToolInputException
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmTool
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.ReviewSummaryRun
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewTopics
import dev.insight.review.SourceChunk
import dev.insight.review.SummaryEvidence
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.TopicEvidence
import dev.insight.review.buildSummaryTree
import dev.insight.review.buildTopicSummaryTree
import dev.insight.review.reviewJson
import dev.insight.review.seedTopicSummaries
import dev.insight.review.summariesReply
import dev.insight.review.topicReadingSummaries
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReviewPipelineGeneralizationTest {
    private data class Source(override val path: String, override val fingerprint: String = "1") : ReviewSource {
        override val absolutePath: String get() = "/repo/$path"
        override val facts: List<String> get() = listOf("40 lines")
    }

    /** [count] parts of ten lines each. */
    private fun parts(count: Int) = List(count) { SourceChunk(it, (it * 10 + 1)..(it * 10 + 10), "h$it") }

    private val big = Source("lib/big.kt")
    private val files = listOf(big, Source("lib/a.kt"), Source("lib/b.kt"), Source("other/c.kt"), Source("other/d.kt"))
    /** big.kt has five parts; with at most three children they form two part groups beneath the file. */
    private fun chunkedTree(maxChildren: Int = 3) =
        buildSummaryTree(files, maxChildren, rootLabel = "Whole scope", chunks = { if (it.path == big.path) parts(5) else emptyList() })

    private fun ReviewSummaryTree.byLocation(location: String) = nodes.single { it.location == location }

    /** Evidence that shows each leaf's lines as fake code; [sizes] overrides a leaf's inline size. */
    private class FakeEvidence(val sizes: Map<String, Int> = emptyMap()) : SummaryEvidence {
        val described = mutableListOf<String>()
        override fun size(node: SummaryNode): Int = sizes[node.location] ?: 10
        override fun rows(node: SummaryNode): IntRange = 1..1
        override fun describe(node: SummaryNode, item: JsonObject, limit: Int) {
            described += node.location
            item.addProperty("code", "code of ${node.location}".take(limit))
        }
        override fun leafSystem(tree: ReviewSummaryTree, focus: String) = "LEAF ${tree.root.label} $focus"
        override fun branchSystem(focus: String) = "BRANCH $focus"
    }

    /** Answers each node with "<kind> <location>". */
    private class Model {
        val requests = mutableListOf<CompletionRequest>()
        fun nodes(request: CompletionRequest): List<JsonObject> =
            JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content.substringBefore("\n\n")).asJsonObject["nodes"]
                .asJsonArray.map { it.asJsonObject }
        fun complete(request: CompletionRequest): Completion {
            requests += request
            val nodes = nodes(request)
            return Completion(reviewJson.toJson(mapOf("summaries" to nodes.map {
                mapOf("id" to it["id"].asInt, "segments" to listOf(mapOf("text" to "${it["kind"].asString} ${it["location"].asString}", "from" to listOf("1"))))
            })))
        }
    }

    @Test fun `summarizer writes parts then part groups then the large file from its parts then folders and the root`() = runBlocking {
        val tree = chunkedTree()
        val file = tree.byLocation("lib/big.kt")
        val groups = file.children
        assertEquals(2, groups.size)
        assertTrue(groups.all { tree[it].kind == SummaryKind.CHUNK && tree[it].children.isNotEmpty() })
        val chunks = tree.nodes.filter { it.kind == SummaryKind.CHUNK && it.children.isEmpty() }
        assertEquals(5, chunks.size)

        val model = Model()
        val evidence = FakeEvidence()
        var saved = ReviewSummaries(tree)
        val batches = mutableListOf<Set<Int>>()
        val run = diffSummarizer(model::complete, null, ReviewSummaryOptions(filesPerRequest = 20, concurrency = 1), evidence)
            .run(saved, "focus", { entries -> batches += entries.keys; saved.summarize(entries).also { saved = it } })
        assertTrue(run.summaries.complete)
        assertNull(run.error)

        val leaves = tree.nodes.filter { it.children.isEmpty() }.map { it.id }.toSet()
        assertEquals(9, leaves.size)
        assertEquals(listOf(
            leaves,
            // other/ only waits for its files, so it is written beside the part groups.
            groups.toSet() + tree.byLocation("other/").id,
            setOf(file.id),
            setOf(tree.byLocation("lib/").id),
            setOf(tree.root.id),
        ), batches)
        assertEquals(5, run.requests)
        // Only leaves carry evidence, each exactly once; the leaf prompt comes from the evidence.
        assertEquals(tree.nodes.filter { it.id in leaves }.map { it.location }.sorted(), evidence.described.sorted())
        assertEquals("LEAF Whole scope focus", model.requests[0].messages.first().content.substringBefore("\n\nThe user"))
        assertTrue(model.requests.drop(1).all { it.messages.first().content == "BRANCH focus" })
        val part = model.nodes(model.requests[0]).single { it["location"].asString == "lib/big.kt:1-10" }
        assertEquals("chunk", part["kind"].asString)
        assertEquals("code of lib/big.kt:1-10", part["code"].asString)
        assertNull(part["children"])

        // The large file is a branch: written from its part groups' summaries, never from evidence of its own.
        val fileItem = model.nodes(model.requests[2]).single()
        assertEquals("file", fileItem["kind"].asString)
        assertNull(fileItem["code"])
        assertEquals(1, fileItem["files"].asInt)
        assertEquals(listOf("Lines 1–30", "Lines 31–50"), fileItem["children"].asJsonArray.map { it.asJsonObject["label"].asString })
        assertEquals(listOf("C1.S1" to "chunk lib/big.kt:1-30", "C2.S1" to "chunk lib/big.kt:31-50"),
            fileItem["children"].asJsonArray.map { it.asJsonObject["claims"].asJsonArray.single().asJsonObject.let { claim -> claim["cite"].asString to claim["text"].asString } })
        assertEquals("file lib/big.kt", run.summaries.text(file.id))
    }

    @Test fun `cut leaf evidence gets a request of its own with the read tool`() = runBlocking {
        val tree = chunkedTree()
        val model = Model()
        val read = object : ChatTool {
            override val definition = LlmTool("read_code", "Read code.", JsonParser.parseString("""{"type":"object"}""").asJsonObject)
            override suspend fun call(arguments: JsonObject): String = "{}"
        }
        var saved = ReviewSummaries(tree)
        val evidence = FakeEvidence(mapOf("lib/big.kt:11-20" to 500))
        diffSummarizer(model::complete, read, ReviewSummaryOptions(patchChars = 100, filesPerRequest = 20, concurrency = 1), evidence)
            .run(saved, "", { saved.summarize(it).also { next -> saved = next } })
        val leafRequests = model.requests.take(2)
        assertEquals(listOf(8, 1), leafRequests.map { model.nodes(it).size })
        assertEquals(listOf(false, true), leafRequests.map { it.tools.isNotEmpty() })
        assertEquals("lib/big.kt:11-20", model.nodes(leafRequests[1]).single()["location"].asString)
        assertEquals("code of lib/big.kt:11-20".take(100), model.nodes(leafRequests[1]).single()["code"].asString)
    }

    @Test fun `summaries reply and tool pages describe sources and parts`() {
        val empty = ReviewSummaryRun(ReviewSummaries(buildSummaryTree(emptyList())), 0, null)
        assertEquals("There are no changed files to summarize.", summariesReply(empty))
        assertEquals("There are no files in scope.", summariesReply(empty, "There are no files in scope."))

        val tree = chunkedTree()
        val page = JsonParser.parseString(changeSummariesJson(ReviewSummaries(tree), 0)).asJsonObject["nodes"].asJsonArray.map { it.asJsonObject }
        fun at(location: String) = page.single { it["location"].asString == location }
        val file = at("lib/big.kt")
        assertEquals(listOf("40 lines"), file["facts"].asJsonArray.map { it.asString })
        assertEquals(2, file["children"].asJsonArray.size())
        assertNull(file["status"])
        val part = at("lib/big.kt:1-10")
        assertEquals(1, part["lines"].asJsonObject["start"].asInt)
        assertEquals(10, part["lines"].asJsonObject["end"].asInt)
        assertNull(part["children"])
        assertNull(part["facts"])
        val group = at("lib/big.kt:31-50")
        assertEquals(2, group["children"].asJsonArray.size())
        val small = at("lib/a.kt")
        assertNull(small["children"])
        assertNull(small["files"])
        // The empty tree's root still lists its (absent) children, as before.
        val emptyRoot = JsonParser.parseString(changeSummariesJson(empty.summaries, 0)).asJsonObject["nodes"].asJsonArray.single().asJsonObject
        assertEquals(0, emptyRoot["files"].asInt)
    }

    // Topics

    private fun topicRequestPath(request: CompletionRequest): String =
        JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content).asJsonObject["path"].asString

    @Test fun `concurrent topics save every file once with counted requests and abort on stale saves and server errors`() = runBlocking {
        val sources = (1..30).map { Source("src/F${it.toString().padStart(2, '0')}.kt") }
        var topics = ReviewTopics(sources)
        var inFlight = 0
        var peak = 0
        val users = mutableListOf<JsonObject>()
        val systems = mutableSetOf<String>()
        val generator = diffTopicGenerator({ request ->
            inFlight++; peak = maxOf(peak, inFlight)
            users += JsonParser.parseString(request.messages.last().content).asJsonObject
            systems += request.messages.first().content
            delay(5)
            inFlight--
            Completion("""{"topic":"Core>Files>${topicRequestPath(request).substringAfter('/').substringBefore('.')}"}""")
        }, null, system = "CUSTOM", evidence = { source, _ -> TopicEvidence(mapOf("path" to source.path, "code" to "x"), cut = false) },
            concurrency = 4)
        val saved = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val run = generator.run(topics, "focus", { path, label -> saved += path; topics.assign(path, label).also { topics = it } },
            onProgress = { done, _ -> progress += done })
        assertTrue(run.topics.complete)
        assertNull(run.error)
        assertEquals(30, run.requests)
        assertEquals(sources.map { it.path }, saved.sorted())
        assertEquals((0..30).toList(), progress)
        assertTrue("Requests ran one at a time", peak in 2..4)
        assertEquals(setOf("CUSTOM"), systems)
        assertEquals(setOf("path", "code", "existing_topics", "focus"), users.first().keySet())

        var requests = 0
        try {
            diffTopicGenerator({ requests++; delay(5); Completion("""{"topic":"Core>Files"}""") }, null,
                evidence = { source, _ -> TopicEvidence(mapOf("path" to source.path), false) }, concurrency = 4)
                .run(ReviewTopics(sources), "", { _, _ -> throw ToolInputException("stale") })
            fail("Stale save swallowed")
        } catch (_: ToolInputException) { }
        assertTrue(requests < sources.size)

        try {
            diffTopicGenerator({ throw LlmApiException(401, "{}", null) }, null,
                evidence = { source, _ -> TopicEvidence(mapOf("path" to source.path), false) }, concurrency = 4)
                .run(ReviewTopics(sources), "", { _, _ -> error("Must not save") })
            fail("Server error swallowed")
        } catch (_: LlmApiException) { }
    }

    @Test fun `sequential topics keep sorted order and offer each request the vocabulary saved before it`() = runBlocking {
        var topics = ReviewTopics(listOf(Source("c"), Source("a"), Source("b")))
        val visited = mutableListOf<String>()
        val vocabularies = mutableListOf<Int>()
        val run = diffTopicGenerator({ request ->
            val input = JsonParser.parseString(request.messages.last().content).asJsonObject
            visited += input["path"].asString
            vocabularies += input["existing_topics"].asJsonArray.size()
            Completion("""{"topic":"Core>${input["path"].asString}"}""")
        }, null, evidence = { source, _ -> TopicEvidence(mapOf("path" to source.path), false) })
            .run(topics, "", { path, label -> topics.assign(path, label).also { topics = it } })
        assertEquals(listOf("a", "b", "c"), visited)
        assertEquals(listOf(0, 1, 2), vocabularies)
        assertEquals(3, run.requests)
    }

    @Test fun `topic bookkeeping stays fast on ten thousand files`() {
        val sources = (1..10_000).map { Source("src/m${it % 50}/F$it.kt") }
        val labeled = ReviewTopics(sources, sources.associate { it.path to "Core > M${it.path.length % 7}" })
        val started = System.nanoTime()
        val retained = labeled.retainedIn(sources.map { if (it.path.endsWith("7.kt")) it.copy(fingerprint = "2") else it })
        assertEquals(9_000, retained.labels.size)
        assertSame(retained.groups, retained.groups)
        var topics = ReviewTopics(sources)
        for (source in sources.take(1_000)) topics = topics.assign(source.path, "Core > Part ${source.path.length}")
        assertEquals(1_000, topics.labels.size)
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("Took $seconds s", seconds < 2)
        // Lazy values stay out of equality.
        assertEquals(ReviewTopics(sources, labeled.labels), labeled)
    }

    // Topic summaries

    private fun topicTrees(): Pair<ReviewSummaryTree, ReviewSummaryTree> {
        val chunks: (ReviewSource) -> List<SourceChunk> = { if (it.path == big.path) parts(12) else emptyList() }
        val topics = ReviewTopics(files, mapOf(big.path to "Core > Big", "lib/a.kt" to "Core > Small", "lib/b.kt" to "Core > Small",
            "other/c.kt" to "UI > Other", "other/d.kt" to "UI > Other"))
        return buildSummaryTree(files, chunks = chunks) to buildTopicSummaryTree(topics, chunks)
    }

    @Test fun `seeding never uses a part summary for a file set and seeds parts by key`() {
        val (sharedTree, topicTree) = topicTrees()
        assertTrue(topicTree.nodes.count { it.kind == SummaryKind.CHUNK } > 12)
        // Only the parts are summarized: nothing may stand in for the file or the topic that holds only it.
        val partsOnly = ReviewSummaries(sharedTree, sharedTree.nodes.filter { it.kind == SummaryKind.CHUNK }.associate { it.id to "part ${it.identity}" })
        val seeded = seedTopicSummaries(ReviewSummaries(topicTree), partsOnly)
        topicTree.nodes.forEach { node ->
            val text = seeded.text(node.id)
            if (node.kind == SummaryKind.CHUNK) assertEquals("part ${node.identity}", text) else assertNull(node.identity, text)
        }

        val all = ReviewSummaries(sharedTree, sharedTree.nodes.associate { it.id to "shared ${it.identity}" })
        val full = seedTopicSummaries(ReviewSummaries(topicTree), all)
        val file = topicTree.nodes.single { it.kind == SummaryKind.FILE && it.path == big.path }
        assertEquals("shared file:lib/big.kt", full.text(file.id))
        assertEquals("shared file:lib/big.kt", full.text(topicTree.nodes.single { it.identity == "topic:core > big" }.id))
        topicTree.nodes.filter { it.kind == SummaryKind.CHUNK }.forEach { assertEquals("shared ${it.identity}", full.text(it.id)) }
        // A saved topic summary is never overwritten.
        val own = ReviewSummaries(topicTree, mapOf(file.id to "own"))
        assertEquals("own", seedTopicSummaries(own, all).text(file.id))
    }

    @Test fun `reading tree drops parts so files are leaves and topics never read a part summary`() {
        val (sharedTree, topicTree) = topicTrees()
        val partsOnly = ReviewSummaries(sharedTree, sharedTree.nodes.filter { it.kind == SummaryKind.CHUNK }.associate { it.id to "part ${it.identity}" })
        val reading = topicReadingSummaries(ReviewSummaries(topicTree), partsOnly)
        val tree = reading.tree
        assertTrue(tree.nodes.none { it.kind == SummaryKind.CHUNK || it.kind == SummaryKind.GROUP })
        tree.nodes.forEachIndexed { index, node ->
            assertEquals(index, node.id)
            node.parent?.let { assertTrue(it < node.id); assertTrue(node.id in tree[it].children) }
        }
        assertTrue(tree.nodes.filter { it.kind == SummaryKind.FILE }.all { it.children.isEmpty() })
        assertEquals(files.size, tree.nodes.count { it.kind == SummaryKind.FILE })
        assertTrue(tree.nodes.all { reading.text(it.id) == null })

        val all = ReviewSummaries(sharedTree, sharedTree.nodes.associate { it.id to "shared ${it.identity}" })
        val read = topicReadingSummaries(ReviewSummaries(topicTree), all)
        assertEquals("shared file:lib/big.kt", read.text(read.tree.nodes.single { it.identity == "topic:core > big" }.id))
        assertTrue(read.tree.nodes.none { read.text(it.id)?.startsWith("shared chunk") == true })
    }
}
