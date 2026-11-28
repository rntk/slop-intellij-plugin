package dev.insight.diff

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import dev.insight.agent.SourceSide
import dev.insight.agent.findSourceFile
import dev.insight.canvas.OpenCanvasSessionAction
import dev.insight.context.ContextData
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextSummaryActions
import dev.insight.context.reviewDiffPick
import dev.insight.context.showContextPicker
import dev.insight.diff.walk.WalkCanvasView
import dev.insight.diff.walk.WalkHunk
import dev.insight.diff.walk.ui.WalkthroughActions
import dev.insight.diff.walk.ui.WalkthroughContent
import dev.insight.diff.walk.ui.WalkthroughPage
import dev.insight.review.FileSummaries
import dev.insight.review.CanvasTabKey
import dev.insight.review.ReviewNoteKey
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ReviewedFiles
import dev.insight.review.WordScope
import dev.insight.review.ui.CanvasExtension
import dev.insight.review.ui.ChangeSummariesViewState
import dev.insight.review.ui.ChangeSummaryActions
import dev.insight.review.ui.SnapshotCanvasCards
import dev.insight.review.ui.SummariesCard
import dev.insight.review.ui.SummaryDiffView
import dev.insight.review.wall.WallChats
import dev.insight.review.wall.WallLine
import dev.insight.review.wall.WallReviewSupport
import kotlinx.coroutines.CoroutineScope
import javax.swing.JPanel

/** Metric card removal supplied by the review hosting the canvas. */
internal data class MetricCardActions(
    val removeWords: () -> Unit = {},
    val removeSemantic: () -> Unit = {},
    val removeMoves: () -> Unit = {},
)

/** Content of the diff review's own canvas pages, alongside the shared canvas state. */
internal data class DiffCanvasContent(
    val diffs: List<ReviewFile> = emptyList(),
    val words: WordChanges? = null,
    val wordFiles: List<ReviewFile> = emptyList(),
    val semantic: SemanticResult? = null,
    val moves: CodeMoves? = null,
    val wall: DiffsWallContent? = null,
    val walkthrough: WalkthroughContent? = null,
)

/** Optional context-aware wall link dependencies passed to [DiffCanvasCards]. */
internal data class DiffWallLinks(
    val scope: CoroutineScope? = null,
    val allWallFiles: (() -> List<ReviewFile>)? = null,
    val summaryTree: (() -> ReviewSummaryTree?)? = null,
)

/**
 * The diff review's canvas pages: the Diffs wall, Words, semantic metrics and moved code, diff snapshots in
 * native viewers, and the summaries Diff mode. Set [content] before the canvas rebuilds.
 */
