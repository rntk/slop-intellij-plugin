package dev.insight.board

/** A placed block in board coordinates (unscaled pixels). */
data class BoardRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/** Where every block landed and the board's extent; grouped blocks are placed inside their group's rectangle. */
data class BoardLayoutResult(val rects: Map<String, BoardRect>, val width: Int, val height: Int)

/** Spacing of the auto-layout: [gap] between blocks, [groupPadding] inside a group frame, [groupHeader] for its title. */
data class BoardMetrics(val gap: Int = 16, val groupPadding: Int = 12, val groupHeader: Int = 28)

/** The pixel width of a block spanning [width] of [columns] columns of [columnWidth] with [gap] between them. */
fun blockPixelWidth(width: BlockWidth, columns: Int, columnWidth: Int, gap: Int): Int {
    val span = when (width) {
        BlockWidth.NARROW -> 1
        BlockWidth.WIDE -> 2
        BlockWidth.FULL -> columns
    }.coerceIn(1, columns)
    return span * columnWidth + (span - 1) * gap
}

/**
 * Lays [board]'s top-level blocks into [columns] columns of [columnWidth], in block order, left to right then down:
 * a block that does not fit the remaining columns of a row starts the next row, and every row is as tall as its tallest
 * block. Group frames hold their children by the same rule within the group's inner width, under a header of
 * [BoardMetrics.groupHeader]. [measure] gives a block's height at a pixel width (views measure their components; tests
 * pass a formula). Pure and PSI-free.
 */
fun boardLayout(board: Board, columns: Int, columnWidth: Int, metrics: BoardMetrics = BoardMetrics(),
    measure: (Block, Int) -> Int): BoardLayoutResult {
    val cols = columns.coerceAtLeast(1)
    val rects = LinkedHashMap<String, BoardRect>()
    val inners = HashMap<String, GroupGeometry>()
    val top = flow(board.topLevel, cols, columnWidth, metrics.gap) { block, pixelWidth ->
        if (block is GroupBlock) groupGeometry(board, block, pixelWidth, cols, metrics, measure).also { inners[block.id] = it }.height(metrics)
        else measure(block, pixelWidth)
    }
    for (p in top.items) {
        rects[p.block.id] = BoardRect(p.x, p.y, p.width, p.height)
        val group = p.block as? GroupBlock ?: continue
        val inner = inners.getValue(group.id)
        val cx = p.x + metrics.groupPadding
        val cy = p.y + metrics.groupHeader + metrics.groupPadding
        for (child in inner.flow.items) rects[child.block.id] = BoardRect(cx + child.x, cy + child.y, child.width, child.height)
    }
    return BoardLayoutResult(rects, cols * columnWidth + (cols - 1) * metrics.gap, top.height)
}

private class Placed(val block: Block, val x: Int, val y: Int, val width: Int, val height: Int)

private class Flow(val items: List<Placed>, val height: Int)

private class GroupGeometry(val flow: Flow) {
    /** Header, padding, the children's rows and padding; an empty group is header plus two paddings. */
    fun height(metrics: BoardMetrics) = metrics.groupHeader + 2 * metrics.groupPadding + flow.height
}

private fun span(width: BlockWidth, columns: Int): Int = when (width) {
    BlockWidth.NARROW -> 1
    BlockWidth.WIDE -> 2
    BlockWidth.FULL -> columns
}.coerceIn(1, columns)

/** Flows [blocks] into rows of [columns] columns, in coordinates relative to the origin; a row is as tall as its tallest block. */
private fun flow(blocks: List<Block>, columns: Int, columnWidth: Int, gap: Int, height: (Block, Int) -> Int): Flow {
    val items = ArrayList<Placed>()
    var rowY = 0
    var rowHeight = 0
    var used = 0
    for (block in blocks) {
        val s = span(block.width, columns)
        if (used > 0 && s > columns - used) {
            rowY += rowHeight + gap
            rowHeight = 0
            used = 0
        }
        val pixelWidth = blockPixelWidth(block.width, columns, columnWidth, gap)
        val h = height(block, pixelWidth).coerceAtLeast(0)
        items += Placed(block, used * (columnWidth + gap), rowY, pixelWidth, h)
        rowHeight = maxOf(rowHeight, h)
        used += s
    }
    return Flow(items, if (items.isEmpty()) 0 else rowY + rowHeight)
}

/**
 * The children of [group] flow in inner columns: as many as the group spans, each as wide as the group's inner width
 * allows, so NARROW/WIDE/FULL children keep their meaning relative to the group. Groups do not nest; a group listed
 * as a child is skipped.
 */
private fun groupGeometry(board: Board, group: GroupBlock, pixelWidth: Int, columns: Int, metrics: BoardMetrics,
    measure: (Block, Int) -> Int): GroupGeometry {
    val innerColumns = maxOf(1, span(group.width, columns))
    val innerWidth = (pixelWidth - 2 * metrics.groupPadding).coerceAtLeast(1)
    val innerColumnWidth = ((innerWidth - (innerColumns - 1) * metrics.gap) / innerColumns).coerceAtLeast(1)
    val children = board.children(group).filter { it !is GroupBlock }
    return GroupGeometry(flow(children, innerColumns, innerColumnWidth, metrics.gap, measure))
}

/** The number of columns for a viewport: as many [columnWidth] columns as fit with [gap], at least [min] and at most [max]. */
fun boardColumns(viewportWidth: Int, columnWidth: Int, gap: Int, min: Int = 2, max: Int = 6): Int =
    ((viewportWidth + gap) / (columnWidth + gap)).coerceIn(min, max)
