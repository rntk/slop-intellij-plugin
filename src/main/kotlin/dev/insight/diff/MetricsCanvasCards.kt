package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.insight.review.CanvasTabKey
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import dev.insight.review.ui.viewSelector

/** Navigation, removal and in-place refresh supplied by the canvas hosting the metric cards. */
internal data class MetricsCanvasActions(
    val open: (String) -> Unit,
    val openDirectoryDiff: ((List<ReviewFile>) -> Unit)? = null,
    val removeWords: () -> Unit = {},
    val removeDiffOverview: () -> Unit = {},
    val removeSemantic: () -> Unit = {},
    val removeMoves: () -> Unit = {},
    val refresh: (CanvasTabKey) -> Unit,
)

/** Word changes, diff overview, semantic metrics and moved code, including their cached charts and view choices. */
internal class MetricsCanvasCards(private val actions: MetricsCanvasActions, private val project: Project? = null) {
    private var wordFiles = emptyList<ReviewFile>()
    private var semantic: SemanticResult? = null
    /** Placing the words is slow, so the chart is kept across rebuilds while its counts are the same. */
    private var wordChart: WordChangeChart? = null
    private var diffOverviewChart: DiffOverviewChart? = null
    /** The selected view and plotted metric of the semantic changes card, kept across rebuilds. */
    private var semanticMode = SemanticMode.METRICS
    private var semanticMetric = Metric.CYCLOMATIC
    private var semanticChart: SemanticComplexityChart? = null
    private var constructChart: Pair<SemanticChanges, WordChangeChart>? = null
    /** The file or directory the chart counts; kept while the snapshot still changes files in it. */
    private var wordScope = WordScope("", true, 0)
    /** The words of [wordScope], counted again only when the scope or the snapshot's words change. */
    private var scopedWords: ScopedWords? = null
    /** The source file the moved-code chart shows alone, or null for all of them; kept while it still has moves. */
    private var moveFocus: String? = null
    /** The move whose diffs sit beside the chart, and their viewers, kept across rebuilds while the moves are the same. */
    private var moveSelection: MoveSelection? = null
    private var moveDetail: Pair<CodeMoves, CodeMoveDetail>? = null

    fun update(files: List<ReviewFile>, semantic: SemanticResult?, moves: CodeMoves?) {
        wordFiles = files
        this.semantic = semantic
        if (moveDetail?.first !== moves) releaseMoveDetail()
    }

