package dev.insight.diff.walk

import dev.insight.agent.ToolInputException
import dev.insight.llm.clipLine

/** What a ledger mutation did: the resulting [step], links added or already known, and hunks newly handled. */
data class StepChange(val step: WalkStep, val linksAdded: Int, val linksKnown: Int, val newlyHandled: Int)

/**
 * The mutable state of one walkthrough run, written by the recording tools. Confined to one coroutine: tools run in
 * sequence, so it is not thread-safe. Every mutation validates its whole input before changing anything (bad input throws
 * [ToolInputException] for the model to fix) and then sends a [snapshot] to [onUpdate] so a view can stream.
 *
 * Status only rises (PENDING < COVERED < STANDALONE < LINKED): a hunk analyzed once is never downgraded. A step's root
 * and listed hunks become LINKED, except those listed as covered, which become COVERED; since the root is always LINKED,
 * every covered hunk sits in a step holding a LINKED hunk. Duplicate links are ignored, symmetric ones in either direction.
 */
class WalkLedger(
    val hunks: List<WalkHunk>,
    private val triage: Map<String, HunkTriage>? = null,
    private val onUpdate: suspend (Walkthrough) -> Unit = {},
) {
    private val byId = hunks.associateBy { it.id }
    private val status = LinkedHashMap<String, HunkStatus>().apply { hunks.forEach { put(it.id, HunkStatus.PENDING) } }
    private val steps = mutableListOf<WalkStep>()
    private val links = LinkedHashMap<LinkKey, WalkLink>()
    private val warnings = mutableListOf<String>()
    private var complete = false
    private var finalized: Walkthrough? = null

    init { require(byId.size == hunks.size) { "Hunk ids must be unique" } }

    fun snapshot(): Walkthrough = finalized?.copy(links = links.values.toList(), complete = complete, warnings = warnings.toList())
        ?: Walkthrough(hunks, steps.toList(), links.values.toList(), status.toMap(), triage, complete, warnings.toList())

    fun hunk(id: String): WalkHunk? = byId[id]
    fun status(id: String): HunkStatus = status[id] ?: throw ToolInputException(unknown(listOf(id)))
    fun step(id: String): WalkStep? = steps.firstOrNull { it.id == id }
    val stepCount: Int get() = steps.size
    val pendingIds: List<String> get() = status.filterValues { it == HunkStatus.PENDING }.keys.toList()
    val pendingCount: Int get() = status.values.count { it == HunkStatus.PENDING }
    val linkCount: Int get() = links.size
    fun stepsOf(id: String): List<WalkStep> = steps.filter { id in it.hunks }

    fun warn(message: String) { warnings += message }

    /**
     * Records a chain rooted at [root] showing [hunks] in order (the root first, added when missing). [covered] hunks
     * must be among [hunks] and not the root. Every [links] entry must touch a hunk of the step.
     */
    suspend fun recordStep(title: String, explanation: String, root: String, hunks: List<String>,
                           links: List<WalkLink>, covered: List<String>): StepChange {
        checkOpen()
        val ordered = (listOf(root.trim()) + hunks.map { it.trim() }).distinct()
        checkKnown(ordered + covered.map { it.trim() } + links.flatMap { listOf(it.from, it.to) })
        if (ordered.size > MAX_STEP_HUNKS) throw ToolInputException("A step may show at most $MAX_STEP_HUNKS hunks")
        val coveredSet = checkCovered(covered, ordered.toSet(), root.trim())
        val accepted = checkLinks(links, ordered.toSet())
        val step = WalkStep("S${steps.size + 1}", clipTitle(title), clipExplanation(explanation), root.trim(), ordered)
        steps += step
        val handled = apply(ordered, coveredSet)
        val (added, known) = addLinks(accepted)
        emit()
        return StepChange(step, added, known, handled)
    }

    /** Appends [hunks] to step [stepId], marking [covered] ones COVERED; a standalone step becomes a chain. */
    suspend fun extendStep(stepId: String, hunks: List<String>, links: List<WalkLink>, covered: List<String>,
                           explanation: String? = null): StepChange {
        checkOpen()
        val index = steps.indexOfFirst { it.id == stepId.trim() }
        if (index < 0) throw ToolInputException("Unknown step '$stepId'. Steps: ${steps.joinToString { it.id }.ifEmpty { "none yet" }}.")
        val old = steps[index]
        if (old.kind == WalkStepKind.UNLINKED) throw ToolInputException("The unlinked step cannot be extended")
        val additions = hunks.map { it.trim() }
        checkKnown(additions + covered.map { it.trim() } + links.flatMap { listOf(it.from, it.to) })
        val ordered = (old.hunks + additions).distinct()
        if (ordered.size > MAX_STEP_HUNKS) throw ToolInputException("A step may show at most $MAX_STEP_HUNKS hunks")
        val coveredSet = checkCovered(covered, ordered.toSet(), old.rootHunk)
        if (additions.isEmpty() && coveredSet.isEmpty() && links.isEmpty() && explanation.isNullOrBlank())
            throw ToolInputException("Give hunks, covered hunks, links or an explanation to add")
        val accepted = checkLinks(links, ordered.toSet())
        val step = old.copy(hunks = ordered, kind = WalkStepKind.CHAIN,
            explanation = explanation?.takeIf { it.isNotBlank() }?.let { clipExplanation(old.explanation + "\n\n" + it.trim()) } ?: old.explanation)
        steps[index] = step
        // Hunks already in the step keep their status unless listed as covered; newly added ones are linked.
        val handled = apply(listOf(old.rootHunk) + additions, coveredSet)
        val (added, known) = addLinks(accepted)
        emit()
        return StepChange(step, added, known, handled)
    }

    /** Adds links between any known hunks, such as across steps. Returns links added and already known. */
    suspend fun addLinks(links: List<WalkLink>, emit: Boolean): Pair<Int, Int> {
        checkOpen()
        checkKnown(links.flatMap { listOf(it.from, it.to) })
        val accepted = checkLinks(links, null)
        return addLinks(accepted).also { if (emit) emit() }
    }

    /** Gives the pending hunk [id] its own one-hunk step with [note]. */
    suspend fun markStandalone(id: String, note: String, title: String? = null): StepChange {
        checkOpen()
        val hunkId = id.trim()
        checkKnown(listOf(hunkId))
        val current = status.getValue(hunkId)
        if (current != HunkStatus.PENDING) {
            val where = stepsOf(hunkId).joinToString { it.id }
            throw ToolInputException("$hunkId is already ${current.name.lowercase()}${if (where.isEmpty()) "" else " in $where"}; " +
                "use extend_step to change how it is shown.")
        }
        if (note.isBlank()) throw ToolInputException("note must be a nonempty string")
        val hunk = byId.getValue(hunkId)
        val step = WalkStep("S${steps.size + 1}", clipTitle(title?.takeIf { it.isNotBlank() } ?: defaultTitle(hunk)),
            clipExplanation(note), hunkId, listOf(hunkId), WalkStepKind.STANDALONE)
        steps += step
        status[hunkId] = HunkStatus.STANDALONE
        emit()
        return StepChange(step, 0, 0, 1)
    }

    /** Stores verifier [confidence] on matching links. */
    suspend fun setConfidence(confidence: Map<LinkKey, Double>) {
        if (confidence.isEmpty()) return
        links.replaceAll { key, link -> confidence[key]?.let { link.copy(confidence = it) } ?: link }
        emit()
    }

    /**
     * Ends recording: every pending hunk becomes UNLINKED in the final step (see [Walkthrough.ensureEveryHunkShown]).
     * Links and warnings may still change; [markComplete] sets [Walkthrough.complete]. Does not emit.
     */
    fun finalize(): Walkthrough {
        val done = finalized ?: Walkthrough(hunks, steps.toList(), links.values.toList(), status.toMap(), triage, false, emptyList())
            .ensureEveryHunkShown().also { finalized = it }
        return done.copy(links = links.values.toList(), complete = complete, warnings = warnings.toList())
    }

    /** Finalizes when needed, marks the run complete and emits the final snapshot. */
    suspend fun markComplete(): Walkthrough {
        finalize()
        complete = true
        return snapshot().also { onUpdate(it) }
    }

    /** Sends the current snapshot to the listener. */
    suspend fun emit() = onUpdate(snapshot())

    private fun checkOpen() {
        if (finalized != null) throw ToolInputException("The walkthrough is finished; nothing more can be recorded.")
    }

    private fun checkKnown(ids: List<String>) {
        val missing = ids.map { it.trim() }.filter { it !in byId }.distinct()
        if (missing.isNotEmpty()) throw ToolInputException(unknown(missing))
    }

    private fun unknown(ids: List<String>) = "Unknown hunk id${if (ids.size == 1) "" else "s"}: ${ids.take(10).joinToString()}. " +
        "Use ids exactly as list_hunks, search_diff or walk_status print them, such as path/File.kt#2."

    private fun checkCovered(covered: List<String>, inStep: Set<String>, root: String): Set<String> {
        val ids = covered.map { it.trim() }.toSet()
        val outside = ids - inStep
        if (outside.isNotEmpty()) throw ToolInputException("Covered hunks must also be in the step's hunks: ${outside.take(10).joinToString()}")
        if (root in ids) throw ToolInputException("The root $root is analyzed, so it cannot be covered")
        return ids
    }

    private fun checkLinks(links: List<WalkLink>, inStep: Set<String>?): List<WalkLink> = links.mapIndexed { index, link ->
        val prefix = if (links.size > 1) "Link ${index + 1}: " else ""
        val from = link.from.trim()
        val to = link.to.trim()
        if (from == to) throw ToolInputException("${prefix}from and to must be different hunks")
        if (inStep != null && from !in inStep && to !in inStep)
            throw ToolInputException("${prefix}a link recorded with a step must touch one of its hunks; use add_links for others")
        if (link.reason.isBlank()) throw ToolInputException("${prefix}reason must be a nonempty string")
        link.copy(from = from, to = to, reason = link.reason.clipLine(MAX_REASON_CHARS), confidence = null)
    }

    /** Raises the status of [ids], COVERED for [covered] ones and LINKED otherwise; returns how many were pending. */
    private fun apply(ids: List<String>, covered: Set<String>): Int {
        var handled = 0
        for (id in (ids + covered).distinct()) {
            val target = if (id in covered) HunkStatus.COVERED else HunkStatus.LINKED
            val current = status.getValue(id)
            if (current == HunkStatus.PENDING) handled++
            if (target.rank > current.rank) status[id] = target
        }
        return handled
    }

    private fun addLinks(accepted: List<WalkLink>): Pair<Int, Int> {
        var added = 0
        var known = 0
        accepted.forEach { link -> if (links.putIfAbsent(link.key, link) == null) added++ else known++ }
        return added to known
    }

    private fun defaultTitle(hunk: WalkHunk) = hunk.section.ifEmpty { hunk.path.substringAfterLast('/') }

    private fun clipTitle(title: String): String = title.clipLine(MAX_TITLE_CHARS).ifEmpty { throw ToolInputException("title must be a nonempty string") }
    private fun clipExplanation(text: String): String = text.trim().let { if (it.length <= MAX_EXPLANATION_CHARS) it else it.take(MAX_EXPLANATION_CHARS - 1) + "…" }

    companion object {
        /**
         * A finished ledger holding [walkthrough] as it is, for read-only tools over a built (or pending) walkthrough, such as
         * its chats; nothing more can be recorded.
         */
        fun of(walkthrough: Walkthrough): WalkLedger = WalkLedger(walkthrough.hunks, walkthrough.triage).apply {
            status.putAll(walkthrough.status)
            steps += walkthrough.steps
            walkthrough.links.forEach { links[it.key] = it }
            warnings += walkthrough.warnings
            complete = walkthrough.complete
            finalized = walkthrough
        }

        const val MAX_STEP_HUNKS = 400
        const val MAX_TITLE_CHARS = 120
        const val MAX_EXPLANATION_CHARS = 4_000
        const val MAX_REASON_CHARS = 300
    }
}
