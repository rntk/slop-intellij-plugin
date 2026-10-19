package dev.insight.codereview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.diff.ChangeSummaries
import dev.insight.diff.reviewJson
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmRole

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
internal fun fillAll(summaries: ChangeSummaries, save: ((Map<Int, String>) -> ChangeSummaries)? = null): ChangeSummaries {
    var current = summaries
    while (true) {
        val ready = current.ready()
        if (ready.isEmpty()) return current
        ready.chunked(ChangeSummaries.MAX_PER_CALL).forEach { batch ->
            current = (save ?: current::summarize)(batch.associateWith { "${current.tree[it].kind.id} ${current.tree[it].location}" })
        }
    }
}

/** Answers every summary request with "<kind> <location>" for each node. */
internal class SummaryModel {
    val requests = mutableListOf<CompletionRequest>()
    fun nodes(request: CompletionRequest): List<JsonObject> =
        JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content).asJsonObject["nodes"]
            .asJsonArray.map { it.asJsonObject }

    suspend fun complete(request: CompletionRequest): Completion {
        synchronized(requests) { requests += request }
        return Completion(reviewJson.toJson(mapOf("summaries" to nodes(request).map {
            mapOf("id" to it["id"].asInt, "summary" to "${it["kind"].asString} ${it["location"].asString}")
        })))
    }
}
