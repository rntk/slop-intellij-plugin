package dev.insight.canvas

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportRawProgress
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ui.LinkedTextNavigator
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomUnscaled
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a card needs from the canvas it sits on. */
internal interface CanvasCardHost {
    val project: Project
    val model: CanvasSessionModel
    val controller: CanvasSessionController
    val scope: CoroutineScope
    val services: CanvasSessionServices
    /** What the cards' chats may do on this canvas. */
    val actions: CanvasActions
    val sourceListener: CanvasSourcePane.Listener
    /** Unified patches by absolute file path, for files opened from a diff; empty otherwise. */
    val diffs: Map<String, String>
    val zoom: Double
    /** Shows [component] in [base] (its own font by default) at the current zoom, now and after each zoom change. */
    fun <T : JComponent> zoomFont(component: T, base: Font = component.font): T
    /** The card's size may have changed: lay out the canvas again. */
    fun cardResized()
    fun close(cardId: String)
    fun expandToFile(cardId: String)
    fun openSideBySide(cardId: String)
    fun attachFile(cardId: String)
    /** Opens the whole [file] from [cardId]'s usage list, listing its usage sites of [targetName] on that card. */
    fun openUsageFile(cardId: String, file: CanvasUsageFile, targetName: String)
    /** Scrolls card [cardId]'s source to 0-based document [line] and brings it into view. */
    fun revealLine(cardId: String, line: Int)
    /** Where links in [cardId]'s summary and chats lead: to the code on the canvas, the card itself first. */
    fun textNavigator(cardId: String): LinkedTextNavigator
    /** Summarizes the gaps of [cardId]'s source that have no summary yet. */
    fun summarizeGaps(cardId: String)
    /** Whether the gaps of [cardId] are being summarized. */
    fun summarizingGaps(cardId: String): Boolean
    fun navigate(source: CanvasSource?, path: String, line: Int)
    /** Moves keyboard focus back to the canvas surface. */
    fun focusCanvas()
    /** Anchors of open children inside [cardId]'s source, for highlighting and hibernation anchor caching. */
    fun childAnchors(cardId: String): List<CardAnchor> = model.children(cardId).filterNot { it.isDirectorySeed }.map { it.anchor }
}

/** The outline of a card at rest, clearly set off from [CANVAS_BACKGROUND]. */
internal val CANVAS_CARD_BORDER = JBColor(Color(0xA4, 0xAA, 0xB6), Color(0x6A, 0x6F, 0x78))

/** The canvas behind the cards, a shade apart from the panel background the cards are drawn on. */
internal val CANVAS_BACKGROUND = JBColor(Color(0xE9, 0xEB, 0xEF), Color(0x1A, 0x1B, 0x1E))

/** A card's width at zoom 1: the default of the cards opened on the canvas. */
internal const val CARD_WIDTH = 1030
/** The tallest a card's source or diff grows at zoom 1 before it scrolls within the card. */
internal const val CARD_SOURCE_MAX_HEIGHT = 640
/** The root card's widest and narrowest width at zoom 1, its source following the file's longest lines between them. */
internal const val ROOT_MAX_WIDTH = 1267
internal const val ROOT_CHAT_MIN_WIDTH = 576

/** How wide a card is laid out on the canvas, at zoom 1. */
internal sealed interface CanvasCardWidth {
    /** Always [width]. */
    data class Fixed(val width: Int) : CanvasCardWidth
    /** As wide as its source and header want, at least [min] (room to chat) and with source lines cut to [max]. */
    data class Content(val min: Int, val max: Int) : CanvasCardWidth

    companion object {
        /** The root card follows its file's width; other cards share one width, so columns line up. */
        fun of(card: CanvasCard): CanvasCardWidth =
            if (card.kind == CardKind.ROOT) Content(ROOT_CHAT_MIN_WIDTH, ROOT_MAX_WIDTH) else Fixed(CARD_WIDTH)
    }
}

/**
 * One card on the canvas: a header with the card's kind, name and place, then its summary, source (with a Diff tab for
 * a file opened from a diff), chat history and chat input, each folded with its toggle as saved in the card. The card's
 * chat keeps its history in the model. The [CardKind.ROOT] card shows the root file (or lists a directory's files) and
 * cannot be closed.
 */
