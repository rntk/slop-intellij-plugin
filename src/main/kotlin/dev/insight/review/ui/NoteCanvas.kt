package dev.insight.review.ui

import com.intellij.openapi.Disposable
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
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseWheelEvent
import javax.swing.*
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
    internal val topicsView get() = topicsCardBuilder.view
    private val topicsCardBuilder = TopicsCanvasCard(vocabulary, reviewed, sourceActions, summaryActions, topicActions,
        TopicsCanvasActions(open, openSummaryNode, showSummaryNode, openSummaryLines, ::toggleReviewed,
            refresh = { refreshCard(CanvasTabKey.Topics) }), project)
    private val summarySession: CanvasSummarySession = CanvasSummarySession(summaryActions.contextSummaries,
        currentTree = { summaries?.summaries?.tree }, changed = { version ->
            summariesCardBuilder.generationChanged(version)
            if (version == null) refreshTopics()
        })
    internal val topicSummariesRunning: Boolean get() = summarySession.topicsRunning
    private val summaryGenerationRunning: Boolean get() = summarySession.running
    private val summaries: SummariesCard? get() = state.summaries
    internal val summariesView get() = summariesCardBuilder.summariesView
    private val summariesCardBuilder: SummariesCanvasCards = SummariesCanvasCards(vocabulary, reviewed, sourceActions, summaryActions,
        summarySession, SummariesCanvasActions(
            open = open, openNode = openSummaryNode, showNode = showSummaryNode, openLines = openSummaryLines,
            toggleReviewed = ::toggleReviewed, replace = ::replaceSummaryCard, rebuildPages = { showNotes(state) },
            focus = ::focusCard, restyleReview = ::restyleReview, refreshTopics = ::refreshTopics,
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
        restyleReview(CanvasTabKey.Topics)
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

    init {
        minimumSize = Dimension(100, 100)
        background = UIUtil.getPanelBackground()
        add(tabs, BorderLayout.CENTER)
        extension?.attach(::refreshCard)
    }

    fun showNotes(state: CanvasState) {
        if (state.topics == null) {
            summarySession.stopTopics()
        }
        fileViewCardBuilder.update(state.fileView)
        this.state = state
        extension?.update()
        val summaries = state.summaries
        topicsCardBuilder.update(topics)
        summariesCardBuilder.update(summaries)
        snapshotCards.update(sources)
        val selected = pages.entries.firstOrNull { it.value === tabs.selectedComponent }?.key
        val bodyScrolls = cards.mapValues { bodyScroll(it.value)?.viewport?.viewPosition?.let(::Point) }
        val scrolls = pages.mapValues { Point(it.value.viewport.viewPosition) }
        tabs.removeAll(); cards.clear(); pages.clear(); stickyTargets.clear()
        val flow = changeFlow(notes).takeIf { it.rows.isNotEmpty() }
        val summaryCards = summariesCardBuilder.buildCards()
        val built = listOfNotNull(explanation?.let { CanvasTabKey.Explanation to explanationCard(it) },
            topics?.let { CanvasTabKey.Topics to topicsCard(it) }).toMap() +
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

    internal fun focusWords() { focusCard(CanvasTabKey.Words) }
    internal fun focusDiffOverview() { focusCard(CanvasTabKey.DiffOverview) }
    internal fun focusSemantic() { focusCard(CanvasTabKey.Semantic) }
    internal fun focusMoves() { focusCard(CanvasTabKey.Moves) }
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
        onPageFocused(key)
    }

    private fun tabTooltip(key: CanvasTabKey): String = summariesCardBuilder.tooltip(key)
        ?: (key as? ReviewNoteKey)?.paths.orEmpty().joinToString(", ").ifEmpty { tabTitle(key) }

    private fun tabTitle(key: CanvasTabKey): String = extension?.title(key) ?: when (key) {
        CanvasTabKey.Topics -> "Topics"
        CanvasTabKey.Summaries, is CanvasTabKey.SummarySearch -> summariesCardBuilder.title(key)
        CanvasTabKey.FileView -> "File · ${fileView?.file?.path?.substringAfterLast('/').orEmpty()}"
        CanvasTabKey.Words, CanvasTabKey.DiffOverview, CanvasTabKey.Semantic, CanvasTabKey.Moves -> key.toString()
        CanvasTabKey.Explanation -> if (explanation?.running == true) "Explaining…" else "Explanation"
        CanvasTabKey.SymbolFlow -> "Symbol flow"
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
            CanvasTabKey.Topics -> topicsCardBuilder.scroll = target.y
            CanvasTabKey.FileView -> fileViewCardBuilder.scroll = target.y
            else -> summariesCardBuilder.scrollTo(key, target.y)
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
        summaryGenerationRunning, summarySession.topicStatus, topicEntryMenu(content),
    ) { installDocumentWheelZoom(CanvasTabKey.Topics, it) }

    private fun refreshTopics() = topicsCardBuilder.refresh()

    internal var requestResummaryInstructions: (SummaryNode, Boolean, ResummaryDestination) -> String? = { node, recursive, destination ->
        ResummaryDialog(this, node, recursive, destination).let { if (it.showAndGet()) it.instructions else null }
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
        if (summaryActions.contextSummaries == null) return
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
        summarySession.startTopics(start, request, save)
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
            CanvasTabKey.Words, CanvasTabKey.DiffOverview, CanvasTabKey.Semantic, CanvasTabKey.Moves -> extension?.card(key, snapshotCards)
            CanvasTabKey.Topics -> topics?.let(::topicsCard)
            CanvasTabKey.Summaries, is CanvasTabKey.SummarySearch ->
                summariesCardBuilder.card(key)
            CanvasTabKey.FileView -> fileView?.let(::fileViewCard)
            CanvasTabKey.SymbolFlow -> snapshotCards.flowCard(changeFlow(notes))
            CanvasTabKey.Explanation -> null
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
        summarySession.dispose()
        summariesCardBuilder.dispose()
        topicsCardBuilder.dispose()
        snapshotCards.dispose()
        extension?.dispose()
    }

    private companion object {
        const val MIN_DOCUMENT_ZOOM = 0.1
        const val MAX_DOCUMENT_ZOOM = 3.0
        const val GAP = 20
    }
}
