package dev.insight.callgraph

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.ui.accent
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ActionEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.atan2
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private val rootFill = JBColor(Color(232, 232, 252), Color(58, 58, 92))

/** Opacity of nodes and edges outside the highlighted file's neighbourhood, so the highlight stands out. */
private const val MUTED_ALPHA = 0.25f

/** Theme-aware Java2D rendering of a [CallGraph]: callers on the left, callees on the right. */
class CallGraphCanvas(
    private val onSelect: (Int?) -> Unit,
    private val onOpen: (Int) -> Unit,
    private val popup: ((Int) -> JPopupMenu?)? = null,
) : JPanel() {
    private var graph: CallGraph? = null
    private var weights: Map<CallGraphEdge, Int> = emptyMap()
    private var layout: GraphLayout? = null
    private var highlight = FileGraphHighlight(emptySet(), emptySet())
    private var scale = 1.0
    var zoom: Double
        get() = scale
        set(value) { zoomAt(value) }

    private val viewport: JViewport?
        get() = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport

    /** Keeps the graph point under the cursor (or viewport centre) stationary while scaling. */
    fun zoomAt(value: Double, anchor: Point? = null) {
        val next = value.coerceIn(0.15, 2.0)
        if (next == scale) return
        val view = viewport
        val position = view?.viewPosition ?: Point()
        val point = anchor ?: Point(position.x + (view?.extentSize?.width ?: width) / 2,
            position.y + (view?.extentSize?.height ?: height) / 2)
        val old = scale
        scale = next
        revalidate()
        if (view != null) {
            view.viewSize = preferredSize
            moveViewport(Point((point.x * next / old - (point.x - position.x)).roundToInt(),
                (point.y * next / old - (point.y - position.y)).roundToInt()))
        }
        firePropertyChange("zoom", old, next)
        repaint()
    }

    private fun moveViewport(position: Point) {
        val view = viewport ?: return
        val size = preferredSize
        view.viewPosition = Point(position.x.coerceIn(0, max(0, size.width - view.extentSize.width)),
            position.y.coerceIn(0, max(0, size.height - view.extentSize.height)))
    }

    fun highlightFile(path: String?) = highlight(graph?.highlightFile(path) ?: FileGraphHighlight(emptySet(), emptySet()))

    fun highlight(value: FileGraphHighlight) {
        highlight = value
        repaint()
    }

    fun revealFile() {
        val id = graph?.nodes?.firstOrNull { it.id in highlight.owned && it.kind == SymbolKind.FILE }?.id
            ?: highlight.owned.firstOrNull()
        reveal(id ?: return)
    }

    fun reveal(id: Int) {
        val box = layout?.boxes?.get(id) ?: return
        scrollRectToVisible(Rectangle((box.x * zoom).toInt(), (box.y * zoom).toInt(),
            (box.width * zoom).toInt(), (box.height * zoom).toInt()))
    }

    var selected: Int? = null
        private set

    init {
        isFocusable = true
        ToolTipManager.sharedInstance().registerComponent(this)
        val mouse = object : MouseAdapter() {
            private var start: Point? = null
            private var startPosition = Point()
            private var dragged = false

            override fun mousePressed(e: MouseEvent) {
                if (showPopup(e)) return
                if (!SwingUtilities.isLeftMouseButton(e) && !SwingUtilities.isMiddleMouseButton(e)) return
                requestFocusInWindow()
                start = viewport?.let { SwingUtilities.convertPoint(this@CallGraphCanvas, e.point, it) }
                startPosition = viewport?.viewPosition ?: Point()
                dragged = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val origin = start ?: return
                val view = viewport ?: return
                val point = SwingUtilities.convertPoint(this@CallGraphCanvas, e.point, view)
                if (!dragged && origin.distance(point) < JBUI.scale(4)) return
                dragged = true
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                moveViewport(Point(startPosition.x + origin.x - point.x, startPosition.y + origin.y - point.y))
            }

            override fun mouseReleased(e: MouseEvent) {
                if (showPopup(e)) return
                if (start == null) return
                if (!dragged && SwingUtilities.isLeftMouseButton(e)) {
                    select(nodeAt(e.point))
                    if (e.clickCount == 2) selected?.let(onOpen)
                }
                start = null
                cursor = Cursor.getDefaultCursor()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener { event ->
            zoomAt(zoom * 1.15.pow(-event.preciseWheelRotation), event.point)
            event.consume()
        }
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "open")
        actionMap.put("open", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { selected?.let(onOpen) }
        })
        for ((key, delta) in mapOf("LEFT" to Point(-1, 0), "RIGHT" to Point(1, 0),
            "UP" to Point(0, -1), "DOWN" to Point(0, 1))) {
            for (fast in listOf(false, true)) {
                val stroke = if (fast) "shift $key" else key
                getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), stroke)
                actionMap.put(stroke, object : AbstractAction() {
                    override fun actionPerformed(e: ActionEvent?) {
                        val position = viewport?.viewPosition ?: return
                        val step = JBUI.scale(if (fast) 160 else 40)
                        moveViewport(Point(position.x + delta.x * step, position.y + delta.y * step))
                    }
                })
            }
        }
    }

    /** Edges with a [weights] entry above 1 stand for several merged relationships and are drawn thicker. */
    fun show(graph: CallGraph?, weights: Map<CallGraphEdge, Int> = emptyMap()) {
        this.graph = graph
        this.weights = weights
        layout = graph?.let {
            layoutCallGraph(it, LayoutMetrics(
                nodeWidth = JBUI.scale(220),
                nodeHeight = JBUI.scale(46),
                columnGap = JBUI.scale(180),
                rowGap = JBUI.scale(48),
                margin = JBUI.scale(20),
            ))
        }
        highlight = FileGraphHighlight(emptySet(), emptySet())
        select(null)
        revalidate()
        repaint()
    }

    /** Shows the context menu for the node under a popup-trigger click; returns whether the event was handled. */
    private fun showPopup(e: MouseEvent): Boolean {
        if (!e.isPopupTrigger || popup == null) return false
        val id = nodeAt(e.point) ?: return true
        requestFocusInWindow()
        select(id)
        popup.invoke(id)?.show(this, e.x, e.y)
        return true
    }

    fun select(id: Int?) {
        if (id == selected) return
        selected = id
        onSelect(id)
        repaint()
    }

    private fun nodeAt(point: Point) = layout?.boxes?.entries?.firstOrNull { it.value.contains(point.x / zoom, point.y / zoom) }?.key

    override fun getPreferredSize(): Dimension = layout?.size?.let { Dimension((it.width * zoom).toInt(), (it.height * zoom).toInt()) } ?: Dimension(0, 0)

    override fun getToolTipText(event: MouseEvent): String? {
        val node = nodeAt(event.point)?.let { graph?.nodes?.get(it) } ?: return null
        val lines = listOf("<b>${StringUtil.escapeXmlEntities(node.name)}</b>", StringUtil.escapeXmlEntities(node.container), StringUtil.escapeXmlEntities(node.location),
            node.kind.name.lowercase(),
            if (node.inProject) "" else "Library code")
        return lines.filter { it.isNotEmpty() }.joinToString("<br>", "<html>", "</html>") { it }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graph = graph ?: return
        val boxes = layout?.boxes ?: return
        val canvas = g.create() as Graphics2D
        try {
            canvas.scale(zoom, zoom)
            val clip = canvas.clipBounds
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val base = canvas.composite
            val muted = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, MUTED_ALPHA)
            val focus = focus()
            // Muted edges first, so highlighted ones are drawn on top of them.
            for (edge in graph.edges.sortedBy { isHighlighted(it) }) {
                val highlighted = isHighlighted(edge)
                canvas.composite = if (focus != null && !highlighted) muted else base
                canvas.color = if (highlighted) accent else if (edge.kind == RelationKind.CONTAINS) JBColor.border() else JBColor.GRAY
                // Merged relationships get up to three extra pixels, growing with the logarithm of their count.
                val merged = min(3, log2((weights[edge] ?: 1).toDouble()).toInt())
                val width = JBUI.scale((if (highlighted) 2 else 1) + merged).toFloat()
                canvas.stroke = when (edge.kind) {
                    RelationKind.IMPORT -> BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, floatArrayOf(8f, 4f), 0f)
                    RelationKind.REFERENCE -> BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, floatArrayOf(2f, 4f), 0f)
                    else -> BasicStroke(width)
                }
                drawEdge(canvas, boxes.getValue(edge.caller), boxes.getValue(edge.callee))
            }
            for (node in graph.nodes) {
                val box = boxes.getValue(node.id)
                if (clip != null && !clip.intersects(box)) continue
                canvas.composite = if (focus != null && node.id !in focus) muted else base
                drawNode(canvas, node, box)
            }
        } finally { canvas.dispose() }
    }

    private fun isHighlighted(edge: CallGraphEdge) = selected == edge.caller || selected == edge.callee ||
        edge.caller in highlight.owned || edge.callee in highlight.owned

    /**
     * Nodes kept at full opacity while a file is highlighted: the file's own nodes, their direct neighbours,
     * and the selection with its neighbours. Returns null when nothing is highlighted and nothing is muted.
     */
    internal fun focus(): Set<Int>? {
        if (highlight.owned.isEmpty()) return null
        val focus = HashSet(highlight.owned).apply { addAll(highlight.related) }
        selected?.let { id ->
            focus += id
            graph?.edges?.forEach { if (it.caller == id) focus += it.callee else if (it.callee == id) focus += it.caller }
        }
        return focus
    }

    private fun drawEdge(g: Graphics2D, from: Rectangle, to: Rectangle) {
        val bend = JBUI.scale(40).toDouble()
        val curve = when {
            from == to -> CubicCurve2D.Double(from.maxX, from.centerY - 6, from.maxX + bend, from.y - bend / 2,
                from.maxX + bend, from.maxY + bend / 2, from.maxX, from.centerY + 6)
            from.x < to.x -> {
                val dx = max(bend, (to.x - from.maxX) / 2)
                CubicCurve2D.Double(from.maxX, from.centerY, from.maxX + dx, from.centerY,
                    to.x - dx, to.centerY, to.x.toDouble(), to.centerY)
            }
            from.x == to.x -> CubicCurve2D.Double(from.maxX, from.centerY, from.maxX + bend, from.centerY,
                to.maxX + bend, to.centerY, to.maxX, to.centerY)
            // Calls back towards the root, for example recursion through several functions.
            else -> CubicCurve2D.Double(from.centerX, from.maxY, from.centerX, from.maxY + bend,
                to.centerX, to.maxY + bend, to.centerX, to.maxY)
        }
        val bounds = curve.bounds.apply { grow(JBUI.scale(8), JBUI.scale(8)) }
        if (g.clipBounds?.intersects(bounds) == false) return
        g.draw(curve)
        arrowHead(g, curve.ctrlP2, curve.p2)
    }

    private fun arrowHead(g: Graphics2D, from: Point2D, tip: Point2D) {
        val angle = atan2(tip.y - from.y, tip.x - from.x)
        val size = JBUI.scale(8).toDouble()
        val head = Path2D.Double().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - size * Math.cos(angle - 0.4), tip.y - size * Math.sin(angle - 0.4))
            lineTo(tip.x - size * Math.cos(angle + 0.4), tip.y - size * Math.sin(angle + 0.4))
            closePath()
        }
        g.fill(head)
    }

    private fun drawNode(g: Graphics2D, node: CallGraphNode, box: Rectangle) {
        val arc = JBUI.scale(10)
        if (node.hidden > 0) {
            // Collapsed groups look like a stack of cards.
            val offset = JBUI.scale(4)
            g.color = UIUtil.getPanelBackground()
            g.fillRoundRect(box.x + offset, box.y + offset, box.width, box.height, arc, arc)
            g.color = JBColor.border()
            g.stroke = BasicStroke(1f)
            g.drawRoundRect(box.x + offset, box.y + offset, box.width, box.height, arc, arc)
        }
        val isRoot = graph?.projectGraph != true && node.level == 0
        val owned = node.id in highlight.owned
        val related = node.id in highlight.related
        g.color = if (isRoot || owned) rootFill else UIUtil.getPanelBackground()
        g.fillRoundRect(box.x, box.y, box.width, box.height, arc, arc)
        g.color = if (node.id == selected || isRoot || owned || related) accent else JBColor.border()
        g.stroke = if (node.inProject) BasicStroke(JBUI.scale(if (node.id == selected || owned || related) 2 else 1).toFloat())
        else BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
        g.drawRoundRect(box.x, box.y, box.width, box.height, arc, arc)

        val padding = JBUI.scale(8)
        val textWidth = box.width - 2 * padding
        var nameWidth = textWidth
        val badge = when {
            node.hidden > 0 -> "+${node.hidden}"
            node.expanded -> "\u2212"
            else -> null
        }
        if (badge != null) {
            g.font = font.deriveFont(font.size2D - 1f)
            g.color = UIUtil.getContextHelpForeground()
            val badgeWidth = g.fontMetrics.stringWidth(badge)
            g.drawString(badge, box.maxX.toInt() - padding - badgeWidth, box.y + box.height / 2 - JBUI.scale(3))
            nameWidth -= badgeWidth + padding
        }
        g.font = font.deriveFont(Font.BOLD)
        g.color = if (node.inProject) JBColor.foreground() else UIUtil.getContextHelpForeground()
        g.drawString(fit(node.name, g.fontMetrics, nameWidth), box.x + padding, box.y + box.height / 2 - JBUI.scale(3))
        g.font = font.deriveFont(font.size2D - 1f)
        g.color = UIUtil.getContextHelpForeground()
        val detail = if (graph?.projectGraph == true) "${node.kind.name.lowercase()} · ${node.location}" else node.container.ifEmpty { node.location }
        g.drawString(fit(detail, g.fontMetrics, textWidth), box.x + padding, box.y + box.height / 2 + g.fontMetrics.ascent)
    }

    private fun fit(text: String, metrics: FontMetrics, width: Int): String {
        if (metrics.stringWidth(text) <= width) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.take(end) + "…") > width) end--
        return text.take(end) + "…"
    }
}
