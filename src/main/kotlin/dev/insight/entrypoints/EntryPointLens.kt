package dev.insight.entrypoints

import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphNode
import dev.insight.graph.Emphasis
import dev.insight.graph.GraphDecorations
import dev.insight.graph.NodeDecoration
import dev.insight.graph.SymbolKind
import java.nio.file.Path

/** Matches discovered source locations, then lifts their start markers into the displayed groups. */
object EntryPointLens {
    /** With [exact], require a unique path/line/symbol match; decorations can use softer display fallbacks. */
    fun targets(graph: CallGraph, entry: EntryPoint, projectPath: String?, exact: Boolean = false): List<CallGraphNode> {
        if (projectPath == null) return emptyList()
        val path = normalize(Path.of(projectPath).resolve(entry.path).toString())
        val nodes = graph.nodes.filter { it.inProject && it.filePath?.let(::normalize) == path }
        val symbols = nodes.filter { it.kind != SymbolKind.FILE && it.kind != SymbolKind.DIRECTORY }
        val atLine = symbols.filter { it.location.substringAfterLast(':').toIntOrNull() == entry.line }
        val named = symbols.filter { symbol(it.name) == symbol(entry.symbol) && entry.symbol.isNotBlank() }
        // Flow roots must agree on path, declaration line and symbol, with no ambiguous matches.
        if (exact) return atLine.filter { it in named }.singleOrNull()?.let(::listOf).orEmpty()
        return atLine.filter { it in named }.ifEmpty { atLine }
            .ifEmpty { named.singleOrNull()?.let(::listOf).orEmpty() }
            .ifEmpty { nodes.filter { it.kind == SymbolKind.FILE } }
    }

    fun decorate(
        base: GraphDecorations,
        graph: CallGraph,
        entries: List<EntryPoint>,
        projectPath: String?,
        shownId: (Int) -> Int? = { it },
    ): GraphDecorations {
        if (entries.isEmpty() || projectPath == null) return base
        val matched = linkedMapOf<Int, MutableList<Pair<CallGraphNode, EntryPoint>>>()
        for (entry in entries) {
            for (node in targets(graph, entry, projectPath)) shownId(node.id)?.let { id ->
                matched.getOrPut(id) { mutableListOf() } += node to entry
            }
        }
        if (matched.isEmpty()) return base
        val decorations = base.nodes.toMutableMap()
        for ((id, starts) in matched) {
            val old = decorations[id] ?: NodeDecoration()
            val details = starts.distinct().joinToString("\n") { (node, entry) ->
                val contains = if (node.kind == SymbolKind.FILE) "Contains entry point" else "Entry point"
                "$contains: ${entry.title} (${entry.kind.label}) · ${entry.path}:${entry.line}" +
                    entry.description.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            }
            decorations[id] = old.copy(entryPoint = true, emphasis = Emphasis.STRONG,
                tooltip = listOfNotNull(old.tooltip, details).joinToString("\n"))
        }
        return base.copy(nodes = decorations)
    }

    private fun normalize(path: String) = Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/')
    private fun symbol(name: String) = name.substringBefore('(').trim().substringAfterLast("::").substringAfterLast('.').substringAfterLast('#')
}
