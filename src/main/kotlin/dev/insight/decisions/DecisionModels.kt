package dev.insight.decisions

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive

/** State and descriptions accept JSON strings, objects, or arrays. */
data class DecisionRequest(
    val state: JsonElement,
    val questions: Map<String, DecisionQuestion>,
    val model: String = "jev-latest",
) {
    constructor(state: String, questions: Map<String, DecisionQuestion>, model: String = "jev-latest") :
        this(JsonPrimitive(state), questions, model)
}

sealed interface DecisionQuestion {
    val instructions: JsonElement

    data class Noul(
        override val instructions: JsonElement,
        val criteria: Map<Boolean, JsonElement>? = null,
    ) : DecisionQuestion {
        constructor(instructions: String, criteria: Map<Boolean, JsonElement>? = null) :
            this(JsonPrimitive(instructions), criteria)
    }

    /** Null descriptions are allowed when the option name is sufficient. */
    data class Choice(
        override val instructions: JsonElement,
        val criteria: Map<String, JsonElement?>,
    ) : DecisionQuestion {
        constructor(instructions: String, criteria: Map<String, JsonElement?>) :
            this(JsonPrimitive(instructions), criteria)
    }

    data class Score(
        override val instructions: JsonElement,
        val criteria: List<JsonElement>,
    ) : DecisionQuestion {
        constructor(instructions: String, criteria: List<JsonElement>) :
            this(JsonPrimitive(instructions), criteria)
    }
}

sealed interface DecisionAnswer {
    data class Noul(val noul: Double) : DecisionAnswer
    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        val confidence: Double,
    ) : DecisionAnswer
    data class Score(
        val score: Double,
        val legend: Map<String, JsonElement>,
        val probabilities: Map<String, Double>,
        val confidence: Double,
    ) : DecisionAnswer
}

data class DecisionUsage(val inputTokens: Long, val outputTokens: Long)
data class DecisionResponse(
    val model: String,
    val answers: Map<String, DecisionAnswer>,
    val usage: DecisionUsage,
)
