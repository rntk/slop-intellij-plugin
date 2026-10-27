package dev.insight.codereview

import dev.insight.review.summarizeTopics
import dev.insight.review.summarize
import dev.insight.chat.ToolInputException
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewSummaries
import dev.insight.review.SummaryKind
import org.junit.Assert.*
import org.junit.Test

class CodeReviewStateTest {
    private val a = codeFile("src/app/a.kt", "fun a() = 1\nfun a2() = 2\n")
    private val b = codeFile("src/app/b.kt", "fun b() = 2\n")
    private val c = codeFile("docs/c.md", "# Docs\n")
    private val big = codeFile("src/lib/Big.kt", largeSource())

    private fun state(vararg files: CodeFile, paths: Set<String>? = null) = CodeReviewState(paths).apply { update(codeSnapshot(*files)) }

    private fun CodeReviewState.fill() = fillAll(summaries) { summarize(revision, it) }

    private fun CodeReviewState.text(location: String): String? =
        summaries.tree.nodes.single { it.location == location }.let { summaries.text(it.id) }

    private fun assertRejected(message: String, block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (e: ToolInputException) { assertTrue(e.message, e.message!!.contains(message)) }
    }

    @Test fun `stale revisions and refreshes are rejected with code wording`() {
        val state = state(a, b)
        assertEquals(1, state.revision)
        assertFalse(big.chunks.isEmpty())
        assertRejected("The files changed") { state.addNotes(0, listOf(ReviewNote(listOf(a.path), "note"))) }
        assertRejected("The files changed") { state.summarize(0, mapOf(1 to "x")) }
        state.refreshing = true
        assertRejected("The code is refreshing") { state.check(1) }
        state.update(codeSnapshot(a, b))
        // An equal snapshot is not a new revision.
        assertEquals(1, state.revision)
        state.check(1)
        state.update(codeSnapshot(a))
        assertEquals(2, state.revision)
        assertRejected("The files changed") { state.check(1) }
    }

    @Test fun `notes are validated against the scope and kept below the limit`() {
        val state = state(a, b, paths = setOf("src/app"))
        assertEquals(listOf(a.path, b.path), state.snapshot.files.map { it.path })
        assertRejected("not a file in this review's scope") { state.addNotes(1, listOf(ReviewNote(listOf(c.path), "x"))) }
        assertRejected("Note 2: Use a note of 1–2000") { state.addNotes(1, listOf(ReviewNote(listOf(a.path), "ok"), ReviewNote(listOf(b.path), " "))) }
        assertTrue("A rejected batch adds nothing", state.notes.isEmpty())
        val added = state.addNotes(1, listOf(ReviewNote(listOf(b.path, a.path, a.path), " both ", "a")))
        assertEquals(listOf(a.path, b.path), added.single().paths)
        assertEquals("both", added.single().text)
        state.removeNote(listOf(b.path, a.path), "a")
        assertTrue(state.notes.isEmpty())
        val many = (1..200).map { ReviewNote(listOf(a.path), "n$it", "s$it") }
        many.chunked(20).forEach { state.addNotes(1, it) }
        assertRejected("200-note limit") { state.addNotes(1, listOf(ReviewNote(listOf(b.path), "one more"))) }
    }

    @Test fun `notes and sources are dropped only for files whose content changed or vanished`() {
        val state = state(a, b, c)
        state.addNotes(1, listOf(ReviewNote(listOf(a.path), "a"), ReviewNote(listOf(b.path), "b"), ReviewNote(listOf(a.path, c.path), "ac"),
            ReviewNote(listOf(c.path), "c")))
        val block = state.showSource(1, ReviewSourceRange(a.path, 1, 2, "after"))
        assertEquals("current", block.range.side)
        assertEquals("fun a() = 1\nfun a2() = 2", block.text)
        state.showSource(1, ReviewSourceRange(b.path, 1, 1))
        state.update(codeSnapshot(codeFile(a.path, "fun a() = 10\n"), b))
        assertEquals(2, state.revision)
        assertEquals(listOf("b"), state.notes.map { it.text })
        assertEquals(listOf(b.path), state.sources.map { it.range.path })
        state.clearNotes()
        assertTrue(state.notes.isEmpty() && state.sources.isEmpty())
    }

    @Test fun `summaries survive an edit to another file and a narrower scope`() {
        val state = state(a, b, c)
        state.fill()
        assertTrue(state.summaries.complete)
        assertEquals("Whole scope", state.summaries.tree.root.label)
        state.update(codeSnapshot(codeFile(a.path, "fun a() = 10\n"), b, c))
        assertNull(state.text(a.path))
        assertEquals("file ${b.path}", state.text(b.path))
        assertEquals("file ${c.path}", state.text(c.path))
        assertNull("The folder above the edit is invalidated", state.text("src/app/"))
        assertNull(state.text("./"))

        // A narrower start directory keeps every file summary it still holds.
        state.fill()
        state.update(codeSnapshot(state.snapshot.byPath.getValue(a.path), b, scope = "src/app"))
        assertEquals(3, state.revision)
        assertEquals("file ${a.path}", state.text(a.path))
        assertEquals("file ${b.path}", state.text(b.path))
    }

