package dev.insight.callgraph

import com.intellij.psi.codeStyle.NameUtil

/** Starting points for progressive disclosure; the user can expand or collapse any group afterwards. */
enum class GraphDetail(val label: String) {
    OVERVIEW("Overview"), FILE("Files"), SYMBOL("Symbols");
    override fun toString() = label
}

/** When several relationships connect two shown nodes, the edge shows the first kind in this order. */
private val relationPriority = listOf(RelationKind.INHERITS, RelationKind.CALL, RelationKind.REFERENCE, RelationKind.IMPORT, RelationKind.CONTAINS)

/**
 * Containment tree over a project graph: directories > files > classes > members. Ids below `graph.nodes.size`
 * are the graph's node ids; larger ids are directories derived from file paths. Directories are relative to the
 * deepest directory shared by all files, and chains of directories without files are merged (`main/kotlin/dev`).
 */
class GraphHierarchy(val graph: CallGraph) {
    val nodes: List<CallGraphNode>
    val parent: IntArray
    val children: List<List<Int>>
    val roots: List<Int>
    /** Number of descendants of each node. */
    private val sizes: IntArray

    private class Directory(val path: String, var name: String) {
        val directories = sortedMapOf<String, Directory>()
        val files = mutableListOf<Int>()
    }

    init {
        val parents = HashMap<Int, Int>()
        for (edge in graph.edges) if (edge.kind == RelationKind.CONTAINS && edge.caller != edge.callee) parents.putIfAbsent(edge.callee, edge.caller)
        val fileByPath = graph.nodes.filter { it.kind == SymbolKind.FILE && it.filePath != null }.associateBy({ it.filePath!! }, { it.id })

        val segments = fileByPath.keys.associateWith { it.substringBeforeLast('/', "").split('/') }
        val shared = segments.values.reduceOrNull { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }.orEmpty()
        val top = Directory(shared.joinToString("/"), "")
        for ((path, id) in fileByPath) {
            var directory = top
            for (segment in segments.getValue(path).drop(shared.size)) {
                directory = directory.directories.getOrPut(segment) { Directory("${directory.path}/$segment", segment) }
            }
            directory.files += id
        }
        fun merge(directory: Directory): Directory {
            var current = directory
            while (current.files.isEmpty() && current.directories.size == 1) {
                val only = current.directories.values.single()
                only.name = "${current.name}/${only.name}"
                current = only
            }
            current.directories.replaceAll { _, child -> merge(child) }
            return current
        }
        top.directories.replaceAll { _, child -> merge(child) }

        val all = graph.nodes.toMutableList()
        val directoryParents = HashMap<Int, Int>()
        fun fileCount(directory: Directory): Int = directory.files.size + directory.directories.values.sumOf(::fileCount)
        fun emit(directory: Directory, parentId: Int?) {
            val id = all.size
            val files = fileCount(directory)
            all += CallGraphNode(id, "${directory.name}/", directory.path.removePrefix(top.path).trimStart('/'),
                if (files == 1) "1 file" else "$files files", 0, true, SymbolKind.DIRECTORY, directory.path)
            parentId?.let { directoryParents[id] = it }
            directory.files.forEach { directoryParents[it] = id }
            directory.directories.values.forEach { emit(it, id) }
        }
        top.directories.values.forEach { emit(it, null) }
        nodes = all
        parent = IntArray(all.size) { id ->
            parents[id] ?: directoryParents[id]
                // Declarations without a containment edge belong to their file.
                ?: graph.nodes.getOrNull(id)?.takeIf { it.kind != SymbolKind.FILE }?.filePath?.let(fileByPath::get)?.takeIf { it != id }
                ?: -1
        }
        val lists = List(all.size) { mutableListOf<Int>() }
        all.indices.forEach { id -> parent[id].takeIf { it >= 0 }?.let { lists[it] += id } }
        // Directories before files, then source order.
        lists.forEach { it.sortBy { id -> if (all[id].kind == SymbolKind.DIRECTORY) -1 else id } }
        children = lists
        roots = all.indices.filter { parent[it] < 0 }.sortedBy { if (all[it].kind == SymbolKind.DIRECTORY) -1 else it }
        sizes = IntArray(all.size)
        for (id in preorder().asReversed()) parent[id].takeIf { it >= 0 }?.let { sizes[it] += sizes[id] + 1 }
    }

