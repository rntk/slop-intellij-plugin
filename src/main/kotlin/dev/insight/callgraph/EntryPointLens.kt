package dev.insight.callgraph

import dev.insight.entrypoints.EntryPoint
import java.nio.file.Path

/** Matches discovered source locations, then lifts their start markers into the displayed groups. */
object EntryPointLens {
    fun decorate(
        base: GraphDecorations,
        graph: CallGraph,
        entries: List<EntryPoint>,
        projectPath: String?,
        shownId: (Int) -> Int? = { it },
    ): GraphDecorations {
        if (entries.isEmpty() || projectPath == null) return base
        val byPath = graph.nodes.filter { it.inProject && it.filePath != null }
            .groupBy { normalize(it.filePath!!) }
        val matched = linkedMapOf<Int, MutableList<Pair<CallGraphNode, EntryPoint>>>()
        for (entry in entries) {
            val path = normalize(Path.of(projectPath).resolve(entry.path).toString())
            val nodes = byPath[path].orEmpty()
            val symbols = nodes.filter { it.kind != SymbolKind.FILE && it.kind != SymbolKind.DIRECTORY }
            val atLine = symbols.filter { it.location.substringAfterLast(':').toIntOrNull() == entry.line }
            val named = symbols.filter { symbol(it.name) == symbol(entry.symbol) && entry.symbol.isNotBlank() }
            // Exact locations disambiguate overloads. Only use a name fallback when it is unique in this file.
            val targets = atLine.filter { it in named }.ifEmpty { atLine }
                .ifEmpty { named.singleOrNull()?.let(::listOf).orEmpty() }
                .ifEmpty { nodes.filter { it.kind == SymbolKind.FILE } }
            for (node in targets) shownId(node.id)?.let { id ->
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
