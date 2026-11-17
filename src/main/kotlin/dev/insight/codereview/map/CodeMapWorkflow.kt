package dev.insight.codereview.map

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import dev.insight.codereview.CodeFile
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<CodeMapWorkflow>()

data class CodeMapRunOptions(
    /** Probability from which two flows are merged. */
    val mergeThreshold: Double = 0.7,
    /** Confidence from which a standalone unit joins the chosen flow. */
    val attachThreshold: Double = 0.6,
    val maxMergeQuestions: Int = 40,
    val maxAttachQuestions: Int = 40,
    val concurrency: Int = 4,
    /** Source characters of one unit shown to a judgment or title request. */
    val unitChars: Int = 1_200,
    val titlesPerRequest: Int = 8,
)

/** [error] tells why the judgments or titles are incomplete; the [map] is valid either way. */
data class CodeMapRun(val map: CodeMap, val requests: Int, val merged: Int, val attached: Int, val error: String?)

/**
 * Refines a code map built by code: typed judgments merge flows and attach standalone units, then a model titles every
 * flow. Models never add, drop or move units on their own; without [decide] the flows stay as code made them.
 */
internal class CodeMapWorkflow(
    private val complete: CompletionFn,
    private val decide: DecisionFn?,
    private val options: CodeMapRunOptions = CodeMapRunOptions(),
) {
    private class Progress(private val report: suspend (Int, Int) -> Unit) {
        private val mutex = Mutex()
        private var done = 0
        private var total = 0
        suspend fun expect(count: Int) { if (count > 0) mutex.withLock { total += count; report(done, total) } }
        suspend fun step() = mutex.withLock { done++; report(done, total) }
        suspend fun skip(count: Int) { if (count > 0) mutex.withLock { total -= count; report(done, total) } }
    }

    suspend fun run(map: CodeMap, files: List<CodeFile>, focus: String = "",
                    onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }): CodeMapRun {
        val sources = files.associateBy { it.path }
        val progress = Progress(onProgress)
        val requests = AtomicInteger()
        var current = map
        var merged = 0
        var attached = 0
        val errors = mutableListOf<String>()

        if (decide != null) {
            val candidates = flowMergeCandidates(current).take(options.maxMergeQuestions.coerceAtLeast(0))
            progress.expect(candidates.size)
            val accepted = ConcurrentLinkedQueue<FlowMergeCandidate>()
            var failure = judge(decide, candidates, requests, progress, ask = { flowMergeRequest(current, it, sources, options.unitChars) },
                answered = { candidate, response -> if (mergeProbability(response) >= options.mergeThreshold) accepted += candidate })
            val groups = connectedGroups(current, accepted.map { it.a to it.b })
            if (groups.isNotEmpty()) {
                current = current.mergedFlows(groups)
                merged = groups.sumOf { it.size - 1 }
            }
            if (failure == null) {
                val standalone = current.standalone.asSequence()
                    .mapNotNull { flow -> flowAttachCandidates(current, flow).take(MAX_ATTACH_OPTIONS).takeIf { it.isNotEmpty() }?.let { flow to it } }
                    .take(options.maxAttachQuestions.coerceAtLeast(0)).toList()
                progress.expect(standalone.size)
                val asked = standalone.map { (flow, candidates) -> flowAttachRequest(current, flow, candidates, sources, options.unitChars) }
                val chosen = ConcurrentLinkedQueue<Pair<String, String>>()
                failure = judge(decide, asked, requests, progress, ask = { it.request },
                    answered = { question, response -> attachChoice(question, response, options.attachThreshold)?.let { chosen += it to question.standalone } })
                val attach = chosen.groupBy({ it.first }, { it.second }).map { (flow, units) -> linkedSetOf(flow).apply { addAll(units) } as Set<String> }
                if (attach.isNotEmpty()) {
                    current = current.mergedFlows(attach)
                    attached = chosen.size
                }
            }
            failure?.let {
                errors += "Decisions API unavailable: $it; " +
                    if (merged + attached == 0) "flows were left as code built them." else "remaining judgments were skipped and flows keep the decisions made so far."
            }
        }

        val untitled = current.chains.filter { it.title == null }.chunked(options.titlesPerRequest.coerceAtLeast(1))
        if (untitled.isNotEmpty()) {
            progress.expect(untitled.size)
            val titles = ConcurrentHashMap<String, String>()
            val snapshot = current
            val counted: CompletionFn = { requests.incrementAndGet(); complete(it) }
            val stopped = AtomicInteger()
            val failure = AtomicReference<String?>()
            forEachConcurrently(untitled, options.concurrency) { batch ->
                if (stopped.get() > 0) { progress.skip(1); return@forEachConcurrently }
                try {
                    titles += titleBatch(counted, snapshot, batch, focus, sources)
                } catch (e: Exception) {
                    if (e is CancellationException || e is ControlFlowException) throw e
                    LOG.warn("Code map title request failed", e)
                    failure.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                    if (e is LlmApiException || e is LlmContextLimitException) stopped.incrementAndGet()
                }
                progress.step()
            }
            current = current.withTitles(titles)
            failure.get()?.let { errors += "Titles unavailable: $it; flows without a title are named from their code." }
        }
        return CodeMapRun(current, requests.get(), merged, attached, errors.joinToString(" ").ifEmpty { null })
    }

    private suspend fun <T> judge(decide: DecisionFn, items: List<T>, requests: AtomicInteger, progress: Progress,
                                  ask: (T) -> DecisionRequest, answered: (T, DecisionResponse) -> Unit): String? {
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
                LOG.warn("Code map decision request failed", e)
                failure.compareAndSet(null, e.message ?: e.javaClass.simpleName)
            }
            progress.step()
        }
        progress.skip(items.size - asked.get())
        return failure.get()
    }

    /** Titles for [batch], asking once more for the ids the first answer left out; what is still missing stays untitled. */
    private suspend fun titleBatch(counted: CompletionFn, map: CodeMap, batch: List<CodeFlow>, focus: String, sources: Map<String, CodeFile>): Map<String, String> {
        val titles = linkedMapOf<String, String>()
        var pending = batch
        repeat(2) {
            if (pending.isEmpty()) return@repeat
            val ask = pending
            val answer = completeJson(counted, TITLES_SYSTEM, flowTitleRequest(map, ask, focus, sources, options.unitChars), TITLES_SCHEMA,
                maxTokens = 400 + 120 * ask.size)
            val decoded = decodeFlowTitles(answer, ask.size)
            decoded.forEach { (id, title) -> titles[ask[id - 1].id] = title }
            pending = ask.filterIndexed { index, _ -> index + 1 !in decoded }
        }
        return titles
    }

    companion object {
        internal const val MAX_ATTACH_OPTIONS = 4
        internal const val MERGE_QUESTION = "same_flow"
        internal const val ATTACH_QUESTION = "attach_to"
        internal const val NO_ATTACH = "none"
        internal const val MAX_TITLE_CHARS = 60
        internal val TITLES_SYSTEM = PromptTemplates.render("code-map-titles.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA))
        internal val TITLES_SCHEMA = JsonSchemas.obj("titles" to JsonSchemas.array(JsonSchemas.obj(
            "id" to JsonSchemas.integer(minimum = 1),
            "title" to JsonSchemas.string(minLength = 1, maxLength = MAX_TITLE_CHARS),
        )))
    }
}

