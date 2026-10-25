package dev.insight.diff

import kotlinx.coroutines.Job

internal data class ContextSummaryRequest(
    val query: String,
    val nodeIds: Set<Int>,
    val instructions: String = "",
    val regenerate: Boolean = false,
    val label: String = query,
)

/** A single entry uses existing child summaries, generating missing dependencies when necessary. */
internal fun resummaryRequest(summaries: ChangeSummaries, node: SummaryNode, recursive: Boolean,
                             instructions: String,
                             /** The descendants a recursive run regenerates; the others are written only when missing. */
                             regenerates: (SummaryNode) -> Boolean = { true }): ContextSummaryRequest {
    val ids = linkedSetOf(node.id)
    fun includeChildren(id: Int) {
        for (child in summaries.tree[id].children) {
            if (recursive && regenerates(summaries.tree[child]) || summaries.text(child) == null) {
                ids += child
                includeChildren(child)
            }
        }
    }
    includeChildren(node.id)
    return ContextSummaryRequest("", ids, instructions.trim(), regenerate = true,
        label = (if (recursive) "Resummarize all · " else "Resummarize · ") + node.entryLabel)
}

/** Every missing summary beneath and including [node], so one topic can be filled without the rest of the hierarchy. */
internal fun fillSummaryRequest(summaries: ChangeSummaries, node: SummaryNode): ContextSummaryRequest {
    val ids = linkedSetOf<Int>()
    fun visit(id: Int) {
        if (summaries.text(id) == null) ids += id
        summaries.tree[id].children.forEach(::visit)
    }
    visit(node.id)
    return ContextSummaryRequest("", ids, label = "Fill summaries · " + node.entryLabel)
}

/**
 * The summaries a targeted run has written into [updated], to save over [current]: the [ids] it rewrote, and any summary
 * [current] lacks beneath those entries, such as a seeded child a rewritten parent was written from. Children come before
 * parents, in batches the summary model accepts, and an entry whose children cannot all be saved waits. Empty once the tree beneath the run changed,
 * as a reclassification or refresh replaces it.
 */
internal fun targetedEntries(current: ChangeSummaries, updated: ChangeSummaries, ids: Set<Int>): List<Map<Int, String>> {
    val tree = current.tree
    if (updated.tree.root.key != tree.root.key || updated.tree.nodes.size != tree.nodes.size) return emptyList()
    val scope = ids.toMutableSet()
    // Include seeded dependencies of the requested entries, but never unrelated branches.
    for (node in tree.nodes) if (node.id in scope) scope.addAll(node.children)
    val saved = HashSet<Int>()
    val entries = mutableListOf<Pair<Int, String>>()
    // Pre-order: walking backwards visits children before their parents.
    for (node in tree.nodes.asReversed()) {
        if (node.id !in scope) continue
        val text = updated.text(node.id) ?: continue
        val old = current.text(node.id)
        if (node.children.any { current.text(it) == null && it !in saved }) continue
        if (old == null || node.id in ids && text != old) { entries += node.id to text; saved += node.id }
    }
    return entries.chunked(ChangeSummaries.MAX_PER_CALL) { it.toMap() }
}

/** A topic reads as its topic path; files and folders as their location. */
internal val SummaryNode.entryLabel: String
    get() = if (isTopicBranch) path.ifEmpty { label } else location

/** Matches and their descendants, without the ancestors used to reach them. */
internal fun relatedSummaryNodes(tree: ChangeSummaryTree, identities: List<String>): Set<String> {
    val related = identities.toHashSet()
    for (node in tree.nodes) if (node.identity in related) node.children.forEach { related += tree[it].identity }
    return related
}

/** Publishes immutable summary snapshots and completion on the EDT. */
internal fun interface ContextSummaryLauncher {
    fun launch(start: ChangeSummaries, request: ContextSummaryRequest,
               updated: (ChangeSummaries) -> Unit, done: (Result<ChangeSummaryRun>) -> Unit): Job
}

/** A generated version belongs to the review; tabs choose which one they read independently. */
internal class ContextSummaryVersion(val id: Int, var request: ContextSummaryRequest, var summaries: ChangeSummaries) {
    val related = request.nodeIds.mapTo(hashSetOf()) { summaries.tree[it].identity }
    var job: Job? = null
    var running = true
    var error: String? = null
    override fun toString() = "${id}. ${request.label}"
}
