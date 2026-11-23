package dev.insight.diff.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkModelTest {
    @Test fun `multi-hunk patches split into stable readable hunks`() {
        val hunks = walkHunks(RenameFixture.user)
        assertEquals(listOf("src/User.kt#1", "src/User.kt#2"), hunks.map { it.id })
        val (first, second) = hunks
        assertEquals("@@ -1,3 +1,3 @@ class Users", first.header)
        assertEquals("class Users", first.section)
        assertEquals(1 to 1, first.added to first.removed)
        assertEquals(1, first.patchRow)
        assertTrue(first.patch.startsWith("@@ -1,3") && first.patch.endsWith(" }\n"))
        assertEquals(listOf(20, 3, 20, 4), listOf(second.oldStart, second.oldCount, second.newStart, second.newCount))
        assertEquals(20..23, second.newLines)
        assertEquals(1 to 0, second.added to second.removed)
        assertEquals(6, second.patchRow)
        assertFalse(second.wholeFile)
    }

    @Test fun `files without hunks become one whole-file hunk`() {
        val binary = walkHunks(RenameFixture.logo).single()
        assertEquals("images/logo.png#1", binary.id)
        assertTrue(binary.wholeFile)
        assertTrue(binary.patch.startsWith("[Diff content unavailable"))
        val renamed = walkHunks(RenameFixture.moved).single()
        assertEquals("src/New.kt#1", renamed.id)
        assertEquals("src/Old.kt", renamed.previousPath)
        assertEquals("renamed", renamed.fileStatus)
        assertTrue(renamed.wholeFile && renamed.newLines.isEmpty())
        val empty = walkHunks(reviewFile("empty.txt", "", status = "added", after = "")).single()
        assertEquals(0, empty.added)
    }

    @Test fun `trailing notes stay with the last hunk and added files keep their status`() {
        val hunk = walkHunks(reviewFile("src/N.kt", "@@ -0,0 +1,2 @@\n+a\n+b\n[After: no newline at end of file]\n", status = "added")).single()
        assertTrue(hunk.patch.endsWith("[After: no newline at end of file]\n"))
        assertEquals(2, hunk.added)
        assertEquals("added", hunk.fileStatus)
        assertEquals(1..2, hunk.newLines)
        assertTrue(hunk.oldLines.isEmpty())
    }

    @Test fun `every file of a snapshot yields hunks in order`() {
        assertEquals(listOf("src/User.kt#1", "src/User.kt#2", "src/A.kt#1", "src/B.kt#1", "src/test/UserTest.kt#1",
            "images/logo.png#1", "src/New.kt#1"), RenameFixture.hunkIds)
    }

    @Test fun `links group by relation and direction with labels`() {
        val hunks = walkHunks(RenameFixture.files)
        val walk = Walkthrough(hunks, links = listOf(
            WalkLink("src/User.kt#1", "src/A.kt#1", WalkRelation.CAUSES, "calls fetchUser"),
            WalkLink("src/User.kt#1", "src/B.kt#1", WalkRelation.CAUSES, "calls fetchUser", confidence = 0.2),
            WalkLink("src/A.kt#1", "src/B.kt#1", WalkRelation.SAME_EDIT, "same rename"),
            WalkLink("src/User.kt#1", "src/test/UserTest.kt#1", WalkRelation.TESTS, "tests fetchUser"),
        ))
        val ofRoot = walk.linksOf("src/User.kt#1")
        assertEquals(listOf("leads to", "tested by"), ofRoot.map { it.label })
        assertEquals(listOf("src/A.kt#1", "src/B.kt#1"), ofRoot.first().hunkIds)
        val ofA = walk.linksOf("src/A.kt#1")
        assertEquals(listOf("caused by" to LinkDirection.INCOMING, "same edit as" to LinkDirection.SYMMETRIC), ofA.map { it.label to it.direction })
        assertEquals(listOf("tests"), walk.linksOf("src/test/UserTest.kt#1").map { it.label })
        assertEquals(listOf("src/A.kt#1", "src/test/UserTest.kt#1"), walk.neighbors("src/User.kt#1", includeWeak = false))
        assertTrue(walk.links[1].weak)
        assertEquals(WalkLink("b", "a", WalkRelation.SAME_EDIT, "x").key, WalkLink("a", "b", WalkRelation.SAME_EDIT, "y").key)
    }

    @Test fun `ensureEveryHunkShown lists missing hunks in an unlinked step by path`() {
        val hunks = walkHunks(RenameFixture.files)
        val step = WalkStep("S1", "Rename", "x", "src/User.kt#1", listOf("src/User.kt#1", "src/A.kt#1"))
        val walk = Walkthrough(hunks, listOf(step), status = hunks.associate { it.id to HunkStatus.PENDING } +
            mapOf("src/User.kt#1" to HunkStatus.LINKED, "src/A.kt#1" to HunkStatus.COVERED))
        assertEquals(5, walk.missingHunks().size)
        val shown = walk.ensureEveryHunkShown()
        assertTrue(shown.missingHunks().isEmpty())
        val unlinked = shown.steps.last()
        assertEquals(WalkStepKind.UNLINKED, unlinked.kind)
        assertEquals(WalkStep.UNLINKED_TITLE, unlinked.title)
        assertEquals(listOf("images/logo.png#1", "src/B.kt#1", "src/New.kt#1", "src/User.kt#2", "src/test/UserTest.kt#1"), unlinked.hunks)
        assertEquals(HunkStatus.COVERED, shown.statusOf("src/A.kt#1"))
        assertEquals(5, shown.coverage()[HunkStatus.UNLINKED])
        assertEquals(2, shown.coverage().handled)
        assertEquals(hunks.map { it.id }.toSet(), shown.steps.flatMap { it.hunks }.toSet())
        assertSame(shown, shown.ensureEveryHunkShown())
        assertEquals(listOf("S1"), shown.stepsOf("src/A.kt#1").map { it.id })
        assertEquals("S1", shown.primaryStep("src/User.kt#1")?.id)
    }

    @Test fun `unlinked walkthrough shows every hunk without a model`() {
        val walk = Walkthrough.unlinked(RenameFixture.files)
        assertEquals(RenameFixture.hunkIds.toSet(), walk.steps.single().hunks.toSet())
        assertTrue(walk.status.values.all { it == HunkStatus.UNLINKED })
        assertTrue(Walkthrough.pending(walkHunks(RenameFixture.files)).status.values.all { it == HunkStatus.PENDING })
    }
}
