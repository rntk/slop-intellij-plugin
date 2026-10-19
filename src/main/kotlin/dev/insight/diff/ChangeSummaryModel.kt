package dev.insight.diff

import dev.insight.chat.ToolInputException

enum class SummaryKind(val id: String) { ROOT("root"), DIRECTORY("directory"), GROUP("group"), FILE("file"), CHUNK("chunk") }

/** One level of the summary tree. Code builds the structure from the changed paths; only the text is the model's. */
data class SummaryNode(
    val id: Int,
    val kind: SummaryKind,
    /** A file path, or the directory that holds the node's entries without a trailing slash ("" for the repository root). */
    val path: String,
    /** Name relative to the parent: a file name, a merged folder chain such as `main/kotlin`, a group's first and last entry, or a part's lines. */
    val label: String,
    val parent: Int?,
    val children: List<Int>,
    /** Every file beneath, in tree order. */
    val files: List<String>,
    /** Equal across snapshots while the structure and evidence beneath are unchanged, so a summary can be kept. */
    val key: String,
    /** Survives content changes; keeps expansion and selection across renders. */
    val identity: String,
    /** The file of a FILE node and of every part beneath it. */
    val file: ReviewSource? = null,
    /** The 1-based lines of a part of a large file, or of a group of parts. */
    val lines: IntRange? = null,
) {
    /** The node's position, for a reader who sees only its summary. */
    val location: String get() = when (kind) {
        SummaryKind.FILE -> path
        SummaryKind.CHUNK -> lines?.let { "$path:${it.first}-${it.last}" } ?: path
        SummaryKind.GROUP -> "${path.ifEmpty { "." }}/ · $label"
        else -> if (path.isEmpty()) "./" else "$path/"
    }
}

/** One part of a large file: contiguous 1-based [lines] and a [hash] of their text, so its key survives edits elsewhere. */
data class SourceChunk(val index: Int, val lines: IntRange, val hash: String)

/** Pre-order: the root is node 0 and parents precede their children. */
class ChangeSummaryTree(val nodes: List<SummaryNode>) {
    val root: SummaryNode get() = nodes[0]
    operator fun get(id: Int): SummaryNode = nodes[id]
    fun depth(id: Int): Int = generateSequence(nodes[id].parent) { nodes[it].parent }.count()
    /** Nodes that need a summary; an empty diff has none, not even the root. */
    val summarizable: List<SummaryNode> get() = if (root.files.isEmpty()) emptyList() else nodes

    companion object {
        /** Folders and groups with more entries are split into groups, recursively, so every summary condenses a few. */
        const val MAX_CHILDREN = 8
    }
}

/**
 * Builds the tree bottom-up from paths. A folder with a single entry adds no level of meaning, so it merges with that
 * entry (`main/kotlin`, or `docs/README.md`); the root absorbs a single top folder. Folders list subfolders before files.
 * A file with [chunks] gets its parts beneath it, so a large file is summarized from its parts.
 */
