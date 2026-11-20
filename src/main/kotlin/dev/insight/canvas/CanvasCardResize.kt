package dev.insight.canvas

import com.intellij.util.ui.JBUI
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomUnscaled
import java.awt.Cursor
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** Where a card is resized from: its right edge (width), bottom edge (height) or bottom-right corner (both). */
internal enum class CanvasResizeEdge(val cursor: Int, val width: Boolean, val height: Boolean) {
    RIGHT(Cursor.E_RESIZE_CURSOR, width = true, height = false),
    BOTTOM(Cursor.S_RESIZE_CURSOR, width = false, height = true),
    CORNER(Cursor.SE_RESIZE_CURSOR, width = true, height = true),
}

/** How far inside a card's border, at zoom 1, its edges can be grabbed; within its padding, clear of its content. */
internal const val CARD_RESIZE_ZONE = 6

/** The least height a resized card leaves its source or diff viewer at zoom 1: a few lines. */
internal const val CARD_VIEWER_MIN_HEIGHT = 96

/**
 * The resize handle at ([x], [y]) in a card of [width] by [height] pixels, with edges [zone] pixels deep; null when
 * the point is on no handle. The corner is twice as deep, so it is easy to grab.
 */
internal fun canvasResizeEdge(x: Int, y: Int, width: Int, height: Int, zone: Int): CanvasResizeEdge? {
    if (x !in 0 until width || y !in 0 until height || zone <= 0) return null
    val right = x >= width - zone
    val bottom = y >= height - zone
    return when {
        x >= width - 2 * zone && y >= height - 2 * zone -> CanvasResizeEdge.CORNER
        right -> CanvasResizeEdge.RIGHT
        bottom -> CanvasResizeEdge.BOTTOM
        else -> null
    }
}

/**
 * The size of a card dragged from [edge] by [dx], [dy] units, having been [startWidth] by [startHeight] units when the
 * drag began with saved size [saved]: the dragged dimensions follow the pointer, the others keep [saved].
 */
internal fun canvasResizedSize(saved: CardSize?, edge: CanvasResizeEdge, startWidth: Int, startHeight: Int, dx: Int, dy: Int): CardSize? =
    CardSize(if (edge.width) startWidth + dx else saved?.width, if (edge.height) startHeight + dy else saved?.height).bounded()

/** [size] with the dimensions [edge] resizes automatic again: double-clicking a handle resets what it resizes. */
internal fun canvasResetSize(size: CardSize?, edge: CanvasResizeEdge): CardSize? =
    size?.let { CardSize(it.width.takeUnless { edge.width }, it.height.takeUnless { edge.height }) }?.bounded()

/**
 * The tallest a card's source or diff viewer may be, in pixels, so the card is [userHeight] tall with [chrome] pixels
 * of other sections (header, meta, summary, usages, chat, input, padding) around the viewer.
 *
 * Without a user height the viewer is capped at [autoMax], as cards always were. With one, the viewer gets the rest
 * of it, but at least [minViewer]; so the card cannot shrink below its other sections and a few lines of code. The
 * viewer never grows past its text, so a short source keeps the card at its natural height rather than showing an
 * empty viewer: the card is as tall as `chrome + min(text, this)`. A card with no viewer (folded, or nothing to show)
 * simply keeps its content's height.
 */
internal fun canvasViewerMaxHeight(userHeight: Int?, chrome: Int, minViewer: Int, autoMax: Int): Int =
    if (userHeight == null) autoMax else maxOf(userHeight - chrome, minViewer)

/**
 * Drags [view]'s edges to resize it. The view shows the dragged size live, laid out again on every move without
 * saving; the model gets the size on release, so autosave writes once per drag. Alt + drag stays a canvas pan:
 * pressing with Alt starts no resize. The canvas asks [claims] so a press on a handle starts no pan.
 */
