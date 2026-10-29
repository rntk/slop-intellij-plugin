package dev.insight.codereview

import dev.insight.review.ChatFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeReviewPromptsTest {
    @Test fun `scope describes roots entries and selected paths`() {
        assertEquals("the project root", codePromptScope(CodeScope("", "/project"), null))
        assertEquals("src", codePromptScope(CodeScope("src", "/project/src"), null))
        assertEquals("2 selected paths of src", codePromptScope(CodeScope("src", "/project/src"), setOf("src/a.kt", "src/b.kt")))
        assertEquals("the entry file src/main.kt and the project files it depends on (imports, calls and references, transitively)",
            codePromptScope(CodeScope("src/main.kt", "/project/src/main.kt", entry = true), null))
    }

    @Test fun `single file focus pins only the numbered part and reports missing evidence`() {
        val snapshot = codeSnapshot(codeFile("a.kt", "first\nsecond\nthird"), codeFile("b.kt", "unrelated"))
        assertEquals("", codeFocusPrompt(snapshot, null))
        val prompt = codeFocusPrompt(snapshot, ChatFocus("part", listOf("a.kt"), 2..2))
        assertTrue(prompt.contains("2: second"))
        assertFalse(prompt.contains("1: first"))
        assertFalse(prompt.contains("3: third"))
        assertFalse(prompt.contains("unrelated"))
        assertTrue(codeFocusPrompt(snapshot, ChatFocus("missing", listOf("missing.kt")))
            .contains("Unavailable in this tab's current scope"))
    }

    @Test fun `multi file focus pins whole files even when the focus has a line range`() {
        val snapshot = codeSnapshot(codeFile("a.kt", "first\nsecond"), codeFile("b.kt", "other"))
        val prompt = codeFocusPrompt(snapshot, ChatFocus("files", listOf("a.kt", "b.kt"), 2..2))
        assertTrue(prompt.contains("1: first\n2: second"))
        assertTrue(prompt.contains("1: other"))
    }
}
