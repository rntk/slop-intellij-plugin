package dev.insight.review

import dev.insight.agent.ToolChatRunner
import dev.insight.agent.ToolInputException

/**
 * Shared paginated JSON serializer for review summary trees (used by both diff and code review tools).
 * Traverses [summaries] in pre-order (optionally rooted under [under]), builds each node's base metadata
 * plus caller-supplied [nodeExtras], and stops before the serialized JSON exceeds [maxChars].
 */
internal fun reviewSummariesJson(
    summaries: ReviewSummaries,
    offset: Int,
    under: String? = null,
    evidence: String,
    noteWhenEmpty: String? = null,
    useTreeTotals: Boolean = false,
    maxChars: Int = ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS,
    nodeExtras: (SummaryNode, MutableMap<String, Any>) -> Unit = { _, _ -> },
): String {
    val tree = summaries.tree
    val top = under?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != "./" }?.let { subtreeRoot(summaries, it) }
        ?: tree.root
    val wholeTree = top.id == tree.root.id
    val scope = if (wholeTree) tree.nodes else {
        // In pre-order a subtree ends at the last leaf of its last child.
        var last = top
        while (last.children.isNotEmpty()) last = tree[last.children.last()]
        tree.nodes.subList(top.id, last.id + 1)
    }
    if (offset !in 0..scope.size) throw ToolInputException("Offset is outside the node list")
    val nodes = mutableListOf<Map<String, Any>>()
    val totalNodes = if (useTreeTotals && wholeTree) summaries.total else scope.size
    val summarized = if (useTreeTotals && wholeTree) summaries.count else scope.count { summaries.text(it.id) != null }
    fun render(): String = reviewJson.toJson(buildMap {
        put("evidence", evidence)
        if (top.id != tree.root.id) put("under", top.location)
        put("totals", mapOf("nodes" to totalNodes, "files" to top.files.size, "summarized" to summarized,
            "missing" to totalNodes - summarized))
        if (noteWhenEmpty != null && summaries.count == 0) put("note", noteWhenEmpty)
        put("nodes", nodes)
        (offset + nodes.size).takeIf { it < scope.size }?.let { put("next_offset", it) }
    })
    for (index in offset until scope.size) {
        val node = scope[index]
        nodes += buildMap {
            put("id", node.id)
            put("kind", node.kind.id)
            put("location", node.location)
            node.parent?.let { put("parent", it) }
            nodeExtras(node, this)
            summaries.text(node.id)?.let { put("summary", it) }
        }
        if (render().length > maxChars) {
            nodes.removeAt(nodes.lastIndex)
            break
        }
    }
    if (nodes.isEmpty() && offset < scope.size) throw ToolInputException("Node metadata exceeds the result limit at offset $offset.")
    return render()
}

private fun subtreeRoot(summaries: ReviewSummaries, under: String): SummaryNode {
    val tree = summaries.tree
    tree.nodes.firstOrNull { it.location == under }?.let { return it }
    val path = under.trimEnd('/')
    tree.nodes.firstOrNull { it.path == path && it.kind != SummaryKind.GROUP && it.kind != SummaryKind.CHUNK }?.let { return it }
    val wanted = tree.root.files.filterTo(HashSet()) { it == path || it.startsWith("$path/") }
    if (wanted.isEmpty()) throw ToolInputException("No summary node covers $under. Use a folder or file path from the tree, or omit path.")
    var node = tree.root
    while (true) {
        node = node.children.map(tree::get).firstOrNull { child -> child.kind != SummaryKind.CHUNK && child.files.containsAll(wanted) } ?: return node
    }
}