    private fun preorder(): List<Int> {
        val order = ArrayList<Int>(nodes.size)
        val stack = ArrayDeque(roots.asReversed())
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            order += id
            children[id].asReversed().forEach(stack::addLast)
        }
        return order
    }

    private fun descendants(id: Int): Sequence<Int> = sequence {
        val stack = ArrayDeque(children[id])
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            yield(next)
            stack.addAll(children[next])
        }
    }

    fun isGroup(id: Int) = children.getOrNull(id)?.isNotEmpty() == true

    /** [id] and the groups below it. */
    fun groupsWithin(id: Int): Set<Int> = (descendants(id) + id).filterTo(hashSetOf(), ::isGroup)

    /** Stable identity across rebuilds, used to keep the user's expanded groups when the project changes. */
    fun key(id: Int): String = stableKey(nodes[id])

    fun ids(keys: Collection<String>): Set<Int> {
        val wanted = keys.toHashSet()
        return nodes.indices.filterTo(hashSetOf()) { key(it) in wanted }
    }

    /** Groups to expand so the file at [path] is shown. */
    fun ancestors(path: String?): Set<Int> {
        val file = graph.nodes.firstOrNull { it.kind == SymbolKind.FILE && it.filePath == path } ?: return emptySet()
        return ancestors(file.id)
    }

    /** Groups to expand so the node [id] is shown. */
    fun ancestors(id: Int): Set<Int> = generateSequence(parent[id]) { parent[it] }.takeWhile { it >= 0 }.toSet()

    fun expansion(detail: GraphDetail, budget: Int = 40): Set<Int> = when (detail) {
        GraphDetail.OVERVIEW -> overview(budget)
        GraphDetail.FILE -> nodes.indices.filterTo(hashSetOf()) { nodes[it].kind == SymbolKind.DIRECTORY }
        GraphDetail.SYMBOL -> nodes.indices.filterTo(hashSetOf(), ::isGroup)
    }

    /** Expands groups level by level, smallest first, while at most [budget] nodes are shown. */
    private fun overview(budget: Int): Set<Int> {
        val expanded = hashSetOf<Int>()
        var shown = roots.size
        var level = roots
        while (level.isNotEmpty()) {
            val next = mutableListOf<Int>()
            for (id in level.filter(::isGroup).sortedBy { children[it].size }) {
                if (shown + children[id].size > budget) continue
                expanded += id
                shown += children[id].size
                next += children[id]
            }
            level = next
        }
        return expanded
    }

    /**
     * Nodes whose name fuzzily matches [query] (camel humps, word starts and subsequences, ignoring case), best
     * match first: names matching from their start, then by the platform's matching degree, then in tree order.
     * A blank query matches nothing.
     */
    fun search(query: String): List<Int> = fuzzyNameSearch(query, preorder()) { nodes[it].name }

    /**
     * Shows roots and the members of [expanded] groups; every hidden node is folded into its nearest shown ancestor.
     * Relationships are merged per pair of shown nodes, and expanded groups are linked to their members by
     * containment edges. Users are placed left of what they depend on. [excluded] nodes and their descendants are
     * left out with their relationships, as are directories and files left without members.
     *
     * With [only], just those nodes are shown whatever is expanded: their descendants fold into them, other nodes are
     * left out with their relationships, and each shown node is linked to its nearest shown ancestor.
     */
    fun view(expanded: Set<Int>, excluded: Set<Int> = emptySet(), only: Set<Int>? = null): DetailGraph {
        val gone = gone(excluded)
        val live = { id: Int -> gone == null || !gone[id] }
        val liveSizes = if (gone == null) sizes else IntArray(nodes.size).also { counts ->
            for (id in preorder().asReversed()) if (live(id)) parent[id].takeIf { it >= 0 }?.let { counts[it] += counts[id] + 1 }
        }
        val shown = mutableListOf<Int>()
        val shownAs = IntArray(nodes.size) { -1 }
        if (only != null) {
            for (id in preorder()) {
                if (!live(id)) continue
                if (id in only) {
                    shownAs[id] = shown.size
                    shown += id
                } else if (parent[id] >= 0) shownAs[id] = shownAs[parent[id]]
            }
        } else {
            val stack = ArrayDeque(roots.filter(live).asReversed())
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                shownAs[id] = shown.size
                shown += id
                if (id in expanded) children[id].filter(live).asReversed().forEach(stack::addLast)
                else descendants(id).filter(live).forEach { shownAs[it] = shownAs[id] }
            }
        }
        // Descendants folded into each shown node of a filtered view.
        val folded = only?.let { IntArray(shown.size).also { counts -> shownAs.forEachIndexed { id, at -> if (at >= 0 && shown[at] != id) counts[at]++ } } }
        val weights = LinkedHashMap<Pair<Int, Int>, Int>()
        val kinds = HashMap<Pair<Int, Int>, RelationKind>()
        fun link(from: Int, to: Int, kind: RelationKind) {
            if (from < 0 || to < 0 || from == to) return
            val pair = from to to
            weights.merge(pair, 1, Int::plus)
            kinds.merge(pair, kind) { a, b -> minOf(a, b, compareBy(relationPriority::indexOf)) }
        }
        for (id in shown) if (parent[id] >= 0) link(shownAs[parent[id]], shownAs[id], RelationKind.CONTAINS)
        for (edge in graph.edges) if (edge.kind != RelationKind.CONTAINS) link(shownAs[edge.caller], shownAs[edge.callee], edge.kind)

        val edges = weights.keys.mapTo(linkedSetOf()) { CallGraphEdge(it.first, it.second, kinds.getValue(it)) }
        val levels = dependencyLevels(shown.size, edges)
        val viewNodes = shown.mapIndexed { index, id ->
            val group = children[id].any(live)
            if (folded != null) nodes[id].copy(id = index, level = levels[index], hidden = folded[index], expanded = false)
            else nodes[id].copy(id = index, level = levels[index], hidden = if (group && id !in expanded) liveSizes[id] else 0,
                expanded = group && id in expanded)
        }
        return DetailGraph(CallGraph(viewNodes, edges, graph.truncated, projectGraph = true), shown, shownAs, graph,
            edges.associateWith { weights.getValue(it.caller to it.callee) })
    }

    /** Null when nothing is excluded; otherwise flags [excluded], their descendants and directories or files emptied by them. */
    private fun gone(excluded: Set<Int>): BooleanArray? {
        if (excluded.none { it in nodes.indices }) return null
        val gone = BooleanArray(nodes.size)
        for (id in preorder()) if (id in excluded || parent[id].let { it >= 0 && gone[it] }) gone[id] = true
        for (id in preorder().asReversed()) {
            val kind = nodes[id].kind
            if ((kind == SymbolKind.DIRECTORY || kind == SymbolKind.FILE) && children[id].isNotEmpty() && children[id].all { gone[it] }) gone[id] = true
        }
        return gone
    }
}

