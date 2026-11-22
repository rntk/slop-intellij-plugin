package dev.insight.canvas

import dev.insight.context.showFileContext

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.agent.ProjectFiles
import dev.insight.agent.ToolInputException
import dev.insight.callgraph.CallGraphService
import dev.insight.callgraph.SchematicGapWorkflow
import dev.insight.core.withCountProgress
import dev.insight.graph.CallGraphNode
import dev.insight.links.SymbolCandidate
import dev.insight.links.SymbolMention
import dev.insight.review.ui.LinkedTextNavigator
import dev.insight.ui.ComponentMouseEvents
import dev.insight.ui.FloatingPanel
import dev.insight.ui.CanvasZoomAnchor
import dev.insight.ui.layoutZoomContent
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import dev.insight.ui.openSourceChain
import dev.insight.ui.showCardSearch
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.AWTEvent
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
import java.awt.Polygon
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.CubicCurve2D
import javax.swing.AbstractAction
import javax.swing.AbstractButton
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.KeyStroke
import javax.swing.Timer
import javax.swing.text.JTextComponent
import kotlin.math.abs
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
 * canvas pans without edges by dragging empty space, middle-dragging, or Alt + dragging anywhere, and zooms with Ctrl/⌘ +
 * wheel. Every model change is saved.
 * [onSwitch] shows another session of the same file in place of this panel, which the caller then disposes.
 */