internal class CanvasCardView(
    private val host: CanvasCardHost,
    initial: CanvasCard,
    materializeSource: Boolean = true,
) : JPanel(StackLayout { zoomScaled(4, host.zoom) }), Disposable {
    var appearance = 1f
    private val isRoot = initial.kind == CardKind.ROOT
    /** How [layOut] sizes the card. */
    val widthPolicy: CanvasCardWidth = CanvasCardWidth.of(initial)
    /** Resizes the card by its right and bottom edges. */
    val resize = CanvasCardResize(this, host)
    /** Whether the reader is dragging one of the card's edges. */
    val resizing: Boolean get() = resize.active

    override fun paint(g: Graphics) {
        val copy = g.create() as Graphics2D
        try {
            copy.composite = AlphaComposite.SrcOver.derive(appearance)
            super.paint(copy)
            paintCanvasResizeGrip(copy, width, height, host.zoom)
        } finally {
            copy.dispose()
        }
    }

    val id = initial.id
    var card: CanvasCard = initial
        private set
    private var rendered = false
    var source: CanvasSource? = host.controller.source(id)
        private set
    private val initialHibernated = !materializeSource && !isRoot && source != null && source!!.text.isNotEmpty() &&
        !(initial.kind == CardKind.ATTACHMENT && initial.attachment?.imageBase64 != null)
    /** Whether this card's heavy `EditorEx` viewer is currently paused off-screen. */
    var hibernated: Boolean = initialHibernated
        private set
    private var hibernatedBaseHeight: Int = if (initialHibernated) estimateBaseSourceHeight(source!!.text) else 0
    private var savedScrollX = 0
    private var savedScrollY = 0
    private var pendingScrollOffset: Int? = null
    private var pendingScrollLine: Int? = null
    private var cachedAnchorYs: Map<Int, Int> = emptyMap()
    private var wasShowingDiff = false
    private val hibernatedPlaceholder = HibernatedSourcePlaceholder()
    /** The card's source viewer; null when it has no source text or is hibernated. */
    var sourcePane: CanvasSourcePane? = if (!initialHibernated) {
        source?.takeIf { it.text.isNotEmpty() }?.let { CanvasSourcePane(host.project, id, it, host.sourceListener) }
    } else null
        private set
    /** Whether this card currently owns keyboard and wheel navigation. */
    var focused = false
        set(value) {
            if (field == value) return
            field = value
            updateBorder()
            repaint()
        }
    /** Whether the card is drawn emphasised, after it was revealed. */
    var highlighted = false
        set(value) {
            if (field == value) return
            field = value
            updateBorder()
            repaint()
        }

    private val metaLabel = host.zoomFont(JLabel().apply { foreground = UIUtil.getContextHelpForeground() })
    private val notice = host.zoomFont(wrappedText(source?.notice.orEmpty()).apply {
        foreground = NOTICE
        isVisible = !source?.notice.isNullOrBlank()
    })
    private val meta = JPanel(StackLayout { scaled(2) }).apply {
        isOpaque = false
        add(metaLabel)
        add(notice)
    }

    private val summaryText = host.linkedSummaryText(initial.id)
    private val summaryStatus = host.zoomFont(JLabel().apply { foreground = UIUtil.getContextHelpForeground() })
    private val summaryRetry = host.zoomFont(ActionLink("Summarize") { requestSummary() })
    private var summaryJob: Job? = null
    private var renderedSummaryPending = false
    private val summary = CanvasFoldSection(host, id, CardSection.SUMMARY, "Summary", JPanel(StackLayout { scaled(2) }).apply {
        isOpaque = false
        add(summaryText)
        add(summaryStatus)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(summaryRetry) })
    })

    private val usageSitesBody = JPanel(StackLayout { scaled(2) }).apply {
        name = "canvas-usage-sites"
        isOpaque = false
    }
    private var shownSites: CardUsages? = null
    private val usageSites = CanvasFoldSection(host, id, CardSection.USAGES, "Usages", usageSitesBody).apply {
        isVisible = false
        addToHeader(host.zoomFont(ActionLink("Clear") { host.model.setUsages(id, null) }.apply {
            name = "canvas-clear-usage-sites"
            toolTipText = "Remove this list of usage sites from the card"
        }))
    }

    private val sourceBody = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(when {
            initialHibernated -> hibernatedPlaceholder
            sourcePane != null -> sourcePane
            else -> emptySourceLabel(initial)
        })
    }
    /** The changed hunks within the card's source; null when it shows no change from a diff. */
    var diffPane: CanvasDiffPane? = null
        private set
    private val sourceSection = CanvasSourceSection(host, id, canvasSourceTitle(initial), sourceBody)
    private val titleLabel = host.zoomFont(CanvasTitleLabel(card.title, icon(card)),
        UIUtil.getLabelFont().deriveFont(Font.BOLD))
    private val openedByLabel = host.zoomFont(JLabel("LLM").apply {
        name = "canvas-opened-by-llm"
        toolTipText = "Opened by the LLM through chat tools"
        foreground = ACCENT
        isVisible = card.openedBy == CardOpenedBy.LLM
    }, JBUI.Fonts.smallFont().deriveFont(Font.BOLD))
    private var usageSearch: Job? = null
    private var usagesLoaded = false
    internal val usages = JPanel(StackLayout { scaled(2) }).apply {
        name = "canvas-usages"
        isOpaque = false
        isVisible = false
    }
    val canFindUsages: Boolean
        get() = card.kind == CardKind.SYMBOL && !card.wholeFile && host.controller.canFindUsages(source?.node)
    private val canExpand: Boolean
        get() = card.kind != CardKind.ATTACHMENT && !card.wholeFile && source?.file != null && !source?.text.isNullOrEmpty() &&
            card.symbol?.kind != "FILE"
    internal val header = CanvasCardHeader(host, id, titleLabel, openedByLabel, ::menuActions,
        "Close this card and the cards opened from it", if (isRoot) null else ({ host.close(id) }))

    // The history section is created first, so the chat view can name it as messages arrive.
    private val historyBody = JPanel(BorderLayout()).apply { isOpaque = false }
    private val history = CanvasFoldSection(host, id, CardSection.CHAT, "Chat", historyBody)
    internal val chat = CanvasChatView(host, id, initial, { canvasWelcome(card) }, canvasAskPlaceholder(initial), history)
    val input get() = chat.input

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        name = if (isRoot) "canvas-root" else "canvas-card-$id"
        add(header)
        add(meta)
        add(summary)
        add(usageSites)
        add(sourceSection)
        add(usages)
        add(history)
        add(chat.ask)
        addMouseWheelListener(host.sourceListener::wheel)
        // Before the card joins the canvas, which observes the gestures of components that listen to the mouse.
        resize.install()
        update(initial)
        applyZoom()
    }

    /** The entries of the card's ⋯ menu (also on right-click), for its current state. */
    fun menuActions(): List<CanvasCardAction> = buildList {
        val what = if (isRoot) card.rootKind else "card"
        if (isRoot) add(CanvasCardAction("canvas-summarize", summaryRetry.text, "Summarize this $what",
            enabled = summaryJob?.isActive != true && summarizedText().isNotBlank()) { requestSummary() })
        if (canFindUsages) add(CanvasCardAction("canvas-find-usages", usagesLabel(), "Find project callers and references to this symbol",
            enabled = usageSearch?.isActive != true) { showUsages() })
        if (canExpand) add(CanvasCardAction("canvas-expand", "Expand to file", "Show the whole file here, with links and this card's chat") {
            host.expandToFile(id)
        })
        addAll(canvasGapActions(host, id, source, card.gapSummaries))
        // The root heads every chain, so it has no chain of its own to open beside it.
        if (!isRoot) add(CanvasCardAction("canvas-side-by-side", "Side by side", "Open the chain from the root file to this card in a new tab") {
            host.openSideBySide(id)
        })
        // A directory's listing is not a file to go to.
        if (!card.isDirectoryRoot) add(CanvasCardAction("canvas-source", if (card.kind == CardKind.ATTACHMENT) "Open file" else "Go to source",
            "Open ${card.location} in the editor", enabled = card.filePath.isNotEmpty(), separated = isRoot) {
            host.navigate(source, card.filePath, card.firstLine)
        })
        add(CanvasCardAction("canvas-attach-file", "Attach file…", "Attach documentation, screenshots or other files to this $what",
            separated = true) { host.attachFile(id) })
        add(CanvasCardAction("canvas-new-chat", "New chat", "Start another conversation about this $what") { host.model.addChat(id) })
        if (card.size != null) add(CanvasCardAction("canvas-reset-size", "Reset size",
            "Return this $what to its automatic width and height") { host.model.resize(id, null) })
        if (!isRoot) add(CanvasCardAction("canvas-close-card", "Close card", "Close this card and the cards opened from it", separated = true) {
            host.close(id)
        })
    }

    /** The text a summary is written from: the card's snapshot, or the root file's source, which is not saved. */
    private fun summarizedText(): String = if (isRoot) source?.text.orEmpty() else card.text

    private fun usagesLabel() = when {
        usagesLoaded -> if (usages.isVisible) "Hide usages" else "Show usages"
        usages.isVisible -> "Retry usages"
        else -> "Find usages"
    }

    /** Shows [next], the card's state in the model. */
    fun update(next: CanvasCard) {
        // The input already shows a typed draft; keystrokes need no card refresh or canvas layout.
        val draftOnly = rendered && host.controller.source(id) === source && renderedSummaryPending == (summaryJob?.isActive == true) &&
            card.activeChatId == next.activeChatId &&
            card.copy(chats = next.chats) == next && card.chats.size == next.chats.size &&
            card.chats.zip(next.chats).all { (a, b) -> a.copy(draft = b.draft) == b }
        card = next
        chat.update(next)
        if (draftOnly) return
        val fresh = host.controller.source(id)
        val sourceChanged = fresh !== source
        if (sourceChanged) {
            summaryJob?.cancel()
            summaryJob = null
            usageSearch?.cancel()
            usageSearch = null
            usagesLoaded = false
            usages.removeAll()
            usages.isVisible = false
            sourcePane?.let(Disposer::dispose)
            sourcePane = null
            source = fresh
            val shouldHibernate = hibernated && !isRoot && fresh != null && fresh.text.isNotEmpty() &&
                !(next.kind == CardKind.ATTACHMENT && next.attachment?.imageBase64 != null)
            if (shouldHibernate) {
                hibernatedBaseHeight = estimateBaseSourceHeight(fresh!!.text)
                savedScrollX = 0
                savedScrollY = 0
                cachedAnchorYs = emptyMap()
                sourceBody.removeAll()
                sourceBody.add(hibernatedPlaceholder)
            } else {
                hibernated = false
                sourcePane = populateSourceBody(next, fresh)
            }
            notice.text = fresh?.notice.orEmpty()
            notice.isVisible = !fresh?.notice.isNullOrBlank()
        }
        if (sourceChanged || !rendered) {
            if (!hibernated) showDiff(next)
            else {
                diffPane?.let(Disposer::dispose)
                diffPane = null
                sourceSection.setDiff(null, "")
            }
        }
        if ((sourceChanged || !rendered) && next.kind == CardKind.ATTACHMENT && next.attachment?.imageBase64 != null) {
            hibernated = false
            sourceBody.removeAll()
            sourceBody.add(CanvasImagePreview(next.attachment.imageBase64) { host.zoom })
        }
        rendered = true
        titleLabel.text = next.title
        openedByLabel.isVisible = next.openedBy == CardOpenedBy.LLM
        titleLabel.icon = icon(next)
        titleLabel.toolTipText = "${canvasKindLabel(next)} ${next.title}"
        val shown = if (next.wholeFile && fresh != null) next.copy(text = fresh.text, textTruncated = false) else next
        metaLabel.text = "${canvasKindLabel(next)} · ${canvasCardMeta(shown)}"
        metaLabel.toolTipText = metaLabel.text
        meta.isVisible = CardSection.META !in next.folded
        header.metaToggle.text = canvasFoldGlyph(CardSection.META !in next.folded)
        source?.let { shown -> sourcePane?.showGapSummaries(shown.shownGapSummaries(next.gapSummaries)) }
        showUsageSites(next.usages)
        listOf(summary, usageSites, sourceSection, history).forEach { it.fold(it.section in next.folded) }
        sourcePane?.isVisible = CardSection.SOURCE !in next.folded
        hibernatedPlaceholder.isVisible = CardSection.SOURCE !in next.folded
        sourceSection.title.text = canvasSourceTitle(shown)
        val pending = summaryJob?.isActive == true
        renderedSummaryPending = pending
        summaryText.markdown = next.summary.orEmpty()
        summaryText.isVisible = next.summary != null
        summaryStatus.text = when {
            pending -> "Summarizing…"
            next.summaryError != null -> next.summaryError
            next.summary == null && summarizedText().isBlank() -> "No source to summarize."
            else -> ""
        }
        summaryStatus.foreground = if (next.summaryError != null && !pending) NOTICE else UIUtil.getContextHelpForeground()
        summaryStatus.isVisible = summaryStatus.text.isNotEmpty()
        summaryRetry.text = when {
            next.summaryError != null -> "Retry"
            next.summary != null -> "Summarize again"
            else -> "Summarize"
        }
        summaryRetry.parent?.isVisible = !pending && summarizedText().isNotBlank()
        revalidate()
        repaint()
        host.cardResized()
    }

    /** Offers the changed hunks within the card's source in a Diff tab, with no tab for a file not opened from a diff. */
    private fun showDiff(card: CanvasCard) {
        diffPane?.let(Disposer::dispose)
        val rows = canvasSourceDiff(host.diffs, source, card.wholeFile)
        diffPane = rows.takeIf { it.isNotEmpty() }?.let { CanvasDiffPane(host.project, card.filePath, it, host.sourceListener::wheel) }
        diffPane?.applyZoom(host.zoom)
        sourceSection.setDiff(diffPane, canvasDiffTitle(rows))
    }

    /** Asks for the summary, only on request and never when a card opens; the result is saved in the model. */
    fun requestSummary() {
        val current = host.model.card(id) ?: return
        val summarized = if (isRoot) summarizedText() else current.text
        if (summaryJob?.isActive == true || summarized.isBlank()) return
        val input = if (isRoot) CanvasSummaryInput(CardKind.SYMBOL, current.title, current.filePath, source?.firstLine ?: 0, summarized,
            symbolKind = current.rootKind.uppercase(), language = host.controller.rootFile.fileType.displayName)
        else CanvasSummaryInput.of(current, host.model.session.rootPath, host.model.chain(id), language())
        val requestedSource = source
        val symbolSummaries = host.services.symbolSummaries
        val declaration = requestedSource?.takeIf { current.kind == CardKind.SYMBOL && !current.wholeFile }?.let {
            it.summarySpan?.summarySource(it.path, it.text)
        }
        summaryJob = host.scope.launch(Dispatchers.EDT) {
            try {
                val text = withBackgroundProgress(host.project, "Summarizing ${current.title}") {
                    reportRawProgress { reporter ->
                        withContext(Dispatchers.IO) {
                            if (declaration != null && symbolSummaries != null) {
                                symbolSummaries.summarize(declaration, host.services.complete,
                                    refresh = current.summary != null || current.summaryError != null) { reporter.text(it) }
                            } else {
                                CanvasSummarizer(host.services.complete(), tools = host.services.summaryTools(),
                                    maxContextChars = host.services.maxContextChars()).summarize(input) { reporter.text(it) }
                            }
                        }
                    }
                }
                host.model.setSummary(id, text)
            } catch (e: CanvasSummaryException) {
                LOG.info("Canvas card summary failed: ${e.message}", e.cause)
                host.model.setSummary(id, null, e.message)
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Canvas card summary failed", e)
                host.model.setSummary(id, null, "The summary failed: ${e.message ?: e.javaClass.simpleName}. See the IDE log.")
            } finally {
                if (source === requestedSource) {
                    summaryJob = null
                    host.model.card(id)?.let(::update)
                }
            }
        }
        update(current)
    }

    private fun language(): String? = source?.file?.fileType?.displayName?.takeIf { it.isNotBlank() }

    /** Asks the card's selected chat [text]. */
    internal fun send(text: String) = chat.send(text)

    /** Whether a reply runs in any of the card's chats. */
    val chatBusy: Boolean get() = chat.replying

    /** Whether any background work or active drag prevents safely hibernating this card's editor. */
    val busy: Boolean get() = chatBusy || summaryJob?.isActive == true || usageSearch?.isActive == true || resizing

    /** Whether this card can release its heavy `EditorEx` viewer when off-screen. */
    val canHibernate: Boolean
        get() = !isRoot && !busy && source != null && source!!.text.isNotEmpty() &&
            !(card.kind == CardKind.ATTACHMENT && card.attachment?.imageBase64 != null)

    /** Releases the heavy `EditorEx` viewers while preserving cached geometry, scroll state, and anchors. */
    fun hibernateSource() {
        if (hibernated || !canHibernate) return
        val pane = sourcePane
        val visibleArea = pane?.editor?.scrollingModel?.visibleArea
        if (visibleArea != null) {
            savedScrollX = visibleArea.x
            savedScrollY = visibleArea.y
        }
        wasShowingDiff = sourceSection.showsDiff
        hibernatedBaseHeight = pane?.preferredSize?.height?.let { zoomUnscaled(it, host.zoom).coerceIn(48, CARD_MAX_HEIGHT) }
            ?: estimateBaseSourceHeight(source!!.text)
        if (pane != null && sourceSection.body.isVisible && !sourceSection.showsDiff) {
            cachedAnchorYs = host.childAnchors(id).associate { anchor ->
                val y = pane.shownAnchorY(anchor.startOffset)
                val inCardY = SwingUtilities.convertPoint(pane, 0, y, this).y
                anchor.startOffset to zoomUnscaled(inCardY, host.zoom)
            }
        } else {
            cachedAnchorYs = emptyMap()
        }
        pane?.let(Disposer::dispose)
        sourcePane = null
        diffPane?.let(Disposer::dispose)
        diffPane = null
        hibernated = true
        sourceSection.setDiff(null, "")
        sourceBody.removeAll()
        sourceBody.add(hibernatedPlaceholder)
        hibernatedPlaceholder.isVisible = CardSection.SOURCE !in card.folded
        revalidate()
        repaint()
    }

    /** Re-creates the `EditorEx` source/diff viewers for a hibernated card and restores scroll/highlights. */
    fun materializeSource() {
        if (!hibernated) return
        hibernated = false
        val restoreDiff = wasShowingDiff
        wasShowingDiff = false
        val fresh = host.controller.source(id)
        source = fresh
        val pane = populateSourceBody(card, fresh)
        sourcePane = pane
        if (pane != null && fresh != null) {
            pane.showGapSummaries(fresh.shownGapSummaries(card.gapSummaries))
            pane.showUsageSites(card.usages?.sites.orEmpty().map { it.line })
            pane.highlightAnchors(host.childAnchors(id))
            pane.isVisible = CardSection.SOURCE !in card.folded
        }
        showDiff(card)
        if (restoreDiff && diffPane != null) sourceSection.restoreDiff()
        val pendingLine = pendingScrollLine
        pendingScrollLine = null
        val pendingOffset = pendingScrollOffset
        pendingScrollOffset = null
        if (pane != null) {
            if (pendingLine != null) pane.scrollToLine(pendingLine)
            if (pendingOffset != null) {
                pane.centerOnceSized(pendingOffset) { host.cardResized() }
            } else if (savedScrollX != 0 || savedScrollY != 0) {
                val sx = savedScrollX
                val sy = savedScrollY
                val vp = pane.editor.scrollPane.viewport
                fun restoreScroll() {
                    val sm = pane.editor.scrollingModel
                    sm.disableAnimation()
                    try {
                        if (sx > 0) sm.scrollHorizontally(sx)
                        if (sy > 0) sm.scrollVertically(sy)
                    } finally {
                        sm.enableAnimation()
                    }
                }
                if (vp.height > 0) restoreScroll()
                else vp.addComponentListener(object : java.awt.event.ComponentAdapter() {
                    override fun componentResized(e: java.awt.event.ComponentEvent) {
                        if (vp.height <= 0 || pane.editor.isDisposed) return
                        vp.removeComponentListener(this)
                        restoreScroll()
                    }
                })
            }
        }
        revalidate()
        repaint()
        host.cardResized()
    }

    /** Centers the source on [offset] once sized, or defers it until the card is materialized. */
    fun scheduleInitialScroll(offset: Int, onScrolled: () -> Unit) {
        pendingScrollLine = null
        val pane = sourcePane
        if (pane != null) pane.centerOnceSized(offset, onScrolled)
        else if (hibernated) pendingScrollOffset = offset
    }

    /** Highlights 0-based [line] and centers the source on [offset] once sized, or defers both until materialized. */
    fun scheduleInitialLine(line: Int, offset: Int, onScrolled: () -> Unit) {
        val pane = sourcePane
        if (pane != null) {
            pendingScrollLine = null
            pendingScrollOffset = null
            if (pane.scrollToLine(line, onScrolled) == null) {
                pane.centerOnceSized(offset, onScrolled)
            }
        } else if (hibernated) {
            pendingScrollLine = line
            pendingScrollOffset = offset
        }
    }

    private fun populateSourceBody(card: CanvasCard, fresh: CanvasSource?): CanvasSourcePane? {
        sourceBody.removeAll()
        val pane = fresh?.takeIf { it.text.isNotEmpty() }?.let { CanvasSourcePane(host.project, id, it, host.sourceListener) }
        val image = card.attachment?.imageBase64
        when {
            pane != null -> {
                sourceBody.add(pane)
                pane.applyZoom(host.zoom)
            }
            card.kind == CardKind.ATTACHMENT && image != null -> sourceBody.add(CanvasImagePreview(image) { host.zoom })
            else -> sourceBody.add(emptySourceLabel(card))
        }
        return pane
    }

    private fun emptySourceLabel(card: CanvasCard): JComponent = host.zoomFont(JLabel(when (card.kind) {
        CardKind.ROOT -> if (card.isDirectoryRoot) "No project files." else "No source to show."
        CardKind.SYMBOL -> "No source to show."
        CardKind.ATTACHMENT -> "Use Open file to view this attachment in the IDE."
        CardKind.SELECTION -> "No text."
    }).apply {
        foreground = UIUtil.getContextHelpForeground()
    })

    private inner class HibernatedSourcePlaceholder : JPanel(BorderLayout()) {
        var maxWidth: Int = Int.MAX_VALUE
        var maxHeight: Int = Int.MAX_VALUE

        init {
            isOpaque = true
            background = UIUtil.getEditorPaneBackground()
            border = BorderFactory.createLineBorder(CANVAS_CARD_BORDER)
            add(host.zoomFont(JLabel("Source paused off-screen — click to view", SwingConstants.CENTER).apply {
                foreground = UIUtil.getContextHelpForeground()
            }), BorderLayout.CENTER)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) materializeSource()
                }
            })
            addMouseWheelListener(host.sourceListener::wheel)
        }

        override fun getPreferredSize(): Dimension {
            val w = minOf(scaled(CARD_WIDTH - 18), maxWidth).coerceAtLeast(1)
            val h = minOf(scaled(hibernatedBaseHeight), maxHeight).coerceAtLeast(1)
            return Dimension(w, h)
        }
    }

    /** Lists [usages] above the source, each site scrolling the source to its line, and tints their lines. */
    private fun showUsageSites(usages: CardUsages?) {
        sourcePane?.showUsageSites(usages?.sites.orEmpty().map { it.line })
        if (usages == shownSites) return
        shownSites = usages
        usageSitesBody.removeAll()
        usageSites.isVisible = usages != null
        if (usages == null) return
        usageSites.title.text = canvasUsageSitesTitle(usages)
        usages.sites.forEach { site ->
            usageSitesBody.add(host.zoomFont(ActionLink(canvasUsageSiteLabel(site)) { host.revealLine(id, site.line) }.apply {
                name = "canvas-usage-site"
                toolTipText = "Scroll this card to line ${site.line + 1}"
                border = JBUI.Borders.empty(1, 10, 1, 0)
            }))
        }
    }

    /** Shows the source rather than its diff. */
    fun showSource() = sourceSection.showSource()

    /** Searches the project for references to this card's symbol, or toggles the loaded usage list. */
    fun showUsages() {
        val target = source?.node?.takeIf { canFindUsages } ?: return
        if (usageSearch?.isActive == true) return
        if (usagesLoaded) {
            usages.isVisible = !usages.isVisible
            revalidate()
            repaint()
            host.cardResized()
            return
        }
        val requestedSource = source
        usageSearch = host.scope.launch(Dispatchers.EDT) {
            try {
                val found = host.controller.findUsages(target)
                if (source !== requestedSource) return@launch
                usages.removeAll()
                usages.isVisible = true
                usages.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                    isOpaque = false
                    add(host.zoomFont(JLabel(
                        if (found.usages.isEmpty()) "No project usages found."
                        else canvasUsedByTitle(found)
                    ).apply { foreground = UIUtil.getContextHelpForeground() }))
                    add(host.zoomFont(ActionLink("Hide") { showUsages() }.apply { name = "canvas-hide-usages" }))
                })
                canvasUsageFiles(found.usages).forEach { file ->
                    usages.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                        name = "canvas-usage-file"
                        isOpaque = false
                        border = JBUI.Borders.empty(1, 4, 1, 0)
                        add(host.zoomFont(ActionLink("${file.fileName} (${file.usages.size})") {
                            host.openUsageFile(id, file, found.targetName)
                        }.apply {
                            toolTipText = "Open ${file.path} with its usage sites listed on the card"
                        }))
                        add(host.zoomFont(JLabel(file.detail + file.directory.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()).apply {
                            foreground = UIUtil.getContextHelpForeground()
                        }))
                    })
                }
                if (found.truncated) {
                    usages.add(host.zoomFont(JLabel("More usages exist; showing ${found.usages.size} sites.").apply {
                        foreground = UIUtil.getContextHelpForeground()
                    }))
                }
                usagesLoaded = true
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Unable to find canvas usages", e)
                usages.removeAll()
                usages.isVisible = true
                usages.add(host.zoomFont(JLabel("Unable to find usages: ${e.message ?: e.javaClass.simpleName}").apply {
                    foreground = NOTICE
                }))
            } finally {
                if (source === requestedSource) {
                    usageSearch = null
                    revalidate()
                    repaint()
                    host.cardResized()
                }
            }
        }
    }

    /** The size the card is shown at: the one being dragged to while resizing, else the saved one. */
    val shownSize: CardSize? get() = if (resizing) resize.live else card.size

    /**
     * Sizes the card for the canvas at the current zoom, as [widthPolicy] says unless the reader resized it, its source
     * and diff scrolling within the card beyond their bounds; the canvas then only places it. A resized height is shared
     * out as [canvasViewerMaxHeight] says.
     */
    fun layOut() {
        val size = shownSize
        val maxHeight = scaled(CARD_SOURCE_MAX_HEIGHT)
        val userWidth = size?.width?.let { scaled(it.coerceIn(CARD_MIN_WIDTH, CARD_MAX_WIDTH)) }
        val width = if (userWidth != null) userWidth.also { width -> bound(width - 2 * scaled(9), maxHeight) }
        else when (val policy = widthPolicy) {
            is CanvasCardWidth.Fixed -> scaled(policy.width).also { width -> bound(width - 2 * scaled(9), maxHeight) }
            is CanvasCardWidth.Content -> {
                bound(scaled(policy.max), maxHeight)
                // Measured twice: the second time at its own width, after a zoom changed it.
                setSize(contentWidth(policy).coerceAtLeast(1), maxOf(height, 1))
                contentWidth(policy)
            }
        }
        setSize(width, maxOf(height, 1))
        setSize(width, preferredSize.height)
        size?.height?.let { fitHeight(scaled(it.coerceIn(CARD_MIN_HEIGHT, CARD_MAX_HEIGHT)), maxHeight) }
        validate()
    }

    /**
     * Gives the shown viewer what [target] leaves after the card's other sections, measured at [autoMax]; measured
     * again once, since a shorter viewer may gain or lose a scroll bar.
     */
    private fun fitHeight(target: Int, autoMax: Int) {
        val viewer = shownViewer() ?: return
        repeat(2) {
            val chrome = height - viewer.preferredSize.height
            val viewerMax = canvasViewerMaxHeight(target, chrome, scaled(CARD_VIEWER_MIN_HEIGHT), autoMax)
            sourcePane?.maxHeight = viewerMax
            diffPane?.maxHeight = viewerMax
            hibernatedPlaceholder.maxHeight = viewerMax
            setSize(width, preferredSize.height)
        }
    }

    /** The source or diff viewer the card shows, or null when it shows neither (folded, an image, no text). */
    private fun shownViewer(): JComponent? {
        if (!sourceSection.body.isVisible) return null
        val viewer: JComponent = when {
            hibernated -> hibernatedPlaceholder
            sourceSection.showsDiff -> diffPane
            else -> sourcePane
        } ?: return null
        return viewer.takeIf { it.isVisible && SwingUtilities.isDescendingFrom(it, this) }
    }

    private fun bound(width: Int, height: Int) {
        sourcePane?.maxWidth = width
        diffPane?.maxWidth = width
        hibernatedPlaceholder.maxWidth = width
        sourcePane?.maxHeight = height
        diffPane?.maxHeight = height
        hibernatedPlaceholder.maxHeight = height
    }

    /** As wide as the source, its diff or the header want, and wide enough to chat in, even beside a short, narrow file. */
    private fun contentWidth(policy: CanvasCardWidth.Content): Int =
        maxOf(sourcePane?.preferredSize?.width ?: 0, diffPane?.preferredSize?.width ?: 0, header.preferredSize.width,
            scaled(policy.min)) + insets.left + insets.right

    /** Sizes beyond fonts, at the current zoom. */
    fun applyZoom() {
        updateBorder()
        sourcePane?.applyZoom(host.zoom)
        diffPane?.applyZoom(host.zoom)
        if (hibernated) hibernatedPlaceholder.revalidate()
    }

    private fun updateBorder() {
        val emphasized = focused || highlighted
        val accent = when {
            focused -> JBUI.CurrentTheme.Focus.focusColor()
            highlighted -> ACCENT
            else -> CANVAS_CARD_BORDER
        }
        val line = if (emphasized) scaled(2) else scaled(1)
        // Reserve the highlight width without thickening the normal outline.
        border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent, line),
            pixel(scaled(8) + scaled(2) - line))
    }

    val navigationEditor get() = if (hibernated || !sourceSection.body.isVisible) null
        else if (sourceSection.showsDiff) diffPane?.editor else sourcePane?.editor

    /** The y of [anchor]'s line in this card, or the header's middle when the source is folded or shows its diff. */
    fun anchorY(anchor: CardAnchor): Int {
        if (hibernated) {
            if (!sourceSection.body.isVisible || wasShowingDiff) return headerMiddle()
            cachedAnchorYs[anchor.startOffset]?.let { return scaled(it) }
            val src = source ?: return headerMiddle()
            val totalLines = (src.text.count { it == '\n' } + 1).coerceAtLeast(1)
            val relLine = (anchor.line - src.firstLine).coerceIn(0, totalLines - 1)
            val bodyTop = if (sourceBody.height > 0) SwingUtilities.convertPoint(sourceBody, 0, 0, this).y
                else headerMiddle() + scaled(40)
            val bodyHeight = sourceBody.height.takeIf { it > 0 } ?: scaled(hibernatedBaseHeight).coerceAtLeast(1)
            return bodyTop + (bodyHeight.toDouble() * (relLine + 0.5) / totalLines).toInt()
        }
        val pane = sourcePane?.takeIf { sourceSection.body.isVisible && !sourceSection.showsDiff }
            ?: return headerMiddle()
        val y = pane.shownAnchorY(anchor.startOffset)
        return SwingUtilities.convertPoint(pane, 0, y, this).y
    }

    /** The middle of the header, where arrows end. */
    fun headerMiddle(): Int = (components.firstOrNull()?.let { it.y + it.height / 2 } ?: scaled(12))

    override fun dispose() {
        summaryJob?.cancel()
        usageSearch?.cancel()
        Disposer.dispose(chat)
        sourcePane?.let(Disposer::dispose)
        diffPane?.let(Disposer::dispose)
    }

    private fun scaled(value: Int) = zoomScaled(value, host.zoom)

    /** The root shows its file's own icon; other files a plain file icon, and symbols their kind's. */
    private fun icon(card: CanvasCard): Icon = when {
        card.kind == CardKind.ROOT -> host.controller.rootFile.fileType.icon ?: AllIcons.FileTypes.Any_type
        card.wholeFile -> AllIcons.FileTypes.Any_type
        else -> kindIcon(card)
    }

    private companion object {
        val LOG = logger<CanvasCardView>()
        val NOTICE = JBColor(Color(160, 95, 20), Color(214, 165, 90))
        val ACCENT = JBColor(Color(76, 112, 220), Color(110, 150, 240))

        @Suppress("UseDPIAwareBorders")
        fun pixel(value: Int) = javax.swing.border.EmptyBorder(value, value, value, value)

        fun kindIcon(card: CanvasCard): Icon = if (card.kind == CardKind.ATTACHMENT) AllIcons.FileTypes.Any_type else when (card.symbol?.kind?.takeIf { card.kind == CardKind.SYMBOL }) {
            null -> AllIcons.Actions.Selectall
            "CLASS" -> AllIcons.Nodes.Class
            "FUNCTION" -> AllIcons.Nodes.Method
            "PROPERTY" -> AllIcons.Nodes.Property
            "VARIABLE", "PARAMETER" -> AllIcons.Nodes.Variable
            "FILE" -> AllIcons.FileTypes.Any_type
            else -> AllIcons.Nodes.Unknown
        }
    }
}

