package dev.insight.diff.walk.ui

import dev.insight.diff.walk.LinkDirection
import dev.insight.diff.walk.WalkLink
import dev.insight.diff.walk.WalkLinkGroup
import dev.insight.diff.walk.WalkRelation
import dev.insight.diff.walk.WalkStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkBoardTest {
    private val spacing = WalkSpacing(columnGap = 100, rowGap = 10, noteGap = 5, sectionGap = 50, left = 0, top = 0)
    private fun board(height: (String) -> Int = { 100 }) = WalkBoard({ WalkSize(300, height(it)) }, { WalkSize(300, 40) }, spacing)

    private fun group(relation: WalkRelation, direction: LinkDirection, from: String, vararg to: String) = WalkLinkGroup(relation, direction,
        to.map { if (direction == LinkDirection.INCOMING) WalkLink(it, from, relation, "r") else WalkLink(from, it, relation, "r") }, to.toList())

    @Test fun `a step opens its note above its root, and opening it again reveals the root`() {
        val board = board()
        val step = WalkStep("S1", "Rename", "Why", "a#1", listOf("a#1", "b#1"))
        val open = board.openStep(step)
        assertEquals("a#1", open.reveal)
        assertEquals(listOf(walkNoteId("S1"), "a#1"), open.added)
        val note = board.card(walkNoteId("S1"))!!
        val root = board.card("a#1")!!
        assertEquals(WalkRect(0, 0, 300, 40), note.rect)
        assertEquals(WalkRect(0, 45, 300, 100), root.rect)
        assertEquals(note.id, root.parent)
        assertEquals("S1", root.step)
        assertEquals(WalkOpen("a#1"), board.openStep(step))
        assertEquals(2, board.cards.size)
    }

    @Test fun `causes open in a column to the left and effects to the right, without overlaps`() {
        val board = board()
        board.openHunk("root")
        val effects = board.openGroup("root", group(WalkRelation.CAUSES, LinkDirection.OUTGOING, "root", "e1", "e2", "e3"))
        assertEquals(listOf("e1", "e2", "e3"), effects.added)
        assertEquals("e1", effects.reveal)
        assertEquals(listOf(400, 400, 400), effects.added.map { board.card(it)!!.rect.x })
        assertEquals(listOf(0, 110, 220), effects.added.map { board.card(it)!!.rect.y })
        val causes = board.openGroup("root", group(WalkRelation.CAUSES, LinkDirection.INCOMING, "root", "c1"))
        assertEquals(-400, board.card("c1")!!.rect.x)
        assertEquals(listOf("root"), causes.added.map { board.card(it)!!.parent })
        // A second group on the right goes below the column already there.
        board.openGroup("root", group(WalkRelation.TESTS, LinkDirection.OUTGOING, "root", "t1"))
        assertEquals(WalkRect(400, 330, 300, 100), board.card("t1")!!.rect)
        val rects = board.cards.map { it.rect }
        rects.forEachIndexed { i, a -> rects.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.overlaps(b)) } }
        assertEquals(listOf(WalkGroupKey(WalkRelation.CAUSES, LinkDirection.OUTGOING), WalkGroupKey(WalkRelation.CAUSES, LinkDirection.INCOMING),
            WalkGroupKey(WalkRelation.TESTS, LinkDirection.OUTGOING)), board.edges.map { it.group }.distinct())
    }

    @Test fun `a hunk already on the canvas is joined by an arrow and revealed rather than duplicated`() {
        val board = board()
        board.openHunk("a")
        board.openHunk("b")
        val open = board.openGroup("a", group(WalkRelation.SAME_EDIT, LinkDirection.SYMMETRIC, "a", "b"))
        assertTrue(open.added.isEmpty())
        assertEquals("b", open.reveal)
        assertEquals(2, board.cards.size)
        assertEquals(listOf(WalkEdge("a", "b", WalkGroupKey(WalkRelation.SAME_EDIT, LinkDirection.SYMMETRIC))), board.edges)
        board.openGroup("b", group(WalkRelation.SAME_EDIT, LinkDirection.SYMMETRIC, "b", "a"))
        assertEquals("No second arrow between the same cards", 1, board.edges.size)
        assertEquals(WalkOpen("a"), board.openHunk("a"))
    }

    @Test fun `large groups open progressively`() {
        val board = board()
        board.openHunk("root")
        val sites = (1..15).map { "site$it" }.toTypedArray()
        val rename = group(WalkRelation.SAME_EDIT, LinkDirection.SYMMETRIC, "root", *sites)
        val key = rename.key
        assertFalse(board.opened("root", key))
        assertEquals(15, board.remaining(rename))
        assertEquals(WALK_OPEN_BATCH, board.openGroup("root", rename).added.size)
        assertTrue(board.opened("root", key))
        assertEquals(15 - WALK_OPEN_BATCH, board.remaining(rename))
        assertEquals(sites.drop(WALK_OPEN_BATCH).take(WALK_OPEN_BATCH), board.openGroup("root", rename).added)
        assertEquals(3, board.openGroup("root", rename).added.size)
        assertEquals(0, board.remaining(rename))
        assertEquals("Columns keep growing downward", (0 until 15).map { it * 110 }, sites.map { board.card(it)!!.rect.y })
        assertEquals("show 9 more", walkMoreLabel(9))
    }

    @Test fun `a step's other hunks open beside its root in batches`() {
        val board = board()
        val step = WalkStep("S1", "Rename", "Why", "r", listOf("r") + (1..8).map { "h$it" })
        val linked = WalkGroupKey(WalkRelation.CAUSES, LinkDirection.OUTGOING)
        val first = board.openStepHunks(step, { if (it == "h1") linked else null }, limit = 5)
        assertEquals((1..5).map { "h$it" }, first.added)
        assertTrue(board.stepOpened("S1"))
        assertEquals(3, board.remaining(step))
        assertEquals(linked, board.edges.first { it.to == "h1" }.group)
        assertNull(board.edges.first { it.to == "h2" }.group)
        assertEquals(400, board.card("h1")!!.rect.x)
    }

    @Test fun `a card leads back to its step's root and note on its left`() {
        val board = board()
        val step = WalkStep("S1", "Rename", "Why", "r", listOf("r", "h"))
        board.openHunk("h")
        assertFalse(board.stepShownFor("h", step))
        val linked = WalkGroupKey(WalkRelation.CAUSES, LinkDirection.OUTGOING)
        val open = board.openStepOf("h", step, linked)
        assertEquals("r", open.reveal)
        assertEquals(listOf("r", walkNoteId("S1")), open.added)
        assertEquals(WalkRect(-400, 0, 300, 100), board.card("r")!!.rect)
        assertEquals(WalkRect(-400, -45, 300, 40), board.card(walkNoteId("S1"))!!.rect)
        assertEquals(listOf(walkNoteId("S1"), "r"), board.trail("r").map { it.id })
        assertEquals(listOf(WalkEdge("r", "h", linked)), board.edges)
        assertTrue(board.stepShownFor("h", step))
        // Again: nothing new, the root is revealed.
        assertEquals(WalkOpen("r"), board.openStepOf("h", step, linked))
        assertEquals(1, board.edges.size)
        val rects = board.cards.map { it.rect }
        rects.forEachIndexed { i, a -> rects.drop(i + 1).forEach { b -> assertFalse("$a overlaps $b", a.overlaps(b)) } }
    }

    @Test fun `a root already on the canvas is joined to the card, and on the root itself the step opens its note`() {
        val board = board()
        val step = WalkStep("S1", "Rename", "Why", "r", listOf("r", "h"))
        board.openHunk("r")
        board.openHunk("h")
        assertFalse(board.stepShownFor("r", step))
        assertEquals(WalkOpen("r", listOf(walkNoteId("S1"))), board.openStepOf("h", step))
        assertEquals(listOf(WalkEdge("r", "h", null)), board.edges)
        assertEquals(WalkRect(0, -45, 300, 40), board.card(walkNoteId("S1"))!!.rect)
        assertTrue(board.stepShownFor("r", step))
        assertEquals(WalkOpen("r"), board.openStepOf("r", step))
    }

    @Test fun `the trail leads from the root to a card and survives removed cards`() {
        val board = board()
        board.openStep(WalkStep("S1", "t", "e", "a", listOf("a", "b", "c")))
        board.openGroup("a", group(WalkRelation.CAUSES, LinkDirection.OUTGOING, "a", "b"))
        board.openGroup("b", group(WalkRelation.CAUSES, LinkDirection.OUTGOING, "b", "c"))
        assertEquals(listOf(walkNoteId("S1"), "a", "b", "c"), board.trail("c").map { it.id })
        board.focused = "c"
        assertEquals("c", board.focused)
        board.retain(setOf("a", "c"), setOf("S1"))
        assertNull(board.card("b"))
        assertEquals(listOf("c"), board.trail("c").map { it.id })
        assertEquals("c", board.focused)
        assertTrue(board.edges.none { it.from == "b" || it.to == "b" })
        board.retain(setOf("a"), emptySet())
        assertNull(board.focused)
        assertEquals(listOf("a"), board.cards.map { it.id })
        board.focused = "missing"
        assertNull(board.focused)
    }

    @Test fun `loose hunks and new steps open below everything`() {
        val board = board()
        board.openHunk("a")
        board.openHunk("b")
        assertEquals(150, board.card("b")!!.rect.y)
        board.openStep(WalkStep("S2", "t", "e", "c", listOf("c")))
        assertEquals(300, board.card(walkNoteId("S2"))!!.rect.y)
        assertEquals(WalkRect(0, 0, 300, 445), board.bounds())
        assertEquals("c", board.cardAt(10, 400)?.id)
        assertNull(board.cardAt(-5, 0))
        board.clear()
        assertTrue(board.isEmpty)
        assertNull(board.bounds())
    }

    @Test fun `links read left to right from what came first`() {
        assertEquals(WalkSide.LEFT, walkSide(LinkDirection.INCOMING))
        assertEquals(WalkSide.RIGHT, walkSide(LinkDirection.OUTGOING))
        assertEquals(WalkSide.RIGHT, walkSide(LinkDirection.SYMMETRIC))
        assertEquals("caused by", WalkGroupKey(WalkRelation.CAUSES, LinkDirection.INCOMING).label)
    }
}
