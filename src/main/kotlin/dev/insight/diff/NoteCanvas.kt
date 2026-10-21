package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
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
    /** File view: open a file at a line, put lines of the viewed file on the canvas, and close the view. */
    private val openFileAt: (String, Int?) -> Unit = { path, _ -> open(path) },
    private val showFileLines: (String, IntRange) -> Unit = { path, _ -> showDiffCard(path) },
    private val removeFileView: () -> Unit = {},
) : JPanel(BorderLayout()), Disposable {
    private val openSummaryNode: (SummaryNode) -> Unit = openNode ?: { node -> open(node.path) }
    private val showSummaryNode: (SummaryNode) -> Unit = showNode ?: { node -> showDiffCard(node.path) }
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
    internal val summariesView = ChangeSummariesViewState().also {
        it.mode = vocabulary.summariesMode
        it.visibleOnly = vocabulary.scoreVisibleOnly
    }
    private var summariesScroll = 0
    private var fileView: FileViewCard? = null
    private var fileViewScroll = 0
    private val summariesTrace = SummaryTraceSession(traceSummaries, summariesView, { summaries?.summaries }, ::refreshSummaries)
    private val topicsTrace = SummaryTraceSession(traceSummaries, topicsView, { topics?.reading }) { refreshCard(TOPICS_KEY) }
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
    ) {
        if (topics == null) topicsScroll = 0
        if (fileView?.file?.path != this.fileView?.file?.path) fileViewScroll = 0
        this.fileView = fileView
        this.topics = topics
        topics?.let { topicsTrace.retain(it.reading) } ?: topicsTrace.stop()
        this.notes = notes
        this.sources = sources
        this.diffs = diffs
        if (flows?.graph !== this.flows?.graph) flowsScroll = 0
        this.flows = flows
        flowsView.reset(flows)
        this.summaries = summaries
        summaries?.let { summariesView.update(it.summaries.tree); summariesTrace.retain(it.summaries) } ?: summariesTrace.stop()
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
        val built = listOfNotNull(topics?.let { TOPICS_KEY to topicsCard(it) }, flows?.let { FLOWS_KEY to flowsCard(it) }, summaries?.let { SUMMARIES_KEY to summariesCard(it) }, fileView?.let { FILE_VIEW_KEY to fileViewCard(it) }, flow?.let { FLOW_KEY to flowCard(it) }).toMap() +
            notes.associate { it.key to card(it) } +
            sources.associate { it.key to sourceCard(it) } +
            diffs.associate { it.diffKey to diffCard(it) }
        built.forEach { (key, card) ->
            cards[key] = card
            val page = page(card)
            pages[key] = page
            installStickyScrolling(key, page, card)
            tabs.addTab(tabTitle(key), page)
            tabs.setToolTipTextAt(tabs.tabCount - 1, key.paths.joinToString(", ").ifEmpty { tabTitle(key) })
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

    private fun tabTitle(key: DiffNoteKey): String = when (key) {
        TOPICS_KEY -> "Topics"
        SUMMARIES_KEY -> "Summaries"
        FILE_VIEW_KEY -> "File · ${fileView?.file?.path?.substringAfterLast('/').orEmpty()}"
        FLOWS_KEY -> "Change flows"
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
            else -> summariesScroll = target.y
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
        val outline = topicOutline(topics)
        topicsView.update(outline)
        val hierarchy = topicsView.mode == ChangeTopicsMode.HIERARCHY
        val document = !hierarchy
        val viewZoom = if (document) documentZoom else zoom
        val width = if (hierarchy) cardWidth(HierarchyColumnsPanel.defaultWidth(outline.depth))
            else ChangeTopicsPanel.defaultWidth(topicsView.railDepth(outline), viewZoom)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("${vocabulary.topicsTitle} · ${topics.groups.size} topics · ${topics.labels.size} of ${files(topics.files.size)}").apply {
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
                if (hierarchy && topicsTrace.available) add(scoreButton("topics-score", topicsView, content.reading, topicsTrace))
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
                trace = topicsTrace::trace, openNode = openSummaryNode, showNode = showSummaryNode)
            val inner = width - scaled(2 * PADDING + 4) - scaled(16)
            add(if (hierarchy) JPanel(VerticalLayout(scaled(8))).apply {
                isOpaque = false
                traceStatusLines(topicsView, content.reading, topicsTrace, inner).forEach(::add)
                add(ChangeTopicHierarchyPanel(content, topicsView, inner, viewZoom, actions, vocabulary))
            } else ChangeTopicsPanel(content, topicsView, inner, viewZoom, actions, vocabulary), BorderLayout.CENTER)
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        card.add(header, BorderLayout.NORTH)
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
        add(JLabel("Levels").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
        })
        val group = ButtonGroup()
        val current = topicsView.effectiveLevel(outline)
        for (level in 0..outline.depth) add(JToggleButton(if (level == outline.depth) "Files" else "${level + 1}", level == current).apply {
            name = "level:$level"
            font = scaledFont(JBFont.small())
            cursor = Cursor.getDefaultCursor()
            toolTipText = if (topicsView.mode == ChangeTopicsMode.DOCUMENT)
                if (level == outline.depth) "Show every topic rail level" else "Show topic rail levels 1–${level + 1}"
            else if (level == outline.depth) "Show every topic level and file" else "Show topic levels 1–${level + 1}"
            group.add(this)
            addActionListener { topicsView.selectLevel(level, outline); refreshCard(TOPICS_KEY) }
        })
    }

    /** Follows topic assignments, summaries and chat state without rebuilding the other cards while the card stays on the canvas. */
    fun updateTopics(content: TopicsCard?) {
        if ((content == null) != (topics == null) || (content != null && cards[TOPICS_KEY] == null)) {
            showNotes(notes, sources, diffs, flows, summaries, content, fileView)
            return
        }
        if (content == topics) return
        topics = content
        content?.let { topicsTrace.retain(it.reading) }
        refreshCard(TOPICS_KEY)
    }

    /**
     * The summary tree as a wide reading card, as an outline, as the folder hierarchy, or as a document with its folder rail;
     * mode changes, expanding, selecting and new summaries rebuild only this card, in place.
     */
    private fun summariesCard(content: SummariesCard): JPanel {
        val tree = content.summaries.tree
        summariesView.update(tree)
        val hierarchy = summariesView.mode == ChangeSummariesMode.HIERARCHY
        val document = summariesView.mode == ChangeSummariesMode.DOCUMENT
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
            add(JLabel(vocabulary.summariesTitle).apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = vocabulary.summariesTooltip
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                if (document) add(documentZoomControls(SUMMARIES_KEY))
                val summaries = content.summaries
                if ((hierarchy || document) && branchLevels > 0) add(summaryLevels(tree, branchLevels))
                val group = ButtonGroup()
                ChangeSummariesMode.entries.forEach { mode ->
                    add(JToggleButton(when (mode) {
                        ChangeSummariesMode.OUTLINE -> "Outline"
                        ChangeSummariesMode.HIERARCHY -> "Hierarchy"
                        ChangeSummariesMode.DOCUMENT -> "Document"
                    }, summariesView.mode == mode).apply {
                        name = "summaries-mode:${mode.name.lowercase()}"
                        font = scaledFont(JBFont.small())
                        cursor = Cursor.getDefaultCursor()
                        toolTipText = when (mode) {
                            ChangeSummariesMode.OUTLINE -> vocabulary.summariesOutlineTooltip
                            ChangeSummariesMode.HIERARCHY -> "Folders broad to specific, left to right, with each file's summary"
                            ChangeSummariesMode.DOCUMENT -> "Every file in folder order, with its summary and file and folder cards"
                        }
                        group.add(this)
                        addActionListener { summariesView.mode = mode; summariesScroll = 0; refreshSummaries() }
                    })
                }
                if (!document && summariesTrace.available) add(scoreButton("summaries-score", summariesView, summaries, summariesTrace))
                add(JButton(when {
                    summaries.total == 0 || summaries.count == 0 -> "Summarize"
                    summaries.complete -> "Regenerate"
                    else -> "Continue"
                }).apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && summaries.total > 0
                    toolTipText = if (summaries.complete) "Write every summary again (/summaries)"
                        else vocabulary.summarizeTooltip
                    addActionListener { summarize() }
                })
                add(InplaceButton(IconButton("Remove change summaries", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeSummaries() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        val actions = ChangeSummaryActions(
            toggle = { identity -> summariesView.toggle(identity); refreshSummaries() },
            select = { identity -> summariesView.select(identity); refreshSummaries() },
            openFile = open,
            showDiffCard = showDiffCard,
            openNode = openSummaryNode,
            showNode = showSummaryNode,
            showNames = { show -> summariesView.showNames = show; refreshSummaries() },
            trace = summariesTrace::trace,
        )
        val inner = width - scaled(2 * PADDING + 4) - scaled(16)
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(when {
                hierarchy -> JPanel(VerticalLayout(scaled(8))).apply {
                    isOpaque = false
                    add(JLabel(summariesStatus(content, vocabulary.noFiles)).apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() })
                    traceStatusLines(summariesView, content.summaries, summariesTrace, inner).forEach(::add)
                    add(ChangeSummaryHierarchyPanel(content, summariesView, inner, viewZoom, actions, vocabulary))
                }
                document -> JPanel(VerticalLayout(scaled(8))).apply {
                    isOpaque = false
                    add(JLabel(summariesStatus(content, vocabulary.noFiles)).apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() })
                    add(ChangeSummaryDocumentPanel(content, summariesView, inner, viewZoom, actions, vocabulary))
                }
                else -> ChangeSummariesPanel(content, summariesView, inner, zoom, actions, vocabulary)
            }, BorderLayout.CENTER)
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        card.add(header, BorderLayout.NORTH)
        val maxBody = scaled(if (document) MAX_TOPICS_HEIGHT else MAX_SUMMARIES_HEIGHT)
        card.add(JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            // A deep hierarchy or document rail may scroll sideways; leave room for that scroll bar.
            val sideways = if ((hierarchy || document) && body.preferredSize.width > width) horizontalScrollBar.preferredSize.height else 0
            if (!hierarchy && !document) horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(width, minOf(body.preferredSize.height + sideways, maxBody))
            verticalScrollBar.unitIncrement = scaled(16)
            horizontalScrollBar.unitIncrement = scaled(16)
            if (document) installDocumentWheelZoom(SUMMARIES_KEY, this)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = summariesScroll
            verticalScrollBar.addAdjustmentListener { summariesScroll = it.value }
            SwingUtilities.invokeLater {
                verticalScrollBar.value = restore
                // A jump to a traced summary scrolls its row into view once the rebuilt card is laid out.
                // A newer rebuild owns the reveal.
                if (cards[SUMMARIES_KEY] !== card) return@invokeLater
                val target = summariesView.reveal ?: return@invokeLater
                summariesView.reveal = null
                card.validate()
                revealIn(body, target)
            }
        }, BorderLayout.CENTER)
        return card
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
            toolTipText = "Precalculate word contexts for " + (if (state.visibleOnly) "the summaries with children shown now" else "all summaries with children") + ". Uses the Decisions API."
            addActionListener { session.prescore() }
        }

    /**
     * Above a hierarchy: how scoring stands and, while a word is traced, its query and matches. An opened trace hides the
     * traced summary, so its status stays here with a link to clear the marks.
     */
    private fun traceStatusLines(state: SummaryTraceState, summaries: ChangeSummaries, session: SummaryTraceSession, width: Int): List<JComponent> {
        if (!session.available) return emptyList()
        fun line(text: String, name: String, wrap: Int = width) = WrappedText(text, wrap).apply {
            this.name = name
            isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; border = null
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
        }
        val tracing = state.tracing ?: return listOf(line(scoringStatus(state, summaries), "scoring-status"))
        return listOf(line(scoringStatus(state, summaries), "scoring-status"), JPanel(BorderLayout(scaled(8), 0)).apply {
            isOpaque = false
            add(line(traceStatus(tracing, "outlined from the traced card."), "trace-status", width - scaled(56)).apply {
                foreground = if (tracing.trace?.related.isNullOrEmpty()) UIUtil.getContextHelpForeground() else TraceStyle.MATCH_TEXT
            }, BorderLayout.CENTER)
            add(ActionLink("Clear") { session.clear() }.apply { name = "trace-clear"; font = scaledFont(JBFont.small()) }, BorderLayout.EAST)
        })
    }

    /** Level n shows folder levels 1..n+1 and folds the rest, as in the topic hierarchy; the last level shows every file. */
    private fun summaryLevels(tree: ChangeSummaryTree, levels: Int): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        add(JLabel("Levels").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
        })
        val group = ButtonGroup()
        val current = summariesView.shownLevel(tree)
        for (level in 0..levels) add(JToggleButton(if (level == levels) "Files" else "${level + 1}", level == current).apply {
            name = "summary-level:$level"
            font = scaledFont(JBFont.small())
            cursor = Cursor.getDefaultCursor()
            toolTipText = if (summariesView.mode == ChangeSummariesMode.DOCUMENT)
                if (level == levels) "Show every folder rail level" else "Show folder rail levels 1–${level + 1}"
            else if (level == levels) "Show every folder level and file" else "Show folder levels 1–${level + 1}"
            group.add(this)
            addActionListener { summariesView.selectLevel(level, tree); refreshSummaries() }
        })
    }

    /** Follows every summary and chat update without rebuilding the other cards while the card stays on the canvas. */
    fun updateSummaries(content: SummariesCard?) {
        if ((content == null) != (summaries == null) || cards[SUMMARIES_KEY] == null) {
            showNotes(notes, sources, diffs, flows, content, topics, fileView)
            return
        }
        if (content == summaries) return
        summaries = content
        content?.let { summariesView.update(it.summaries.tree); summariesTrace.retain(it.summaries) }
        refreshSummaries()
    }

    private fun refreshSummaries() {
        val content = summaries ?: return
        val card = summariesCard(content)
        replaceCard(SUMMARIES_KEY, card)
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
            showNotes(notes, sources, diffs, flows, summaries, topics, content)
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
            TOPICS_KEY -> topics?.let(::topicsCard)
            SUMMARIES_KEY -> summaries?.let(::summariesCard)
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
        summariesTrace.stop()
        topicsTrace.stop()
        editors.values.forEach { it.dispose() }
        editors.clear()
        diffEditors.values.forEach { Disposer.dispose(it) }
        diffEditors.clear()
    }

    private companion object {
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
        const val SUMMARIES_WIDTH = 840
        const val MAX_SUMMARIES_HEIGHT = 820
        const val MAX_TOPICS_HEIGHT = 900
        const val FLOWS_WIDTH = 920
        const val MAX_FLOWS_HEIGHT = 760
    }
}
