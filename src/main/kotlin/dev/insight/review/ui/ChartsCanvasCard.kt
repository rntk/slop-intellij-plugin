package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.canvas.CanvasSummaryException
import dev.insight.canvas.SymbolSummarySource
import dev.insight.canvas.relativePath
import dev.insight.canvas.summarySource
import dev.insight.context.ReviewDestination
import dev.insight.context.ContextSummaryActions
import dev.insight.review.ChartSearchResult
import dev.insight.review.LineChartMetric
import dev.insight.review.matchChartPaths
import dev.insight.review.OutlineDeclaration
import dev.insight.review.RadarChart
import dev.insight.review.RadarSeries
import dev.insight.review.ReviewLineChart
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ReviewSource
import dev.insight.review.SourceOutlines
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.summaryRadar
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import com.intellij.ui.components.JBTextField
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The scope's folder and file tree and its available summaries. */
internal data class ChartsCard(
    val summaries: ReviewSummaries,
    val canGenerateSummaries: Boolean = true,
    val pendingEntries: Set<Int> = emptySet(),
    /** The review state's revision; search results of another revision are discarded. */
    val revision: Int = 0,
) {
    val tree get() = summaries.tree
}

/** Chart selection and navigation, kept while the page is rebuilt. */
internal class ChartsViewState {
    /** The radar's folder level, 1 being the root's entries; null picks the first level with a few entries. */
    var radarLevel: Int? = null
    var chart: String? = null
    var linesPath: String? = null
    var linesPreview: LineChartPreview? = null
    var linesTagsCloud = false
    var linesScroll: Int = 0
    /** Unfolded directories, kept across directory changes. */
    var linesExpanded: Set<String> = emptySet()
    /** The current row; null picks the first. */
    var linesCursor: String? = null
    /** The divider between the chart and its preview, once moved or laid out. */
    var linesDivider: Int? = null
    /** Whether the chart had keyboard focus when it was last rebuilt. */
    var linesFocused = false
    var lineMetric: LineChartMetric? = null
    var radarSymbols = true
    /** Whether the radar spaces its rings logarithmically. */
    var radarLog = false
    var radarSeries: RadarSeries? = null
    /** The search field's text, kept while the page is rebuilt. */
    var searchQuery = ""
    /** The last finished search; its files make the Search tab. */
    var searchResult: ChartSearchResult? = null
    var searchStatus: String? = null
    var searchRunning = false
}

/**
 * The Charts page: charts that compare how much the folders and files of the review hold. Chart and
 * option changes rebuild only this card, through [refresh].
 */
