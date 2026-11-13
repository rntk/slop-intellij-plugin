package dev.insight.review.ui

import dev.insight.review.CanvasTabKey
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import java.util.Locale

/**
 * One view of a review tab, shown as a tile of the tab's [ReviewTileGrid]: the canvas page it opens, its [label], what the
 * page shows, and the [stats] on its tile. [stats] runs on the EDT and reads only values already in memory; see
 * [ReviewTab.scheduleTileRefresh].
 */
internal class ReviewViewOption(
    val key: CanvasTabKey,
    val label: String,
    val tooltip: String,
    val stats: () -> List<ReviewTileStat> = { emptyList() },
) {
    override fun toString() = label
}

/** The tile that opens the Start page, where the tab's source is chosen. */
internal const val SOURCE_TILE = "source"
/** The tile that shows the tab's chat in the side panel. */
internal const val CHAT_TILE = "chat"

/** The stable id of the tile of the view on [key]'s page: "view-summaries", "view-diff-overview", … */
internal fun viewTileId(key: CanvasTabKey): String = when (key) {
    CanvasTabKey.Summaries -> "view-summaries"
    CanvasTabKey.Topics -> "view-topics"
    CanvasTabKey.Grouping -> "view-grouping"
    CanvasTabKey.Wall -> "view-wall"
    CanvasTabKey.Words -> "view-words"
    CanvasTabKey.DiffOverview -> "view-diff-overview"
    CanvasTabKey.Semantic -> "view-metrics"
    CanvasTabKey.Moves -> "view-moves"
    else -> "view-" + key.toString().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}

/*
 * The stats on the hub's tiles; see [ReviewTab.scheduleTileRefresh]. They are read on the EDT from values a review already
 * holds, so none of these functions loads, parses or counts anything beyond a pass over in-memory collections.
 */

/** "1,234" in the US locale, like the panels' status lines. */
internal fun tileCount(value: Number): String = String.format(Locale.US, "%,d", value)

/** [count] with [one] or [many] as its label. */
internal fun countStat(count: Int, one: String, many: String = "${one}s"): ReviewTileStat =
    ReviewTileStat(tileCount(count), if (count == 1) one else many)

/** "written / total" summary nodes; nothing while the tree has no nodes. */
internal fun summaryStats(summaries: ReviewSummaries): List<ReviewTileStat> {
    val nodes = summaries.tree.summarizable
    if (nodes.isEmpty()) return emptyList()
    val written = nodes.count { summaries.text(it.id) != null }
    return listOf(ReviewTileStat("${tileCount(written)} / ${tileCount(nodes.size)}", "summaries written"))
}

/** The distinct labels of [topics], called [label] ("topic" | "group"), and the files they classify; nothing before the first. */
internal fun topicStats(topics: ReviewTopics, label: String): List<ReviewTileStat> {
    if (topics.labels.isEmpty()) return emptyList()
    val classified = topics.labels.size
    return listOf(countStat(topics.labels.values.distinct().size, label),
        ReviewTileStat("${tileCount(classified)} / ${tileCount(topics.files.size)}", "files classified"))
}

/** The messages of a chat, and whether it is replying now; nothing for an empty, idle chat. */
internal fun chatStats(messages: Int, busy: Boolean): List<ReviewTileStat> = buildList {
    if (messages > 0) add(countStat(messages, "message"))
    if (busy) add(ReviewTileStat("…", "replying"))
}

/** How long a review tab waits after a change before it updates its tiles' stats, so a burst of changes updates them once. */
internal const val TILE_REFRESH_MS = 200
