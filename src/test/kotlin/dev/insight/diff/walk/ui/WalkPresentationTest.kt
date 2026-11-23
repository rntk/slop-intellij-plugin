package dev.insight.diff.walk.ui

import dev.insight.diff.walk.HunkStatus
import dev.insight.diff.walk.RenameFixture
import dev.insight.diff.walk.WalkCoverage
import dev.insight.diff.walk.WalkLink
import dev.insight.diff.walk.WalkPhase
import dev.insight.diff.walk.WalkProgress
import dev.insight.diff.walk.WalkRelation
import dev.insight.diff.walk.WalkStep
import dev.insight.diff.walk.WalkStepKind
import dev.insight.diff.walk.Walkthrough
import dev.insight.diff.walk.walkHunks
import dev.insight.review.ui.ReviewTileStat
import dev.insight.review.wall.WallLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rename fixture walked: the declaration leads to two call sites (one edit) and is weakly tested; one standalone. */
internal object WalkedFixture {
    const val DECL = "src/User.kt#1"
    const val TTL = "src/User.kt#2"
    const val A = "src/A.kt#1"
    const val B = "src/B.kt#1"
    const val TEST = "src/test/UserTest.kt#1"
    const val LOGO = "images/logo.png#1"
    const val MOVED = "src/New.kt#1"
    val hunks = walkHunks(RenameFixture.files)
    val links = listOf(
        WalkLink(DECL, A, WalkRelation.CAUSES, "calls the renamed function"),
        WalkLink(DECL, B, WalkRelation.CAUSES, "calls the renamed function"),
        WalkLink(A, B, WalkRelation.SAME_EDIT, "same rename"),
        WalkLink(DECL, TEST, WalkRelation.TESTS, "covers the rename", confidence = 0.3),
    )
    val steps = listOf(
        WalkStep("S1", "Rename loadUser to fetchUser", "`loadUser` became **fetchUser**; see src/A.kt:6.", DECL, listOf(DECL, A, B, TEST)),
        WalkStep("S2", "Cache TTL", "A new TTL constant.", TTL, listOf(TTL), WalkStepKind.STANDALONE),
    )
    val status = mapOf(DECL to HunkStatus.LINKED, A to HunkStatus.COVERED, B to HunkStatus.COVERED, TEST to HunkStatus.LINKED,
        TTL to HunkStatus.STANDALONE, LOGO to HunkStatus.PENDING, MOVED to HunkStatus.PENDING)
    val running = Walkthrough(hunks, steps, links, status)
    val finished = running.ensureEveryHunkShown().copy(complete = true, warnings = listOf("Stopped at the budget of 1 requests."))
}

class WalkPresentationTest {
    private val w = WalkedFixture.running

    @Test fun `chips name each link group with its count`() {
        assertEquals(listOf("leads to 2", "tested by 1"), w.linksOf(WalkedFixture.DECL).map(::walkChipLabel))
        assertEquals(listOf("caused by 1", "same edit as 1"), w.linksOf(WalkedFixture.A).map(::walkChipLabel))
        assertEquals(listOf("tests 1"), w.linksOf(WalkedFixture.TEST).map(::walkChipLabel))
        assertTrue(w.linksOf(WalkedFixture.LOGO).isEmpty())
    }

