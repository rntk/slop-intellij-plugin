package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.ClientProperty
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.CanvasTabKey
import dev.insight.review.ContextSummaryRequest
import dev.insight.review.ContextSummaryVersion
import dev.insight.review.ExplainResult
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewNoteKey
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ReviewedFiles
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.TopicOutline
import dev.insight.review.diffCardKey
import dev.insight.review.explanationCard
import dev.insight.review.fillSummaryRequest
import dev.insight.review.resummaryRequest
import dev.insight.review.seedTopicSummaries
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseWheelEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Job

/** Review artifacts, each in its own normally scrollable tab. */
internal class NoteCanvas(
    private val open: (String) -> Unit,
    private val remove: (ReviewNote) -> Unit,
    private val project: Project? = null,
    private val vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
    private val reviewed: ReviewedFiles = ReviewedFiles(),
    private val sourceActions: SourceCanvasActions = SourceCanvasActions(),
    private val summaryActions: SummaryCanvasActions = SummaryCanvasActions(),
    private val topicActions: TopicCanvasActions = TopicCanvasActions(),
    metricActions: MetricCardActions = MetricCardActions(),
) : JPanel(BorderLayout()), Disposable {
    private val openFileAt: (String, Int?) -> Unit = sourceActions.openFileAt ?: { path, _ -> open(path) }
    private val showFileLines: (String, IntRange) -> Unit = sourceActions.showFileLines ?: { path, _ -> sourceActions.showDiffCard(path) }
    private val openSummaryNode: (SummaryNode) -> Unit = summaryActions.openNode ?: { node -> open(node.path) }
    private val showSummaryNode: (SummaryNode) -> Unit = summaryActions.showNode ?: { node -> sourceActions.showDiffCard(node.path) }
    private val openSummaryLines: (SummaryNode, LineTrace) -> Unit = summaryActions.openLines ?: { node, _ -> openSummaryNode(node) }
    internal val tabs = JBTabbedPane().apply { tabLayoutPolicy = JBTabbedPane.SCROLL_TAB_LAYOUT }
    private val pages = mutableMapOf<CanvasTabKey, JScrollPane>()
    private val cards = mutableMapOf<CanvasTabKey, JPanel>()
    private val stickyTargets = mutableMapOf<CanvasTabKey, JComponent>()

    private var state = CanvasState()
    private val notes: List<ReviewNote> get() = state.notes
    private val sources: List<ReviewSourceBlock> get() = state.sources
    private val diffs: List<ReviewFile> get() = state.diffs
    private val topics: TopicsCard? get() = state.topics
    internal val topicsView = ChangeTopicsViewState(vocabulary.foldLargeTopics)
    private var topicsScroll = 0
    /** A fill or resummarization of part of the topic hierarchy; one at a time, saved into the review as it goes. */
    private var topicSummaryJob: Job? = null
    private var topicSummaryStatus: String? = null
    internal val topicSummariesRunning: Boolean get() = topicSummaryJob != null
    private val summaryGenerationRunning: Boolean get() = topicSummariesRunning || summaryVersions.any { it.running }
    private val summaries: SummariesCard? get() = state.summaries
    private val summaryVersions = mutableListOf<ContextSummaryVersion>()
    private var summaryVersionCount = 0
    internal val summariesView = ChangeSummariesViewState().also {
        it.mode = vocabulary.summariesMode
    }
    private val fileView: FileViewCard? get() = state.fileView
    private val fileViewCardBuilder = FileViewCanvasCard(vocabulary, FileViewCanvasActions(
        openFileAt = openFileAt, showFileLines = showFileLines, close = sourceActions.removeFileView,
        summarize = summaryActions.summarize, generateTopics = topicActions.generateTopics,
    ), project)
    private val words: WordChanges? get() = state.words
    private val diffOverview: WordChanges? get() = state.diffOverview
    private val semantic: SemanticResult? get() = state.semantic
    private val explanation: ExplainResult? get() = state.explanation
    private val metricsCards = MetricsCanvasCards(MetricsCanvasActions(
        open = open, openDirectoryDiff = summaryActions.openDirectoryDiff, removeWords = metricActions.removeWords,
        removeDiffOverview = metricActions.removeDiffOverview, removeSemantic = metricActions.removeSemantic, refresh = ::refreshCard,
    ))

    /**
     * The summaries tab, or a search tab over the same summaries: its own view and searches, reading position and search
     * field.
     */
    private inner class SummariesTab(val key: CanvasTabKey, val view: ChangeSummariesViewState, val search: Boolean = false) {
        val review = SummaryReviewSession(reviewed)
        var scroll = 0
        /** The sideways reading position of the split view's one-line summaries. */
        var scrollX = 0
        var treeScrollX = 0
        var treeWidth: Int? = null
        var diffPanel: ChangeSummaryDiffPanel? = null
        var version: Int? = null
        val trace = SummaryTraceSession(view, { reading(this) }, openSummaryLines,
            update = { updateTracePresentation(key) }, refresh = { refreshSummaries(this) })
        /** Kept across rebuilds of the card, so typing never loses focus or caret. */
        val searchField = SearchTextField(false).apply {
            name = "summaries-search"
            textEditor.emptyText.text = "Filter summaries"
            toolTipText = "Hide summaries whose name, path or text does not match the typed words (fuzzy)"
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (view.fuzzy == text) return
                    view.fuzzy = text
                    refreshSummaries(this@SummariesTab)
                }
            })
        }
    }

    private val mainSummaries = SummariesTab(CanvasTabKey.Summaries, summariesView)
    private val searchTabs = mutableListOf<SummariesTab>()
    private var searchTabCount = 0
    private fun summaryTabs() = listOf(mainSummaries) + searchTabs
    private fun summaryTab(key: CanvasTabKey) = summaryTabs().firstOrNull { it.key == key }
    private fun version(tab: SummariesTab) = summaryVersions.firstOrNull { it.id == tab.version }
    private fun reading(tab: SummariesTab): ReviewSummaries? = version(tab)?.summaries ?: summaries?.summaries

    private fun retainSummaryVersions(tree: ReviewSummaryTree) {
        summaryVersions.forEach { version ->
            if (version.summaries.tree !== tree) {
                if (version.running && version.summaries.tree.nodes.map { it.key } != tree.nodes.map { it.key }) {
                    version.job?.cancel()
                    version.running = false
                    version.error = "Source changed; resummarize with context again to update this version."
                }
                version.summaries = version.summaries.retainedIn(tree)
                version.request = version.request.copy(nodeIds = tree.summarizable.filter { it.identity in version.related }.mapTo(hashSetOf()) { it.id })
            }
        }
    }
    /** The topics card's search fields, kept across rebuilds of the card so typing never loses focus or caret. */
    private val topicSearchField = topicsSearchField("topics-topic-search", "Filter topics",
        "Hide files whose topic does not match the typed words (fuzzy)", { topicsView.topicFilter }) { topicsView.topicFilter = it }
    private val topicSummarySearchField = topicsSearchField("topics-summary-search", "Filter summaries",
        "Hide files whose name, path or summary does not match the typed words (fuzzy)", { topicsView.summaryFilter }) { topicsView.summaryFilter = it }

    private fun topicsSearchField(name: String, empty: String, tooltip: String, get: () -> String, set: (String) -> Unit) =
        SearchTextField(false).apply {
            this.name = name
            textEditor.emptyText.text = empty
            toolTipText = tooltip
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (get() == text) return
                    set(text)
                    refreshTopics()
                }
            })
        }
    private val topicsTrace = SummaryTraceSession(topicsView, { topics?.reading }, openSummaryLines,
        update = { updateTracePresentation(CanvasTabKey.Topics) }) { refreshCard(CanvasTabKey.Topics) }
    private val topicsReview = SummaryReviewSession(reviewed)

    /** Marks restyle the live cards; rebuilding them would reset both viewports and make the reading position jump. */
    private fun toggleReviewed(node: SummaryNode, value: Boolean) {
        if (summaryActions.markReviewed != null) summaryActions.markReviewed.invoke(node, value) else reviewed.set(node.files, value)
        summaryTabs().forEach { updateReview(it.key, it.review, summaries?.summaries) }
        updateReview(CanvasTabKey.Topics, topicsReview, topics?.reading)
    }

    private fun updateReview(key: CanvasTabKey, session: SummaryReviewSession, content: ReviewSummaries?) {
        if (key !in cards) return
        content?.let(session::sync)
        restyleReview(key)
    }

    private fun restyleReview(key: CanvasTabKey) {
        val card = cards[key] ?: return
        descendantsOf(card).forEach { (it as? JComponent)?.let(::updateReviewPresentation) }
    }

    private val editors = mutableMapOf<ReviewNoteKey, DiffSourceEditor>()
    private val diffEditors = mutableMapOf<ReviewNoteKey, DiffFileEditor>()
    private var highlighted = emptySet<String>()
    private var focused: CanvasTabKey? = null
    private val zoom = 1.0
    private var documentZoom = 1.0

    init {
        minimumSize = Dimension(100, 100)
        background = UIUtil.getPanelBackground()
        add(tabs, BorderLayout.CENTER)
    }

    fun showNotes(state: CanvasState) {
        summaryTabs().forEach { it.diffPanel?.let(Disposer::dispose); it.diffPanel = null }
        if (state.topics == null) topicsScroll = 0
        fileViewCardBuilder.update(state.fileView)
        this.state = state
        metricsCards.update(state.wordFiles, state.semantic)
        val summaries = state.summaries
        if (topics == null) topicsReview.stop()
        topics?.let { topicsTrace.retain(it.reading) } ?: topicsTrace.stop()
        if (summaries == null) {
            summaryTabs().forEach { it.trace.stop(); it.review.stop() }
            searchTabs.clear()
            summaryVersions.filter { it.running }.forEach { it.job?.cancel(); it.running = false }
        } else {
            retainSummaryVersions(summaries.summaries.tree)
            summaryTabs().forEach { it.view.update(summaries.summaries.tree); it.trace.retain(reading(it)!!) }
        }
        val sourcesByKey = sources.associateBy { it.key }
        editors.entries.removeIf { (key, editor) ->
            (sourcesByKey[key] != editor.block).also { if (it) editor.dispose() }
        }
        val diffsByKey = diffs.associateBy { it.diffKey }
        diffEditors.entries.removeIf { (key, editor) ->
            (diffsByKey[key] != editor.file).also { if (it) Disposer.dispose(editor) }
        }
        val selected = pages.entries.firstOrNull { it.value === tabs.selectedComponent }?.key
        val bodyScrolls = cards.mapValues { bodyScroll(it.value)?.viewport?.viewPosition?.let(::Point) }
        val scrolls = pages.mapValues { Point(it.value.viewport.viewPosition) }
        tabs.removeAll(); cards.clear(); pages.clear(); stickyTargets.clear()
        val flow = changeFlow(notes).takeIf { it.rows.isNotEmpty() }
        val summaryCards = summaries?.let { content -> summaryTabs().map { it.key to summariesCard(it, content) } }.orEmpty()
        val built = listOfNotNull(explanation?.let { CanvasTabKey.Explanation to explanationCard(it) },
            topics?.let { CanvasTabKey.Topics to topicsCard(it) },
            words?.let { CanvasTabKey.Words to metricsCards.wordsCard(it) }, diffOverview?.let { CanvasTabKey.DiffOverview to metricsCards.wordsCard(it, overview = true) },
            semantic?.let { CanvasTabKey.Semantic to metricsCards.semanticCard(it) }).toMap() +
            summaryCards +
            listOfNotNull(fileView?.let { CanvasTabKey.FileView to fileViewCard(it) }, flow?.let { CanvasTabKey.SymbolFlow to flowCard(it) }).toMap() +
            notes.associate { it.key to card(it) } +
            sources.associate { it.key to sourceCard(it) } +
            diffs.associate { it.diffKey to diffCard(it) }
        built.forEach { (key, card) ->
            cards[key] = card
            val page = page(card)
            pages[key] = page
            installStickyScrolling(key, page, card)
            tabs.addTab(tabTitle(key), page)
            tabs.setToolTipTextAt(tabs.tabCount - 1, tabTooltip(key))
            bodyScrolls[key]?.let { position -> SwingUtilities.invokeLater {
                if (cards[key] === card) bodyScroll(card)?.viewport?.viewPosition = position
            } }
            scrolls[key]?.let { position -> SwingUtilities.invokeLater {
                if (pages[key] === page) page.viewport.viewPosition = position
            } }
        }
        pages[selected]?.let { tabs.selectedComponent = it }
        if (built.isEmpty()) tabs.addTab("Review", JBScrollPane(emptyState()))
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

    internal fun focusWords() { focusCard(CanvasTabKey.Words) }
    internal fun focusDiffOverview() { focusCard(CanvasTabKey.DiffOverview) }
    internal fun focusSemantic() { focusCard(CanvasTabKey.Semantic) }
    internal fun focusExplanation() { focusCard(CanvasTabKey.Explanation) }

    /** Selects the source tab. */
    internal fun focusSource(range: ReviewSourceRange) { focusCard(ReviewSourceBlock(range, "").key) }

    internal fun focusFileView() { focusCard(CanvasTabKey.FileView) }

    internal fun focusSummaries() {
        focusCard(CanvasTabKey.Summaries)
    }

    private fun focusCard(key: CanvasTabKey) {
        val page = pages[key] ?: return
        tabs.selectedComponent = page
        focused = key
        highlight(highlighted)
    }

    private fun tabTooltip(key: CanvasTabKey): String = summaryTab(key)?.takeIf { it.search }?.let { tab ->
        (listOf("Summaries") + searchPath(tab).map { "“$it”" }).joinToString(" › ")
    } ?: (key as? ReviewNoteKey)?.paths.orEmpty().joinToString(", ").ifEmpty { tabTitle(key) }

    /** A search tab's queries, oldest first. */
    private fun searchPath(tab: SummariesTab): List<String> = tab.view.crumbs.map { it.query }

    private fun tabTitle(key: CanvasTabKey): String = when (key) {
        CanvasTabKey.Topics -> "Topics"
        CanvasTabKey.Summaries -> "Summaries"
        is CanvasTabKey.SummarySearch -> summaryTab(key)?.let(::searchPath)?.lastOrNull()?.let {
            "Search · " + if (it.length > 32) it.take(31) + "…" else it
        } ?: "Search"
        CanvasTabKey.FileView -> "File · ${fileView?.file?.path?.substringAfterLast('/').orEmpty()}"
        CanvasTabKey.Words -> "Word changes"
        CanvasTabKey.DiffOverview -> "Diff overview"
        CanvasTabKey.Semantic -> "Semantic changes"
        CanvasTabKey.Explanation -> if (explanation?.running == true) "Explaining…" else "Explanation"
        CanvasTabKey.SymbolFlow -> "Symbol flow"
        is ReviewNoteKey -> {
            val path = key.paths.firstOrNull()?.substringAfterLast('/') ?: "Note"
            val source = sources.firstOrNull { it.key == key }
            when {
                source != null -> "Source · $path · ${source.range.side} · ${source.range.startLine}–${source.range.endLine}"
                diffs.any { it.diffKey == key } -> "Diff · $path"
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

    private fun bodyScroll(card: JPanel) =
        (card.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as? JScrollPane

    /** Zoom the document around a point in its viewport, keeping that content under the pointer. */
    private fun zoomDocument(key: CanvasTabKey, requested: Double, anchor: Point? = null) {
        val old = cards[key]?.let(::bodyScroll) ?: return
        val next = requested.coerceIn(MIN_DOCUMENT_ZOOM, MAX_DOCUMENT_ZOOM)
        if (next == documentZoom) return
        val point = anchor ?: Point(old.viewport.width / 2, old.viewport.height / 2)
        val previous = Point(old.viewport.viewPosition)
        val ratio = next / documentZoom
        documentZoom = next
        refreshCard(key)
        val updated = cards[key]?.let(::bodyScroll) ?: return
        val target = Point(
            ((previous.x + point.x) * ratio - point.x).roundToInt().coerceAtLeast(0),
            ((previous.y + point.y) * ratio - point.y).roundToInt().coerceAtLeast(0),
        )
        when (key) {
            CanvasTabKey.Topics -> topicsScroll = target.y
            CanvasTabKey.FileView -> fileViewCardBuilder.scroll = target.y
            else -> summaryTab(key)?.scroll = target.y
        }
        SwingUtilities.invokeLater {
            if (cards[key]?.let(::bodyScroll) === updated) updated.viewport.viewPosition = target
        }
    }

    private fun documentZoomControls(key: CanvasTabKey): JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, scaled(2), 0)).apply {
        isOpaque = false
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
        val listener = java.awt.event.MouseWheelListener { event: MouseWheelEvent ->
            if (!event.isControlDown && !event.isMetaDown) {
                if (event.component !== scroll) {
                    scroll.dispatchEvent(SwingUtilities.convertMouseEvent(event.component, event, scroll))
                    event.consume()
                }
                return@MouseWheelListener
            }
            val point = SwingUtilities.convertPoint(event.component, event.point, scroll.viewport)
            zoomDocument(key, documentZoom * 1.15.pow(-event.preciseWheelRotation), point)
            event.consume()
        }
        scroll.addMouseWheelListener(listener)
        scroll.viewport.addMouseWheelListener(listener)
        descendantsOf(scroll.viewport).forEach { it.addMouseWheelListener(listener) }
    }

    /** Both the card and its editor page can scroll; use whichever top edge currently hides more of the card. */
    private fun installStickyScrolling(key: CanvasTabKey, page: JScrollPane, card: JPanel) {
        val inner = bodyScroll(card) ?: return
        val target = descendantsOf(card).filterIsInstance<JComponent>()
            .firstOrNull { it is DocumentColumnsPanel || it is HierarchyColumnsPanel } ?: return
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

    private fun flowCard(flow: ChangeFlow): JPanel {
        val width = cardWidth(CARD_WIDTH)
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Change flow · Verified calls between symbol notes").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Steps are the assistant's symbol notes; each arrow is a resolved call in the source snapshot. " +
                    "Order follows the caller's source, not runtime; conditions and intermediate calls are not shown."
            }, BorderLayout.CENTER)
        }
        val body = JPanel(VerticalLayout(scaled(6))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(ChangeFlowDiagram(flow, width - scaled(2 * PADDING + 4), zoom, ::focusNote))
        }
        card.add(header, BorderLayout.NORTH)
        card.add(scrollBody(body, width, scaled(MAX_BODY_HEIGHT)), BorderLayout.CENTER)
        return card
    }

    internal fun chooseWordScope(scope: WordScope) = metricsCards.chooseWordScope(scope)

    /**
     * The change as one document with its topic rail, or as the topic hierarchy with each file's summary; mode changes,
     * folding and summary updates rebuild only this card, in place.
     */
    private fun topicsCard(content: TopicsCard): JPanel {
        val topics = content.topics
        // The header, levels and width follow every file, so typing in the search fields does not move them.
        val outline = topicsView.fullOutline(content)
        val shown = topicsView.outline(content)
        val hierarchy = topicsView.mode == ChangeTopicsMode.HIERARCHY
        val document = !hierarchy
        val viewZoom = if (document) documentZoom else zoom
        val width = if (hierarchy) cardWidth(HierarchyColumnsPanel.defaultWidth(outline.depth))
            else ChangeTopicsPanel.defaultWidth(topicsView.railDepth(outline), viewZoom)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            val matching = if (topicsView.filtering) " · ${shown.files.size} shown" else ""
            add(JLabel("${vocabulary.topicsTitle} · ${topics.groups.size} topics · ${topics.labels.size} of ${files(topics.files.size)}$matching").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = vocabulary.topicsTooltip
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                if (document) add(documentZoomControls(CanvasTabKey.Topics))
                if (outline.depth > 0) add(topicLevels(outline))
                val group = ButtonGroup()
                ChangeTopicsMode.entries.forEach { mode ->
                    add(JToggleButton(if (mode == ChangeTopicsMode.DOCUMENT) "Document" else "Hierarchy", topicsView.mode == mode).apply {
                        font = scaledFont(JBFont.small())
                        cursor = Cursor.getDefaultCursor()
                        toolTipText = if (mode == ChangeTopicsMode.DOCUMENT) "Every file in topic order, with its summary and the topic rail"
                            else "Topics broad to specific, left to right, with each file's summary"
                        group.add(this)
                        addActionListener { topicsView.mode = mode; topicsScroll = 0; refreshCard(CanvasTabKey.Topics) }
                    })
                }
                add(JButton(when {
                    topics.labels.isEmpty() -> "Classify"
                    topics.complete -> "Regenerate"
                    else -> "Continue"
                }).apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && topics.files.isNotEmpty()
                    toolTipText = if (topics.complete) "Classify every file again (/topics)" else "Give every remaining file a topic (/topics)"
                    addActionListener { topicActions.generateTopics() }
                })
                if (hierarchy) add(JButton("Fill summaries").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && !summaryGenerationRunning && outline.nodes.isNotEmpty() && !content.topicSummaries.complete
                    toolTipText = "Fill missing summaries for every topic and subtopic, including folded topics"
                    addActionListener { topicActions.fillTopicSummaries() }
                })
                val summaries = content.summaries
                if (summaries.total > 0 && !summaries.complete) add(JButton(if (summaries.count == 0) "Summarize" else "Continue summaries").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun
                    toolTipText = vocabulary.topicsSummarizeTooltip
                    addActionListener { summaryActions.summarize() }
                })
                add(InplaceButton(IconButton("Remove change topics", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { topicActions.removeTopics() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            val actions = ChangeTopicActions(openFile = open, showDiffCard = sourceActions.showDiffCard, refresh = { refreshCard(CanvasTabKey.Topics) },
                trace = topicsTrace::trace, openNode = openSummaryNode, showNode = showSummaryNode,
                review = topicsReview.presentation(content.reading, ::toggleReviewed),
                entryMenu = topicEntryMenu(content))
            val inner = width - scaled(2 * PADDING + 4) - scaled(16)
            add(JPanel(VerticalLayout(scaled(8))).apply {
                isOpaque = false
                traceStatusLines(topicsView, topicsTrace, inner).forEach(::add)
                topicSummaryStatus?.let { status ->
                    add(JLabel(status).apply { name = "topic-summary-status"; foreground = UIUtil.getContextHelpForeground(); toolTipText = status })
                }
                if (shown.files.isEmpty() && outline.files.isNotEmpty()) add(JLabel("No files match the search.").apply {
                    name = "topics-no-matches"
                    font = scaledFont(JBFont.label()).deriveFont(java.awt.Font.ITALIC); foreground = UIUtil.getContextHelpForeground()
                })
                else add(if (hierarchy) ChangeTopicHierarchyPanel(content, topicsView, inner, viewZoom, actions, vocabulary)
                    else ChangeTopicsPanel(content, topicsView, inner, viewZoom, actions, vocabulary, project))
            }, BorderLayout.CENTER)
        }
        val search = JPanel(FlowLayout(FlowLayout.RIGHT, scaled(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 12, 4, 8))
            for ((field, text) in listOf(topicSearchField to topicsView.topicFilter, topicSummarySearchField to topicsView.summaryFilter))
                add(field.apply {
                    preferredSize = Dimension(scaled(220), preferredSize.height)
                    if (this.text != text) this.text = text
                })
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(search, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        card.add(JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            // A deep hierarchy may scroll sideways; leave room for that scroll bar.
            val sideways = if (body.preferredSize.width > width) horizontalScrollBar.preferredSize.height else 0
            preferredSize = Dimension(width, minOf(body.preferredSize.height + sideways, scaled(MAX_TOPICS_HEIGHT)))
            verticalScrollBar.unitIncrement = scaled(16)
            horizontalScrollBar.unitIncrement = scaled(16)
            if (document) installDocumentWheelZoom(CanvasTabKey.Topics, this)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = topicsScroll
            verticalScrollBar.addAdjustmentListener { topicsScroll = it.value }
            SwingUtilities.invokeLater {
                verticalScrollBar.value = restore
                // A traced match scrolls into view once the rebuilt card is laid out; a newer rebuild owns the reveal.
                if (cards[CanvasTabKey.Topics] !== card) return@invokeLater
                val target = topicsView.reveal ?: return@invokeLater
                topicsView.reveal = null
                card.validate()
                revealIn(body, target)
            }
        }, BorderLayout.CENTER)
        return card
    }

    /** Level n shows topic levels 1..n+1 and folds the rest, as in the article hierarchy; the last level shows every file. */
    private fun topicLevels(outline: TopicOutline): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        val selector = JComboBox((0..outline.depth).map { level ->
            if (level == outline.depth) "Files" else "${level + 1}"
        }.toTypedArray()).apply {
            name = "topic-level-selector"
            font = scaledFont(JBFont.small())
            selectedIndex = topicsView.effectiveLevel(outline)
            toolTipText = if (topicsView.mode == ChangeTopicsMode.DOCUMENT)
                "Show topic rail levels through the selected level; Files shows every rail level"
            else "Show topic levels through the selected level; Files shows every topic and file"
            addActionListener { topicsView.selectLevel(selectedIndex, outline); refreshCard(CanvasTabKey.Topics) }
        }
        add(JLabel("Levels").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
            labelFor = selector
        })
        add(selector)
    }

    /** Follows topic assignments, summaries and chat state without rebuilding the other cards while the card stays on the canvas. */
    fun updateTopics(content: TopicsCard?) {
        if ((content == null) != (topics == null) || (content != null && cards[CanvasTabKey.Topics] == null)) {
            showNotes(state.copy(topics = content))
            return
        }
        if (content == topics) return
        state = state.copy(topics = content)
        content?.let { topicsTrace.retain(it.reading) }
        refreshTopics()
    }

    /** Rebuilding moves the search fields into the new card; typing goes on where it was. */
    private fun refreshTopics() {
        val typing = listOf(topicSearchField, topicSummarySearchField).firstOrNull { it.textEditor.isFocusOwner }
        refreshCard(CanvasTabKey.Topics)
        typing?.textEditor?.requestFocusInWindow()
    }

    /**
     * The summary tree as a wide reading card, as an outline, as the folder hierarchy, or as a document with its folder rail;
     * mode changes, expanding, selecting and new summaries rebuild only this card, in place. A search tab shows the same
     * card with only the summaries its searches found related, and its queries as breadcrumbs.
     */
    private fun summariesCard(tab: SummariesTab, original: SummariesCard): JPanel {
        tab.diffPanel?.let(Disposer::dispose)
        tab.diffPanel = null
        val version = version(tab)
        val content = original.copy(summaries = reading(tab) ?: original.summaries,
            running = version?.running ?: original.running)
        val summariesView = tab.view
        val summariesTrace = tab.trace
        val key = tab.key
        val tree = content.summaries.tree
        summariesView.update(tree)
        val hierarchy = summariesView.mode == ReviewSummariesMode.HIERARCHY
        val document = summariesView.mode == ReviewSummariesMode.DOCUMENT
        val explorer = summariesView.mode == ReviewSummariesMode.EXPLORER
        val splitMode = summariesView.mode == ReviewSummariesMode.SPLIT
        val diffMode = summariesView.mode == ReviewSummariesMode.DIFF
        if (explorer) summariesView.updateExplorer(content.summaries)
        val viewZoom = if (document) documentZoom else zoom
        val branchLevels = ChangeSummariesViewState.branchLevels(tree)
        val width = when {
            hierarchy -> cardWidth(HierarchyColumnsPanel.defaultWidth(branchLevels))
            document -> DocumentColumnsPanel.defaultWidth(ChangeSummaryDocumentPanel.railDepth(tree, summariesView), viewZoom)
            else -> cardWidth(SUMMARIES_WIDTH)
        }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel(vocabulary.summariesTitle + if (tab.search) " · Search" else "").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = vocabulary.summariesTooltip
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                if (document) add(documentZoomControls(key))
                val summaries = content.summaries
                if (branchLevels > 0 && !explorer) add(summaryLevels(tab, tree, branchLevels))
                if (splitMode) add(JCheckBox("Soft-wrap", summariesView.softWrap).apply {
                    name = "summaries-soft-wrap"
                    isOpaque = false
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    toolTipText = "Wrap summaries to the available width and expand their tree rows to match"
                    addActionListener { summariesView.softWrap = isSelected; refreshSummaries(tab) }
                })
                add(JCheckBox("Tags cloud", summariesView.tagsCloud).apply {
                    name = "summaries-tags-cloud"
                    isOpaque = false
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    toolTipText = "Size summary words by their importance in the child summaries"
                    addActionListener { summariesView.tagsCloud = isSelected; refreshSummaries(tab) }
                })
                val group = ButtonGroup()
                ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF || vocabulary == ReviewVocabulary.DIFF }.forEach { mode ->
                    add(JToggleButton(when (mode) {
                        ReviewSummariesMode.OUTLINE -> "Outline"
                        ReviewSummariesMode.HIERARCHY -> "Hierarchy"
                        ReviewSummariesMode.DOCUMENT -> "Document"
                        ReviewSummariesMode.EXPLORER -> "Explorer"
                        ReviewSummariesMode.SPLIT -> "Split"
                        ReviewSummariesMode.DIFF -> "Diff"
                    }, summariesView.mode == mode).apply {
                        name = "summaries-mode:${mode.name.lowercase()}"
                        font = scaledFont(JBFont.small())
                        cursor = Cursor.getDefaultCursor()
                        toolTipText = when (mode) {
                            ReviewSummariesMode.OUTLINE -> vocabulary.summariesOutlineTooltip
                            ReviewSummariesMode.HIERARCHY -> "Folders broad to specific, left to right, with each file's summary"
                            ReviewSummariesMode.DOCUMENT -> "Every file in folder order, with its summary and file and folder cards"
                            ReviewSummariesMode.EXPLORER -> "Browse one level of summaries at a time, with Back and breadcrumbs"
                            ReviewSummariesMode.SPLIT -> "The file tree beside aligned summaries; hover a row to match it with its summary"
                            ReviewSummariesMode.DIFF -> "The selected file, or every file in the selected folder, in the IDE’s native diff viewer beside a navigable file tree"
                        }
                        group.add(this)
                        addActionListener { summariesView.mode = mode; tab.scroll = 0; refreshSummaries(tab) }
                    })
                }
                add(JButton(when {
                    version != null -> "Regenerate original"
                    summaries.total == 0 || summaries.count == 0 -> "Summarize"
                    summaries.complete -> "Regenerate"
                    else -> "Continue"
                }).apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && summaries.total > 0 && !summaryGenerationRunning
                    toolTipText = if (summaries.complete) "Write every summary again (/summaries)"
                        else vocabulary.summarizeTooltip
                    addActionListener { summaryActions.summarize() }
                })
                add(if (tab.search) InplaceButton(IconButton("Close search tab", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { closeSearchTab(tab) }
                        .apply { name = "close-search-tab"; cursor = Cursor.getDefaultCursor() }
                    else InplaceButton(IconButton("Remove change summaries", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { summaryActions.removeSummaries() }
                        .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        val actions = ChangeSummaryActions(
            toggle = { identity -> summariesView.toggle(identity); refreshSummaries(tab) },
            select = { identity -> summariesView.select(identity); refreshSummaries(tab) },
            openFile = open,
            showDiffCard = sourceActions.showDiffCard,
            openNode = openSummaryNode,
            showNode = showSummaryNode,
            openWholeFile = summaryActions.openWholeFile,
            openDirectory = summaryActions.openDirectoryDiff?.let { open ->
                val files = tree.nodes.mapNotNull { (it.file as? ReviewFile)?.let { file -> file.path to file } }.toMap()
                val navigate: (SummaryNode) -> Unit = { node -> open(node.files.mapNotNull(files::get)) }
                navigate
            },
            trace = summariesTrace::trace,
            searchSelection = summariesTrace::searchSelection,
            searchTab = { context: SummaryContext -> openSearchTab(tab, context) },
            follow = summariesTrace::searchContext,
            explain = summaryActions.explainNode,
            toChat = summaryActions.chatNode?.let { chat -> { node -> chat(node, content.summaries) } },
            inspect = project?.let { project ->
                val sources = tree.nodes.mapNotNull { it.file }.associateBy { it.path }
                val inspect: (SummaryNode) -> Unit = { node ->
                    inspectReviewFiles(project, if (node.kind == SummaryKind.ROOT) vocabulary.rootName else node.path, node.files.mapNotNull(sources::get))
                }
                inspect
            },
            resummarize = { node: SummaryNode, recursive: Boolean -> resummarize(tab, node, recursive) }.takeIf { summaryActions.contextSummaries != null },
            resummaryEnabled = content.canRun && !summaryGenerationRunning,
            review = tab.review.presentation(content.summaries, ::toggleReviewed),
        )
        val inner = width - scaled(2 * PADDING + 4) - scaled(16)
        val split = if (splitMode) ChangeSummarySplitPanel(content, summariesView, zoom, actions, vocabulary) else null
        val diff = if (diffMode) ChangeSummaryDiffPanel(content, summariesView, zoom, actions, project).also { tab.diffPanel = it } else null
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(JPanel(VerticalLayout(0)).apply {
                isOpaque = false
                add(summaryVersionRow(tab, original))
                add(searchRow(tab))
                if (explorer) add(summaryExplorerNavigation(tab, tree))
                if (split != null) add(JLabel(summariesStatus(content, vocabulary.noFiles) +
                    " · Click a summary to read it. Double-click to ${if (vocabulary == ReviewVocabulary.DIFF) "open the diff" else "open the file"}. Drag the divider to resize.").apply {
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.empty(6, PADDING)
                })
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        card.add(JPanel(VerticalLayout(scaled(4))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, PADDING, 8, PADDING)
            traceStatusLines(summariesView, summariesTrace, inner, selection = true,
                where = if (tab.search) "highlighted in the view; the rest are hidden in this tab." else "highlighted in the view.").forEach(::add)
        }, BorderLayout.SOUTH)
        if (diff != null) {
            card.add(diff.withTree(width, scaled(MAX_SUMMARIES_HEIGHT), tab.treeWidth) { tab.treeWidth = it }, BorderLayout.CENTER)
            return card
        }
        val body = if (split != null) split.summariesColumn else JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(when {
                hierarchy -> JPanel(VerticalLayout(scaled(8))).apply {
                    isOpaque = false
                    add(JLabel(summariesStatus(content, vocabulary.noFiles)).apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() })
                    add(ChangeSummaryHierarchyPanel(content, summariesView, inner, viewZoom, actions, vocabulary))
                }
                document -> JPanel(VerticalLayout(scaled(8))).apply {
                    isOpaque = false
                    add(JLabel(summariesStatus(content, vocabulary.noFiles)).apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() })
                    add(ChangeSummaryDocumentPanel(content, summariesView, inner, viewZoom, actions, vocabulary, project))
                }
                else -> ChangeSummariesPanel(content, summariesView, inner, zoom, actions, vocabulary,
                    browse = if (explorer) { identity ->
                        summariesView.browse(identity, tree)
                        tab.scroll = 0
                        refreshSummaries(tab)
                    } else null)
            }, BorderLayout.CENTER)
        }
        val maxBody = scaled(if (document) MAX_TOPICS_HEIGHT else MAX_SUMMARIES_HEIGHT)
        val bodyScroll = object : JBScrollPane(body) {
            override fun getPreferredSize(): Dimension = if (split != null && summariesView.softWrap)
                Dimension(width, minOf(body.preferredSize.height + horizontalScrollBar.preferredSize.height, maxBody))
            else super.getPreferredSize()
        }.apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            if (split != null) {
                // One-line summaries usually outgrow the viewport; keep the sideways reading position across folding.
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
                val restoreX = tab.scrollX
                horizontalScrollBar.addAdjustmentListener { tab.scrollX = it.value }
                SwingUtilities.invokeLater { horizontalScrollBar.value = restoreX }
            }
            // A deep hierarchy or document rail may scroll sideways; leave room for that scroll bar.
            val sideways = if (split != null || (hierarchy || document) && body.preferredSize.width > width) horizontalScrollBar.preferredSize.height else 0
            if (!hierarchy && !document && split == null) horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(width, minOf(body.preferredSize.height + sideways, maxBody))
            verticalScrollBar.unitIncrement = scaled(16)
            horizontalScrollBar.unitIncrement = scaled(16)
            if (document) installDocumentWheelZoom(key, this)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = tab.scroll
            verticalScrollBar.addAdjustmentListener { tab.scroll = it.value }
            SwingUtilities.invokeLater {
                verticalScrollBar.value = restore
                // A jump to a traced summary scrolls its row into view once the rebuilt card is laid out.
                // A newer rebuild owns the reveal.
                if (cards[key] !== card) return@invokeLater
                val target = summariesView.reveal ?: return@invokeLater
                summariesView.reveal = null
                card.validate()
                revealIn(body, target)
            }
        }
        card.add(if (split != null) split.withTree(bodyScroll, width, tab.treeWidth, tab.treeScrollX,
            rememberTreeWidth = { tab.treeWidth = it }, rememberTreeScrollX = { tab.treeScrollX = it }) else bodyScroll,
            BorderLayout.CENTER)
        return card
    }

    private fun summaryVersionRow(tab: SummariesTab, content: SummariesCard): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), scaled(4))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12)
            add(JLabel("Version"))
            add(JComboBox<Any>((listOf("Original") + summaryVersions).toTypedArray()).apply {
                name = "summaries-version"
                selectedItem = version(tab) ?: "Original"
                maximumRowCount = 12
                val selected = version(tab)
                toolTipText = selected?.request?.query ?: "The ordinary summaries, without query context"
                preferredSize = Dimension(scaled(250), preferredSize.height)
                addActionListener {
                    tab.trace.stop()
                    tab.version = (selectedItem as? ContextSummaryVersion)?.id
                    reading(tab)?.let { tab.trace.retain(it) }
                    refreshSummaries(tab)
                }
            })
            add(JButton("Delete version").apply {
                name = "delete-summary-version"
                isEnabled = version(tab) != null || summaryActions.deleteOriginalSummaries != null && content.summaries.count > 0
                addActionListener {
                    val selected = version(tab)
                    if (selected == null) summaryActions.deleteOriginalSummaries?.invoke()
                    else {
                        selected.job?.cancel()
                        summaryVersions.remove(selected)
                        summaryTabs().filter { it.version == selected.id }.forEach {
                            it.trace.stop()
                            it.version = null
                            reading(it)?.let(it.trace::retain)
                        }
                    }
                    summaryTabs().forEach { refreshSummaries(it) }
                    refreshTopics()
                }
            })
            val request = contextSummaryRequest(tab)
            add(JButton("Resummarize with context").apply {
                name = "resummarize-with-context"
                isEnabled = summaryActions.contextSummaries != null && content.canRun && request != null &&
                    !summaryGenerationRunning
                toolTipText = request?.let { "Rewrite only related summaries in the context of “${it.query}”, keeping a separate version" }
                    ?: "Search related summaries to choose a query and the summaries to rewrite"
                addActionListener { startContextSummaries(tab) }
            })
            version(tab)?.let { selected ->
                val count = selected.request.nodeIds.count { selected.summaries.text(it) != null }
                add(JLabel(selected.error ?: if (selected.running) "Resummarizing… $count of ${selected.request.nodeIds.size} summaries"
                    else "$count of ${selected.request.nodeIds.size} summaries rewritten").apply {
                    name = "summary-version-status"
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = text
                })
            }
        }

    /** Page navigation stays above the scrolling summaries, independently of search breadcrumbs. */
    private fun summaryExplorerNavigation(tab: SummariesTab, tree: ReviewSummaryTree): JComponent =
        JPanel(BorderLayout(scaled(8), 0)).apply {
            name = "summary-explorer-navigation"
            isOpaque = false
            border = JBUI.Borders.empty(6, PADDING)
            val path = tab.view.explorerBreadcrumbs(tree)
            fun refresh() { tab.scroll = 0; refreshSummaries(tab) }
            add(JButton("Back", AllIcons.Actions.Back).apply {
                name = "summary-explorer-back"
                font = scaledFont(JBFont.small())
                isEnabled = path.isNotEmpty()
                addActionListener { tab.view.explorerBack(); refresh() }
            }, BorderLayout.WEST)
            val crumbs = JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                fun crumb(title: String, identity: String?, current: Boolean) {
                    add(ActionLink(title) { tab.view.browse(identity, tree); refresh() }.apply {
                        name = "summary-explorer-crumb:${identity ?: "overview"}"
                        font = scaledFont(JBFont.small())
                        isEnabled = !current
                        toolTipText = identity?.let { id -> path.firstOrNull { it.identity == id }?.location }
                    })
                }
                crumb("Overview", null, path.isEmpty())
                path.forEachIndexed { index, node ->
                    add(JLabel("›").apply { foreground = UIUtil.getContextHelpForeground() })
                    crumb(tree.shownLabel(node), node.identity, index == path.lastIndex)
                }
            }
            add(JBScrollPane(crumbs).apply {
                border = null
                isOpaque = false
                viewport.isOpaque = false
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                preferredSize = Dimension(1, crumbs.preferredSize.height + horizontalScrollBar.preferredSize.height)
            }, BorderLayout.CENTER)
        }

    internal var requestResummaryInstructions: (SummaryNode, Boolean, ResummaryDestination) -> String? = { node, recursive, destination ->
        ResummaryDialog(this, node, recursive, destination).let { if (it.showAndGet()) it.instructions else null }
    }

    private fun resummarize(tab: SummariesTab, node: SummaryNode, recursive: Boolean) {
        if (summaryActions.contextSummaries == null || summaries?.canRun != true || summaryGenerationRunning) return
        val current = reading(tab) ?: return
        val instructions = requestResummaryInstructions(node, recursive, ResummaryDestination.VERSION) ?: return
        startContextSummaries(tab, resummaryRequest(current, node, recursive, instructions))
    }

    /**
     * Fill or regenerate one topic, file or level of the topic hierarchy without waiting for the whole hierarchy. Regeneration
     * rewrites topics only: file summaries are shared with the Summaries card, where Resummarize opens them as a version.
     */
    private fun topicEntryMenu(content: TopicsCard): ((String) -> JPopupMenu)? {
        val canSummarize = summaryActions.contextSummaries != null && topicActions.saveTopicSummaries != null
        if (!canSummarize && summaryActions.chatNode == null) return null
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
                if (!canSummarize) return@apply
                item("Fill summaries", enabled && fillSummaryRequest(seeded, node).nodeIds.isNotEmpty(),
                    "Write the missing summaries of this entry and everything beneath it") { summarizeTopic(identity, null) }
                if (node.isTopicBranch) {
                    item("Resummarize", enabled, "Regenerate this topic's summary from the summaries beneath it") { summarizeTopic(identity, false) }
                    item("Resummarize all", enabled, "Regenerate this topic and every sub-topic beneath it") { summarizeTopic(identity, true) }
                }
            }
        }
    }

    /** Fills the missing summaries beneath [identity] when [recursive] is null, else regenerates it, saving into the review. */
    private fun summarizeTopic(identity: String, recursive: Boolean?) {
        val launcher = summaryActions.contextSummaries ?: return
        val save = topicActions.saveTopicSummaries ?: return
        val content = topics ?: return
        if (!content.canRun || summaryGenerationRunning) return
        val seeded = seedTopicSummaries(content.topicSummaries, content.summaries)
        val node = seeded.tree.nodes.firstOrNull { it.identity == identity } ?: return
        val request = if (recursive == null) fillSummaryRequest(seeded, node) else {
            val instructions = requestResummaryInstructions(node, recursive, ResummaryDestination.TOPICS) ?: return
            resummaryRequest(seeded, node, recursive, instructions) { it.isTopicBranch }
        }
        // The dialog is modal; the topics may have changed or another run started meanwhile.
        if (request.nodeIds.isEmpty() || topics !== content || summaryGenerationRunning) return
        val start = if (request.regenerate) seeded.without(request.nodeIds) else seeded
        var latest = start
        var finished = false
        topicSummaryStatus = "${request.label}…"
        val job = launcher.launch(start, request, updated = { latest = it; save(it, request.nodeIds) }) { result ->
            finished = true
            topicSummaryJob = null
            topicSummaryStatus = result.fold({ run ->
                run.error ?: "Some summaries could not be written. Run ${request.label} again to retry."
                    .takeIf { request.nodeIds.any { run.summaries.text(it) == null } }
            }, { it.message ?: "Summarizing stopped." })
            // Saving also lets the review render its cards without the finished run.
            save(result.getOrNull()?.summaries ?: latest, request.nodeIds)
            refreshTopics()
            summaryTabs().forEach { refreshSummaries(it) }
        }
        if (!finished) topicSummaryJob = job
        save(start, request.nodeIds)
        refreshTopics()
        summaryTabs().forEach { refreshSummaries(it) }
    }

    private fun startContextSummaries(tab: SummariesTab, explicit: ContextSummaryRequest? = null) {
        val launcher = summaryActions.contextSummaries ?: return
        val current = reading(tab) ?: return
        val request = explicit ?: contextSummaryRequest(tab) ?: return
        if (summaries?.canRun != true || summaryGenerationRunning) return
        val version = ContextSummaryVersion(++summaryVersionCount, request, current.without(request.nodeIds))
        summaryVersions += version
        tab.trace.stop()
        tab.version = version.id
        summaryTabs().forEach { refreshSummaries(it) }
        refreshTopics()
        try {
            version.job = launcher.launch(version.summaries, request, updated = { updated ->
                if (version in summaryVersions && version.running) {
                    version.summaries = summaries?.summaries?.tree?.let { tree ->
                        if (updated.tree === tree) updated else updated.retainedIn(tree)
                    } ?: updated
                    summaryTabs().filter { it.version == version.id }.forEach { it.trace.retain(version.summaries); refreshSummaries(it) }
                }
            }, done = { result ->
                if (version in summaryVersions && version.running) {
                    version.running = false
                    version.error = result.fold({ run ->
                        run.error ?: "Some summaries could not be rewritten. Run resummarization again to retry."
                            .takeIf { request.nodeIds.any { run.summaries.text(it) == null } }
                    }, { it.message ?: "Resummarizing stopped." })
                    summaryTabs().forEach { refreshSummaries(it) }
                    refreshTopics()
                }
            })
        } catch (e: Exception) {
            version.running = false
            version.error = e.message ?: e.javaClass.simpleName
            summaryTabs().forEach { refreshSummaries(it) }
            refreshTopics()
        }
    }

    private fun contextSummaryRequest(tab: SummariesTab): ContextSummaryRequest? =
        reading(tab)?.let(tab.view::resummaryRequest) ?: version(tab)?.request?.takeIf { it.nodeIds.isNotEmpty() }

    /**
     * A search tab's queries as breadcrumbs, each going back to the summaries left after it, and the search field. The typed
     * filter stays out of the breadcrumbs: the field itself shows it.
     */
    private fun searchRow(tab: SummariesTab): JComponent = JPanel(BorderLayout(scaled(8), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 12, 4, 8))
        add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
            name = "summaries-breadcrumbs"
            isOpaque = false
            if (!tab.search) return@apply
            val crumbs = tab.view.crumbs
            fun separator() = add(JLabel("›").apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() })
            fun crumb(text: String, index: Int, current: Boolean, tooltip: String) = add(
                if (current) JLabel(text).apply { font = scaledFont(JBFont.small().asBold()) }
                else ActionLink(text) { backTo(tab, index) }.apply { font = scaledFont(JBFont.small()) }
            ).also { it.name = "summaries-crumb:$index"; (it as JComponent).toolTipText = tooltip }
            crumb("Summaries", 0, crumbs.isEmpty(), "Every summary")
            crumbs.forEachIndexed { index, it ->
                separator()
                crumb("“${it.query}”", index + 1, index == crumbs.lastIndex, "Summaries related to “${it.query}”")
            }
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(scaled(8), 0)).apply {
            isOpaque = false
            add(tab.searchField.apply {
                preferredSize = Dimension(scaled(260), preferredSize.height)
                if (text != tab.view.fuzzy) text = tab.view.fuzzy
            }, BorderLayout.CENTER)
            add(JButton("Cancel search").apply {
                name = "summaries-cancel-search"
                font = scaledFont(JBFont.small())
                cursor = Cursor.getDefaultCursor()
                toolTipText = "Cancel the current search and clear highlights, selected words and all filters in this tab"
                addActionListener { cancelSearch(tab) }
            }, BorderLayout.EAST)
        }, BorderLayout.EAST)
    }

    private fun cancelSearch(tab: SummariesTab) {
        tab.trace.clear()
        tab.view.clearSearch()
        tab.searchField.text = ""
        refreshSummaries(tab)
    }

    /** Opens a search tab that starts from [from]'s view and searches, and searches [context] there. */
    private fun openSearchTab(from: SummariesTab, context: SummaryContext) {
        val content = summaries ?: return
        val tab = SummariesTab(CanvasTabKey.SummarySearch(++searchTabCount), from.view.fork(), search = true)
        tab.version = from.version
        searchTabs += tab
        tab.view.update(content.summaries.tree)
        // Searching first lets the new card show the running query.
        tab.trace.searchContext(context)
        showNotes(state)
        focusCard(tab.key)
    }

    private fun closeSearchTab(tab: SummariesTab) {
        tab.diffPanel?.let(Disposer::dispose)
        tab.diffPanel = null
        tab.trace.stop()
        tab.review.stop()
        searchTabs -= tab
        showNotes(state)
    }

    /** Goes back to the summaries left after the first [count] searches of a search tab; zero shows every summary. */
    private fun backTo(tab: SummariesTab, count: Int) {
        tab.trace.clear()
        tab.view.backTo(count)
        refreshSummaries(tab)
    }

    /** Scrolls a traced match into view: its card in a hierarchy, else its outline row. */
    private fun revealIn(body: Container, target: String) {
        val hierarchy = descendantsOf(body).filterIsInstance<HierarchyColumnsPanel>().firstOrNull()
        val row = hierarchy?.anchors?.get(target)
            ?: descendantsOf(body).filterIsInstance<JComponent>().firstOrNull { it.name == "summary:$target" } ?: return
        row.scrollRectToVisible(Rectangle(0, 0, row.width, row.height))
    }

    /** Hover and search updates keep the live components and both viewports intact. */
    private fun updateTracePresentation(key: CanvasTabKey) {
        val card = cards[key] ?: return
        val state = summaryTab(key)?.view ?: topicsView
        val results = state.resultTrace
        descendantsOf(card).forEach { component ->
            if (component is javax.swing.text.JTextComponent) updateWordTracing(component)
            if (component is JComponent && results == null) resetTraceDecoration(component)
            ClientProperty.get(component, TRACE_PRESENTATION_UPDATE)?.invoke()
        }
    }

    /**
     * Two fixed-height status lines: changing hover queries never changes the reading area's geometry. [where] tells what
     * became of a search's matches.
     */
    private fun traceStatusLines(
        state: SummaryTraceState, session: SummaryTraceSession, width: Int, selection: Boolean = false,
        where: String = "highlighted in the view.",
    ): List<JComponent> {
        fun line(text: String, name: String, lineWidth: Int = width) = JLabel(text).apply {
            this.name = name
            isOpaque = false
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            toolTipText = text.takeIf { it.isNotBlank() }
            preferredSize = Dimension(lineWidth.coerceAtLeast(1), getFontMetrics(font).height)
        }
        return listOf(line(tracingHint(selection), "tracing-hint"), JPanel(BorderLayout(scaled(8), 0)).apply {
            isOpaque = false
            add(line(" ", "trace-status", width - scaled(56)).apply {
                val update = {
                    val tracing = state.tracing
                    text = tracing?.let { traceStatus(it, where) } ?: " "
                    toolTipText = text.takeIf { it.isNotBlank() }
                    foreground = if (tracing?.trace?.related.isNullOrEmpty()) UIUtil.getContextHelpForeground() else TraceStyle.MATCH_TEXT
                }
                ClientProperty.put(this, TRACE_PRESENTATION_UPDATE, update)
                update()
            }, BorderLayout.CENTER)
            add(ActionLink("Clear") { session.clear() }.apply {
                name = "trace-clear"; font = scaledFont(JBFont.small()); isEnabled = state.tracing != null || state.resultTrace != null
                ClientProperty.put(this, TRACE_PRESENTATION_UPDATE) { isEnabled = state.tracing != null || state.resultTrace != null }
            }, BorderLayout.EAST)
        })
    }

    /** Pick a 1-based folder level, then fold from it or unfold through it. */
    private fun summaryLevels(tab: SummariesTab, tree: ReviewSummaryTree, levels: Int): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
        isOpaque = false
        val summariesView = tab.view
        val selector = JComboBox((1..levels).toList().toTypedArray()).apply {
            name = "summary-level-selector"
            font = scaledFont(JBFont.small())
            selectedItem = summariesView.selectedLevel.coerceIn(1, levels)
            toolTipText = "Folder level (root is level 1); choose Fold or Unfold to apply"
            addActionListener { summariesView.selectedLevel = selectedItem as Int }
        }
        add(JLabel("Level").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
            labelFor = selector
        })
        add(selector)
        add(JButton("Fold").apply {
            name = "summary-fold"
            font = scaledFont(JBFont.small())
            cursor = Cursor.getDefaultCursor()
            toolTipText = "Fold folders at the selected level and deeper"
            addActionListener {
                summariesView.foldLevel(selector.selectedItem as Int, tree)
                refreshSummaries(tab)
            }
        })
        add(JButton("Unfold").apply {
            name = "summary-unfold"
            font = scaledFont(JBFont.small())
            cursor = Cursor.getDefaultCursor()
            toolTipText = "Unfold folders through the selected level; deeper folders stay folded"
            addActionListener {
                summariesView.selectLevel(selector.selectedItem as Int, tree)
                refreshSummaries(tab)
            }
        })
    }

    /** Follows every summary and chat update without rebuilding the other cards while the cards stay on the canvas. */
    fun updateSummaries(content: SummariesCard?) {
        if ((content == null) != (summaries == null) || cards[CanvasTabKey.Summaries] == null) {
            showNotes(state.copy(summaries = content))
            return
        }
        if (content == summaries) return
        state = state.copy(summaries = content)
        content?.let {
            retainSummaryVersions(it.summaries.tree)
            summaryTabs().forEach { tab -> tab.view.update(it.summaries.tree); tab.trace.retain(reading(tab)!!) }
        }
        summaryTabs().forEach { refreshSummaries(it) }
    }

    private fun refreshSummaries(tab: SummariesTab = mainSummaries) {
        val content = summaries ?: return
        // Rebuilding moves the search field into the new card; typing goes on where it was.
        val typing = tab.searchField.textEditor.isFocusOwner
        replaceCard(tab.key, summariesCard(tab, content))
        if (typing) tab.searchField.textEditor.requestFocusInWindow()
        pages[tab.key]?.let { page -> tabs.indexOfComponent(page).takeIf { it >= 0 } }?.let { index ->
            tabs.setTitleAt(index, tabTitle(tab.key))
            tabs.setToolTipTextAt(index, tabTooltip(tab.key))
        }
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

    private fun cardWidth(default: Int) = scaled(default)

    private fun scrollBody(body: JComponent, width: Int, maxHeight: Int) = JBScrollPane(body).apply {
        border = null; isOpaque = false; viewport.isOpaque = false
        preferredSize = Dimension(width, minOf(body.preferredSize.height, maxHeight))
        verticalScrollBar.unitIncrement = scaled(16)
        horizontalScrollBar.unitIncrement = scaled(16)
    }

    private fun refreshCard(key: CanvasTabKey) {
        if (key !in cards) return
        val card = when (key) {
            CanvasTabKey.Words -> words?.let { metricsCards.wordsCard(it) }
            CanvasTabKey.DiffOverview -> diffOverview?.let { metricsCards.wordsCard(it, overview = true) }
            CanvasTabKey.Semantic -> semantic?.let(metricsCards::semanticCard)
            CanvasTabKey.Topics -> topics?.let(::topicsCard)
            CanvasTabKey.Summaries, is CanvasTabKey.SummarySearch ->
                summaryTab(key)?.let { tab -> summaries?.let { summariesCard(tab, it) } }
            CanvasTabKey.FileView -> fileView?.let(::fileViewCard)
            CanvasTabKey.SymbolFlow -> flowCard(changeFlow(notes))
            CanvasTabKey.Explanation -> null
            is ReviewNoteKey -> notes.firstOrNull { it.key == key }?.let(::card)
                ?: sources.firstOrNull { it.key == key }?.let(::sourceCard)
                ?: diffs.firstOrNull { it.diffKey == key }?.let(::diffCard)
        } ?: return
        replaceCard(key, card)
    }

    private fun diffCard(file: ReviewFile): JPanel {
        val width = cardWidth(720)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(JLabel("Diff snapshot · ${file.status}").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove diff block", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                sourceActions.removeDiff(file.path)
            }.apply { cursor = Cursor.getDefaultCursor() }, BorderLayout.EAST)
        }
        val editor = diffEditors.getOrPut(file.diffKey) { DiffFileEditor(file, project) }
        editor.resizeFor(width - scaled(4), zoom)
        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder(false)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(header, BorderLayout.NORTH)
                add(fileRow(file.path, showDiffButton = false).apply { border = JBUI.Borders.empty(0, 12, 8, 8) }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
        }
        return card
    }

    private fun sourceCard(block: ReviewSourceBlock): JPanel {
        val range = block.range
        val width = cardWidth(640)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(JLabel("Source snapshot · ${range.side} · Lines ${range.startLine}–${range.endLine}").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove source block", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                sourceActions.removeSource(range)
            }, BorderLayout.EAST)
        }
        val editor = editors.getOrPut(block.key) { DiffSourceEditor(block, project) }
        editor.resizeFor(width - scaled(4), zoom)
        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder(false)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(header, BorderLayout.NORTH)
                add(fileRow(range.path).apply { border = JBUI.Borders.empty(0, 12, 8, 8) }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
        }
        return card
    }

    private fun card(note: ReviewNote): JPanel {
        val width = cardWidth(CARD_WIDTH)
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        val header = JPanel(BorderLayout(scaled(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("${files(note.paths.size)} · LLM interpretation").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove note", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { remove(note) }
                .apply { cursor = Cursor.getDefaultCursor() }, BorderLayout.EAST)
        }
        val textWidth = width - scaled(2 * PADDING + 4)
        val body = JPanel(VerticalLayout(scaled(10))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(JPanel(VerticalLayout(scaled(2))).apply {
                isOpaque = false
                note.paths.forEach { add(fileRow(it)) }
            })
            note.evidence?.let { evidence ->
                add(JLabel("Verified symbol · Static links · Source snapshot").apply {
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                    toolTipText = "In-project links resolved from current source when this note was added. Direct links only; no runtime order is implied. Callers are limited to this file."
                })
                add(DiffSymbolDiagram(evidence, textWidth, zoom, sourceActions.openSymbol))
            }
            add(JTextArea(note.text).apply {
                isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
                font = scaledFont(JBFont.label()); border = null
                // A wrapping text area reports its height for the width it currently has.
                setSize(textWidth, Short.MAX_VALUE.toInt())
            })
        }
        card.add(header, BorderLayout.NORTH)
        val maxBody = scaled(MAX_BODY_HEIGHT)
        card.add(scrollBody(body, width, maxBody), BorderLayout.CENTER)
        return card
    }

    /** File name as a link, its folder dimmed next to it; the full path stays in the tooltip. */
    private fun fileRow(path: String, showDiffButton: Boolean = true): JComponent = JPanel(BorderLayout(scaled(6), 0)).apply {
        isOpaque = false
        toolTipText = vocabulary.openTooltip(path)
        val name = path.substringAfterLast('/')
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JLabel(FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Any_type))
            add(Box.createHorizontalStrut(scaled(6)))
            add(ActionLink(name) { open(path) }.apply {
                font = scaledFont(JBFont.label()); toolTipText = vocabulary.openTooltip(path)
                minimumSize = Dimension(0, preferredSize.height)
            })
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { folder ->
                add(Box.createHorizontalStrut(scaled(6)))
                add(JLabel(folder).apply {
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); toolTipText = path
                    minimumSize = Dimension(0, preferredSize.height)
                })
            }
        }, BorderLayout.CENTER)
        val canvasText = vocabulary.showOnCanvas
        if (showDiffButton && canvasText != null) {
            add(InplaceButton(IconButton(canvasText, vocabulary.showOnCanvasIcon, vocabulary.showOnCanvasIcon)) {
                sourceActions.showDiffCard(path)
            }.apply { cursor = Cursor.getDefaultCursor(); toolTipText = canvasText }, BorderLayout.EAST)
        }
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

    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"

    private fun scaled(value: Int) = zoomScaled(value, zoom)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(zoom)
    override fun dispose() {
        summaryTabs().forEach { it.diffPanel?.let(Disposer::dispose); it.diffPanel = null }
        summaryVersions.forEach { it.job?.cancel() }
        topicSummaryJob?.cancel()
        summaryTabs().forEach { it.trace.stop(); it.review.stop() }
        topicsTrace.stop()
        topicsReview.stop()
        editors.values.forEach { it.dispose() }
        editors.clear()
        diffEditors.values.forEach { Disposer.dispose(it) }
        diffEditors.clear()
    }

    private companion object {
        val TRACE_PRESENTATION_UPDATE = Key.create<() -> Unit>("insight.note.tracePresentationUpdate")
        const val MIN_DOCUMENT_ZOOM = 0.1
        const val MAX_DOCUMENT_ZOOM = 3.0
        const val CARD_WIDTH = 460
        const val GAP = 20
        const val PADDING = 12
        const val MAX_BODY_HEIGHT = 520
        const val SUMMARIES_WIDTH = 840
        const val MAX_SUMMARIES_HEIGHT = 820
        const val MAX_TOPICS_HEIGHT = 900
    }
}
