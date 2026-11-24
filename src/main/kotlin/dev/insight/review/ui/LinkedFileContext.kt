package dev.insight.review.ui

import dev.insight.agent.SourceSide
import dev.insight.context.CodeContext
import dev.insight.context.ContextData
import dev.insight.context.ContextKeys
import dev.insight.context.ContextOrigin
import dev.insight.context.ContextPick
import dev.insight.context.ContextScopeKind
import dev.insight.context.LinkedFile
import dev.insight.context.ReviewDestination
import dev.insight.diff.ReviewFile
import dev.insight.review.PatchSource
import dev.insight.review.ReviewSource
import dev.insight.review.TreeConnectionSite
import dev.insight.review.formatLinkLocation

/** A Tree link opens both endpoints in source-to-target order. */
internal fun treeConnectionPick(
    from: ReviewSource,
    to: ReviewSource,
    site: TreeConnectionSite,
    destinations: Map<ReviewDestination, () -> Unit>,
    openOnWall: ((List<ReviewSource>, String) -> Unit)?,
): ContextPick {
    val sources = listOf(from, to)
    val files = listOf(
        LinkedFile(from.path, from.absolutePath, site.fromLine?.let { it..it }),
        LinkedFile(to.path, to.absolutePath, site.toLine?.let { it..it }),
    )
    val label = files.joinToString(" → ") { formatLinkLocation(it.path, it.lines) }
    val scope = CodeContext(ContextScopeKind.CHANGE, label, null, null, null, SourceSide.CURRENT,
        label, files = files.map { it.path })
    val data = ContextData().put(ContextKeys.LINKED_FILES, files)
    if (destinations.isNotEmpty()) data.put(ContextKeys.REVIEW_DESTINATIONS, destinations)
    if (openOnWall != null) putWallOpeners(data, files, sources, openOnWall)
    return ContextPick(ContextOrigin.OTHER, listOf(scope), data)
}

/** Wall openers for the linked files the review covers: changed files on the Diffs wall, source files on the Code wall. */
internal fun putWallOpeners(
    data: ContextData,
    files: List<LinkedFile>,
    reviewFiles: List<ReviewSource>,
    open: (List<ReviewSource>, String) -> Unit,
) {
    val sources = files.mapNotNull { file -> matchingReviewSource(reviewFiles, file.path, file.absolutePath)?.let { file.path to it } }
    fun opener(accept: (ReviewSource) -> Boolean): ((CodeContext) -> Unit)? {
        val accepted = sources.filter { accept(it.second) }.takeIf { it.isNotEmpty() } ?: return null
        return { context ->
            val paths = context.files.toSet()
            val selected = accepted.filter { it.first in paths }.map { it.second }.distinct()
            if (selected.isNotEmpty()) open(selected, context.label)
        }
    }
    opener { it is ReviewFile }?.let { data.put(ContextKeys.OPEN_ON_DIFFS_WALL, it) }
    opener { it !is ReviewFile && it !is PatchSource }?.let { data.put(ContextKeys.OPEN_ON_CODE_WALL, it) }
}
