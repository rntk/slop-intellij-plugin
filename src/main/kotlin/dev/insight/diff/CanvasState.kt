package dev.insight.diff

/** Complete canvas content. Partial updates copy this snapshot so unrelated tabs and their inputs survive. */
internal data class CanvasState(
    val notes: List<DiffNote> = emptyList(),
    val sources: List<DiffSourceBlock> = emptyList(),
    val diffs: List<ReviewFile> = emptyList(),
    val flows: ChangeFlows? = null,
    val summaries: SummariesCard? = null,
    val topics: TopicsCard? = null,
    val fileView: FileViewCard? = null,
    val words: WordChanges? = null,
    val wordFiles: List<ReviewFile> = emptyList(),
    val diffOverview: WordChanges? = null,
    val semantic: SemanticResult? = null,
    val explanation: ExplainResult? = null,
)
