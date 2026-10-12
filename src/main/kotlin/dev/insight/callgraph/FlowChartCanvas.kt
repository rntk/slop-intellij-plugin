package dev.insight.callgraph

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.ui.accent
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
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JMenuItem
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private val spineColor = JBColor(Color(0xC9CED6), Color(0x5A5F66))
private val otherwiseColor = JBColor(Color(0xB4B9C0), Color(0x6A6F76))
private val errorColor = JBColor(Color(200, 40, 40), Color(235, 95, 95))
private val pillFill = JBColor(Color(0xE8F0FE), Color(0x2E3A4F))
private val decisionFill = JBColor(Color(0xFFF4D6), Color(0x4A4130))
private val decisionBorder = JBColor(Color(0xE0B050), Color(0x8A7340))
private val searchFill = JBColor(Color(255, 236, 150), Color(98, 86, 40))
private val selectionFill = JBColor(Color(0xEEF3FD), Color(0x2B3445))
private val mutedText = JBColor(Color(0x6E737A), Color(0x9DA2A8))

/**
 * Theme-aware Java2D rendering of a [FlowBoard]: functions placed on the chart, each a column laid out by
 * [layoutFlowChart], explored from any step. The pill below a step shows or hides what it calls; the pill above it
 * shows or hides its direct callers, so the chart grows in both directions on the same canvas. Click a step to select
 * it, double-click or Enter to open it, click a back reference to jump to its step. Drag pans; Ctrl/Cmd + wheel zooms;
 * Up/Down step through the chart; Left toggles callers, Right or Space toggles calls, Delete removes a column.
 */
