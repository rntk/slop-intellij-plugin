package dev.insight.callgraph

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.agent.ChatBackend
import dev.insight.agent.ToolInputException
import dev.insight.chat.ChatPanel
import dev.insight.chat.ChatSession
import dev.insight.chat.LlmChatBackend
import dev.insight.core.withPhaseProgress
import dev.insight.enrichment.liftCalls
import dev.insight.graph.CallGraphResult
import dev.insight.graph.CallOccurrence
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import dev.insight.graph.hierarchy
import dev.insight.settings.ChatSettings
import dev.insight.ui.ComponentMouseEvents
import dev.insight.ui.pixelBorder
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.LayoutManager
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.*
import javax.swing.text.DefaultCaret
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A lossy reading of a file through the call graph. Its source is shown line by line, but the functions it declares
 * fold into blocks, and lines that call project code become blocks too. Focusing or hovering a block previews the
 * summary of the symbol it leads to; Enter or a click pins it. A summary unfolds into the same reading
 * of that symbol, so the reader can walk down the graph, keeping several branches open at once.
 *
 * Source text supports native selection and copying. Dragging elsewhere (or middle-dragging) pans the canvas;
 * Ctrl/⌘ + wheel zooms it. With the canvas focused, keys scroll and zoom it too.
 */
internal class SchematicPanel(private val project: Project, private val file: SchematicFile) : JPanel(null) {
    private val result get() = file.exploration?.result ?: file.result
    private val graph get() = result.graph
    @Volatile private var analysis = Analysis(file.result)
    private val currentAnalysis: Analysis get() {
        val snapshot = result
        return analysis.takeIf { it.result === snapshot } ?: Analysis(snapshot).also { analysis = it }
    }
    private val lifted get() = currentAnalysis.lifted
    private val calls get() = currentAnalysis.calls
    private val hierarchy get() = currentAnalysis.hierarchy
    private val filesByPath get() = currentAnalysis.filesByPath
    private class Analysis(val result: CallGraphResult) {
        val lifted by lazy { liftCalls(result.graph) }
        val calls by lazy { lifted.edges.filter { it.kind == RelationKind.CALL }.groupBy { it.caller } }
        val hierarchy by lazy { result.graph.hierarchy() }
        val filesByPath by lazy {
            result.graph.nodes.filter { it.kind == SymbolKind.FILE && it.inProject && !it.unresolved && it.filePath != null }
                .associateBy { it.filePath }
        }
    }
    private val summaries by lazy { SchematicSummaries(project) { result } }
    private val scope = project.service<CallGraphService>().scope.let { parent ->
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }
    private val status = JLabel().apply { isVisible = false }
    /** Summary runs in progress; changed on the EDT only. */
    private var running = 0
    private var failures = 0
    private var error: String? = null
    private var disposed = false
    private val sourceEditors = mutableSetOf<SchematicSourceEditor>()
    private var useNativeSource = true
    /** Scale of everything on the canvas; 1.0 is the IDE's own size. */
    internal var zoom = 1.0
        private set
    private val zoomLevel = JLabel("100%")
    private var wheelZoom: Double? = null
    private var wheelAnchor: Point? = null
    // Trackpads can deliver many events between paints. Keep their full delta, but lay out at most once per frame.
    private val zoomFrame = Timer(16) {
        wheelZoom?.let { zoomTo(it, wheelAnchor) }
    }.apply { isRepeats = false }
    private val breadcrumbs = JPanel().apply {
        name = "schematic-breadcrumbs"
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }
    private var trailTarget: JComponent? = null
    private var breadcrumbPath = emptyList<Pair<String, JComponent>>()
    /** The file's own level, the start of every chain the chat shows. */
    private val rootSlot: LevelSlot
    /** The floating chat, created on the first Chat; hiding it keeps the conversation. */
    private var chat: SchematicChatBlock? = null
    private val content = object : JPanel(BorderLayout()), Scrollable, Zoomable {
        private val space = Dimension()
        val graphSize: Dimension get() = super.getPreferredSize()

        // The graph can shrink, but the space the reader has visited must remain scrollable.
        override fun getPreferredSize(): Dimension {
            val graph = graphSize
            reserve(graph.width.toLong(), graph.height.toLong())
            return Dimension(space)
        }

        fun reserve(right: Long, bottom: Long) {
            val margin = scaled(CANVAS_MARGIN)
            val width = (right + margin).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val height = (bottom + margin).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (width > space.width || height > space.height) {
                space.setSize(maxOf(space.width, width), maxOf(space.height, height))
                revalidate()
            }
        }

        override fun doLayout() {
            // Lay out the graph at its own size, independently of the surrounding blank canvas.
            val graph = components.firstOrNull() ?: return
            val size = graph.preferredSize
            graph.setBounds(insets.left, insets.top, size.width, size.height)
        }

        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = scaled(24)
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth() = false
        override fun getScrollableTracksViewportHeight() = false
        // The top leaves room for the floating bars, so the first lines are not under them before the reader scrolls.
        override fun applyZoom() { border = pixelBorder(JBUI.scale(FLOATING_ROW_SPACE) + scaled(8), 0, scaled(8), 0) }
    }
    /** The canvas; it takes the keyboard focus for scrolling and zooming. */
    internal val canvas: JComponent get() = content
    private val scroll = JBScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
        ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
    private val code: Font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
    private val codeFont get() = code.zoomed(zoom)
    private val blockBackground = JBColor(Color(235, 240, 255), Color(47, 57, 77))
    private val blockFocusedBackground = JBColor(Color(215, 228, 255), Color(62, 80, 112))
    private val blockOpen = JBColor(Color(76, 112, 220), Color(110, 150, 240))
    private val gapBackground = JBColor(Color(255, 247, 230), Color(65, 55, 38))
    private val gapFocusedBackground = JBColor(Color(255, 237, 199), Color(82, 66, 39))
    private val gapAccent = JBColor(Color(170, 111, 35), Color(211, 163, 85))
    /** Where the current left-button gesture started, on screen, and the view position then. */
    private var dragStart: Point? = null
    private var dragView: Point? = null
    /** Whether the last gesture moved the canvas; it then is not also a click. */
    private var panned = false
    private var dragCursorOwner: Component? = null
    private var dragCursor: Cursor? = null
    private var activeBlock: LevelView.BlockView? = null
    private var hoveredBlock: LevelView.BlockView? = null
    private var hoveredGap: LevelView.GapView? = null
    // Allow the pointer to cross the arrow between a block and its card without losing the preview.
    private val hoverExit = Timer(200) { hover(null) }.apply { isRepeats = false }
    /** Tracks just this canvas, including source editors and branches added as the graph unfolds. */
    private val mouseEvents = ComponentMouseEvents(content) {
        trackHover(it)
        drag(it)
    }

