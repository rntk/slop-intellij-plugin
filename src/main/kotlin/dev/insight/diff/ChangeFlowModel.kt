package dev.insight.diff

import dev.insight.callgraph.LineChange
import dev.insight.callgraph.SymbolKind
import dev.insight.chat.ToolInputException

/** How a function differs between the compared sides, decided from source text alone. [CONTEXT] is unchanged code shown for connection. */
enum class ChangeStatus(val id: String, val badge: String) {
    ADDED("added", "NEW"), MODIFIED("modified", "MOD"), DELETED("deleted", "DEL"), CONTEXT("unchanged", "ctx")
}

/** Differences a reader can usually skip; real code changes have none. */
enum class TrivialChange(val id: String) { FORMATTING("whitespace only"), COMMENTS("comments only") }

/**
 * A declaration on one side of a file, PSI-free. [text] is the declaration's own text with nested functions and classes
 * removed, so editing a local function changes that function and not its owner. [code] encodes PSI leaves without
 * comments, retaining token boundaries and literal contents. Whitespace is ignored only for Java.
 */
data class SideDeclaration(
    val name: String,
    val qualifiedName: String,
    val kind: SymbolKind,
    val header: String,
    val text: String,
    val code: String,
    /** One-based, inclusive. */
    val lines: IntRange,
    val offset: Int,
    /** Lines of nested functions and classes, which count towards those declarations instead. */
    val nested: List<IntRange> = emptyList(),
    /** PSI token evidence preserving literal contents and token boundaries. */
    val formattingTokens: List<String>? = null,
)

/** One line of a declaration's literal diff: [kind] is ' ', '+', '-' or '…' for a run of [text] unchanged lines left out. */
data class HunkLine(val kind: Char, val text: String, val before: Int? = null, val after: Int? = null)

/**
 * An in-project call or an exit (`return`, `throw`, `raise`) in a function body, in source order. [conditions] are the
 * enclosing `if`/`when`/loop/`catch` headers, outermost first. [target] is a node of the graph when the callee is one.
 */
data class ChangeStep(
    val line: Int,
    val text: String,
    val exit: Boolean,
    val conditions: List<String>,
    val target: Int?,
    val callee: String?,
    val changed: Boolean,
)

/** An unchanged in-project function that references a changed one: who is affected by the change. */
data class ChangeCaller(val qualifiedName: String, val path: String, val line: Int, val test: Boolean, val absolutePath: String?, val offset: Int?)

data class ChangeNode(
    val id: Int,
    val path: String,
    val absolutePath: String,
    val name: String,
    val qualifiedName: String,
    val status: ChangeStatus,
    val header: String,
    /** On the after side, or on the before side for deletions. */
    val line: Int,
    val trivial: TrivialChange? = null,
    val signatureChanged: Boolean = false,
    val added: Int = 0,
    val removed: Int = 0,
    val test: Boolean = false,
    /** Name offset in live project source, for navigation; null when the after side is not the project's current source. */
    val offset: Int? = null,
    /** Null when calls could not be resolved, as for deletions or historical revisions. */
    val steps: List<ChangeStep>? = null,
    val callers: List<ChangeCaller> = emptyList(),
    val callersTruncated: Boolean = false,
    /** False when the reference search was skipped (bounded per diff), so an empty [callers] proves nothing. */
    val callersSearched: Boolean = false,
    val hunk: List<HunkLine> = emptyList(),
    val hunkTruncated: Boolean = false,
    val stepsTruncated: Boolean = false,
) {
    val changed: Boolean get() = status != ChangeStatus.CONTEXT
    val churn: Int get() = added + removed
}

/** A statically resolved call on the after side. */
data class ChangeEdge(val from: Int, val to: Int)

/** Connected changed functions, or the unconnected changes of one file ([file]). Membership is structural, never inferred. */
data class ChangeCluster(val id: Int, val members: List<Int>, val title: String, val file: String? = null)

