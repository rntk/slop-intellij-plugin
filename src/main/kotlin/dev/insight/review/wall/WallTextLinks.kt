package dev.insight.review.wall

import com.intellij.openapi.project.Project
import dev.insight.context.ContextPick
import dev.insight.links.SymbolCandidate
import dev.insight.review.ReportLink
import dev.insight.review.ReportTargetKind
import dev.insight.review.formatLinkLocation
import dev.insight.review.linkTooltip
import kotlinx.coroutines.CoroutineScope
import java.awt.Point
import kotlin.math.abs

/** Optional integration wiring passed to [WallView] to enable linked Markdown summaries, chats, and added cards. */
internal data class WallLinkSupport(
    val project: Project? = null,
    val coroutineScope: CoroutineScope? = null,
    val linkHost: WallLinkHost? = null,
    val canSummarize: ((path: String) -> Boolean)? = null,
    val onRemoveBlock: ((path: String) -> Unit)? = null,
)

/** Maximum lines materialized in an added project source card when scrolling deep into a large file. */
internal const val MAX_ADDED_BLOCK_LINES = 20_000

/** Maximum file size in bytes read into an added wall card. */
internal const val MAX_ADDED_FILE_BYTES = 2_000_000L

/** An external/unchanged project file added to a wall from a summary or chat link. */
internal data class AddedWallSource(
    val path: String,
    val absolutePath: String?,
    val text: String,
    val targetLine: Int? = null,
)

/** Host callbacks enabling context-aware link navigation and on-demand card addition on a [WallView]. */
internal interface WallLinkHost {
    val isDiffWall: Boolean
    fun scopePaths(): List<String>
    fun previousPaths(): Map<String, String> = emptyMap()
    fun wallPathFor(path: String, absolutePath: String?): String
    fun ensureCard(path: String, absolutePath: String?, lines: IntRange?): String?
    fun ensureLineLoaded(path: String, targetLine: Int): Boolean
    fun isAddedCard(path: String): Boolean
    fun contextPick(link: ReportLink, path: String, absolutePath: String?): ContextPick?
}

/** A row index resolved in a [WallBlock], whether it matched an exact line number, and all matched row indices to highlight. */
internal data class WallRowTarget(
    val rowIndex: Int,
    val exact: Boolean,
    val highlightedRows: Set<Int> = emptySet(),
)

/** Where following a file or symbol link leads on a wall. */
internal sealed interface WallTextMove {
    /** The file is already on the wall at [path]; activate and scroll to it (and [row] when given). */
    data class Scroll(val path: String, val row: WallRowTarget?) : WallTextMove

    /** The file is not on the wall yet; add its card and scroll to it (at [lines] when given). */
    data class AddAndScroll(val path: String, val lines: IntRange?) : WallTextMove
}

/** Normalizes a project-relative wall path with forward slashes and no leading `./` or `/`. */
internal fun normalizeWallPath(path: String): String =
    path.trim().replace('\\', '/').replace(Regex("/+"), "/").removePrefix("./").trim('/')

/**
 * Finds the row index in [lines] corresponding to 1-based [targetLines]: preferring exact new-side line matches, then
 * exact old-side matches (for deleted/before lines), then the nearest numbered row when the target lies outside diff hunks.
 */
internal fun wallTargetRow(lines: List<WallLine>, targetLines: IntRange?): WallRowTarget? {
    if (targetLines == null || lines.isEmpty()) return null
    val newMatches = lines.indices.filter { lines[it].new?.let { n -> n in targetLines } == true }
    if (newMatches.isNotEmpty()) {
        return WallRowTarget(newMatches.first(), exact = true, highlightedRows = newMatches.toSet())
    }
    val oldMatches = lines.indices.filter { lines[it].old?.let { o -> o in targetLines } == true }
    if (oldMatches.isNotEmpty()) {
        return WallRowTarget(oldMatches.first(), exact = true, highlightedRows = oldMatches.toSet())
    }
    val anchor = targetLines.first
    var bestIdx = -1
    var bestDist = Int.MAX_VALUE
    for ((index, line) in lines.withIndex()) {
        val dNew = line.new?.let { abs(it - anchor) } ?: Int.MAX_VALUE
        val dOld = line.old?.let { abs(it - anchor) } ?: Int.MAX_VALUE
        val dist = minOf(dNew, dOld)
        if (dist < bestDist) {
            bestDist = dist
            bestIdx = index
        }
    }
    return if (bestIdx >= 0) WallRowTarget(bestIdx, exact = false, highlightedRows = emptySet()) else null
}

