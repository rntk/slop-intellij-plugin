package dev.insight.knowledge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmToolCall
import java.util.Collections

/** A propose_claim call anchored to FILE [files]. */
internal fun proposeCall(text: String, vararg files: String, level: String = "file") = LlmToolCall(null, DistillToolNames.PROPOSE_CLAIM,
    JsonObject().apply {
        addProperty("text", text)
        addProperty("level", level)
        add("anchors", JsonArray().apply {
            files.forEach { add(JsonObject().apply { addProperty("kind", "file"); addProperty("ref", it) }) }
        })
    })

internal fun idCall(name: String, id: String, vararg fields: Pair<String, String>) = LlmToolCall(null, name, JsonObject().apply {
    addProperty("id", id)
    fields.forEach { (key, value) -> addProperty(key, value) }
})

internal fun partition(area: String, vararg files: String, parent: String? = area.substringBeforeLast('/', "").takeIf { area.isNotEmpty() }) =
    KnowledgePartition(area, files.toList(), parent)

/**
 * A fake model for distillation. Worker turns (the worker system prompt) get [script] for their area on their first
 * request and "Done." afterwards; [onWorker] runs first (to delay or block). Lift requests are answered by [lift].
 */
internal class FakeDistillModel(
    private val lift: (CompletionRequest) -> String = { """{"text": null}""" },
    private val onWorker: suspend (String) -> Unit = {},
    private val script: (String) -> List<LlmToolCall> = { emptyList() },
) {
    /** Areas of worker turns, once per turn. */
    val workerTurns: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val liftRequests: MutableList<CompletionRequest> = Collections.synchronizedList(mutableListOf())

    suspend fun complete(request: CompletionRequest): Completion {
        val system = request.messages.firstOrNull { it.role == LlmRole.SYSTEM }?.content
        if (system == DistillPrompts.LIFT_SYSTEM) {
            liftRequests += request
            return Completion(lift(request))
        }
        val user = request.messages.first { it.role == LlmRole.USER }.content
        val area = user.lineSequence().first().removePrefix("Area: ").let { if (it == "(project root)") "" else it }
        if (request.messages.size > 2) return Completion("Done.")
        workerTurns += area
        onWorker(area)
        val calls = script(area)
        return if (calls.isEmpty()) Completion("Nothing to add.") else Completion("", toolCalls = calls)
    }
}

/** Answers distillation judgments with [answer] (kind and the question's fields); records requests. */
internal class FakeDistillDecisions(private val answer: (DistillJudgment, Map<String, String>) -> Double?) {
    val requests: MutableList<DecisionRequest> = Collections.synchronizedList(mutableListOf())

    suspend fun decide(request: DecisionRequest): DecisionResponse {
        requests += request
        val answers = request.questions.mapNotNull { (key, question) ->
            val kind = DistillJudgment.entries.firstOrNull { key.startsWith(it.key + "_") }
            val fields = ((question as? DecisionQuestion.Noul)?.instructions as? JsonObject)?.entrySet()
                ?.associate { it.key to it.value.asString }.orEmpty()
            if (kind == null) answerEntailment(key) else answer(kind, fields)?.let { key to DecisionAnswer.Noul(it) }
        }.toMap()
        return DecisionResponse("fake", answers, DecisionUsage(0, 0))
    }

    /** The guard's own entailment questions (rewrites) are answered yes. */
    private fun answerEntailment(key: String) = key to DecisionAnswer.Noul(0.95)

    fun count(kind: DistillJudgment) = requests.count { r -> r.questions.keys.any { it.startsWith(kind.key + "_") } }
}

/** Default judgments: nothing is the same or contradictory, evidence supports claims, refutations are unconvincing. */
internal fun defaultJudgment(kind: DistillJudgment): Double = when (kind) {
    DistillJudgment.SAME, DistillJudgment.CONTRADICT, DistillJudgment.REFUTES -> 0.05
    DistillJudgment.SUPPORTS, DistillJudgment.ENTAILS -> 0.95
}

internal val sourceEvidence = EvidenceProvider { anchors, _ -> anchors.map { Evidence(it.ref, "source of ${it.ref}", it.ref) } }

internal class RecordingProgress : KnowledgeProgress {
    val reports: MutableList<Triple<String, Int, Int>> = Collections.synchronizedList(mutableListOf())
    override fun report(phase: String, done: Int, total: Int) { reports += Triple(phase, done, total) }
}

/** A knowledge base, resolver and fakes wired into [workflow]. Anchors resolve from [anchors]; unknown refs are gone. */
internal class DistillHarness(
    val model: FakeDistillModel = FakeDistillModel(),
    judgment: (DistillJudgment, Map<String, String>) -> Double? = { kind, _ -> defaultJudgment(kind) },
    withDecisions: Boolean = true,
    val anchors: MutableMap<String, AnchorState> = mutableMapOf(),
) {
    val decisions = FakeDistillDecisions(judgment)
    val decide = if (withDecisions) decisions::decide else null
    val store = InMemoryKnowledgeStore()
    val base = KnowledgeBase(store, RatchetGuard(decide))
    val events: MutableList<DistillEvent> = Collections.synchronizedList(mutableListOf())
    val progress = RecordingProgress()

    fun present(vararg refs: String, fingerprint: String = "fp1") = refs.forEach { anchors[it] = AnchorState.Present(fingerprint) }

    fun workflow(partitions: List<KnowledgePartition>, limits: DistillLimits = DistillLimits()) = DistillationWorkflow(
        base, { partitions }, sourceEvidence, FakeAnchorResolver(anchors), model::complete, decide, limits = limits)

    suspend fun run(partitions: List<KnowledgePartition>, limits: DistillLimits = DistillLimits(), scope: List<String> = listOf("src")) =
        workflow(partitions, limits).run(scope, progress) { events += it }

    /** Adds a claim as the user, anchored to FILE [file] with [fingerprint]. */
    suspend fun seed(text: String, area: String, file: String, origin: ClaimOrigin = ClaimOrigin.GENERATED, fingerprint: String = "fp1"): Claim {
        val claim = Claim(base.snapshot().newId(text, area), text, ClaimLevel.FILE, origin, area, listOf(Anchor(AnchorKind.FILE, file, fingerprint)))
        check(base.submit(KnowledgeOp.Add(claim), RatchetGuard.USER).verdict.accepted)
        return claim
    }

    fun active(area: String) = base.snapshot().inArea(area)
    operator fun get(id: String) = base.snapshot()[id]
}
