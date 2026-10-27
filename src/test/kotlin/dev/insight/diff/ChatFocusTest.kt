package dev.insight.diff

import dev.insight.review.summarize
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.SummaryLevel
import dev.insight.review.buildSummaryTree
import dev.insight.review.buildTopicSummaryTree
import dev.insight.review.chatFocus
import dev.insight.review.chatFocusPrompt
import dev.insight.review.chatFocusTabName
import dev.insight.review.current
import dev.insight.review.topicReadingSummaries
import org.junit.Assert.*
import org.junit.Test

class ChatFocusTest {
    private fun file(path: String) = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", "1")
    private val paths = listOf("src/app/Cache.kt", "src/app/Store.kt", "docs/README.md")
    private val tree = buildSummaryTree(paths.map(::file))
    private val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Summary of ${it.identity}." })

    @Test fun `a folder focuses on the files beneath it with the summaries down to it`() {
        val focus = chatFocus(listOf("src/app"), paths, summaries)!!
        assertEquals("src/app/", focus.label)
        assertEquals(listOf("src/app/Cache.kt", "src/app/Store.kt"), focus.files)
        assertEquals(listOf("Summary of root.", "Summary of directory:src.", "Summary of directory:src/app."), focus.context.map { it.text })
        assertEquals("Chat: app", chatFocusTabName(focus))
    }

    @Test fun `a file focuses on itself, and entries outside the scope focus on nothing`() {
        val focus = chatFocus(listOf("docs/README.md"), paths, summaries)!!
        assertEquals(listOf("docs/README.md"), focus.files)
        assertEquals("Summary of file:docs/README.md.", focus.context.last().text)
        assertNull(chatFocus(listOf("lib"), paths, summaries))
        // Without summaries the focus still has its files.
        assertEquals(emptyList<SummaryLevel>(), chatFocus(listOf("docs/README.md"), paths, ReviewSummaries(tree))!!.context)
    }

    @Test fun `several entries focus on all their files`() {
        val focus = chatFocus(listOf("docs/README.md", "src/app/Store.kt"), paths, summaries)!!
        assertEquals("2 selected", focus.label)
        assertEquals(listOf("docs/README.md", "src/app/Store.kt"), focus.files)
    }

    @Test fun `a summary entry focuses on its node`() {
        val node = tree.nodes.single { it.location == "src/app/Store.kt" }
        val focus = chatFocus(node, summaries)
        assertEquals("src/app/Store.kt", focus.label)
        assertEquals("Summary of file:src/app/Store.kt.", focus.context.last().text)
    }

    @Test fun `the prompt pins summaries and evidence, and lists what does not fit`() {
        val focus = chatFocus(listOf("src/app"), paths, summaries)!!
        val prompt = chatFocusPrompt(focus, "change", "read_diff_files", maxChars = 30) { path, _ -> "patch of $path" }
        assertTrue(prompt, "on src/app/ (2 files)" in prompt)
        assertTrue(prompt, "3. src/app/ (focus): Summary of directory:src/app." in prompt)
        assertTrue(prompt, "--- src/app/Cache.kt ---\npatch of src/app/Cache.kt" in prompt)
        assertFalse(prompt, "patch of src/app/Store.kt" in prompt)
        assertTrue(prompt, "read with read_diff_files when needed:\n- src/app/Store.kt" in prompt)
    }

    @Test fun `evidence is not built for files after the budget runs out`() {
        val focus = chatFocus(listOf(""), paths, summaries)!!
        val built = mutableListOf<String>()
        val prompt = chatFocusPrompt(focus, "code", "read_code", maxChars = 5) { path, _ -> built += path; "source of $path" }
        assertEquals(listOf(focus.files.first()), built)
        assertTrue(prompt, focus.files.all { "- $it" in prompt })
    }

    @Test fun `a turn uses regenerated summaries instead of the text captured by To Chat`() {
        val focus = chatFocus(listOf("src/app"), paths, summaries)!!
        val regenerated = summaries.summarize(tree.nodes.associate { it.id to "New ${it.identity}" })
        val current = focus.current(paths.toSet(), regenerated, ReviewSummaries(tree))
        assertEquals(listOf("New root", "New directory:src", "New directory:src/app"), current.context.map { it.text })
        assertEquals(focus.context, focus.current(paths.toSet(), ReviewSummaries(tree), ReviewSummaries(tree)).context)
    }

