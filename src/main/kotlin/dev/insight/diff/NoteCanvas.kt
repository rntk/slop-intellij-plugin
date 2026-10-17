package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import kotlin.math.pow
import kotlin.math.roundToInt
import javax.swing.*

/**
 * A zoomable, pannable board of movable note cards; positions survive chat replies and VCS refreshes.
 * Cards have a fixed logical width; long notes scroll inside their cards.
 */
internal class NoteCanvas(private val open: (String) -> Unit, private val remove: (List<String>) -> Unit) : JPanel(null) {
    private val positions = mutableMapOf<List<String>, Point2D.Double>()
    private val cards = mutableMapOf<List<String>, JPanel>()

    private var notes = emptyList<DiffNote>()
    private var highlighted = emptySet<String>()
    internal var zoom = 1.0
        private set
    private val origin = Point2D.Double()
    var onZoomChanged: (Double) -> Unit = {}

    init {
        minimumSize = Dimension(100, 100)
        installNavigation(this)
        toolTipText = "Wheel to zoom · Drag background or middle-drag to pan"
        background = UIUtil.getPanelBackground()
    }

    fun showNotes(notes: List<DiffNote>) {
        this.notes = notes
        removeAll(); cards.clear()
        if (notes.isEmpty()) add(emptyState().also { installNavigation(it) })
        positions.keys.retainAll(notes.map { it.paths }.toSet())
        val built = notes.associate { it.paths to card(it) }
        // Remembered cards keep their place; new ones fill the first free slot in reading order.
        built.filterKeys { it in positions }.forEach { (paths, card) -> card.location = screen(positions.getValue(paths)) }
        val placed = built.filterKeys { it in positions }.values.mapTo(mutableListOf()) { it.bounds }
        built.filterKeys { it !in positions }.forEach { (paths, card) ->
            card.location = freeSlot(card.size, placed)
            positions[paths] = world(card.location)
            placed += card.bounds
        }
        built.forEach { (paths, card) -> cards[paths] = card; add(card); installNavigation(card) }
        highlight(highlighted)
        revalidate(); repaint()
    }

    fun highlight(paths: Set<String>) {
        highlighted = paths
        cards.forEach { (files, card) -> card.border = cardBorder(files.any { it in paths }) }
        repaint()
    }

