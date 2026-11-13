package dev.insight.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardLayoutTest {
    private val metrics = BoardMetrics(gap = 10, groupPadding = 5, groupHeader = 20)

    /** Height 50 for text blocks, 30 for others; groups are never measured. */
    private val measure: (Block, Int) -> Int = { block, _ -> if (block is TextBlock) 50 else 30 }

    private fun layout(board: Board, columns: Int = 4, columnWidth: Int = 100, m: (Block, Int) -> Int = measure) =
        boardLayout(board, columns, columnWidth, metrics, m)

    @Test
    fun pixelWidths() {
        assertEquals(100, blockPixelWidth(BlockWidth.NARROW, 4, 100, 10))
        assertEquals(210, blockPixelWidth(BlockWidth.WIDE, 4, 100, 10))
        assertEquals(430, blockPixelWidth(BlockWidth.FULL, 4, 100, 10))
        assertEquals(100, blockPixelWidth(BlockWidth.WIDE, 1, 100, 10))
        assertEquals(210, blockPixelWidth(BlockWidth.FULL, 2, 100, 10))
    }

    @Test
    fun columnsForViewport() {
        assertEquals(2, boardColumns(100, 300, 10))
        assertEquals(3, boardColumns(950, 300, 10))
        assertEquals(6, boardColumns(10_000, 300, 10))
    }

    @Test
    fun emptyBoardHasZeroHeight() {
        val result = layout(boardOf())
        assertTrue(result.rects.isEmpty())
        assertEquals(0, result.height)
        assertEquals(4 * 100 + 3 * 10, result.width)
    }

    @Test
    fun narrowBlocksFlowLeftToRightThenWrap() {
        val result = layout(boardOf(text("a"), text("b"), text("c")), columns = 2)
        assertEquals(BoardRect(0, 0, 100, 50), result.rects["a"])
        assertEquals(BoardRect(110, 0, 100, 50), result.rects["b"])
        assertEquals(BoardRect(0, 60, 100, 50), result.rects["c"])
        assertEquals(110, result.height)
        assertEquals(210, result.width)
    }

    @Test
    fun wideBlockThatDoesNotFitWrapsAndLeavesAGap() {
        val result = layout(boardOf(text("a"), text("w", BlockWidth.WIDE), text("b")), columns = 2)
        assertEquals(BoardRect(0, 0, 100, 50), result.rects["a"])
        assertEquals(BoardRect(0, 60, 210, 50), result.rects["w"])
        assertEquals(BoardRect(0, 120, 100, 50), result.rects["b"])
        assertEquals(170, result.height)
    }

    @Test
    fun rowHeightIsTheTallestBlock() {
        val board = boardOf(text("a"), SymbolBlock("s", 1, "S"), text("n"))
        val result = layout(board, columns = 2)
        assertEquals(50, result.rects.getValue("a").height)
        assertEquals(30, result.rects.getValue("s").height)
        assertEquals(60, result.rects.getValue("n").y)
        assertEquals(110, result.height)
    }

    @Test
    fun fullBlocksTakeTheWholeRow() {
        val board = boardOf(text("a"), text("f", BlockWidth.FULL), text("b"), text("c"))
        val result = layout(board, columns = 3)
        assertEquals(BoardRect(0, 0, 100, 50), result.rects["a"])
        assertEquals(BoardRect(0, 60, 320, 50), result.rects["f"])
        assertEquals(BoardRect(0, 120, 100, 50), result.rects["b"])
        assertEquals(BoardRect(110, 120, 100, 50), result.rects["c"])
        assertEquals(320, result.width)
        assertEquals(170, result.height)
    }

    @Test
    fun measureReceivesPixelWidth() {
        val widths = mutableMapOf<String, Int>()
        layout(boardOf(text("a"), text("w", BlockWidth.WIDE)), columns = 3) { b, w -> widths[b.id] = w; 10 }
        assertEquals(100, widths["a"])
        assertEquals(210, widths["w"])
    }

    @Test
    fun oneColumnClampsEverythingToNarrow() {
        val result = layout(boardOf(text("a", BlockWidth.WIDE), text("b", BlockWidth.FULL)), columns = 1)
        assertEquals(BoardRect(0, 0, 100, 50), result.rects["a"])
        assertEquals(BoardRect(0, 60, 100, 50), result.rects["b"])
    }

    @Test
    fun emptyGroupIsHeaderPlusTwoPaddings() {
        val result = layout(boardOf(GroupBlock("g", emptyList())), columns = 3)
        assertEquals(BoardRect(0, 0, 320, 20 + 2 * 5), result.rects["g"])
        assertEquals(30, result.height)
    }

    @Test
    fun groupChildrenAreLaidOutInsideTheGroupInAbsoluteCoordinates() {
        // 3 columns of 100, gap 10: FULL group is 320 wide, inner width 310, 3 inner columns of (310-20)/3 = 96.
        val board = boardOf(text("top"), text("a"), text("b"), text("w", BlockWidth.WIDE), GroupBlock("g", listOf("a", "b", "w")))
        val result = layout(board, columns = 3)
        val group = result.rects.getValue("g")
        assertEquals(BoardRect(0, 60, 320, group.height), group)
        // inner: row 1 = a, b (narrow, 50 high), row 2 = w (wide) 50 high
        assertEquals(BoardRect(5, 60 + 20 + 5, 96, 50), result.rects["a"])
        assertEquals(BoardRect(5 + 106, 85, 96, 50), result.rects["b"])
        // a (1) + b (1) + w (2) > 3, so w wraps
        assertEquals(BoardRect(5, 85 + 60, 2 * 96 + 10, 50), result.rects["w"])
        assertEquals(20 + 5 + (50 + 10 + 50) + 5, group.height)
        assertEquals(60 + group.height, result.height)
    }

    @Test
    fun fullChildSpansTheGroupsInnerWidth() {
        val board = boardOf(GroupBlock("g", listOf("f")), text("f", BlockWidth.FULL))
        val result = layout(board, columns = 2)
        val group = result.rects.getValue("g")
        val child = result.rects.getValue("f")
        assertEquals(group.width - 10, child.width)
        assertEquals(group.x + 5, child.x)
        assertEquals(group.y + 25, child.y)
    }

    @Test
    fun narrowGroupKeepsChildrenInsideItsSpan() {
        // A NARROW group on 3 columns: one inner column, children stack even when WIDE.
        val board = boardOf(GroupBlock("g", listOf("a", "b"), width = BlockWidth.NARROW), text("a", BlockWidth.WIDE), text("b"),
            text("next"))
        val result = layout(board, columns = 3)
        val group = result.rects.getValue("g")
        assertEquals(100, group.width)
        val a = result.rects.getValue("a")
        val b = result.rects.getValue("b")
        assertEquals(a.width, b.width)
        assertTrue(a.right <= group.right && b.right <= group.right)
        assertEquals(a.bottom + 10, b.y)
        // the next top-level block sits beside the group
        assertEquals(BoardRect(110, 0, 100, 50), result.rects["next"])
    }

    @Test
    fun wideGroupHasTwoInnerColumns() {
        val board = boardOf(GroupBlock("g", listOf("a", "b", "c"), width = BlockWidth.WIDE), text("a"), text("b"), text("c"))
        val result = layout(board, columns = 4)
        val group = result.rects.getValue("g")
        assertEquals(210, group.width)
        assertEquals(result.rects.getValue("a").y, result.rects.getValue("b").y)
        assertTrue(result.rects.getValue("c").y > result.rects.getValue("a").y)
        assertTrue(result.rects.getValue("b").right <= group.right - 5)
        assertEquals(group.bottom, result.rects.getValue("c").bottom + 5)
    }

    @Test
    fun missingChildrenAreSkipped() {
        val result = layout(boardOf(GroupBlock("g", listOf("ghost"))), columns = 2)
        assertEquals(1, result.rects.size)
        assertEquals(30, result.height)
    }

    @Test
    fun blocksAfterAGroupFlowByTheGroupsHeight() {
        val board = boardOf(GroupBlock("g", listOf("a")), text("a"), text("after"))
        val result = layout(board, columns = 2)
        val group = result.rects.getValue("g")
        assertEquals(group.bottom + 10, result.rects.getValue("after").y)
    }
}
