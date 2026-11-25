package dev.insight.knowledge

import dev.insight.agent.ToolInputException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistillPartsTest {
    @Test fun `similarity is normalized token jaccard`() {
        assertEquals(1.0, claimSimilarity("The parser caches tokens.", "the parser  CACHES tokens"), 1e-9)
        assertEquals(setOf("token", "cache", "parse", "file"), claimTokens("tokenCache parse of the file"))
        assertTrue(claimSimilarity("Cache entries never expire.", "Cache entries expire after an hour.") in 0.4..0.5)
        assertEquals(0.0, claimSimilarity("Lexing makes tokens.", "Rendering paints widgets."), 1e-9)
        assertTrue(isRicher("The parser caches tokens per file in memory.", "The parser caches tokens per file."))
        assertFalse(isRicher("The parser caches tokens.", "The parser caches tokens per file."))
    }

    @Test fun `similar pairs are thresholded, filtered and ordered`() {
        val pairs = similarPairs(listOf("aa bb cc dd", "aa bb cc ee", "xx yy zz", "aa bb cc dd"), 0.5, 10) { _, b -> b != 2 }
        assertEquals(listOf(0 to 3, 0 to 1, 1 to 3), pairs.map { it.a to it.b })
        val sets = DisjointSets(4).apply { union(0, 3); union(1, 3) }
        assertEquals(listOf(listOf(0, 1, 3)), sets.groups())
    }

    @Test fun `refresh ops follow the anchor states`() {
        val gen = claim("g")
        val human = claim("h", origin = ClaimOrigin.HUMAN)
        assertTrue(refreshOps(gen, listOf(AnchorState.Gone)).single() is KnowledgeOp.Retire)
        assertTrue(refreshOps(human, listOf(AnchorState.Gone)).single() is KnowledgeOp.MarkSuspect)
        assertTrue(refreshOps(gen, listOf(AnchorState.Present("fp-g"))).isEmpty())
        assertTrue(refreshOps(gen, listOf(AnchorState.Present(null))).isEmpty())
        val changed = refreshOps(gen, listOf(AnchorState.Present("other"))).single() as KnowledgeOp.MarkSuspect
        assertTrue(changed.reason.startsWith("Anchor changed"))
        val moved = refreshOps(gen, listOf(AnchorState.Moved("src/n.kt", "fp-g")))
        assertEquals("src/n.kt", (moved.single() as KnowledgeOp.Reanchor).anchors.single().ref)
        val movedAndChanged = refreshOps(gen, listOf(AnchorState.Moved("src/n.kt", "new")))
        assertEquals(listOf(KnowledgeOp.Reanchor::class, KnowledgeOp.MarkSuspect::class), movedAndChanged.map { it::class })
        val already = gen.copy(status = ClaimStatus.Suspect("Anchor changed: src/g.kt"))
        assertTrue(refreshOps(already, listOf(AnchorState.Present("other"))).isEmpty())
    }

    @Test fun `refresh plan reports current anchors and unresolvable claims`() = runBlocking {
        val resolver = AnchorResolver { if (it.ref.contains("boom")) error("index not ready") else AnchorState.Present("new") }
        val ok = claim("ok")
        val broken = Claim("b", "Broken.", ClaimLevel.FILE, ClaimOrigin.GENERATED, "src", listOf(Anchor(AnchorKind.FILE, "boom.kt", "x")))
        val plan = planDistillRefresh(listOf(ok, broken), resolver, 2)
        assertEquals("new", plan.current.getValue("ok").single().fingerprint)
        assertEquals(listOf("ok"), plan.ops.map { (it as KnowledgeOp.MarkSuspect).id })
        assertEquals(1, plan.warnings.size)
    }

    @Test fun `the audit flags claims that left without an accepted op`() {
        val before = stateOf(claim("a"), claim("b"))
        val retire = KnowledgeOp.Retire("a", "gone", RetireEvidence.AnchorsGone(listOf("src/a.kt")))
        val after = before.apply(retire).copy(claims = before.apply(retire).claims + ("b" to claim("b").copy(status = ClaimStatus.Superseded(listOf("x")))))
        val entries = listOf(LedgerEntry(1, retire, "organizer", 0, Verdict.Accepted()))
        assertEquals(listOf(DistillAuditFinding("b", "superseded")), ratchetAudit(before, after, entries))
        assertEquals(2, ratchetAudit(before, after, entries.map { it.copy(verdict = Verdict.Rejected("no")) }).size)
    }

    @Test fun `lift answers parse json and plain text`() {
        assertEquals(ClaimLevel.FLOW to "Requests flow through the gateway.",
            DistillPrompts.parseLift("```json\n{\"level\":\"flow\",\"text\":\"Requests flow through the gateway.\"}\n```"))
        assertEquals(ClaimLevel.MODULE to "Modules share one cache.", DistillPrompts.parseLift("{\"text\":\"Modules share one cache.\"}"))
        assertNull(DistillPrompts.parseLift("{\"text\": null}"))
        assertNull(DistillPrompts.parseLift("NONE"))
        assertEquals(ClaimLevel.MODULE to "Plain claim.", DistillPrompts.parseLift("- \"Plain claim.\""))
    }

    @Test fun `worker message lists known claims, claims to verify and budgeted evidence`() {
        val known = claim("k", "Known fact.")
        val suspect = claim("s", "Suspect fact.").copy(status = ClaimStatus.Suspect("Anchor changed: x"))
        val text = DistillPrompts.workerMessage(partition("src/a", "src/a/A.kt"), listOf(known), listOf(suspect),
            listOf(Evidence("src/a/A.kt", "x".repeat(5_000), "src/a/A.kt", 1, 9)), 1_000, 10)
        assertTrue(text.startsWith("Area: src/a\n"))
        assertTrue(text.contains("- k (file, generated): Known fact."))
        assertTrue(text.contains("Claims to re-verify"))
        assertTrue(text.contains("suspect: Anchor changed: x"))
        assertTrue(text.contains("[Evidence cut.]"))
        assertTrue(text.length < 2_500)
        assertTrue(DistillPrompts.WORKER_SYSTEM.contains("frequently changing values"))
    }

    @Test fun `worker tools only buffer proposals and validate them`() = runBlocking {
        val known = claim("k", "Known fact.", area = "src/a")
        val proposed = mutableListOf<DistillProposal>()
        val tools = DistillTools("src/a", listOf(known), setOf("k"), maxProposals = 2) { proposed += it }
        fun call(tool: dev.insight.agent.ChatTool, json: String) = runBlocking { tool.call(com.google.gson.JsonParser.parseString(json).asJsonObject) }
        fun rejects(tool: dev.insight.agent.ChatTool, json: String, word: String) {
            try { call(tool, json); throw AssertionError("expected rejection of $json") } catch (e: ToolInputException) {
                assertTrue("'${e.message}' lacks '$word'", e.message!!.contains(word, ignoreCase = true))
            }
        }
        assertEquals("Still to re-verify: k. Before answering, call confirm_claim, revise_claim or refute_claim for each.", tools.followUp())
        call(tools.proposeClaim, """{"text":"New fact.","level":"module","anchors":[{"kind":"directory","ref":"src/a"}],
            "citations":[{"path":"src/a/A.kt","start_line":3,"end_line":5}]}""")
        rejects(tools.proposeClaim, """{"text":"new fact","level":"file","anchors":[{"kind":"file","ref":"x"}]}""", "already proposed")
        rejects(tools.proposeClaim, """{"text":"Known fact","level":"file","anchors":[{"kind":"file","ref":"x"}]}""", "already known")
        rejects(tools.proposeClaim, """{"text":"Other.","level":"galaxy","anchors":[{"kind":"file","ref":"x"}]}""", "level")
        rejects(tools.proposeClaim, """{"text":"Other.","level":"file","anchors":[]}""", "anchors")
        rejects(tools.confirmClaim, """{"id":"nope"}""", "unknown claim")
        call(tools.reviseClaim, """{"id":"k","new_text":"Known fact, restated.","reason":"clearer"}""")
        rejects(tools.proposeClaim, """{"text":"Third.","level":"file","anchors":[{"kind":"file","ref":"x"}]}""", "no proposals left")
        call(tools.refuteClaim, """{"id":"k","reason":"no","evidence":"A.kt:1"}""")
        assertNull(tools.followUp())
        assertEquals(3, proposed.size)
        assertEquals(proposed, tools.proposals)
        val first = proposed.first() as DistillProposal.Propose
        assertEquals(ClaimLevel.MODULE, first.level)
        assertEquals(Citation("src/a/A.kt", 3, 5), first.citations.single())
        assertEquals("worker:src/a", first.actor)
        assertEquals(DistillToolNames.ALL, tools.recording().map { it.definition.name }.toSet())
    }

    @Test fun `suspects go to the partition owning their anchors`() {
        val a = partition("src/a", "src/a/A.kt")
        val b = partition("src/a", "src/a/B.kt")
        val onB = claim("x", area = "src/a").copy(anchors = listOf(Anchor(AnchorKind.FILE, "src/a/B.kt")), status = ClaimStatus.Suspect("r"))
        val elsewhere = claim("y", area = "src/a").copy(status = ClaimStatus.Suspect("r"))
        val live = claim("z", area = "src/a")
        assertEquals(mapOf(b to listOf(onB), a to listOf(elsewhere)), assignSuspects(listOf(a, b), listOf(onB, elsewhere, live)))
        assertEquals(listOf("src/a/A.kt"), uncoveredFiles(partition("src/a", "src/a/A.kt", "src/a/B.kt"), listOf(onB)))
        val dir = claim("d", area = "src/a").copy(anchors = listOf(Anchor(AnchorKind.DIRECTORY, "src/a")))
        assertTrue(uncoveredFiles(partition("src/a", "src/a/A.kt"), listOf(dir)).isEmpty())
        assertEquals(listOf(0, 1, 2), listOf("", "src", "src/a").map(::areaDepth))
    }
}
