package dev.insight.context

import dev.insight.agent.SourceSide
import dev.insight.diff.ReviewFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextScopesTest {
    private val before = (1..20).joinToString("\n", postfix = "\n") { "line $it" }
    // Line 3 removed, a line added after 5; line 15 replaced by two lines.
    private val after = (1..20).flatMap { n ->
        when (n) { 3 -> emptyList(); 5 -> listOf("line 5", "added"); 15 -> listOf("new 15a", "new 15b"); else -> listOf("line $n") }
    }.joinToString("\n", postfix = "\n")
    private val patch = """
        @@ -1,8 +1,8 @@
         line 1
         line 2
        -line 3
         line 4
         line 5
        +added
         line 6
         line 7
         line 8
        @@ -12,7 +12,8 @@
         line 12
         line 13
         line 14
        -line 15
        +new 15a
        +new 15b
         line 16
         line 17
         line 18
        [After: no newline at end of file]
    """.trimIndent() + "\n"

    private fun file(path: String = "src/A.kt", patch: String = this.patch) = ReviewFile(path, "/p/$path", null, "modified", patch, "1", before, after)

    private val method = SymbolSpan(ContextSymbol("render", "A.render", "function"), 4..7)
    private val type = SymbolSpan(ContextSymbol("A", "A", "class"), 1..19)

    @Test
    fun hunksCoverBothSidesIncludingAddedAndRemovedOnlyLines() {
        val hunks = patchHunks(patch)
        assertEquals(2, hunks.size)
        assertEquals(1..8, hunks[0].before)
        assertEquals(12..19, hunks[1].after)
        // The note after the last hunk is not a patch row.
        assertFalse(hunks[1].rows.any { it.startsWith("[") })
        assertEquals(hunks[0], hunkAt(patch, 3, SourceSide.BEFORE))   // removed-only line
        assertEquals(hunks[0], hunkAt(patch, 5, SourceSide.AFTER))    // added-only line
        assertEquals(hunks[1], hunkAt(patch, 15, SourceSide.BEFORE))
        assertEquals(hunks[1], hunkAt(patch, 16, SourceSide.AFTER))
        assertNull(hunkAt(patch, 10, SourceSide.AFTER))
        assertNull(hunkAt(patch, 20, SourceSide.BEFORE))
    }

    @Test
    fun emptySideOfAHunkAnchorsOnItsLine() {
        val deletion = "@@ -4,2 +3,0 @@\n-a\n-b\n"
        assertEquals(3..3, patchHunks(deletion).single().after)
        assertEquals(4..5, hunkAt(deletion, 5, SourceSide.BEFORE)!!.before)
        assertEquals(1..1, patchHunks("@@ -0,0 +1 @@\n+x\n").single().before)
    }

    @Test
    fun diffScopesRunNarrowToWide() {
        val scopes = diffScopes(file(), 5, SourceSide.AFTER, listOf(method, type), listOf(file(), file("src/B.kt")))
        assertEquals(listOf(ContextScopeKind.LINE, ContextScopeKind.SYMBOL, ContextScopeKind.SYMBOL, ContextScopeKind.HUNK,
            ContextScopeKind.FILE_DIFF, ContextScopeKind.FILE, ContextScopeKind.CHANGE), scopes.map { it.kind })
        assertEquals("Line 5", scopes[0].label)
        assertEquals("added", scopes[0].text)
        assertEquals("Function render", scopes[1].label)
        assertEquals("line 5\nadded\nline 6\nline 7", scopes[1].text)
        assertEquals(1, defaultScopeIndex(scopes))
        val hunk = scopes[3]
        assertEquals("Hunk @@ -1,8 +1,8 @@", hunk.label)
        assertTrue(hunk.isPatch)
        assertEquals(1..8, hunk.lines)
        assertTrue(hunk.text.startsWith("@@ -1,8 +1,8 @@\n line 1\n"))
        assertEquals(after, scopes[5].text)
        val change = scopes[6]
        assertNull(change.path)
        assertEquals(listOf("src/A.kt", "src/B.kt"), change.files)
        assertTrue(change.text.contains("+++ b/src/B.kt\n@@ -1,8"))
    }

    @Test
    fun beforeSideUsesBeforeText() {
        val scopes = diffScopes(file(), 3, SourceSide.BEFORE, emptyList(), null)
        assertEquals("line 3", scopes.first().text)
        assertEquals(SourceSide.BEFORE, scopes.first().side)
        assertEquals(before, scopes.single { it.kind == ContextScopeKind.FILE }.text)
        // Without symbols the hunk is picked first.
        assertEquals(ContextScopeKind.HUNK, scopes[defaultScopeIndex(scopes)].kind)
    }

    @Test
    fun lineOutsideAnyHunkHasNoHunkScope() {
        val scopes = diffScopes(file(), 10, SourceSide.AFTER, emptyList(), listOf(file()))
        assertEquals(listOf(ContextScopeKind.LINE, ContextScopeKind.FILE_DIFF, ContextScopeKind.FILE), scopes.map { it.kind })
        assertEquals(0, defaultScopeIndex(scopes))
    }

    @Test
    fun lineWithoutSideTextComesFromItsPatchRow() {
        val scopes = diffScopes(file().copy(before = null, after = null), 5, SourceSide.AFTER, emptyList(), null)
        assertEquals("added", scopes.first().text)
        assertTrue(scopes.none { it.kind == ContextScopeKind.FILE })
    }

    @Test
    fun sourceScopesStartWithTheSelection() {
        val scopes = sourceScopes("src/A.kt", "/p/src/A.kt", before, 5, 5..6, listOf(method), 4..6)
        assertEquals(listOf(ContextScopeKind.SELECTION, ContextScopeKind.LINE, ContextScopeKind.SYMBOL, ContextScopeKind.HUNK,
            ContextScopeKind.FILE), scopes.map { it.kind })
        assertEquals("line 5\nline 6", scopes[0].text)
        assertEquals(0, defaultScopeIndex(scopes))
        assertTrue(scopes.all { it.side == SourceSide.CURRENT })
        val plain = sourceScopes("src/A.kt", null, before, 5, null, emptyList(), null)
        assertEquals(listOf(ContextScopeKind.LINE, ContextScopeKind.FILE), plain.map { it.kind })
        assertEquals(0, defaultScopeIndex(plain))
        assertEquals(1, defaultScopeIndex(sourceScopes("src/A.kt", null, before, 5, null, emptyList(), 4..6)))
    }

    @Test
    fun longTextIsCutAtALineAndMarked() {
        val text = (1..100).joinToString("\n", postfix = "\n") { "x".repeat(9) }
        val capped = capContext(text, 25)
        assertEquals("xxxxxxxxx\nxxxxxxxxx\n… (truncated: 98 more lines)\n", capped)
        assertEquals(text, capContext(text))
        val file = sourceScopes("big.txt", null, "y".repeat(MAX_CONTEXT_CHARS * 2), 1, null, emptyList(), null).last()
        assertTrue(file.text.length < MAX_CONTEXT_CHARS + 100)
        assertTrue(file.text.endsWith("(truncated: 1 more lines)\n"))
    }
}
