package dev.insight.review.ui

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.LineChartMetric
import dev.insight.review.RadarSeries
import dev.insight.review.ReviewLineChart
import dev.insight.review.ReviewTreeBoard
import dev.insight.review.TreeBoardHighlight
import dev.insight.review.TreeBoardNode
import dev.insight.review.TreeBoardViewport
import dev.insight.review.TreeFileConnections
import dev.insight.ui.FloatingPanel
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class TreeConnectionSelection(
    val path: String,
    val connections: TreeFileConnections? = null,
    val message: String? = null,
)

/**
 * A pannable, zoomable whole-project tree board with foldable directories, a keyboard/mouse cursor, and branch
 * highlighting for the active and hovered paths.
 */
internal class TreeBoardPanel(
    private val chart: ReviewLineChart,
    private val rootLabel: String,
    private val open: (String) -> Unit,
    private val contextMenu: ((ReviewLineChart.Entry) -> JPopupMenu)? = null,
    collapsed: Set<String> = emptySet(),
    private val collapsedChanged: (Set<String>) -> Unit = {},
    cursorPath: String? = null,
    private val cursorChanged: (String?) -> Unit = {},
    initialZoom: Double = 1.0,
    initialPanX: Double = 0.0,
    initialPanY: Double = 0.0,
    private val viewportChanged: (Double, Double, Double) -> Unit = { _, _, _ -> },
    private val toggleConnections: ((String) -> Unit)? = null,
) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    private val metric = chart.metric
    private val numberColor: Color get() = metric.diffSeries?.let(RadarChartPanel::color) ?: UIUtil.getLabelForeground()
    private val accentColor: Color get() = metric.diffSeries?.let(RadarChartPanel::color) ?: JBUI.CurrentTheme.Focus.focusColor()

    private var collapsedPaths: Set<String> = collapsed
    var board: ReviewTreeBoard = ReviewTreeBoard(chart, collapsedPaths)
        private set
    private val viewport = TreeBoardViewport(initialPanX, initialPanY, initialZoom)
    private var cursorIndex: Int = board.indexOf(cursorPath).let {
        if (it >= 0) it else if (board.nodes.isNotEmpty()) 0 else -1
    }
    private var hoveredIndex: Int = -1

    var connectionSelection: TreeConnectionSelection? = null
        set(value) {
            field = value
            updateConnectionStatus()
            canvas.repaint()
        }

    val cursorPath: String? get() = board.nodes.getOrNull(cursorIndex)?.entry?.path
    val hoveredPath: String? get() = board.nodes.getOrNull(hoveredIndex)?.entry?.path
    val zoom: Double get() = viewport.zoom

    private val totalLabel = JLabel(totalSummaryText()).apply {
        name = "charts-tree-total"
        font = JBFont.small().asBold()
        foreground = numberColor
        toolTipText = metric.description
    }
    private val statusLabel = JLabel().apply {
        name = "charts-tree-status"
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }
    private val zoomLabel = JLabel(zoomText()).apply {
        name = "charts-tree-zoom-level"
        font = JBFont.small()
        toolTipText = "Reset zoom and pan (0)"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) resetView()
            }
        })
    }
    private val connectionStatus = JLabel().apply {
        name = "charts-tree-connections"
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }

    private val controls = FloatingPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        name = "charts-tree-controls"
        add(controlButton("Unfold all", "charts-tree-unfold-all", "Expand every directory") { unfoldAll() })
        add(controlButton("Fold all", "charts-tree-fold-all", "Collapse every subdirectory") { foldAll() })
        add(controlButton("Fit", "charts-tree-fit", "Fit the whole tree inside the board") { fitToView() })
        add(controlButton("−", "charts-tree-zoom-out", "Zoom out (− or Ctrl/⌘+wheel)") {
            zoomTo(viewport.zoom / TreeBoardViewport.ZOOM_STEP)
        })
        add(zoomLabel)
        add(controlButton("+", "charts-tree-zoom-in", "Zoom in (+ or Ctrl/⌘+wheel)") {
            zoomTo(viewport.zoom * TreeBoardViewport.ZOOM_STEP)
        })
    }

    internal val canvas = TreeBoardCanvas()

    private val boardLayer = object : JPanel(null) {
        init {
            isOpaque = false
            add(controls)
            add(canvas)
        }

        override fun doLayout() {
            canvas.setBounds(0, 0, width, height)
            val pad = JBUI.scale(10)
            val pref = controls.preferredSize
            controls.setBounds((width - pref.width - pad).coerceAtLeast(pad), pad, pref.width, pref.height)
        }
    }

    init {
        name = "charts-tree-board"
        isOpaque = false
        isFocusable = true
        getAccessibleContext().accessibleName = "${metric.label} tree board"

        val header = JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(2))).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
                isOpaque = false
                add(totalLabel)
                add(statusLabel)
            }, BorderLayout.CENTER)
            add(connectionStatus, BorderLayout.SOUTH)
        }
        add(header, BorderLayout.NORTH)
        add(boardLayer, BorderLayout.CENTER)

        updateStatus()
        updateConnectionStatus()
        installKeys()
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = canvas.repaint()
            override fun focusLost(e: FocusEvent) = canvas.repaint()
        })
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { requestFocusInWindow() }
        })
    }

    private fun controlButton(text: String, name: String, tooltip: String, action: () -> Unit) = JButton(text).apply {
        this.name = name
        isFocusable = false
        font = JBFont.small()
        margin = JBUI.insets(2, 6)
        toolTipText = tooltip
        addActionListener { action() }
    }

    fun toggleCollapsed(path: String) {
        val currentIdx = board.indexOf(path)
        val node = board.nodes.getOrNull(currentIdx) ?: return
        if (!node.collapsible) return
        val nextCursor = if (node.collapsed) cursorPath else board.cursorAfterCollapse(cursorPath, path)
        collapsedPaths = if (path in collapsedPaths) collapsedPaths - path else collapsedPaths + path
        rebuildBoard(nextCursor ?: path)
        collapsedChanged(collapsedPaths)
    }

    fun foldAll() {
        val all = board.allCollapsiblePaths()
        if (all == collapsedPaths) return
        val nextCursor = cursorPath?.let { path ->
            val topDir = all.firstOrNull { path == it || (it.isNotEmpty() && ReviewLineChart.isBeneath(path, it)) }
            topDir ?: path
        }
        collapsedPaths = all
        rebuildBoard(nextCursor)
        collapsedChanged(collapsedPaths)
    }

    fun unfoldAll() {
        if (collapsedPaths.isEmpty()) return
        val keep = cursorPath
        collapsedPaths = emptySet()
        rebuildBoard(keep)
        collapsedChanged(collapsedPaths)
    }

    fun moveCursor(index: Int, reveal: Boolean = true) {
        if (board.nodes.isEmpty()) return
        val next = index.coerceIn(0, board.nodes.lastIndex)
        if (next == cursorIndex) return
        cursorIndex = next
        if (reveal) {
            viewport.ensureRowVisible(rowTopWorld(next), rowBottomWorld(next), canvas.height, margin = JBUI.scale(24).toDouble())
            notifyViewportChanged()
        }
        updateStatus()
        canvas.repaint()
        cursorChanged(cursorPath)
    }

    fun fitToView() {
        val bounds = canvas.contentWorldBounds()
        viewport.fitToBounds(bounds.first, bounds.second, canvas.width, canvas.height, JBUI.scale(24).toDouble())
        zoomLabel.text = zoomText()
        notifyViewportChanged()
        canvas.repaint()
    }

    fun zoomTo(targetZoom: Double, anchor: Point? = null) {
        val sx = anchor?.x?.toDouble() ?: (canvas.width / 2.0)
        val sy = anchor?.y?.toDouble() ?: (canvas.height / 2.0)
        viewport.zoomAround(sx, sy, targetZoom)
        zoomLabel.text = zoomText()
        notifyViewportChanged()
        canvas.repaint()
    }

    fun resetView() {
        viewport.reset()
        zoomLabel.text = zoomText()
        notifyViewportChanged()
        canvas.repaint()
    }

    fun rowScreenBounds(path: String): Rectangle? {
        val node = board.nodes.getOrNull(board.indexOf(path)) ?: return null
        return canvas.rowScreenBounds(node)
    }

    fun toggleScreenBounds(path: String): Rectangle? {
        val node = board.nodes.getOrNull(board.indexOf(path)) ?: return null
        return canvas.toggleScreenBounds(node)
    }

    fun connectionButtonScreenBounds(path: String): Rectangle? =
        board.nodes.getOrNull(board.indexOf(path))?.let(canvas::connectionButtonBounds)

    private fun rebuildBoard(preferredCursorPath: String?) {
        val previousHover = hoveredPath
        board = ReviewTreeBoard(chart, collapsedPaths)
        cursorIndex = board.indexOf(preferredCursorPath).let {
            if (it >= 0) it else if (board.nodes.isNotEmpty()) 0 else -1
        }
        hoveredIndex = board.indexOf(previousHover)
        updateStatus()
        updateConnectionStatus()
        canvas.invalidateRowWidth()
        canvas.repaint()
        cursorChanged(cursorPath)
    }

    private fun totalSummaryText(): String {
        val root = chart.root
        val count = valueText(root.value)
        return if (metric == LineChartMetric.ITEMS) count else "$count · ${fileCount(root.files)}"
    }

    private fun updateStatus() {
        val node = board.nodes.getOrNull(hoveredIndex) ?: board.nodes.getOrNull(cursorIndex)
        if (node == null) {
            statusLabel.text = if (metric.hidesEmpty) "No ${metric.plural} here" else "No files in scope"
            return
        }
        val path = node.entry.path.ifEmpty { rootLabel }
        val files = if (node.entry.directory && metric != LineChartMetric.ITEMS) " · ${fileCount(node.entry.files)}" else ""
        statusLabel.text = "$path · ${valueText(node.entry.value)}$files"
        statusLabel.toolTipText = statusLabel.text
    }

    private fun updateConnectionStatus() {
        val selection = connectionSelection
        val links = selection?.connections
        connectionStatus.toolTipText = null
        connectionStatus.text = when {
            selection == null -> "Drag to pan · Ctrl/⌘+wheel to zoom · ↑↓ move, ←→ fold, Enter opens" +
                if (toggleConnections == null) "" else " · C shows code connections"
            selection.message != null -> "${selection.path} · ${selection.message}"
            links == null || (links.incoming.isEmpty() && links.outgoing.isEmpty()) ->
                "${selection.path} · No code connections within this review"
            else -> {
                val hidden = (links.incoming + links.outgoing).count { board.indexOf(it) < 0 }
                val hint = when {
                    board.indexOf(selection.path) < 0 -> " · Selected file is hidden"
                    hidden > 0 -> " · $hidden hidden by folds or the current tab"
                    else -> ""
                }
                connectionStatus.toolTipText = "Incoming links are dashed. Outgoing links are solid. Arrows point to the referenced file."
                "${selection.path} · ${links.incoming.size} incoming · ${links.outgoing.size} outgoing$hint"
            }
        }
    }

    private fun notifyViewportChanged() = viewportChanged(viewport.zoom, viewport.panX, viewport.panY)

    private fun zoomText() = "${(viewport.zoom * 100).roundToInt()}%"

    private fun installKeys() {
        fun bind(key: Int, id: String, action: () -> Unit) {
            val stroke = KeyStroke.getKeyStroke(key, 0)
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, id)
            actionMap.put(id, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = action()
            })
        }
        bind(KeyEvent.VK_UP, "charts-tree-up") { moveCursor(cursorIndex - 1) }
        bind(KeyEvent.VK_DOWN, "charts-tree-down") { moveCursor(cursorIndex + 1) }
        bind(KeyEvent.VK_HOME, "charts-tree-home") { moveCursor(0) }
        bind(KeyEvent.VK_END, "charts-tree-end") { moveCursor(board.nodes.lastIndex) }
        bind(KeyEvent.VK_PAGE_UP, "charts-tree-page-up") { moveCursor(cursorIndex - PAGE_ROWS) }
        bind(KeyEvent.VK_PAGE_DOWN, "charts-tree-page-down") { moveCursor(cursorIndex + PAGE_ROWS) }
        bind(KeyEvent.VK_LEFT, "charts-tree-left") {
            val node = board.nodes.getOrNull(cursorIndex) ?: return@bind
            if (node.collapsible && !node.collapsed) toggleCollapsed(node.entry.path)
            else node.parentIndex?.let { moveCursor(it) }
        }
        bind(KeyEvent.VK_RIGHT, "charts-tree-right") {
            val node = board.nodes.getOrNull(cursorIndex) ?: return@bind
            if (node.collapsible && node.collapsed) toggleCollapsed(node.entry.path)
            else board.firstChildIndex(cursorIndex)?.let { moveCursor(it) }
        }
        bind(KeyEvent.VK_SPACE, "charts-tree-toggle") {
            val node = board.nodes.getOrNull(cursorIndex) ?: return@bind
            if (node.collapsible) toggleCollapsed(node.entry.path)
        }
        bind(KeyEvent.VK_ENTER, "charts-tree-activate") {
            val node = board.nodes.getOrNull(cursorIndex) ?: return@bind
            if (!node.entry.directory) open(node.entry.path)
            else if (node.collapsible) toggleCollapsed(node.entry.path)
        }
        if (toggleConnections != null) bind(KeyEvent.VK_C, "charts-tree-connections") {
            val entry = board.nodes.getOrNull(cursorIndex)?.entry ?: return@bind
            if (entry.kind == ReviewLineChart.Kind.FILE) toggleConnections.invoke(entry.path)
        }
        bind(KeyEvent.VK_EQUALS, "charts-tree-zoom-eq") { zoomTo(viewport.zoom * TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_PLUS, "charts-tree-zoom-plus") { zoomTo(viewport.zoom * TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_ADD, "charts-tree-zoom-add") { zoomTo(viewport.zoom * TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_MINUS, "charts-tree-zoom-minus") { zoomTo(viewport.zoom / TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_SUBTRACT, "charts-tree-zoom-sub") { zoomTo(viewport.zoom / TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_0, "charts-tree-zoom-reset") { resetView() }
        bind(KeyEvent.VK_NUMPAD0, "charts-tree-zoom-reset-num") { resetView() }
    }

    private fun baseMonospaceFont(): Font {
        val editorFont = runCatching {
            EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        }.getOrNull()
        val base = editorFont ?: Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
        return base.deriveFont(JBFont.label().size2D * FONT_RATIO)
    }

    private fun rowTopWorld(index: Int): Double = worldPadY + index * rowHeight
    private fun rowBottomWorld(index: Int): Double = rowTopWorld(index) + rowHeight
    private fun rowCenterWorld(index: Int): Double = rowTopWorld(index) + rowHeight / 2.0
    private fun columnWorldX(col: Int): Double = worldPadX + col * indentStep + JBUI.scale(6).toDouble()
    private fun nodeContentStartWorldX(depth: Int): Double =
        if (depth == 0) worldPadX else columnWorldX(depth - 1) + connectorStub

    private val worldPadX get() = JBUI.scale(24).toDouble()
    private val worldPadY get() = JBUI.scale(20).toDouble()
    private val rowHeight get() = JBUI.scale(24).toDouble()
    private val indentStep get() = JBUI.scale(28).toDouble()
    private val connectorStub get() = JBUI.scale(16).toDouble()
    private val toggleWidth get() = JBUI.scale(16).toDouble()
    private val connectionButtonWidth get() = JBUI.scale(22)

    internal inner class TreeBoardCanvas : JComponent() {
        private var dragStart: Point? = null
        private var dragPanStart: Pair<Double, Double>? = null
        private var dragged = false
        private var rowWidthAtZoom: Pair<Double, Int>? = null

        init {
            name = "charts-tree-canvas"
            isOpaque = true
            toolTipText = ""

            val mouse = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val hit = nodeAt(e.x, e.y)
                    val nextHover = hit?.index ?: -1
                    if (nextHover != hoveredIndex) {
                        hoveredIndex = nextHover
                        updateStatus()
                        repaint()
                    }
                    cursor = if (hit != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else Cursor.getDefaultCursor()
                }

                override fun mouseExited(e: MouseEvent) {
                    if (hoveredIndex != -1) {
                        hoveredIndex = -1
                        updateStatus()
                        repaint()
                    }
                    cursor = Cursor.getDefaultCursor()
                }

                override fun mousePressed(e: MouseEvent) {
                    this@TreeBoardPanel.requestFocusInWindow()
                    if (showPopup(e)) return
                    if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                        dragStart = e.point
                        dragPanStart = viewport.panX to viewport.panY
                        dragged = false
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    val start = dragStart ?: return
                    val panStart = dragPanStart ?: return
                    val dx = e.x - start.x
                    val dy = e.y - start.y
                    val panTrigger = SwingUtilities.isMiddleMouseButton(e) || e.isAltDown || hypot(dx.toDouble(), dy.toDouble()) > PAN_THRESHOLD_PX
                    if (!dragged && panTrigger) {
                        dragged = true
                        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    }
                    if (dragged) {
                        viewport.panX = panStart.first + dx
                        viewport.panY = panStart.second + dy
                        notifyViewportChanged()
                        repaint()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    val wasDragged = dragged
                    dragStart = null
                    dragPanStart = null
                    cursor = if (nodeAt(e.x, e.y) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else Cursor.getDefaultCursor()
                    if (!wasDragged) showPopup(e)
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger || e.isAltDown || dragged) return
                    val node = nodeAt(e.x, e.y) ?: return
                    if (connectionButtonBounds(node)?.contains(e.point) == true) {
                        moveCursor(node.index, reveal = false)
                        if (e.clickCount == 1) toggleConnections?.invoke(node.entry.path)
                        return
                    }
                    if (node.collapsible && toggleScreenBounds(node).contains(e.point)) {
                        moveCursor(node.index, reveal = false)
                        toggleCollapsed(node.entry.path)
                        return
                    }
                    moveCursor(node.index, reveal = false)
                    if (e.clickCount >= 2) {
                        if (!node.entry.directory) open(node.entry.path)
                        else if (node.collapsible) toggleCollapsed(node.entry.path)
                    }
                }

                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    if (e.isControlDown || e.isMetaDown) {
                        val factor = ZOOM_WHEEL_BASE.pow(-e.preciseWheelRotation)
                        zoomTo(viewport.zoom * factor, e.point)
                    } else if (e.isShiftDown) {
                        viewport.panBy(-e.preciseWheelRotation * JBUI.scale(32), 0.0)
                        notifyViewportChanged()
                        repaint()
                    } else {
                        viewport.panBy(0.0, -e.preciseWheelRotation * JBUI.scale(32))
                        notifyViewportChanged()
                        repaint()
                    }
                }

                private fun showPopup(e: MouseEvent): Boolean {
                    if (!e.isPopupTrigger) return false
                    val node = nodeAt(e.x, e.y) ?: return false
                    moveCursor(node.index, reveal = false)
                    val menu = contextMenu?.invoke(node.entry)?.takeIf { it.componentCount > 0 } ?: return false
                    menu.show(this@TreeBoardCanvas, e.x, e.y)
                    return true
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            addMouseWheelListener(mouse)
        }

        private fun scaledFont(bold: Boolean): Font {
            val base = baseMonospaceFont()
            val derived = base.deriveFont((base.size2D * viewport.zoom).toFloat().coerceAtLeast(6f))
            return if (bold) derived.deriveFont(derived.style or Font.BOLD) else derived
        }

        private fun nodeLabel(node: TreeBoardNode): String = when {
            node.depth == 0 -> if (node.entry.path.isEmpty()) "$rootLabel/" else "$rootLabel (${node.entry.path})/"
            node.entry.directory -> "${node.entry.label}/"
            else -> node.entry.label
        }

        private fun nodeBadge(node: TreeBoardNode): String = if (node.collapsed) {
            "  (${fileCount(node.entry.files)} · ${valueText(node.entry.value)})"
        } else {
            "  ${valueText(node.entry.value)}"
        }

        private fun rowContentScreenWidth(node: TreeBoardNode, boldMetrics: FontMetrics, plainMetrics: FontMetrics): Int {
            val labelWidth = boldMetrics.stringWidth(nodeLabel(node))
            val badgeWidth = plainMetrics.stringWidth(nodeBadge(node))
            val toggleScreen = if (node.collapsible) (toggleWidth * viewport.zoom).roundToInt() else 0
            val padRight = (JBUI.scale(10) * viewport.zoom).roundToInt()
            return toggleScreen + labelWidth + badgeWidth + padRight + buttonScreenWidth(node)
        }

        private fun buttonScreenWidth(node: TreeBoardNode): Int =
            if (toggleConnections != null && node.entry.kind == ReviewLineChart.Kind.FILE) {
                (connectionButtonWidth * viewport.zoom).roundToInt()
            } else 0

        fun connectionButtonBounds(node: TreeBoardNode): Rectangle? {
            val width = buttonScreenWidth(node)
            if (width == 0) return null
            val row = rowScreenBounds(node)
            return Rectangle(row.x + row.width - width, row.y, width, row.height)
        }

        fun invalidateRowWidth() {
            rowWidthAtZoom = null
        }

        fun contentWorldBounds(): Pair<Double, Double> {
            if (board.nodes.isEmpty()) return 200.0 to 100.0
            val baseBold = baseMonospaceFont().let { it.deriveFont(it.style or Font.BOLD) }
            val basePlain = baseMonospaceFont()
            val boldMetrics = getFontMetrics(baseBold)
            val plainMetrics = getFontMetrics(basePlain)
            var maxRight = 0.0
            for (node in board.nodes) {
                val left = nodeContentStartWorldX(node.depth)
                val toggle = if (node.collapsible) toggleWidth else 0.0
                val textW = boldMetrics.stringWidth(nodeLabel(node)) + plainMetrics.stringWidth(nodeBadge(node))
                val button = if (buttonScreenWidth(node) > 0) connectionButtonWidth else 0
                maxRight = maxOf(maxRight, left + toggle + textW + JBUI.scale(24) + button)
            }
            val laneCount = visibleConnections().size
            if (laneCount > 0) maxRight += connectionLaneOffset(laneCount - 1)
            val totalHeight = worldPadY * 2 + board.nodes.size * rowHeight
            return (maxRight + worldPadX) to totalHeight
        }

        fun toggleScreenBounds(node: TreeBoardNode): Rectangle {
            val sx = viewport.worldToScreenX(nodeContentStartWorldX(node.depth)).roundToInt()
            val sy = viewport.worldToScreenY(rowTopWorld(node.index)).roundToInt()
            val sw = (toggleWidth * viewport.zoom).roundToInt().coerceAtLeast(8)
            val sh = (rowHeight * viewport.zoom).roundToInt().coerceAtLeast(8)
            return Rectangle(sx - 2, sy, sw + 4, sh)
        }

        fun rowScreenBounds(node: TreeBoardNode): Rectangle {
            val boldMetrics = getFontMetrics(scaledFont(bold = true))
            val plainMetrics = getFontMetrics(scaledFont(bold = false))
            val sx = viewport.worldToScreenX(nodeContentStartWorldX(node.depth)).roundToInt()
            val sy = viewport.worldToScreenY(rowTopWorld(node.index)).roundToInt()
            val sw = rowContentScreenWidth(node, boldMetrics, plainMetrics).coerceAtLeast(16)
            val sh = (rowHeight * viewport.zoom).roundToInt().coerceAtLeast(8)
            return Rectangle(sx, sy, sw, sh)
        }

        private fun nodeAt(sx: Int, sy: Int): TreeBoardNode? {
            if (board.nodes.isEmpty()) return null
            val wy = viewport.screenToWorldY(sy.toDouble())
            val index = floor((wy - worldPadY) / rowHeight).toInt()
            val node = board.nodes.getOrNull(index) ?: return null
            val bounds = rowScreenBounds(node)
            val padded = Rectangle(bounds.x - JBUI.scale(6), bounds.y, bounds.width + JBUI.scale(12), bounds.height)
            return if (padded.contains(sx, sy)) node else null
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val node = nodeAt(event.x, event.y) ?: return null
            if (connectionButtonBounds(node)?.contains(event.point) == true) {
                return if (connectionSelection?.path == node.entry.path) "Hide code connections (C)"
                else "Show incoming and outgoing code connections (C)"
            }
            val entry = node.entry
            val files = if (entry.directory && metric != LineChartMetric.ITEMS) " · ${fileCount(entry.files)}" else ""
            val action = when {
                node.collapsible -> if (node.collapsed) " · Click chevron or double-click to unfold" else " · Click chevron or double-click to fold"
                !entry.directory && metric.diffSeries == null -> " · Double-click to open file"
                !entry.directory -> " · Double-click to view diff"
                else -> ""
            }
            return "${entry.path.ifEmpty { rootLabel }} · ${valueText(entry.value)}$files$action"
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                UISettings.setupAntialiasing(g)

                g.color = UIUtil.getListBackground()
                g.fillRect(0, 0, width, height)

                paintGrid(g)

                if (board.nodes.isEmpty()) {
                    g.font = JBFont.label()
                    g.color = UIUtil.getContextHelpForeground()
                    val msg = if (metric.hidesEmpty) "No ${metric.plural} in scope" else "No files in scope"
                    g.drawString(msg, JBUI.scale(24), JBUI.scale(36))
                    return
                }

                val worldTop = viewport.screenToWorldY(0.0)
                val worldBottom = viewport.screenToWorldY(height.toDouble())
                val firstRow = floor((worldTop - worldPadY) / rowHeight).toInt().coerceAtLeast(0)
                val lastRow = ceil((worldBottom - worldPadY) / rowHeight).toInt().coerceAtMost(board.nodes.lastIndex)
                if (firstRow > lastRow) return

                val cursorHl = board.highlight(cursorIndex)
                val hoverHl = hoveredIndex.takeIf { it >= 0 && it != cursorIndex }?.let(board::highlight)

                val plainFont = scaledFont(bold = false)
                val boldFont = scaledFont(bold = true)
                val plainMetrics = g.getFontMetrics(plainFont)
                val boldMetrics = g.getFontMetrics(boldFont)

                val baseLineColor = JBColor(Color(0xB0B7C0), Color(0x525964))
                val primaryAccent = accentColor
                val hoverAccent = alpha(primaryAccent, 195)
                val normalStroke = BasicStroke((1.0f * viewport.zoom.toFloat()).coerceAtLeast(1.0f))
                val highlightStroke = BasicStroke((2.0f * viewport.zoom.toFloat()).coerceAtLeast(1.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val panelFocused = this@TreeBoardPanel.isFocusOwner

                for (i in firstRow..lastRow) {
                    val node = board.nodes[i]
                    val topY = viewport.worldToScreenY(rowTopWorld(i))
                    val bottomY = viewport.worldToScreenY(rowBottomWorld(i))
                    val centerY = viewport.worldToScreenY(rowCenterWorld(i))
                    val startX = viewport.worldToScreenX(nodeContentStartWorldX(node.depth))

                    // Row highlight pill
                    if (i == cursorIndex || i == hoveredIndex) {
                        val rowW = rowContentScreenWidth(node, boldMetrics, plainMetrics).toDouble()
                        val padX = 4.0 * viewport.zoom
                        val padY = 1.5 * viewport.zoom
                        val arc = 6.0 * viewport.zoom
                        val pill = RoundRectangle2D.Double(startX - padX, topY + padY, rowW + padX * 2, (bottomY - topY) - padY * 2, arc, arc)
                        g.color = if (i == cursorIndex) JBUI.CurrentTheme.List.Selection.background(panelFocused)
                        else JBUI.CurrentTheme.List.Hover.background(true)
                        g.fill(pill)
                        if (i == cursorIndex) {
                            g.color = primaryAccent
                            g.stroke = BasicStroke((1.2f * viewport.zoom.toFloat()).coerceAtLeast(1.0f))
                            g.draw(pill)
                        }
                    }

                    // Ancestor vertical trunks
                    for (col in 0 until (node.depth - 1).coerceAtLeast(0)) {
                        if (!node.ancestorHasNextSibling[col]) continue
                        val x = viewport.worldToScreenX(columnWorldX(col)).toInt()
                        val lineColor = when {
                            cursorHl?.verticalLineHighlighted(node, col, board.nodes) == true -> primaryAccent
                            hoverHl?.verticalLineHighlighted(node, col, board.nodes) == true -> hoverAccent
                            else -> null
                        }
                        g.color = lineColor ?: baseLineColor
                        g.stroke = if (lineColor != null) highlightStroke else normalStroke
                        g.drawLine(x, topY.toInt(), x, bottomY.toInt())
                    }

                    // Own elbow / tee connector
                    if (node.depth > 0) {
                        val col = node.depth - 1
                        val x = viewport.worldToScreenX(columnWorldX(col)).toInt()
                        val midY = centerY.toInt()
                        val rightX = (startX - 3.0 * viewport.zoom).toInt().coerceAtLeast(x + 2)

                        val topColor = when {
                            cursorHl?.let { it.connectorHighlighted(node) || it.verticalLineHighlighted(node, col, board.nodes) } == true -> primaryAccent
                            hoverHl?.let { it.connectorHighlighted(node) || it.verticalLineHighlighted(node, col, board.nodes) } == true -> hoverAccent
                            else -> null
                        }
                        g.color = topColor ?: baseLineColor
                        g.stroke = if (topColor != null) highlightStroke else normalStroke
                        g.drawLine(x, topY.toInt(), x, midY)

                        if (!node.isLastChild) {
                            val bottomColor = when {
                                cursorHl?.verticalLineHighlighted(node, col, board.nodes) == true -> primaryAccent
                                hoverHl?.verticalLineHighlighted(node, col, board.nodes) == true -> hoverAccent
                                else -> null
                            }
                            g.color = bottomColor ?: baseLineColor
                            g.stroke = if (bottomColor != null) highlightStroke else normalStroke
                            g.drawLine(x, midY, x, bottomY.toInt())
                        }

                        val horizColor = when {
                            cursorHl?.connectorHighlighted(node) == true -> primaryAccent
                            hoverHl?.connectorHighlighted(node) == true -> hoverAccent
                            else -> null
                        }
                        g.color = horizColor ?: baseLineColor
                        g.stroke = if (horizColor != null) highlightStroke else normalStroke
                        g.drawLine(x, midY, rightX, midY)
                    }

                    // Fold chevron
                    var textX = startX.toFloat()
                    if (node.collapsible) {
                        val chevronColor = if (cursorHl?.contains(i) == true || hoverHl?.contains(i) == true) primaryAccent else UIUtil.getContextHelpForeground()
                        drawChevron(g, startX, centerY, node.collapsed, chevronColor)
                        textX += (toggleWidth * viewport.zoom).toFloat()
                    }

                    // Label & metric badge
                    val isBranchActive = cursorHl?.contains(i) == true || hoverHl?.contains(i) == true
                    val useBold = i == cursorIndex || i == hoveredIndex || (node.entry.directory && isBranchActive)
                    val labelFont = if (useBold) boldFont else plainFont
                    val lm = if (useBold) boldMetrics else plainMetrics
                    val baselineY = (centerY + (lm.ascent - lm.descent) / 2.0).toFloat()

                    val labelText = nodeLabel(node)
                    g.font = labelFont
                    g.color = when {
                        i == cursorIndex || i == hoveredIndex -> UIUtil.getLabelForeground()
                        isBranchActive && node.entry.directory -> UIUtil.getLabelForeground()
                        node.entry.directory -> UIUtil.getLabelForeground()
                        else -> UIUtil.getLabelForeground()
                    }
                    g.drawString(labelText, textX, baselineY)

                    val badgeX = textX + lm.stringWidth(labelText)
                    g.font = plainFont
                    g.color = if (metric.diffSeries != null) numberColor else UIUtil.getContextHelpForeground()
                    g.drawString(nodeBadge(node), badgeX, baselineY)
                    val buttonWidth = buttonScreenWidth(node)
                    if (buttonWidth > 0) {
                        val rowWidth = rowContentScreenWidth(node, boldMetrics, plainMetrics)
                        paintConnectionButton(g, startX + rowWidth - buttonWidth / 2.0, centerY, node.entry.path)
                    }
                }
                paintConnections(g, boldMetrics, plainMetrics)
            } finally {
                g.dispose()
            }
        }

        private fun paintConnectionButton(g: Graphics2D, x: Double, y: Double, path: String) {
            val active = connectionSelection?.path == path
            val radius = (JBUI.scale(5) * viewport.zoom).coerceAtLeast(2.0)
            val dot = (JBUI.scale(3) * viewport.zoom).roundToInt().coerceAtLeast(2)
            val left = (x - radius).roundToInt()
            val right = (x + radius).roundToInt()
            val upper = (y - radius).roundToInt()
            val lower = (y + radius).roundToInt()
            val middle = y.roundToInt()
            g.color = if (active) outgoingColor else UIUtil.getContextHelpForeground()
            g.stroke = BasicStroke(viewport.zoom.toFloat().coerceAtLeast(1f))
            g.drawLine(left, middle, right, upper)
            g.drawLine(left, middle, right, lower)
            g.fillOval(left - dot / 2, middle - dot / 2, dot, dot)
            g.fillOval(right - dot / 2, upper - dot / 2, dot, dot)
            g.fillOval(right - dot / 2, lower - dot / 2, dot, dot)
        }

        private fun visibleConnections(): List<Pair<Int, Int>> {
            val selection = connectionSelection ?: return emptyList()
            val links = selection.connections ?: return emptyList()
            val selected = board.indexOf(selection.path)
            if (selected < 0) return emptyList()
            return links.incoming.mapNotNull { path -> board.indexOf(path).takeIf { it >= 0 }?.let { it to selected } } +
                links.outgoing.mapNotNull { path -> board.indexOf(path).takeIf { it >= 0 }?.let { selected to it } }
        }

        private fun connectionLaneOffset(index: Int) = JBUI.scale(24) + index * JBUI.scale(12)

        private fun paintConnections(g: Graphics2D, bold: FontMetrics, plain: FontMetrics) {
            val links = visibleConnections().sortedBy { (from, to) -> kotlin.math.abs(from - to) }
            if (links.isEmpty()) return
            val selected = board.indexOf(connectionSelection?.path)
            fun rowRight(node: TreeBoardNode) = (nodeContentStartWorldX(node.depth) * viewport.zoom).roundToInt() +
                rowContentScreenWidth(node, bold, plain)
            val right = rowWidthAtZoom?.takeIf { it.first == viewport.zoom }?.second
                ?: board.nodes.maxOf(::rowRight).also { rowWidthAtZoom = viewport.zoom to it }
            val strokeWidth = (1.5 * viewport.zoom).toFloat().coerceAtLeast(1f)
            val solid = BasicStroke(strokeWidth)
            val dashed = BasicStroke(strokeWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f,
                floatArrayOf(strokeWidth * 3, strokeWidth * 2), 0f)
            val arrow = (JBUI.scale(6) * viewport.zoom).coerceAtLeast(3.0)
            for ((index, link) in links.withIndex()) {
                val (from, to) = link
                val outgoing = from == selected
                val offset = JBUI.scale(4) * viewport.zoom * if (outgoing) 1 else -1
                val y1 = viewport.worldToScreenY(rowCenterWorld(from)) + offset
                val y2 = viewport.worldToScreenY(rowCenterWorld(to)) + offset
                if (maxOf(y1, y2) < 0 || minOf(y1, y2) > height) continue
                val x1 = rowRight(board.nodes[from]) + viewport.panX
                val x2 = rowRight(board.nodes[to]) + viewport.panX
                val bend = right + viewport.panX + connectionLaneOffset(index) * viewport.zoom
                g.color = if (outgoing) outgoingColor else incomingColor
                g.stroke = if (outgoing) solid else dashed
                g.draw(Path2D.Double().apply {
                    moveTo(x1, y1)
                    lineTo(bend, y1)
                    lineTo(bend, y2)
                    lineTo(x2, y2)
                })
                g.stroke = solid
                g.drawLine(x2.roundToInt(), y2.roundToInt(), (x2 + arrow).roundToInt(), (y2 - arrow / 2).roundToInt())
                g.drawLine(x2.roundToInt(), y2.roundToInt(), (x2 + arrow).roundToInt(), (y2 + arrow / 2).roundToInt())
            }
        }

        private fun paintGrid(g: Graphics2D) {
            val stepWorld = rowHeight
            val stepScreen = stepWorld * viewport.zoom
            if (stepScreen < 8.0) return
            val dotColor = alpha(JBColor.border(), 95)
            g.color = dotColor
            val startWX = floor(viewport.screenToWorldX(0.0) / stepWorld) * stepWorld
            val endWX = viewport.screenToWorldX(width.toDouble())
            val startWY = floor(viewport.screenToWorldY(0.0) / stepWorld) * stepWorld
            val endWY = viewport.screenToWorldY(height.toDouble())
            val dotSize = (1.5 * viewport.zoom).roundToInt().coerceIn(1, 3)
            var wx = startWX
            while (wx <= endWX) {
                val sx = viewport.worldToScreenX(wx).roundToInt()
                var wy = startWY
                while (wy <= endWY) {
                    val sy = viewport.worldToScreenY(wy).roundToInt()
                    g.fillRect(sx, sy, dotSize, dotSize)
                    wy += stepWorld
                }
                wx += stepWorld
            }
        }

        private fun drawChevron(g: Graphics2D, startX: Double, centerY: Double, collapsed: Boolean, color: Color) {
            val cx = startX + (toggleWidth * viewport.zoom) * 0.42
            val cy = centerY
            val r = (3.6 * viewport.zoom).coerceAtLeast(2.5)
            val poly = if (collapsed) {
                Polygon(
                    intArrayOf((cx - r * 0.5).roundToInt(), (cx + r * 0.8).roundToInt(), (cx - r * 0.5).roundToInt()),
                    intArrayOf((cy - r).roundToInt(), cy.roundToInt(), (cy + r).roundToInt()),
                    3,
                )
            } else {
                Polygon(
                    intArrayOf((cx - r).roundToInt(), (cx + r).roundToInt(), cx.roundToInt()),
                    intArrayOf((cy - r * 0.5).roundToInt(), (cy - r * 0.5).roundToInt(), (cy + r * 0.8).roundToInt()),
                    3,
                )
            }
            g.color = color
            g.fillPolygon(poly)
        }
    }

    private fun alpha(color: Color, alpha: Int) = Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))
    private fun number(value: Long) = String.format(Locale.US, "%,d", value)
    private fun valueText(value: Long) = "${number(value)} ${metric.unitText(value)}"
    private fun fileCount(files: Int) = "${number(files.toLong())} ${if (files == 1) "file" else "files"}"

    private companion object {
        const val FONT_RATIO = 1.05f
        const val PAGE_ROWS = 12
        const val PAN_THRESHOLD_PX = 4.0
        const val ZOOM_WHEEL_BASE = 1.15
        val outgoingColor = JBColor(Color(0x087D76), Color(0x65C9BD))
        val incomingColor = JBColor(Color(0x7050AA), Color(0xBD9BFD))
    }
}
