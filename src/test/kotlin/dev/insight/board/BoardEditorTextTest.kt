package dev.insight.board

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class BoardEditorTextTest {
    @Test
    fun `ask drafts name the block by kind and what tells it apart`() {
        assertEquals("About block c1 (code · Handler): ", boardAskDraft(CodeBlock("c1", anchor(5, 9), "x", title = "Handler")))
        assertEquals("About block c2 (diff · src/A.kt:5-9): ", boardAskDraft(CodeBlock("c2", anchor(5, 9), "x", kind = CodeKind.DIFF)))
        assertEquals("About block t1 (warning · Watch out): ",
            boardAskDraft(TextBlock("t1", "Body", title = "Watch\n  out", tone = Tone.WARNING)))
        assertEquals("About block s1 (steps): ", boardAskDraft(StepsBlock("s1", listOf(Step("a")))))
        assertEquals("About block ch (chain · main → run): ",
            boardAskDraft(ChainBlock("ch", listOf(ChainStep(1, "main"), ChainStep(2, "run")))))
    }

    @Test
    fun `long labels are clipped`() {
        val label = boardBlockLabel(TextBlock("t1", "x".repeat(100)), max = 10)
        assertEquals("text · xxxxxxxxx…", label)
    }

    @Test
    fun `status shows the block count and save state`() {
        assertEquals("1 block · saved", boardStatusText(1, BoardSaveState.SAVED))
        assertEquals("3 blocks · unsaved changes", boardStatusText(3, BoardSaveState.UNSAVED))
        assertEquals("0 blocks · not saved yet", boardStatusText(0, BoardSaveState.NEW))
    }

    @Test
    fun `saved board entries show title, blocks and time`() {
        val entry = BoardEntry("b1", "How retries work", null, 0, 86_400_000L + 3_600_000L, 4)
        assertEquals("How retries work · 4 blocks · 1970-01-02 01:00", boardEntryLabel(entry, ZoneOffset.UTC))
    }
}
