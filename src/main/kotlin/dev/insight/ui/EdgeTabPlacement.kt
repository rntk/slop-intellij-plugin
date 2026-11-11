package dev.insight.ui

import java.awt.Rectangle

/** How much of an [EdgeTab] shows: its icon and text, its icon only, or a thin handle that unfolds while the pointer is on it. */
internal enum class EdgeTabForm { FULL, ICON, HANDLE }

internal enum class EdgeSide { LEFT, RIGHT }

/** Where an [EdgeTab] floats: its [bounds] in the host, flush with [side]. */
internal data class EdgeTabSpot(val bounds: Rectangle, val form: EdgeTabForm, val side: EdgeSide)

/** The sizes of an [EdgeTab]: the [thickness] across the edge, its length along it in each form, and the [handle] width. */
internal data class EdgeTabSize(val thickness: Int, val fullLength: Int, val iconLength: Int, val handle: Int)

/**
 * Finds a place along the left or right edge of a host where a floating tab covers none of the [obstacles], the controls of
 * the page below it. The full tab is preferred, then the icon alone, top to bottom on the left edge and then the right; where
 * neither fits, a thin handle goes where it covers the least.
 */
internal object EdgeTabPlacement {
    fun place(
        width: Int,
        height: Int,
        size: EdgeTabSize,
        obstacles: List<Rectangle>,
        previous: EdgeTabSpot? = null,
        margin: Int = 4,
        step: Int = 4,
        gap: Int = 2,
    ): EdgeTabSpot {
        val blocked = obstacles.filter { !it.isEmpty }.map { Rectangle(it.x - gap, it.y - gap, it.width + 2 * gap, it.height + 2 * gap) }
        fun free(rect: Rectangle) = blocked.none { it.intersects(rect) }
        val room = height - 2 * margin
        val forms = listOf(EdgeTabForm.FULL to size.fullLength.coerceAtMost(room), EdgeTabForm.ICON to size.iconLength)
        if (width >= size.thickness * 3) forms.filter { (_, length) -> length in size.iconLength..room }.forEach { (form, length) ->
            // Staying put while the place is still free keeps the tab from jumping as the page scrolls or relayouts.
            previous?.takeIf { it.form == form && it.bounds.height == length }?.let { spot ->
                val rect = rect(spot.side, width, spot.bounds.y, size.thickness, length)
                if (spot.bounds.y + length <= height - margin && free(rect)) return EdgeTabSpot(rect, form, spot.side)
            }
            EdgeSide.entries.forEach { side ->
                offsets(margin, height - margin - length, step).forEach { y ->
                    val rect = rect(side, width, y, size.thickness, length)
                    if (free(rect)) return EdgeTabSpot(rect, form, side)
                }
            }
        }
        val length = size.fullLength.coerceAtMost(room).coerceAtLeast(size.iconLength)
        val y = offsets(margin, height - margin - length, step).minByOrNull { y ->
            val rect = Rectangle(0, y, size.handle, length)
            blocked.sumOf { it.intersection(rect).takeIf { overlap -> !overlap.isEmpty }?.let { overlap -> overlap.width.toLong() * overlap.height } ?: 0L }
        } ?: margin
        return EdgeTabSpot(Rectangle(0, y, size.handle, length), EdgeTabForm.HANDLE, EdgeSide.LEFT)
    }

    private fun rect(side: EdgeSide, width: Int, y: Int, thickness: Int, length: Int) =
        Rectangle(if (side == EdgeSide.LEFT) 0 else width - thickness, y, thickness, length)

    private fun offsets(first: Int, last: Int, step: Int): List<Int> =
        if (last < first) listOf(first) else (first..last step step).toList().let { if (it.last() != last) it + last else it }
}
