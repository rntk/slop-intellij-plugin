package dev.insight.diff

import dev.insight.agent.SourceSide
import dev.insight.context.CodeContext
import dev.insight.context.ContextScopeKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewDiffSessionTest {
    @Test
    fun selectedScopeKeepsCapturedFilesAndUsesCanonicalRenamePath() {
        val renamed = ReviewFile("new.kt", "/p/new.kt", "old.kt", "renamed", "patch", "1", "old", "new")
        val deleted = ReviewFile("gone.kt", "/p/gone.kt", null, "deleted", "patch", "2", "old", null)
        var opened = emptyList<ReviewFile>()
        val session = ReviewDiffSession(listOf(renamed, deleted), { files, _ -> opened = files })
        val before = CodeContext(ContextScopeKind.HUNK, "Hunk", "old.kt", null, 1..1, SourceSide.BEFORE, "old")
        session.openContext(renamed, before)
        assertEquals(listOf(renamed), opened)
        session.openContext(deleted, before)
        assertEquals(listOf(deleted), opened)
        session.openContext(renamed, before.copy(kind = ContextScopeKind.CHANGE, files = listOf("new.kt", "gone.kt")))
        assertEquals(listOf(renamed, deleted), opened)
    }
}
