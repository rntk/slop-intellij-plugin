package dev.insight.codereview

import dev.insight.review.summarize
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmRole
import dev.insight.review.ReviewSummaries
import dev.insight.review.reviewJson

/** A code file with its parts, as the loader builds it. */
internal fun codeFile(path: String, text: String) = CodeFile(path, "/project/$path", text, chunks = chunkSource(text))

internal fun codeSnapshot(vararg files: CodeFile, scope: String = "") =
    CodeSnapshot(CodeScope(scope, "/project/$scope".trimEnd('/')), files.sortedBy { it.path })

/** [count] top-level functions of about 330 characters each; [edited] changes the body of one of them. */
internal fun largeSource(count: Int = 120, edited: Int? = null): String = (1..count).joinToString("") { i ->
    "/** Computes step $i. */\nfun step$i(x: Int): Int {\n" +
        (1..8).joinToString("") { "    val v$it = x * $it + $i${if (i == edited) " + 1" else ""} // keeps the body long\n" } +
        "    return x\n}\n\n"
}

/** Writes every missing summary bottom up as "<kind> <location>", like a model that answers each wave. */
internal fun fillAll(summaries: ReviewSummaries, save: ((Map<Int, String>) -> ReviewSummaries)? = null): ReviewSummaries {
    var current = summaries
    while (true) {
        val ready = current.ready()
        if (ready.isEmpty()) return current
        ready.chunked(ReviewSummaries.MAX_PER_CALL).forEach { batch ->
            current = (save ?: { entries -> current.summarize(entries) })(batch.associateWith { "${current.tree[it].kind.id} ${current.tree[it].location}" })
        }
    }
}

/** Answers every summary request with "<kind> <location>" for each node. */
internal class SummaryModel {
    val requests = mutableListOf<CompletionRequest>()
    fun nodes(request: CompletionRequest): List<JsonObject> =
        JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content).asJsonObject["nodes"]
            .asJsonArray.map { it.asJsonObject }

    fun complete(request: CompletionRequest): Completion {
        synchronized(requests) { requests += request }
        return Completion(reviewJson.toJson(mapOf("summaries" to nodes(request).map {
            mapOf("id" to it["id"].asInt, "segments" to listOf(mapOf("text" to "${it["kind"].asString} ${it["location"].asString}", "from" to listOf(it["start_line"]?.asString ?: "1"))))
        })))
    }
}