/**
 * Decides whether a link to [targetPath] at 1-based [targetLines] can scroll to an existing block on [shownBlocks]
 * (matching exact paths, normalized paths, or renamed [previousPaths]), or must add a card first.
 */
internal fun wallTextMove(
    targetPath: String,
    targetLines: IntRange?,
    shownBlocks: List<WallBlock>,
    previousPaths: Map<String, String> = emptyMap(),
): WallTextMove {
    val normalizedTarget = normalizeWallPath(targetPath)
    val renamedTarget = previousPaths[targetPath]
        ?: previousPaths.entries.firstOrNull { normalizeWallPath(it.key) == normalizedTarget }?.value
    val normalizedRenamed = renamedTarget?.let(::normalizeWallPath)
    val block = shownBlocks.firstOrNull { it.path == targetPath }
        ?: shownBlocks.firstOrNull { normalizeWallPath(it.path) == normalizedTarget }
        ?: renamedTarget?.let { renamed -> shownBlocks.firstOrNull { it.path == renamed } }
        ?: normalizedRenamed?.let { nr -> shownBlocks.firstOrNull { normalizeWallPath(it.path) == nr } }
        ?: return WallTextMove.AddAndScroll(normalizedTarget, targetLines)
    return WallTextMove.Scroll(block.path, wallTargetRow(block.lines, targetLines))
}

/** Context file paths for symbol ranking: the text's own [sourcePath], then cards on the wall, then the rest of the scope. */
internal fun wallContextFiles(sourcePath: String?, wallPaths: List<String>, scopePaths: List<String> = emptyList()): List<String> =
    (listOfNotNull(sourcePath) + wallPaths + scopePaths).distinct()

/**
 * Resolves an ambiguous filename link without asking when the wall context points unambiguously to one file:
 * the text's own [sourcePath], else the single candidate already on the wall, else the single candidate in scope.
 */
internal fun wallPreferredFile(
    candidates: List<String>,
    sourcePath: String?,
    wallPaths: Set<String>,
    scopePaths: Set<String> = emptySet(),
): String? {
    if (sourcePath != null && sourcePath in candidates) return sourcePath
    val onWall = candidates.filter { it in wallPaths }
    if (onWall.size == 1) return onWall.single()
    if (onWall.size > 1) return null
    val inScope = candidates.filter { it in scopePaths }
    if (inScope.size == 1) return inScope.single()
    return null
}

/** Orders ambiguous file [candidates] by relevance to the wall: [sourcePath], then [wallPaths], then [scopePaths]. */
internal fun wallRankFiles(
    candidates: List<String>,
    sourcePath: String?,
    wallPaths: Set<String>,
    scopePaths: Set<String> = emptySet(),
): List<String> = candidates.sortedBy { path ->
    when {
        sourcePath != null && path == sourcePath -> 0
        path in wallPaths -> 1
        path in scopePaths -> 2
        else -> 3
    }
}

/**
 * Picks a unique declaration among [candidates] already shown on the wall without prompting, unless [explicitHintFiles]
 * already pointed to a file named right before the symbol (so `OtherFile.kt` + `foo()` never hijacks to the current card).
 */
