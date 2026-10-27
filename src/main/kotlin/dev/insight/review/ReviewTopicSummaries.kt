package dev.insight.review

/** A summary tree with every topic prefix preserved, independent of the hierarchy's folded state. Files with [chunks] get their parts beneath them. */
internal fun buildTopicSummaryTree(topics: ReviewTopics, chunks: (ReviewSource) -> List<SourceChunk> = { emptyList() }): ReviewSummaryTree {
    val outline = topicOutline(topics)
    val nodes = mutableListOf<SummaryNode>()
    fun add(kind: SummaryKind, path: String, label: String, identity: String, parent: Int?,
            file: ReviewSource? = null, entries: () -> List<Int>): Int {
        val id = nodes.size
        nodes += SummaryNode(id, kind, path, label, parent, emptyList(), emptyList(), "", identity, file, isTopicBranch = kind != SummaryKind.FILE)
        var children = entries()
        // Keep large topics bounded just like large directories in /summaries.
        while (children.size > ReviewSummaryTree.MAX_CHILDREN) {
            children = children.chunked(ReviewSummaryTree.MAX_CHILDREN).map { members ->
                val group = nodes.size
                val files = members.flatMap { nodes[it].files }
                nodes += SummaryNode(group, SummaryKind.GROUP, path, "${nodes[members.first()].label} … ${nodes[members.last()].label}",
                    id, members, files, reviewFingerprint(*members.map { nodes[it].key }.toTypedArray()),
                    "topic-group:$identity:${files.first()}:${files.last()}", isTopicBranch = true)
                members.forEach { nodes[it] = nodes[it].copy(parent = group) }
                group
            }
        }
        val files = file?.let { listOf(it.path) } ?: children.flatMap { nodes[it].files }
        val key = reviewFingerprint(identity, file?.fingerprint.orEmpty(), *children.map { nodes[it].key }.toTypedArray())
        nodes[id] = nodes[id].copy(children = children, files = files, key = key)
        return id
    }
    fun entry(entry: TopicEntry, parent: Int): Int = when (entry) {
        is TopicEntry.File -> {
            val file = outline.files[entry.index]
            val id = nodes.size
            add(SummaryKind.FILE, file.path, file.path.substringAfterLast('/'), "file:${file.path}", parent, file) { addChunkNodes(nodes, file, chunks(file), id) }
        }
        is TopicEntry.Topic -> {
            val node = entry.node
            val id = nodes.size
            add(SummaryKind.DIRECTORY, node.fullPath, node.name, "topic:${node.identity}", parent) {
                outline.children(node).map { entry(it, id) }
            }
        }
    }
    add(SummaryKind.ROOT, "", "All topics", "topic-root", null) {
        outline.children(null).map { entry(it, 0) }
    }
    // Grouping adds nodes after their children; restore the summary tree's pre-order contract.
    val order = mutableListOf<Int>()
    fun visit(id: Int) { order += id; nodes[id].children.forEach(::visit) }
    visit(0)
    val ids = order.withIndex().associate { it.value to it.index }
    return ReviewSummaryTree(order.map { old ->
        nodes[old].copy(id = ids.getValue(old), parent = nodes[old].parent?.let(ids::getValue),
            children = nodes[old].children.map(ids::getValue))
    })
}

/**
 * The topic tree as the hierarchy shows it, for reading and word tracing. Groups are dropped, so a topic's children are the
 * entries beside it and "two levels below" means two hierarchy columns. Parts of large files are dropped too: the hierarchy
 * shows files, and tracing must not match a part. Files read their shared summary; topics read their own, else the summary
 * of exactly their files. Citations of dropped nodes are carried through to what they cite, so a topic links to the files
 * beneath its groups and a file to the lines beneath its parts.
 */
internal fun topicReadingSummaries(topics: ReviewSummaries, shared: ReviewSummaries): ReviewSummaries {
    val tree = topics.tree
    val fileNodes = shared.tree.nodes.filter { it.kind == SummaryKind.FILE }.associateBy { it.path }
    val byFiles = summariesByFiles(shared)
    val nodes = mutableListOf<SummaryNode>()
    val texts = mutableMapOf<Int, Summary>()
    fun shown(id: Int): List<Int> = tree[id].children.flatMap { child ->
        when (tree[child].kind) {
            SummaryKind.GROUP -> shown(child)
            SummaryKind.CHUNK -> emptyList()
            else -> listOf(child)
        }
    }
    fun visit(old: Int, parent: Int?): Int {
        val node = tree[old]
        val id = nodes.size
        nodes += node.copy(id = id, parent = parent, children = emptyList())
        val kept = shown(old)
        val text = if (node.kind == SummaryKind.FILE)
            fileNodes[node.path]?.let { file -> shared.flattened(file.id) { null } } ?: topics.flattened(old) { null }
        else topics.flattened(old) { child -> kept.indexOf(child.id).takeIf { it >= 0 } }
            ?: byFiles[node.files.toSet()]?.summary?.unlinked()
        text?.let { texts[id] = it }
        val children = kept.map { visit(it, id) }
        nodes[id] = nodes[id].copy(children = children)
        return id
    }
    visit(0, null)
    return ReviewSummaries(ReviewSummaryTree(nodes), texts)
}

/**
 * Existing exact-file-set summaries are valid evidence too; never overwrite a saved topic summary. A part of a large file
 * covers only some of its file, so parts never stand for a file set; a part takes the shared summary of the same part.
 * A seed's citations are moved to the topic's children with the same files; a topic with one child relays that child.
 */
internal fun seedTopicSummaries(topics: ReviewSummaries, shared: ReviewSummaries): ReviewSummaries {
    val byFiles = summariesByFiles(shared)
    val parts = shared.tree.nodes.filter { it.kind == SummaryKind.CHUNK }.mapNotNull { node ->
        shared.summary(node.id)?.let { node.key to Seed(node, it, node.id in shared.cachedIds) }
    }.toMap()
    val cached = hashSetOf<Int>()
    val texts = mutableMapOf<Int, Summary>()
    for (node in topics.tree.nodes.asReversed()) {
        val existing = topics.summary(node.id)?.let { it to (node.id in topics.cachedIds) }
        val seed = (if (node.kind == SummaryKind.CHUNK) parts[node.key] else byFiles[node.files.toSet()])
            ?.let { source ->
                if (node.isTopicBranch && node.children.size == 1) {
                    // A topic's evidence is its child, even when the seed came directly from that child's file.
                    val child = node.children.single()
                    texts[child]?.relay()?.let { it to (child in cached) }
                } else retarget(
                    source.summary,
                    shared.tree,
                    source.node,
                    topics.tree,
                    node,
                    shared,
                    ReviewSummaries(topics.tree, texts, cached),
                ) to source.cached
            }
        (existing ?: seed)?.let { (text, fromCache) ->
            if (fromCache) cached += node.id
            texts[node.id] = text
        }
    }
    return ReviewSummaries(topics.tree, texts, cached)
}

private data class Seed(val node: SummaryNode, val summary: Summary, val cached: Boolean)

/** Summaries by the exact set of files beneath their node, deepest last; parts of files are left out. */
private fun summariesByFiles(summaries: ReviewSummaries): Map<Set<String>, Seed> = summaries.tree.nodes
    .filter { it.kind != SummaryKind.CHUNK }
    .mapNotNull { node -> summaries.summary(node.id)?.let { node.files.toSet() to Seed(node, it, node.id in summaries.cachedIds) } }.toMap()