    private fun card(note: DiffNote): JPanel {
        val width = scaled(CARD_WIDTH)
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        val header = JPanel(BorderLayout(scaled(8), 0)).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Drag to move"
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("${files(note.paths.size)} · LLM interpretation").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove note", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { remove(note.paths) }
                .apply { cursor = Cursor.getDefaultCursor() }, BorderLayout.EAST)
        }
        dragBy(header, note.paths)
        val textWidth = width - scaled(2 * PADDING + 4)
        val body = JPanel(VerticalLayout(scaled(10))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(JPanel(VerticalLayout(scaled(2))).apply {
                isOpaque = false
                note.paths.forEach { add(fileRow(it)) }
            })
            add(JTextArea(note.text).apply {
                isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
                font = scaledFont(JBFont.label()); border = null
                // A wrapping text area reports its height for the width it currently has.
                setSize(textWidth, Short.MAX_VALUE.toInt())
            })
        }
        card.add(header, BorderLayout.NORTH)
        val maxBody = scaled(MAX_BODY_HEIGHT)
        card.add(if (body.preferredSize.height <= maxBody) body else JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(width, maxBody)
        }, BorderLayout.CENTER)
        card.size = Dimension(width, card.preferredSize.height)
        return card
    }

    /** File name as a link, its folder dimmed next to it; the full path stays in the tooltip. */
    private fun fileRow(path: String): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        toolTipText = "View changes in $path"
        val name = path.substringAfterLast('/')
        add(JLabel(FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Any_type))
        add(Box.createHorizontalStrut(scaled(6)))
        add(ActionLink(name) { open(path) }.apply { font = scaledFont(JBFont.label()); toolTipText = "View changes in $path" })
        add(Box.createHorizontalStrut(scaled(6)))
        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { folder ->
            add(JLabel(folder).apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); toolTipText = path })
        }
    }

    private fun dragBy(handle: JComponent, paths: List<String>) {
        val drag = object : MouseAdapter() {
            var anchor: Point? = null
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                anchor = SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas)
                cards[paths]?.let { setComponentZOrder(it, 0); repaint() }
            }
            override fun mouseReleased(e: MouseEvent) { anchor = null }
            override fun mouseDragged(e: MouseEvent) {
                val before = anchor ?: return
                val now = SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas)
                val target = cards[paths] ?: return
                target.setLocation(target.x + now.x - before.x, target.y + now.y - before.y)
                positions[paths] = world(target.location)
                anchor = now
                repaint()
            }
        }
        fun attach(component: Component) {
            if (component is InplaceButton) return
            component.addMouseListener(drag); component.addMouseMotionListener(drag)
            if (component is Container) component.components.forEach(::attach)
        }
        attach(handle)
    }

    private fun freeSlot(size: Dimension, placed: List<Rectangle>): Point {
        val gap = scaled(GAP)
        val visible = width.takeIf { it > 0 } ?: scaled(1000)
        val columns = maxOf(1, (visible - gap) / (size.width + gap))
        var y = gap
        while (true) {
            for (column in 0 until columns) {
                val candidate = Rectangle(origin.x.roundToInt() + gap + column * (size.width + gap), origin.y.roundToInt() + y, size.width, size.height)
                val padded = Rectangle(candidate).apply { grow(gap / 2, gap / 2) }
                if (placed.none { it.intersects(padded) }) return candidate.location
            }
            y += scaled(8)
        }
    }

    private fun emptyState(): JComponent = JPanel(VerticalLayout(scaled(6))).apply {
        isOpaque = false
        add(JLabel("Diff review canvas").apply { font = scaledFont(JBFont.label().asBold().biggerOn(2f)) })
        listOf(
            "Select files on the left to inspect changes.",
            "Click Description or type /description below to add notes.",
            "Drag a note's header to arrange it; drag the background to pan.",
            "Use the mouse wheel to zoom, or middle-drag anywhere to pan.",
        ).forEach { add(JLabel(it).apply { foreground = UIUtil.getContextHelpForeground() }) }
        val gap = scaled(GAP)
        setBounds(gap, gap, scaled(600), preferredSize.height)
    }

    private fun cardBorder(highlighted: Boolean) =
        if (highlighted) JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2)
        else JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))

    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"

    private fun scaled(value: Int) = (JBUI.scale(value) * zoom).roundToInt().coerceAtLeast(1)
    private fun scaledFont(font: java.awt.Font) = font.deriveFont((font.size2D * zoom).toFloat())
    private fun screen(point: Point2D.Double) = Point((point.x * zoom + origin.x).roundToInt(), (point.y * zoom + origin.y).roundToInt())
    private fun world(point: Point) = Point2D.Double((point.x - origin.x) / zoom, (point.y - origin.y) / zoom)

    internal fun zoomAt(value: Double, anchor: Point = Point(width / 2, height / 2)) {
        val next = value.coerceIn(0.25, 2.5)
        if (next == zoom) return
        val fixed = world(anchor)
        zoom = next
        origin.setLocation(anchor.x - fixed.x * zoom, anchor.y - fixed.y * zoom)
        showNotes(notes)
        onZoomChanged(zoom)
    }

    fun resetView() {
        zoom = 1.0
        origin.setLocation(0.0, 0.0)
        showNotes(notes)
        onZoomChanged(zoom)
    }

    fun fitView() {
        if (cards.isEmpty() || width <= 0 || height <= 0) { resetView(); return }
        val bounds = cards.values.map { it.bounds }.reduce { a, b -> a.union(b) }
        val center = world(Point(bounds.centerX.roundToInt(), bounds.centerY.roundToInt()))
        val margin = JBUI.scale(GAP) * 2
        zoom = (zoom * minOf((width - margin).toDouble() / bounds.width,
            (height - margin).toDouble() / bounds.height)).coerceIn(0.25, 2.5)
        origin.setLocation(width / 2.0 - center.x * zoom, height / 2.0 - center.y * zoom)
        showNotes(notes)
        onZoomChanged(zoom)
    }

    private fun installNavigation(component: Component) {
        val navigation = object : MouseAdapter() {
            private var anchor: Point? = null
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isMiddleMouseButton(e) ||
                    (cards.values.none { SwingUtilities.isDescendingFrom(e.component, it) } && SwingUtilities.isLeftMouseButton(e))) {
                    anchor = SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas)
                    this@NoteCanvas.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }
            override fun mouseReleased(e: MouseEvent) {
                anchor = null
                this@NoteCanvas.cursor = Cursor.getDefaultCursor()
            }
            override fun mouseDragged(e: MouseEvent) {
                val before = anchor ?: return
                val now = SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas)
                origin.setLocation(origin.x + now.x - before.x, origin.y + now.y - before.y)
                components.forEach { it.setLocation(it.x + now.x - before.x, it.y + now.y - before.y) }
                anchor = now
                repaint()
            }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                zoomAt(zoom * 1.12.pow(-e.preciseWheelRotation),
                    SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas))
                e.consume()
            }
        }
        component.addMouseListener(navigation)
        component.addMouseMotionListener(navigation)
        // Leave wheel scrolling inside long notes to their scroll pane.
        if (component !is JScrollPane && SwingUtilities.getAncestorOfClass(JScrollPane::class.java, component) == null)
            component.addMouseWheelListener(navigation)
        if (component is Container) component.components.forEach(::installNavigation)
    }

    private companion object {
        const val CARD_WIDTH = 460
        const val GAP = 20
        const val PADDING = 12
        const val MAX_BODY_HEIGHT = 520
    }
}
