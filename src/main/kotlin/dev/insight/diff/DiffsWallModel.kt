package dev.insight.diff

import java.awt.Point
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/** One painted row of a Diffs wall block, as `git diff` prints it: [text] keeps its `+`, `-` or space marker. */
internal data class WallLine(val kind: Kind, val text: String, val old: Int? = null, val new: Int? = null) {
    enum class Kind { HUNK, CONTEXT, ADDED, REMOVED, NOTE }
}

/** One changed file of the Diffs wall: its [title] (`old → new` for renames), line counts and the rows to paint. */
internal data class WallBlock(
    val file: ReviewFile,
    val title: String,
    val added: Int,
    val removed: Int,
    val lines: List<WallLine>,
    /** Rows cut by the line cap; the native diff shows them. */
    val hiddenLines: Int,
)

/** A block's size or a placed block, in pixels. */
internal data class WallRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Pixel measures of the wall's monospace text, so sizing needs no font. */
internal data class WallMetrics(val charWidth: Int, val lineHeight: Int, val headerHeight: Int, val padding: Int, val gap: Int)

private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/** The rows of unified [patch]: hunk headers, changed and context lines numbered on their sides, and notes such as missing newlines. */
internal fun wallLines(patch: String): List<WallLine> {
    val raw = patch.replace("\r\n", "\n").split('\n').let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
    var old = 0
    var new = 0
    var inHunk = false
    return raw.map { line ->
        val text = line.replace("\t", "    ")
        val hunk = HUNK.find(line)
        when {
            hunk != null -> {
                old = hunk.groupValues[1].toInt(); new = hunk.groupValues[2].toInt(); inHunk = true
                WallLine(WallLine.Kind.HUNK, text)
            }
            // Text before the first hunk and the loader's bracketed notes are not file lines.
            !inHunk || line.startsWith("[") || line.startsWith("\\") -> WallLine(WallLine.Kind.NOTE, text)
            line.startsWith("+") -> WallLine(WallLine.Kind.ADDED, text, new = new++)
            line.startsWith("-") -> WallLine(WallLine.Kind.REMOVED, text, old = old++)
            else -> WallLine(WallLine.Kind.CONTEXT, if (line.isEmpty()) " " else text, old++, new++)
        }
    }
}

/** [file] as a wall block of at most [maxLines] rows; longer diffs stay readable on a crowded wall and open in full natively. */
internal fun wallBlock(file: ReviewFile, maxLines: Int = WALL_MAX_LINES): WallBlock {
    val all = wallLines(file.patch)
    val title = file.previousPath?.takeIf { it != file.path }?.let { "$it → ${file.path}" } ?: file.path
    return WallBlock(file, title, all.count { it.kind == WallLine.Kind.ADDED }, all.count { it.kind == WallLine.Kind.REMOVED },
        all.take(maxLines), (all.size - maxLines).coerceAtLeast(0))
}

/**
 * The width every block shares, fitting the longest row of [blocks] between [minChars] and [maxChars] characters. One width keeps
 * the masonry columns aligned; longer rows are clipped.
 */
internal fun wallBlockWidth(blocks: List<WallBlock>, metrics: WallMetrics, minChars: Int = 60, maxChars: Int = 96): Int {
    val longest = blocks.maxOfOrNull { block -> maxOf(block.title.length + 12, block.lines.maxOfOrNull { it.text.length } ?: 0) } ?: 0
    return longest.coerceIn(minChars, maxChars) * metrics.charWidth + 2 * metrics.padding
}

/** The height of [block]: its header, its rows and a row noting the cut lines or an empty diff. */
internal fun wallBlockHeight(block: WallBlock, metrics: WallMetrics): Int {
    val rows = block.lines.size + if (block.hiddenLines > 0 || block.lines.isEmpty()) 1 else 0
    return metrics.headerHeight + rows * metrics.lineHeight + 2 * metrics.padding
}

/**
 * Packs blocks of [width] and [heights] into [columns] masonry columns, each in turn into the shortest column, so the scope's
 * order still reads left to right and top to bottom.
 */
internal fun wallLayout(heights: List<Int>, width: Int, columns: Int, gap: Int): List<WallRect> {
    val count = columns.coerceAtMost(heights.size).coerceAtLeast(1)
    val bottoms = IntArray(count) { gap }
    return heights.map { height ->
        val column = bottoms.indices.minBy { bottoms[it] }
        WallRect(gap + column * (width + gap), bottoms[column], width, height).also { bottoms[column] += height + gap }
    }
}

/** The size the placed [rects] cover from the origin, with a [gap] margin right and below. */
internal fun wallExtent(rects: List<WallRect>, gap: Int): WallRect =
    WallRect(0, 0, (rects.maxOfOrNull { it.x + it.width } ?: 0) + gap, (rects.maxOfOrNull { it.y + it.height } ?: 0) + gap)

