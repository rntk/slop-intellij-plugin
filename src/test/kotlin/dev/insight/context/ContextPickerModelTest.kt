package dev.insight.context

import dev.insight.agent.SourceSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ContextPickerModelTest {
    private class Stub(override val id: String, override val group: String) : ContextAction {
        override val title get() = id
        override fun isAvailable(request: ContextRequest) = true
        override fun perform(request: ContextRequest) {}
    }

    private fun context(kind: ContextScopeKind, lines: IntRange?, text: String, path: String? = "src/a/B.kt", patch: Boolean = false,
        files: List<String> = listOfNotNull(path)) = CodeContext(kind, "label", path, null, lines, SourceSide.CURRENT, text, patch, files = files)

    @Test
    fun noActionsLeavesTheMutedNote() {
        assertEquals(listOf(PickerRow.Empty), actionRows(emptyList()))
    }

    @Test
    fun rowsListEachHeaderBeforeItsActions() {
        val a = Stub("a", "Open")
        val b = Stub("b", "Open")
        val c = Stub("c", "Copy")
        assertEquals(listOf(PickerRow.Header("Open"), PickerRow.Item(a), PickerRow.Item(b), PickerRow.Header("Copy"), PickerRow.Item(c)),
            actionRows(arrangeActions(listOf(c, b, a))))
    }

    @Test
    fun blankFilterKeepsEveryActionAndSelectsNothing() {
        val a = Stub("Open in editor", "Open")
        val matches = filterActions(listOf(a), "  ")
        assertEquals(actionRows(arrangeActions(listOf(a))), matches.rows)
        assertNull(matches.best)
    }

    @Test
    fun filterMatchesWordStartsAndCaseAndKeepsTheSections() {
        val editor = Stub("Open in editor", "Open")
        val diff = Stub("Open IDE diff", "Open")
        val graph = Stub("Call graph", "Explore")
        val path = Stub("Copy path", "Copy")
        val matches = filterActions(listOf(editor, diff, graph, path), "op dif")
        assertEquals(listOf(PickerRow.Header("Open"), PickerRow.Item(diff)), matches.rows)
        assertSame(diff, matches.best)
        assertEquals(listOf(PickerRow.Header("Explore"), PickerRow.Item(graph)), filterActions(listOf(editor, graph, path), "GRAPH").rows)
    }

    @Test
    fun aTitleMatchBeatsAGroupMatch() {
        val copyOpen = Stub("Copy open files", "Copy")
        val editor = Stub("Editor", "Open")
        val matches = filterActions(listOf(editor, copyOpen), "open")
        assertEquals(listOf(PickerRow.Header("Open"), PickerRow.Item(editor), PickerRow.Header("Copy"), PickerRow.Item(copyOpen)), matches.rows)
        assertSame("Only the title of the copy action names it", copyOpen, matches.best)
    }

    @Test
    fun aFilterWithoutMatchesLeavesItsOwnNote() {
        assertEquals(listOf(PickerRow.NoMatch), filterActions(listOf(Stub("Copy path", "Copy")), "zzz").rows)
        assertEquals(listOf(PickerRow.Empty), filterActions(emptyList(), "zzz").rows)
    }
    @Test
    fun detailNamesTheLinesOrTheSize() {
        val lines = { n: Int -> "$n lines" }
        val files = { n: Int -> "$n files" }
        assertEquals("42", scopeDetail(context(ContextScopeKind.LINE, 42..42, "x"), lines, files))
        assertEquals("10-20", scopeDetail(context(ContextScopeKind.SYMBOL, 10..20, "x"), lines, files))
        assertEquals("3 lines", scopeDetail(context(ContextScopeKind.FILE, null, "a\nb\nc\n"), lines, files))
        assertEquals("2 files", scopeDetail(context(ContextScopeKind.CHANGE, null, "x", null, true, listOf("a", "b")), lines, files))
    }

    @Test
    fun previewFollowsThePathOrThePatch() {
        assertEquals("B.kt", previewFileName(context(ContextScopeKind.FILE, null, "")))
        assertEquals("context.diff", previewFileName(context(ContextScopeKind.HUNK, 1..2, "", patch = true)))
        assertEquals("context.txt", previewFileName(context(ContextScopeKind.CHANGE, null, "", path = null)))
        assertEquals(10, previewFirstLine(context(ContextScopeKind.SYMBOL, 10..12, "")))
        assertEquals(null, previewFirstLine(context(ContextScopeKind.HUNK, 10..12, "", patch = true)))
    }
}
