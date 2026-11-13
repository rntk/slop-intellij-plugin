package dev.insight.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.CanvasTabKey
import dev.insight.review.ExplainResult
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewNoteKey
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ReviewedFiles
import dev.insight.review.SummaryNode
import dev.insight.review.diffCardKey
import dev.insight.review.explanationCard
import dev.insight.review.fillSummaryRequest
import dev.insight.review.resummaryRequest
import dev.insight.review.seedTopicSummaries
import dev.insight.ui.layoutZoomContent
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.geom.Point2D
import java.awt.Rectangle
import javax.swing.*
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Hosts review pages and routes updates; card builders own their presentation and per-tab state. */
internal class NoteCanvas(
    private val open: (String) -> Unit,
    private val remove: (ReviewNote) -> Unit,
    private val project: Project? = null,
    private val vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
    private val reviewed: ReviewedFiles = ReviewedFiles(),
    private val sourceActions: SourceCanvasActions = SourceCanvasActions(),
    private val summaryActions: SummaryCanvasActions = SummaryCanvasActions(),
    private val topicActions: TopicCanvasActions = TopicCanvasActions(),
    private val groupingActions: TopicCanvasActions = TopicCanvasActions(),
    /** The review's own pages and the summaries Diff mode; see [CanvasExtension]. */
    private val extension: CanvasExtension? = null,
) : JPanel(BorderLayout()), Disposable {
    private val openFileAt: (String, Int?) -> Unit = sourceActions.openFileAt ?: { path, _ -> open(path) }
    private val showFileLines: (String, IntRange) -> Unit = sourceActions.showFileLines ?: { path, _ -> sourceActions.showDiffCard(path) }
    private val openSummaryNode: (SummaryNode) -> Unit = summaryActions.openNode ?: { node -> open(node.path) }
    private val showSummaryNode: (SummaryNode) -> Unit = summaryActions.showNode ?: { node -> sourceActions.showDiffCard(node.path) }
    private val openSummaryLines: (SummaryNode, LineTrace) -> Unit = summaryActions.openLines ?: { node, _ -> openSummaryNode(node) }
    internal val tabs = ReviewPages()
    internal var onPageFocused: (CanvasTabKey) -> Unit = {}
    internal var onPagesChanged: () -> Unit = {}
    private val editorPages = mutableMapOf<CanvasTabKey, JPanel>()
    internal val pageKeys: Set<CanvasTabKey> get() = pages.keys
    internal val selectedKey: CanvasTabKey? get() = pages.entries.firstOrNull { it.value === tabs.selectedComponent }?.key
    internal fun editorPage(key: CanvasTabKey): JPanel = editorPages.getOrPut(key) { JPanel(BorderLayout()) }
    internal fun pageTitle(key: CanvasTabKey) = tabTitle(key)
    internal fun selectPage(key: CanvasTabKey) { pages[key]?.let { tabs.selectedComponent = it } }
    private val pages = mutableMapOf<CanvasTabKey, JScrollPane>()
    private val cards = mutableMapOf<CanvasTabKey, JPanel>()
    private val stickyTargets = mutableMapOf<CanvasTabKey, JComponent>()

    private var state = CanvasState()
    private val notes: List<ReviewNote> get() = state.notes
    private val sources: List<ReviewSourceBlock> get() = state.sources
    private val topics: TopicsCard? get() = state.topics
    private val grouping: TopicsCard? get() = state.grouping
    internal val topicsView get() = topicsCardBuilder.view
    internal val groupingView get() = groupingCardBuilder.view
    private val topicsCardBuilder = TopicsCanvasCard(vocabulary, reviewed, sourceActions, summaryActions, topicActions,
        TopicsCanvasActions(open, openSummaryNode, showSummaryNode, openSummaryLines, ::toggleReviewed,
            refresh = { refreshCard(CanvasTabKey.Topics) }), project)
    private val groupingCardBuilder = TopicsCanvasCard(
        vocabulary, reviewed, sourceActions, summaryActions, groupingActions,
        TopicsCanvasActions(open, openSummaryNode, showSummaryNode, openSummaryLines, ::toggleReviewed,
            refresh = { refreshCard(CanvasTabKey.Grouping) }),
        project,
        TopicsCardLabels(vocabulary.groupingTitle, vocabulary.groupingTooltip,
            command = "/grouping", singular = "group", plural = "groups", closeText = "Remove change grouping"),
    )
    private val summarySession: CanvasSummarySession = CanvasSummarySession(summaryActions.contextSummaries,
        currentTree = { summaries?.summaries?.tree }, changed = { version ->
            summariesCardBuilder.generationChanged(version)
            if (version == null) {
                refreshTopics()
                refreshGrouping()
            }
        })
    internal val topicSummariesRunning: Boolean get() = summarySession.topicsRunning
    private val summaryGenerationRunning: Boolean get() = summarySession.running
    private val summaries: SummariesCard? get() = state.summaries
    internal val summariesView get() = summariesCardBuilder.summariesView
    private val summariesCardBuilder: SummariesCanvasCards = SummariesCanvasCards(vocabulary, reviewed, sourceActions, summaryActions,
        summarySession, SummariesCanvasActions(
            open = open, openNode = openSummaryNode, showNode = showSummaryNode, openLines = openSummaryLines,
            toggleReviewed = ::toggleReviewed, replace = ::replaceSummaryCard, rebuildPages = { showNotes(state) },
            focus = ::focusCard, restyleReview = ::restyleReview, refreshTopics = { refreshTopics(); refreshGrouping() },
            documentZoom = { documentZoom }, zoomControls = ::documentZoomControls, installWheelZoom = ::installDocumentWheelZoom,
            requestInstructions = { node, recursive, destination -> requestResummaryInstructions(node, recursive, destination) },
        ), project, extension)
    private val fileView: FileViewCard? get() = state.fileView
    private val fileViewCardBuilder = FileViewCanvasCard(vocabulary, FileViewCanvasActions(
        openFileAt = openFileAt, showFileLines = showFileLines, close = sourceActions.removeFileView,
        summarize = summaryActions.summarize, generateTopics = topicActions.generateTopics,
    ), project)
    private val explanation: ExplainResult? get() = state.explanation

    /** Marks restyle the live cards; rebuilding them would reset both viewports and make the reading position jump. */
    private fun toggleReviewed(node: SummaryNode, value: Boolean) {
        if (summaryActions.markReviewed != null) summaryActions.markReviewed.invoke(node, value) else reviewed.set(node.files, value)
        summariesCardBuilder.syncReview()
        topicsCardBuilder.syncReview()
        groupingCardBuilder.syncReview()
        restyleReview(CanvasTabKey.Topics)
        restyleReview(CanvasTabKey.Grouping)
    }

    private fun restyleReview(key: CanvasTabKey) {
        val card = cards[key] ?: return
        descendantsOf(card).forEach { (it as? JComponent)?.let(::updateReviewPresentation) }
    }

    private var highlighted = emptySet<String>()
    private var focused: CanvasTabKey? = null
    private val zoom = 1.0
    private val snapshotCards = SnapshotCanvasCards(open, remove, ::focusNote, sourceActions, vocabulary, project, zoom)
    private var documentZoom = 1.0
    private data class DocumentView(val zoom: Double, val position: Point, val pagePosition: Point)
    private val documentHistory = mutableMapOf<CanvasTabKey, ArrayDeque<DocumentView>>()
    /** The card is rebuilt once at the destination; frames only transform two bitmaps on [DocumentZoomOverlay]. */
    private inner class DocumentMotion(val key: CanvasTabKey, val card: JPanel, val from: DocumentZoomSnapshot, val duration: Double) {
        var to: DocumentZoomSnapshot? = null
        /** Zooms end where the old bitmap, scaled around the anchor, lands; [to] is rebased to appear there. */
        var toView: DocumentZoomView? = null
        var started = System.nanoTime()
        val timer = Timer(1000 / 60) {
            documentMotionFrame((System.nanoTime() - started) / 1e6 / duration)
        }.apply { isCoalesce = true }
    }
    /**
     * Ctrl+wheel scales a bitmap immediately. The real layout is not uniform in zoom, so the card is rebuilt whenever
     * the bitmap drifts more than [WHEEL_REBUILD_RATIO] from it and once the gesture pauses; each rebuild keeps the
     * content under the pointer in place and cross-fades into the new layout.
     */
    private inner class DocumentWheelZoom(val key: CanvasTabKey, var scroll: JScrollPane, var from: DocumentZoomSnapshot) {
        var view = from.view
        /** The pointer in [scroll]'s viewport. */
        var point = Point()
        /** The bitmap before the latest rebuild, fading out over [from]. */
        var previous: DocumentZoomSnapshot? = null
        var fade = 0.0
        var fadeStarted = 0L
        var rebuilding = false
        var settled = false
        val settle = Timer(WHEEL_SETTLE_MS) { settled = true; advanceWheelZoom() }.apply { isRepeats = false }
        val fadeTimer = Timer(1000 / 60) {
            wheelFadeFrame((System.nanoTime() - fadeStarted) / 1e6 / WHEEL_FADE_MS)
        }.apply { isCoalesce = true }
    }
    /** A reader's spot in the live document, tied to its file row and column so it survives a non-uniform re-layout. */
    private class DocumentAnchor(val viewportPoint: Point, val viewPoint: Point2D.Double, val zoom: Double,
                                 val row: DocumentBandAnchor?, val column: DocumentBandAnchor?)
    private var documentMotion: DocumentMotion? = null
    private var documentWheel: DocumentWheelZoom? = null
    private var documentOverlay: DocumentZoomOverlay? = null
    private var documentMotionVersion = 0
    /** Allows deterministic animation frames in headless tests. */
    internal var animatedDocuments: Boolean? = null

    init {
        minimumSize = Dimension(100, 100)
        background = UIUtil.getPanelBackground()
        add(tabs, BorderLayout.CENTER)
        extension?.attach(::refreshCard)
    }

    fun showNotes(state: CanvasState) {
        stopDocumentMotion()
        if (state.topics == null) summarySession.stopTopics(CanvasTabKey.Topics)
        if (state.grouping == null) summarySession.stopTopics(CanvasTabKey.Grouping)
        fileViewCardBuilder.update(state.fileView)
        this.state = state
        extension?.update()
        val summaries = state.summaries
        topicsCardBuilder.update(topics)
        groupingCardBuilder.update(grouping)
        summariesCardBuilder.update(summaries)
        snapshotCards.update(sources)
        val selected = pages.entries.firstOrNull { it.value === tabs.selectedComponent }?.key
        val bodyScrolls = cards.mapValues { bodyScroll(it.value)?.viewport?.viewPosition?.let(::Point) }
        val scrolls = pages.mapValues { Point(it.value.viewport.viewPosition) }
        tabs.removeAll(); cards.clear(); pages.clear(); stickyTargets.clear()
        val flow = changeFlow(notes).takeIf { it.rows.isNotEmpty() }
        val summaryCards = summariesCardBuilder.buildCards()
        val built = listOfNotNull(
            explanation?.let { CanvasTabKey.Explanation to explanationCard(it) },
            topics?.let { CanvasTabKey.Topics to topicsCard(it) },
            grouping?.let { CanvasTabKey.Grouping to groupingCard(it) },
        ).toMap() +
            extension?.leadingCards().orEmpty() +
            summaryCards +
            listOfNotNull(fileView?.let { CanvasTabKey.FileView to fileViewCard(it) }, flow?.let { CanvasTabKey.SymbolFlow to snapshotCards.flowCard(it) }).toMap() +
            notes.associate { it.key to snapshotCards.noteCard(it) } +
            sources.associate { it.key to snapshotCards.sourceCard(it) } +
            extension?.trailingCards(snapshotCards).orEmpty()
        built.forEach { (key, card) ->
            cards[key] = card
            val page = page(card)
            pages[key] = page
            installStickyScrolling(key, page, card)
            val host = if (project == null) page else editorPage(key).apply {
                removeAll()
                add(page, BorderLayout.CENTER)
                revalidate()
                repaint()
            }
            tabs.addTab(tabTitle(key), page, host)
            tabs.setToolTipTextAt(tabs.tabCount - 1, tabTooltip(key))
            bodyScrolls[key]?.let { position -> SwingUtilities.invokeLater {
                if (cards[key] === card) bodyScroll(card)?.viewport?.viewPosition = position
            } }
            scrolls[key]?.let { position -> SwingUtilities.invokeLater {
                if (pages[key] === page) page.viewport.viewPosition = position
            } }
        }
        documentHistory.keys.retainAll(pages.keys)
        pages[selected]?.let { tabs.selectedComponent = it }
        if (built.isEmpty()) tabs.addTab("Review", JBScrollPane(emptyState()))
        editorPages.keys.retainAll(pages.keys)
        onPagesChanged()
        highlight(highlighted)
        revalidate(); repaint()
    }

    fun highlight(paths: Set<String>) {
        highlighted = paths
        cards.forEach { (key, card) -> card.border = cardBorder(key == focused || (key as? ReviewNoteKey)?.paths.orEmpty().any { it in paths }) }
        repaint()
    }

    /** Selects the note tab. */
    internal fun focusNote(note: ReviewNote) {
        focusCard(note.key)
    }

    internal fun focusDiff(path: String) {
        focusCard(diffCardKey(path))
    }

    internal fun focusTopics() { focusCard(CanvasTabKey.Topics) }
    internal fun focusGrouping() { focusCard(CanvasTabKey.Grouping) }

    internal fun focusWords() { focusCard(CanvasTabKey.Words) }
    internal fun focusDiffOverview() { focusCard(CanvasTabKey.DiffOverview) }
    internal fun focusSemantic() { focusCard(CanvasTabKey.Semantic) }
    internal fun focusMoves() { focusCard(CanvasTabKey.Moves) }
    internal fun focusWall() { focusCard(CanvasTabKey.Wall) }
    internal fun focusExplanation() { focusCard(CanvasTabKey.Explanation) }

    /** Selects the source tab. */
    internal fun focusSource(range: ReviewSourceRange) { focusCard(ReviewSourceBlock(range, "").key) }

    internal fun focusFileView() { focusCard(CanvasTabKey.FileView) }

    internal fun focusSummaries() {
        focusCard(CanvasTabKey.Summaries)
    }

    /** Selects the summaries in the Tree view and unfolds the route to the file at [path]. */
    internal fun showSummariesInTree(path: String) {
        summariesCardBuilder.showInTree(path)
        focusSummaries()
    }

    private fun focusCard(key: CanvasTabKey) {
        val page = pages[key] ?: return
        tabs.selectedComponent = page
        focused = key
        highlight(highlighted)
        onPageFocused(key)
    }

    private fun tabTooltip(key: CanvasTabKey): String = summariesCardBuilder.tooltip(key)
        ?: (key as? ReviewNoteKey)?.paths.orEmpty().joinToString(", ").ifEmpty { tabTitle(key) }

    private fun tabTitle(key: CanvasTabKey): String = extension?.title(key) ?: when (key) {
        CanvasTabKey.Topics -> "Topics"
        CanvasTabKey.Grouping -> "Grouping"
        CanvasTabKey.Summaries, is CanvasTabKey.SummarySearch -> summariesCardBuilder.title(key)
        CanvasTabKey.FileView -> "File · ${fileView?.file?.path?.substringAfterLast('/').orEmpty()}"
        CanvasTabKey.Words, CanvasTabKey.DiffOverview, CanvasTabKey.Semantic, CanvasTabKey.Moves -> key.toString()
        CanvasTabKey.Wall -> vocabulary.wallName
        CanvasTabKey.Explanation -> if (explanation?.running == true) "Explaining…" else "Explanation"
        CanvasTabKey.SymbolFlow -> "Symbol flow"
        CanvasTabKey.Board -> "Explainer board"
        is ReviewNoteKey -> {
            val path = key.paths.firstOrNull()?.substringAfterLast('/') ?: "Note"
            val source = sources.firstOrNull { it.key == key }
            when {
                source != null -> "Source · $path · ${source.range.side.label} · ${source.range.startLine}–${source.range.endLine}"
                else -> "Note · $path" + (notes.firstOrNull { it.key == key }?.symbol?.let { " · $it" } ?: "")
            }
        }
    }

    private fun page(card: JPanel): JScrollPane {
        val content = object : JPanel(BorderLayout()), Scrollable {
            init { add(card, BorderLayout.CENTER) }
            override fun getPreferredScrollableViewportSize() = preferredSize
            override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = scaled(16)
            override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) =
                if (orientation == SwingConstants.VERTICAL) r.height else r.width
            override fun getScrollableTracksViewportWidth() = parent.width >= preferredSize.width
            override fun getScrollableTracksViewportHeight() = parent.height >= preferredSize.height
        }
        return JBScrollPane(content).apply { border = null }
    }

    private fun bodyScroll(card: JPanel): JScrollPane? {
        val body = (card.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
        return body as? JScrollPane ?: (body as? Container)?.let { container ->
            descendantsOf(container).filterIsInstance<JScrollPane>().firstOrNull()
        }
    }

    /** Zoom the document around a point in its viewport, keeping that content under the pointer. */
    private fun zoomDocument(key: CanvasTabKey, requested: Double, anchor: Point? = null) {
        finishWheelZoom()
        val old = cards[key]?.let(::bodyScroll) ?: return
        val next = requested.coerceIn(MIN_DOCUMENT_ZOOM, MAX_DOCUMENT_ZOOM)
        if (next == documentZoom) return
        val point = anchor ?: Point(old.viewport.width / 2, old.viewport.height / 2)
        val position = old.viewport.viewPosition
        val spot = documentAnchor(old, point, Point2D.Double((position.x + point.x).toDouble(), (position.y + point.y).toDouble()))
        val host = SwingUtilities.convertPoint(old.viewport, point, documentHost(key))
        navigateDocument(key, next, ZOOM_MOTION_MS, host) { placeDocument(key, it, spot, next) }
    }

    private fun rememberDocumentScroll(key: CanvasTabKey, y: Int) = when (key) {
        CanvasTabKey.Topics -> topicsCardBuilder.scroll = y
        CanvasTabKey.Grouping -> groupingCardBuilder.scroll = y
        CanvasTabKey.FileView -> fileViewCardBuilder.scroll = y
        else -> summariesCardBuilder.scrollTo(key, y)
    }

    private fun documentPanel(scroll: JScrollPane): DocumentColumnsPanel? =
        descendantsOf(scroll.viewport).filterIsInstance<DocumentColumnsPanel>().firstOrNull()

    /** [viewPoint] is in the document view's pixels at the current zoom; [viewportPoint] is where it appears. */
    private fun documentAnchor(scroll: JScrollPane, viewportPoint: Point, viewPoint: Point2D.Double): DocumentAnchor {
        val panel = documentPanel(scroll)
        val origin = panel?.let { SwingUtilities.convertPoint(it, 0, 0, scroll.viewport.view) } ?: Point()
        return DocumentAnchor(Point(viewportPoint), viewPoint, documentZoom,
            panel?.let { documentBandAnchor(it.zoomRows(), viewPoint.y - origin.y) },
            panel?.let { documentBandAnchor(it.zoomColumns(), viewPoint.x - origin.x) })
    }

    /** Scrolls a rebuilt, laid-out document so the anchored content is back under the same viewport point. */
    private fun placeDocument(key: CanvasTabKey, scroll: JScrollPane, anchor: DocumentAnchor, zoom: Double) {
        val view = scroll.viewport.view ?: return
        val ratio = zoom / anchor.zoom
        val panel = documentPanel(scroll)
        val origin = panel?.let { SwingUtilities.convertPoint(it, 0, 0, view) } ?: Point()
        val x = panel?.let { p -> anchor.column?.let { resolveDocumentBandAnchor(it, p.zoomColumns()) } }?.plus(origin.x)
            ?: (anchor.viewPoint.x * ratio)
        val y = panel?.let { p -> anchor.row?.let { resolveDocumentBandAnchor(it, p.zoomRows()) } }?.plus(origin.y)
            ?: (anchor.viewPoint.y * ratio)
        val extent = scroll.viewport.extentSize
        val width = maxOf(view.width, view.preferredSize.width)
        val height = maxOf(view.height, view.preferredSize.height)
        val target = Point(
            (x - anchor.viewportPoint.x).roundToInt().coerceIn(0, (width - extent.width).coerceAtLeast(0)),
            (y - anchor.viewportPoint.y).roundToInt().coerceIn(0, (height - extent.height).coerceAtLeast(0)),
        )
        scroll.viewport.viewPosition = target
        rememberDocumentScroll(key, target.y)
    }

    private fun layoutDocumentHost(key: CanvasTabKey) {
        // IDE editor pages can be hosted outside the standalone preview canvas.
        val host = pages[key]?.parent ?: this
        layoutZoomContent(if (SwingUtilities.isDescendingFrom(host, this)) this else host)
    }

    /** Rebuilds the card at [zoom] and keeps [anchor] in place, including after builders restore their old scroll. */
    private fun rebuildDocument(key: CanvasTabKey, zoom: Double, anchor: DocumentAnchor): JScrollPane? {
        documentZoom = zoom
        refreshCard(key)
        val card = cards[key] ?: return null
        val updated = bodyScroll(card) ?: return null
        layoutDocumentHost(key)
        placeDocument(key, updated, anchor, zoom)
        SwingUtilities.invokeLater {
            if (cards[key] !== card || documentZoom != zoom) return@invokeLater
            layoutDocumentHost(key)
            placeDocument(key, updated, anchor, zoom)
        }
        return updated
    }

    private fun wheelZoomDocument(key: CanvasTabKey, scroll: JScrollPane, factor: Double, point: Point) {
        if (!documentAnimated(key)) { zoomDocument(key, documentZoom * factor, point); return }
        val wheel = documentWheel?.takeIf { it.key == key && it.scroll === scroll } ?: run {
            finishWheelZoom()
            // Wheel frames scale a bitmap of the real layout, so the gesture starts from what is laid out now.
            stopDocumentMotion()
            val from = documentSnapshot(key, live = true) ?: run { zoomDocument(key, documentZoom * factor, point); return }
            DocumentWheelZoom(key, scroll, from).also { documentWheel = it }
        }
        val zoom = (wheel.view.zoom * factor).coerceIn(MIN_DOCUMENT_ZOOM, MAX_DOCUMENT_ZOOM)
        wheel.point = Point(point)
        wheel.settled = false
        wheel.view = documentZoomAround(wheel.view, SwingUtilities.convertPoint(scroll.viewport, point, documentHost(key)), zoom)
        showWheelZoom(wheel)
        cards[key]?.let(::descendantsOf)?.firstOrNull { it.name == "document-zoom-level" }
            ?.let { (it as? JLabel)?.text = "${(zoom * 100).roundToInt()}%" }
        wheel.settle.restart()
        advanceWheelZoom()
    }

    private fun showWheelZoom(wheel: DocumentWheelZoom) {
        val previous = wheel.previous
        if (previous == null) showDocumentOverlay(wheel.key, wheel.from, null, wheel.view, 0.0)
        else showDocumentOverlay(wheel.key, previous, wheel.from, wheel.view, wheel.fade)
    }

    private fun wheelDrift(wheel: DocumentWheelZoom) = abs(ln(wheel.view.zoom / documentZoom))

    /** Rebuilds when the bitmap has drifted too far from the real layout or the gesture has paused; ends once both agree. */
    private fun advanceWheelZoom() {
        val wheel = documentWheel ?: return
        if (wheel.rebuilding || wheel.previous != null) return
        val drift = wheelDrift(wheel)
        when {
            drift > ln(WHEEL_REBUILD_RATIO) || (wheel.settled && drift > ZOOM_EPSILON) -> rebuildWheelZoom(wheel)
            wheel.settled -> stopDocumentMotion()
        }
    }

    /** The live content under the pointer. Wheel views use the live layout's coordinates at 100%. */
    private fun wheelAnchor(wheel: DocumentWheelZoom): DocumentAnchor {
        val host = SwingUtilities.convertPoint(wheel.scroll.viewport, wheel.point, documentHost(wheel.key))
        val view = wheel.view
        return documentAnchor(wheel.scroll, wheel.point, Point2D.Double(
            (view.content.x + (host.x - view.screen.x) / view.zoom) * documentZoom,
            (view.content.y + (host.y - view.screen.y) / view.zoom) * documentZoom,
        ))
    }

    private fun rebuildWheelZoom(wheel: DocumentWheelZoom) {
        val key = wheel.key
        if (cards[key]?.let(::bodyScroll) !== wheel.scroll) { stopDocumentMotion(); return }
        wheel.rebuilding = true
        val updated = rebuildDocument(key, wheel.view.zoom, wheelAnchor(wheel)) ?: run { stopDocumentMotion(); return }
        wheel.scroll = updated
        val card = cards[key]
        // Runs after the rebuild has restored its final position.
        SwingUtilities.invokeLater {
            if (documentWheel !== wheel) return@invokeLater
            if (cards[key] !== card) { stopDocumentMotion(); return@invokeLater }
            val next = documentSnapshot(key, live = true) ?: run { stopDocumentMotion(); return@invokeLater }
            val shown = wheel.view
            // Steps taken during the rebuild continue from the new layout; the old bitmap fades out where it is.
            val view = documentZoomAround(next.view, SwingUtilities.convertPoint(updated.viewport, wheel.point, documentHost(key)), shown.zoom)
            wheel.previous = documentZoomRebase(wheel.from, shown, view)
            wheel.from = next
            wheel.view = view
            wheel.rebuilding = false
            wheel.fade = 0.0
            wheel.fadeStarted = System.nanoTime()
            showWheelZoom(wheel)
            if (animatedDocuments == null) wheel.fadeTimer.start()
        }
    }

    private fun wheelFadeFrame(progress: Double) {
        val wheel = documentWheel ?: return
        if (wheel.previous == null) return
        if (cards[wheel.key]?.let(::bodyScroll) !== wheel.scroll) { stopDocumentMotion(); return }
        if (progress < 1.0) {
            wheel.fade = easeDocumentZoom(progress)
            showWheelZoom(wheel)
            return
        }
        wheel.fadeTimer.stop()
        wheel.previous = null
        wheel.fade = 0.0
        showWheelZoom(wheel)
        advanceWheelZoom()
    }

    /** Settles the wheel gesture now, as if it had paused. */
    internal fun commitWheelZoom() {
        val wheel = documentWheel ?: return
        wheel.settle.stop()
        wheel.settled = true
        advanceWheelZoom()
    }

    /** Other moves start from the real layout, so a pending wheel zoom is applied without animation. */
    private fun finishWheelZoom() {
        val wheel = documentWheel ?: return
        val current = cards[wheel.key]?.let(::bodyScroll) === wheel.scroll
        val anchor = if (current && !wheel.rebuilding && wheelDrift(wheel) > ZOOM_EPSILON) wheelAnchor(wheel) else null
        val zoom = wheel.view.zoom
        stopDocumentMotion()
        anchor?.let { rebuildDocument(wheel.key, zoom, it) }
    }

    private fun focusDocumentFile(key: CanvasTabKey, index: Int) {
        finishWheelZoom()
        val scroll = cards[key]?.let(::bodyScroll) ?: return
        val from = DocumentView(documentZoom, Point(scroll.viewport.viewPosition), Point(pages[key]?.viewport?.viewPosition ?: Point()))
        documentHistory.getOrPut(key) { ArrayDeque() }.addLast(from)
        navigateDocument(key, maxOf(documentZoom, 1.0)) { updated ->
            val panel = descendantsOf(updated.viewport).filterIsInstance<DocumentColumnsPanel>().firstOrNull() ?: return@navigateDocument
            val section = panel.fileSection(index) ?: return@navigateDocument
            val target = SwingUtilities.convertPoint(panel, section.location, updated.viewport.view)
            val horizontalRoom = (updated.viewport.view.width - updated.viewport.extentSize.width).coerceAtLeast(0)
            updated.viewport.viewPosition = Point(target.x.coerceIn(0, horizontalRoom), target.y.coerceAtLeast(0))
            // Wide documents can scroll in the containing page instead of the document's own viewport.
            pages[key]?.viewport?.let { viewport ->
                val x = SwingUtilities.convertPoint(section, 0, 0, viewport.view).x
                val room = (viewport.view.width - viewport.extentSize.width).coerceAtLeast(0)
                viewport.viewPosition = Point(x.coerceIn(0, room), 0)
            }
            section.requestFocusInWindow()
        }
    }

    private fun backDocument(key: CanvasTabKey) {
        finishWheelZoom()
        val previous = documentHistory[key]?.removeLastOrNull() ?: return
        navigateDocument(key, previous.zoom) { updated ->
            updated.viewport.viewPosition = previous.position
            pages[key]?.viewport?.viewPosition = previous.pagePosition
            descendantsOf(updated.viewport).filterIsInstance<DocumentColumnsPanel>().firstOrNull()?.requestFocusInWindow()
        }
    }

    /**
     * Like wall navigation, ease scale and both scroll positions together, with one history entry per click.
     * Rebuilding the card per frame re-lays out every diff, so the card is rebuilt once at [targetZoom] and the
     * move is played on bitmaps of the views before and after.
     */
    private fun navigateDocument(key: CanvasTabKey, targetZoom: Double, duration: Double = NAVIGATION_MOTION_MS,
                                 zoomAnchor: Point? = null, place: (JScrollPane) -> Unit) {
        val from = if (documentAnimated(key)) documentSnapshot(key) else null
        stopDocumentMotion()
        documentZoom = targetZoom
        refreshCard(key)
        val card = cards[key] ?: return
        val updated = bodyScroll(card) ?: return
        val version = documentMotionVersion
        fun place(): Boolean {
            if (version != documentMotionVersion || cards[key] !== card || bodyScroll(card) !== updated) return false
            layoutDocumentHost(key)
            place(updated)
            return true
        }
        place()
        if (from == null) {
            // Card builders restore their old scroll asynchronously; this move owns the final position.
            SwingUtilities.invokeLater { place() }
            return
        }
        val motion = DocumentMotion(key, card, from, duration)
        documentMotion = motion
        // The previous view covers the rebuilt card until the destination can be captured.
        showDocumentOverlay(key, from, null, from.view, 0.0)
        SwingUtilities.invokeLater {
            if (!place() || documentMotion !== motion) return@invokeLater
            val to = documentSnapshot(key, live = true) ?: run { stopDocumentMotion(); return@invokeLater }
            if (zoomAnchor == null) motion.to = to else {
                // Scale the old bitmap around the anchor and fade in the new layout where it actually lands, rather than
                // sliding between layouts whose coordinates do not scale uniformly.
                val target = documentZoomAround(from.view, zoomAnchor, targetZoom)
                motion.toView = target
                motion.to = documentZoomRebase(to, to.view, target)
            }
            motion.started = System.nanoTime()
            if (animatedDocuments == null) motion.timer.start()
        }
    }

    internal fun documentMotionFrame(progress: Double) {
        if (documentWheel != null) { wheelFadeFrame(progress); return }
        val motion = documentMotion ?: return
        if (cards[motion.key] !== motion.card || (animatedDocuments == null && !motion.card.isShowing)) {
            stopDocumentMotion(); return
        }
        val to = motion.to ?: return
        if (progress >= 1.0) { stopDocumentMotion(); return }
        val t = easeDocumentZoom(progress)
        showDocumentOverlay(motion.key, motion.from, to, documentZoomViewAt(motion.from.view, motion.toView ?: to.view, t), t)
    }

    internal val documentMotionRunning: Boolean get() = documentMotion != null || documentWheel != null

    private fun stopDocumentMotion() {
        documentMotion?.timer?.stop()
        documentMotion = null
        documentWheel?.let { it.settle.stop(); it.fadeTimer.stop() }
        documentWheel = null
        documentOverlay?.detach()
        documentOverlay = null
        documentMotionVersion++
    }

    private fun documentAnimated(key: CanvasTabKey) = animatedDocuments
        ?: (cards[key]?.isShowing == true && ApplicationManager.getApplication()?.isUnitTestMode == false)

    private fun documentView(scroll: JScrollPane): JComponent? = scroll.viewport.view as? JComponent

    /** Overlays live above the card wherever it is hosted, including IDE editor tabs. */
    private fun documentHost(key: CanvasTabKey): Component = cards[key]?.let(SwingUtilities::getRootPane)?.layeredPane ?: this

    /** What readers currently see: the overlay frame while one is shown, otherwise the live document. */
    private fun documentSnapshot(key: CanvasTabKey, live: Boolean = false): DocumentZoomSnapshot? {
        if (!live) documentOverlay?.takeIf { it.view != null }?.snapshot(this)?.let { return it }
        val view = cards[key]?.let(::bodyScroll)?.let(::documentView) ?: return null
        return captureDocumentZoom(view, view.visibleRect, documentZoom, documentHost(key), documentBackground(view))
    }

    private fun documentBackground(view: Component): java.awt.Color =
        generateSequence(view) { it.parent }.firstOrNull { it.isOpaque }?.background ?: UIUtil.getPanelBackground()

    private fun showDocumentOverlay(key: CanvasTabKey, from: DocumentZoomSnapshot, to: DocumentZoomSnapshot?, view: DocumentZoomView, fade: Double) {
        val fill = cards[key]?.let(::bodyScroll)?.let(::documentView)?.let(::documentBackground) ?: UIUtil.getPanelBackground()
        val overlay = documentOverlay ?: DocumentZoomOverlay(fill).also { documentOverlay = it }
        overlay.fill = fill
        (documentHost(key) as? JLayeredPane)?.let(overlay::attach)
        overlay.show(from, to, view, fade)
    }

    private fun documentZoomControls(key: CanvasTabKey): JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, scaled(2), 0)).apply {
        isOpaque = false
        add(JButton("Back").apply {
            name = "document-zoom-back"
            toolTipText = "Return to the view before clicking a card or diff (Esc)"
            isEnabled = documentHistory[key]?.isNotEmpty() == true
            addActionListener { backDocument(key) }
        })
        add(JButton("−").apply {
            name = "document-zoom-out"
            toolTipText = "Zoom out (Ctrl/⌘ + wheel)"
            isEnabled = documentZoom > MIN_DOCUMENT_ZOOM
            addActionListener { zoomDocument(key, documentZoom / 1.2) }
        })
        add(JLabel("${(documentZoom * 100).roundToInt()}%").apply { name = "document-zoom-level" })
        add(JButton("+").apply {
            name = "document-zoom-in"
            toolTipText = "Zoom in (Ctrl/⌘ + wheel)"
            isEnabled = documentZoom < MAX_DOCUMENT_ZOOM
            addActionListener { zoomDocument(key, documentZoom * 1.2) }
        })
        add(JButton("100%").apply {
            name = "document-zoom-reset"
            toolTipText = "Reset document zoom"
            isEnabled = documentZoom != 1.0
            addActionListener { zoomDocument(key, 1.0) }
        })
    }

    private fun installDocumentWheelZoom(key: CanvasTabKey, scroll: JScrollPane) {
        descendantsOf(scroll.viewport).filterIsInstance<DocumentColumnsPanel>().forEach { panel ->
            panel.focusFile = { index -> focusDocumentFile(key, index) }
        }
        scroll.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "document-back")
        scroll.actionMap.put("document-back", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) { backDocument(key) }
        })
        // Intercept before the native listeners so a gesture scrolls exactly one pane,
        // and Ctrl/⌘+wheel only zooms, without first scrolling a nested block.
        val components = listOf<Component>(scroll, scroll.viewport) + descendantsOf(scroll.viewport)
        components.distinct().forEach { component ->
            val nativeListeners = component.mouseWheelListeners.toList()
            nativeListeners.forEach(component::removeMouseWheelListener)
            component.addMouseWheelListener { event ->
                if (event.isControlDown || event.isMetaDown) {
                    val point = SwingUtilities.convertPoint(event.component, event.point, scroll.viewport)
                    wheelZoomDocument(key, scroll, 1.15.pow(-event.preciseWheelRotation.coerceIn(-MAX_WHEEL_ROTATION, MAX_WHEEL_ROTATION)), point)
                    event.consume()
                } else {
                    // The overlay cannot follow a scroll, so the real layout takes over first.
                    if (documentWheel?.key == key) finishWheelZoom()
                    val panes = generateSequence(event.component) { it.parent }
                        .filterIsInstance<JScrollPane>()
                    val target = panes.firstOrNull { pane ->
                        val bar = if (event.isShiftDown) pane.horizontalScrollBar else pane.verticalScrollBar
                        pane.isWheelScrollingEnabled && bar.maximum - bar.minimum > bar.visibleAmount
                    } ?: scroll
                    if (target === component) {
                        val bar = if (event.isShiftDown) target.horizontalScrollBar else target.verticalScrollBar
                        val canMove = if (event.preciseWheelRotation < 0) bar.value > bar.minimum
                            else bar.value < bar.maximum - bar.visibleAmount
                        // JBScrollPane forwards wheel input to its parent at an edge; stop it here.
                        if (canMove) nativeListeners.forEach { it.mouseWheelMoved(event) }
                    } else target.dispatchEvent(SwingUtilities.convertMouseEvent(component, event, target))
                    // A scrollable block owns the gesture even at its limits.
                    event.consume()
                }
            }
        }
    }

    /** Both the card and its editor page can scroll; use whichever top edge currently hides more of the card. */
    private fun installStickyScrolling(key: CanvasTabKey, page: JScrollPane, card: JPanel) {
        val inner = bodyScroll(card) ?: return
        val target = descendantsOf(card).filterIsInstance<JComponent>()
            .firstOrNull { it is DocumentColumnsPanel || it is HierarchyColumnsPanel } ?: return
        if (target is DocumentColumnsPanel) {
            target.navigation.install(page)
            card.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "document-back")
            card.actionMap.put("document-back", inner.actionMap.get("document-back"))
        }
        stickyTargets[key] = target
        inner.viewport.addChangeListener { updateStickyScrolling(key, page) }
        if (page.getClientProperty("diff.stickyScrolling") != true) {
            page.putClientProperty("diff.stickyScrolling", true)
            page.viewport.addChangeListener { updateStickyScrolling(key, page) }
        }
    }

    private fun updateStickyScrolling(key: CanvasTabKey, page: JScrollPane) {
        val panel = stickyTargets[key] ?: return
        val inner = cards[key]?.let(::bodyScroll) ?: return
        val innerTop = SwingUtilities.convertPoint(inner.viewport, 0, 0, panel).y
        val outerTop = SwingUtilities.convertPoint(page.viewport, 0, 0, panel).y
        val top = maxOf(innerTop, outerTop)
        when (panel) {
            is DocumentColumnsPanel -> panel.updateStickyHeaders(top)
            is HierarchyColumnsPanel -> panel.updateStickyHeaders(top)
        }
    }

    private fun replaceCard(key: CanvasTabKey, card: JPanel) {
        val old = cards[key] ?: return
        val container = old.parent ?: return
        val horizontal = bodyScroll(old)?.horizontalScrollBar?.value
        container.remove(old)
        container.add(card, BorderLayout.CENTER)
        cards[key] = card
        pages[key]?.let { installStickyScrolling(key, it, card) }
        highlight(highlighted)
        container.revalidate(); container.repaint()
        horizontal?.let { value -> SwingUtilities.invokeLater {
            if (cards[key] === card) bodyScroll(card)?.horizontalScrollBar?.value = value
        } }
    }

    /** Follows topic assignments, summaries and chat state without rebuilding the other cards while the card stays on the canvas. */
    fun updateTopics(content: TopicsCard?) {
        if ((content == null) != (topics == null) || (content != null && cards[CanvasTabKey.Topics] == null)) {
            showNotes(state.copy(topics = content))
            return
        }
        if (content == topics) return
        state = state.copy(topics = content)
        topicsCardBuilder.update(content)
        refreshTopics()
    }

    private fun topicsCard(content: TopicsCard): JPanel = topicsCardBuilder.card(
        content, documentZoom, documentZoomControls(CanvasTabKey.Topics),
        summaryGenerationRunning, summarySession.topicStatusFor(CanvasTabKey.Topics), topicEntryMenu(content, topicActions) { topics },
    ) { installDocumentWheelZoom(CanvasTabKey.Topics, it) }

    private fun refreshTopics() = topicsCardBuilder.refresh()

    /** Follows grouping assignments, summaries and chat state without rebuilding the other cards while the card stays on the canvas. */
    fun updateGrouping(content: TopicsCard?) {
        if ((content == null) != (grouping == null) || (content != null && cards[CanvasTabKey.Grouping] == null)) {
            showNotes(state.copy(grouping = content))
            return
        }
        if (content == grouping) return
        state = state.copy(grouping = content)
        groupingCardBuilder.update(content)
        refreshGrouping()
    }

    private fun groupingCard(content: TopicsCard): JPanel = groupingCardBuilder.card(
        content, documentZoom, documentZoomControls(CanvasTabKey.Grouping),
        summaryGenerationRunning, summarySession.topicStatusFor(CanvasTabKey.Grouping), topicEntryMenu(content, groupingActions) { grouping },
    ) { installDocumentWheelZoom(CanvasTabKey.Grouping, it) }

    private fun refreshGrouping() = groupingCardBuilder.refresh()

    internal var requestResummaryInstructions: (SummaryNode, Boolean, ResummaryDestination) -> String? = { node, recursive, destination ->
        ResummaryDialog(this, node, recursive, destination).let { if (it.showAndGet()) it.instructions else null }
    }

    /**
     * Fill or regenerate one topic, file or level of the topic hierarchy without waiting for the whole hierarchy. Regeneration
     * rewrites topics only: file summaries are shared with the Summaries card, where Resummarize opens them as a version.
     */
    private fun topicEntryMenu(
        content: TopicsCard,
        actions: TopicCanvasActions = topicActions,
        currentContent: () -> TopicsCard? = { topics },
    ): ((String) -> JPopupMenu)? {
        val canSummarize = summaryActions.contextSummaries != null && actions.saveTopicSummaries != null
        if (!canSummarize && summaryActions.chatNode == null && summaryActions.openOnWall == null) return null
        val seeded by lazy { seedTopicSummaries(content.topicSummaries, content.summaries) }
        val nodes by lazy { seeded.tree.nodes.associateBy { it.identity } }
        return { identity ->
            JPopupMenu().apply {
                val node = nodes[identity] ?: return@apply
                val enabled = content.canRun && !summaryGenerationRunning
                fun item(label: String, enabled: Boolean, tooltip: String, action: () -> Unit) = add(JMenuItem(label).apply {
                    name = "topic-summary-action:$label"
                    isEnabled = enabled
                    toolTipText = tooltip
                    addActionListener { action() }
                })
                summaryActions.chatNode?.let { chat ->
                    if (node.files.isNotEmpty()) item("To Chat", true, "Chat about this entry in a new tab") {
                        chat(node, content.reading)
                    }
                }
                summaryActions.openOnWall?.let { openWall ->
                    if (node.file == null && node.files.isNotEmpty()) {
                        val sources = content.summaries.tree.nodes.mapNotNull { it.file }.associateBy { it.path }
                        item(vocabulary.openOnWall, true, vocabulary.openOnWallTooltip) {
                            openWall(node.files.mapNotNull(sources::get), wallTitle(node))
                        }
                    }
                }
                if (!canSummarize) return@apply
                item("Fill summaries", enabled && fillSummaryRequest(seeded, node).nodeIds.isNotEmpty(),
                    "Write the missing summaries of this entry and everything beneath it") { summarizeTopic(identity, null, actions, currentContent) }
                if (node.isTopicBranch) {
                    item("Resummarize", enabled, "Regenerate this topic's summary from the summaries beneath it") { summarizeTopic(identity, false, actions, currentContent) }
                    item("Resummarize all", enabled, "Regenerate this topic and every sub-topic beneath it") { summarizeTopic(identity, true, actions, currentContent) }
                }
            }
        }
    }

    /** Fills the missing summaries beneath [identity] when [recursive] is null, else regenerates it, saving into the review. */
    private fun summarizeTopic(
        identity: String,
        recursive: Boolean?,
        actions: TopicCanvasActions = topicActions,
        currentContent: () -> TopicsCard? = { topics },
    ) {
        if (summaryActions.contextSummaries == null) return
        val save = actions.saveTopicSummaries ?: return
        val content = currentContent() ?: return
        if (!content.canRun || summaryGenerationRunning) return
        val seeded = seedTopicSummaries(content.topicSummaries, content.summaries)
        val node = seeded.tree.nodes.firstOrNull { it.identity == identity } ?: return
        val request = if (recursive == null) fillSummaryRequest(seeded, node) else {
            val instructions = requestResummaryInstructions(node, recursive, ResummaryDestination.TOPICS) ?: return
            resummaryRequest(seeded, node, recursive, instructions) { it.isTopicBranch }
        }
        // The dialog is modal; the topics may have changed or another run started meanwhile.
        if (request.nodeIds.isEmpty() || currentContent() !== content || summaryGenerationRunning) return
        val start = if (request.regenerate) seeded.without(request.nodeIds) else seeded
        summarySession.startTopics(start, request, if (actions === groupingActions) CanvasTabKey.Grouping else CanvasTabKey.Topics, save)
    }

    /** Follows every summary and chat update without rebuilding the other cards while the cards stay on the canvas. */
    fun updateSummaries(content: SummariesCard?) {
        if ((content == null) != (summaries == null) || cards[CanvasTabKey.Summaries] == null) {
            showNotes(state.copy(summaries = content))
            return
        }
        if (content == summaries) return
        state = state.copy(summaries = content)
        summariesCardBuilder.update(content)
        summariesCardBuilder.refreshAll()
    }

    private fun replaceSummaryCard(key: CanvasTabKey, card: JPanel) {
        replaceCard(key, card)
        pages[key]?.let { page -> tabs.indexOfComponent(page).takeIf { it >= 0 } }?.let { index ->
            tabs.setTitleAt(index, tabTitle(key))
            tabs.setToolTipTextAt(index, tabTooltip(key))
        }
        onPagesChanged()
    }

    private fun fileViewCard(content: FileViewCard): JPanel = fileViewCardBuilder.card(
        content, documentZoom, documentZoomControls(CanvasTabKey.FileView),
    ) { installDocumentWheelZoom(CanvasTabKey.FileView, it) }

    /** Follows the analysis, topics, summaries and chat state without rebuilding the other cards. */
    fun updateFileView(content: FileViewCard?) {
        if ((content == null) != (fileView == null) || (content != null && cards[CanvasTabKey.FileView] == null)) {
            showNotes(state.copy(fileView = content))
            return
        }
        if (content == fileView) return
        fileViewCardBuilder.update(content)
        state = state.copy(fileView = content)
        refreshCard(CanvasTabKey.FileView)
    }

    private fun descendantsOf(component: Container): List<Component> =
        component.components.flatMap { listOf(it) + ((it as? Container)?.let(::descendantsOf) ?: emptyList()) }

    private fun refreshCard(key: CanvasTabKey) {
        if (key !in cards) return
        val card = when (key) {
            CanvasTabKey.Words, CanvasTabKey.DiffOverview, CanvasTabKey.Semantic, CanvasTabKey.Moves, CanvasTabKey.Wall -> extension?.card(key, snapshotCards)
            CanvasTabKey.Topics -> topics?.let(::topicsCard)
            CanvasTabKey.Grouping -> grouping?.let(::groupingCard)
            CanvasTabKey.Summaries, is CanvasTabKey.SummarySearch ->
                summariesCardBuilder.card(key)
            CanvasTabKey.FileView -> fileView?.let(::fileViewCard)
            CanvasTabKey.SymbolFlow -> snapshotCards.flowCard(changeFlow(notes))
            CanvasTabKey.Explanation, CanvasTabKey.Board -> null
            is ReviewNoteKey -> notes.firstOrNull { it.key == key }?.let(snapshotCards::noteCard)
                ?: sources.firstOrNull { it.key == key }?.let(snapshotCards::sourceCard)
                ?: extension?.card(key, snapshotCards)
        } ?: return
        replaceCard(key, card)
    }

    private fun emptyState(): JComponent = JPanel(VerticalLayout(scaled(6))).apply {
        isOpaque = false
        add(JLabel(vocabulary.canvasHeading).apply { font = scaledFont(JBFont.label().asBold().biggerOn(2f)) })
        vocabulary.canvasHints.forEach { add(JLabel(it).apply { foreground = UIUtil.getContextHelpForeground() }) }
        val gap = scaled(GAP)
        setBounds(gap, gap, scaled(600), preferredSize.height)
    }

    private fun cardBorder(highlighted: Boolean) =
        if (highlighted) JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2)
        else JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))

    private fun scaled(value: Int) = zoomScaled(value, zoom)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(zoom)
    override fun dispose() {
        stopDocumentMotion()
        summarySession.dispose()
        summariesCardBuilder.dispose()
        topicsCardBuilder.dispose()
        groupingCardBuilder.dispose()
        snapshotCards.dispose()
        extension?.dispose()
    }

    private companion object {
        const val MIN_DOCUMENT_ZOOM = 0.1
        const val MAX_DOCUMENT_ZOOM = 3.0
        const val NAVIGATION_MOTION_MS = 260.0
        const val ZOOM_MOTION_MS = 160.0
        const val WHEEL_SETTLE_MS = 140
        const val WHEEL_FADE_MS = 90.0
        /** How far a wheel bitmap may be scaled before the real layout is rebuilt under it. */
        const val WHEEL_REBUILD_RATIO = 1.5
        /** Caps one wheel event (one notch is 1), so fast flicks step instead of leaping. */
        const val MAX_WHEEL_ROTATION = 3.0
        const val ZOOM_EPSILON = 1e-6
        const val GAP = 20
    }
}
