package dev.insight.knowledge.ui

import dev.insight.codereview.CodeKnowledge
import dev.insight.knowledge.ClaimOrigin
import dev.insight.knowledge.ClaimStatus
import dev.insight.knowledge.DistillEvent
import dev.insight.knowledge.ExclusionReason
import dev.insight.knowledge.ExcludedClaim
import dev.insight.knowledge.KnowledgeOp
import dev.insight.knowledge.LedgerEntry
import dev.insight.knowledge.RenderedDoc
import dev.insight.knowledge.RetireEvidence
import dev.insight.knowledge.Verdict
import dev.insight.knowledge.claim
import dev.insight.knowledge.stateOf
import dev.insight.review.ui.ReviewTileStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeContentTest {
    private val live = claim("a", area = "src/b")
    private val suspect = claim("b", area = "src/a").copy(status = ClaimStatus.Suspect("moved"))
    private val retired = claim("c", area = "").copy(status = ClaimStatus.Retired("gone", RetireEvidence.AnchorsGone(listOf("x"))))
    private val superseded = claim("d", area = "src/a").copy(status = ClaimStatus.Superseded(listOf("a")))

    private fun entry(seq: Long, op: KnowledgeOp, verdict: Verdict = Verdict.Accepted(), actor: String = "user") =
        LedgerEntry(seq, op, actor, seq, verdict)

    @Test fun `counts split claims by lifecycle and the tile shows live and suspect`() {
        val counts = knowledgeCounts(stateOf(live, suspect, retired, superseded))
        assertEquals(KnowledgeCounts(live = 1, suspect = 1, retired = 1, superseded = 1), counts)
        assertEquals(2, counts.active)
        assertEquals("1 live · 1 suspect · 1 retired · 1 superseded", countsLine(counts))
        assertEquals(listOf(ReviewTileStat("1", "live"), ReviewTileStat("1", "suspect")), knowledgeStats(counts))
        assertEquals(listOf(ReviewTileStat("3", "live")), knowledgeStats(KnowledgeCounts(live = 3)))
        assertTrue(knowledgeStats(KnowledgeCounts()).isEmpty())
    }

    @Test fun `claims group by area in path order and filter by status`() {
        val all = listOf(live, suspect, retired, superseded, claim("e", area = "src/a"))
        assertEquals(listOf("src/a" to listOf("b", "e"), "src/b" to listOf("a")),
            claimsByArea(all, ClaimFilter.ACTIVE).map { (area, claims) -> area to claims.map { it.id } })
        assertEquals(listOf("" to listOf("c")), claimsByArea(all, ClaimFilter.RETIRED).map { (a, c) -> a to c.map { it.id } })
        assertEquals(listOf("src/a"), claimsByArea(all, ClaimFilter.SUPERSEDED).map { it.first })
        assertEquals(listOf("", "src/a", "src/b"), claimsByArea(all, ClaimFilter.ALL).map { it.first })
        assertEquals("(project)", areaLabel(""))
    }

    @Test fun `a claim's history is every entry that names it`() {
        val merged = claim("m")
        val entries = listOf(
            entry(1, KnowledgeOp.Add(live)),
            entry(2, KnowledgeOp.Add(suspect)),
            entry(3, KnowledgeOp.Merge(listOf("a", "b"), merged)),
            entry(4, KnowledgeOp.Retire("zz", "gone", RetireEvidence.ByUser("x"))),
            entry(5, KnowledgeOp.Contradict("m", "q", "disagree"), Verdict.Rejected("no")),
        )
        assertEquals(listOf(1L, 3L), claimHistory(entries, "a").map { it.seq })
        assertEquals(listOf(3L, 5L), claimHistory(entries, "m").map { it.seq })
        assertTrue(claimHistory(entries, "none").isEmpty())
    }

    @Test fun `entries read as one line and a rejected one carries its reason`() {
        val accepted = entry(7, KnowledgeOp.Retire("c-1", "anchors gone", RetireEvidence.AnchorsGone(listOf("x"))), actor = "drift")
        assertEquals("#7 drift · Retire c-1: anchors gone", describeEntry(accepted))
        assertNull(rejectionOf(accepted))
        val rejected = entry(8, KnowledgeOp.Restore("c-2"), Verdict.Rejected("Only the user can restore a claim"), actor = "worker:src")
        assertEquals("#8 worker:src · Restore c-2 — rejected: Only the user can restore a claim", describeEntry(rejected))
        assertEquals("Only the user can restore a claim", rejectionOf(rejected))
        assertTrue(hasRejections(listOf(accepted, rejected)))
        assertFalse(hasRejections(listOf(accepted)))
    }

    @Test fun `events and findings become activity lines`() {
        assertEquals("Reading src/a (3 files, 1 of 2)", describeEvent(DistillEvent.PartitionStarted("src/a", 3, 0, 2)))
        assertEquals("src/a: 4 proposals", describeEvent(DistillEvent.PartitionFinished("src/a", 4)))
        assertEquals("(project) unchanged, skipped", describeEvent(DistillEvent.PartitionFinished("", 0, skipped = true)))
        assertEquals("src/a failed: boom", describeEvent(DistillEvent.PartitionFinished("src/a", 0, error = "boom")))
        assertNull(describeEvent(DistillEvent.Proposed("src/a", dev.insight.knowledge.DistillProposal.Confirm("worker:src/a", "c-1"))))
        assertEquals("Warning: careful", describeEvent(DistillEvent.Warning("careful")))
        val rejected = entry(3, KnowledgeOp.Restore("c-2"), Verdict.Rejected("nope"))
        assertEquals(describeEntry(rejected), describeEvent(DistillEvent.Rejected(rejected)))
    }

    @Test fun `progress is a fraction of the phase`() {
        assertEquals(0.25, fractionOf(1, 4)!!, 1e-9)
        assertEquals(1.0, fractionOf(9, 4)!!, 1e-9)
        assertNull(fractionOf(0, 0))
        assertEquals("Syncing docs 3 / 10", progressText("Syncing docs", 3, 10))
        assertEquals("Partitioning", progressText("Partitioning", 0, 0))
    }

    @Test fun `a doc target is named for its area and updates the doc most claims came from`() {
        val fromFlow = claim("a", area = "walk").copy(origin = ClaimOrigin.HUMAN, source = "flows/walk.md")
        val other = claim("b", area = "walk").copy(source = "docs/other.md")
        val third = claim("c", area = "walk").copy(source = "flows/walk.md")
        val target = docTargetFor("walk", listOf(fromFlow, other, third), "Project")
        assertEquals("walk", target.title)
        assertEquals(setOf("walk"), target.areas)
        assertEquals("flows/walk.md", target.existingPath)
        val root = docTargetFor("", listOf(live), "My Project")
        assertEquals("My Project", root.title)
        assertNull(root.existingPath)
        assertEquals("flows/my-project.md", root.docPath)
    }

    @Test fun `a diff is fenced by more backticks than it contains`() {
        assertEquals("```diff\n+a\n```\n", fenced("+a\n", "diff"))
        val inner = "+```kotlin\n+x\n+```\n"
        val fenced = fenced(inner, "diff")
        assertTrue(fenced.startsWith("````diff\n"))
        assertTrue(fenced.endsWith("\n````\n"))
    }

    @Test fun `the draft report says nothing was written and the diff report fences the diff`() {
        val doc = RenderedDoc("flows/x.md", "# X flow\n\nIt works.\n", listOf("c-1"), emptyList(),
            listOf(ExcludedClaim("c-2", ExclusionReason.DETAIL), ExcludedClaim("c-3", ExclusionReason.DETAIL), ExcludedClaim("c-4", ExclusionReason.VOLATILE)),
            listOf("Model call failed"))
        val draft = CodeKnowledge.draftMarkdown(doc, exists = false)
        assertTrue(draft, draft.contains("Nothing was written to disk"))
        assertTrue(draft, draft.contains("(a new doc)"))
        assertTrue(draft, draft.contains("# X flow"))
        assertTrue(draft, draft.contains("2 detail, 1 volatile"))
        assertTrue(draft, draft.contains("- Model call failed"))
        val diff = CodeKnowledge.diffMarkdown("flows/x.md", "--- a/x\n+++ b/x\n+new\n", exists = true)
        assertTrue(diff, diff.contains("```diff\n--- a/x"))
        assertTrue(CodeKnowledge.diffMarkdown("flows/x.md", "", exists = true).contains("matches the existing doc"))
    }

    @Test fun `the page offers an area per active claim and the root`() {
        val content = KnowledgeContent(claims = listOf(live, suspect, retired, superseded))
        assertEquals(listOf("", "src/a", "src/b"), content.areas)
        assertEquals(listOf(""), KnowledgeContent().areas)
    }
}
