package dev.insight.knowledge

import com.google.gson.JsonObject
import dev.insight.agent.ChatTool
import dev.insight.agent.SEARCH_TOOLS
import dev.insight.agent.ToolRegistry
import dev.insight.llm.Completion
import dev.insight.llm.LlmTool
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DistillationWorkflowTest {
    @Test fun `project tools are discovered on demand and limited independently for each worker`() = runBlocking<Unit> {
        val h = DistillHarness(withDecisions = false)
        val lookups = AtomicInteger()
        val read = object : ChatTool {
            override val definition = LlmTool("read_file", "Read a project file.", JsonObject())
            override suspend fun call(arguments: JsonObject): String = "Read ${lookups.incrementAndGet()}"
        }
        val registry = ToolRegistry().register("reading current project files", read)
        val partitions = listOf(partition("src/a", "src/a/A.kt"), partition("src/b", "src/b/B.kt"))
        val workflow = DistillationWorkflow(h.base, { partitions }, sourceEvidence, FakeAnchorResolver(h.anchors), { request ->
            val names = request.tools.map { it.name }
            when (request.messages.last().toolCallId) {
                null -> {
                    assertTrue(SEARCH_TOOLS in names)
                    assertFalse("read_file" in names)
                    Completion("", toolCalls = listOf(LlmToolCall("find", SEARCH_TOOLS, JsonObject().apply { addProperty("query", "read_file") })))
                }
                "find" -> {
                    assertTrue("read_file" in names)
                    Completion("", toolCalls = listOf(LlmToolCall("read-1", "read_file", JsonObject()), LlmToolCall("read-2", "read_file", JsonObject())))
                }
                else -> {
                    assertTrue(request.messages.last().content.contains("No project tool calls left for this area"))
                    Completion("Done.")
                }
            }
        }, h.decide, registry = registry, limits = DistillLimits(maxToolCalls = 1))

        assertEquals(2, workflow.run(listOf("src"), h.progress).distilled)
        assertEquals(2, lookups.get())
    }

    @Test fun `partition limit advances across runs even when workers add no claims`() = runBlocking<Unit> {
        val h = DistillHarness()
        val partitions = (0 until 205).map { partition("src/p$it", "src/p$it/File.kt") }
        val limits = DistillLimits(maxPartitions = 200)

        assertEquals(200, h.run(partitions, limits).partitions)
        assertEquals(5, h.run(partitions, limits).partitions)
        assertEquals(partitions.map { it.area }.toSet(), h.model.workerTurns.toSet())
        assertEquals(200, h.run(partitions, limits).partitions)
    }

    @Test fun `a scope that shrinks within the limit is processed in full`() = runBlocking<Unit> {
        val h = DistillHarness()
        val partitions = (0 until 4).map { partition("src/p$it", "src/p$it/File.kt") }
        val limits = DistillLimits(maxPartitions = 2)
        h.run(partitions, limits)
        h.model.workerTurns.clear()

        assertEquals(2, h.run(partitions.take(2), limits).partitions)
        assertEquals(partitions.take(2).map { it.area }.toSet(), h.model.workerTurns.toSet())
        assertEquals(0, h.base.distillOffset(listOf("src")))
    }

    private val topics = mapOf(
        "src/a" to "Lexing turns characters into tokens.",
        "src/b" to "Rendering paints widgets onto a canvas.",
        "src/c" to "Persistence writes snapshots to disk.",
        "src/d" to "Telemetry batches usage events.",
    )

    @Test fun `parallel workers overlap up to the parallelism limit`() = runBlocking<Unit> {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val model = FakeDistillModel(onWorker = {
            peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            delay(100)
            inFlight.decrementAndGet()
        }, script = { area -> listOf(proposeCall(topics.getValue(area), "$area/Main.kt")) })
        val h = DistillHarness(model)
        topics.keys.forEach { h.present("$it/Main.kt") }
        val result = h.run(topics.keys.map { partition(it, "$it/Main.kt") }, DistillLimits(parallelism = 2))

        assertEquals(2, peak.get())
        assertEquals(4, result.distilled)
        topics.forEach { (area, text) -> assertEquals(listOf(text), h.active(area).map { it.text }) }
        assertEquals("fp1", h.active("src/a").single().anchors.single().fingerprint)
        assertEquals(4, h.events.count { it is DistillEvent.PartitionStarted })
        assertTrue(h.base.entries().filter { it.seq > 0 }.all { it.actor == RatchetGuard.USER || it.actor == "organizer" })
        assertTrue(result.audit.isEmpty())
        assertTrue(h.progress.reports.any { it.first == DistillationWorkflow.PHASE_DISTILL && it.second == 4 && it.third == 4 })
    }

    @Test fun `duplicate proposals of two workers in one area collapse to one claim`() = runBlocking<Unit> {
        val model = FakeDistillModel(script = { _ ->
            listOf(proposeCall(if (model0Calls.incrementAndGet() == 1) "The parser caches tokens per file." else "The parser caches tokens for each file.",
                if (model0Calls.get() == 1) "src/a/A.kt" else "src/a/B.kt"))
        })
        val h = DistillHarness(model, judgment = { kind, fields ->
            if (kind == DistillJudgment.SAME && fields["claim_a"]!!.contains("caches") && fields["claim_b"]!!.contains("caches")) 0.95
            else defaultJudgment(kind)
        })
        h.present("src/a/A.kt", "src/a/B.kt")
        val result = h.run(listOf(partition("src/a", "src/a/A.kt"), partition("src/a", "src/a/B.kt")))

        val claim = h.active("src/a").single()
        assertEquals(setOf("src/a/A.kt", "src/a/B.kt"), claim.anchors.map { it.ref }.toSet())
        assertEquals(1, result.duplicates)
        assertEquals(2, result.proposals)
        assertTrue(result.audit.isEmpty())
    }

    private val model0Calls = AtomicInteger()

    @Test fun `equal token sets need a duplicate judgment when normalized text differs`() = runBlocking<Unit> {
        val original = "The cache reads from the store."
        val reordered = "The store reads from the cache."
        for (withDecisions in listOf(true, false)) {
            val model = FakeDistillModel(script = { listOf(proposeCall(reordered, "src/a/Store.kt")) })
            val h = DistillHarness(model, withDecisions = withDecisions)
            h.present("src/a/Cache.kt", "src/a/Store.kt")
            h.seed(original, "src/a", "src/a/Cache.kt")
            val result = h.run(listOf(partition("src/a", "src/a/Cache.kt", "src/a/Store.kt")))

            assertEquals(setOf(original, reordered), h.active("src/a").map { it.text }.toSet())
            assertEquals(0, result.duplicates)
            assertEquals(if (withDecisions) 1 else 0, h.decisions.count(DistillJudgment.SAME))
        }
    }

    @Test fun `a suspect revision requires current evidence support and bidirectional entailment`() = runBlocking<Unit> {
        val replacement = "The lexer reads tokens lazily."
        for ((support, entailment) in listOf(0.1 to 0.95, 0.95 to 0.1, 0.95 to 0.95)) {
            lateinit var original: Claim
            val model = FakeDistillModel(script = {
                listOf(idCall(DistillToolNames.REVISE_CLAIM, original.id, "new_text" to replacement, "reason" to "Clearer"))
            })
            val h = DistillHarness(model, judgment = { kind, fields ->
                if (kind == DistillJudgment.SUPPORTS) {
                    assertEquals(replacement, fields["claim"])
                    assertTrue(fields.getValue("evidence").contains("src/a/New.kt"))
                    assertFalse(fields.getValue("evidence").contains("src/a/Old.kt"))
                    support
                } else defaultJudgment(kind)
            }, rewriteEntailment = entailment)
            h.anchors["src/a/Old.kt"] = AnchorState.Moved("src/a/New.kt", "fp2")
            original = h.seed("Tokens are read lazily by the lexer.", "src/a", "src/a/Old.kt")
            val result = h.run(listOf(partition("src/a", "src/a/New.kt")))

            assertEquals(1, h.decisions.count(DistillJudgment.SUPPORTS))
            val active = h.active("src/a").single()
            if (support == 0.95 && entailment == 0.95) {
                assertEquals(replacement, active.text)
                assertEquals(ClaimStatus.Live, active.status)
                assertEquals(listOf(Anchor(AnchorKind.FILE, "src/a/New.kt", "fp2")), active.anchors)
            } else {
                assertEquals(original.id, active.id)
                assertTrue(active.status is ClaimStatus.Suspect)
            }
            assertEquals(support == 0.95, result.entries.any { it.op is KnowledgeOp.Rewrite })
        }
    }

    @Test fun `a contradiction makes both claims suspect and keeps them`() = runBlocking<Unit> {
        val model = FakeDistillModel(script = { listOf(proposeCall("Cache entries never expire.", "src/a/Cache.kt")) })
        val h = DistillHarness(model, judgment = { kind, _ -> if (kind == DistillJudgment.CONTRADICT) 0.9 else defaultJudgment(kind) })
        h.present("src/a/Cache.kt", "src/a/Store.kt")
        val old = h.seed("Cache entries expire after an idle period.", "src/a", "src/a/Cache.kt")
        val result = h.run(listOf(partition("src/a", "src/a/Cache.kt", "src/a/Store.kt")))

        val claims = h.active("src/a")
        assertEquals(2, claims.size)
        claims.forEach { assertTrue("${it.id} should be suspect", (it.status as? ClaimStatus.Suspect)?.reason?.startsWith("Contradicts") == true) }
        assertTrue(old.id in claims.map { it.id })
        assertTrue(result.entries.any { it.op is KnowledgeOp.Contradict && it.verdict.accepted })
        assertTrue(result.audit.isEmpty())
    }

    @Test fun `a richer duplicate cannot replace a suspect claim without evidence support`() = runBlocking<Unit> {
        val replacement = "The parser caches tokens per source file."
        val model = FakeDistillModel(script = { listOf(proposeCall(replacement, "src/a/New.kt")) })
        val h = DistillHarness(model, judgment = { kind, fields ->
            when (kind) {
                DistillJudgment.SAME -> 0.95
                DistillJudgment.SUPPORTS -> {
                    assertEquals(replacement, fields["claim"])
                    assertTrue(fields.getValue("evidence").contains("src/a/New.kt"))
                    0.1
                }
                else -> defaultJudgment(kind)
            }
        })
        h.present("src/a/Old.kt", fingerprint = "fp2")
        h.present("src/a/New.kt")
        val original = h.seed("The parser caches tokens per file.", "src/a", "src/a/Old.kt")
        val result = h.run(listOf(partition("src/a", "src/a/Old.kt", "src/a/New.kt")))

        assertEquals(1, h.decisions.count(DistillJudgment.SUPPORTS))
        assertTrue(result.entries.none { it.op is KnowledgeOp.Rewrite })
        assertEquals(original.id, h.active("src/a").single().id)
        assertTrue(h[original.id]!!.status is ClaimStatus.Suspect)
    }

    @Test fun `refutations retire only with a confident decision and never retire human claims`() = runBlocking<Unit> {
        lateinit var weak: Claim
        lateinit var strong: Claim
        lateinit var human: Claim
        val model = FakeDistillModel(script = {
            listOf(weak, strong, human).map { idCall(DistillToolNames.REFUTE_CLAIM, it.id, "reason" to "Not true", "evidence" to "src/a/A.kt:3") }
        })
        val h = DistillHarness(model, judgment = { kind, fields ->
            if (kind != DistillJudgment.REFUTES) defaultJudgment(kind)
            else if (fields["claim"]!!.startsWith("Weak")) 0.3 else 0.95
        })
        h.present("src/a/A.kt", "src/a/New.kt")
        weak = h.seed("Weak: the scanner skips comments.", "src/a", "src/a/A.kt")
        strong = h.seed("Strong: the scanner is table driven.", "src/a", "src/a/A.kt")
        human = h.seed("Human: never block the event thread.", "src/a", "src/a/A.kt", origin = ClaimOrigin.HUMAN)
        val result = h.run(listOf(partition("src/a", "src/a/A.kt", "src/a/New.kt")))

        assertEquals(ClaimStatus.Live, h[weak.id]!!.status)
        val retired = h[strong.id]!!.status as ClaimStatus.Retired
        assertEquals(0.95, (retired.evidence as RetireEvidence.Contradicted).confidence, 1e-9)
        assertTrue(h[human.id]!!.status is ClaimStatus.Suspect)
        assertTrue(result.entries.none { it.op is KnowledgeOp.Retire && (it.op as KnowledgeOp.Retire).id == human.id })
        assertTrue(result.warnings.any { weak.id in it })
        assertTrue(result.audit.isEmpty())
    }

    @Test fun `a re-run with unchanged anchors skips the worker and a changed anchor is re-verified`() = runBlocking<Unit> {
        lateinit var h: DistillHarness
        val model = FakeDistillModel(script = { area ->
            val known = h.active(area)
            if (known.isEmpty()) listOf(proposeCall("The lexer is a hand-written state machine.", "src/a/Lexer.kt"))
            else known.map { idCall(DistillToolNames.CONFIRM_CLAIM, it.id) }
        })
        h = DistillHarness(model)
        h.present("src/a/Lexer.kt")
        val partitions = listOf(partition("src/a", "src/a/Lexer.kt"))
        h.run(partitions)
        assertEquals(listOf("src/a"), model.workerTurns)

        val again = h.run(partitions)
        assertEquals(listOf("src/a"), model.workerTurns)
        assertEquals(1, again.skipped)
        assertEquals(0, again.submitted)

        h.present("src/a/Lexer.kt", fingerprint = "fp2")
        val third = h.run(partitions)
        assertEquals(2, model.workerTurns.size)
        val claim = h.active("src/a").single()
        assertEquals(ClaimStatus.Live, claim.status)
        assertEquals("fp2", claim.anchors.single().fingerprint)
        assertTrue(third.entries.any { it.op is KnowledgeOp.MarkSuspect })
        assertTrue(third.entries.any { it.op is KnowledgeOp.Confirm && it.verdict.accepted })
        assertTrue(third.audit.isEmpty())
    }

    @Test fun `gone anchors retire generated claims, mark human claims suspect and moved anchors are reanchored`() = runBlocking<Unit> {
        val h = DistillHarness()
        h.present("src/a/A.kt")
        h.anchors["src/a/M.kt"] = AnchorState.Moved("src/a/N.kt", "fp1")
        val generated = h.seed("The old loader reads manifests.", "src/a", "src/a/Old.kt")
        val human = h.seed("Loaders must stay side-effect free.", "src/a", "src/a/Gone.kt", origin = ClaimOrigin.HUMAN)
        val moved = h.seed("The mapper converts rows to records.", "src/a", "src/a/M.kt")
        val result = h.run(listOf(partition("src/a", "src/a/A.kt")))

        val retired = h[generated.id]!!.status as ClaimStatus.Retired
        assertEquals(RetireEvidence.AnchorsGone(listOf("src/a/Old.kt")), retired.evidence)
        assertTrue(h[human.id]!!.status is ClaimStatus.Suspect)
        assertEquals("src/a/N.kt", h[moved.id]!!.anchors.single().ref)
        assertEquals(ClaimStatus.Live, h[moved.id]!!.status)
        assertTrue(result.audit.isEmpty())
    }

    @Test fun `lifting adds a parent claim and keeps the children`() = runBlocking<Unit> {
        val model = FakeDistillModel(
            lift = { """{"level": "MODULE", "text": "Every module reports errors through the shared notifier."}""" },
            script = { area ->
                when (area) {
                    "src/a" -> listOf(proposeCall("Errors are reported through the shared notifier.", "src/a/A.kt"))
                    "src/b" -> listOf(proposeCall("Errors are reported through the shared notifier service.", "src/b/B.kt"))
                    else -> emptyList()
                }
            })
        val h = DistillHarness(model, judgment = { kind, fields ->
            if (kind == DistillJudgment.SAME && fields["claim_a"]!!.startsWith("Errors") && fields["claim_b"]!!.startsWith("Errors")) 0.9
            else defaultJudgment(kind)
        })
        h.present("src/a/A.kt", "src/b/B.kt")
        val partitions = listOf(partition("src/a", "src/a/A.kt"), partition("src/b", "src/b/B.kt"), partition("src", parent = null))
        val result = h.run(partitions)

        val parent = h.active("src").single()
        assertEquals(ClaimLevel.MODULE, parent.level)
        assertTrue(DistillOrganizer.LIFTED_TAG in parent.tags)
        assertEquals(setOf("src/a/A.kt", "src/b/B.kt"), parent.anchors.map { it.ref }.toSet())
        assertEquals(1, h.active("src/a").size)
        assertEquals(1, h.active("src/b").size)
        assertEquals(1, result.lifted)
        assertTrue(result.entries.none { it.op is KnowledgeOp.Merge })
        assertTrue(result.audit.isEmpty())

        h.run(partitions)
        assertEquals(1, model.liftRequests.size)
        assertEquals(1, h.active("src").size)
    }

    @Test fun `cancelling mid-run keeps the claims already submitted`() = runBlocking<Unit> {
        val submitted = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val model = FakeDistillModel(onWorker = { area ->
            if (area == "src/b") { blocked.complete(Unit); awaitCancellation() }
        }, script = { area -> listOf(proposeCall(topics.getValue(area), "$area/Main.kt")) })
        val h = DistillHarness(model)
        h.present("src/a/Main.kt", "src/b/Main.kt")
        val workflow = h.workflow(listOf(partition("src/a", "src/a/Main.kt"), partition("src/b", "src/b/Main.kt")), DistillLimits(parallelism = 1))
        val job = launch {
            workflow.run(listOf("src"), h.progress) { event ->
                h.events += event
                if (event is DistillEvent.Submitted) submitted.complete(Unit)
            }
        }
        submitted.await()
        blocked.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(listOf(topics.getValue("src/a")), h.active("src/a").map { it.text })
        assertTrue(h.active("src/b").isEmpty())
        assertTrue(h.events.any { it is DistillEvent.Warning && it.message.contains("stopped") })
        assertTrue(ratchetAudit(KnowledgeState.EMPTY, h.base.snapshot(), h.base.entries()).isEmpty())
    }

    @Test fun `without decisions dedupe requires matching normalized text and rewrites are rejected`() = runBlocking<Unit> {
        lateinit var existing: Claim
        val calls = AtomicInteger()
        val model = FakeDistillModel(script = {
            if (calls.incrementAndGet() == 1) listOf(
                proposeCall("The parser caches tokens per file.", "src/a/A.kt"),
                proposeCall("Cache entries never expire.", "src/a/A.kt"),
                idCall(DistillToolNames.REVISE_CLAIM, existing.id, "new_text" to "Cache entries expire when idle.", "reason" to "Clearer"),
            ) else listOf(proposeCall("The parser caches tokens per file", "src/a/B.kt"))
        })
        val h = DistillHarness(model, withDecisions = false)
        h.present("src/a/A.kt", "src/a/B.kt", "src/a/C.kt")
        existing = h.seed("Cache entries expire after an idle period.", "src/a", "src/a/C.kt")
        val result = h.run(listOf(partition("src/a", "src/a/A.kt"), partition("src/a", "src/a/B.kt")))

        assertTrue(result.warnings.contains(DistillationWorkflow.NO_DECISIONS))
        assertEquals(1, h.active("src/a").count { it.text.contains("parser", ignoreCase = true) })
        assertEquals(1, result.duplicates)
        assertTrue(result.entries.none { it.op is KnowledgeOp.Contradict })
        val rewrite = result.entries.single { it.op is KnowledgeOp.Rewrite }
        assertFalse(rewrite.verdict.accepted)
        assertEquals(result.rejected, h.events.count { it is DistillEvent.Rejected })
        assertTrue(result.rejected >= 1)
        assertTrue(h[existing.id]!!.status is ClaimStatus.Suspect)
        assertTrue(result.audit.isEmpty())
    }

    @Test fun `a failing worker is reported and its proposals are still organized`() = runBlocking<Unit> {
        val model = FakeDistillModel(script = { listOf(proposeCall("Tokens carry their source span.", "src/a/A.kt")) })
        val h = DistillHarness(model)
        h.present("src/a/A.kt")
        val failing = DistillationWorkflow(h.base, { listOf(partition("src/a", "src/a/A.kt")) }, sourceEvidence, FakeAnchorResolver(h.anchors),
            { request -> if (request.messages.size > 2) throw java.io.IOException("server down") else model.complete(request) }, h.decide)
        val result = failing.run(listOf("src"), h.progress) { h.events += it }

        assertNotNull(h.events.filterIsInstance<DistillEvent.PartitionFinished>().single().error)
        assertEquals(1, h.active("src/a").size)
        assertTrue(result.warnings.any { it.contains("failed") })
    }
}
