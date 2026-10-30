package dev.insight.review.ui

import dev.insight.review.PatchSource
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.CanvasTabKey
import dev.insight.review.ContextSummaryRequest
import dev.insight.review.ContextSummaryVersion
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ReviewedFiles
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.resummaryRequest
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.event.DocumentEvent

/** Page operations and shared presentation supplied by the hosting canvas. */
internal data class SummariesCanvasActions(
    val open: (String) -> Unit,
    val openNode: (SummaryNode) -> Unit,
    val showNode: (SummaryNode) -> Unit,
    val openLines: (SummaryNode, LineTrace) -> Unit,
    val toggleReviewed: (SummaryNode, Boolean) -> Unit,
    val replace: (CanvasTabKey, JPanel) -> Unit,
    val rebuildPages: () -> Unit,
    val focus: (CanvasTabKey) -> Unit,
    val restyleReview: (CanvasTabKey) -> Unit,
    val refreshTopics: () -> Unit,
    val documentZoom: () -> Double,
    val zoomControls: (CanvasTabKey) -> JComponent,
    val installWheelZoom: (CanvasTabKey, JScrollPane) -> Unit,
    val requestInstructions: (SummaryNode, Boolean, ResummaryDestination) -> String?,
)

/** Summary and search cards, including each tab's reading, search, trace and version-selection state. */
internal class SummariesCanvasCards(
    private val vocabulary: ReviewVocabulary,
    private val reviewed: ReviewedFiles,
    private val sourceActions: SourceCanvasActions,
    private val summaryActions: SummaryCanvasActions,
    private val summarySession: CanvasSummarySession,
    private val actions: SummariesCanvasActions,
    private val project: Project? = null,
    /** Supplies the Diff mode; without one it shows the outline. */
    private val extension: CanvasExtension? = null,
) : Disposable {
    val summariesView = ChangeSummariesViewState().also { it.mode = vocabulary.summariesMode }
    private var summaries: SummariesCard? = null
    private val currentCards = mutableMapOf<CanvasTabKey, JPanel>()
    private val zoom = 1.0
    private val documentZoom get() = actions.documentZoom()
    private val summaryGenerationRunning get() = summarySession.running
    private val open get() = actions.open
    private val openSummaryNode get() = actions.openNode
    private val showSummaryNode get() = actions.showNode
    private val openSummaryLines get() = actions.openLines

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
        var diffPanel: SummaryDiffView? = null
        var version: Int? = null
        val trace = SummaryTraceSession(view, { reading(this) }, openSummaryLines,
            update = { currentCards[key]?.let { updateCanvasTracePresentation(it, view) } }, refresh = { refreshSummaries(this) })
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
    private fun version(tab: SummariesTab) = summarySession.versions.firstOrNull { it.id == tab.version }
    private fun reading(tab: SummariesTab): ReviewSummaries? = version(tab)?.summaries ?: summaries?.summaries

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
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                if (document) add(actions.zoomControls(key))
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
                add(viewSelector("summaries-mode", summaryModes(), summariesView.mode, scaled(4), scaledFont(JBFont.small()), ::summaryModeLabel, ::summaryModeTooltip) {
                    summariesView.mode = it; tab.scroll = 0; refreshSummaries(tab)
                })
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
            }, BorderLayout.WEST)
        }
        val panelActions = ChangeSummaryActions(
            toggle = { identity -> summariesView.toggle(identity); refreshSummaries(tab) },
            select = { identity -> summariesView.select(identity); refreshSummaries(tab) },
            openFile = open,
            showDiffCard = sourceActions.showDiffCard,
            openNode = openSummaryNode,
            showNode = showSummaryNode,
            openWholeFile = summaryActions.openWholeFile,
            openDirectory = summaryActions.openDirectoryDiff?.let { open ->
                val files = tree.nodes.mapNotNull { (it.file as? PatchSource)?.let { file -> file.path to file } }.toMap()
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
            review = tab.review.presentation(content.summaries, actions.toggleReviewed),
        )
        val inner = width - scaled(2 * PADDING + 4) - scaled(16)
        val split = if (splitMode) ChangeSummarySplitPanel(content, summariesView, zoom, panelActions, vocabulary) else null
        val diff = if (diffMode) extension?.summaryDiffView(content, summariesView, zoom, panelActions)?.also { tab.diffPanel = it } else null
        val card = object : JPanel(SummaryOverlayLayout()) {
            override fun isOptimizedDrawingEnabled(): Boolean = false
        }.apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        currentCards[key] = card
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(JPanel(VerticalLayout(0)).apply {
                isOpaque = false
                add(summaryVersionRow(tab, original))
                if (tab.search) add(searchRow(tab))
                if (explorer) add(summaryExplorerNavigation(tab, tree))
                if (split != null) add(JLabel(summariesStatus(content, vocabulary.noFiles) +
                    " · Click a summary to read it. Double-click to ${if (vocabulary == ReviewVocabulary.DIFF) "open the diff" else "open the file"}. Drag the divider to resize.").apply {
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.empty(6, PADDING)
                })
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        val traceWhere = if (tab.search) "highlighted in the view; the rest are hidden in this tab." else "highlighted in the view."
        if (diff != null) {
            installSummariesTraceOverlay(card, diff.withTree(width, scaled(MAX_SUMMARIES_HEIGHT), tab.treeWidth) { tab.treeWidth = it },
                summariesView, summariesTrace, inner, traceWhere)
            return card
        }
        val body = if (split != null) split.summariesColumn else JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(when {
                hierarchy -> JPanel(VerticalLayout(scaled(8))).apply {
                    isOpaque = false
                    add(JLabel(summariesStatus(content, vocabulary.noFiles)).apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() })
                    add(ChangeSummaryHierarchyPanel(content, summariesView, inner, viewZoom, panelActions, vocabulary))
                }
                document -> JPanel(VerticalLayout(scaled(8))).apply {
                    isOpaque = false
                    add(JLabel(summariesStatus(content, vocabulary.noFiles)).apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() })
                    add(ChangeSummaryDocumentPanel(content, summariesView, inner, viewZoom, panelActions, vocabulary, project))
                }
                else -> ChangeSummariesPanel(content, summariesView, inner, zoom, panelActions, vocabulary,
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
                horizontalScrollBar.addAdjustmentListener { if (currentCards[key] === card) tab.scrollX = it.value }
                SwingUtilities.invokeLater { if (currentCards[key] === card) horizontalScrollBar.value = restoreX }
            }
            // A deep hierarchy or document rail may scroll sideways; leave room for that scroll bar.
            val sideways = if (split != null || (hierarchy || document) && body.preferredSize.width > width) horizontalScrollBar.preferredSize.height else 0
            if (!hierarchy && !document && split == null) horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(width, minOf(body.preferredSize.height + sideways, maxBody))
            verticalScrollBar.unitIncrement = scaled(16)
            horizontalScrollBar.unitIncrement = scaled(16)
            if (document) actions.installWheelZoom(key, this)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = tab.scroll
            verticalScrollBar.addAdjustmentListener { if (currentCards[key] === card) tab.scroll = it.value }
            SwingUtilities.invokeLater {
                if (currentCards[key] !== card) return@invokeLater
                verticalScrollBar.value = restore
                // A jump to a traced summary scrolls its row into view once the rebuilt card is laid out.
                val target = summariesView.reveal ?: return@invokeLater
                summariesView.reveal = null
                card.validate()
                revealCanvasTrace(body, target)
            }
        }
        val readingArea = if (split != null) split.withTree(bodyScroll, width, tab.treeWidth, tab.treeScrollX,
            rememberTreeWidth = { tab.treeWidth = it }, rememberTreeScrollX = { tab.treeScrollX = it }) else bodyScroll
        installSummariesTraceOverlay(card, readingArea, summariesView, summariesTrace, inner, traceWhere)
        return card
    }

    private fun summaryVersionRow(tab: SummariesTab, content: SummariesCard): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), scaled(4))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12)
            add(JLabel("Version"))
            add(JComboBox<Any>((listOf("Original") + summarySession.versions).toTypedArray()).apply {
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
                        summarySession.delete(selected)
                        summaryTabs().filter { it.version == selected.id }.forEach {
                            it.trace.stop()
                            it.version = null
                            reading(it)?.let(it.trace::retain)
                        }
                    }
                    summaryTabs().forEach { refreshSummaries(it) }
                    actions.refreshTopics()
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
            add(tab.searchField.apply {
                preferredSize = Dimension(scaled(260), preferredSize.height)
                if (text != tab.view.fuzzy) text = tab.view.fuzzy
            })
            add(JButton("Cancel search").apply {
                name = "summaries-cancel-search"
                font = scaledFont(JBFont.small())
                cursor = Cursor.getDefaultCursor()
                toolTipText = "Cancel the current search and clear highlights, selected words and all filters in this tab"
                addActionListener { cancelSearch(tab) }
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

    private fun resummarize(tab: SummariesTab, node: SummaryNode, recursive: Boolean) {
        if (summaryActions.contextSummaries == null || summaries?.canRun != true || summaryGenerationRunning) return
        val current = reading(tab) ?: return
        val instructions = actions.requestInstructions(node, recursive, ResummaryDestination.VERSION) ?: return
        startContextSummaries(tab, resummaryRequest(current, node, recursive, instructions))
    }

    private fun startContextSummaries(tab: SummariesTab, explicit: ContextSummaryRequest? = null) {
        val current = reading(tab) ?: return
        val request = explicit ?: contextSummaryRequest(tab) ?: return
        if (summaries?.canRun != true) return
        summarySession.startVersion(current, request) { version ->
            tab.trace.stop()
            tab.version = version.id
        }
    }

    private fun contextSummaryRequest(tab: SummariesTab): ContextSummaryRequest? =
        reading(tab)?.let(tab.view::resummaryRequest) ?: version(tab)?.request?.takeIf { it.nodeIds.isNotEmpty() }

    /**
     * A search tab's queries as breadcrumbs, each going back to the summaries left after it. The typed filter stays out
     * of the breadcrumbs: the search field itself shows it.
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
        actions.rebuildPages()
        actions.focus(tab.key)
    }

    private fun closeSearchTab(tab: SummariesTab) {
        tab.diffPanel?.let(Disposer::dispose)
        tab.diffPanel = null
        tab.trace.stop()
        tab.review.stop()
        searchTabs -= tab
        currentCards.remove(tab.key)
        actions.rebuildPages()
    }

    /** Goes back to the summaries left after the first [count] searches of a search tab; zero shows every summary. */
    private fun backTo(tab: SummariesTab, count: Int) {
        tab.trace.clear()
        tab.view.backTo(count)
        refreshSummaries(tab)
    }

    private fun summaryModes() = ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF || vocabulary == ReviewVocabulary.DIFF }

    private fun summaryModeLabel(mode: ReviewSummariesMode) = when (mode) {
        ReviewSummariesMode.OUTLINE -> "Outline"
        ReviewSummariesMode.HIERARCHY -> "Hierarchy"
        ReviewSummariesMode.DOCUMENT -> "Document"
        ReviewSummariesMode.EXPLORER -> "Explorer"
        ReviewSummariesMode.SPLIT -> "Split"
        ReviewSummariesMode.DIFF -> "Diff"
    }

    private fun summaryModeTooltip(mode: ReviewSummariesMode) = when (mode) {
        ReviewSummariesMode.OUTLINE -> vocabulary.summariesOutlineTooltip
        ReviewSummariesMode.HIERARCHY -> "Folders broad to specific, left to right, with each file's summary"
        ReviewSummariesMode.DOCUMENT -> "Every file in folder order, with its summary and file and folder cards"
        ReviewSummariesMode.EXPLORER -> "Browse one level of summaries at a time, with Back and breadcrumbs"
        ReviewSummariesMode.SPLIT -> "The file tree beside aligned summaries; hover a row to match it with its summary"
        ReviewSummariesMode.DIFF -> "The selected file, or every file in the selected folder, in the IDE’s native diff viewer beside a navigable file tree"
    }

    /** Open folders through the picked 1-based level and fold deeper folders. */
    private fun summaryLevels(tab: SummariesTab, tree: ReviewSummaryTree, levels: Int): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
        isOpaque = false
        val summariesView = tab.view
        val selector = JComboBox((1..levels).toList().toTypedArray()).apply {
            name = "summary-level-selector"
            font = scaledFont(JBFont.small())
            selectedItem = (summariesView.shownLevel(tree) ?: summariesView.selectedLevel).coerceIn(1, levels)
            toolTipText = "Unfold folders through this level (root is level 1) and fold deeper folders"
            addActionListener {
                summariesView.selectLevel(selectedItem as Int, tree)
                refreshSummaries(tab)
            }
        }
        add(JLabel("Level").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
            labelFor = selector
        })
        add(selector)
    }

    fun update(content: SummariesCard?) {
        summaries = content
        if (content == null) {
            summaryTabs().forEach { disposeTab(it) }
            searchTabs.clear()
            currentCards.clear()
            summarySession.stopVersions()
        } else {
            summarySession.retain(content.summaries.tree)
            summaryTabs().forEach { it.view.update(content.summaries.tree); it.trace.retain(reading(it)!!) }
        }
    }

    fun buildCards(): List<Pair<CanvasTabKey, JPanel>> = summaries?.let { content ->
        summaryTabs().map { it.key to summariesCard(it, content) }
    }.orEmpty()

    fun card(key: CanvasTabKey): JPanel? = summaryTab(key)?.let { tab -> summaries?.let { summariesCard(tab, it) } }
    fun scrollTo(key: CanvasTabKey, y: Int) { summaryTab(key)?.scroll = y }
    fun refreshAll() { summaryTabs().forEach { refreshSummaries(it) } }

    fun generationChanged(version: ContextSummaryVersion?) {
        if (version == null) refreshAll()
        else summaryTabs().filter { it.version == version.id }.forEach {
            it.trace.retain(version.summaries)
            refreshSummaries(it)
        }
    }

    fun syncReview() {
        summaryTabs().forEach { tab ->
            summaries?.summaries?.let(tab.review::sync)
            actions.restyleReview(tab.key)
        }
    }

    fun title(key: CanvasTabKey): String = if (key == CanvasTabKey.Summaries) "Summaries"
        else summaryTab(key)?.let(::searchPath)?.lastOrNull()?.let {
            "Search · " + if (it.length > 32) it.take(31) + "…" else it
        } ?: "Search"

    fun tooltip(key: CanvasTabKey): String? = summaryTab(key)?.takeIf { it.search }?.let { tab ->
        (listOf("Summaries") + searchPath(tab).map { "“$it”" }).joinToString(" › ")
    }

    private fun searchPath(tab: SummariesTab): List<String> = tab.view.crumbs.map { it.query }

    private fun refreshSummaries(tab: SummariesTab) {
        val content = summaries ?: return
        val typing = tab.searchField.textEditor.isFocusOwner
        actions.replace(tab.key, summariesCard(tab, content))
        if (typing) tab.searchField.textEditor.requestFocusInWindow()
    }

    private fun disposeTab(tab: SummariesTab) {
        tab.diffPanel?.let(Disposer::dispose)
        tab.diffPanel = null
        tab.trace.stop()
        tab.review.stop()
    }

    override fun dispose() {
        summaryTabs().forEach(::disposeTab)
        currentCards.clear()
    }

    private fun cardWidth(default: Int) = scaled(default)
    private fun cardBorder(highlighted: Boolean) =
        if (highlighted) JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2)
        else JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))
    private fun scaled(value: Int) = zoomScaled(value, zoom)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(zoom)

    private companion object {
        const val PADDING = 12
        const val SUMMARIES_WIDTH = 840
        const val MAX_SUMMARIES_HEIGHT = 820
        const val MAX_TOPICS_HEIGHT = 900
    }
}