    /** Floats over the canvas, so the whole tab shows it: the trail on the left, the controls on the right. */
    private val trailBar = FloatingPanel(BorderLayout()).apply {
        add(JBScrollPane(breadcrumbs, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }, BorderLayout.CENTER)
    }
    private val nativeEditorToggle = JCheckBox("IDE editor", true).apply {
        name = "schematic-native-editor"
        isOpaque = false
        isFocusable = false
        toolTipText = "Use the IDE source editor; turn off to use the original canvas view"
        addActionListener { switchSourceView(isSelected) }
    }
    private val controls = FloatingPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        add(nativeEditorToggle)
        add(JButton("Fold all").apply { isFocusable = false; addActionListener { foldAll() } })
        add(JButton("−").apply {
            isFocusable = false
            toolTipText = "Zoom out (− or Ctrl/⌘ + wheel)"
            addActionListener { zoomTo(zoom / ZOOM_STEP) }
        })
        add(zoomLevel.apply { toolTipText = "Reset zoom (0)"; onClick(this) { zoomTo(1.0) } })
        add(JButton("+").apply {
            isFocusable = false
            toolTipText = "Zoom in (+ or Ctrl/⌘ + wheel)"
            addActionListener { zoomTo(zoom * ZOOM_STEP) }
        })
        add(status)
        add(JLabel("?").apply {
            font = font.deriveFont(Font.BOLD)
            toolTipText = "<html><div style='width:420px'>Hover a block or use Tab / Shift+Tab to preview its summaries. " +
                "Enter or a left click on a block pins its cards and requests summaries of those functions. Unpin releases a card. " +
                "Click a summary to unfold or fold the code it leads to. " +
                "Choose Show whole file to explore other declarations in its file. " +
                "IDE editor enables source selection and copying; turn it off for the original canvas view. " +
                "Choose Source on a summary to open the file; in the original view, double-click code. " +
                "Choose Compact on an unfolded summary to keep only clickable blocks; Full restores the plain code. " +
                "Drag outside source text, or middle-drag, to move; Ctrl/⌘ + wheel to zoom. " +
                "With the canvas focused, + and − zoom; arrows, Page Up/Down, Home and End scroll.<br><br>" +
                "Calls load as you unfold scopes; summaries describe each function's own source. Reopen the schematic after edits.</div></html>"
        })
    }

    init {
        content.isFocusable = true
        // Children inherit these empty traversal keys, so Tab reaches our canvas navigation bindings.
        content.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
        content.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())
        content.applyZoom()
        rootSlot = LevelSlot(file.root)
        content.add(rootSlot, BorderLayout.NORTH)
        scroll.viewport.addChangeListener {
            val view = scroll.viewport.viewRect
            content.reserve(view.x.toLong() + view.width, view.y.toLong() + view.height)
        }
        installNavigation()
        content.addMouseWheelListener(::wheel)
        // Earlier components paint on top, so the floating bars come before the canvas.
        add(controls)
        add(trailBar)
        add(scroll)
        // Blitting would copy the bars along with the canvas; repaint the viewport instead.
        scroll.viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
        refreshBreadcrumbs()
    }

    private fun wheel(e: MouseWheelEvent) {
        if (e.isControlDown || e.isMetaDown) {
            wheelZoom = ((wheelZoom ?: zoom) * WHEEL_ZOOM_STEP.pow(-e.preciseWheelRotation)).coerceIn(MIN_ZOOM, MAX_ZOOM)
            wheelAnchor = SwingUtilities.convertPoint(e.component, e.point, scroll.viewport)
            if (!zoomFrame.isRunning) zoomFrame.start()
            e.consume()
        } else scroll.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, scroll))
    }

    override fun doLayout() {
        scroll.setBounds(0, 0, width, height)
        scroll.validate()
        val view = SwingUtilities.convertRectangle(scroll, scroll.viewport.bounds, this)
        val gap = JBUI.scale(FLOATING_GAP)
        val controlSize = controls.preferredSize
        val trailScroll = trailBar.getComponent(0) as JScrollPane
        val trailPreferred = breadcrumbs.preferredSize.width + JBUI.scale(16)
        val roomForTrail = view.width - controlSize.width - 3 * gap
        // Too narrow for both on one row: the controls stay in the corner and the trail drops below them.
        val sameRow = roomForTrail >= JBUI.scale(MIN_TRAIL_WIDTH)
        val controlsWidth = minOf(controlSize.width, maxOf(view.width - 2 * gap, 0))
        controls.setBounds(view.x + view.width - gap - controlsWidth, view.y + gap, controlsWidth, controlSize.height)
        val trailWidth = minOf(trailPreferred, maxOf(if (sameRow) roomForTrail else view.width - 2 * gap, 0))
        val trailHeight = breadcrumbs.preferredSize.height + JBUI.scale(8) +
            if (trailPreferred > trailWidth) trailScroll.horizontalScrollBar.preferredSize.height else 0
        trailBar.setBounds(view.x + gap, view.y + gap + if (sameRow) 0 else controlSize.height + gap, trailWidth, trailHeight)
        // The chat floats on the right, below the bars, so the trail and controls stay usable.
        chat?.takeIf { it.isVisible }?.let { block ->
            val top = maxOf(controls.y + controls.height, if (sameRow) 0 else trailBar.y + trailBar.height) + gap
            val chatWidth = minOf(JBUI.scale(CHAT_WIDTH), maxOf(view.width - 2 * gap, 0))
            block.setBounds(view.x + view.width - gap - chatWidth, top, chatWidth, maxOf(view.y + view.height - gap - top, 0))
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.isVisible = text.isNotBlank()
        controls.revalidate()
    }

    fun dispose() {
        disposed = true
        hoverExit.stop()
        zoomFrame.stop()
        wheelZoom = null
        wheelAnchor = null
        mouseEvents.dispose()
        endDrag()
        chat?.dispose()
        chat = null
        scope.cancel()
        sourceEditors.forEach { Disposer.dispose(it) }
        sourceEditors.clear()
    }

    private fun releaseBranch(component: Component) {
        if (component is LevelSlot) component.cancelLoad()
        if (component is LevelView) component.release()
        if (component is SchematicSourceEditor) {
            sourceEditors.remove(component)
            Disposer.dispose(component)
        } else if (component is Container) component.components.forEach(::releaseBranch)
    }

    /** Change only source rendering: loaded levels, cards and the reader's place belong to the same exploration. */
    private fun switchSourceView(native: Boolean) {
        if (useNativeSource == native) return
        val position = Point(scroll.viewport.viewPosition)
        useNativeSource = native
        fun visit(component: Component) {
            if (component is SchematicSourceEditor) return
            if (component is LevelView) component.switchSourceView()
            if (component is Container) component.components.forEach(::visit)
        }
        visit(content)
        content.requestFocusInWindow()
        content.revalidate()
        scroll.validate()
        scrollTo(position.x, position.y)
        content.repaint()
    }

    /** Tab moves the reading cursor; Enter pins its cards. Other keys scroll and zoom the canvas. */
    private fun installNavigation() {
        val line = { content.getFontMetrics(codeFont).height }
        val page = { scroll.viewport.height - line() }
        fun bind(stroke: String, action: () -> Unit) {
            content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(stroke), stroke)
            content.actionMap.put(stroke, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = action()
            })
        }
        bind("TAB") { focusNext(false) }
        bind("shift TAB") { focusNext(true) }
        bind("ENTER") { hoveredGap?.card?.pinTo(true) ?: (hoveredBlock ?: activeBlock)?.pin() }
        bind("ESCAPE") {
            hoverExit.stop()
            activeBlock = null
            hoveredBlock = null
            hoveredGap = null
            refreshCards()
        }
        for (prefix in listOf("", "KP_")) {
            bind("${prefix}UP") { scrollBy(0, -SCROLL_LINES * line()) }
            bind("${prefix}DOWN") { scrollBy(0, SCROLL_LINES * line()) }
            bind("${prefix}LEFT") { scrollBy(-SCROLL_LINES * line(), 0) }
            bind("${prefix}RIGHT") { scrollBy(SCROLL_LINES * line(), 0) }
        }
        bind("PAGE_UP") { scrollBy(0, -page()) }
        bind("PAGE_DOWN") { scrollBy(0, page()) }
        bind("shift PAGE_UP") { scrollBy(-scroll.viewport.width, 0) }
        bind("shift PAGE_DOWN") { scrollBy(scroll.viewport.width, 0) }
        for (modifier in listOf("", "ctrl ", "meta ")) {
            bind("${modifier}HOME") { scrollTo(0, 0) }
            bind("${modifier}END") { scrollTo(0, maxOf(0, content.graphSize.height - scroll.viewport.height)) }
        }
        // Typed keys follow the keyboard layout, and the numeric keypad types the same characters.
        bind("typed +") { zoomTo(zoom * ZOOM_STEP) }
        bind("typed =") { zoomTo(zoom * ZOOM_STEP) }
        bind("typed -") { zoomTo(zoom / ZOOM_STEP) }
        bind("typed 0") { zoomTo(1.0) }
    }

    /** Source order, descending into unfolded cards directly after their owning block. */
    private fun visibleBlocks(): List<LevelView.BlockView> = buildList {
        fun visit(component: Component) {
            if (!component.isVisible) return
            if (component is LevelView) {
                for (block in component.blocks) {
                    add(block)
                    component.cardsFor(block).forEach(::visit)
                }
            } else if (component is Container) component.components.forEach(::visit)
        }
        visit(content)
    }

    private fun focusNext(backwards: Boolean) {
        val blocks = visibleBlocks()
        if (blocks.isEmpty()) return
        val index = blocks.indexOf(hoveredBlock ?: activeBlock)
        val next = if (index < 0) {
            if (backwards) blocks.lastIndex else 0
        } else Math.floorMod(index + if (backwards) -1 else 1, blocks.size)
        hoverExit.stop()
        hoveredBlock = null
        hoveredGap = null
        activeBlock = blocks[next]
        trailTarget = blocks[next]
        refreshCards()
        // Reflow the newly visible card before locating the block in canvas coordinates.
        SwingUtilities.invokeLater {
            if (!disposed && activeBlock === blocks[next]) {
                scroll.validate()
                val box = blocks[next].box
                content.scrollRectToVisible(SwingUtilities.convertRectangle(box.parent, box.bounds, content))
            }
        }
    }

    private fun hover(block: LevelView.BlockView?, gap: LevelView.GapView? = null) {
        if (hoveredBlock === block && hoveredGap === gap) return
        hoveredBlock = block
        hoveredGap = gap
        refreshCards()
    }

    private fun trackHover(e: MouseEvent) {
        if (e.id !in listOf(MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_EXITED, MouseEvent.MOUSE_MOVED)) return
        if (!SwingUtilities.isDescendingFrom(e.component, content)) return
        val owner = if (e.id == MouseEvent.MOUSE_EXITED) null else
            generateSequence(e.component) { it.parent }.firstOrNull {
                it is LevelView.BlockView || it is LevelView.SummaryCard ||
                    it is LevelView.GapSummaryCard || (it is LevelView.GapView && it.card != null)
            }
        val block = when (owner) {
            is LevelView.BlockView -> owner
            is LevelView.SummaryCard -> owner.row
            else -> null
        }
        val gap = when (owner) {
            is LevelView.GapView -> owner
            is LevelView.GapSummaryCard -> owner.gap
            else -> null
        }
        if (block != null || gap != null) {
            hoverExit.stop()
            hover(block, gap)
        } else if (!hoverExit.isRunning) hoverExit.start()
    }

    private fun refreshCards() {
        fun visit(component: Component) {
            if (component is LevelView) component.refreshCards()
            if (component is Container) component.components.forEach(::visit)
        }
        visit(content)
        refreshBreadcrumbs()
        content.revalidate()
        content.repaint()
    }

    /** Follow the actual unfolded branch, including repeated symbols reached through recursion or different callers. */
    private fun refreshBreadcrumbs() {
        val root = content.components.firstOrNull() as? JComponent ?: return
        fun showing(component: Component) = generateSequence(component) { it.parent }
            .takeWhile { it !== content }.all { it.isVisible }
        val target = trailTarget?.let { selected ->
            generateSequence<Component>(selected) { it.parent }.takeWhile { it !== content }
                .filterIsInstance<JComponent>().firstOrNull { showing(it) }
        }
        val path = buildList {
            add(graph.nodes[file.root].name to root)
            if (target != null) {
                generateSequence<Component>(target) { it.parent }.filterIsInstance<LevelView.SummaryCard>()
                    .toList().asReversed().forEach { add(it.trailName to it) }
                if (target is LevelView.BlockView) add(target.trailName to target)
            }
        }
        if (path == breadcrumbPath) return
        val previousPath = breadcrumbPath
        breadcrumbPath = path
        (previousPath + path).mapNotNull { (_, component) ->
            when (component) {
                is LevelView.BlockView -> component
                is LevelView.SummaryCard -> component.row
                else -> null
            }
        }.distinct().forEach { it.refreshTitle() }
        breadcrumbs.removeAll()
        for ((index, entry) in path.withIndex()) {
            if (index > 0) breadcrumbs.add(JLabel(" → "))
            breadcrumbs.add(ActionLink(entry.first) { revealBreadcrumb(entry.second) }.apply {
                font = font.deriveFont(readableSize(font.size2D))
                toolTipText = entry.first
            })
        }
        breadcrumbs.revalidate()
        this.revalidate()
        breadcrumbs.repaint()
        SwingUtilities.invokeLater {
            if (!disposed) breadcrumbs.components.lastOrNull()?.let { last ->
                breadcrumbs.scrollRectToVisible(last.bounds)
            }
        }
    }

    /** Selects [target] and scrolls to it; the chat passes [focus] false so its input keeps the keyboard. */
    private fun revealBreadcrumb(target: JComponent, focus: Boolean = true) {
        hoverExit.stop()
        hoveredBlock = null
        hoveredGap = null
        trailTarget = target
        activeBlock = when (target) {
            is LevelView.BlockView -> target
            is LevelView.SummaryCard -> target.row
            else -> null
        }
        refreshCards()
        scroll.validate()
        if (focus) content.requestFocusInWindow()
        val anchor = when (target) {
            is LevelView.BlockView -> target.box
            is LevelView.SummaryCard -> target.trailAnchor
            else -> { scrollTo(0, 0); return }
        }
        content.scrollRectToVisible(SwingUtilities.convertRectangle(anchor.parent, anchor.bounds, content))
    }

    private fun scrollBy(dx: Int, dy: Int) {
        val position = scroll.viewport.viewPosition
        scrollTo(position.x.toLong() + dx, position.y.toLong() + dy)
    }

    private fun scrollTo(x: Number, y: Number) {
        val viewport = scroll.viewport
        val left = x.toLong().coerceIn(0L, (Int.MAX_VALUE - viewport.width).toLong())
        val top = y.toLong().coerceIn(0L, (Int.MAX_VALUE - viewport.height).toLong())
        content.reserve(left + viewport.width, top + viewport.height)
        viewport.viewSize = content.preferredSize
        viewport.viewPosition = Point(left.toInt(), top.toInt())
    }

    /** Zooms to [requested], keeping the content under [anchor] (in viewport coordinates; the middle by default) in place. */
    internal fun zoomTo(requested: Double, anchor: Point? = null) {
        zoomFrame.stop()
        wheelZoom = null
        wheelAnchor = null
        val next = requested.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        val viewport = scroll.viewport
        val point = anchor ?: Point(viewport.width / 2, viewport.height / 2)
        val previous = viewport.viewPosition
        val ratio = next / zoom
        zoom = next
        zoomLevel.text = "${(zoom * 100).roundToInt()}%"
        applyZoom(content)
        content.revalidate()
        scroll.validate()
        scrollTo(((previous.x + point.x) * ratio - point.x).roundToInt(), ((previous.y + point.y) * ratio - point.y).roundToInt())
        content.repaint()
    }

    /** Hidden branches catch up when revealed, instead of rebuilding their HTML and fonts on every wheel event. */
    private fun applyZoom(component: Component) {
        if (!component.isVisible || component is SchematicSourceEditor) return
        if (component is JComponent) (component.getClientProperty(BASE_FONT) as? Font)?.let { component.font = it.zoomed(zoom) }
        (component as? Container)?.components?.forEach(::applyZoom)
        if (component is Zoomable) component.applyZoom()
    }

    /** Shows [base] (this component's own font by default) at the current zoom, now and after every zoom change. */
    private fun <T : JComponent> T.zoomFont(base: Font = font): T = apply {
        putClientProperty(BASE_FONT, base)
        font = base.zoomed(zoom)
    }

    private fun scaled(value: Int) = zoomScaled(value, zoom)

    private fun readableSize(size: Float) = maxOf(size, JBUI.scale(MIN_CALLABLE_FONT_SIZE).toFloat())

    private fun inBreadcrumbs(component: JComponent) = breadcrumbPath.any { it.second === component }

    /** Only the explored chain keeps readable titles as surrounding code shrinks. */
    private inner class CallableLabel(text: String, private val onTrail: () -> Boolean) : JLabel(text), Zoomable {
        init { applyZoom() }

        override fun applyZoom() {
            val size = code.size2D * zoom.toFloat()
            font = code.deriveFont(if (onTrail()) readableSize(size) else size)
        }
    }

    /** Swing's HTML code style otherwise keeps its own fixed font size, ignoring the label's zoomed font. */
    private inner class HtmlLabel(private val onTrail: () -> Boolean = { false }) : JLabel(), Zoomable {
        var body = ""
            set(value) {
                if (field == value) return
                field = value
                applyZoom()
            }

        init { zoomFont() }

        override fun applyZoom() {
            val titleSize = if (onTrail()) readableSize(font.size2D) else font.size2D
            text = "<html><head><style>code { font-size: ${font.size}pt; } " +
                ".callable-title code { font-size: ${titleSize.roundToInt()}pt; }</style></head>" +
                "<div style='width:${scaled(CARD_TEXT_WIDTH)}px'>$body</div></html>"
        }
    }

    private fun line(color: Color, thickness: Int) = BorderFactory.createLineBorder(color, scaled(thickness))

    private fun drag(e: MouseEvent) {
        if (!SwingUtilities.isDescendingFrom(e.component, content)) return
        if (e.isPopupTrigger && (e.id == MouseEvent.MOUSE_PRESSED || e.id == MouseEvent.MOUSE_RELEASED)) {
            // IDE source text shows its own menu, with the line and selection it knows about.
            if (generateSequence(e.component) { it.parent }.none { it is SchematicSourceEditor }) {
                showContextMenu(e)
            }
            return
        }
        when (e.id) {
            MouseEvent.MOUSE_PRESSED -> if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                // Native text keeps its own focus, selection and copy gestures.
                panned = false
                if (SwingUtilities.isLeftMouseButton(e) && generateSequence(e.component) { it.parent }
                        .any { it is SchematicSourceEditor }) return
                dragStart = e.locationOnScreen
                dragView = scroll.viewport.viewPosition
                if (e.component !is ActionLink) content.requestFocusInWindow()
            }
            MouseEvent.MOUSE_DRAGGED -> {
                val start = dragStart ?: return
                val view = dragView ?: return
                val now = e.locationOnScreen
                if (!panned && start.distance(now) < JBUI.scale(DRAG_THRESHOLD)) return
                if (!panned) {
                    panned = true
                    dragCursorOwner = e.component.also { owner ->
                        dragCursor = owner.cursor.takeIf { owner.isCursorSet }
                        owner.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    }
                }
                scrollTo(view.x + start.x - now.x, view.y + start.y - now.y)
            }
            MouseEvent.MOUSE_RELEASED -> {
                endDrag()
            }
        }
    }

    private fun endDrag() {
        dragStart = null
        dragView = null
        dragCursorOwner?.cursor = dragCursor
        dragCursorOwner = null
        dragCursor = null
    }

    /** Right-click anywhere on the canvas; [pointer] describes the block, card or line under the pointer for the chat. */
    private fun showContextMenu(e: MouseEvent, pointer: String? = null, copy: (() -> Unit)? = null) {
        // Stop at the nearest level so unfolded source never inherits its parent card's actions.
        val owner = generateSequence(e.component) { it.parent }
            .firstOrNull { it is LevelView.BlockView || it is LevelView.SummaryCard || it is LevelView }
        val context = pointer ?: when (owner) {
            is LevelView.BlockView -> owner.describe()
            is LevelView.SummaryCard -> owner.describe()
            else -> null
        }
        JPopupMenu().apply {
            add(JMenuItem("Chat").apply {
                toolTipText = "Ask about this file and the code it reaches; answers unfold the calls here"
                addActionListener { openChat(context) }
            })
            val level = generateSequence(e.component) { it.parent }.filterIsInstance<LevelView>().firstOrNull()
                ?: rootSlot.components.firstOrNull() as? LevelView
            level?.let { view ->
                add(JMenuItem("Summarize gaps").apply {
                    isEnabled = view.canSummarizeGaps()
                    addActionListener { view.summarizeGaps() }
                })
            }
            copy?.let { action -> add(JMenuItem("Copy").apply { addActionListener { action() } }) }
            if (owner is LevelView.SummaryCard) {
                addSeparator()
                owner.addContextActions(this)
            }
        }.show(e.component, e.x, e.y)
    }

    private fun openChat(pointer: String?) {
        if (disposed) return
        val block = chat ?: SchematicChatBlock().also {
            chat = it
            // Earlier components paint on top: the chat floats over the bars and the canvas.
            add(it, 0)
        }
        block.point(pointer)
        block.isVisible = true
        revalidate()
        repaint()
        block.chat.input.requestFocusInWindow()
    }

    private fun relativePath(path: String): String = project.basePath?.let { base -> path.removePrefix("$base/") } ?: path

    /** Read action: the current source of [id] with 1-based line numbers. */
    private suspend fun readSource(id: Int): String = smartReadAction(project) {
        val element = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid }
            ?: throw ToolInputException("The source of #$id is no longer available.")
        val psiFile = element.containingFile ?: throw ToolInputException("#$id has no source file.")
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: throw ToolInputException("#$id has no readable source.")
        val range = element.textRange
        val first = document.getLineNumber(range.startOffset.coerceIn(0, document.textLength))
        val last = document.getLineNumber((range.endOffset - 1).coerceIn(range.startOffset, document.textLength).coerceAtLeast(0))
        val shown = minOf(last, first + MAX_READ_LINES - 1)
        val path = psiFile.virtualFile?.path?.let(::relativePath) ?: psiFile.name
        val width = (shown + 1).toString().length
        "$path (lines ${first + 1}-${shown + 1})\n" + (first..shown).joinToString("\n") { line ->
            "${(line + 1).toString().padStart(width)}| " +
                document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
        } + if (shown < last) "\n[${last - shown} more lines; use read_file with path $path from line ${shown + 2}.]" else ""
    }

    /** The canvas as the chat sees and drives it; everything runs on the EDT, waiting for levels without blocking it. */
    private inner class ChatCanvas : SchematicCanvas {
        suspend fun roots(): List<Int> = rootSlot.awaitLevel()?.targets.orEmpty()

        override suspend fun snapshot(): String {
            val level = rootSlot.awaitLevel()
            return withContext(Dispatchers.EDT) {
                val root = graph.nodes[file.root]
                buildString {
                    append("File: ${root.filePath?.let(::relativePath) ?: root.name} (schematic of #${file.root} ${root.name})\n")
                    if (level == null) append("The file's schematic could not be read; reading tools still work.\n")
                    else {
                        append("Blocks of this file (line: code → symbols it leads to; * shown on the canvas):\n")
                        append(level.describeBlocks())
                        append("\nA whole-file card is a containing file expanded from a symbol, not a call to that file.\n")
                        val chains = level.branches(emptyList())
                        append("\nShown on the canvas: ")
                        append(if (chains.isEmpty()) "no cards" else chains.joinToString("; ") { chain ->
                            chain.joinToString(" → ") { "#$it ${graph.nodes[it].name}" }
                        })
                    }
                    chat?.pointer?.let { append("\nThe user opened the chat from: $it") }
                }
            }
        }

        override suspend fun showChain(chain: List<Int>, openLast: Boolean): String = withContext(Dispatchers.EDT) {
            val steps = chain.dropWhile { it == file.root }
            if (steps.isEmpty()) throw ToolInputException("symbol_ids must start with a symbol a block of this file leads to")
            steps.firstOrNull { it !in graph.nodes.indices }?.let { throw ToolInputException("No symbol #$it") }
            var slot = rootSlot
            val shown = mutableListOf<Int>()
            var last: LevelView.SummaryCard? = null
            fun done(problem: String?): String {
                last?.let { revealBreadcrumb(it, focus = false) }
                val names = shown.joinToString(" → ") { "#$it ${graph.nodes[it].name}" }
                return if (problem == null) "Shown on the canvas: $names. The last card is selected."
                else (if (shown.isEmpty()) "Nothing was shown. " else "Shown on the canvas: $names. ") +
                    "Stopped at step ${shown.size + 1}: $problem"
            }
            for ((index, id) in steps.withIndex()) {
                val level = slot.awaitLevel()
                if (disposed) return@withContext "The schematic was closed."
                if (level == null) return@withContext done("the schematic of ${graph.nodes[slot.id].name} could not be read.")
                val card = level.cardFor(id) ?: return@withContext done("#$id ${graph.nodes[id].name} is not among the blocks of " +
                    "${if (slot === rootSlot) "this file" else "#${slot.id} ${graph.nodes[slot.id].name}"}. Its blocks lead to: " +
                    level.targets.take(60).joinToString { "#$it ${graph.nodes[it].name}" }.ifEmpty { "nothing" })
                card.follow(id)
                shown += id
                last = card
                if (index < steps.lastIndex || openLast) {
                    slot = card.unfold() ?: return@withContext done(
                        if (index < steps.lastIndex) "#$id ${graph.nodes[id].name} cannot be unfolded: it is external or unresolved."
                        else null)
                }
            }
            // Let the last unfolded level appear before reporting, so a following get_canvas sees it.
            if (openLast) slot.awaitLevel()
            done(null)
        }

        override suspend fun reset(): String = withContext(Dispatchers.EDT) {
            fun cards(component: Component): List<LevelView.SummaryCard> =
                (if (component is LevelView.SummaryCard && (component.pinned || component.open)) listOf(component) else emptyList()) +
                    ((component as? Container)?.components.orEmpty().flatMap(::cards))
            cards(content).forEach { it.pinTo(false) }
            fun unpinGaps(component: Component) {
                if (component is LevelView.GapSummaryCard) component.pinTo(false)
                (component as? Container)?.components?.forEach(::unpinGaps)
            }
            unpinGaps(content)
            hoverExit.stop()
            activeBlock = null
            hoveredBlock = null
            hoveredGap = null
            trailTarget = null
            refreshCards()
            scrollTo(0, 0)
            "The canvas shows the plain file again."
        }
    }

    /** A chat about this schematic, floating over the canvas's right side. */
    private inner class SchematicChatBlock : FloatingPanel(BorderLayout(0, JBUI.scale(4))) {
        private val canvasAccess = ChatCanvas()
        private val tools by lazy {
            val graphTools = SchematicGraph(lifted, result.occurrences, summaries::get, ::relativePath, ::readSource,
                snapshot = { currentAnalysis.let { it.lifted to it.result.occurrences } },
                expand = { file.exploration?.expand(it) })
            schematicChatTools(project, graphTools, canvasAccess, canvasAccess::roots)
        }
        private val session = object : ChatSession(project, scope, backendFactory = { _ -> createBackend() },
            welcome = "Ask about ${graph.nodes[file.root].name} and the code " +
            "it reaches. I read the code in the background and show the answer here as a chain of unfolded calls.") {
            override fun replyProgressTitle(text: String) = "Schematic chat: ${graph.nodes[file.root].name}"
        }

        private fun createBackend(): ChatBackend = LlmChatBackend(ChatSettings.getInstance().createClient()::complete, tools,
            schematicChatPrompt, maxContextChars = 120_000, maxToolRounds = 60, context = canvasAccess::snapshot,
            contextLabel = "Current schematic", evictionTarget = 0.7)

        val chat = ChatPanel(project, session, title = "Schematic Chat", showDashboard = false, compactTools = true)
        /** What the user right-clicked to open the chat; the next questions are likely about it. */
        var pointer: String? = null
            private set
        private val pointerLabel = JLabel().apply { foreground = UIUtil.getContextHelpForeground() }
        private val pointerRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(pointerLabel, BorderLayout.CENTER)
            add(ActionLink("Forget") { point(null) }.apply { toolTipText = "Stop telling the chat about this place" }, BorderLayout.EAST)
        }

        init {
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(pointerRow, BorderLayout.CENTER)
                add(JButton("Hide").apply {
                    isFocusable = false
                    toolTipText = "Hide the chat; Chat in the canvas menu shows it again with the same conversation"
                    addActionListener {
                        this@SchematicChatBlock.isVisible = false
                        content.requestFocusInWindow()
                    }
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(chat, BorderLayout.CENTER)
        }

        fun point(value: String?) {
            pointer = value
            pointerLabel.text = value?.let { "About: ${it.lineSequence().first().take(80)}" }
            pointerLabel.toolTipText = value
            pointerRow.isVisible = value != null
            revalidate()
        }

        // The panel behind is translucent; the conversation needs a solid background to stay readable.
        override fun paintComponent(g: Graphics) {
            g.color = UIUtil.getPanelBackground()
            g.fillRect(0, 0, width, height)
            super.paintComponent(g)
        }

        fun dispose() {
            chat.dispose()
            session.discard()
        }
    }

    /** Summarizes only the functions explicitly requested by the reader. */
    private fun summarize(roots: List<Int>) {
        if (disposed || !summaries.needs(roots)) return
        running++
        updateStatus()
        scope.launch(Dispatchers.EDT) {
            try {
                failures += withBackgroundProgress(project, "Summarizing schematic: ${graph.nodes[file.root].name}") {
                    withContext(Dispatchers.Default) {
                        withPhaseProgress("Reading source facts…", "functions") { onProgress ->
                            summaries.summarize(roots) { phase, done, total ->
                                onProgress(phase, done, total)
                                ApplicationManager.getApplication().invokeLater {
                                    if (!disposed && running > 0) showStatus("$phase: $done / $total")
                                    if (!disposed) refreshSummaries(content)
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Schematic summaries failed", e)
                error = e.message ?: e.javaClass.simpleName
            } finally {
                running--
                if (!disposed) updateStatus()
            }
        }
    }

    private fun updateStatus() {
        showStatus(when {
            running > 0 -> "Summarizing…"
            error != null -> "Summaries failed: $error. Check Settings → Tools → Insight Chat."
            failures > 0 -> "$failures functions could not be summarized."
            else -> ""
        })
        refreshSummaries(content)
    }

    private fun refreshSummaries(component: Component) {
        if (component is LevelView.SummaryCard) component.update()
        if (component is Container) component.components.forEach(::refreshSummaries)
    }

    private fun foldAll() {
        fun cards(component: Component): List<LevelView.SummaryCard> =
            (if (component is LevelView.SummaryCard && component.open) listOf(component) else emptyList()) +
                ((component as? Container)?.components.orEmpty().flatMap(::cards))
        // Outermost first: folding hides what is inside, which then stays folded too.
        cards(content).forEach { if (it.open) it.toggle() }
    }

    private fun navigate(file: VirtualFile, line: Int) = OpenFileDescriptor(project, file, line, 0).navigate(true)

    private fun navigate(id: Int) {
        val pointer = result.pointers.getOrNull(id) ?: return
        val target = com.intellij.openapi.application.ReadAction.compute<Pair<VirtualFile, Int>?, Throwable> {
            val element = pointer.element?.takeIf { it.isValid } ?: return@compute null
            element.containingFile?.virtualFile?.let { it to element.textOffset }
        } ?: return
        OpenFileDescriptor(project, target.first, target.second).navigate(true)
    }

    /** Resolves only [id]'s visible scope, then shows it without requesting descendant summaries. */
    private inner class LevelSlot(val id: Int) : JPanel(BorderLayout()) {
        private var loading: Job? = null
        /** Completes with the loaded level, or null when it could not be read; replaced by a retry. */
        @Volatile private var ready = CompletableDeferred<LevelView?>()
        fun cancelLoad() { loading?.cancel() }

        /** The level once loaded, or null when it failed or took too long. */
        suspend fun awaitLevel(): LevelView? = withTimeoutOrNull(LOAD_TIMEOUT_MS) { ready.await() }

        var showSource = true
            set(value) {
                field = value
                (components.firstOrNull() as? LevelView)?.showSource(value)
            }

        init {
            isOpaque = false
            load()
        }

        private fun load() {
            removeAll()
            add(JLabel("Reading ${graph.nodes[id].name}…").zoomFont())
            revalidate()
            repaint()
            if (ready.isCompleted) ready = CompletableDeferred()
            val loaded = ready
            loading = scope.launch(Dispatchers.EDT) {
                try {
                    build()
                } catch (e: Throwable) {
                    if (e is CancellationException || e is ControlFlowException) throw e
                    LOG.warn("Unable to load schematic for ${graph.nodes[id].name}", e)
                    removeAll()
                    add(JLabel("Unable to load schematic: ${e.message ?: e.javaClass.simpleName}. See the IDE log.").zoomFont())
                    add(ActionLink("Retry") { load() }.zoomFont())
                    revalidate()
                    repaint()
                } finally { loaded.complete(components.firstOrNull() as? LevelView) }
            }
        }

        private suspend fun build() {
            val schematic = try {
                withBackgroundProgress(project, "Building schematic: ${graph.nodes[id].name}") {
                    file.exploration?.expand(id)
                    smartReadAction(project) { SchematicBuilder.build(result, hierarchy, calls, id) }
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                removeAll()
                add(ActionLink("Schematic loading cancelled. Retry") { load() }.zoomFont())
                revalidate()
                repaint()
                return
            }
            removeAll()
            if (schematic == null) add(JLabel("Source of ${graph.nodes[id].name} is no longer available. Reopen the schematic.").zoomFont())
            else add(LevelView(schematic).apply { showSource(this@LevelSlot.showSource) })
            revalidate()
            repaint()
        }
    }

    /** The rows of one schematic on the left and a summary card per block target on the right, joined by arrows. */
    private inner class LevelView(private val schematic: Schematic) : JPanel(null), Zoomable {
        private val cards = mutableListOf<SummaryCard>()
        private val rows = mutableListOf<JComponent>()
        /** Both kinds share one card column, in source order. */
        private val externalCards: List<ExternalCard> get() =
            (cards + rows.filterIsInstance<GapView>().mapNotNull { it.card }).sortedBy { it.rowIndex }
        private val indent = schematic.lines.filter { it.isNotBlank() }.minOfOrNull { line -> detab(line).takeWhile { it == ' ' }.length } ?: 0
        private val charWidth get() = getFontMetrics(codeFont).charWidth(' ')
        /** Links of this level in source order; a split line has several. */
        val blocks get() = rows.filterIsInstance<LineView>().flatMap { it.blocks }
        private var source: SchematicSourceEditor? = null
        private var showPlainSource = true
        private var gapsRunning = false
        private var gapSummaryJob: Job? = null
        private var released = false

        fun release() {
            released = true
            if (hoveredGap?.let { SwingUtilities.isDescendingFrom(it, this) } == true) {
                hoverExit.stop()
                hoveredGap = null
            }
            gapSummaryJob?.cancel()
            gapSummaryJob = null
        }

        fun canSummarizeGaps() = !released && !gapsRunning && rows.filterIsInstance<GapView>().any { it.summary == null && !it.skipped && it.hasText }

        fun summarizeGaps() {
            if (disposed || !canSummarizeGaps()) return
            val gaps = rows.filterIsInstance<GapView>().filter { it.summary == null && !it.skipped && it.hasText }
            gapsRunning = true
            running++
            error = null
            updateStatus()
            gapSummaryJob = scope.launch(Dispatchers.EDT) {
                try {
                    withBackgroundProgress(project, "Summarizing gaps: ${schematic.file.name}") {
                        val workflow = SchematicGapWorkflow(ChatSettings.getInstance().createClient()::complete)
                        for (gap in gaps) {
                            currentCoroutineContext().ensureActive()
                            try {
                                val input = smartReadAction(project) { schematicGapSource(project, schematic, gap.row) }
                                val summary = withContext(Dispatchers.Default) { workflow.summarize(input, gap.row) }
                                currentCoroutineContext().ensureActive()
                                if (released) return@withBackgroundProgress
                                if (summary == null) {
                                    gap.skipped = true
                                    continue
                                }
                                if (!disposed) {
                                    gap.showSummary(summary)
                                    source?.let { releaseBranch(it); remove(it); source = null }
                                    switchSourceView()
                                    revalidate()
                                    repaint()
                                }
                            } catch (e: Throwable) {
                                if (e is CancellationException || e is ControlFlowException) throw e
                                LOG.warn("Schematic gap summary failed", e)
                                error = e.message ?: e.javaClass.simpleName
                            }
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException || e is ControlFlowException) throw e
                    LOG.warn("Schematic gaps failed", e)
                    error = e.message ?: e.javaClass.simpleName
                } finally {
                    gapsRunning = false
                    running--
                    if (!disposed) updateStatus()
                }
            }
        }
        fun cardsFor(block: BlockView) = cards.filter { it.row === block }
        /** Symbols this level's blocks lead to, in source order. */
        val targets: List<Int> get() = schematic.targets

        /** The first card showing [id], or else one whose unknown callee may be [id]. */
        fun cardFor(id: Int): SummaryCard? = cards.firstOrNull { it.shown == id || it.symbol == id }
            ?: cards.firstOrNull { id in it.choices }

        /** For the chat: one line per block, with 1-based lines; `*` marks symbols with a card on the canvas. */
        fun describeBlocks(): String = blocks.take(MAX_DESCRIBED_BLOCKS).joinToString("\n") { block ->
            "${block.row.headline + 1}: ${text(block.row.headline).trim().take(120)} → " +
                cardsFor(block).joinToString(", ") { card ->
                    "#${card.symbol} ${graph.nodes[card.symbol].name}" +
                        (if (card.shown != card.symbol) " (whole file #${card.shown} ${graph.nodes[card.shown].name})" else "") +
                        if (card.pinned || card.open) "*" else ""
                }
        } + if (blocks.size > MAX_DESCRIBED_BLOCKS) "\n[${blocks.size - MAX_DESCRIBED_BLOCKS} more blocks]" else ""

        /** The chains of pinned or unfolded cards from this level down, each prefixed by [prefix]. */
        fun branches(prefix: List<Int>): List<List<Int>> = cards.filter { it.pinned || it.open }.flatMap { card ->
            val path = prefix + card.shown
            card.level?.takeIf { card.open }?.let { it.components.firstOrNull() as? LevelView }?.branches(path)
                ?.ifEmpty { null } ?: listOf(path)
        }

        /** For the chat: [line] (0-based) of this level and the [selection] the user made on it. */
        private fun describeLine(line: Int, selection: String?): String =
            "${relativePath(schematic.file.path)}:${line + 1} ${schematic.text(line).trim().take(160)}" +
                (if (schematic.scope != file.root) " (in the unfolded #${schematic.scope} ${graph.nodes[schematic.scope].name})" else "") +
                selection?.takeIf { it.isNotBlank() }?.let { "\nSelected text:\n${it.take(MAX_SELECTION)}" }.orEmpty()

        override fun isOptimizedDrawingEnabled() = false

        init {
            isOpaque = false
            for (row in schematic.rows) {
                val view = when (row) {
                    is CodeRow -> GapView(row)
                    is BlockRow -> LineView(row)
                }
                rows += view
                add(view)
                if (view is LineView) view.blocks.forEach { block ->
                    block.link.targets.forEach { target ->
                        cards += SummaryCard(target, block, rows.lastIndex, block.link.occurrences.filter { target in it.displayTargets }).also(::add)
                    }
                }
            }
            switchSourceView()
            refreshCards()
        }

        /** Keep shared blocks and cards in place, and allocate native editors only while that mode is selected. */
        fun switchSourceView() {
            val lines = rows.filterIsInstance<LineView>()
            lines.forEach { applyZoom(it) }
            if (useNativeSource && source == null) {
                val folded = lines.map { it.row to it } + rows.filterIsInstance<GapView>().filter { it.summary != null }.map { it.row to it }
                source = SchematicSourceEditor(project, schematic, folded, ::wheel, onNavigate = { stroke ->
                    val key = content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke(stroke))
                    content.actionMap.get(key)?.actionPerformed(ActionEvent(content, 0, stroke))
                }, onContextMenu = { event, line, selection ->
                    val editor = source?.editor
                    showContextMenu(event, describeLine(line, selection),
                        if (selection.isNullOrEmpty() || editor == null) null else { { editor.selectionModel.copySelectionToClipboard() } })
                }).also {
                    sourceEditors += it
                    // Earlier children paint above the native editor, inside the space reserved by its folds.
                    add(it)
                }
            } else if (!useNativeSource) source?.let {
                releaseBranch(it)
                remove(it)
                source = null
            }
            showSource(showPlainSource)
        }

        override fun applyZoom() {
            source?.takeIf { it.isVisible }?.applyZoom(zoom)
        }

        fun refreshCards() {
            val preview = if (hoveredGap == null) hoveredBlock ?: activeBlock else null
            cards.forEach {
                val visible = it.pinned || it.row === preview
                val revealed = visible && !it.isVisible
                it.isVisible = visible
                if (revealed) applyZoom(it)
            }
            rows.filterIsInstance<LineView>().forEach { it.repaintBorder() }
            rows.filterIsInstance<GapView>().forEach { gap ->
                gap.card?.let { card ->
                    val visible = card.pinned || hoveredGap === gap
                    val revealed = visible && !card.isVisible
                    card.isVisible = visible
                    if (revealed) applyZoom(card)
                }
                gap.repaintBorder()
            }
            revalidate()
            repaint()
        }

        private fun text(line: Int) = detab(schematic.text(line)).drop(indent).trimEnd()

        /** Compact mode hides plain code in either renderer, preserving the same loaded blocks and branches. */
        fun showSource(show: Boolean) {
            showPlainSource = show
            source?.isVisible = show
            rows.filterIsInstance<GapView>().forEach {
                it.isVisible = (show && !useNativeSource) || it.summary != null
                it.showCode(show && !useNativeSource)
                if (it.isVisible) applyZoom(it)
            }
            applyZoom()
            revalidate()
            repaint()
        }

        /** A gap keeps its source in the file and links to a separate summary card. */
        inner class GapView(val row: CodeRow) : JPanel(BorderLayout()), Zoomable {
            var summary: String? = null
                private set
            var skipped = false
            val hasText = (row.first..row.last).any { schematic.text(it).isNotBlank() }
            var card: GapSummaryCard? = null
                private set
            private val code = codeView(row)
            val box = JPanel(BorderLayout()).apply { isOpaque = false; add(code, BorderLayout.CENTER) }
            fun showCode(show: Boolean) { code.isVisible = show || summary != null }
            init {
                isOpaque = false
                // Register the hover surface before this row joins the observed component tree.
                box.addMouseListener(object : MouseAdapter() {})
                add(box, BorderLayout.NORTH)
            }
            fun showSummary(value: String) {
                if (summary != null) return
                summary = value
                card = GapSummaryCard(this, rows.indexOf(this), value).also { this@LevelView.add(it) }
                box.background = gapBackground
                box.isOpaque = true
                box.toolTipText = "Summarized gap: hover to preview; click to pin the summary"
                onClick(box) { card?.pinTo(card?.pinned != true) }
                onClick(code) { card?.pinTo(card?.pinned != true) }
                applyZoom()
            }
            override fun applyZoom() {
                repaintBorder()
                components.forEach { this@SchematicPanel.applyZoom(it) }
            }

            fun repaintBorder() {
                if (summary == null) return
                val focused = hoveredGap === this
                box.background = if (focused) gapFocusedBackground else gapBackground
                box.border = gapBorder(if (focused || card?.pinned == true) gapAccent else JBColor.border(), 1, 6)
            }
        }

        /** An amber left rule distinguishes gap summaries even without the background color. */
        private fun gapBorder(outline: Color, vertical: Int, horizontal: Int) = BorderFactory.createCompoundBorder(
            line(outline, 1), BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, scaled(3), 0, 0, gapAccent),
                pixelBorder(scaled(vertical), scaled(horizontal), scaled(vertical), scaled(horizontal))))

        /** Geometry shared by symbol and gap summary cards. */
        abstract inner class ExternalCard(val rowIndex: Int) : JPanel(BorderLayout()), Zoomable {
            abstract val anchor: JComponent
            abstract val marked: Boolean
            abstract val headerCenter: Int
            open val accent: Color get() = blockOpen
        }

        inner class GapSummaryCard(val gap: GapView, rowIndex: Int, summary: String) : ExternalCard(rowIndex) {
            var pinned = false
                private set
            override val anchor get() = gap.box
            override val marked get() = pinned || hoveredGap === gap
            override val accent: Color get() = gapAccent
            private val header = JPanel(BorderLayout())
            override val headerCenter get() = header.y + header.height / 2
            private val pinToggle = ActionLink("Pin") { pinTo(!pinned) }.zoomFont()

            init {
                background = gapBackground
                // Passive summary text routes hover events to this card, including during arrow crossing.
                addMouseListener(object : MouseAdapter() {})
                header.isOpaque = false
                header.add(HtmlLabel().apply { body = escape(summary).replace("\n", "<br>") }, BorderLayout.CENTER)
                header.add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(pinToggle)
                    add(ActionLink("Source") { navigate(schematic.file, gap.row.first) }.zoomFont())
                }, BorderLayout.EAST)
                add(header, BorderLayout.NORTH)
                isVisible = false
                applyZoom()
            }

            fun pinTo(value: Boolean) {
                pinned = value
                pinToggle.text = if (value) "Unpin" else "Pin"
                if (!value && hoveredGap === gap) {
                    hoverExit.stop()
                    hoveredGap = null
                }
                applyZoom()
                this@SchematicPanel.refreshCards()
            }

            override fun applyZoom() {
                border = gapBorder(if (pinned) gapAccent else JBColor.border(), 6, 8)
                (header.layout as BorderLayout).hgap = scaled(6)
            }
        }

        /** Plain code; runs of blank lines show as one. */
        private fun codeView(row: CodeRow): JComponent {
            val shown = (row.first..row.last).filterIndexed { index, line ->
                schematic.text(line).isNotBlank() || index == 0 || schematic.text(line - 1).isNotBlank()
            }
            return CodeView(shown.joinToString("\n") { text(it) }).apply {
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount != 2 || panned) return
                        val index = runCatching { getLineOfOffset(viewToModel2D(e.point)) }.getOrDefault(0)
                        navigate(schematic.file, shown.getOrElse(index) { row.first })
                    }
                })
            }
        }

        private fun measure(): LevelMeasurement {
            val rowSizes = rows.map { if (it.isVisible) it.preferredSize else Dimension() }
            val native = source?.takeIf { it.isVisible }
            val sourceSize = native?.preferredSize ?: Dimension()
            val sourceWidth = if (native != null) maxOf(sourceSize.width, rowSizes.maxOfOrNull { it.width } ?: 0) else maxOf(
                rowSizes.maxOfOrNull { it.width }?.coerceAtMost(scaled(MAX_SOURCE_WIDTH)) ?: 0,
                rows.indices.filter { (rows[it] as? LineView)?.onTrail == true }
                    .maxOfOrNull { rowSizes[it].width }?.coerceAtMost(JBUI.scale(MAX_SOURCE_WIDTH)) ?: 0,
            )
            var bottom = 0
            val rowTops = if (native != null) rows.map { when {
                it is LineView -> native.blockY(it.row)
                it is GapView && it.summary != null -> native.blockY(it.row)
                else -> 0
            } }
                else rowSizes.map { size -> bottom.also { bottom += size.height } }
            val sourceHeight = if (native != null) sourceSize.height else bottom
            val external = externalCards
            val cardSizes = external.map { if (it.isVisible) it.preferredSize else Dimension() }
            var cardBottom: Int? = null
            val cardTops = external.mapIndexed { index, card ->
                if (!card.isVisible) 0 else
                    maxOf(rowTops[card.rowIndex], cardBottom?.plus(scaled(GAP)) ?: 0).also { cardBottom = it + cardSizes[index].height }
            }
            val cardsWidth = cardSizes.maxOfOrNull { it.width } ?: 0
            val width = sourceWidth + if (external.none { it.isVisible }) 0 else scaled(ARROW_WIDTH) + cardsWidth
            return LevelMeasurement(sourceWidth, rowSizes, rowTops, cardSizes, cardTops, Dimension(width, maxOf(sourceHeight, cardBottom ?: 0)))
        }

        override fun doLayout() {
            with(measure()) {
                val native = source?.takeIf { it.isVisible }
                source?.setBounds(0, 0, sourceWidth, native?.preferredSize?.height ?: 0)
                // Lay out through the viewport before converting native fold coordinates to canvas coordinates.
                fun layoutSource(component: Component) {
                    component.doLayout()
                    (component as? Container)?.components?.forEach(::layoutSource)
                }
                native?.let(::layoutSource)
                val origin = native?.textOrigin() ?: Point()
                rows.forEachIndexed { index, row ->
                    row.setBounds(origin.x, origin.y + rowTops[index], sourceWidth - origin.x, rowSizes[index].height)
                }
                val x = sourceWidth + scaled(ARROW_WIDTH)
                externalCards.forEachIndexed { index, card ->
                    if (card.isVisible) card.setBounds(x, origin.y + cardTops[index], cardSizes[index].width, cardSizes[index].height)
                    else card.setBounds(0, 0, 0, 0)
                }
            }
        }

        override fun getPreferredSize(): Dimension = measure().size

        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.stroke = BasicStroke(scaled(1).toFloat())
                val head = scaled(5)
                for (card in externalCards.filter { it.isVisible }) {
                    // Links of a split line share its right edge, so the arrow never crosses the rest of the line.
                    val box = card.anchor
                    val from = SwingUtilities.convertRectangle(box.parent, box.bounds, this@LevelView)
                    g2.color = if (card.marked) card.accent else JBColor.border()
                    val startX = from.x + from.width
                    val startY = from.y + from.height / 2
                    val endX = card.x - 1
                    val endY = card.y + minOf(card.headerCenter, card.height / 2)
                    val middle = card.x - scaled(ARROW_WIDTH) / 2
                    g2.drawPolyline(intArrayOf(startX, middle, middle, endX), intArrayOf(startY, startY, endY, endY), 4)
                    g2.fillPolygon(intArrayOf(endX, endX - head, endX - head), intArrayOf(endY, endY - head, endY + head), 3)
                }
            } finally {
                g2.dispose()
            }
        }

        /** Read-only code that leaves dragging to the canvas: no focus, selection or scrolling to the caret. */
        inner class CodeView(text: String) : JTextArea(text), Zoomable {
            init {
                caret = object : DefaultCaret() {
                    override fun adjustVisibility(nloc: java.awt.Rectangle?) = Unit
                    override fun setSelectionVisible(vis: Boolean) = super.setSelectionVisible(false)
                }
                isEditable = false
                isFocusable = false
                autoscrolls = false
                isOpaque = false
                foreground = UIUtil.getLabelForeground()
                applyZoom()
            }

            override fun applyZoom() {
                font = codeFont
                border = pixelBorder(0, scaled(4), 0, scaled(4))
            }
        }

        /**
         * A block row at the code's indentation. Its only link is the whole row, or on a split line each call is a link
         * of its own between the line's plain text.
         */
        inner class LineView(val row: BlockRow) : JPanel(BorderLayout()), Zoomable {
            private val links = mutableListOf<BlockView>()
            /** Titles and plain text of the line, scaled together so a split line stays one readable line. */
            val labels = mutableListOf<CallableLabel>()
            val blocks: List<BlockView> get() = links
            /** The line's frame; for a whole-row link, that link's box. */
            val box: JPanel
            val onTrail get() = links.any { it.onTrail }

            init {
                isOpaque = false
                if (!row.split) {
                    val block = BlockView(this, row.links.single(), text(row.headline).trimStart())
                    links += block
                    box = block.box
                    add(block, BorderLayout.WEST)
                } else {
                    box = JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.X_AXIS)
                        background = blockBackground
                    }
                    fun plain(text: String) {
                        box.add(CallableLabel(detab(text)) { onTrail }.also { labels += it; it.alignmentY = CENTER_ALIGNMENT })
                    }
                    fun link(link: SchematicLink, label: String) {
                        box.add(BlockView(this, link, label).also { links += it; it.alignmentY = CENTER_ALIGNMENT })
                    }
                    val raw = schematic.text(row.headline)
                    val end = raw.trimEnd().length
                    var at = raw.length - raw.trimStart().length
                    for (link in row.links.filter { it.columns != null }) {
                        val columns = link.columns!!
                        val start = columns.first.coerceIn(at, end)
                        val stop = (columns.last + 1).coerceIn(start, end)
                        if (start > at) plain(raw.substring(at, start))
                        link(link, if (stop > start) detab(raw.substring(start, stop)) else names(link))
                        at = stop
                    }
                    if (end > at) plain(raw.substring(at, end))
                    // Calls whose place on the line is unknown follow it, named.
                    row.links.filter { it.columns == null }.forEach { link ->
                        plain("  ")
                        link(link, "→ ${names(link)}")
                    }
                    if (row.last > row.first) box.add(JLabel("  ${row.last - row.first + 1} lines").apply {
                        foreground = UIUtil.getContextHelpForeground()
                        alignmentY = CENTER_ALIGNMENT
                    }.zoomFont())
                    box.toolTipText = "<html>Calls, types and properties on this line are separate links<br>" +
                        "<i>Click one, or reach it with Tab</i></html>"
                    add(box, BorderLayout.WEST)
                }
                applyZoom()
            }

            private fun names(link: SchematicLink) = link.targets.joinToString(", ") { graph.nodes[it].name.substringBefore('(') }

            fun refreshTitle() {
                labels.forEach { it.applyZoom() }
                box.revalidate()
                source?.takeIf { it.isVisible }?.refreshBlocks()
                links.forEach { block -> cardsFor(block).forEach { it.refreshTitle() } }
                revalidate()
            }

            override fun applyZoom() {
                val depth = detab(schematic.text(row.headline)).takeWhile { it == ' ' }.length - if (useNativeSource) 0 else indent
                border = pixelBorder(0, scaled(4) + charWidth * depth.coerceAtLeast(0), 0, 0)
                repaintBorder()
            }

            fun repaintBorder() {
                links.forEach { it.repaintBorder() }
                // Each link of a split line marks itself; the line keeps a neutral frame.
                if (row.split) box.border = BorderFactory.createCompoundBorder(line(JBColor.border(), 2),
                    pixelBorder(scaled(1), scaled(4), scaled(1), scaled(4)))
            }

            override fun getMaximumSize(): Dimension = preferredSize
        }

        /** A link of a block row, showing [label]; clicking pins the cards of its targets. */
        inner class BlockView(val line: LineView, val link: SchematicLink, private val label: String) : JPanel(BorderLayout()), Zoomable {
            val row get() = line.row
            val box = JPanel(BorderLayout())
            val trailName get() = link.targets.joinToString(", ") { graph.nodes[it].name }
            val onTrail get() = inBreadcrumbs(this) || breadcrumbPath.any { (_, component) ->
                component is SummaryCard && component.row === this
            }
            private val title = CallableLabel(label) { line.onTrail }.also { line.labels += it }

            init {
                isOpaque = false
                box.background = blockBackground
                box.add(title, BorderLayout.CENTER)
                if (!row.split && row.last > row.first) {
                    box.add(JLabel("${row.last - row.first + 1} lines").apply { foreground = UIUtil.getContextHelpForeground() }.zoomFont(),
                        BorderLayout.EAST)
                }
                add(box, BorderLayout.WEST)
                applyZoom()
                onClick(box) { pin() }
                box.toolTipText = "<html>" + link.targets.joinToString("<br>") { escape(graph.nodes[it].name) } + "<br><i>Click or Enter to pin summaries</i></html>"
            }

            /** Pins this block's cards; on a block that is already pinned it releases them and drops focus, like Esc. */
            fun pin() {
                val mine = cardsFor(this)
                if (mine.isNotEmpty() && mine.all { it.pinned } && this === (hoveredBlock ?: activeBlock)) {
                    hoverExit.stop()
                    activeBlock = null
                    hoveredBlock = null
                    hoveredGap = null
                    mine.forEach { it.pinTo(false) }
                    this@SchematicPanel.refreshCards()
                    return
                }
                activeBlock = this
                trailTarget = this
                mine.forEach { it.pinTo(true) }
                this@SchematicPanel.refreshCards()
            }

            fun describe(): String = describeLine(row.headline, null) +
                when {
                    !row.split -> ""
                    link.reference -> " (the reference to `${label.trim()}`)"
                    row.declared != null && row.declared in link.targets -> " (the declared name `${label.trim()}`)"
                    else -> " (the call at `${label.trim()}`)"
                } + " → " +
                link.targets.joinToString(", ") { "#$it ${graph.nodes[it].name}" }

            fun refreshTitle() = line.refreshTitle()

            override fun applyZoom() {
                (box.layout as BorderLayout).hgap = scaled(8)
                repaintBorder()
            }

            fun repaintBorder() {
                val focused = this === (hoveredBlock ?: activeBlock)
                val marked = focused || cardsFor(this).any { it.pinned || it.open }
                // A constant inset keeps focusing a block from moving the source under the pointer.
                box.border = if (row.split) BorderFactory.createCompoundBorder(line(if (marked) blockOpen else JBColor.border(), 1),
                    pixelBorder(0, scaled(2), 0, scaled(2)))
                else BorderFactory.createCompoundBorder(line(if (marked) blockOpen else JBColor.border(), 2),
                    pixelBorder(scaled(1), scaled(6), scaled(1), scaled(6)))
                box.background = if (focused) blockFocusedBackground else blockBackground
            }

            override fun getMaximumSize(): Dimension = preferredSize
        }

        /** The summary of [target]; unfolds into [target]'s own schematic. */
        inner class SummaryCard(val target: Int, val row: BlockView, rowIndex: Int,
            private val occurrences: List<CallOccurrence>) : ExternalCard(rowIndex) {
            override val anchor get() = row.line.box
            override val marked get() = pinned || open
            var pinned = false
                private set
            var open = false
                private set
            private val node = graph.nodes[target]
            private val expandable = node.inProject && !node.unresolved
            /** For an unknown callee, declarations sharing its name; the user's [chosen] one unfolds in place of [target]. */
            val choices = if (node.unresolved) occurrences.flatMap { it.choices }.distinct() else emptyList()
            private var chosen: Int? = null
            /** The callee, or the declaration the user picked for an unknown one. */
            val symbol: Int get() = chosen ?: target
            private var wholeFile: Int? = null
            /** The scope shown by the card, including a containing file the reader expanded to. */
            val shown: Int get() = wholeFile ?: symbol
            private val containingFile: Int? get() = graph.nodes[symbol].takeIf {
                it.inProject && !it.unresolved && it.kind != SymbolKind.FILE
            }?.filePath?.let { filesByPath[it]?.id }
            private val choiceLinks = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            private var allChoices = false
            private val arrow = JLabel(if (expandable) "▸" else "·").zoomFont()
            private val text = HtmlLabel { inBreadcrumbs(this) || inBreadcrumbs(row) }
            private val header = JPanel(BorderLayout())
            private val actions = JPanel()
            val trailName get() = graph.nodes[shown].name
            val trailAnchor get() = header
            private val details = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                isVisible = false
            }
            var level: LevelSlot? = null
                private set
            private val pinToggle = ActionLink("Pin") {
                trailTarget = this
                pinTo(!pinned)
            }.zoomFont()
            private val sourceToggle = ActionLink("Compact") {
                level?.let { slot ->
                    slot.showSource = !slot.showSource
                    sourceToggleText()
                    this@LevelView.revalidate()
                    this@LevelView.repaint()
                }
            }.apply { isVisible = false; verticalAlignment = SwingConstants.TOP }.zoomFont()
            private val fileToggle = ActionLink("Show whole file") { showWholeFile(wholeFile == null) }
                .apply { verticalAlignment = SwingConstants.TOP }.zoomFont()
            override val headerCenter get() = header.y + header.height / 2

            init {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                header.isOpaque = false
                header.add(arrow, BorderLayout.WEST)
                header.add(text, BorderLayout.CENTER)
                header.add(actions.apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(pinToggle)
                    add(ActionLink(if (node.unresolved) "Call site" else "Source") { navigate(if (node.unresolved) target else shown) }
                        .apply { verticalAlignment = SwingConstants.TOP }.zoomFont())
                    if (occurrences.isNotEmpty()) add(ActionLink("Explain link") {
                        details.isVisible = !details.isVisible
                        if (details.isVisible) applyZoom(details)
                        this@LevelView.revalidate()
                        this@LevelView.repaint()
                    }.zoomFont())
                    add(sourceToggle)
                    add(fileToggle)
                }, BorderLayout.EAST)
                add(if (choices.isEmpty()) header else JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(header.apply { alignmentX = LEFT_ALIGNMENT })
                    add(choiceLinks.apply { alignmentX = LEFT_ALIGNMENT })
                }, BorderLayout.NORTH)
                showChoices()
                add(details, BorderLayout.SOUTH)
                for (occurrence in occurrences) {
                    details.add(HtmlLabel().apply {
                        body = "<code>${escape(occurrence.expression)}</code><br>${escape(occurrence.explanation)}"
                    })
                    details.add(ActionLink("Call site") {
                        OpenFileDescriptor(project, schematic.file, occurrence.startOffset).navigate(true)
                    }.zoomFont())
                    fun links(label: String, ids: List<Int>) {
                        for (id in ids) details.add(ActionLink("$label: ${graph.nodes[id].name} · ${graph.nodes[id].location}") { navigate(id) }.zoomFont())
                    }
                    links("Candidate", occurrence.targets)
                    links("Receiver ${occurrence.receiver.orEmpty()}", occurrence.receiverTargets)
                    occurrence.receiverType?.let { details.add(JLabel("Receiver type: $it").zoomFont()) }
                    links("Binding", occurrence.bindings.filter { it !in occurrence.targets })
                    if (occurrence.receiver != null && occurrence.receiverTargets.isEmpty()) {
                        details.add(JLabel("Receiver ${occurrence.receiver}: declaration unknown").zoomFont())
                    }
                }
                if (expandable || choices.isNotEmpty()) {
                    onClick(header) { toggle() }
                    onClick(text) { toggle() }
                    onClick(arrow) { toggle() }
                }
                applyZoom()
                update()
            }

            fun addContextActions(menu: JPopupMenu) {
                for (link in actions.components.filterIsInstance<ActionLink>().filter { it.isVisible }) {
                    menu.add(JMenuItem(link.text).apply {
                        isEnabled = link.isEnabled
                        toolTipText = link.toolTipText
                        addActionListener { link.doClick() }
                    })
                }
            }

            private fun showChoices() {
                choiceLinks.removeAll()
                val shown = if (allChoices) choices else choices.take(CHOICES_SHOWN)
                for (id in shown) {
                    val choice = graph.nodes[id]
                    val label = listOf(choice.name.take(60), choice.container.take(60), choice.location).filter { it.isNotEmpty() }
                        .joinToString(" · ") + if (choice.inProject) "" else " (external)"
                    choiceLinks.add(ActionLink(if (id == chosen) "▸ $label" else label) { pick(id) }.zoomFont())
                }
                if (shown.size < choices.size) choiceLinks.add(ActionLink("Show all ${choices.size}") {
                    allChoices = true
                    showChoices()
                }.zoomFont())
                choiceLinks.revalidate()
                choiceLinks.repaint()
            }

            /** Follows [id] as if it were the callee: project code unfolds here, external code opens its source. */
            private fun pick(id: Int) {
                trailTarget = this
                pinTo(true)
                if (!graph.nodes[id].inProject) { navigate(id); return }
                if (open) toggle()
                level?.let { releaseBranch(it); remove(it) }
                level = null
                wholeFile = null
                chosen = id
                summarize(listOf(id))
                showChoices()
                toggle()
                update()
            }

            /**
             * Shows [id] on this card for the chat: pinned, and for an unknown callee its project declaration picked.
             * External code stays a card; opening its source would leave the schematic.
             */
            fun follow(id: Int) {
                trailTarget = this
                if (id == symbol && wholeFile != null) showWholeFile(false)
                if (id != shown && id in choices && graph.nodes[id].inProject) pick(id) else pinTo(true)
            }

            /** Replace this branch with the containing file's normal schematic, keeping its caller in place. */
            private fun showWholeFile(show: Boolean) {
                val scope = if (show) containingFile ?: return else null
                if (open) toggle()
                level?.let { releaseBranch(it); remove(it) }
                level = null
                wholeFile = scope
                update()
                toggle()
            }

            /** Unfolds the card unless it is open already; its level, or null when it cannot unfold. */
            fun unfold(): LevelSlot? {
                if (!open) toggle()
                return level.takeIf { open }
            }

            fun describe(): String = "the card of ${symbolText(shown)}" +
                (if (wholeFile != null) " (whole file expanded from ${symbolText(symbol)})" else "") + " beside ${row.describe()}"

            private fun symbolText(id: Int) = "#$id ${graph.nodes[id].name}" +
                graph.nodes[id].location.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

            override fun applyZoom() {
                border = BorderFactory.createCompoundBorder(line(if (pinned) blockOpen else JBColor.border(), 1), pixelBorder(scaled(6), scaled(8), scaled(6), scaled(8)))
                (layout as BorderLayout).vgap = scaled(6)
                (header.layout as BorderLayout).hgap = scaled(6)
            }

            fun refreshTitle() {
                text.applyZoom()
                revalidate()
            }

            fun pinTo(value: Boolean) {
                if (pinned == value) return
                pinned = value
                if (value) summarize(listOf(symbol))
                pinToggle.text = if (value) "Unpin" else "Pin"
                // Releasing an unfolded card also folds its branch, so hidden descendants cannot retain the cursor.
                if (!value && open) toggle()
                applyZoom()
                this@SchematicPanel.refreshCards()
            }

            private fun sourceToggleText() {
                sourceToggle.text = if (level?.showSource != false) "Compact" else "Full"
            }

            fun update() {
                fileToggle.isVisible = wholeFile != null || containingFile != null
                fileToggle.text = if (wholeFile != null) "Show symbol" else "Show whole file"
                fileToggle.toolTipText = if (wholeFile != null) "Return to ${graph.nodes[symbol].name}"
                    else "Explore the containing file here, with clickable declarations and calls"
                val shownNode = wholeFile?.let { graph.nodes[it] } ?: node
                text.body = SummaryCardText.render(
                    node = shownNode,
                    summary = summaries[target],
                    occurrences = occurrences,
                    target = target,
                    progress = when {
                        summaries.isPending(target) -> SummaryProgress.PENDING
                        summaries.isCovered(target) -> SummaryProgress.UNAVAILABLE
                        else -> SummaryProgress.NOT_REQUESTED
                    },
                    expandedFrom = wholeFile?.let { graph.nodes[symbol] },
                    choices = choices.size,
                    chosen = chosen?.let { graph.nodes[it] },
                    excerpt = schematic.excerpts[shownNode.id],
                    reference = row.link.reference,
                )
            }

            fun toggle() {
                if (!expandable && chosen == null) return
                trailTarget = this
                open = !open
                arrow.text = if (open) "▾" else "▸"
                if (open) {
                    pinTo(true)
                    val slot = level ?: LevelSlot(shown).also { level = it; add(it, BorderLayout.CENTER) }
                    slot.isVisible = true
                    applyZoom(slot)
                } else level?.let { slot ->
                    if (activeBlock?.let { SwingUtilities.isDescendingFrom(it, slot) } == true) activeBlock = row
                    if (hoveredBlock?.let { SwingUtilities.isDescendingFrom(it, slot) } == true) hoveredBlock = null
                    if (hoveredGap?.let { SwingUtilities.isDescendingFrom(it, slot) } == true) hoveredGap = null
                    slot.isVisible = false
                }
                sourceToggle.isVisible = open
                sourceToggleText()
                this@SchematicPanel.refreshCards()
            }
        }
    }

    private fun onClick(component: JComponent, action: (MouseEvent) -> Unit) {
        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        component.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && !panned) action(e)
            }
        })
    }

    /** A part of the canvas with sizes beyond its font, which it sets again for the current zoom. */
    private interface Zoomable {
        fun applyZoom()
    }

    private data class LevelMeasurement(
        val sourceWidth: Int,
        val rowSizes: List<Dimension>,
        val rowTops: List<Int>,
        val cardSizes: List<Dimension>,
        val cardTops: List<Int>,
        val size: Dimension,
    )

    private companion object {
        val LOG = logger<SchematicPanel>()
        const val MAX_SOURCE_WIDTH = 720
        const val ARROW_WIDTH = 56
        const val GAP = 8
        const val CARD_TEXT_WIDTH = 320
        const val MIN_CALLABLE_FONT_SIZE = 14
        /** Name-match choices listed before "Show all". */
        const val CHOICES_SHOWN = 8
        const val MIN_ZOOM = 0.25
        const val MAX_ZOOM = 3.0
        const val ZOOM_STEP = 1.2
        const val WHEEL_ZOOM_STEP = 1.15
        const val SCROLL_LINES = 3
        const val DRAG_THRESHOLD = 3
        /** Blank space beyond the graph and the viewport, extended as the reader pans. */
        const val CANVAS_MARGIN = 512
        const val FLOATING_GAP = 6
        const val FLOATING_ROW_SPACE = 34
        const val MIN_TRAIL_WIDTH = 160
        const val BASE_FONT = "insight.schematic.baseFont"
        const val CHAT_WIDTH = 440
        /** Blocks of the file listed in the chat context; the rest are counted. */
        const val MAX_DESCRIBED_BLOCKS = 200
        const val MAX_SELECTION = 2_000
        const val MAX_READ_LINES = 400
        /** How long the chat waits for a level to load before giving up on it. */
        const val LOAD_TIMEOUT_MS = 120_000L

        fun detab(line: String) = line.replace("\t", "    ")
        fun escape(text: String) = SummaryCardText.escape(text)
    }
}

/** A translucent plate for controls that float over the canvas; it absorbs the pointer so the canvas below does not pan or scroll. */
private open class FloatingPanel(layout: LayoutManager) : JPanel(layout) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        val absorb = object : MouseAdapter() {}
        addMouseListener(absorb)
        addMouseMotionListener(absorb)
        addMouseWheelListener(absorb)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(10)
            g2.color = Color(background.red, background.green, background.blue, 225)
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }
}
