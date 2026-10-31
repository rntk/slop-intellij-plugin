package dev.insight.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.callgraph.CallGraphService
import dev.insight.graph.CallGraphNode
import dev.insight.ui.ComponentMouseEvents
import dev.insight.ui.FloatingPanel
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.CubicCurve2D
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.text.JTextComponent
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One canvas session: the root file's source on the left and the cards opened from it in columns to the right, each
 * joined to its anchor by an arrow. Clicking a link opens a symbol card, selecting text offers a selection card. The
 * canvas pans by dragging empty space (or middle-dragging) and zooms with Ctrl/⌘ + wheel. Every model change is saved.
 * [onSwitch] shows another session of the same file in place of this panel, which the caller then disposes.
 */
internal class CanvasSessionPanel(
    override val project: Project,
    private val file: CanvasSessionFile,
    session: CanvasSession,
    override val services: CanvasSessionServices,
    private val onSwitch: (CanvasSession) -> Unit,
) : JPanel(null), CanvasCardHost, Disposable {
    override val model = CanvasSessionModel(session)
    override val controller = CanvasSessionController(project, file.source, file.exploration, file.root, model)
    override val scope: CoroutineScope = project.service<CallGraphService>().scope.let { parent ->
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    }
    private val autosave = services.store?.let { CanvasSessionAutosave(it, scope) }
    /** Off once the session is deleted, so nothing writes it again. */
    private var persist = true
    override var zoom = 1.0
        private set
    private var disposed = false
    /** True once the root file is shown and a saved session's cards are restored. */
    internal var ready = false
        private set
    private var rootView: RootView? = null
    private val views = LinkedHashMap<String, CanvasCardView>()
    internal val cardViews: Map<String, CanvasCardView> get() = views
    /** Each card's anchor, in its parent's y, as last laid out; arrows start there. */
    private val anchorYs = HashMap<String, Int>()
    private var message: JComponent? = null
    private var revealed: String? = null

    private val content = Canvas()
    internal val canvas: JComponent get() = content
    private val scroll = JBScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
    private val status = JLabel().apply { isVisible = false }
    private val zoomLevel = JLabel("100%")
    private var wheelZoom: Double? = null
    private var wheelAnchor: Point? = null
    private val zoomFrame = Timer(16) { wheelZoom?.let { zoomTo(it, wheelAnchor) } }.apply { isRepeats = false }
    private val viewSave = Timer(400) { saveView() }.apply { isRepeats = false }
    private val unreveal = Timer(1_500) { reveal(null) }.apply { isRepeats = false }
    private val selectionButton = JButton("Open card").apply {
        isVisible = false
        isFocusable = false
        toolTipText = "Open a card for the selected code"
        addActionListener { selectionPane?.let(::openSelection) }
    }
    private var selectionPane: CanvasSourcePane? = null
    private var dragStart: Point? = null
    private var dragView: Point? = null
    private var panned = false
    private var dragCursorOwner: Component? = null
    private var dragCursor: Cursor? = null
    private val mouseEvents = ComponentMouseEvents(content, ::drag)

    private val sessions = JButton("Sessions").apply {
        isFocusable = false
        toolTipText = "Browse saved canvas sessions and search by file name"
        addActionListener { ToolWindowManager.getInstance(project).getToolWindow(CANVAS_SESSIONS_WINDOW)?.activate(null) }
    }
    private val sessionActions = JButton("Session ▾").apply {
        isFocusable = false
        toolTipText = "Start a new session, switch to another saved session of this file, or delete this one"
        addActionListener { showSessions(this) }
    }
    private val controls = FloatingPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        add(sessions)
        add(sessionActions)
        add(JButton("Fold all").apply {
            isFocusable = false
            toolTipText = "Fold every card's summary, source and chat; again to unfold them"
            addActionListener { foldAll() }
        })
        add(JButton("−").apply {
            isFocusable = false
            toolTipText = "Zoom out (Ctrl/⌘ + wheel)"
            addActionListener { zoomTo(zoom / ZOOM_STEP) }
        })
        add(zoomLevel.apply { toolTipText = "Reset zoom" }.onLeftClick { zoomTo(1.0) })
        add(JButton("+").apply {
            isFocusable = false
            toolTipText = "Zoom in (Ctrl/⌘ + wheel)"
            addActionListener { zoomTo(zoom * ZOOM_STEP) }
        })
        add(status)
        add(JLabel("?").apply {
            font = font.deriveFont(Font.BOLD)
            toolTipText = canvasHelpHtml
        })
    }

    override val sourceListener = object : CanvasSourcePane.Listener {
        override fun wheel(event: MouseWheelEvent) = this@CanvasSessionPanel.wheel(event)
        override fun linkClicked(pane: CanvasSourcePane, link: CanvasLink, event: MouseEvent) {
            if (!panned) openLink(pane, link, event)
        }
        override fun selectionChanged(pane: CanvasSourcePane) = showSelectionButton(pane)
        override fun contextMenu(pane: CanvasSourcePane, event: MouseEvent, line: Int) = showContextMenu(pane, event, line)
    }

    private val subscription = model.subscribe(::changed)

    init {
        content.isFocusable = true
        content.add(selectionButton)
        content.addMouseWheelListener(::wheel)
        scroll.viewport.addChangeListener {
            val view = scroll.viewport.viewRect
            content.reserve(view.x.toLong() + view.width, view.y.toLong() + view.height)
            if (ready) viewSave.restart()
        }
        // Earlier components paint on top, so the floating bar comes before the canvas.
        add(controls)
        add(scroll)
        scroll.viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
        load()
    }

    private fun load() {
        showMessage("Reading ${file.source.name}…")
        scope.launch(Dispatchers.EDT) {
            try {
                val root = controller.loadRoot()
                if (model.cards.isNotEmpty()) controller.restore { index, count -> showStatus("Restoring card $index of $count…") }
                showMessage(null)
                rootView = RootView(root).also { content.add(it) }
                model.cards.forEach(::addView)
                ready = true
                showStatus(if (model.cards.isEmpty()) "Click an underlined name or select code to open a card." else "")
                refreshAnchors()
                content.revalidate()
                content.repaint()
                restoreView(model.session.view)
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) {
                    if (!disposed && currentActive()) showMessage("Loading cancelled.", retry = true)
                    throw e
                }
                LOG.warn("Unable to show canvas session of ${file.rootPath}", e)
                showMessage("Unable to show ${file.source.name}: ${e.message ?: e.javaClass.simpleName}. See the IDE log.", retry = true)
            }
        }
    }

    private fun currentActive() = scope.isActive

    private fun changed(change: CanvasChange) {
        if (persist) autosave?.request(model.session)
        if (!ready) return
        when (change) {
            is CanvasChange.CardOpened -> {
                addView(change.card)
                showStatus("")
            }
            is CanvasChange.CardsRemoved -> change.ids.forEach { id ->
                views.remove(id)?.let { view ->
                    content.remove(view)
                    Disposer.dispose(view)
                    if (selectionPane === view.sourcePane) hideSelectionButton()
                }
                anchorYs.remove(id)
            }
            is CanvasChange.CardChanged -> views[change.card.id]?.update(change.card)
            else -> return
        }
        if (change !is CanvasChange.CardChanged) refreshAnchors()
        content.revalidate()
        content.repaint()
    }

    private fun addView(card: CanvasCard) {
        if (card.id in views) return
        val view = CanvasCardView(this, card)
        views[card.id] = view
        content.add(view)
    }

    private fun refreshAnchors() {
        rootView?.pane?.highlightAnchors(model.children(null).map { it.anchor })
        views.values.forEach { view -> view.sourcePane?.highlightAnchors(model.children(view.id).map { it.anchor }) }
    }

    private fun openLink(pane: CanvasSourcePane, link: CanvasLink, event: MouseEvent) {
        val targets = link.targets.mapNotNull(controller::node)
        when {
            targets.isEmpty() -> showStatus("This link leads nowhere the graph knows.")
            targets.size == 1 -> openSymbol(pane.owner, link, targets.single().id)
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
                .setTitle("Open Card")
                .setRenderer(SimpleListCellRenderer.create("") { node: CallGraphNode -> canvasTargetLabel(node, controller::relative) })
                .setItemChosenCallback { node -> openSymbol(pane.owner, link, node.id) }
                .createPopup()
                .show(RelativePoint(event))
        }
    }

    /** Opens the card of [target] from [link] in [parentId]'s source (null: the root file), then shows it. */
    internal fun openSymbol(parentId: String?, link: CanvasLink, target: Int): Job = scope.launch(Dispatchers.EDT) {
        val name = controller.node(target)?.name ?: "symbol"
        showStatus("Opening $name…")
        try {
            val card = controller.openSymbol(parentId, link.range.first, link.range.last + 1, target)
            showStatus("")
            content.validate()
            reveal(card.id)
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException) throw e
            LOG.warn("Unable to open canvas card of $name", e)
            showStatus("Could not open $name: " +
                (e.message?.replace("Reopen the schematic", "Reopen the canvas session") ?: e.javaClass.simpleName))
        }
    }

    internal fun openSelection(pane: CanvasSourcePane) {
        val range = pane.selection() ?: return
        hideSelectionButton()
        val card = controller.openSelection(pane.owner, range.first, range.last + 1) ?: return
        pane.clearSelection()
        content.validate()
        reveal(card.id)
    }

    private fun showSelectionButton(pane: CanvasSourcePane) {
        val end = pane.selectionEnd()
        if (end == null) {
            if (selectionPane === pane) hideSelectionButton()
            return
        }
        selectionPane = pane
        val at = SwingUtilities.convertPoint(pane, end, content)
        val size = selectionButton.preferredSize
        selectionButton.setBounds(at.x + JBUI.scale(4), at.y + JBUI.scale(2), size.width, size.height)
        selectionButton.isVisible = true
        content.setComponentZOrder(selectionButton, 0)
        content.repaint()
    }

    private fun hideSelectionButton() {
        selectionPane = null
        selectionButton.isVisible = false
        content.repaint()
    }

    private fun showContextMenu(pane: CanvasSourcePane, event: MouseEvent, line: Int) {
        val selected = pane.selection()
        JPopupMenu().apply {
            add(JMenuItem("Open card for selection").apply {
                isEnabled = selected != null
                addActionListener { openSelection(pane) }
            })
            add(JMenuItem("Copy").apply {
                isEnabled = selected != null
                addActionListener { pane.editor.selectionModel.selectedText?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) } }
            })
            add(JMenuItem("Go to source").apply {
                toolTipText = "Open ${pane.source.path}:${line + 1} in the editor"
                addActionListener { navigate(pane.source, pane.source.path, line) }
            })
        }.show(event.component, event.x, event.y)
    }

    override fun navigate(source: CanvasSource?, path: String, line: Int) {
        val target = source?.file?.takeIf { it.isValid } ?: controller.findFile(path)
        if (target == null) {
            showStatus("Cannot find $path.")
            return
        }
        OpenFileDescriptor(project, target, maxOf(line, 0), 0).navigate(true)
    }

    override fun close(cardId: String) {
        model.remove(cardId)
    }

    override fun cardResized() {
        if (!ready) return
        content.revalidate()
        content.repaint()
    }

    /** Emphasises card [cardId] for a moment and scrolls it into view; null clears the emphasis. */
    private fun reveal(cardId: String?) {
        revealed?.let { views[it]?.highlighted = false }
        revealed = cardId
        val view = cardId?.let(views::get) ?: return
        view.highlighted = true
        val viewport = scroll.viewport.extentSize
        content.scrollRectToVisible(Rectangle(view.x - zoomScaled(16, zoom), view.y - zoomScaled(16, zoom),
            minOf(view.width + zoomScaled(32, zoom), viewport.width), minOf(view.height, viewport.height)))
        unreveal.restart()
    }

    private fun foldAll() {
        val sections = listOf(CardSection.SUMMARY, CardSection.SOURCE, CardSection.CHAT, CardSection.INPUT)
        val fold = !model.cards.all { it.folded.containsAll(sections) }
        model.cards.map { it.id }.forEach { id -> sections.forEach { model.setFolded(id, it, fold) } }
    }

    private fun showSessions(button: JComponent) {
        val store = services.store
        scope.launch(Dispatchers.EDT) {
            val entries = store?.let { withContext(Dispatchers.IO) { it.list(file.rootPath) } }.orEmpty()
            JPopupMenu().apply {
                add(JMenuItem("New session for this file").apply { addActionListener { onSwitch(CanvasSession.new(file.rootPath)) } })
                val others = entries.filter { it.id != model.session.id }
                if (others.isNotEmpty()) {
                    addSeparator()
                    others.forEach { entry -> add(JMenuItem(canvasSessionLabel(entry)).apply { addActionListener { switchTo(entry.id) } }) }
                }
                addSeparator()
                add(JMenuItem("Delete current session…").apply {
                    isEnabled = store != null
                    addActionListener { deleteCurrent() }
                })
            }.show(button, 0, button.height)
        }
    }

    private fun switchTo(id: String) {
        val store = services.store ?: return
        scope.launch(Dispatchers.EDT) {
            val next = withContext(Dispatchers.IO) { store.load(id) }
            if (next == null) showStatus("Could not load that session. See the IDE log.") else onSwitch(next)
        }
    }

    private fun deleteCurrent() {
        val store = services.store ?: return
        val session = model.session
        val cards = if (session.cards.size == 1) "1 card" else "${session.cards.size} cards"
        val answer = Messages.showYesNoDialog(project, "Delete the canvas session \"${session.title}\" with $cards? Its summaries and chats are lost.",
            "Delete Canvas Session", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        persist = false
        viewSave.stop()
        scope.launch(Dispatchers.EDT) {
            val next = withContext(Dispatchers.IO) {
                autosave?.flush()
                store.delete(session.id)
                store.list(file.rootPath).firstNotNullOfOrNull { store.load(it.id) }
            }
            onSwitch(next ?: CanvasSession.new(file.rootPath))
        }
    }

    private fun showStatus(text: String) {
        status.text = text
        status.isVisible = text.isNotBlank()
        controls.revalidate()
        controls.repaint()
        revalidate()
    }

    private fun showMessage(text: String?, retry: Boolean = false) {
        message?.let(content::remove)
        message = text?.let {
            JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(zoomFont(JLabel(it)))
                if (retry) add(zoomFont(ActionLink("Retry") { load() }))
            }.also(content::add)
        }
        content.revalidate()
        content.repaint()
    }

    private fun restoreView(view: CanvasView) {
        zoomTo(view.zoom)
        content.validate()
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            scrollTo(view.x, view.y)
        }
    }

    private fun saveView() {
        if (!ready || disposed || !persist) return
        val position = scroll.viewport.viewPosition
        model.setView(CanvasView(zoom, position.x, position.y))
    }

    private fun wheel(e: MouseWheelEvent) {
        if (e.isControlDown || e.isMetaDown) {
            wheelZoom = ((wheelZoom ?: zoom) * WHEEL_ZOOM_STEP.pow(-e.preciseWheelRotation)).coerceIn(MIN_ZOOM, MAX_ZOOM)
            wheelAnchor = SwingUtilities.convertPoint(e.component, e.point, scroll.viewport)
            if (!zoomFrame.isRunning) zoomFrame.start()
            e.consume()
        } else scroll.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, scroll))
    }

    /** Zooms to [requested], keeping the content under [anchor] (viewport coordinates; the middle by default) in place. */
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
        rootView?.pane?.applyZoom(zoom)
        content.revalidate()
        scroll.validate()
        scrollTo(((previous.x + point.x) * ratio - point.x).roundToInt(), ((previous.y + point.y) * ratio - point.y).roundToInt())
        content.repaint()
    }

    private fun applyZoom(component: Component) {
        if (component is CanvasSourcePane) return
        if (component is JComponent) (component.getClientProperty(BASE_FONT) as? Font)?.let { component.font = it.zoomed(zoom) }
        (component as? Container)?.components?.forEach(::applyZoom)
        if (component is CanvasCardView) component.applyZoom()
    }

    override fun <T : JComponent> zoomFont(component: T, base: Font): T = component.apply {
        putClientProperty(BASE_FONT, base)
        font = base.zoomed(zoom)
    }

    private fun scrollTo(x: Number, y: Number) {
        val viewport = scroll.viewport
        val left = x.toLong().coerceIn(0L, (Int.MAX_VALUE - viewport.width).toLong())
        val top = y.toLong().coerceIn(0L, (Int.MAX_VALUE - viewport.height).toLong())
        content.reserve(left + viewport.width, top + viewport.height)
        viewport.viewSize = content.preferredSize
        viewport.viewPosition = Point(left.toInt(), top.toInt())
    }

    private fun drag(e: MouseEvent) {
        if (!SwingUtilities.isDescendingFrom(e.component, content)) return
        when (e.id) {
            MouseEvent.MOUSE_PRESSED -> if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                panned = false
                // Source text, chat text and buttons keep their own selection and clicks.
                if (SwingUtilities.isLeftMouseButton(e) && generateSequence(e.component) { it.parent }
                        .any { it is CanvasSourcePane || it is JTextComponent || it is AbstractButton || it is ActionLink }) return
                dragStart = e.locationOnScreen
                dragView = scroll.viewport.viewPosition
                content.requestFocusInWindow()
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
            MouseEvent.MOUSE_RELEASED -> endDrag()
        }
    }

    private fun endDrag() {
        dragStart = null
        dragView = null
        dragCursorOwner?.cursor = dragCursor
        dragCursorOwner = null
        dragCursor = null
    }

    override fun doLayout() {
        scroll.setBounds(0, 0, width, height)
        scroll.validate()
        val view = SwingUtilities.convertRectangle(scroll, scroll.viewport.bounds, this)
        val gap = JBUI.scale(FLOATING_GAP)
        val size = controls.preferredSize
        val controlsWidth = minOf(size.width, maxOf(view.width - 2 * gap, 0))
        controls.setBounds(view.x + view.width - gap - controlsWidth, view.y + gap, controlsWidth, size.height)
    }

    override fun dispose() {
        if (disposed) return
        if (ready && persist) {
            viewSave.stop()
            saveView()
        }
        disposed = true
        zoomFrame.stop()
        viewSave.stop()
        unreveal.stop()
        mouseEvents.dispose()
        endDrag()
        subscription.dispose()
        views.values.forEach(Disposer::dispose)
        views.clear()
        rootView?.pane?.let(Disposer::dispose)
        rootView = null
        if (persist) autosave?.flush()
        scope.cancel()
        Disposer.dispose(controller)
    }

    /** The root file: a small header with its path above its source. */
    private inner class RootView(source: CanvasSource) : JPanel(StackLayout { zoomScaled(4, zoom) }) {
        val pane = CanvasSourcePane(project, null, source, sourceListener)

        init {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(zoomFont(JLabel(source.path, file.source.fileType.icon, JLabel.LEFT), UIUtil.getLabelFont().deriveFont(Font.BOLD)))
                add(zoomFont(ActionLink("Source") { navigate(source, source.path, source.firstLine) }.apply {
                    toolTipText = "Open ${source.path} in the editor"
                }))
            })
            add(pane)
        }

        fun anchorY(anchor: CardAnchor): Int = SwingUtilities.convertPoint(pane, 0, pane.anchorY(anchor.startOffset), this).y

        override fun getPreferredSize(): Dimension {
            val stacked = super.getPreferredSize()
            return Dimension(maxOf(pane.preferredSize.width, components.first().preferredSize.width), stacked.height)
        }
    }

    /** The scrollable canvas: lays out the root file and the cards, and draws the arrows between them. */
    private inner class Canvas : JPanel(null), Scrollable {
        private val space = Dimension()
        private var extent = Dimension()

        init {
            background = UIUtil.getPanelBackground()
            isOpaque = true
        }

        override fun getPreferredSize(): Dimension {
            reserve(extent.width.toLong(), extent.height.toLong())
            return Dimension(space)
        }

        /** Keeps [right] by [bottom] scrollable, so the reader never loses space they panned to. */
        fun reserve(right: Long, bottom: Long) {
            val margin = zoomScaled(CANVAS_MARGIN, zoom)
            val width = (right + margin).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val height = (bottom + margin).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (width > space.width || height > space.height) {
                space.setSize(maxOf(space.width, width), maxOf(space.height, height))
                revalidate()
            }
        }

        override fun doLayout() {
            val margin = zoomScaled(24, zoom)
            val top = JBUI.scale(FLOATING_ROW_SPACE) + zoomScaled(12, zoom)
            var right = 0
            var bottom = 0
            message?.let {
                val size = it.preferredSize
                it.setBounds(margin, top, size.width, size.height)
                right = margin + size.width
                bottom = top + size.height
            }
            val root = rootView
            if (root != null) {
                root.pane.maxWidth = zoomScaled(ROOT_MAX_WIDTH, zoom)
                // Measured twice: the second time at its own width, after a zoom changed it.
                root.setSize(root.preferredSize.width.coerceAtLeast(1), maxOf(root.height, 1))
                val size = root.preferredSize
                root.setBounds(margin, top, size.width, size.height)
                root.validate()
                right = maxOf(right, root.x + root.width)
                bottom = maxOf(bottom, root.y + root.height)
                val width = zoomScaled(CARD_WIDTH, zoom)
                val items = model.cards.mapNotNull { card ->
                    val view = views[card.id] ?: return@mapNotNull null
                    view.sourcePane?.maxWidth = width - 2 * zoomScaled(9, zoom)
                    view.setSize(width, maxOf(view.height, 1))
                    val height = view.preferredSize.height
                    view.setSize(width, height)
                    view.validate()
                    val anchor = card.parentId?.let { parent -> views[parent]?.anchorY(card.anchor) } ?: root.anchorY(card.anchor)
                    anchorYs[card.id] = anchor
                    CanvasLayoutItem(card.id, card.parentId?.takeIf { it in views }, anchor, width, height)
                }
                val placed = canvasLayout(items, root.width, CanvasLayoutOptions(left = margin, top = top,
                    arrowGap = zoomScaled(64, zoom), gap = zoomScaled(14, zoom), anchorLift = zoomScaled(12, zoom)))
                for (item in items) {
                    val at = placed[item.id] ?: continue
                    views[item.id]?.setBounds(at.x, at.y, item.width, item.height)
                    right = maxOf(right, at.x + item.width)
                    bottom = maxOf(bottom, at.y + item.height)
                }
            }
            extent = Dimension(right, bottom)
            reserve(right.toLong(), bottom.toLong())
            selectionPane?.let { pane -> if (pane.isShowing) showSelectionButton(pane) else hideSelectionButton() }
        }

        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            val root = rootView ?: return
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.stroke = BasicStroke(zoomScaled(2, zoom).toFloat() * 0.75f)
                for (card in model.cards) {
                    val view = views[card.id] ?: continue
                    val parent: JComponent = card.parentId?.let(views::get) ?: root
                    val anchor = anchorYs[card.id] ?: continue
                    arrow(g2, Point(parent.x + parent.width, parent.y + anchor), Point(view.x, view.y + view.headerMiddle()),
                        if (card.id == revealed) ARROW_ACTIVE else ARROW)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun arrow(g: Graphics2D, from: Point, to: Point, color: Color) {
            g.color = color
            val dot = zoomScaled(3, zoom)
            g.fillOval(from.x - dot, from.y - dot, 2 * dot, 2 * dot)
            val head = zoomScaled(7, zoom)
            val bend = maxOf((to.x - from.x) / 2, head)
            g.draw(CubicCurve2D.Double(from.x.toDouble(), from.y.toDouble(), (from.x + bend).toDouble(), from.y.toDouble(),
                (to.x - bend).toDouble(), to.y.toDouble(), (to.x - head).toDouble(), to.y.toDouble()))
            g.fillPolygon(Polygon(intArrayOf(to.x, to.x - head, to.x - head), intArrayOf(to.y, to.y - head / 2 - 1, to.y + head / 2 + 1), 3))
        }

        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = zoomScaled(24, zoom)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth() = false
        override fun getScrollableTracksViewportHeight() = false
    }

    private companion object {
        val LOG = logger<CanvasSessionPanel>()
        val ARROW = JBColor(Color(130, 150, 200), Color(110, 130, 175))
        val ARROW_ACTIVE = JBColor(Color(76, 112, 220), Color(110, 150, 240))
        const val CARD_WIDTH = 560
        const val ROOT_MAX_WIDTH = 960
        const val MIN_ZOOM = 0.25
        const val MAX_ZOOM = 3.0
        const val ZOOM_STEP = 1.2
        const val WHEEL_ZOOM_STEP = 1.15
        const val DRAG_THRESHOLD = 3
        const val CANVAS_MARGIN = 512
        const val FLOATING_GAP = 6
        const val FLOATING_ROW_SPACE = 34
        const val BASE_FONT = "insight.canvas.baseFont"
    }
}
