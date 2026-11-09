package dev.insight.diff

import dev.insight.review.WordScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.insight.review.CanvasTabKey
import dev.insight.review.ReviewNoteKey
import dev.insight.review.ui.CanvasExtension
import dev.insight.review.ui.ChangeSummariesViewState
import dev.insight.review.ui.ChangeSummaryActions
import dev.insight.review.ui.SnapshotCanvasCards
import dev.insight.review.ui.SummariesCard
import dev.insight.review.ui.SummaryDiffView
import javax.swing.JPanel

/** Metric card removal supplied by the review hosting the canvas. */
internal data class MetricCardActions(
    val removeWords: () -> Unit = {},
    val removeDiffOverview: () -> Unit = {},
    val removeSemantic: () -> Unit = {},
    val removeMoves: () -> Unit = {},
)

/** Content of the diff review's own canvas pages, alongside the shared canvas state. */
internal data class DiffCanvasContent(
    val diffs: List<ReviewFile> = emptyList(),
    val words: WordChanges? = null,
    val wordFiles: List<ReviewFile> = emptyList(),
    val diffOverview: WordChanges? = null,
    val semantic: SemanticResult? = null,
    val moves: CodeMoves? = null,
    val wall: DiffsWallContent? = null,
)

/**
 * The diff review's canvas pages: the Diffs wall, word changes, diff overview, semantic metrics and moved code, diff snapshots in
 * native viewers, and the summaries Diff mode. Set [content] before the canvas rebuilds.
 */
internal class DiffCanvasCards(
    open: (String) -> Unit,
    private val project: Project? = null,
    private val removeDiff: (String) -> Unit = {},
    openDirectoryDiff: ((List<ReviewFile>) -> Unit)? = null,
    metricActions: MetricCardActions = MetricCardActions(),
    /** Opens a wall block's native diff, at the double-clicked line when there is one. */
    openWallDiff: (ReviewFile, WallLine?) -> Unit = { _, _ -> },
    /** Shows every file of the change on the wall. */
    showAllChanges: () -> Unit = {},
) : CanvasExtension {
    var content = DiffCanvasContent()
    private var refresh: (CanvasTabKey) -> Unit = {}
    private val diffEditors = mutableMapOf<ReviewNoteKey, DiffFileEditor>()
    // One view for the tab session, so block positions survive canvas rebuilds.
    private val wall by lazy { DiffsWallView(openWallDiff, showAllChanges) }
    private val metricsCards = MetricsCanvasCards(MetricsCanvasActions(
        open = open, openDirectoryDiff = openDirectoryDiff, removeWords = metricActions.removeWords,
        removeDiffOverview = metricActions.removeDiffOverview, removeSemantic = metricActions.removeSemantic,
        removeMoves = metricActions.removeMoves, refresh = { refresh(it) },
    ), project)

    override fun attach(refresh: (CanvasTabKey) -> Unit) { this.refresh = refresh }

    override fun update() {
        metricsCards.update(content.wordFiles, content.semantic, content.moves)
        val diffsByKey = content.diffs.associateBy { it.diffKey }
        diffEditors.entries.removeIf { (key, editor) ->
            (diffsByKey[key] != editor.file).also { if (it) Disposer.dispose(editor) }
        }
    }

    override fun leadingCards(): Map<CanvasTabKey, JPanel> = listOfNotNull(
        content.wall?.let { CanvasTabKey.DiffsWall to wallCard(it) },
        content.words?.let { CanvasTabKey.Words to metricsCards.wordsCard(it) },
        content.diffOverview?.let { CanvasTabKey.DiffOverview to metricsCards.wordsCard(it, overview = true) },
        content.semantic?.let { CanvasTabKey.Semantic to metricsCards.semanticCard(it) },
        content.moves?.let { CanvasTabKey.Moves to metricsCards.movesCard(it) },
    ).toMap()

    override fun trailingCards(snapshots: SnapshotCanvasCards): Map<CanvasTabKey, JPanel> =
        content.diffs.associate { it.diffKey to diffCard(it, snapshots) }

    override fun card(key: CanvasTabKey, snapshots: SnapshotCanvasCards): JPanel? = when (key) {
        CanvasTabKey.Words -> content.words?.let { metricsCards.wordsCard(it) }
        CanvasTabKey.DiffOverview -> content.diffOverview?.let { metricsCards.wordsCard(it, overview = true) }
        CanvasTabKey.Semantic -> content.semantic?.let(metricsCards::semanticCard)
        CanvasTabKey.Moves -> content.moves?.let(metricsCards::movesCard)
        CanvasTabKey.DiffsWall -> content.wall?.let(::wallCard)
        is ReviewNoteKey -> content.diffs.firstOrNull { it.diffKey == key }?.let { diffCard(it, snapshots) }
        else -> null
    }

    override fun title(key: CanvasTabKey): String? = when (key) {
        CanvasTabKey.Words -> "Word changes"
        CanvasTabKey.DiffOverview -> "Diff overview"
        CanvasTabKey.Semantic -> "Semantic changes"
        CanvasTabKey.Moves -> "Moved code"
        CanvasTabKey.DiffsWall -> "Diffs wall"
        is ReviewNoteKey -> content.diffs.firstOrNull { it.diffKey == key }?.let { "Diff · ${it.path.substringAfterLast('/')}" }
        else -> null
    }

    override fun summaryDiffView(card: SummariesCard, state: ChangeSummariesViewState, zoom: Double,
                                 actions: ChangeSummaryActions): SummaryDiffView =
        ChangeSummaryDiffPanel(card, state, zoom, actions, project)

    internal fun chooseWordScope(scope: WordScope) = metricsCards.chooseWordScope(scope)

    private fun wallCard(content: DiffsWallContent): JPanel = wall.card.also { wall.show(content) }

    /** Gives the wall the latest file summaries without rebuilding the canvas; blocks keep their place and size. */
    fun updateWallSummaries(summaries: Map<String, String>) {
        val current = content.wall ?: return
        if (current.summaries == summaries) return
        content = content.copy(wall = current.copy(summaries = summaries))
        wall.show(content.wall!!)
    }

    private fun diffCard(file: ReviewFile, snapshots: SnapshotCanvasCards): JPanel =
        snapshots.diffCard(file.path, file.status, { removeDiff(file.path) }) { width, zoom ->
            diffEditors.getOrPut(file.diffKey) { DiffFileEditor(file, project) }.also { it.resizeFor(width, zoom) }
        }

    override fun dispose() {
        // The tab clears its canvas after disposing it; no viewer may be created again.
        content = DiffCanvasContent()
        diffEditors.values.forEach { Disposer.dispose(it) }
        diffEditors.clear()
        metricsCards.dispose()
    }
}
