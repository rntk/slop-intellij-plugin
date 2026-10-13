package dev.insight.callgraph

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Hunks from the IDE's line comparison, the way [DiffService] computes them for a modified file. */
class DiffHunksTest : BasePlatformTestCase() {
    private val before = (1..20).joinToString("\n", postfix = "\n") { "line $it" }

    fun `test modified line maps to its new-side range`() {
        val after = before.replace("line 8\n", "line 8 changed\n")
        val changes = DiffService.lineChanges(before, after)
        assertEquals(listOf(LineChange(7, 8, 7, 8)), changes)
        val diff = fileDiffOf("/p/a.kt", null, changes, lineCount(after), contextLines = 0)
        assertEquals(listOf(8..8), diff.hunks)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(listOf(5..11), fileDiffOf("/p/a.kt", null, changes, lineCount(after)).hunks)
    }

    fun `test insertion and deletion`() {
        val after = before.replace("line 3\n", "line 3\nnew a\nnew b\n").replace("line 15\nline 16\n", "")
        val diff = fileDiffOf("/p/a.kt", null, DiffService.lineChanges(before, after), lineCount(after), contextLines = 0)
        // Inserted after line 3 -> new lines 4..5; lines 15..16 removed after new line 16 (old 14 + 2 inserted).
        assertEquals(listOf(4..5, 16..16), diff.hunks)
        assertEquals(2, diff.added)
        assertEquals(2, diff.removed)
    }

    fun `test identical texts have no hunks`() {
        assertEquals(emptyList<LineChange>(), DiffService.lineChanges(before, before))
    }
}
