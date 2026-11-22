package dev.insight.review.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.messages.MessageBusConnection
import com.intellij.openapi.wm.StatusBar
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.context.showContextPicker
import dev.insight.links.ProjectSymbols
import dev.insight.links.SymbolCandidate
import dev.insight.links.SymbolHints
import dev.insight.links.SymbolLinkNavigator
import dev.insight.links.SymbolMention
import dev.insight.links.SymbolNames
import dev.insight.review.RenderedReport
import dev.insight.review.ReportLink
import dev.insight.review.ReportProjectIndex
import dev.insight.review.ReportTargetKind
import dev.insight.review.ReportThemeColors
import dev.insight.review.linkTooltip
import dev.insight.review.parseReportLinkHref
import dev.insight.review.renderMarkdownReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent
import javax.swing.text.AttributeSet
import javax.swing.text.DefaultCaret
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLDocument

private val LOG = logger<LinkedTextLinks>()

/** [rendered] text and the [index] its file links were resolved against. */
internal data class LinkedText(val rendered: RenderedReport, val index: ReportProjectIndex)

/**
 * The project files and declared names that generated text, such as summaries and chat replies, links to, the way the
 * report viewer links a report. One per project, shared by every [LinkedTextPane]. While any pane is shown it is brought
 * up to date in the background when files are added, moved or removed or declarations change, after which the panes
 * render again. Rendering runs off the EDT; texts shown again come from a cache.
 */
@Service(Service.Level.PROJECT)
internal class LinkedTextLinks(private val project: Project, private val scope: CoroutineScope) {
    private var index = ReportProjectIndex()
    private var symbols: SymbolNames = SymbolNames.NONE
    private var indexedAt = -1L
    private var checkedAt = 0L
    private var job: Job? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val cache = object : LinkedHashMap<Pair<String, ReportThemeColors>, LinkedText>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, ReportThemeColors>, LinkedText>) = size > MAX_CACHED
    }
    /** Renderings under way, shared by panes showing the same text; all against the current [index] and [symbols]. */
    private val pending = HashMap<Pair<String, ReportThemeColors>, Deferred<LinkedText>>()
    private var watching: MessageBusConnection? = null
    private val refreshQueued = AtomicBoolean()
    @Volatile private var changedAt = 0L

    /** [markdown] as it links now if it was rendered already, else null; on the EDT. */
    fun cached(markdown: String): LinkedText? {
        refresh()
        return cache[markdown to reportThemeColors()]
    }

    /** [markdown] rendered with links; call on the EDT, the rendering itself runs in the background. */
    suspend fun render(markdown: String): LinkedText {
        val key = markdown to reportThemeColors()
        cache[key]?.let { return it }
        return pending.getOrPut(key) { startRendering(key) }.await()
    }

    /** Renders [key]'s text in the background, caching it unless the links changed meanwhile. EDT. */
    private fun startRendering(key: Pair<String, ReportThemeColors>): Deferred<LinkedText> {
        val linkedIndex = index
        val names = symbols
        lateinit var rendering: Deferred<LinkedText>
        rendering = scope.async(Dispatchers.EDT, start = CoroutineStart.LAZY) {
            try {
                val html = withContext(Dispatchers.Default) {
                    renderMarkdownReport(key.first, linkedIndex, key.second, mapSource = false, symbols = names, embedded = true)
                }
                LinkedText(html, linkedIndex).also { if (pending[key] === rendering) cache[key] = it }
            } finally {
                if (pending[key] === rendering) pending.remove(key)
            }
        }
        return rendering
    }

    /** Calls [listener] on the EDT whenever texts link differently, until it is removed. EDT. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
        watch()
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
        if (listeners.isEmpty()) {
            watching?.disconnect()
            watching = null
        }
    }

    /** Refreshes after project changes that may link texts differently, for as long as any pane is shown. */
    private fun watch() {
        if (watching != null || project.isDisposed) return
        watching = project.messageBus.connect().also { connection ->
            connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it !is VFileContentChangeEvent }) scheduleRefresh()
                }
            })
            connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { scheduleRefresh() })
            connection.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
                override fun exitDumbMode() = scheduleRefresh()
            })
        }
    }

    /** Refreshes once changes have been quiet for a moment, so typing does not collect names on every keystroke. Any thread. */
    private fun scheduleRefresh() {
        changedAt = System.currentTimeMillis()
        if (project.isDisposed || !refreshQueued.compareAndSet(false, true)) return
        scope.launch(Dispatchers.EDT) {
            try {
                while (true) {
                    val now = System.currentTimeMillis()
                    val wait = maxOf(changedAt + QUIET_MS - now, checkedAt + REFRESH_INTERVAL_MS - now)
                    if (wait <= 0) break
                    delay(wait)
                }
            } finally {
                refreshQueued.set(false)
            }
            job?.join()
            if (listeners.isNotEmpty()) refresh(throttle = false)
        }
    }

    private fun refresh(throttle: Boolean = true) {
        val now = System.currentTimeMillis()
        if (job?.isActive == true || project.isDisposed || (throttle && now - checkedAt < REFRESH_INTERVAL_MS)) return
        checkedAt = now
        job = scope.launch(Dispatchers.EDT) {
            try {
                val stamp = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount
                if (stamp != indexedAt) {
                    val loaded = withContext(Dispatchers.Default) { collectProjectReportSnapshot(project, emptyList()).index }
                    indexedAt = stamp
                    publish(loaded, symbols)
                }
                // Declared names wait for indexing, so files link before names do.
                val names = ProjectSymbols.names(project)
                if (names !== symbols) publish(index, names)
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Could not collect the project files and names that text links to", e)
            }
        }
    }

    private fun publish(nextIndex: ReportProjectIndex, nextSymbols: SymbolNames) {
        if (project.isDisposed) return
        index = nextIndex
        symbols = nextSymbols
        cache.clear()
        // Renderings under way link the old way; panes waiting for them render again through their listeners.
        pending.values.forEach { it.cancel() }
        pending.clear()
        listeners.forEach { it() }
    }

    private companion object {
        const val MAX_CACHED = 128
        const val REFRESH_INTERVAL_MS = 3_000L
        const val QUIET_MS = 1_000L
    }
}

