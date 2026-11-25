package dev.insight.knowledge

import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest

internal fun docClaim(
    id: String,
    text: String = "Claim $id describes the flow.",
    area: String = "src/app",
    level: ClaimLevel = ClaimLevel.FLOW,
    anchors: List<Anchor> = listOf(Anchor(AnchorKind.FILE, "$area/$id.kt")),
    tags: Set<String> = emptySet(),
    stability: Double? = null,
) = Claim(id, text, level, ClaimOrigin.GENERATED, area, anchors, tags = tags, stability = stability)

/** Answers classifier questions by claim id: [detail] and [volatile] ids get 0.9 else 0.1; stability from [stability] (default 4). */
internal class FakeClassifierDecisions(
    private val detail: Set<String> = emptySet(),
    private val volatile: Set<String> = emptySet(),
    private val stability: Map<String, Double> = emptyMap(),
    private val unanswered: Set<String> = emptySet(),
    private val failWith: Exception? = null,
) {
    val requests = mutableListOf<DecisionRequest>()

    suspend fun decide(request: DecisionRequest): DecisionResponse {
        requests += request
        failWith?.let { throw it }
        val answers = LinkedHashMap<String, DecisionAnswer>()
        request.questions.forEach { (key, question) ->
            val id = key.substringAfter(':')
            if (id in unanswered) return@forEach
            answers[key] = when (question) {
                is DecisionQuestion.Noul ->
                    DecisionAnswer.Noul(if (key.startsWith("detail:") && id in detail || key.startsWith("volatile:") && id in volatile) 0.9 else 0.1)
                is DecisionQuestion.Score -> DecisionAnswer.Score(stability[id] ?: 4.0, emptyMap(), emptyMap(), 0.8)
                is DecisionQuestion.Choice -> error("unexpected choice question")
            }
        }
        return DecisionResponse("fake", answers, DecisionUsage(0, 0))
    }
}

/** Replies with [reply]; records requests. */
internal class FakeCompletion(private val reply: (CompletionRequest) -> String) {
    val requests = mutableListOf<CompletionRequest>()
    suspend fun complete(request: CompletionRequest): Completion {
        requests += request
        return Completion(reply(request))
    }
}