/**
 * How many columns spread the wall over a viewport of [viewportWidth] × [viewportHeight]: at least as many as fit its width, and
 * when the wall would still be taller than the viewport, up to [maxColumns] to bring the wall's shape closest to the viewport's,
 * so zooming out shows all of it.
 */
internal fun wallColumns(heights: List<Int>, width: Int, gap: Int, viewportWidth: Int, viewportHeight: Int, maxColumns: Int = 12): Int {
    if (heights.isEmpty()) return 1
    val limit = minOf(heights.size, maxColumns).coerceAtLeast(1)
    val fit = ((viewportWidth - gap) / (width + gap)).coerceIn(1, limit)
    if (viewportWidth <= 0 || viewportHeight <= 0) return fit
    fun extent(columns: Int) = wallExtent(wallLayout(heights, width, columns, gap), gap)
    if (extent(fit).height <= viewportHeight) return fit
    val target = viewportWidth.toDouble() / viewportHeight
    return (fit..limit).minBy { columns -> extent(columns).let { abs(ln(it.width.toDouble() / it.height / target)) } }
}

internal const val WALL_MIN_ZOOM = 0.1
internal const val WALL_MAX_ZOOM = 3.0
/** The least zoom the wall picks by itself, where text stays readable. */
internal const val WALL_MIN_AUTO_ZOOM = 0.6

internal fun wallClampZoom(zoom: Double): Double = zoom.coerceIn(WALL_MIN_ZOOM, WALL_MAX_ZOOM)

/** [zoom] scaled by [factor] and clamped, stopping at 100% when the step passes it. */
internal fun wallZoomBy(zoom: Double, factor: Double): Double {
    val next = wallClampZoom(zoom * factor)
    return if (zoom != 1.0 && minOf(zoom, next) < 1.0 && maxOf(zoom, next) > 1.0) 1.0 else next
}

