package dev.insight.diff.walk

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import dev.insight.diff.ReviewFile
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmToolCall

internal fun reviewFile(path: String, patch: String, status: String = "modified", before: String? = null, after: String? = null,
                        previousPath: String? = null) = ReviewFile(path, "/p/$path", previousPath, status, patch, path, before, after)

internal fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

internal fun toolCall(name: String, arguments: String) =
    Completion("", toolCalls = listOf(LlmToolCall(null, name, json(arguments))))

/** A diff where `loadUser` becomes `fetchUser`: the declaration, two call sites and a test, plus unrelated changes. */
internal object RenameFixture {
    val user = reviewFile("src/User.kt",
        "@@ -1,3 +1,3 @@ class Users\n class Users {\n-    fun loadUser(id: Int) = db.get(id)\n+    fun fetchUser(id: Int) = db.get(id)\n }\n" +
            "@@ -20,3 +20,4 @@ object Cache\n val cache = mutableMapOf<Int, User>()\n+val ttl = 60\n fun clear() = cache.clear()\n // end\n",
        before = "class Users {\n    fun loadUser(id: Int) = db.get(id)\n}\n", after = "class Users {\n    fun fetchUser(id: Int) = db.get(id)\n}\n")
    val a = reviewFile("src/A.kt", "@@ -5,3 +5,3 @@ fun a\n fun a() {\n-    users.loadUser(1)\n+    users.fetchUser(1)\n }\n")
    val b = reviewFile("src/B.kt", "@@ -7,3 +7,3 @@ fun b\n fun b() {\n-    users.loadUser(2)\n+    users.fetchUser(2)\n }\n")
    val test = reviewFile("src/test/UserTest.kt", "@@ -1,2 +1,2 @@\n-    assertEquals(u, users.loadUser(1))\n+    assertEquals(u, users.fetchUser(1))\n")
    val logo = reviewFile("images/logo.png", "[Diff content unavailable: binary, unreadable, or over the text size limit.]\n")
    val moved = reviewFile("src/New.kt", "", status = "renamed", previousPath = "src/Old.kt")
    val files = listOf(user, a, b, test, logo, moved)
    val hunkIds = walkHunks(files).map { it.id }
}

/** Answers decisions requests with [answer] for each question id, recording requests. */
internal class FakeDecisions(private val answer: (String, DecisionQuestion, DecisionRequest) -> DecisionAnswer?) {
    val requests = mutableListOf<DecisionRequest>()
    suspend fun decide(request: DecisionRequest): DecisionResponse {
        requests += request
        val answers = request.questions.mapNotNull { (id, question) -> answer(id, question, request)?.let { id to it } }.toMap()
        return DecisionResponse("fake", answers, DecisionUsage(0, 0))
    }
}

internal fun choice(value: String) = DecisionAnswer.Choice(value, mapOf(value to 1.0), 1.0)
internal fun noul(value: Double) = DecisionAnswer.Noul(value)

/**
 * A fake model with separate scripts for main turns (requests offering record_step) and subagent runs. Each script is
 * consumed in order; when it runs out, [fallback] answers. Every request is recorded.
 */
internal class RoutedModel(
    main: List<Completion> = emptyList(),
    sub: List<Completion> = emptyList(),
    private val fallback: suspend (CompletionRequest) -> Completion = { Completion("Done.") },
) {
    private val mainScript = ArrayDeque(main)
    private val subScript = ArrayDeque(sub)
    val mainRequests = mutableListOf<CompletionRequest>()
    val subRequests = mutableListOf<CompletionRequest>()

    suspend fun complete(request: CompletionRequest): Completion {
        val isMain = request.tools.any { it.name == WalkToolNames.RECORD_STEP }
        (if (isMain) mainRequests else subRequests) += request
        val script = if (isMain) mainScript else subScript
        return script.removeFirstOrNull() ?: fallback(request)
    }

    /** The first user message of each main turn: the turn's seed message. */
    val turnMessages: List<String> get() = mainRequests.map { it.messages.first { m -> m.role == LlmRole.USER }.content }.distinct()
}
