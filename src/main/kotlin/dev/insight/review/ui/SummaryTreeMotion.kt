package dev.insight.review.ui

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The geometry of the Tree view's fold and unfold transitions. Parts glide between layouts, and the viewport follows an anchor
 * part, so what the reader is looking at stays in place while the rest of the tree reflows around it.
 */
internal object SummaryTreeMotion {
    const val DURATION_MS = 240
    const val FRAME_MS = 15

    /** Slow at both ends, so a transition starts and settles gently. */
    fun ease(progress: Double): Double {
        val t = progress.coerceIn(0.0, 1.0)
        return if (t < 0.5) 4 * t * t * t else 1 - Math.pow(-2 * t + 2, 3.0) / 2
    }

    fun lerp(from: Int, to: Int, t: Double): Int = (from + (to - from) * t).roundToInt()
    fun lerp(from: Point, to: Point, t: Double) = Point(lerp(from.x, to.x, t), lerp(from.y, to.y, t))
    fun lerp(from: Dimension, to: Dimension, t: Double) = Dimension(lerp(from.width, to.width, t), lerp(from.height, to.height, t))

    /** Where a part that is new in a layout starts: at its nearest ancestor that was already shown, else where it ends. */
    fun origin(key: String, end: Point, parents: Map<String, String?>, shown: Map<String, Rectangle>): Point {
        var up = parents[key]
        while (up != null) {
            shown[up]?.let { return it.location }
            up = parents[up]
        }
        return end
    }

    /** Where a part that leaves a layout heads for: its nearest ancestor that stays, else where it is. */
    fun destination(key: String, here: Point, previousParents: Map<String, String?>, stays: Map<String, Rectangle>): Point {
        var up = previousParents[key]
        while (up != null) {
            stays[up]?.let { return it.location }
            up = previousParents[up]
        }
        return here
    }

    /** The shown part nearest to the middle of the viewport, among those that are in both layouts; null when none is in view. */
    fun pickAnchor(shown: Map<String, Rectangle>, stays: Set<String>, view: Rectangle): String? {
        val cx = view.centerX
        val cy = view.centerY
        return stays.mapNotNull { key -> shown[key]?.takeIf { it.intersects(view) }?.let { key to it } }
            .minByOrNull { (_, rect) -> hypot(rect.centerX - cx, rect.centerY - cy) }?.first
    }

    /** The viewport position that keeps the anchor [offset] from the viewport's corner, within the scrollable [extent]. */
    fun followAnchor(anchor: Point, offset: Point, extent: Dimension, port: Dimension) = Point(
        (anchor.x - offset.x).coerceIn(0, maxOf(0, extent.width - port.width)),
        (anchor.y - offset.y).coerceIn(0, maxOf(0, extent.height - port.height)),
    )

    /** The smallest scroll from [current] that brings [target] fully into view, with a [margin] around it when it fits. */
    fun reveal(target: Rectangle, current: Point, port: Dimension, extent: Dimension, margin: Int): Point {
        fun axis(start: Int, size: Int, view: Int, now: Int, total: Int): Int {
            val low = start - margin
            val high = start + size + margin - view
            val next = if (low < now) low else if (high > now) high else now
            return next.coerceIn(0, maxOf(0, total - view))
        }
        return Point(axis(target.x, target.width, port.width, current.x, extent.width),
            axis(target.y, target.height, port.height, current.y, extent.height))
    }
}
