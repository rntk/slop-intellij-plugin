package dev.insight.diff.walk

import dev.insight.diff.ReviewFile
import dev.insight.diff.ReviewSnapshot

/**
 * One hunk of a changed file: the unit a walkthrough links. [id] is `path#index` with a one-based [index], such as
 * `src/Foo.kt#2`; it is stable for the same patch. [patch] holds the hunk's rows including its `@@` [header], whole even
 * when huge (tools page it). A file whose patch has no `@@` (binary, unreadable, a rename without edits, an empty file)
 * becomes one hunk with a blank [header] and [wholeFile] set. [patchRow] is the one-based row of the hunk's first line in
 * the file's patch, as read_diff_files numbers it. Line ranges are those of the header; whole-file hunks have zero counts.
 */
data class WalkHunk(
    val id: String,
    val path: String,
    val index: Int,
    val header: String,
    val patch: String,
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val added: Int,
    val removed: Int,
    val fileStatus: String,
    val previousPath: String? = null,
    val patchRow: Int = 1,
) {
    val wholeFile: Boolean get() = header.isEmpty()
    /** Old-side lines the hunk spans; empty for a pure insertion or a whole-file hunk. */
    val oldLines: IntRange get() = if (oldCount == 0) IntRange.EMPTY else oldStart until oldStart + oldCount
    /** New-side lines the hunk spans; empty for a pure deletion or a whole-file hunk. */
    val newLines: IntRange get() = if (newCount == 0) IntRange.EMPTY else newStart until newStart + newCount
    /** The text after the second `@@`, often the enclosing declaration; empty when the header has none. */
    val section: String get() = header.substringAfter("@@", "").substringAfter("@@", "").trim()
}

/** The hunks of every file in [snapshot], in file order. */
fun walkHunks(snapshot: ReviewSnapshot): List<WalkHunk> = walkHunks(snapshot.files)

/** The hunks of [files], in file order then patch order; every file yields at least one hunk. */
fun walkHunks(files: List<ReviewFile>): List<WalkHunk> = files.flatMap(::walkHunks)

/**
 * The hunks of [file]'s patch. Rows before the first header are dropped; bracketed loader notes after the last hunk,
 * such as a missing final newline, stay with it. A patch without headers becomes one whole-file hunk.
 */
fun walkHunks(file: ReviewFile): List<WalkHunk> {
    val rows = file.patch.replace("\r\n", "\n").split('\n').let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
    val starts = rows.indices.filter { HUNK_HEADER.containsMatchIn(rows[it]) }
    if (starts.isEmpty()) {
        val added = if (file.status == "added" || file.status == "untracked") file.after?.let(::lineCount) ?: 0 else 0
        val removed = if (file.status == "deleted") file.before?.let(::lineCount) ?: 0 else 0
        return listOf(WalkHunk("${file.path}#1", file.path, 1, "", file.patch, 0, 0, 0, 0, added, removed,
            file.status, file.previousPath, 1))
    }
    return starts.mapIndexed { position, start ->
        val end = starts.getOrNull(position + 1) ?: rows.size
        val body = rows.subList(start, end)
        val match = HUNK_HEADER.find(body[0])!!
        fun group(index: Int, default: Int) = match.groups[index]?.value?.toIntOrNull() ?: default
        WalkHunk(
            id = "${file.path}#${position + 1}", path = file.path, index = position + 1, header = body[0],
            patch = body.joinToString("\n", postfix = "\n"),
            oldStart = group(1, 0), oldCount = group(2, 1), newStart = group(3, 0), newCount = group(4, 1),
            added = body.drop(1).count { it.startsWith("+") }, removed = body.drop(1).count { it.startsWith("-") },
            fileStatus = file.status, previousPath = file.previousPath, patchRow = start + 1,
        )
    }
}

private fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.removeSuffix("\n").count { it == '\n' } + 1

private val HUNK_HEADER = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

/**
 * How one hunk relates to another, read from [WalkLink.from] to [WalkLink.to]. [outgoing] labels the link on the `from`
 * card, [incoming] on the `to` card; [symmetric] relations read the same both ways. [wire] is the tools' name.
 */
enum class WalkRelation(val wire: String, val outgoing: String, val incoming: String, val symmetric: Boolean) {
    /** `from` leads to, or requires, `to`: a renamed function causes its call sites to change. */
    CAUSES("causes", "leads to", "caused by", false),
    /** The same mechanical edit applied in both places, such as two call sites of a renamed function. */
    SAME_EDIT("same_edit", "same edit as", "same edit as", true),
    /** `to` tests `from`. */
    TESTS("tests", "tested by", "tests", false),
    /** `to` documents or configures `from`. */
    DOCUMENTS("documents", "documented by", "documents", false),
    /** Related without a clear direction. */
    RELATED("related", "related to", "related to", true);

