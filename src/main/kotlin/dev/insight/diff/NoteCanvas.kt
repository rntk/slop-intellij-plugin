package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
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
internal class NoteCanvas(
    private val open: (String) -> Unit,
    private val remove: (DiffNote) -> Unit,
    private val openSymbol: (DiffSymbol) -> Unit = {},
    private val removeSource: (DiffSourceRange) -> Unit = {},
    private val project: Project? = null,
) : JPanel(null), Disposable {
    private val positions = mutableMapOf<DiffNoteKey, Point2D.Double>()
    private val cards = mutableMapOf<DiffNoteKey, JPanel>()

    private var notes = emptyList<DiffNote>()
    private var sources = emptyList<DiffSourceBlock>()
    private val editors = mutableMapOf<DiffNoteKey, DiffSourceEditor>()
    private var highlighted = emptySet<String>()
    private var focused: DiffNoteKey? = null
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

    fun showNotes(notes: List<DiffNote>, sources: List<DiffSourceBlock> = emptyList()) {
        this.notes = notes
        this.sources = sources
        val sourcesByKey = sources.associateBy { it.key }
        editors.entries.removeIf { (key, editor) ->
            (sourcesByKey[key] != editor.block).also { if (it) editor.dispose() }
        }
        removeAll(); cards.clear()
        if (notes.isEmpty() && sources.isEmpty()) add(emptyState().also { installNavigation(it) })
        val flow = changeFlow(notes).takeIf { it.rows.isNotEmpty() }
        positions.keys.retainAll(notes.map { it.key }.toSet() + sourcesByKey.keys + listOfNotNull(flow?.let { FLOW_KEY }))
        // The flow comes first so a fresh canvas starts with the overview.
        val built = listOfNotNull(flow?.let { FLOW_KEY to flowCard(it) }).toMap() + notes.associate { it.key to card(it) } +
            sources.associate { it.key to sourceCard(it) }
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
        cards.forEach { (key, card) -> card.border = cardBorder(key == focused || key.paths.any { it in paths }) }
        repaint()
    }

    /** Brings a note card to the centre and outlines it briefly. */
    internal fun focusNote(note: DiffNote) {
        val card = cards[note.key] ?: return
        origin.setLocation(origin.x + width / 2.0 - card.bounds.centerX, origin.y + height / 2.0 - card.bounds.centerY)
        focused = note.key
        showNotes(notes, sources)
        cards[note.key]?.let { setComponentZOrder(it, 0) }
        Timer(1500) { if (focused == note.key) { focused = null; highlight(highlighted) } }.apply { isRepeats = false }.start()
    }

    private fun flowCard(flow: ChangeFlow): JPanel {
        val width = scaled(CARD_WIDTH)
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Drag to move"
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Change flow · Verified calls between symbol notes").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Steps are the assistant's symbol notes; each arrow is a resolved call in the source snapshot. " +
                    "Order follows the caller's source, not runtime; conditions and intermediate calls are not shown."
            }, BorderLayout.CENTER)
        }
        dragBy(header, FLOW_KEY)
        val body = JPanel(VerticalLayout(scaled(6))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(ChangeFlowDiagram(flow, width - scaled(2 * PADDING + 4), zoom, ::focusNote))
        }
        card.add(header, BorderLayout.NORTH)
        card.add(body, BorderLayout.CENTER)
        card.size = Dimension(width, card.preferredSize.height)
        return card
    }

    private fun sourceCard(block: DiffSourceBlock): JPanel {
        val range = block.range
        val width = scaled(640)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(JLabel("Source snapshot · ${range.side} · Lines ${range.startLine}–${range.endLine}").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove source block", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                removeSource(range)
            }, BorderLayout.EAST)
        }
        dragBy(header, block.key)
        val editor = editors.getOrPut(block.key) { DiffSourceEditor(block, project) }
        editor.resizeFor(width - scaled(4), zoom)
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder(false)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(header, BorderLayout.NORTH)
                add(fileRow(range.path).apply { border = JBUI.Borders.empty(0, 12, 8, 8) }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
            size = Dimension(width, preferredSize.height)
        }
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
            add(InplaceButton(IconButton("Remove note", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { remove(note) }
                .apply { cursor = Cursor.getDefaultCursor() }, BorderLayout.EAST)
        }
        dragBy(header, note.key)
        val textWidth = width - scaled(2 * PADDING + 4)
        val body = JPanel(VerticalLayout(scaled(10))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(JPanel(VerticalLayout(scaled(2))).apply {
                isOpaque = false
                note.paths.forEach { add(fileRow(it)) }
            })
            note.evidence?.let { evidence ->
                add(JLabel("Verified symbol · Static links · Source snapshot").apply {
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                    toolTipText = "In-project links resolved from current source when this note was added. Direct links only; no runtime order is implied. Callers are limited to this file."
                })
                add(DiffSymbolDiagram(evidence, textWidth, zoom, openSymbol))
            }
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

    private fun dragBy(handle: JComponent, paths: DiffNoteKey) {
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
        showNotes(notes, sources)
        onZoomChanged(zoom)
    }

    fun resetView() {
        zoom = 1.0
        origin.setLocation(0.0, 0.0)
        showNotes(notes, sources)
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
        showNotes(notes, sources)
        onZoomChanged(zoom)
    }

    private fun installNavigation(component: Component) {
        // Native editor owns selection, wheel scrolling and mouse gestures within its body.
        if (component is DiffSourceEditor) return
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

    override fun dispose() {
        editors.values.forEach { it.dispose() }
        editors.clear()
    }

    private companion object {
        const val CARD_WIDTH = 460
        const val GAP = 20
        const val PADDING = 12
        const val MAX_BODY_HEIGHT = 520
        val FLOW_KEY = DiffNoteKey(emptyList(), "change-flow")
    }
}
