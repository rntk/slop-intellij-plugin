package dev.insight.diff.atoms

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.diff.ReviewFile
import dev.insight.diff.patchRows
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.CompletionFn
import dev.insight.llm.JsonSchemas
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmContextLimitException
import dev.insight.llm.clipLine
import dev.insight.llm.completeJson
import dev.insight.llm.forEachConcurrently
import dev.insight.llm.jsonText
import dev.insight.prompts.PromptTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<ChangeMapWorkflow>()

data class ChangeMapOptions(
    /** Probability from which two clusters are merged. */
    val mergeThreshold: Double = 0.7,
    /** Confidence from which a standalone change joins the chosen cluster. */
    val attachThreshold: Double = 0.6,
    val maxMergeQuestions: Int = 40,
    val maxAttachQuestions: Int = 40,
    val concurrency: Int = 4,
    /** Patch characters of one atom shown to a judgment or title request. */
    val atomChars: Int = 1_500,
    val titlesPerRequest: Int = 8,
)

/** [error] tells why the judgments or titles are incomplete; the [map] is valid either way. */
data class ChangeMapRun(val map: ChangeMap, val requests: Int, val merged: Int, val attached: Int, val error: String?)

/**
 * Refines a change map built by code: typed judgments merge clusters and attach standalone changes, then a model titles
 * every chain. Models never add, drop or move atoms on their own; without [decide] the clusters stay as code made them.
 * The clustering functions are parameters so the workflow runs against fakes; the defaults are the real ones.
 */
