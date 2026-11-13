package dev.insight.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoardModelTest {
    private var now = 1_000L
    private var counter = 0
    private val model = BoardModel(Board.new("Board", now = 500, id = "b"), clock = { ++now }, ids = { "x${++counter}" })
    private val changes = mutableListOf<BoardChange>()

    init {
        model.subscribe { changes += it }
    }

    private fun assertRejects(message: String? = null, action: () -> Unit) {
        val before = model.board
        val revision = model.revision
        val seen = changes.size
        try {
            action()
            fail("Expected BoardEditException")
        } catch (e: BoardEditException) {
            if (message != null) assertTrue("'${e.message}' should mention '$message'", e.message!!.contains(message))
        }
        assertSame(before, model.board)
        assertEquals(revision, model.revision)
        assertEquals(seen, changes.size)
    }

    @Test
    fun addAppendsBumpsRevisionStampsUpdatedAndNotifies() {
        assertEquals(0, model.revision)
        val block = text("a")
        assertSame(block, model.add(block))
        model.add(text("b"))
        assertEquals(listOf("a", "b"), model.board.blocks.map { it.id })
        assertEquals(2, model.revision)
        assertEquals(1_002L, model.board.updated)
        assertEquals(500L, model.board.created)
        assertEquals(listOf<BoardChange>(BoardChange.BlockAdded(block), BoardChange.BlockAdded(text("b"))), changes)
    }

    @Test
    fun addBeforeInsertsAtThePosition() {
        model.add(text("a"))
        model.add(text("b"))
        model.add(text("c"), before = "b")
        assertEquals(listOf("a", "c", "b"), model.board.blocks.map { it.id })
        assertRejects("No block 'nope'") { model.add(text("d"), before = "nope") }
    }

    @Test
    fun addBeforeAGroupedBlockJoinsItsGroup() {
        model.add(text("a"))
        model.add(text("b"))
        model.add(GroupBlock("g", listOf("a", "b")))
        model.add(text("c"), before = "b")
        assertEquals(listOf("a", "c", "b"), (model.block("g") as GroupBlock).children)
        assertEquals("g", model.board.parent("c")?.id)
        // Groups never nest, so a group added before a grouped block only takes its place in the list.
        model.add(GroupBlock("h", emptyList()), before = "a")
        assertNull(model.board.parent("h"))
        assertEquals(listOf("a", "c", "b"), (model.block("g") as GroupBlock).children)
    }

    @Test
    fun addRejectsDuplicateIds() {
        model.add(text("a"))
        assertRejects("already exists") { model.add(text("a")) }
    }

    @Test
    fun addRejectsBeyondTheBlockLimit() {
        repeat(BoardLimits.MAX_BLOCKS) { model.add(text("b$it")) }
        assertRejects("blocks") { model.add(text("one more")) }
    }

    @Test
    fun newIdSkipsTakenIds() {
        model.add(text("bx1"))
        assertEquals("bx2", model.newId())
        assertEquals("nx3", model.newId("n"))
    }

    @Test
    fun updateReplacesAndNotifies() {
        model.add(text("a"))
        val revised = TextBlock("a", "New text")
        model.update(revised)
        assertEquals(revised, model.block("a"))
        assertEquals(BoardChange.BlockChanged(revised), changes.last())
        assertEquals(2, model.revision)
        assertRejects("No block 'zzz'") { model.update(text("zzz")) }
    }

    @Test
    fun removeDropsEdgesAndGroupMembership() {
        model.add(text("a"))
        model.add(text("b"))
        model.add(text("c"))
        model.add(GroupBlock("g", listOf("a", "b")))
        model.connect(BoardEdge("a", "c"))
        model.connect(BoardEdge("c", "b"))
        model.connect(BoardEdge("a", "b"))
        model.remove("a")
        assertEquals(listOf("b", "c", "g"), model.board.blocks.map { it.id })
        assertEquals(listOf("b"), (model.block("g") as GroupBlock).children)
        assertEquals(listOf(BoardEdge("c", "b")), model.board.edges)
        assertEquals(BoardChange.BlocksRemoved(setOf("a")), changes.last())
        assertRejects("No block 'a'") { model.remove("a") }
    }

    @Test
    fun removingAGroupKeepsChildrenAtTheTopLevel() {
        model.add(text("a"))
        model.add(GroupBlock("g", listOf("a")))
        assertEquals(listOf("g"), model.board.topLevel.map { it.id })
        model.remove("g")
        assertEquals(listOf("a"), model.board.topLevel.map { it.id })
    }

    @Test
    fun moveReordersAndNotifies() {
        listOf("a", "b", "c").forEach { model.add(text(it)) }
        model.move("c", "a")
        assertEquals(listOf("c", "a", "b"), model.board.blocks.map { it.id })
        assertEquals(BoardChange.Reordered, changes.last())
        model.move("c", null)
        assertEquals(listOf("a", "b", "c"), model.board.blocks.map { it.id })
        val revision = model.revision
        model.move("a", "a")
        assertEquals(revision, model.revision)
        assertRejects("No block 'x'") { model.move("x", null) }
        assertRejects("No block 'y'") { model.move("a", "y") }
    }

    @Test
    fun connectValidatesEndsAndDuplicates() {
        model.add(text("a"))
        model.add(text("b"))
        model.connect(BoardEdge("a", "b", "calls"))
        assertEquals(BoardChange.EdgeAdded(BoardEdge("a", "b", "calls")), changes.last())
        assertRejects("already connected") { model.connect(BoardEdge("a", "b")) }
        assertRejects("two different") { model.connect(BoardEdge("a", "a")) }
        assertRejects("No block 'q'") { model.connect(BoardEdge("q", "b")) }
        assertRejects("No block 'q'") { model.connect(BoardEdge("a", "q")) }
        model.connect(BoardEdge("b", "a"))
    }

    @Test
    fun disconnectRemovesOrRejects() {
        model.add(text("a"))
        model.add(text("b"))
        model.connect(BoardEdge("a", "b"))
        model.disconnect("a", "b")
        assertTrue(model.board.edges.isEmpty())
        assertEquals(BoardChange.EdgesRemoved(listOf(BoardEdge("a", "b"))), changes.last())
        assertRejects("No arrow") { model.disconnect("a", "b") }
    }

    @Test
    fun setTitleTrimsRejectsBlankAndIgnoresNoChange() {
        model.setTitle("  New title ")
        assertEquals("New title", model.board.title)
        assertEquals(BoardChange.TitleChanged("New title"), changes.last())
        val revision = model.revision
        model.setTitle("New title")
        assertEquals(revision, model.revision)
        assertRejects("blank") { model.setTitle("   ") }
    }

    @Test
    fun groupValidation() {
        model.add(text("a"))
        model.add(text("b"))
        model.add(GroupBlock("inner", emptyList()))
        model.add(GroupBlock("g", listOf("a")))
        assertRejects("itself") { model.add(GroupBlock("self", listOf("self"))) }
        assertRejects("No block 'ghost'") { model.add(GroupBlock("g2", listOf("ghost"))) }
        assertRejects("already in group 'g'") { model.add(GroupBlock("g2", listOf("a"))) }
        assertRejects("nested") { model.add(GroupBlock("g2", listOf("inner"))) }
        assertRejects("once") { model.add(GroupBlock("g2", listOf("b", "b"))) }
        assertRejects("already in group 'g'") { model.update(GroupBlock("inner", listOf("a"))) }
        // A group may keep and extend its own children.
        model.update(GroupBlock("g", listOf("a", "b")))
        assertEquals(listOf("a", "b"), (model.block("g") as GroupBlock).children)
        assertNull(model.board.topLevel.firstOrNull { it.id == "a" })
    }

    @Test
    fun tableValidation() {
        assertRejects("columns") { model.add(TableBlock("t", emptyList(), emptyList())) }
        assertRejects("cells") { model.add(TableBlock("t", listOf("a", "b"), listOf(listOf(Cell("1"))))) }
        model.add(TableBlock("t", listOf("a"), listOf(listOf(Cell("1")))))
        assertRejects("cells") { model.update(TableBlock("t", listOf("a", "b"), listOf(listOf(Cell("1"))))) }
    }

    @Test
    fun codeAnnotationMustBeInsideTheAnchor() {
        assertRejects("outside") { model.add(CodeBlock("c", anchor(5, 8), "x", annotations = listOf(Annotation(9, "late")))) }
        assertRejects("outside") { model.add(CodeBlock("c", anchor(5, 8), "x", annotations = listOf(Annotation(4, "early")))) }
        model.add(CodeBlock("c", anchor(5, 8), "x", annotations = listOf(Annotation(5, "first"), Annotation(8, "last"))))
        assertRejects("outside") { model.update(CodeBlock("c", anchor(5, 8), "x", annotations = listOf(Annotation(1, "x")))) }
    }

    @Test
    fun resetReplacesTheBoardAndNotifies() {
        model.add(text("a"))
        val other = Board.new("Other", now = 9, id = "o")
        model.reset(other)
        assertSame(other, model.board)
        assertEquals(BoardChange.Reset, changes.last())
        assertEquals(2, model.revision)
        assertEquals(9L, model.board.updated)
    }

    @Test
    fun disposedSubscriptionStopsNotifications() {
        val seen = mutableListOf<BoardChange>()
        val subscription = model.subscribe { seen += it }
        model.add(text("a"))
        subscription.dispose()
        model.add(text("b"))
        assertEquals(1, seen.size)
        assertNotEquals(seen.size, changes.size)
    }

    @Test
    fun failedEditsDoNotChangeUpdated() {
        model.add(text("a"))
        val updated = model.board.updated
        assertRejects { model.add(text("a")) }
        assertEquals(updated, model.board.updated)
    }
}