    /** Words of the removed and added lines, counted from the snapshot; a word opens the diffs of its files. */
    fun wordsCard(all: WordChanges, overview: Boolean = false): JPanel {
        val width = scaled(WORDS_WIDTH)
        val scopes = wordScopes(wordFiles)
        wordScope = scopes.firstOrNull { it.path == wordScope.path && it.directory == wordScope.directory } ?: scopes.first()
        val words = if (wordScope.path.isEmpty()) all
            else scopedWords?.takeIf { it.all === all && it.scope == wordScope }?.words
                ?: wordChanges(wordFiles.filter { wordScope.contains(it.path) }).also { scopedWords = ScopedWords(all, wordScope, it) }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel(if (overview) "Diff overview · Deleted and added lines, and word balance"
                else "Word changes · ${words.words.size} most frequent words of the changed lines").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Counted from the removed and added lines of the snapshot; context lines are not counted. " +
                    (if (overview) "Compact word lists fill opposite corners, longest first. The middle chart compares deleted and added line counts, including blank lines. "
                    else "A word's position compares its share of removed and added words, its size how often it occurs. ") +
                    "Click a word to open its files' diffs."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                add(wordScopeButton(scopes))
                add(InplaceButton(IconButton(if (overview) "Remove diff overview" else "Remove word changes", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                    if (overview) actions.removeDiffOverview() else actions.removeWords()
                }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.WEST)
        }
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(if (words.words.isEmpty() && !overview) JLabel("No words in the changed lines.").apply { foreground = UIUtil.getContextHelpForeground() }
                else if (overview) diffOverviewChart?.takeIf { it.changes === words }
                    ?: DiffOverviewChart(words, width - scaled(2 * PADDING + 4), scaled(WORDS_HEIGHT), ::openWordDiff).also { diffOverviewChart = it }
                else wordChart?.takeIf { it.changes === words }
                    ?: WordChangeChart(words, width - scaled(2 * PADDING + 4), scaled(WORDS_HEIGHT), ::openWordDiff).also { wordChart = it }, BorderLayout.CENTER)
            if (overview && words.balance.total > 0) {
                add(JPanel(BorderLayout()).apply {
                    isOpaque = false; border = JBUI.Borders.emptyTop(8)
                    add(WordBalanceChart(words.balance, width - scaled(2 * PADDING + 4)), BorderLayout.CENTER)
                }, BorderLayout.SOUTH)
            }
        }
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder()
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    /**
     * Semantic metrics counted from both sides of the changed files, as a delta table, a scatter of function complexity
     * or a cloud of the constructs added and removed. The mode and the plotted metric survive rebuilds.
     */
    fun semanticCard(result: SemanticResult): JPanel {
        val width = scaled(WORDS_WIDTH)
        val changes = (result as? SemanticResult.Ready)?.changes
        val skipped = changes?.files?.filter { it.skipped != null }.orEmpty()
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                if (changes != null) {
                    add(viewSelector("semantic-mode", SemanticMode.entries, semanticMode, scaled(4), scaledFont(JBFont.small()), SemanticMode::label, { mode ->
                        when (mode) {
                            SemanticMode.METRICS -> "Totals of each metric before and after the change; click a row to open the diffs of its files"
                            SemanticMode.COMPLEXITY -> "Each function's value before and after the change; click a point to open its diff"
                            SemanticMode.CONSTRUCTS -> "Source constructs removed on the left and added on the right; click one to open its diffs"
                        }
                    }) { semanticMode = it; actions.refresh(CanvasTabKey.Semantic) })
                } else add(JLabel("Semantic changes").apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() })
            }, BorderLayout.EAST)
            add(JLabel(if (skipped.isEmpty()) "" else "${files(skipped.size)} skipped").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                horizontalAlignment = SwingConstants.CENTER
                name = "semantic-skipped"
                toolTipText = if (skipped.isEmpty()) null else "<html>Not counted:<br>" + skipped.take(20).joinToString("<br>") {
                    "${escapeHtml(it.path)} · ${escapeHtml(it.skipped.orEmpty())}"
                } + (if (skipped.size > 20) "<br>… ${skipped.size - 20} more" else "") + "</html>"
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                if (changes != null && semanticMode == SemanticMode.COMPLEXITY) add(JComboBox(COMPLEXITY_METRICS.toTypedArray()).apply {
                    name = "semantic-metric"
                    font = scaledFont(JBFont.small())
                    renderer = SimpleListCellRenderer.create("") { it.label }
                    toolTipText = "The metric plotted for each function"
                    selectedItem = semanticMetric
                    addActionListener { (selectedItem as? Metric)?.takeIf { it != semanticMetric }?.let { semanticMetric = it; actions.refresh(CanvasTabKey.Semantic) } }
                })
                add(InplaceButton(IconButton("Remove semantic changes", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { actions.removeSemantic() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.WEST)
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
            background = UIUtil.getListBackground(); border = cardBorder()
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    /**
     * Code removed from one file and added to another; all source files at once, or one of them fanning out by share.
     * Beside the chart, the diffs of the chosen move show the code where it left and where it arrived.
     */
    fun movesCard(moves: CodeMoves): JPanel {
        val width = scaled(MOVES_WIDTH)
        if (moves.sources.none { it.path == moveFocus }) moveFocus = null
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel(if (moves.sources.isEmpty()) "Moved code" else "Moved code · ${files(moves.sources.size)} moved " +
                "${moves.blocks.sumOf { it.lines }} lines to ${files(moves.destinations.size)}").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Runs of lines removed from one file and added to another, compared without indentation. Blank, " +
                    "brace-only and import lines are not counted. Percentages are shares of each file's lines before the change. " +
                    "Click a line or a destination to compare the moved code in both files beside the chart."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                if (moves.sources.isNotEmpty()) add(JComboBox((listOf<String?>(null) + moves.sources.map { it.path }).toTypedArray()).apply {
                    name = "move-focus"
                    font = scaledFont(JBFont.small())
                    renderer = SimpleListCellRenderer.create<String?>("All files") { it }
                    toolTipText = "Show where the code of one file went"
                    selectedItem = moveFocus
                    addActionListener { chooseMoveFocus(selectedItem as String?) }
                })
                add(InplaceButton(IconButton("Remove moved code", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { actions.removeMoves() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.WEST)
        }
        val body = JPanel(BorderLayout(scaled(PADDING), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            if (moves.sources.isEmpty()) {
                add(JLabel("No code was moved between the changed files.").apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
                return@apply
            }
            val chartWidth = scaled(MOVES_CHART_WIDTH)
            val height = scaled(MOVES_HEIGHT)
            val slot = JPanel(BorderLayout()).apply {
                isOpaque = false
                name = "move-detail"
                preferredSize = Dimension(width - chartWidth - scaled(3 * PADDING + 4), height)
            }
            lateinit var chart: CodeMoveChart
            fun show(selection: MoveSelection) {
                moveSelection = selection
                chart.selected = selection
                slot.removeAll()
                slot.add(moveDetail(moves, selection), BorderLayout.CENTER)
                slot.revalidate(); slot.repaint()
            }
            chart = CodeMoveChart(moves, moveFocus, chartWidth, ::show, ::chooseMoveFocus)
            add(scrollBody(chart, chartWidth, height), BorderLayout.WEST)
            add(slot, BorderLayout.CENTER)
            show(validSelection(moves))
        }
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder()
            add(header, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }
    }

    /** The kept selection while it is still a move of the shown files; otherwise the largest move of the focused or first file. */
    private fun validSelection(moves: CodeMoves): MoveSelection {
        val kept = moveSelection?.takeIf { selection ->
            (moveFocus == null || selection.source == moveFocus) && moves.sources.any { file ->
                file.path == selection.source && (selection.target == null || file.targets.any { it.path == selection.target })
            }
        }
        if (kept != null) return kept
        val file = moves.sources.firstOrNull { it.path == moveFocus } ?: moves.sources.first()
        return MoveSelection(file.path, file.targets.firstOrNull()?.path)
    }

    /** The diffs of [selection], reusing the open viewers when it is already shown. */
    private fun moveDetail(moves: CodeMoves, selection: MoveSelection): JComponent {
        moveDetail?.takeIf { it.first === moves && it.second.selection == selection }?.let { return it.second }
        releaseMoveDetail()
        val source = wordFiles.firstOrNull { it.path == selection.source }
            ?: return JLabel("${selection.source} is not in this diff.").apply { foreground = UIUtil.getContextHelpForeground() }
        val target = selection.target?.let { path -> wordFiles.firstOrNull { it.path == path } }
        val blocks = if (target == null) emptyList() else moves.blocks.filter { it.from == source.path && it.to == target.path }
        return CodeMoveDetail(project, selection, source, target, blocks) { paths -> openPaths(paths.toSet()) }
            .also { moveDetail = moves to it }
    }

    private fun releaseMoveDetail() {
        moveDetail?.second?.let(Disposer::dispose)
        moveDetail = null
    }

    fun dispose() = releaseMoveDetail()

    fun chooseMoveFocus(path: String?) {
        if (path == moveFocus) return
        moveFocus = path
        actions.refresh(CanvasTabKey.Moves)
    }

    private fun openMetricDiff(metric: Metric) {
        val changes = (semantic as? SemanticResult.Ready)?.changes ?: return
        openPaths(changes.filesChanging(metric).map { it.path }.toSet())
    }

    private fun openDeclarationDiff(declaration: DeclarationMetrics) { actions.open(declaration.path) }

    private fun openWordDiff(word: WordChange) { openPaths(word.paths) }

    private fun openPaths(paths: Set<String>) {
        val files = wordFiles.filter { it.path in paths }
        val directory = actions.openDirectoryDiff
        when {
            files.size == 1 || (directory == null && files.isNotEmpty()) -> actions.open(files.first().path)
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

    fun chooseWordScope(scope: WordScope) {
        if (scope == wordScope) return
        wordScope = scope
        actions.refresh(CanvasTabKey.Words)
        actions.refresh(CanvasTabKey.DiffOverview)
    }

    private fun scrollBody(body: JComponent, width: Int, maxHeight: Int) = JBScrollPane(body).apply {
        border = null; isOpaque = false; viewport.isOpaque = false
        preferredSize = Dimension(width, minOf(body.preferredSize.height, maxHeight))
        verticalScrollBar.unitIncrement = scaled(16)
        horizontalScrollBar.unitIncrement = scaled(16)
    }

    private fun cardBorder() =
        JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))

    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"
    private fun scaled(value: Int) = zoomScaled(value, 1.0)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(1.0)

    /** The word changes of [scope], memoized while the unscoped changes stay [all]. */
    private data class ScopedWords(val all: WordChanges, val scope: WordScope, val words: WordChanges)

    private companion object {
        const val PADDING = 12
        const val WORDS_WIDTH = 1100
        const val WORDS_HEIGHT = 640
        const val MOVES_WIDTH = 1500
        const val MOVES_CHART_WIDTH = 560
        const val MOVES_HEIGHT = 760
    }
}
