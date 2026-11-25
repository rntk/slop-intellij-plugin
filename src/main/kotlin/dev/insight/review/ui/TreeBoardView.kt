package dev.insight.review.ui

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.LineChartMetric
import dev.insight.review.LineLinkMatch
import dev.insight.review.RadarSeries
import dev.insight.review.ReportTargetKind
import dev.insight.review.ReviewLineChart
import dev.insight.review.ReviewTreeBoard
import dev.insight.review.SummaryLinkSpan
import dev.insight.review.TreeBoardHighlight
import dev.insight.review.TreeBoardGroupHighlight
import dev.insight.review.TreeBoardNode
import dev.insight.review.TreeBoardViewport
import dev.insight.review.TreeBrief
import dev.insight.review.TreeBriefs
import dev.insight.review.TreeConnectionSite
import dev.insight.review.TreeFileConnections
import dev.insight.review.TreeFocusLinks
import dev.insight.review.TreeRailCard
import dev.insight.review.TreeRailMode
import dev.insight.review.TreeRailModel
import dev.insight.review.TreeRailPlacement
import dev.insight.review.computeTreeRailPlacements
import dev.insight.review.floatingCardTop
import dev.insight.review.treeCanvasCardScale
import dev.insight.review.WrappedSummaryLine
import dev.insight.review.lineLinkSpans
import dev.insight.review.linkTooltip
import dev.insight.review.wrapSummaryText
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
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
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

internal data class TreeFocusSelection(
    val path: String,
    val directory: Boolean,
    val links: TreeFocusLinks? = null,
    val message: String? = null,
)

/** One brief beside a Tree row: a [prefix] in the link or accent [color], then the readable [text]. */
internal data class BriefSegment(val prefix: String, val color: Color, val text: String, val ready: Boolean)

private data class SummaryText(val path: String, val text: String)