/** [analysis] is null when declarations were compared, otherwise why the file's code was not analyzed. */
data class ChangeFile(
    val path: String,
    val status: String,
    val analysis: String? = null,
    /** True when calls were resolved against live project source. */
    val calls: Boolean = false,
    val outsideAdded: Int = 0,
    val outsideRemoved: Int = 0,
    /** Changed classes and properties, such as "added class Foo". */
    val declarations: List<String> = emptyList(),
)

/** The deterministic layer: every node, edge, cluster and count comes from source analysis of one snapshot. */
data class ChangeGraph(
    val nodes: List<ChangeNode>,
    val edges: List<ChangeEdge>,
    val clusters: List<ChangeCluster>,
    val files: List<ChangeFile>,
    val truncated: Boolean = false,
) {
    init { require(nodes.withIndex().all { (index, node) -> node.id == index }) { "Node ids must be their indexes" } }

    private val out: Map<Int, List<Int>> by lazy { edges.groupBy({ it.from }, { it.to }) }
    private val into: Map<Int, List<Int>> by lazy { edges.groupBy({ it.to }, { it.from }) }
    private val owner: Map<Int, ChangeCluster> by lazy { clusters.flatMap { c -> c.members.map { it to c } }.toMap() }

    fun callees(id: Int): List<Int> = out[id].orEmpty()
    fun callers(id: Int): List<Int> = into[id].orEmpty()
    fun cluster(node: Int): ChangeCluster? = owner[node]
    val changed: List<ChangeNode> get() = nodes.filter { it.changed }
}

