package dev.insight.callgraph

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.diff.pixelBorder
import dev.insight.diff.zoomScaled
import dev.insight.diff.zoomed
import dev.insight.enrichment.liftCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.AWTEvent
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
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.text.DefaultCaret
import kotlin.math.pow
import kotlin.math.roundToInt

class SchematicFile(val result: CallGraphResult, val root: Int) : LightVirtualFile("Schematic: ${result.graph.nodes[root].name}")

fun openSchematic(project: Project, result: CallGraphResult, root: Int) {
    FileEditorManager.getInstance(project).openFile(SchematicFile(result, root), true)
}

/** Opens the schematic of the selected file. */
class OpenSchematicAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && file != null && !file.isDirectory &&
            ProjectFileIndex.getInstance(project).isInContent(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val service = project.service<CallGraphService>()
        fun showNotice(text: String) {
            if (project.isDisposed) return
            if (editor != null && !editor.isDisposed) HintManager.getInstance().showInformationHint(editor, text)
            else StatusBar.Info.set(text, project)
        }
        service.scope.launch(Dispatchers.EDT) {
            try {
                val built = service.currentProject()
                val node = built.graph.nodes.firstOrNull { it.kind == SymbolKind.FILE && it.filePath == file.path }
                if (node == null) showNotice("This file is not in the application graph.")
                else openSchematic(project, built, node.id)
            } catch (e: CancellationException) {
                showNotice("Schematic cancelled. Choose Schematic to retry.")
                throw e
            }
        }
    }
}

class SchematicEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is SchematicFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = SchematicEditor(project, file as SchematicFile)
    override fun getEditorTypeId() = "insight-schematic"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class SchematicEditor(project: Project, private val file: SchematicFile) : UserDataHolderBase(), FileEditor {
    private val panel = SchematicPanel(project, file)
    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.canvas
    override fun getName() = "Schematic"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = panel.dispose()
}

/**
 * A lossy reading of a file through the call graph. Its source is shown line by line, but the functions it declares
 * fold into blocks, and lines that call project code become blocks too. Focusing or hovering a block previews the
 * summary of the symbol it leads to; Enter or a click pins it. A summary unfolds into the same reading
 * of that symbol, so the reader can walk down the graph, keeping several branches open at once.
 *
 * The reading is a canvas: Ctrl/⌘ + wheel or + and − zoom it, dragging moves it, and the arrow, Page Up/Down, Home and
 * End keys scroll it.
 */
