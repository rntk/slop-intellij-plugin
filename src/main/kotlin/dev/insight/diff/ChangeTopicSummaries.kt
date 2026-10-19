package dev.insight.diff

/** A summary tree with every topic prefix preserved, independent of the hierarchy's folded state. */
internal fun buildTopicSummaryTree(topics: ChangeTopics): ChangeSummaryTree {
    val outline = topicOutline(topics)
    val nodes = mutableListOf<SummaryNode>()
    fun add(kind: SummaryKind, path: String, label: String, identity: String, parent: Int?,
            file: ReviewFile? = null, entries: () -> List<Int>): Int {
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
            add(SummaryKind.FILE, file.path, file.path.substringAfterLast('/'), "file:${file.path}", parent, file) { emptyList() }
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

/** Existing exact-file-set summaries are valid evidence too; never overwrite a saved topic summary. */
internal fun seedTopicSummaries(topics: ChangeSummaries, shared: ChangeSummaries): ChangeSummaries {
    val byFiles = shared.tree.nodes.mapNotNull { node -> shared.text(node.id)?.let { node.files.toSet() to it } }.toMap()
    val texts = topics.tree.nodes.mapNotNull { node ->
        (topics.text(node.id) ?: byFiles[node.files.toSet()])?.let { node.id to it }
    }.toMap()
    return ChangeSummaries(topics.tree, texts)
}
