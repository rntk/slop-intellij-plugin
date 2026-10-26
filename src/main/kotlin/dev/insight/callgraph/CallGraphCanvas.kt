package dev.insight.callgraph

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.Gray
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
import java.awt.geom.Area
import java.awt.geom.CubicCurve2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.AbstractAction
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

/** Opacity of [Emphasis.FAINT] nodes that are not selected, highlighted or spotlighted. */
private const val FAINT_ALPHA = 0.55f

/** The colour stripe stays at least this opaque, however low the confidence. */
private const val MIN_STRIPE_ALPHA = 0.35

private val errorColor = JBColor(Color(200, 40, 40), Color(235, 95, 95))
private val matchFill = JBColor(Color(255, 236, 150), Color(98, 86, 40))
private val stepText = JBColor(Color.WHITE, Gray._30)

/** Theme-aware Java2D rendering of a [CallGraph]: callers on the left, callees on the right. */
class CallGraphCanvas(
    private val onSelect: (Int?) -> Unit,
    private val onOpen: (Int) -> Unit,
    private val popup: ((Int) -> JPopupMenu?)? = null,
    private val menuOnClick: Boolean = false,
) : JPanel() {
    private var graph: CallGraph? = null
    private var weights: Map<CallGraphEdge, Int> = emptyMap()
    private var layout: GraphLayout? = null
    private var highlight = FileGraphHighlight(emptySet(), emptySet())
    private var decorations = GraphDecorations.NONE
    private var sections: GraphSections? = null
    /** Search matches; when set, other nodes are muted and the file highlight and spotlight give way. */
    private var matches: Set<Int>? = null
    private var currentMatch: Int? = null
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


    fun highlight(value: FileGraphHighlight) {
        highlight = value
        repaint()
    }

    /**
     * Paints the enrichment overlay on top of the graph. The overlay refers to displayed ids, so the caller sets it
     * again (or [GraphDecorations.NONE]) after every [show].
     */
    fun decorate(value: GraphDecorations) {
        decorations = value
        updateScrollMode()
        repaint()
    }

    /** The legend is painted at a fixed spot of the viewport, which blit scrolling would smear. */
    private fun updateScrollMode() {
        viewport?.scrollMode = if (decorations.legend.isEmpty()) JViewport.BLIT_SCROLL_MODE else JViewport.SIMPLE_SCROLL_MODE
    }

    override fun addNotify() {
        super.addNotify()
        updateScrollMode()
    }

    fun revealFile() {
        val boxes = layout?.boxes.orEmpty()
        val id = graph?.nodes?.firstOrNull { it.id in highlight.owned && it.kind == SymbolKind.FILE && it.id in boxes }?.id
            ?: highlight.owned.firstOrNull { it in boxes }
        reveal(id ?: return)
    }

    fun reveal(id: Int) {
        val box = layout?.boxes?.get(id) ?: return
        scrollRectToVisible(Rectangle((box.x * zoom).toInt(), (box.y * zoom).toInt(),
            (box.width * zoom).toInt(), (box.height * zoom).toInt()))
    }

    /** Centres the node in the viewport, first zooming in to [minZoom] if the graph is shown smaller. */
    fun zoomTo(id: Int, minZoom: Double = 1.0) {
        val box = layout?.boxes?.get(id) ?: return
        if (zoom < minZoom) zoomAt(minZoom)
        val view = viewport ?: return reveal(id)
        moveViewport(Point((box.centerX * zoom - view.extentSize.width / 2.0).roundToInt(),
            (box.centerY * zoom - view.extentSize.height / 2.0).roundToInt()))
        repaint()
    }

    /**
     * Marks search [matches] (displayed ids) and the [current] one; null clears the marks. Like [decorate], the
     * caller sets them again after every [show].
     */
    fun markMatches(matches: Set<Int>?, current: Int? = null) {
        this.matches = matches
        currentMatch = current
        repaint()
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
                    else if (menuOnClick) selected?.let { popup?.invoke(it)?.show(this@CallGraphCanvas, e.x, e.y) }
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
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "open")
        actionMap.put("open", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) { selected?.let(onOpen) }
        })
        for ((key, delta) in mapOf("LEFT" to Point(-1, 0), "RIGHT" to Point(1, 0),
            "UP" to Point(0, -1), "DOWN" to Point(0, 1))) {
            for (fast in listOf(false, true)) {
                val stroke = if (fast) "shift $key" else key
                getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), stroke)
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

    /**
     * Edges with a [weights] entry above 1 stand for several merged relationships and are drawn thicker. With
     * [sections], nodes are arranged in titled columns instead of the free layered layout.
     */
    fun show(graph: CallGraph?, weights: Map<CallGraphEdge, Int> = emptyMap(), sections: GraphSections? = null) {
        this.graph = graph
        this.weights = weights
        this.sections = sections
        layout = graph?.let {
            if (sections != null) layoutSections(it, sections, SectionMetrics(
                nodeWidth = JBUI.scale(210),
                nodeHeight = JBUI.scale(46),
                columnGap = JBUI.scale(70),
                rowGap = JBUI.scale(18),
                padding = JBUI.scale(36),
                sectionGap = JBUI.scale(10),
                margin = JBUI.scale(20),
                maxRows = 12,
            ), sectionText)
            else layoutCallGraph(it, LayoutMetrics(
                nodeWidth = JBUI.scale(220),
                nodeHeight = JBUI.scale(46),
                columnGap = JBUI.scale(180),
                rowGap = JBUI.scale(48),
                margin = JBUI.scale(20),
            ))
        }
        highlight = FileGraphHighlight(emptySet(), emptySet())
        matches = null
        currentMatch = null
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
        val node = nodeAt(event.point)?.let { graph?.nodes?.get(it) } ?: return sectionTooltip(event.point)
        val lines = listOf("<b>${StringUtil.escapeXmlEntities(node.name)}</b>", StringUtil.escapeXmlEntities(node.container), StringUtil.escapeXmlEntities(node.location),
            node.kind.name.lowercase(),
            if (node.inProject) "" else "Library code") +
            decorations.nodes[node.id]?.tooltip?.lines().orEmpty().filter { it.isNotBlank() }.map { StringUtil.escapeXmlEntities(it) }
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
            val faint = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, FAINT_ALPHA)
            val focus = focus()
            val frames = layout?.sections
            val sections = sections
            if (frames != null && sections != null) drawSections(canvas, sections, frames)
            // In sections, nodes without a section (such as expanded files) have no box and their edges are skipped.
            val edges = graph.edges.filter { it !in decorations.hiddenEdges && it.caller in boxes && it.callee in boxes }
            // Muted edges first, so highlighted ones are drawn on top of them.
            for (edge in edges.sortedBy { edgeRank(it, focus) }) {
                val highlighted = isHighlighted(edge)
                val spotlit = isSpotlit(edge)
                val style = decorations.edges[edge]?.takeIf { edge.kind != RelationKind.CONTAINS }
                canvas.composite = if (edgeMuted(edge, focus)) muted else base
                canvas.color = when {
                    highlighted || spotlit -> accent
                    edge.kind == RelationKind.CONTAINS -> JBColor.border()
                    style == EdgeStyle.ERROR -> errorColor
                    style == EdgeStyle.MAIN -> JBColor.foreground()
                    style == EdgeStyle.INCIDENTAL -> ColorUtil.withAlpha(JBColor.GRAY, 0.5)
                    else -> JBColor.GRAY
                }
                // Merged relationships get up to three extra pixels, growing with the logarithm of their count.
                val merged = min(3, log2((weights[edge] ?: 1).toDouble()).toInt())
                val main = if (style == EdgeStyle.MAIN) 1 else 0
                var width = JBUI.scale((if (highlighted || spotlit) 2 else 1) + merged + main).toFloat()
                if (style == EdgeStyle.INCIDENTAL && !highlighted && !spotlit) width *= 0.7f
                canvas.stroke = when {
                    style == EdgeStyle.ERROR -> BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, floatArrayOf(6f, 4f), 0f)
                    edge.kind == RelationKind.IMPORT -> BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, floatArrayOf(8f, 4f), 0f)
                    edge.kind == RelationKind.REFERENCE -> BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, floatArrayOf(2f, 4f), 0f)
                    else -> BasicStroke(width)
                }
                drawEdge(canvas, boxes.getValue(edge.caller), boxes.getValue(edge.callee))
            }
            // Step markers and stacked cards reach slightly outside the box.
            val reach = JBUI.scale(8)
            for (node in graph.nodes) {
                val box = boxes[node.id] ?: continue
                if (clip != null && !clip.intersects(Rectangle(box).apply { grow(reach, reach) })) continue
                canvas.composite = when {
                    nodeMuted(node.id, focus) -> muted
                    isFaint(node.id) -> faint
                    else -> base
                }
                drawNode(canvas, node, box)
            }
        } finally { canvas.dispose() }
        if (decorations.legend.isNotEmpty()) {
            val overlay = g.create() as Graphics2D
            try {
                overlay.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                drawLegend(overlay, decorations.legend)
            } finally { overlay.dispose() }
        }
    }

    /**
     * Muting combines the file highlight ([focus]) and the spotlight: a node is kept when either set includes it,
     * and the selection stays readable while a spotlight is set. Without either, nothing is muted.
     */
    private fun nodeMuted(id: Int, focus: Set<Int>?): Boolean {
        matches?.let { return id !in it && id != selected }
        val spotlight = decorations.spotlight
        if (focus == null && spotlight == null) return false
        return (focus == null || id !in focus) && (spotlight == null || (id !in spotlight && id != selected))
    }

    /**
     * Without a spotlight, edges are muted outside the file highlight as before. With one, only
     * [GraphDecorations.spotlightEdges] and, if a file is highlighted too, that file's highlighted edges stay opaque.
     */
    private fun edgeMuted(edge: CallGraphEdge, focus: Set<Int>?): Boolean =
        if (matches != null) selected != edge.caller && selected != edge.callee
        else if (decorations.spotlight != null) !isSpotlit(edge) && !(focus != null && isHighlighted(edge))
        else focus != null && !isHighlighted(edge)

    private fun edgeRank(edge: CallGraphEdge, focus: Set<Int>?) = when {
        edgeMuted(edge, focus) -> 0
        isHighlighted(edge) || isSpotlit(edge) -> 2
        else -> 1
    }

    private fun isSpotlit(edge: CallGraphEdge) = decorations.spotlight != null && edge in decorations.spotlightEdges

    private fun isFaint(id: Int): Boolean {
        if (decorations.nodes[id]?.emphasis != Emphasis.FAINT) return false
        return id != selected && matches?.contains(id) != true && id !in highlight.owned && id !in highlight.related && decorations.spotlight?.contains(id) != true
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
        val decoration = decorations.nodes[node.id]
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
        val matched = matches?.contains(node.id) == true
        val start = decoration?.entryPoint == true
        g.color = if (matched) matchFill else if (isRoot || owned || start) rootFill else UIUtil.getPanelBackground()
        g.fillRoundRect(box.x, box.y, box.width, box.height, arc, arc)
        val stripe = decoration?.colors.orEmpty().filter { it.second > 0.0 }
        if (decoration != null && stripe.isNotEmpty()) drawStripe(g, box, arc, stripe, decoration.confidence)
        val strong = decoration?.emphasis == Emphasis.STRONG || start
        val marked = node.id == selected || owned || related || matched
        g.color = if (marked || isRoot || start) accent else if (strong) JBColor.foreground() else JBColor.border()
        val current = if (node.id == currentMatch) 2 else 0
        val borderWidth = JBUI.scale((if (marked) 2 else 1) + (if (strong) 1 else 0) + current).toFloat()
        g.stroke = if (node.inProject) BasicStroke(borderWidth)
        else BasicStroke(borderWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f)
        g.drawRoundRect(box.x, box.y, box.width, box.height, arc, arc)

        val padding = JBUI.scale(8)
        val left = box.x + padding + if (stripe.isNotEmpty()) JBUI.scale(3) else 0
        var right = box.maxX.toInt() - padding
        val line = box.y + box.height / 2 - JBUI.scale(3)
        val badge = when {
            node.hidden > 0 -> "+${node.hidden}"
            node.expanded -> "−"
            else -> null
        }
        g.color = UIUtil.getContextHelpForeground()
        if (badge != null) {
            g.font = font.deriveFont(font.size2D - 1f)
            val badgeWidth = g.fontMetrics.stringWidth(badge)
            g.drawString(badge, right - badgeWidth, line)
            right -= badgeWidth + padding
        }
        val badges = decoration?.badges.orEmpty()
        if (badges.isNotEmpty()) {
            g.font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            val badgesWidth = g.fontMetrics.stringWidth(badges)
            g.drawString(badges, right - badgesWidth, line)
            right -= badgesWidth + padding
        }
        g.font = font.deriveFont(Font.BOLD)
        var nameLeft = left
        if (start) {
            val marker = if (g.font.canDisplay('▶')) "▶ Start" else "> Start"
            g.color = accent
            g.drawString(marker, nameLeft, line)
            nameLeft += g.fontMetrics.stringWidth(marker) + JBUI.scale(4)
        }
        g.color = if (node.inProject) JBColor.foreground() else UIUtil.getContextHelpForeground()
        g.drawString(fit(node.name, g.fontMetrics, right - nameLeft), nameLeft, line)
        g.font = font.deriveFont(font.size2D - 1f)
        g.color = UIUtil.getContextHelpForeground()
        val detail = decoration?.label
            ?: if (graph?.projectGraph == true) "${node.kind.name.lowercase()} · ${node.location}" else node.container.ifEmpty { node.location }
        g.drawString(fit(detail, g.fontMetrics, box.maxX.toInt() - padding - left), left, box.y + box.height / 2 + g.fontMetrics.ascent)
        decoration?.step?.let { drawStep(g, box, it) }
    }

    /** A vertical stripe on the node's left edge, split by share and clipped to the rounded box. */
    private fun drawStripe(g: Graphics2D, box: Rectangle, arc: Int, colors: List<Pair<Color, Double>>, confidence: Double) {
        val alpha = confidence.coerceIn(MIN_STRIPE_ALPHA, 1.0)
        val outline = Area(RoundRectangle2D.Double(box.x.toDouble(), box.y.toDouble(), box.width.toDouble(), box.height.toDouble(),
            arc.toDouble(), arc.toDouble()))
        val total = colors.sumOf { it.second }
        val width = JBUI.scale(5).toDouble()
        var top = box.y.toDouble()
        for ((index, entry) in colors.withIndex()) {
            val bottom = if (index == colors.lastIndex) box.maxY else top + box.height * entry.second / total
            val part = Area(Rectangle2D.Double(box.x.toDouble(), top, width, bottom - top)).apply { intersect(outline) }
            g.color = ColorUtil.withAlpha(entry.first, alpha * entry.first.alpha / 255.0)
            g.fill(part)
            top = bottom
        }
    }

    /** Numbered accent circle centred on the node's top-left corner. */
    private fun drawStep(g: Graphics2D, box: Rectangle, step: Int) {
        g.font = font.deriveFont(Font.BOLD, font.size2D - 2f)
        val text = step.toString()
        val metrics = g.fontMetrics
        val diameter = max(JBUI.scale(16), metrics.stringWidth(text) + JBUI.scale(6))
        val x = box.x - diameter / 2
        val y = box.y - diameter / 2
        g.color = accent
        g.fillOval(x, y, diameter, diameter)
        g.color = stepText
        g.drawString(text, x + (diameter - metrics.stringWidth(text)) / 2, y + (diameter - metrics.height) / 2 + metrics.ascent)
    }

    /** Colour swatch and label per entry in the top-left corner of the visible area, unaffected by zoom and scrolling. */
    private fun drawLegend(g: Graphics2D, legend: List<LegendEntry>) {
        val area = visibleRect
        if (area.isEmpty) return
        g.font = font.deriveFont(font.size2D - 1f)
        val metrics = g.fontMetrics
        val margin = JBUI.scale(8)
        val padding = JBUI.scale(6)
        val swatch = JBUI.scale(10)
        val row = max(metrics.height, swatch) + JBUI.scale(2)
        val entries = legend.take(max(1, (area.height - 2 * margin - 2 * padding) / row))
        val width = padding * 2 + swatch + JBUI.scale(6) + entries.maxOf { metrics.stringWidth(it.label) }
        val height = padding * 2 + row * entries.size
        val x = area.x + margin
        val y = area.y + margin
        val arc = JBUI.scale(8)
        g.color = ColorUtil.withAlpha(UIUtil.getPanelBackground(), 0.85)
        g.fillRoundRect(x, y, width, height, arc, arc)
        g.color = JBColor.border()
        g.stroke = BasicStroke(1f)
        g.drawRoundRect(x, y, width, height, arc, arc)
        for ((index, entry) in entries.withIndex()) {
            val top = y + padding + index * row
            g.color = entry.color
            g.fillRoundRect(x + padding, top + (row - swatch) / 2, swatch, swatch, JBUI.scale(3), JBUI.scale(3))
            g.color = JBColor.foreground()
            g.drawString(entry.label, x + padding + swatch + JBUI.scale(6), top + (row - metrics.height) / 2 + metrics.ascent)
        }
    }

    private val bannerFont get() = font.deriveFont(Font.BOLD, font.size2D + 8f)
    private val titleFont get() = font.deriveFont(Font.BOLD, font.size2D + 1f)
    private val boldFont get() = font.deriveFont(Font.BOLD)
    private val smallFont get() = font.deriveFont(font.size2D - 1f)
    private val textPadding get() = JBUI.scale(10)

    /** Annotation of a section: bold headline running into the plain description. */
    private fun annotationRuns(section: Section) = listOfNotNull(section.headline?.let { TextRun(it, boldFont) },
        section.description?.let { TextRun(it, font) })

    private fun factRuns(section: Section) = listOf(TextRun(section.facts.joinToString(" · "), smallFont)).takeIf { section.facts.isNotEmpty() }

    private fun itemRuns(item: FooterItem) = listOfNotNull(TextRun(item.headline, boldFont), item.text?.let { TextRun(it, font) })

    private fun footerColumns(footer: SectionFooter) = footer.items.size.coerceIn(1, 4)

    private fun footerColumnWidth(footer: SectionFooter, width: Int): Int {
        val columns = footerColumns(footer)
        return (width - 2 * textPadding - (columns - 1) * 2 * textPadding) / columns
    }

    private fun lineHeight(font: Font) = getFontMetrics(font).height

    private val sectionText = object : SectionText {
        override fun banner(sections: GraphSections, width: Int): Int = 2 * textPadding + lineHeight(bannerFont) +
            (sections.subtitle?.let { wrap(listOf(TextRun(it, font)), width - 2 * textPadding).size * lineHeight(font) } ?: 0)

        override fun title(): Int = lineHeight(titleFont) + 2 * textPadding

        override fun annotation(section: Section, width: Int): Int {
            val text = wrap(annotationRuns(section), width).size * lineHeight(font)
            val facts = factRuns(section)?.let { wrap(it, width).size * lineHeight(smallFont) + textPadding / 2 } ?: 0
            return if (text + facts == 0) 0 else text + facts + 2 * textPadding
        }

        override fun footer(footer: SectionFooter, width: Int): Int {
            val itemWidth = footerColumnWidth(footer, width)
            val rows = footer.items.chunked(footerColumns(footer)).sumOf { row -> row.maxOf { wrap(itemRuns(it), itemWidth).size } }
            return 3 * textPadding + lineHeight(boldFont) + rows * lineHeight(font) + (footer.items.size - 1) / footerColumns(footer) * textPadding
        }
    }

    /** Banner, section columns with title bands and annotations, and the footer, painted below edges and nodes. */
    private fun drawSections(g: Graphics2D, sections: GraphSections, frames: SectionFrames) {
        val clip = g.clipBounds
        val banner = frames.banner
        if (clip == null || clip.intersects(banner)) {
            g.color = accent
            g.fillRect(banner.x, banner.y, banner.width, banner.height)
            g.color = stepText
            g.font = bannerFont
            var y = banner.y + textPadding
            drawCentered(g, fit(sections.title, g.fontMetrics, banner.width - 2 * textPadding), banner.x, banner.width, y)
            y += g.fontMetrics.height
            sections.subtitle?.let { drawLines(g, wrap(listOf(TextRun(it, font)), banner.width - 2 * textPadding), banner.x, banner.width, y, center = true) }
        }
        for ((index, column) in frames.columns.withIndex()) {
            if (clip != null && !clip.intersects(column)) continue
            val section = sections.sections.getOrNull(index) ?: continue
            val tint = section.color
            g.color = tint?.let { ColorUtil.withAlpha(it, 0.10) } ?: ColorUtil.withAlpha(JBColor.foreground(), if (index % 2 == 0) 0.035 else 0.06)
            g.fillRect(column.x, column.y, column.width, column.height)
            g.color = tint?.let { ColorUtil.withAlpha(it, 0.35) } ?: ColorUtil.withAlpha(JBColor.foreground(), 0.10)
            g.fillRect(column.x, column.y, column.width, frames.titleHeight)
            g.color = JBColor.foreground()
            g.font = titleFont
            drawCentered(g, fit(section.title, g.fontMetrics, column.width - 2 * textPadding), column.x, column.width, column.y + textPadding)
            val width = column.width - JBUI.scale(36)
            var y = column.y + frames.titleHeight + textPadding
            val annotation = wrap(annotationRuns(section), width)
            y = drawLines(g, annotation, column.x, column.width, y, center = true)
            factRuns(section)?.let {
                g.color = UIUtil.getContextHelpForeground()
                drawLines(g, wrap(it, width), column.x, column.width, y + textPadding / 2, center = true)
            }
        }
        val footer = frames.footer ?: return
        val content = sections.footer ?: return
        if (clip != null && !clip.intersects(footer)) return
        g.color = ColorUtil.withAlpha(JBColor.foreground(), 0.06)
        g.fillRect(footer.x, footer.y, footer.width, footer.height)
        g.font = boldFont
        val metrics = g.fontMetrics
        var y = footer.y + textPadding
        val titleWidth = metrics.stringWidth(content.title)
        g.color = JBColor.foreground()
        drawCentered(g, content.title, footer.x, footer.width, y)
        // Arrows on both sides of the title, as the footer applies across all sections.
        val middle = y + metrics.height / 2.0
        val gap = JBUI.scale(16)
        val left = footer.x + textPadding.toDouble()
        val right = footer.maxX - textPadding
        val titleLeft = footer.centerX - titleWidth / 2.0 - gap
        val titleRight = footer.centerX + titleWidth / 2.0 + gap
        g.stroke = BasicStroke(1f)
        if (titleLeft - left > gap) {
            g.draw(java.awt.geom.Line2D.Double(left, middle, titleLeft, middle))
            g.draw(java.awt.geom.Line2D.Double(titleRight, middle, right, middle))
            arrowHead(g, Point2D.Double(left + gap, middle), Point2D.Double(left, middle))
            arrowHead(g, Point2D.Double(right - gap, middle), Point2D.Double(right, middle))
        }
        y += metrics.height + textPadding
        val columns = footerColumns(content)
        val itemWidth = footerColumnWidth(content, footer.width)
        for (row in content.items.chunked(columns)) {
            var bottom = y
            for ((index, item) in row.withIndex()) {
                g.color = JBColor.foreground()
                val x = footer.x + textPadding + index * (itemWidth + 2 * textPadding)
                bottom = max(bottom, drawLines(g, wrap(itemRuns(item), itemWidth), x, itemWidth, y, center = false))
            }
            y = bottom + textPadding
        }
    }

    private fun drawCentered(g: Graphics2D, text: String, x: Int, width: Int, top: Int) {
        val metrics = g.fontMetrics
        g.drawString(text, x + (width - metrics.stringWidth(text)) / 2, top + metrics.ascent)
    }

    /** Draws wrapped lines from [top], centred in or left-aligned at [x]; returns the bottom of the last line. */
    private fun drawLines(g: Graphics2D, lines: List<List<TextRun>>, x: Int, width: Int, top: Int, center: Boolean): Int {
        var y = top
        for (line in lines) {
            // Words of one font are drawn as one string, so spacing follows the font's own metrics.
            val segments = mutableListOf<TextRun>()
            for (word in line) {
                val last = segments.lastOrNull()
                if (last != null && last.font == word.font) segments[segments.lastIndex] = TextRun("${last.text} ${word.text}", word.font)
                else segments += word
            }
            val widths = segments.mapIndexed { index, segment ->
                val metrics = g.getFontMetrics(segment.font)
                metrics.stringWidth(segment.text) + if (index > 0) metrics.charWidth(' ') else 0
            }
            var left = if (center) x + (width - widths.sum()) / 2 else x
            for ((index, segment) in segments.withIndex()) {
                val metrics = g.getFontMetrics(segment.font)
                if (index > 0) left += metrics.charWidth(' ')
                g.font = segment.font
                g.drawString(segment.text, left, y + metrics.ascent)
                left += metrics.stringWidth(segment.text)
            }
            y += line.maxOf { lineHeight(it.font) }
        }
        return y
    }

    /** Greedy word wrap of mixed-font text into lines of single words; a word wider than [width] gets its own line. */
    private fun wrap(runs: List<TextRun>, width: Int): List<List<TextRun>> {
        val lines = mutableListOf<MutableList<TextRun>>()
        var current = mutableListOf<TextRun>()
        var used = 0
        for (run in runs) {
            val metrics = getFontMetrics(run.font)
            for (word in run.text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val wordWidth = metrics.stringWidth(word)
                val needed = if (current.isEmpty()) wordWidth else used + metrics.charWidth(' ') + wordWidth
                if (current.isNotEmpty() && needed > width) {
                    lines += current
                    current = mutableListOf()
                    used = wordWidth
                } else used = needed
                current += TextRun(word, run.font)
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun sectionTooltip(point: Point): String? {
        val frames = layout?.sections ?: return null
        val sections = sections ?: return null
        val x = point.x / zoom
        val y = point.y / zoom
        val index = frames.columns.indexOfFirst { it.contains(x, y) && y < it.y + frames.titleHeight + frames.annotationHeight }
        val section = sections.sections.getOrNull(index) ?: return null
        val lines = listOf("<b>${StringUtil.escapeXmlEntities(section.title)}</b>") +
            listOfNotNull(section.headline, section.description, section.facts.joinToString(" · ").ifEmpty { null }).map(StringUtil::escapeXmlEntities) +
            section.tooltip?.lines().orEmpty().filter { it.isNotBlank() }.map(StringUtil::escapeXmlEntities)
        return lines.joinToString("<br>", "<html>", "</html>")
    }

    private class TextRun(val text: String, val font: Font)

    private fun fit(text: String, metrics: FontMetrics, width: Int): String {
        if (metrics.stringWidth(text) <= width) return text
        var end = text.length
        while (end > 0 && metrics.stringWidth(text.take(end) + "…") > width) end--
        return text.take(end) + "…"
    }
}
