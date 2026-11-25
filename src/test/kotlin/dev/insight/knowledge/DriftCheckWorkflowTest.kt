package dev.insight.knowledge

import dev.insight.decisions.DecisionFn
import dev.insight.llm.CompletionFn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftCheckWorkflowTest {
    private val parserOk = AnchorState.Present("fp1")
    private fun resolver() = MapResolver("src/Parser.kt" to parserOk)

    private fun workflow(
        base: KnowledgeBase,
        resolver: MapResolver = resolver(),
        decide: DecisionFn? = FakeDrift().fn,
        complete: CompletionFn? = null,
        evidence: EvidenceProvider = RecordingEvidence(),
        limits: DriftLimits = DriftLimits(parallelism = 1),
        anchorer: ClaimAnchorer = LinkAnchorer(),
    ) = DriftCheckWorkflow(base, anchorer, resolver, evidence, decide, complete, limits)

    private fun run(workflow: DriftCheckWorkflow, text: String = DEMO_TEXT, onFinding: (DriftFinding) -> Unit = {}) =
        runBlocking { workflow.check(listOf(DocInput(DEMO_DOC, text)), NoProgress(), onFinding) }

    private fun KnowledgeBase.claimWith(text: String) = snapshot().claims.values.single { it.text.startsWith(text) }

    @Test fun `first run imports statements as human flow claims by user`() {
        val base = driftBase()
        val report = run(workflow(base))
        val claims = base.snapshot().active()
        assertEquals(3, claims.size)
        assertTrue(claims.all { it.origin == ClaimOrigin.HUMAN && it.level == ClaimLevel.FLOW && it.area == "demo" && it.source == DEMO_DOC })
        assertEquals(3, report.counts.added)
        val parser = base.claimWith("The demo parses")
        assertEquals(listOf(Anchor(AnchorKind.FILE, "src/Parser.kt", "fp1")), parser.anchors)
        assertEquals(Citation(DEMO_DOC, 3, 3), parser.citations.single())
        assertTrue("entry-point" in base.claimWith("[Parser.kt]").tags)
        val adds = base.entries().filter { it.op is KnowledgeOp.Add }
        assertTrue(adds.all { it.actor == RatchetGuard.USER && it.verdict.accepted })
    }

    @Test fun `running twice adds and changes nothing`() {
        val base = driftBase()
        val wf = workflow(base)
        run(wf)
        val after = base.entries().size
        val second = run(wf)
        assertEquals(after, base.entries().size)
        assertEquals(0, second.counts.added)
        assertEquals(0, second.counts.retired)
        assertEquals(2, second.counts.unchanged)
        assertEquals(listOf(DriftKind.UNANCHORED), second.findings.map { it.kind })
    }

    @Test fun `editing the doc retires the old sentence and adds the new one`() {
        val base = driftBase()
        val wf = workflow(base)
        run(wf)
        val old = base.claimWith("It never touches")
        val edited = DEMO_TEXT.replace("It never touches the disk.", "It writes a cache file.")
        val report = run(wf, edited)
        assertEquals(1, report.counts.added)
        assertEquals(1, report.counts.retired)
        val status = base.snapshot()[old.id]!!.status as ClaimStatus.Retired
        assertEquals(RetireEvidence.ByUser("removed from $DEMO_DOC"), status.evidence)
        assertEquals(RatchetGuard.USER, base.entries().last { it.op is KnowledgeOp.Retire }.actor)
        assertTrue(base.snapshot().active().any { it.text == "It writes a cache file." })
        assertFalse(base.snapshot().active().any { it.id == old.id })
    }

    @Test fun `a claim the user retired stays retired while the doc still says it`() {
        val base = driftBase()
        val wf = workflow(base)
        run(wf)
        val claim = base.claimWith("It never touches")
        runBlocking { base.submit(KnowledgeOp.Retire(claim.id, "wrong", RetireEvidence.ByUser("wrong")), RatchetGuard.USER) }
        val report = run(wf)
        assertEquals(0, report.counts.added)
        assertEquals(2, base.snapshot().active().size)
    }

    @Test fun `a removed then restored sentence is added again`() {
        val base = driftBase()
        val wf = workflow(base)
        run(wf)
        run(wf, DEMO_TEXT.replace(" It never touches the disk.", ""))
        val report = run(wf)
        assertEquals(1, report.counts.added)
        assertEquals(3, base.snapshot().active().size)
    }

    @Test fun `gone anchor gives a broken anchor finding and suspect claim by drift`() {
        val base = driftBase()
        val resolver = resolver()
        val wf = workflow(base, resolver)
        run(wf)
        resolver.states.remove("src/Parser.kt")
        val seen = mutableListOf<DriftFinding>()
        val report = run(wf, onFinding = { seen += it })
        val broken = report.findings.filter { it.kind == DriftKind.BROKEN_ANCHOR }
        assertEquals(2, broken.size)
        assertEquals(report.findings, seen.sortedWith(compareBy({ it.startLine }, { it.kind.ordinal })))
        val claim = base.claimWith("The demo parses")
        assertTrue(claim.status is ClaimStatus.Suspect)
        assertTrue(claim.isActive)
        val mark = base.entries().last { it.op is KnowledgeOp.MarkSuspect }
        assertEquals("drift", mark.actor)
        assertTrue(mark.verdict.accepted)
        assertEquals(3, broken.first().startLine)
        assertTrue("src/Parser.kt" in broken.first().reason)
        // A second run keeps reporting it without piling up identical ops.
        val before = base.entries().size
        assertEquals(2, run(wf).findings.count { it.kind == DriftKind.BROKEN_ANCHOR })
        assertEquals(before, base.entries().size)
    }

    @Test fun `moved anchor is reanchored`() {
        val base = driftBase()
        val resolver = resolver()
        val wf = workflow(base, resolver)
        run(wf)
        resolver.states.remove("src/Parser.kt")
        resolver.states["src/core/Parser.kt"] = AnchorState.Present("fp1")
        resolver.states["src/Parser.kt"] = AnchorState.Moved("src/core/Parser.kt", "fp1")
        val report = run(wf)
        assertEquals(2, report.counts.reanchored)
        assertEquals("src/core/Parser.kt", base.claimWith("The demo parses").anchors.single().ref)
        assertTrue(report.findings.none { it.kind == DriftKind.BROKEN_ANCHOR })
        assertEquals("drift", base.entries().last { it.op is KnowledgeOp.Reanchor }.actor)
    }

    @Test fun `a previously unanchored statement is reanchored and verified when an anchor becomes available`() {
        val base = driftBase()
        val text = "# Demo\n\nThe parser validates input."
        val anchor = Anchor(AnchorKind.FILE, "src/Parser.kt")
        var available = false
        var anchorAttempts = 0
        val anchorer = ClaimAnchorer { _, _ ->
            anchorAttempts++
            if (available) listOf(anchor) else emptyList()
        }
        val wf = workflow(base, anchorer = anchorer)

        assertEquals(1, run(wf, text).counts[DriftKind.UNANCHORED])
        assertEquals(1, anchorAttempts)
        val id = base.snapshot().active().single().id

        available = true
        val report = run(wf, text)
        assertEquals(0, report.counts.added)
        assertEquals(1, report.counts.reanchored)
        assertEquals(listOf(DriftKind.OK), report.findings.map { it.kind })
        assertEquals(listOf(anchor.copy(fingerprint = "fp1")), base.snapshot()[id]!!.anchors)
        val reanchor = base.entries().single { it.op is KnowledgeOp.Reanchor }
        assertEquals(DriftCheckWorkflow.ACTOR, reanchor.actor)
        assertTrue(reanchor.verdict.accepted)
        assertEquals(1, run(wf, text).counts.unchanged)
        assertEquals(2, anchorAttempts)
    }

    @Test fun `accurate statement is confirmed with refreshed fingerprints`() {
        val base = driftBase()
        val resolver = resolver()
        val fake = FakeDrift()
        val wf = workflow(base, resolver, fake.fn)
        val first = run(wf)
        assertEquals(2, first.counts[DriftKind.OK])
        assertEquals(2, fake.requests.size)
        resolver.states["src/Parser.kt"] = AnchorState.Present("fp2")
        val seen = mutableListOf<DriftFinding>()
        val report = run(wf, onFinding = { seen += it })
        assertEquals(2, report.counts[DriftKind.OK])
        assertEquals(4, fake.requests.size)
        val claim = base.claimWith("The demo parses")
        assertEquals("fp2", claim.anchors.single().fingerprint)
        assertEquals(ClaimStatus.Live, claim.status)
        assertEquals("drift", base.entries().last { it.op is KnowledgeOp.Confirm }.actor)
        assertTrue(seen.any { it.kind == DriftKind.OK })
        val question = fake.requests.last().questions
        assertEquals(setOf("accurate", "detail"), question.keys)
    }

    @Test fun `inaccurate statement is stale and marked suspect, and a later accurate answer confirms it`() {
        val base = driftBase()
        val resolver = resolver()
        var accuracy = 0.1
        val wf = workflow(base, resolver, FakeDrift({ accuracy }).fn)
        val report = run(wf)
        assertEquals(2, report.counts[DriftKind.STALE])
        assertTrue(base.claimWith("The demo parses").status is ClaimStatus.Suspect)
        accuracy = 0.9
        run(wf)
        assertEquals(ClaimStatus.Live, base.claimWith("The demo parses").status)
    }

    @Test fun `accurate but detailed statement is too detailed and gets no op`() {
        val base = driftBase()
        val wf = workflow(base, decide = FakeDrift(detail = { "volatile_value" }).fn)
        val before = base.entries().size
        val report = run(wf)
        assertEquals(2, report.counts[DriftKind.TOO_DETAILED])
        assertEquals(3, base.entries().size)
        assertTrue(base.entries().none { it.op is KnowledgeOp.Confirm || it.op is KnowledgeOp.MarkSuspect })
        assertTrue(report.findings.first { it.kind == DriftKind.TOO_DETAILED }.reason.contains("changing value"))
    }

    @Test fun `without decide only anchor findings are made and a warning says so`() {
        val base = driftBase()
        val resolver = resolver()
        val evidence = RecordingEvidence()
        val wf = workflow(base, resolver, decide = null, evidence = evidence)
        val ok = run(wf)
        assertEquals(listOf(DriftKind.UNANCHORED), ok.findings.map { it.kind })
        assertTrue(ok.warnings.any { "Decisions are not configured" in it })
        assertEquals(0, evidence.calls.get())
        assertTrue(base.entries().none { it.op is KnowledgeOp.Confirm })
        resolver.states.clear()
        val broken = run(wf)
        assertEquals(2, broken.counts[DriftKind.BROKEN_ANCHOR])
        assertTrue(broken.warnings.any { "Decisions are not configured" in it })
    }

    @Test fun `limits skip verification and say so`() {
        val base = driftBase()
        val fake = FakeDrift()
        val report = run(workflow(base, decide = fake.fn, limits = DriftLimits(parallelism = 1, maxClaimsVerified = 1)))
        assertEquals(1, fake.requests.size)
        assertEquals(1, report.counts.skipped)
        assertTrue(report.warnings.any { "run limit" in it })
    }

    @Test fun `concurrent reads reserve their shares of the evidence budget`() = runBlocking {
        val budgets = mutableListOf<Int>()
        val bothStarted = CompletableDeferred<Unit>()
        val reader = EvidenceProvider { anchors, maxChars ->
            budgets += maxChars
            if (budgets.size == 2) bothStarted.complete(Unit)
            bothStarted.await()
            listOf(Evidence(anchors.single().ref, "x".repeat(maxChars)))
        }
        val limits = DriftLimits(parallelism = 2, evidenceCharsPerClaim = 7, maxEvidenceChars = 10)
        val report = withTimeout(5_000) {
            workflow(driftBase(), evidence = reader, limits = limits).check(listOf(DocInput(DEMO_DOC, DEMO_TEXT)), NoProgress())
        }
        assertEquals(listOf(3, 7), budgets.sorted())
        assertEquals(2, report.counts.verified)
    }

    @Test fun `a short evidence read returns its unused budget to later reads`() {
        val budgets = mutableListOf<Int>()
        val reader = EvidenceProvider { anchors, maxChars ->
            budgets += maxChars
            listOf(Evidence(anchors.single().ref, "xx"))
        }
        val limits = DriftLimits(parallelism = 1, evidenceCharsPerClaim = 7, maxEvidenceChars = 8)
        val report = run(workflow(driftBase(), evidence = reader, limits = limits))
        assertEquals(listOf(7, 6), budgets)
        assertEquals(2, report.counts.verified)
    }

    @Test fun `statements without evidence are not judged`() {
        val fake = FakeDrift()
        val report = run(workflow(driftBase(), decide = fake.fn, evidence = RecordingEvidence(empty = true)))
        assertTrue(fake.requests.isEmpty())
        assertTrue(report.findings.none { it.kind == DriftKind.OK || it.kind == DriftKind.STALE })
        assertTrue(report.warnings.any { "no evidence" in it })
    }

    @Test fun `a failing decision becomes a warning`() {
        val failing: DecisionFn = { throw java.io.IOException("server down") }
        val report = run(workflow(driftBase(), decide = failing))
        assertTrue(report.warnings.any { "Verification failed for 2" in it && "server down" in it })
    }

    @Test fun `stale findings get suggested patches from the model`() {
        val base = driftBase()
        val answer = """
            ```json
            {"patches":[{"start_line":3,"end_line":3,"old":"The demo parses input with [Parser.kt](../src/Parser.kt).","new":"The demo reads input with [Parser.kt](../src/Parser.kt).","reason":"renamed"}]}
            ```
        """.trimIndent()
        val report = run(workflow(base, decide = FakeDrift({ 0.1 }).fn, complete = completion(answer)))
        val patch = report.patches.single()
        assertEquals(DEMO_DOC, patch.doc)
        assertEquals(3, patch.startLine)
        assertTrue(patch.new.startsWith("The demo reads"))
        assertTrue(report.toMarkdown().contains("The demo reads input"))
    }

    @Test fun `malformed patch answers become warnings and no patches`() {
        val report = run(workflow(driftBase(), decide = FakeDrift({ 0.1 }).fn, complete = completion("not json at all")))
        assertTrue(report.patches.isEmpty())
        assertTrue(report.warnings.any { "Proposing edits failed" in it })
        assertEquals(2, report.counts[DriftKind.STALE])
    }

    @Test fun `no patch is requested without stale or broken findings`() {
        var calls = 0
        val complete: CompletionFn = { calls++; throw AssertionError("not expected") }
        run(workflow(driftBase(), complete = complete))
        assertEquals(0, calls)
    }

    @Test fun `cancellation keeps what was reached`() = runBlocking<Unit> {
        val base = driftBase()
        val fake = FakeDrift()
        val seen = java.util.Collections.synchronizedList(mutableListOf<DriftFinding>())
        var calls = 0
        lateinit var job: kotlinx.coroutines.Job
        val cancelling: DecisionFn = { request ->
            if (++calls == 2) { job.cancel(); kotlinx.coroutines.awaitCancellation() }
            fake.fn(request)
        }
        val wf = workflow(base, decide = cancelling)
        job = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            wf.check(listOf(DocInput(DEMO_DOC, DEMO_TEXT)), NoProgress()) { seen += it }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(3, base.snapshot().active().size)
        assertTrue(seen.any { it.kind == DriftKind.OK })
        assertEquals(1, base.entries().count { it.op is KnowledgeOp.Confirm })
    }

    @Test fun `a duplicate sentence in the same doc is imported once`() {
        val base = driftBase()
        run(workflow(base), "# T\n\nSame thing here.\n\nSame thing here.\n")
        assertEquals(1, base.snapshot().active().size)
        assertTrue(base.entries().all { it.verdict.accepted })
    }
}