    @Test fun `coverage segments follow the status order and stay clickable when small`() {
        val segments = walkCoverageSegments(w.coverage())
        assertEquals(listOf(HunkStatus.LINKED, HunkStatus.COVERED, HunkStatus.STANDALONE, HunkStatus.PENDING), segments.map { it.status })
        assertEquals(listOf(2, 2, 1, 2), segments.map { it.count })
        assertEquals(1.0, segments.sumOf { it.fraction }, 1e-9)
        assertEquals("2 linked", segments.first().label)
        assertEquals(listOf(0 until 19, 19 until 38, 38 until 50, 50 until 70), walkSegmentSpans(segments, 70))
        assertEquals(HunkStatus.LINKED, walkSegmentAt(segments, 70, 0)?.status)
        assertEquals(HunkStatus.STANDALONE, walkSegmentAt(segments, 70, 45)?.status)
        assertEquals(HunkStatus.PENDING, walkSegmentAt(segments, 70, 69)?.status)
        assertNull(walkSegmentAt(segments, 70, 70))
        val skewed = walkCoverageSegments(WalkCoverage(1001, mapOf(HunkStatus.LINKED to 1000, HunkStatus.UNLINKED to 1)))
        assertTrue(walkSegmentSpans(skewed, 100).last().let { it.last - it.first + 1 } >= 6)
        assertTrue(walkCoverageSegments(WalkCoverage(0, emptyMap())).isEmpty())
        assertEquals("5 / 7 changes handled · 2 linked · 2 covered · 1 standalone · 2 pending", walkCoverageText(w.coverage()))
    }

    @Test fun `the tile counts steps and linked changes once there are steps`() {
        assertTrue(walkTileStats(null).isEmpty())
        assertTrue(walkTileStats(Walkthrough.pending(WalkedFixture.hunks)).isEmpty())
        assertEquals(listOf(ReviewTileStat("2", "steps"), ReviewTileStat("5 / 7", "changes linked")), walkTileStats(WalkedFixture.finished))
        assertEquals(3, WalkedFixture.finished.steps.size)
        assertEquals(2, walkStoryCount(WalkedFixture.finished))
    }

    @Test fun `the step list lists steps, then hunks in no step, and filters by status or canvas`() {
        val all = walkListRows(w, WalkListFilter.ALL, emptySet(), emptySet())
        assertEquals(listOf("S1", "S2", WALK_PENDING_GROUP), all.map { (it as WalkListRow.Group).id })
        val pendingGroup = all.last() as WalkListRow.Group
        assertTrue(pendingGroup.flagged)
        assertEquals("2 changes", pendingGroup.countText)

        val open = walkListRows(w, WalkListFilter.ALL, setOf("S1"), setOf(WalkedFixture.DECL))
        val hunkRows = open.filterIsInstance<WalkListRow.Hunk>()
        assertEquals(listOf(WalkedFixture.DECL, WalkedFixture.A, WalkedFixture.B, WalkedFixture.TEST), hunkRows.map { it.hunk.id })
        assertEquals(listOf(true, false, false, false), hunkRows.map { it.root })
        assertEquals(listOf(true, false, false, false), hunkRows.map { it.onCanvas })
        assertEquals(HunkStatus.COVERED, hunkRows[1].status)

        val covered = walkListRows(w, WalkListFilter.COVERED, emptySet(), emptySet())
        val group = covered.first() as WalkListRow.Group
        assertEquals("S1", group.id)
        assertEquals("2 of 4 changes", group.countText)
        assertTrue("A lone group is expanded", group.expanded)
        assertEquals(listOf(WalkedFixture.A, WalkedFixture.B), covered.filterIsInstance<WalkListRow.Hunk>().map { it.hunk.id })

        val pending = walkListRows(w, WalkListFilter.of(HunkStatus.PENDING), emptySet(), emptySet())
        assertEquals(listOf(WalkedFixture.LOGO, WalkedFixture.MOVED), pending.filterIsInstance<WalkListRow.Hunk>().map { it.hunk.id })

        val shown = setOf(WalkedFixture.DECL, WalkedFixture.A, WalkedFixture.B, WalkedFixture.TEST, WalkedFixture.TTL)
        val notShown = walkListRows(w, WalkListFilter.NOT_ON_CANVAS, emptySet(), shown)
        assertEquals(listOf(WALK_PENDING_GROUP), notShown.filterIsInstance<WalkListRow.Group>().map { it.id })

        val finished = walkListRows(WalkedFixture.finished, WalkListFilter.ALL, emptySet(), emptySet()).filterIsInstance<WalkListRow.Group>()
        assertEquals(listOf("S1", "S2", "unlinked"), finished.map { it.id })
        assertTrue(finished.last().flagged)
        assertEquals("not linked", finished.last().badge)
        assertEquals("chain", finished.first().badge)

        val before = walkListRows(Walkthrough.pending(WalkedFixture.hunks), WalkListFilter.ALL, emptySet(), emptySet())
        assertEquals("Before a run every hunk is listed", 1 + WalkedFixture.hunks.size, before.size)
        assertTrue((before.first() as WalkListRow.Group).expanded)
    }

