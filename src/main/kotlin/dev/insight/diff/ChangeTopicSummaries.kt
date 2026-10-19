package dev.insight.diff

/** A summary tree with every topic prefix preserved, independent of the hierarchy's folded state. Files with [chunks] get their parts beneath them. */
internal fun buildTopicSummaryTree(topics: ChangeTopics, chunks: (ReviewSource) -> List<SourceChunk> = { emptyList() }): ChangeSummaryTree {
    val outline = topicOutline(topics)
    val nodes = mutableListOf<SummaryNode>()
    fun add(kind: SummaryKind, path: String, label: String, identity: String, parent: Int?,
            file: ReviewSource? = null, entries: () -> List<Int>): Int {
        val id = nodes.size
        nodes += SummaryNode(id, kind, path, label, parent, emptyList(), emptyList(), "", identity, file)
        var children = entries()
        // Keep large topics bounded just like large directories in /summaries.
        while (children.size > ChangeSummaryTree.MAX_CHILDREN) {
            children = children.chunked(ChangeSummaryTree.MAX_CHILDREN).map { members ->
                val group = nodes.size
                val files = members.flatMap { nodes[it].files }
                nodes += SummaryNode(group, SummaryKind.GROUP, path, "${nodes[members.first()].label} … ${nodes[members.last()].label}",
                    id, members, files, reviewFingerprint(*members.map { nodes[it].key }.toTypedArray()),
                    "topic-group:$identity:${files.first()}:${files.last()}")
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
    return ChangeSummaryTree(order.map { old ->
        nodes[old].copy(id = ids.getValue(old), parent = nodes[old].parent?.let(ids::getValue),
            children = nodes[old].children.map(ids::getValue))
    })
}

/**
 * The topic tree as the hierarchy shows it, for reading and word tracing. Groups are dropped, so a topic's children are the
 * entries beside it and "two levels below" means two hierarchy columns. Parts of large files are dropped too: the hierarchy
 * shows files, and tracing must not match a part. Files read their shared summary; topics read their own, else the summary
 * of exactly their files.
 */
internal fun topicReadingSummaries(topics: ChangeSummaries, shared: ChangeSummaries): ChangeSummaries {
    val tree = topics.tree
    val fileTexts = shared.tree.nodes.filter { it.kind == SummaryKind.FILE }.mapNotNull { node -> shared.text(node.id)?.let { node.path to it } }.toMap()
    val byFiles = summariesByFiles(shared)
    val nodes = mutableListOf<SummaryNode>()
    val texts = mutableMapOf<Int, String>()
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
        val text = if (node.kind == SummaryKind.FILE) fileTexts[node.path] ?: topics.text(old) else topics.text(old) ?: byFiles[node.files.toSet()]
        text?.let { texts[id] = it }
        val children = shown(old).map { visit(it, id) }
        nodes[id] = nodes[id].copy(children = children)
        return id
    }
    visit(0, null)
    return ChangeSummaries(ChangeSummaryTree(nodes), texts)
}

/**
 * Existing exact-file-set summaries are valid evidence too; never overwrite a saved topic summary. A part of a large file
 * covers only some of its file, so parts never stand for a file set; a part takes the shared summary of the same part.
 */
internal fun seedTopicSummaries(topics: ChangeSummaries, shared: ChangeSummaries): ChangeSummaries {
    val byFiles = summariesByFiles(shared)
    val parts = shared.tree.nodes.filter { it.kind == SummaryKind.CHUNK }.mapNotNull { node -> shared.text(node.id)?.let { node.key to it } }.toMap()
    val texts = topics.tree.nodes.mapNotNull { node ->
        val seed = if (node.kind == SummaryKind.CHUNK) parts[node.key] else byFiles[node.files.toSet()]
        (topics.text(node.id) ?: seed)?.let { node.id to it }
    }.toMap()
    return ChangeSummaries(topics.tree, texts)
}

/** Summaries by the exact set of files beneath their node, deepest last; parts of files are left out. */
private fun summariesByFiles(summaries: ChangeSummaries): Map<Set<String>, String> = summaries.tree.nodes
    .filter { it.kind != SummaryKind.CHUNK }
    .mapNotNull { node -> summaries.text(node.id)?.let { node.files.toSet() to it } }.toMap()