/**
 * The gap entries of the ⋯ menu of card [cardId] showing [source]: summarize the gaps with no
 * summary among [summaries], and clear those shown. None for a source without gaps.
 */
internal fun canvasGapActions(host: CanvasCardHost, cardId: String, source: CanvasSource?, summaries: List<GapSummary>): List<CanvasCardAction> {
    if (source == null || source.gaps.isEmpty()) return emptyList()
    val running = host.summarizingGaps(cardId)
    val pending = source.unsummarizedGaps(summaries).size
    val shown = source.shownGapSummaries(summaries).isNotEmpty()
    return listOfNotNull(
        CanvasCardAction("canvas-summarize-gaps", if (running) "Summarizing gaps…" else "Summarize gaps",
            if (pending == 0) "Every gap of this source is summarized"
            else "Summarize the code between the symbols of this source ($pending ${if (pending == 1) "gap" else "gaps"})",
            enabled = !running && pending > 0, separated = true) { host.summarizeGaps(cardId) },
        CanvasCardAction("canvas-clear-gaps", "Clear gap summaries", "Remove the gap summaries shown in this source",
            enabled = !running) { host.model.clearGapSummaries(cardId) }.takeIf { shown },
    )
}

/**
 * Compact inline continuation card placed where a large graph, directory, or pending file list was chunked.
 */
