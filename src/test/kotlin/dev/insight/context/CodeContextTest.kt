package dev.insight.context

import dev.insight.agent.SourceSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeContextTest {
    private fun context(lines: IntRange?, text: String = "val x = 1", side: SourceSide = SourceSide.CURRENT, patch: Boolean = false) =
        CodeContext(ContextScopeKind.LINE, "Line 3", "src/A.kt", "/p/src/A.kt", lines, side, text, patch)

    @Test
    fun locationNamesPathAndLines() {
        assertEquals("src/A.kt:10-20", context(10..20).location())
        assertEquals("src/A.kt:3", context(3..3).location())
        assertEquals("src/A.kt", context(null).location())
        val change = CodeContext(ContextScopeKind.CHANGE, "Change", null, null, null, SourceSide.AFTER, "", true, files = listOf("a", "b"))
        assertEquals("2 files", change.location())
    }

    @Test
    fun markdownFencesSourceByExtensionAndPatchesAsDiff() {
        assertEquals("**Line 3** `src/A.kt:3`\n```kt\nval x = 1\n```\n", context(3..3).toMarkdown())
        assertEquals("**Line 3** `src/A.kt:1-2` (before)\n```diff\n@@ -1 +1 @@\n-a\n+b\n```\n",
            context(1..2, "@@ -1 +1 @@\n-a\n+b\n", SourceSide.BEFORE, patch = true).toMarkdown())
        // A fence inside the text lengthens the outer fence.
        assertEquals("**Line 3** `src/A.kt:3`\n````kt\n```\n````\n", context(3..3, "```").toMarkdown())
    }

    @Test
    fun dataIsTyped() {
        val count = ContextKey<Int>("count")
        val data = ContextData().put(count, 3)
        assertEquals(3, data[count])
        assertNull(data[ContextKey<Int>("count")])
    }
}
