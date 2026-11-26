package dev.insight.review

import kotlin.math.max
import kotlin.math.min

/** Which side of the board the source preview docks to. */
internal enum class PreviewSide { LEFT, RIGHT }

/** Screen bounds of the source preview window, and the side it docks to. */
internal data class PreviewPlacement(val x: Int, val y: Int, val width: Int, val height: Int, val side: PreviewSide) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/**
 * Places the floating source preview of a Tree row inside a [viewWidth]×[viewHeight] board.
 *
 * Horizontally it docks to the right edge, so it stays put while the reader hovers row after row; it moves to the left
 * edge only when docking right would cover the anchor row and the left side has room.
 *
 * Vertically it follows the row while it is short, so it opens beside the pointer. The taller its content, the more it
 * favors the top of the board: a window that needs most of the board's height opens at the top and grows down, and
 * stays there as the reader moves between rows. It always overlaps the anchor row's height when it can, so the reader
 * sees which row it belongs to.
 */
internal fun treePreviewPlacement(
    viewWidth: Int,
    viewHeight: Int,
    anchorTop: Int,
    anchorBottom: Int,
    anchorLeft: Int,
    anchorRight: Int,
    width: Int,
    naturalHeight: Int,
    margin: Int,
    gap: Int,
    minWidth: Int,
    minHeight: Int,
    /** Where the title sits below the window's top, to line up with the row when the window can follow it. */
    headerOffset: Int = 0,
): PreviewPlacement {
    val availableWidth = max(1, viewWidth - 2 * margin)
    val w = width.coerceAtMost(availableWidth).coerceAtLeast(min(minWidth, availableWidth))
    val availableHeight = max(1, viewHeight - 2 * margin)
    val h = naturalHeight.coerceAtMost(availableHeight).coerceAtLeast(min(minHeight, availableHeight))

    val rightX = viewWidth - margin - w
    val leftX = margin
    val coversAnchorOnRight = anchorRight + gap > rightX && anchorLeft < rightX + w
    val leftFits = anchorLeft - gap >= leftX + w
    val side = if (coversAnchorOnRight && leftFits) PreviewSide.LEFT else PreviewSide.RIGHT
    val x = if (side == PreviewSide.RIGHT) rightX else leftX

    // 0 while the window is small enough to follow the row, rising to 1 as it needs the whole board.
    val tallness = ((h.toDouble() / availableHeight - FOLLOW_FRACTION) / (1.0 - FOLLOW_FRACTION)).coerceIn(0.0, 1.0)
    val followTop = (anchorTop - headerOffset).toDouble()
    var top = followTop * (1.0 - tallness) + margin * tallness
    // Keep the anchor row within the window's height, then keep the window on the board.
    top = top.coerceAtMost(anchorTop.toDouble()).coerceAtLeast((anchorBottom - h).toDouble())
    top = top.coerceAtMost((viewHeight - margin - h).toDouble()).coerceAtLeast(margin.toDouble())
    return PreviewPlacement(x, top.toInt(), w, h, side)
}

/** The fraction of the board's height up to which the preview simply follows its row. */
internal const val FOLLOW_FRACTION = 0.35

/**
 * Whether the pointer, moving from [fromX],[fromY] to [toX],[toY], is heading for the preview window [placement], so
 * crossing other rows on the way shouldn't replace what it shows. The corridor is the triangle between the starting
 * point and the window's near edge, widened by [slack] pixels.
 */
internal fun headingToPreview(
    fromX: Int,
    fromY: Int,
    toX: Int,
    toY: Int,
    placement: PreviewPlacement,
    slack: Int,
): Boolean {
    val edgeX = if (placement.side == PreviewSide.RIGHT) placement.x else placement.right
    val towardEdge = if (placement.side == PreviewSide.RIGHT) toX >= fromX else toX <= fromX
    if (!towardEdge) return false
    if (fromX == toX && fromY == toY) return false
    return pointInTriangle(
        toX.toDouble(), toY.toDouble(),
        fromX.toDouble(), fromY.toDouble(),
        edgeX.toDouble(), (placement.y - slack).toDouble(),
        edgeX.toDouble(), (placement.bottom + slack).toDouble(),
    )
}

private fun pointInTriangle(
    px: Double, py: Double,
    ax: Double, ay: Double,
    bx: Double, by: Double,
    cx: Double, cy: Double,
): Boolean {
    fun sign(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) =
        (x1 - x3) * (y2 - y3) - (x2 - x3) * (y1 - y3)
    val d1 = sign(px, py, ax, ay, bx, by)
    val d2 = sign(px, py, bx, by, cx, cy)
    val d3 = sign(px, py, cx, cy, ax, ay)
    val negative = d1 < 0 || d2 < 0 || d3 < 0
    val positive = d1 > 0 || d2 > 0 || d3 > 0
    return !(negative && positive)
}

/** Added and removed line counts of a unified [patch], ignoring its file and hunk headers. */
internal fun patchLineCounts(patch: String): Pair<Int, Int> {
    var added = 0
    var removed = 0
    for (line in patch.lineSequence()) {
        when {
            line.startsWith("+++") || line.startsWith("---") -> Unit
            line.startsWith("+") -> added++
            line.startsWith("-") -> removed++
        }
    }
    return added to removed
}