/** The zoom that shows a wall of [extent] (at 100%) whole in a viewport of [viewportWidth] × [viewportHeight]. */
internal fun wallFitZoom(extent: WallRect, viewportWidth: Int, viewportHeight: Int): Double {
    if (extent.width <= 0 || extent.height <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return 1.0
    return wallClampZoom(minOf(viewportWidth.toDouble() / extent.width, viewportHeight.toDouble() / extent.height))
}

/** The zoom a freshly laid out wall starts at: 100% unless less shows it whole, but never below readable. */
internal fun wallInitialZoom(extent: WallRect, viewportWidth: Int, viewportHeight: Int): Double =
    wallFitZoom(extent, viewportWidth, viewportHeight).coerceIn(WALL_MIN_AUTO_ZOOM, 1.0)

/**
 * The view position along one axis that keeps the content under [pointer] (relative to the viewport) in place when the zoom
 * goes [from] → [to]. The zoomed surface starts at [offset] in the view, below or beside unzoomed content such as the label row.
 */
internal fun wallZoomAround(viewPosition: Double, pointer: Double, offset: Double, from: Double, to: Double): Double =
    offset + (viewPosition + pointer - offset) * (to / from) - pointer

/**
 * Indices of [rects] in reading order: rows top to bottom, a row being the blocks whose tops lie within [tolerance] of its first
 * top, each row left to right. An untouched masonry reads about in its file order.
 */
internal fun wallReadingOrder(rects: List<WallRect>, tolerance: Int): List<Int> {
    val byTop = rects.indices.sortedWith(compareBy({ rects[it].y }, { rects[it].x }))
    val rows = mutableListOf<MutableList<Int>>()
    byTop.forEach { index ->
        val row = rows.lastOrNull()
        if (row != null && rects[index].y - rects[row.first()].y <= tolerance) row += index else rows += mutableListOf(index)
    }
    return rows.flatMap { row -> row.sortedBy { rects[it].x } }
}

/** The block after [current] in [order], or before it when not [forward], wrapping around; the first or last without a current. */
internal fun wallNextBlock(order: List<Int>, current: Int?, forward: Boolean): Int? {
    if (order.isEmpty()) return null
    val at = current?.let(order::indexOf)?.takeIf { it >= 0 } ?: return if (forward) order.first() else order.last()
    return order[(at + if (forward) 1 else -1).mod(order.size)]
}

/**
 * The zoom that makes a block [blockWidth] wide (at 100%) readable in a viewport [viewportWidth] wide: its width fitting with
 * [margin] on each side within [[min], [max]], and never less than [current] when that is already readable. A compact block,
 * given with its capped [blockHeight], is fitted whole in [viewportHeight] too.
 */
internal fun wallFocusZoom(
    blockWidth: Int, viewportWidth: Int, current: Double, margin: Int, min: Double = 1.0, max: Double = 1.5,
    blockHeight: Int? = null, viewportHeight: Int = 0,
): Double {
    if (blockWidth <= 0) return maxOf(current, min)
    var fit = (viewportWidth - 2.0 * margin) / blockWidth
    if (blockHeight != null && blockHeight > 0 && viewportHeight > 0) fit = minOf(fit, (viewportHeight - 2.0 * margin) / blockHeight)
    val target = fit.coerceIn(min, max)
    return if (current < min || blockHeight != null) target else maxOf(current, target)
}

/** The share of the viewport's height a compact block takes at most. */
internal const val WALL_COMPACT_SHARE = 0.6
/** Blocks taller than this many viewport heights open compact. */
internal const val WALL_TALL_SHARE = 1.5

/**
 * The height (at 100%) a compact block is capped at: [share] of a viewport [viewportHeight] pixels high at [zoom], rounded down to
 * whole rows below the header, and at least a few rows.
 */
internal fun wallCompactHeight(viewportHeight: Int, zoom: Double, metrics: WallMetrics, share: Double = WALL_COMPACT_SHARE): Int {
    val chrome = metrics.headerHeight + 2 * metrics.padding
    val rows = ((viewportHeight * share / zoom - chrome) / metrics.lineHeight).toInt().coerceAtLeast(3)
    return chrome + rows * metrics.lineHeight
}

/** Whether a block [height] tall (at 100%) opens compact: it would be taller than [share] viewports [viewportHeight] high at [zoom]. */
internal fun wallDefaultCompact(height: Int, viewportHeight: Int, zoom: Double, share: Double = WALL_TALL_SHARE): Boolean =
    viewportHeight > 0 && height * zoom > share * viewportHeight

/** A compact block's body scroll after moving [offset] by [delta], within the rows its [height] hides under its [cap]. */
internal fun wallBodyScroll(offset: Int, delta: Int, height: Int, cap: Int): Int =
    (offset + delta).coerceIn(0, (height - cap).coerceAtLeast(0))

/**
 * [text] broken into rows of at most [columns] characters: at spaces where it can, mid-word for words longer than a row, with
 * line breaks kept. The wall's text is monospace, so characters measure the width.
 */
internal fun wallWrap(text: String, columns: Int): List<String> {
    val width = columns.coerceAtLeast(1)
    val rows = mutableListOf<String>()
    for (paragraph in text.replace("\r\n", "\n").trim().split('\n')) {
        var row = ""
        for (word in paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var rest = word
            while (rest.length > width) {
                if (row.isNotEmpty()) { rows += row; row = "" }
                rows += rest.take(width)
                rest = rest.drop(width)
            }
            row = when {
                row.isEmpty() -> rest
                row.length + 1 + rest.length <= width -> "$row $rest"
                else -> { rows += row; rest }
            }
        }
        rows += row
    }
    return rows
}

/** How many rows the body of a block [height] tall (at 100%) shows: what is left under its header and padding. */
internal fun wallBodyRows(height: Int, metrics: WallMetrics): Int =
    ((height - metrics.headerHeight - 2 * metrics.padding) / metrics.lineHeight).coerceAtLeast(1)

/** The first row shown of [total] summary rows after moving [first] by [delta] rows, with [visible] rows in view. */
internal fun wallSummaryScroll(first: Int, delta: Int, total: Int, visible: Int): Int =
    (first + delta).coerceIn(0, (total - visible).coerceAtLeast(0))

/** The index of the row at [y] (at 100%, from the block's top) in a body scrolled by [offset], or null on the header. */
internal fun wallRowAt(y: Double, offset: Int, metrics: WallMetrics): Int? {
    val top = metrics.headerHeight + metrics.padding
    if (y < metrics.headerHeight) return null
    return floor((y - top + offset) / metrics.lineHeight).toInt().takeIf { it >= 0 }
}

/**
 * The view position that shows [rect] (at 100%) at [zoom]: centred horizontally, and vertically too when it fits the viewport,
 * else with its top [margin] below the viewport's top. The zoomed surface starts at ([offsetX], [offsetY]) in the view.
 */
internal fun wallFocusPosition(rect: WallRect, zoom: Double, offsetX: Int, offsetY: Int, viewportWidth: Int, viewportHeight: Int, margin: Int): Point {
    val width = rect.width * zoom
    val height = rect.height * zoom
    val x = offsetX + rect.x * zoom + width / 2 - viewportWidth / 2.0
    val y = if (height + 2 * margin <= viewportHeight) offsetY + rect.y * zoom + height / 2 - viewportHeight / 2.0
    else offsetY + rect.y * zoom - margin
    return Point(x.roundToInt().coerceAtLeast(0), y.roundToInt().coerceAtLeast(0))
}

internal const val WALL_MAX_LINES = 400
