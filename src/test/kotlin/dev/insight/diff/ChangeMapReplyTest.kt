package dev.insight.diff

import dev.insight.diff.ChangeMapFixture.map
import dev.insight.diff.atoms.ChangeGraph
import dev.insight.diff.atoms.ChangeMap
import dev.insight.diff.atoms.ChangeMapRun
import dev.insight.diff.atoms.buildChangeMapTree
import dev.insight.review.ReviewSummaries
import dev.insight.review.summarize
import org.junit.Assert.*
import org.junit.Test

class ChangeMapReplyTest {
    private val tree = buildChangeMapTree(map, ChangeMapFixture.files())
    private val none = ReviewSummaries(tree)
    private val all = none.summarize(tree.nodes.associate { it.id to "About ${it.label}." })

    @Test fun `an empty diff and a map without atoms say so`() {
        assertEquals("There are no changed files to map.", changeMapReply(0, null, none, 0))
        val empty = ChangeMap(ChangeGraph(emptyList(), emptyList()), emptyList())
        assertEquals("No changed declarations found.", changeMapReply(3, empty, ReviewSummaries(buildChangeMapTree(empty, emptyList())), 0))
    }

    @Test fun `a complete map answers with the root summary and its counts`() {
        val reply = changeMapReply(7, map, all, 4, ChangeMapRun(map, 2, 0, 0, null))
        assertEquals("About All changes.\n\n[2 logical changes · 2 standalone · 7 declarations · ${tree.nodes.size} summaries · 6 requests]", reply)
        val withError = changeMapReply(7, map, all, 4, ChangeMapRun(map, 2, 0, 0, "Titles unavailable: down."))
        assertTrue(withError.startsWith("About All changes."))
        assertTrue(withError.endsWith("\n\nTitles unavailable: down."))
    }

    @Test fun `an incomplete map counts what it holds says why it stopped and how to continue`() {
        val partial = none.summarize(tree.nodes.filter { it.children.isEmpty() }.associate { it.id to "Leaf." })
        val reply = changeMapReply(7, map, partial, 3, ChangeMapRun(map, 5, 1, 2, "Decisions API unavailable: off."), "Summary request failed.")
        assertTrue(reply, reply.startsWith("Mapped 7 changed declarations into 2 logical changes and 2 standalone changes. " +
            "Judgments merged 1 and attached 2. Summarized 7 of ${tree.nodes.size} entries with 8 requests."))
        assertTrue(reply.contains("\n\nDecisions API unavailable: off.\n\nSummary request failed."))
        assertTrue(reply.endsWith("[Run /change-map again to write the missing summaries.]"))
        // A continued map has no build of its own and no judgments to report.
        val continued = changeMapReply(7, map, partial, 1)
        assertFalse(continued.contains("Judgments"))
        assertTrue(continued.contains("with 1 request."))
    }
}