internal fun wallPreferredSymbol(
    candidates: List<SymbolCandidate>,
    sourcePath: String?,
    shownBlocks: List<WallBlock>,
    explicitHintFiles: List<String> = emptyList(),
): SymbolCandidate? {
    if (candidates.isEmpty()) return null
    if (explicitHintFiles.isNotEmpty()) {
        val top = candidates.first()
        if (top.path in explicitHintFiles) {
            val inSameFile = candidates.filter { it.path == top.path }
            return if (inSameFile.size == 1) top else null
        }
        return null
    }
    val byPath = shownBlocks.associateBy { it.path }
    val tiers = listOf(
        candidates.filter { c ->
            sourcePath != null && c.path == sourcePath &&
                byPath[c.path]?.let { wallTargetRow(it.lines, c.line..c.line)?.exact == true } == true
        },
        candidates.filter { c -> sourcePath != null && c.path == sourcePath },
        candidates.filter { c ->
            byPath[c.path]?.let { wallTargetRow(it.lines, c.line..c.line)?.exact == true } == true
        },
        candidates.filter { c -> c.path in byPath },
    )
    for (tier in tiers) {
        if (tier.size == 1) return tier.single()
        if (tier.size > 1) return null
    }
    return null
}

/**
 * Picks a non-overlapping position (at 100% zoom) for a newly added card of [cardWidth] x [cardHeight]:
 * preferring below or right of [sourceRect] while avoiding [existingRects] and [avoidedRects] (such as open chat panels),
 * falling back to the bottom of the shortest masonry column.
 */
internal fun wallNewCardPosition(
    sourceRect: WallRect?,
    existingRects: List<WallRect>,
    cardWidth: Int,
    cardHeight: Int,
    gap: Int,
    avoidedRects: List<WallRect> = emptyList(),
): Point {
    if (existingRects.isEmpty() && avoidedRects.isEmpty()) return Point(gap, gap)
    val obstacles = existingRects + avoidedRects
    val pad = gap / 2
    fun intersects(a: WallRect, b: WallRect): Boolean =
        a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y
    fun free(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        val padded = WallRect(x - pad, y - pad, cardWidth + 2 * pad, cardHeight + 2 * pad)
        return obstacles.none { intersects(padded, it) }
    }
    if (sourceRect != null) {
        val candidates = buildList {
            add(Point(sourceRect.x, sourceRect.y + sourceRect.height + gap))
            add(Point(sourceRect.x + sourceRect.width + gap, sourceRect.y))
            avoidedRects.filter { r ->
                r.x >= sourceRect.x + sourceRect.width &&
                    r.y < sourceRect.y + sourceRect.height &&
                    r.y + r.height > sourceRect.y
            }.sortedBy { it.x + it.width }.forEach { avoided ->
                add(Point(avoided.x + avoided.width + gap, sourceRect.y))
            }
            val colBottom = existingRects
                .filter { it.x < sourceRect.x + cardWidth && it.x + it.width > sourceRect.x }
                .maxOfOrNull { it.y + it.height }
            if (colBottom != null) add(Point(sourceRect.x, colBottom + gap))
        }
        candidates.firstOrNull { free(it.x, it.y) }?.let { return it }
    }
    val columns = existingRects.groupBy { it.x }
    val chosenX = if (columns.isNotEmpty()) {
        columns.entries.minWith(compareBy<Map.Entry<Int, List<WallRect>>> { entry ->
            entry.value.maxOf { it.y + it.height }
        }.thenBy { it.key }).key
    } else gap
    var y = (columns[chosenX]?.maxOfOrNull { it.y + it.height } ?: 0) + gap
    while (true) {
        val padded = WallRect(chosenX - pad, y - pad, cardWidth + 2 * pad, cardHeight + 2 * pad)
        val blocking = obstacles.firstOrNull { intersects(padded, it) } ?: break
        y = blocking.y + blocking.height + gap
    }
    return Point(chosenX, y)
}

/**
 * Builds a numbered source [WallBlock] for a file added to the wall on demand, expanding past [maxLines] up to
 * [MAX_ADDED_BLOCK_LINES] when [targetLine] sits deeper in the file, and keeping remaining lines up to [WALL_MAX_SKIM_LINES] in [WallBlock.more].
 */
internal fun wallAddedSourceBlock(
    path: String,
    text: String,
    targetLine: Int? = null,
    badge: String? = null,
    maxLines: Int = WALL_MAX_LINES,
): WallBlock = wallAddedSourceBlock(path, text, targetLine, badge, maxLines, WALL_MAX_SKIM_LINES)

