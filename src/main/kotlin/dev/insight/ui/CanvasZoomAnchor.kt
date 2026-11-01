package dev.insight.ui

import java.awt.Component
import java.awt.Container
import java.awt.Point
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Tracks a position within a real component, since Swing layouts do not scale uniformly. */
internal class CanvasZoomAnchor(content: Container, point: Point) {
    private val target: Component? = SwingUtilities.getDeepestComponentAt(content, point.x, point.y)
        ?.takeUnless { it === content || it.width <= 0 || it.height <= 0 }
    private val local = target?.let { SwingUtilities.convertPoint(content, point, it) }
    private val x = local?.let { it.x.toDouble() / target!!.width } ?: 0.0
    private val y = local?.let { it.y.toDouble() / target!!.height } ?: 0.0
    private val original = Point(point)

    fun resolve(content: Container, ratio: Double): Point {
        val component = target
        return if (component != null && SwingUtilities.isDescendingFrom(component, content)) {
            SwingUtilities.convertPoint(component,
                Point((x * component.width).roundToInt(), (y * component.height).roundToInt()), content)
        } else Point((original.x * ratio).roundToInt(), (original.y * ratio).roundToInt())
    }
}

/** Complete child layouts before measuring the anchor, including in an unattached canvas. */
internal fun layoutZoomContent(container: Container) {
    container.doLayout()
    container.components.filterIsInstance<Container>().filter { it.isVisible }.forEach(::layoutZoomContent)
}