internal class ChangeMapWorkflow(
    private val complete: CompletionFn,
    private val decide: DecisionFn?,
    private val options: ChangeMapOptions = ChangeMapOptions(),
    private val mergeCandidatesOf: (ChangeMap) -> List<MergeCandidate> = ::mergeCandidates,
    private val attachCandidatesOf: (ChangeMap, ChangeCluster) -> List<ChangeCluster> = ::attachCandidates,
    private val mergeClusters: (ChangeMap, List<Set<String>>) -> ChangeMap = { map, groups -> map.merged(groups) },
) {
    /** Progress counts judgments and title requests; its total grows as each phase learns how many requests it needs. */
    private class Progress(private val report: suspend (Int, Int) -> Unit) {
        private val mutex = Mutex()
        private var done = 0
        private var total = 0
        suspend fun expect(count: Int) { if (count > 0) mutex.withLock { total += count; report(done, total) } }
        suspend fun step() = mutex.withLock { done++; report(done, total) }
        suspend fun skip(count: Int) { if (count > 0) mutex.withLock { total -= count; report(done, total) } }
    }

    suspend fun run(map: ChangeMap, files: List<ReviewFile>, focus: String = "",
                    onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): ChangeMapRun {
        val patches = files.associate { it.path to it.patch }
        val progress = Progress(onProgress)
        val requests = AtomicInteger()
        var current = map
        var merged = 0
        var attached = 0
        val errors = mutableListOf<String>()
        var decisionFailure: String? = null

        if (decide != null) {
            val candidates = mergeCandidatesOf(current).take(options.maxMergeQuestions.coerceAtLeast(0))
            progress.expect(candidates.size)
            val accepted = ConcurrentLinkedQueue<MergeCandidate>()
            decisionFailure = judge(decide, candidates, requests, progress,
                ask = { mergeRequest(current, it, patches, options.atomChars) },
                answered = { candidate, response ->
                    if (mergeProbability(response) >= options.mergeThreshold) accepted += candidate
                })
            val groups = mergeGroups(current, accepted.toList())
            if (groups.isNotEmpty()) {
                current = mergeClusters(current, groups)
                merged = groups.sumOf { it.size - 1 }
            }

            if (decisionFailure == null) {
                val standalone = current.standalone.asSequence()
                    .mapNotNull { cluster -> attachCandidatesOf(current, cluster).take(MAX_ATTACH_OPTIONS).takeIf { it.isNotEmpty() }?.let { cluster to it } }
                    .take(options.maxAttachQuestions.coerceAtLeast(0)).toList()
                progress.expect(standalone.size)
                val asked = standalone.map { (cluster, candidates) -> attachRequest(current, cluster, candidates, patches, options.atomChars) }
                val chosen = ConcurrentLinkedQueue<Pair<String, String>>()
                decisionFailure = judge(decide, asked, requests, progress,
                    ask = { it.request },
                    answered = { question, response ->
                        attachTarget(question, response, options.attachThreshold)?.let { chosen += it to question.standalone }
                    })
                val groups = attachGroups(chosen.toList())
                if (groups.isNotEmpty()) {
                    current = mergeClusters(current, groups)
                    attached = chosen.size
                }
            }
            decisionFailure?.let {
                errors += "Decisions API unavailable: $it; " +
                    if (merged + attached == 0) "clusters were left as code built them." else "remaining judgments were skipped and clusters keep the decisions made so far."
            }
        }

        val titleFailure = AtomicReference<String?>()
        val untitled = current.chains.filter { it.title == null }.chunked(options.titlesPerRequest.coerceAtLeast(1))
        if (untitled.isNotEmpty()) {
            progress.expect(untitled.size)
            val titles = java.util.concurrent.ConcurrentHashMap<String, String>()
            val snapshot = current
            val counted: CompletionFn = { requests.incrementAndGet(); complete(it) }
            val stopped = AtomicInteger()
            forEachConcurrently(untitled, options.concurrency) { batch ->
                if (stopped.get() > 0) { progress.skip(1); return@forEachConcurrently }
                try {
                    titles += titleBatch(counted, snapshot, batch, focus, patches)
                } catch (e: Exception) {
                    if (e is CancellationException || e is ControlFlowException) throw e
                    LOG.warn("Title request failed", e)
                    titleFailure.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                    // The same server answer would reject every other batch too.
                    if (e is LlmApiException || e is LlmContextLimitException) stopped.incrementAndGet()
                }
                progress.step()
            }
            current = current.withTitles(titles)
            titleFailure.get()?.let { errors += "Titles unavailable: $it; changes without a title are named from their code." }
        }
        return ChangeMapRun(current, requests.get(), merged, attached, errors.joinToString(" ").ifEmpty { null })
    }

    /**
     * Asks [items] one at a time per permit; the first failure stops the phase, so later items are not asked and leave the
     * progress total. Returns that failure's message, or null when every item was answered.
     */
    private suspend fun <T> judge(
        decide: DecisionFn,
        items: List<T>,
        requests: AtomicInteger,
        progress: Progress,
        ask: (T) -> DecisionRequest,
        answered: (T, DecisionResponse) -> Unit,
    ): String? {
        val failure = AtomicReference<String?>()
        val asked = AtomicInteger()
        forEachConcurrently(items, options.concurrency) { item ->
            if (failure.get() != null) return@forEachConcurrently
            asked.incrementAndGet()
            try {
                requests.incrementAndGet()
                answered(item, decide(ask(item)))
            } catch (e: Exception) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Decision request failed", e)
                failure.compareAndSet(null, e.message ?: e.javaClass.simpleName)
            }
            progress.step()
        }
        progress.skip(items.size - asked.get())
        return failure.get()
    }

    /** Titles for [batch], asking once more for the ids the first answer left out; what is still missing stays untitled. */
    private suspend fun titleBatch(counted: CompletionFn, map: ChangeMap, batch: List<ChangeCluster>, focus: String,
                                   patches: Map<String, String>): Map<String, String> {
        val titles = linkedMapOf<String, String>()
        var pending = batch
        repeat(2) {
            if (pending.isEmpty()) return@repeat
            val ask = pending
            val user = titleRequest(map, ask, focus, patches, options.atomChars)
            val answer = completeJson(counted, TITLES_SYSTEM, user, TITLES_SCHEMA, maxTokens = 400 + 120 * ask.size)
            val decoded = decodeTitles(answer, ask.size)
            decoded.forEach { (id, title) -> titles[ask[id - 1].id] = title }
            pending = ask.filterIndexed { index, _ -> index + 1 !in decoded }
        }
        return titles
    }

    companion object {
        /** Options offered to an attach judgment besides "none". */
        internal const val MAX_ATTACH_OPTIONS = 4
        internal const val MERGE_QUESTION = "same_change"
        internal const val ATTACH_QUESTION = "attach_to"
        internal const val NO_ATTACH = "none"
        internal const val MAX_TITLE_CHARS = 60
        internal val TITLES_SYSTEM = PromptTemplates.render("diff-change-map-titles.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA))
        internal val TITLES_SCHEMA = JsonSchemas.obj("titles" to JsonSchemas.array(JsonSchemas.obj(
            "id" to JsonSchemas.integer(minimum = 1),
            "title" to JsonSchemas.string(minLength = 1, maxLength = MAX_TITLE_CHARS),
        )))
    }
}