/** Closed vocabularies: the model classifies, code decides what that means for the view. */
enum class ChangeIntent(val id: String, val label: String) {
    NEW_BEHAVIOR("new-behavior", "new behavior"),
    BEHAVIOR_CHANGE("behavior-change", "behavior change"),
    FIX("fix", "fix"),
    REFACTOR("refactor", "refactor"),
    CLEANUP("cleanup", "cleanup"),
    WIRING("wiring", "wiring"),
    TEST("test", "test");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/** [MINOR] changes fold out of the overview; [KEY] changes are where a reviewer should start. */
enum class ChangeImportance(val id: String) {
    KEY("key"), SUPPORTING("supporting"), MINOR("minor");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

data class NodeAnnotation(val summary: String, val intent: ChangeIntent, val importance: ChangeImportance, val attention: String? = null)
data class ClusterAnnotation(val title: String, val summary: String? = null)
/** The model's grouping of whole clusters (and optionally other changed files) into one reader-level change. */
data class ChangeTheme(val title: String, val summary: String?, val clusters: List<Int>, val files: List<String> = emptyList())

/** A validated batch from the model; [themes] replaces every theme when present. */
data class FlowAnnotationUpdate(
    val nodes: Map<Int, NodeAnnotation> = emptyMap(),
    val clusters: Map<Int, ClusterAnnotation> = emptyMap(),
    val themes: List<ChangeTheme>? = null,
)

/** A top-level entry of the view. [inferred] themes come from the model; the others are one structural cluster each. */
data class DisplayTheme(val title: String, val summary: String?, val clusters: List<ChangeCluster>, val files: List<String>, val inferred: Boolean)

data class FlowCoverage(val nodes: List<Int>, val clusters: List<Int>) {
    val complete: Boolean get() = nodes.isEmpty() && clusters.isEmpty()
}

/** Graph plus interpretations. Interpretations attach to existing ids only; they never change the graph. */
data class ChangeFlows(
    val graph: ChangeGraph,
    val nodeNotes: Map<Int, NodeAnnotation> = emptyMap(),
    val clusterNotes: Map<Int, ClusterAnnotation> = emptyMap(),
    val themes: List<ChangeTheme> = emptyList(),
) {
    /** Validates the whole batch before applying any of it. */
    fun annotate(update: FlowAnnotationUpdate): ChangeFlows {
        if (update.nodes.isEmpty() && update.clusters.isEmpty() && update.themes == null)
            throw ToolInputException("Provide nodes, clusters or themes to annotate.")
        if (update.nodes.size > MAX_NODE_NOTES) throw ToolInputException("Annotate at most $MAX_NODE_NOTES nodes per call.")
        update.nodes.forEach { (id, note) ->
            val node = graph.nodes.getOrNull(id) ?: throw ToolInputException("Node $id is not in the change graph.")
            if (!node.changed) throw ToolInputException("Node $id (${node.qualifiedName}) is unchanged context; annotate changed nodes only.")
            text(note.summary, "Node $id summary", MAX_SUMMARY)
            note.attention?.let { text(it, "Node $id attention", MAX_ATTENTION) }
        }
        update.clusters.forEach { (id, note) ->
            if (graph.clusters.none { it.id == id }) throw ToolInputException("Cluster $id is not in the change graph.")
            text(note.title, "Cluster $id title", MAX_TITLE)
            note.summary?.let { text(it, "Cluster $id summary", MAX_THEME_SUMMARY) }
        }
        update.themes?.let { themes ->
            if (themes.size !in 1..MAX_THEMES) throw ToolInputException("Use 1 to $MAX_THEMES themes.")
            val clusters = hashSetOf<Int>()
            val files = hashSetOf<String>()
            val known = graph.files.mapTo(hashSetOf()) { it.path }
            themes.forEachIndexed { index, theme ->
                val prefix = "Theme ${index + 1}"
                text(theme.title, "$prefix title", MAX_TITLE)
                theme.summary?.let { text(it, "$prefix summary", MAX_THEME_SUMMARY) }
                if (theme.clusters.isEmpty() && theme.files.isEmpty()) throw ToolInputException("$prefix needs at least one cluster or file.")
                theme.clusters.forEach {
                    if (graph.clusters.none { cluster -> cluster.id == it }) throw ToolInputException("$prefix: cluster $it is not in the change graph.")
                    if (!clusters.add(it)) throw ToolInputException("$prefix: cluster $it is already in another theme. Each cluster belongs to one theme.")
                }
                theme.files.forEach {
                    if (it !in known) throw ToolInputException("$prefix: $it is not a file of this diff.")
                    if (!files.add(it)) throw ToolInputException("$prefix: $it is already in another theme.")
                }
            }
        }
        return copy(
            nodeNotes = nodeNotes + update.nodes.mapValues { (_, note) -> note.copy(summary = note.summary.trim(), attention = note.attention?.trim()?.ifEmpty { null }) },
            clusterNotes = clusterNotes + update.clusters.mapValues { (_, note) -> note.copy(title = note.title.trim(), summary = note.summary?.trim()?.ifEmpty { null }) },
            themes = update.themes?.map { it.copy(title = it.title.trim(), summary = it.summary?.trim()?.ifEmpty { null },
                clusters = it.clusters.distinct(), files = it.files.distinct()) } ?: themes,
        )
    }

    /** Model themes first, in the model's order; clusters it left out keep their structural titles, in graph order. */
    fun displayThemes(): List<DisplayTheme> {
        val byId = graph.clusters.associateBy { it.id }
        val inferred = themes.map { theme -> DisplayTheme(theme.title, theme.summary, theme.clusters.mapNotNull(byId::get), theme.files, true) }
        val themed = themes.flatMapTo(hashSetOf()) { it.clusters }
        return inferred + graph.clusters.filter { it.id !in themed }.map { cluster ->
            val note = clusterNotes[cluster.id]
            DisplayTheme(note?.title ?: cluster.title, note?.summary, listOf(cluster), emptyList(), note != null)
        }
    }

    /** Changed files that no cluster or theme covers, such as documentation, configuration or unanalyzed code. */
    fun otherFiles(): List<ChangeFile> {
        val clustered = graph.nodes.filter { it.changed }.mapTo(hashSetOf()) { it.path }
        val themed = themes.flatMapTo(hashSetOf()) { it.files }
        return graph.files.filter { it.path !in clustered && it.path !in themed }
    }

    /**
     * What the model still has to classify: changed code nodes (the [MAX_REQUIRED_NODES] largest, so huge diffs stay
     * bounded) and clusters without a theme. Whitespace- and comment-only changes need no interpretation.
     */
    fun uncovered(): FlowCoverage {
        val nodes = graph.nodes.filter { it.changed && it.trivial == null }.sortedByDescending { it.churn }
            .take(MAX_REQUIRED_NODES).map { it.id }.filter { it !in nodeNotes }.sorted()
        val themed = themes.flatMapTo(hashSetOf()) { it.clusters }
        return FlowCoverage(nodes, graph.clusters.map { it.id }.filter { it !in themed })
    }

    private fun text(value: String, label: String, max: Int) {
        if (value.isBlank() || value.length > max) throw ToolInputException("$label must be 1 to $max characters.")
    }

    companion object {
        const val MAX_NODE_NOTES = 80
        const val MAX_REQUIRED_NODES = 60
        const val MAX_THEMES = 12
        const val MAX_TITLE = 70
        const val MAX_SUMMARY = 240
        const val MAX_THEME_SUMMARY = 300
        const val MAX_ATTENTION = 160
    }
}

// ---- Declaration matching and classification -------------------------------------------------------------------------

/**
 * Pairs declarations of one kind across sides: first identical name and header (so overloads keep their partners), then
 * the remaining same-name declarations in source order (a changed signature). Everything else is added or deleted;
 * a rename reads as one of each.
 */
internal fun matchDeclarations(before: List<SideDeclaration>, after: List<SideDeclaration>): List<Pair<SideDeclaration?, SideDeclaration?>> {
    val pairs = mutableListOf<Pair<SideDeclaration?, SideDeclaration?>>()
    val left = before.toMutableList()
    val right = after.toMutableList()
    fun pass(key: (SideDeclaration) -> Any) {
        val pending = left.groupBy(key).mapValues { it.value.toMutableList() }
        for (declaration in right.toList()) {
            val partner = pending[key(declaration)]?.removeFirstOrNull() ?: continue
            pairs += partner to declaration
            left.remove(partner); right.remove(declaration)
        }
    }
    pass { Triple(it.qualifiedName, it.kind, it.header) }
    pass { it.qualifiedName to it.kind }
    pairs += right.map { null to it }
    pairs += left.map { it to null }
    return pairs
}

/** Null when both sides are textually identical (apart from nested declarations, which are classified on their own). */
internal fun classify(before: SideDeclaration?, after: SideDeclaration?): Triple<ChangeStatus, TrivialChange?, Boolean>? = when {
    before == null && after == null -> null
    before == null -> Triple(ChangeStatus.ADDED, null, false)
    after == null -> Triple(ChangeStatus.DELETED, null, false)
    before.text == after.text -> null
    before.formattingTokens != null && before.formattingTokens == after.formattingTokens -> Triple(ChangeStatus.MODIFIED, TrivialChange.FORMATTING, false)
    before.code == after.code -> Triple(ChangeStatus.MODIFIED, TrivialChange.COMMENTS, false)
    else -> Triple(ChangeStatus.MODIFIED, null, spacing(before.header) != spacing(after.header))
}

/** Whitespace-insensitive form: runs collapse, and space next to punctuation goes, so `a+b` equals `a + b`. */
internal fun spacing(text: String): String =
    text.replace(Regex("\\s+"), " ").replace(Regex(" ?([^\\w ]) ?"), "$1").trim()

/**
 * The declaration header without annotations or decorators: from the first other line up to its body (`{`, an
 * expression body `=` or the end of the line after the parameter list). Whitespace is collapsed.
 */
internal fun declarationHeader(code: String): String {
    val lines = code.lines().map { it.trim() }.dropWhile { it.isEmpty() || it.startsWith("@") }
    val text = lines.joinToString("\n")
    var depth = 0
    var closed = false
    for ((i, c) in text.withIndex()) {
        when (c) {
            '(', '[', '<' -> depth++
            ')', ']', '>' -> { if (depth > 0) depth--; if (depth == 0 && c == ')') closed = true }
            '{' -> if (depth == 0) return clipHeader(text.substring(0, i))
            '=' -> if (depth == 0 && text.getOrNull(i + 1) != '=' && text.getOrNull(i + 1) != '>' &&
                text.getOrNull(i - 1)?.let { it in "=!<>+-*/%&|^" } != true) return clipHeader(text.substring(0, i))
            '\n' -> if (depth == 0 && closed) return clipHeader(text.substring(0, i))
        }
    }
    return clipHeader(text)
}

private fun clipHeader(text: String): String = text.replace(Regex("\\s+"), " ").trim().let { if (it.length <= 200) it else it.take(199) + "…" }

/** Zero-based changed line indexes on each side of one file. */
internal class ChangedLines(changes: List<LineChange>) {
    val before: Set<Int> = changes.flatMapTo(hashSetOf()) { it.start1 until it.end1 }
    val after: Set<Int> = changes.flatMapTo(hashSetOf()) { it.start2 until it.end2 }