internal class SchematicPanel(private val project: Project, private val file: SchematicFile) : JPanel(BorderLayout()) {
    private val result = file.result
    private val graph = result.graph
    private val lifted by lazy { liftCalls(graph) }
    private val calls by lazy { lifted.edges.filter { it.kind == RelationKind.CALL }.groupBy { it.caller } }
    private val hierarchy by lazy { graph.hierarchy() }
    private val summaries by lazy { SchematicSummaries(project, result, lifted) }
    private val scope = project.service<CallGraphService>().scope.let { parent ->
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }
    private val status = JLabel(" ")
    /** Summary runs in progress; changed on the EDT only. */
    private var running = 0
    private var failures = 0
    private var error: String? = null
    private var disposed = false
    /** Scale of everything on the canvas; 1.0 is the IDE's own size. */
    internal var zoom = 1.0
        private set
    private val zoomLevel = JLabel("100%")
    private val content = object : JPanel(BorderLayout()), Scrollable, Zoomable {
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) = scaled(24)
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth() = false
        override fun getScrollableTracksViewportHeight() = false
        override fun applyZoom() { border = pixelBorder(scaled(8), 0, scaled(8), 0) }
    }
    /** The canvas; it takes the keyboard focus for scrolling and zooming. */
    internal val canvas: JComponent get() = content
    private val scroll = JBScrollPane(content)
    private val code: Font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
    private val codeFont get() = code.zoomed(zoom)
    private val blockBackground = JBColor(Color(235, 240, 255), Color(47, 57, 77))
    private val blockFocusedBackground = JBColor(Color(215, 228, 255), Color(62, 80, 112))
    private val blockOpen = JBColor(Color(76, 112, 220), Color(110, 150, 240))
    /** Where the current left-button gesture started, on screen, and the view position then. */
    private var dragStart: Point? = null
    private var dragView: Point? = null
    /** Whether the last gesture moved the canvas; it then is not also a click. */
    private var panned = false
    private var dragCursorOwner: Component? = null
    private var dragCursor: Cursor? = null
    private var activeBlock: LevelView.BlockView? = null
    private var hoveredBlock: LevelView.BlockView? = null
    // Allow the pointer to cross the arrow between a block and its card without losing the preview.
    private val hoverExit = Timer(200) { hover(null) }.apply { isRepeats = false }
    /** Sees mouse events before the components under the pointer, so dragging moves the canvas over code and blocks too. */
    private val dragListener = AWTEventListener { event -> (event as? MouseEvent)?.let {
        trackHover(it)
        drag(it)
    } }

    init {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(JLabel("Schematic of ${graph.nodes[file.root].name}").apply { font = font.deriveFont(Font.BOLD) })
                add(JButton("Fold all").apply { isFocusable = false; addActionListener { foldAll() } })
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
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
                })
                add(status)
            }, BorderLayout.NORTH)
            add(JLabel("<html>Hover a block or use Tab / Shift+Tab to preview its summaries. Enter or a left click on a block pins its cards; " +
                "Unpin releases a card. Click a summary to unfold or fold the code it leads to. " +
                "Double-click code, or choose Source on a summary, to open it in the editor. " +
                "Choose Compact on an unfolded summary to keep only clickable blocks; Full restores the plain code. " +
                "Drag to move; Ctrl/⌘ + wheel or + and − to zoom; arrows, Page Up/Down, Home and End to scroll.</html>").apply {
                foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        content.isFocusable = true
        // Children inherit these empty traversal keys, so Tab reaches our canvas navigation bindings.
        content.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
        content.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())
        content.applyZoom()
        content.add(LevelSlot(file.root), BorderLayout.NORTH)
        installNavigation()
        content.addMouseWheelListener { e ->
            if (e.isControlDown || e.isMetaDown) {
                zoomTo(zoom * WHEEL_ZOOM_STEP.pow(-e.preciseWheelRotation), SwingUtilities.convertPoint(e.component, e.point, scroll.viewport))
                e.consume()
            } else scroll.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, scroll))
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(dragListener, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK)
        add(scroll, BorderLayout.CENTER)
        add(JLabel("Source and calls are a snapshot of the application graph; reopen the schematic after edits.").apply {
            foreground = UIUtil.getContextHelpForeground()
        }, BorderLayout.SOUTH)
    }

    fun dispose() {
        disposed = true
        hoverExit.stop()
        Toolkit.getDefaultToolkit().removeAWTEventListener(dragListener)
        scope.cancel()
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
        bind("ENTER") { (hoveredBlock ?: activeBlock)?.pin() }
        bind("ESCAPE") {
            hoverExit.stop()
            activeBlock = null
            hoveredBlock = null
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
            bind("${modifier}END") { scrollTo(0, Int.MAX_VALUE) }
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
        activeBlock = blocks[next]
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

    private fun hover(block: LevelView.BlockView?) {
        if (hoveredBlock === block) return
        hoveredBlock = block
        refreshCards()
    }

    private fun trackHover(e: MouseEvent) {
        if (e.id !in listOf(MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_EXITED, MouseEvent.MOUSE_MOVED)) return
        if (!SwingUtilities.isDescendingFrom(e.component, content)) return
        val owner = if (e.id == MouseEvent.MOUSE_EXITED) null else
            generateSequence(e.component) { it.parent }.firstOrNull { it is LevelView.BlockView || it is LevelView.SummaryCard }
        val block = when (owner) {
            is LevelView.BlockView -> owner
            is LevelView.SummaryCard -> owner.row
            else -> null
        }
        if (block != null) {
            hoverExit.stop()
            hover(block)
        } else if (!hoverExit.isRunning) hoverExit.start()
    }

    private fun refreshCards() {
        fun visit(component: Component) {
            if (component is LevelView) component.refreshCards()
            if (component is Container) component.components.forEach(::visit)
        }
        visit(content)
        content.revalidate()
        content.repaint()
    }

    private fun scrollBy(dx: Int, dy: Int) {
        val position = scroll.viewport.viewPosition
        scrollTo(position.x.toLong() + dx, position.y.toLong() + dy)
    }

    private fun scrollTo(x: Number, y: Number) {
        val viewport = scroll.viewport
        val view = viewport.view ?: return
        viewport.viewPosition = Point(x.toLong().coerceIn(0L, maxOf(0, view.width - viewport.width).toLong()).toInt(),
            y.toLong().coerceIn(0L, maxOf(0, view.height - viewport.height).toLong()).toInt())
    }

    /** Zooms to [requested], keeping the content under [anchor] (in viewport coordinates; the middle by default) in place. */
    internal fun zoomTo(requested: Double, anchor: Point? = null) {
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

    /** Rescales [component] and everything in it to the current zoom. */
    private fun applyZoom(component: Component) {
        if (component is JComponent) (component.getClientProperty(BASE_FONT) as? Font)?.let { component.font = it.zoomed(zoom) }
        if (component is Zoomable) component.applyZoom()
        (component as? Container)?.components?.forEach(::applyZoom)
    }

    /** Shows [base] (this component's own font by default) at the current zoom, now and after every zoom change. */
    private fun <T : JComponent> T.zoomFont(base: Font = font): T = apply {
        putClientProperty(BASE_FONT, base)
        font = base.zoomed(zoom)
    }

    private fun scaled(value: Int) = zoomScaled(value, zoom)

    private fun line(color: Color, thickness: Int) = BorderFactory.createLineBorder(color, scaled(thickness))

    private fun drag(e: MouseEvent) {
        if (!SwingUtilities.isDescendingFrom(e.component, content)) return
        when (e.id) {
            MouseEvent.MOUSE_PRESSED -> if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                dragStart = e.locationOnScreen
                dragView = scroll.viewport.viewPosition
                panned = false
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
                dragStart = null
                dragView = null
                dragCursorOwner?.cursor = dragCursor
                dragCursorOwner = null
                dragCursor = null
            }
        }
    }

    /** Summarizes what [roots] reach unless every function in them was already included in a run. */
    private fun summarize(roots: List<Int>) {
        if (disposed || !summaries.needs(roots)) return
        running++
        updateStatus()
        scope.launch(Dispatchers.EDT) {
            try {
                failures += withBackgroundProgress(project, "Summarizing schematic: ${graph.nodes[file.root].name}") {
                    withContext(Dispatchers.Default) {
                        reportRawProgress { reporter ->
                            reporter.text("Reading source facts…")
                            summaries.summarize(roots) { phase, done, total ->
                                reporter.text(phase)
                                reporter.details("$done / $total functions")
                                reporter.fraction(if (total > 0) done.toDouble() / total else null)
                                ApplicationManager.getApplication().invokeLater {
                                    if (!disposed && running > 0) status.text = "$phase: $done / $total"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is ControlFlowException) throw e
                error = e.message ?: e.javaClass.simpleName
            } finally {
                running--
                if (!disposed) updateStatus()
            }
        }
    }

    private fun updateStatus() {
        status.text = when {
            running > 0 -> "Summarizing…"
            error != null -> "Summaries failed: $error. Check Settings → Tools → Insight Chat."
            failures > 0 -> "$failures functions could not be summarized."
            else -> " "
        }
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

    /** Loads the schematic of [id] in the background, then shows it and summarizes what it leads to. */
    private inner class LevelSlot(private val id: Int) : JPanel(BorderLayout()) {
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
            scope.launch(Dispatchers.EDT) {
                val schematic = try {
                    withBackgroundProgress(project, "Building schematic: ${graph.nodes[id].name}") {
                        smartReadAction(project) { SchematicBuilder.build(result, hierarchy, calls, id) }
                    }
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    removeAll()
                    add(ActionLink("Schematic loading cancelled. Retry") { load() }.zoomFont())
                    revalidate()
                    repaint()
                    return@launch
                }
                removeAll()
                if (schematic == null) add(JLabel("Source of ${graph.nodes[id].name} is no longer available. Reopen the schematic.").zoomFont())
                else add(LevelView(schematic).apply { showSource(this@LevelSlot.showSource) })
                revalidate()
                repaint()
                if (schematic != null) summarize(listOf(id) + schematic.targets)
            }
        }
    }

    /** The rows of one schematic on the left and a summary card per block target on the right, joined by arrows. */
    private inner class LevelView(private val schematic: Schematic) : JPanel(null) {
        private val rows = mutableListOf<JComponent>()
        private val cards = mutableListOf<SummaryCard>()
        private val indent = schematic.lines.filter { it.isNotBlank() }.minOfOrNull { line -> detab(line).takeWhile { it == ' ' }.length } ?: 0
        private val charWidth get() = getFontMetrics(codeFont).charWidth(' ')
        val blocks get() = rows.filterIsInstance<BlockView>()
        fun cardsFor(block: BlockView) = cards.filter { it.row === block }

        init {
            isOpaque = false
            for (row in schematic.rows) {
                val view = when (row) {
                    is CodeRow -> codeView(row)
                    is BlockRow -> BlockView(row)
                }
                rows += view
                add(view)
                if (row is BlockRow) row.targets.forEach { target ->
                    cards += SummaryCard(target, view as BlockView, row.occurrences.filter { target in it.displayTargets }).also(::add)
                }
            }
            refreshCards()
        }

        fun refreshCards() {
            val preview = hoveredBlock ?: activeBlock
            cards.forEach { it.isVisible = it.pinned || it.row === preview }
            blocks.forEach { it.repaintBorder() }
            revalidate()
            repaint()
        }

        private fun text(line: Int) = detab(schematic.text(line)).drop(indent).trimEnd()

        /** Keep the same blocks and unfolded branches while omitting plain source rows. */
        fun showSource(show: Boolean) {
            rows.filterIsInstance<CodeView>().forEach { it.isVisible = show }
            revalidate()
            repaint()
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

        /** Positions of rows (from the top) and cards (from the top, pushed down so they never overlap). */
        private fun measure(): Triple<Int, List<Int>, List<Int>> {
            val sourceWidth = rows.filter { it.isVisible }.maxOfOrNull { it.preferredSize.width }?.coerceAtMost(scaled(MAX_SOURCE_WIDTH)) ?: 0
            var y = 0
            val rowTops = rows.map { row -> y.also { if (row.isVisible) y += row.preferredSize.height } }
            var bottom: Int? = null
            val cardTops = cards.map { card ->
                if (!card.isVisible) 0 else
                    maxOf(rowTops[rows.indexOf(card.row)], bottom?.plus(scaled(GAP)) ?: 0).also { bottom = it + card.preferredSize.height }
            }
            return Triple(sourceWidth, rowTops, cardTops)
        }

        override fun doLayout() {
            val (sourceWidth, rowTops, cardTops) = measure()
            rows.forEachIndexed { index, row -> row.setBounds(0, rowTops[index], sourceWidth, row.preferredSize.height) }
            val x = sourceWidth + scaled(ARROW_WIDTH)
            cards.forEachIndexed { index, card ->
                if (card.isVisible) card.setBounds(x, cardTops[index], card.preferredSize.width, card.preferredSize.height)
                else card.setBounds(0, 0, 0, 0)
            }
        }

        override fun getPreferredSize(): Dimension {
            val (sourceWidth, rowTops, cardTops) = measure()
            val sourceHeight = rows.indices.maxOfOrNull { rowTops[it] + if (rows[it].isVisible) rows[it].preferredSize.height else 0 } ?: 0
            val shown = cards.indices.filter { cards[it].isVisible }
            val cardsHeight = shown.maxOfOrNull { cardTops[it] + cards[it].preferredSize.height } ?: 0
            val cardsWidth = shown.maxOfOrNull { cards[it].preferredSize.width } ?: 0
            return Dimension(sourceWidth + if (shown.isEmpty()) 0 else scaled(ARROW_WIDTH) + cardsWidth, maxOf(sourceHeight, cardsHeight))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.stroke = BasicStroke(scaled(1).toFloat())
                val head = scaled(5)
                for (card in cards.filter { it.isVisible }) {
                    val row = card.row
                    g2.color = if (card.pinned || card.open) blockOpen else JBColor.border()
                    val startX = row.x + row.box.x + row.box.width
                    val startY = row.y + row.box.y + row.box.height / 2
                    val endX = card.x - 1
                    val endY = card.y + minOf(card.headerCenter, card.height / 2)
                    val middle = (startX + endX) / 2
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

        /** A block at the code's indentation; clicking pins all of its target cards. */
        inner class BlockView(val row: BlockRow) : JPanel(BorderLayout()), Zoomable {
            val box = JPanel(BorderLayout())

            init {
                isOpaque = false
                box.background = blockBackground
                box.add(JLabel(text(row.headline).trimStart()).zoomFont(code), BorderLayout.CENTER)
                if (row.last > row.first) {
                    box.add(JLabel("${row.last - row.first + 1} lines").apply { foreground = UIUtil.getContextHelpForeground() }.zoomFont(),
                        BorderLayout.EAST)
                }
                add(box, BorderLayout.WEST)
                applyZoom()
                onClick(box) { pin() }
                box.toolTipText = "<html>" + row.targets.joinToString("<br>") { escape(graph.nodes[it].name) } + "<br><i>Click or Enter to pin summaries</i></html>"
            }

            fun pin() {
                activeBlock = this
                cardsFor(this).forEach { it.pinTo(true) }
                this@SchematicPanel.refreshCards()
            }

            override fun applyZoom() {
                val depth = detab(schematic.text(row.headline)).takeWhile { it == ' ' }.length - indent
                border = pixelBorder(0, scaled(4) + charWidth * depth.coerceAtLeast(0), 0, 0)
                (box.layout as BorderLayout).hgap = scaled(8)
                repaintBorder()
            }

            fun repaintBorder() {
                val focused = this === (hoveredBlock ?: activeBlock)
                val marked = focused || cardsFor(this).any { it.pinned || it.open }
                // A constant inset keeps focusing a block from moving the source under the pointer.
                box.border = BorderFactory.createCompoundBorder(line(if (marked) blockOpen else JBColor.border(), 2),
                    pixelBorder(scaled(1), scaled(6), scaled(1), scaled(6)))
                box.background = if (focused) blockFocusedBackground else blockBackground
            }

            override fun getMaximumSize(): Dimension = preferredSize
        }

        /** The summary of [target]; unfolds into [target]'s own schematic. */
        inner class SummaryCard(private val target: Int, val row: BlockView,
            private val occurrences: List<CallOccurrence>) : JPanel(BorderLayout()), Zoomable {
            var pinned = false
                private set
            var open = false
                private set
            private val node = graph.nodes[target]
            private val expandable = node.inProject && !node.unresolved
            /** For an unknown callee, declarations sharing its name; the user's [chosen] one unfolds in place of [target]. */
            private val choices = if (node.unresolved) occurrences.flatMap { it.choices }.distinct() else emptyList()
            private var chosen: Int? = null
            private val choiceLinks = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
            private var allChoices = false
            private val arrow = JLabel(if (expandable) "▸" else "·").zoomFont()
            private val text = JLabel().zoomFont()
            private val header = JPanel(BorderLayout())
            private val details = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                isVisible = false
            }
            private var level: LevelSlot? = null
            private val pinToggle = ActionLink("Pin") { pinTo(!pinned) }.zoomFont()
            private val sourceToggle = ActionLink("Compact") {
                level?.let { slot ->
                    slot.showSource = !slot.showSource
                    sourceToggleText()
                    this@LevelView.revalidate()
                    this@LevelView.repaint()
                }
            }.apply { isVisible = false; verticalAlignment = SwingConstants.TOP }.zoomFont()
            val headerCenter get() = header.y + header.height / 2

            init {
                isOpaque = true
                background = UIUtil.getPanelBackground()
                header.isOpaque = false
                header.add(arrow, BorderLayout.WEST)
                header.add(text, BorderLayout.CENTER)
                header.add(JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    add(pinToggle)
                    add(ActionLink(if (node.unresolved) "Call site" else "Source") { navigate(target) }
                        .apply { verticalAlignment = SwingConstants.TOP }.zoomFont())
                    if (occurrences.isNotEmpty()) add(ActionLink("Explain link") {
                        details.isVisible = !details.isVisible
                        this@LevelView.revalidate()
                        this@LevelView.repaint()
                    }.zoomFont())
                    add(sourceToggle)
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
                    details.add(JLabel("<html><div style='width:${scaled(CARD_TEXT_WIDTH)}px'><code>${escape(occurrence.expression)}</code><br>" +
                        "${escape(occurrence.explanation)}</div></html>").zoomFont())
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
                pinTo(true)
                if (!graph.nodes[id].inProject) { navigate(id); return }
                if (open) toggle()
                level?.let(::remove)
                level = null
                chosen = id
                showChoices()
                toggle()
                update()
            }

            override fun applyZoom() {
                border = BorderFactory.createCompoundBorder(line(if (pinned) blockOpen else JBColor.border(), 1), pixelBorder(scaled(6), scaled(8), scaled(6), scaled(8)))
                (layout as BorderLayout).vgap = scaled(6)
                (header.layout as BorderLayout).hgap = scaled(6)
                update()
            }

            fun pinTo(value: Boolean) {
                if (pinned == value) return
                pinned = value
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
                val summary = summaries[target]
                val name = escape(node.name.take(80))
                val kind = node.kind.name.lowercase()
                val body = when {
                    node.unresolved && choices.isNotEmpty() -> "<font color='gray'>Implementation unknown: ${choices.size} declarations share this " +
                        "name. Pick one to continue; the IDE did not establish any of them.</font>" + chosen?.let {
                            "<br>Following your pick: <code>${escape(graph.nodes[it].name.take(80))}</code> · ${escape(graph.nodes[it].location)}"
                        }.orEmpty()
                    node.unresolved -> "<font color='gray'>Implementation unknown. Inspect the call site or receiver.</font>"
                    !node.inProject -> "<font color='gray'>External declaration. Open Source to inspect it.</font>" +
                        node.signature?.let { "<br><code>${escape(it)}</code>" }.orEmpty()
                    summary != null -> "<b>${escape(summary.label)}</b>" + (summary.role?.let { " <font color='gray'>· ${it.id}</font>" } ?: "") +
                        (summary.description?.let { "<br>${escape(it)}" } ?: "")
                    node.kind != SymbolKind.FUNCTION -> "<font color='gray'>Unfold to inspect its declaration.</font>"
                    running > 0 && !summaries.isCovered(target) -> "<font color='gray'>Summarizing…</font>"
                    summaries.isCovered(target) -> "<font color='gray'>No summary: short, test code, or the request failed.</font>"
                    else -> "<font color='gray'>Not summarized.</font>"
                }
                val evidence = when {
                    occurrences.any { it.byName } -> "<br><font color='gray'>Matched by name: the receiver's type is unknown.</font>"
                    occurrences.any { it.targets.isEmpty() && target in it.heldBy } ->
                        "<br><font color='gray'>Callable value: the function it holds is assigned elsewhere.</font>"
                    occurrences.any { it.status == ResolutionStatus.AMBIGUOUS } -> "<br><font color='gray'>Possible target; several candidates.</font>"
                    occurrences.any { it.operation == CallOperation.CALLABLE_REFERENCE } -> "<br><font color='gray'>Callable reference; invocation is not established here.</font>"
                    else -> ""
                }
                text.text = "<html><div style='width:${scaled(CARD_TEXT_WIDTH)}px'><code>$name</code> <font color='gray'>$kind</font><br>$body$evidence</div></html>"
            }

            fun toggle() {
                val shown = if (expandable) target else chosen ?: return
                open = !open
                arrow.text = if (open) "▾" else "▸"
                if (open) {
                    pinTo(true)
                    val slot = level ?: LevelSlot(shown).also { level = it; add(it, BorderLayout.CENTER) }
                    slot.isVisible = true
                } else level?.let { slot ->
                    if (activeBlock?.let { SwingUtilities.isDescendingFrom(it, slot) } == true) activeBlock = row
                    if (hoveredBlock?.let { SwingUtilities.isDescendingFrom(it, slot) } == true) hoveredBlock = null
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

    private companion object {
        const val MAX_SOURCE_WIDTH = 720
        const val ARROW_WIDTH = 56
        const val GAP = 8
        const val CARD_TEXT_WIDTH = 320
        /** Name-match choices listed before "Show all". */
        const val CHOICES_SHOWN = 8
        const val MIN_ZOOM = 0.25
        const val MAX_ZOOM = 3.0
        const val ZOOM_STEP = 1.2
        const val WHEEL_ZOOM_STEP = 1.15
        const val SCROLL_LINES = 3
        const val DRAG_THRESHOLD = 3
        const val BASE_FONT = "insight.schematic.baseFont"

        fun detab(line: String) = line.replace("\t", "    ")
        fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