/** One row of a file's patch: its 1-based number in the patch and its text. */
internal data class AtomRow(val number: Int, val text: String)

/** The lines of [patch] by 1-based row, numbered as summaries cite them. */
internal fun patchLines(patch: String): List<String> = if (patchRows(patch) == 0) emptyList() else patch.removeSuffix("\n").split('\n')

/** The rows of [atom] in patch order, from the lines of its file's patch; rows beyond the patch are dropped. */
internal fun atomRows(atom: ChangeAtom, lines: List<String>): List<AtomRow> =
    atom.rows.flatMap { range -> range.filter { it in 1..lines.size }.map { AtomRow(it, lines[it - 1]) } }

/** The longest prefix of [rows] within [limit] characters (a row counts its text and a newline); a first row alone may be clipped. */
internal fun rowsWithin(rows: List<AtomRow>, limit: Int): List<AtomRow> {
    var used = 0
    val shown = rows.takeWhile { row -> (used + row.text.length + 1 <= limit).also { if (it) used += row.text.length + 1 } }
    return shown.ifEmpty { rows.take(1).map { it.copy(text = it.text.take(limit.coerceAtLeast(1))) } }
}

/** [rows] as text; rows that do not follow one another are parted by a line "…" that has no number. */
internal fun renderRows(rows: List<AtomRow>, numbered: Boolean): String = buildString {
    var previous: Int? = null
    for (row in rows) {
        if (previous != null) append('\n')
        if (previous != null && row.number != previous + 1) append("…\n")
        if (numbered) append(row.number).append('|')
        append(row.text)
        previous = row.number
    }
}

/** What a judgment or title request shows of an atom's patch: its rows clipped to [chars], marked when cut. */
internal fun atomPatchExcerpt(atom: ChangeAtom, patch: String?, chars: Int): String? {
    val rows = atomRows(atom, patchLines(patch.orEmpty())).takeIf { it.isNotEmpty() } ?: return null
    val shown = rowsWithin(rows, chars)
    return renderRows(shown, numbered = false) + if (shown.size < rows.size) "\n…" else ""
}

/** A reference to an atom in a sentence: "Foo.bar (src/Foo.kt)". */
internal fun ChangeAtom.reference(): String = "$name ($path)"

/** "B uses the new A" for an edge from A to B, or null when an end is not in the graph. */
internal fun ChangeGraph.edgeSentence(edge: AtomEdge): String? {
    val from = atom(edge.from) ?: return null
    val to = atom(edge.to) ?: return null
    return "${to.reference()} ${edge.relation.label} ${from.reference()}"
}

private fun ChangeMap.roleOf(cluster: ChangeCluster, atom: ChangeAtom): AtomRole =
    cluster.steps.firstOrNull { it.atom == atom.id }?.role ?: AtomRole.STANDALONE

/** The edges' sentences, strongest relation first, without repeats. */
internal fun ChangeMap.sentences(edges: List<AtomEdge>, limit: Int): List<String> =
    edges.sortedBy { it.relation.strength.ordinal }.mapNotNull(graph::edgeSentence).distinct().take(limit)

private const val MAX_STATE_ATOMS = 8
private const val MAX_STATE_LINKS = 12

/** A cluster for a judgment: its atoms in chain order with name, kind, status, role, counts and patch rows. */
internal fun clusterJson(map: ChangeMap, cluster: ChangeCluster, patches: Map<String, String>, atomChars: Int, excerpts: Int = MAX_STATE_ATOMS,
                         excerptChars: Int = atomChars): JsonObject = JsonObject().apply {
    val atoms = map.atoms(cluster)
    cluster.title?.let { addProperty("title", it) }
    add("atoms", JsonArray().apply {
        atoms.take(MAX_STATE_ATOMS).forEachIndexed { index, atom ->
            add(JsonObject().apply {
                addProperty("step", index + 1)
                addProperty("role", map.roleOf(cluster, atom).name.lowercase())
                addProperty("name", atom.name)
                addProperty("path", atom.path)
                addProperty("kind", atom.kind.name.lowercase())
                addProperty("status", atom.status.name.lowercase())
                addProperty("added", atom.added)
                addProperty("removed", atom.removed)
                if (index < excerpts) atomPatchExcerpt(atom, patches[atom.path], excerptChars)?.let { addProperty("patch", it) }
            })
        }
    })
    if (atoms.size > MAX_STATE_ATOMS) addProperty("more_atoms", atoms.size - MAX_STATE_ATOMS)
    val inside = map.sentences(map.edgesWithin(cluster), MAX_STATE_LINKS)
    if (inside.isNotEmpty()) add("relations", JsonArray().apply { inside.forEach(::add) })
}