internal class CanvasMoreCard(
    private val host: CanvasCardHost,
    initial: CanvasDeferredBranch,
    private val onLoad: (String) -> Unit,
) : JPanel(StackLayout { zoomScaled(4, host.zoom) }) {
    var branch: CanvasDeferredBranch = initial
        private set
    private var loading = false
    private val titleLabel = host.zoomFont(JLabel(), UIUtil.getLabelFont().deriveFont(Font.BOLD))
    private val actionLink = host.zoomFont(ActionLink("") { trigger() }).apply {
        name = "canvas-more-action"
    }

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        name = "canvas-more-${initial.parentId}"
        add(titleLabel)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(actionLink)
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) trigger()
            }
        })
        addMouseWheelListener(host.sourceListener::wheel)
        applyZoom(host.zoom)
        update(initial, CANVAS_LOAD_CHUNK, loading = false)
    }

    private fun trigger() {
        if (!loading) onLoad(branch.parentId)
    }

    fun update(next: CanvasDeferredBranch, chunkSize: Int, loading: Boolean) {
        this.branch = next
        this.loading = loading
        titleLabel.text = next.title()
        actionLink.text = if (loading) "Loading…" else next.actionLabel(chunkSize)
        actionLink.isEnabled = !loading
        toolTipText = "${next.title()} — ${next.actionLabel(chunkSize)}"
        revalidate()
        repaint()
    }

    fun applyZoom(zoom: Double) {
        val stroke = zoomScaled(1, zoom).toFloat().coerceAtLeast(1f)
        val dash = zoomScaled(4, zoom).toFloat().coerceAtLeast(2f)
        val pad = zoomScaled(10, zoom)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createDashedBorder(CANVAS_CARD_BORDER, stroke, dash, dash, false),
            JBUI.Borders.empty(pad, pad, pad, pad),
        )
    }

    fun layOut(zoom: Double) {
        val targetWidth = zoomScaled(360, zoom).coerceAtLeast(1)
        setSize(targetWidth, maxOf(height, 1))
        setSize(targetWidth, preferredSize.height.coerceAtLeast(1))
        validate()
    }
}
