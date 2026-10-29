package dev.insight.diff

import dev.insight.review.ChatFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffReviewPromptsTest {
    @Test fun `focused diff pins its whole patch and describes the line focus`() {
        val file = ReviewFile("a.kt", "/project/a.kt", null, "MODIFIED", "@@ -1 +1 @@\n-old\n+new", "fingerprint")
        val other = file.copy(path = "b.kt", patch = "unrelated patch")
        assertEquals("", diffFocusPrompt(listOf(file), null))
        val prompt = diffFocusPrompt(listOf(file, other), ChatFocus("part", listOf(file.path), 4..6))
        assertTrue(prompt.contains(file.patch))
        assertTrue(prompt.contains("The file's diff covers the whole file; the focus is lines 4-6 of it."))
        assertFalse(prompt.contains(other.patch))
    }

    @Test fun `removed focus files do not claim to pin a whole file diff`() {
        val prompt = diffFocusPrompt(emptyList(), ChatFocus("gone", emptyList(), 4..6))
        assertTrue(prompt.contains("The focus no longer has files"))
        assertFalse(prompt.contains("The file's diff covers the whole file"))
    }
}