/**
 * Builds a numbered source [WallBlock] for a file added to the wall on demand, expanding past [maxLines] up to
 * [MAX_ADDED_BLOCK_LINES] when [targetLine] sits deeper in the file, and keeping remaining lines up to [maxSkimLines] in [WallBlock.more].
 */
internal fun wallAddedSourceBlock(
    path: String,
    text: String,
    targetLine: Int?,
    badge: String?,
    maxLines: Int,
    maxSkimLines: Int,
): WallBlock {
    val totalLines = countWallSourceLines(text)
    val showCount = if (targetLine != null && targetLine > maxLines) {
        minOf(totalLines, maxOf(maxLines, targetLine + 50), MAX_ADDED_BLOCK_LINES)
    } else {
        minOf(totalLines, maxLines)
    }
    val parseLimit = minOf(MAX_ADDED_BLOCK_LINES, maxOf(showCount, maxSkimLines))
    val parsed = wallSourceLines(text, maxLines = parseLimit)
    val lines = parsed.take(showCount)
    val more = parsed.drop(showCount)
    val hiddenLines = (totalLines - lines.size).coerceAtLeast(0)
    val countLabel = if (totalLines == 0) "" else "$totalLines ${if (totalLines == 1) "line" else "lines"}"
    val stats = listOfNotNull(countLabel.takeIf { it.isNotEmpty() }, badge?.takeIf { it.isNotBlank() }).joinToString(" · ")
    val loadMore: ((Int) -> WallBlock)? = if (hiddenLines > more.size && parseLimit < MAX_ADDED_BLOCK_LINES) {
        { nextMaxSkim -> wallAddedSourceBlock(path, text, targetLine, badge, maxLines, maxOf(parseLimit, nextMaxSkim)) }
    } else null
    return WallBlock(path, path, stats, lines, hiddenLines, more, loadMore)
}

/** Materializes rows from [WallBlock.more] into [WallBlock.lines] when [targetLine] lies beyond the current line cap. */
internal fun wallExpandBlockForLine(block: WallBlock, targetLine: Int): WallBlock {
    if (block.more.isEmpty()) return block
    val index = block.more.indexOfFirst { it.new == targetLine }
        .takeIf { it >= 0 }
        ?: block.more.indexOfFirst { it.old == targetLine }
    if (index < 0) return block
    val take = minOf(block.more.size, index + 51)
    return block.copy(
        lines = block.lines + block.more.take(take),
        hiddenLines = (block.hiddenLines - take).coerceAtLeast(0),
        more = block.more.drop(take),
    )
}

/** Context-aware tooltip for a [ReportLink] inside a wall summary or chat. */
internal fun wallLinkTooltip(
    link: ReportLink,
    wallPaths: Set<String>,
    scopePaths: Set<String>,
    isDiffWall: Boolean,
    preferred: String? = null,
): String = when (link.kind) {
    ReportTargetKind.EXTERNAL_URL, ReportTargetKind.DIRECTORY ->
        linkTooltip(link, inReviewScope = link.primaryPath in scopePaths, isDiffReview = isDiffWall)
    ReportTargetKind.SYMBOL -> {
        val name = link.symbol?.let { (it.qualifier + it.name).joinToString(".") } ?: link.displayText
        "Symbol: $name · Click to scroll or add card · ${WALL_MENU_KEY}Click for editor · Right-click for Context…"
    }
    ReportTargetKind.FILE -> {
        val resolved = preferred ?: if (!link.isAmbiguous) link.primaryPath else null
        if (resolved == null) {
            "Matches ${link.candidates.size} files: ${link.candidates.take(4).joinToString(", ")}" +
                "${if (link.candidates.size > 4) ", …" else ""} · Click to choose · ${WALL_MENU_KEY}Click for editor · Right-click for Context…"
        } else {
            val location = formatLinkLocation(resolved, link.lines, link.column)
            val action = when (resolved) {
                in wallPaths -> "on wall · Click to scroll"
                in scopePaths -> "${if (isDiffWall) "in diff" else "in scope"} · Click to add card"
                else -> "Click to add card"
            }
            "$location · $action · ${WALL_MENU_KEY}Click for editor · Right-click for Context…"
        }
    }
}