fun CallGraph.hierarchy() = GraphHierarchy(this)

/** Identity of a node that survives rebuilds of the graph: kind, file, container and name. Overloads share a key. */
fun stableKey(node: CallGraphNode): String = "${node.kind}|${node.filePath}|${node.container}|${node.name}"

/** Longest chain of users above each node; edges closing a cycle are ignored. Iterative so large graphs cannot overflow the stack. */
internal fun dependencyLevels(size: Int, edges: Collection<CallGraphEdge>): IntArray {
    val users = List(size) { mutableListOf<Int>() }
    edges.forEach { if (it.caller != it.callee) users[it.callee] += it.caller }
    val levels = IntArray(size) { -1 }
    val visiting = BooleanArray(size)
    for (start in 0 until size) {
        if (levels[start] >= 0) continue
        val stack = ArrayDeque<Pair<Int, Int>>().apply { addLast(start to 0) }
        visiting[start] = true
        while (stack.isNotEmpty()) {
            val (node, next) = stack.last()
            val user = users[node].getOrNull(next)
            if (user == null) {
                levels[node] = users[node].filter { levels[it] >= 0 && !visiting[it] }.maxOfOrNull { levels[it] + 1 } ?: 0
                visiting[node] = false
                stack.removeLast()
                continue
            }
            stack[stack.lastIndex] = node to next + 1
            if (levels[user] < 0 && !visiting[user]) {
                visiting[user] = true
                stack.addLast(user to 0)
            }
        }
    }
    return levels
}

/**
 * A displayed graph derived from a [GraphHierarchy]. [sources] maps displayed ids to hierarchy ids (which are also
 * indices into the source graph's pointers for non-directory nodes). [weights] counts merged relationships per edge.
 */
class DetailGraph(
    val graph: CallGraph,
    val sources: List<Int>,
    private val shownAs: IntArray,
    private val base: CallGraph,
    val weights: Map<CallGraphEdge, Int>,
) {
    /** The displayed id of the node (or its nearest shown ancestor) for a hierarchy id. */
    fun shownId(source: Int): Int? = shownAs.getOrNull(source)?.takeIf { it >= 0 }

    /** Highlights the shown nodes that contain the file's symbols and the nodes they are related to. */
    fun highlightFile(path: String?): FileGraphHighlight {
        val highlight = base.highlightFile(path)
        val owned = highlight.owned.mapNotNullTo(hashSetOf(), ::shownId)
        return FileGraphHighlight(owned, highlight.related.mapNotNullTo(hashSetOf(), ::shownId) - owned)
    }
}

/** Shared name matching for graph symbols and the steps currently visible on a flow chart. */
internal fun fuzzyNameSearch(query: String, ids: Iterable<Int>, name: (Int) -> String): List<Int> {
    val pattern = query.trim()
    if (pattern.isEmpty()) return emptyList()
    val anywhere = NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    val start = NameUtil.buildMatcher(pattern, NameUtil.MatchingCaseSensitivity.NONE)
    return ids.filter { anywhere.matches(name(it)) }
        .sortedWith(compareByDescending<Int> { start.matches(name(it)) }.thenByDescending { anywhere.matchingDegree(name(it)) })
}
