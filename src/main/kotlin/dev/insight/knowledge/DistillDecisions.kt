package dev.insight.knowledge

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import kotlinx.coroutines.CancellationException

/** Kinds of distillation judgments; each is the prefix of its question keys, such as `same_3`. */
enum class DistillJudgment(val key: String) { SAME("same"), CONTRADICT("contradict"), SUPPORTS("supports"), REFUTES("refutes"), ENTAILS("entails") }

/**
 * Batched Noul judgments for the organizer. Each method returns one probability per input, in order, or null where the
 * request failed or the answer is missing; failures are reported to [warn]. Cancellation propagates.
 */
internal class DistillJudge(
    private val decide: DecisionFn,
    private val batchSize: Int,
    private val warn: (String) -> Unit,
) {
    var requests: Int = 0
        private set

    /** Do the two claims state the same fact? */
    suspend fun same(pairs: List<Pair<String, String>>): List<Double?> = ask(DistillJudgment.SAME, pairs.map { (a, b) ->
        question(DistillPrompts.SAME_QUESTION, "claim_a" to a, "claim_b" to b,
            yes = "Both claims state the same fact about the code; neither adds a substantive fact.",
            no = "The claims state different facts, or one adds a substantive fact the other lacks.")
    })

    /** Can the two claims not both be true of the same code? */
    suspend fun contradict(pairs: List<Pair<String, String>>): List<Double?> = ask(DistillJudgment.CONTRADICT, pairs.map { (a, b) ->
        question(DistillPrompts.CONTRADICT_QUESTION, "claim_a" to a, "claim_b" to b,
            yes = "The claims cannot both be true of the same code.", no = "Both claims can be true at once.")
    })

    /** Does [evidence] support [claim]? */
    suspend fun supports(claim: String, evidence: String): Double? = ask(DistillJudgment.SUPPORTS, listOf(
        question(DistillPrompts.SUPPORTS_QUESTION, "claim" to claim, "evidence" to evidence,
            yes = "The evidence shows the claim holds.", no = "The evidence does not show the claim, or contradicts it."),
    )).single()

    /** Does [evidence] contradict [claim]? The answer is the confidence of a refutation. */
    suspend fun refutes(claim: String, evidence: String): Double? = ask(DistillJudgment.REFUTES, listOf(
        question(DistillPrompts.REFUTES_QUESTION, "claim" to claim, "evidence" to evidence,
            yes = "The evidence shows the claim is false for this code.", no = "The evidence is consistent with the claim or does not decide it."),
    )).single()

    /** Does [premise] (child claims) imply [hypothesis] (a lifted claim)? */
    suspend fun entails(premise: String, hypothesis: String): Double? = ask(DistillJudgment.ENTAILS, listOf(
        question(DistillPrompts.ENTAILS_QUESTION, "premise" to premise, "hypothesis" to hypothesis,
            yes = "The premise supports everything the hypothesis says.", no = "The hypothesis says something the premise does not support."),
    )).single()

    private suspend fun ask(kind: DistillJudgment, questions: List<DecisionQuestion.Noul>): List<Double?> {
        val answers = arrayOfNulls<Double>(questions.size)
        questions.indices.chunked(batchSize).forEach { chunk ->
            val keyed = chunk.associate { i -> "${kind.key}_$i" to i }
            val request = DecisionRequest(JsonObject().apply { addProperty("task", DistillPrompts.DECISION_TASK) },
                keyed.entries.associate { (key, i) -> key to questions[i] })
            requests++
            val response = try {
                decide(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("Decisions failed for ${kind.key} checks: ${e.message ?: e.javaClass.simpleName}")
                return@forEach
            }
            keyed.forEach { (key, i) -> answers[i] = (response.answers[key] as? DecisionAnswer.Noul)?.noul }
        }
        return answers.toList()
    }

    private fun question(text: String, vararg fields: Pair<String, String>, yes: String, no: String) = DecisionQuestion.Noul(
        JsonObject().apply {
            addProperty("question", text)
            fields.forEach { (name, value) -> addProperty(name, value) }
        },
        mapOf(true to JsonPrimitive(yes), false to JsonPrimitive(no)),
    )
}