class FlowChartCanvas(
    private val onSelect: (Int?) -> Unit,
    private val onOpen: (Int) -> Unit,
    /** The menu of a function, or of the chart itself for null. */
    private val popup: ((Int?) -> JPopupMenu?)? = null,
    /** Called with each chart built, for example to read the call sites of steps that just appeared. */
    private val onChart: (FlowChart?) -> Unit = {},
    /** Shown while nothing is on the chart. */
    private val emptyText: String = "Nothing on the chart yet.",
    private val onRegionSelect: (FlowRegion?) -> Unit = {},
) : JPanel() {
    var board = FlowBoard()
        private set
    private var source: FlowSource? = null
    var chart: FlowChart? = null
        private set
    private var message: String? = null
    internal var geometry: FlowGeometry? = null
        private set
    private var scale = 1.0
    internal var searchMatches: Set<Int>? = null
        private set
    private var regionMatches: Set<String> = emptySet()

    fun markRegionMatches(keys: Set<String>) { regionMatches = keys; repaint() }

    /** Highlights matching cards while keeping their surrounding steps and branches visible. */
    fun markMatches(nodes: Set<Int>?) {
        searchMatches = nodes
        repaint()
    }

    /** The function whose step is selected. */
    var selectedNode: Int? = null
        private set
    var selectedRegion: String? = null
        private set

    private val metaFont get() = UIUtil.getLabelFont()
    private val titleFont get() = UIUtil.getLabelFont().let { it.deriveFont(Font.BOLD, it.size2D + 2) }
    private val textFont get() = UIUtil.getLabelFont().let { it.deriveFont(it.size2D - 1) }
    private val smallFont get() = JBUI.Fonts.smallFont()
    private val bannerFont get() = UIUtil.getLabelFont().let { it.deriveFont(Font.BOLD, it.size2D + 6) }

    var zoom: Double
        get() = scale
        set(value) { zoomAt(value) }

    private val viewport: JViewport?
        get() = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport

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
                start = viewport?.let { SwingUtilities.convertPoint(this@FlowChartCanvas, e.point, it) } ?: e.point
                startPosition = viewport?.viewPosition ?: Point()
                dragged = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val origin = start ?: return
                val view = viewport ?: return
                val point = SwingUtilities.convertPoint(this@FlowChartCanvas, e.point, view)
                if (!dragged && origin.distance(point) < JBUI.scale(4)) return
                dragged = true
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                moveViewport(Point(startPosition.x + origin.x - point.x, startPosition.y + origin.y - point.y))
            }

            override fun mouseReleased(e: MouseEvent) {
                if (showPopup(e)) return
                if (start == null) return
                if (!dragged && SwingUtilities.isLeftMouseButton(e)) click(e.point, e.clickCount)
                start = null
                cursor = Cursor.getDefaultCursor()
            }

            override fun mouseMoved(e: MouseEvent) {
                cursor = if (shapeAt(e.point) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener { event ->
            if (event.isControlDown || event.isMetaDown) {
                zoomAt(zoom * 1.15.pow(-event.preciseWheelRotation), event.point)
                event.consume()
            } else {
                // Plain scrolling belongs to the scroll pane.
                parent?.dispatchEvent(SwingUtilities.convertMouseEvent(this, event, parent))
            }
        }
        fun key(stroke: String, action: () -> Unit) {
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), stroke)
            actionMap.put(stroke, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = action()
            })
        }
        key("ENTER") { selectedRegion?.let(::toggleRegion) ?: selectedNode?.let(onOpen) }
        key("DOWN") { step(1) }
        key("UP") { step(-1) }
        key("SPACE") { selectedRegion?.let(::toggleRegion) ?: selectedNode?.let(::toggleCalls) }
        key("RIGHT") { selectedRegion?.let { if (it !in board.openRegions) toggleRegion(it) } ?: selectedNode?.let(::toggleCalls) }
        key("LEFT") { selectedRegion?.let { if (it in board.openRegions) toggleRegion(it) } ?: selectedNode?.let(::toggleCallers) }
        key("DELETE") { selectedNode?.let(::removeColumn) }
        key("BACK_SPACE") { selectedNode?.let(::removeColumn) }
    }

    /**
     * Draws the board with the calls of [source] (new call sites, cards or ids of the same graph), or [message] when
     * there is no source yet. The board and selection are kept.
     */
    fun show(source: FlowSource?, message: String? = null) {
        this.source = source
        this.message = message
        update(board)
    }

    /**
     * Moves the board and selection into a rebuilt graph by [id]; functions it maps to null are dropped. The calls of
     * the old graph no longer apply, so the chart is drawn again by the next [show].
     */
    fun remap(id: (Int) -> Int?) {
        board = board.map(id)
        selectedNode = selectedNode?.let(id)
        selectedRegion = null
        source = null
    }

    /** Places [start] on the chart as a new column with its calls shown, or selects it when it is already a column. */
    fun add(start: FlowStart) {
        if (board.starts.none { it.node == start.node }) {
            update(board.copy(starts = board.starts + start, openCalls = board.openCalls + start.node))
        }
        selectNode(start.node)
    }

    /** Shows what each of [nodes] calls, wherever its step is. */
    fun openCalls(nodes: Set<Int>) {
        if (!board.openCalls.containsAll(nodes)) update(board.copy(openCalls = board.openCalls + nodes))
    }

    /** Takes the column of [node] off the chart; what is open elsewhere stays open. */
    fun removeColumn(node: Int) {
        if (board.starts.any { it.node == node }) update(board.copy(starts = board.starts.filter { it.node != node }))
    }

    fun clear() = update(FlowBoard(compact = board.compact))

    fun setCompact(compact: Boolean) = update(board.copy(compact = compact))

    fun collapseRegions() = update(board.copy(openRegions = emptySet()))

    fun toggleRegion(key: String) = update(board.copy(
        openRegions = if (key in board.openRegions) board.openRegions - key else board.openRegions + key))

    fun selectRegion(key: String): Boolean {
        val box = geometry?.shapes?.filterIsInstance<FlowRegionBox>()?.firstOrNull { it.region.plan.evidence.key == key }
            ?: return false
        select(null)
        selectedRegion = key
        onRegionSelect(box.region)
        val bounds = box.bounds
        scrollRectToVisible(Rectangle((bounds.x * zoom).toInt(), (bounds.y * zoom).toInt(),
            (bounds.width * zoom).toInt(), (bounds.height * zoom).toInt()))
        repaint()
        return true
    }

    /** Shows or hides what [node] calls, wherever its step is. */
    fun toggleCalls(node: Int) =
        update(board.copy(openCalls = if (node in board.openCalls) board.openCalls - node else board.openCalls + node))

    /** Shows or hides the direct callers of [node] above its step, keeping what is open further up for reopening. */
    fun toggleCallers(node: Int) {
        update(board.copy(openCallers = if (node in board.openCallers) board.openCallers - node else board.openCallers + node))
        selectedNode?.let(::reveal)
    }

    private fun update(next: FlowBoard) {
        board = next
        val source = source
        chart = if (source == null || next.starts.isEmpty()) null else buildFlowChart(next, source)
        geometry = chart?.let { layoutFlowChart(it, metrics(), measure) }
        revalidate()
        viewport?.let { it.viewSize = preferredSize }
        if (selectedNode != null && geometry?.cards?.containsKey(selectedNode) != true) {
            selectedNode = null
            onSelect(null)
        }
        if (selectedRegion != null && chart?.regions?.none { it.plan.evidence.key == selectedRegion } != false) {
            selectedRegion = null
            onRegionSelect(null)
        }
        repaint()
        onChart(chart)
    }

    private fun metrics() = FlowMetrics(
        cardWidth = JBUI.scale(250),
        gutter = JBUI.scale(18),
        dotRadius = JBUI.scale(5),
        gap = JBUI.scale(16),
        pillHeight = JBUI.scale(22),
        pillPadding = JBUI.scale(10),
        branchGap = JBUI.scale(28),
        framePadding = JBUI.scale(12),
        decisionHeight = JBUI.scale(34),
        decisionMaxWidth = JBUI.scale(300),
        bannerWidth = JBUI.scale(520),
        margin = JBUI.scale(28),
        emptyBranch = JBUI.scale(24),
        laneGap = JBUI.scale(80),
    )

    /** Selects the step of [node] (null clears) and reports it. */
    fun select(node: Int?) {
        selectedRegion = null
        onRegionSelect(null)
        val next = node?.takeIf { geometry?.cards?.containsKey(it) == true }
        if (next == selectedNode) return
        selectedNode = next
        onSelect(next)
        repaint()
    }

    /** Selects and scrolls to the step of [node]. Returns whether the function has a step on the chart. */
    fun selectNode(node: Int): Boolean {
        if (geometry?.cards?.containsKey(node) != true) return false
        select(node)
        reveal(node)
        return true
    }

    /** Moves the selection [delta] steps in reading order, from the first or last step when nothing is selected. */
    fun step(delta: Int): Int? {
        val cards = chart?.visibleCards?.takeIf { it.isNotEmpty() } ?: return null
        val current = cards.indexOfFirst { item -> when (item) {
            is FlowStep -> item.node == selectedNode
            is FlowRegion -> item.plan.evidence.key == selectedRegion
            else -> false
        } }
        val target = cards[if (current < 0) (if (delta > 0) 0 else cards.lastIndex) else (current + delta).coerceIn(cards.indices)]
        return when (target) {
            is FlowStep -> { selectNode(target.node); target.node }
            is FlowRegion -> { selectRegion(target.plan.evidence.key); null }
            else -> null
        }
    }

    fun reveal(node: Int) {
        val box = geometry?.cards?.get(node)?.bounds ?: return
        val area = Rectangle(box).apply { grow(JBUI.scale(40), JBUI.scale(40)) }
        scrollRectToVisible(Rectangle((area.x * zoom).toInt(), (area.y * zoom).toInt(), (area.width * zoom).toInt(), (area.height * zoom).toInt()))
    }

    fun zoomAt(value: Double, anchor: Point? = null) {
        val next = value.coerceIn(0.2, 2.0)
        if (next == scale) return
        val view = viewport
        val position = view?.viewPosition ?: Point()
        val point = anchor ?: Point(position.x + (view?.extentSize?.width ?: width) / 2, position.y + (view?.extentSize?.height ?: height) / 2)
        val old = scale
        scale = next
        revalidate()
        if (view != null) {
            view.viewSize = preferredSize
            moveViewport(Point((point.x * next / old - (point.x - position.x)).roundToInt(), (point.y * next / old - (point.y - position.y)).roundToInt()))
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

    override fun getPreferredSize(): Dimension =
        geometry?.size?.let { Dimension((it.width * zoom).toInt(), (it.height * zoom).toInt()) } ?: Dimension(0, 0)

    /** The interactive shape under a point in component coordinates. */
    private fun shapeAt(point: Point): FlowShape? {
        val x = (point.x / zoom).toInt()
        val y = (point.y / zoom).toInt()
        val reach = JBUI.scale(8)
        return geometry?.shapes?.lastOrNull { shape ->
            when (shape) {
                is FlowCardBox -> shape.bounds.contains(x, y) || shape.dot.distance(x.toDouble(), y.toDouble()) <= reach
                is FlowRegionBox -> shape.bounds.contains(x, y) || shape.dot.distance(x.toDouble(), y.toDouble()) <= reach
                is FlowPill -> (shape.toggle != null || shape.regionKey != null) && shape.bounds.contains(x, y)
                is FlowRefBox -> shape.bounds.contains(x, y) || shape.dot.distance(x.toDouble(), y.toDouble()) <= reach
                is FlowDecisionBox -> shape.bounds.contains(x, y)
                else -> false
            }
        }
    }

    private fun click(point: Point, clicks: Int) {
        when (val shape = shapeAt(point)) {
            is FlowCardBox -> {
                select(shape.step.node)
                if (clicks == 2) onOpen(shape.step.node)
            }
            is FlowRegionBox -> {
                selectRegion(shape.region.plan.evidence.key)
                toggleRegion(shape.region.plan.evidence.key)
                onRegionSelect(chart?.regions?.firstOrNull { it.plan.evidence.key == selectedRegion })
            }
            is FlowPill -> if (shape.regionKey != null) toggleRegion(shape.regionKey)
                else shape.toggle?.let { if (shape.callers) toggleCallers(it) else toggleCalls(it) }
            is FlowRefBox -> selectNode(shape.ref.node)
            else -> select(null)
        }
    }

    private fun showPopup(e: MouseEvent): Boolean {
        if (!e.isPopupTrigger || popup == null) return false
        requestFocusInWindow()
        val region = (shapeAt(e.point) as? FlowRegionBox)?.region
        if (region != null) {
            val menu = JPopupMenu()
            menu.add(JMenuItem(if (region.expanded) "Collapse subflow" else "Expand subflow").apply {
                addActionListener { toggleRegion(region.plan.evidence.key) }
            })
            menu.add(JMenuItem("Open owning function").apply { addActionListener { onOpen(region.plan.evidence.owner) } })
            region.plan.evidence.sites.forEach { site ->
                menu.add(JMenuItem("Open ${source?.card?.invoke(site.callee)?.title} · offset ${site.offset}").apply {
                    addActionListener { onOpen(site.callee) }
                })
            }
            menu.show(this, e.x, e.y)
            return true
        }
        val node = when (val shape = shapeAt(e.point)) {
            is FlowCardBox -> shape.step.node
            is FlowRefBox -> shape.ref.node
            else -> null
        }
        if (node != null) select(node)
        popup.invoke(node)?.show(this, e.x, e.y)
        return true
    }

    override fun getToolTipText(event: MouseEvent): String? = when (val shape = shapeAt(event.point)) {
        is FlowRegionBox -> {
            val evidence = shape.region.plan.evidence
            val text = listOf(shape.region.plan.title, shape.region.plan.description.orEmpty(),
                "Grounded in ${source?.card?.invoke(evidence.owner)?.title}; ordered call occurrences:") +
                evidence.sites.map { "${source?.card?.invoke(it.callee)?.title} · offset ${it.offset}" +
                    it.text?.replace(Regex("\\s+"), " ")?.take(180)?.let { code -> ": $code" }.orEmpty() }
            text.filter { it.isNotBlank() }.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
        }
        is FlowCardBox -> shape.step.card.let { card ->
            (listOf("<b>${StringUtil.escapeXmlEntities(card.title)}</b>") + listOfNotNull(card.meta, card.tooltip ?: card.description, card.details)
                .flatMap { it.lines() }.filter { it.isNotBlank() }.map { StringUtil.escapeXmlEntities(it) })
                .joinToString("<br>", "<html>", "</html>")
        }
        is FlowPill -> when {
            shape.regionKey != null -> "Collapse subflow"
            shape.tooltip != null -> "<html>Leaves the function: <code>${StringUtil.escapeXmlEntities(shape.tooltip)}</code></html>"
            shape.callers -> if (shape.toggle in board.openCallers) "Hide callers" else "Show direct callers"
            shape.toggle == null -> null
            shape.toggle in board.openCalls -> "Hide what this function calls"
            else -> "Show what this function calls"
        }
        is FlowRefBox -> "Shown as step ${shape.ref.number}: click to go there"
        is FlowDecisionBox -> StringUtil.escapeXmlEntities(shape.decision.question)
        else -> null
    }

    private class TextLine(val text: String, val font: Font, val color: Color)

    /** The card's lines at [width]: step number and meta, title, description and details, each wrapped and capped. */
    private fun cardLines(step: FlowStep, width: Int): List<TextLine> = buildList {
        val card = step.card
        listOfNotNull("Step ${step.number}".takeIf { step.number > 0 }, card.meta).joinToString(" · ").let { meta -> wrap(meta, getFontMetrics(metaFont), width, 2).forEach { add(TextLine(it, metaFont, accent)) } }
        wrap(card.title, getFontMetrics(titleFont), width, 2).forEach { add(TextLine(it, titleFont, JBColor.foreground())) }
        card.description?.let { text -> wrap(text, getFontMetrics(textFont), width, 3).forEach { add(TextLine(it, textFont, mutedText)) } }
        card.details?.let { text -> wrap(text, getFontMetrics(smallFont), width, 2).forEach { add(TextLine(it, smallFont, mutedText)) } }
    }

    private val measure = object : FlowMeasure {
        override fun card(step: FlowStep, width: Int) = cardLines(step, width).sumOf { getFontMetrics(it.font).height }
        override fun firstLine() = getFontMetrics(metaFont).height / 2
        override fun text(text: String) = getFontMetrics(smallFont).stringWidth(text)
        override fun line() = getFontMetrics(smallFont).height + JBUI.scale(4)
        override fun banner(lane: FlowLane, width: Int) = bannerLines(lane, width).sumOf { getFontMetrics(it.font).height } + JBUI.scale(4)
    }

    private fun bannerLines(lane: FlowLane, width: Int): List<TextLine> =
        wrap(lane.title, getFontMetrics(bannerFont), width, 2).map { TextLine(it, bannerFont, JBColor.foreground()) } +
            lane.description?.let { text -> wrap(text, getFontMetrics(metaFont), width, 4).map { TextLine(it, metaFont, mutedText) } }.orEmpty()

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val geometry = geometry
        if (geometry == null) {
            paintMessage(g)
            return
        }
        val canvas = g.create() as Graphics2D
        try {
            canvas.scale(zoom, zoom)
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val clip = canvas.clipBounds
            val reach = JBUI.scale(12)
            for (shape in geometry.shapes) {
                if (clip != null && !clip.intersects(Rectangle(bounds(shape)).apply { grow(reach, reach) })) continue
                when (shape) {
                    is FlowFrame -> paintFrame(canvas, shape)
                    is FlowSegment -> paintSegment(canvas, shape)
                    is FlowDot -> paintDot(canvas, shape.center, shape.step.card.color ?: accent, shape.step.node == selectedNode, hollow = false)
                    is FlowCardBox -> paintCard(canvas, shape)
                    is FlowRegionBox -> {
                        val presentation = shape.region.presentation()
                        paintDot(canvas, shape.dot, accent, shape.region.plan.evidence.key == selectedRegion, hollow = true)
                        paintCard(canvas, FlowCardBox(presentation, shape.bounds, shape.leftOfSpine, shape.dot),
                            selected = shape.region.plan.evidence.key == selectedRegion,
                            matching = shape.region.plan.evidence.key in regionMatches)
                    }
                    is FlowPill -> paintPill(canvas, shape)
                    is FlowDecisionBox -> paintDecision(canvas, shape)
                    is FlowRefBox -> paintRef(canvas, shape)
                    is FlowBanner -> paintBanner(canvas, shape)
                }
            }
        } finally { canvas.dispose() }
    }

    /** Bounds of [shape]; may be the shape's own rectangle, so callers must not change it. */
    private fun bounds(shape: FlowShape): Rectangle = when (shape) {
        is FlowFrame -> shape.bounds
        is FlowSegment -> Rectangle(shape.from).apply { add(shape.to) }
        is FlowDot -> Rectangle(shape.center)
        is FlowCardBox -> shape.bounds
        is FlowRegionBox -> shape.bounds
        is FlowPill -> shape.bounds
        is FlowDecisionBox -> shape.bounds
        is FlowRefBox -> Rectangle(shape.bounds).apply { add(shape.dot) }
        is FlowBanner -> shape.bounds
    }

    private fun paintMessage(g: Graphics) {
        val text = message ?: emptyText
        val canvas = g.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            canvas.font = metaFont
            canvas.color = mutedText
            val fm = canvas.fontMetrics
            val lines = wrap(text, fm, max(JBUI.scale(200), (viewport?.extentSize?.width ?: width) - JBUI.scale(40)), 4)
            var y = JBUI.scale(40)
            for (line in lines) {
                canvas.drawString(line, JBUI.scale(20), y)
                y += fm.height
            }
        } finally { canvas.dispose() }
    }

    private fun paintFrame(g: Graphics2D, frame: FlowFrame) {
        val arc = JBUI.scale(16).toDouble()
        val shape = RoundRectangle2D.Double(frame.bounds.x.toDouble(), frame.bounds.y.toDouble(), frame.bounds.width.toDouble(),
            frame.bounds.height.toDouble(), arc, arc)
        // Nested frames are drawn over each other, so deeper sub-flows get a deeper tint.
        g.color = ColorUtil.withAlpha(accent, 0.035)
        g.fill(shape)
        g.color = ColorUtil.withAlpha(accent, if (frame.loop) 0.45 else 0.22)
        g.stroke = if (frame.loop) dashed(1f) else BasicStroke(JBUI.scale(1).toFloat())
        g.draw(shape)
    }

    private fun dashed(width: Float) = BasicStroke(JBUIScale.scale(width), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
        floatArrayOf(JBUIScale.scale(6f), JBUIScale.scale(4f)), 0f)

    private fun paintSegment(g: Graphics2D, segment: FlowSegment) {
        when (segment.style) {
            FlowLineStyle.NORMAL -> { g.color = spineColor; g.stroke = BasicStroke(JBUIScale.scale(2f)) }
            FlowLineStyle.OTHERWISE -> { g.color = otherwiseColor; g.stroke = dashed(1.5f) }
            FlowLineStyle.ERROR -> { g.color = errorColor; g.stroke = dashed(1.5f) }
        }
        g.drawLine(segment.from.x, segment.from.y, segment.to.x, segment.to.y)
    }

    private fun paintDot(g: Graphics2D, center: Point, color: Color, selected: Boolean, hollow: Boolean) {
        val radius = JBUI.scale(if (selected) 7 else 5)
        val ring = radius + JBUI.scale(3)
        g.color = background
        g.fillOval(center.x - ring, center.y - ring, ring * 2, ring * 2)
        g.color = color
        if (hollow) {
            g.stroke = BasicStroke(JBUIScale.scale(1.5f))
            g.drawOval(center.x - radius, center.y - radius, radius * 2, radius * 2)
        } else g.fillOval(center.x - radius, center.y - radius, radius * 2, radius * 2)
    }

    private fun paintCard(g: Graphics2D, box: FlowCardBox, selected: Boolean = box.step.node == selectedNode,
        matching: Boolean = searchMatches?.contains(box.step.node) == true) {
        val bounds = box.bounds
        if (selected || matching) {
            val pad = JBUI.scale(4)
            val arc = JBUI.scale(10).toDouble()
            val shape = RoundRectangle2D.Double((bounds.x - pad).toDouble(), (bounds.y - pad).toDouble(), (bounds.width + pad * 2).toDouble(),
                (bounds.height + pad * 2).toDouble(), arc, arc)
            g.color = if (matching) searchFill else selectionFill
            g.fill(shape)
            g.color = ColorUtil.withAlpha(accent, 0.6)
            g.stroke = BasicStroke(JBUIScale.scale(1f))
            g.draw(shape)
        }
        var y = bounds.y
        for (line in cardLines(box.step, bounds.width)) {
            g.font = line.font
            g.color = line.color
            val fm = g.fontMetrics
            val x = if (box.leftOfSpine) bounds.x + bounds.width - fm.stringWidth(line.text) else bounds.x
            g.drawString(line.text, x, y + fm.ascent)
            y += fm.height
        }
    }

    private fun paintPill(g: Graphics2D, pill: FlowPill) {
        val r = pill.bounds
        val shape = RoundRectangle2D.Double(r.x.toDouble(), r.y.toDouble(), r.width.toDouble(), r.height.toDouble(), r.height.toDouble(), r.height.toDouble())
        val (fill, border, text) = when (pill.style) {
            FlowPillStyle.SUBFLOW, FlowPillStyle.LOOP -> Triple(pillFill, ColorUtil.withAlpha(accent, 0.45), accent)
            FlowPillStyle.CASE -> Triple(background, spineColor, JBColor.foreground())
            FlowPillStyle.OTHERWISE -> Triple(background, otherwiseColor, mutedText)
            FlowPillStyle.ERROR -> Triple(background, errorColor, errorColor)
            // Exits are terminals like End: filled, nothing below them.
            FlowPillStyle.END, FlowPillStyle.EXIT -> Triple(spineColor, spineColor, JBColor.foreground())
            FlowPillStyle.THROW -> Triple(ColorUtil.withAlpha(errorColor, 0.18), errorColor, errorColor)
        }
        g.color = fill
        g.fill(shape)
        g.color = border
        g.stroke = BasicStroke(JBUIScale.scale(1f))
        g.draw(shape)
        g.font = smallFont
        g.color = text
        val fm = g.fontMetrics
        val label = clipText(pill.text, fm, r.width - JBUI.scale(8))
        g.drawString(label, r.x + (r.width - fm.stringWidth(label)) / 2, r.y + (r.height - fm.height) / 2 + fm.ascent)
    }

    /** A hexagon, which holds a line of text better than a diamond. */
    private fun paintDecision(g: Graphics2D, box: FlowDecisionBox) {
        val r = box.bounds
        val tip = r.height / 2.0
        val shape = Path2D.Double().apply {
            moveTo(r.x.toDouble(), r.centerY)
            lineTo(r.x + tip, r.y.toDouble())
            lineTo(r.maxX - tip, r.y.toDouble())
            lineTo(r.maxX, r.centerY)
            lineTo(r.maxX - tip, r.maxY)
            lineTo(r.x + tip, r.maxY)
            closePath()
        }
        val error = box.decision.kind == DecisionKind.ERROR
        g.color = if (error) ColorUtil.withAlpha(errorColor, 0.12) else decisionFill
        g.fill(shape)
        g.color = if (error) errorColor else decisionBorder
        g.stroke = BasicStroke(JBUIScale.scale(1.5f))
        g.draw(shape)
        g.font = smallFont.deriveFont(Font.BOLD)
        g.color = JBColor.foreground()
        val fm = g.fontMetrics
        val text = clipText(box.decision.question, fm, (r.width - tip * 2).toInt())
        g.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2, r.y + (r.height - fm.height) / 2 + fm.ascent)
    }

    private fun paintRef(g: Graphics2D, box: FlowRefBox) {
        paintDot(g, box.dot, mutedText, selected = false, hollow = true)
        g.font = smallFont
        g.color = mutedText
        val fm = g.fontMetrics
        g.drawString("↺ step ${box.ref.number} · ${box.ref.title}", box.bounds.x, box.bounds.y + (box.bounds.height - fm.height) / 2 + fm.ascent)
    }

    private fun paintBanner(g: Graphics2D, banner: FlowBanner) {
        var y = banner.bounds.y
        for (line in bannerLines(banner.lane, banner.bounds.width)) {
            g.font = line.font
            g.color = line.color
            val fm = g.fontMetrics
            g.drawString(line.text, banner.bounds.x + (banner.bounds.width - fm.stringWidth(line.text)) / 2, y + fm.ascent)
            y += fm.height
        }
    }
}

/** Word-wrapped lines of [text] no wider than [width]; the last of [maxLines] ends with an ellipsis when text is left over. */
internal fun wrap(text: String, fm: FontMetrics, width: Int, maxLines: Int): List<String> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val lines = mutableListOf<String>()
    var current = ""
    var index = 0
    while (index < words.size && lines.size < maxLines) {
        val word = words[index]
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (fm.stringWidth(candidate) <= width) {
            current = candidate
            index++
        } else if (current.isEmpty()) {
            lines += clipText(word, fm, width)
            index++
        } else {
            lines += current
            current = ""
        }
    }
    if (current.isNotEmpty() && lines.size < maxLines) lines += current
    if (index < words.size && lines.isNotEmpty()) lines[lines.lastIndex] = clipText(lines.last() + " " + words.drop(index).joinToString(" "), fm, width)
    return lines
}

/** [text] shortened with an ellipsis to fit [width]. */
internal fun clipText(text: String, fm: FontMetrics, width: Int): String {
    if (fm.stringWidth(text) <= width) return text
    var end = text.length
    while (end > 0 && fm.stringWidth(text.substring(0, end).trimEnd() + "…") > width) end--
    return text.substring(0, end).trimEnd() + "…"
}
