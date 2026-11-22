package dev.insight.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasTextLinksTest {
    private val root = CanvasShownSpan(CANVAS_ROOT, "src/A.kt", 0..200, whole = true)
    private val run = CanvasShownSpan("run", "src/B.kt", 10..30, whole = false)
    private val bFile = CanvasShownSpan("bFile", "src/B.kt", 0..120, whole = true)

    @Test
    fun `a line the context card shows scrolls that card`() {
        assertEquals(CanvasTextMove.Scroll("run", 12), canvasTextMove("run", "src/B.kt", 12, listOf(root, run, bFile)))
    }

    @Test
    fun `a line only another card shows scrolls the nearest such card`() {
        val nearness = mapOf(CANVAS_ROOT to 2, "run" to 0, "bFile" to 1)
        assertEquals(CanvasTextMove.Scroll("bFile", 80), canvasTextMove("run", "src/B.kt", 80, listOf(root, run, bFile), nearness::getValue))
        assertEquals(CanvasTextMove.Scroll(CANVAS_ROOT, 5), canvasTextMove("run", "src/A.kt", 5, listOf(root, run, bFile), nearness::getValue))
    }

    @Test
    fun `among equally near cards an excerpt wins over the whole file`() {
        val nearness = { _: String -> 1 }
        assertEquals(CanvasTextMove.Scroll("run", 20), canvasTextMove(CANVAS_ROOT, "src/B.kt", 20, listOf(bFile, run), nearness))
    }

    @Test
    fun `code no card shows opens a new card`() {
        assertEquals(CanvasTextMove.Open, canvasTextMove("run", "src/C.kt", 3, listOf(root, run, bFile)))
        assertEquals(CanvasTextMove.Open, canvasTextMove("run", "src/B.kt", 500, listOf(root, run, bFile)))
    }

    @Test
    fun `a file link without a line needs a card showing the whole file`() {
        assertEquals(CanvasTextMove.Open, canvasTextMove("run", "src/B.kt", null, listOf(run)))
        assertEquals(CanvasTextMove.Scroll("bFile", null), canvasTextMove("run", "src/B.kt", null, listOf(run, bFile)))
    }

    @Test
    fun `nearness prefers the context, then its children, then its chain`() {
        var next = 0
        val model = CanvasSessionModel(CanvasSession.new("src/A.kt", now = 0), clock = { 0 }, ids = { "c${++next}" })
        val parent = model.open(CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(0, 1, 0), "src/A.kt", 0, "fun f() {}", SymbolRef("src/A.kt", "f", "FUNCTION"))
        val context = model.open(parent.id, CardKind.SYMBOL, CardAnchor(0, 1, 0), "src/B.kt", 0, "fun g() {}", SymbolRef("src/B.kt", "g", "FUNCTION"))
        val child = model.open(context.id, CardKind.SYMBOL, CardAnchor(0, 1, 0), "src/C.kt", 0, "fun h() {}", SymbolRef("src/C.kt", "h", "FUNCTION"))
        val other = model.open(CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(5, 6, 0), "src/D.kt", 0, "fun k() {}", SymbolRef("src/D.kt", "k", "FUNCTION"))
        val near = { id: String -> canvasCardNearness(model.session, context.id, id) }
        val order = listOf(other.id, CANVAS_ROOT, parent.id, child.id, context.id).sortedBy(near)
        assertEquals(listOf(context.id, child.id, parent.id, CANVAS_ROOT, other.id), order)
    }

    @Test
    fun `a new card anchors at the whole word its parent names`() {
        assertEquals(9..11, canvasTextAnchor("val runs\nrun()", "run"))
        assertNull(canvasTextAnchor("val runs = 1", "run"))
        assertNull(canvasTextAnchor("x", ""))
    }

    @Test
    fun `a selection shows a declaration only within its columns`() {
        // Parent lines 10..12; the selection runs from column 14 of line 10 to before column 5 of line 12.
        val parent = "val handler = run()\nfoo()\nbar() + baz()"
        val anchor = CardAnchor(parent.indexOf("run"), parent.indexOf(" + baz"), 10)
        val (start, end) = canvasSelectionColumns(parent, 0, anchor)
        assertEquals(14 to 5, start to end)
        val selection = CanvasShownSpan("sel", "src/B.kt", 10..12, whole = false, startColumn = start, endColumn = end)
        assertEquals(CanvasTextMove.Open, canvasTextMove("x", "src/B.kt", 10, listOf(selection), column = 4))
        assertEquals(CanvasTextMove.Scroll("sel", 10), canvasTextMove("x", "src/B.kt", 10, listOf(selection), column = 14))
        assertEquals(CanvasTextMove.Scroll("sel", 11), canvasTextMove("x", "src/B.kt", 11, listOf(selection), column = 0))
        assertEquals(CanvasTextMove.Scroll("sel", 12), canvasTextMove("x", "src/B.kt", 12, listOf(selection), column = 4))
        assertEquals(CanvasTextMove.Open, canvasTextMove("x", "src/B.kt", 12, listOf(selection), column = 8))
        // Without a column, a line link still matches the selection's lines.
        assertEquals(CanvasTextMove.Scroll("sel", 10), canvasTextMove("x", "src/B.kt", 10, listOf(selection)))
    }

    @Test
    fun `a selection of a selection adds its parent's start column on the first line`() {
        val anchor = CardAnchor(4, 9, 10)
        assertEquals(18 to 23, canvasSelectionColumns("run(a, b)", 14, anchor))
        assertEquals(1 to 3, canvasSelectionColumns("x\nabc", 14, CardAnchor(3, 5, 11)))
    }
}