private const val MAX_STATE_UNITS = 8
private const val MAX_STATE_LINKS = 12
private const val MAX_MEMBERS = 12

/** The start of a unit's source: its header and first lines up to [chars], marked when cut. */
internal fun unitExcerpt(unit: CodeUnit, file: CodeFile?, chars: Int): String? {
    file ?: return null
    val text = when {
        unit.kind == UnitKind.CLASS && unit.lines != null -> file.lines(unit.lines)
        unit.kind == UnitKind.TOP_LEVEL && unit.members.isNotEmpty() && unit.lines != null -> unit.members.joinToString("\n") { file.lines(it.lines) }
        else -> file.text
    }.trimEnd()
    if (text.isEmpty()) return null
    return if (text.length <= chars) text else text.take(chars).substringBeforeLast('\n').ifEmpty { text.take(chars) } + "\n…"
}

/** A flow for a judgment: its units in chain order with name, kind, role, members and the start of their source. */
internal fun flowJson(map: CodeMap, flow: CodeFlow, sources: Map<String, CodeFile>, unitChars: Int, excerpts: Int = MAX_STATE_UNITS,
                      excerptChars: Int = unitChars): JsonObject = JsonObject().apply {
    val roles = flow.steps.associate { it.unit to it.role }
    val units = map.units(flow)
    flow.title?.let { addProperty("title", it) }
    add("units", JsonArray().apply {
        units.take(MAX_STATE_UNITS).forEachIndexed { index, unit ->
            add(JsonObject().apply {
                addProperty("step", index + 1)
                addProperty("role", roles[unit.id]?.name?.lowercase() ?: "standalone")
                addProperty("name", unit.name)
                addProperty("path", unit.path)
                addProperty("kind", unit.kind.name.lowercase())
                addProperty("lines", unit.lineCount)
                if (unit.members.isNotEmpty()) addProperty("members", unit.members.take(MAX_MEMBERS).joinToString(", ") { it.name.substringAfterLast('.') } +
                    if (unit.members.size > MAX_MEMBERS) ", …" else "")
                if (index < excerpts) unitExcerpt(unit, sources[unit.path], excerptChars)?.let { addProperty("source", it) }
            })
        }
    })
    if (units.size > MAX_STATE_UNITS) addProperty("more_units", units.size - MAX_STATE_UNITS)
    val inside = map.sentences(map.edgesWithin(flow), MAX_STATE_LINKS)
    if (inside.isNotEmpty()) add("relations", JsonArray().apply { inside.forEach(::add) })
    if (flow.shared.isNotEmpty()) add("uses_shared", JsonArray().apply { flow.shared.mapNotNull(map.graph::unit).take(MAX_STATE_LINKS).forEach { add(it.reference()) } })
}