internal fun buildSummaryTree(
    files: List<ReviewSource>,
    maxChildren: Int = ChangeSummaryTree.MAX_CHILDREN,
    rootLabel: String = "All changes",
    chunks: (ReviewSource) -> List<SourceChunk> = { emptyList() },
): ChangeSummaryTree {
    require(maxChildren >= 2) { "Groups need at least two entries" }
    class Folder(val path: String) {
        val folders = sortedMapOf<String, Folder>()
        val files = mutableListOf<ReviewSource>()
    }
    class Proto(val kind: SummaryKind, val path: String, val label: String, val children: List<Proto>, val file: ReviewSource? = null)

    val top = Folder("")
    for (file in files.distinctBy { it.path }) {
        var folder = top
        for (name in file.path.split('/').dropLast(1)) {
            val parent = folder
            folder = parent.folders.getOrPut(name) { Folder(if (parent.path.isEmpty()) name else "${parent.path}/$name") }
        }
        folder.files += file
    }
    fun entries(folder: Folder): List<Proto> = folder.folders.map { (name, child) ->
        val children = entries(child)
        val only = children.singleOrNull()
        if (only == null) Proto(SummaryKind.DIRECTORY, child.path, name, children)
        else Proto(only.kind, only.path, "$name/${only.label}", only.children, only.file)
    }.plus(folder.files.map { Proto(SummaryKind.FILE, it.path, it.path.substringAfterLast('/'), emptyList(), it) })
        // A folder merged into its only file sorts with the files.
        .sortedWith(compareBy<Proto>({ it.kind == SummaryKind.FILE }, { it.label }))
    var rootPath = ""
    var rootChildren = entries(top)
    while (rootChildren.size == 1 && rootChildren[0].kind == SummaryKind.DIRECTORY) {
        rootPath = rootChildren[0].path
        rootChildren = rootChildren[0].children
    }
    fun grouped(path: String, children: List<Proto>): List<Proto> = balancedGroups(children, maxChildren) { members ->
        Proto(SummaryKind.GROUP, path, "${members.first().label} … ${members.last().label}", members)
    }
    val nodes = mutableListOf<SummaryNode>()
    fun add(proto: Proto, parent: Int?): SummaryNode {
        val id = nodes.size
        nodes += SummaryNode(id, proto.kind, proto.path, proto.label, parent, emptyList(), emptyList(), "", "")
        val file = proto.file
        val children = if (file != null) addChunkNodes(nodes, file, chunks(file), id, maxChildren).map(nodes::get)
            else grouped(proto.path, proto.children).map { add(it, id) }
        val fileList = file?.let { listOf(it.path) } ?: children.flatMap { it.files }
        // Without parts this is the plain file key, so diff summaries are kept exactly as before.
        val key = file?.let { reviewFingerprint(proto.kind.id, it.path, it.fingerprint, *children.map { child -> child.key }.toTypedArray()) }
            ?: reviewFingerprint(proto.kind.id, proto.path, proto.label, *children.map { it.key }.toTypedArray())
        val identity = when (proto.kind) {
            SummaryKind.ROOT -> "root"
            SummaryKind.GROUP -> "group:${proto.path}:${fileList.first()}:${fileList.last()}"
            else -> "${proto.kind.id}:${proto.path}"
        }
        return nodes[id].copy(children = children.map { it.id }, files = fileList, key = key, identity = identity, file = file)
            .also { nodes[id] = it }
    }
    add(Proto(SummaryKind.ROOT, rootPath, if (rootPath.isEmpty()) rootLabel else "$rootPath/", grouped(rootPath, rootChildren)), null)
    return ChangeSummaryTree(nodes)
}

/** Splits [level] into at most [maxChildren] contiguous, evenly sized groups, recursively; a group of one stays itself. */
internal fun <T> balancedGroups(level: List<T>, maxChildren: Int, group: (List<T>) -> T): List<T> {
    var current = level
    while (current.size > maxChildren) {
        val count = (current.size + maxChildren - 1) / maxChildren
        val base = current.size / count
        val extra = current.size % count
        var start = 0
        val previous = current
        current = List(count) { index ->
            val members = previous.subList(start, start + base + if (index < extra) 1 else 0).also { start += it.size }
            members.singleOrNull() ?: group(members)
        }
    }
    return current
}

/**
 * Adds the parts of [file] beneath [parent] in pre-order and returns the ids of its direct children. More than [maxChildren]
 * parts are grouped like folder entries; every node beneath a file is CHUNK. Keys hold no line numbers, so a part's summary
 * survives edits elsewhere in the file.
 */