private data class ConnectionRoute(
    val from: String, val to: String, val outgoing: Boolean,
    val x1: Double, val y1: Double, val bend: Double, val x2: Double, val y2: Double,
) {
    fun distanceSq(point: Point): Double {
        val x = point.x.toDouble()
        val y = point.y.toDouble()
        return minOf(
            Line2D.ptSegDistSq(x1, y1, bend, y1, x, y),
            Line2D.ptSegDistSq(bend, y1, bend, y2, x, y),
            Line2D.ptSegDistSq(bend, y2, x2, y2, x, y),
        )
    }
}

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
    initialPanX: Double? = null,
    initialPanY: Double = 0.0,
    private val viewportChanged: (Double, Double, Double) -> Unit = { _, _, _ -> },
    private val toggleConnections: ((String) -> Unit)? = null,
    private val openConnection: ((String, String, TreeConnectionSite, RelativePoint) -> Unit)? = null,
    private val openAt: ((String, Int) -> Unit)? = null,
    private val project: Project? = null,
    explainConnection: (suspend (String, String, TreeConnectionSite) -> String)? = null,
    initialFocusSelection: TreeFocusSelection? = null,
    private val toggleFocus: ((String, Boolean) -> Unit)? = null,
    private val clearFocus: (() -> Unit)? = null,
    /** Briefs a file and each of its links beside the Tree rows, or hides them; null hides the control. */
    private val toggleBriefs: ((String) -> Unit)? = null,
    private val fileSummaries: Map<String, String> = emptyMap(),
    expandedSummaries: Set<String> = emptySet(),
    private val expandedSummariesChanged: (Set<String>, Boolean) -> Unit = { _, _ -> },
    private val diffMode: Boolean = false,
    private val summaryLinkNavigator: ((String) -> ContextualLinkedTextNavigator)? = null,
    private val summaryFileMenuItems: ((String, IntRange?) -> List<JMenuItem>)? = null,
    private val nodeSummaries: Map<String, String> = fileSummaries,
    private val railModel: TreeRailModel? = null,
    allowGroupingRail: Boolean = false,
    private val railMode: TreeRailMode = TreeRailMode.TOPICS,
    railModeChanged: (TreeRailMode) -> Unit = {},
    railLevel: Int? = null,
    railLevelChanged: (Int?) -> Unit = {},
    initialSelectedRailKey: String? = null,
    private val selectedRailKeyChanged: (String?) -> Unit = {},
    initialRailVisible: Boolean = true,
    private val railVisibleChanged: (Boolean) -> Unit = {},
    showEmptyRail: Boolean = false,
    summaryLinkContext: ((String?, List<String>) -> SummaryTextLinkContext?)? = null,
) : JPanel(BorderLayout()) {
    private val metric = chart.metric
    private val numberColor: Color get() = metric.diffSeries?.let(RadarChartPanel::color) ?: UIUtil.getLabelForeground()
    private val accentColor: Color get() = metric.diffSeries?.let(RadarChartPanel::color) ?: JBUI.CurrentTheme.Focus.focusColor()

    private var collapsedPaths: Set<String> = collapsed
    private var focusCollapsedPaths: Set<String> = emptySet()
    private var expandedSummaryPaths: Set<String> = expandedSummaries.intersect(fileSummaries.keys)
    private val resolvedSummaryLinks = HashMap<SummaryText, PlainTextLinks>()
    private val summaryLinkJobs = HashMap<SummaryText, Job>()
    private var listeningSummaryLinks = false
    private val summaryLinksChanged: () -> Unit = { loadSummaryLinks() }
    private var savedViewport: Triple<Double, Double, Double>? = null
    private val isFocused: Boolean get() = focusSelection?.links != null
    private val activeCollapsedPaths: Set<String> get() = if (isFocused) focusCollapsedPaths else collapsedPaths
    private val visibleFocusFiles: Set<String>? get() = focusSelection?.links?.visibleFiles

    private val viewport = TreeBoardViewport(initialPanX ?: 0.0, initialPanY, initialZoom)
    private var cursorIndex: Int = -1
    private var hoveredIndex: Int = -1
    private var hoveredRailCard: TreeRailCard? = null
    private var selectedRailKey: String? = initialSelectedRailKey?.takeIf { railModel?.cardByKey(it) != null }
    private var railVisible: Boolean = initialRailVisible
    private var groupHighlight: TreeBoardGroupHighlight? = null

    val isRailVisible: Boolean get() = railVisible && rail != null
    val activeRailCard: TreeRailCard? get() = if (isRailVisible) hoveredRailCard ?: railModel?.cardByKey(selectedRailKey) else null
    val selectedRailCardKey: String? get() = selectedRailKey

    /** What the floating summary is anchored to: a tree row or a Topic/Group card. */
    private sealed interface SummaryAnchor {
        data class Row(val index: Int) : SummaryAnchor
        data class Card(val key: String) : SummaryAnchor
    }

    private var summaryAnchor: SummaryAnchor? = null

    /** The floating summary card, placed on the canvas to the left of the tree; null when nothing can be summarized. */
    internal val summaryPanel: TreeBoardSummaryPanel? =
        if (nodeSummaries.isNotEmpty() || railModel?.cards?.isNotEmpty() == true) {
            TreeBoardSummaryPanel(project, summaryLinkContext) { e ->
                canvas.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, canvas))
            }
        } else null

    /** The Topic or Group cards, painted on the canvas to the right of the tree. */
    internal val rail: TreeBoardRail? =
        if (railModel != null && (railModel.cards.isNotEmpty() || showEmptyRail)) {
            TreeBoardRail(
                model = railModel,
                metricsFor = { font -> canvas.getFontMetrics(font) },
                startX = { viewport.worldToScreenX(railStartWorldX()) },
                scale = { treeCanvasCardScale(viewport.zoom) },
                rowScreenY = { row -> viewport.worldToScreenY(rowTopWorld(row)) to viewport.worldToScreenY(rowBottomWorld(row)) },
            )
        } else null

    /** Whether the floating summary has anything to show. */
    private val summaryEnabled: Boolean
        get() = summaryPanel != null && (nodeSummaries.isNotEmpty() || (isRailVisible && railModel?.cards?.isNotEmpty() == true))

    private val railModeSelector: JComboBox<String>? = if (rail != null && allowGroupingRail) {
        JComboBox(arrayOf(TreeRailMode.TOPICS.title, TreeRailMode.GROUPING.title)).apply {
            name = "charts-tree-rail-mode"
            font = JBFont.small()
            isFocusable = false
            selectedIndex = if (railMode == TreeRailMode.GROUPING) 1 else 0
            toolTipText = "Switch between code Topics and change Groups"
            addActionListener {
                val chosen = if (selectedIndex == 1) TreeRailMode.GROUPING else TreeRailMode.TOPICS
                if (chosen != railMode) railModeChanged(chosen)
            }
        }
    } else null

    private val railLevelSelector: JComboBox<String>? = railModel?.takeIf { rail != null && it.depth > 1 }?.let { model ->
        val options = (0 until model.depth - 1).map { "Level ${it + 1}" } + "All levels"
        JComboBox(options.toTypedArray()).apply {
            name = "charts-tree-rail-level"
            font = JBFont.small()
            isFocusable = false
            selectedIndex = if (railLevel == null || railLevel >= model.depth - 1) options.lastIndex else railLevel.coerceIn(0, model.depth - 2)
            toolTipText = "Show ${model.mode.plural} up to the selected depth"
            addActionListener {
                val nextLevel = if (selectedIndex >= model.depth - 1) null else selectedIndex
                if (nextLevel != railLevel) railLevelChanged(nextLevel)
            }
        }
    }

    private val totalLabel = JLabel(totalSummaryText()).apply {
        name = "charts-tree-total"
        font = JBFont.small().asBold()
        foreground = numberColor
        toolTipText = "${metric.description} · Drag to pan · Ctrl/⌘+wheel to zoom · ↑↓ move, ←→ fold, Enter opens" +
            (if (toggleConnections == null) "" else " · C shows code connections") +
            (if (toggleFocus == null) "" else " · F focuses on related files") +
            (if (toggleBriefs == null) "" else " · S summarizes a file and its links") +
            (if (fileSummaries.isEmpty()) "" else " · D toggles ${if (diffMode) "diff " else ""}summary")
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
    private val focusStatus = JLabel().apply {
        name = "charts-tree-focus-status"
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }

    private val exitFocusButton = controlButton("Show all", "charts-tree-exit-focus", "Exit focus mode and show the full tree (Esc)") {
        clearFocus?.invoke() ?: run { focusSelection = null }
    }.apply {
        isVisible = initialFocusSelection != null
    }

    private val summariesButton = controlButton("", "charts-tree-toggle-summaries", "") {
        toggleAllSummaries()
    }

    private val railToggleButton = controlButton("", "charts-tree-toggle-rail", "") {
        toggleRailVisible()
    }

    val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
        name = "charts-tree-controls"
        isOpaque = false
        add(exitFocusButton)
        add(summariesButton)
        add(railToggleButton)
        railModeSelector?.let(::add)
        railLevelSelector?.let(::add)
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

    private val details = object : JPanel(CardLayout()) {
        // Paths and connection messages may be much wider than the toolbar.
        override fun getPreferredSize() = Dimension(0, super.getPreferredSize().height)
    }.apply {
        isOpaque = false
        add(statusLabel, statusLabel.name)
        add(connectionStatus, connectionStatus.name)
        add(focusStatus, focusStatus.name)
    }

    val status = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(totalLabel, BorderLayout.WEST)
        add(details, BorderLayout.CENTER)
    }

    internal val canvas = TreeBoardCanvas()
    internal val connectionPreview = TreeConnectionPreviewController(canvas, openAt, openConnection, project, explainConnection)

    var focusSelection: TreeFocusSelection? = initialFocusSelection
        set(value) {
            val wasFocused = field?.links != null
            val nowFocused = value?.links != null
            val targetChanged = field?.path != value?.path || field?.links != value?.links
            field = value
            if (!wasFocused && nowFocused) {
                savedViewport = Triple(viewport.zoom, viewport.panX, viewport.panY)
            }
            if (nowFocused && targetChanged) {
                focusCollapsedPaths = emptySet()
                rebuildBoard(value?.path ?: cursorPath)
                resetViewport()
                if (canvas.height > 0 && cursorIndex >= 0) {
                    viewport.ensureRowVisible(rowTopWorld(cursorIndex), rowBottomWorld(cursorIndex), canvas.height, margin = JBUI.scale(24).toDouble())
                }
                zoomLabel.text = zoomText()
                notifyViewportChanged()
            } else if (wasFocused && !nowFocused) {
                rebuildBoard(cursorPath)
                savedViewport?.let { (z, px, py) ->
                    savedViewport = null
                    viewport.reset()
                    viewport.zoomAround(0.0, 0.0, z)
                    viewport.panX = px
                    viewport.panY = py
                }
                zoomLabel.text = zoomText()
                notifyViewportChanged()
            }
            exitFocusButton.isVisible = value != null
            updateFocusStatus()
            canvas.invalidateRoutes()
            canvas.repaint()
        }

    var board: ReviewTreeBoard = ReviewTreeBoard(
        chart,
        if (initialFocusSelection?.links != null) emptySet() else collapsedPaths,
        initialFocusSelection?.links?.visibleFiles,
    )
        private set

    var connectionSelection: TreeConnectionSelection? = null
        set(value) {
            field = value
            connectionPreview.hide()
            // Link lanes widen the tree, which moves the cards beyond them.
            canvas.invalidateRowWidth()
            updateConnectionStatus()
            positionSummary()
            canvas.repaint()
        }

    var briefs: TreeBriefs? = null
        set(value) {
            field = value
            canvas.invalidateRowWidth()
            loadSummaryLinks()
            updateConnectionStatus()
            canvas.repaint()
        }

    val cursorPath: String? get() = board.nodes.getOrNull(cursorIndex)?.entry?.path
    val hoveredPath: String? get() = board.nodes.getOrNull(hoveredIndex)?.entry?.path
    val zoom: Double get() = viewport.zoom
    val shownSummaryPaths: Set<String> get() = expandedSummaryPaths

    private fun allSummariesShown(): Boolean =
        fileSummaries.isNotEmpty() && fileSummaries.keys.all { it in expandedSummaryPaths }

    private fun updateSummariesButton() {
        summariesButton.isVisible = fileSummaries.isNotEmpty()
        if (allSummariesShown()) {
            summariesButton.text = "Hide summaries"
            summariesButton.toolTipText = if (diffMode) "Hide all diff summaries" else "Hide all file summaries"
        } else {
            summariesButton.text = "Show summaries"
            summariesButton.toolTipText = if (diffMode) "Show diff summaries for all summarized files" else "Show summaries for all summarized files"
        }
    }

    fun toggleSummary(path: String) {
        if (path !in fileSummaries) return
        expandedSummaryPaths = if (path in expandedSummaryPaths) expandedSummaryPaths - path else expandedSummaryPaths + path
        canvas.invalidateRowLayout()
        updateSummariesButton()
        expandedSummariesChanged(expandedSummaryPaths, allSummariesShown())
        loadSummaryLinks()
        canvas.repaint()
    }

    fun toggleAllSummaries() {
        if (fileSummaries.isEmpty()) return
        expandedSummaryPaths = if (allSummariesShown()) emptySet() else fileSummaries.keys.toSet()
        canvas.invalidateRowLayout()
        updateSummariesButton()
        expandedSummariesChanged(expandedSummaryPaths, allSummariesShown())
        loadSummaryLinks()
        canvas.repaint()
    }

    internal fun summaryLinksFor(path: String): List<LineLinkMatch> = fileSummaries[path]?.let { resolvedSummaryLinks[SummaryText(path, it)]?.matches }.orEmpty()

    internal fun summaryLinkScreenBounds(path: String, match: LineLinkMatch): Rectangle? =
        board.nodes.getOrNull(board.indexOf(path))?.let { canvas.summaryLinkScreenBounds(it, match) }

    private fun listenSummaryLinks(on: Boolean) {
        val ownerProject = project?.takeUnless { it.isDisposed } ?: return
        if (on == listeningSummaryLinks) return
        listeningSummaryLinks = on
        val links = ownerProject.service<LinkedTextLinks>()
        if (on) links.addListener(summaryLinksChanged) else links.removeListener(summaryLinksChanged)
    }

    private fun loadSummaryLinks() {
        val ownerProject = project?.takeUnless { it.isDisposed } ?: return
        val texts = buildSet {
            for (path in expandedSummaryPaths) {
                fileSummaries[path]?.let { add(SummaryText(path, it)) }
            }
            briefs?.let { current ->
                for (path in setOf(current.path) + current.incoming.keys + current.outgoing.keys) {
                    for (segment in canvas.briefSegments(path)) {
                        if (segment.ready) add(SummaryText(path, segment.text))
                    }
                }
            }
        }
        resolvedSummaryLinks.keys.retainAll(texts)
        for (key in summaryLinkJobs.keys - texts) summaryLinkJobs.remove(key)?.cancel()
        if (texts.isEmpty()) return
        val links = ownerProject.service<LinkedTextLinks>()
        for (key in texts) {
            val (path, text) = key
            val navigator = summaryLinkNavigator?.invoke(path)
            val defaultFile = navigator?.defaultFile() ?: path
            val extras = navigator?.extraFiles().orEmpty()
            val cached = links.cachedPlain(text, defaultFile, extras)
            if (cached != null) {
                if (resolvedSummaryLinks[key] !== cached) {
                    resolvedSummaryLinks[key] = cached
                    canvas.repaint()
                }
                continue
            }
            if (summaryLinkJobs[key]?.isActive == true) continue
            summaryLinkJobs[key] = links.coroutineScope.launch(Dispatchers.EDT) {
                while (true) {
                    val next = try {
                        links.findPlain(text, defaultFile, extras)
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        continue
                    }
                    if (resolvedSummaryLinks[key] !== next) {
                        resolvedSummaryLinks[key] = next
                        canvas.repaint()
                    }
                    break
                }
            }
        }
    }

    private fun summaryFollower(key: SummaryText): LinkedTextFollower? {
        val ownerProject = project?.takeUnless { it.isDisposed } ?: return null
        val links = ownerProject.service<LinkedTextLinks>()
        return LinkedTextFollower(ownerProject, links.coroutineScope, canvas,
            { summaryLinkNavigator?.invoke(key.path) }, { resolvedSummaryLinks[key]?.index })
    }

    fun hideConnectionPreview() = connectionPreview.hide()

    override fun addNotify() {
        super.addNotify()
        listenSummaryLinks(true)
        loadSummaryLinks()
    }

    override fun removeNotify() {
        listenSummaryLinks(false)
        summaryLinkJobs.values.forEach { it.cancel() }
        summaryLinkJobs.clear()
        connectionPreview.dispose()
        super.removeNotify()
    }

    init {
        cursorIndex = board.indexOf(cursorPath).let {
            if (it >= 0) it else if (board.nodes.isNotEmpty()) 0 else -1
        }
        name = "charts-tree-board"
        isOpaque = false
        isFocusable = true
        getAccessibleContext().accessibleName = "${metric.label} tree board"

        add(canvas, BorderLayout.CENTER)
        summaryPanel?.let(canvas::add)
        // A fresh board leaves room for the summary to the left of the tree.
        if (initialPanX == null) viewport.panX = homePanX()

        updateStatus()
        updateConnectionStatus()
        updateFocusStatus()
        updateSummariesButton()
        updateRailToggleButton()
        updateGroupHighlight()
        syncRailPlacements()
        updateSidePanels()
        loadSummaryLinks()
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
        val current = activeCollapsedPaths
        val next = if (path in current) current - path else current + path
        if (isFocused) {
            focusCollapsedPaths = next
            rebuildBoard(nextCursor ?: path)
        } else {
            collapsedPaths = next
            rebuildBoard(nextCursor ?: path)
            collapsedChanged(collapsedPaths)
        }
    }

    fun foldAll() {
        val all = board.allCollapsiblePaths()
        if (all == activeCollapsedPaths) return
        val nextCursor = cursorPath?.let { path ->
            val topDir = all.firstOrNull { path == it || (it.isNotEmpty() && ReviewLineChart.isBeneath(path, it)) }
            topDir ?: path
        }
        if (isFocused) {
            focusCollapsedPaths = all
            rebuildBoard(nextCursor)
        } else {
            collapsedPaths = all
            rebuildBoard(nextCursor)
            collapsedChanged(collapsedPaths)
        }
    }

    fun unfoldAll() {
        if (activeCollapsedPaths.isEmpty()) return
        val keep = cursorPath
        if (isFocused) {
            focusCollapsedPaths = emptySet()
            rebuildBoard(keep)
        } else {
            collapsedPaths = emptySet()
            rebuildBoard(keep)
            collapsedChanged(collapsedPaths)
        }
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
        updateSidePanels()
        canvas.repaint()
        cursorChanged(cursorPath)
    }

    fun fitToView() {
        val (_, contentHeight) = canvas.contentWorldBounds()
        // The summary and cards keep their screen size when zooming out, so refine the fit a few times.
        repeat(3) {
            val scale = treeCanvasCardScale(viewport.zoom)
            val left = summaryLaneScreenWidth(scale)
            val railWidth = rail?.takeIf { isRailVisible }?.let { it.baseWidth * scale / viewport.zoom } ?: 0.0
            val right = if (railWidth > 0.0) railStartWorldX() + railWidth else canvas.treeRightWorldX()
            viewport.fitToBounds(right + worldPadX, contentHeight, (canvas.width - left.roundToInt()).coerceAtLeast(1), canvas.height, JBUI.scale(24).toDouble())
            viewport.panX += left
        }
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
        resetViewport()
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

    fun focusButtonScreenBounds(path: String): Rectangle? =
        board.nodes.getOrNull(board.indexOf(path))?.let(canvas::focusButtonBounds)

    fun briefButtonScreenBounds(path: String): Rectangle? =
        board.nodes.getOrNull(board.indexOf(path))?.let(canvas::briefButtonBounds)

    fun summaryButtonScreenBounds(path: String): Rectangle? =
        board.nodes.getOrNull(board.indexOf(path))?.let(canvas::summaryButtonBounds)

    fun summaryBlockScreenBounds(path: String): Rectangle? =
        board.nodes.getOrNull(board.indexOf(path))?.let(canvas::summaryBlockScreenBounds)

    private fun rebuildBoard(preferredCursorPath: String?) {
        val previousHover = hoveredPath
        board = ReviewTreeBoard(chart, activeCollapsedPaths, visibleFocusFiles)
        cursorIndex = board.indexOf(preferredCursorPath).let {
            if (it >= 0) it else if (board.nodes.isNotEmpty()) 0 else -1
        }
        hoveredIndex = board.indexOf(previousHover)
        updateStatus()
        updateConnectionStatus()
        updateFocusStatus()
        updateGroupHighlight()
        canvas.invalidateRowLayout()
        syncRailPlacements()
        updateSidePanels()
        canvas.repaint()
        cursorChanged(cursorPath)
    }

    private fun hoverRailCard(card: TreeRailCard?) {
        if (hoveredRailCard == card) return
        hoveredRailCard = card
        updateGroupHighlight()
        updateSidePanels()
        canvas.repaint()
    }

    private fun toggleRailCardSelection(card: TreeRailCard) {
        selectedRailKey = if (selectedRailKey == card.key) null else card.key
        selectedRailKeyChanged(selectedRailKey)
        updateGroupHighlight()
        updateSidePanels()
        if (selectedRailKey != null && canvas.height > 0) {
            val targets = (groupHighlight?.matchingRows.orEmpty() + groupHighlight?.collapsedContainers.orEmpty()).sorted()
            val anyVisible = targets.any { idx ->
                val top = viewport.worldToScreenY(rowTopWorld(idx))
                val bottom = viewport.worldToScreenY(rowBottomWorld(idx))
                bottom > 0.0 && top < canvas.height
            }
            if (!anyVisible && targets.isNotEmpty()) {
                val first = targets.first()
                viewport.ensureRowVisible(rowTopWorld(first), rowBottomWorld(first), canvas.height, margin = JBUI.scale(24).toDouble())
                notifyViewportChanged()
            }
        }
        canvas.repaint()
    }

    /** Screen width the floating summary and its gap take to the left of the tree at card [scale]. */
    private fun summaryLaneScreenWidth(scale: Double): Double =
        if (summaryEnabled) (JBUI.scale(TreeBoardSummaryPanel.DEFAULT_WIDTH) + 2 * JBUI.scale(TreeBoardSummaryPanel.GAP)) * scale else 0.0

    /** Horizontal pan of a reset board: the tree starts after room for the floating summary. */
    private fun homePanX(): Double = summaryLaneScreenWidth(1.0)

    private fun resetViewport() {
        viewport.reset()
        viewport.panX = homePanX()
    }

    /** World x of the innermost card column: past the tree's widest row, with a gap that keeps its screen size. */
    private fun railStartWorldX(): Double =
        canvas.treeRightWorldX() + JBUI.scale(TreeBoardRail.TREE_GAP) * treeCanvasCardScale(viewport.zoom) / viewport.zoom

    /** Screen width of the board's summary and card lanes at zoom 1, for callers sizing the board. */
    val sideLanesPreferredWidth: Int
        get() = summaryLaneScreenWidth(1.0).roundToInt() +
            (rail?.takeIf { isRailVisible }?.let { it.baseWidth + JBUI.scale(TreeBoardRail.TREE_GAP) } ?: 0)

    /** Places the floating summary beside the tree, next to what it describes, or hides it. */
    internal fun positionSummary() {
        val panel = summaryPanel ?: return
        val anchor = summaryAnchorScreenY()
        if (!summaryEnabled || panel.display == null || anchor == null || canvas.width <= 0 || canvas.height <= 0) {
            panel.isVisible = false
            return
        }
        val scale = treeCanvasCardScale(viewport.zoom)
        panel.show(panel.display, scale)
        val width = (JBUI.scale(TreeBoardSummaryPanel.DEFAULT_WIDTH) * scale).roundToInt()
        val margin = JBUI.scale(TreeBoardSummaryPanel.MARGIN).toDouble()
        val height = panel.preferredHeightFor(width).coerceAtMost((canvas.height - 2 * margin).roundToInt().coerceAtLeast(JBUI.scale(60)))
        val top = floatingCardTop(anchor.first, anchor.second, height.toDouble(), canvas.height.toDouble(), margin)
        if (top == null) {
            panel.isVisible = false
            return
        }
        val right = viewport.worldToScreenX(0.0) - JBUI.scale(TreeBoardSummaryPanel.GAP) * scale
        val bounds = Rectangle((right - width).roundToInt(), top.roundToInt(), width, height)
        if (panel.bounds != bounds) {
            panel.bounds = bounds
            panel.revalidate()
        }
        panel.isVisible = true
        panel.repaint()
    }

    private fun summaryAnchorScreenY(): Pair<Double, Double>? = when (val anchor = summaryAnchor) {
        null -> null
        is SummaryAnchor.Row -> anchor.index.takeIf { it in board.nodes.indices }?.let {
            viewport.worldToScreenY(rowTopWorld(it)) to viewport.worldToScreenY(rowHeaderBottomWorld(it))
        }
        is SummaryAnchor.Card -> rail?.anchorBounds(anchor.key, canvas.height)?.let {
            it.y.toDouble() to (it.y + it.height).toDouble()
        }
    }

    private fun updateRailToggleButton() {
        railToggleButton.isVisible = rail != null
        railModeSelector?.isVisible = railVisible
        railLevelSelector?.isVisible = railVisible
        if (railVisible) {
            railToggleButton.text = "Hide ${railMode.plural}"
            railToggleButton.toolTipText = "Hide the ${railMode.plural} rail"
        } else {
            railToggleButton.text = "Show ${railMode.plural}"
            railToggleButton.toolTipText = "Show the ${railMode.plural} rail"
        }
    }

    fun toggleRailVisible() {
        if (rail == null) return
        railVisible = !railVisible
        if (!railVisible) hoveredRailCard = null
        railVisibleChanged(railVisible)
        updateRailToggleButton()
        updateGroupHighlight()
        // The tree keeps its place on the canvas; only the cards and family dots appear or disappear.
        canvas.invalidateRowWidth()
        updateSidePanels()
        canvas.repaint()
    }

    private fun syncRailPlacements() {
        val model = railModel ?: return
        val placements = computeTreeRailPlacements(model, board) { dir -> chart.files(dir).map { it.path } }
        rail?.setPlacements(placements)
    }

    private fun updateGroupHighlight() {
        val active = activeRailCard
        groupHighlight = if (active == null) null else board.highlightFiles(active.files) { dir ->
            chart.files(dir).map { it.path }
        }
    }

    private fun updateSidePanels() {
        val hoveredNode = board.nodes.getOrNull(hoveredIndex)
        val cursorNode = board.nodes.getOrNull(cursorIndex)
        val selectedCard = if (isRailVisible) railModel?.cardByKey(selectedRailKey) else null
        val hoveredCard = hoveredRailCard?.takeIf { isRailVisible }
        val (display, anchor) = when {
            hoveredCard != null -> railSummaryDisplay(hoveredCard) to SummaryAnchor.Card(hoveredCard.key)
            hoveredNode != null -> nodeSummaryDisplay(hoveredNode) to SummaryAnchor.Row(hoveredNode.index)
            selectedCard != null -> railSummaryDisplay(selectedCard) to SummaryAnchor.Card(selectedCard.key)
            cursorNode != null -> nodeSummaryDisplay(cursorNode) to SummaryAnchor.Row(cursorNode.index)
            else -> null to null
        }
        summaryAnchor = anchor
        summaryPanel?.show(display)
        if (isRailVisible) {
            rail?.updateHighlights(hoveredRailCard?.key, selectedRailKey, matchingRailKeys(hoveredNode ?: cursorNode))
        }
        positionSummary()
    }

    private fun matchingRailKeys(node: TreeBoardNode?): Set<String> {
        val model = railModel ?: return emptySet()
        if (node == null) return emptySet()
        return when {
            node.entry.kind == ReviewLineChart.Kind.FILE -> model.matchingCardKeys(node.entry.path)
            node.entry.directory -> model.matchingCardKeysForFiles(chart.files(node.entry.path).map { it.path })
            else -> emptySet()
        }
    }

    private fun railSummaryDisplay(card: TreeRailCard): TreeSummaryDisplay {
        val mode = railModel?.mode ?: railMode
        val kindLabel = if (mode == TreeRailMode.GROUPING) "Group summary" else "Topic summary"
        val countText = fileCount(card.files.size)
        val subtitle = if (card.fullPath != card.name) "${card.fullPath} · $countText" else countText
        val missingText = if (card.placeholder) card.fullPath else "No ${mode.singular} summary yet"
        return TreeSummaryDisplay(
            kindLabel = kindLabel,
            title = card.name,
            subtitle = subtitle,
            summary = card.summary,
            missingText = missingText,
            accent = card.family?.let(DocumentColumnsPanel::accent),
            linkSourcePath = card.files.singleOrNull(),
            linkEntryFiles = card.files.toList(),
        )
    }

    private fun nodeSummaryDisplay(node: TreeBoardNode): TreeSummaryDisplay {
        val path = node.entry.path
        val summary = nodeSummaries[path]
        if (node.entry.kind == ReviewLineChart.Kind.FILE) {
            val label = railModel?.fileLabels?.get(path)
            val prefix = if (railModel?.mode == TreeRailMode.GROUPING) "Group: " else "Topic: "
            val family = railModel?.fileFamilies?.get(path)
            return TreeSummaryDisplay(
                kindLabel = if (diffMode) "Diff summary" else "File summary",
                title = node.entry.label,
                subtitle = path.takeIf { it != node.entry.label },
                topicBadge = label?.let { "$prefix$it" },
                summary = summary,
                accent = family?.let(DocumentColumnsPanel::accent),
                linkSourcePath = path,
                linkEntryFiles = listOf(path),
            )
        }
        val filesText = fileCount(node.entry.files)
        val dirFiles = chart.files(path).map { it.path }
        return TreeSummaryDisplay(
            kindLabel = if (node.depth == 0) "Scope summary" else "Folder summary",
            title = if (node.depth == 0) rootLabel else "${node.entry.label}/",
            subtitle = if (node.depth == 0 || path == node.entry.label) filesText else "$path · $filesText",
            summary = summary,
            linkSourcePath = null,
            linkEntryFiles = dirFiles,
        )
    }

    private fun totalSummaryText(): String {
        val root = chart.root
        val count = valueText(root.value)
        return if (metric == LineChartMetric.ITEMS) count else "$count · ${fileCount(root.files)}"
    }

    private fun updateStatus() {
        val node = board.nodes.getOrNull(hoveredIndex) ?: board.nodes.getOrNull(cursorIndex)
        statusLabel.text = if (node == null) {
            if (metric.hidesEmpty) "No ${metric.plural} here" else "No files in scope"
        } else {
            val path = node.entry.path.ifEmpty { rootLabel }
            val files = if (node.entry.directory && metric != LineChartMetric.ITEMS) " · ${fileCount(node.entry.files)}" else ""
            "$path · ${valueText(node.entry.value)}$files"
        }
        statusLabel.toolTipText = statusLabel.text
    }

    private fun updateConnectionStatus() {
        val selection = connectionSelection
        val links = selection?.connections
        connectionStatus.text = when {
            selection == null -> ""
            selection.message != null -> "${selection.message} · ${selection.path}"
            links == null || (links.incoming.isEmpty() && links.outgoing.isEmpty()) ->
                "No code connections within this review · ${selection.path}"
            else -> {
                val hidden = (links.incoming.keys + links.outgoing.keys).count { board.indexOf(it) < 0 }
                val hint = when {
                    board.indexOf(selection.path) < 0 -> " · Selected file is hidden"
                    hidden > 0 -> " · $hidden hidden by folds or the current tab"
                    else -> ""
                }
                "${links.incoming.size} incoming · ${links.outgoing.size} outgoing$hint${briefStatus(selection.path)} · ${selection.path}"
            }
        }
        connectionStatus.toolTipText = when {
            selection == null -> null
            links == null || (links.incoming.isEmpty() && links.outgoing.isEmpty()) -> connectionStatus.text
            else -> "${connectionStatus.text} · Incoming links are dashed. Outgoing links are solid. Arrows point to the referenced file."
        }
        updateDetailsCard()
    }

    private fun briefStatus(path: String): String {
        val current = briefs?.takeIf { it.path == path } ?: return ""
        val failed = if (current.failed > 0) " · ${current.failed} unavailable" else ""
        return if (current.running) " · Summarizing ${current.done} of ${current.total}…$failed" else " · Summarized$failed"
    }

    private fun updateFocusStatus() {
        val selection = focusSelection
        val links = selection?.links
        focusStatus.text = when {
            selection == null -> ""
            selection.message != null -> "${selection.message} · ${selection.path}"
            links == null || links.linkedFiles.isEmpty() -> "Focused: ${selection.path} · No linked files within this review"
            else -> {
                val hiddenByTab = links.visibleFiles.count { path ->
                    board.indexOf(path) < 0 && activeCollapsedPaths.none { collapsed ->
                        path == collapsed || (collapsed.isNotEmpty() && ReviewLineChart.isBeneath(path, collapsed))
                    }
                }
                val hiddenHint = if (hiddenByTab > 0) " · $hiddenByTab hidden by current tab" else ""
                "Focused: ${selection.path} · ${links.incoming.size} incoming · ${links.outgoing.size} outgoing$hiddenHint"
            }
        }
        focusStatus.toolTipText = focusStatus.text.takeIf { it.isNotEmpty() }
        updateDetailsCard()
    }

    private fun updateDetailsCard() {
        val name = when {
            connectionSelection != null -> connectionStatus.name
            focusSelection != null -> focusStatus.name
            else -> statusLabel.name
        }
        (details.layout as CardLayout).show(details, name)
    }

    private fun notifyViewportChanged() {
        connectionPreview.hide()
        canvas.invalidateRoutes()
        viewportChanged(viewport.zoom, viewport.panX, viewport.panY)
        positionSummary()
    }

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
        if (toggleFocus != null) bind(KeyEvent.VK_F, "charts-tree-focus") {
            val node = board.nodes.getOrNull(cursorIndex) ?: return@bind
            if (node.depth > 0 && (node.entry.kind == ReviewLineChart.Kind.FILE || node.entry.kind == ReviewLineChart.Kind.DIRECTORY)) {
                toggleFocus.invoke(node.entry.path, node.entry.directory)
            }
        }
        if (toggleBriefs != null) bind(KeyEvent.VK_S, "charts-tree-briefs") {
            val entry = board.nodes.getOrNull(cursorIndex)?.entry ?: return@bind
            if (entry.kind == ReviewLineChart.Kind.FILE) toggleBriefs.invoke(entry.path)
        }
        if (fileSummaries.isNotEmpty()) bind(KeyEvent.VK_D, "charts-tree-summary") {
            val entry = board.nodes.getOrNull(cursorIndex)?.entry ?: return@bind
            if (entry.kind == ReviewLineChart.Kind.FILE && entry.path in fileSummaries) toggleSummary(entry.path)
        }
        bind(KeyEvent.VK_EQUALS, "charts-tree-zoom-eq") { zoomTo(viewport.zoom * TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_PLUS, "charts-tree-zoom-plus") { zoomTo(viewport.zoom * TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_ADD, "charts-tree-zoom-add") { zoomTo(viewport.zoom * TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_MINUS, "charts-tree-zoom-minus") { zoomTo(viewport.zoom / TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_SUBTRACT, "charts-tree-zoom-sub") { zoomTo(viewport.zoom / TreeBoardViewport.ZOOM_STEP) }
        bind(KeyEvent.VK_0, "charts-tree-zoom-reset") { resetView() }
        bind(KeyEvent.VK_NUMPAD0, "charts-tree-zoom-reset-num") { resetView() }
        bind(KeyEvent.VK_ESCAPE, "charts-tree-dismiss-preview") {
            if (connectionPreview.activePanel != null) connectionPreview.hide()
            else if (focusSelection != null) clearFocus?.invoke() ?: run { focusSelection = null }
            else if (selectedRailKey != null) {
                selectedRailKey = null
                selectedRailKeyChanged(null)
                updateGroupHighlight()
                updateSidePanels()
                canvas.repaint()
            }
        }
    }

    private fun baseMonospaceFont(): Font {
        val editorFont = runCatching {
            EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        }.getOrNull()
        val base = editorFont ?: Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().size)
        return base.deriveFont(JBFont.label().size2D * FONT_RATIO)
    }

    private fun baseSummaryFont(): Font = JBFont.small().let { it.deriveFont(it.size2D * SUMMARY_FONT_SCALE) }

    private fun rowTopWorld(index: Int): Double = canvas.rowLayout().tops.getOrElse(index) { worldPadY + index * rowHeight }
    private fun rowHeaderBottomWorld(index: Int): Double = rowTopWorld(index) + rowHeight
    private fun rowBottomWorld(index: Int): Double =
        rowTopWorld(index) + canvas.rowLayout().heights.getOrElse(index) { rowHeight }
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
    private val focusButtonWidth get() = JBUI.scale(22)
    private val connectionButtonWidth get() = JBUI.scale(22)
    private val briefButtonWidth get() = JBUI.scale(22)
    private val summaryButtonWidth get() = JBUI.scale(22)
    private val briefGap get() = JBUI.scale(12)
    private val summaryMaxWorldWidth get() = JBUI.scale(520).toDouble()
    private val summaryPadX get() = JBUI.scale(8).toDouble()
    private val summaryPadY get() = JBUI.scale(5).toDouble()
    private val summaryTopGap get() = JBUI.scale(2).toDouble()
    private val summaryBottomGap get() = JBUI.scale(6).toDouble()
    private val summaryIndent get() = JBUI.scale(4).toDouble()
    private val familyDotSpace get() = JBUI.scale(10)

    internal data class WrappedSummaryBlock(
        val lines: List<WrappedSummaryLine>,
        val lineHeight: Double,
        val blockWidth: Double,
        val blockHeight: Double,
    )

    private data class SummaryLinkHit(val text: SummaryText, val match: LineLinkMatch)

    private data class SummaryBlockGeometry(
        val boxX: Double,
        val boxY: Double,
        val boxW: Double,
        val boxH: Double,
        val padX: Double,
        val padY: Double,
        val barW: Double,
        val stepY: Double,
        val textX: Float,
        val firstBaseline: Double,
        val metrics: FontMetrics,
    )

    internal class RowLayout(
        val tops: DoubleArray,
        val heights: DoubleArray,
        val totalHeight: Double,
        val summaries: Map<Int, WrappedSummaryBlock>,
    )

    internal inner class TreeBoardCanvas : JComponent() {
        private var dragStart: Point? = null
        private var dragPanStart: Pair<Double, Double>? = null
        private var dragged = false
        private var rowWidthAtZoom: Pair<Double, Int>? = null
        private var cachedRowLayout: RowLayout? = null

        private var routes: List<ConnectionRoute>? = null
        private var hoveredRoute: ConnectionRoute? = null
        private var hoveredSummaryLink: SummaryLinkHit? = null

        fun invalidateRoutes() {
            routes = null
            hoveredRoute = null
        }

        fun invalidateRowLayout() {
            cachedRowLayout = null
            invalidateRowWidth()
        }

        fun rowLayout(): RowLayout {
            cachedRowLayout?.let { return it }
            val count = board.nodes.size
            val tops = DoubleArray(count)
            val heights = DoubleArray(count)
            val summaries = LinkedHashMap<Int, WrappedSummaryBlock>()
            val metrics = getFontMetrics(baseSummaryFont())
            val lineHeight = metrics.height.coerceAtLeast(1).toDouble()
            val wrapWidth = (summaryMaxWorldWidth - summaryPadX * 2).roundToInt().coerceAtLeast(80)
            var y = worldPadY
            for (i in 0 until count) {
                val node = board.nodes[i]
                tops[i] = y
                val summaryText = if (node.entry.kind == ReviewLineChart.Kind.FILE && node.entry.path in expandedSummaryPaths) {
                    fileSummaries[node.entry.path]
                } else null
                val wrapped = summaryText?.let { wrapSummaryText(it, wrapWidth, metrics::stringWidth) }.orEmpty()
                val height = if (wrapped.isNotEmpty()) {
                    val maxLineWidth = wrapped.maxOf { metrics.stringWidth(it.text) }.toDouble()
                    val blockWidth = maxLineWidth + summaryPadX * 2
                    val blockHeight = wrapped.size * lineHeight + summaryPadY * 2
                    summaries[i] = WrappedSummaryBlock(wrapped, lineHeight, blockWidth, blockHeight)
                    rowHeight + summaryTopGap + blockHeight + summaryBottomGap
                } else {
                    rowHeight
                }
                heights[i] = height
                y += height
            }
            return RowLayout(tops, heights, y + worldPadY, summaries).also { cachedRowLayout = it }
        }

        private fun rowIndexAtWorldY(wy: Double): Int {
            val layout = rowLayout()
            val count = layout.tops.size
            if (count == 0 || wy < layout.tops[0]) return -1
            var lo = 0
            var hi = count - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (layout.tops[mid] <= wy) lo = mid + 1 else hi = mid - 1
            }
            val index = hi
            if (index < 0 || index >= count) return -1
            return if (wy < layout.tops[index] + layout.heights[index]) index else -1
        }

        private fun visibleRowRange(worldTop: Double, worldBottom: Double): IntRange {
            val layout = rowLayout()
            val count = layout.tops.size
            if (count == 0) return IntRange.EMPTY
            var lo = 0
            var hi = count - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (layout.tops[mid] + layout.heights[mid] <= worldTop) lo = mid + 1 else hi = mid
            }
            val first = lo.coerceIn(0, count - 1)
            lo = first
            hi = count - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (layout.tops[mid] <= worldBottom) lo = mid + 1 else hi = mid - 1
            }
            val last = hi.coerceIn(-1, count - 1)
            return if (first <= last) first..last else IntRange.EMPTY
        }

        /** Places the floating summary, the canvas's only child, whenever the canvas is laid out. */
        override fun doLayout() = positionSummary()

        private fun railCardAt(point: Point): TreeRailPlacement? =
            rail?.takeIf { isRailVisible }?.hitTest(point)?.takeUnless { it.card.placeholder }

        init {
            name = "charts-tree-canvas"
            isOpaque = true
            toolTipText = ""
            layout = null

            val mouse = object : MouseAdapter() {
                private var popupShown = false

                override fun mouseMoved(e: MouseEvent) {
                    val cardHit = railCardAt(e.point)
                    val linkHit = if (cardHit == null) textLinkAt(e.point) else null
                    val hit = if (linkHit == null && cardHit == null) nodeAt(e.x, e.y) else null
                    val nextHover = hit?.index ?: -1
                    val route = if (hit == null && linkHit == null && cardHit == null) connectionAt(e.point) else null
                    if (nextHover != hoveredIndex || route != hoveredRoute || linkHit != hoveredSummaryLink) {
                        hoveredSummaryLink = linkHit
                        hoveredRoute = route
                        val hoverChanged = nextHover != hoveredIndex
                        hoveredIndex = nextHover
                        updateStatus()
                        if (hoverChanged && hoveredRailCard == cardHit?.card) updateSidePanels()
                        repaint()
                    }
                    hoverRailCard(cardHit?.card)
                    if (route != null) {
                        connectionSiteFor(route)?.let { site ->
                            connectionPreview.scheduleHover(route.from, route.to, site, e.point)
                        }
                    } else {
                        connectionPreview.onHoverCleared()
                    }
                    cursor = if (hit != null || cardHit != null || linkHit != null || route != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else Cursor.getDefaultCursor()
                }

                override fun mouseExited(e: MouseEvent) {
                    connectionPreview.onHoverCleared()
                    // Moving onto the floating summary keeps what it shows, so its links stay reachable.
                    if (summaryPanel?.let { it.isVisible && it.bounds.contains(e.point) } == true) return
                    hoverRailCard(null)
                    if (hoveredIndex != -1 || hoveredRoute != null || hoveredSummaryLink != null) {
                        hoveredSummaryLink = null
                        hoveredRoute = null
                        val hoverChanged = hoveredIndex != -1
                        hoveredIndex = -1
                        updateStatus()
                        if (hoverChanged) updateSidePanels()
                        repaint()
                    }
                    cursor = Cursor.getDefaultCursor()
                }

                override fun mousePressed(e: MouseEvent) {
                    this@TreeBoardPanel.requestFocusInWindow()
                    dragged = false
                    popupShown = showPopup(e)
                    if (popupShown) return
                    if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                        dragStart = e.point
                        dragPanStart = viewport.panX to viewport.panY
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
                    mouseMoved(e)
                    if (!wasDragged && !popupShown) popupShown = showPopup(e)
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger || popupShown || e.isAltDown || dragged) return
                    railCardAt(e.point)?.let { placement ->
                        if (e.clickCount == 1) toggleRailCardSelection(placement.card)
                        return
                    }
                    textLinkAt(e.point)?.let { hit ->
                        if (e.clickCount == 1) {
                            summaryFollower(hit.text)?.activate(
                                hit.match.link,
                                e.point,
                                shift = e.isShiftDown,
                                inEditor = e.isControlDown || e.isMetaDown,
                            )
                        }
                        return
                    }
                    val node = nodeAt(e.x, e.y) ?: run {
                        if (e.clickCount == 1) previewConnection(e)
                        return
                    }
                    if (focusButtonBounds(node)?.contains(e.point) == true) {
                        moveCursor(node.index, reveal = false)
                        if (e.clickCount == 1) toggleFocus?.invoke(node.entry.path, node.entry.directory)
                        return
                    }
                    if (connectionButtonBounds(node)?.contains(e.point) == true) {
                        moveCursor(node.index, reveal = false)
                        if (e.clickCount == 1) toggleConnections?.invoke(node.entry.path)
                        return
                    }
                    if (briefButtonBounds(node)?.contains(e.point) == true) {
                        moveCursor(node.index, reveal = false)
                        if (e.clickCount == 1) toggleBriefs?.invoke(node.entry.path)
                        return
                    }
                    if (summaryButtonBounds(node)?.contains(e.point) == true) {
                        moveCursor(node.index, reveal = false)
                        if (e.clickCount == 1) toggleSummary(node.entry.path)
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
                    textLinkAt(e.point)?.let { hit ->
                        connectionPreview.hide()
                        return showSummaryLinkPopup(hit, e)
                    }
                    val node = nodeAt(e.x, e.y) ?: run {
                        connectionPreview.hide()
                        return activateConnection(e)
                    }
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

        private fun scaledSummaryFont(): Font {
            val base = baseSummaryFont()
            return base.deriveFont((base.size2D * viewport.zoom).toFloat().coerceAtLeast(6f))
        }

        private fun nodeLabel(node: TreeBoardNode): String = when {
            node.depth == 0 -> if (node.entry.path.isEmpty()) "$rootLabel/" else "$rootLabel (${node.entry.path})/"
            node.entry.directory -> "${node.entry.label}/"
            else -> node.entry.label
        }

        private fun nodeBadge(node: TreeBoardNode, collapsed: Boolean = node.collapsed): String = if (collapsed) {
            "  (${fileCount(node.entry.files)} · ${valueText(node.entry.value)})"
        } else {
            "  ${valueText(node.entry.value)}"
        }

        private fun hasFamilyDot(node: TreeBoardNode): Boolean =
            isRailVisible && node.entry.kind == ReviewLineChart.Kind.FILE && railModel?.fileFamilies?.get(node.entry.path) != null

        private fun rowContentScreenWidth(node: TreeBoardNode, boldMetrics: FontMetrics, plainMetrics: FontMetrics): Int {
            val labelWidth = boldMetrics.stringWidth(nodeLabel(node))
            val badgeWidth = plainMetrics.stringWidth(nodeBadge(node))
            val toggleScreen = if (node.collapsible) (toggleWidth * viewport.zoom).roundToInt() else 0
            val dotScreen = if (hasFamilyDot(node)) (familyDotSpace * viewport.zoom).roundToInt() else 0
            val padRight = (JBUI.scale(10) * viewport.zoom).roundToInt()
            return toggleScreen + dotScreen + labelWidth + badgeWidth + padRight + buttonScreenWidth(node)
        }

        private fun hasFocusButton(node: TreeBoardNode): Boolean =
            toggleFocus != null && node.depth > 0 &&
                (node.entry.kind == ReviewLineChart.Kind.FILE || node.entry.kind == ReviewLineChart.Kind.DIRECTORY)

        private fun hasConnectionButton(node: TreeBoardNode): Boolean =
            toggleConnections != null && node.entry.kind == ReviewLineChart.Kind.FILE

        private fun hasBriefButton(node: TreeBoardNode): Boolean =
            toggleBriefs != null && node.entry.kind == ReviewLineChart.Kind.FILE

        private fun hasSummaryButton(node: TreeBoardNode): Boolean =
            node.entry.kind == ReviewLineChart.Kind.FILE && node.entry.path in fileSummaries

        private fun buttonScreenWidth(node: TreeBoardNode): Int {
            var width = 0
            if (hasFocusButton(node)) width += (focusButtonWidth * viewport.zoom).roundToInt()
            if (hasConnectionButton(node)) width += (connectionButtonWidth * viewport.zoom).roundToInt()
            if (hasBriefButton(node)) width += (briefButtonWidth * viewport.zoom).roundToInt()
            if (hasSummaryButton(node)) width += (summaryButtonWidth * viewport.zoom).roundToInt()
            return width
        }

        private fun buttonWorldWidth(node: TreeBoardNode): Int {
            var width = 0
            if (hasFocusButton(node)) width += focusButtonWidth
            if (hasConnectionButton(node)) width += connectionButtonWidth
            if (hasBriefButton(node)) width += briefButtonWidth
            if (hasSummaryButton(node)) width += summaryButtonWidth
            return width
        }

        fun connectionButtonBounds(node: TreeBoardNode): Rectangle? {
            if (!hasConnectionButton(node)) return null
            val width = (connectionButtonWidth * viewport.zoom).roundToInt()
            val row = rowScreenBounds(node)
            return Rectangle(row.x + row.width - width, row.y, width, row.height)
        }

        fun focusButtonBounds(node: TreeBoardNode): Rectangle? {
            if (!hasFocusButton(node)) return null
            val width = (focusButtonWidth * viewport.zoom).roundToInt()
            val connWidth = if (hasConnectionButton(node)) (connectionButtonWidth * viewport.zoom).roundToInt() else 0
            val row = rowScreenBounds(node)
            return Rectangle(row.x + row.width - connWidth - width, row.y, width, row.height)
        }

        fun briefButtonBounds(node: TreeBoardNode): Rectangle? {
            if (!hasBriefButton(node)) return null
            val width = (briefButtonWidth * viewport.zoom).roundToInt()
            val connWidth = if (hasConnectionButton(node)) (connectionButtonWidth * viewport.zoom).roundToInt() else 0
            val focusWidth = if (hasFocusButton(node)) (focusButtonWidth * viewport.zoom).roundToInt() else 0
            val row = rowScreenBounds(node)
            return Rectangle(row.x + row.width - connWidth - focusWidth - width, row.y, width, row.height)
        }

        fun summaryButtonBounds(node: TreeBoardNode): Rectangle? {
            if (!hasSummaryButton(node)) return null
            val width = (summaryButtonWidth * viewport.zoom).roundToInt()
            val connWidth = if (hasConnectionButton(node)) (connectionButtonWidth * viewport.zoom).roundToInt() else 0
            val focusWidth = if (hasFocusButton(node)) (focusButtonWidth * viewport.zoom).roundToInt() else 0
            val briefWidth = if (hasBriefButton(node)) (briefButtonWidth * viewport.zoom).roundToInt() else 0
            val row = rowScreenBounds(node)
            return Rectangle(row.x + row.width - connWidth - focusWidth - briefWidth - width, row.y, width, row.height)
        }

        fun summaryBlockScreenBounds(node: TreeBoardNode): Rectangle? {
            val block = rowLayout().summaries[node.index] ?: return null
            val x = viewport.worldToScreenX(nodeContentStartWorldX(node.depth) + summaryIndent).roundToInt()
            val y = viewport.worldToScreenY(rowHeaderBottomWorld(node.index) + summaryTopGap).roundToInt()
            val w = (block.blockWidth * viewport.zoom).roundToInt().coerceAtLeast(16)
            val h = (block.blockHeight * viewport.zoom).roundToInt().coerceAtLeast(12)
            return Rectangle(x, y, w, h)
        }

        /** The one-line briefs beside [path]: the file's own summary, or how it links with the briefed file. */
        fun briefSegments(path: String): List<BriefSegment> {
            val current = briefs ?: return emptyList()
            val name = current.path.substringAfterLast('/')
            fun text(brief: TreeBrief?, pending: String) = when (brief) {
                null -> null
                TreeBrief.Pending -> pending
                TreeBrief.Failed -> "summary unavailable"
                is TreeBrief.Ready -> brief.text
            }
            if (path == current.path) {
                return listOf(BriefSegment("— ", accentColor, text(current.file, "summarizing…")!!, current.file is TreeBrief.Ready))
            }
            val incoming = current.incoming[path]
            val outgoing = current.outgoing[path]
            return listOfNotNull(
                text(incoming, "…")?.let { BriefSegment("→ $name: ", incomingColor, it, incoming is TreeBrief.Ready) },
                text(outgoing, "…")?.let { BriefSegment("← $name: ", outgoingColor, it, outgoing is TreeBrief.Ready) },
            )
        }

        /** Width of [node]'s briefs, including the gap after its row, measured with [metrics]. */
        fun briefWidth(node: TreeBoardNode, metrics: FontMetrics, gap: Double): Int {
            val segments = briefSegments(node.entry.path)
            if (segments.isEmpty()) return 0
            val separator = metrics.stringWidth(BRIEF_SEPARATOR) * (segments.size - 1)
            return (gap + segments.sumOf { metrics.stringWidth(it.prefix) + metrics.stringWidth(it.text) } + separator).roundToInt()
        }

        fun invalidateRowWidth() {
            rowWidthAtZoom = null
            cachedTreeRight = null
            invalidateRoutes()
        }

        private var cachedTreeRight: Double? = null
        private var cachedUnfoldedRight: Pair<Any?, Double>? = null

        /** World right edge of one row's header, briefs, and inline summary, measured with the base fonts. */
        private fun rowRightWorld(node: TreeBoardNode, boldMetrics: FontMetrics, plainMetrics: FontMetrics, layout: RowLayout?): Double {
            val left = nodeContentStartWorldX(node.depth)
            val toggle = if (node.collapsible) toggleWidth else 0.0
            val dot = if (hasFamilyDot(node)) familyDotSpace else 0
            val textW = boldMetrics.stringWidth(nodeLabel(node)) + plainMetrics.stringWidth(nodeBadge(node, node.collapsed || (layout == null && node.collapsible)))
            var right = left + toggle + dot + textW + JBUI.scale(24) + buttonWorldWidth(node)
            if (layout != null) {
                right += briefWidth(node, plainMetrics, briefGap.toDouble())
                layout.summaries[node.index]?.let { block -> right = maxOf(right, left + summaryIndent + block.blockWidth) }
            }
            return right
        }

        /**
         * World right edge of the tree: its widest row when every folder is unfolded, so folding doesn't move the
         * cards, or wider while briefs, inline summaries, or link lanes extend the visible rows.
         */
        fun treeRightWorldX(): Double {
            cachedTreeRight?.let { return it }
            val boldMetrics = getFontMetrics(baseMonospaceFont().let { it.deriveFont(it.style or Font.BOLD) })
            val plainMetrics = getFontMetrics(baseMonospaceFont())
            val layout = rowLayout()
            var maxRight = board.nodes.maxOfOrNull { rowRightWorld(it, boldMetrics, plainMetrics, layout) } ?: 0.0
            val laneCount = visibleConnections().size
            if (laneCount > 0) maxRight += connectionLaneOffset(laneCount - 1)
            val unfoldedKey = visibleFocusFiles to isRailVisible
            val unfolded = cachedUnfoldedRight?.takeIf { it.first == unfoldedKey }?.second
                ?: ReviewTreeBoard(chart, emptySet(), visibleFocusFiles).nodes
                    .maxOfOrNull { rowRightWorld(it, boldMetrics, plainMetrics, null) }.let { it ?: 0.0 }
                    .also { cachedUnfoldedRight = unfoldedKey to it }
            return maxOf(maxRight, unfolded).also { cachedTreeRight = it }
        }

        fun contentWorldBounds(): Pair<Double, Double> {
            if (board.nodes.isEmpty()) return 200.0 to 100.0
            return (treeRightWorldX() + worldPadX) to rowLayout().totalHeight
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
            val index = rowIndexAtWorldY(wy)
            val node = board.nodes.getOrNull(index) ?: return null
            if (wy >= rowHeaderBottomWorld(index)) return null
            val bounds = rowScreenBounds(node)
            val padded = Rectangle(bounds.x - JBUI.scale(6), bounds.y, bounds.width + JBUI.scale(12), bounds.height)
            return if (padded.contains(sx, sy)) node else null
        }

        override fun getToolTipText(event: MouseEvent): String? {
            rail?.takeIf { isRailVisible }?.hitTest(event.point)?.let { return rail.tooltip(it) }
            textLinkAt(event.point)?.let { hit ->
                return summaryFollower(hit.text)?.tooltip(hit.match.link)
                    ?: linkTooltip(hit.match.link, inReviewScope = false, isDiffReview = diffMode)
            }
            val node = nodeAt(event.x, event.y) ?: run {
                return connectionAt(event.point)?.let {
                    "${it.from} → ${it.to} · Click to inspect snippets · Right-click for context"
                }
            }
            if (focusButtonBounds(node)?.contains(event.point) == true) {
                return if (focusSelection?.path == node.entry.path) "Exit focus mode (F)"
                else "Focus tree on linked files (F)"
            }
            if (connectionButtonBounds(node)?.contains(event.point) == true) {
                return if (connectionSelection?.path == node.entry.path) "Hide code connections (C)"
                else "Show incoming and outgoing code connections (C)"
            }
            if (briefButtonBounds(node)?.contains(event.point) == true) {
                return if (briefs?.path == node.entry.path) "Hide one-line summaries (S)"
                else "Summarize this file and how each linked file relates to it, beside the rows (S)"
            }
            if (summaryButtonBounds(node)?.contains(event.point) == true) {
                val label = if (diffMode) "diff summary" else "file summary"
                return if (node.entry.path in expandedSummaryPaths) "Hide $label (D)"
                else "Show $label (D)"
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
                val visibleRange = visibleRowRange(worldTop, worldBottom)
                if (visibleRange.isEmpty()) {
                    paintRail(g)
                    return
                }
                val layout = rowLayout()

                val cursorHl = board.highlight(cursorIndex)
                val hoverHl = hoveredIndex.takeIf { it >= 0 && it != cursorIndex }?.let(board::highlight)
                val groupHl = groupHighlight

                val plainFont = scaledFont(bold = false)
                val boldFont = scaledFont(bold = true)
                val summaryFont = scaledSummaryFont()
                val plainMetrics = g.getFontMetrics(plainFont)
                val boldMetrics = g.getFontMetrics(boldFont)

                val baseLineColor = JBColor(Color(0xB0B7C0), Color(0x525964))
                val primaryAccent = accentColor
                val hoverAccent = alpha(primaryAccent, 195)
                val groupColor = activeRailCard?.family?.let(DocumentColumnsPanel::accent) ?: primaryAccent
                val groupBranchColor = alpha(groupColor, 175)
                val normalStroke = BasicStroke((1.0f * viewport.zoom.toFloat()).coerceAtLeast(1.0f))
                val highlightStroke = BasicStroke((2.0f * viewport.zoom.toFloat()).coerceAtLeast(1.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val panelFocused = this@TreeBoardPanel.isFocusOwner

                for (i in visibleRange) {
                    val node = board.nodes[i]
                    val topY = viewport.worldToScreenY(rowTopWorld(i))
                    val headerBottomY = viewport.worldToScreenY(rowHeaderBottomWorld(i))
                    val bottomY = viewport.worldToScreenY(rowBottomWorld(i))
                    val centerY = viewport.worldToScreenY(rowCenterWorld(i))
                    val startX = viewport.worldToScreenX(nodeContentStartWorldX(node.depth))

                    // Row highlight pill
                    val isGroupRow = groupHl != null && (i in groupHl.matchingRows || i in groupHl.collapsedContainers)
                    if (i == cursorIndex || i == hoveredIndex || isGroupRow) {
                        val rowW = rowContentScreenWidth(node, boldMetrics, plainMetrics).toDouble()
                        val padX = 4.0 * viewport.zoom
                        val padY = 1.5 * viewport.zoom
                        val arc = 6.0 * viewport.zoom
                        val pill = RoundRectangle2D.Double(startX - padX, topY + padY, rowW + padX * 2, (headerBottomY - topY) - padY * 2, arc, arc)
                        when {
                            i == cursorIndex -> {
                                g.color = JBUI.CurrentTheme.List.Selection.background(panelFocused)
                                g.fill(pill)
                                g.color = primaryAccent
                                g.stroke = BasicStroke((1.2f * viewport.zoom.toFloat()).coerceAtLeast(1.0f))
                                g.draw(pill)
                            }
                            i == hoveredIndex -> {
                                g.color = JBUI.CurrentTheme.List.Hover.background(true)
                                g.fill(pill)
                            }
                            isGroupRow -> {
                                g.color = alpha(groupColor, 38)
                                g.fill(pill)
                                g.color = alpha(groupColor, 160)
                                g.stroke = BasicStroke((1.0f * viewport.zoom.toFloat()).coerceAtLeast(1.0f))
                                g.draw(pill)
                            }
                        }
                    }

                    // Ancestor vertical trunks
                    for (col in 0 until (node.depth - 1).coerceAtLeast(0)) {
                        if (!node.ancestorHasNextSibling[col]) continue
                        val x = viewport.worldToScreenX(columnWorldX(col)).toInt()
                        val lineColor = when {
                            cursorHl?.verticalLineHighlighted(node, col, board.nodes) == true -> primaryAccent
                            hoverHl?.verticalLineHighlighted(node, col, board.nodes) == true -> hoverAccent
                            groupHl?.verticalLineHighlighted(node, col, board.nodes) == true -> groupBranchColor
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
                            groupHl?.let { it.connectorHighlighted(node) || it.verticalLineHighlighted(node, col, board.nodes) } == true -> groupBranchColor
                            else -> null
                        }
                        g.color = topColor ?: baseLineColor
                        g.stroke = if (topColor != null) highlightStroke else normalStroke
                        g.drawLine(x, topY.toInt(), x, midY)

                        if (!node.isLastChild) {
                            val bottomColor = when {
                                cursorHl?.verticalLineHighlighted(node, col, board.nodes) == true -> primaryAccent
                                hoverHl?.verticalLineHighlighted(node, col, board.nodes) == true -> hoverAccent
                                groupHl?.verticalLineHighlighted(node, col, board.nodes) == true -> groupBranchColor
                                else -> null
                            }
                            g.color = bottomColor ?: baseLineColor
                            g.stroke = if (bottomColor != null) highlightStroke else normalStroke
                            g.drawLine(x, midY, x, bottomY.toInt())
                        }

                        val horizColor = when {
                            cursorHl?.connectorHighlighted(node) == true -> primaryAccent
                            hoverHl?.connectorHighlighted(node) == true -> hoverAccent
                            groupHl?.connectorHighlighted(node) == true -> groupBranchColor
                            else -> null
                        }
                        g.color = horizColor ?: baseLineColor
                        g.stroke = if (horizColor != null) highlightStroke else normalStroke
                        g.drawLine(x, midY, rightX, midY)
                    }

                    // Fold chevron
                    var textX = startX.toFloat()
                    if (node.collapsible) {
                        val chevronColor = if (cursorHl?.contains(i) == true || hoverHl?.contains(i) == true) primaryAccent
                        else if (groupHl?.contains(i) == true) groupColor
                        else UIUtil.getContextHelpForeground()
                        drawChevron(g, startX, centerY, node.collapsed, chevronColor)
                        textX += (toggleWidth * viewport.zoom).toFloat()
                    }

                    // Topic/Group family dot for files
                    if (hasFamilyDot(node)) {
                        val family = railModel?.fileFamilies?.get(node.entry.path)
                        if (family != null) {
                            val dotSize = (JBUI.scale(5) * viewport.zoom).roundToInt().coerceAtLeast(3)
                            val dotX = textX.roundToInt()
                            val dotY = (centerY - dotSize / 2.0).roundToInt()
                            g.color = DocumentColumnsPanel.accent(family)
                            g.fillOval(dotX, dotY, dotSize, dotSize)
                        }
                        textX += (familyDotSpace * viewport.zoom).toFloat()
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
                    val badgeText = nodeBadge(node)
                    g.font = plainFont
                    g.color = if (metric.diffSeries != null) numberColor else UIUtil.getContextHelpForeground()
                    g.drawString(badgeText, badgeX, baselineY)
                    if (groupHl != null && i in groupHl.collapsedContainers) {
                        val dotSize = (JBUI.scale(5) * viewport.zoom).roundToInt().coerceAtLeast(3)
                        val dotX = (badgeX + plainMetrics.stringWidth(badgeText) + JBUI.scale(3) * viewport.zoom).roundToInt()
                        val dotY = (centerY - dotSize / 2.0).roundToInt()
                        g.color = groupColor
                        g.fillOval(dotX, dotY, dotSize, dotSize)
                    }
                    val rowWidth = rowContentScreenWidth(node, boldMetrics, plainMetrics)
                    var buttonRightX = startX + rowWidth
                    if (hasConnectionButton(node)) {
                        val connWidth = (connectionButtonWidth * viewport.zoom).roundToInt()
                        paintConnectionButton(g, buttonRightX - connWidth / 2.0, centerY, node.entry.path)
                        buttonRightX -= connWidth
                    }
                    if (hasFocusButton(node)) {
                        val focusW = (focusButtonWidth * viewport.zoom).roundToInt()
                        paintFocusButton(g, buttonRightX - focusW / 2.0, centerY, node.entry.path)
                        buttonRightX -= focusW
                    }
                    if (hasBriefButton(node)) {
                        val briefW = (briefButtonWidth * viewport.zoom).roundToInt()
                        paintBriefButton(g, buttonRightX - briefW / 2.0, centerY, node.entry.path)
                        buttonRightX -= briefW
                    }
                    if (hasSummaryButton(node)) {
                        val summaryW = (summaryButtonWidth * viewport.zoom).roundToInt()
                        paintSummaryButton(g, buttonRightX - summaryW / 2.0, centerY, node.entry.path)
                    }
                    paintBriefs(g, node, centerY, plainFont)
                    layout.summaries[i]?.let { block ->
                        paintSummaryBlock(g, node, block, summaryFont)
                    }
                }
                paintConnections(g)
                paintRail(g)
            } finally {
                g.dispose()
            }
        }


        private fun paintRail(g: Graphics2D) {
            if (isRailVisible) rail?.paint(g, width, height)
        }
        private fun paintFocusButton(g: Graphics2D, x: Double, y: Double, path: String) {
            val active = focusSelection?.path == path
            val radius = (JBUI.scale(4) * viewport.zoom).coerceAtLeast(2.0)
            val tick = (JBUI.scale(2) * viewport.zoom).coerceAtLeast(1.0)
            val cx = x.roundToInt()
            val cy = y.roundToInt()
            val r = radius.roundToInt()
            val t = tick.roundToInt()
            val dot = (JBUI.scale(2) * viewport.zoom).roundToInt().coerceAtLeast(2)
            val strokeBase = viewport.zoom.toFloat().coerceAtLeast(1f)
            g.color = if (active) accentColor else UIUtil.getContextHelpForeground()
            g.stroke = BasicStroke(if (active) strokeBase * 1.4f else strokeBase)
            g.drawOval(cx - r, cy - r, r * 2, r * 2)
            g.fillOval(cx - dot / 2, cy - dot / 2, dot, dot)
            g.drawLine(cx, cy - r - t, cx, cy - r + 1)
            g.drawLine(cx, cy + r - 1, cx, cy + r + t)
            g.drawLine(cx - r - t, cy, cx - r + 1, cy)
            g.drawLine(cx + r - 1, cy, cx + r + t, cy)
        }

        private fun paintSummaryButton(g: Graphics2D, x: Double, y: Double, path: String) {
            val active = path in expandedSummaryPaths
            val halfW = (JBUI.scale(4) * viewport.zoom).coerceAtLeast(2.0)
            val halfH = (JBUI.scale(5) * viewport.zoom).coerceAtLeast(2.5)
            val left = (x - halfW).roundToInt()
            val top = (y - halfH).roundToInt()
            val width = (halfW * 2).roundToInt().coerceAtLeast(4)
            val height = (halfH * 2).roundToInt().coerceAtLeast(5)
            val arc = (JBUI.scale(2) * viewport.zoom).roundToInt().coerceAtLeast(1)
            val strokeBase = viewport.zoom.toFloat().coerceAtLeast(1f)
            g.color = if (active) accentColor else UIUtil.getContextHelpForeground()
            g.stroke = BasicStroke(if (active) strokeBase * 1.35f else strokeBase, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawRoundRect(left, top, width, height, arc, arc)
            val pad = (JBUI.scale(2) * viewport.zoom).roundToInt().coerceAtLeast(1)
            val textRight = (left + width - pad).coerceAtLeast(left + pad + 1)
            val line1Y = (y - halfH * 0.28).roundToInt()
            val line2Y = (y + halfH * 0.28).roundToInt()
            g.drawLine(left + pad, line1Y, textRight, line1Y)
            g.drawLine(left + pad, line2Y, (left + pad + (textRight - left - pad) * 0.7).roundToInt(), line2Y)
        }

        private fun summaryBlockGeometry(node: TreeBoardNode, block: WrappedSummaryBlock, metrics: FontMetrics): SummaryBlockGeometry {
            val boxX = viewport.worldToScreenX(nodeContentStartWorldX(node.depth) + summaryIndent)
            val boxY = viewport.worldToScreenY(rowHeaderBottomWorld(node.index) + summaryTopGap)
            val padX = summaryPadX * viewport.zoom
            val padY = summaryPadY * viewport.zoom
            val barW = (JBUI.scale(2) * viewport.zoom).coerceAtLeast(1.5)
            val maxScreenLineW = block.lines.maxOf { metrics.stringWidth(it.text) }.toDouble()
            val boxW = maxOf(block.blockWidth * viewport.zoom, maxScreenLineW + padX * 2 + barW * 0.5)
            val boxH = block.blockHeight * viewport.zoom
            val stepY = block.lineHeight * viewport.zoom
            val textX = (boxX + padX + barW * 0.5).toFloat()
            val firstBaseline = boxY + padY + (stepY - metrics.height) / 2.0 + metrics.ascent
            return SummaryBlockGeometry(boxX, boxY, boxW, boxH, padX, padY, barW, stepY, textX, firstBaseline, metrics)
        }

        fun summaryLinkScreenBounds(node: TreeBoardNode, match: LineLinkMatch): Rectangle? {
            val block = rowLayout().summaries[node.index] ?: return null
            val metrics = getFontMetrics(scaledSummaryFont())
            val geom = summaryBlockGeometry(node, block, metrics)
            var result: Rectangle? = null
            for ((idx, line) in block.lines.withIndex()) {
                val span = lineLinkSpans(line, listOf(match)).firstOrNull() ?: continue
                val startX = geom.textX + geom.metrics.stringWidth(line.text.substring(0, span.startCol))
                val endX = geom.textX + geom.metrics.stringWidth(line.text.substring(0, span.endCol))
                val topY = geom.boxY + geom.padY + idx * geom.stepY
                val rect = Rectangle(
                    floor(startX.toDouble()).toInt(),
                    floor(topY).toInt(),
                    ceil((endX - startX).toDouble()).toInt().coerceAtLeast(1),
                    ceil(geom.stepY).toInt().coerceAtLeast(1),
                )
                result = result?.union(rect) ?: rect
            }
            return result
        }

        private fun textLinkAt(point: Point): SummaryLinkHit? {
            val wy = viewport.screenToWorldY(point.y.toDouble())
            val node = board.nodes.getOrNull(rowIndexAtWorldY(wy)) ?: return null
            if (wy >= rowHeaderBottomWorld(node.index)) return summaryLinkAt(node, point)
            if (briefs == null) return null
            val metrics = getFontMetrics(scaledFont(bold = false))
            for ((segment, x) in positionedBriefs(node, metrics)) {
                val key = SummaryText(node.entry.path, segment.text)
                val match = briefLinkSpans(key, segment).firstOrNull {
                    val start = x + metrics.stringWidth(segment.text.substring(0, it.startCol))
                    val end = x + metrics.stringWidth(segment.text.substring(0, it.endCol))
                    point.x >= start && point.x < end
                }
                if (match != null) return SummaryLinkHit(key, match.match)
            }
            return null
        }

        private fun summaryLinkAt(node: TreeBoardNode, point: Point): SummaryLinkHit? {
            val block = rowLayout().summaries[node.index] ?: return null
            val path = node.entry.path
            val key = SummaryText(path, fileSummaries.getValue(path))
            val matches = resolvedSummaryLinks[key]?.matches.orEmpty()
            if (matches.isEmpty()) return null
            val metrics = getFontMetrics(scaledSummaryFont())
            val geom = summaryBlockGeometry(node, block, metrics)
            if (point.x < geom.boxX || point.x > geom.boxX + geom.boxW || point.y < geom.boxY || point.y > geom.boxY + geom.boxH) {
                return null
            }
            val lineIdx = floor((point.y - (geom.boxY + geom.padY)) / geom.stepY).toInt()
            val line = block.lines.getOrNull(lineIdx) ?: return null
            for (span in lineLinkSpans(line, matches)) {
                val startX = geom.textX + geom.metrics.stringWidth(line.text.substring(0, span.startCol))
                val endX = geom.textX + geom.metrics.stringWidth(line.text.substring(0, span.endCol))
                if (point.x >= startX && point.x <= endX) {
                    return SummaryLinkHit(key, span.match)
                }
            }
            return null
        }

        private fun showSummaryLinkPopup(hit: SummaryLinkHit, e: MouseEvent): Boolean {
            val follower = summaryFollower(hit.text) ?: return false
            val link = hit.match.link
            val menu = JPopupMenu()
            follower.addMenuItems(menu, link, e.point, hasSelection = false)
            val primary = link.primaryPath
            if (link.kind == ReportTargetKind.FILE && primary != null && !link.isAmbiguous) {
                summaryFileMenuItems?.invoke(primary, link.lines)?.forEach(menu::add)
            }
            if (menu.componentCount == 0) return false
            menu.show(this@TreeBoardCanvas, e.x, e.y)
            return true
        }

        private fun paintSummaryBlock(g: Graphics2D, node: TreeBoardNode, block: WrappedSummaryBlock, font: Font) {
            g.font = font
            val geom = summaryBlockGeometry(node, block, g.getFontMetrics(font))
            val arc = 6.0 * viewport.zoom
            val shape = RoundRectangle2D.Double(geom.boxX, geom.boxY, geom.boxW, geom.boxH, arc, arc)
            g.color = summaryBgColor
            g.fill(shape)
            g.color = JBColor.border()
            g.stroke = BasicStroke((1.0f * viewport.zoom.toFloat()).coerceAtLeast(1.0f))
            g.draw(shape)
            val barMargin = 2.0 * viewport.zoom
            g.color = alpha(accentColor, 180)
            g.fill(RoundRectangle2D.Double(geom.boxX, geom.boxY + barMargin, geom.barW, (geom.boxH - barMargin * 2).coerceAtLeast(1.0), geom.barW, geom.barW))
            val path = node.entry.path
            val key = SummaryText(path, fileSummaries.getValue(path))
            val matches = resolvedSummaryLinks[key]?.matches.orEmpty()
            for ((idx, line) in block.lines.withIndex()) {
                val baseline = (geom.firstBaseline + idx * geom.stepY).toFloat()
                paintLinkedLine(g, key, line.text, lineLinkSpans(line, matches), geom.textX, baseline, geom.metrics)
            }
        }

        private fun paintLinkedLine(
            g: Graphics2D,
            key: SummaryText,
            text: String,
            spans: List<SummaryLinkSpan>,
            x: Float,
            baseline: Float,
            metrics: FontMetrics,
        ) {
            var col = 0
            var start = x
            val underlineY = (baseline + 1.5f * viewport.zoom.toFloat()).roundToInt()
            for (span in spans) {
                g.color = UIUtil.getLabelForeground()
                g.drawString(text.substring(col, span.startCol), start, baseline)
                start = x + metrics.stringWidth(text.substring(0, span.startCol))
                val end = x + metrics.stringWidth(text.substring(0, span.endCol))
                val hovered = hoveredSummaryLink == SummaryLinkHit(key, span.match)
                g.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
                g.drawString(text.substring(span.startCol, span.endCol), start, baseline)
                val weight = if (hovered) 1.6f else 1.0f
                val minimum = if (hovered) 1.5f else 1.0f
                g.stroke = BasicStroke((weight * viewport.zoom.toFloat()).coerceAtLeast(minimum))
                g.drawLine(start.roundToInt(), underlineY, end.roundToInt().coerceAtLeast(start.roundToInt() + 1), underlineY)
                start = end
                col = span.endCol
            }
            g.color = UIUtil.getLabelForeground()
            g.drawString(text.substring(col), start, baseline)
        }

        private fun paintBriefButton(g: Graphics2D, x: Double, y: Double, path: String) {
            val active = briefs?.path == path
            val half = (JBUI.scale(5) * viewport.zoom).coerceAtLeast(2.0)
            val gap = (JBUI.scale(3) * viewport.zoom).coerceAtLeast(1.5)
            val left = (x - half).roundToInt()
            g.color = if (active) accentColor else UIUtil.getContextHelpForeground()
            g.stroke = BasicStroke(viewport.zoom.toFloat().coerceAtLeast(1f) * if (active) 1.4f else 1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            // Three text lines of decreasing length.
            for ((row, length) in listOf(1.0, 0.75, 0.5).withIndex()) {
                val lineY = (y + (row - 1) * gap).roundToInt()
                g.drawLine(left, lineY, (left + 2 * half * length).roundToInt(), lineY)
            }
        }

        private fun positionedBriefs(node: TreeBoardNode, metrics: FontMetrics): List<Pair<BriefSegment, Float>> {
            val segments = briefSegments(node.entry.path)
            if (segments.isEmpty()) return emptyList()
            val rowStart = viewport.worldToScreenX(nodeContentStartWorldX(node.depth))
            val rowWidth = rowContentScreenWidth(node, getFontMetrics(scaledFont(bold = true)), metrics)
            var x = (rowStart + rowWidth + briefGap * viewport.zoom).toFloat()
            val separatorWidth = metrics.stringWidth(BRIEF_SEPARATOR)
            return segments.map { segment ->
                val textStart = x + metrics.stringWidth(segment.prefix)
                x = textStart + metrics.stringWidth(segment.text) + separatorWidth
                segment to textStart
            }
        }

        private fun briefLinkSpans(key: SummaryText, segment: BriefSegment): List<SummaryLinkSpan> {
            if (!segment.ready) return emptyList()
            val matches = resolvedSummaryLinks[key]?.matches.orEmpty()
            if (matches.isEmpty()) return emptyList()
            val text = segment.text
            return lineLinkSpans(WrappedSummaryLine(text, 0, text.length, IntArray(text.length) { it }), matches)
        }

        private fun paintBriefs(g: Graphics2D, node: TreeBoardNode, centerY: Double, font: Font) {
            g.font = font
            val metrics = g.getFontMetrics(font)
            val baseline = (centerY + (metrics.ascent - metrics.descent) / 2.0).toFloat()
            for ((index, positioned) in positionedBriefs(node, metrics).withIndex()) {
                val (segment, x) = positioned
                val prefixX = x - metrics.stringWidth(segment.prefix)
                if (index > 0) {
                    g.color = UIUtil.getContextHelpForeground()
                    g.drawString(BRIEF_SEPARATOR, prefixX - metrics.stringWidth(BRIEF_SEPARATOR), baseline)
                }
                g.color = segment.color
                g.drawString(segment.prefix, prefixX, baseline)
                val key = SummaryText(node.entry.path, segment.text)
                paintLinkedLine(g, key, segment.text, briefLinkSpans(key, segment), x, baseline, metrics)
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
            return links.incoming.keys.mapNotNull { path -> board.indexOf(path).takeIf { it >= 0 }?.let { it to selected } } +
                links.outgoing.keys.mapNotNull { path -> board.indexOf(path).takeIf { it >= 0 }?.let { selected to it } }
        }

        private fun connectionLaneOffset(index: Int) = JBUI.scale(24) + index * JBUI.scale(12)

        private fun connectionRoutes(): List<ConnectionRoute> {
            routes?.let { return it }
            val bold = getFontMetrics(scaledFont(true))
            val plain = getFontMetrics(scaledFont(false))
            val links = visibleConnections().sortedBy { (from, to) -> kotlin.math.abs(from - to) }
            if (links.isEmpty()) return emptyList<ConnectionRoute>().also { routes = it }
            val selected = board.indexOf(connectionSelection?.path)
            val layout = rowLayout()
            // Links start after a row's briefs and expanded summary block so their lines never cross the text.
            fun rowRight(node: TreeBoardNode): Int {
                val headerRight = (nodeContentStartWorldX(node.depth) * viewport.zoom).roundToInt() +
                    rowContentScreenWidth(node, bold, plain) +
                    briefWidth(node, plain, briefGap * viewport.zoom).let { if (it > 0) it + (JBUI.scale(6) * viewport.zoom).roundToInt() else 0 }
                val summaryRight = layout.summaries[node.index]?.let { block ->
                    ((nodeContentStartWorldX(node.depth) + summaryIndent + block.blockWidth) * viewport.zoom).roundToInt()
                } ?: 0
                return maxOf(headerRight, summaryRight)
            }
            val right = rowWidthAtZoom?.takeIf { it.first == viewport.zoom }?.second
                ?: board.nodes.maxOf(::rowRight).also { rowWidthAtZoom = viewport.zoom to it }
            return links.mapIndexed { index, (from, to) ->
                val outgoing = from == selected
                val offset = JBUI.scale(4) * viewport.zoom * if (outgoing) 1 else -1
                val y1 = viewport.worldToScreenY(rowCenterWorld(from)) + offset
                val y2 = viewport.worldToScreenY(rowCenterWorld(to)) + offset
                val x1 = rowRight(board.nodes[from]) + viewport.panX
                val x2 = rowRight(board.nodes[to]) + viewport.panX
                val bend = right + viewport.panX + connectionLaneOffset(index) * viewport.zoom
                ConnectionRoute(board.nodes[from].entry.path, board.nodes[to].entry.path, outgoing, x1, y1, bend, x2, y2)
            }.also { routes = it }
        }

        private fun connectionAt(point: Point): ConnectionRoute? {
            if (connectionSelection?.connections == null) return null
            val radius = JBUI.scale(7)
            var best = (radius * radius).toDouble()
            var nearest: ConnectionRoute? = null
            for (route in connectionRoutes().asReversed()) {
                val distance = route.distanceSq(point)
                if (distance < best) {
                    nearest = route
                    best = distance
                }
            }
            return nearest
        }

        private fun connectionSiteFor(route: ConnectionRoute): TreeConnectionSite? {
            val links = connectionSelection?.connections ?: return null
            return if (route.outgoing) links.outgoing[route.to] else links.incoming[route.from]
        }

        private fun previewConnection(event: MouseEvent): Boolean {
            val route = connectionAt(event.point) ?: return false
            val site = connectionSiteFor(route) ?: return false
            connectionPreview.showPinned(route.from, route.to, site, event.point)
            return true
        }

        private fun activateConnection(event: MouseEvent): Boolean {
            val route = connectionAt(event.point) ?: return false
            val site = connectionSiteFor(route) ?: return false
            openConnection?.invoke(route.from, route.to, site, RelativePoint(event))
            return true
        }

        private fun paintConnections(g: Graphics2D) {
            val links = connectionRoutes()
            val arrow = (JBUI.scale(6) * viewport.zoom).coerceAtLeast(3.0)
            fun draw(route: ConnectionRoute, highlighted: Boolean) {
                val (_, _, outgoing, x1, y1, bend, x2, y2) = route
                if (maxOf(y1, y2) < 0 || minOf(y1, y2) > height) return
                val weight = JBUI.scale(if (highlighted) 4 else 2)
                val strokeWidth = (weight * viewport.zoom).toFloat().coerceAtLeast(weight.toFloat())
                val solid = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                val dashed = BasicStroke(strokeWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f,
                    floatArrayOf(strokeWidth * 3, strokeWidth * 2), 0f)
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
            for (route in links) if (route != hoveredRoute) draw(route, false)
            hoveredRoute?.let { draw(it, true) }
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
        const val SUMMARY_FONT_SCALE = 1.1f
        const val PAGE_ROWS = 12
        const val PAN_THRESHOLD_PX = 4.0
        const val ZOOM_WHEEL_BASE = 1.15
        const val BRIEF_SEPARATOR = "   ·   "
        val outgoingColor = JBColor(Color(0x087D76), Color(0x65C9BD))
        val incomingColor = JBColor(Color(0x7050AA), Color(0xBD9BFD))
        val summaryBgColor = JBColor(Color(0xF6F8FA), Color(0x2B2D30))
    }
}
