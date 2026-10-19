package dev.insight.diff

import com.google.gson.JsonParser
import dev.insight.llm.Completion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChangeTopicSummariesTest {
    private fun file(path: String, version: String = "1") =
        ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new", version)

    @Test fun `every topic prefix survives grouping and tree remains bounded and preorder`() {
        val files = (1..70).map { file("F$it.kt") }
        val topics = ChangeTopics(files, files.associate { it.path to "Storage > Wallet" })
        val tree = buildTopicSummaryTree(topics)
        assertEquals(setOf("topic:storage", "topic:storage > wallet"),
            tree.nodes.filter { it.kind == SummaryKind.DIRECTORY }.map { it.identity }.toSet())
        assertEquals(files.size, tree.nodes.count { it.kind == SummaryKind.FILE })
        tree.nodes.forEach { node ->
            assertTrue(node.children.size <= ChangeSummaryTree.MAX_CHILDREN)
            node.parent?.let { assertTrue(it < node.id); assertTrue(node.id in tree[it].children) }
        }
    }

    @Test fun `fills missing topics resumes without requests and shares file summaries`() = runBlocking {
        val state = DiffReviewState()
        state.update(ReviewSnapshot(listOf(file("a.kt"), file("b.kt"), file("c.kt")), emptyList()))
        state.assignTopic(state.revision, "a.kt", "Storage > Wallet")
        state.assignTopic(state.revision, "b.kt", "Storage > Wallet")
        state.assignTopic(state.revision, "c.kt", "UI > Checkout")
        val existingFile = state.summaries.tree.nodes.single { it.path == "a.kt" && it.kind == SummaryKind.FILE }
        state.summarize(state.revision, mapOf(existingFile.id to "Existing summary"))
        var requests = 0
        val summarizer = ChangeSummarizer({ request ->
            requests++
            val nodes = JsonParser.parseString(request.messages.last().content).asJsonObject.getAsJsonArray("nodes")
            val answers = nodes.map { node ->
                "{\"id\":${node.asJsonObject.get("id")},\"summary\":\"Generated summary\"}"
            }
            Completion("{\"summaries\":[${answers.joinToString()}]}")
        }, null)
        val run = summarizer.run(state.startTopicSummaries(state.revision), "", { state.summarizeTopics(state.revision, it) })
        assertTrue(run.summaries.complete)
        assertEquals("Existing summary", state.summaries.text(existingFile.id))
        assertTrue(state.summaries.tree.nodes.filter { it.kind == SummaryKind.FILE }.all { state.summaries.text(it.id) != null })
        val before = requests
        summarizer.run(state.startTopicSummaries(state.revision), "", { state.summarizeTopics(state.revision, it) })
        assertEquals(before, requests)
    }

    @Test fun `changed membership invalidates affected topics while unrelated summaries survive`() {
        val files = listOf(file("a.kt"), file("b.kt"), file("c.kt"))
        val topics = ChangeTopics(files, mapOf("a.kt" to "Storage > Wallet", "b.kt" to "Storage > Wallet", "c.kt" to "UI > Checkout"))
        val tree = buildTopicSummaryTree(topics)
        val complete = ChangeSummaries(tree, tree.nodes.associate { it.id to it.identity })
        val next = complete.retainedIn(buildTopicSummaryTree(topics.assign("a.kt", "Storage > Account")))
        assertNull(next.text(next.tree.nodes.single { it.identity == "topic:storage" }.id))
        assertEquals("topic:ui", next.text(next.tree.nodes.single { it.identity == "topic:ui" }.id))
        val edited = complete.retainedIn(buildTopicSummaryTree(topics.retainedIn(listOf(file("a.kt", "2"), files[1], files[2]))))
        // The remaining single-file branch may reuse its unchanged file summary.
        assertNotEquals("topic:storage", edited.text(edited.tree.nodes.single { it.identity == "topic:storage" }.id))
        assertEquals("topic:ui", edited.text(edited.tree.nodes.single { it.identity == "topic:ui" }.id))
    }

    @Test fun `reading tree drops groups and reads shared file summaries and exact file set summaries`() {
        val files = (1..10).map { file("F$it.kt") }
        val topics = ChangeTopics(files, files.associate { it.path to "Storage > Wallet" })
        val grouped = ChangeSummaries(buildTopicSummaryTree(topics))
        assertTrue(grouped.tree.nodes.any { it.kind == SummaryKind.GROUP })
        val sharedTree = buildSummaryTree(files)
        val shared = ChangeSummaries(sharedTree).summarize(sharedTree.nodes.associate {
            it.id to if (it.kind == SummaryKind.FILE) "Shared ${it.path}." else "Every wallet file in ${it.label}."
        })
        val reading = topicReadingSummaries(grouped, shared)
        val tree = reading.tree
        assertTrue(tree.nodes.none { it.kind == SummaryKind.GROUP })
        val wallet = tree.nodes.single { it.identity == "topic:storage > wallet" }
        // The topic's children are the files beside it in the hierarchy, in pre-order.
        assertEquals(files.map { "file:${it.path}" }.sorted(), wallet.children.map { tree[it].identity })
        tree.nodes.forEach { node -> node.parent?.let { assertTrue(it < node.id); assertTrue(node.id in tree[it].children) } }
        assertEquals("Shared F3.kt.", reading.text(tree.nodes.single { it.identity == "file:F3.kt" }.id))
        assertEquals("Every wallet file in ${sharedTree.root.label}.", reading.text(wallet.id))
    }
}