/**
 * Where a [LinkedTextPane]'s links lead in the view showing it, such as the canvas keeping the reader on its cards.
 * Without one, links open in the editor.
 */
internal interface LinkedTextNavigator {
    /** Project-relative paths of the files the text is about, most relevant first; ranks the declarations a symbol names. */
    fun contextFiles(): List<String> = emptyList()

    /** The declaration among [candidates] (ranked, most likely first) the view already shows, followed without asking. */
    fun shown(candidates: List<SymbolCandidate>): SymbolCandidate? = null

    /** Follows a file link to project-relative [path] (at absolute [absolutePath] when known), at 1-based [lines]. */
    fun openFile(path: String, absolutePath: String?, lines: IntRange?, column: Int?)

    /** Follows a symbol link to [declaration], the one [mention] names. */
    fun openDeclaration(declaration: SymbolCandidate, mention: SymbolMention)
}

/**
 * Read-only Markdown text whose file paths, line references, URLs and code symbols link the way they do in the report viewer:
 * a click opens the file or declaration in the editor (listing the candidates when several match), Shift+click or right-click
 * on a link offers its [Context picker][showContextPicker]. A [navigator] can make links lead elsewhere in the view, in which
 * case Ctrl/Cmd+click still opens the editor. The text can still be selected and copied. Its height follows its
 * width, so it suits wrapping layouts; [onRendered] runs when a text finishes rendering in the background or the project's
 * files or names change how it links while it is shown, as its height may have changed with them.
 */
internal class LinkedTextPane(private val project: Project, private val scope: CoroutineScope) : JEditorPane() {
    private val links = project.service<LinkedTextLinks>()
    private var shown: LinkedText? = null
    private var rendering: Job? = null
    private val linksChanged: () -> Unit = { showMarkdown(notify = true) }
    var onRendered: (() -> Unit)? = null
    /** Where links lead; null opens them in the editor. */
    var navigator: LinkedTextNavigator? = null

    /** The Markdown shown. */
    var markdown: String = ""
        set(value) {
            if (field == value && (shown != null || rendering?.isActive == true)) return
            field = value
            showMarkdown(notify = false)
        }

    init {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        margin = JBUI.emptyInsets()
        font = UIUtil.getLabelFont()
        foreground = UIUtil.getLabelForeground()
        putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        // Setting the text must never scroll the canvas or view around the pane.
        (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.NEVER_UPDATE
        addHyperlinkListener { event ->
            val link = parseReportLinkHref(event.description)?.let { shown?.rendered?.links?.getOrNull(it) } ?: return@addHyperlinkListener
            val mouse = event.inputEvent as? MouseEvent
            when (event.eventType) {
                HyperlinkEvent.EventType.ENTERED -> toolTipText = linkTooltip(link, inReviewScope = false, isDiffReview = false)
                HyperlinkEvent.EventType.EXITED -> toolTipText = null
                HyperlinkEvent.EventType.ACTIVATED -> activate(link, mouse?.point ?: Point(width / 2, height / 2), mouse?.isShiftDown == true,
                    inEditor = mouse != null && (mouse.isControlDown || mouse.isMetaDown))
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val link = linkAt(e.point) ?: return
                e.consume()
                showContext(link, e.point)
            }
        })
    }

    override fun addNotify() {
        super.addNotify()
        links.addListener(linksChanged)
        showMarkdown(notify = false)
    }

    override fun removeNotify() {
        links.removeListener(linksChanged)
        rendering?.cancel()
        rendering = null
        super.removeNotify()
    }