internal fun addChunkNodes(
    nodes: MutableList<SummaryNode>,
    file: ReviewSource,
    chunks: List<SourceChunk>,
    parent: Int,
    maxChildren: Int = ChangeSummaryTree.MAX_CHILDREN,
): List<Int> {
    class Part(val first: SourceChunk, val last: SourceChunk, val members: List<Part> = emptyList())
    fun add(part: Part, parent: Int): Int {
        val id = nodes.size
        val lines = part.first.lines.first..part.last.lines.last
        val (key, identity) = if (part.members.isEmpty())
            reviewFingerprint("chunk", file.path, part.first.hash) to "chunk:${file.path}:${part.first.index}"
            else "" to "chunk-group:${file.path}:${part.first.index}:${part.last.index}"
        nodes += SummaryNode(id, SummaryKind.CHUNK, file.path, "Lines ${lines.first}–${lines.last}", parent, emptyList(),
            listOf(file.path), key, identity, file, lines)
        if (part.members.isNotEmpty()) {
            val children = part.members.map { add(it, id) }
            nodes[id] = nodes[id].copy(children = children,
                key = reviewFingerprint("chunk-group", file.path, *children.map { nodes[it].key }.toTypedArray()))
        }
        return id
    }
    val parts = balancedGroups(chunks.sortedBy { it.index }.map { Part(it, it) }, maxChildren) { Part(it.first().first, it.last().last, it) }
    return parts.map { add(it, parent) }
}

/** The tree of one snapshot revision with the summaries written so far. Immutable: every change returns a new value. */
class ChangeSummaries(val tree: ChangeSummaryTree, private val texts: Map<Int, String> = emptyMap()) {
    fun text(id: Int): String? = texts[id]
    val count: Int get() = texts.size
    val total: Int get() = tree.summarizable.size
    val complete: Boolean get() = missing().isEmpty()
    fun missing(): List<Int> = tree.summarizable.filter { it.id !in texts }.map { it.id }
    /** Missing nodes whose children are all summarized: leaves (files and parts), then the rest from the deepest, which can be written now. */
    fun ready(): List<Int> = tree.summarizable
        .filter { it.id !in texts && it.children.all(texts::containsKey) }
        .sortedWith(compareBy<SummaryNode> { it.children.isNotEmpty() }.thenByDescending { tree.depth(it.id) }.thenBy { it.id })
        .map { it.id }

    /** Keeps the summaries whose node, with everything beneath it, is unchanged in [next]. */
    fun retainedIn(next: ChangeSummaryTree): ChangeSummaries {
        val byKey = texts.mapKeys { (id, _) -> tree[id].key }
        val kept = next.nodes.mapNotNull { node -> byKey[node.key]?.let { node.id to it } }.toMap(LinkedHashMap())
        for (node in next.nodes.asReversed()) if (node.id !in kept && node.children.size == 1)
            kept[node.children.single()]?.let { kept[node.id] = it }
        return ChangeSummaries(next, kept)
    }

    /**
     * Validates the whole batch before anything changes. A folder, group or the root needs every child summarized, before or in
     * the same batch, so summaries are written from the bottom up. A parent with a single child reuses that child's summary.
     */
    fun summarize(entries: Map<Int, String>): ChangeSummaries {
        if (tree.summarizable.isEmpty()) throw ToolInputException("There are no files to summarize.")
        if (entries.isEmpty()) throw ToolInputException("Add at least one summary.")
        if (entries.size > MAX_PER_CALL) throw ToolInputException("Add at most $MAX_PER_CALL summaries per call.")
        val next = texts.toMutableMap()
        for ((id, text) in entries) {
            if (id !in tree.nodes.indices) throw ToolInputException("Node $id is not in the summary tree.")
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw ToolInputException("Node $id: provide a nonempty summary.")
            next[id] = trimmed
        }
        for (id in entries.keys) {
            val waiting = tree[id].children.filter { it !in next }
            if (waiting.isNotEmpty()) throw ToolInputException("Node $id: summarize its children first (ids ${waiting.joinToString()}), " +
                "then write its summary from theirs. Nothing was saved.")
        }
        for (node in tree.nodes.asReversed()) if (node.id !in entries && node.children.size == 1)
            next[node.children.single()]?.let { next[node.id] = it }
        return ChangeSummaries(tree, next)
    }

    companion object {
        const val MAX_PER_CALL = 80
    }
}
