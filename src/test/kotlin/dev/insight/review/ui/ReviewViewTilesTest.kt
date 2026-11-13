package dev.insight.review.ui

import dev.insight.review.CanvasTabKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewViewTilesTest {
    @Test fun `view tiles have stable ids distinct from the source and chat tiles`() {
        val keys = listOf(CanvasTabKey.Summaries, CanvasTabKey.Topics, CanvasTabKey.Grouping, CanvasTabKey.Wall, CanvasTabKey.Words,
            CanvasTabKey.DiffOverview, CanvasTabKey.Semantic, CanvasTabKey.Moves)
        assertEquals(listOf("view-summaries", "view-topics", "view-grouping", "view-wall", "view-words", "view-diff-overview",
            "view-metrics", "view-moves"), keys.map(::viewTileId))
        assertTrue(keys.map(::viewTileId).none { it == SOURCE_TILE || it == CHAT_TILE })
    }

    @Test fun `counts read as numbers with singular or plural labels`() {
        assertEquals(ReviewTileStat("1", "file"), countStat(1, "file"))
        assertEquals(ReviewTileStat("12,345", "files"), countStat(12_345, "file"))
        assertEquals(ReviewTileStat("2", "untracked included"), countStat(2, "untracked included", "untracked included"))
    }

    @Test fun `an idle empty chat shows nothing and a busy one says it is replying`() {
        assertTrue(chatStats(0, busy = false).isEmpty())
        assertEquals(listOf(ReviewTileStat("…", "replying")), chatStats(0, busy = true))
        assertEquals(listOf(ReviewTileStat("3", "messages"), ReviewTileStat("…", "replying")), chatStats(3, busy = true))
    }
}