    /** Changed lines of [lines] (one-based) on one side, without the lines of nested declarations. */
    fun count(side: Set<Int>, lines: IntRange?, nested: List<IntRange> = emptyList()): Int =
        lines?.count { (it - 1) in side && nested.none { range -> it in range } } ?: 0
}

/**
 * The literal diff of one declaration: lines inside [beforeRange] or [afterRange] (one-based), aligned as in a unified
 * diff. Unchanged runs longer than twice [context] keep [context] lines at each end. Returns the lines and whether
 * [limit] cut them off.
 */
internal fun declarationHunk(
    before: List<String>, after: List<String>, changes: List<LineChange>,
    beforeRange: IntRange?, afterRange: IntRange?, context: Int = 2, limit: Int = 160,
): Pair<List<HunkLine>, Boolean> {
    val aligned = mutableListOf<HunkLine>()
    fun keep(old: Int?, new: Int?) = (old != null && beforeRange != null && old + 1 in beforeRange) ||
        (new != null && afterRange != null && new + 1 in afterRange)
    var a = 0
    var c = 0
    fun same(untilA: Int, untilC: Int) {
        while (a < untilA && c < untilC) {
            if (keep(a, c)) aligned += HunkLine(' ', after[c], a + 1, c + 1)
            a++; c++
        }
    }
    for (change in changes.sortedBy { it.start1 }) {
        same(change.start1.coerceAtMost(before.size), change.start2.coerceAtMost(after.size))
        while (a < minOf(change.end1, before.size)) { if (keep(a, null)) aligned += HunkLine('-', before[a], before = a + 1); a++ }
        while (c < minOf(change.end2, after.size)) { if (keep(null, c)) aligned += HunkLine('+', after[c], after = c + 1); c++ }
    }
    same(before.size, after.size)
    // Collapse long unchanged runs, keeping a little context around each change.
    val result = mutableListOf<HunkLine>()
    var i = 0
    while (i < aligned.size) {
        if (aligned[i].kind != ' ') { result += aligned[i++]; continue }
        var j = i
        while (j < aligned.size && aligned[j].kind == ' ') j++
        val run = aligned.subList(i, j)
        val head = if (i == 0) 0 else context
        val tail = if (j == aligned.size) 0 else context
        if (run.size > head + tail + 1) {
            result += run.take(head)
            result += HunkLine('…', "${run.size - head - tail} unchanged lines")
            result += run.takeLast(tail)
        } else result += run
        i = j
    }
    return if (result.size <= limit) result to false else result.take(limit) to true
}

/** Splits like the review patch: a trailing newline does not start another line. */
internal fun sourceLines(text: String): List<String> =
    if (text.isEmpty()) emptyList() else text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

// ---- Clustering ------------------------------------------------------------------------------------------------------

/**
 * Changed functions connected by resolved calls form one cluster, including through one unchanged function that a
 * changed function calls and that calls a changed function (a bridge, kept as context). Changed functions without any
 * such call are grouped per file. Larger clusters come first; clusters of only tests, then of only trivial edits, last.
 */
internal fun clusterChanges(nodes: List<ChangeNode>, edges: List<ChangeEdge>): List<ChangeCluster> {
    val parent = IntArray(nodes.size) { it }
    fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var y = x; while (parent[y] != r) { val n = parent[y]; parent[y] = r; y = n }; return r }
    fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
    val linked = hashSetOf<Int>()
    for (edge in edges) {
        if (edge.from == edge.to) continue
        val from = nodes[edge.from]
        val to = nodes[edge.to]
        if (from.changed && to.changed) { union(edge.from, edge.to); linked += edge.from; linked += edge.to }
    }
    for (bridge in nodes.filter { !it.changed }) {
        val callers = edges.filter { it.to == bridge.id && nodes[it.from].changed }.map { it.from }
        val callees = edges.filter { it.from == bridge.id && nodes[it.to].changed }.map { it.to }
        if (callers.isEmpty() || callees.isEmpty()) continue
        (callers + callees).forEach { union(it, bridge.id); linked += it }
        linked += bridge.id
    }
    val groups = linkedMapOf<Any, MutableList<Int>>()
    for (node in nodes) {
        if (node.id in linked) groups.getOrPut(find(node.id)) { mutableListOf() } += node.id
        else if (node.changed) groups.getOrPut("file:" + node.path) { mutableListOf() } += node.id
    }
    fun rank(members: List<Int>): Int {
        val changed = members.map { nodes[it] }.filter { it.changed }
        return when {
            changed.all { it.trivial != null } -> 2
            changed.all { it.test } -> 1
            else -> 0
        }
    }
    val ordered = groups.entries.sortedWith(compareBy<Map.Entry<Any, MutableList<Int>>> { rank(it.value) }
        .thenByDescending { entry -> entry.value.sumOf { nodes[it].churn } }
        .thenBy { entry -> entry.value.minOf { it } })
    return ordered.mapIndexed { index, (key, members) ->
        val file = (key as? String)?.removePrefix("file:")
        ChangeCluster(index, members.sorted(), clusterTitle(nodes, edges, members, file), file)
    }
}

/** Structural names say what the cluster is made of, never what it is for. */
private fun clusterTitle(nodes: List<ChangeNode>, edges: List<ChangeEdge>, members: List<Int>, file: String?): String {
    if (file != null) return "Changes in ${file.substringAfterLast('/')}"
    val roots = flowRoots(nodes, edges, members)
    val names = roots.take(2).joinToString(", ") { nodes[it].qualifiedName }
    return "Flow from $names" + if (roots.size > 2) " (+${roots.size - 2})" else ""
}

/** Members not called from inside the cluster by non-test code; tests are set apart unless only tests are left. */
internal fun flowRoots(nodes: List<ChangeNode>, edges: List<ChangeEdge>, members: List<Int>): List<Int> {
    val set = members.toHashSet()
    val code = members.filter { !nodes[it].test }
    val pool = code.ifEmpty { members }
    val called = edges.filter { it.from in set && it.to in set && it.from != it.to && !nodes[it.from].test }.mapTo(hashSetOf()) { it.to }
    return pool.filter { it !in called && (nodes[it].changed || edges.any { e -> e.from == it && e.to in set }) }
        .ifEmpty { listOfNotNull(pool.minOrNull()) }
}