internal class CanvasCardResize(private val view: CanvasCardView, private val host: CanvasCardHost) : MouseAdapter() {
    /** The handle being dragged, or null. */
    var edge: CanvasResizeEdge? = null
        private set
    /** The size shown while dragging, before it is saved. */
    var live: CardSize? = null
        private set
    private var start: Point? = null
    private var startWidth = 0
    private var startHeight = 0
    private var moved = false
    private var cursorShown = false

    val active: Boolean get() = edge != null

    fun install() {
        view.addMouseListener(this)
        view.addMouseMotionListener(this)
    }

    /** Whether [e] belongs to a resize: a press on a handle, or anything during one. */
    fun claims(e: MouseEvent): Boolean = active || (e.id == MouseEvent.MOUSE_PRESSED && startsResize(e))

    private fun startsResize(e: MouseEvent) =
        e.component === view && SwingUtilities.isLeftMouseButton(e) && !e.isAltDown && edgeAt(e.point) != null

    private fun edgeAt(point: Point) =
        canvasResizeEdge(point.x, point.y, view.width, view.height, maxOf(zoomScaled(CARD_RESIZE_ZONE, host.zoom), JBUI.scale(2)))

    override fun mouseMoved(e: MouseEvent) = hover(e)
    override fun mouseEntered(e: MouseEvent) = hover(e)

    override fun mouseExited(e: MouseEvent) {
        if (!active) showCursor(null)
    }

    override fun mousePressed(e: MouseEvent) {
        if (active || !startsResize(e)) return
        edge = edgeAt(e.point)
        start = e.locationOnScreen
        startWidth = zoomUnscaled(view.width, host.zoom)
        startHeight = zoomUnscaled(view.height, host.zoom)
        live = view.card.size
        moved = false
        e.consume()
    }

    override fun mouseDragged(e: MouseEvent) {
        val dragged = edge ?: return
        val from = start ?: return
        val dx = e.locationOnScreen.x - from.x
        val dy = e.locationOnScreen.y - from.y
        if (!moved && maxOf(Math.abs(dx), Math.abs(dy)) < JBUI.scale(2)) return
        moved = true
        live = canvasResizedSize(view.card.size, dragged, startWidth, startHeight, zoomUnscaled(dx, host.zoom), zoomUnscaled(dy, host.zoom))
        view.revalidate()
        host.cardResized()
        e.consume()
    }

    override fun mouseReleased(e: MouseEvent) {
        if (!active) return
        val size = live
        val commit = moved
        edge = null
        start = null
        live = null
        moved = false
        if (commit) host.model.resize(view.id, size)
        // Laid out again without the live size, whether or not the model changed.
        host.cardResized()
        hover(e)
        e.consume()
    }

    override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e) || e.isAltDown) return
        val at = edgeAt(e.point) ?: return
        host.model.resize(view.id, canvasResetSize(view.card.size, at))
        e.consume()
    }

    private fun hover(e: MouseEvent) {
        if (active) return
        showCursor(if (e.component === view && !e.isAltDown) edgeAt(e.point) else null)
    }

    /** Shows [edge]'s cursor over the card, or none; leaves cursors set by others (such as a pan) alone. */
    private fun showCursor(edge: CanvasResizeEdge?) {
        if (edge != null) {
            view.cursor = Cursor.getPredefinedCursor(edge.cursor)
            cursorShown = true
        } else if (cursorShown) {
            view.cursor = null
            cursorShown = false
        }
    }
}

/** A subtle grip of three diagonal strokes in the bottom-right corner of a card of [width] by [height] pixels. */
internal fun paintCanvasResizeGrip(g: Graphics2D, width: Int, height: Int, zoom: Double) {
    val inset = zoomScaled(3, zoom)
    val step = zoomScaled(3, zoom)
    if (width < 6 * step || height < 6 * step) return
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = CANVAS_CARD_BORDER
    val right = width - inset
    val bottom = height - inset
    for (i in 1..3) g.drawLine(right - i * step, bottom, right, bottom - i * step)
}
