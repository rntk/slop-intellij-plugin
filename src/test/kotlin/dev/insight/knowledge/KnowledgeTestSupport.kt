package dev.insight.knowledge

import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage

internal fun claim(
    id: String,
    text: String = "Claim $id holds.",
    area: String = "src",
    origin: ClaimOrigin = ClaimOrigin.GENERATED,
    level: ClaimLevel = ClaimLevel.FILE,
) = Claim(id, text, level, origin, area, listOf(Anchor(AnchorKind.FILE, "$area/$id.kt", "fp-$id")))

internal fun stateOf(vararg claims: Claim) = KnowledgeState(claims.associateBy { it.id })

/** Answers every Noul question with [answer] (by question key); records requests. */
internal class FakeEntailment(private val answer: (String) -> Double? = { 0.95 }) {
    val requests = mutableListOf<DecisionRequest>()
    suspend fun decide(request: DecisionRequest): DecisionResponse {
        requests += request
        val answers = request.questions.mapNotNull { (key, question) ->
            if (question !is DecisionQuestion.Noul) null else answer(key)?.let { key to DecisionAnswer.Noul(it) }
        }.toMap()
        return DecisionResponse("fake", answers, DecisionUsage(0, 0))
    }
}

/** Resolves anchors from a fixed map; unknown refs are gone. */
internal class FakeAnchorResolver(private val states: Map<String, AnchorState>) : AnchorResolver {
    override suspend fun resolve(anchor: Anchor): AnchorState = states[anchor.ref] ?: AnchorState.Gone
}
