package dev.insight.review

import dev.insight.agent.ToolInputException

enum class SummaryKind(val id: String) { ROOT("root"), DIRECTORY("directory"), GROUP("group"), FILE("file"), CHUNK("chunk") }

/** One level of the summary tree. Code builds the structure from the changed paths; only the text is the model's. */
data class SummaryNode(
    val id: Int,
    val kind: SummaryKind,
    /** A file/directory path, or topic ancestry for [isTopicBranch]; empty for the repository or topic root. */
    val path: String,
    /** Name relative to the parent: a file or folder name, a group's first and last entry, or a part's lines. */
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
    /** A topic, topic group, or topic root; [path] is its topic ancestry (empty at the root). */
    val isTopicBranch: Boolean = false,
) {
    /** The node's position, for a reader who sees only its summary. */
    val location: String get() = when (kind) {
        SummaryKind.FILE -> path
        SummaryKind.CHUNK -> lines?.let { "$path:${it.first}-${it.last}" } ?: path
        SummaryKind.GROUP -> "${path.ifEmpty { "." }}/ · $label"
        else -> if (path.isEmpty()) "./" else "$path/"
    }
}

/** One part of a large file: contiguous 1-based [lines] and a [hash] of their text. */
data class SourceChunk(val index: Int, val lines: IntRange, val hash: String)

/** Pre-order: the root is node 0 and parents precede their children. */
class ReviewSummaryTree(val nodes: List<SummaryNode>) {
    val root: SummaryNode get() = nodes[0]
    operator fun get(id: Int): SummaryNode = nodes[id]
    fun depth(id: Int): Int = generateSequence(nodes[id].parent) { nodes[it].parent }.count()
    /** Nodes that need a summary; an empty diff has none, not even the root. */
    val summarizable: List<SummaryNode> get() = if (root.files.isEmpty()) emptyList() else nodes

    /**
     * A folder whose only entry is another folder repeats that folder's summary. Views merge such a chain into one entry: the
     * last folder, labeled with the joined names. Only the display merges; the tree and its summaries keep every folder.
     */
    fun isMerged(id: Int): Boolean {
        val node = nodes[id]
        val child = node.children.singleOrNull()?.let(nodes::get) ?: return false
        return node.kind == SummaryKind.DIRECTORY && child.kind == SummaryKind.DIRECTORY && !node.isTopicBranch && !child.isTopicBranch
    }

    /**
     * The folders below the root that are its only entry, down to the first one holding several entries or a file. The root's
     * summary is theirs too, so views show the root alone, with what the last of them holds directly beneath it.
     */
    private val rootChain: List<SummaryNode> by lazy {
        if (nodes[0].isTopicBranch) emptyList()
        else generateSequence(nodes[0]) { node -> node.children.singleOrNull()?.let(nodes::get)?.takeIf { it.kind == SummaryKind.DIRECTORY && !it.isTopicBranch } }
            .drop(1).toList()
    }
    private val absorbed: Set<Int> by lazy { rootChain.mapTo(hashSetOf()) { it.id } }

    /** A folder that is the root's only entry, or the only entry of one that is; the root's entry stands for it. */
    fun isAbsorbed(id: Int): Boolean = id in absorbed

    /** Whether views leave the node out of their rows: folded into the root, or into the folder it holds. */
    fun isHidden(id: Int): Boolean = isAbsorbed(id) || isMerged(id)

    /** A folder (or the root) whose only entry is a file repeats that file's summary; an open one can leave its summary to the file. */
    fun holdsOnlyFile(id: Int): Boolean {
        val node = nodes[id]
        if (node.isTopicBranch || node.kind != SummaryKind.DIRECTORY && node.kind != SummaryKind.ROOT) return false
        return shownChildren(node).singleOrNull()?.kind == SummaryKind.FILE
    }

    /** The entry [id] shows as: the root for an absorbed folder, else the last folder of its merged chain, else the node itself. */
    fun shown(id: Int): SummaryNode =
        if (isAbsorbed(id)) nodes[0]
        else generateSequence(nodes[id]) { node -> if (isMerged(node.id)) nodes[node.children.single()] else null }.last()

    fun shownChildren(node: SummaryNode): List<SummaryNode> =
        (if (node.id == 0) rootChain.lastOrNull() ?: node else node).children.map(::shown)