    @Test fun `a fixed tab scope follows its folders and keeps summaries of its files`() {
        val state = state(a, b, c, paths = setOf("src/app/"))
        assertEquals(setOf(a.path, b.path), state.snapshot.files.map { it.path }.toSet())
        state.fill()
        val added = codeFile("src/app/new.kt", "fun n() = 3\n")
        state.update(codeSnapshot(a, b, c, added))
        assertEquals(listOf(a.path, b.path, added.path), state.snapshot.files.map { it.path })
        assertEquals("file ${a.path}", state.text(a.path))
        assertNull(state.text(added.path))
    }

    @Test fun `parts of a large file keep their summaries when another part is edited`() {
        val state = state(a, big)
        state.fill()
        val before = state.summaries.tree.nodes.filter { it.kind == SummaryKind.CHUNK && it.children.isEmpty() }
        assertTrue(before.size > 3)
        val edited = codeFile(big.path, largeSource(edited = 118))
        state.update(codeSnapshot(a, edited))
        val tree = state.summaries.tree
        val leaves = tree.nodes.filter { it.kind == SummaryKind.CHUNK && it.children.isEmpty() }
        val kept = leaves.filter { state.summaries.text(it.id) != null }
        // Every part before the edit keeps its summary; the edited part and the file above it are written again.
        assertEquals(leaves.size - 1, kept.size)
        val missing = leaves.single { state.summaries.text(it.id) == null }
        assertTrue(missing.lines!!.contains(edited.text.lines().indexOfFirst { "+ 118 + 1" in it } + 1))
        assertNull(state.summaries.text(tree.nodes.single { it.kind == SummaryKind.FILE && it.path == big.path }.id))
        assertEquals("file ${a.path}", state.text(a.path))
    }

    @Test fun `topics are kept for unchanged files`() {
        val state = state(a, b, c)
        state.startTopics()
        state.assignTopic(1, a.path, "Core > A")
        state.assignTopic(1, b.path, "Core > B")
        state.assignTopic(1, c.path, "Docs > Guide")
        assertTrue(state.topics.complete)
        state.update(codeSnapshot(codeFile(a.path, "fun a() = 10\n"), b, c))
        assertEquals(mapOf(b.path to "Core > B", c.path to "Docs > Guide"), state.topics.labels)
        assertRejected("The files changed") { state.assignTopic(1, a.path, "Core > A") }
        state.assignTopic(2, a.path, "Core > A")
        assertTrue(state.topics.complete)
        // Starting a complete result regenerates it.
        assertTrue(state.startTopics().labels.isEmpty())
    }

    @Test fun `file and part summaries are shared between topic summaries and summaries`() {
        val state = state(a, big)
        state.startTopics()
        state.assignTopic(1, a.path, "Core > A")
        state.assignTopic(1, big.path, "Core > Steps")
        // Topic summaries first: their file and part summaries fill the ordinary tree.
        fillAll(state.startTopicSummaries(1)) { state.summarizeTopics(1, it) }
        assertTrue(state.topicSummaries.complete)
        val tree = state.summaries.tree
        val shared = tree.nodes.filter { it.kind == SummaryKind.FILE || it.kind == SummaryKind.CHUNK }
        assertTrue(shared.all { state.summaries.text(it.id) != null })
        // A folder holding a single file reuses its summary; the others wait for their own.
        assertTrue(tree.nodes.filter { it.kind != SummaryKind.FILE && it.kind != SummaryKind.CHUNK && it.children.size > 1 }.none { state.summaries.text(it.id) != null })
        assertEquals("file ${a.path}", state.text(a.path))

        // The other way: summaries written first seed the topic tree, parts by key.
        val other = state(a, big)
        other.fill()
        other.startTopics()
        other.assignTopic(1, a.path, "Core > A")
        other.assignTopic(1, big.path, "Core > Steps")
        val seeded = other.startTopicSummaries(1)
        val leaves = seeded.tree.nodes.filter { it.kind == SummaryKind.FILE || it.kind == SummaryKind.CHUNK }
        assertTrue(leaves.all { seeded.text(it.id) != null })
        val part = seeded.tree.nodes.first { it.kind == SummaryKind.CHUNK }
        assertEquals("chunk ${part.location}", seeded.text(part.id))
    }

    @Test fun `regenerate clears and a complete tree restarts`() {
        val state = state(a, b)
        state.fill()
        assertTrue(state.summaries.complete)
        assertEquals(0, state.startSummaries(1).count)
        assertTrue(state.summariesVisible)
        state.fill()
        state.clearSummaries()
        assertEquals(0, state.summaries.count)
        assertTrue(ReviewSummaries(state.summaries.tree).total > 0)
    }
}