internal class CanvasSessionPanel(
    override val project: Project,
    private val file: CanvasSessionFile,
    session: CanvasSession,
    override val services: CanvasSessionServices,
    private val loadChunk: Int = CANVAS_LOAD_CHUNK,
    private val activeEditorLimit: Int = CANVAS_ACTIVE_EDITOR_LIMIT,
    private val activeEditorTarget: Int = CANVAS_ACTIVE_EDITOR_TARGET,
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
    private var pendingInitialLine: Int? = file.initialLine.also { file.initialLine = null }
        ?: model.session.root.symbol?.line?.takeIf { it > 0 }
    private val directory = file.source.isDirectory
    private val rootKind = if (directory) "directory" else "file"
    /** Every card's view, the root card's first, once the root file is shown. */
    private val views = LinkedHashMap<String, CanvasCardView>()
    internal val cardViews: Map<String, CanvasCardView> get() = views
    /** Currently loaded card ids (root-closed subset when a large graph is chunked). */
    private val loadedIds = LinkedHashSet<String>()
    /** Inline continuation stubs by synthetic `more:<parentId>` id. */
    private val moreViews = LinkedHashMap<String, CanvasMoreCard>()
    internal val moreCards: Map<String, CanvasMoreCard> get() = moreViews
    private var loadMoreJob: Job? = null
    private val viewportSync = Timer(120) { reconcileActiveEditors() }.apply { isRepeats = false }
    private val rootView: CanvasCardView? get() = views[CANVAS_ROOT]
    /** The root file's chat, once the root file is shown. */
    internal val rootChat: CanvasChatView? get() = rootView?.chat
    /** The root file's source viewer, once the root file is shown. */
    internal val rootSourcePane: CanvasSourcePane? get() = rootView?.sourcePane
    /** Each card's anchor, in its parent's y, as last laid out; arrows start there. */
    private val anchorYs = HashMap<String, Int>()
    private var message: JComponent? = null
    private var revealed: String? = null

    private val content = Canvas()
    internal val canvas: JComponent get() = content
    private val scroll = JBScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS)
    private val status = JLabel().apply {
        name = "canvas-status"
        isVisible = false
    }
    private val zoomLevel = JLabel("100%")
    private var wheelZoom: Double? = null
    private var wheelAnchor: Point? = null
    private val zoomFrame = Timer(16) { wheelZoom?.let { zoomTo(it, wheelAnchor) } }.apply { isRepeats = false }
    private val scrollMotion = CanvasMotion(VIEW_GLIDE_NANOS)
    private val scrollFrames: Timer = Timer(16) {
        val now = System.nanoTime()
        val at = scrollMotion.position("viewport", now)
        scrollTo(at.x + content.origin.x, at.y + content.origin.y, animationFrame = true)
        if (!scrollMotion.active(now)) scrollFrames.stop()
    }
    private var zoomFrom = 1.0
    private var zoomTarget = 1.0
    private var zoomStarted = 0L
    private val zoomFrames: Timer = Timer(16) {
        val t = ((System.nanoTime() - zoomStarted).toDouble() / VIEW_GLIDE_NANOS).coerceIn(0.0, 1.0)
        val eased = 1 - (1 - t).pow(3)
        zoomLevelTo(if (t >= 1) zoomTarget else zoomFrom + (zoomTarget - zoomFrom) * eased, null)
        if (t >= 1) zoomFrames.stop()
    }
    private val viewSave = Timer(400) { saveView() }.apply { isRepeats = false }
    private val unreveal = Timer(1_500) { clearReveal() }.apply { isRepeats = false }
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
    /** Whether an Alt + drag pan is under way, and whether the click after its release is still to be swallowed. */
    private var altPanning = false
    private var swallowClick = false
    private val altPanDispatcher = Disposer.newDisposable("canvas Alt pan").also {
        IdeEventQueue.getInstance().addDispatcher(::altPan, it)
    }

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
    private val findCard = JButton("Find card").apply {
        isFocusable = false
        toolTipText = "Fuzzy search the cards on the canvas and move to one (Ctrl/⌘ + F outside source text, where it finds in the text)"
        addActionListener { openCardSearch() }
    }
    private val refreshButton = JButton("Refresh").apply {
        name = "canvas-refresh"
        isFocusable = false
        toolTipText = "Read the code again after it changed, keeping the cards, chats and layout"
        addActionListener { refresh() }
    }
    private val loadMoreButton = JButton().apply {
        name = "canvas-load-more"
        isVisible = false
        isFocusable = false
        addActionListener { loadMore() }
    }
    private val controls = FloatingPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        add(sessions)
        add(sessionActions)
        add(findCard)
        add(refreshButton)
        add(loadMoreButton)
        add(JButton("Fold all").apply {
            isFocusable = false
            toolTipText = "Fold the file and every card's summary, source and chat; again to unfold them"
            addActionListener { foldAll() }
        })
        add(JButton("−").apply {
            isFocusable = false
            toolTipText = "Zoom out (− or Ctrl/⌘ + wheel)"
            addActionListener { zoomStep(1 / ZOOM_STEP) }
        })
        add(zoomLevel.apply { toolTipText = "Reset zoom (0)" }.onLeftClick { glideZoom(1.0) })
        add(JButton("+").apply {
            isFocusable = false
            toolTipText = "Zoom in (+ or Ctrl/⌘ + wheel)"
            addActionListener { zoomStep(ZOOM_STEP) }
        })
        add(status)
        add(JLabel("?").apply {
            font = font.deriveFont(Font.BOLD)
            toolTipText = canvasHelpHtml
        })
    }

    /** The card the reader is on, which the trail leads to. */
    private var current: String = CANVAS_ROOT
    /** Whether card [focusedId] owns keyboard and wheel navigation. */
    private var cardFocused = false
    private var focusedId: String? = null
    internal val hasFocusedCard: Boolean get() = cardFocused
    internal val focusedCard: String? get() = if (cardFocused) focusedId else null
    /** The root-first ids of [current]'s chain, as last shown, so the trail can step back when a card closes. */
    private var trail: List<String> = listOf(CANVAS_ROOT)
    private val breadcrumbs = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        name = "canvas-breadcrumbs"
        isOpaque = false
    }
    private val trailBar = FloatingPanel(BorderLayout()).apply {
        isVisible = false
        add(JBScrollPane(breadcrumbs, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }, BorderLayout.CENTER)
    }
    /** Running gap summaries by card id. */
    private val gapJobs = HashMap<String, Job>()

    /** The trail from the root file to the current card, as its crumbs read: the file name, then each card's title. */
    internal val trailTitles: List<String> get() = trail.mapNotNull { model.card(it)?.title }

    override val diffs: Map<String, String> get() = file.currentDiffs

    // While refreshing, sources are replaced and their symbol ids change, so they open nothing.
    override val sourceListener = object : CanvasSourcePane.Listener {
        override fun wheel(event: MouseWheelEvent) = this@CanvasSessionPanel.wheel(event)
        override fun linkClicked(pane: CanvasSourcePane, link: CanvasLink, event: MouseEvent) {
            if (!panned && ready) openLink(pane, link, event)
        }
        override fun selectionChanged(pane: CanvasSourcePane) {
            if (ready) showSelectionButton(pane)
        }
        override fun contextMenu(pane: CanvasSourcePane, event: MouseEvent, line: Int, link: CanvasLink?) {
            if (ready) showContextMenu(pane, event, line, link)
        }
    }

    private val subscription = model.subscribe(::changed)

    init {
        content.isFocusable = true
        content.add(selectionButton)
        content.addMouseWheelListener(::wheel)
        scroll.viewport.addChangeListener {
            val view = scroll.viewport.viewRect
            content.reserve(view.x.toLong() + view.width, view.y.toLong() + view.height)
            if (ready) {
                viewSave.restart()
                if (views.size > activeEditorLimit) viewportSync.restart()
            }
        }
        // Earlier components paint on top, so the floating bars come before the canvas.
        add(controls)
        add(trailBar)
        add(scroll)
        scroll.viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
        listOf(scroll.horizontalScrollBar, scroll.verticalScrollBar).forEach { bar ->
            bar.addAdjustmentListener { if (it.valueIsAdjusting) stopGlides() }
        }
        // Children inherit these empty traversal keys, so Tab reaches the canvas's own bindings.
        content.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
        content.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())
        installKeys()
        // Off inside the file's and cards' source, so the shortcut falls through to the IDE's Find there.
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = openCardSearch()
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = generateSequence(e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)) { it.parent }
                    .none { it is CanvasSourcePane || it is CanvasDiffPane }
            }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }.registerCustomShortcutSet(CommonShortcuts.getFind(), this, this)
        load()
    }

    private fun load() {
        showMessage("Reading ${file.source.name}…")
        scope.launch(Dispatchers.EDT) {
            try {
                val root = controller.loadRoot()
                loadedIds.clear()
                if (directory && !model.session.directoryInitialized) {
                    showMessage("Finding connections in ${file.source.name}…")
                    controller.initializeDirectory(loadChunk) { index, count -> showMessage("Opening file $index of $count…") }
                    loadedIds.addAll(model.cards.map { it.id })
                    if (persist) autosave?.request(model.session)
                } else if (model.cards.size > 1) {
                    loadedIds.addAll(selectInitialLoadedCards(model.cards, loadChunk))
                    controller.restore(loadedIds) { index, count -> showMessage("Restoring card $index of $count…") }
                    if (persist && model.session.pendingFiles.isNotEmpty()) autosave?.request(model.session)
                } else {
                    loadedIds += CANVAS_ROOT
                    if (persist && model.session.pendingFiles.isNotEmpty()) autosave?.request(model.session)
                }
                showMessage(null)
                model.cards.filter { it.id in loadedIds }.forEach { card ->
                    addView(card, materialize = activeEditorCount() < activeEditorLimit)
                }
                syncContinuationControls()
                ready = true
                refreshTrail()
                showStatus(when {
                    model.cards.size > 1 -> ""
                    directory -> if (root.text.isEmpty()) "This directory has no project files." else ""
                    else -> "Click an underlined name or select code to open a card, or ask about the file."
                })
                refreshAnchors()
                val initialLine = pendingInitialLine
                    ?: file.initialLine.also { file.initialLine = null }
                    ?: model.session.root.symbol?.line?.takeIf { it > 0 }
                pendingInitialLine = null
                restoreCardScrolls()
                content.revalidate()
                content.repaint()
                restoreView(model.session.view, initialLine)
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

    /**
     * Reads the root file and currently loaded cards' code again in place, as reopening the session would, keeping the
     * views and their chats. Not while a chat replies, since its tools hold symbol ids of the current graph. EDT.
     */
    internal fun refresh(): Job? {
        if (disposed || !ready) return null
        if (views.values.any { it.chatBusy }) {
            showStatus("Wait for the chat replies to finish, or stop them, then refresh.")
            return null
        }
        loadMoreJob?.cancel()
        loadMoreJob = null
        gapJobs.values.forEach(Job::cancel)
        gapJobs.clear()
        hideSelectionButton()
        ready = false
        refreshButton.isEnabled = false
        val targetIds = loadedIds.toSet()
        return scope.launch(Dispatchers.EDT) {
            showStatus("Reading ${file.source.name} again…")
            var problem: String? = null
            try {
                // The views pick up the fresh sources, the root file's included, as they resync.
                controller.refresh(targetIds) { index, count -> showStatus("Refreshing card $index of $count…") }
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Unable to refresh canvas session of ${file.rootPath}", e)
                problem = "Could not refresh: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (!disposed) {
                    ready = true
                    refreshButton.isEnabled = true
                    resyncViews()
                    showStatus(problem.orEmpty())
                }
            }
        }
    }

    /** Brings the views in line with the loaded subset and the controller's sources after changes made while not [ready]. */
    private fun resyncViews() {
        loadedIds.retainAll { model.card(it) != null }
        views.keys.filter { it !in loadedIds }.forEach { id ->
            views.remove(id)?.let { view ->
                content.remove(view)
                Disposer.dispose(view)
            }
            anchorYs.remove(id)
        }
        model.cards.filter { it.id in loadedIds }.forEach { card ->
            views[card.id]?.update(card) ?: addView(card, materialize = activeEditorCount() < activeEditorLimit)
        }
        if (cardFocused && focusedId !in views) setCardFocus(false)
        if (model.card(current) == null) current = trail.lastOrNull { model.card(it) != null } ?: CANVAS_ROOT
        refreshTrail()
        syncContinuationControls()
        refreshAnchors()
        content.validate()
        reconcileActiveEditors()
        content.revalidate()
        content.repaint()
    }

    private fun changed(change: CanvasChange) {
        if (persist) autosave?.request(model.session)
        if (!ready) return
        when (change) {
            is CanvasChange.CardOpened -> {
                loadedIds += change.card.id
                content.entering.add(change.card.id)
                addView(change.card, materialize = true)
                showStatus("")
                syncContinuationControls()
                if (views.size > activeEditorLimit) viewportSync.restart()
            }
            is CanvasChange.CardsOpened -> {
                mountLoadedCards(change.cards)
                showStatus("")
                syncContinuationControls()
                content.validate()
                reconcileActiveEditors()
            }
            is CanvasChange.CardsRemoved -> {
                loadedIds.removeAll(change.ids)
                change.ids.forEach { id ->
                    views.remove(id)?.let { view ->
                        content.remove(view)
                        Disposer.dispose(view)
                        if (selectionPane === view.sourcePane) hideSelectionButton()
                    }
                    anchorYs.remove(id)
                    gapJobs.remove(id)?.cancel()
                }
                if (cardFocused && focusedId in change.ids) setCardFocus(false)
                // The trail steps back to the nearest card left open.
                if (current in change.ids) current = trail.lastOrNull { it !in change.ids } ?: CANVAS_ROOT
                refreshTrail()
                syncContinuationControls()
            }
            is CanvasChange.CardChanged -> {
                val view = views[change.card.id] ?: return
                val anchorsChanged = view.card.anchor != change.card.anchor || view.card.wholeFile != change.card.wholeFile
                val titleChanged = view.card.title != change.card.title
                view.update(change.card)
                if (anchorsChanged) refreshAnchors()
                if (titleChanged && change.card.id in trail) refreshTrail()
            }
            else -> return
        }
        if (change !is CanvasChange.CardChanged) refreshAnchors()
        content.revalidate()
        content.repaint()
    }

    private fun addView(card: CanvasCard, materialize: Boolean = true): CanvasCardView? {
        if (card.id in views) return null
        val view = CanvasCardView(this, card, materializeSource = materialize)
        views[card.id] = view
        content.add(view)
        return view
    }

    private fun activeEditorCount(): Int = views.values.count { !it.hibernated && it.sourcePane != null }

    /** Loads the next chunk of deferred cards or pending files (scoped to [branchParentId] when specified). */
    internal fun loadMore(branchParentId: String? = null): Job? {
        if (disposed || !ready || loadMoreJob?.isActive == true) return null
        lateinit var launched: Job
        launched = scope.launch(Dispatchers.EDT) {
            var problem: String? = null
            try {
                val deferred = selectNextLoadedCards(model.cards, loadedIds, loadChunk, branchParentId)
                if (deferred.isNotEmpty()) {
                    controller.restoreCards(deferred) { index, count -> showStatus("Loading card $index of $count…") }
                    if (!disposed) mountLoadedCards(deferred)
                }
                val left = loadChunk - deferred.size
                if (left > 0 && !disposed) {
                    if (directory && controller.pendingDirectoryCount > 0 &&
                        (branchParentId == null || branchParentId == CANVAS_ROOT)) {
                        controller.loadMoreDirectoryFiles(left) { index, count ->
                            showStatus("Opening file $index of $count…")
                        }
                        if (persist) autosave?.request(model.session)
                    } else if (controller.pendingLinkedFiles.isNotEmpty()) {
                        val result = controller.loadMorePendingFiles(left, branchParentId, loadedIds) { index, count ->
                            showStatus("Opening file $index of $count…")
                        }
                        if (persist) autosave?.request(model.session)
                        problem = formatPendingLoadNotice(result)
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Unable to load more canvas cards of ${file.rootPath}", e)
                problem = "Could not load more: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (!disposed) {
                    if (loadMoreJob === launched) loadMoreJob = null
                    showStatus(problem.orEmpty())
                    syncContinuationControls()
                    refreshAnchors()
                    content.validate()
                    reconcileActiveEditors()
                    content.revalidate()
                    content.repaint()
                }
            }
        }
        loadMoreJob = launched
        syncContinuationControls()
        return launched
    }

    /** Loads [cardId] and its unloaded ancestor chain on demand, and ensures its editor is materialized. */
    internal suspend fun ensureCardLoaded(cardId: String): Boolean = withContext(Dispatchers.EDT) {
        if (disposed) return@withContext false
        loadMoreJob?.join()
        if (disposed) return@withContext false
        if (cardId in loadedIds && cardId in views) {
            views[cardId]?.materializeSource()
            return@withContext true
        }
        if (model.card(cardId) == null) return@withContext false
        val chain = selectChainToLoad(model.cards, loadedIds, cardId)
        if (chain.isEmpty()) return@withContext cardId in views
        showStatus("Loading ${chain.last().title}…")
        controller.restoreCards(chain) { index, count -> showStatus("Loading card $index of $count…") }
        if (disposed) return@withContext false
        mountLoadedCards(chain, forceMaterializeId = cardId)
        views[cardId]?.materializeSource()
        showStatus("")
        syncContinuationControls()
        refreshAnchors()
        content.validate()
        reconcileActiveEditors()
        content.revalidate()
        content.repaint()
        cardId in views
    }

    private fun mountLoadedCards(cards: List<CanvasCard>, forceMaterializeId: String? = null) {
        val added = mutableListOf<CanvasCardView>()
        for (card in cards) {
            val current = model.card(card.id) ?: continue
            if (current.parentId != null && current.parentId !in loadedIds) continue
            loadedIds += current.id
            if (current.id !in views) {
                val view = addView(current, materialize = current.id == forceMaterializeId || activeEditorCount() < activeEditorLimit)
                if (view != null) {
                    content.entering.add(current.id)
                    added += view
                }
            }
        }
        restoreCardScrollsFor(added)
    }

    private fun currentDeferredBranches(): List<CanvasDeferredBranch> {
        val pendingByParent = controller.pendingLinkedByParent(loadedIds)
        return deferredBranches(model.cards, loadedIds, pendingByParent, controller.pendingDirectoryCount)
    }

    private fun syncContinuationControls() {
        val branches = currentDeferredBranches()
        syncMoreViews(branches)
        updateLoadMoreControl(branches)
    }

    private fun syncMoreViews(branches: List<CanvasDeferredBranch>) {
        val loading = loadMoreJob?.isActive == true
        val desiredIds = branches.mapTo(hashSetOf()) { it.id }
        moreViews.keys.filter { it !in desiredIds }.forEach { id ->
            moreViews.remove(id)?.let { content.remove(it) }
        }
        for (branch in branches) {
            val existing = moreViews[branch.id]
            if (existing != null) {
                existing.update(branch, loadChunk, loading)
            } else {
                val card = CanvasMoreCard(this, branch) { parentId -> loadMore(parentId) }
                card.update(branch, loadChunk, loading)
                card.applyZoom(zoom)
                moreViews[branch.id] = card
                content.add(card)
            }
        }
    }

    private fun updateLoadMoreControl(branches: List<CanvasDeferredBranch>) {
        val remaining = branches.sumOf { it.total }
        if (remaining <= 0) {
            loadMoreButton.isVisible = false
            controls.revalidate()
            controls.repaint()
            revalidate()
            return
        }
        val loaded = (loadedIds.size - 1).coerceAtLeast(0)
        val total = loaded + remaining
        val loading = loadMoreJob?.isActive == true
        loadMoreButton.text = if (loading) "Loading…" else "Load more ($loaded / $total)"
        loadMoreButton.isEnabled = !loading
        loadMoreButton.toolTipText = if (remaining <= loadChunk) "Load the remaining $remaining cards"
            else "Load the next $loadChunk of $remaining remaining cards"
        loadMoreButton.isVisible = true
        controls.revalidate()
        controls.repaint()
        revalidate()
    }

    /** Reconciles off-screen editor hibernation and near-viewport materialization. */
    internal fun reconcileActiveEditors() {
        if (disposed || !ready) return
        val tracked = views.values.filter { it.hibernated || it.sourcePane != null }
        if (tracked.size <= activeEditorLimit && views.values.none { it.hibernated }) return
        val origin = content.origin
        val bounds = tracked.map { view ->
            val at = content.destination(view.id)
            val x = at?.let { it.x + origin.x } ?: view.x
            val y = at?.let { it.y + origin.y } ?: view.y
            CanvasCardBounds(view.id, x, y, view.width.coerceAtLeast(1), view.height.coerceAtLeast(1))
        }
        val rect = scroll.viewport.viewRect
        val viewport = CanvasViewportRect(rect.x, rect.y, rect.width, rect.height)
        val pinned = buildSet {
            add(CANVAS_ROOT)
            focusedId?.let(::add)
            revealed?.let(::add)
            selectionPane?.owner?.let(::add)
            tracked.filter { it.busy || summarizingGaps(it.id) }.forEach { add(it.id) }
        }
        val materialized = tracked.filter { !it.hibernated }.mapTo(hashSetOf()) { it.id }
        val plan = selectActiveEditorCards(bounds, viewport, materialized, pinned, activeEditorLimit, activeEditorTarget)
        if (plan.hibernate.isEmpty() && plan.materialize.isEmpty()) return
        plan.hibernate.forEach { views[it]?.hibernateSource() }
        plan.materialize.forEach { views[it]?.materializeSource() }
        content.revalidate()
        content.repaint()
    }

    /**
     * Scrolls each restored card's code back to where the chain left it: the line its first child card was opened from,
     * or else its first usage site. Cards hang from those lines, and a card's code otherwise reopens at its top.
     */
    private fun restoreCardScrolls() {
        content.validate()
        restoreCardScrollsFor(views.values)
    }

    private fun restoreCardScrollsFor(targets: Collection<CanvasCardView>) {
        // The root file reopens at its top, as when it is first opened.
        for (view in targets) {
            if (view.id == CANVAS_ROOT) continue
            val src = view.source ?: continue
            if (src.text.isEmpty()) continue
            val childOrUsageOffset = model.children(view.id).minOfOrNull { it.anchor.startOffset }
                ?: view.card.usages?.sites?.firstOrNull()?.let { view.sourcePane?.lineStart(it.line) ?: sourceLineOffset(src.text, src.firstLine, it.line) }
            if (childOrUsageOffset != null) {
                // The cards hanging from this one follow the line, so lay them out again once it has moved.
                view.scheduleInitialScroll(childOrUsageOffset) { if (!disposed) content.revalidate() }
                continue
            }
            val targetLine = view.card.symbol?.line?.takeIf { it > 0 && view.card.wholeFile } ?: continue
            val offset = view.sourcePane?.lineStart(targetLine) ?: sourceLineOffset(src.text, src.firstLine, targetLine) ?: continue
            view.scheduleInitialLine(targetLine, offset) { if (!disposed) content.revalidate() }
        }
    }

    private fun refreshAnchors() {
        views.values.forEach { view -> view.sourcePane?.highlightAnchors(childAnchors(view.id)) }
    }

    /** Follows [link]: a use opens the card of what it names; a symbol's own declared name lists its callers and users. */
    private fun openLink(pane: CanvasSourcePane, link: CanvasLink, event: MouseEvent) {
        if (link.definition) return findLinkUsages(pane, link, event)
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

    /** Opens the card of [target] from [link] in [parentId]'s source, then shows it. */
    internal fun openSymbol(parentId: String, link: CanvasLink, target: Int): Job = scope.launch(Dispatchers.EDT) {
        val name = controller.node(target)?.name ?: "symbol"
        showStatus("Opening $name…")
        try {
            val card = controller.openSymbol(parentId, link.range.first, link.range.last + 1, target)
            if (card.id !in views) ensureCardLoaded(card.id)
            showStatus("")
            content.validate()
            reveal(card.id)
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException) throw e
            LOG.warn("Unable to open canvas card of $name", e)
            showStatus("Could not open $name: " +
                (e.message ?: e.javaClass.simpleName))
        }
    }

    /** The cards' chats act on the canvas like the reader: each card they open or show is revealed. */
    override val actions: CanvasActions = object : CanvasActions {
        override val session: CanvasSession get() = model.session
        override fun source(cardId: String): CanvasSource? = controller.source(cardId)
        override suspend fun loadSource(cardId: String): CanvasSource? {
            withContext(Dispatchers.EDT) {
                if (!disposed && ready && cardId !in loadedIds && model.card(cardId) != null) ensureCardLoaded(cardId)
            }
            return controller.source(cardId)
        }
        override fun target(id: Int): CanvasTarget? = controller.target(id)

        override suspend fun declarations(path: String, name: String): List<CanvasTarget> =
            controller.declarations(projectFile(path), name).mapNotNull { controller.target(it.id) }

        override suspend fun findUsages(target: Int): CanvasUsageSearch = controller.findUsages(target)

        override suspend fun openSymbol(parentId: String, start: Int, end: Int, target: Int) =
            revealing { controller.openSymbol(parentId, start, end, target, CardOpenedBy.LLM) }

        override suspend fun openSelection(parentId: String, start: Int, end: Int) = revealing {
            withContext(Dispatchers.EDT) { controller.openSelection(parentId, start, end, CardOpenedBy.LLM) }
                ?: throw ToolInputException("The selected text is blank.")
        }

        override suspend fun openFile(parentId: String, start: Int, end: Int, path: String) =
            revealing { controller.openFile(parentId, start, end, projectFile(path), CardOpenedBy.LLM) }

        override suspend fun show(cardId: String) = withContext(Dispatchers.EDT) {
            checkReady()
            ensureCardLoaded(cardId)
            content.validate()
            reveal(cardId)
        }

        override val graph: CanvasGraph get() = controller.graph

        private suspend fun revealing(open: suspend () -> CanvasCard): CanvasCard {
            withContext(Dispatchers.EDT) { checkReady() }
            val card = open()
            withContext(Dispatchers.EDT) {
                if (!disposed) {
                    if (card.id !in views) ensureCardLoaded(card.id)
                    content.validate()
                    reveal(card.id)
                }
            }
            return card
        }

        private fun checkReady() {
            if (disposed || !ready) throw ToolInputException("The canvas is still loading; answer without changing it.")
        }

        private suspend fun projectFile(path: String): VirtualFile = readAction { ProjectFiles(project).resolve(path) }
            .also { if (it.isDirectory) throw ToolInputException("'$path' is a directory; name a file.") }
    }

    override fun openSideBySide(cardId: String) {
        val chain = model.session.chain(cardId)
        val locations = canvasSourceChain(model.session, cardId).mapIndexed { index, location ->
            val source = chain.getOrNull(index)?.let { controller.source(it.id) }
            location.copy(file = source?.file?.takeIf { it.isValid } ?: controller.findFile(location.path))
        }
        openSourceChain(project, locations, model.card(cardId)?.title ?: model.session.title)
    }

    private fun sideBySideAction(cardId: String) = JMenuItem("Side by side").apply {
        toolTipText = "Open the chain from the root file to this card in a new tab"
        addActionListener { openSideBySide(cardId) }
    }

    override fun expandToFile(cardId: String) {
        scope.launch(Dispatchers.EDT) {
            showStatus("Opening full file…")
            try {
                controller.expandToFile(cardId)
                hideSelectionButton()
                showStatus("")
                content.validate()
                reveal(cardId)
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Unable to expand canvas card to file", e)
                showStatus("Could not expand to file: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    internal fun openSelection(pane: CanvasSourcePane) {
        val range = pane.selection() ?: return
        hideSelectionButton()
        val card = controller.openSelection(pane.owner, range.first, range.last + 1) ?: return
        pane.clearSelection()
        if (card.id in views) {
            content.validate()
            reveal(card.id)
        } else {
            scope.launch(Dispatchers.EDT) {
                if (ensureCardLoaded(card.id) && !disposed) {
                    content.validate()
                    reveal(card.id)
                }
            }
        }
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

    private fun showContextMenu(pane: CanvasSourcePane, event: MouseEvent, line: Int, link: CanvasLink? = null) {
        val selected = pane.selection()
        val cardView = pane.owner?.let(views::get)
        val linkTargets = link?.targets?.filter(controller::canFindUsages).orEmpty()
        JPopupMenu().apply {
            pane.source.file?.let { file -> add(JMenuItem("Context…").apply {
                addActionListener { showFileContext(project, file, line + 1, pane) }
            }) }
            if (pane.source.file == null && model.card(pane.owner)?.isDirectoryRoot != true) add(JMenuItem("Go to source").apply {
                addActionListener { navigate(pane.source, pane.source.path, line) }
            })
            if (pane.owner != CANVAS_ROOT) add(sideBySideAction(pane.owner))
            if (link != null && linkTargets.isNotEmpty()) {
                add(JMenuItem("Find usages").apply {
                    addActionListener { findLinkUsages(pane, link, event) }
                })
            } else if (cardView?.canFindUsages == true) {
                add(JMenuItem("Find usages").apply {
                    addActionListener { cardView.showUsages() }
                })
            }
            add(JMenuItem("Open card for selection").apply {
                isEnabled = selected != null
                addActionListener { openSelection(pane) }
            })
            add(JMenuItem("Copy").apply {
                isEnabled = selected != null
                addActionListener { pane.editor.selectionModel.selectedText?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) } }
            })
        }.show(event.component, event.x, event.y)
    }

    private fun findLinkUsages(pane: CanvasSourcePane, link: CanvasLink, event: MouseEvent) {
        val targets = link.targets.filter(controller::canFindUsages).mapNotNull(controller::node)
        when {
            targets.isEmpty() -> showStatus("Cannot search usages for this symbol.")
            targets.size == 1 -> searchLinkUsages(pane, link, targets.single().id, event)
            else -> JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
                .setTitle("Find Usages Of")
                .setRenderer(SimpleListCellRenderer.create("") { node: CallGraphNode -> canvasTargetLabel(node, controller::relative) })
                .setItemChosenCallback { node -> searchLinkUsages(pane, link, node.id, event) }
                .createPopup()
                .show(RelativePoint(event))
        }
    }

    internal fun searchLinkUsages(pane: CanvasSourcePane, link: CanvasLink, target: Int, event: MouseEvent): Job = scope.launch(Dispatchers.EDT) {
        val name = controller.node(target)?.name ?: "symbol"
        try {
            val found = controller.findUsages(target)
            showStatus(if (found.usages.isEmpty()) "No project usages found for $name." else "")
            if (found.usages.isEmpty()) return@launch
            JBPopupFactory.getInstance().createPopupChooserBuilder(canvasUsageFiles(found.usages))
                .setTitle("Usages of ${found.targetName}")
                .setRenderer(SimpleListCellRenderer.create("") { file: CanvasUsageFile ->
                    "${file.fileName} (${file.usages.size}) · ${file.detail}" + file.directory.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
                })
                .setItemChosenCallback { file -> openUsageFileAt(pane.owner, link.range, file, found.targetName) }
                .createPopup()
                .show(RelativePoint(event))
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException) throw e
            LOG.warn("Unable to find usages of $name", e)
            showStatus("Could not find usages of $name: " +
                (e.message ?: e.javaClass.simpleName))
        }
    }

    override fun openUsageFile(cardId: String, file: CanvasUsageFile, targetName: String) {
        openUsageFileAt(cardId, controller.usageAnchor(cardId), file, targetName)
    }

    /**
     * Opens the card of the whole [file] of usages of [targetName] from [range] of [parentId]'s source, lists the usage
     * sites on it and scrolls it to the first.
     */
    internal fun openUsageFileAt(parentId: String, range: IntRange, file: CanvasUsageFile, targetName: String): Job =
        scope.launch(Dispatchers.EDT) {
            val start = range.first
            val end = maxOf(range.first, range.last + 1)
            val name = file.fileName
            showStatus("Opening $name…")
            try {
                val found = controller.findFile(file.filePath) ?: error("Cannot find ${file.path}.")
                val card = controller.openFile(parentId, start, end, found)
                val sites = file.sites(targetName)
                model.setUsages(card.id, sites)
                if (card.id !in views) ensureCardLoaded(card.id)
                showStatus("")
                content.validate()
                reveal(card.id)
                sites.sites.firstOrNull()?.let { revealLine(card.id, it.line) }
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Unable to open canvas usage $name", e)
                showStatus("Could not open $name: " +
                    (e.message ?: e.javaClass.simpleName))
            }
        }

    override fun navigate(source: CanvasSource?, path: String, line: Int) {
        val target = source?.file?.takeIf { it.isValid } ?: controller.findFile(path)
        if (target == null) {
            showStatus("Cannot find $path.")
            return
        }
        if (target.fileType.isBinary) FileEditorManager.getInstance(project).openFile(target, true)
        else OpenFileDescriptor(project, target, maxOf(line, 0), 0).navigate(true)
    }

    /**
     * Links in a card's summary and chats keep the reader on the canvas: code the card itself shows scrolls it there,
     * code another card shows reveals that card, and other code opens on a new card from this one (a declaration's
     * symbol card, or the whole file at the referenced line, attached when the graph cannot read it).
     */
    override fun textNavigator(cardId: String): LinkedTextNavigator = object : LinkedTextNavigator {
        override fun contextFiles(): List<String> = model.session.chain(cardId).asReversed()
            .filterNot { it.isDirectoryRoot }.map { canvasPath(it.filePath) }.distinct()

        override fun shown(candidates: List<SymbolCandidate>): SymbolCandidate? {
            val spans = shownSpans()
            return candidates.mapNotNull { candidate ->
                val move = canvasTextMove(cardId, canvasPath(candidate.absolutePath), candidate.line - 1, spans, nearness(cardId),
                    column = candidate.column - 1)
                (move as? CanvasTextMove.Scroll)?.let { candidate to nearness(cardId)(it.cardId) }
            }.minByOrNull { it.second }?.first
        }

        override fun openFile(path: String, absolutePath: String?, lines: IntRange?, column: Int?) {
            val file = controller.findFile(absolutePath ?: path) ?: return showStatus("Cannot find $path.")
            if (file.isDirectory) return
            showTextTarget(cardId, canvasPath(file.path), lines?.first?.minus(1)?.coerceAtLeast(0), file.name) { parentId ->
                val (start, end) = textAnchor(parentId, file.name)
                controller.openOrAttachFile(parentId, start, end, file)
            }
        }

        override fun openDeclaration(declaration: SymbolCandidate, mention: SymbolMention) {
            val file = controller.findFile(declaration.absolutePath) ?: return showStatus("Cannot find ${declaration.path}.")
            val line = (declaration.line - 1).coerceAtLeast(0)
            showTextTarget(cardId, canvasPath(file.path), line, mention.name, column = declaration.column - 1) { parentId ->
                val (start, end) = textAnchor(parentId, mention.name)
                val node = controller.declarations(file, mention.name)
                    .mapNotNull { node -> nodeLine(node)?.let { node to abs(it - line) } }
                    .filter { it.second <= DECLARATION_LINE_SLACK }
                    .minByOrNull { it.second }?.first
                if (node != null) controller.openSymbol(parentId, start, end, node.id)
                else controller.openOrAttachFile(parentId, start, end, file)
            }
        }
    }

    private fun canvasPath(path: String): String = controller.relative(path) ?: path

    private fun nearness(contextId: String): (String) -> Int = { canvasCardNearness(model.session, contextId, it) }

    /** What each card shows, for leading text links to cards already on the canvas. */
    private fun shownSpans(): List<CanvasShownSpan> {
        val columns = HashMap<String, Pair<Int, Int>>()
        // A selection's columns follow from its offsets in its parent's text, and that parent may be a selection too.
        fun selectionColumns(card: CanvasCard): Pair<Int, Int>? {
            if (card.kind != CardKind.SELECTION) return null
            columns[card.id]?.let { return it }
            val parentId = card.parentId ?: return null
            val parentText = controller.source(parentId)?.text ?: return null
            val parentStart = model.session.card(parentId)?.let(::selectionColumns)?.first ?: 0
            return canvasSelectionColumns(parentText, parentStart, card.anchor).also { columns[card.id] = it }
        }
        return model.cards.filterNot { it.isDirectoryRoot }.map { card ->
            val source = controller.source(card.id)
            val first = source?.firstLine ?: card.firstLine
            val last = first + (source?.text ?: card.text).count { it == '\n' }
            val selected = selectionColumns(card)
            CanvasShownSpan(card.id, canvasPath(card.filePath), first..last,
                whole = card.kind == CardKind.ROOT || card.kind == CardKind.ATTACHMENT || card.wholeFile,
                startColumn = selected?.first ?: 0, endColumn = selected?.second)
        }
    }

    /** Where a card opened from a link in [parentId]'s text anchors: where its source names [name], else its own name. */
    private fun textAnchor(parentId: String, name: String): Pair<Int, Int> {
        val range = controller.source(parentId)?.text?.let { canvasTextAnchor(it, name) } ?: controller.usageAnchor(parentId)
        return range.first to maxOf(range.first, range.last + 1)
    }

    /**
     * Shows [path] at 0-based [line] (the file as a whole when null) for a link in [contextId]'s text: scrolls the card
     * that already shows it, nearest [contextId] first, or opens a card from [contextId] with [open] and scrolls a
     * whole-file card to [line]. A [column] on that line must be shown too, for selections that start or end within it.
     */
    private fun showTextTarget(contextId: String, path: String, line: Int?, label: String, column: Int? = null,
        open: suspend (String) -> CanvasCard) {
        scope.launch(Dispatchers.EDT) {
            try {
                when (val move = canvasTextMove(contextId, path, line, shownSpans(), nearness(contextId), column)) {
                    is CanvasTextMove.Scroll -> showCardLine(move.cardId, move.line)
                    CanvasTextMove.Open -> {
                        showStatus("Opening $label…")
                        if (!ensureCardLoaded(contextId)) return@launch
                        val card = open(contextId)
                        showStatus("")
                        val scrolls = card.wholeFile || card.kind == CardKind.ATTACHMENT
                        showCardLine(card.id, line.takeIf { scrolls })
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Unable to show $label on the canvas", e)
                showStatus("Could not open $label: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Brings card [cardId] into view, scrolled to 0-based [line] when given. */
    private suspend fun showCardLine(cardId: String, line: Int?) {
        if (!ensureCardLoaded(cardId) || disposed) return
        content.validate()
        if (line == null) reveal(cardId)
        else {
            if (revealed != cardId) reveal(cardId, glide = false)
            revealLine(cardId, line)
        }
    }

    override fun attachFile(cardId: String) {
        val descriptor = FileChooserDescriptor(true, false, true, true, false, true).apply {
            title = "Attach Files to ${model.card(cardId)?.title ?: file.source.name}"
            description = "Link documents, screenshots or other files to this card"
        }
        FileChooser.chooseFiles(descriptor, project, null) { files ->
            scope.launch(Dispatchers.EDT) {
                for (attachment in files) {
                    try {
                        val card = controller.attachFile(cardId, attachment)
                        if (card.id !in views) ensureCardLoaded(card.id)
                        content.validate()
                        reveal(card.id)
                        showStatus("")
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ControlFlowException) throw e
                        LOG.warn("Could not attach ${attachment.path}", e)
                        showStatus("Could not attach ${attachment.name}: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    override fun close(cardId: String) {
        val card = model.card(cardId) ?: return
        val parent = card.parentId ?: return
        model.remove(cardId)
        moveTo(parent, focus = false)
    }

    override fun cardResized() {
        if (!ready) return
        content.revalidate()
        content.repaint()
    }

    private fun setCardFocus(focused: Boolean, cardId: String? = null) {
        val nextFocused = focused && cardId != null && cardId in views
        val nextId = if (nextFocused) cardId else null
        if (nextFocused == cardFocused && nextId == focusedId) return
        focusedId?.let { views[it]?.focused = false }
        cardFocused = nextFocused
        focusedId = nextId
        nextId?.let {
            views[it]?.let { view ->
                view.materializeSource()
                view.focused = true
            }
        }
        content.repaint()
    }

    private fun clearReveal() {
        unreveal.stop()
        revealed?.let { views[it]?.highlighted = false }
        if (revealed != null) {
            revealed = null
            content.repaint()
        }
    }

    private fun exitCardFocus(focusCanvas: Boolean = true, clearSelection: Boolean = false) {
        setCardFocus(false)
        clearReveal()
        if (clearSelection) {
            selectionPane?.clearSelection()
            hideSelectionButton()
        }
        if (focusCanvas) content.requestFocusInWindow()
    }

    override fun focusCanvas() {
        content.requestFocusInWindow()
    }

    private fun focusedNavigationEditor(): EditorEx? =
        if (!cardFocused) null else focusedId?.let(views::get)?.navigationEditor

    private fun typing(): Boolean {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        return owner is JTextComponent && owner.isEditable && SwingUtilities.isDescendingFrom(owner, content)
    }

    override fun revealLine(cardId: String, line: Int) {
        scrollFrames.stop()
        val view = views[cardId] ?: return
        view.materializeSource()
        setCardFocus(true, cardId)
        select(cardId)
        if (!typing()) content.requestFocusInWindow()
        if (CardSection.SOURCE in view.card.folded) model.setFolded(cardId, CardSection.SOURCE, false)
        view.showSource()
        content.validate()
        val pane = view.sourcePane ?: return
        val y = pane.scrollToLine(line) ?: return showStatus("Line ${line + 1} is not in the shown text of ${view.card.title}.")
        // A card opened just now may still be gliding into place, so aim at where it settles.
        val inCard = SwingUtilities.convertPoint(pane, 0, y, view)
        val card = content.destination(cardId) ?: CanvasPlacement(view.x, view.y)
        val viewport = scroll.viewport.viewRect
        val height = zoomScaled(96, zoom).coerceAtMost(viewport.height)
        val padding = zoomScaled(16, zoom)
        glideTo(Rectangle(card.x - padding, card.y + inCard.y - height / 2,
            minOf(view.width + padding * 2, viewport.width), height), viewport)
    }

    /** Emphasises card [cardId] for a moment and scrolls it into view; null clears the emphasis. */
    private fun reveal(cardId: String?, glide: Boolean = true) {
        if (cardId == null) {
            clearReveal()
            return
        }
        revealed?.let { views[it]?.highlighted = false }
        revealed = cardId
        val view = views[cardId] ?: return
        view.materializeSource()
        view.highlighted = true
        select(cardId)
        setCardFocus(true, cardId)
        if (!typing()) content.requestFocusInWindow()
        if (glide) {
            content.validate()
            val viewport = scroll.viewport.viewRect
            val at = content.destination(cardId) ?: CanvasPlacement(view.x, view.y)
            val padding = zoomScaled(16, zoom)
            glideTo(Rectangle(at.x - padding, at.y - padding,
                minOf(view.width + padding * 2, viewport.width), minOf(view.height, viewport.height)), viewport)
        }
        unreveal.restart()
    }

    /** Glides the view the least distance that brings [target] (canvas coordinates) into [viewport]. */
    private fun glideTo(target: Rectangle, viewport: Rectangle) {
        fun offset(start: Int, length: Int, visibleStart: Int, visibleLength: Int) = when {
            start < visibleStart -> start
            start + length > visibleStart + visibleLength -> start + length - visibleLength
            else -> visibleStart
        }
        glideTo(offset(target.x, target.width, viewport.x, viewport.width),
            offset(target.y, target.height, viewport.y, viewport.height))
    }

    /**
     * Eases the view's top left to ([x], [y]) so the reader can follow where the canvas went, as the Diffs wall does
     * between files; at once when not on screen. A new destination mid-way continues from where the view is.
     */
    private fun glideTo(x: Int, y: Int) {
        // Glides stay within the canvas; only the reader's own panning grows it.
        val destination = CanvasPlacement(maxOf(x, 0), maxOf(y, 0))
        if (!isShowing) return scrollTo(destination.x, destination.y)
        // The glide runs relative to the canvas origin, which the reader may grow on the way.
        val origin = content.origin
        val at = scroll.viewport.viewPosition
        val now = System.nanoTime()
        scrollMotion.target("viewport", CanvasPlacement(at.x - origin.x, at.y - origin.y), now, false)
        scrollMotion.target("viewport", CanvasPlacement(destination.x - origin.x, destination.y - origin.y), now, true)
        scrollFrames.start()
    }

    private fun openCardSearch() {
        if (!ready || disposed) return
        showCardSearch(findCard, model.cards.filter { it.kind != CardKind.ROOT }.map(::canvasCardCandidate)) { id ->
            if (id in views) moveTo(id)
            else scope.launch(Dispatchers.EDT) {
                if (ensureCardLoaded(id) && !disposed) moveTo(id)
            }
        }
    }

    /**
     * Centres the canvas on card [cardId] (its top, if taller than the view). With [focus], also focuses the card and
     * emphasises it for a moment; the root card, always in its place, is focused without emphasis.
     */
    private fun moveTo(cardId: String, focus: Boolean = true) {
        val view = views[cardId] ?: return
        view.materializeSource()
        if (focus) setCardFocus(true, cardId)
        content.validate()
        val viewport = scroll.viewport.extentSize
        val top = JBUI.scale(FLOATING_ROW_SPACE) + zoomScaled(16, zoom)
        val at = content.destination(cardId) ?: CanvasPlacement(view.x, view.y)
        val x = if (view.width <= viewport.width) at.x + view.width / 2 - viewport.width / 2 else at.x - zoomScaled(16, zoom)
        val y = if (view.height <= viewport.height - top) at.y + view.height / 2 - (viewport.height + top) / 2 else at.y - top
        if (focus) {
            if (cardId != CANVAS_ROOT) reveal(cardId, glide = false) else clearReveal()
        }
        glideTo(x, y)
        if (focus && !typing()) content.requestFocusInWindow()
    }

    /** Makes [cardId] the card the trail leads to. */
    private fun select(cardId: String) {
        val next = cardId.takeIf { model.card(it) != null } ?: CANVAS_ROOT
        if (next == current && trail == model.chain(next).map(CanvasCard::id)) return
        current = next
        refreshTrail()
    }

    /** Shows the trail from the root file to [current]: a crumb for each, which moves the canvas there. */
    private fun refreshTrail() {
        trail = model.chain(current).map(CanvasCard::id).ifEmpty { listOf(CANVAS_ROOT) }
        breadcrumbs.removeAll()
        val steps = trail.mapNotNull { id -> model.card(id)?.let { id to it.title } }
        steps.forEachIndexed { index, (id, title) ->
            if (index > 0) breadcrumbs.add(JLabel("›").apply { foreground = UIUtil.getContextHelpForeground() })
            breadcrumbs.add(ActionLink(title) { moveTo(id) }.apply {
                name = "canvas-crumb"
                isFocusable = false
                toolTipText = if (id == CANVAS_ROOT) "Move to ${file.rootPath}" else this@CanvasSessionPanel.model.card(id)?.let { "Move to ${describe(it)}" }
                if (index == steps.lastIndex) font = font.deriveFont(Font.BOLD)
            })
        }
        trailBar.isVisible = ready
        breadcrumbs.revalidate()
        revalidate()
        repaint()
    }

    override fun summarizingGaps(cardId: String): Boolean = gapJobs[cardId]?.isActive == true

    /**
     * Summarizes, one at a time, the gaps of [cardId]'s source without a current summary;
     * each summary is saved as it arrives. A replaced source (the card expanded or closed) ends the run.
     */
    override fun summarizeGaps(cardId: String) {
        if (disposed || summarizingGaps(cardId)) return
        val source = controller.source(cardId) ?: return
        val units = source.unsummarizedGaps(model.card(cardId)?.gapSummaries ?: return)
        if (units.isEmpty()) {
            showStatus("Every gap of this source is summarized.")
            return
        }
        val name = model.card(cardId)?.title ?: file.source.name
        gapJobs[cardId] = scope.launch(Dispatchers.EDT) {
            val workflow = SchematicGapWorkflow(services.maxContextChars(), services.complete())
            var failed = 0
            var problem: String? = null
            try {
                withBackgroundProgress(project, "Summarizing gaps of $name") {
                    withCountProgress("Summarizing gaps of $name", "gaps") { onProgress ->
                        for ((index, unit) in units.withIndex()) {
                            if (controller.source(cardId) !== source) break
                            onProgress(index, units.size)
                            showStatus("Summarizing gaps of $name: ${index + 1} / ${units.size}…")
                            try {
                                val input = controller.gapSource(cardId, unit)
                                val text = withContext(Dispatchers.IO) { workflow.summarize(input, unit) }?.trim()?.takeIf { it.isNotEmpty() }
                                val hash = source.gapHash(unit.first, unit.last)
                                if (text != null && hash != null && controller.source(cardId) === source) {
                                    model.addGapSummary(cardId, GapSummary(unit.first, unit.last, hash, text))
                                }
                            } catch (e: Throwable) {
                                if (e is CancellationException || e is ControlFlowException) throw e
                                LOG.warn("Canvas gap summary of $name failed", e)
                                failed++
                                problem = e.message ?: e.javaClass.simpleName
                                // A changed file fails every remaining gap the same way.
                                if (e is IllegalStateException && problem.startsWith("Source changed")) {
                                    failed += units.size - index - 1
                                    break
                                }
                            }
                            onProgress(index + 1, units.size)
                        }
                    }
                }
                showStatus(if (failed == 0) "" else "$failed of ${units.size} gaps of $name could not be summarized: $problem")
            } catch (e: CancellationException) {
                showStatus("")
                throw e
            } finally {
                if (gapJobs[cardId] === coroutineContext[Job]) gapJobs.remove(cardId)
            }
        }
    }

    /**
     * Arrows, pages, Space and Home/End scroll a focused card's code on its scrollable axes, and the canvas otherwise.
     * Escape or empty space returns to canvas navigation; Tab moves between cards in opening order.
     */
    private fun installKeys() {
        fun acceptsFocus(): Boolean {
            val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            return owner != null && (owner === content || SwingUtilities.isDescendingFrom(owner, content)) && !typing()
        }
        fun ownerCard(): Pair<Boolean, String?>? {
            val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return null
            if (owner is JTextComponent && owner.isEditable) return null
            val ancestors = generateSequence(owner) { it.parent }.toList()
            if (ancestors.none { it is CanvasSourcePane || it is CanvasDiffPane }) return null
            return true to ancestors.filterIsInstance<CanvasCardView>().firstOrNull()?.id
        }
        fun focusInViewer(): Boolean = ownerCard() != null
        fun bind(name: String, strokes: List<String>, enabled: () -> Boolean = { true }, action: () -> Unit) {
            val keyStrokes = strokes.map { requireNotNull(KeyStroke.getKeyStroke(it)) { "Invalid keystroke: $it" } }
            keyStrokes.forEach { content.getInputMap(JComponent.WHEN_FOCUSED).put(it, name) }
            val shortcuts = keyStrokes.filter { it.keyCode != 0 }.map { KeyboardShortcut(it, null) }
            if (shortcuts.isNotEmpty()) {
                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        if (ready && !disposed && acceptsFocus() && enabled()) action()
                    }
                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = ready && !disposed && acceptsFocus() && enabled()
                    }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                }.registerCustomShortcutSet(CustomShortcutSet(*shortcuts.toTypedArray()), content)
            }
            content.actionMap.put(name, object : AbstractAction(name) {
                override fun actionPerformed(e: ActionEvent?) {
                    if (ready && !disposed && enabled()) action()
                }
                override fun isEnabled() = ready && !disposed && enabled()
            })
        }
        val step = { zoomScaled(KEY_SCROLL_STEP, zoom) }
        fun page(vertical: Boolean) = maxOf((if (vertical) scroll.viewport.height else scroll.viewport.width) - step(), step())
        fun scrollBy(dx: Int, dy: Int, page: Boolean = false, edge: Boolean = false) {
            if (!cardFocused) ownerCard()?.let { (_, id) -> setCardFocus(true, id) }
            val rest = focusedNavigationEditor()?.let { scrollCanvasViewer(it, dx, dy, page, edge) } ?: Point(dx, dy)
            if (rest.x == 0 && rest.y == 0) return
            stopGlides()
            val at = scroll.viewport.viewPosition
            val maxX = maxOf(0, content.preferredSize.width - scroll.viewport.width)
            val maxY = maxOf(0, content.preferredSize.height - scroll.viewport.height)
            // Home and End go to the canvas's current edges; steps go on past them, growing the canvas.
            val x = when {
                rest.x == 0 -> at.x.toLong()
                edge -> if (rest.x > 0) maxX.toLong() else 0L
                else -> at.x.toLong() + rest.x
            }
            val y = when {
                rest.y == 0 -> at.y.toLong()
                edge -> if (rest.y > 0) maxY.toLong() else 0L
                else -> at.y.toLong() + rest.y
            }
            scrollTo(x, y)
        }
        bind("up", listOf("UP", "KP_UP")) { scrollBy(0, -step()) }
        bind("down", listOf("DOWN", "KP_DOWN")) { scrollBy(0, step()) }
        bind("left", listOf("LEFT", "KP_LEFT", "shift LEFT", "shift KP_LEFT", "shift UP", "shift KP_UP")) { scrollBy(-step(), 0) }
        bind("right", listOf("RIGHT", "KP_RIGHT", "shift RIGHT", "shift KP_RIGHT", "shift DOWN", "shift KP_DOWN")) { scrollBy(step(), 0) }
        bind("page-up", listOf("PAGE_UP", "shift SPACE")) { scrollBy(0, -page(true), page = true) }
        bind("page-down", listOf("PAGE_DOWN", "SPACE")) { scrollBy(0, page(true), page = true) }
        bind("page-left", listOf("shift PAGE_UP")) { scrollBy(-page(false), 0, page = true) }
        bind("page-right", listOf("shift PAGE_DOWN")) { scrollBy(page(false), 0, page = true) }
        bind("top", listOf("HOME")) { scrollBy(0, -Int.MAX_VALUE / 2, edge = true) }
        bind("bottom", listOf("END")) { scrollBy(0, Int.MAX_VALUE / 2, edge = true) }
        bind("start", listOf("ctrl HOME", "meta HOME")) { scrollBy(-Int.MAX_VALUE / 2, -Int.MAX_VALUE / 2, edge = true) }
        bind("end", listOf("ctrl END", "meta END")) { scrollBy(Int.MAX_VALUE / 2, Int.MAX_VALUE / 2, edge = true) }
        bind("escape", listOf("ESCAPE"), { cardFocused || revealed != null || selectionPane != null || focusInViewer() }) {
            exitCardFocus(focusCanvas = true, clearSelection = true)
        }
        // Typed keys follow the keyboard layout, and the numeric keypad types the same characters.
        bind("zoom-in", listOf("typed +", "typed =", "ctrl EQUALS", "meta EQUALS", "ctrl shift EQUALS", "meta shift EQUALS",
            "ctrl PLUS", "meta PLUS", "ctrl ADD", "meta ADD")) { zoomStep(ZOOM_STEP) }
        bind("zoom-out", listOf("typed -", "ctrl MINUS", "meta MINUS", "ctrl SUBTRACT", "meta SUBTRACT")) { zoomStep(1 / ZOOM_STEP) }
        bind("zoom-reset", listOf("typed 0", "ctrl 0", "meta 0", "ctrl NUMPAD0", "meta NUMPAD0")) { glideZoom(1.0) }
        bind("next-card", listOf("TAB")) { stepCard(forward = true) }
        bind("previous-card", listOf("shift TAB")) { stepCard(forward = false) }
    }

    /** Moves to the card after (or before) the current one in opening order, wrapping around through the root file. */
    internal fun stepCard(forward: Boolean) {
        val order = model.cards.map { it.id }.filter { it in views }
        if (order.isEmpty()) return
        val anchor = if (cardFocused) focusedId else current
        val index = order.indexOf(anchor).coerceAtLeast(0)
        val next = order[Math.floorMod(index + if (forward) 1 else -1, order.size)]
        if (next == CANVAS_ROOT) {
            current = CANVAS_ROOT
            refreshTrail()
        }
        moveTo(next)
        content.requestFocusInWindow()
    }

    /** Folds the file and every loaded card's summary, source and chat; again to unfold them. */
    private fun foldAll() {
        val sections = listOf(CardSection.SUMMARY, CardSection.SOURCE, CardSection.CHAT)
        val loaded = model.cards.filter { it.id in loadedIds }
        val fold = !loaded.all { it.folded.containsAll(sections) }
        loaded.map { it.id }.forEach { id -> sections.forEach { model.setFolded(id, it, fold) } }
    }

    private fun showSessions(button: JComponent) {
        val store = services.store
        scope.launch(Dispatchers.EDT) {
            val entries = store?.let { withContext(Dispatchers.IO) { it.list(file.rootPath) } }.orEmpty()
            JPopupMenu().apply {
                add(JMenuItem("New session for this $rootKind").apply {
                    addActionListener { onSwitch(CanvasSession.new(file.rootPath, file.source.name, directory = directory)) }
                })
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
        val opened = session.cards.count { it.kind != CardKind.ROOT }
        val cards = if (opened == 1) "1 card" else "$opened cards"
        val answer = Messages.showYesNoDialog(project, "Delete the canvas session \"${session.title}\" with $cards? Its summaries and chats are lost.",
            "Delete Canvas Session", Messages.getQuestionIcon())
        if (answer != Messages.YES) return
        discard()
    }

    /** Deletes this session without asking and shows the file's next saved session, or a new one. */
    fun discard() {
        val store = services.store ?: return
        val session = model.session
        persist = false
        viewSave.stop()
        scope.launch(Dispatchers.EDT) {
            val next = withContext(Dispatchers.IO) {
                autosave?.flush()
                store.delete(session.id)
                store.list(file.rootPath).firstNotNullOfOrNull { store.load(it.id) }
            }
            onSwitch(next ?: CanvasSession.new(file.rootPath, file.source.name, directory = directory))
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

    internal fun revealInitialLine(line: Int) {
        val target = line.coerceAtLeast(0)
        file.initialLine = null
        if (ready) {
            pendingInitialLine = null
            revealLine(CANVAS_ROOT, target)
        } else {
            pendingInitialLine = target
        }
    }

    private fun restoreView(view: CanvasView, initialLine: Int? = null) {
        zoomTo(view.zoom)
        content.validate()
        if (initialLine != null) revealLine(CANVAS_ROOT, initialLine)
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            scrollTo(view.x + content.origin.x, view.y + content.origin.y)
            content.validate()
            reconcileActiveEditors()
            if (initialLine != null) revealLine(CANVAS_ROOT, initialLine)
        }
    }

    private fun saveView() {
        if (!ready || disposed || !persist) return
        // Relative to the origin, which is not saved: a resumed session grows the canvas again as needed.
        val position = scroll.viewport.viewPosition
        model.setView(CanvasView(zoom, position.x - content.origin.x, position.y - content.origin.y))
    }

    private fun wheel(e: MouseWheelEvent) {
        e.consume()
        if (e.isControlDown || e.isMetaDown) {
            stopGlides()
            wheelZoom = ((wheelZoom ?: zoom) * WHEEL_ZOOM_STEP.pow(-e.preciseWheelRotation)).coerceIn(MIN_ZOOM, MAX_ZOOM)
            wheelAnchor = SwingUtilities.convertPoint(e.component, e.point, scroll.viewport)
            if (!zoomFrame.isRunning) zoomFrame.start()
            return
        }
        val editor = focusedNavigationEditor()
        val horizontal = e.isShiftDown
        if (editor != null && canScrollCanvasViewer(editor, horizontal)) {
            val delta = canvasWheelDelta(e.preciseWheelRotation, editor.lineHeight, WHEEL_ROWS)
            if (delta != 0) scrollCanvasViewer(editor, if (horizontal) delta else 0, if (horizontal) 0 else delta)
            return
        }
        stopGlides()
        // At an edge of the canvas, the wheel goes on past it, growing the canvas, rather than stopping there.
        val rotation = e.preciseWheelRotation
        val at = scroll.viewport.viewPosition
        val extent = scroll.viewport.extentSize
        val size = content.preferredSize
        val atEdge = if (horizontal) (rotation < 0 && at.x <= 0) || (rotation > 0 && at.x + extent.width >= size.width)
            else (rotation < 0 && at.y <= 0) || (rotation > 0 && at.y + extent.height >= size.height)
        if (atEdge && rotation != 0.0) {
            val delta = (rotation * e.scrollAmount.coerceAtLeast(1) * zoomScaled(24, zoom)).roundToInt()
            if (horizontal) scrollTo(at.x.toLong() + delta, at.y) else scrollTo(at.x, at.y.toLong() + delta)
            return
        }
        scroll.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, scroll))
    }

    /** Zooms to [requested], keeping the content under [anchor] (viewport coordinates; the middle by default) in place. */
    internal fun zoomTo(requested: Double, anchor: Point? = null) {
        zoomFrames.stop()
        zoomLevelTo(requested, anchor)
    }

    /** One step of [zoomTo] that leaves a zoom glide running, so the glide's frames can use it. */
    private fun zoomLevelTo(requested: Double, anchor: Point?) {
        zoomFrame.stop()
        wheelZoom = null
        wheelAnchor = null
        val next = requested.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (next == zoom) return
        val viewport = scroll.viewport
        val point = anchor ?: Point(viewport.width / 2, viewport.height / 2)
        val previous = viewport.viewPosition
        val ratio = next / zoom
        val tracked = CanvasZoomAnchor(content, Point(previous.x + point.x, previous.y + point.y))
        scrollFrames.stop()
        content.resetMotion()
        content.scaleOrigin(ratio)
        zoom = next
        zoomLevel.text = "${(zoom * 100).roundToInt()}%"
        applyZoom(content)
        content.revalidate()
        scroll.validate()
        layoutZoomContent(content)
        val destination = tracked.resolve(content, ratio)
        scrollTo(destination.x - point.x, destination.y - point.y)
        content.repaint()
        if (ready && views.size > activeEditorLimit) viewportSync.restart()
    }

    /** Glides the zoom by [factor] from where an ongoing zoom glide is heading, snapping to 100% when a step crosses it. */
    private fun zoomStep(factor: Double) {
        val base = if (zoomFrames.isRunning) zoomTarget else zoom
        val next = (base * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val snapped = if (base != 1.0 && minOf(base, next) < 1.0 && maxOf(base, next) > 1.0) 1.0 else next
        glideZoom(snapped)
    }

    /** Eases the zoom to [requested] around the middle of the view, as [glideTo] does for scrolling; at once off screen. */
    private fun glideZoom(requested: Double) {
        val next = requested.coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (!isShowing) return zoomTo(next)
        zoomFrom = zoom
        zoomTarget = next
        zoomStarted = System.nanoTime()
        zoomFrames.start()
    }

    /** Stops the view's scroll and zoom glides, as the reader takes over with the mouse or scroll bars. */
    private fun stopGlides() {
        scrollFrames.stop()
        zoomFrames.stop()
    }

    private fun applyZoom(component: Component) {
        if (component is CanvasSourcePane || component is CanvasDiffPane) return
        if (component is JComponent) (component.getClientProperty(BASE_FONT) as? Font)?.let { component.font = it.zoomed(zoom) }
        (component as? Container)?.components?.forEach(::applyZoom)
        if (component is CanvasCardView) component.applyZoom()
        if (component is CanvasMoreCard) component.applyZoom(zoom)
    }

    override fun <T : JComponent> zoomFont(component: T, base: Font): T = component.apply {
        putClientProperty(BASE_FONT, base)
        font = base.zoomed(zoom)
    }

    /**
     * Shows the canvas from ([x], [y]) on. Before the canvas's left or top edge, the canvas grows there first, so the
     * reader can pan any way without hitting a wall.
     */
    private fun scrollTo(x: Number, y: Number, animationFrame: Boolean = false) {
        if (!animationFrame) scrollFrames.stop()
        val viewport = scroll.viewport
        var wantedX = x.toLong()
        var wantedY = y.toLong()
        if (wantedX < 0 || wantedY < 0) {
            // Grow in generous steps, so a steady pan does not relayout on every frame.
            fun growth(wanted: Long, extent: Int) = if (wanted >= 0) 0L
                else (-wanted + maxOf(extent, JBUI.scale(CANVAS_MARGIN))).coerceAtMost((Int.MAX_VALUE / 4).toLong())
            val dx = growth(wantedX, viewport.width)
            val dy = growth(wantedY, viewport.height)
            content.growOrigin(dx.toInt(), dy.toInt())
            wantedX += dx
            wantedY += dy
        }
        val left = wantedX.coerceIn(0L, (Int.MAX_VALUE - viewport.width).toLong())
        val top = wantedY.coerceIn(0L, (Int.MAX_VALUE - viewport.height).toLong())
        content.reserve(left + viewport.width, top + viewport.height)
        viewport.viewSize = content.preferredSize
        viewport.viewPosition = Point(left.toInt(), top.toInt())
    }

    private fun drag(e: MouseEvent) {
        if (e.id == MouseEvent.MOUSE_PRESSED) stopGlides()
        if (!SwingUtilities.isDescendingFrom(e.component, content)) return
        val target = if (e.component === content) SwingUtilities.getDeepestComponentAt(content, e.x, e.y) ?: content else e.component
        val ancestors = generateSequence(target) { it.parent }.toList()
        val card = ancestors.filterIsInstance<CanvasCardView>().firstOrNull()
        if (e.isPopupTrigger) {
            val point = SwingUtilities.convertPoint(e.component, e.point, content)
            val underPointer = SwingUtilities.getDeepestComponentAt(content, point.x, point.y) ?: e.component
            val popupAncestors = generateSequence(underPointer) { it.parent }.toList()
            val popupCard = popupAncestors.filterIsInstance<CanvasCardView>().firstOrNull()
            val actions = popupCard?.menuActions()
            if (actions != null && popupAncestors.none { it is CanvasSourcePane || it is CanvasDiffPane || it is JTextComponent }) {
                endDrag()
                setCardFocus(true, popupCard.id)
                if (popupCard.id != CANVAS_ROOT) select(popupCard.id)
                canvasActionMenu(actions).show(e.component, e.x, e.y)
                return
            }
        }
        if (e.id == MouseEvent.MOUSE_PRESSED && target === selectionButton) return
        if (e.id == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e)) {
            if (card != null) setCardFocus(true, card.id) else exitCardFocus(focusCanvas = false)
        }
        // Clicking into a card makes it the one the trail leads to; the root file keeps the trail.
        if (e.id == MouseEvent.MOUSE_PRESSED) card?.takeIf { it.id != CANVAS_ROOT }?.let { select(it.id) }
        when (e.id) {
            MouseEvent.MOUSE_PRESSED -> if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                panned = false
                // A press on a card's edge resizes the card; it focuses the card above but starts no pan.
                if (card?.resize?.claims(e) == true) return
                // Source text, chat text and buttons keep their own selection and clicks.
                if (SwingUtilities.isLeftMouseButton(e) && ancestors
                        .any { it is CanvasSourcePane || it is CanvasDiffPane || it is JTextComponent || it is AbstractButton || it is ActionLink }) return
                dragStart = e.locationOnScreen
                dragView = scroll.viewport.viewPosition.also { it.translate(-content.origin.x, -content.origin.y) }
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
                panBy(view, start, now)
            }
            MouseEvent.MOUSE_RELEASED -> endDrag()
        }
    }

    /** Pans so the canvas follows the pointer from [start] to [now] (screen), [view] being the view's start relative to the origin. */
    private fun panBy(view: Point, start: Point, now: Point) {
        val origin = content.origin
        scrollTo(view.x.toLong() + origin.x + start.x - now.x, view.y.toLong() + origin.y + start.y - now.y)
    }

    /**
     * Alt + left drag pans the canvas from anywhere, cards and source text included. It runs ahead of the components
     * under the pointer, so text keeps no selection and editors start no column selection.
     */
    private fun altPan(event: AWTEvent): Boolean {
        val e = event as? MouseEvent ?: return false
        if (swallowClick && e !is MouseWheelEvent) {
            // The click that Swing may send after the release ends the gesture too.
            swallowClick = false
            if (e.id == MouseEvent.MOUSE_CLICKED) {
                e.consume()
                return true
            }
        }
        if (!altPanning) {
            if (e.id != MouseEvent.MOUSE_PRESSED || !e.isAltDown || !SwingUtilities.isLeftMouseButton(e)) return false
            if (!ready || disposed) return false
            val component = e.component ?: return false
            if (!SwingUtilities.isDescendingFrom(component, content)) return false
            val inView = SwingUtilities.convertPoint(component, e.point, scroll.viewport)
            if (!Rectangle(scroll.viewport.size).contains(inView)) return false
            stopGlides()
            altPanning = true
            dragStart = e.locationOnScreen
            dragView = scroll.viewport.viewPosition.also { it.translate(-content.origin.x, -content.origin.y) }
            dragCursorOwner = component.also { owner ->
                dragCursor = owner.cursor.takeIf { owner.isCursorSet }
                owner.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            }
            e.consume()
            return true
        }
        when (e.id) {
            MouseEvent.MOUSE_DRAGGED -> {
                val start = dragStart
                val view = dragView
                if (start != null && view != null) panBy(view, start, e.locationOnScreen)
            }
            MouseEvent.MOUSE_RELEASED -> if (SwingUtilities.isLeftMouseButton(e)) {
                endDrag()
                swallowClick = true
            }
            MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_CLICKED -> Unit
            else -> return false
        }
        e.consume()
        return true
    }

    private fun endDrag() {
        altPanning = false
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
        // The trail sits left of the controls; too narrow for both on one row, it drops below them.
        val trailScroll = trailBar.getComponent(0) as JScrollPane
        val trailPreferred = breadcrumbs.preferredSize.width + JBUI.scale(16)
        val roomForTrail = view.width - size.width - 3 * gap
        val sameRow = roomForTrail >= JBUI.scale(MIN_TRAIL_WIDTH)
        val trailWidth = minOf(trailPreferred, maxOf(if (sameRow) roomForTrail else view.width - 2 * gap, 0))
        val trailHeight = breadcrumbs.preferredSize.height + JBUI.scale(8) +
            if (trailPreferred > trailWidth) trailScroll.horizontalScrollBar.preferredSize.height else 0
        trailBar.setBounds(view.x + gap, view.y + gap + if (sameRow) 0 else size.height + gap, trailWidth, trailHeight)
    }

    override fun dispose() {
        if (disposed) return
        if (ready && persist) {
            viewSave.stop()
            saveView()
        }
        disposed = true
        loadMoreJob?.cancel()
        loadMoreJob = null
        viewportSync.stop()
        zoomFrame.stop()
        content.stopMotion()
        stopGlides()
        viewSave.stop()
        unreveal.stop()
        mouseEvents.dispose()
        Disposer.dispose(altPanDispatcher)
        endDrag()
        subscription.dispose()
        views.values.forEach(Disposer::dispose)
        views.clear()
        moreViews.clear()
        if (persist) autosave?.flush()
        scope.cancel()
        Disposer.dispose(controller)
    }

    /** The scrollable canvas: lays out the root file and the cards, and draws the arrows between them. */
    private inner class Canvas : JPanel(null), Scrollable {
        private val space = Dimension()
        private var extent = Dimension()
        private var placements: Map<String, CanvasPlacement> = emptyMap()
        private val motion = CanvasMotion()
        val entering = HashSet<String>()
        /**
         * Where the layout's (0, 0) sits in the canvas. It grows when the reader pans past the left or top edge, so
         * the canvas has no wall there; layouts, motion and saved views stay relative to it.
         */
        val origin = Point()
        fun destination(id: String) = placements[id]?.let { CanvasPlacement(it.x + origin.x, it.y + origin.y) }

        /** Moves everything [dx], [dy] further from the top left, adding that much space before it. */
        fun growOrigin(dx: Int, dy: Int) {
            if (dx <= 0 && dy <= 0) return
            val x = maxOf(dx, 0)
            val y = maxOf(dy, 0)
            origin.translate(x, y)
            space.setSize((space.width.toLong() + x).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                (space.height.toLong() + y).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            extent = Dimension(extent.width + x, extent.height + y)
            components.forEach { it.setLocation(it.x + x, it.y + y) }
            revalidate()
            repaint()
        }

        /** Keeps the origin at the same content after a zoom by [ratio]. */
        fun scaleOrigin(ratio: Double) {
            origin.setLocation((origin.x * ratio).roundToInt(), (origin.y * ratio).roundToInt())
        }
        private var layoutZoom = zoom
        private val frames = Timer(16) { animateFrame() }

        fun stopMotion() { frames.stop() }

        fun resetMotion() {
            frames.stop()
            placements = emptyMap()
            motion.clear()
        }

        private fun animateFrame() {
            val now = System.nanoTime()
            for ((id, view) in views) {
                if (id !in placements) continue
                val at = motion.position(id, now)
                view.setLocation(at.x + origin.x, at.y + origin.y)
                view.appearance = motion.opacity(id, now)
            }
            for ((id, stub) in moreViews) {
                if (id !in placements) continue
                val at = motion.position(id, now)
                stub.setLocation(at.x + origin.x, at.y + origin.y)
            }
            repaint()
            if (!motion.active(now)) frames.stop()
        }

        init {
            background = CANVAS_BACKGROUND
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
                it.setBounds(origin.x + margin, origin.y + top, size.width, size.height)
                right = origin.x + margin + size.width
                bottom = origin.y + top + size.height
            }
            val root = rootView
            if (root != null) {
                // The root stays in its place; the cards opened from it follow in columns to its right.
                root.layOut()
                root.setLocation(origin.x + margin, origin.y + top)
                right = maxOf(right, root.x + root.width)
                bottom = maxOf(bottom, root.y + root.height)
                val cardItems = model.cards.mapNotNull { card ->
                    val parentId = card.parentId ?: return@mapNotNull null
                    val view = views[card.id] ?: return@mapNotNull null
                    view.layOut()
                    val anchor = (views[parentId] ?: root).anchorY(card.anchor)
                    anchorYs[card.id] = anchor
                    CanvasLayoutItem(card.id, parentId.takeIf { it in views && it != CANVAS_ROOT }, anchor, view.width, view.height)
                }
                val stubItems = moreViews.values.mapNotNull { stub ->
                    val parentId = stub.branch.parentId
                    val parentView = views[parentId] ?: return@mapNotNull null
                    stub.layOut(zoom)
                    val anchor = maxOf(parentView.height, 0)
                    CanvasLayoutItem(stub.branch.id, parentId.takeIf { it != CANVAS_ROOT }, anchor, stub.width, stub.height)
                }
                val items = cardItems + stubItems
                val animate = isShowing && layoutZoom == zoom
                layoutZoom = zoom
                val placed = canvasLayout(items, root.width, CanvasLayoutOptions(left = margin, top = top,
                    arrowGap = zoomScaled(64, zoom), gap = zoomScaled(14, zoom), anchorLift = zoomScaled(12, zoom)))
                val now = System.nanoTime()
                motion.retain(placed.keys)
                for (item in items) {
                    val at = placed[item.id] ?: continue
                    // A card being resized follows the pointer at once; the cards it moves may glide.
                    val view = views[item.id]
                    if (view != null) {
                        val glide = animate && !view.resizing && (item.id in placements || item.id in entering)
                        motion.target(item.id, at, now, glide, zoomScaled(12, zoom))
                        val shown = motion.position(item.id, now)
                        view.setBounds(shown.x + origin.x, shown.y + origin.y, item.width, item.height)
                        view.appearance = motion.opacity(item.id, now)
                    } else {
                        val stub = moreViews[item.id] ?: continue
                        val glide = animate && item.id in placements
                        motion.target(item.id, at, now, glide, zoomScaled(12, zoom))
                        val shown = motion.position(item.id, now)
                        stub.setBounds(shown.x + origin.x, shown.y + origin.y, item.width, item.height)
                    }
                    right = maxOf(right, at.x + origin.x + item.width)
                    bottom = maxOf(bottom, at.y + origin.y + item.height)
                }
                entering.clear()
                placements = placed
                if (motion.active(now) && !frames.isRunning) frames.start()
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
                    if (card.parentId == null || card.isDirectorySeed) continue
                    val view = views[card.id] ?: continue
                    val parent: JComponent = card.parentId?.let(views::get) ?: root
                    val anchor = anchorYs[card.id] ?: continue
                    g2.composite = java.awt.AlphaComposite.SrcOver.derive(view.appearance)
                    arrow(g2, Point(parent.x + parent.width, parent.y + anchor), Point(view.x, view.y + view.headerMiddle()),
                        if (card.id == revealed || (cardFocused && card.id == focusedId)) ARROW_ACTIVE else ARROW)
                }
                g2.stroke = BasicStroke(
                    zoomScaled(2, zoom).toFloat() * 0.75f,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER,
                    10f,
                    floatArrayOf(zoomScaled(6, zoom).toFloat().coerceAtLeast(2f), zoomScaled(4, zoom).toFloat().coerceAtLeast(2f)),
                    0f,
                )
                g2.composite = java.awt.AlphaComposite.SrcOver
                for (stub in moreViews.values) {
                    if (stub.branch.parentId == CANVAS_ROOT && stub.branch.isDirectoryFiles) continue
                    val parent: JComponent = views[stub.branch.parentId] ?: continue
                    val anchorY = (parent.height - zoomScaled(16, zoom)).coerceAtLeast(
                        (parent as? CanvasCardView)?.headerMiddle() ?: zoomScaled(12, zoom),
                    )
                    arrow(g2, Point(parent.x + parent.width, parent.y + anchorY), Point(stub.x, stub.y + stub.height / 2), ARROW)
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
        /** How far the line a declaration's name is on may be from where the graph places the declaration. */
        const val DECLARATION_LINE_SLACK = 5
        val LOG = logger<CanvasSessionPanel>()
        val ARROW = JBColor(Color(130, 150, 200), Color(110, 130, 175))
        val ARROW_ACTIVE = JBColor(Color(76, 112, 220), Color(110, 150, 240))
        const val MIN_ZOOM = 0.05
        const val MAX_ZOOM = 3.0
        const val ZOOM_STEP = 1.2
        const val WHEEL_ZOOM_STEP = 1.15
        const val DRAG_THRESHOLD = 3
        const val CANVAS_MARGIN = 512
        const val FLOATING_GAP = 6
        const val FLOATING_ROW_SPACE = 34
        const val MIN_TRAIL_WIDTH = 220
        const val KEY_SCROLL_STEP = 72
        const val WHEEL_ROWS = 3
        /** How long the view takes to glide to a card, line or zoom: long enough to follow, short enough not to wait. */
        const val VIEW_GLIDE_NANOS = 320_000_000L
        const val BASE_FONT = "insight.canvas.baseFont"
    }
}