private val MERGE_CRITERIA = mapOf<Boolean, com.google.gson.JsonElement>(
    true to com.google.gson.JsonPrimitive("One cause or purpose explains both groups, and a reviewer would read them together: for example one feature, " +
        "fix or refactoring whose second group exists because of the first."),
    false to com.google.gson.JsonPrimitive("Independent changes that merely touch related code, share an owner or call each other, with no common cause shown by the patches."),
)

/** A judgment of whether the two clusters of [candidate] are one logical change. */
internal fun mergeRequest(map: ChangeMap, candidate: MergeCandidate, patches: Map<String, String>, atomChars: Int): DecisionRequest {
    val first = checkNotNull(map.cluster(candidate.a)) { "Unknown cluster ${candidate.a}" }
    val second = checkNotNull(map.cluster(candidate.b)) { "Unknown cluster ${candidate.b}" }
    val state = JsonObject().apply {
        add("first_group", clusterJson(map, first, patches, atomChars))
        add("second_group", clusterJson(map, second, patches, atomChars))
        add("links", JsonArray().apply { map.sentences(candidate.edges, MAX_STATE_LINKS).forEach(::add) })
    }
    return DecisionRequest(state, mapOf(ChangeMapWorkflow.MERGE_QUESTION to DecisionQuestion.Noul(
        "Are `first_group` and `second_group` parts of one logical change that a reviewer should read together, because the same " +
            "cause or purpose explains both, as opposed to independent changes that merely touch related code? " +
            "Judge by the patches of their `atoms`; `links` are relations found by code and are not proof of a common purpose.",
        MERGE_CRITERIA,
    )))
}

private fun mergeProbability(response: DecisionResponse): Double =
    (response.answers[ChangeMapWorkflow.MERGE_QUESTION] as? DecisionAnswer.Noul)?.noul
        ?: throw IOException("Decisions API returned no ${ChangeMapWorkflow.MERGE_QUESTION} answer")

/** The connected sets of the accepted pairs as cluster ids, in map order; empty when nothing was accepted. */
internal fun mergeGroups(map: ChangeMap, accepted: List<MergeCandidate>): List<Set<String>> {
    val parent = HashMap<String, String>()
    fun find(id: String): String = generateSequence(id) { parent[it]?.takeIf { next -> next != it } }.last()
    for (pair in accepted) {
        val a = find(pair.a)
        val b = find(pair.b)
        if (a != b) parent[b] = a
    }
    val order = map.clusters.map { it.id }
    val known = order.toSet()
    return accepted.flatMap { listOf(it.a, it.b) }.filter { it in known }.distinct().groupBy(::find).values
        .map { members -> order.filterTo(LinkedHashSet()) { it in members } as Set<String> }
        .filter { it.size > 1 }.sortedBy { order.indexOf(it.first()) }
}

/** An attach judgment: [options] maps the option keys of its Choice question to candidate cluster ids. */
internal class AttachQuestion(val request: DecisionRequest, val standalone: String, val options: Map<String, String>)

/** Describes a candidate in one line for a Choice option: its title (or default) and its atoms. */
private fun ChangeMap.describeCluster(cluster: ChangeCluster): String =
    "${title(cluster)}: " + atoms(cluster).take(MAX_STATE_ATOMS).joinToString("; ") { "${it.name} (${it.path})" } +
        if (cluster.steps.size > MAX_STATE_ATOMS) "; and ${cluster.steps.size - MAX_STATE_ATOMS} more" else ""

