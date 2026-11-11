package dev.insight.review.wall

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ui.ReviewTab
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
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.event.ChangeListener
import javax.swing.text.JTextComponent
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
) : Disposable {
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
    private val surface = Surface()
    private val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
        isOpaque = false
        add(label)
        add(zoomLabel)
        add(allLink)
        add(canvasChatLink)
    }
    val card: JPanel = JPanel(BorderLayout()).apply {
        name = labels.id
        background = UIUtil.getPanelBackground()
        add(header, BorderLayout.NORTH)
        add(surface, BorderLayout.CENTER)
    }
    private var scope: WallScope? = null
    private var shown: List<WallBlock> = emptyList()
    /** The summary text of the files that have one, by path. */
    private var summaries: Map<String, String> = emptyMap()
    /** Blocks showing their summary in place of their rows. */
    private val summarized = mutableSetOf<String>()
    /** The first summary row shown in each block that shows its summary. */
    private val summaryScrolls = mutableMapOf<String, Int>()
    /** Where each block sits at 100%, by path. */
    private val positions = mutableMapOf<String, Point>()
    private var views: List<BlockView> = emptyList()
    /** Compact blocks by path, with the height (at 100%) each is capped at. */
    private val compact = mutableMapOf<String, Int>()
    /** Blocks the reader turned compact off for, which a pack must not make compact again. */
    private val expanded = mutableSetOf<String>()
    /** How far (at 100%) each compact block's body is scrolled. */
    private val scrolls = mutableMapOf<String, Int>()
    /** Set once the reader moves a block: the layout is theirs and viewport changes no longer repack it. */
    private var arranged = false
    /** The viewport size the blocks were last packed for; null asks for a pack. */
    private var packedFor: Dimension? = null
    private var zoom = 1.0
    /** Set once the reader zooms, so a later pack keeps their zoom. */
    private var zoomTouched = false
    private var activePath: String? = null
    /** The first Tab focuses the initially active block before navigation advances. */
    private var firstStep = true
    /** The zoom and view position from before Tab navigation zoomed in, restored by Escape. */
    private var overview: Pair<Double, Point>? = null
    private var animation: Timer? = null
    private var watched: JScrollPane? = null
    /** Each block's Chat tab, by path; empty without [chats]. */
    private var chatTabs: Map<String, ChatTab> = emptyMap()
    /** Blocks whose chat window is unfolded. */
    private val chatOpen = linkedSetOf<String>()
    /** Each file's chat window once opened, kept with its draft while folded or off the wall. */
    private val chatPanels = mutableMapOf<String, WallChatPanel>()
    private val chatSubscription = chats?.subscribe { path -> chatTabs[path]?.repaint() }
    /** The wall's own chat window once opened, kept with its draft while folded; it stays open across scopes. */
    private var canvasChatPanel: WallChatPanel? = null
    private var canvasChatOpen = false
    /** Keeps the wall's own chat window in the visible corner of the wall while the page scrolls. */
    private val scrolled = ChangeListener { if (canvasChatOpen) { placeCanvasChat(); canvasChatPanel?.repaint() } }
    /** Each block's row colours once worked out, by path, with the block they were worked out for; null rows for a plain block. */
    private val highlights = mutableMapOf<String, Pair<WallBlock, List<List<WallSpan>?>?>>()
    /** Blocks whose row colours are being worked out, by path. */
    private val highlighting = mutableMapOf<String, WallBlock>()
    /** Bumped when the colour scheme changes, so colours worked out for the old one are dropped. */
    @Volatile private var highlightGeneration = 0
    @Volatile private var disposed = false
    /** Works the colours out again when the reader changes the editor's colour scheme. */
    private val schemeConnection = highlighter?.let {
        ApplicationManager.getApplication()?.messageBus?.connect()?.apply {
            subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
                ApplicationManager.getApplication().invokeLater({ if (!disposed) rehighlight() }, ModalityState.any())
            })
        }
    }
    private val resized = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            if (!arranged) { surface.revalidate(); surface.repaint() }
        }
    }

    /** The blocks in the scope's file order. */
    internal val blocks: List<BlockView> get() = views

    /** Shows [content]; a new scope relays the blocks out, the same scope keeps where they were moved. */
    fun show(content: WallContent) {
        label.text = labels.header(content.scope.title, content.blocks.size)
        allLink.isVisible = content.scope.paths != null
        if (content.scope == scope && content.blocks == shown) {
            // Only summaries arrived or changed: blocks keep their size and place, and repaint with the new text.
            if (content.summaries != summaries) {
                summaries = content.summaries
                forgetMissingSummaries()
                views.forEach { it.repaint() }
            }
            return
        }
        if (content.scope != scope) {
            stopAnimation()
            positions.clear()
            compact.clear()
            expanded.clear()
            scrolls.clear()
            summarized.clear()
            summaryScrolls.clear()
            arranged = false
            zoomTouched = false
            setZoom(1.0)
            activePath = null
            firstStep = true
            overview = null
            // A new layout starts folded; each file's chats stay to be unfolded again.
            chatOpen.clear()
            // The canvas restores the page's previous scroll after its rebuild; a new layout starts at the top instead.
            SwingUtilities.invokeLater { SwingUtilities.invokeLater { viewport()?.viewPosition = Point(0, 0); focusWhenOpened() } }
        }
        packedFor = null
        scope = content.scope
        shown = content.blocks
        summaries = content.summaries
        forgetMissingSummaries()
        val metrics = metrics()
        val width = wallBlockWidth(content.blocks, metrics)
        surface.removeAll()
        views = content.blocks.map { BlockView(it, width, metrics) }
        views.forEach(surface::add)
        if (views.none { it.block.path == activePath }) {
            activePath = null
            views.firstOrNull()?.let(::activate)
            firstStep = true
        }
        if (chats != null) {
            chatTabs = views.associate { it.block.path to ChatTab(it) }
            chatTabs.values.forEach(surface::add)
            chatOpen.retainAll(chatTabs.keys)
            chatOpen.forEach { surface.add(chatPanel(it)) }
            if (canvasChatOpen) surface.add(canvasChat())
            raiseChats()
        }
        // Files that join an arranged scope go below the others rather than reshuffling them.
        if (arranged) views.filter { it.block.path !in positions }.forEach { joined ->
            val bottom = views.filter { it.block.path in positions }
                .maxOfOrNull { positions.getValue(it.block.path).y + it.logical.height } ?: 0
            positions[joined.block.path] = Point(metrics.gap, bottom + metrics.gap)
        }
        surface.revalidate()
        surface.repaint()
        requestHighlights()
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
        val work = views.mapNotNull { view ->
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
    private fun rects(): List<WallRect> = views.map { view ->
        val at = positions[view.block.path] ?: Point(0, 0)
        WallRect(at.x, at.y, view.logical.width, view.logical.height)
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
        if (arranged || views.isEmpty()) return
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
        views.zip(rects).forEach { (view, rect) -> positions[view.block.path] = Point(rect.x, rect.y) }
        if (available != null && !zoomTouched) setZoom(wallInitialZoom(wallExtent(rects, gap), available.width, available.height))
    }

    private fun setZoom(value: Double) {
        zoom = value
        zoomLabel.text = "${(value * 100).roundToInt()}%"
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
        (scrollPane() ?: viewport())?.validate()
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
        animate(wallFitZoom(wallExtent(rects(), gap), viewport.width - offset.x, viewport.height - offset.y), Point(0, 0))
    }

    /** The height (at 100%) a block turned compact now would be capped at, or null before the page has a viewport. */
    private fun compactCap(metrics: WallMetrics): Int? =
        viewport()?.height?.takeIf { it > 0 }?.let { wallCompactHeight(it, zoom, metrics) }

    /** Turns [view] compact or back; an auto-packed wall packs again, an arranged one keeps its positions. */
    private fun toggleCompact(view: BlockView) {
        val path = view.block.path
        if (compact.remove(path) != null) expanded += path
        else {
            val cap = compactCap(view.metrics)?.takeIf { view.fullHeight > it } ?: return
            compact[path] = cap
            expanded -= path
            scrolls.remove(path)
        }
        if (!arranged) packedFor = null
        surface.revalidate()
        surface.repaint()
    }

    /** A block whose summary is gone shows its rows again. */
    private fun forgetMissingSummaries() {
        summarized.retainAll(summaries.keys)
        summaryScrolls.keys.retainAll(summarized)
    }

    /** Shows [view]'s summary in place of its rows, or its rows again; the block keeps its size. */
    private fun toggleSummary(view: BlockView) {
        val path = view.block.path
        if (path in summarized) {
            summarized -= path
            summaryScrolls.remove(path)
        } else if (path in summaries) summarized += path
        view.repaint()
    }

    /** [path]'s chat window, made when first unfolded. */
    private fun chatPanel(path: String): WallChatPanel = chatPanels.getOrPut(path) {
        WallChatPanel(path, chats!!, labels.fileChat(path), labels.id, onClose = { if (path in chatOpen) toggleChat(path) },
            onRaise = { chatPanels[path]?.let { if (it.parent === surface) surface.setComponentZOrder(it, 0); it.repaint() } })
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
            surface.scrollRectToVisible(panel.bounds)
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
        chatSubscription?.dispose()
        chatPanels.values.forEach(Disposer::dispose)
        chatPanels.clear()
        chatOpen.clear()
        canvasChatPanel?.let(Disposer::dispose)
        canvasChatPanel = null
        canvasChatOpen = false
        watch(null)
    }

    private fun activate(view: BlockView) {
        val previous = views.firstOrNull { it.block.path == activePath }
        activePath = view.block.path
        surface.setComponentZOrder(view, 0)
        previous?.repaint()
        view.repaint()
    }

    /** Activates the next block in reading order, or the previous one, and zooms and scrolls to read it. */
    private fun step(forward: Boolean) {
        if (views.isEmpty()) return
        val rects = rects()
        val current = views.indexOfFirst { it.block.path == activePath }.takeIf { it >= 0 }
        val next = (if (firstStep) current else null)
            ?: wallNextBlock(wallReadingOrder(rects, gap), current, forward) ?: return
        activate(views[next])
        val viewport = viewport() ?: return
        firstStep = false
        if (overview == null) overview = zoom to viewport.viewPosition
        val margin = gap
        val capped = views[next].block.path in compact
        val target = wallFocusZoom(rects[next].width, viewport.width, zoom, margin,
            blockHeight = rects[next].height.takeIf { capped }, viewportHeight = viewport.height)
        val offset = surfaceOffset(viewport)
        zoomTouched = true
        animate(target, wallFocusPosition(rects[next], target, offset.x, offset.y, viewport.width, viewport.height, margin))
    }

    /** Returns to the zoom and scroll from before Tab navigation, keeping the active block. */
    private fun backToOverview() {
        val (value, position) = overview ?: return
        overview = null
        animate(value, position)
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
        watched = pane
        pane?.addComponentListener(resized)
        pane?.viewport?.addChangeListener(scrolled)
    }

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
            Key("up", listOf(key(KeyEvent.VK_UP))) { scrollBy(0, -step) },
            Key("down", listOf(key(KeyEvent.VK_DOWN))) { scrollBy(0, step) },
            Key("left", listOf(key(KeyEvent.VK_LEFT), key(KeyEvent.VK_LEFT, shift), key(KeyEvent.VK_UP, shift))) { scrollBy(-step, 0) },
            Key("right", listOf(key(KeyEvent.VK_RIGHT), key(KeyEvent.VK_RIGHT, shift), key(KeyEvent.VK_DOWN, shift))) { scrollBy(step, 0) },
            Key("page-up", listOf(key(KeyEvent.VK_PAGE_UP))) { scrollBy(0, -page(true)) },
            Key("page-down", listOf(key(KeyEvent.VK_PAGE_DOWN))) { scrollBy(0, page(true)) },
            Key("page-left", listOf(key(KeyEvent.VK_PAGE_UP, shift))) { scrollBy(-page(false), 0) },
            Key("page-right", listOf(key(KeyEvent.VK_PAGE_DOWN, shift))) { scrollBy(page(false), 0) },
            Key("top", listOf(key(KeyEvent.VK_HOME))) { scrollBy(0, -far()) },
            Key("bottom", listOf(key(KeyEvent.VK_END))) { scrollBy(0, far()) },
            Key("start", menu(KeyEvent.VK_HOME)) { scrollBy(-far(), -far()) },
            Key("end", menu(KeyEvent.VK_END)) { scrollBy(far(), far()) },
            Key("zoom-in", menu(KeyEvent.VK_EQUALS) + menu(KeyEvent.VK_EQUALS, shift) + menu(KeyEvent.VK_PLUS) + menu(KeyEvent.VK_ADD)) {
                zoomTo(wallZoomBy(zoom, KEY_ZOOM))
            },
            Key("zoom-out", menu(KeyEvent.VK_MINUS) + menu(KeyEvent.VK_SUBTRACT)) { zoomTo(wallZoomBy(zoom, 1 / KEY_ZOOM)) },
            Key("zoom-reset", menu(KeyEvent.VK_0) + menu(KeyEvent.VK_NUMPAD0)) { zoomTo(1.0) },
            Key("zoom-fit", listOf(key(KeyEvent.VK_F)) + menu(KeyEvent.VK_0, shift)) { fit() },
            Key("next-block", listOf(key(KeyEvent.VK_TAB))) { step(forward = true) },
            Key("previous-block", listOf(key(KeyEvent.VK_TAB, shift))) { step(forward = false) },
            Key("overview", listOf(key(KeyEvent.VK_ESCAPE)), { overview != null }) { backToOverview() },
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
                if (e.isControlDown || e.isMetaDown) {
                    e.consume()
                    val viewport = viewport()
                    zoomTo(wallZoomBy(zoom, WHEEL_ZOOM.pow(-e.preciseWheelRotation)), viewport?.let { SwingUtilities.convertPoint(this, e.point, it) })
                } else {
                    // Listening for the wheel takes it from the page; plain scrolling goes back to it.
                    scrollPane()?.let { it.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, it)) }
                }
            }
            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) { repaintActive() }
                override fun focusLost(e: FocusEvent) { repaintActive() }
            })
            addHierarchyListener { e ->
                watch(scrollPane())
                if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) SwingUtilities.invokeLater { focusWhenOpened() }
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

        /** Whether the keyboard is in one of the surface's text fields, such as a chat input, rather than on the wall. */
        private fun typing(): Boolean {
            val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            return owner is JTextComponent && SwingUtilities.isDescendingFrom(owner, this)
        }

        override fun doLayout() {
            ensureLayout()
            views.forEach { view ->
                val path = view.block.path
                val at = positions[path] ?: Point(0, 0)
                val size = view.preferredSize
                view.setBounds((at.x * zoom).roundToInt(), (at.y * zoom).roundToInt(), size.width, size.height)
                val tab = chatTabs[path] ?: return@forEach
                tab.bounds = chatTabBounds(view.bounds)
                chatPanels[path]?.takeIf { path in chatOpen }?.let { panel ->
                    panel.setBounds(tab.x + tab.width, view.y, wallChatWidth(), wallChatHeight(view.height))
                    panel.validate()
                }
            }
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
            val extent = wallExtent(rects(), gap)
            var width = (extent.width * zoom).roundToInt()
            var height = (extent.height * zoom).roundToInt()
            // An unfolded chat window may reach past the blocks; the page grows so it can be scrolled to.
            chatOpen.forEach { path ->
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
    internal inner class BlockView(val block: WallBlock, private val blockWidth: Int, internal val metrics: WallMetrics) : JComponent() {
        /** The block's whole height at 100%. */
        val fullHeight = wallBlockHeight(block, metrics)
        /** The characters of the line-number gutter source rows paint before their text; none for diff rows. */
        private val gutter = wallGutterChars(block)
        /** The cap (at 100%) while the block is compact. */
        private val cap: Int? get() = compact[block.path]?.takeIf { it < fullHeight }
        internal val isCompact: Boolean get() = cap != null
        /** The block's painted size at 100%. */
        val logical: Dimension get() = Dimension(blockWidth, cap ?: fullHeight)
        /** Whether Tab, Enter, C and the focus outline are on this block. */
        internal val active: Boolean get() = activePath == block.path
        private val offset: Int get() = if (isCompact && !showsSummary) scrolls[block.path] ?: 0 else 0
        /** Where the Compact switch was last painted, or null when the block is too short for it. */
        private var toggle: Rectangle? = null
        /** Where the Summary switch was last painted, or null when the file has no summary. */
        internal var summaryToggle: Rectangle? = null
            private set
        /** The file's summary text, if it has one. */
        private val summary: String? get() = summaries[block.path]
        /** Whether the summary is shown in place of the rows. */
        internal val showsSummary: Boolean get() = block.path in summarized && summary != null

        init {
            name = "${labels.id}-block:${block.path}"
            val drag = object : MouseAdapter() {
                /** Where the block was grabbed at 100%, so it moves with the pointer rather than jumping to it. */
                private var grab: Point2D.Double? = null
                override fun mousePressed(e: MouseEvent) {
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
                    // A moved block is the reader's layout; a later viewport size must not repack it.
                    arranged = true
                    location = Point((at.x * zoom).roundToInt(), (at.y * zoom).roundToInt())
                    surface.revalidate()
                    surface.scrollRectToVisible(Rectangle(pointer.x, pointer.y, 1, 1))
                }
                override fun mouseReleased(e: MouseEvent) {
                    if (grab == null) return
                    grab = null
                    cursor = Cursor.getDefaultCursor()
                }
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2 && !onToggle(e.point) && !onSummaryToggle(e.point)) openLine(block.path, lineAt(e.y))
                }
                override fun mouseMoved(e: MouseEvent) {
                    cursor = if (onToggle(e.point) || onSummaryToggle(e.point)) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                }
            }
            addMouseListener(drag)
            addMouseMotionListener(drag)
            val pan = Pan(this) { SwingUtilities.isMiddleMouseButton(it) }
            addMouseListener(pan)
            addMouseMotionListener(pan)
            // A compact body takes the wheel while it has more to show; the canvas gets the rest, and Ctrl/Cmd for zoom.
            addMouseWheelListener { e ->
                if (showsSummary && !e.isControlDown && !e.isMetaDown && !e.isShiftDown) {
                    val rows = summaryRows()
                    val visible = wallBodyRows(logical.height, metrics)
                    val first = summaryScrolls[block.path] ?: 0
                    val moved = wallSummaryScroll(first, (e.preciseWheelRotation * WHEEL_ROWS).roundToInt(), rows.size, visible)
                    if (moved != first) {
                        e.consume()
                        summaryScrolls[block.path] = moved
                        repaint()
                        return@addMouseWheelListener
                    }
                    surface.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, surface))
                    return@addMouseWheelListener
                }
                val cap = cap
                val moved = if (cap == null || e.isControlDown || e.isMetaDown || e.isShiftDown) null
                else wallBodyScroll(offset, (e.preciseWheelRotation * WHEEL_ROWS * metrics.lineHeight).roundToInt(), fullHeight, cap)
                if (moved != null && moved != offset) {
                    e.consume()
                    scrolls[block.path] = moved
                    repaint()
                } else surface.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, surface))
            }
            autoscrolls = true
            ToolTipManager.sharedInstance().registerComponent(this)
        }

        private fun onToggle(point: Point) = toggle?.contains(point) == true

        private fun onSummaryToggle(point: Point) = summaryToggle?.contains(point) == true

        /** The summary wrapped to the rows this block's width holds. */
        private fun summaryRows(): List<String> =
            wallWrap(summary.orEmpty(), (blockWidth - 2 * metrics.padding) / metrics.charWidth.coerceAtLeast(1))

        override fun getToolTipText(event: MouseEvent): String? = when {
            onToggle(event.point) -> if (isCompact) labels.expandTooltip else labels.compactTooltip
            onSummaryToggle(event.point) -> if (showsSummary) labels.bodyTooltip else labels.summaryTooltip
            else -> null
        }

        override fun getPreferredSize() = logical.let { Dimension((it.width * zoom).roundToInt(), (it.height * zoom).roundToInt()) }

        /** The row at [y] (painted, at the current zoom), or null on the header and padding. */
        internal fun lineAt(y: Int): WallLine? =
            if (showsSummary) null else wallRowAt(y / zoom, offset, metrics)?.let(block.lines::getOrNull)

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
                paintHeader(g2, scheme, muted, sketch)
                // The header stays put while a compact body scrolls beneath it.
                val bodyTop = (metrics.headerHeight * z).roundToInt()
                g2.clipRect(0, bodyTop, width, height - bodyTop)
                g2.font = text
                val ascent = g2.fontMetrics.ascent
                val pad = metrics.padding * z
                val lineHeight = metrics.lineHeight * z
                val top = (metrics.headerHeight + metrics.padding - offset) * z
                fun rowTop(index: Int) = (top + index * lineHeight).roundToInt()
                val clip = g2.clipBounds ?: Rectangle(0, 0, width, height)
                val from = floor((clip.y - top) / lineHeight).toInt().coerceAtLeast(0)
                val wrapped = if (showsSummary) summaryRows() else null
                val first = wrapped?.let { (summaryScrolls[block.path] ?: 0).coerceAtMost((it.size - 1).coerceAtLeast(0)) } ?: 0
                val shown = wrapped?.drop(first)
                val rowCount = shown?.size ?: block.lines.size
                val to = (ceil((clip.y + clip.height - top) / lineHeight).toInt() + 1).coerceAtMost(rowCount)
                val spans = rowSpans(block)
                val added = tint(scheme, DiffColors.DIFF_INSERTED, ADDED, background)
                val removed = tint(scheme, DiffColors.DIFF_DELETED, REMOVED, background)
                if (shown != null) {
                    g2.color = scheme.defaultForeground
                    for (index in from until to) {
                        val y = rowTop(index)
                        if (sketch) sketch(g2, " " + shown[index], pad, y, rowTop(index + 1) - y)
                        else g2.drawString(shown[index], pad.toFloat(), (top + index * lineHeight + ascent).toFloat())
                    }
                    paintSummaryIndicator(g2, first + shown.size, first, bodyTop, muted)
                } else {
                    for (index in from until to) {
                        val line = block.lines[index]
                        val y = rowTop(index)
                        val next = rowTop(index + 1)
                        when (line.kind) {
                            WallLine.Kind.ADDED -> added
                            WallLine.Kind.REMOVED -> removed
                            else -> null
                        }?.let { g2.color = it; g2.fillRect(1, y, width - 2, next - y) }
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
                    val footer = when {
                        block.lines.isEmpty() -> labels.emptyBlock
                        block.hiddenLines > 0 -> labels.hiddenLines(block.hiddenLines)
                        else -> null
                    }
                    if (footer != null && !sketch) {
                        g2.color = muted
                        g2.drawString(footer, pad.toFloat(), (top + block.lines.size * lineHeight + ascent).toFloat())
                    }
                    cap?.let { paintScrollIndicator(g2, it, bodyTop, muted) }
                }
            } finally {
                g2.dispose()
            }
            if (active && surface.isFocusOwner) paintFocus(g, arc)
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
        private fun paintHeader(g2: Graphics2D, scheme: EditorColorsScheme, muted: Color, sketch: Boolean) {
            val z = zoom
            val pad = metrics.padding * z
            val headerHeight = metrics.headerHeight * z
            val title = block.title
            val split = title.lastIndexOf('/') + 1
            toggle = null
            summaryToggle = null
            if (sketch) {
                g2.color = scheme.defaultForeground
                sketch(g2, " " + title, pad - metrics.charWidth * z, (headerHeight / 4).roundToInt(), (headerHeight / 2).roundToInt())
                return
            }
            g2.font = headerFont().let { it.deriveFont(it.size2D * z.toFloat()) }
            val fm = g2.fontMetrics
            val baseline = ((headerHeight - fm.height) / 2 + fm.ascent + JBUI.scale(1) * z).toFloat()
            var x = pad.toFloat()
            g2.color = muted
            g2.drawString(title.substring(0, split), x, baseline)
            x += fm.stringWidth(title.substring(0, split))
            g2.color = scheme.defaultForeground
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
            // Only a block taller than the cap has anything to compact.
            if (isCompact || compactCap(metrics)?.let { fullHeight > it } == true) {
                g2.color = if (isCompact) link else quiet
                g2.drawString(COMPACT, x, baseline)
                toggle = Rectangle(x.toInt() - JBUI.scale(2), 0, small.stringWidth(COMPACT) + JBUI.scale(4), headerHeight.roundToInt())
                x += small.stringWidth(COMPACT) + small.charWidth(' ') * 2
            }
            // Only a file with a summary has one to show.
            if (summary != null) {
                g2.color = if (showsSummary) link else quiet
                g2.drawString(SUMMARY, x, baseline)
                summaryToggle = Rectangle(x.toInt() - JBUI.scale(2), 0, small.stringWidth(SUMMARY) + JBUI.scale(4), headerHeight.roundToInt())
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
        const val SKETCH_FONT_SIZE = 4.5f
        const val WHEEL_ROWS = 3
        const val COMPACT = "Compact"
        const val SUMMARY = "Summary"

        /** The scheme's diff colour softened toward the background, so dozens of tinted blocks stay calm. */
        fun tint(scheme: EditorColorsScheme, key: TextAttributesKey, fallback: Color, background: Color): Color =
            ColorUtil.mix(background, scheme.getAttributes(key)?.backgroundColor ?: fallback, 0.7)
    }
}