    /** The label of a link seen from a hunk in [direction]. */
    fun label(direction: LinkDirection): String = if (direction == LinkDirection.INCOMING) incoming else outgoing

    companion object {
        fun fromWire(value: String): WalkRelation? = entries.firstOrNull { it.wire.equals(value.trim(), ignoreCase = true) }
    }
}

/** A link seen from one hunk: it starts there, ends there, or is [SYMMETRIC] and reads the same both ways. */
enum class LinkDirection { OUTGOING, INCOMING, SYMMETRIC }

/**
 * A directed link between two hunk ids with a short concrete [reason]. [confidence] is the verifier's probability that the
 * link holds, or null when it was not verified; [weak] links are kept but should be drawn dashed.
 */
data class WalkLink(
    val from: String,
    val to: String,
    val relation: WalkRelation,
    val reason: String,
    val confidence: Double? = null,
) {
    val weak: Boolean get() = confidence != null && confidence < WEAK_LINK_CONFIDENCE

    /** Identifies the link regardless of reason and confidence; symmetric links ignore their direction. */
    val key: LinkKey get() =
        if (relation.symmetric && to < from) LinkKey(to, from, relation) else LinkKey(from, to, relation)

    /** The other end of this link from [hunkId]. */
    fun other(hunkId: String): String = if (from == hunkId) to else from

    companion object {
        /** Verified links below this confidence are [weak]. */
        const val WEAK_LINK_CONFIDENCE = 0.5
    }
}

data class LinkKey(val from: String, val to: String, val relation: WalkRelation)

enum class WalkStepKind {
    /** A chain the model recorded or extended: a root and what it leads to. */
    CHAIN,
    /** One self-contained hunk with a short note. */
    STANDALONE,
    /** The final step listing every hunk the model never handled. */
    UNLINKED,
}

/**
 * One logical story: [rootHunk] first in [hunks], then the hunks it involves in reading order. [explanation] is Markdown
 * and may cite `path:line`. Steps are numbered `S1`, `S2`, ...; the unlinked step is [UNLINKED_STEP_ID].
 */
data class WalkStep(
    val id: String,
    val title: String,
    val explanation: String,
    val rootHunk: String,
    val hunks: List<String>,
    val kind: WalkStepKind = WalkStepKind.CHAIN,
) {
    companion object {
        const val UNLINKED_STEP_ID = "unlinked"
        const val UNLINKED_TITLE = "Not linked by the assistant"
    }
}

/** What happened to a hunk; only [PENDING] means it is not shown in a step yet. */
enum class HunkStatus(internal val rank: Int) {
    /** Not handled yet. */
    PENDING(0),
    /** The run ended without the model handling it; it sits in the "Not linked by the assistant" step. */
    UNLINKED(0),
    /** In a step via another hunk's analysis, not separately analyzed, such as one of many rename sites. */
    COVERED(1),
    /** Self-contained: its own one-hunk step with a short note. */
    STANDALONE(2),
    /** In a step, analyzed. */
    LINKED(3);

    val handled: Boolean get() = this != PENDING && this != UNLINKED
}

/** The links of one hunk with the same relation and direction, as a card shows them: "leads to: A, B". */
data class WalkLinkGroup(val relation: WalkRelation, val direction: LinkDirection, val links: List<WalkLink>, val hunkIds: List<String>) {
    val label: String get() = relation.label(direction)
}

/** Hunk counts per status; [total] counts every hunk. */
data class WalkCoverage(val total: Int, val counts: Map<HunkStatus, Int>) {
    operator fun get(status: HunkStatus): Int = counts[status] ?: 0
    /** Hunks the model handled: linked, covered or standalone. */
    val handled: Int get() = this[HunkStatus.LINKED] + this[HunkStatus.COVERED] + this[HunkStatus.STANDALONE]
    val pending: Int get() = this[HunkStatus.PENDING]
}

/**
 * An immutable walkthrough of a diff: its [hunks], the [steps] telling their stories, [links] between hunks, and each hunk's
 * [status]. [triage] holds the cheap per-hunk classification when one ran. [complete] is true once a run finished normally;
 * a cancelled run's final snapshot is finalized (every hunk shown) but not complete. [warnings] say what limited the run,
 * such as a request budget or a failed triage, in user-facing words.
 */
