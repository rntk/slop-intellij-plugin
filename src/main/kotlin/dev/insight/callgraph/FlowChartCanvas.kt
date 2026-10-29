package dev.insight.callgraph

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.graph.DecisionKind
import dev.insight.graph.FlowBoard
import dev.insight.graph.FlowChart
import dev.insight.graph.FlowItem
import dev.insight.graph.FlowLane
import dev.insight.graph.FlowRegion
import dev.insight.graph.FlowRegionEvidence
import dev.insight.graph.FlowRegionPlan
import dev.insight.graph.FlowSource
import dev.insight.graph.FlowStart
import dev.insight.graph.FlowStep
import dev.insight.graph.buildFlowChart
import dev.insight.graph.children
import dev.insight.graph.presentation
import dev.insight.graph.regionCandidates
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
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
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
private val startColor = JBColor(Color(0x237A46), Color(0x73CF99))
private val mutedText = JBColor(Color(0x6E737A), Color(0x9DA2A8))
private val focusColor = JBColor(Color(0x2F6FDB), Color(0x6EA1F5))
private val contextColor = JBColor(Color(0x7A8290), Color(0x9AA3B0))
private val sourceColor = JBColor(Color(0x1F8A4C), Color(0x5FCB8A))
private val sinkColor = JBColor(Color(0x8A3FC1), Color(0xC08BF0))
private val problemColor = JBColor(Color(0xC8452B), Color(0xF07A5F))
private val transformColor = JBColor(Color(0x0E8A72), Color(0x4CC9B0))
private val decisionRoleColor = JBColor(Color(0xC27803), Color(0xE8A838))
private val orchestratorColor = JBColor(Color(0x5A45B0), Color(0x9B87F5))
private val callLinkColor = JBColor(Color(0x50565E), Color(0xB0B5BC))
private val dataLinkColor = JBColor(Color(0x0F8A8A), Color(0x4FC9C9))
private val eventLinkColor = JBColor(Color(0xB7791F), Color(0xE5B45A))
private val callbackLinkColor = JBColor(Color(0xB0397A), Color(0xE07DB5))
private val snippetFill = JBColor(Color(0xF7F8FA), Color(0x1E1F22))
private val groupPalette = listOf(JBColor(Color(0x3D7BD9), Color(0x6E9FE8)), JBColor(Color(0x2E9A5C), Color(0x62C08A)),
    JBColor(Color(0xC08A1E), Color(0xD9AE57)), JBColor(Color(0x8C55C9), Color(0xB18BE6)), JBColor(Color(0x1E9696), Color(0x55C2C2)),
    JBColor(Color(0xC4506E), Color(0xE0839B)))

internal fun externalColor(kind: ExternalKind): Color = when (kind) {
    ExternalKind.DATABASE -> sinkColor
    ExternalKind.NETWORK -> focusColor
    ExternalKind.FILE -> eventLinkColor
    ExternalKind.QUEUE -> dataLinkColor
    ExternalKind.LLM -> callbackLinkColor
    ExternalKind.UI -> sourceColor
    ExternalKind.OTHER -> contextColor
}

internal fun roleColor(role: SceneRole): Color = when (role) {
    SceneRole.FOCUS -> focusColor
    SceneRole.CONTEXT -> contextColor
    SceneRole.SOURCE -> sourceColor
    SceneRole.SINK -> sinkColor
    SceneRole.PROBLEM -> problemColor
    SceneRole.TRANSFORM -> transformColor
    SceneRole.DECISION -> decisionRoleColor
    SceneRole.ORCHESTRATOR -> orchestratorColor
}

internal fun linkColor(kind: SceneLinkKind): Color = when (kind) {
    SceneLinkKind.CALL -> callLinkColor
    SceneLinkKind.DATA -> dataLinkColor
    SceneLinkKind.EVENT -> eventLinkColor
    SceneLinkKind.CALLBACK -> callbackLinkColor
    SceneLinkKind.ASYNC -> accent
}
private const val MAX_ATTACH_TRIES = 12

/** How a function joined the chart: shown in the calls ([Kind.CALLS]) or callers ([Kind.CALLERS]) of the step [via]. */
data class FlowAttachment(val via: Int, val kind: Kind) {
    enum class Kind { CALLS, CALLERS }
}