    @Test fun `hunk cards reuse the wall's rows without the header`() {
        val decl = w.hunk(WalkedFixture.DECL)!!
        val rows = walkHunkRows(decl, RenameFixture.user)
        assertEquals("src/User.kt", rows.block.path)
        assertEquals(listOf(WallLine.Kind.CONTEXT, WallLine.Kind.REMOVED, WallLine.Kind.ADDED, WallLine.Kind.CONTEXT), rows.lines.map { it.kind })
        assertEquals(listOf(1, 2, null, 3), rows.lines.map { it.old })
        assertEquals(listOf(1, null, 2, 3), rows.lines.map { it.new })
        assertEquals(0, rows.hidden)
        val cut = walkHunkRows(decl, null, maxRows = 2)
        assertEquals(2, cut.lines.size)
        assertEquals(2, cut.hidden)
        val moved = walkHunkRows(w.hunk(WalkedFixture.MOVED)!!, RenameFixture.moved)
        assertEquals(WallLine.Kind.NOTE, moved.lines.single().kind)
        assertEquals("lines 1–3 · class Users", walkHunkPlace(decl))
        assertEquals("whole file · renamed from Old.kt", walkHunkPlace(w.hunk(WalkedFixture.MOVED)!!))
        assertEquals("src/Old.kt → src/New.kt", walkHunkTitle(w.hunk(WalkedFixture.MOVED)!!))
        assertEquals("User.kt #2", walkHunkName(w.hunk(WalkedFixture.TTL)!!))
    }

    @Test fun `link reasons read from the hunk and note weak verification`() {
        val reasons = walkLinkReasons(WalkedFixture.DECL, w.linksTouching(WalkedFixture.DECL)) { w.hunk(it)!!.let(::walkHunkName) }
        assertEquals(listOf("leads to A.kt #1: calls the renamed function", "leads to B.kt #1: calls the renamed function",
            "tested by UserTest.kt #1: covers the rename (weak, 30% sure)"), reasons)
        assertEquals("caused by User.kt #1: calls the renamed function",
            walkLinkReasons(WalkedFixture.A, w.linksTouching(WalkedFixture.A).take(1)) { w.hunk(it)!!.let(::walkHunkName) }.single())
    }

    @Test fun `notes read as plain wrapped text`() {
        assertEquals("Title\n• bold code link", walkPlainText("## Title\n- **bold** `code` [link](src/A.kt)"))
        val length: (String) -> Int = { it.length }
        assertEquals(listOf("aaa bbb", "ccc"), walkWrap("aaa bbb ccc", 7, length))
        assertEquals(listOf("abcd", "efgh", "ij"), walkWrap("abcdefghij", 4, length))
        assertEquals(listOf("aaa bbb…"), walkWrap("aaa bbb ccc", 7, length, maxLines = 1))
        assertEquals(listOf("a", "", "b"), walkWrap("a\n\nb", 7, length))
    }

    @Test fun `progress and labels`() {
        assertEquals("Walking · 3 / 7 handled · 1 step · turn 2 / 80 · 5 requests — Turn 2: x",
            walkProgressText(WalkProgress(WalkPhase.WALKING, 3, 7, 1, turn = 2, maxTurns = 80, requests = 5, message = "Turn 2: x")))
        assertEquals("Classifying · 0 / 7 handled · 0 steps", walkProgressText(WalkProgress(WalkPhase.TRIAGE, 0, 7, 0)))
        assertEquals(WalkListFilter.UNLINKED, WalkListFilter.of(HunkStatus.UNLINKED))
        assertEquals("not linked", walkStatusLabel(HunkStatus.UNLINKED))
    }
}
