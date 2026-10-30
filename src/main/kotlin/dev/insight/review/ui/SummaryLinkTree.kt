package dev.insight.review.ui

import dev.insight.review.*

/** An occurrence, rather than a summary node: a claim can contribute to several different branches. */
internal data class SummaryLinkNode(
    val key: String,
    val text: String,
    val owner: SummaryNode,
    val file: Boolean = false,
    val children: List<SummaryLinkNode> = emptyList(),
    /** The summary parts this occurrence reads as; several once a repeating chain merges into it. */
    val parts: List<SummaryPart> = emptyList(),
)

/** Segment [segment] of node [node]'s summary. */
internal data class SummaryPart(val node: Int, val segment: Int)

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
            SummaryLinkNode("$key/$index", segment.text.trim(), node, children = children, parts = listOf(SummaryPart(node.id, index)))
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
    val parts = build(root, null, root.identity).map { it.merged() }
    return parts.singleOrNull()
        ?: SummaryLinkNode(root.identity + "/empty", "", root, children = parts)
}

/**
 * Folds the repetition of a single-entry path into one node, as the split view merges its folders. A part whose only child
 * says the same is that child, so a chain of folders over one file reads once; a part of a file over the file's leaf is that
 * leaf, which then shows the summary as well as the file.
 */
private fun SummaryLinkNode.merged(): SummaryLinkNode {
    val below = children.map { it.merged() }
    val only = below.singleOrNull() ?: return copy(children = below)
    return when {
        only.file && only.children.isEmpty() && !file && (owner.kind == SummaryKind.FILE || owner.kind == SummaryKind.CHUNK) ->
            only.copy(text = text.ifEmpty { only.text }, parts = parts + only.parts)
        text.isNotEmpty() && text == only.text -> only.copy(parts = parts + only.parts)
        else -> copy(children = below)
    }
}

/**
 * The keys of nodes that read as reviewed: a leaf once its own files are reviewed, and a claim once everything it leads to is,
 * so a whole branch dims together.
 */
internal fun SummaryLinkNode.reviewedKeys(isReviewed: (SummaryNode) -> Boolean): Set<String> {
    val result = HashSet<String>()
    fun visit(node: SummaryLinkNode): Boolean {
        val children = node.children.map(::visit)
        val done = if (children.isEmpty()) isReviewed(node.owner) else children.all { it }
        if (done) result += node.key
        return done
    }
    visit(this)
    return result
}

/** The place a node names under its text: a file leaf its path, a folder its own, a part of a file its folder. */
internal fun SummaryLinkNode.directory(): String = when {
    owner.isTopicBranch -> ""
    file -> owner.path
    owner.kind == SummaryKind.FILE || owner.kind == SummaryKind.CHUNK -> owner.path.substringBeforeLast('/', "")
    else -> owner.path
}

/** Every occurrence of a file and its ancestors, so navigation follows all of its cited routes. */
internal fun SummaryLinkNode.pathKeys(path: String): Set<String> {
    fun normalize(value: String) = value.trim().removeSurrounding("\"").removeSurrounding("'")
        .replace('\\', '/').removePrefix("./")
    val target = normalize(path)
    if (target.isEmpty()) return emptySet()
    val keys = HashSet<String>()
    fun visit(node: SummaryLinkNode): Boolean {
        val children = node.children.map(::visit)
        val matches = node.file && (normalize(node.owner.path) == target ||
            node.owner.file?.absolutePath?.let(::normalize) == target)
        if (matches || children.any { it }) keys += node.key
        return node.key in keys
    }
    visit(this)
    return keys
}

/** One entry of a full summary: a shown summary at [depth], and which of its segments the occurrence's route passes. */
internal data class FullSummaryEntry(val node: SummaryNode, val depth: Int, val route: Set<Int>)

/**
 * The shown summaries from the root down to the occurrence [key], as the outline reads them; empty once the tree no longer
 * holds the occurrence.
 */
internal fun SummaryLinkNode.fullSummary(tree: ReviewSummaryTree, key: String): List<FullSummaryEntry> {
    fun route(node: SummaryLinkNode): List<SummaryLinkNode>? =
        if (node.key == key) listOf(node) else node.children.firstNotNullOfOrNull(::route)?.let { listOf(node) + it }
    val nodes = route(this) ?: return emptyList()
    // Merged and absorbed folders repeat the summary of the entry they show as, segment for segment.
    val passed = nodes.flatMap { it.parts }.groupBy({ tree.shown(it.node).id }, { it.segment })
    val chain = generateSequence(tree.shown(nodes.last().owner.id), tree::shownParent).toList().asReversed()
    return chain.mapIndexed { depth, node -> FullSummaryEntry(node, depth, passed[node.id].orEmpty().toSet()) }
}
