package dev.insight.canvas

import dev.insight.graph.CallGraphNode
import kotlin.math.abs

/**
 * A reference to [node] for saving: [path] is the path the canvas uses for its file (project-relative), [offset] and
 * [line] (0-based) its declaration's place, or -1 when unknown. Null when the node has no file.
 */
fun symbolRef(node: CallGraphNode, offset: Int, line: Int, path: String? = node.filePath): SymbolRef? =
    path?.let { SymbolRef(it, node.name, node.kind.name, node.container, offset, line) }

/**
 * The node among [candidates] that [ref] most likely saved, in a graph built again since: same file, kind and name
 * (or, failing that, the same name without its parameters), preferring the same container, then the declaration
 * nearest the saved offset, else the saved line. [path] gives a node's file in [SymbolRef.filePath]'s form;
 * [offset] its declaration's offset when known. Unresolved calls never match; null when nothing does.
 */
fun restoreSymbol(ref: SymbolRef, candidates: Collection<CallGraphNode>, path: (CallGraphNode) -> String? = { it.filePath },
    offset: (CallGraphNode) -> Int? = { null }): CallGraphNode? {
    val inFile = candidates.filter { !it.unresolved && it.kind.name == ref.kind && path(it) == ref.filePath }
    val named = inFile.filter { it.name == ref.name }.ifEmpty { inFile.filter { baseName(it.name) == baseName(ref.name) } }
    if (named.size <= 1) return named.singleOrNull()
    return named.minWithOrNull(compareBy<CallGraphNode> { if (it.container == ref.container) 0 else 1 }
        .thenBy { node -> offset(node)?.takeIf { ref.offset >= 0 }?.let { abs(it - ref.offset) } ?: Int.MAX_VALUE }
        .thenBy { node -> nodeLine(node)?.takeIf { ref.line >= 0 }?.let { abs(it - ref.line) } ?: Int.MAX_VALUE }
        .thenBy { it.id })
}

/** 0-based line of a node's `File.kt:12` location, or null. */
internal fun nodeLine(node: CallGraphNode): Int? = node.location.substringAfterLast(':', "").toIntOrNull()?.minus(1)

private fun baseName(name: String) = name.substringBefore('(').trim()