    /**
     * Draws [markdown] as it links now: at once when it was rendered already (calling [onRendered] on a change when [notify]),
     * otherwise once rendered in the background, then calling [onRendered]. A text changing while it renders, as a streamed reply
     * does, shows each finished rendering on the way to the latest.
     */
    private fun showMarkdown(notify: Boolean) {
        links.cached(markdown)?.let { if (draw(it) && notify) onRendered?.invoke(); return }
        if (rendering?.isActive == true) return
        rendering = scope.launch(Dispatchers.EDT) {
            while (true) {
                val wanted = markdown
                val next = try {
                    links.render(wanted)
                } catch (e: CancellationException) {
                    // A rendering the links outdated was dropped; render again unless this pane stopped.
                    currentCoroutineContext().ensureActive()
                    continue
                } catch (e: Throwable) {
                    if (e is ControlFlowException) throw e
                    LOG.warn("Could not render linked text", e)
                    break
                }
                if (draw(next)) onRendered?.invoke()
                if (wanted == markdown && links.cached(wanted) === next) break
            }
        }
    }

    /** Shows [next] unless it is shown already; returns whether it changed. */
    private fun draw(next: LinkedText): Boolean {
        if (next === shown) return false
        shown = next
        text = next.rendered.html
        revalidate()
        repaint()
        return true
    }

    /** Follows [link] through the [navigator], or in the editor when there is none or [inEditor] asks for it. */
    private fun activate(link: ReportLink, point: Point, shift: Boolean, inEditor: Boolean = false) {
        val index = shown?.index ?: return
        val navigator = navigator.takeUnless { inEditor }
        when {
            link.kind == ReportTargetKind.EXTERNAL_URL -> link.externalUrl?.let(BrowserUtil::browse)
            shift || link.kind == ReportTargetKind.DIRECTORY -> showContext(link, point)
            link.kind == ReportTargetKind.SYMBOL -> followSymbol(link, point, navigator) { declaration ->
                val mention = link.symbol
                if (navigator != null && mention != null) navigator.openDeclaration(declaration, mention)
                else openReportFile(project, declaration.path, declaration.line..declaration.line, declaration.column, declaration.absolutePath)
            }
            else -> choosePath(link, point, "Open matching file…") { path ->
                val absolute = index.absolutePathFor(path)
                if (navigator != null) navigator.openFile(path, absolute, link.lines, link.column)
                else openReportFile(project, path, link.lines, link.column, absolutePath = absolute)
            }
        }
    }

    private fun showContext(link: ReportLink, point: Point) {
        val index = shown?.index ?: return
        when (link.kind) {
            ReportTargetKind.EXTERNAL_URL -> return
            // A symbol's context is the file and line of the declaration it names.
            ReportTargetKind.SYMBOL -> followSymbol(link, point) { declaration ->
                showContext(ReportLink(link.id, link.displayText, ReportTargetKind.FILE, listOf(declaration.path),
                    declaration.line..declaration.line), point)
            }
            ReportTargetKind.FILE, ReportTargetKind.DIRECTORY -> {
                val title = if (link.kind == ReportTargetKind.DIRECTORY) "Choose matching directory…" else "Choose matching file…"
                choosePath(link, point, title) { path ->
                    val absolute = if (link.kind == ReportTargetKind.FILE) index.absolutePathFor(path) else index.absoluteDirPathFor(path)
                    val pick = reportLinkContextPick(project, link.kind, path, link.lines, reviewFiles = emptyList(), summaryTree = null,
                        reviewDestinations = emptyMap(), summaryActions = null, openOnWall = null, openOnCanvas = null,
                        absolutePath = absolute) ?: return@choosePath
                    showContextPicker(project, pick, this, RelativePoint(this, point))
                }
            }
        }
    }

    /**
     * Looks up the declarations [link] names, ranked first by the files [navigator] says the text is about, and opens the
     * only one, the one the view already shows, or the one the reader picks.
     */
    private fun followSymbol(link: ReportLink, point: Point, navigator: LinkedTextNavigator? = this.navigator,
        open: (SymbolCandidate) -> Unit) {
        val mention = link.symbol ?: return
        val index = shown?.index
        val hints = link.symbolHints ?: SymbolHints()
        SymbolLinkNavigator.follow(project, scope, mention,
            navigator?.let { hints.copy(files = (it.contextFiles() + hints.files).distinct()) } ?: hints,
            at = { RelativePoint(this, point) },
            notice = { StatusBar.Info.set(it, project) },
            relativePath = { index?.pathForAbsolute(it.absolutePath) ?: it.path },
            preferred = { navigator?.shown(it) },
            open = open)
    }

    private fun choosePath(link: ReportLink, point: Point, title: String, onChosen: (String) -> Unit) {
        if (!link.isAmbiguous) {
            link.primaryPath?.let(onChosen)
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(link.candidates)
            .setTitle(title)
            .setItemChosenCallback(onChosen)
            .createPopup()
            .show(RelativePoint(this, point))
    }

    private fun linkAt(point: Point): ReportLink? {
        val offset = viewToModel2D(point).takeIf { it >= 0 } ?: return null
        val document = document as? HTMLDocument ?: return null
        val element = document.getCharacterElement(offset.coerceIn(0, maxOf(0, document.length - 1)))
        val anchor = element?.attributes?.getAttribute(HTML.Tag.A) as? AttributeSet ?: return null
        val id = parseReportLinkHref(anchor.getAttribute(HTML.Attribute.HREF) as? String) ?: return null
        return shown?.rendered?.links?.getOrNull(id)
    }
}