    fun shownParent(node: SummaryNode): SummaryNode? = generateSequence(node.parent) { nodes[it].parent }.firstOrNull { !isHidden(it) }?.let(nodes::get)

    /** The depth among shown entries, which merged and absorbed folders do not add to. */
    fun shownDepth(id: Int): Int = generateSequence(nodes[id].parent) { nodes[it].parent }.count { !isHidden(it) }

    /** The node's label, prefixed by the folders merged into it: `src/main/kotlin` for `kotlin`. */
    fun shownLabel(node: SummaryNode): String =
        generateSequence(node) { it.parent?.takeIf(::isMerged)?.let(nodes::get) }.toList().asReversed().joinToString("/") { it.label }

    companion object {
        /** Folders and groups with more entries are split into groups, recursively, so every summary condenses a few. */
        const val MAX_CHILDREN = 8
    }
}

/**
 * Builds the tree bottom-up from paths. Every path segment is its own folder, even one with a single entry, so each label
 * is a plain file or folder name. Folders list subfolders before files. A file with [chunks] gets its parts beneath it,
 * so a large file is summarized from its parts.
 */
internal fun buildSummaryTree(
    files: List<ReviewSource>,
    maxChildren: Int = ReviewSummaryTree.MAX_CHILDREN,
    rootLabel: String = "All changes",
    chunks: (ReviewSource) -> List<SourceChunk> = { emptyList() },
): ReviewSummaryTree {
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
    fun entries(folder: Folder): List<Proto> =
        folder.folders.map { (name, child) -> Proto(SummaryKind.DIRECTORY, child.path, name, entries(child)) } +
            folder.files.map { Proto(SummaryKind.FILE, it.path, it.path.substringAfterLast('/'), emptyList(), it) }.sortedBy { it.label }
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
    add(Proto(SummaryKind.ROOT, "", rootLabel, grouped("", entries(top))), null)
    return ReviewSummaryTree(nodes)
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
 * parts are grouped like folder entries; every node beneath a file is CHUNK. Keys include the line range because source
 * citations use absolute file lines. A part's summary survives edits elsewhere only while its position is unchanged,
 * both when retained in memory and when restored from disk.
 */
internal fun addChunkNodes(
    nodes: MutableList<SummaryNode>,
    file: ReviewSource,
    chunks: List<SourceChunk>,
    parent: Int,
    maxChildren: Int = ReviewSummaryTree.MAX_CHILDREN,
): List<Int> {
    class Part(val first: SourceChunk, val last: SourceChunk, val members: List<Part> = emptyList())
    fun add(part: Part, parent: Int): Int {
        val id = nodes.size
        val lines = part.first.lines.first..part.last.lines.last
        val (key, identity) = if (part.members.isEmpty())
            reviewFingerprint("chunk", file.path, part.first.hash, lines.first.toString(), lines.last.toString()) to "chunk:${file.path}:${part.first.index}"
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
class ReviewSummaries(
    val tree: ReviewSummaryTree,
    private val texts: Map<Int, Summary> = emptyMap(),
    /** Text restored from disk, rather than written in this tab. Never persisted. */
    internal val cachedIds: Set<Int> = emptySet(),
) {
    fun summary(id: Int): Summary? = texts[id]
    fun text(id: Int): String? = texts[id]?.text
    internal fun generatedSummary(id: Int): Summary? = summary(id).takeUnless { id in cachedIds }
    val count: Int get() = texts.size
    val total: Int get() = tree.summarizable.size
    val complete: Boolean get() = missing().isEmpty()
    /** Starts a separate version, retaining other prose but dropping citations that depend on the removed summaries. */
    fun without(ids: Set<Int>): ReviewSummaries {
        val next = texts.filterKeys { it !in ids }.toMutableMap()
        val cached = (cachedIds - ids).toMutableSet()
        updateParents(next, emptySet(), reuseSingleChildren = false, cached = cached)
        return ReviewSummaries(tree, next, cached)
    }
    fun missing(): List<Int> = tree.summarizable.filter { it.id !in texts }.map { it.id }
    /** Missing nodes whose children are all summarized: leaves (files and parts), then the rest from the deepest, which can be written now. */
    fun ready(): List<Int> = tree.summarizable
        .filter { it.id !in texts && it.children.all(texts::containsKey) }
        .sortedWith(compareBy<SummaryNode> { it.children.isNotEmpty() }.thenByDescending { tree.depth(it.id) }.thenBy { it.id })
        .map { it.id }

    /** Keeps the summaries whose node, with everything beneath it, is unchanged in [next]. */
    fun retainedIn(next: ReviewSummaryTree): ReviewSummaries {
        val byKey = texts.mapKeys { (id, _) -> tree[id].key }
        val cachedKeys = cachedIds.map { tree[it].key }.toSet()
        val kept = next.nodes.mapNotNull { node -> byKey[node.key]?.let { node.id to it } }.toMap(LinkedHashMap())
        val cached = next.nodes.filter { it.id in kept && it.key in cachedKeys }.mapTo(hashSetOf()) { it.id }
        for (node in next.nodes.asReversed()) if (node.id !in kept && node.children.size == 1) {
            val child = node.children.single()
            kept[child]?.let { kept[node.id] = it.relay(); if (child in cached) cached += node.id }
        }
        return ReviewSummaries(next, kept, cached)
    }

    /**
     * Validates the whole batch before anything changes. A folder, group or the root needs every child summarized, before or in
     * the same batch, so summaries are written from the bottom up. A parent with a single child reuses that child's summary.
     */
    fun summarize(entries: Map<Int, Summary>): ReviewSummaries = summarize(entries, reuseSingleChildren = true)

    fun summarize(entries: Map<Int, Summary>, reuseSingleChildren: Boolean): ReviewSummaries =
        write(entries, reuseSingleChildren, emptySet())

    /** Copies shared summaries without treating cached text as newly generated. */
    internal fun share(entries: Map<Int, Summary>, cached: Set<Int>): ReviewSummaries = write(entries, true, cached)

    private fun write(entries: Map<Int, Summary>, reuseSingleChildren: Boolean, cached: Set<Int>): ReviewSummaries {
        if (tree.summarizable.isEmpty()) throw ToolInputException("There are no files to summarize.")
        if (entries.isEmpty()) throw ToolInputException("Add at least one summary.")
        if (entries.size > MAX_PER_CALL) throw ToolInputException("Add at most $MAX_PER_CALL summaries per call.")
        val next = texts.toMutableMap()
        val nextCached = (cachedIds - entries.keys + cached).toMutableSet()
        for ((id, summary) in entries) {
            if (id !in tree.nodes.indices) throw ToolInputException("Node $id is not in the summary tree.")
            next[id] = Summary.of(summary.segments) ?: throw ToolInputException("Node $id: provide a nonempty summary.")
        }
        for (id in entries.keys) {
            val waiting = tree[id].children.filter { it !in next }
            if (waiting.isNotEmpty()) throw ToolInputException("Node $id: summarize its children first (ids ${waiting.joinToString()}), " +
                "then write its summary from theirs. Nothing was saved.")
        }
        updateParents(next, entries.keys, reuseSingleChildren, nextCached)
        return ReviewSummaries(tree, next, nextCached)
    }

    /** Old claim indices cannot be reused after a child changes. Freshly written parents already cite the new children. */
    private fun updateParents(next: MutableMap<Int, Summary>, rewritten: Set<Int>, reuseSingleChildren: Boolean,
                              cached: MutableSet<Int>) {
        for (node in tree.nodes.asReversed()) {
            if (node.id in rewritten || node.children.isEmpty()) continue
            if (reuseSingleChildren && node.children.size == 1) {
                val child = node.children.single()
                next[child]?.let {
                    next[node.id] = it.relay()
                    if (child in cached) cached += node.id else cached -= node.id
                }
            } else {
                val summary = next[node.id] ?: continue
                next[node.id] = Summary(summary.segments.map { segment ->
                    // A claim may condense several children; dropping only one source would misrepresent its evidence.
                    if (segment.sources.filterIsInstance<SummarySource.Claim>().any { source ->
                            node.children.getOrNull(source.child)?.let { texts[it] != next[it] } == true
                        }) segment.copy(sources = emptyList()) else segment
                })
            }
        }
    }

    companion object {
        const val MAX_PER_CALL = 80
    }
}