    @Test fun `changed evidence drops captured summaries but keeps unchanged levels`() {
        val focus = chatFocus(listOf("src/app"), paths, summaries)!!
        val changed = buildSummaryTree(paths.map { file(it).copy(fingerprint = if (it == "docs/README.md") "2" else "1") })
        val current = focus.current(paths.toSet(), ReviewSummaries(changed), ReviewSummaries(tree))
        assertEquals(listOf("Summary of directory:src.", "Summary of directory:src/app."), current.context.map { it.text })
        val changedFocus = buildSummaryTree(paths.map { file(it).copy(fingerprint = "2") })
        assertTrue(focus.current(paths.toSet(), ReviewSummaries(changedFocus), ReviewSummaries(tree)).context.isEmpty())
    }

    @Test fun `files that left scope are reported separately from readable files over budget`() {
        val focus = chatFocus(listOf("src/app"), paths, summaries)!!
        val livePaths = setOf("src/app/Store.kt", "docs/README.md", "src/app/Renamed.kt")
        val live = ReviewSummaries(buildSummaryTree(livePaths.map(::file)))
        val current = focus.current(livePaths, live, ReviewSummaries(tree))
        val built = mutableListOf<String>()
        val prompt = chatFocusPrompt(current, "change", "read_diff_files", maxChars = 1) { path, _ ->
            built += path
            "patch of $path"
        }
        assertEquals(listOf("src/app/Store.kt"), built)
        assertEquals(listOf("src/app/Cache.kt"), current.unavailableFiles)
        assertFalse(prompt, "Summary of" in prompt)
        assertFalse(prompt, "Renamed.kt" in prompt)
        assertTrue(prompt, "read with read_diff_files when needed:\n- src/app/Store.kt\n\nUnavailable" in prompt)
        assertTrue(prompt, "the snapshot tools cannot read these focused files:\n- src/app/Cache.kt" in prompt)
    }

    @Test fun `a removed focus explicitly says that none of its files remain in scope`() {
        val focus = chatFocus(listOf("src/app/Cache.kt"), paths, summaries)!!
        val live = ReviewSummaries(buildSummaryTree(listOf(file("docs/README.md"))))
        val current = focus.current(setOf("docs/README.md"), live, ReviewSummaries(tree))
        val prompt = chatFocusPrompt(current, "code", "read_code") { _, _ -> fail("Removed files must not be rendered"); null }
        assertTrue(prompt, "The focus no longer has files in this tab's current scope." in prompt)
        assertTrue(prompt, "The focused summary entry no longer exists" in prompt)
        assertFalse(prompt, "read with read_code" in prompt)
        assertFalse(prompt, "Summary of" in prompt)
    }

    @Test fun `missing evidence is unavailable and does not prevent rendering later files`() {
        val focus = chatFocus(listOf("src/app"), paths, summaries)!!
        val prompt = chatFocusPrompt(focus, "code", "read_code") { path, _ ->
            if (path == "src/app/Cache.kt") null else "source of $path"
        }
        assertTrue(prompt, "source of src/app/Store.kt" in prompt)
        assertTrue(prompt, "the snapshot tools cannot read these focused files:\n- src/app/Cache.kt" in prompt)
        assertFalse(prompt, "read with read_code" in prompt)
    }

    @Test fun `a topic file keeps live topic ancestry and uses the shared file summary`() {
        val topics = ReviewTopics(paths.map(::file), paths.associateWith { "Storage > Wallet" })
        val topicTree = buildTopicSummaryTree(topics)
        val topicSummaries = ReviewSummaries(topicTree).summarize(
            topicTree.nodes.associate { it.id to "Topic ${it.identity}" }, reuseSingleChildren = false)
        val reading = topicReadingSummaries(topicSummaries, summaries)
        val focus = chatFocus(reading.tree.nodes.single { it.path == "src/app/Cache.kt" }, reading)
        val regenerated = summaries.summarize(tree.nodes.associate { it.id to "New ${it.identity}" })
        val current = focus.current(paths.toSet(), regenerated, topicSummaries)
        assertEquals(listOf("Topic topic-root", "Topic topic:storage", "Topic topic:storage > wallet", "New file:src/app/Cache.kt"),
            current.context.map { it.text })
    }
}