private val MERGE_CRITERIA = mapOf<Boolean, JsonElement>(
    true to JsonPrimitive("One feature, responsibility or execution flow explains both groups, and a reader learning the code would read them together: " +
        "for example a command and the workflow it runs, or a model and the only service that operates on it."),
    false to JsonPrimitive("Separate responsibilities that merely call each other, share a helper or sit in the same folder."),
)

/** A judgment of whether the two flows of [candidate] are one flow of the code. */
internal fun flowMergeRequest(map: CodeMap, candidate: FlowMergeCandidate, sources: Map<String, CodeFile>, unitChars: Int): DecisionRequest {
    val first = checkNotNull(map.flow(candidate.a)) { "Unknown flow ${candidate.a}" }
    val second = checkNotNull(map.flow(candidate.b)) { "Unknown flow ${candidate.b}" }
    val state = JsonObject().apply {
        add("first_group", flowJson(map, first, sources, unitChars))
        add("second_group", flowJson(map, second, sources, unitChars))
        add("links", JsonArray().apply { map.sentences(candidate.edges, MAX_STATE_LINKS).forEach(::add) })
    }
    return DecisionRequest(state, mapOf(CodeMapWorkflow.MERGE_QUESTION to DecisionQuestion.Noul(
        "Are `first_group` and `second_group` parts of one feature or execution flow that a reader should learn together, because one " +
            "responsibility explains both, as opposed to separate responsibilities that merely use each other? Judge by the `source` of their " +
            "`units`; `links` are references found by code and are not proof of a shared responsibility.",
        MERGE_CRITERIA,
    )))
}

private fun mergeProbability(response: DecisionResponse): Double =
    (response.answers[CodeMapWorkflow.MERGE_QUESTION] as? DecisionAnswer.Noul)?.noul
        ?: throw IOException("Decisions API returned no ${CodeMapWorkflow.MERGE_QUESTION} answer")

/** The connected sets of the accepted pairs as flow ids, in map order. */
internal fun connectedGroups(map: CodeMap, accepted: List<Pair<String, String>>): List<Set<String>> {
    val parent = HashMap<String, String>()
    fun find(id: String): String = generateSequence(id) { parent[it]?.takeIf { next -> next != it } }.last()
    for ((a, b) in accepted) {
        val x = find(a); val y = find(b)
        if (x != y) parent[y] = x
    }
    val order = map.flows.map { it.id }
    val known = order.toSet()
    return accepted.flatMap { listOf(it.first, it.second) }.filter { it in known }.distinct().groupBy(::find).values
        .map { members -> order.filterTo(LinkedHashSet()) { it in members } as Set<String> }
        .filter { it.size > 1 }.sortedBy { order.indexOf(it.first()) }
}

/** An attach judgment: [options] maps the option keys of its Choice question to candidate flow ids. */
internal class FlowAttachQuestion(val request: DecisionRequest, val standalone: String, val options: Map<String, String>)

