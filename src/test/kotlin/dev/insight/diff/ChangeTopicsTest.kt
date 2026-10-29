package dev.insight.diff

import dev.insight.review.summarize
import com.google.gson.JsonParser
import dev.insight.chat.ToolInputException
import dev.insight.llm.Completion
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmTruncatedException
import dev.insight.llm.LlmRole
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopicGenerator
import dev.insight.review.ReviewTopics
import dev.insight.review.SummaryKind
import dev.insight.review.TopicEntry
import dev.insight.review.buildSummaryTree
import dev.insight.review.isTopicsRequest
import dev.insight.review.normalizeTopic
import dev.insight.review.reviewJson
import dev.insight.review.topicOutline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChangeTopicsTest {
    private fun file(path: String, version: String = "1") = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new", version)

    @Test fun `reasoning truncation raises the budget without nested retries or resetting it`() = runBlocking {
        for (protocolError in listOf(false, true)) {
            var topics = ReviewTopics(listOf(file("a"), file("b")))
            val budgets = mutableListOf<Int?>()
            val run = diffTopicGenerator({ request ->
                budgets += request.maxTokens
                if (budgets.size == 1) {
                    if (protocolError) throw LlmTruncatedException("LLM response was truncated at the output-token limit")
                    Completion("", reasoning = "Still classifying", finishReason = FinishReason.TRUNCATED)
                } else Completion("""{"topic":"UI>DiffReview>Topics"}""")
            }, null).run(topics, "", { path, label -> topics.assign(path, label).also { topics = it } })
            assertEquals(listOf(10_000, 20_000, 10_000), budgets)
            assertEquals(3, run.requests)
            assertTrue(run.topics.complete)
        }
    }

    @Test fun `topic vocabulary has a serialized size bound even with escaped long labels`() {
        var topics = ReviewTopics((1..100).map { file("$it") })
        for (file in topics.files) topics = topics.assign(file.path, "${file.path}${"\"".repeat(50)}>" + "x".repeat(60))
        val vocabulary = ReviewTopicGenerator.topicVocabulary(topics)
        assertTrue(vocabulary.isNotEmpty())
        assertTrue(vocabulary.size < 100)
        assertTrue(reviewJson.toJson(vocabulary).length <= 8000)
        assertEquals(topics.groups.keys.take(vocabulary.size), vocabulary)
    }

    @Test fun `context overflow is not retried as output truncation`() = runBlocking {
        var requests = 0
        try {
            diffTopicGenerator({ requests++; throw LlmApiException(400, "context_length_exceeded", null) }, null)
                .run(ReviewTopics(listOf(file("a"))), "", { _, _ -> error("Must not save") })
            fail("Context overflow swallowed")
        } catch (_: LlmApiException) { }
        assertEquals(1, requests)
    }

    @Test fun `large patch reads evict older tool rounds while preserving current evidence`() = runBlocking {
        var topics = ReviewTopics(listOf(file("large.kt").copy(patch = "x".repeat(60_000))))
        var calls = 0
        val reader = object : dev.insight.chat.ChatTool {
            override val definition = dev.insight.llm.LlmTool("read_diff_files", "Read patch",
                JsonParser.parseString("""{"type":"object"}""").asJsonObject)
            override suspend fun call(arguments: com.google.gson.JsonObject) = "y".repeat(12_000)
        }
        val run = diffTopicGenerator({ request ->
            calls++
            assertTrue(dev.insight.llm.LlmJson.encode(request, "", true).length <= 48_000)
            if (calls <= 8) Completion("", toolCalls = listOf(dev.insight.llm.LlmToolCall("read$calls", "read_diff_files",
                JsonParser.parseString("""{"paths":["large.kt"],"offset":${calls * 12_000}}""").asJsonObject)))
            else {
                assertTrue(request.messages.any { "Context omitted to fit the window" in it.content })
                assertFalse(request.messages.any { it.toolCallId == "read1" })
                assertTrue(request.messages.any { it.toolCallId == "read8" })
                Completion("""{"topic":"UI>DiffReview>Topics"}""")
            }
        }, reader).run(topics, "", { path, label -> topics.assign(path, label).also { topics = it } })
        assertEquals(9, run.requests)
        assertTrue(run.topics.complete)
    }

    @Test fun `paths are normalized validated and grouped with canonical casing`() {
        assertEquals("Storage > User > Wallet", normalizeTopic(" Storage > User  > Wallet "))
        listOf("", "Storage", "Storage>>Wallet", "a>b>c>d>e>f", "Storage>Wallet:Update", "a>${"b".repeat(61)}")
            .forEach { assertNull(it, normalizeTopic(it)) }
        val topics = ReviewTopics(listOf(file("b"), file("a")))
            .assign("a", "Storage>User>Wallet").assign("b", "storage>user>wallet")
        assertEquals(mapOf("Storage > User > Wallet" to listOf("a", "b")), topics.groups)
        assertTrue(topics.complete)
    }

    @Test fun `code visits every missing file in sorted order retries invalid labels and supplies vocabulary`() = runBlocking {
        var topics = ReviewTopics(listOf(file("c"), file("b"), file("a")))
        val visited = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val generator = diffTopicGenerator({ request ->
            val input = JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content).asJsonObject
            assertEquals(listOf("focus", "existing_topics", "path"), input.keySet().take(3))
            visited += input["path"].asString
            if (visited.size == 1) Completion("""{"topic":"Invalid"}""") else {
                if (input["path"].asString != "a") assertEquals("Storage > User > Wallet", input["existing_topics"].asJsonArray[0].asString)
                Completion("""{"topic":"Storage>User>Wallet"}""")
            }
        }, null)
        val run = generator.run(topics, "", { path, label -> topics.assign(path, label).also { topics = it } },
            onProgress = { done, _ -> progress += done })
        assertEquals(listOf("a", "a", "b", "c"), visited)
        assertEquals(listOf(0, 1, 2, 3), progress)
        assertEquals(4, run.requests)
        assertTrue(run.topics.complete)
        assertNull(run.error)
        val resumed = generator.run(topics, "", { _, _ -> fail("No files are missing"); topics })
        assertEquals(0, resumed.requests)
    }

    @Test fun `exhausted file does not prevent later files from receiving topics`() = runBlocking {
        var topics = ReviewTopics(listOf(file("a"), file("b")))
        var count = 0
        val run = diffTopicGenerator({
            count++
            Completion(if (count <= 5) """{"topic":3}""" else """{"topic":"Security>User>Auth"}""")
        }, null).run(topics, "", { path, label -> topics.assign(path, label).also { topics = it } })
        assertEquals(setOf("b"), run.topics.labels.keys)
        assertFalse(run.topics.complete)
        assertNotNull(run.error)
    }

    @Test fun `snapshot refresh retains unchanged files and rejects stale writes`() {
        val state = DiffReviewState()
        state.update(ReviewSnapshot(listOf(file("a"), file("b")), emptyList()))
        val revision = state.revision
        state.startTopics()
        state.assignTopic(revision, "a", "Storage>User>Wallet")
        state.assignTopic(revision, "b", "Security>User>Auth")
        state.clearNotes()
        assertFalse(state.topicsVisible)
        assertTrue(state.topics.complete)
        state.update(ReviewSnapshot(listOf(file("a", "2"), file("b")), emptyList()))
        assertEquals(setOf("b"), state.topics.labels.keys)
        try { state.assignTopic(revision, "a", "UI>User>Profile"); fail("Stale write accepted") }
        catch (_: ToolInputException) { }
        assertEquals(setOf("b"), state.startTopics().labels.keys)
        state.assignTopic(state.revision, "a", "UI>User>Profile")
        assertTrue(state.startTopics().labels.isEmpty())
    }

    @Test fun `stale save and cancellation abort without retries or subsequent files`() = runBlocking {
        var requests = 0
        val generator = diffTopicGenerator({ requests++; Completion("""{"topic":"Storage>User>Wallet"}""") }, null)
        try {
            generator.run(ReviewTopics(listOf(file("a"), file("b"))), "", { _, _ -> throw ToolInputException("stale") })
            fail("Stale save swallowed")
        } catch (_: ToolInputException) { }
        assertEquals(1, requests)
        try {
            diffTopicGenerator({ throw CancellationException("stopped") }, null)
                .run(ReviewTopics(listOf(file("a"))), "", { _, _ -> error("Must not save") })
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun `outline orders files by topic levels so every node spans a contiguous run`() {
        val topics = ReviewTopics(listOf("e", "d", "c", "b", "a", "z").map { file(it) })
            .assign("a", "UI>Checkout>Payment Form")
            .assign("b", "Storage>User>Wallet")
            .assign("c", "storage>User")
            // A level that sorts between "Storage" and its children by raw string order must not split the Storage run.
            .assign("d", "Storage-Legacy>Import")
            .assign("e", "Storage>User>Auth")
        val outline = topicOutline(topics)
        assertEquals(listOf("c", "e", "b", "d", "a", "z"), outline.files.map { it.path })
        assertEquals(5, outline.labeled)
        assertEquals(5..5, outline.pending)
        assertEquals(3, outline.depth)
        fun nodes(level: Int) = outline.nodes.filter { it.level == level }.map { "${it.fullPath} ${it.first}-${it.last}" }
        // The first file sets a node's casing; matching is case-insensitive, like topic reuse.
        assertEquals(listOf("storage 0-2", "Storage-Legacy 3-3", "UI 4-4"), nodes(0))
        assertEquals(listOf("storage > User 0-2", "Storage-Legacy > Import 3-3", "UI > Checkout 4-4"), nodes(1))
        // "storage>User" has no third level, so the leaf column has a gap beside its file.
        assertEquals(listOf("Storage > User > Auth 1-1", "Storage > User > Wallet 2-2", "UI > Checkout > Payment Form 4-4"), nodes(2))
        val empty = topicOutline(ReviewTopics(listOf(file("a"))))
        assertEquals(0, empty.depth)
        assertEquals(0..0, empty.pending)
        assertTrue(empty.nodes.isEmpty())
    }

    @Test fun `outline children nest topics and files that end at each level`() {
        val topics = ReviewTopics(listOf("a", "b", "c", "z").map { file(it) })
            .assign("a", "Storage>User>Wallet")
            .assign("b", "Storage>User")
            .assign("c", "UI>Checkout")
        val outline = topicOutline(topics)
        fun describe(entries: List<TopicEntry>) = entries.map {
            when (it) { is TopicEntry.Topic -> it.node.fullPath; is TopicEntry.File -> outline.files[it.index].path }
        }
        fun node(path: String) = outline.nodes.single { it.fullPath == path }
        assertEquals(listOf("Storage", "UI"), describe(outline.children(null)))
        assertEquals(listOf("Storage > User"), describe(outline.children(node("Storage"))))
        // A file whose topic ends here precedes the deeper topics, as in the outline order.
        assertEquals(listOf("b", "Storage > User > Wallet"), describe(outline.children(node("Storage > User"))))
        assertEquals(listOf("a"), describe(outline.children(node("Storage > User > Wallet"))))
        assertEquals(listOf("c"), describe(outline.children(node("UI > Checkout"))))
        assertEquals(3..3, outline.pending)
    }

    @Test fun `chosen level folds topics that appear later and toggles survive updates`() {
        var topics = ReviewTopics(listOf(file("a"), file("b"))).assign("a", "Storage>User>Wallet")
        val state = ChangeTopicsViewState()
        var outline = topicOutline(topics)
        state.update(outline)
        assertEquals(3, state.effectiveLevel(outline))
        state.selectLevel(1, outline)
        assertEquals(listOf(false, true, true), outline.nodes.map(state::isCollapsed))
        state.toggle(outline.nodes.single { it.level == 1 })
        topics = topics.assign("b", "UI>Checkout")
        outline = topicOutline(topics)
        state.update(outline)
        assertEquals(mapOf("Storage" to false, "Storage > User" to false, "Storage > User > Wallet" to true, "UI" to false, "UI > Checkout" to true),
            outline.nodes.associate { it.fullPath to state.isCollapsed(it) })
        state.selectLevel(outline.depth, outline)
        assertTrue(outline.nodes.none(state::isCollapsed))
    }

    @Test fun `topic and summary filters hide unmatched files and the topics left empty`() {
        val topics = ReviewTopics(listOf("a", "b", "c", "d").map { file(it) })
            .assign("a", "Storage>User>Wallet").assign("b", "Storage>Cache").assign("c", "UI>Checkout")
        val tree = buildSummaryTree(topics.files)
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.filter { it.kind == SummaryKind.FILE }
            .associate { it.id to "Summary of ${it.path}: " + if (it.path == "c" || it.path == "d") "renders the payment form" else "persists records" })
        val card = TopicsCard(topics, summaries, canRun = false)
        val state = ChangeTopicsViewState()
        assertSame(state.fullOutline(card), state.outline(card))

        state.topicFilter = "storge"
        assertEquals(listOf("b", "a"), state.outline(card).files.map { it.path })
        assertEquals(4, state.fullOutline(card).files.size)
        state.summaryFilter = "payment"
        assertTrue("Both fields must match", state.outline(card).files.isEmpty())
        state.topicFilter = ""
        // A file awaiting a topic still matches by summary and stays last.
        assertEquals(listOf("c", "d"), state.outline(card).files.map { it.path })
        assertEquals(listOf("UI", "UI > Checkout"), state.outline(card).nodes.map { it.fullPath })
    }

    @Test fun `summary filter opens folded topics until folded by hand`() {
        val topics = ReviewTopics(listOf(file("a"), file("b"))).assign("a", "Storage>User").assign("b", "UI>Checkout")
        val card = TopicsCard(topics, ReviewSummaries(buildSummaryTree(topics.files)), canRun = false)
        val state = ChangeTopicsViewState()
        state.selectLevel(0, state.fullOutline(card))
        assertTrue(state.outline(card).nodes.all(state::isCollapsed))
        state.summaryFilter = "a"
        val storage = state.outline(card).nodes.single { it.fullPath == "Storage" }
        assertFalse(state.isCollapsed(storage))
        state.toggle(storage)
        assertTrue(state.isCollapsed(storage))
        state.summaryFilter = ""
        assertTrue("Clearing the field restores the folding", state.fullOutline(card).nodes.all(state::isCollapsed))
    }

    @Test fun `command recognizes topics without capturing ordinary questions`() {
        listOf("topics", "/topics", " /TOPICS auth ").forEach { assertTrue(isTopicsRequest(it)) }
        listOf("topics about auth", "/topicsExtra", "What topics changed?").forEach { assertFalse(isTopicsRequest(it)) }
    }
    @Test fun longPatchesExposeBoundedInlineEvidenceAndReader() = runBlocking {
        val source = file("large.kt").copy(patch = "x".repeat(100))
        var topics = ReviewTopics(listOf(source))
        var calls = 0
        var reads = 0
        val reader = object : dev.insight.chat.ChatTool {
            override val definition = dev.insight.llm.LlmTool("read_diff_files", "Read patch", JsonParser.parseString("""{"type":"object"}""").asJsonObject)
            override suspend fun call(arguments: com.google.gson.JsonObject): String { reads++; return source.patch.drop(10) }
        }
        val run = diffTopicGenerator({ request ->
            calls++
            assertEquals(10_000, request.maxTokens)
            if (calls == 1) {
                val input = JsonParser.parseString(request.messages.last().content).asJsonObject
                assertEquals(10, input["patch"].asString.length)
                assertEquals(10, input["patch_cut_at"].asInt)
                assertEquals(100, input["patch_length"].asInt)
                Completion("", toolCalls = listOf(dev.insight.llm.LlmToolCall("read", "read_diff_files",
                    JsonParser.parseString("""{"paths":["large.kt"],"offset":10}""").asJsonObject)))
            } else Completion("""{"topic":"Storage>User>Wallet"}""")
        }, reader, patchChars = 10).run(topics, "", { path, label -> topics.assign(path, label).also { topics = it } })
        assertEquals(1, reads)
        assertEquals(2, run.requests)
        assertTrue(run.topics.complete)
    }
}
