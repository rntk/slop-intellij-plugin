package dev.insight.diff.walk

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.logger
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import kotlinx.coroutines.CancellationException

private val LOG = logger<WalkTriage>()

/** What kind of change a hunk is; [priority] orders seeds, so roots and logic are analyzed before mechanical edits. */
enum class HunkKind(val wire: String, val description: String, internal val priority: Double) {
    API_OR_SIGNATURE_CHANGE("api_or_signature_change",
        "changes a declaration other code depends on: a signature, name, type, interface, public constant or data shape", 1.0),
    LOGIC("logic", "changes behavior inside existing code: conditions, calculations, control flow, calls made", 0.8),
    NEW_CODE("new_code", "adds new functions, classes or files", 0.7),
    REMOVAL("removal", "removes code without replacing it", 0.5),
    DOCS_OR_CONFIG("docs_or_config", "documentation, comments, build scripts, configuration or resources", 0.3),
    TESTS("tests", "tests, test fixtures or test data", 0.25),
    RENAME_OR_MECHANICAL("rename_or_mechanical",
        "a mechanical edit repeated elsewhere: adapting a call site or reference to a rename or signature change, a moved import", 0.1),
    FORMATTING_OR_IMPORTS("formatting_or_imports", "only formatting, whitespace or import lines", 0.05);

    companion object {
        fun fromWire(value: String): HunkKind? = entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
    }
}

enum class TriageSource { DECISIONS, HEURISTIC }

/** A hunk's [kind] and the probability ([root], 0 to 1) that other changes depend on it. */
data class HunkTriage(val kind: HunkKind, val root: Double, val source: TriageSource) {
    /** Higher seeds first. */
    val priority: Double get() = root * 0.6 + kind.priority * 0.4
}

/** Triage results with what went wrong, in user-facing words. [requests] counts decisions requests made. */
data class TriageResult(val triage: Map<String, HunkTriage>, val requests: Int, val warnings: List<String>)

/**
 * Classifies hunks cheaply so the walkthrough starts from likely roots. With [decide], hunks go in batches of
 * [batchSize] (each patch cut to [patchChars]) and each hunk gets a kind [DecisionQuestion.Choice] and a root
 * [DecisionQuestion.Noul]; at most [maxRequests] requests are made. Hunks beyond that, in a failed batch, or without
 * [decide] use [heuristicTriage]. A failure is logged and reported as a warning; it never fails the run.
 */
