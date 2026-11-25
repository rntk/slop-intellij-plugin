package dev.insight.knowledge

import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import dev.insight.llm.Completion
import dev.insight.llm.CompletionFn
import java.util.concurrent.ConcurrentHashMap

internal const val DEMO_DOC = "flows/demo.md"

internal val DEMO_TEXT = """
    # Demo flow

    The demo parses input with [Parser.kt](../src/Parser.kt). It never touches the disk.

    Entry points for further exploration:

    - [Parser.kt](../src/Parser.kt) — parsing
""".trimIndent()

internal fun driftBase(): KnowledgeBase = KnowledgeBase(InMemoryKnowledgeStore(), RatchetGuard(null))

/** Anchors a statement to every `(../path)` link target as a FILE anchor `path`; statements without links have none. */
internal class LinkAnchorer : ClaimAnchorer {
    private val link = Regex("\\]\\(\\.\\./([^)]+)\\)")
    override suspend fun anchors(text: String, docPath: String?): List<Anchor> =
        link.findAll(text).map { Anchor(AnchorKind.FILE, it.groupValues[1]) }.distinct().toList()
}

/** Resolves refs from a mutable map: absent refs are gone. */
internal class MapResolver(vararg initial: Pair<String, AnchorState>) : AnchorResolver {
    val states = ConcurrentHashMap<String, AnchorState>().apply { putAll(initial) }
    override suspend fun resolve(anchor: Anchor): AnchorState = states[anchor.ref] ?: AnchorState.Gone
}

internal class RecordingEvidence(private val empty: Boolean = false) : EvidenceProvider {
    val calls = java.util.concurrent.atomic.AtomicInteger()
    override suspend fun evidence(anchors: List<Anchor>, maxChars: Int): List<Evidence> {
        calls.incrementAndGet()
        if (empty) return emptyList()
        return anchors.map { Evidence(it.ref, "content of ${it.ref}".take(maxChars), it.ref) }
    }
}

/**
 * A decisions fake: [accuracy] gives the answer to the accuracy question from the statement, [detail] the chosen level
 * (with confidence 0.9). Requests are recorded.
 */
internal class FakeDrift(
    private val accuracy: (String) -> Double = { 0.95 },
    private val detail: (String) -> String = { "durable" },
) {
    val requests = java.util.Collections.synchronizedList(mutableListOf<DecisionRequest>())
    val fn: DecisionFn = { request ->
        requests += request
        val statement = request.state.asJsonObject.get("statement").asString
        val answers = request.questions.mapValues { (key, q) ->
            when (q) {
                is DecisionQuestion.Noul -> DecisionAnswer.Noul(if (key == "accurate") accuracy(statement) else 0.5)
                is DecisionQuestion.Choice -> DecisionAnswer.Choice(detail(statement), mapOf(detail(statement) to 0.9), 0.9)
                is DecisionQuestion.Score -> DecisionAnswer.Score(0.0, emptyMap(), emptyMap(), 0.0)
            }
        }
        DecisionResponse("fake", answers, DecisionUsage(0, 0))
    }
}

internal fun completion(content: String): CompletionFn = { Completion(content) }

internal class NoProgress : KnowledgeProgress {
    val phases = java.util.Collections.synchronizedList(mutableListOf<String>())
    override fun report(phase: String, done: Int, total: Int) { phases += phase }
}
