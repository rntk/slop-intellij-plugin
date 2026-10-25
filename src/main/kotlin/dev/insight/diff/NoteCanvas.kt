package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseWheelEvent
import kotlinx.coroutines.Job
import kotlin.math.pow
import kotlin.math.roundToInt
import javax.swing.*
import javax.swing.event.DocumentEvent

/** Review artifacts, each in its own normally scrollable tab. */
internal class NoteCanvas(
    private val open: (String) -> Unit,
    private val remove: (DiffNote) -> Unit,
    private val openSymbol: (DiffSymbol) -> Unit = {},
    private val removeSource: (DiffSourceRange) -> Unit = {},
    private val showDiffCard: (String) -> Unit = {},
    private val removeDiff: (String) -> Unit = {},
    private val project: Project? = null,
    private val removeFlows: () -> Unit = {},
    private val summarize: () -> Unit = {},
    private val generateTopics: () -> Unit = {},
    private val fillTopicSummaries: () -> Unit = {},
    private val removeTopics: () -> Unit = {},
    private val removeSummaries: () -> Unit = {},
    /** Summary prescoring and search use the Decisions API through this launcher. */
    private val traceSummaries: SummaryTraceLauncher? = null,
    /** The words of the canvas; a diff review by default. */
    private val vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
    /** Open the file of a summary node (a part opens at its first line). Null opens the node's file by path like [open]. */
    openNode: ((SummaryNode) -> Unit)? = null,
    /** Put a summary node's source on the canvas. Null shows the node's file by path like [showDiffCard]. */
    showNode: ((SummaryNode) -> Unit)? = null,
    /** Open a file or part at the lines a traced word of its summary refers to, highlighted. Null opens the node like [openNode]. */
    openLines: ((SummaryNode, LineTrace) -> Unit)? = null,
    /** File view: open a file at a line, put lines of the viewed file on the canvas, and close the view. */
    private val openFileAt: (String, Int?) -> Unit = { path, _ -> open(path) },
    private val showFileLines: (String, IntRange) -> Unit = { path, _ -> showDiffCard(path) },
    private val removeFileView: () -> Unit = {},
    private val contextSummaries: ContextSummaryLauncher? = null,
    private val deleteOriginalSummaries: (() -> Unit)? = null,
    private val openWholeFile: ((ReviewFile) -> Unit)? = null,
    private val openDirectoryDiff: ((List<ReviewFile>) -> Unit)? = null,
    private val removeWords: () -> Unit = {},
    private val removeDiffOverview: () -> Unit = {},
    private val removeSemantic: () -> Unit = {},
    /** Ask the review chat to explain a summary entry (/explain); null hides Explain. */
    private val explainNode: ((SummaryNode) -> Unit)? = null,
    private val reviewed: ReviewedFiles = ReviewedFiles(),
    private val markReviewed: ((SummaryNode, Boolean) -> Unit)? = null,
    private val reviewSummaries: SummaryReviewLauncher? = null,
) : JPanel(BorderLayout()), Disposable {
    private val openSummaryNode: (SummaryNode) -> Unit = openNode ?: { node -> open(node.path) }
    private val showSummaryNode: (SummaryNode) -> Unit = showNode ?: { node -> showDiffCard(node.path) }
    private val openSummaryLines: (SummaryNode, LineTrace) -> Unit = openLines ?: { node, _ -> openSummaryNode(node) }
    internal val tabs = JTabbedPane().apply { tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT }
    private val pages = mutableMapOf<DiffNoteKey, JScrollPane>()
    private val cards = mutableMapOf<DiffNoteKey, JPanel>()
    private val stickyTargets = mutableMapOf<DiffNoteKey, JComponent>()

    private var notes = emptyList<DiffNote>()
    private var sources = emptyList<DiffSourceBlock>()
    private var diffs = emptyList<ReviewFile>()
    private var flows: ChangeFlows? = null
    internal val flowsView = ChangeFlowsViewState()
    private var flowsScroll = 0
    private var topics: TopicsCard? = null
    internal val topicsView = ChangeTopicsViewState(vocabulary.foldLargeTopics).also { it.visibleOnly = vocabulary.scoreVisibleOnly }
    private var topicsScroll = 0
    private var summaries: SummariesCard? = null
    private val summaryVersions = mutableListOf<ContextSummaryVersion>()
    private var summaryVersionCount = 0
    internal val summariesView = ChangeSummariesViewState().also {
        it.mode = vocabulary.summariesMode
        it.visibleOnly = vocabulary.scoreVisibleOnly
    }
    private var fileView: FileViewCard? = null
    private var fileViewScroll = 0
    private var words: WordChanges? = null
    private var wordFiles = emptyList<ReviewFile>()
    /** Placing the words is slow, so the chart is kept across rebuilds while its counts are the same. */
    private var wordChart: WordChangeChart? = null
    private var diffOverview: WordChanges? = null
    private var diffOverviewChart: DiffOverviewChart? = null
    private var semantic: SemanticResult? = null
    private var explanation: ExplainResult? = null
    /** The selected view and plotted metric of the semantic changes card, kept across rebuilds. */
    private var semanticMode = SemanticMode.METRICS
    private var semanticMetric = Metric.CYCLOMATIC
    private var semanticChart: SemanticComplexityChart? = null
    private var constructChart: Pair<SemanticChanges, WordChangeChart>? = null
    /** The file or directory the chart counts; kept while the snapshot still changes files in it. */
    private var wordScope = WordScope("", true, 0)
    /** The words of [wordScope], counted again only when the scope or the snapshot's words change. */
    private var scopedWords: Triple<WordChanges, WordScope, WordChanges>? = null

    /**
     * The summaries tab, or a search tab over the same summaries: its own view and searches, reading position and search
     * field.
     */
    private inner class SummariesTab(val key: DiffNoteKey, val view: ChangeSummariesViewState, val search: Boolean = false) {
        val review = SummaryReviewSession(reviewed, reviewSummaries) { restyleReview(key) }
        var scroll = 0
        /** The sideways reading position of the split view's one-line summaries. */
        var scrollX = 0
        var treeScrollX = 0
        var treeWidth: Int? = null
        var version: Int? = null
        val trace = SummaryTraceSession(traceSummaries, view, { reading(this) }, openSummaryLines,
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

    private val mainSummaries = SummariesTab(SUMMARIES_KEY, summariesView)
    private val summariesTrace get() = mainSummaries.trace
    private val searchTabs = mutableListOf<SummariesTab>()
    private var searchTabCount = 0
    private fun summaryTabs() = listOf(mainSummaries) + searchTabs
    private fun summaryTab(key: DiffNoteKey) = summaryTabs().firstOrNull { it.key == key }
    private fun version(tab: SummariesTab) = summaryVersions.firstOrNull { it.id == tab.version }
    private fun reading(tab: SummariesTab): ChangeSummaries? = version(tab)?.summaries ?: summaries?.summaries

    private fun retainSummaryVersions(tree: ChangeSummaryTree) {
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
    private val topicsTrace = SummaryTraceSession(traceSummaries, topicsView, { topics?.reading }, openSummaryLines,
        update = { updateTracePresentation(TOPICS_KEY) }) { refreshCard(TOPICS_KEY) }
    private val topicsReview = SummaryReviewSession(reviewed, reviewSummaries) { restyleReview(TOPICS_KEY) }

    /** Marks restyle the live cards; rebuilding them would reset both viewports and make the reading position jump. */
    private fun toggleReviewed(node: SummaryNode, value: Boolean) {
        if (markReviewed != null) markReviewed.invoke(node, value) else reviewed.set(node.files, value)
        summaryTabs().forEach { updateReview(it.key, it.review, summaries?.summaries) }
        updateReview(TOPICS_KEY, topicsReview, topics?.reading)
    }

    private fun updateReview(key: DiffNoteKey, session: SummaryReviewSession, content: ChangeSummaries?) {
        if (key !in cards) return
        content?.let(session::sync)
        restyleReview(key)
    }

    private fun restyleReview(key: DiffNoteKey) {
        val card = cards[key] ?: return
        descendantsOf(card).forEach { (it as? JComponent)?.let(::updateReviewPresentation) }
    }

    private val editors = mutableMapOf<DiffNoteKey, DiffSourceEditor>()
    private val diffEditors = mutableMapOf<DiffNoteKey, DiffFileEditor>()
    private var highlighted = emptySet<String>()
    private var focused: DiffNoteKey? = null
    private val zoom = 1.0
    private var documentZoom = 1.0

    init {
        minimumSize = Dimension(100, 100)
        background = UIUtil.getPanelBackground()
        add(tabs, BorderLayout.CENTER)
    }

    fun showNotes(
        notes: List<DiffNote>,
        sources: List<DiffSourceBlock> = emptyList(),
        diffs: List<ReviewFile> = emptyList(),
        flows: ChangeFlows? = null,
        summaries: SummariesCard? = null,
        topics: TopicsCard? = null,
        fileView: FileViewCard? = null,
        words: WordChanges? = null,
        wordFiles: List<ReviewFile> = emptyList(),
        diffOverview: WordChanges? = null,
        semantic: SemanticResult? = null,
        explanation: ExplainResult? = null,
    ) {
        this.explanation = explanation
        this.words = words
        this.diffOverview = diffOverview
        this.semantic = semantic
        this.wordFiles = wordFiles
        if (topics == null) topicsScroll = 0
        if (fileView?.file?.path != this.fileView?.file?.path) fileViewScroll = 0
        this.fileView = fileView
        this.topics = topics
        if (topics == null) topicsReview.stop()
        topics?.let { topicsTrace.retain(it.reading) } ?: topicsTrace.stop()
        this.notes = notes
        this.sources = sources
        this.diffs = diffs
        if (flows?.graph !== this.flows?.graph) flowsScroll = 0
        this.flows = flows
        flowsView.reset(flows)
        this.summaries = summaries
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
        val built = listOfNotNull(explanation?.let { EXPLANATION_KEY to explanationCard(it) },
            topics?.let { TOPICS_KEY to topicsCard(it) }, flows?.let { FLOWS_KEY to flowsCard(it) },
            words?.let { WORDS_KEY to wordsCard(it) }, diffOverview?.let { DIFF_OVERVIEW_KEY to wordsCard(it, overview = true) },
            semantic?.let { SEMANTIC_KEY to semanticCard(it) }).toMap() +
            summaryCards +
            listOfNotNull(fileView?.let { FILE_VIEW_KEY to fileViewCard(it) }, flow?.let { FLOW_KEY to flowCard(it) }).toMap() +
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
        cards.forEach { (key, card) -> card.border = cardBorder(key == focused || key.paths.any { it in paths }) }
        repaint()
    }

    /** Selects the note tab. */
    internal fun focusNote(note: DiffNote) {
        focusCard(note.key)
    }

    internal fun focusDiff(path: String) {
        focusCard(diffNoteKey(path))
    }

    internal fun focusTopics() { focusCard(TOPICS_KEY) }

    internal fun focusWords() { focusCard(WORDS_KEY) }
    internal fun focusDiffOverview() { focusCard(DIFF_OVERVIEW_KEY) }
    internal fun focusSemantic() { focusCard(SEMANTIC_KEY) }
    internal fun focusExplanation() { focusCard(EXPLANATION_KEY) }

    /** Selects the source tab. */
    internal fun focusSource(range: DiffSourceRange) { focusCard(DiffSourceBlock(range, "").key) }

    internal fun focusFileView() { focusCard(FILE_VIEW_KEY) }

    internal fun focusSummaries() {
        focusCard(SUMMARIES_KEY)
    }

    private fun focusCard(key: DiffNoteKey) {
        val page = pages[key] ?: return
        tabs.selectedComponent = page
        focused = key
        highlight(highlighted)
    }

    private fun tabTooltip(key: DiffNoteKey): String = summaryTab(key)?.takeIf { it.search }?.let { tab ->
        (listOf("Summaries") + searchPath(tab).map { "“$it”" }).joinToString(" › ")
    } ?: key.paths.joinToString(", ").ifEmpty { tabTitle(key) }

    /** A search tab's queries: the finished searches, or the first query while it is still searched. */
    private fun searchPath(tab: SummariesTab): List<String> = tab.view.crumbs.map { it.query }.ifEmpty {
        listOfNotNull(tab.view.tracing?.takeIf { it.searchRequested }?.context?.query)
    }

    private fun tabTitle(key: DiffNoteKey): String = when (key) {
        TOPICS_KEY -> "Topics"
        SUMMARIES_KEY -> "Summaries"
        in searchTabs.map { it.key } -> searchPath(summaryTab(key)!!).lastOrNull()?.let {
            "Search · " + if (it.length > 32) it.take(31) + "…" else it
        } ?: "Search"
        FILE_VIEW_KEY -> "File · ${fileView?.file?.path?.substringAfterLast('/').orEmpty()}"
        FLOWS_KEY -> "Change flows"
        WORDS_KEY -> "Word changes"
        DIFF_OVERVIEW_KEY -> "Diff overview"
        SEMANTIC_KEY -> "Semantic changes"
        EXPLANATION_KEY -> if (explanation?.running == true) "Explaining…" else "Explanation"
        FLOW_KEY -> "Symbol flow"
        else -> {
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
    private fun zoomDocument(key: DiffNoteKey, requested: Double, anchor: Point? = null) {
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
            TOPICS_KEY -> topicsScroll = target.y
            FILE_VIEW_KEY -> fileViewScroll = target.y
            else -> summaryTab(key)?.scroll = target.y
        }
        SwingUtilities.invokeLater {
            if (cards[key]?.let(::bodyScroll) === updated) updated.viewport.viewPosition = target
        }
    }

    private fun documentZoomControls(key: DiffNoteKey): JComponent = JPanel(FlowLayout(FlowLayout.RIGHT, scaled(2), 0)).apply {
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

    private fun installDocumentWheelZoom(key: DiffNoteKey, scroll: JScrollPane) {
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
    private fun installStickyScrolling(key: DiffNoteKey, page: JScrollPane, card: JPanel) {
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

    private fun updateStickyScrolling(key: DiffNoteKey, page: JScrollPane) {
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

    private fun replaceCard(key: DiffNoteKey, card: JPanel) {
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

    /** The change-flow graph; expanding or collapsing rebuilds only this card, in place. */
    private fun flowsCard(flows: ChangeFlows): JPanel {
        val width = cardWidth(FLOWS_WIDTH)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Change flows").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Functions, calls, clusters, counts and outlines come from comparing and resolving the snapshot's source. " +
                    "Arrows are statically resolved calls, not runtime order. Themes, summaries and intents are the assistant's interpretation."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                val group = ButtonGroup()
                ChangeFlowsMode.entries.forEach { mode ->
                    add(JToggleButton(if (mode == ChangeFlowsMode.MAP) "Map" else "Outline", flowsView.mode == mode).apply {
                        font = scaledFont(JBFont.small())
                        cursor = Cursor.getDefaultCursor()
                        toolTipText = if (mode == ChangeFlowsMode.MAP) "Connected steps with function details on selection" else "Expandable call tree with inline details"
                        group.add(this)
                        addActionListener { flowsView.mode = mode; flowsScroll = 0; refreshFlows() }
                    })
                }
                add(InplaceButton(IconButton("Remove change flows", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeFlows() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        val actions = ChangeFlowActions(
            toggle = { key -> flowsView.toggle(key); refreshFlows() },
            focus = { node -> flowsView.focus(flows, node); refreshFlows() },
            openNode = { node ->
                if (node.offset != null) openSymbol(DiffSymbol(node.path, node.absolutePath, node.name, node.qualifiedName,
                    dev.insight.callgraph.SymbolKind.FUNCTION, node.line, node.offset))
                else open(node.path)
            },
            openCaller = { caller ->
                if (caller.absolutePath != null && caller.offset != null) openSymbol(DiffSymbol(caller.path, caller.absolutePath,
                    caller.qualifiedName.substringAfterLast('.'), caller.qualifiedName, dev.insight.callgraph.SymbolKind.FUNCTION, caller.line, caller.offset))
            },
            openFile = open,
        )
        val inner = width - scaled(2 * PADDING + 4)
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(ChangeFlowsPanel(flows, flowsView, inner - scaled(16), zoom, actions), BorderLayout.CENTER)
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        card.add(header, BorderLayout.NORTH)
        val maxBody = scaled(MAX_FLOWS_HEIGHT)
        card.add(JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(width, minOf(body.preferredSize.height, maxBody))
            verticalScrollBar.unitIncrement = scaled(16)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = flowsScroll
            verticalScrollBar.addAdjustmentListener { flowsScroll = it.value }
            SwingUtilities.invokeLater { verticalScrollBar.value = restore }
        }, BorderLayout.CENTER)
        return card
    }

    /** Words of the removed and added lines, counted from the snapshot; a word opens the diffs of its files. */
    private fun wordsCard(all: WordChanges, overview: Boolean = false): JPanel {
        val width = cardWidth(WORDS_WIDTH)
        val scopes = wordScopes(wordFiles)
        wordScope = scopes.firstOrNull { it.path == wordScope.path && it.directory == wordScope.directory } ?: scopes.first()
        val words = if (wordScope.path.isEmpty()) all
            else scopedWords?.takeIf { it.first === all && it.second == wordScope }?.third
                ?: wordChanges(wordFiles.filter { wordScope.contains(it.path) }).also { scopedWords = Triple(all, wordScope, it) }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel(if (overview) "Diff overview · Deleted and added lines"
                else "Word changes · ${words.words.size} most frequent words of the changed lines").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Counted from the removed and added lines of the snapshot; context lines are not counted. " +
                    (if (overview) "Compact word lists fill opposite corners, longest first. The middle chart compares deleted and added line counts, including blank lines. "
                    else "A word's position compares its share of removed and added words, its size how often it occurs. ") +
                    "Click a word to open its files' diffs."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                add(wordScopeButton(scopes))
                add(InplaceButton(IconButton(if (overview) "Remove diff overview" else "Remove word changes", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                    if (overview) removeDiffOverview() else removeWords()
                }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(if (words.words.isEmpty() && !overview) JLabel("No words in the changed lines.").apply { foreground = UIUtil.getContextHelpForeground() }
                else if (overview) diffOverviewChart?.takeIf { it.changes === words }
                    ?: DiffOverviewChart(words, width - scaled(2 * PADDING + 4), scaled(WORDS_HEIGHT), ::openWordDiff).also { diffOverviewChart = it }
                else wordChart?.takeIf { it.changes === words }
                    ?: WordChangeChart(words, width - scaled(2 * PADDING + 4), scaled(WORDS_HEIGHT), ::openWordDiff).also { wordChart = it }, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder(false)
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    /**
     * Semantic metrics counted from both sides of the changed files, as a delta table, a scatter of function complexity
     * or a cloud of the constructs added and removed. The mode and the plotted metric survive rebuilds.
     */
    private fun semanticCard(result: SemanticResult): JPanel {
        val width = cardWidth(WORDS_WIDTH)
        val changes = (result as? SemanticResult.Ready)?.changes
        val skipped = changes?.files?.filter { it.skipped != null }.orEmpty()
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                if (changes != null) {
                    val group = ButtonGroup()
                    SemanticMode.entries.forEach { mode ->
                        add(JToggleButton(mode.label, mode == semanticMode).apply {
                            name = "semantic-mode:${mode.name}"
                            font = scaledFont(JBFont.small())
                            toolTipText = when (mode) {
                                SemanticMode.METRICS -> "Totals of each metric before and after the change; click a row to open the diffs of its files"
                                SemanticMode.COMPLEXITY -> "Each function's value before and after the change; click a point to open its diff"
                                SemanticMode.CONSTRUCTS -> "Source constructs removed on the left and added on the right; click one to open its diffs"
                            }
                            group.add(this)
                            addActionListener { if (semanticMode != mode) { semanticMode = mode; refreshCard(SEMANTIC_KEY) } }
                        })
                    }
                } else add(JLabel("Semantic changes").apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() })
            }, BorderLayout.WEST)
            add(JLabel(if (skipped.isEmpty()) "" else "${files(skipped.size)} skipped").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                horizontalAlignment = SwingConstants.CENTER
                name = "semantic-skipped"
                toolTipText = if (skipped.isEmpty()) null else "<html>Not counted:<br>" + skipped.take(20).joinToString("<br>") {
                    "${escapeHtml(it.path)} · ${escapeHtml(it.skipped.orEmpty())}"
                } + (if (skipped.size > 20) "<br>… ${skipped.size - 20} more" else "") + "</html>"
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                if (changes != null && semanticMode == SemanticMode.COMPLEXITY) add(JComboBox(COMPLEXITY_METRICS.toTypedArray()).apply {
                    name = "semantic-metric"
                    font = scaledFont(JBFont.small())
                    renderer = SimpleListCellRenderer.create("") { it.label }
                    toolTipText = "The metric plotted for each function"
                    selectedItem = semanticMetric
                    addActionListener { (selectedItem as? Metric)?.takeIf { it != semanticMetric }?.let { semanticMetric = it; refreshCard(SEMANTIC_KEY) } }
                })
                add(InplaceButton(IconButton("Remove semantic changes", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeSemantic() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        val chartWidth = width - scaled(2 * PADDING + 4)
        fun note(text: String) = JLabel(text).apply { foreground = UIUtil.getContextHelpForeground() }
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(when (result) {
                SemanticResult.Counting -> note("Counting…").apply { name = "semantic-counting" }
                is SemanticResult.Failed -> note("Could not count semantic changes: ${result.message}").apply {
                    name = "semantic-error"; foreground = JBColor.RED
                }
                is SemanticResult.Ready -> when (semanticMode) {
                    SemanticMode.METRICS -> SemanticMetricsTable(result.changes, chartWidth, ::openMetricDiff).let { table ->
                        if (table.rows().isEmpty()) note("No metric differs.") else scrollBody(table, chartWidth, scaled(WORDS_HEIGHT))
                    }
                    SemanticMode.COMPLEXITY ->
                        (semanticChart?.takeIf { it.changes === result.changes && it.metric == semanticMetric }
                            ?: SemanticComplexityChart(result.changes, semanticMetric, chartWidth, scaled(WORDS_HEIGHT), ::openDeclarationDiff)
                                .also { semanticChart = it }).let { if (it.hasFunctions()) it else note("No functions in the changed files.") }
                    SemanticMode.CONSTRUCTS -> constructChart?.takeIf { it.first === result.changes }?.second
                        ?: constructChanges(result.changes).let { constructs ->
                            if (constructs.words.isEmpty()) note("No construct was added or removed.")
                            else WordChangeChart(constructs, chartWidth, scaled(WORDS_HEIGHT), ::openWordDiff).also { constructChart = result.changes to it }
                        }
                }
            }, BorderLayout.CENTER)
        }
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder(false)
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    private fun openMetricDiff(metric: Metric) {
        val changes = (semantic as? SemanticResult.Ready)?.changes ?: return
        openPaths(changes.filesChanging(metric).map { it.path }.toSet())
    }

    private fun openDeclarationDiff(declaration: DeclarationMetrics) { open(declaration.path) }

    private fun openWordDiff(word: WordChange) { openPaths(word.paths) }

    private fun openPaths(paths: Set<String>) {
        val files = wordFiles.filter { it.path in paths }
        val directory = openDirectoryDiff
        when {
            files.size == 1 || (directory == null && files.isNotEmpty()) -> open(files.first().path)
            directory != null && files.isNotEmpty() -> directory(files)
        }
    }

    private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Limits the chart to one changed file or directory, chosen from a list that typing filters. */
    private fun wordScopeButton(scopes: List<WordScope>): JButton = JButton(wordScope.toString(), AllIcons.General.ArrowDown).apply {
        name = "word-scope"
        horizontalTextPosition = SwingConstants.LEFT
        font = scaledFont(JBFont.small())
        toolTipText = "Count the words of one changed file or directory · type in the list to filter it"
        addActionListener {
            JBPopupFactory.getInstance().createPopupChooserBuilder(scopes)
                .setTitle("Word Changes Scope")
                .setNamerForFiltering { if (it.path.isEmpty()) "All changed files" else it.path }
                .setSelectedValue(wordScope, true)
                .setMovable(true)
                .setResizable(true)
                .setItemChosenCallback(::chooseWordScope)
                .createPopup().showUnderneathOf(this)
        }
    }

    internal fun chooseWordScope(scope: WordScope) {
        if (scope == wordScope) return
        wordScope = scope
        refreshCard(WORDS_KEY)
        refreshCard(DIFF_OVERVIEW_KEY)
    }

    private fun refreshFlows() {
        val flows = flows ?: return
        val card = flowsCard(flows)
        replaceCard(FLOWS_KEY, card)
        flowsView.reveal?.let { node ->
            flowsView.reveal = null
            SwingUtilities.invokeLater {
                card.validate()
                val target = descendantsOf(card).firstOrNull { it.name == "node:$node" } as? JComponent ?: return@invokeLater
                target.scrollRectToVisible(Rectangle(0, 0, target.width, minOf(target.height, scaled(120))))
            }
        }
    }

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
                if (document) add(documentZoomControls(TOPICS_KEY))
                if (outline.depth > 0) add(topicLevels(outline))
                val group = ButtonGroup()
                ChangeTopicsMode.entries.forEach { mode ->
                    add(JToggleButton(if (mode == ChangeTopicsMode.DOCUMENT) "Document" else "Hierarchy", topicsView.mode == mode).apply {
                        font = scaledFont(JBFont.small())
                        cursor = Cursor.getDefaultCursor()
                        toolTipText = if (mode == ChangeTopicsMode.DOCUMENT) "Every file in topic order, with its summary and the topic rail"
                            else "Topics broad to specific, left to right, with each file's summary"
                        group.add(this)
                        addActionListener { topicsView.mode = mode; topicsScroll = 0; refreshCard(TOPICS_KEY) }
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
                    addActionListener { generateTopics() }
                })
                if (topicsTrace.available) add(scoreButton("topics-score", topicsView, content.reading, topicsTrace))
                if (hierarchy) add(JButton("Fill summaries").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && outline.nodes.isNotEmpty() && !content.topicSummaries.complete
                    toolTipText = "Fill missing summaries for every topic and subtopic, including folded topics"
                    addActionListener { fillTopicSummaries() }
                })
                val summaries = content.summaries
                if (summaries.total > 0 && !summaries.complete) add(JButton(if (summaries.count == 0) "Summarize" else "Continue summaries").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun
                    toolTipText = vocabulary.topicsSummarizeTooltip
                    addActionListener { summarize() }
                })
                add(InplaceButton(IconButton("Remove change topics", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeTopics() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            val actions = ChangeTopicActions(openFile = open, showDiffCard = showDiffCard, refresh = { refreshCard(TOPICS_KEY) },
                trace = topicsTrace::trace, openNode = openSummaryNode, showNode = showSummaryNode,
                review = topicsReview.presentation(content.reading, ::toggleReviewed))
            val inner = width - scaled(2 * PADDING + 4) - scaled(16)
            add(JPanel(VerticalLayout(scaled(8))).apply {
                isOpaque = false
                traceStatusLines(topicsView, content.reading, topicsTrace, inner).forEach(::add)
                reviewStatus(topicsReview)?.let(::add)
                if (shown.files.isEmpty() && outline.files.isNotEmpty()) add(JLabel("No files match the search.").apply {
                    name = "topics-no-matches"
                    font = scaledFont(JBFont.label()).deriveFont(java.awt.Font.ITALIC); foreground = UIUtil.getContextHelpForeground()
                })
                else add(if (hierarchy) ChangeTopicHierarchyPanel(content, topicsView, inner, viewZoom, actions, vocabulary)
                    else ChangeTopicsPanel(content, topicsView, inner, viewZoom, actions, vocabulary))
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
            if (document) installDocumentWheelZoom(TOPICS_KEY, this)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = topicsScroll
            verticalScrollBar.addAdjustmentListener { topicsScroll = it.value }
            SwingUtilities.invokeLater {
                verticalScrollBar.value = restore
                // A traced match scrolls into view once the rebuilt card is laid out; a newer rebuild owns the reveal.
                if (cards[TOPICS_KEY] !== card) return@invokeLater
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
            addActionListener { topicsView.selectLevel(selectedIndex, outline); refreshCard(TOPICS_KEY) }
        }
        add(JLabel("Levels").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
            labelFor = selector
        })
        add(selector)
    }

    /** Follows topic assignments, summaries and chat state without rebuilding the other cards while the card stays on the canvas. */
    fun updateTopics(content: TopicsCard?) {
        if ((content == null) != (topics == null) || (content != null && cards[TOPICS_KEY] == null)) {
            showNotes(notes, sources, diffs, flows, summaries, content, fileView, explanation = explanation)
            return
        }
        if (content == topics) return
        topics = content
        content?.let { topicsTrace.retain(it.reading) }
        refreshTopics()
    }

    /** Rebuilding moves the search fields into the new card; typing goes on where it was. */
    private fun refreshTopics() {
        val typing = listOf(topicSearchField, topicSummarySearchField).firstOrNull { it.textEditor.isFocusOwner }
        refreshCard(TOPICS_KEY)
        typing?.textEditor?.requestFocusInWindow()
    }

    /**
     * The summary tree as a wide reading card, as an outline, as the folder hierarchy, or as a document with its folder rail;
     * mode changes, expanding, selecting and new summaries rebuild only this card, in place. A search tab shows the same
     * card with only the summaries its searches found related, and its queries as breadcrumbs.
     */
    private fun summariesCard(tab: SummariesTab, original: SummariesCard): JPanel {
        val version = version(tab)
        val content = original.copy(summaries = reading(tab) ?: original.summaries,
            running = version?.running ?: original.running)
        val summariesView = tab.view
        val summariesTrace = tab.trace
        val key = tab.key
        val tree = content.summaries.tree
        summariesView.update(tree)
        val hierarchy = summariesView.mode == ChangeSummariesMode.HIERARCHY
        val document = summariesView.mode == ChangeSummariesMode.DOCUMENT
        val explorer = summariesView.mode == ChangeSummariesMode.EXPLORER
        val splitMode = summariesView.mode == ChangeSummariesMode.SPLIT
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
                ChangeSummariesMode.entries.forEach { mode ->
                    add(JToggleButton(when (mode) {
                        ChangeSummariesMode.OUTLINE -> "Outline"
                        ChangeSummariesMode.HIERARCHY -> "Hierarchy"
                        ChangeSummariesMode.DOCUMENT -> "Document"
                        ChangeSummariesMode.EXPLORER -> "Explorer"
                        ChangeSummariesMode.SPLIT -> "Split"
                    }, summariesView.mode == mode).apply {
                        name = "summaries-mode:${mode.name.lowercase()}"
                        font = scaledFont(JBFont.small())
                        cursor = Cursor.getDefaultCursor()
                        toolTipText = when (mode) {
                            ChangeSummariesMode.OUTLINE -> vocabulary.summariesOutlineTooltip
                            ChangeSummariesMode.HIERARCHY -> "Folders broad to specific, left to right, with each file's summary"
                            ChangeSummariesMode.DOCUMENT -> "Every file in folder order, with its summary and file and folder cards"
                            ChangeSummariesMode.EXPLORER -> "Browse one level of summaries at a time, with Back and breadcrumbs"
                            ChangeSummariesMode.SPLIT -> "The file tree beside aligned summaries; hover a row to match it with its summary"
                        }
                        group.add(this)
                        addActionListener { summariesView.mode = mode; tab.scroll = 0; refreshSummaries(tab) }
                    })
                }
                if (summariesTrace.available) add(scoreButton("summaries-score", summariesView, summaries, summariesTrace))
                add(JButton(when {
                    version != null -> "Regenerate original"
                    summaries.total == 0 || summaries.count == 0 -> "Summarize"
                    summaries.complete -> "Regenerate"
                    else -> "Continue"
                }).apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && summaries.total > 0 && summaryVersions.none { it.running }
                    toolTipText = if (summaries.complete) "Write every summary again (/summaries)"
                        else vocabulary.summarizeTooltip
                    addActionListener { summarize() }
                })
                add(if (tab.search) InplaceButton(IconButton("Close search tab", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { closeSearchTab(tab) }
                        .apply { name = "close-search-tab"; cursor = Cursor.getDefaultCursor() }
                    else InplaceButton(IconButton("Remove change summaries", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeSummaries() }
                        .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        val actions = ChangeSummaryActions(
            toggle = { identity -> summariesView.toggle(identity); refreshSummaries(tab) },
            select = { identity -> summariesView.select(identity); refreshSummaries(tab) },
            openFile = open,
            showDiffCard = showDiffCard,
            openNode = openSummaryNode,
            showNode = showSummaryNode,
            openWholeFile = openWholeFile,
            openDirectory = openDirectoryDiff?.let { open ->
                val files = tree.nodes.mapNotNull { (it.file as? ReviewFile)?.let { file -> file.path to file } }.toMap()
                val navigate: (SummaryNode) -> Unit = { node -> open(node.files.mapNotNull(files::get)) }
                navigate
            },
            trace = summariesTrace::trace,
            searchSelection = summariesTrace::searchSelection.takeIf { summariesTrace.available },
            searchTab = { context: SummaryContext -> openSearchTab(tab, context) }.takeIf { summariesTrace.available },
            follow = summariesTrace::searchContext.takeIf { summariesTrace.available },
            score = summariesTrace::prescore.takeIf { summariesTrace.available },
            explain = explainNode,
            review = tab.review.presentation(content.summaries, ::toggleReviewed),
        )
        val inner = width - scaled(2 * PADDING + 4) - scaled(16)
        val split = if (splitMode) ChangeSummarySplitPanel(content, summariesView, zoom, actions, vocabulary) else null
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
                    add(ChangeSummaryDocumentPanel(content, summariesView, inner, viewZoom, actions, vocabulary))
                }
                else -> ChangeSummariesPanel(content, summariesView, inner, zoom, actions, vocabulary,
                    browse = if (explorer) { identity ->
                        summariesView.browse(identity, tree)
                        tab.scroll = 0
                        refreshSummaries(tab)
                    } else null)
            }, BorderLayout.CENTER)
        }
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
                    border = JBUI.Borders.empty(6, PADDING, 6, PADDING)
                })
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        if (summariesTrace.available || reviewSummaries != null) card.add(JPanel(VerticalLayout(scaled(4))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, PADDING, 8, PADDING)
            traceStatusLines(summariesView, content.summaries, summariesTrace, inner, selection = true,
                where = if (tab.search) "highlighted in the view; the rest are hidden in this tab." else "highlighted in the view.").forEach(::add)
            reviewStatus(tab.review)?.let(::add)
        }, BorderLayout.SOUTH)
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

    /** A fixed-height line, so linking starting or finishing never moves the summaries. */
    private fun reviewStatus(session: SummaryReviewSession): JComponent? {
        if (reviewSummaries == null) return null
        fun message() = when {
            session.running -> "Linking reviewed summary text…"
            session.error != null -> "Review links unavailable: ${session.error}. Toggle Reviewed to retry."
            else -> " "
        }
        return JLabel(message()).apply {
            name = "review-link-status"; foreground = UIUtil.getContextHelpForeground()
            preferredSize = Dimension(preferredSize.width, getFontMetrics(font).height)
            installReviewPresentationUpdate(this) { text = message() }
        }
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
                isEnabled = version(tab) != null || deleteOriginalSummaries != null && content.summaries.count > 0
                addActionListener {
                    val selected = version(tab)
                    if (selected == null) deleteOriginalSummaries?.invoke()
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
                }
            })
            val request = contextSummaryRequest(tab)
            val pendingSearch = tab.view.tracing?.let { it.searchRequested && it.trace == null && it.error == null } == true
            add(JButton("Resummarize with context").apply {
                name = "resummarize-with-context"
                isEnabled = contextSummaries != null && content.canRun && request != null &&
                    !pendingSearch && summaryVersions.none { it.running }
                toolTipText = request?.let { "Rewrite only related summaries in the context of “${it.query}”, keeping a separate version" }
                    ?: "Search related summaries to choose a query and the summaries to rewrite"
                addActionListener { startContextSummaries(tab) }
            })
            version(tab)?.let { selected ->
                val count = selected.request.nodeIds.count { selected.summaries.text(it) != null }
                add(JLabel(selected.error ?: if (selected.running) "Resummarizing… $count of ${selected.request.nodeIds.size} related summaries"
                    else "$count of ${selected.request.nodeIds.size} related summaries rewritten").apply {
                    name = "summary-version-status"
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = text
                })
            }
        }

    /** Page navigation stays above the scrolling summaries, independently of search breadcrumbs. */
    private fun summaryExplorerNavigation(tab: SummariesTab, tree: ChangeSummaryTree): JComponent =
        JPanel(BorderLayout(scaled(8), 0)).apply {
            name = "summary-explorer-navigation"
            isOpaque = false
            border = JBUI.Borders.empty(6, PADDING, 6, PADDING)
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
                    crumb(node.label, node.identity, index == path.lastIndex)
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

    private fun startContextSummaries(tab: SummariesTab) {
        val launcher = contextSummaries ?: return
        val current = reading(tab) ?: return
        val request = contextSummaryRequest(tab) ?: return
        if (summaryVersions.any { it.running }) return
        val version = ContextSummaryVersion(++summaryVersionCount, request, current.without(request.nodeIds))
        summaryVersions += version
        tab.trace.stop()
        tab.version = version.id
        summaryTabs().forEach { refreshSummaries(it) }
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
                        run.error ?: "Some related summaries could not be rewritten. Resummarize with context to try again."
                            .takeIf { request.nodeIds.any { run.summaries.text(it) == null } }
                    }, { it.message ?: "Resummarizing stopped." })
                    summaryTabs().forEach { refreshSummaries(it) }
                }
            })
        } catch (e: Exception) {
            version.running = false
            version.error = e.message ?: e.javaClass.simpleName
            summaryTabs().forEach { refreshSummaries(it) }
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
            // A search still running shows its query, not yet a step to go back to.
            val running = tab.view.tracing?.takeIf { it.searchRequested && it.file == null && it.trace == null && it.error == null }
            crumb("Summaries", 0, crumbs.isEmpty() && running == null, "Every summary")
            crumbs.forEachIndexed { index, it ->
                separator()
                crumb("“${it.query}”", index + 1, index == crumbs.lastIndex && running == null, "Summaries related to “${it.query}”")
            }
            if (running != null) {
                separator()
                add(JLabel("“${running.context?.query ?: running.word.text}”…").apply {
                    name = "summaries-crumb:searching"
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                })
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
        val tab = SummariesTab(DiffNoteKey(emptyList(), "summary-search-${++searchTabCount}"), from.view.fork(), search = true)
        tab.version = from.version
        searchTabs += tab
        tab.view.update(content.summaries.tree)
        // Searching first lets the new card show the running query.
        tab.trace.searchContext(context)
        showNotes(notes, sources, diffs, flows, summaries, topics, fileView, explanation = explanation)
        focusCard(tab.key)
    }

    private fun closeSearchTab(tab: SummariesTab) {
        tab.trace.stop()
        tab.review.stop()
        searchTabs -= tab
        showNotes(notes, sources, diffs, flows, summaries, topics, fileView, explanation = explanation)
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

    private fun scoreButton(name: String, state: SummaryTraceState, summaries: ChangeSummaries, session: SummaryTraceSession) =
        JButton("Score").apply {
            this.name = name
            font = scaledFont(JBFont.small())
            cursor = Cursor.getDefaultCursor()
            isEnabled = !state.scoring && state.pending(summaries).isNotEmpty()
            putClientProperty(TRACE_PRESENTATION_UPDATE, {
                isEnabled = !state.scoring && state.pending(summaries).isNotEmpty()
            })
            toolTipText = "Precalculate word contexts for " + scoredSummaries(state) + ". Uses the Decisions API."
            addActionListener { session.prescore() }
        }

    /** Hover and scoring updates keep the live components and both viewports intact. */
    private fun updateTracePresentation(key: DiffNoteKey) {
        val card = cards[key] ?: return
        val state = summaryTab(key)?.view ?: topicsView
        val results = state.resultTrace
        descendantsOf(card).forEach { component ->
            if (component is javax.swing.text.JTextComponent) updateWordTracing(component)
            if (component is JComponent && results == null) resetTraceDecoration(component)
            @Suppress("UNCHECKED_CAST")
            ((component as? JComponent)?.getClientProperty(TRACE_PRESENTATION_UPDATE) as? (() -> Unit))?.invoke()
        }
    }

    /**
     * Two fixed-height status lines: changing hover queries never changes the reading area's geometry. [where] tells what
     * became of a search's matches.
     */
    private fun traceStatusLines(
        state: SummaryTraceState, summaries: ChangeSummaries, session: SummaryTraceSession, width: Int, selection: Boolean = false,
        where: String = "highlighted in the view.",
    ): List<JComponent> {
        if (!session.available) return emptyList()
        fun line(text: String, name: String, lineWidth: Int = width) = JLabel(text).apply {
            this.name = name
            isOpaque = false
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            toolTipText = text.takeIf { it.isNotBlank() }
            preferredSize = Dimension(lineWidth.coerceAtLeast(1), getFontMetrics(font).height)
        }
        return listOf(line(scoringStatus(state, summaries, selection), "scoring-status").apply {
            putClientProperty(TRACE_PRESENTATION_UPDATE, {
                text = scoringStatus(state, summaries, selection)
                toolTipText = text
            })
        }, JPanel(BorderLayout(scaled(8), 0)).apply {
            isOpaque = false
            add(line(" ", "trace-status", width - scaled(56)).apply {
                val update = {
                    val tracing = state.tracing
                    text = tracing?.let { traceStatus(it, where) } ?: " "
                    toolTipText = text.takeIf { it.isNotBlank() }
                    foreground = if (tracing?.trace?.related.isNullOrEmpty()) UIUtil.getContextHelpForeground() else TraceStyle.MATCH_TEXT
                }
                putClientProperty(TRACE_PRESENTATION_UPDATE, update)
                update()
            }, BorderLayout.CENTER)
            add(ActionLink("Clear") { session.clear() }.apply {
                name = "trace-clear"; font = scaledFont(JBFont.small()); isEnabled = state.tracing != null || state.resultTrace != null
                putClientProperty(TRACE_PRESENTATION_UPDATE, { isEnabled = state.tracing != null || state.resultTrace != null })
            }, BorderLayout.EAST)
        })
    }

    /** Pick a 1-based folder level, then fold from it or unfold through it. */
    private fun summaryLevels(tab: SummariesTab, tree: ChangeSummaryTree, levels: Int): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
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
        if ((content == null) != (summaries == null) || cards[SUMMARIES_KEY] == null) {
            showNotes(notes, sources, diffs, flows, content, topics, fileView, explanation = explanation)
            return
        }
        if (content == summaries) return
        summaries = content
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

    /**
     * One file block by block, each beside the topics and summaries of the files its calls end in; zoom, selection and new
     * topics or summaries rebuild only this card, in place.
     */
    private fun fileViewCard(content: FileViewCard): JPanel {
        val structure = content.structure
        val width = DocumentColumnsPanel.defaultWidth(FileStructurePanel.railDepth(content), documentZoom)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel(listOfNotNull("File view · ${content.file.path}", structure?.let {
                "${it.blocks.size} ${if (it.blocks.size == 1) "block" else "blocks"} · reaches ${files(content.reached().size)}"
            }).joinToString(" · ")).apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Blocks, calls and reached files come from resolving the source: each call is followed through the call graph " +
                    "to the next project file, following local helpers. Hover a source line or topic card for its neighbors' summaries. " +
                    "Calls are static, not runtime order. Topics and summaries are the assistant's interpretation."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                if (structure != null) add(documentZoomControls(FILE_VIEW_KEY))
                if (content.missingTopics > 0) add(JButton("Classify").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun
                    toolTipText = "${files(content.missingTopics)} reached from this file have no topic yet (/topics)"
                    addActionListener { generateTopics() }
                })
                if (content.missingSummaries > 0) add(JButton("Summarize").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun
                    toolTipText = "${files(content.missingSummaries)} reached from this file have no summary yet (/summaries)"
                    addActionListener { summarize() }
                })
                add(InplaceButton(IconButton("Close file view", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeFileView() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        val inner = width - scaled(2 * PADDING + 4) - scaled(16)
        val body = JPanel(VerticalLayout(scaled(8))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            val status = when {
                content.error != null -> content.error
                structure == null -> "Following the calls of ${content.file.path}…"
                structure.truncated -> "Some local helper chains reached the analysis limit; neighboring calls may be incomplete."
                else -> null
            }
            status?.let { add(JLabel(it).apply { name = "file-view-status"; font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() }) }
            if (structure != null) add(FileStructurePanel(content, structure, inner, documentZoom,
                FileStructureActions(openFile = openFileAt, showLines = showFileLines), vocabulary))
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        card.add(header, BorderLayout.NORTH)
        card.add(JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            // Several neighboring topics may scroll sideways; leave room for that scroll bar.
            val sideways = if (body.preferredSize.width > width) horizontalScrollBar.preferredSize.height else 0
            preferredSize = Dimension(width, minOf(body.preferredSize.height + sideways, scaled(MAX_TOPICS_HEIGHT)))
            verticalScrollBar.unitIncrement = scaled(16)
            horizontalScrollBar.unitIncrement = scaled(16)
            if (structure != null) installDocumentWheelZoom(FILE_VIEW_KEY, this)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = fileViewScroll
            verticalScrollBar.addAdjustmentListener { fileViewScroll = it.value }
            SwingUtilities.invokeLater { verticalScrollBar.value = restore }
        }, BorderLayout.CENTER)
        return card
    }

    /** Follows the analysis, topics, summaries and chat state without rebuilding the other cards. */
    fun updateFileView(content: FileViewCard?) {
        if ((content == null) != (fileView == null) || (content != null && cards[FILE_VIEW_KEY] == null)) {
            showNotes(notes, sources, diffs, flows, summaries, topics, content, explanation = explanation)
            return
        }
        if (content == fileView) return
        if (content?.file?.path != fileView?.file?.path) fileViewScroll = 0
        fileView = content
        refreshCard(FILE_VIEW_KEY)
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

    private fun refreshCard(key: DiffNoteKey) {
        if (key !in cards) return
        val card = when (key) {
            FLOWS_KEY -> flows?.let(::flowsCard)
            WORDS_KEY -> words?.let { wordsCard(it) }
            DIFF_OVERVIEW_KEY -> diffOverview?.let { wordsCard(it, overview = true) }
            SEMANTIC_KEY -> semantic?.let(::semanticCard)
            TOPICS_KEY -> topics?.let(::topicsCard)
            in summaryTabs().map { it.key } -> summaries?.let { summariesCard(summaryTab(key)!!, it) }
            FILE_VIEW_KEY -> fileView?.let(::fileViewCard)
            FLOW_KEY -> flowCard(changeFlow(notes))
            else -> notes.firstOrNull { it.key == key }?.let(::card)
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
                removeDiff(file.path)
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

    private fun sourceCard(block: DiffSourceBlock): JPanel {
        val range = block.range
        val width = cardWidth(640)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(JLabel("Source snapshot · ${range.side} · Lines ${range.startLine}–${range.endLine}").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove source block", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                removeSource(range)
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

    private fun card(note: DiffNote): JPanel {
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
                add(DiffSymbolDiagram(evidence, textWidth, zoom, openSymbol))
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
                showDiffCard(path)
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

    private fun scaled(value: Int) = (JBUI.scale(value) * zoom).roundToInt().coerceAtLeast(1)
    private fun scaledFont(font: java.awt.Font) = font.deriveFont((font.size2D * zoom).toFloat())
    override fun dispose() {
        summaryVersions.forEach { it.job?.cancel() }
        summaryTabs().forEach { it.trace.stop(); it.review.stop() }
        topicsTrace.stop()
        topicsReview.stop()
        editors.values.forEach { it.dispose() }
        editors.clear()
        diffEditors.values.forEach { Disposer.dispose(it) }
        diffEditors.clear()
    }

    private companion object {
        val TRACE_PRESENTATION_UPDATE = Any()
        const val MIN_DOCUMENT_ZOOM = 0.1
        const val MAX_DOCUMENT_ZOOM = 3.0
        const val CARD_WIDTH = 460
        const val GAP = 20
        const val PADDING = 12
        const val MAX_BODY_HEIGHT = 520
        val FLOW_KEY = DiffNoteKey(emptyList(), "change-flow")
        val FLOWS_KEY = DiffNoteKey(emptyList(), "change-flows")
        val TOPICS_KEY = DiffNoteKey(emptyList(), "change-topics")
        val SUMMARIES_KEY = DiffNoteKey(emptyList(), "change-summaries")
        val FILE_VIEW_KEY = DiffNoteKey(emptyList(), "file-view")
        val WORDS_KEY = DiffNoteKey(emptyList(), "word-changes")
        val DIFF_OVERVIEW_KEY = DiffNoteKey(emptyList(), "diff-overview")
        val SEMANTIC_KEY = DiffNoteKey(emptyList(), "semantic-changes")
        val EXPLANATION_KEY = DiffNoteKey(emptyList(), "explanation")
        const val WORDS_WIDTH = 1100
        const val WORDS_HEIGHT = 640
        const val SUMMARIES_WIDTH = 840
        const val MAX_SUMMARIES_HEIGHT = 820
        const val MAX_TOPICS_HEIGHT = 900
        const val FLOWS_WIDTH = 920
        const val MAX_FLOWS_HEIGHT = 760
    }
}
