package dev.insight.review.wall

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.links.SymbolCandidate
import dev.insight.links.SymbolHints
import dev.insight.links.SymbolMention
import dev.insight.review.ReportLink
import dev.insight.review.ui.ContextualLinkedTextNavigator
import dev.insight.review.ui.LinkedTextNavigator
import dev.insight.review.ui.LinkedTextPane
import dev.insight.review.ui.ReviewTab
import dev.insight.review.ui.openReportFile
import dev.insight.ui.pixelBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.event.ChangeListener
import javax.swing.text.JTextComponent
import javax.swing.text.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A wall page: one custom-painted block per file on a pannable, zoomable surface, as a diff's `git diff` rows or a source file's
 * numbered lines. Blocks drag anywhere and come to front; their positions last while the scope stays the same, so the one [card]
 * is reused across canvas rebuilds. Painting rather than embedding editors keeps a wall of many files light; positions are kept at
 * 100% and painted at the zoom, with the editor font scaled, so text stays crisp and hit-testing stays plain Swing.
 */
internal class WallView(
    /** The client's words and component names. */
    private val labels: WallLabels,
    /** Opens the file at the block's path in the client's own view, at the line when one was double-clicked. */
    private val openLine: (path: String, line: WallLine?) -> Unit,
    /** Shown as a link when the wall holds a subset of the review's files. */
    private val showAll: () -> Unit,
    /** Each file's chats, opened from the Chat tab on its block's right edge; without them blocks have no tab. */
    private val chats: WallChats? = null,
    /** Colours the blocks' rows with the IDE's syntax highlighting; without it rows paint in plain colours. */
    private val highlighter: WallHighlighter? = null,
    /** Opens the file at a block's path on a canvas session; without it the block's "..." menu has no such item. */
    private val openOnCanvas: ((path: String) -> Unit)? = null,
    /** Called with a block, the row under the pointer (null on the header, padding or summary) and the point when a block is right-clicked; without it right-click does nothing. */
    private val onContext: ((block: WallBlock, line: WallLine?, point: RelativePoint) -> Unit)? = null,
    /**
     * Asks for the summary of the file at a path that has none, when its Summary switch is pressed; true when it is being written,
     * and then arrives, or leaves [WallContent.summarizing], in a later [show]. Without it only files with a summary have the switch.
     */
    private val summarize: ((path: String) -> Boolean)? = null,
    /** Multiplier for the shared card width at 100% zoom. */
    private val cardWidthScale: Double = 1.0,
    /** Number of blocks loaded per chunk onto the wall so large scopes stay responsive. */
    internal val loadChunk: Int = WALL_LOAD_CHUNK,
) : Disposable {
    private var project: Project? = null
    private var externalScope: CoroutineScope? = null
    private var ownedScope: CoroutineScope? = null
    private val textScope: CoroutineScope? get() = externalScope ?: ownedScope
    private var linkHost: WallLinkHost? = null
    private var canSummarize: ((path: String) -> Boolean)? = null
    private var onRemoveBlock: ((path: String) -> Unit)? = null

    internal constructor(
        labels: WallLabels,
        openLine: (path: String, line: WallLine?) -> Unit,
        showAll: () -> Unit,
        chats: WallChats?,
        highlighter: WallHighlighter?,
        openOnCanvas: ((path: String) -> Unit)?,
        onContext: ((block: WallBlock, line: WallLine?, point: RelativePoint) -> Unit)?,
        summarize: ((path: String) -> Boolean)?,
        cardWidthScale: Double,
        links: WallLinkSupport,
        loadChunk: Int = WALL_LOAD_CHUNK,
    ) : this(labels, openLine, showAll, chats, highlighter, openOnCanvas, onContext, summarize, cardWidthScale, loadChunk) {
        project = links.project
        externalScope = links.coroutineScope
        linkHost = links.linkHost
        canSummarize = links.canSummarize
        onRemoveBlock = links.onRemoveBlock
        ownedScope = if (links.project != null && links.coroutineScope == null) {
            CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        } else null
    }
    private val label = JLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBFont.small()
        toolTipText = labels.controls
    }
    private val zoomLabel = JLabel("100%").apply {
        name = "${labels.id}-zoom"
        foreground = ColorUtil.withAlpha(UIUtil.getContextHelpForeground(), 0.8)
        font = JBFont.small()
        toolTipText = "Zoom; click for 100% (${WALL_MENU_KEY}0)"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { zoomTo(1.0) }
        })
    }
    private val allLink = ActionLink(labels.showAll) { showAll() }.apply { font = JBFont.small() }
    /** Unfolds the wall's own chat about all its files; there only with [chats]. */
    private val canvasChatLink = ActionLink(labels.canvasChatLink) { toggleCanvasChat() }.apply {
        name = "${labels.id}-canvas-chat-link"
        font = JBFont.small()
        toolTipText = labels.canvasChatLinkTooltip
        isVisible = chats != null
    }
    /** How the blocks are laid out; the reader's last choice opens the next wall. */
    internal var mode: WallMode = loadMode()
        private set
    /** Switches between the cards and the skim column. */
    private val modeLink = ActionLink(modeLinkText()) { switchMode(if (mode == WallMode.SKIM) WallMode.CARDS else WallMode.SKIM) }.apply {
        name = "${labels.id}-mode"
        font = JBFont.small()
        toolTipText = modeLinkTooltip()
    }
    /** Loads the next chunk of files when a large wall is chunked. */
    private val loadMoreLink = ActionLink("") { loadMore() }.apply {
        name = "${labels.id}-load-more"
        font = JBFont.small()
        isVisible = false
    }
    private val surface = Surface()
    private val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
        isOpaque = false
        add(label)
        add(zoomLabel)
        add(modeLink)
        add(loadMoreLink)
        add(allLink)
        add(canvasChatLink)
    }
    val card: JPanel = JPanel(BorderLayout()).apply {
        name = labels.id
        background = UIUtil.getPanelBackground()
        add(header, BorderLayout.NORTH)
        add(surface, BorderLayout.CENTER)
    }
    init {
        if (mode == WallMode.SKIM) label.toolTipText = labels.skimControls
    }
    private var scope: WallScope? = null
    private var lastContent: WallContent? = null
    private var allPaths: List<String> = emptyList()
    private val loadedPaths = linkedSetOf<String>()
    private var shown: List<WallBlock> = emptyList()
    private var summaries: Map<String, String> = emptyMap()
    /** Files whose summary is being written; a block asking for one shows that until it arrives. */
    private var summarizing: Set<String> = emptySet()
    /** Blocks showing their summary in place of their rows. */
    private val summarized = mutableSetOf<String>()
    /** Paths among [summarized] currently materialized as rich HTML summary panes. */
    private var activeSummaryPaths: Set<String> = emptySet()
    /** The first summary row shown in each block that shows its summary. */
    private val summaryScrolls = mutableMapOf<String, Int>()
    /** Logical (100% zoom) vertical scroll offset of each rich Markdown summary, by path. */
    private val summaryScrollOffsets = mutableMapOf<String, Double>()
    /** Where each block sits at 100%, by path. */
    private val positions = mutableMapOf<String, Point>()
    /** Cached logical bounds recorded during layout, by path. */
    private val layoutRects = mutableMapOf<String, WallRect>()
    /** Paths whose remaining cut lines have been materialized on demand. */
    private val expandedLinePaths = mutableSetOf<String>()
    private val expandedLineBlocks = mutableMapOf<String, Pair<WallBlock, WallBlock>>()
    private var views: List<BlockView> = emptyList()
    private var moreView: MoreView? = null
    private var morePosition: Point? = null
    internal val totalFilesCount: Int get() = allPaths.size
    internal val remainingFilesCount: Int get() = (allPaths.size - shown.size).coerceAtLeast(0)
    /** Card width locked once blocks are arranged so newly added wider cards never overlap existing columns. */
    private var lockedCardWidth: Int? = null
    /** Path of the card whose text link is currently adding a new card, so the new card lands beside it. */
    private var pendingAnchorPath: String? = null
    /** Path and 0-based row indices currently highlighted after following a line/symbol link. */
    private var highlightedTarget: Pair<String, Set<Int>>? = null
    private var highlightClearTimer: Timer? = null
    /** Compact blocks by path, with the height (at 100%) each is capped at. */
    private val compact = mutableMapOf<String, Int>()
    /** Blocks the reader turned compact off for, which a pack must not make compact again. */
    private val expanded = mutableSetOf<String>()
    /** How far (at 100%) each compact block's body is scrolled. */
    private val scrolls = mutableMapOf<String, Int>()
    /** Horizontal body offsets at 100%, independent of the fixed headers. */
    private val horizontalScrolls = mutableMapOf<String, Int>()
    /** Blocks the skim column shows as their header alone. */
    private val folded = mutableSetOf<String>()
    /** Where the other mode left the wall, restored when the reader switches back within the same scope. */
    private val modeStates = mutableMapOf<WallMode, ModeState>()
    /** The scroll mode the page's viewport had before the skim column, whose sticky headers need whole repaints, took it over. */
    private var blitMode: Int? = null
    /** Set once the reader moves a block: the layout is theirs and viewport changes no longer repack it. */
    private var arranged = false
    /** The viewport size the blocks were last packed for; null asks for a pack. */
    private var packedFor: Dimension? = null
    private var zoom = 1.0
    /** Set once the reader zooms, so a later pack keeps their zoom. */
    private var zoomTouched = false
    private var activePath: String? = null
    private val activeBlock: BlockView? get() = views.firstOrNull { it.active }
    private val focusedBlock: BlockView? get() = if (overview != null) activeBlock else null
    /** The first Tab focuses the initially active block before navigation advances. */
    private var firstStep = true
    /** The zoom and view position from before card focus, restored by Escape. */
    private var overview: Pair<Double, Point>? = null
    private var clickZoom: Timer? = null
    private var animation: Timer? = null
    private var watched: JScrollPane? = null
    /** Each block's Chat tab, by path; empty without [chats]. */
    private var chatTabs: Map<String, ChatTab> = emptyMap()
    private val chatOpen = linkedSetOf<String>()
    /** Each file's chat window once opened, kept with its draft while folded or off the wall. */
    private val chatPanels = mutableMapOf<String, WallChatPanel>()
    private val chatSubscription = chats?.subscribe { path -> chatTabs[path]?.repaint() }
    /** The wall's own chat window once opened, kept with its draft while folded; it stays open across scopes. */
    private var canvasChatPanel: WallChatPanel? = null
    private var canvasChatOpen = false
    /** Keeps the wall's own chat window in the visible corner of the wall while the page scrolls. */
    private val scrolled = ChangeListener {
        if (canvasChatOpen) { placeCanvasChat(); canvasChatPanel?.repaint() }
        val prevActive = activePath
        if (mode == WallMode.SKIM) {
            placeFileChats()
            followScroll()
        }
        if (summarized.size > WALL_ACTIVE_SUMMARY_LIMIT && activePath == prevActive) reconcileActiveSummaries()
    }
    /** Each block's row colours once worked out, by path, with the block they were worked out for; null rows for a plain block. */
    private val highlights = mutableMapOf<String, Pair<WallBlock, List<List<WallSpan>?>?>>()
    /** Blocks whose row colours are being worked out, by path. */
    private val highlighting = mutableMapOf<String, WallBlock>()
    /** Bumped when the colour scheme changes, so colours worked out for the old one are dropped. */
    @Volatile private var highlightGeneration = 0
    @Volatile private var disposed = false
    private val schemeConnection = highlighter?.let {
        ApplicationManager.getApplication()?.messageBus?.connect()?.apply {
            subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
                ApplicationManager.getApplication().invokeLater({ if (!disposed) rehighlight() }, ModalityState.any())
            })
        }
    }
    private val resized = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            if (!arranged || mode == WallMode.SKIM) { surface.revalidate(); surface.repaint() }
        }
    }

    /** The blocks in the scope's file order. */
    internal val blocks: List<BlockView> get() = views

    /** The 0-based row indices currently emphasized for [path] after navigating to a link target. */
    internal fun highlightedRows(path: String): Set<Int> =
        if (highlightedTarget?.first == path) highlightedTarget!!.second else emptySet()

    private fun updateHeaderAndContinuation(wallScope: WallScope, shownCount: Int) {
        val total = allPaths.size
        val remaining = (total - shownCount).coerceAtLeast(0)
        if (remaining > 0) {
            label.text = "${labels.header(wallScope.title, total)} · $shownCount shown"
            loadMoreLink.text = wallLoadMoreHeaderText(shownCount, total, loadChunk)
            loadMoreLink.toolTipText = "Load up to $loadChunk more files onto the wall"
            loadMoreLink.isVisible = true
        } else {
            label.text = labels.header(wallScope.title, total)
            loadMoreLink.isVisible = false
        }
    }

    /** Shows [content]; a new scope relays the blocks out, the same scope keeps where they were moved. */
    fun show(content: WallContent) {
        lastContent = content
        val incomingAllPaths = content.allPaths
        val prevAllPaths = allPaths
        val scopeChanged = content.scope != scope
        val wasEmpty = prevAllPaths.isEmpty() && shown.isEmpty()
        val hadAllLoaded = prevAllPaths.isNotEmpty() && loadedPaths.size >= prevAllPaths.size && shown.size == prevAllPaths.size
        val currentAllSet = incomingAllPaths.toSet()

        if (scopeChanged) {
            stopAnimation()
            stopHighlightTimer()
            highlightedTarget = null
            positions.clear()
            layoutRects.clear()
            expandedLinePaths.clear()
            expandedLineBlocks.clear()
            morePosition = null
            lockedCardWidth = null
            pendingAnchorPath = null
            compact.clear()
            expanded.clear()
            scrolls.clear()
            horizontalScrolls.clear()
            summarized.clear()
            summaryScrolls.clear()
            summaryScrollOffsets.clear()
            activeSummaryPaths = emptySet()
            folded.clear()
            modeStates.clear()
            arranged = false
            zoomTouched = false
            setZoom(1.0)
            activePath = null
            firstStep = true
            overview = null
            loadedPaths.clear()
            // A new layout starts folded; each file's chats stay to be unfolded again.
            chatOpen.clear()
            // The canvas restores the page's previous scroll after its rebuild; a new layout starts at the top instead.
            SwingUtilities.invokeLater { SwingUtilities.invokeLater { viewport()?.viewPosition = Point(0, 0); focusWhenOpened() } }
        } else {
            loadedPaths.retainAll(currentAllSet)
            expandedLinePaths.retainAll(currentAllSet)
            expandedLineBlocks.keys.retainAll(currentAllSet)
        }

        if (scopeChanged || (wasEmpty && incomingAllPaths.isNotEmpty())) {
            loadedPaths.addAll(selectInitialWallPaths(incomingAllPaths, loadChunk))
        } else if (hadAllLoaded && incomingAllPaths.size <= loadChunk) {
            loadedPaths.addAll(incomingAllPaths)
        } else if (loadedPaths.isEmpty() && incomingAllPaths.isNotEmpty()) {
            loadedPaths.addAll(selectInitialWallPaths(incomingAllPaths, loadChunk))
        }

        val byPath = if (content.blockFor == null) content.blocks.associateBy { it.path } else emptyMap()
        val nextShown = incomingAllPaths.filter { it in loadedPaths }.mapNotNull { path ->
            val raw = content.blockFor?.invoke(path) ?: byPath[path] ?: return@mapNotNull null
            if (path in expandedLinePaths && raw.loadMore != null) {
                val cached = expandedLineBlocks[path]
                if (cached != null && cached.first == raw) cached.second
                else wallLoadMoreLines(raw).also { expandedLineBlocks[path] = raw to it }
            } else {
                raw
            }
        }
        allPaths = incomingAllPaths
        allLink.isVisible = content.scope.paths != null
        updateHeaderAndContinuation(content.scope, nextShown.size)

        if (!scopeChanged && nextShown == shown) {
            if (incomingAllPaths != prevAllPaths) {
                syncMoreViewAttachment()
                packedFor = null
                surface.revalidate()
                surface.repaint()
            }
            // Only summaries arrived or changed: blocks keep their size and place, and repaint with the new text.
            if (content.summaries != summaries || content.summarizing != summarizing) {
                summaries = content.summaries
                summarizing = content.summarizing
                forgetMissingSummaries()
                reconcileActiveSummaries(syncAll = true)
                if (mode == WallMode.SKIM) {
                    // Shown summaries take room in the skim column, so new text restacks it.
                    packedFor = null
                    surface.revalidate()
                }
                views.forEach { it.repaint() }
            }
            return
        }

        packedFor = null
        scope = content.scope
        shown = nextShown
        summaries = content.summaries
        summarizing = content.summarizing
        forgetMissingSummaries()
        val currentPaths = nextShown.map { it.path }.toSet()
        folded.retainAll(currentPaths)
        positions.keys.retainAll(currentPaths)
        layoutRects.keys.retainAll(currentPaths)
        compact.keys.retainAll(currentPaths)
        expanded.retainAll(currentPaths)
        scrolls.keys.retainAll(currentPaths)
        horizontalScrolls.keys.retainAll(currentPaths)
        summaryScrollOffsets.keys.retainAll(currentPaths)
        if (highlightedTarget?.first !in currentPaths) {
            highlightedTarget = null
            stopHighlightTimer()
        }
        buildViews()
        if (scopeChanged || wasEmpty || (activePath != null && activeBlock == null)) {
            activePath = null
            views.firstOrNull()?.let(::activate)
            firstStep = true
            if (activePath == null) overview = null
        }
        placeJoinedCards()
        reconcileActiveSummaries(syncAll = true)
        chatPanels.values.forEach { it.refreshLinks() }
        canvasChatPanel?.refreshLinks()
        surface.revalidate()
        surface.repaint()
        requestHighlights()
    }

    /** Materializes all remaining cut lines for [path] when its block supports on-demand expansion. */
    internal fun loadMoreLines(path: String): Boolean {
        val current = shown.firstOrNull { it.path == path } ?: return false
        if (current.loadMore == null) return false
        val expanded = expandedLineBlocks[path]?.takeIf { it.first == current }?.second
            ?: wallLoadMoreLines(current).also { expandedLineBlocks[path] = current to it }
        if (expanded == current) return false
        expandedLinePaths += path
        lastContent?.let(::show)
        return true
    }

    /** Returns the cached logical bounds recorded during the latest layout for [path], if any. */
    internal fun cachedLayoutRect(path: String): WallRect? = layoutRects[path]

    /** Paths currently active for rich summary rendering. */
    internal val activeSummaries: Set<String> get() = activeSummaryPaths

    /** Loads the next [count] unloaded files onto the wall, preserving existing card positions in cards mode. */
    internal fun loadMore(count: Int = loadChunk): Int {
        val next = selectNextWallPaths(allPaths, loadedPaths, count)
        if (next.isEmpty()) return 0
        if (mode == WallMode.CARDS && !arranged && views.isNotEmpty()) {
            ensureLayout()
            arranged = true
            lockedCardWidth = views.first().blockWidth
        }
        loadedPaths.addAll(next)
        lastContent?.let(::show)
        return next.size
    }

    private fun findUnloadedScopePath(resolved: String): String? {
        if (resolved in allPaths) return resolved
        val normalizedTarget = normalizeWallPath(resolved)
        allPaths.firstOrNull { normalizeWallPath(it) == normalizedTarget }?.let { return it }
        val previous = linkHost?.previousPaths().orEmpty()
        val renamed = previous[resolved]
            ?: previous.entries.firstOrNull { normalizeWallPath(it.key) == normalizedTarget }?.value
            ?: return null
        val normalizedRenamed = normalizeWallPath(renamed)
        return allPaths.firstOrNull { it == renamed || normalizeWallPath(it) == normalizedRenamed }
    }

    /** Ensures [path] is loaded onto the wall (adding it to [loadedPaths] and rebuilding if needed). */
    internal fun ensurePathLoaded(path: String): Boolean {
        if (path in loadedPaths && views.any { it.block.path == path }) return true
        if (path !in allPaths && linkHost?.ensureCard(path, null, null) == null) return false
        if (mode == WallMode.CARDS && !arranged && views.isNotEmpty()) {
            ensureLayout()
            arranged = true
            lockedCardWidth = views.first().blockWidth
        }
        val added = loadedPaths.add(path)
        if (added || views.none { it.block.path == path }) {
            lastContent?.let(::show)
        }
        return views.any { it.block.path == path }
    }

    /** Places newly loaded or joined cards into non-overlapping positions when [arranged] is active in cards mode. */
    private fun placeJoinedCards() {
        if (!arranged || mode != WallMode.CARDS) return
        val joined = views.filter { it.block.path !in positions }
        if (joined.isEmpty()) return
        available()?.let { avail ->
            val cap = wallCompactHeight(avail.height, zoom, views.first().metrics)
            joined.filter { it.block.path !in compact && it.block.path !in expanded }
                .filter { it.fullHeight > cap && wallDefaultCompact(it.fullHeight, avail.height, zoom) }
                .forEach { compact[it.block.path] = cap }
        }
        val existingRects = views.filter { it.block.path in positions }.map(::rect).toMutableList()
        val avoidedRects = chatOpen.mapNotNull { chatPath ->
            val owner = views.firstOrNull { it.block.path == chatPath } ?: return@mapNotNull null
            val at = positions[chatPath] ?: return@mapNotNull null
            val chatWidthLogical = ((CHAT_TAB_WIDTH * zoom + wallChatWidth()) / zoom).roundToInt()
            val chatHeightLogical = (wallChatHeight((owner.logical.height * zoom).roundToInt()) / zoom).roundToInt()
            WallRect(at.x + owner.logical.width, at.y, chatWidthLogical, chatHeightLogical)
        }
        val sourceRect = pendingAnchorPath?.takeIf { it in positions }
            ?.let { p -> views.firstOrNull { it.block.path == p } }?.let(::rect)
        joined.forEach { newView ->
            val size = newView.logical
            val at = wallNewCardPosition(sourceRect, existingRects, size.width, size.height, gap, avoidedRects)
            val rect = WallRect(at.x, at.y, size.width, size.height)
            positions[newView.block.path] = at
            layoutRects[newView.block.path] = rect
            existingRects += rect
        }
    }

    private fun syncMoreViewAttachment() {
        if (remainingFilesCount > 0 && views.isNotEmpty()) {
            val more = moreView ?: MoreView().also { moreView = it }
            if (more.parent !== surface) surface.add(more)
        } else {
            moreView?.let { if (it.parent === surface) surface.remove(it) }
            moreView = null
            morePosition = null
        }
    }

    /**
     * Makes the block views for [shown] in the current mode, with their Chat tabs and unfolded chat windows: the skim column shows
     * the rows the line cap cut too.
     */
    private fun buildViews() {
        val metrics = metrics()
        val blocks = if (mode == WallMode.SKIM) shown.map(::wallSkimBlock) else shown
        val width = if (mode == WallMode.CARDS && arranged && lockedCardWidth != null) {
            lockedCardWidth!!
        } else {
            val baseWidth = wallBlockWidth(blocks, metrics)
            if (mode == WallMode.CARDS) (baseWidth * cardWidthScale).roundToInt() else baseWidth
        }
        surface.removeAll()
        views = blocks.map { BlockView(it, width, metrics) }
        views.forEach(surface::add)
        syncMoreViewAttachment()
        if (chats != null) {
            chatTabs = views.associate { it.block.path to ChatTab(it) }
            chatTabs.values.forEach(surface::add)
            chatOpen.retainAll(chatTabs.keys)
            chatOpen.forEach { surface.add(chatPanel(it)) }
            if (canvasChatOpen) surface.add(canvasChat())
            raiseChats()
        }
    }

    /**
     * Lays the wall out as [next]: keeps the active file in view, and restores the zoom, scroll and arrangement the other mode had
     * when the reader comes back to it within the same scope.
     */
    internal fun switchMode(next: WallMode) {
        if (next == mode) return
        stopAnimation()
        val viewport = viewport()
        modeStates[mode] = ModeState(zoom, zoomTouched, arranged, lockedCardWidth, positions.toMap(), layoutRects.toMap(), viewport?.viewPosition)
        overview = null
        firstStep = true
        mode = next
        saveMode()
        modeLink.text = modeLinkText()
        modeLink.toolTipText = modeLinkTooltip()
        label.toolTipText = if (next == WallMode.SKIM) labels.skimControls else labels.controls
        positions.clear()
        layoutRects.clear()
        val restored = modeStates[next]
        if (restored != null) {
            positions.putAll(restored.positions)
            layoutRects.putAll(restored.layoutRects)
            arranged = restored.arranged
            lockedCardWidth = restored.lockedCardWidth
            zoomTouched = restored.zoomTouched
            setZoom(restored.zoom)
        } else {
            arranged = false
            lockedCardWidth = null
            zoomTouched = false
            setZoom(1.0)
        }
        packedFor = null
        applyScrollMode()
        val path = activePath
        if (shown.isNotEmpty()) {
            buildViews()
            placeJoinedCards()
            reconcileActiveSummaries(syncAll = true)
            views.firstOrNull { it.block.path == path }?.let(::activate)
            requestHighlights()
        }
        surface.revalidate()
        surface.repaint()
        val returnTo = restored?.view
        SwingUtilities.invokeLater {
            (scrollPane() ?: viewport())?.validate()
            val active = views.firstOrNull { it.block.path == activePath }
            when {
                next == WallMode.SKIM -> active?.let { scrollTo(0, skimTop(it)) }
                returnTo != null -> scrollTo(returnTo.x, returnTo.y)
                else -> scrollTo(0, 0)
            }
            surface.requestFocusInWindow()
        }
    }

    /** The view position that puts [view]'s top just under the viewport's top in the skim column. */
    private fun skimTop(view: BlockView): Int {
        val viewport = viewport() ?: return 0
        return surfaceOffset(viewport).y + view.y - gap / 2
    }

    /** The skim column's sticky headers repaint with every scroll, which the viewport's blitting would smear. */
    private fun applyScrollMode() {
        val viewport = watched?.viewport ?: return
        if (mode == WallMode.SKIM) {
            if (blitMode == null) blitMode = viewport.scrollMode
            viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
        } else blitMode?.let { viewport.scrollMode = it; blitMode = null }
    }

    private fun loadMode(): WallMode {
        val app = ApplicationManager.getApplication() ?: return WallMode.CARDS
        if (app.isUnitTestMode) return WallMode.CARDS
        val saved = PropertiesComponent.getInstance().getValue(modeKey())
        return WallMode.entries.firstOrNull { it.name == saved } ?: WallMode.CARDS
    }

    private fun saveMode() {
        val app = ApplicationManager.getApplication() ?: return
        if (!app.isUnitTestMode) PropertiesComponent.getInstance().setValue(modeKey(), mode.name, WallMode.CARDS.name)
    }

    private fun modeKey() = "dev.insight.wall.${labels.id}.mode"

    private fun modeLinkText() = if (mode == WallMode.SKIM) "Cards" else "Skim"

    private fun modeLinkTooltip() = if (mode == WallMode.SKIM) "Lay the files out as movable cards on a canvas (M)"
        else "Read every file whole in one column, like git diff (M)"

    /** In the skim column, the file under the viewport's top becomes the active one, which Enter, C and S act on. */
    private fun followScroll() {
        val viewport = viewport() ?: return
        val top = viewport.viewPosition.y - surfaceOffset(viewport).y + gap
        val view = views.lastOrNull { it.y <= top } ?: views.firstOrNull() ?: return
        if (view.block.path != activePath) activate(view)
    }

    /** Scrolls the skim column to the next hunk header below the sticky header, or the previous one above it. */
    private fun stepHunk(forward: Boolean) {
        val viewport = viewport() ?: return
        val metrics = views.firstOrNull()?.metrics ?: return
        val header = (metrics.headerHeight * zoom).roundToInt()
        var current = viewport.viewPosition.y + header
        val expanded = mutableSetOf<String>()
        while (true) {
            val offset = surfaceOffset(viewport).y
            val unfolded = views.filter { it.block.path !in folded }
            val tops = unfolded.flatMap { view ->
                view.block.lines.withIndex().filter { it.value.kind == WallLine.Kind.HUNK }.map { offset + view.y + view.rowTop(it.index) }
            }
            val candidate = wallNextTop(tops, current, forward)
            val toExpand = if (forward) {
                unfolded.firstOrNull { view ->
                    view.block.path !in expanded && view.canLoadMoreLines() &&
                        offset + view.y + view.height > current + 1 &&
                        (candidate == null || offset + view.y + view.rowTop(view.block.lines.size) <= candidate)
                }
            } else {
                unfolded.lastOrNull { view ->
                    val footerTop = offset + view.y + view.rowTop(view.block.lines.size)
                    view.block.path !in expanded && view.canLoadMoreLines() &&
                        footerTop < current - 1 &&
                        (candidate == null || footerTop >= candidate)
                }
            }
            if (toExpand != null) {
                expanded += toExpand.block.path
                val oldBottom = toExpand.y + toExpand.height
                if (loadMoreLines(toExpand.block.path)) {
                    surface.doLayout()
                    layoutScrollHierarchy()
                    if (!forward) {
                        val updatedView = views.firstOrNull { it.block.path == toExpand.block.path }
                        if (updatedView != null) {
                            val delta = (updatedView.y + updatedView.height) - oldBottom
                            if (delta != 0) {
                                current += delta
                                scrollTo(viewport.viewPosition.x, viewport.viewPosition.y + delta)
                            }
                        }
                    }
                    continue
                }
            }
            val target = candidate ?: return
            animate(zoom, Point(viewport.viewPosition.x, target - header))
            return
        }
    }

    /** Folds [view] to its header in the skim column, or unfolds it. */
    private fun toggleFold(view: BlockView) {
        val path = view.block.path
        if (!folded.remove(path)) folded += path
        view.syncSummary()
        packedFor = null
        surface.revalidate()
        surface.repaint()
        // A file folded while its header was stuck at the top leaves the reader at the next file rather than past it.
        SwingUtilities.invokeLater {
            val viewport = viewport() ?: return@invokeLater
            if (skimTop(view) < viewport.viewPosition.y) scrollTo(viewport.viewPosition.x, skimTop(view))
        }
    }

    /** Folds every file of the skim column, or unfolds them all when every one is folded already. */
    private fun toggleFoldAll() {
        val paths = views.map { it.block.path }
        if (folded.containsAll(paths)) folded.clear() else folded.addAll(paths)
        views.forEach { it.syncSummary() }
        packedFor = null
        surface.revalidate()
        surface.repaint()
        views.firstOrNull { it.block.path == activePath }?.let { active ->
            SwingUtilities.invokeLater { (scrollPane() ?: viewport())?.validate(); scrollTo(0, skimTop(active)) }
        }
    }

    /** Drops every block's colours and works them out again, as after a colour scheme change. */
    private fun rehighlight() {
        highlightGeneration++
        highlights.clear()
        highlighting.clear()
        requestHighlights()
        views.forEach { it.repaint() }
    }

    /**
     * Works out, one block after another in the background, the row colours of the shown blocks that have none yet; each block
     * repaints as its colours arrive.
     */
    private fun requestHighlights() {
        val highlighter = highlighter ?: return
        val app = ApplicationManager.getApplication() ?: return
        val paths = views.map { it.block.path }.toSet()
        highlights.keys.retainAll(paths)
        highlighting.keys.retainAll(paths)
        val vp = currentLogicalViewport()
        val prioritizedViews = if (vp == null || positions.isEmpty() || views.size <= 1) views else {
            val cx = vp.x + vp.width / 2.0
            val cy = vp.y + vp.height / 2.0
            fun intersects(r: WallRect) = r.width > 0 && r.height > 0 &&
                r.x.toLong() < vp.x.toLong() + vp.width && r.x.toLong() + r.width > vp.x.toLong() &&
                r.y.toLong() < vp.y.toLong() + vp.height && r.y.toLong() + r.height > vp.y.toLong()
            views.sortedWith(
                compareBy<BlockView> { it.block.path != activePath }
                    .thenBy { !intersects(rect(it)) }
                    .thenBy {
                        val r = rect(it)
                        val dx = (r.x + r.width / 2.0) - cx
                        val dy = (r.y + r.height / 2.0) - cy
                        dx * dx + dy * dy
                    },
            )
        }
        val work = prioritizedViews.mapNotNull { view ->
            val block = view.block
            if (highlights[block.path]?.first == block || highlighting[block.path] == block) return@mapNotNull null
            val job = highlighter.prepare(block)
            if (job == null) {
                highlights[block.path] = block to null
                return@mapNotNull null
            }
            highlighting[block.path] = block
            block to job
        }
        if (work.isEmpty()) return
        val generation = highlightGeneration
        app.executeOnPooledThread {
            for ((block, job) in work) {
                if (disposed || generation != highlightGeneration) return@executeOnPooledThread
                val rows = try {
                    runReadAction { job() }
                } catch (e: ProcessCanceledException) {
                    null
                } catch (e: Exception) {
                    LOG.warn("Could not highlight ${block.path} on the wall", e)
                    null
                }
                app.invokeLater({
                    if (disposed || generation != highlightGeneration || highlighting[block.path] != block) return@invokeLater
                    highlighting.remove(block.path)
                    highlights[block.path] = block to rows
                    views.firstOrNull { it.block == block }?.repaint()
                }, ModalityState.any())
            }
        }
    }

    /** [block]'s row colours, once worked out for it. */
    internal fun rowSpans(block: WallBlock): List<List<WallSpan>?>? = highlights[block.path]?.takeIf { it.first == block }?.second

    private fun viewport(): JViewport? = SwingUtilities.getAncestorOfClass(JViewport::class.java, surface) as? JViewport

    private fun scrollPane(): JScrollPane? = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, surface) as? JScrollPane

    private fun layoutScrollHierarchy() {
        val root = scrollPane() ?: viewport()
        if (root == null) {
            surface.doLayout()
            return
        }
        if (root.isDisplayable) {
            root.validate()
            return
        }
        fun layoutDown(component: java.awt.Container) {
            component.doLayout()
            component.components.filterIsInstance<java.awt.Container>().forEach(::layoutDown)
        }
        layoutDown(root)
    }

    private fun scheme(): EditorColorsScheme = EditorColorsManager.getInstance().globalScheme

    private fun textFont(): Font = scheme().let { it.getFont(EditorFontType.PLAIN).deriveFont(it.editorFontSize2D) }

    private fun headerFont(): Font = textFont().let { it.deriveFont(it.size2D * 0.92f) }

    /** Measures at 100%. */
    private fun metrics(): WallMetrics {
        val text = surface.getFontMetrics(textFont())
        val header = surface.getFontMetrics(headerFont())
        val padding = JBUI.scale(6)
        return WallMetrics(text.charWidth('m'), text.height, header.height + 2 * padding, padding, JBUI.scale(24))
    }

    private val gap: Int get() = JBUI.scale(24)

    /** Each block's place and size at 100%, in file order. */
    private fun rects(): List<WallRect> = views.map(::rect)

    private fun rect(view: BlockView): WallRect {
        val at = positions[view.block.path] ?: Point(0, 0)
        val size = view.logical
        return WallRect(at.x, at.y, size.width, size.height)
    }

    private fun extentRects(): List<WallRect> {
        val base = rects()
        val more = moreView ?: return base
        val at = morePosition ?: return base
        val size = more.logicalSize()
        return base + WallRect(at.x, at.y, size.width, size.height)
    }

    private fun updateMorePosition() {
        val more = moreView
        if (more == null || remainingFilesCount <= 0 || views.isEmpty()) {
            morePosition = null
            return
        }
        val size = more.logicalSize()
        morePosition = if (mode == WallMode.SKIM) {
            val bottom = rects().maxOfOrNull { it.y + it.height } ?: 0
            Point(gap, bottom + gap)
        } else {
            wallNewCardPosition(null, rects(), size.width, size.height, gap)
        }
    }

    private fun currentLogicalViewport(): WallRect? {
        val vp = viewport() ?: return null
        if (vp.width <= 0 || vp.height <= 0) return null
        val offset = surfaceOffset(vp)
        val pos = vp.viewPosition
        val x = floor((pos.x - offset.x) / zoom).toInt()
        val y = floor((pos.y - offset.y) / zoom).toInt()
        val w = ceil(vp.width / zoom).toInt().coerceAtLeast(1)
        val h = ceil(vp.height / zoom).toInt().coerceAtLeast(1)
        return WallRect(x, y, w, h)
    }

    private fun reconcileActiveSummaries(syncAll: Boolean = false) {
        val previous = activeSummaryPaths
        val next = if (summarized.size <= WALL_ACTIVE_SUMMARY_LIMIT) {
            summarized.toSet()
        } else {
            if (layoutRects.size < views.size) ensureLayout()
            selectActiveSummaryPaths(summarized, layoutRects, currentLogicalViewport(), activePath)
        }
        val changed = next != previous
        activeSummaryPaths = next
        if (syncAll) {
            views.forEach { it.syncSummary() }
        } else if (changed) {
            views.filter { view ->
                val path = view.block.path
                val wasActive = path in previous
                val isActive = path in next
                wasActive != isActive || (path in summarized && isActive && view.summaryTextPane == null)
            }.forEach { it.syncSummary() }
            if (mode == WallMode.SKIM) {
                packedFor = null
                surface.revalidate()
            }
        }
    }

    /**
     * The size the wall has to spread over: the page's scroll pane less a scrollbar each way and the label row, which stays put
     * whether scrollbars show; null while the page is too small to be real yet.
     */
    private fun available(): Dimension? {
        val pane = scrollPane() ?: return null
        val insets = pane.insets
        val width = pane.width - insets.left - insets.right - pane.verticalScrollBar.preferredSize.width
        val height = pane.height - insets.top - insets.bottom - pane.horizontalScrollBar.preferredSize.height - header.preferredSize.height
        return Dimension(width, height).takeIf { width >= JBUI.scale(240) && height >= JBUI.scale(120) }
    }

    /**
     * Packs the blocks for the space the page offers and picks a zoom that shows several columns, until the reader moves a block;
     * before the page has a real size a wide default keeps the preferred size sensible.
     */
    private fun ensureLayout() {
        if (mode == WallMode.SKIM) return ensureSkimLayout()
        if (views.isEmpty()) return
        if (arranged) {
            views.forEach { view ->
                if (view.block.path !in layoutRects) layoutRects[view.block.path] = rect(view)
            }
            updateMorePosition()
            return
        }
        val available = available()
        val target = available ?: Dimension(JBUI.scale(1600), 0)
        if (target == packedFor) return
        packedFor = target
        if (available != null) {
            // A huge file opens compact rather than towering over the canvas.
            val cap = wallCompactHeight(available.height, zoom, views.first().metrics)
            views.filter { val path = it.block.path; path !in compact && path !in expanded }
                .filter { it.fullHeight > cap && wallDefaultCompact(it.fullHeight, available.height, zoom) }
                .forEach { compact[it.block.path] = cap }
        }
        val heights = views.map { it.logical.height }
        val width = views.first().logical.width
        val rects = wallLayout(heights, width, wallColumns(heights, width, gap, target.width, target.height), gap)
        layoutRects.clear()
        views.zip(rects).forEach { (view, rect) ->
            positions[view.block.path] = Point(rect.x, rect.y)
            layoutRects[view.block.path] = rect
        }
        updateMorePosition()
        if (available != null && !zoomTouched) setZoom(wallInitialZoom(wallExtent(extentRects(), gap), available.width, available.height))
    }

    /** Stacks the blocks in one column as wide as the page at the current zoom, each as tall as its rows, or its header when folded. */
    private fun ensureSkimLayout() {
        if (views.isEmpty()) return
        val target = available() ?: Dimension(JBUI.scale(1200), 0)
        if (target == packedFor) return
        packedFor = target
        val metrics = views.first().metrics
        val tab = if (chats != null) CHAT_TAB_WIDTH else 0
        val width = wallSkimWidth(target.width, zoom, gap, tab, 40 * metrics.charWidth + 2 * metrics.padding)
        views.forEach { it.blockWidth = width }
        val rects = wallLayout(views.map { it.logical.height }, width, 1, gap)
        layoutRects.clear()
        views.zip(rects).forEach { (view, rect) ->
            positions[view.block.path] = Point(rect.x, rect.y)
            layoutRects[view.block.path] = rect
        }
        updateMorePosition()
    }

    private fun setZoom(value: Double) {
        // The skim column's width follows the zoom so it keeps filling the page.
        if (mode == WallMode.SKIM && value != zoom) packedFor = null
        val changed = value != zoom
        zoom = value
        zoomLabel.text = "${(value * 100).roundToInt()}%"
        if (changed) views.forEach { it.syncSummary() }
    }

    /** Where the surface starts in the scrolled view, below the label row. */
    private fun surfaceOffset(viewport: JViewport): Point =
        viewport.view?.let { SwingUtilities.convertPoint(surface, 0, 0, it) } ?: Point(0, 0)

    private fun clamped(viewport: JViewport, x: Int, y: Int): Point {
        val view = viewport.view ?: return Point(0, 0)
        return Point(x.coerceIn(0, (view.width - viewport.width).coerceAtLeast(0)), y.coerceIn(0, (view.height - viewport.height).coerceAtLeast(0)))
    }

    private fun scrollTo(x: Int, y: Int) {
        val viewport = viewport() ?: return
        viewport.viewPosition = clamped(viewport, x, y)
    }

    private fun scrollBy(dx: Int, dy: Int) {
        stopAnimation()
        val viewport = viewport() ?: return
        val at = viewport.viewPosition
        scrollTo(at.x + dx, at.y + dy)
    }

    /** Paints the wall at [value] and scrolls to ([x], [y]) once the page has measured it again. */
    private fun place(value: Double, x: Int, y: Int) {
        setZoom(value)
        surface.revalidate()
        layoutScrollHierarchy()
        scrollTo(x, y)
        surface.repaint()
    }

    /** Zooms to [target], keeping the content under [anchor] (in the viewport; its centre by default) where it is. */
    private fun zoomTo(target: Double, anchor: Point? = null) {
        stopAnimation()
        val next = wallClampZoom(target)
        zoomTouched = true
        val viewport = viewport() ?: return place(next, 0, 0)
        val at = anchor ?: Point(viewport.width / 2, viewport.height / 2)
        val offset = surfaceOffset(viewport)
        val position = viewport.viewPosition
        place(next, wallZoomAround(position.x.toDouble(), at.x.toDouble(), offset.x.toDouble(), zoom, next).roundToInt(),
            wallZoomAround(position.y.toDouble(), at.y.toDouble(), offset.y.toDouble(), zoom, next).roundToInt())
    }

    /** Shows the whole wall from its top-left corner. */
    private fun fit() {
        val viewport = viewport() ?: return
        val offset = surfaceOffset(viewport)
        zoomTouched = true
        animate(wallFitZoom(wallExtent(extentRects(), gap), viewport.width - offset.x, viewport.height - offset.y), Point(0, 0))
    }

    /** The height (at 100%) a block turned compact now would be capped at, or null before the page has a viewport. */
    private fun compactCap(metrics: WallMetrics): Int? =
        viewport()?.height?.takeIf { it > 0 }?.let { wallCompactHeight(it, zoom, metrics) }

    /** Turns [view] compact or back; an auto-packed wall packs again, an arranged one keeps its positions. */
    private fun toggleCompact(view: BlockView) {
        if (mode == WallMode.SKIM) return toggleFold(view)
        val path = view.block.path
        if (compact.remove(path) != null) expanded += path
        else {
            val cap = compactCap(view.metrics)?.takeIf { view.fullHeight > it } ?: return
            compact[path] = cap
            expanded -= path
            scrolls.remove(path)
        }
        if (!arranged) packedFor = null else layoutRects[path] = rect(view)
        surface.revalidate()
        surface.repaint()
    }

    /** A block whose summary is gone, and is not being written, shows its rows again. */
    private fun forgetMissingSummaries() {
        summarized.retainAll { it in summaries || it in summarizing }
        summaryScrolls.keys.retainAll(summarized)
        summaryScrollOffsets.keys.retainAll(summarized)
    }

    /** Whether the file at [path] has a summary to show, or one the wall can ask for. */
    private fun hasSummary(path: String): Boolean =
        path in summaries || path in summarizing || (summarize != null && (canSummarize?.invoke(path) ?: true))

    /**
     * Shows [view]'s summary in place of its rows, or its rows again; the block keeps its size. A file without a summary asks
     * for one and shows that it is being written until it arrives.
     */
    private fun toggleSummary(view: BlockView) {
        val path = view.block.path
        if (path in summarized) {
            summarized -= path
            summaryScrolls.remove(path)
            summaryScrollOffsets.remove(path)
        } else if (path in summaries || path in summarizing || (canSummarize?.invoke(path) != false && summarize?.invoke(path) == true)) {
            summarized += path
            // The skim column shows the summary of a folded file above its rows, so it opens too.
            if (mode == WallMode.SKIM) folded -= path
        }
        reconcileActiveSummaries()
        view.syncSummary()
        if (mode == WallMode.SKIM) {
            // There the summary adds rows above the diff rather than taking their place, so the column restacks.
            packedFor = null
            surface.revalidate()
            surface.repaint()
        } else view.repaint()
    }

    /** Context-aware link navigator for summaries and chats on this wall. */
    internal fun textNavigator(sourcePath: String?, fromChat: Boolean = false): LinkedTextNavigator = object : ContextualLinkedTextNavigator {
        override fun defaultFile(): String? = sourcePath

        override fun extraFiles(): List<String> =
            (views.map { it.block.path } + linkHost?.scopePaths().orEmpty() + linkHost?.previousPaths()?.keys.orEmpty()).distinct()

        override fun contextFiles(): List<String> =
            wallContextFiles(sourcePath, views.map { it.block.path }, linkHost?.scopePaths().orEmpty())

        override fun preferredFile(candidates: List<String>): String? =
            wallPreferredFile(candidates, sourcePath, views.map { it.block.path }.toSet(), linkHost?.scopePaths().orEmpty().toSet())

        override fun rankFiles(candidates: List<String>): List<String> =
            wallRankFiles(candidates, sourcePath, views.map { it.block.path }.toSet(), linkHost?.scopePaths().orEmpty().toSet())

        override fun shown(candidates: List<SymbolCandidate>, hints: SymbolHints): SymbolCandidate? {
            val mapped = candidates.map { candidate ->
                val resolved = linkHost?.wallPathFor(candidate.path, candidate.absolutePath) ?: normalizeWallPath(candidate.path)
                candidate.copy(path = resolved) to candidate
            }
            val explicit = if (hints.files.firstOrNull() == sourcePath) emptyList() else hints.files.filter { it != sourcePath }
            val picked = wallPreferredSymbol(mapped.map { it.first }, sourcePath, views.map { it.block }, explicit) ?: return null
            return mapped.firstOrNull { it.first === picked }?.second ?: picked
        }

        override fun linkTooltip(link: ReportLink): String {
            val wallPaths = views.map { it.block.path }.toSet()
            val scopePaths = linkHost?.scopePaths().orEmpty().toSet()
            val preferred = if (link.isAmbiguous) preferredFile(link.candidates) else null
            return wallLinkTooltip(link, wallPaths, scopePaths, linkHost?.isDiffWall == true, preferred)
        }

        override fun contextPick(link: ReportLink, path: String, absolutePath: String?) =
            linkHost?.contextPick(link, path, absolutePath)

        override fun openFile(path: String, absolutePath: String?, lines: IntRange?, column: Int?) =
            followLink(sourcePath, path, absolutePath, lines, column, fromChat)

        override fun openDeclaration(declaration: SymbolCandidate, mention: SymbolMention) =
            followLink(sourcePath, declaration.path, declaration.absolutePath, declaration.line..declaration.line, declaration.column, fromChat)
    }

    /** Follows a link to [rawPath] at 1-based [lines], scrolling an existing card or adding a missing card first. */
    internal fun followLink(
        sourcePath: String?,
        rawPath: String,
        absolutePath: String?,
        lines: IntRange?,
        column: Int? = null,
        fromChat: Boolean = false,
    ) {
        val resolved = linkHost?.wallPathFor(rawPath, absolutePath) ?: normalizeWallPath(rawPath)
        when (val move = wallTextMove(resolved, lines, views.map { it.block }, linkHost?.previousPaths().orEmpty())) {
            is WallTextMove.Scroll -> {
                var row = move.row
                val currentBlock = views.firstOrNull { it.block.path == move.path }?.block
                    ?: shown.firstOrNull { it.path == move.path }
                val hasNewMatch = currentBlock != null && lines != null &&
                    currentBlock.lines.any { it.new != null && it.new in lines }
                if (lines != null && !hasNewMatch && currentBlock != null && currentBlock.hiddenLines > 0) {
                    if (linkHost?.ensureLineLoaded(move.path, lines.first) == true) {
                        views.firstOrNull { it.block.path == move.path }?.let { updated ->
                            row = wallTargetRow(updated.block.lines, lines)
                        }
                    }
                }
                revealBlock(move.path, row, focusSurface = !fromChat && sourcePath != null)
            }
            is WallTextMove.AddAndScroll -> {
                if (mode == WallMode.CARDS && !arranged && views.isNotEmpty()) {
                    ensureLayout()
                    arranged = true
                    lockedCardWidth = views.first().blockWidth
                }
                pendingAnchorPath = sourcePath
                val addedPath = try {
                    val inScopePath = findUnloadedScopePath(resolved)
                    val hostAdded = linkHost?.ensureCard(resolved, absolutePath, lines)
                    val targetPath = hostAdded ?: inScopePath
                    if (targetPath != null) {
                        ensurePathLoaded(targetPath)
                    }
                    targetPath
                } finally {
                    pendingAnchorPath = null
                }
                if (addedPath == null) {
                    project?.let { openReportFile(it, rawPath, lines, column, absolutePath) }
                    return
                }
                val view = views.firstOrNull { it.block.path == addedPath } ?: return
                val row = wallTargetRow(view.block.lines, lines)
                revealBlock(addedPath, row, focusSurface = !fromChat && sourcePath != null)
            }
        }
    }

    /** Brings card [path] into view, activates it, unfolds or reveals its body, and scrolls/highlights [rowTarget]. */
    internal fun revealBlock(path: String, rowTarget: WallRowTarget?, focusSurface: Boolean = true) {
        if (path in allPaths && path !in loadedPaths) {
            ensurePathLoaded(path)
        }
        val view = views.firstOrNull { it.block.path == path } ?: return
        var relayout = false
        if (mode == WallMode.SKIM && path in folded) {
            folded -= path
            view.syncSummary()
            relayout = true
        }
        if (mode == WallMode.CARDS && rowTarget != null && path in summarized) {
            summarized -= path
            summaryScrolls.remove(path)
            summaryScrollOffsets.remove(path)
            reconcileActiveSummaries()
            view.syncSummary()
        }
        activate(view)
        val rowsToHighlight = rowTarget?.highlightedRows.orEmpty()
        val previousHighlighted = highlightedTarget?.first
        if (rowsToHighlight.isNotEmpty()) {
            highlightedTarget = path to rowsToHighlight
            scheduleHighlightClear()
        } else {
            highlightedTarget = null
            stopHighlightTimer()
        }
        previousHighlighted?.takeIf { it != path }?.let { oldPath ->
            views.firstOrNull { it.block.path == oldPath }?.repaint()
        }
        view.repaint()
        if (relayout) packedFor = null
        surface.revalidate()
        surface.doLayout()
        layoutScrollHierarchy()

        if (mode == WallMode.CARDS) {
            if (rowTarget != null && view.isCompact) {
                val cap = view.capHeight
                if (cap != null) {
                    val bodyHeight = cap - view.metrics.headerHeight - 2 * view.metrics.padding
                    val targetY = rowTarget.rowIndex * view.metrics.lineHeight
                    val desired = (targetY - bodyHeight / 2 + view.metrics.lineHeight / 2).coerceAtLeast(0)
                    scrolls[path] = wallBodyScroll(0, desired, view.fullHeight, cap)
                    horizontalScrolls.remove(path)
                    view.repaint()
                }
            }
            val viewport = viewport()?.takeIf { it.width > 0 && it.height > 0 }
            if (viewport != null) {
                beginCardFocus(viewport)
                val rect = rect(view)
                val margin = gap
                val effWidth = if (canvasChatOpen && viewport.width > wallChatWidth() + JBUI.scale(300)) {
                    viewport.width - wallChatWidth() - JBUI.scale(16)
                } else viewport.width
                val targetZoom = wallFocusZoom(rect.width, effWidth, zoom, margin,
                    blockHeight = rect.height.takeIf { view.isCompact }, viewportHeight = viewport.height)
                val offset = surfaceOffset(viewport)
                val pos = wallFocusPosition(rect, targetZoom, offset.x, offset.y, effWidth, viewport.height, margin)
                if (rowTarget != null && !view.isCompact && rect.height * targetZoom > viewport.height) {
                    val rowCenterY = offset.y + (rect.y + view.metrics.headerHeight + view.metrics.padding +
                        rowTarget.rowIndex * view.metrics.lineHeight + view.metrics.lineHeight / 2.0) * targetZoom
                    val minY = (offset.y + rect.y * targetZoom - margin).roundToInt().coerceAtLeast(0)
                    pos.y = maxOf(minY, (rowCenterY - viewport.height / 2.0).roundToInt())
                }
                zoomTouched = true
                animate(targetZoom, pos)
            }
        } else {
            val viewport = viewport()?.takeIf { it.width > 0 && it.height > 0 }
            if (viewport != null) {
                val targetY = if (rowTarget != null) {
                    val offset = surfaceOffset(viewport).y
                    val headerPx = (view.metrics.headerHeight * zoom).roundToInt()
                    val rowY = offset + view.y + view.rowTop(rowTarget.rowIndex)
                    val leadIn = maxOf(headerPx + gap, viewport.height / 3)
                    maxOf(skimTop(view), rowY - leadIn)
                } else {
                    skimTop(view)
                }
                animate(zoom, Point(viewport.viewPosition.x, targetY))
            }
        }
        if (focusSurface) surface.requestFocusInWindow()
    }

    private fun scheduleHighlightClear() {
        stopHighlightTimer()
        if (ApplicationManager.getApplication()?.isUnitTestMode == true) return
        highlightClearTimer = Timer(HIGHLIGHT_MS) {
            val oldPath = highlightedTarget?.first
            highlightedTarget = null
            highlightClearTimer = null
            oldPath?.let { p -> views.firstOrNull { it.block.path == p }?.repaint() }
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun stopHighlightTimer() {
        highlightClearTimer?.stop()
        highlightClearTimer = null
    }

    /** [path]'s chat window, made when first unfolded. */
    private fun chatPanel(path: String): WallChatPanel = chatPanels.getOrPut(path) {
        WallChatPanel(path, chats!!, labels.fileChat(path), labels.id, onClose = { if (path in chatOpen) toggleChat(path) },
            onRaise = { chatPanels[path]?.let { if (it.parent === surface) surface.setComponentZOrder(it, 0); it.repaint() } },
            navigator = textNavigator(path, fromChat = true))
    }

    /** Keeps unfolded chat windows in front of the blocks they reach over, the latest unfolded frontmost. */
    private fun raiseChats() {
        chatOpen.forEach { path -> chatPanels[path]?.takeIf { it.parent === surface }?.let { surface.setComponentZOrder(it, 0) } }
        canvasChatPanel?.takeIf { canvasChatOpen && it.parent === surface }?.let { surface.setComponentZOrder(it, 0) }
    }

    /** The wall's own chat window, made when first unfolded. */
    private fun canvasChat(): WallChatPanel = canvasChatPanel ?: WallChatPanel(WALL_CANVAS_CHAT, chats!!, labels.canvasChat, labels.id,
        onClose = { if (canvasChatOpen) toggleCanvasChat() },
        onRaise = { canvasChatPanel?.let { if (it.parent === surface) surface.setComponentZOrder(it, 0); it.repaint() } },
        navigator = textNavigator(null, fromChat = true),
    ).apply { name = "${labels.id}-canvas-chat" }.also { canvasChatPanel = it }

    /**
     * Unfolds the wall's own chat about all its files in the visible top-right corner of the wall and focuses its input, or folds
     * it and returns focus to the wall.
     */
    internal fun toggleCanvasChat() {
        if (chats == null) return
        val panel = canvasChat()
        canvasChatOpen = !canvasChatOpen
        if (canvasChatOpen) {
            surface.add(panel)
            surface.setComponentZOrder(panel, 0)
        } else {
            surface.remove(panel)
            surface.requestFocusInWindow()
        }
        canvasChatLink.text = if (canvasChatOpen) CANVAS_CHAT_HIDE else labels.canvasChatLink
        surface.revalidate()
        surface.repaint()
        if (canvasChatOpen) SwingUtilities.invokeLater {
            if (panel.parent !== surface) return@invokeLater
            surface.doLayout()
            panel.input.requestFocusInWindow()
        }
    }

    /** Whether the wall's own chat window is unfolded. */
    internal fun canvasChatShown(): Boolean = canvasChatOpen

    /** The wall's own chat window, once it has been unfolded. */
    internal fun canvasChatWindow(): WallChatPanel? = canvasChatPanel

    /** Puts the wall's own chat window in the top-right corner of the part of the wall the page shows, at screen size. */
    private fun placeCanvasChat() {
        val panel = canvasChatPanel?.takeIf { canvasChatOpen && it.parent === surface } ?: return
        val margin = JBUI.scale(8)
        val visible = surface.visibleRect.takeIf { !it.isEmpty } ?: Rectangle(0, 0, surface.width, surface.height)
        val width = wallChatWidth().coerceAtMost((surface.width - 2 * margin).coerceAtLeast(JBUI.scale(200)))
        val height = wallCanvasChatHeight(visible.height - 2 * margin).coerceAtMost((surface.height - 2 * margin).coerceAtLeast(JBUI.scale(120)))
        val x = (visible.x + visible.width - width - margin).coerceAtLeast(visible.x + margin).coerceAtMost((surface.width - width - margin).coerceAtLeast(0))
        val y = (visible.y + margin).coerceAtMost((surface.height - height - margin).coerceAtLeast(0))
        if (panel.bounds != Rectangle(x, y, width, height)) {
            panel.setBounds(x, y, width, height)
            panel.validate()
        }
    }

    /**
     * In the skim column, where blocks fill the page's width, puts each unfolded file chat over its block's right side, kept in the
     * part of the block in view while the page scrolls.
     */
    private fun placeFileChats() {
        if (chatOpen.isEmpty()) return
        val visible = surface.visibleRect.takeIf { !it.isEmpty } ?: Rectangle(0, 0, surface.width, surface.height)
        val margin = JBUI.scale(8)
        chatOpen.forEach { path ->
            val panel = chatPanels[path]?.takeIf { it.parent === surface } ?: return@forEach
            val view = views.firstOrNull { it.block.path == path } ?: return@forEach
            val header = (view.metrics.headerHeight * zoom).roundToInt()
            val width = wallChatWidth().coerceAtMost((view.width - 2 * margin).coerceAtLeast(JBUI.scale(200)))
            val height = wallCanvasChatHeight(visible.height - header - 2 * margin)
            val x = view.x + view.width - width - margin
            val y = (visible.y + header + margin).coerceAtMost(view.y + view.height - height - margin).coerceAtLeast(view.y + header + margin)
            if (panel.bounds != Rectangle(x, y, width, height)) {
                panel.setBounds(x, y, width, height)
                panel.validate()
            }
        }
    }

    /** Unfolds [path]'s chat window beside its block and focuses its input, or folds it and returns focus to the wall. */
    internal fun toggleChat(path: String) {
        if (chats == null || path !in chatTabs) return
        val panel = chatPanel(path)
        if (chatOpen.remove(path)) {
            surface.remove(panel)
            surface.requestFocusInWindow()
        } else {
            chatOpen += path
            surface.add(panel)
            surface.setComponentZOrder(panel, 0)
        }
        chatTabs[path]?.repaint()
        surface.revalidate()
        surface.repaint()
        if (path in chatOpen) SwingUtilities.invokeLater {
            if (panel.parent !== surface) return@invokeLater
            surface.doLayout()
            if (mode == WallMode.CARDS) surface.scrollRectToVisible(panel.bounds)
            panel.input.requestFocusInWindow()
        }
    }

    /** Whether [path]'s chat window is unfolded. */
    internal fun chatShown(path: String): Boolean = path in chatOpen

    /** [path]'s chat window, once it has been unfolded. */
    internal fun chatWindow(path: String): WallChatPanel? = chatPanels[path]

    override fun dispose() {
        disposed = true
        schemeConnection?.disconnect()
        stopAnimation()
        stopHighlightTimer()
        chatSubscription?.dispose()
        chatPanels.values.forEach(Disposer::dispose)
        chatPanels.clear()
        chatOpen.clear()
        canvasChatPanel?.let(Disposer::dispose)
        canvasChatPanel = null
        canvasChatOpen = false
        surface.removeAll()
        ownedScope?.cancel()
        watch(null)
    }

    private fun activate(view: BlockView) {
        val previous = activeBlock
        activePath = view.block.path
        if (summarized.size > WALL_ACTIVE_SUMMARY_LIMIT) reconcileActiveSummaries()
        surface.setComponentZOrder(view, 0)
        // Open chat windows stay in front of blocks.
        if (mode == WallMode.SKIM || chatOpen.isNotEmpty() || canvasChatOpen) raiseChats()
        previous?.repaint()
        view.repaint()
    }

    /** Activates the next block in reading order, or the previous one, and zooms and scrolls to read it. */
    private fun step(forward: Boolean) {
        if (views.isEmpty()) return
        if (mode == WallMode.SKIM) {
            // The column reads in file order and is already readable: the next file's top comes up under the viewport's top.
            val current = views.indexOfFirst { it.block.path == activePath }.takeIf { it >= 0 }
            val next = wallNextBlock(views.indices.toList(), current, forward) ?: return
            activate(views[next])
            val at = viewport()?.viewPosition ?: return
            animate(zoom, Point(at.x, skimTop(views[next])))
            return
        }
        val current = views.indexOfFirst { it.block.path == activePath }.takeIf { it >= 0 }
        val next = (if (firstStep) current else null)
            ?: wallNextBlock(wallReadingOrder(rects(), gap), current, forward) ?: return
        val view = views[next]
        activate(view)
        val viewport = viewport() ?: return
        beginCardFocus(viewport)
        zoomToCard(view, viewport)
    }

    private fun zoomToCard(view: BlockView, viewport: JViewport) {
        val rect = rect(view)
        val margin = gap
        val capped = view.block.path in compact
        val target = wallFocusZoom(rect.width, viewport.width, zoom, margin,
            blockHeight = rect.height.takeIf { capped }, viewportHeight = viewport.height)
        val offset = surfaceOffset(viewport)
        zoomTouched = true
        animate(target, wallFocusPosition(rect, target, offset.x, offset.y, viewport.width, viewport.height, margin))
    }

    private fun beginCardFocus(viewport: JViewport) {
        firstStep = false
        if (overview == null) overview = zoom to viewport.viewPosition
    }

    /** Waits before moving the card so a second click can still open the row under the pointer. */
    private fun focusOnClick(path: String) {
        stopAnimation()
        beginCardFocus(viewport() ?: return)
        val delay = (Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int) ?: 500
        clickZoom = Timer(delay) {
            clickZoom = null
            val view = focusedBlock?.takeIf { it.block.path == path } ?: return@Timer
            viewport()?.let { zoomToCard(view, it) }
        }.apply {
            isRepeats = false
            start()
        }
    }

    /** Leaves card focus on the first Escape and clears the current card on the next. */
    private fun escapeCards() {
        val previous = overview
        if (previous != null) {
            overview = null
            animate(previous.first, previous.second)
        } else {
            val active = activeBlock
            activePath = null
            firstStep = true
            active?.repaint()
        }
    }

    /** Eases the zoom and scroll to [target] and [position] over a moment; at once when not on screen. */
    private fun animate(target: Double, position: Point) {
        stopAnimation()
        val viewport = viewport()
        if (viewport == null || !surface.isShowing || ApplicationManager.getApplication()?.isUnitTestMode != false) {
            place(target, position.x, position.y)
            return
        }
        val fromZoom = zoom
        val from = viewport.viewPosition
        val started = System.nanoTime()
        animation = Timer(15) {
            val t = ((System.nanoTime() - started) / 1e6 / ANIMATION_MS).coerceAtMost(1.0)
            val eased = 1 - (1 - t).pow(3)
            place(fromZoom + (target - fromZoom) * eased, (from.x + (position.x - from.x) * eased).roundToInt(),
                (from.y + (position.y - from.y) * eased).roundToInt())
            if (t >= 1.0) stopAnimation()
        }.apply { start() }
    }

    private fun stopAnimation() {
        clickZoom?.stop()
        clickZoom = null
        animation?.stop()
        animation = null
    }

    /** Focuses the surface when the wall is opened from its own review tab, but not away from text the reader is typing. */
    private fun focusWhenOpened() {
        if (!surface.isShowing) return
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val tab = SwingUtilities.getAncestorOfClass(ReviewTab::class.java, surface)
        if (owner == null || owner !is JTextComponent && tab != null && SwingUtilities.isDescendingFrom(owner, tab)) surface.requestFocusInWindow()
    }

    private fun watch(pane: JScrollPane?) {
        if (pane === watched) return
        watched?.removeComponentListener(resized)
        watched?.viewport?.removeChangeListener(scrolled)
        blitMode?.let { watched?.viewport?.scrollMode = it }
        blitMode = null
        watched = pane
        pane?.addComponentListener(resized)
        pane?.viewport?.addChangeListener(scrolled)
        applyScrollMode()
    }

    /** Each scrollable axis belongs to the focused body, including at its limits. */
    private fun navigate(dx: Int, dy: Int, page: Boolean = false, edge: Boolean = false) {
        val focused = focusedBlock
        if (focused == null) {
            scrollBy(dx, dy)
            return
        }
        fun delta(value: Int, size: Int): Int {
            val direction = if (value < 0) -1 else 1
            return when {
                value == 0 -> 0
                edge -> direction * (Int.MAX_VALUE / 2)
                page -> direction * (size - focused.metrics.lineHeight).coerceAtLeast(focused.metrics.lineHeight)
                else -> (value / zoom).roundToInt()
            }
        }
        val bodyX = if (focused.canScrollHorizontally) dx else 0
        val bodyY = if (focused.canScrollVertically) dy else 0
        if (bodyX != 0 || bodyY != 0) {
            focused.scrollContent(delta(bodyX, focused.logical.width), delta(bodyY, focused.logical.height - focused.metrics.headerHeight))
        }
        if (bodyX != dx || bodyY != dy) scrollBy(dx - bodyX, dy - bodyY)
    }

    /** Where a mode left the wall: its zoom, whether the reader set it or moved blocks, the blocks' places and the page's scroll. */
    private class ModeState(
        val zoom: Double,
        val zoomTouched: Boolean,
        val arranged: Boolean,
        val lockedCardWidth: Int?,
        val positions: Map<String, Point>,
        val layoutRects: Map<String, WallRect>,
        val view: Point?,
    )

    /** One keyboard command of the wall, bound to its [strokes] while the surface has focus. */
    private class Key(val name: String, val strokes: List<KeyStroke>, val enabled: () -> Boolean = { true }, val run: () -> Unit)

    private fun keys(): List<Key> {
        fun key(code: Int, modifiers: Int = 0) = KeyStroke.getKeyStroke(code, modifiers)
        fun menu(code: Int, extra: Int = 0) = listOf(key(code, InputEvent.CTRL_DOWN_MASK or extra), key(code, InputEvent.META_DOWN_MASK or extra))
        val shift = InputEvent.SHIFT_DOWN_MASK
        val step = JBUI.scale(48)
        fun page(vertical: Boolean) = viewport()?.let { if (vertical) it.height else it.width }?.minus(step)?.coerceAtLeast(step) ?: step
        fun far() = viewport()?.view?.let { maxOf(it.width, it.height) } ?: 0
        return listOf(
            Key("up", listOf(key(KeyEvent.VK_UP))) { navigate(0, -step) },
            Key("down", listOf(key(KeyEvent.VK_DOWN))) { navigate(0, step) },
            Key("left", listOf(key(KeyEvent.VK_LEFT), key(KeyEvent.VK_LEFT, shift), key(KeyEvent.VK_UP, shift))) { navigate(-step, 0) },
            Key("right", listOf(key(KeyEvent.VK_RIGHT), key(KeyEvent.VK_RIGHT, shift), key(KeyEvent.VK_DOWN, shift))) { navigate(step, 0) },
            Key("page-up", listOf(key(KeyEvent.VK_PAGE_UP), key(KeyEvent.VK_SPACE, shift))) { navigate(0, -page(true), page = true) },
            Key("page-down", listOf(key(KeyEvent.VK_PAGE_DOWN), key(KeyEvent.VK_SPACE))) { navigate(0, page(true), page = true) },
            Key("page-left", listOf(key(KeyEvent.VK_PAGE_UP, shift))) { navigate(-page(false), 0, page = true) },
            Key("page-right", listOf(key(KeyEvent.VK_PAGE_DOWN, shift))) { navigate(page(false), 0, page = true) },
            Key("top", listOf(key(KeyEvent.VK_HOME))) { navigate(0, -far(), edge = true) },
            Key("bottom", listOf(key(KeyEvent.VK_END))) { navigate(0, far(), edge = true) },
            Key("start", menu(KeyEvent.VK_HOME)) { navigate(-far(), -far(), edge = true) },
            Key("end", menu(KeyEvent.VK_END)) { navigate(far(), far(), edge = true) },
            Key("zoom-in", menu(KeyEvent.VK_EQUALS) + menu(KeyEvent.VK_EQUALS, shift) + menu(KeyEvent.VK_PLUS) + menu(KeyEvent.VK_ADD)) {
                zoomTo(wallZoomBy(zoom, KEY_ZOOM))
            },
            Key("zoom-out", menu(KeyEvent.VK_MINUS) + menu(KeyEvent.VK_SUBTRACT)) { zoomTo(wallZoomBy(zoom, 1 / KEY_ZOOM)) },
            Key("zoom-reset", menu(KeyEvent.VK_0) + menu(KeyEvent.VK_NUMPAD0)) { zoomTo(1.0) },
            Key("zoom-fit", listOf(key(KeyEvent.VK_F)) + menu(KeyEvent.VK_0, shift), { mode == WallMode.CARDS }) { fit() },
            Key("mode", listOf(key(KeyEvent.VK_M))) { switchMode(if (mode == WallMode.SKIM) WallMode.CARDS else WallMode.SKIM) },
            Key("next-hunk", listOf(key(KeyEvent.VK_N)), { mode == WallMode.SKIM }) { stepHunk(forward = true) },
            Key("previous-hunk", listOf(key(KeyEvent.VK_N, shift)), { mode == WallMode.SKIM }) { stepHunk(forward = false) },
            Key("fold-all", listOf(key(KeyEvent.VK_C, shift)), { mode == WallMode.SKIM }) { toggleFoldAll() },
            Key("summary", listOf(key(KeyEvent.VK_S)), { activePath?.let(::hasSummary) == true }) {
                views.firstOrNull { it.block.path == activePath }?.let(::toggleSummary)
            },
            Key("next-block", listOf(key(KeyEvent.VK_TAB))) { step(forward = true) },
            Key("previous-block", listOf(key(KeyEvent.VK_TAB, shift))) { step(forward = false) },
            Key("overview", listOf(key(KeyEvent.VK_ESCAPE)), { mode == WallMode.CARDS && (overview != null || activePath != null) }) {
                escapeCards()
            },
            Key("open", listOf(key(KeyEvent.VK_ENTER)), { activePath != null }) {
                views.firstOrNull { it.block.path == activePath }?.let { openLine(it.block.path, null) }
            },
            Key("compact", listOf(key(KeyEvent.VK_C)), { activePath != null }) {
                views.firstOrNull { it.block.path == activePath }?.let(::toggleCompact)
            },
        )
    }

    /** Pans the page while a mouse button [accepts] is dragged on [target]; the pointer's screen travel is zoom-independent. */
    private inner class Pan(private val target: JComponent, private val accepts: (MouseEvent) -> Boolean) : MouseAdapter() {
        private var pressed: Point? = null
        private var origin = Point()
        override fun mousePressed(e: MouseEvent) {
            if (!accepts(e)) return
            stopAnimation()
            origin = viewport()?.viewPosition ?: return
            pressed = e.locationOnScreen
            target.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        }
        override fun mouseDragged(e: MouseEvent) {
            val start = pressed ?: return
            scrollTo(origin.x - (e.locationOnScreen.x - start.x), origin.y - (e.locationOnScreen.y - start.y))
        }
        override fun mouseReleased(e: MouseEvent) {
            if (pressed == null) return
            pressed = null
            target.cursor = Cursor.getDefaultCursor()
        }
    }

    /** The null-layout, focusable surface: empty space pans the enclosing page, Ctrl/Cmd + wheel zooms, keys scroll and step. */
    internal inner class Surface : JComponent() {
        init {
            name = "${labels.id}-surface"
            layout = null
            isFocusable = true
            // Tab and Shift+Tab step through blocks rather than leaving the wall.
            focusTraversalKeysEnabled = false
            val pan = Pan(this) { SwingUtilities.isLeftMouseButton(it) || SwingUtilities.isMiddleMouseButton(it) }
            addMouseListener(pan)
            addMouseMotionListener(pan)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { requestFocusInWindow() }
            })
            addMouseWheelListener { e ->
                e.consume()
                if (e.isControlDown || e.isMetaDown) {
                    val viewport = viewport()
                    zoomTo(wallZoomBy(zoom, WHEEL_ZOOM.pow(-e.preciseWheelRotation)), viewport?.let { SwingUtilities.convertPoint(this, e.point, it) })
                } else {
                    val focused = focusedBlock?.takeIf {
                        if (e.isShiftDown) it.canScrollHorizontally else it.canScrollVertically
                    }
                    if (focused != null) {
                        val delta = (e.preciseWheelRotation * WHEEL_ROWS * focused.metrics.lineHeight).roundToInt()
                        if (e.isShiftDown) focused.scrollContent(delta, 0) else focused.scrollContent(0, delta)
                    } else {
                        stopAnimation()
                        scrollPane()?.let { it.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, it)) }
                    }
                }
            }
            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) { repaintActive() }
                override fun focusLost(e: FocusEvent) { repaintActive() }
            })
            addHierarchyListener { e ->
                watch(scrollPane())
                if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                    if (isShowing) SwingUtilities.invokeLater { focusWhenOpened() } else stopAnimation()
                }
            }
            keys().forEach { key ->
                val action = object : AbstractAction(key.name) {
                    override fun actionPerformed(e: ActionEvent?) { if (key.enabled()) key.run() }
                    override fun isEnabled() = key.enabled()
                }
                actionMap.put(key.name, action)
                key.strokes.forEach { getInputMap(WHEN_FOCUSED).put(it, key.name) }
                // Component shortcuts come before the IDE keymap, whose editor actions would otherwise take keys like Tab or Escape.
                // They reach the surface's children too, so typing in a chat window must not scroll or step the wall.
                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) { if (!typing()) key.run() }
                    override fun update(e: AnActionEvent) { e.presentation.isEnabled = !typing() && key.enabled() }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                }.registerCustomShortcutSet(CustomShortcutSet(*key.strokes.map { KeyboardShortcut(it, null) }.toTypedArray()), this)
            }
        }

        private fun repaintActive() { views.firstOrNull { it.block.path == activePath }?.repaint() }

        /** Whether the keyboard is in an editable field or a chat panel, rather than on the wall or its read-only summaries. */
        private fun typing(): Boolean {
            val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            return owner is JTextComponent && SwingUtilities.isDescendingFrom(owner, this) &&
                (owner.isEditable || SwingUtilities.getAncestorOfClass(WallChatPanel::class.java, owner) != null)
        }

        override fun doLayout() {
            ensureLayout()
            views.forEach { view ->
                val path = view.block.path
                val at = positions[path] ?: Point(0, 0)
                val size = view.preferredSize
                view.setBounds((at.x * zoom).roundToInt(), (at.y * zoom).roundToInt(), size.width, size.height)
                view.doLayout()
                val tab = chatTabs[path] ?: return@forEach
                tab.bounds = chatTabBounds(view.bounds)
                if (mode == WallMode.CARDS) chatPanels[path]?.takeIf { path in chatOpen }?.let { panel ->
                    panel.setBounds(tab.x + tab.width, view.y, wallChatWidth(), wallChatHeight(view.height))
                    panel.validate()
                }
            }
            moreView?.let { more ->
                val at = morePosition ?: Point(gap, gap)
                val size = more.preferredSize
                more.setBounds((at.x * zoom).roundToInt(), (at.y * zoom).roundToInt(), size.width, size.height)
            }
            if (mode == WallMode.SKIM) placeFileChats()
            placeCanvasChat()
        }

        /** The Chat tab hanging off a block's right edge near its top, scaled with the zoom so it stays in the gap. */
        private fun chatTabBounds(block: Rectangle): Rectangle {
            val width = (CHAT_TAB_WIDTH * zoom).roundToInt().coerceAtLeast(JBUI.scale(4))
            val height = (CHAT_TAB_HEIGHT * zoom).roundToInt().coerceAtLeast(JBUI.scale(12)).coerceAtMost(block.height.coerceAtLeast(1))
            // One pixel under the block's border, so the tab reads as part of it.
            return Rectangle(block.x + block.width - 1, block.y + (JBUI.scale(10) * zoom).roundToInt(), width, height)
        }

        override fun getPreferredSize(): Dimension {
            ensureLayout()
            if (views.isEmpty()) return Dimension(gap, gap)
            val extent = wallExtent(extentRects(), gap)
            var width = (extent.width * zoom).roundToInt()
            var height = (extent.height * zoom).roundToInt()
            // An unfolded chat window may reach past the blocks; the page grows so it can be scrolled to. The skim column keeps
            // them over their blocks.
            if (mode == WallMode.CARDS) chatOpen.forEach { path ->
                val view = views.firstOrNull { it.block.path == path } ?: return@forEach
                val at = positions[path] ?: Point(0, 0)
                val x = (at.x * zoom).roundToInt() + view.preferredSize.width + (CHAT_TAB_WIDTH * zoom).roundToInt()
                val y = (at.y * zoom).roundToInt()
                width = maxOf(width, x + wallChatWidth() + gap)
                height = maxOf(height, y + wallChatHeight(view.preferredSize.height) + gap)
            }
            // The wall's own chat window needs room even on a small wall.
            if (canvasChatOpen) {
                val margin = JBUI.scale(8)
                width = maxOf(width, wallChatWidth() + 2 * margin)
                height = maxOf(height, wallCanvasChatHeight(0) + 2 * margin)
            }
            return Dimension(width, height)
        }

        override fun paintComponent(g: Graphics) {
            if (views.isNotEmpty()) return
            g.color = UIUtil.getContextHelpForeground()
            g.font = JBFont.label()
            g.drawString(labels.emptyWall, JBUI.scale(24), JBUI.scale(24) + g.fontMetrics.ascent)
        }
    }

    /**
     * One file, painted as a diff's `git diff` rows or a source file's numbered lines under a quiet path header; dragging it
     * anywhere moves it. A compact block is capped in height and its body scrolls under the fixed header.
     */
    internal inner class BlockView(val block: WallBlock, initialWidth: Int, internal val metrics: WallMetrics) : JComponent() {
        /** The block's width at 100%: the cards' shared width, or the skim column's, which follows the page. */
        internal var blockWidth: Int = initialWidth
        /** The block's whole height at 100%. */
        val fullHeight = wallBlockHeight(block, metrics)
        /** The characters of the line-number gutter source rows paint before their text; none for diff rows. */
        private val gutter = wallGutterChars(block)
        private val skim: Boolean get() = mode == WallMode.SKIM
        /** The cap (at 100%) while the block is compact; the skim column never caps. */
        private val cap: Int? get() = if (skim) null else compact[block.path]?.takeIf { it < fullHeight }
        internal val capHeight: Int? get() = cap
        internal val isCompact: Boolean get() = cap != null
        /** Whether the skim column shows the block as its header alone. */
        internal val isFolded: Boolean get() = skim && block.path in folded
        /** Whether the summary takes the rows' place, as on the cards; the skim column shows it above them instead. */
        private val replacesBody: Boolean get() = showsSummary && !skim
        private var summaryPane: LinkedTextPane? = null
        private var summaryScroll: JBScrollPane? = null
        private var inlineSummaryHolder: InlineSummaryHolder? = null
        internal val summaryTextPane: LinkedTextPane? get() = summaryPane
        private fun isSketch(): Boolean = textFont().size2D * zoom.toFloat() < SKETCH_FONT_SIZE
        private fun richSummaryAvailable(): Boolean =
            project != null && textScope != null && summary != null && !isSketch() && block.path in activeSummaryPaths
        /** The summary rows the skim column shows above the block's rows, and the height (at 100%) they take. */
        private val inlineSummary: List<String> get() = if (skim && showsSummary && !richSummaryAvailable()) summaryRows() else emptyList()
        private val lead: Int get() = when {
            !skim || !showsSummary -> 0
            richSummaryAvailable() -> measureSummaryPaneLogicalHeight() + metrics.padding / 2
            else -> inlineSummary.size.takeIf { it > 0 }?.let { it * metrics.lineHeight + metrics.padding } ?: 0
        }
        /** The block's painted size at 100%. */
        val logical: Dimension get() = Dimension(blockWidth, when {
            isFolded -> metrics.headerHeight
            skim -> fullHeight + lead
            else -> cap ?: fullHeight
        })
        /** Whether Tab, Enter, C and the focus outline are on this block. */
        internal val active: Boolean get() = activePath == block.path
        private val offset: Int get() = if (isCompact && !replacesBody) scrolls[block.path] ?: 0 else 0
        /** Current body scroll at 100%, for both the diff/source and summary views. */
        internal val bodyScroll: Point get() = when {
            replacesBody && summaryScroll?.parent === this -> Point(0, (summaryScrollOffsets[block.path] ?: 0.0).roundToInt())
            replacesBody -> Point(0, (summaryScrolls[block.path] ?: 0) * metrics.lineHeight)
            else -> Point(horizontalScrolls[block.path] ?: 0, offset)
        }
        /** Where the header paints from the block's top: under the viewport's top edge while the skim column scrolls past it. */
        internal val stickyTop: Int get() {
            if (!skim) return 0
            val visible = visibleRect.takeIf { !it.isEmpty } ?: return 0
            return wallStickyOffset(visible.y, height, (metrics.headerHeight * zoom).roundToInt())
        }

        /** Where row [index] is painted, from the block's top, at the current zoom. */
        internal fun rowTop(index: Int): Int =
            ((metrics.headerHeight + metrics.padding + lead - offset + index * metrics.lineHeight) * zoom).roundToInt()
        /** Where the Compact switch was last painted, or null when the block is too short for it. */
        private var toggle: Rectangle? = null
        /** Where the "..." menu button was last painted, or null while the header is sketched. */
        private var menuToggle: Rectangle? = null
        /** Where the Summary switch was last painted, or null when the file has no summary and none can be asked for. */
        internal var summaryToggle: Rectangle? = null
            private set
        private val summary: String? get() = summaries[block.path]
        /** Whether the file's summary is being written and has not arrived yet. */
        private val writingSummary: Boolean get() = summary == null && block.path in summarizing
        /** Whether the summary, or the note that it is being written, is shown in place of the rows. */
        internal val showsSummary: Boolean get() = block.path in summarized && (summary != null || writingSummary)
        private val summaryTooltip: String get() = when {
            showsSummary -> labels.bodyTooltip
            summary == null && !writingSummary -> labels.summarizeTooltip
            else -> labels.summaryTooltip
        }

        init {
            name = "${labels.id}-block:${block.path}"
            val drag = object : MouseAdapter() {
                /** Where the block was grabbed at 100%, so it moves with the pointer rather than jumping to it. */
                private var grab: Point2D.Double? = null
                override fun mousePressed(e: MouseEvent) {
                    if (!skim) stopAnimation()
                    if (contextMenu(e)) return
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    surface.requestFocusInWindow()
                    activate(this@BlockView)
                    if (onToggle(e.point)) {
                        toggleCompact(this@BlockView)
                        return
                    }
                    if (onSummaryToggle(e.point)) {
                        toggleSummary(this@BlockView)
                        return
                    }
                    if (onMenu(e.point)) {
                        showMenu(e.point)
                        return
                    }
                    if (onMoreLines(e.point)) {
                        loadMoreLines(block.path)
                        return
                    }
                    // The skim column keeps its order; its blocks do not move.
                    if (skim) return
                    grab = Point2D.Double(e.x / zoom, e.y / zoom)
                }
                override fun mouseDragged(e: MouseEvent) {
                    val offset = grab ?: return
                    stopAnimation()
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    // Measured on the surface, so the block stays under the pointer while the page auto-scrolls.
                    val pointer = SwingUtilities.convertPoint(this@BlockView, e.point, surface)
                    val at = Point((pointer.x / zoom - offset.x).roundToInt().coerceAtLeast(0), (pointer.y / zoom - offset.y).roundToInt().coerceAtLeast(0))
                    positions[block.path] = at
                    layoutRects[block.path] = layoutRects[block.path]?.copy(x = at.x, y = at.y)
                        ?: WallRect(at.x, at.y, logical.width, logical.height)
                    // A moved block is the reader's layout; a later viewport size must not repack it.
                    arranged = true
                    location = Point((at.x * zoom).roundToInt(), (at.y * zoom).roundToInt())
                    surface.revalidate()
                    surface.scrollRectToVisible(Rectangle(pointer.x, pointer.y, 1, 1))
                }
                override fun mouseReleased(e: MouseEvent) {
                    if (contextMenu(e)) return
                    if (grab == null) return
                    grab = null
                    cursor = Cursor.getDefaultCursor()
                }
                /** Hands a popup trigger (press or release, by platform) to the host's [onContext]; true when it took the event. */
                private fun contextMenu(e: MouseEvent): Boolean {
                    val host = onContext
                    if (host == null || !e.isPopupTrigger || onToggle(e.point) || onSummaryToggle(e.point) || onMenu(e.point)) return false
                    surface.requestFocusInWindow()
                    activate(this@BlockView)
                    host(block, lineAt(e.y), RelativePoint(e))
                    e.consume()
                    return true
                }
                override fun mouseClicked(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e) || onToggle(e.point) || onSummaryToggle(e.point) || onMenu(e.point) || onMoreLines(e.point)) return
                    if (e.clickCount == 2) {
                        if (!skim) stopAnimation()
                        openLine(block.path, lineAt(e.y))
                    } else if (e.clickCount == 1 && !skim) {
                        focusOnClick(block.path)
                    }
                }
                override fun mouseMoved(e: MouseEvent) {
                    cursor = if (onToggle(e.point) || onSummaryToggle(e.point) || onMenu(e.point) || onMoreLines(e.point)) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                }
            }
            addMouseListener(drag)
            addMouseMotionListener(drag)
            val pan = Pan(this) { SwingUtilities.isMiddleMouseButton(it) }
            addMouseListener(pan)
            addMouseMotionListener(pan)
            addMouseWheelListener { e ->
                // Skim blocks fit the page, so Shift+wheel can reveal a row wider than the column.
                if (skim && e.isShiftDown && !e.isControlDown && !e.isMetaDown && maxHorizontalScroll > 0) {
                    val first = horizontalScrolls[block.path] ?: 0
                    val moved = (first + (e.preciseWheelRotation * WHEEL_ROWS * metrics.lineHeight).roundToInt())
                        .coerceIn(0, maxHorizontalScroll)
                    if (moved != first) {
                        e.consume()
                        horizontalScrolls[block.path] = moved
                        repaint()
                        return@addMouseWheelListener
                    }
                }
                e.consume()
                surface.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, surface))
            }
            autoscrolls = true
            ToolTipManager.sharedInstance().registerComponent(this)
        }

        /** Scrolls the body without moving the canvas or header. Summary offsets are measured in rows (or logical px for rich summaries). */
        internal fun scrollContent(dx: Int, dy: Int) {
            if (replacesBody && summaryScroll?.parent === this) {
                val scroller = summaryScroll!!
                val pane = summaryPane!!
                val viewHeight = scroller.viewport.height.coerceAtLeast(1)
                val contentHeight = maxOf(pane.height, pane.preferredSize.height)
                val maxPx = (contentHeight - viewHeight).coerceAtLeast(0)
                val currentPx = scroller.viewport.viewPosition.y
                val nextPx = (currentPx.toDouble() + dy.toDouble() * zoom).roundToInt()
                    .let { if (dy > 0 && dy >= Int.MAX_VALUE / 4) maxPx else if (dy < 0 && dy <= -(Int.MAX_VALUE / 4)) 0 else it }
                    .coerceIn(0, maxPx)
                scroller.viewport.viewPosition = Point(0, nextPx)
                summaryScrollOffsets[block.path] = nextPx / zoom
            } else if (replacesBody) {
                val rows = summaryRows()
                val delta = if (dy == 0) 0 else (dy / metrics.lineHeight).let {
                    if (it == 0) (if (dy < 0) -1 else 1) else it
                }
                summaryScrolls[block.path] = wallSummaryScroll(summaryScrolls[block.path] ?: 0, delta,
                    rows.size, wallBodyRows(logical.height, metrics))
            } else {
                cap?.let { scrolls[block.path] = wallBodyScroll(offset, dy, fullHeight, it) }
                horizontalScrolls[block.path] = ((horizontalScrolls[block.path] ?: 0) + dx).coerceIn(0, maxHorizontalScroll)
            }
            repaint()
        }

        /** The characters of the block's longest row, its gutter included. */
        private val longest: Int = (block.lines.maxOfOrNull { it.text.length } ?: 0) + gutter
        private val maxHorizontalScroll: Int get() = (longest * metrics.charWidth + 2 * metrics.padding - blockWidth).coerceAtLeast(0)
        val canScrollHorizontally: Boolean get() = !replacesBody && maxHorizontalScroll > 0
        val canScrollVertically: Boolean
            get() = when {
                replacesBody && summaryScroll?.parent === this -> {
                    val scroller = summaryScroll!!
                    val pane = summaryPane!!
                    scroller.verticalScrollBar.maximum > scroller.viewport.extentSize.height ||
                        pane.preferredSize.height > scroller.viewport.height
                }
                replacesBody -> summaryRows().size > wallBodyRows(logical.height, metrics)
                else -> cap != null
            }

        private fun onToggle(point: Point) = toggle?.contains(point) == true

        private fun onSummaryToggle(point: Point) = summaryToggle?.contains(point) == true

        private fun onMenu(point: Point) = menuToggle?.contains(point) == true

        internal fun canLoadMoreLines(): Boolean =
            skim && !replacesBody && !isFolded && block.hiddenLines > 0 && block.loadMore != null

        internal fun onMoreLines(point: Point): Boolean {
            if (!canLoadMoreLines()) return false
            val stick = stickyTop
            if (stick > 0 && point.y < stick + metrics.headerHeight * zoom) return false
            return wallRowAt(point.y / zoom, offset - lead, metrics)?.let { it >= block.lines.size } == true
        }

        /** Only a block taller than the cap has anything to compact; every block of the skim column folds. */
        private fun canCompact(): Boolean = skim || isCompact || compactCap(metrics)?.let { fullHeight > it } == true

        /** The Compact switch's word, which folds in the skim column. */
        private fun compactText(): String = when {
            skim -> if (isFolded) UNFOLD else FOLD
            else -> COMPACT
        }

        private fun compactTooltip(): String = when {
            skim -> if (isFolded) "Show this file's rows again (C)" else "Fold this file to its header (C); Shift+C folds every file"
            isCompact -> labels.expandTooltip
            else -> labels.compactTooltip
        }

        /** The 0-based row indices currently emphasized in this block after navigating to a link target. */
        internal fun highlightedRows(): Set<Int> = highlightedRows(block.path)

        /** The block's "..." menu: display switches, context picker, and remove action for added cards. */
        internal fun buildMenu(): JPopupMenu {
            val menu = JPopupMenu().apply { name = "${labels.id}-menu:${block.path}" }
            fun item(text: String, tooltip: String?, enabled: Boolean, action: () -> Unit) {
                menu.add(JMenuItem(text).apply {
                    name = "${labels.id}-menu-item:$text"
                    toolTipText = tooltip
                    isEnabled = enabled
                    addActionListener { action() }
                })
            }
            item(if (isCompact) "Expand" else compactText(), compactTooltip(), canCompact()) {
                toggleCompact(this@BlockView)
            }
            item(if (showsSummary) "Hide summary" else SUMMARY, summaryTooltip, hasSummary(block.path)) {
                toggleSummary(this@BlockView)
            }
            // The Chat tab scrolls away with a tall skim block's top; its sticky header keeps the chat in reach.
            if (chats != null && skim) item(if (chatShown(block.path)) "Hide chat" else "Chat", labels.chatTabTooltip, true) {
                toggleChat(block.path)
            }
            if (canLoadMoreLines()) {
                item("Load remaining ${block.hiddenLines} lines", "Show the remaining lines of this file", true) {
                    loadMoreLines(block.path)
                }
            }
            if (onContext != null) {
                item("Context…", "Explore this file in other views", true) {
                    onContext.invoke(block, null, RelativePoint(this, Point(width - 10, metrics.headerHeight / 2)))
                }
            } else openOnCanvas?.let { open ->
                item("Open on canvas", "Explore this file as source cards on a canvas session", true) { open(block.path) }
            }
            onRemoveBlock?.takeIf { linkHost?.isAddedCard(block.path) == true }?.let { remove ->
                item("Remove from wall", "Remove this added file from the wall", true) {
                    remove(block.path)
                }
            }
            return menu
        }

        private fun showMenu(at: Point) {
            buildMenu().show(this, at.x, at.y)
        }

        /** Ensures [summaryPane] and its scroll/inline wrapper match the current mode, zoom, and summary visibility. */
        internal fun syncSummary() {
            val activeRich = showsSummary && !isFolded && richSummaryAvailable()
            if (!activeRich) {
                summaryScroll?.takeIf { it.parent === this }?.let(::remove)
                inlineSummaryHolder?.takeIf { it.parent === this }?.let(::remove)
                if (!showsSummary || block.path !in activeSummaryPaths) {
                    summaryPane = null
                    summaryScroll = null
                    inlineSummaryHolder = null
                }
                return
            }
            val pane = summaryPane ?: LinkedTextPane(project!!, textScope!!).apply {
                name = "${labels.id}-summary:${block.path}"
                navigator = textNavigator(block.path, fromChat = false)
                onRendered = { onSummaryRendered() }
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            surface.requestFocusInWindow()
                            activate(this@BlockView)
                        } else if (SwingUtilities.isMiddleMouseButton(e)) {
                            this@BlockView.dispatchEvent(SwingUtilities.convertMouseEvent(this@apply, e, this@BlockView))
                        }
                        if (e.isPopupTrigger && !e.isConsumed) {
                            surface.requestFocusInWindow()
                            activate(this@BlockView)
                            onContext?.invoke(block, null, RelativePoint(e))
                            e.consume()
                        }
                    }
                    override fun mouseReleased(e: MouseEvent) {
                        if (SwingUtilities.isMiddleMouseButton(e)) {
                            this@BlockView.dispatchEvent(SwingUtilities.convertMouseEvent(this@apply, e, this@BlockView))
                        }
                        if (e.isPopupTrigger && !e.isConsumed) {
                            surface.requestFocusInWindow()
                            activate(this@BlockView)
                            onContext?.invoke(block, null, RelativePoint(e))
                            e.consume()
                        }
                    }
                })
                addMouseMotionListener(object : MouseAdapter() {
                    override fun mouseDragged(e: MouseEvent) {
                        if (SwingUtilities.isMiddleMouseButton(e)) {
                            this@BlockView.dispatchEvent(SwingUtilities.convertMouseEvent(this@apply, e, this@BlockView))
                        }
                    }
                })
                addMouseWheelListener { e ->
                    e.consume()
                    this@BlockView.dispatchEvent(SwingUtilities.convertMouseEvent(this@apply, e, this@BlockView))
                }
            }.also { summaryPane = it }
            updateSummaryPaneAppearance(pane)
            pane.markdown = summary.orEmpty()
            if (skim) {
                summaryScroll?.takeIf { it.parent === this }?.let(::remove)
                val holder = inlineSummaryHolder ?: InlineSummaryHolder().also { inlineSummaryHolder = it }
                if (pane.parent !== holder) {
                    holder.removeAll()
                    holder.add(pane)
                }
                if (holder.parent !== this) add(holder)
            } else {
                inlineSummaryHolder?.takeIf { it.parent === this }?.let(::remove)
                val scroller = summaryScroll ?: JBScrollPane(pane).apply {
                    border = JBUI.Borders.empty()
                    isOpaque = false
                    viewport.isOpaque = false
                    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    isWheelScrollingEnabled = false
                    addMouseWheelListener { e ->
                        e.consume()
                        this@BlockView.dispatchEvent(SwingUtilities.convertMouseEvent(this@apply, e, this@BlockView))
                    }
                    viewport.addChangeListener {
                        if (parent === this@BlockView && !skim) {
                            summaryScrollOffsets[block.path] = viewport.viewPosition.y / zoom
                        }
                    }
                }.also { summaryScroll = it }
                if (scroller.viewport.view !== pane) scroller.setViewportView(pane)
                if (scroller.parent !== this) add(scroller)
            }
            if (width > 0 && height > 0) doLayout()
        }

        private fun updateSummaryPaneAppearance(pane: LinkedTextPane) {
            val base = UIUtil.getLabelFont()
            val targetFont = base.deriveFont(base.size2D * zoom.toFloat())
            if (pane.font != targetFont) pane.font = targetFont
            val pad = (metrics.padding * zoom).roundToInt().coerceAtLeast(1)
            val insets = pane.insets
            if (insets.top != pad || insets.left != pad || insets.bottom != pad || insets.right != pad) {
                pane.border = pixelBorder(pad, pad, pad, pad)
            }
        }

        private fun onSummaryRendered() {
            if (skim) {
                packedFor = null
                surface.revalidate()
                surface.repaint()
            } else {
                summaryPane?.revalidate()
                summaryScroll?.revalidate()
                if (width > 0 && height > 0) doLayout()
                repaint()
            }
        }

        private fun measureSummaryPaneLogicalHeight(): Int {
            val pane = summaryPane ?: return summaryRows().size * metrics.lineHeight + metrics.padding
            updateSummaryPaneAppearance(pane)
            val targetW = ((blockWidth * zoom).roundToInt() - 2).coerceAtLeast(JBUI.scale(40))
            val root = pane.ui?.getRootView(pane)
            if (root != null && pane.document.length > 0) {
                val insets = pane.insets
                val innerW = (targetW - insets.left - insets.right).coerceAtLeast(1).toFloat()
                root.setSize(innerW, Int.MAX_VALUE.toFloat())
                val pxHeight = ceil(root.getPreferredSpan(View.Y_AXIS).toDouble()).toInt() + insets.top + insets.bottom
                return ceil(pxHeight / zoom).toInt().coerceAtLeast(metrics.lineHeight + 2 * metrics.padding)
            }
            return summaryRows().size * metrics.lineHeight + metrics.padding
        }

        override fun doLayout() {
            if (width <= 2 || height <= 2) return
            val z = zoom
            val headerPx = (metrics.headerHeight * z).roundToInt()
            if (replacesBody && summaryScroll?.parent === this) {
                val scroller = summaryScroll!!
                val pane = summaryPane!!
                updateSummaryPaneAppearance(pane)
                val bw = (width - 2).coerceAtLeast(1)
                val bh = (height - headerPx - 1).coerceAtLeast(1)
                scroller.setBounds(1, headerPx, bw, bh)
                scroller.doLayout()
                scroller.viewport.doLayout()
                val vpW = scroller.viewport.width.coerceAtLeast(1)
                pane.setSize(vpW, maxOf(pane.height, 1))
                val prefH = pane.preferredSize.height
                pane.setSize(vpW, maxOf(prefH, scroller.viewport.height))
                scroller.doLayout()
                scroller.viewport.doLayout()
                val saved = summaryScrollOffsets[block.path] ?: 0.0
                val maxY = (prefH - scroller.viewport.height).coerceAtLeast(0)
                val targetY = (saved * z).roundToInt().coerceIn(0, maxY)
                if (scroller.viewport.viewPosition.y != targetY) scroller.viewport.viewPosition = Point(0, targetY)
            } else if (skim && showsSummary && inlineSummaryHolder?.parent === this) {
                val holder = inlineSummaryHolder!!
                val pane = summaryPane!!
                updateSummaryPaneAppearance(pane)
                val topPx = ((metrics.headerHeight + metrics.padding / 2.0) * z).roundToInt()
                val bandH = (measureSummaryPaneLogicalHeight() * z).roundToInt().coerceAtLeast(1)
                holder.setBounds(1, topPx, (width - 2).coerceAtLeast(1), bandH)
                holder.doLayout()
            }
        }

        override fun paintChildren(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                val clipTop = stickyTop + (metrics.headerHeight * zoom).roundToInt()
                g2.clipRect(1, clipTop, (width - 2).coerceAtLeast(0), (height - clipTop - 1).coerceAtLeast(0))
                super.paintChildren(g2)
            } finally {
                g2.dispose()
            }
        }

        /** Hosts the skim column's inline [LinkedTextPane] summary while deferring sticky-header hits to [BlockView]. */
        private inner class InlineSummaryHolder : JComponent() {
            init {
                layout = null
                isOpaque = false
            }

            override fun doLayout() {
                val pane = summaryPane ?: return
                pane.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
            }

            override fun contains(x: Int, y: Int): Boolean {
                val clipTop = stickyTop + (metrics.headerHeight * zoom).roundToInt()
                if (this.y + y < clipTop) return false
                return super.contains(x, y)
            }

            override fun paintComponent(g: Graphics) {
                g.color = ColorUtil.mix(UIUtil.getListBackground(), JBUI.CurrentTheme.Link.Foreground.ENABLED, 0.07)
                g.fillRect(0, 0, width, height)
            }
        }

        /** The summary wrapped to the rows this block's width holds. */
        private fun summaryRows(): List<String> =
            wallWrap(summary ?: if (writingSummary) labels.summarizing else "", (blockWidth - 2 * metrics.padding) / metrics.charWidth.coerceAtLeast(1))

        override fun getToolTipText(event: MouseEvent): String? = when {
            onToggle(event.point) -> compactTooltip()
            onSummaryToggle(event.point) -> summaryTooltip
            onMenu(event.point) -> "More actions"
            onMoreLines(event.point) -> "Load the remaining ${block.hiddenLines} lines of this file"
            else -> null
        }

        override fun getPreferredSize() = logical.let { Dimension((it.width * zoom).roundToInt(), (it.height * zoom).roundToInt()) }

        /** The row at [y] (painted, at the current zoom), or null on the header and padding. */
        internal fun lineAt(y: Int): WallLine? {
            if (replacesBody || isFolded) return null
            val stick = stickyTop
            // The rows the sticky header covers are not under the pointer; the header is.
            if (stick > 0 && y < stick + metrics.headerHeight * zoom) return null
            return wallRowAt(y / zoom, offset - lead, metrics)?.let(block.lines::getOrNull)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            val z = zoom
            val arc = (JBUI.scale(8) * z).roundToInt().coerceAtLeast(2)
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Zoomed text keeps its advance in proportion, so rows stay as wide as the block was sized for.
                if (z != 1.0) g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                val scheme = scheme()
                val background = scheme.defaultBackground
                val muted = UIUtil.getContextHelpForeground()
                g2.color = background
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = ColorUtil.withAlpha(JBColor.border(), 0.6)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.clipRect(1, 1, width - 2, height - 2)
                val text = textFont().let { it.deriveFont(it.size2D * z.toFloat()) }
                // Below a legible size rows are drawn as their shapes, which reads better in an overview and paints faster.
                val sketch = text.size2D < SKETCH_FONT_SIZE
                // The header stays put while a compact body scrolls beneath it, and sticks to the page's top in the skim column.
                val stick = stickyTop
                paintHeader(g2, scheme, muted, sketch, stick)
                val bodyTop = stick + (metrics.headerHeight * z).roundToInt()
                if (stick > 0) {
                    g2.color = ColorUtil.withAlpha(JBColor.border(), 0.6)
                    g2.drawLine(1, bodyTop - 1, width - 2, bodyTop - 1)
                }
                g2.clipRect(0, bodyTop, width, height - bodyTop)
                g2.font = text
                val ascent = g2.fontMetrics.ascent
                val pad = metrics.padding * z
                val lineHeight = metrics.lineHeight * z
                val inline = inlineSummary
                if (inline.isNotEmpty() && inlineSummaryHolder?.parent !== this) paintInlineSummary(g2, inline, scheme, background, sketch, ascent)
                val top = (metrics.headerHeight + metrics.padding + lead - offset) * z
                fun rowTop(index: Int) = (top + index * lineHeight).roundToInt()
                val clip = g2.clipBounds ?: Rectangle(0, 0, width, height)
                val from = floor((clip.y - top) / lineHeight).toInt().coerceAtLeast(0)
                val wrapped = if (replacesBody) summaryRows() else null
                val first = wrapped?.let { (summaryScrolls[block.path] ?: 0).coerceAtMost((it.size - 1).coerceAtLeast(0)) } ?: 0
                val shown = wrapped?.drop(first)
                val rowCount = shown?.size ?: block.lines.size
                val to = (ceil((clip.y + clip.height - top) / lineHeight).toInt() + 1).coerceAtMost(rowCount)
                val bodyTransform = g2.transform
                val horizontal = if (replacesBody) 0 else horizontalScrolls[block.path] ?: 0
                g2.translate(-horizontal * z, 0.0)
                val spans = rowSpans(block)
                val added = tint(scheme, DiffColors.DIFF_INSERTED, ADDED, background)
                val removed = tint(scheme, DiffColors.DIFF_DELETED, REMOVED, background)
                val highlighted = highlightedRows(block.path)
                val focusColor = JBUI.CurrentTheme.Focus.focusColor()
                if (shown != null) {
                    if (summaryScroll?.parent === this) {
                        g2.color = UIUtil.getListBackground()
                        g2.fillRect(1, bodyTop, width - 2, (height - bodyTop - 1).coerceAtLeast(0))
                    } else {
                        g2.color = scheme.defaultForeground
                        for (index in from until to) {
                            val y = rowTop(index)
                            if (sketch) sketch(g2, " " + shown[index], pad, y, rowTop(index + 1) - y)
                            else g2.drawString(shown[index], pad.toFloat(), (top + index * lineHeight + ascent).toFloat())
                        }
                        paintSummaryIndicator(g2, first + shown.size, first, bodyTop, muted)
                    }
                } else {
                    for (index in from until to) {
                        val line = block.lines[index]
                        val y = rowTop(index)
                        val next = rowTop(index + 1)
                        val rowX = (horizontal * z).roundToInt() + 1
                        when (line.kind) {
                            WallLine.Kind.ADDED -> added
                            WallLine.Kind.REMOVED -> removed
                            else -> null
                        }?.let { g2.color = it; g2.fillRect(rowX, y, width - 2, next - y) }
                        if (index in highlighted) {
                            g2.color = ColorUtil.withAlpha(focusColor, 0.14)
                            g2.fillRect(rowX, y, width - 2, next - y)
                            g2.color = focusColor
                            val barW = (JBUI.scale(3) * z).roundToInt().coerceAtLeast(2)
                            g2.fillRect(rowX, y, barW, next - y)
                        }
                        val baseline = (top + index * lineHeight + ascent).toFloat()
                        if (line.kind == WallLine.Kind.SOURCE) {
                            // A source row has no marker: its number, right-aligned in the gutter, comes before its text.
                            val indent = pad + (gutter - 1) * metrics.charWidth * z
                            if (!sketch) {
                                g2.color = ColorUtil.withAlpha(muted, 0.8)
                                g2.drawString(line.new?.toString().orEmpty().padStart(gutter - 1), pad.toFloat(), baseline)
                            }
                            g2.color = scheme.defaultForeground
                            if (sketch) sketch(g2, " " + line.text, indent, y, next - y)
                            else drawRow(g2, line.text, (indent + metrics.charWidth * z).toFloat(), baseline, spans?.getOrNull(index))
                            continue
                        }
                        g2.color = when (line.kind) {
                            WallLine.Kind.HUNK, WallLine.Kind.NOTE -> muted
                            else -> scheme.defaultForeground
                        }
                        if (sketch) sketch(g2, line.text, pad, y, next - y)
                        else drawRow(g2, line.text, pad.toFloat(), baseline, spans?.getOrNull(index))
                    }
                    val canExpand = canLoadMoreLines()
                    val footer = when {
                        block.lines.isEmpty() -> labels.emptyBlock
                        canExpand -> labels.hiddenSkimLines(block.hiddenLines)
                        block.hiddenLines > 0 -> labels.hiddenLines(block.hiddenLines)
                        else -> null
                    }
                    if (footer != null && !sketch) {
                        g2.color = if (canExpand) JBUI.CurrentTheme.Link.Foreground.ENABLED else muted
                        g2.drawString(footer, pad.toFloat(), (top + block.lines.size * lineHeight + ascent).toFloat())
                    }
                    g2.transform = bodyTransform
                    cap?.let { paintScrollIndicator(g2, it, bodyTop, muted) }
                    paintHorizontalScrollIndicator(g2, horizontal, muted)
                }
            } finally {
                g2.dispose()
            }
            if (active && surface.isFocusOwner) paintFocus(g, arc)
        }

        /** The skim column's summary [rows] on a quiet band between the header and the block's rows. */
        private fun paintInlineSummary(g2: Graphics2D, rows: List<String>, scheme: EditorColorsScheme, background: Color, sketch: Boolean, ascent: Int) {
            val z = zoom
            val pad = metrics.padding * z
            val lineHeight = metrics.lineHeight * z
            val top = (metrics.headerHeight + metrics.padding / 2.0) * z
            g2.color = ColorUtil.mix(background, JBUI.CurrentTheme.Link.Foreground.ENABLED, 0.07)
            g2.fillRect(1, top.roundToInt(), width - 2, (rows.size * lineHeight + pad / 2).roundToInt())
            g2.color = scheme.defaultForeground
            val first = (metrics.headerHeight + metrics.padding) * z
            val clip = g2.clipBounds ?: Rectangle(0, 0, width, height)
            val from = floor((clip.y - first) / lineHeight).toInt().coerceAtLeast(0)
            val to = (ceil((clip.y + clip.height - first) / lineHeight).toInt() + 1).coerceAtMost(rows.size)
            for (index in from until to) {
                val y = (first + index * lineHeight).roundToInt()
                if (sketch) sketch(g2, " " + rows[index], pad, y, (first + (index + 1) * lineHeight).roundToInt() - y)
                else g2.drawString(rows[index], pad.toFloat(), (first + index * lineHeight + ascent).toFloat())
            }
        }

        /** Fonts in the bold and italic styles of the last row font, which every row of a paint shares. */
        private var styled: Pair<Font, MutableMap<Int, Font>>? = null

        /**
         * [text] in the current font and colour, with its syntax [spans] in their own colours and styles. Each piece advances by
         * its width in the plain font, so columns stay where the plain row has them.
         */
        private fun drawRow(g2: Graphics2D, text: String, x: Float, baseline: Float, spans: List<WallSpan>?) {
            if (spans == null) {
                g2.drawString(text, x, baseline)
                return
            }
            val base = g2.font
            val color = g2.color
            val fonts = styled?.takeIf { it.first == base }?.second ?: mutableMapOf<Int, Font>().also { styled = base to it }
            val context = g2.fontRenderContext
            var at = x
            for ((piece, style) in wallSegments(text, spans)) {
                g2.font = style?.fontStyle?.takeIf { it != Font.PLAIN }?.let { fonts.getOrPut(it) { base.deriveFont(it) } } ?: base
                g2.color = style?.color ?: color
                g2.drawString(piece, at, baseline)
                at += base.getStringBounds(piece, context).width.toFloat()
            }
            g2.font = base
            g2.color = color
        }

        /** The same thumb for a long summary: [first] of [total] rows is at the top of the body. */
        private fun paintSummaryIndicator(g2: Graphics2D, total: Int, first: Int, bodyTop: Int, muted: Color) {
            val visible = wallBodyRows(logical.height, metrics)
            if (total <= visible) return
            val inset = (metrics.padding * zoom).roundToInt().coerceAtLeast(1)
            val track = height - bodyTop - 2 * inset
            if (track <= 0) return
            val thumb = (track.toDouble() * visible / total).roundToInt().coerceIn(JBUI.scale(8).coerceAtMost(track), track)
            val y = bodyTop + inset + ((track - thumb).toDouble() * first / (total - visible)).roundToInt()
            val thickness = JBUI.scale(3)
            g2.color = ColorUtil.withAlpha(muted, 0.35)
            g2.fillRoundRect(width - thickness - JBUI.scale(2), y, thickness, thumb, thickness, thickness)
        }

        /** A thin, quiet thumb at the right edge showing which part of a compact body is in view. */
        private fun paintScrollIndicator(g2: Graphics2D, cap: Int, bodyTop: Int, muted: Color) {
            val inset = (metrics.padding * zoom).roundToInt().coerceAtLeast(1)
            val track = height - bodyTop - 2 * inset
            val body = fullHeight - metrics.headerHeight
            if (track <= 0 || body <= 0) return
            val thumb = (track.toDouble() * (cap - metrics.headerHeight) / body).roundToInt().coerceIn(JBUI.scale(8).coerceAtMost(track), track)
            val y = bodyTop + inset + ((track - thumb).toDouble() * offset / (fullHeight - cap)).roundToInt()
            val thickness = JBUI.scale(3)
            g2.color = ColorUtil.withAlpha(muted, 0.35)
            g2.fillRoundRect(width - thickness - JBUI.scale(2), y, thickness, thumb, thickness, thickness)
        }

        private fun paintHorizontalScrollIndicator(g2: Graphics2D, offset: Int, muted: Color) {
            val max = maxHorizontalScroll
            if (max == 0) return
            val inset = (metrics.padding * zoom).roundToInt().coerceAtLeast(1)
            val track = width - 2 * inset
            if (track <= 0) return
            val thumb = (track.toDouble() * blockWidth / (blockWidth + max)).roundToInt().coerceIn(1, track)
            val x = inset + ((track - thumb).toDouble() * offset / max).roundToInt()
            val thickness = JBUI.scale(3)
            g2.color = ColorUtil.withAlpha(muted, 0.35)
            g2.fillRoundRect(x, height - thickness - JBUI.scale(2), thumb, thickness, thickness, thickness)
        }

        /** A thin focus-coloured outline in place of the border. */
        private fun paintFocus(g: Graphics, arc: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val stroke = JBUIScale.scale(1.5f)
                g2.color = JBUI.CurrentTheme.Focus.focusColor()
                g2.stroke = BasicStroke(stroke)
                g2.draw(RoundRectangle2D.Float(stroke / 2, stroke / 2, width - stroke, height - stroke, arc.toFloat(), arc.toFloat()))
            } finally {
                g2.dispose()
            }
        }

        /** [text]'s shape as a soft bar from its indent to its end, for zooms too small to read. */
        private fun sketch(g2: Graphics2D, text: String, pad: Double, y: Int, height: Int) {
            val indent = text.drop(1).takeWhile { it == ' ' }.length + 1
            val length = text.trimEnd().length - indent
            if (length <= 0) return
            val char = metrics.charWidth * zoom
            val x = (pad + indent * char).roundToInt()
            val color = g2.color
            g2.color = ColorUtil.withAlpha(color, 0.35)
            g2.fillRect(x, y + height / 4, (length * char).roundToInt().coerceAtMost(width - x).coerceAtLeast(1), (height / 2).coerceAtLeast(1))
            g2.color = color
        }

        /** The path with its folder muted, then the block's stats and the Compact and Summary switches, in a smaller editor font. */
        private fun paintHeader(g2: Graphics2D, scheme: EditorColorsScheme, muted: Color, sketch: Boolean, top: Int) {
            val z = zoom
            val pad = metrics.padding * z
            val headerHeight = metrics.headerHeight * z
            val title = block.title
            val split = title.lastIndexOf('/') + 1
            toggle = null
            summaryToggle = null
            menuToggle = null
            if (sketch) {
                g2.color = scheme.defaultForeground
                sketch(g2, " " + title, pad - metrics.charWidth * z, top + (headerHeight / 4).roundToInt(), (headerHeight / 2).roundToInt())
                return
            }
            g2.font = headerFont().let { it.deriveFont(it.size2D * z.toFloat()) }
            val fm = g2.fontMetrics
            val baseline = (top + (headerHeight - fm.height) / 2 + fm.ascent + JBUI.scale(1) * z).toFloat()
            var x = pad.toFloat()
            g2.color = muted
            g2.drawString(title.substring(0, split), x, baseline)
            x += fm.stringWidth(title.substring(0, split))
            // A folded file reads as set aside.
            g2.color = if (isFolded) muted else scheme.defaultForeground
            g2.drawString(title.substring(split), x, baseline)
            x += fm.stringWidth(title.substring(split)) + fm.charWidth(' ') * 2
            g2.color = ColorUtil.withAlpha(muted, 0.8)
            if (block.stats.isNotEmpty()) {
                g2.drawString(block.stats, x, baseline)
                x += fm.stringWidth(block.stats) + fm.charWidth(' ') * 2
            }
            g2.font = g2.font.deriveFont(g2.font.size2D * 0.9f)
            val small = g2.fontMetrics
            val link = JBUI.CurrentTheme.Link.Foreground.ENABLED
            val quiet = ColorUtil.withAlpha(muted, 0.7)
            if (canCompact()) {
                val word = compactText()
                g2.color = if (isCompact || isFolded) link else quiet
                g2.drawString(word, x, baseline)
                toggle = Rectangle(x.toInt() - JBUI.scale(2), top, small.stringWidth(word) + JBUI.scale(4), headerHeight.roundToInt())
                x += small.stringWidth(word) + small.charWidth(' ') * 2
            }
            // A file with a summary has one to show; without one, the switch asks for it when the wall can.
            if (hasSummary(block.path)) {
                g2.color = if (showsSummary) link else quiet
                g2.drawString(SUMMARY, x, baseline)
                summaryToggle = Rectangle(x.toInt() - JBUI.scale(2), top, small.stringWidth(SUMMARY) + JBUI.scale(4), headerHeight.roundToInt())
                x += small.stringWidth(SUMMARY) + small.charWidth(' ') * 2
            }
            // The menu sits at the header's right end, where the title cannot push it away.
            val menuWidth = small.stringWidth(MENU) + JBUI.scale(8)
            val menuX = (blockWidth * z - pad).toFloat() - menuWidth
            if (menuX >= x) {
                g2.color = quiet
                g2.drawString(MENU, menuX + JBUI.scale(4), baseline)
                menuToggle = Rectangle(menuX.toInt(), top, menuWidth, headerHeight.roundToInt())
            }
            // The skim column says where the file sits in it, so a long read keeps its bearings.
            if (skim) {
                val place = "${views.indexOf(this@BlockView) + 1} / ${views.size}"
                val placeX = (if (menuToggle != null) menuX else (blockWidth * z - pad).toFloat()) - small.stringWidth(place) - small.charWidth(' ') * 2
                if (placeX >= x) {
                    g2.color = quiet
                    g2.drawString(place, placeX, baseline)
                }
            }
        }
    }

    /**
     * Inline continuation card placed on the surface after the loaded blocks when more files remain unloaded.
     */
    internal inner class MoreView : JComponent() {
        init {
            name = "${labels.id}-more"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) loadMore()
                }
            })
            addMouseWheelListener { e ->
                e.consume()
                surface.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, surface))
            }
            ToolTipManager.sharedInstance().registerComponent(this)
        }

        override fun getToolTipText(event: MouseEvent): String? =
            remainingFilesCount.takeIf { it > 0 }?.let { rem ->
                "${wallMoreCardTitle(shown.size, allPaths.size)} — ${wallLoadMoreAction(rem, loadChunk)}"
            }

        fun logicalSize(): Dimension {
            val m = views.firstOrNull()?.metrics ?: metrics()
            val width = views.firstOrNull()?.blockWidth ?: (60 * m.charWidth + 2 * m.padding)
            val height = m.headerHeight + m.lineHeight + 2 * m.padding
            return Dimension(width, height)
        }

        override fun getPreferredSize(): Dimension =
            logicalSize().let { Dimension((it.width * zoom).roundToInt(), (it.height * zoom).roundToInt()) }

        override fun paintComponent(g: Graphics) {
            val rem = remainingFilesCount
            if (rem <= 0) return
            val g2 = g.create() as Graphics2D
            val z = zoom
            val arc = (JBUI.scale(8) * z).roundToInt().coerceAtLeast(2)
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (z != 1.0) g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                val scheme = scheme()
                val m = views.firstOrNull()?.metrics ?: metrics()
                g2.color = scheme.defaultBackground
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                val strokeW = (JBUI.scale(1) * z).toFloat().coerceAtLeast(1f)
                val dash = (JBUI.scale(4) * z).toFloat().coerceAtLeast(2f)
                g2.color = ColorUtil.withAlpha(JBColor.border(), 0.8)
                g2.stroke = BasicStroke(strokeW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(dash, dash), 0f)
                g2.draw(RoundRectangle2D.Float(strokeW / 2f, strokeW / 2f, width - strokeW, height - strokeW, arc.toFloat(), arc.toFloat()))

                val text = textFont().let { it.deriveFont(it.size2D * z.toFloat()) }
                val pad = m.padding * z
                val headerHeight = m.headerHeight * z
                if (text.size2D < SKETCH_FONT_SIZE) {
                    g2.color = ColorUtil.withAlpha(JBUI.CurrentTheme.Link.Foreground.ENABLED, 0.5)
                    val h = (headerHeight / 2).roundToInt().coerceAtLeast(1)
                    g2.fillRect(pad.roundToInt(), (headerHeight / 4).roundToInt(), ((width - 2 * pad) * 0.6).roundToInt().coerceAtLeast(1), h)
                    return
                }
                val hFont = headerFont().let { it.deriveFont(it.size2D * z.toFloat()) }
                g2.font = hFont
                val fm = g2.fontMetrics
                val title = wallMoreCardTitle(shown.size, allPaths.size)
                val titleY = ((headerHeight - fm.height) / 2 + fm.ascent + JBUI.scale(1) * z).toFloat()
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString(title, pad.toFloat(), titleY)

                g2.font = hFont
                val action = wallLoadMoreAction(rem, loadChunk)
                val actionY = (headerHeight + m.padding * z + fm.ascent).toFloat()
                g2.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
                g2.drawString(action, pad.toFloat(), actionY)
            } finally {
                g2.dispose()
            }
        }
    }

    /**
     * The tab on [view]'s right edge that folds and unfolds the file's chat window. It reads "Chat" upright at legible zooms,
     * marks a file that has a conversation with a dot, and takes the link colour while its window is open.
     */
    internal inner class ChatTab(private val view: BlockView) : JComponent() {
        private val path = view.block.path

        init {
            name = "${labels.id}-chat-tab:$path"
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    activate(view)
                    toggleChat(path)
                }
            })
            ToolTipManager.sharedInstance().registerComponent(this)
        }

        override fun getToolTipText(event: MouseEvent): String =
            if (chatShown(path)) "Fold the chat about this file" else labels.chatTabTooltip +
                if (chats?.hasConversation(path) == true) " (has earlier chats)" else ""

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val open = chatShown(path)
                val link = JBUI.CurrentTheme.Link.Foreground.ENABLED
                val background = scheme().defaultBackground
                val arc = (JBUI.scale(8) * zoom).roundToInt().coerceAtLeast(2)
                // Squared off on the left, where it meets the block, and rounded on the right.
                val shape = java.awt.geom.Area(RoundRectangle2D.Float(-arc.toFloat(), 0f, width + arc - 1f, height - 1f, arc.toFloat(), arc.toFloat()))
                g2.color = if (open) ColorUtil.mix(background, link, 0.18) else background
                g2.fill(shape)
                g2.color = if (open) ColorUtil.withAlpha(link, 0.7) else ColorUtil.withAlpha(JBColor.border(), 0.6)
                g2.draw(shape)
                val muted = UIUtil.getContextHelpForeground()
                val talked = chats?.hasConversation(path) == true
                val font = headerFont().let { it.deriveFont(it.size2D * 0.85f * zoom.toFloat()) }
                if (font.size2D < MIN_TAB_FONT_SIZE) {
                    // Too small to read: a dot marks a file that has a chat.
                    if (talked || open) {
                        g2.color = link
                        val d = (width / 2).coerceAtLeast(2)
                        g2.fillOval((width - d) / 2, (height - d) / 2, d, d)
                    }
                    return
                }
                g2.font = font
                val fm = g2.fontMetrics
                val label = "Chat"
                val dot = if (talked && !open) (JBUI.scale(5) * zoom).roundToInt().coerceIn(2, (width - 2).coerceAtLeast(2)) else 0
                if (dot > 0) {
                    g2.color = if (chats!!.busy(path)) muted else link
                    g2.fillOval((width - dot) / 2, (JBUI.scale(4) * zoom).roundToInt(), dot, dot)
                }
                g2.color = if (open) link else muted
                // Read top to bottom, as side tabs are: turned a quarter clockwise, glyphs rise to the right of the baseline.
                g2.translate((width - fm.ascent + fm.descent) / 2.0, (height - fm.stringWidth(label) + dot) / 2.0)
                g2.rotate(Math.PI / 2)
                g2.drawString(label, 0f, 0f)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        val LOG = Logger.getInstance(WallView::class.java)
        val CHAT_TAB_WIDTH = JBUI.scale(18)
        val CHAT_TAB_HEIGHT = JBUI.scale(60)
        const val MIN_TAB_FONT_SIZE = 7f
        const val CANVAS_CHAT_HIDE = "Hide chat"
        val ADDED = JBColor(0xDDF5E3, 0x2D4535)
        val REMOVED = JBColor(0xF9DEDE, 0x4D3134)
        const val KEY_ZOOM = 1.25
        const val WHEEL_ZOOM = 1.1
        const val ANIMATION_MS = 180.0
        const val HIGHLIGHT_MS = 2_500
        const val SKETCH_FONT_SIZE = 4.5f
        const val WHEEL_ROWS = 3
        const val COMPACT = "Compact"
        const val FOLD = "Fold"
        const val UNFOLD = "Unfold"
        const val SUMMARY = "Summary"
        const val MENU = "..."

        /** The scheme's diff colour softened toward the background, so dozens of tinted blocks stay calm. */
        fun tint(scheme: EditorColorsScheme, key: TextAttributesKey, fallback: Color, background: Color): Color =
            ColorUtil.mix(background, scheme.getAttributes(key)?.backgroundColor ?: fallback, 0.7)
    }
}
