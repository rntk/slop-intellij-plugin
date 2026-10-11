package dev.insight.decisions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/** A successful HTTP response did not match the requested decision contract. */
class DecisionProtocolException(message: String) : IOException(message)

internal object DecisionJson {
    fun encode(request: DecisionRequest): String {
        require(request.model.isNotBlank()) { "Model must not be blank" }
        require(request.questions.isNotEmpty()) { "At least one question is required" }
        description(request.state)
        val questions = JsonObject()
        request.questions.forEach { (id, question) ->
            require(id.isNotBlank()) { "Question IDs must not be blank" }
            description(question.instructions)
            val json = JsonObject().apply { add("instructions", question.instructions) }
            when (question) {
                is DecisionQuestion.Noul -> {
                    json.addProperty("type", "noul")
                    question.criteria?.let { criteria ->
                        json.add("criteria", JsonObject().apply {
                            criteria.forEach { (key, value) -> description(value); add(key.toString(), value) }
                        })
                    }
                }
                is DecisionQuestion.Choice -> {
                    require(question.criteria.size in 1..255) { "Choice requires 1–255 options" }
                    json.addProperty("type", "choice")
                    json.add("criteria", JsonObject().apply {
                        question.criteria.forEach { (key, value) ->
                            if (value != null && !value.isJsonNull) description(value)
                            add(key, value ?: JsonNull.INSTANCE)
                        }
                    })
                }
                is DecisionQuestion.Score -> {
                    require(question.criteria.size in 2..10) { "Score requires 2–10 levels" }
                    json.addProperty("type", "score")
                    json.add("criteria", JsonArray().apply {
                        question.criteria.forEach { description(it); add(it) }
                    })
                }
            }
            questions.add(id, json)
        }
        return JsonObject().apply {
            add("state", request.state)
            addProperty("model", request.model)
            add("questions", questions)
        }.toString()
    }

    fun decode(body: String, questions: Map<String, DecisionQuestion>): DecisionResponse = try {
        val root = JsonParser.parseString(body).asJsonObject
        val answers = root.getAsJsonObject("answers")
        require(answers.keySet() == questions.keys) { "Answer IDs do not match question IDs" }
        val parsed = questions.mapValues { (id, question) ->
            val answer = answers.getAsJsonObject(id)
            when (question) {
                is DecisionQuestion.Noul -> {
                    require(answer.string("type") == "noul") { "Answer type mismatch" }
                    DecisionAnswer.Noul(answer.probability("noul"))
                }
                is DecisionQuestion.Choice -> {
                    require(answer.string("type") == "choice") { "Answer type mismatch" }
                    val choice = answer.string("choice")
                    val probabilities = answer.probabilities()
                    require(choice in question.criteria && probabilities.keys == question.criteria.keys) {
                        "Choice options do not match criteria"
                    }
                    DecisionAnswer.Choice(choice, probabilities, answer.probability("confidence"))
                }
                is DecisionQuestion.Score -> {
                    require(answer.string("type") == "score") { "Answer type mismatch" }
                    val score = answer.number("score")
                    val legend = answer.getAsJsonObject("legend").entrySet().associate { it.key to it.value }
                    val probabilities = answer.probabilities()
                    val levels = question.criteria.indices.map { it.toString() }.toSet()
                    require(score in 0.0..question.criteria.lastIndex.toDouble()) { "Score outside rubric" }
                    require(legend.keys == levels && probabilities.keys == levels) { "Score levels do not match criteria" }
                    DecisionAnswer.Score(score, legend, probabilities, answer.probability("confidence"))
                }
            }
        }
        val usage = root.getAsJsonObject("usage")
        DecisionResponse(root.string("model"), parsed, DecisionUsage(usage.tokens("input_tokens"), usage.tokens("output_tokens")))
    } catch (_: RuntimeException) {
        // Do not include response content: it may contain source text or credentials.
        throw DecisionProtocolException("Invalid decisions API response")
    }

    private fun description(value: JsonElement) {
        require(value.isJsonObject || value.isJsonArray || (value.isJsonPrimitive && value.asJsonPrimitive.isString)) {
            "State and descriptions must be JSON strings, objects, or arrays"
        }
    }

    private fun JsonObject.string(key: String): String {
        val value = getAsJsonPrimitive(key)
        require(value.isString) { "Expected string field" }
        return value.asString
    }

    private fun JsonObject.number(key: String): Double {
        val value = getAsJsonPrimitive(key)
        require(value.isNumber) { "Expected numeric field" }
        return value.asDouble.also { require(it.isFinite()) { "Expected finite number" } }
    }

    private fun JsonObject.probability(key: String): Double = number(key).also {
        require(it in 0.0..1.0) { "Probability outside 0–1" }
    }

    private fun JsonObject.probabilities(): Map<String, Double> = getAsJsonObject("probabilities").let { values ->
        values.keySet().associateWith { values.probability(it) }
    }

    private fun JsonObject.tokens(key: String): Long {
        val value = getAsJsonPrimitive(key)
        require(value.isNumber) { "Expected token count" }
        return value.asBigDecimal.longValueExact().also { require(it >= 0) { "Negative token count" } }
    }
}