/**
 * Theme-aware Java2D rendering of a [FlowBoard]: functions placed on the chart, each a column laid out by
 * [layoutFlowChart], explored from any step. The pill below a step shows or hides what it calls; the pill above it
 * shows or hides its direct callers, so the chart grows in both directions on the same canvas. Click a step to select
 * it, double-click or Enter to open it, click a back reference to jump to its step. Drag pans; Ctrl/Cmd + wheel zooms;
 * Up/Down step through the chart; Left toggles callers, Right or Space toggles calls, Delete removes a column; Page
 * Up/Down or Alt+Left/Right step through the beats of a chat story.
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
    private val onToChat: ((FlowChatTarget) -> Unit)? = null,
) : JPanel() {
    var board = FlowBoard()
        private set
    private var source: FlowSource? = null
    var chart: FlowChart? = null
        private set
    private var message: String? = null
    internal var geometry: FlowGeometry? = null
        private set
    /** Group boxes, externals and snippets of the scene placed around [geometry]; see [layoutSceneOverlay]. */
    internal var overlay: SceneOverlay? = null
        private set
    private var scale = 1.0
    internal var searchMatches: Set<Int>? = null
        private set
    private var regionMatches: Set<String> = emptySet()
    /** The assistant's current picture: emphasis, badges, notes and typed links; see [ChartScene]. */
    var scene = ChartScene()
        private set

    /** The guided tour whose current beat is the scene; any other scene change closes it. See [ChartStory]. */
    var story: ChartStory? = null
        private set(value) {
            val changed = field !== value
            field = value
            if (changed) onStoryChange()
        }
    /** Called when the story opens, steps or closes. */
    internal var onStoryChange: () -> Unit = {}
    /** A function emphasised while a chat reference to it is hovered; not part of the chart state. */
    internal var hoveredNode: Int? = null
        private set

    /** Replaces the scene, closing the story unless [story] is the one the scene belongs to; notes change card heights. */
    fun showScene(next: ChartScene, story: ChartStory? = null) {
        scene = next
        // The title caption is painted at the top of the viewport, so the viewport must not blit stale pixels.
        if (next.title != null) viewport?.scrollMode = JViewport.SIMPLE_SCROLL_MODE
        update(board)
        this.story = story
    }

    /** Shows the beat [delta] beats away; false at either end or without a story. */
    fun stepStory(delta: Int): Boolean = story?.let { showStoryBeat(this, it.index + delta) } == true

    /** Closes the story and keeps the current beat's scene. */
    fun closeStory() { story = null }

    fun hover(node: Int?) {
        if (hoveredNode == node) return
        hoveredNode = node
        repaint()
    }

    fun saveState() = ChartCanvasState(board, scene, selectedNode, story)

    fun restoreState(state: ChartCanvasState) {
        scene = state.scene
        update(state.board)
        story = state.story
        state.selected?.let(::selectNode) ?: select(null)
    }

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
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), stroke)
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
        // Disabled without a story, so the scroll pane keeps Page Up/Down then.
        for ((stroke, delta) in listOf("PAGE_DOWN" to 1, "alt RIGHT" to 1, "PAGE_UP" to -1, "alt LEFT" to -1)) {
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), "story $stroke")
            actionMap.put("story $stroke", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) { stepStory(delta) }
                override fun isEnabled() = story != null
            })
        }
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
        // Explanations are source-dependent; do not carry them into a rebuilt graph.
        scene = scene.map(id)
        overlay = null
        story = null
        hoveredNode = null
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

    fun setColumn(start: FlowStart) {
        val starts = if (board.starts.any { it.node == start.node }) {
            board.starts.map { existing ->
                if (existing.node == start.node) {
                    existing.copy(title = start.title ?: existing.title, description = start.description ?: existing.description)
                } else existing
            }
        } else {
            board.starts + start
        }
        update(board.copy(starts = starts, openCalls = board.openCalls + start.node))
    }

    fun resetBoard(starts: List<FlowStart> = emptyList()) {
        update(FlowBoard(starts = starts.distinctBy { it.node }, openCalls = starts.mapTo(hashSetOf()) { it.node }, compact = board.compact))
    }

    /**
     * Shows [node] as a continuation of the chart rather than a column of its own: below a visible step that calls it
     * (opening that step's calls or the compact subflow holding the call), or above a visible step it calls (opening
     * that step's callers). Earlier steps are tried first. Returns how it was attached, or null when no visible step
     * can show it, for example when it is unconnected or folded as a helper; the board is then unchanged.
     */
    fun attach(node: Int): FlowAttachment? {
        val source = source ?: return null
        val chart = chart ?: return null
        if (chart.step(node) != null) return null
        fun visible(ids: Iterable<Int>) = ids.distinct().mapNotNull(chart::step).sortedBy { it.number }.map { it.node }
        val candidates = chart.regions.filter { !it.expanded && it.plan.evidence.sites.any { site -> site.callee == node } }
            .map { FlowAttachment(it.plan.evidence.owner, FlowAttachment.Kind.CALLS) to
                board.copy(openRegions = board.openRegions + it.plan.evidence.key) } +
            visible(source.callers[node].orEmpty()).map { FlowAttachment(it, FlowAttachment.Kind.CALLS) to
                board.copy(openCalls = board.openCalls + it) } +
            visible(source.calls[node].orEmpty().map { it.callee }).map { FlowAttachment(it, FlowAttachment.Kind.CALLERS) to
                board.copy(openCallers = board.openCallers + it) }
        val (attachment, next) = candidates.asSequence().filter { (_, next) -> next != board }.take(MAX_ATTACH_TRIES)
            .firstOrNull { (_, next) -> buildFlowChart(next, source).step(node) != null } ?: return null
        update(next)
        selectNode(node)
        return attachment
    }

    /** Shows what each of [nodes] calls, wherever its step is. */
    fun openCalls(nodes: Set<Int>) {
        if (!board.openCalls.containsAll(nodes)) update(board.copy(openCalls = board.openCalls + nodes))
    }

    /** Takes the column of [node] off the chart; what is open elsewhere stays open. */
    fun removeColumn(node: Int) {
        if (board.starts.any { it.node == node }) update(board.copy(starts = board.starts.filter { it.node != node }))
    }

    fun clear() {
        scene = ChartScene()
        story = null
        update(FlowBoard(compact = board.compact))
    }

    fun setCompact(compact: Boolean) = update(board.copy(compact = compact))

    fun collapseRegions() = update(board.copy(openRegions = emptySet()))

    fun toggleRegion(key: String) = update(board.copy(
        openRegions = if (key in board.openRegions) board.openRegions - key else board.openRegions + key))

    internal fun groupingBlocks(owner: Int): List<FlowRegionEvidence> = source?.regionCandidates(owner).orEmpty()

    /** Applies an already validated replacement for one block; other blocks retain their interpretations. */
    internal fun groupCalls(block: FlowRegionEvidence, plans: List<FlowRegionPlan>) {
        // Enabling compact mode must keep the owning function reachable on the board.
        fun pathToOwner(item: FlowItem): Set<Int>? {
            if (item is FlowStep && item.node == block.owner) return setOf(item.node)
            val path = ((item as? FlowStep)?.upstream.orEmpty() + children(item)).firstNotNullOfOrNull(::pathToOwner)
                ?: return null
            return if (item is FlowStep) path + item.node else path
        }
        val path = if (board.compact) emptySet() else chart?.lanes?.firstNotNullOfOrNull { pathToOwner(it.root) }.orEmpty()
        val oldKeys = chart?.regions.orEmpty().filter { region ->
            region.plan.evidence.owner == block.owner && region.plan.evidence.sites.any { it in block.sites }
        }.map { it.plan.evidence.key }.toSet() + plans.map { it.evidence.key }
        var next = board.copy(compact = true, openCalls = board.openCalls + block.owner,
            openRegions = board.openRegions - oldKeys,
            regionOverrides = board.regionOverrides + (block.key to plans))
        val source = source
        if (!board.compact && source != null) {
            while (true) {
                val preview = buildFlowChart(next, source)
                if (preview.step(block.owner) != null) break
                val ancestors = preview.regions.filter { !it.expanded && it.plan.evidence.sites.any { site -> site.callee in path } }
                    .map { it.plan.evidence.key }
                if (ancestors.isEmpty()) {
                    // A helper folded by compact mode can still be explored as an explicit column root.
                    next = next.copy(starts = next.starts + FlowStart(block.owner))
                    break
                }
                next = next.copy(openRegions = next.openRegions + ancestors)
            }
        }
        update(next)
    }

    internal fun setRegionsExpanded(keys: Set<String>, expanded: Boolean) = update(board.copy(
        openRegions = if (expanded) board.openRegions + keys else board.openRegions - keys))

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
        overlay = geometry?.let(::layoutOverlay)
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

    fun fitScene() {
        val cardBounds = scene.nodes.mapNotNull { geometry?.cards?.get(it.node)?.bounds }
        val allBounds = overlay?.bounds?.let { cardBounds + it } ?: cardBounds
        val union = allBounds.reduceOrNull { a, b -> a.union(b) } ?: return
        val area = Rectangle(union).apply { grow(JBUI.scale(48), JBUI.scale(48)) }
        val extent = viewport?.extentSize?.takeIf { it.width > 0 && it.height > 0 }
        if (extent != null && area.width > 0 && area.height > 0) {
            zoomAt(minOf(1.0, extent.width.toDouble() / area.width, extent.height.toDouble() / area.height))
            moveViewport(Point((area.centerX * zoom - extent.width / 2.0).roundToInt(),
                (area.centerY * zoom - extent.height / 2.0).roundToInt()))
        } else {
            scrollRectToVisible(Rectangle((area.x * zoom).toInt(), (area.y * zoom).toInt(), (area.width * zoom).toInt(), (area.height * zoom).toInt()))
        }
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
        geometry?.size?.let { size ->
            val extra = overlay?.bounds
            val width = max(size.width, (extra?.maxX?.toInt() ?: 0) + JBUI.scale(28))
            val height = max(size.height, (extra?.maxY?.toInt() ?: 0) + JBUI.scale(28))
            Dimension((width * zoom).toInt(), (height * zoom).toInt())
        } ?: Dimension(0, 0)

    /** The interactive shape under a point in component coordinates. */
    private fun shapeAt(point: Point): FlowShape? {
        val x = (point.x / zoom).toInt()
        val y = (point.y / zoom).toInt()
        val reach = JBUI.scale(8)
        return geometry?.shapes?.lastOrNull { shape ->
            when (shape) {
                is FlowCardBox -> shape.bounds.contains(x, y) || shape.dot.distance(x.toDouble(), y.toDouble()) <= reach
                is FlowRegionBox -> shape.bounds.contains(x, y) || shape.dot.distance(x.toDouble(), y.toDouble()) <= reach
                is FlowPill -> shape.bounds.contains(x, y)
                is FlowRefBox -> shape.bounds.contains(x, y) || shape.dot.distance(x.toDouble(), y.toDouble()) <= reach
                is FlowDecisionBox -> shape.bounds.contains(x, y)
                is FlowBanner -> shape.bounds.contains(x, y)
                is FlowSegment -> java.awt.geom.Line2D.ptSegDist(shape.from.x.toDouble(), shape.from.y.toDouble(),
                    shape.to.x.toDouble(), shape.to.y.toDouble(), point.x / zoom, point.y / zoom) <= JBUI.scale(5) / zoom
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

    internal fun chatTargetAt(point: Point): FlowChatTarget? {
        val shape = shapeAt(point) ?: return null
        val target = geometry?.chatTargets?.get(shape) ?: return null
        return if (shape is FlowSegment) target.copy(kind = "connector",
            label = "${target.label.orEmpty()} · ${shape.style.name.lowercase()} connector") else target
    }

    /** Used by the platform context-menu gesture. */
    internal fun contextMenuAt(point: Point): JPopupMenu? {
        val shape = shapeAt(point)
        val region = (shape as? FlowRegionBox)?.region
        val node = when (shape) {
            is FlowCardBox -> shape.step.node
            is FlowRefBox -> shape.ref.node
            else -> null
        }
        val menu = if (region != null) JPopupMenu().apply {
            add(JMenuItem(if (region.expanded) "Collapse subflow" else "Expand subflow").apply {
                addActionListener { toggleRegion(region.plan.evidence.key) }
            })
            add(JMenuItem("Open owning function").apply { addActionListener { onOpen(region.plan.evidence.owner) } })
            region.plan.evidence.sites.forEach { site ->
                add(JMenuItem("Open ${source?.card?.invoke(site.callee)?.title} · offset ${site.offset}").apply {
                    addActionListener { onOpen(site.callee) }
                })
            }
        } else popup?.invoke(node) ?: JPopupMenu()
        if (shape is FlowPill) {
            shape.toggle?.let { id ->
                menu.add(JMenuItem(if (shape.callers) "Toggle callers" else "Toggle calls").apply {
                    addActionListener { if (shape.callers) toggleCallers(id) else toggleCalls(id) }
                })
            }
            shape.regionKey?.let { key ->
                menu.add(JMenuItem("Toggle subflow").apply { addActionListener { toggleRegion(key) } })
            }
        }
        val target = chatTargetAt(point)
        if (target != null && onToChat != null) {
            menu.insert(JMenuItem("To chat").apply { addActionListener { onToChat.invoke(target) } }, 0)
        }
        return menu.takeIf { it.componentCount > 0 }
    }

    private fun showPopup(e: MouseEvent): Boolean {
        if (!e.isPopupTrigger || (popup == null && onToChat == null)) return false
        requestFocusInWindow()
        when (val shape = shapeAt(e.point)) {
            is FlowCardBox -> select(shape.step.node)
            is FlowRefBox -> select(shape.ref.node)
            else -> Unit
        }
        contextMenuAt(e.point)?.show(this, e.x, e.y)
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
            (listOf("<b>${StringUtil.escapeXmlEntities(card.title)}</b>") + (listOfNotNull(card.meta, card.tooltip ?: card.description, card.details,
                scene.noteOf[shape.step.node]?.let { "Assistant note: $it" }) +
                scene.links.filter { it.from == shape.step.node || it.to == shape.step.node }.map { link ->
                    "${if (link.kind == SceneLinkKind.CALL) "Static call" else "Assistant ${link.kind.name.lowercase()} link (explanation)"} " +
                        "${source?.card?.invoke(link.from)?.title ?: link.from} → ${source?.card?.invoke(link.to)?.title ?: link.to}" +
                        link.label?.let { ": $it" }.orEmpty() +
                        link.payload?.let { " [$it]" }.orEmpty()
                })
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
        else -> overlayTooltip(Point((event.point.x / zoom).toInt(), (event.point.y / zoom).toInt()))
    }

    private fun overlayTooltip(point: Point): String? {
        val overlay = overlay ?: return null
        val text = overlay.externals.lastOrNull { it.bounds.contains(point) }?.external?.let { external ->
            listOf("${external.label} (${external.kind.name.lowercase()})", if (external.verified) EXTERNAL_VERIFIED else EXTERNAL_UNVERIFIED) +
                external.connected.map { link -> "${link.direction.name.lowercase()}: ${source?.card?.invoke(link.node)?.title ?: link.node}" +
                    link.label?.let { " · $it" }.orEmpty() }
        } ?: overlay.snippets.lastOrNull { it.bounds.contains(point) }?.snippet?.let { snippet ->
            listOfNotNull("${snippet.path}:${snippet.startLine}-${snippet.endLine}", snippet.caption,
                "Source captured when the assistant showed it; it may have changed since.")
        } ?: return null
        return text.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
    }

    private class TextLine(val text: String, val font: Font, val color: Color)

    /** The card's lines at [width]: step number and meta, title, description and details, each wrapped and capped. */
    private fun cardLines(step: FlowStep, width: Int): List<TextLine> = buildList {
        val card = step.card
        if (card.entryPoint) add(TextLine("▶ START · Entry point", metaFont.deriveFont(Font.BOLD), startColor))
        val cardMeta = if (card.entryPoint) card.meta?.removePrefix("▶ entry point")?.removePrefix(" · ")?.takeIf { it.isNotBlank() } else card.meta
        listOfNotNull("Step ${step.number}".takeIf { step.number > 0 }, cardMeta).joinToString(" · ").let { meta -> wrap(meta, getFontMetrics(metaFont), width, 2).forEach { add(TextLine(it, metaFont, accent)) } }
        wrap(card.title, getFontMetrics(titleFont), width, 2).forEach { add(TextLine(it, titleFont, JBColor.foreground())) }
        card.description?.let { text -> wrap(text, getFontMetrics(textFont), width, 3).forEach { add(TextLine(it, textFont, mutedText)) } }
        card.details?.let { text -> wrap(text, getFontMetrics(smallFont), width, 2).forEach { add(TextLine(it, smallFont, mutedText)) } }
        scene.noteOf[step.node]?.let { text ->
            val color = scene.roles[step.node]?.role?.let(::roleColor) ?: accent
            wrap("Note: $text", getFontMetrics(textFont), width, 8).forEach { add(TextLine(it, textFont, color)) }
        }
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
            val reach = JBUI.scale(20)
            val dimming = scene.dimOthers && scene.nodes.isNotEmpty()
            val dimmed = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f)
            val normal = canvas.composite
            // Group boxes and externals belong to the scene, so they are never dimmed; boxes stay behind everything.
            val overlay = overlay
            overlay?.groups?.forEach { paintGroup(canvas, it) }
            paintSceneLinks(canvas, geometry, chips = null)
            overlay?.let { paintConnectors(canvas, geometry, it, chips = null) }
            for (shape in geometry.shapes) {
                if (clip != null && !clip.intersects(Rectangle(bounds(shape)).apply { grow(reach, reach) })) continue
                canvas.composite = if (dimming && !inScene(shape, geometry)) dimmed else normal
                when (shape) {
                    is FlowFrame -> paintFrame(canvas, shape)
                    is FlowSegment -> paintSegment(canvas, shape)
                    is FlowDot -> {
                        paintDot(canvas, shape.center, shape.step.card.color ?: accent, shape.step.node == selectedNode, hollow = false)
                        if (shape.step.card.entryPoint) paintStartRing(canvas, shape.center)
                    }
                    is FlowCardBox -> paintCard(canvas, shape)
                    is FlowRegionBox -> {
                        val presentation = shape.region.presentation()
                        paintDot(canvas, shape.dot, accent, shape.region.plan.evidence.key == selectedRegion, hollow = true)
                        paintCard(canvas, FlowCardBox(presentation, shape.bounds, shape.leftOfSpine, shape.dot),
                            selected = shape.region.plan.evidence.key == selectedRegion,
                            matching = shape.region.plan.evidence.key in regionMatches, role = null)
                    }
                    is FlowPill -> paintPill(canvas, shape)
                    is FlowDecisionBox -> paintDecision(canvas, shape)
                    is FlowRefBox -> paintRef(canvas, shape)
                    is FlowBanner -> paintBanner(canvas, shape)
                }
            }
            canvas.composite = normal
            val chips = ChipSpace(overlay?.solid.orEmpty().toMutableList(), chartObstacles(geometry))
            overlay?.snippets?.forEach { paintSnippet(canvas, it) }
            overlay?.externals?.forEach { paintExternal(canvas, it) }
            paintSceneLinks(canvas, geometry, chips)
            overlay?.let { paintConnectors(canvas, geometry, it, chips) }
        } finally { canvas.dispose() }
        if (story == null) scene.title?.let { paintCaption(g, it) }
    }

    /** Whether [shape] belongs to the scene: its cards, dots and references, and connectors inside scene functions. */
    private fun inScene(shape: FlowShape, geometry: FlowGeometry): Boolean = when (shape) {
        is FlowCardBox -> shape.step.node in scene.roles || shape.step.node == hoveredNode
        is FlowDot -> shape.step.node in scene.roles
        is FlowRefBox -> shape.ref.node in scene.roles
        is FlowBanner -> true
        is FlowRegionBox -> shape.region.plan.evidence.owner in scene.roles || shape.region.plan.evidence.sites.any { it.callee in scene.roles }
        else -> geometry.chatTargets[shape]?.owner?.let { it in scene.roles } == true
    }

    /** Typed scene links: without [chips] the arrows behind cards, with it their label chips on top, avoiding [chips]. */
    private fun paintSceneLinks(g: Graphics2D, geometry: FlowGeometry, chips: ChipSpace?) {
        val previous = g.stroke
        for (link in scene.links) {
            val from = geometry.cards[link.from]?.bounds ?: continue
            val to = geometry.cards[link.to]?.bounds ?: continue
            val color = linkColor(link.kind)
            // Cards stacked in one column are joined on their right edges with a bulge, so the arrow is not hidden.
            val stacked = kotlin.math.abs(from.centerX - to.centerX) < from.width / 2.0
            val down = to.centerY >= from.centerY
            val (x1, y1) = if (stacked) from.maxX to from.centerY else from.centerX to if (down) from.maxY else from.minY
            val (x2, y2) = if (stacked) to.maxX to to.centerY else to.centerX to if (down) to.minY else to.maxY
            val bulge = JBUI.scale(48) + kotlin.math.abs(y2 - y1) / 8
            val path = Path2D.Double().apply {
                moveTo(x1, y1)
                if (stacked) curveTo(x1 + bulge, y1, x2 + bulge, y2, x2, y2) else lineTo(x2, y2)
            }
            val midX = if (stacked) maxOf(x1, x2) + bulge * 0.75 else (x1 + x2) / 2
            val midY = (y1 + y2) / 2
            if (chips == null) {
                g.color = color
                g.stroke = linkStroke(link.kind)
                g.draw(path)
                arrowHead(g, x2, y2, if (stacked) kotlin.math.atan2(0.0, -1.0) else kotlin.math.atan2(y2 - y1, x2 - x1))
                continue
            }
            val base = if (link.kind == SceneLinkKind.CALL) link.label ?: "call" else
                link.kind.name.lowercase() + link.label?.let { ": $it" }.orEmpty()
            val chipText = if (link.payload != null) "$base · [${link.payload}]" else base
            paintChip(g, chipText, color, midX, midY, chips)
        }
        g.stroke = previous
    }

    private fun arrowHead(g: Graphics2D, x: Double, y: Double, angle: Double) {
        g.stroke = BasicStroke(JBUIScale.scale(1.5f))
        val head = JBUI.scale(10).toDouble()
        for (delta in listOf(-0.5, 0.5)) g.draw(java.awt.geom.Line2D.Double(x, y,
            x - head * kotlin.math.cos(angle + delta), y - head * kotlin.math.sin(angle + delta)))
    }

    /**
     * A label chip centred at ([x], [y]), moved down past the snippets, externals and earlier chips of [space], and past
     * cards and pills too when that keeps it near its line.
     */
    private fun paintChip(g: Graphics2D, text: String, color: Color, x: Double, y: Double, space: ChipSpace) {
        g.font = smallFont
        val fm = g.fontMetrics
        val label = clipText(text, fm, JBUI.scale(180))
        val w = fm.stringWidth(label) + JBUI.scale(10)
        val h = fm.height + JBUI.scale(2)
        val candidate = Rectangle((x - w / 2.0).toInt(), (y - h / 2.0).toInt(), w, h)
        val spot = freeSpot(candidate, space.taken + space.soft, JBUI.scale(2)).takeIf { it.y - candidate.y <= h * 4 }
            ?: freeSpot(candidate, space.taken, JBUI.scale(2))
        space.taken += spot
        val chip = RoundRectangle2D.Double(spot.x.toDouble(), spot.y.toDouble(), w.toDouble(), h.toDouble(), h.toDouble(), h.toDouble())
        g.color = background
        g.fill(chip)
        g.color = color
        g.stroke = BasicStroke(JBUIScale.scale(1f))
        g.draw(chip)
        g.drawString(label, spot.x + (w - fm.stringWidth(label)) / 2, spot.y + (h - fm.height) / 2 + fm.ascent)
    }

    private val monoFont get() = Font(Font.MONOSPACED, Font.PLAIN, smallFont.size)

    /** Snippet lines with tabs expanded and their common indentation removed. */
    private fun snippetCode(snippet: SceneSnippet): List<String> {
        val lines = snippet.lines.orEmpty().map { it.replace("\t", "    ") }
        val indent = lines.filter { it.isNotBlank() }.minOfOrNull { it.length - it.trimStart().length } ?: 0
        return lines.map { it.drop(indent) }
    }

    private fun layoutOverlay(geometry: FlowGeometry): SceneOverlay? {
        if (scene.groups.isEmpty() && scene.externals.isEmpty() && scene.snippets.isEmpty()) return null
        val small = getFontMetrics(smallFont)
        val pad = JBUI.scale(6)
        return layoutSceneOverlay(scene, geometry, JBUI.scale(8), JBUI.scale(8), small.height + JBUI.scale(2),
            { snippet -> Dimension(JBUI.scale(340), pad * 2 + small.height * (if (snippet.caption != null) 2 else 1) +
                getFontMetrics(monoFont).height * snippet.lines.orEmpty().size) },
            { external -> Dimension((maxOf(small.stringWidth(externalTag(external)), getFontMetrics(smallFont.deriveFont(Font.BOLD))
                .stringWidth(external.label)) + pad * 4).coerceIn(JBUI.scale(110), JBUI.scale(220)),
                small.height * 2 + pad * 2 + if (external.kind == ExternalKind.DATABASE) JBUI.scale(8) else 0) })
    }

    private fun externalTag(external: SceneExternal) = external.kind.name.lowercase() + if (external.verified) " · rule effects" else " · explanation"

    private fun paintGroup(g: Graphics2D, box: GroupBox) {
        val group = scene.groups.getOrNull(box.group) ?: return
        val color = groupPalette[box.group % groupPalette.size]
        val r = box.bounds
        val shape = RoundRectangle2D.Double(r.x.toDouble(), r.y.toDouble(), r.width.toDouble(), r.height.toDouble(),
            JBUI.scale(18).toDouble(), JBUI.scale(18).toDouble())
        g.color = ColorUtil.withAlpha(color, 0.08)
        g.fill(shape)
        g.color = ColorUtil.withAlpha(color, 0.35)
        g.stroke = BasicStroke(JBUIScale.scale(1f))
        g.draw(shape)
        g.font = smallFont.deriveFont(Font.BOLD)
        g.color = color
        val fm = g.fontMetrics
        val text = clipText(group.label + group.kind?.let { " · ${it.name.lowercase()}" }.orEmpty(), fm, r.width - JBUI.scale(16))
        g.drawString(text, r.x + JBUI.scale(8), r.y + JBUI.scale(3) + fm.ascent)
    }

    /** Dashed connectors between externals and their cards: without [chips] the lines, with it their label chips. */
    private fun paintConnectors(g: Graphics2D, geometry: FlowGeometry, overlay: SceneOverlay, chips: ChipSpace?) {
        for (box in overlay.externals) {
            val color = externalColor(box.external.kind)
            for (link in box.external.connected) {
                val card = geometry.cards[link.node]?.bounds ?: continue
                val (x1, y1) = card.maxX to card.centerY
                val (x2, y2) = box.bounds.x.toDouble() to box.bounds.centerY
                if (chips != null) {
                    link.label?.let { paintChip(g, it, color, (x1 + x2) / 2, (y1 + y2) / 2, chips) }
                    continue
                }
                g.color = color
                g.stroke = linkStroke(SceneLinkKind.DATA)
                g.draw(java.awt.geom.Line2D.Double(x1, y1, x2, y2))
                val angle = kotlin.math.atan2(y2 - y1, x2 - x1)
                if (link.direction != ExternalDirection.IN) arrowHead(g, x2, y2, angle)
                if (link.direction != ExternalDirection.OUT) arrowHead(g, x1, y1, angle + Math.PI)
            }
        }
    }

    /** A cylinder for databases, a pill for other systems; dashed while it is an explanation. */
    private fun paintExternal(g: Graphics2D, box: ExternalBox) {
        val external = box.external
        val color = externalColor(external.kind)
        val r = box.bounds
        val shape: java.awt.Shape = if (external.kind == ExternalKind.DATABASE) {
            val cap = JBUI.scale(8).toDouble()
            Path2D.Double().apply {
                moveTo(r.x.toDouble(), r.y + cap / 2)
                curveTo(r.x.toDouble(), r.y - cap / 6, r.maxX, r.y - cap / 6, r.maxX, r.y + cap / 2)
                lineTo(r.maxX, r.maxY - cap / 2)
                curveTo(r.maxX, r.maxY + cap / 6, r.x.toDouble(), r.maxY + cap / 6, r.x.toDouble(), r.maxY - cap / 2)
                closePath()
                moveTo(r.x.toDouble(), r.y + cap / 2)
                curveTo(r.x.toDouble(), r.y + cap * 7 / 6, r.maxX, r.y + cap * 7 / 6, r.maxX, r.y + cap / 2)
            }
        } else RoundRectangle2D.Double(r.x.toDouble(), r.y.toDouble(), r.width.toDouble(), r.height.toDouble(), r.height.toDouble(), r.height.toDouble())
        g.color = background
        g.fill(shape)
        g.color = ColorUtil.withAlpha(color, 0.14)
        g.fill(shape)
        g.color = color
        g.stroke = if (external.verified) BasicStroke(JBUIScale.scale(1.5f)) else dashed(1.2f)
        g.draw(shape)
        val fm = getFontMetrics(smallFont)
        val top = r.y + (r.height - fm.height * 2) / 2 + if (external.kind == ExternalKind.DATABASE) JBUI.scale(3) else 0
        val width = r.width - JBUI.scale(16)
        for ((i, line) in listOf(externalTag(external) to smallFont, external.label to smallFont.deriveFont(Font.BOLD)).withIndex()) {
            g.font = line.second
            g.color = if (i == 0) mutedText else JBColor.foreground()
            val text = clipText(line.first, g.fontMetrics, width)
            g.drawString(text, r.x + (r.width - g.fontMetrics.stringWidth(text)) / 2, top + fm.height * i + g.fontMetrics.ascent)
        }
    }

    /** A monospace callout with file, line numbers and the captured lines, joined to its card by a thin pointer. */
    private fun paintSnippet(g: Graphics2D, box: SnippetBox) {
        val r = box.bounds
        val snippet = box.snippet
        val color = scene.roles[snippet.node]?.role?.let(::roleColor) ?: accent
        val anchorY = box.card.y + JBUI.scale(10)
        g.color = ColorUtil.withAlpha(color, 0.6)
        g.stroke = BasicStroke(JBUIScale.scale(1f))
        val cardX = if (box.left) box.card.x else box.card.x + box.card.width
        val boxX = if (box.left) r.x + r.width else r.x
        g.drawLine(cardX, anchorY, boxX, r.y + JBUI.scale(10))
        g.fillOval(cardX - JBUI.scale(3), anchorY - JBUI.scale(3), JBUI.scale(6), JBUI.scale(6))
        val shape = RoundRectangle2D.Double(r.x.toDouble(), r.y.toDouble(), r.width.toDouble(), r.height.toDouble(),
            JBUI.scale(8).toDouble(), JBUI.scale(8).toDouble())
        g.color = snippetFill
        g.fill(shape)
        g.color = ColorUtil.withAlpha(color, 0.6)
        g.draw(shape)
        val pad = JBUI.scale(6)
        var y = r.y + pad
        g.font = smallFont
        g.color = mutedText
        val small = g.fontMetrics
        val header = "${snippet.path?.substringAfterLast('/')}:${snippet.startLine}" +
            (if (snippet.endLine > snippet.startLine) "-${snippet.endLine}" else "")
        g.drawString(clipText(header, small, r.width - pad * 2), r.x + pad, y + small.ascent)
        y += small.height
        snippet.caption?.let { caption ->
            g.font = smallFont.deriveFont(Font.BOLD)
            g.color = JBColor.foreground()
            g.drawString(clipText(caption, g.fontMetrics, r.width - pad * 2), r.x + pad, y + g.fontMetrics.ascent)
            y += small.height
        }
        g.font = monoFont
        val mono = g.fontMetrics
        val gutter = mono.stringWidth("${snippet.endLine}") + pad
        for ((i, line) in snippetCode(snippet).withIndex()) {
            g.color = mutedText
            val number = "${snippet.startLine + i}"
            g.drawString(number, r.x + pad + gutter - pad - mono.stringWidth(number), y + mono.ascent)
            g.color = JBColor.foreground()
            g.drawString(clipText(line, mono, r.width - pad * 2 - gutter), r.x + pad + gutter, y + mono.ascent)
            y += mono.height
        }
    }

    private fun linkStroke(kind: SceneLinkKind): BasicStroke {
        fun dash(vararg pattern: Float) = BasicStroke(JBUIScale.scale(1.6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
            pattern.map { JBUIScale.scale(it) }.toFloatArray(), 0f)
        return when (kind) {
            SceneLinkKind.CALL -> BasicStroke(JBUIScale.scale(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            SceneLinkKind.DATA -> dash(7f, 4f)
            SceneLinkKind.EVENT -> dash(2f, 4f)
            SceneLinkKind.CALLBACK -> dash(8f, 3f, 2f, 3f)
            SceneLinkKind.ASYNC -> dash(12f, 6f)
        }
    }

    /** The scene title as a small caption pinned to the top of the visible area. */
    private fun paintCaption(g: Graphics, title: String) {
        val canvas = g.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            canvas.font = metaFont.deriveFont(Font.BOLD)
            val fm = canvas.fontMetrics
            val visible = visibleRect.takeIf { it.width > 0 } ?: Rectangle(0, 0, width, height)
            val pad = JBUI.scale(8)
            val text = clipText(title, fm, max(JBUI.scale(80), visible.width - pad * 6))
            val box = RoundRectangle2D.Double((visible.x + pad).toDouble(), (visible.y + pad).toDouble(),
                (fm.stringWidth(text) + pad * 2).toDouble(), (fm.height + pad).toDouble(), pad * 1.5, pad * 1.5)
            canvas.color = ColorUtil.withAlpha(background, 0.92)
            canvas.fill(box)
            canvas.color = ColorUtil.withAlpha(accent, 0.6)
            canvas.stroke = BasicStroke(JBUIScale.scale(1f))
            canvas.draw(box)
            canvas.color = JBColor.foreground()
            canvas.drawString(text, (box.x + pad).toFloat(), (box.y + pad / 2.0 + fm.ascent).toFloat())
        } finally { canvas.dispose() }
    }

    private fun paintBadge(g: Graphics2D, text: String, color: Color, center: Point) {
        g.font = smallFont.deriveFont(Font.BOLD)
        val fm = g.fontMetrics
        val radius = max(JBUI.scale(9), fm.stringWidth(text) / 2 + JBUI.scale(4))
        g.color = color
        g.fillOval(center.x - radius, center.y - radius, radius * 2, radius * 2)
        g.color = background
        g.stroke = BasicStroke(JBUIScale.scale(1.5f))
        g.drawOval(center.x - radius, center.y - radius, radius * 2, radius * 2)
        g.drawString(text, center.x - fm.stringWidth(text) / 2, center.y - fm.height / 2 + fm.ascent)
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

    private fun paintStartRing(g: Graphics2D, center: Point) {
        val radius = JBUI.scale(10)
        g.color = startColor
        g.stroke = BasicStroke(JBUIScale.scale(2f))
        g.drawOval(center.x - radius, center.y - radius, radius * 2, radius * 2)
    }

    private fun paintCard(g: Graphics2D, box: FlowCardBox, selected: Boolean = box.step.node == selectedNode,
        matching: Boolean = searchMatches?.contains(box.step.node) == true, role: SceneNode? = scene.roles[box.step.node]) {
        val bounds = box.bounds
        val pad = JBUI.scale(4)
        if (selected || matching || role != null) {
            val arc = JBUI.scale(10).toDouble()
            val shape = RoundRectangle2D.Double((bounds.x - pad).toDouble(), (bounds.y - pad).toDouble(), (bounds.width + pad * 2).toDouble(),
                (bounds.height + pad * 2).toDouble(), arc, arc)
            g.color = when {
                matching -> searchFill
                role != null -> ColorUtil.withAlpha(roleColor(role.role), if (role.role == SceneRole.CONTEXT) 0.07 else 0.14)
                else -> selectionFill
            }
            g.fill(shape)
            g.color = role?.let { roleColor(it.role) } ?: ColorUtil.withAlpha(accent, 0.6)
            g.stroke = if (role?.role == SceneRole.CONTEXT) dashed(1f)
                else BasicStroke(JBUIScale.scale(if (role != null) 2f else 1f))
            g.draw(shape)
        }
        if (box.step.node == hoveredNode) {
            val ring = JBUI.scale(8)
            g.color = ColorUtil.withAlpha(accent, 0.18)
            g.fill(RoundRectangle2D.Double((bounds.x - ring).toDouble(), (bounds.y - ring).toDouble(), (bounds.width + ring * 2).toDouble(),
                (bounds.height + ring * 2).toDouble(), JBUI.scale(14).toDouble(), JBUI.scale(14).toDouble()))
            g.color = accent
            g.stroke = BasicStroke(JBUIScale.scale(3f))
            g.drawRoundRect(bounds.x - ring, bounds.y - ring, bounds.width + ring * 2, bounds.height + ring * 2, JBUI.scale(14), JBUI.scale(14))
        }
        if (box.step.card.entryPoint) {
            val pad = JBUI.scale(3)
            g.color = startColor
            g.stroke = BasicStroke(JBUIScale.scale(2f))
            g.drawRoundRect(bounds.x - pad, bounds.y - pad, bounds.width + pad * 2, bounds.height + pad * 2,
                JBUI.scale(8), JBUI.scale(8))
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
        val badge = role?.badge ?: return
        // On the outer top corner, away from the spine dot.
        paintBadge(g, badge, roleColor(role.role), Point(if (box.leftOfSpine) bounds.x - pad else bounds.x + bounds.width + pad, bounds.y - pad))
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
        val start = source?.card?.invoke(box.ref.node)?.entryPoint == true
        paintDot(g, box.dot, mutedText, selected = false, hollow = true)
        if (start) paintStartRing(g, box.dot)
        g.font = smallFont
        g.color = if (start) startColor else mutedText
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

/** Where label chips go: [taken] they must avoid (overlay elements, earlier chips), [soft] they avoid when nearby. */
internal class ChipSpace(val taken: MutableList<Rectangle>, val soft: List<Rectangle>)