class WalkTriage(
    private val decide: DecisionFn?,
    private val batchSize: Int = 8,
    private val patchChars: Int = 1_200,
    private val maxRequests: Int = 60,
) {
    init { require(batchSize > 0 && patchChars > 0 && maxRequests >= 0) }

    suspend fun classify(hunks: List<WalkHunk>): TriageResult {
        val result = LinkedHashMap<String, HunkTriage>()
        var requests = 0
        var failures = 0
        var lastError: String? = null
        var beyondLimit = 0
        var unanswered = 0
        if (decide != null) for (batch in hunks.chunked(batchSize)) {
            if (requests >= maxRequests) { beyondLimit += batch.size; continue }
            requests++
            try {
                val response = decide.invoke(DecisionRequest(state(batch), questions(batch.size)))
                batch.forEachIndexed { index, hunk ->
                    val kind = (response.answers["${ref(index)}.kind"] as? DecisionAnswer.Choice)?.choice?.let(HunkKind::fromWire)
                    val root = (response.answers["${ref(index)}.root"] as? DecisionAnswer.Noul)?.noul
                    if (kind != null && root != null) result[hunk.id] = HunkTriage(kind, root.coerceIn(0.0, 1.0), TriageSource.DECISIONS)
                    else unanswered++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                lastError = e.message ?: e.javaClass.simpleName
                LOG.warn("Walkthrough triage batch failed", e)
            }
        }
        val fallback = hunks.filter { it.id !in result }
        fallback.forEach { result[it.id] = heuristicTriage(it) }
        val warnings = buildList {
            if (failures > 0) add("Triage failed for $failures of $requests batches ($lastError); those hunks were ordered by heuristics.")
            if (unanswered > 0) add("Decisions gave no usable answer for $unanswered hunks; they were ordered by heuristics.")
            if (beyondLimit > 0) add("$beyondLimit hunks were triaged by heuristics beyond the decisions request limit.")
        }
        return TriageResult(hunks.associate { it.id to result.getValue(it.id) }, requests, warnings)
    }

    private fun ref(index: Int) = "h${index + 1}"

    private fun state(batch: List<WalkHunk>) = JsonObject().apply {
        add("hunks", JsonArray().apply {
            batch.forEachIndexed { index, hunk ->
                add(JsonObject().apply {
                    addProperty("ref", ref(index))
                    addProperty("path", hunk.path)
                    addProperty("file_status", hunk.fileStatus)
                    hunk.previousPath?.let { addProperty("previous_path", it) }
                    addProperty("added_lines", hunk.added)
                    addProperty("removed_lines", hunk.removed)
                    addProperty("patch", trimmedPatch(hunk, patchChars))
                })
            }
        })
    }

    private fun questions(size: Int): Map<String, DecisionQuestion> = buildMap {
        val kinds = HunkKind.entries.associate { it.wire to JsonPrimitive(it.description) as com.google.gson.JsonElement? }
        repeat(size) { index ->
            val ref = ref(index)
            put("$ref.kind", DecisionQuestion.Choice("Which kind of change is the hunk with ref `$ref` in `hunks`?", kinds))
            put("$ref.root", DecisionQuestion.Noul(
                "Is the hunk with ref `$ref` in `hunks` likely a root cause that other changes in the same diff depend on or " +
                    "adapt to, such as a changed signature, a renamed or new declaration, or a changed data shape? " +
                    "Answer no for call sites adapting to another change, tests, formatting and imports."))
        }
    }
}

/** [hunk]'s patch cut at a row boundary to about [chars] characters, with a note when cut. */
internal fun trimmedPatch(hunk: WalkHunk, chars: Int): String {
    if (hunk.patch.length <= chars) return hunk.patch
    val cut = hunk.patch.lastIndexOf('\n', chars).takeIf { it > 0 } ?: chars
    return hunk.patch.substring(0, cut) + "\n[cut: ${hunk.patch.length - cut} more characters]"
}

/** A path-and-content guess at [hunk]'s kind and root probability, used without decisions. */
fun heuristicTriage(hunk: WalkHunk): HunkTriage {
    val path = hunk.path.lowercase()
    val name = path.substringAfterLast('/')
    val rows = hunk.patch.lineSequence().drop(if (hunk.wholeFile) 0 else 1)
    val changed = rows.filter { (it.startsWith("+") || it.startsWith("-")) }.map { it.drop(1).trim() }.toList()
    val meaningful = changed.filter { it.isNotEmpty() }
    val kind = when {
        TEST_DIR.containsMatchIn(path) || TEST_NAME.containsMatchIn(hunk.path.substringAfterLast('/')) -> HunkKind.TESTS
        DOC_NAME.containsMatchIn(name) || path.startsWith("docs/") || "/docs/" in path -> HunkKind.DOCS_OR_CONFIG
        meaningful.isNotEmpty() && meaningful.all { IMPORT.containsMatchIn(it) } -> HunkKind.FORMATTING_OR_IMPORTS
        changed.isNotEmpty() && meaningful.isEmpty() -> HunkKind.FORMATTING_OR_IMPORTS
        hunk.fileStatus == "added" || hunk.fileStatus == "untracked" -> HunkKind.NEW_CODE
        hunk.fileStatus == "deleted" -> HunkKind.REMOVAL
        hunk.wholeFile && changed.isEmpty() -> if (hunk.fileStatus == "renamed") HunkKind.RENAME_OR_MECHANICAL else HunkKind.LOGIC
        hunk.removed > 0 && hunk.added > 0 && meaningful.any { DECLARATION.containsMatchIn(it) } -> HunkKind.API_OR_SIGNATURE_CHANGE
        hunk.added > 0 && hunk.removed == 0 && meaningful.any { DECLARATION.containsMatchIn(it) } -> HunkKind.NEW_CODE
        hunk.added == 0 && hunk.removed > 0 -> HunkKind.REMOVAL
        hunk.added == hunk.removed && hunk.added <= 3 && similarPairs(hunk) -> HunkKind.RENAME_OR_MECHANICAL
        else -> HunkKind.LOGIC
    }
    val root = when (kind) {
        HunkKind.API_OR_SIGNATURE_CHANGE -> 0.8
        HunkKind.NEW_CODE -> 0.6
        HunkKind.LOGIC -> 0.5
        HunkKind.REMOVAL -> 0.4
        HunkKind.DOCS_OR_CONFIG -> 0.2
        HunkKind.TESTS -> 0.15
        HunkKind.RENAME_OR_MECHANICAL -> 0.1
        HunkKind.FORMATTING_OR_IMPORTS -> 0.05
    }
    return HunkTriage(kind, root, TriageSource.HEURISTIC)
}

/** Whether each removed row has an added row of nearly the same shape, as a renamed reference does. */
private fun similarPairs(hunk: WalkHunk): Boolean {
    val rows = hunk.patch.lineSequence().toList()
    val removed = rows.filter { it.startsWith("-") }.map { it.drop(1).trim() }
    val added = rows.filter { it.startsWith("+") }.map { it.drop(1).trim() }
    return removed.zip(added).all { (a, b) ->
        val tokensA = a.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        val tokensB = b.split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        tokensA.size == tokensB.size && tokensA.zip(tokensB).count { it.first != it.second } <= 2
    }
}

/** Seed order: highest [HunkTriage.priority] first, then by path and index; hunks without triage use heuristics. */
fun seedOrder(hunks: List<WalkHunk>, triage: Map<String, HunkTriage>?): List<WalkHunk> = hunks.sortedWith(
    compareByDescending<WalkHunk> { (triage?.get(it.id) ?: heuristicTriage(it)).priority }.thenBy { it.path }.thenBy { it.index })

private val TEST_DIR = Regex("(^|/)(test|tests|testing|spec|__tests__|testdata)/")
private val TEST_NAME = Regex("(Test|Tests|Spec|IT)\\.[A-Za-z0-9]+$|[._-](test|spec)\\.[A-Za-z0-9]+$|^test_")
private val DOC_NAME = Regex("\\.(md|markdown|txt|rst|adoc|xml|json|ya?ml|toml|properties|ini|cfg|conf|gradle|kts|lock)$|^(readme|license|changelog)")
private val IMPORT = Regex("^(import|from\\s+\\S+\\s+import|#include|using|package|require\\(|use\\s)")
private val DECLARATION = Regex("\\b(fun|def|func|function|class|interface|object|enum|struct|trait|type|val|var|const|public|private|protected|internal|override|static)\\b")
private val TOKEN_SPLIT = Regex("[^A-Za-z0-9_]+")

/** Verifier answers keyed by [WalkLink.key]: the probability that the link holds. */
data class LinkVerification(val confidence: Map<LinkKey, Double>, val requests: Int, val warnings: List<String>)

/**
 * Checks recorded links with one [DecisionQuestion.Noul] per link, [batchSize] links per request, at most [maxRequests]
 * requests. Each request's state holds the linked hunks' patches cut to [patchChars]. Unverified links keep a null
 * confidence; failures are logged and reported as warnings.
 */
class WalkLinkVerifier(
    private val decide: DecisionFn,
    private val batchSize: Int = 6,
    private val patchChars: Int = 1_000,
    private val maxRequests: Int = 40,
) {
    init { require(batchSize > 0 && patchChars > 0 && maxRequests >= 0) }

    suspend fun verify(links: List<WalkLink>, hunks: Map<String, WalkHunk>): LinkVerification {
        val confidence = LinkedHashMap<LinkKey, Double>()
        var requests = 0
        var failures = 0
        var lastError: String? = null
        val targets = links.filter { it.from in hunks && it.to in hunks }
        for (batch in targets.chunked(batchSize)) {
            if (requests >= maxRequests) break
            requests++
            try {
                val refs = LinkedHashMap<String, String>()
                batch.forEach { link -> listOf(link.from, link.to).forEach { refs.getOrPut(it) { "h${refs.size + 1}" } } }
                val state = JsonObject().apply {
                    add("hunks", JsonArray().apply {
                        refs.forEach { (id, ref) ->
                            val hunk = hunks.getValue(id)
                            add(JsonObject().apply {
                                addProperty("ref", ref); addProperty("path", hunk.path)
                                addProperty("patch", trimmedPatch(hunk, patchChars))
                            })
                        }
                    })
                    add("links", JsonArray().apply {
                        batch.forEachIndexed { index, link ->
                            add(JsonObject().apply {
                                addProperty("ref", "l${index + 1}"); addProperty("from", refs.getValue(link.from))
                                addProperty("to", refs.getValue(link.to)); addProperty("relation", link.relation.wire)
                                addProperty("reason", link.reason)
                            })
                        }
                    })
                }
                val questions = batch.indices.associate { index ->
                    "l${index + 1}" to DecisionQuestion.Noul(question(batch[index].relation, "l${index + 1}"))
                }
                val response = decide(DecisionRequest(state, questions))
                batch.forEachIndexed { index, link ->
                    (response.answers["l${index + 1}"] as? DecisionAnswer.Noul)?.noul?.let { confidence[link.key] = it.coerceIn(0.0, 1.0) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                lastError = e.message ?: e.javaClass.simpleName
                LOG.warn("Walkthrough link verification batch failed", e)
            }
        }
        val warnings = buildList {
            if (failures > 0) add("Link verification failed for $failures of $requests batches ($lastError).")
            if (confidence.size < targets.size && failures == 0) add("${targets.size - confidence.size} links were not verified.")
        }
        return LinkVerification(confidence, requests, warnings)
    }

    private fun question(relation: WalkRelation, ref: String): String {
        val claim = when (relation) {
            WalkRelation.CAUSES -> "the `to` hunk is a direct consequence of the `from` hunk: it changed because of it or is required by it"
            WalkRelation.SAME_EDIT -> "the `from` and `to` hunks apply the same mechanical edit, such as adapting to the same rename"
            WalkRelation.TESTS -> "the `to` hunk tests the code changed by the `from` hunk"
            WalkRelation.DOCUMENTS -> "the `to` hunk documents or configures what the `from` hunk changed"
            WalkRelation.RELATED -> "the `from` and `to` hunks belong to the same logical change"
        }
        return "For the link with ref `$ref` in `links` (hunks by ref in `hunks`): is it true that $claim? " +
            "Judge from the patches; the reason is the claim to check, not evidence."
    }
}