internal class DiffCanvasCards(
    open: (String) -> Unit,
    private val project: Project? = null,
    private val removeDiff: (String) -> Unit = {},
    openDirectoryDiff: ((List<ReviewFile>) -> Unit)? = null,
    openMovesOnWall: (List<ReviewFile>) -> Unit = {},
    metricActions: MetricCardActions = MetricCardActions(),
    /** Opens a wall block's native diff, at the double-clicked line when there is one. */
    openWallDiff: (ReviewFile, WallLine?) -> Unit = { _, _ -> },
    /** Shows every file of the change on the wall. */
    showAllChanges: () -> Unit = {},
    /** The chats wall blocks unfold; null leaves blocks without a Chat tab. */
    wallChats: WallChats? = null,
    /** Opens a wall block's file on a canvas session. */
    openWallOnCanvas: ((path: String, line: Int?) -> Unit)? = null,
    openOnWall: ((List<ReviewFile>, String) -> Unit)? = null,
    reviewDestinations: (() -> Map<dev.insight.context.ReviewDestination, () -> Unit>)? = null,
    private val diffSession: (() -> ReviewDiffSession)? = null,
    contextSummaryActions: ContextSummaryActions? = null,
) : CanvasExtension {
    private var wallLinks: DiffWallLinks? = null

    internal constructor(
        open: (String) -> Unit,
        project: Project?,
        removeDiff: (String) -> Unit,
        openDirectoryDiff: ((List<ReviewFile>) -> Unit)?,
        openMovesOnWall: (List<ReviewFile>) -> Unit,
        metricActions: MetricCardActions,
        openWallDiff: (ReviewFile, WallLine?) -> Unit,
        showAllChanges: () -> Unit,
        wallChats: WallChats?,
        openWallOnCanvas: ((path: String, line: Int?) -> Unit)?,
        openOnWall: ((List<ReviewFile>, String) -> Unit)?,
        reviewDestinations: (() -> Map<dev.insight.context.ReviewDestination, () -> Unit>)?,
        diffSession: (() -> ReviewDiffSession)?,
        contextSummaryActions: ContextSummaryActions?,
        wallLinks: DiffWallLinks,
    ) : this(open, project, removeDiff, openDirectoryDiff, openMovesOnWall, metricActions, openWallDiff, showAllChanges,
        wallChats, openWallOnCanvas, openOnWall, reviewDestinations, diffSession,
        contextSummaryActions) {
        this.wallLinks = wallLinks
    }

    var content = DiffCanvasContent()
    private var refresh: (CanvasTabKey) -> Unit = {}
    private var reviewed: ReviewedFiles? = null
    private var toggleReviewed: ((List<String>, Boolean) -> Unit)? = null
    private val diffEditors = mutableMapOf<ReviewNoteKey, DiffFileEditor>()
    // One view for the tab session, so block positions survive canvas rebuilds.
    private val wallView = lazy {
        DiffsWallView(openWallDiff, showAllChanges, wallChats, project, openWallOnCanvas, openOnWall, reviewDestinations,
            contextSummaryActions, wallLinks?.scope,
            { wallLinks?.allWallFiles?.invoke() ?: content.wall?.files.orEmpty() }, wallLinks?.summaryTree,
            review = reviewed?.let { r ->
                WallReviewSupport(r::isReviewed, { path, value ->
                    toggleReviewed?.invoke(listOf(path), value) ?: r.set(listOf(path), value)
                })
            })
    }
    private val wall by wallView
    /** What the Walkthrough page asks of the review; set by the tab before the page is shown. */
    internal var walkthroughActions = WalkthroughActions()
    // One page for the tab session, so its canvas keeps cards, pan and zoom across canvas rebuilds and run updates.
    private val walkView = lazy {
        WalkthroughPage(project, wallLinks?.scope, WalkthroughActions(
            run = { walkthroughActions.run(it) }, stop = { walkthroughActions.stop() },
            openDiff = { file, line -> walkthroughActions.openDiff(file, line) },
            showContext = project?.let { ::showWalkthroughContext }, chats = walkthroughActions.chats))
    }
    internal val walkthroughPage by walkView

    /** What the Walkthrough canvas shows, for its own chat; null before the page is first shown. */
    internal fun walkthroughCanvasView(): WalkCanvasView? = if (walkView.isInitialized()) walkthroughPage.canvasView() else null
    private val metricsCards = MetricsCanvasCards(MetricsCanvasActions(
        open = open, openDirectoryDiff = openDirectoryDiff, openOnWall = openMovesOnWall, removeWords = metricActions.removeWords,
        removeSemantic = metricActions.removeSemantic,
        removeMoves = metricActions.removeMoves, refresh = { refresh(it) },
    ), project)

    override fun attach(refresh: (CanvasTabKey) -> Unit) {
        this.refresh = refresh
    }

    override fun attachReviewed(reviewed: ReviewedFiles, toggleReviewed: (List<String>, Boolean) -> Unit) {
        this.reviewed = reviewed
        this.toggleReviewed = toggleReviewed
    }

    override fun syncReviewed() {
        if (wallView.isInitialized()) wall.syncReviewed()
    }

    override fun update() {
        metricsCards.update(content.wordFiles, content.semantic, content.moves)
        val diffsByKey = content.diffs.associateBy { it.diffKey }
        diffEditors.entries.removeIf { (key, editor) ->
            (diffsByKey[key] != editor.file).also { if (it) Disposer.dispose(editor) }
        }
    }

    override fun leadingCards(): Map<CanvasTabKey, JPanel> = listOfNotNull(
        content.wall?.let { CanvasTabKey.Wall to wallCard(it) },
        content.walkthrough?.let { CanvasTabKey.Walkthrough to walkthroughCard(it) },
        content.words?.let { CanvasTabKey.Words to metricsCards.wordsCard(it) },
        content.semantic?.let { CanvasTabKey.Semantic to metricsCards.semanticCard(it) },
        content.moves?.let { CanvasTabKey.Moves to metricsCards.movesCard(it) },
    ).toMap()

    override fun trailingCards(snapshots: SnapshotCanvasCards): Map<CanvasTabKey, JPanel> =
        content.diffs.associate { it.diffKey to diffCard(it, snapshots) }

    override fun card(key: CanvasTabKey, snapshots: SnapshotCanvasCards): JPanel? = when (key) {
        CanvasTabKey.Words -> content.words?.let(metricsCards::wordsCard)
        CanvasTabKey.Semantic -> content.semantic?.let(metricsCards::semanticCard)
        CanvasTabKey.Moves -> content.moves?.let(metricsCards::movesCard)
        CanvasTabKey.Wall -> content.wall?.let(::wallCard)
        CanvasTabKey.Walkthrough -> content.walkthrough?.let(::walkthroughCard)
        is ReviewNoteKey -> content.diffs.firstOrNull { it.diffKey == key }?.let { diffCard(it, snapshots) }
        else -> null
    }

    override fun title(key: CanvasTabKey): String? = when (key) {
        CanvasTabKey.Words -> "Words"
        CanvasTabKey.Semantic -> "Semantic changes"
        CanvasTabKey.Moves -> "Moved code"
        CanvasTabKey.Wall -> ReviewVocabulary.DIFF.wallName
        CanvasTabKey.Walkthrough -> "Walkthrough"
        is ReviewNoteKey -> content.diffs.firstOrNull { it.diffKey == key }?.let { "Diff · ${it.path.substringAfterLast('/')}" }
        else -> null
    }

    override fun summaryDiffView(card: SummariesCard, state: ChangeSummariesViewState, zoom: Double,
                                 actions: ChangeSummaryActions): SummaryDiffView =
        ChangeSummaryDiffPanel(card, state, zoom, actions, project)

    fun selectWordOverview() = metricsCards.selectWordOverview()

    internal fun chooseWordScope(scope: WordScope) = metricsCards.chooseWordScope(scope)

    private fun wallCard(content: DiffsWallContent): JPanel = wall.card.also { wall.show(content) }

    private fun walkthroughCard(content: WalkthroughContent): JPanel = walkthroughPage.card.also { walkthroughPage.show(content) }

    private fun showWalkthroughContext(file: ReviewFile, hunk: WalkHunk, line: WallLine?, point: RelativePoint) {
        val project = project ?: return
        val data = ContextData()
        diffSession?.let { data.put(REVIEW_CONTEXT_SESSION, it) }
        val session = diffSession?.invoke()
        session?.let {
            data.put(ContextKeys.REVIEW_DESTINATIONS, it.destinations)
            it.summaryActionsFor(file)?.let { summary -> data.put(ContextKeys.SUMMARY_GENERATION, summary) }
            data.put(ContextKeys.OPEN_ON_DIFFS_WALL) { context -> it.openContext(file, context) }
        }
        val (side, number) = wallLineSide(line)
        findSourceFile(project, file.path, file.absolutePath)?.let { currentFile ->
            data.put(ContextKeys.OPEN_ON_CANVAS) { context ->
                val currentLine = context.lines?.first?.takeIf { context.side == SourceSide.AFTER }
                    ?: line?.new ?: hunk.newStart.takeIf { it > 0 }
                if (currentFile.isValid) OpenCanvasSessionAction().open(project, currentFile, currentLine)
            }
        }
        val files = session?.files ?: content.walkthrough?.files
        showContextPicker(project, reviewDiffPick(project, ContextOrigin.OTHER, file, number, side, files, data), point.component, point)
    }

    /** Shows a run's latest walkthrough on its page without rebuilding the canvas; false when the page is not on it. */
    fun updateWalkthrough(walkthrough: WalkthroughContent): Boolean {
        if (content.walkthrough == null) return false
        content = content.copy(walkthrough = walkthrough)
        walkthroughPage.show(walkthrough)
        return true
    }

    /** Gives the wall the latest file summaries without rebuilding the canvas; blocks keep their place and size. */
    fun updateWallSummaries(summaries: FileSummaries) {
        val current = content.wall ?: return
        if (current.summaries == summaries) return
        content = content.copy(wall = current.copy(summaries = summaries))
        wall.show(content.wall!!)
    }

    private fun diffCard(file: ReviewFile, snapshots: SnapshotCanvasCards): JPanel =
        snapshots.diffCard(file.path, file.status, { removeDiff(file.path) }) { width, zoom ->
            diffEditors.getOrPut(file.diffKey) { DiffFileEditor(file, project, session = diffSession) }.also { it.resizeFor(width, zoom) }
        }

    override fun dispose() {
        // The tab clears its canvas after disposing it; no viewer may be created again.
        content = DiffCanvasContent()
        diffEditors.values.forEach { Disposer.dispose(it) }
        diffEditors.clear()
        metricsCards.dispose()
        if (wallView.isInitialized()) wall.dispose()
        if (walkView.isInitialized()) Disposer.dispose(walkthroughPage)
    }
}
