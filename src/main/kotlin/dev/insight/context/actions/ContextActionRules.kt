package dev.insight.context.actions

import dev.insight.agent.SourceSide
import dev.insight.context.CodeContext
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.review.ui.EvidenceLine
import dev.insight.review.ui.LineSide
import dev.insight.review.ui.LineTrace

/**
 * What the built-in actions decide availability on, read from a request without any lookup: [hasFile] means the host named a project
 * file (an editor file, or an absolute path) that [dev.insight.agent.findSourceFile] is tried on when an action runs.
 */
internal data class ContextFacts(
    val context: CodeContext,
    val origin: ContextOrigin,
    val hasFile: Boolean,
    /** The scope comes from a captured review diff. */
    val hasReviewFile: Boolean,
    /** The host can open the context's file on a canvas session itself. */
    val hasCanvasCallback: Boolean,
)

internal fun ContextRequest.facts() = ContextFacts(
    context, origin,
    hasFile = ContextKeys.VIRTUAL_FILE in data || (context.path != null && context.absolutePath != null),
    hasReviewFile = ContextKeys.REVIEW_FILE in data,
    hasCanvasCallback = ContextKeys.OPEN_ON_CANVAS in data,
)

/** Symbol kinds the application graph and flow chart can show. */
private val CHART_SYMBOL_KINDS = setOf("function", "class", "property")

private val ContextFacts.kind get() = context.kind

/** Code whose lines can be found in the file on disk: not the removed side of a diff, and not a comparison of several files. */
private val ContextFacts.onDisk get() = hasFile && kind != ContextScopeKind.CHANGE && kind != ContextScopeKind.DIRECTORY && context.side != SourceSide.BEFORE

internal fun canOpenInEditor(f: ContextFacts) = f.onDisk

internal fun canOpenInIdeDiff(f: ContextFacts) = f.hasReviewFile && f.origin != ContextOrigin.IDE_DIFF &&
    f.kind != ContextScopeKind.CHANGE && f.kind != ContextScopeKind.DIRECTORY

/** Directory scopes open live project content. Captured file diffs use their host's canvas. */
internal fun canOpenOnCanvas(f: ContextFacts) = when {
    f.kind == ContextScopeKind.DIRECTORY -> f.hasFile
    f.hasReviewFile -> f.hasCanvasCallback && f.kind != ContextScopeKind.CHANGE && f.context.path != null
    else -> f.onDisk && f.kind in setOf(ContextScopeKind.FILE, ContextScopeKind.SYMBOL, ContextScopeKind.LINE, ContextScopeKind.SELECTION)
}

internal fun canShowCallGraph(f: ContextFacts) = f.onDisk && f.kind == ContextScopeKind.SYMBOL && f.context.lines != null &&
    f.context.symbol?.kind == "function"

internal fun canOpenCodeWall(f: ContextFacts) = f.onDisk && !f.hasReviewFile && f.origin != ContextOrigin.CODE_WALL &&
    f.kind in setOf(ContextScopeKind.FILE, ContextScopeKind.SYMBOL, ContextScopeKind.LINE, ContextScopeKind.SELECTION)

internal fun canAddToFlowChart(f: ContextFacts) = f.onDisk && f.kind == ContextScopeKind.SYMBOL && f.context.lines != null &&
    f.context.symbol?.kind in CHART_SYMBOL_KINDS

internal fun canOpenSideBySide(f: ContextFacts) = f.onDisk && f.context.lines != null

internal fun canShowInSummaryTree(f: ContextFacts) = f.onDisk && f.kind == ContextScopeKind.FILE

/** The most lines of a range the IDE diff is asked to highlight. */
private const val MAX_HIGHLIGHTED_LINES = 5_000

/** The lines of [context] on its diff side for the IDE diff to scroll to and highlight; null for a context of the whole file. */
internal fun diffLineTrace(path: String, context: CodeContext): LineTrace? {
    val lines = context.lines ?: return null
    val side = if (context.side == SourceSide.BEFORE) LineSide.BEFORE else LineSide.AFTER
    val evidence = lines.take(MAX_HIGHLIGHTED_LINES).map { EvidenceLine(side, it, "", ' ', diff = true) }
    return LineTrace(path, path, "", emptyList(), evidence)
}

/** The chat input after adding [addition] below what is already typed. */
internal fun appendToChatInput(existing: String, addition: String): String =
    if (existing.isBlank()) addition else existing.trimEnd() + "\n\n" + addition