/** A judgment of which of up to four [candidates], if any, the standalone unit belongs to. */
internal fun flowAttachRequest(map: CodeMap, standalone: CodeFlow, candidates: List<CodeFlow>, sources: Map<String, CodeFile>, unitChars: Int): FlowAttachQuestion {
    val keys = candidates.take(CodeMapWorkflow.MAX_ATTACH_OPTIONS).mapIndexed { index, flow -> "c${index + 1}" to flow }
    val ids = standalone.units.toSet()
    val state = JsonObject().apply {
        add("unit", flowJson(map, standalone, sources, unitChars))
        add("candidates", JsonArray().apply {
            keys.forEach { (key, flow) ->
                add(JsonObject().apply {
                    addProperty("option", key)
                    add("group", flowJson(map, flow, sources, unitChars, excerpts = 2, excerptChars = unitChars / 2))
                    val between = map.graph.edges.filter { (it.from in ids) != (it.to in ids) && (it.from in flow.units || it.to in flow.units) }
                    add("links", JsonArray().apply { map.sentences(between, MAX_STATE_LINKS).forEach(::add) })
                })
            }
        })
    }
    val criteria = linkedMapOf<String, JsonElement?>().apply {
        keys.forEach { (key, flow) ->
            put(key, JsonPrimitive("${map.title(flow)}: " + map.units(flow).take(MAX_STATE_UNITS).joinToString("; ") { it.reference() } +
                if (flow.steps.size > MAX_STATE_UNITS) "; and ${flow.steps.size - MAX_STATE_UNITS} more" else ""))
        }
        put(CodeMapWorkflow.NO_ATTACH, JsonPrimitive("None of the groups: the unit has a responsibility of its own or belongs elsewhere."))
    }
    val question = DecisionQuestion.Choice(
        "Does the one `unit` belong to one of the `candidates` as part of the same feature or execution flow, so that a reader should learn " +
            "them together? Choose that candidate's option, or `none` when it only uses or is used by related code. `links` are references " +
            "found by code and are not proof of a shared responsibility.",
        criteria,
    )
    return FlowAttachQuestion(DecisionRequest(state, mapOf(CodeMapWorkflow.ATTACH_QUESTION to question)), standalone.id, keys.associate { (key, flow) -> key to flow.id })
}

/** The flow id the answer attaches the standalone unit to, or null for "none", an unknown option or low confidence. */
internal fun attachChoice(question: FlowAttachQuestion, response: DecisionResponse, threshold: Double): String? {
    val answer = response.answers[CodeMapWorkflow.ATTACH_QUESTION] as? DecisionAnswer.Choice
        ?: throw IOException("Decisions API returned no ${CodeMapWorkflow.ATTACH_QUESTION} answer")
    return question.options[answer.choice]?.takeIf { answer.confidence >= threshold }
}

/** The user message that asks for titles of [flows], numbered from 1 in order. */
internal fun flowTitleRequest(map: CodeMap, flows: List<CodeFlow>, focus: String, sources: Map<String, CodeFile>, unitChars: Int): String =
    JsonObject().apply {
        focus.takeIf { it.isNotBlank() }?.let { addProperty("focus", it.clipLine(300)) }
        add("required_ids", JsonArray().apply { flows.indices.forEach { add(it + 1) } })
        add("flows", JsonArray().apply {
            flows.forEachIndexed { index, flow ->
                add(flowJson(map, flow, sources, unitChars, excerpts = TITLE_EXCERPTS, excerptChars = unitChars / TITLE_EXCERPTS).apply {
                    remove("title")
                    addProperty("id", index + 1)
                    if (flow.foundation) addProperty("shared_foundation", true)
                })
            }
        })
    }.toString()

private const val TITLE_EXCERPTS = 3

/** A title as the views show it: one line, no surrounding quotes or trailing period, at most 60 characters; null if nothing remains. */
internal fun cleanFlowTitle(text: String): String? =
    text.clipLine(CodeMapWorkflow.MAX_TITLE_CHARS).trim().trim('"', '\'', '`').trimEnd('.').trim().takeIf { it.isNotEmpty() }

/** Titles by 1-based id from a title answer; unknown ids, empty titles and ids answered twice are left out. */
internal fun decodeFlowTitles(answer: JsonObject, count: Int): Map<Int, String> {
    val entries = answer.get("titles")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
    val ids = entries.map { entry -> entry.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it % 1.0 == 0.0 }?.toInt() }
    val duplicated = ids.filterNotNull().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val titles = linkedMapOf<Int, String>()
    entries.forEachIndexed { index, entry ->
        val id = ids[index]?.takeIf { it in 1..count && it !in duplicated } ?: return@forEachIndexed
        entry.jsonText("title")?.let(::cleanFlowTitle)?.let { titles[id] = it }
    }
    return titles
}