internal class ChartsCanvasCard(
    private val vocabulary: ReviewVocabulary,
    private val open: (String) -> Unit,
    private val refresh: () -> Unit,
    private val close: (() -> Unit)? = null,
    private val project: Project? = null,
    private val openOnCanvas: ((SummaryNode) -> Unit)? = null,
    private val openOnWall: ((List<ReviewSource>, String) -> Unit)? = null,
    private val reviewDestinations: () -> Map<ReviewDestination, () -> Unit> = { emptyMap() },
    private val summaryActions: ContextSummaryActions? = null,
    /** Opens a file at a 1-based line; null opens the file. */
    private val openAt: ((String, Int) -> Unit)? = null,
    /** Parses files into declarations when unfolded; null keeps files as leaves. */
    private val outlines: SourceOutlines? = null,
    private val symbolSummaries: SymbolSummaryActions? = null,
    /** Finds files for a query typed above the hierarchy; null hides the search. */
    private val search: ChartSearchActions? = null,
) : Disposable {
    val view = ChartsViewState()
    private val zoom = 1.0
    private var preview: HierarchyPreviewPanel? = null
    private var currentCard: JPanel? = null
    private var activeSymbolKey: String? = null
    private val pendingSymbols = mutableSetOf<String>()
    private val symbolErrors = mutableMapOf<String, String>()
    /** The review revision of the last built card; a search of another revision is stale. */
    private var searchRevision = 0

    fun update(content: ChartsCard?) {
        if (content == null) {
            preview?.let(Disposer::dispose)
            preview = null
            currentCard = null
            activeSymbolKey = null
        }
    }

    fun card(content: ChartsCard): JPanel {
        preview?.let(Disposer::dispose)
        preview = null
        activeSymbolKey = null
        val radar = view.chart == "Radar"
        val circles = view.chart == CIRCLES
        // The line hierarchy and the circles share the metric tabs, the directory and the search.
        val tabbed = !radar
        val lines = tabbed && !circles
        val diff = vocabulary == ReviewVocabulary.DIFF
        val linesTitle = if (diff) "Changed lines" else "File metrics"
        searchRevision = content.revision
        // A search names the files of the revision it ran against; a new revision may have removed or changed them.
        if (view.searchResult?.let { it.revision != content.revision } == true) {
            view.searchResult = null
            view.searchStatus = null
        }
        val baseMetrics = if (diff) listOf(LineChartMetric.ADDED, LineChartMetric.REMOVED)
            else listOf(LineChartMetric.LINES, LineChartMetric.SIZE, LineChartMetric.ITEMS) + LineChartMetric.lenses(
                content.tree.nodes.mapNotNull { if (it.kind == SummaryKind.FILE) it.file else null }.distinctBy { it.path })
        val metrics = baseMetrics + listOfNotNull(LineChartMetric.FOUND.takeIf { search != null && view.searchResult?.files?.isNotEmpty() == true })
        val metric = view.lineMetric?.takeIf { it in metrics } ?: metrics.first()
        val chart by lazy { summaryRadar(content.tree, view.radarLevel, view.radarSymbols) }
        val width = scaled(RadarChartPanel.DEFAULT_WIDTH)
        val header = JPanel(BorderLayout(scaled(8), 0)).apply {
            name = "charts-controls"
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Charts").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = if (diff) "Explore added or removed lines by directory, walk through them as circles, or compare changes on the radar"
                    else "Explore source lines, byte sizes, file counts and PSI lenses (classes, functions, tests…) by directory and down into each file's declarations, walk through them as circles, or compare code structure on the radar"
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                add(JComboBox(arrayOf(linesTitle, CIRCLES, "Radar")).apply {
                    name = "charts-type-selector"
                    font = scaledFont(JBFont.small())
                    selectedItem = when { lines -> linesTitle; circles -> CIRCLES; else -> "Radar" }
                    addActionListener { view.chart = selectedItem as String; refresh() }
                })
                if (radar && chart.levels > 1) add(radarLevels(chart))
                if (radar && vocabulary == ReviewVocabulary.CODE) add(checkBox("Symbols", "charts-radar-symbols", view.radarSymbols,
                    "Compare PSI declarations (including constructors) and comment blocks; uncheck to compare lines") {
                    view.radarSymbols = it
                })
                if (radar) add(checkBox("Log scale", "charts-radar-log", view.radarLog,
                    "Space the rings logarithmically, so small folders stay visible beside a large one") { view.radarLog = it })
                close?.let { remove ->
                    add(InplaceButton(IconButton("Remove charts", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { remove() }
                        .apply { cursor = Cursor.getDefaultCursor() })
                }
            }, BorderLayout.WEST)
        }
        val inner = width - scaled(2 * PADDING + 4) - scaled(16)
        val hierarchy = if (tabbed) ReviewLineChart(content.tree, metric, outlines,
            only = view.searchResult?.files?.takeIf { metric == LineChartMetric.FOUND }) else null
        val activePreview = hierarchy?.takeIf { lines }?.let { chart ->
            view.linesPreview?.takeIf { chart.hasEntry(it.path) }.also { view.linesPreview = it }
        }
        val lineChart = if (hierarchy != null && lines) LineChartPanel(
            hierarchy,
            view.linesPath,
            vocabulary.rootName,
            { path ->
                view.linesPath = path
                view.linesScroll = 0
                rebuildKeepingFocus()
            },
            open,
            // Search rows offer only the matching files, never the hidden ones of the same folder.
            contextMenu = project?.let { { entry -> contextMenu(content.tree, entry, hierarchy.files(hierarchy.root.path)) } },
            preview = activePreview,
            togglePreview = { selection ->
                view.linesPreview = if (view.linesPreview == selection) null else selection
                rebuildKeepingFocus()
            },
            expanded = view.linesExpanded,
            expansionChanged = { view.linesExpanded = it },
            cursorPath = view.linesCursor,
            cursorChanged = { view.linesCursor = it },
            openAt = openAt ?: { path, _ -> open(path) },
            loadOutline = outlines?.let { provider -> { file, done -> provider.load(file, done) } },
            canSummarizeDeclaration = { symbolSummaries != null && it.summarySpan != null },
        ) else null
        val circleChart = if (hierarchy != null && circles) circleChart(hierarchy, content.tree, inner) else null
        val body = JPanel(VerticalLayout(scaled(8))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            if (lineChart != null) add(lineChart)
            else if (circleChart != null) add(circleChart)
            else {
                val status = JLabel(radarStatus(chart, view.radarSeries)).apply {
                    name = "charts-radar-status"
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                }
                add(status)
                val nodes = content.tree.nodes.filter { it.kind == SummaryKind.FILE || it.kind == SummaryKind.DIRECTORY }
                    .associateBy { "node:${it.identity}" }
                add(RadarChartPanel(chart, inner, zoom, view.radarLog, open, view.radarSeries,
                    axisMenu = project?.let { { axis -> nodes[axis.identity]?.let { contextMenu(it, axis.files) } } }) { selected ->
                    view.radarSeries = selected
                    status.text = radarStatus(chart, selected)
                })
            }
        }
        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))
        }
        val declaration = lineChart?.previewDeclaration()
        val previewFiles = lineChart?.previewFiles().orEmpty() +
            listOfNotNull(declaration?.first?.takeIf { activePreview?.kind == LineChartPreviewKind.SOURCE })
        val summary = activePreview?.takeIf { it.kind == LineChartPreviewKind.SUMMARY }?.let { selection ->
            if (declaration != null) {
                val (file, symbol) = declaration
                val path = project?.let { relativePath(it, file.absolutePath) } ?: file.path
                val source = file.text?.let { symbol.summarySpan?.summarySource(path, it) }
                if (source != null && symbolSummaries != null) {
                    symbolSummaryPreview(source, "${symbol.name} · ${file.path}:${symbol.lines.first}", symbolSummaries)
                } else null
            } else {
                val node = content.tree.scopeNode(selection.path, SummaryKind.FILE)
                    ?: content.tree.scopeNode(selection.path, SummaryKind.DIRECTORY)
                node?.let { summaryPreview(it, content) }
            }
        }
        val bodyScroll = JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            val minHeight = if (previewFiles.isNotEmpty() || summary != null) scaled(MIN_PREVIEW_HEIGHT) else 0
            preferredSize = Dimension(width, minOf(maxOf(body.preferredSize.height, minHeight), scaled(MAX_HEIGHT)))
            verticalScrollBar.unitIncrement = scaled(16)
            if (lines) {
                val restore = view.linesScroll
                verticalScrollBar.addAdjustmentListener { if (currentCard === card) view.linesScroll = it.value }
                SwingUtilities.invokeLater {
                    if (currentCard === card) verticalScrollBar.value = restore
                }
            }
        }
        val readingArea = if (lineChart != null) {
            val actionLabel = if (diff) "Show diff" else "Show source"
            val hint = if (symbolSummaries == null) "Choose Show summary or $actionLabel on a file or directory."
                else "Choose Show summary or $actionLabel on a file, directory, or declaration."
            HierarchyPreviewPanel(bodyScroll, project, hint, detail = summary,
                dividerLocation = view.linesDivider,
                rememberDivider = { if (currentCard === card) view.linesDivider = it },
            ).also {
                if (activePreview?.kind == LineChartPreviewKind.SOURCE && declaration != null) {
                    it.showLines(declaration.first, declaration.second.lines, declaration.second.name)
                }
                else if (summary == null) it.showFiles(previewFiles)
                preview = it
            }
        } else bodyScroll
        val searchRow = search?.takeIf { tabbed }?.let { actions ->
            searchRow(actions, content.tree.nodes.mapNotNull { if (it.kind == SummaryKind.FILE) it.file else null }.distinctBy { it.path },
                content.revision)
        }
        card.add(if (searchRow == null) header else JPanel(BorderLayout()).apply {
            isOpaque = false
            add(searchRow, BorderLayout.NORTH)
            add(header, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        card.add(if (tabbed) metricTabs(metrics, metric, readingArea) else readingArea, BorderLayout.CENTER)
        currentCard = card
        val focusTarget: JComponent? = lineChart ?: circleChart
        if (focusTarget != null && view.linesFocused) {
            view.linesFocused = false
            SwingUtilities.invokeLater { if (currentCard === card) focusTarget.requestFocusInWindow() }
        }
        return card
    }

    private fun metricTabs(metrics: List<LineChartMetric>, selected: LineChartMetric, content: JComponent) =
        JBTabbedPane().apply {
            name = "charts-lines-metric-tabs"
            // Lenses vary with the languages in scope; scrolling keeps one row of tabs and the selection in place.
            tabLayoutPolicy = javax.swing.JTabbedPane.SCROLL_TAB_LAYOUT
            accessibleContext.accessibleName = "Chart metric"
            font = scaledFont(JBFont.small())
            metrics.forEachIndexed { index, metric ->
                val found = view.searchResult?.takeIf { metric == LineChartMetric.FOUND }
                val title = found?.let { "Search: ${it.query.ellipsized(SEARCH_TAB_CHARS)}" } ?: metric.label
                val tooltip = found?.let { "${it.files.size} files found for “${it.query}”" } ?: metric.description
                addTab(title, null, if (metric == selected) content else JPanel(), tooltip)
                // Darcula's scrolling layout can leave JBTabbedPane's custom labels unpainted.
                setTabComponentAt(index, null)
                metric.diffSeries?.let { series ->
                    setForegroundAt(index, RadarChartPanel.color(series))
                }
            }
            selectedIndex = metrics.indexOf(selected)
            addChangeListener {
                val restoreFocus = isFocusOwner
                view.lineMetric = metrics[selectedIndex]
                refresh()
                if (restoreFocus) {
                    val card = currentCard
                    val tabs = (card?.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER)
                    SwingUtilities.invokeLater { if (currentCard === card) tabs?.requestFocusInWindow() }
                }
            }
        }

    /**
     * A one-line query and Send: the model lists and reads the review's files and names the matching paths. The result
     * becomes a Search tab rather than filtering the current one.
     */
    private fun searchRow(actions: ChartSearchActions, files: List<ReviewSource>, revision: Int): JComponent {
        val status = JLabel(view.searchStatus.orEmpty()).apply {
            name = "charts-search-status"
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
        }
        val field = JBTextField(view.searchQuery).apply {
            name = "charts-search-field"
            emptyText.text = if (vocabulary == ReviewVocabulary.DIFF) "Find changed files, e.g. “error handling”"
                else "Find files, e.g. “settings persistence”"
            accessibleContext.accessibleName = "Search query"
            isEnabled = !view.searchRunning
            document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) { view.searchQuery = text }
            })
        }
        val send = JButton(if (view.searchRunning) "Searching…" else "Send").apply {
            name = "charts-search-send"
            isEnabled = !view.searchRunning
            toolTipText = "Ask the model which files match; the matches open in a Search tab"
        }
        val start = {
            val query = field.text.trim()
            if (query.isNotEmpty() && !view.searchRunning) {
                runSearch(actions, query, files, revision)
                field.isEnabled = false
                send.isEnabled = false
                send.text = "Searching…"
                status.text = view.searchStatus
            }
        }
        field.addActionListener { start() }
        send.addActionListener { start() }
        return JPanel(BorderLayout(scaled(6), 0)).apply {
            name = "charts-search"
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 8))
            add(field, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
                isOpaque = false
                add(send)
                add(status)
                if (view.searchResult != null && !view.searchRunning) {
                    add(InplaceButton(IconButton("Clear search", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                        view.searchResult = null
                        view.searchStatus = null
                        refresh()
                    }.apply { name = "charts-search-clear"; cursor = Cursor.getDefaultCursor() })
                }
            }, BorderLayout.EAST)
        }
    }

    private fun runSearch(actions: ChartSearchActions, query: String, files: List<ReviewSource>, revision: Int) {
        if (!actions.scope.isActive) return
        // The review context is captured here on the EDT; the client, which reads the password safe, starts on IO.
        val start = try {
            actions.search()
        } catch (e: Exception) {
            if (e is CancellationException || e is ControlFlowException) throw e
            logger<ChartsCanvasCard>().warn("Could not start the Charts search", e)
            view.searchStatus = "The search could not start. See the IDE log."
            return refresh()
        }
        view.searchRunning = true
        view.searchStatus = "Searching…"
        actions.scope.launch {
            try {
                val paths = withContext(Dispatchers.IO) { start().find(query) }
                if (revision != searchRevision) {
                    view.searchStatus = "The review changed during the search. Send again to search the current files."
                    return@launch
                }
                val found = matchChartPaths(paths, files)
                view.searchResult = ChartSearchResult(query, paths, found, revision).takeIf { found.isNotEmpty() } ?: view.searchResult
                view.searchStatus = when {
                    found.isEmpty() -> "No matching files for “${query.ellipsized(SEARCH_TAB_CHARS)}”."
                    else -> "${found.size} ${if (found.size == 1) "file" else "files"} found — see the Search tab."
                }
            } catch (e: Exception) {
                if (e is CancellationException || e is ControlFlowException) throw e
                logger<ChartsCanvasCard>().warn("Charts search failed", e)
                view.searchStatus = "The search failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                view.searchRunning = false
                if (isActive && currentCard != null) refresh()
            }
        }
    }

    private fun String.ellipsized(max: Int) = if (length <= max) this else take(max - 1) + "…"

    /** Rebuilds the card, returning keyboard focus to the chart when it had it. */
    private fun rebuildKeepingFocus() {
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        view.linesFocused = owner != null && currentCard?.let { SwingUtilities.isDescendingFrom(owner, it) } == true
        refresh()
    }

    /** The circles of the shown directory; walking into a folder keeps the hierarchy's directory in step. */
    private fun circleChart(hierarchy: ReviewLineChart, tree: ReviewSummaryTree, width: Int): CircleChartPanel =
        CircleChartPanel(hierarchy, view.linesPath, vocabulary.rootName, minOf(width, scaled(MAX_CIRCLES_DIAMETER)),
            navigate = { path ->
                view.linesPath = path
                view.linesScroll = 0
                rebuildKeepingFocus()
            },
            open = open,
            contextMenu = project?.let { { entry -> contextMenu(tree, entry, hierarchy.files(hierarchy.root.path)) } },
        )

    private fun summaryPreview(node: SummaryNode, content: ChartsCard) = JPanel(BorderLayout()).apply {
        val summary = content.summaries.text(node.id)
        name = "charts-lines-summary:${node.path}"
        background = UIUtil.getListBackground()
        add(JPanel(BorderLayout(scaled(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8)
            add(JLabel(node.location).apply {
                font = JBFont.label().asBold()
                toolTipText = node.location
            }, BorderLayout.CENTER)
            if (summary != null) {
                add(checkBox("Tags cloud", "charts-lines-tags-cloud", view.linesTagsCloud,
                    "Size summary words by their importance in the child summaries or file source") {
                    view.linesTagsCloud = it
                }, BorderLayout.EAST)
            } else if (summaryActions != null) {
                val pending = node.id in content.pendingEntries
                add(JButton(if (pending) "Summarizing…" else "Summarize").apply {
                    name = "charts-lines-generate-summary"
                    isEnabled = !pending && content.canGenerateSummaries && summaryActions.available(node.path, node.kind)
                    toolTipText = "Generate a summary for ${node.location}"
                    addActionListener {
                        if (summaryActions.available(node.path, node.kind)) summaryActions.generate(node.path, node.kind)
                    }
                }, BorderLayout.EAST)
            }
        }, BorderLayout.NORTH)
        val weights = if (summary != null && view.linesTagsCloud) SummaryWordWeights(content.summaries)[node.id] else emptyMap()
        add(summaryBody(summary, "No summary available yet.", weights), BorderLayout.CENTER)
    }

    private fun symbolSummaryPreview(source: SymbolSummarySource, location: String, actions: SymbolSummaryActions): JPanel {
        val key = source.key
        activeSymbolKey = key
        val error = symbolErrors[key]
        if (error == null && !actions.cache.checked(key)) runSymbolTask(source, actions, generate = false)
        val summary = actions.cache.peek(key)
        val pending = key in pendingSymbols
        return JPanel(BorderLayout()).apply {
            name = "charts-lines-symbol-summary"
            background = UIUtil.getListBackground()
            add(JPanel(BorderLayout(scaled(8), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(8)
                add(JLabel(location).apply {
                    font = JBFont.label().asBold()
                    toolTipText = location
                }, BorderLayout.CENTER)
                if (summary == null) add(JButton(if (error == null) "Summarize" else "Retry").apply {
                    name = "charts-lines-generate-symbol-summary"
                    isEnabled = !pending
                    addActionListener {
                        runSymbolTask(source, actions, generate = true)
                        rebuildKeepingFocus()
                    }
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            val status = when {
                pending && actions.cache.checked(key) -> "Summarizing…"
                pending -> "Loading summary…"
                error != null -> error
                else -> "No summary available yet."
            }
            add(summaryBody(summary, status), BorderLayout.CENTER)
        }
    }

    private fun runSymbolTask(source: SymbolSummarySource, actions: SymbolSummaryActions, generate: Boolean) {
        val key = source.key
        if (!actions.scope.isActive || !pendingSymbols.add(key)) return
        if (generate) symbolErrors.remove(key)
        actions.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (generate) actions.cache.summarize(source, actions.complete)
                    else actions.cache.read(key)
                }
            } catch (e: CanvasSummaryException) {
                symbolErrors[key] = e.message ?: "The summary failed."
            } catch (e: Exception) {
                if (e is CancellationException || e is ControlFlowException) throw e
                logger<ChartsCanvasCard>().warn("Could not load or summarize ${source.path}", e)
                symbolErrors[key] = "The summary failed. See the IDE log."
            } finally {
                pendingSymbols.remove(key)
                if (isActive && activeSymbolKey == key && currentCard != null) rebuildKeepingFocus()
            }
        }
    }

    private fun summaryBody(summary: String?, status: String, weights: Map<String, Double> = emptyMap()): JComponent {
        val text = WrappedSummaryText(summary ?: status, scaled(240),
            JBFont.label().forSummaryText(), weights, breakLongWords = true).apply {
            name = "charts-lines-summary-text"
            border = JBUI.Borders.empty(0, 8, 8, 8)
            foreground = if (summary == null) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
        }
        return JBScrollPane(text).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            viewport.addChangeListener {
                val width = viewport.extentSize.width
                if (width > 0) text.wrapAt(width)
            }
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = scaled(16)
        }
    }

    private fun contextMenu(tree: ReviewSummaryTree, entry: ReviewLineChart.Entry, sources: List<ReviewSource>): JPopupMenu {
        val node = tree.nodes.firstOrNull { it.path == entry.filePath } ?: tree.root
        return contextMenu(node, sources, entry.declaration)
    }

    private fun contextMenu(node: SummaryNode, sources: List<ReviewSource>, declaration: OutlineDeclaration? = null): JPopupMenu = JPopupMenu().apply {
        addSummaryContextItem(this, project, node, sources, openOnCanvas, openOnWall, reviewDestinations(), summaryActions, declaration)
    }

    private fun checkBox(text: String, name: String, selected: Boolean, tooltip: String, set: (Boolean) -> Unit) =
        JCheckBox(text, selected).apply {
            this.name = name
            isOpaque = false
            font = scaledFont(JBFont.small())
            cursor = Cursor.getDefaultCursor()
            toolTipText = tooltip
            addActionListener { set(isSelected); refresh() }
        }

    /** The radar's folder level: 1 compares the root's entries, the last compares every file. */
    private fun radarLevels(chart: RadarChart): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
        isOpaque = false
        val selector = JComboBox((1..chart.levels).map { if (it == chart.levels) "Files" else "$it" }.toTypedArray()).apply {
            name = "charts-radar-level-selector"
            font = scaledFont(JBFont.small())
            selectedIndex = chart.level - 1
            toolTipText = "Compare the folders at this level (the root's entries are level 1); Files compares every file"
            addActionListener {
                view.radarLevel = selectedIndex + 1
                refresh()
            }
        }
        add(JLabel("Level").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
            labelFor = selector
        })
        add(selector)
    }

    private fun scaled(value: Int) = zoomScaled(value, zoom)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(zoom)

    override fun dispose() {
        preview?.let(Disposer::dispose)
        preview = null
        currentCard = null
        activeSymbolKey = null
    }

    private companion object {
        const val PADDING = 12
        const val MIN_PREVIEW_HEIGHT = 560
        const val MAX_HEIGHT = 820
        const val SEARCH_TAB_CHARS = 28
        const val CIRCLES = "Circles"
        const val MAX_CIRCLES_DIAMETER = 680
    }
}