data class Walkthrough(
    val hunks: List<WalkHunk>,
    val steps: List<WalkStep> = emptyList(),
    val links: List<WalkLink> = emptyList(),
    val status: Map<String, HunkStatus> = hunks.associate { it.id to HunkStatus.PENDING },
    val triage: Map<String, HunkTriage>? = null,
    val complete: Boolean = false,
    val warnings: List<String> = emptyList(),
) {
    private val byId by lazy { hunks.associateBy { it.id } }
    private val stepIndex by lazy {
        val index = HashMap<String, MutableList<WalkStep>>()
        steps.forEach { step -> step.hunks.distinct().forEach { index.getOrPut(it) { mutableListOf() } += step } }
        index
    }
    private val linkIndex by lazy {
        val index = HashMap<String, MutableList<WalkLink>>()
        links.forEach { link ->
            index.getOrPut(link.from) { mutableListOf() } += link
            if (link.to != link.from) index.getOrPut(link.to) { mutableListOf() } += link
        }
        index
    }

    fun hunk(id: String): WalkHunk? = byId[id]
    fun hunksOf(path: String): List<WalkHunk> = hunks.filter { it.path == path }
    fun statusOf(id: String): HunkStatus = status[id] ?: HunkStatus.PENDING
    fun step(id: String): WalkStep? = steps.firstOrNull { it.id == id }

    /** Steps showing [hunkId], in step order. */
    fun stepsOf(hunkId: String): List<WalkStep> = stepIndex[hunkId].orEmpty()

    /** The step [hunkId] belongs to most: the one it is root of, else the first showing it. */
    fun primaryStep(hunkId: String): WalkStep? = stepsOf(hunkId).let { steps -> steps.firstOrNull { it.rootHunk == hunkId } ?: steps.firstOrNull() }

    /** Every link touching [hunkId]. */
    fun linksTouching(hunkId: String): List<WalkLink> = linkIndex[hunkId].orEmpty()

    /**
     * [hunkId]'s links grouped by relation and direction, in a stable order: caused by, leads to, same edit, tests,
     * documents, related.
     */
    fun linksOf(hunkId: String): List<WalkLinkGroup> = linksTouching(hunkId)
        .groupBy { link ->
            link.relation to when {
                link.relation.symmetric -> LinkDirection.SYMMETRIC
                link.from == hunkId -> LinkDirection.OUTGOING
                else -> LinkDirection.INCOMING
            }
        }
        .entries.sortedWith(compareBy({ it.key.first.ordinal }, { if (it.key.second == LinkDirection.INCOMING) 0 else 1 }))
        .map { (key, links) -> WalkLinkGroup(key.first, key.second, links, links.map { it.other(hunkId) }.distinct()) }

    /** Hunks linked to [hunkId] directly, optionally without [weak][WalkLink.weak] links: the cards a click reveals. */
    fun neighbors(hunkId: String, includeWeak: Boolean = true): List<String> =
        linksTouching(hunkId).filter { includeWeak || !it.weak }.map { it.other(hunkId) }.distinct()

    fun coverage(): WalkCoverage = WalkCoverage(hunks.size, hunks.groupingBy { statusOf(it.id) }.eachCount())

    /** Hunks a user could not see yet: pending, or in no step. Empty after a finalized run. */
    fun missingHunks(): List<String> = hunks.map { it.id }.filter { statusOf(it) == HunkStatus.PENDING || stepIndex[it] == null }

    /**
     * This walkthrough with every [missing][missingHunks] hunk marked [HunkStatus.UNLINKED] and listed, by path and index,
     * in the final "Not linked by the assistant" step, so no hunk disappears. Returns this when nothing is missing.
     */
    fun ensureEveryHunkShown(): Walkthrough {
        val missing = missingHunks().toSet()
        if (missing.isEmpty()) return this
        val existing = steps.firstOrNull { it.kind == WalkStepKind.UNLINKED }
        val ordered = (existing?.hunks.orEmpty() + hunks.filter { it.id in missing }.sortedWith(compareBy({ it.path }, { it.index })).map { it.id }).distinct()
        val step = WalkStep(WalkStep.UNLINKED_STEP_ID, WalkStep.UNLINKED_TITLE,
            "The assistant did not analyze or link these changes. Review them directly.", ordered.first(), ordered, WalkStepKind.UNLINKED)
        return copy(
            steps = steps.filter { it.kind != WalkStepKind.UNLINKED } + step,
            status = status + missing.associateWith { HunkStatus.UNLINKED },
        )
    }

    companion object {
        /** A walkthrough of [hunks] before any run: all pending, no steps. Use it to show cards without the model. */
        fun pending(hunks: List<WalkHunk>): Walkthrough = Walkthrough(hunks)

        /** Every hunk of [files] in the unlinked step: what to show when no model is configured. */
        fun unlinked(files: List<ReviewFile>): Walkthrough = Walkthrough(walkHunks(files)).ensureEveryHunkShown()
    }
}
