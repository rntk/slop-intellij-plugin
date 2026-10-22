package dev.insight.diff

import kotlinx.coroutines.Job

internal data class ContextSummaryRequest(val query: String, val nodeIds: Set<Int>)

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
    override fun toString() = "${id}. ${request.query}"
}
