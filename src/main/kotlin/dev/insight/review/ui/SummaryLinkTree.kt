package dev.insight.review.ui

import dev.insight.review.*

/** An occurrence, rather than a summary node: a claim can contribute to several different branches. */
internal data class SummaryLinkNode(
    val key: String,
    val text: String,
    val owner: SummaryNode,
    val file: Boolean = false,
    val children: List<SummaryLinkNode> = emptyList(),
)

/** Follow recorded claims, preserving independent parts instead of inferring relationships from words. */
internal fun summaryLinkTree(summaries: ReviewSummaries, visible: (SummaryNode) -> Boolean = { true }): SummaryLinkNode {
    val tree = summaries.tree
    fun build(node: SummaryNode, selected: List<Int>?, key: String): List<SummaryLinkNode> {
        if (!visible(node)) return emptyList()
        val summary = summaries.summary(node.id)
        val claims = selected ?: summary?.claims.orEmpty()
        val parts = claims.mapNotNull { index ->
            val segment = summary?.segments?.getOrNull(index) ?: return@mapNotNull null
            val links = segment.sources.filterIsInstance<SummarySource.Claim>()
                .groupBy { it.child }
            val children = links.flatMap { (position, sources) ->
                node.children.getOrNull(position)?.let { build(tree[it], sources.map { it.segment }.distinct(), "$key/$index/$position") }.orEmpty()
            }.ifEmpty {
                if (node.kind == SummaryKind.FILE || node.kind == SummaryKind.CHUNK) {
                    val file = if (node.kind == SummaryKind.FILE) node else tree.nodes.firstOrNull { it.kind == SummaryKind.FILE && it.path == node.path }
                    file?.let { listOf(SummaryLinkNode("$key/$index/file", it.label, it, file = true)) }.orEmpty()
                } else emptyList()
            }
            SummaryLinkNode("$key/$index", segment.text.trim(), node, children = children)
        }
        if (selected != null) return parts
        // Missing summaries and uncited children remain reachable while generation is in progress.
        val cited = claims.flatMap { summary?.segments?.getOrNull(it)?.sources.orEmpty() }
            .filterIsInstance<SummarySource.Claim>().map { it.child }.toSet()
        val rest = node.children.withIndex().filter { it.index !in cited }.flatMap { (position, id) ->
            build(tree[id], null, "$key/uncited/$position")
        }
        if (parts.isEmpty() && rest.isEmpty() && node.kind == SummaryKind.FILE)
            return listOf(SummaryLinkNode("$key/file", node.label, node, file = true))
        return parts + rest
    }
    val root = tree.root
    val parts = build(root, null, root.identity)
    return parts.singleOrNull()
        ?: SummaryLinkNode(root.identity + "/empty", "", root, children = parts)
}