/** A judgment of which of up to four [candidates], if any, the change of the standalone cluster belongs to. */
internal fun attachRequest(map: ChangeMap, standalone: ChangeCluster, candidates: List<ChangeCluster>, patches: Map<String, String>,
                           atomChars: Int): AttachQuestion {
    val keys = candidates.take(ChangeMapWorkflow.MAX_ATTACH_OPTIONS).mapIndexed { index, cluster -> "c${index + 1}" to cluster }
    val ids = standalone.atoms.toSet()
    val state = JsonObject().apply {
        add("change", clusterJson(map, standalone, patches, atomChars))
        add("candidates", JsonArray().apply {
            keys.forEach { (key, cluster) ->
                add(JsonObject().apply {
                    addProperty("option", key)
                    // A candidate only needs enough patch to compare its purpose; the standalone change carries the full excerpt.
                    add("group", clusterJson(map, cluster, patches, atomChars, excerpts = 2, excerptChars = atomChars / 2))
                    val between = map.graph.edges.filter { (it.from in ids) != (it.to in ids) && (it.from in cluster.atoms || it.to in cluster.atoms) }
                    add("links", JsonArray().apply { map.sentences(between, MAX_STATE_LINKS).forEach(::add) })
                })
            }
        })
    }
    val criteria = linkedMapOf<String, com.google.gson.JsonElement?>().apply {
        keys.forEach { (key, cluster) -> put(key, com.google.gson.JsonPrimitive(map.describeCluster(cluster))) }
        put(ChangeMapWorkflow.NO_ATTACH, com.google.gson.JsonPrimitive("None of the groups: the change stands on its own or belongs elsewhere."))
    }
    val question = DecisionQuestion.Choice(
        "Does the one `change` belong to one of the `candidates` as part of the same logical change, because the same cause or purpose " +
            "explains both and a reviewer should read them together? Choose that candidate's option, or `none` when it only touches related code. " +
            "`links` are relations found by code and are not proof of a common purpose.",
        criteria,
    )
    return AttachQuestion(DecisionRequest(state, mapOf(ChangeMapWorkflow.ATTACH_QUESTION to question)), standalone.id, keys.associate { (key, cluster) -> key to cluster.id })
}

/** The cluster id the answer attaches the standalone change to, or null for "none", an unknown option or low confidence. */
internal fun attachTarget(question: AttachQuestion, response: DecisionResponse, threshold: Double): String? {
    val answer = response.answers[ChangeMapWorkflow.ATTACH_QUESTION] as? DecisionAnswer.Choice
        ?: throw IOException("Decisions API returned no ${ChangeMapWorkflow.ATTACH_QUESTION} answer")
    return question.options[answer.choice]?.takeIf { answer.confidence >= threshold }
}

/** Candidate clusters with the standalone clusters attached to them, one group each, in the order the candidates were first chosen. */
internal fun attachGroups(chosen: List<Pair<String, String>>): List<Set<String>> =
    chosen.groupBy({ it.first }, { it.second }).map { (candidate, standalone) -> linkedSetOf(candidate).apply { addAll(standalone) } }

/** The user message that asks for titles of [clusters], numbered from 1 in order. */
internal fun titleRequest(map: ChangeMap, clusters: List<ChangeCluster>, focus: String, patches: Map<String, String>, atomChars: Int): String =
    JsonObject().apply {
        focus.takeIf { it.isNotBlank() }?.let { addProperty("focus", it.clipLine(300)) }
        add("required_ids", JsonArray().apply { clusters.indices.forEach { add(it + 1) } })
        add("changes", JsonArray().apply {
            clusters.forEachIndexed { index, cluster ->
                add(clusterJson(map, cluster, patches, atomChars, excerpts = TITLE_EXCERPTS, excerptChars = atomChars / TITLE_EXCERPTS).apply {
                    // Any earlier title came from a merge that dropped it; titles are always written fresh, so none is shown.
                    remove("title")
                    addProperty("id", index + 1)
                })
            }
        })
    }.toString()

private const val TITLE_EXCERPTS = 3

/** A title as the views show it: one line, no surrounding quotes or trailing period, at most 60 characters; null if nothing remains. */
internal fun cleanTitle(text: String): String? =
    text.clipLine(ChangeMapWorkflow.MAX_TITLE_CHARS).trim().trim('"', '\'', '`').trimEnd('.').trim().takeIf { it.isNotEmpty() }

/** Titles by 1-based id from a title answer; unknown ids, empty titles and ids answered twice are left out. */
internal fun decodeTitles(answer: JsonObject, count: Int): Map<Int, String> {
    val entries = answer.get("titles")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
    val ids = entries.map { entry ->
        entry.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it % 1.0 == 0.0 }?.toInt()
    }
    val duplicated = ids.filterNotNull().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val titles = linkedMapOf<Int, String>()
    entries.forEachIndexed { index, entry ->
        val id = ids[index]?.takeIf { it in 1..count && it !in duplicated } ?: return@forEachIndexed
        entry.jsonText("title")?.let(::cleanTitle)?.let { titles[id] = it }
    }
    return titles
}
