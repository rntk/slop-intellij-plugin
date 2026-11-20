package dev.insight.canvas

import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallGraphResult
import dev.insight.graph.RelationKind

/** A resolved cross-file relation, with declaration offsets for traversal in either direction. */
internal data class FileConnection(val from: String, val to: String, val fromOffset: Int, val toOffset: Int)
internal data class ConnectedFile(val path: String, val parent: String?, val offset: Int = 0)

private val fileRelationKinds = setOf(RelationKind.CALL, RelationKind.REFERENCE, RelationKind.INHERITS)

/** Read action only. Path keys restrict the result to files included in this canvas. */
internal fun fileConnections(result: CallGraphResult, pathKeys: Map<String, String>): List<FileConnection> =
    result.graph.edges.mapNotNull { edge ->
        if (edge.kind !in fileRelationKinds) return@mapNotNull null
        val from = result.graph.nodes[edge.caller]
        val to = result.graph.nodes[edge.callee]
        if (from.unresolved || to.unresolved || from.filePath == to.filePath) return@mapNotNull null
        val fromPath = pathKeys[from.filePath] ?: return@mapNotNull null
        val toPath = pathKeys[to.filePath] ?: return@mapNotNull null
        FileConnection(fromPath, toPath, result.pointers.getOrNull(from.id)?.element?.textOffset ?: 0,
            result.pointers.getOrNull(to.id)?.element?.textOffset ?: 0)
    }.distinct()

/** Visit both directions once per file, starting a new tree at each seed outside the trees already visited. */
internal fun fileConnectionForest(seeds: List<String>, connections: List<FileConnection>): List<ConnectedFile> {
    val neighbours = linkedMapOf<String, MutableList<Pair<String, Int>>>()
    connections.forEach {
        if (it.from != it.to) {
            neighbours.getOrPut(it.from) { mutableListOf() } += it.to to it.fromOffset
            neighbours.getOrPut(it.to) { mutableListOf() } += it.from to it.toOffset
        }
    }
    val seen = hashSetOf<String>()
    val queue = ArrayDeque<String>()
    return buildList {
        for (seed in seeds) {
            if (!seen.add(seed)) continue
            add(ConnectedFile(seed, null))
            queue.add(seed)
            while (queue.isNotEmpty()) {
                val parent = queue.removeFirst()
                neighbours[parent].orEmpty().sortedBy { it.first }.forEach { (path, offset) ->
                    if (seen.add(path)) {
                        add(ConnectedFile(path, parent, offset))
                        queue.add(path)
                    }
                }
            }
        }
    }
}

/** Prefer the reference site in the parent, or the declaration offset for an incoming relation. */
internal fun fileConnectionAnchor(source: CanvasSource, path: String, offset: Int, node: (Int) -> CallGraphNode?): CardAnchor {
    val link = source.links.firstOrNull { link -> link.targets.any { node(it)?.filePath == path } }
    val start = link?.range?.first ?: offset
    return cardAnchor(source.text, start, link?.range?.last?.plus(1) ?: start, source.firstLine)
}
