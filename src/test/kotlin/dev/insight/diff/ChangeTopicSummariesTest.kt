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
        var topicRequests = 0
        var fileRequests = 0
        val summarizer = ChangeSummarizer({ request ->
            requests++
            val nodes = JsonParser.parseString(request.messages.last().content).asJsonObject.getAsJsonArray("nodes")
            val system = request.messages.first().content
            if (nodes.all { it.asJsonObject["kind"].asString == "file" }) {
                fileRequests++
                assertFalse(system.contains("topic", ignoreCase = true))
                assertTrue(nodes.all { !it.asJsonObject.has("topic_path") })
            } else {
                topicRequests++
                assertTrue(system.contains("Topic labels provide context, not additional evidence."))
                nodes.map { it.asJsonObject }.filter { it["kind"].asString == "topic" }.forEach {
                    assertEquals("Storage > Wallet", it["topic_path"].asString)
                    assertFalse(it.has("location"))
                }
                nodes.map { it.asJsonObject }.filter { it["kind"].asString == "root" }.forEach {
                    assertTrue(it["children"].asJsonArray.all { child ->
                        child.asJsonObject["kind"].asString == "topic" && child.asJsonObject.has("topic_path")
                    })
                }
            }
            val answers = nodes.map { node ->
                "{\"id\":${node.asJsonObject.get("id")},\"summary\":\"Generated summary\"}"
            }
            Completion("{\"summaries\":[${answers.joinToString()}]}")
        }, null)
        val run = summarizer.run(state.startTopicSummaries(state.revision), "", { state.summarizeTopics(state.revision, it) })
        assertTrue(run.summaries.complete)
        assertTrue(fileRequests > 0)
        assertTrue(topicRequests > 0)
        assertEquals("Existing summary", state.summaries.text(existingFile.id))
        assertTrue(state.summaries.tree.nodes.filter { it.kind == SummaryKind.FILE }.all { state.summaries.text(it.id) != null })
        val before = requests
        summarizer.run(state.startTopicSummaries(state.revision), "", { state.summarizeTopics(state.revision, it) })
        assertEquals(before, requests)
    }

    @Test fun `topic metadata survives identity changes and large topic sets`() = runBlocking {
        val files = (1..10).flatMap { topic -> (1..10).map { file("T$topic/F$it.kt") } }
        val topics = ChangeTopics(files, files.associate { it.path to it.path.substringBefore('/') })
        // UI identities may change without changing the meaning of requests.
        val tree = ChangeSummaryTree(buildTopicSummaryTree(topics).nodes.map { it.copy(identity = "ui-node-${it.id}") })
        assertTrue(tree.nodes.filter { it.kind == SummaryKind.FILE }.none { it.isTopicBranch })
        assertTrue(tree.nodes.filter { it.kind != SummaryKind.FILE }.all { it.isTopicBranch })
        var current = ChangeSummaries(tree)
        val seen = mutableSetOf<Pair<String, String>>()
        val summarizer = ChangeSummarizer({ request ->
            val nodes = JsonParser.parseString(request.messages.last().content).asJsonObject.getAsJsonArray("nodes")
            val system = request.messages.first().content
            val guidance = system.indexOf("This request describes an existing topic hierarchy.")
            val focus = system.indexOf("The user asked to focus on: reliability")
            val query = system.indexOf("The reader is exploring this query: persistence")
            assertTrue(focus >= 0)
            assertTrue(query > focus)
            nodes.map { it.asJsonObject }.forEach { node ->
                val kind = node["kind"].asString
                if (kind == "file") {
                    assertEquals(-1, guidance)
                    assertTrue(node.has("location"))
                    assertFalse(node.has("topic_path"))
                } else {
                    assertTrue(guidance >= 0 && guidance < focus)
                    assertFalse(node.has("location"))
                    seen += kind to node["topic_path"].asString
                    node["children"].asJsonArray.map { it.asJsonObject }.forEach { child ->
                        if (child["kind"].asString != "file") assertTrue(child.has("topic_path"))
                    }
                }
            }
            Completion("{\"summaries\":[" + nodes.joinToString {
                "{\"id\":${it.asJsonObject["id"]},\"summary\":\"Generated summary\"}"
            } + "]}")
        }, null, ChangeSummaryOptions(concurrency = 1))
        val run = summarizer.run(current, "reliability", { current = current.summarize(it); current }, queryContext = "persistence")
        assertTrue(run.summaries.complete)
        assertTrue("root" to "" in seen)
        assertTrue("group" to "" in seen)
        assertTrue("group" to "T1" in seen)
        assertTrue("topic" to "T1" in seen)
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
