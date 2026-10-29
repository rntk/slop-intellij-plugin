package dev.insight.callgraph

import dev.insight.entrypoints.EntryPoint
import dev.insight.entrypoints.EntryPointKind
import dev.insight.graph.CallGraphNode
import dev.insight.graph.SymbolKind
import java.nio.file.Path

/** Keep source coordinates, rather than graph ids, so the choice survives graph rebuilds. */
internal fun CallGraphNode.manualFlowEntryPoint(projectPath: String?): EntryPoint? {
    if (!inProject || kind != SymbolKind.FUNCTION || projectPath == null || filePath == null) return null
    val line = location.substringAfterLast(':').toIntOrNull()?.takeIf { it > 0 } ?: return null
    val base = Path.of(projectPath.replace('\\', '/')).normalize()
    val file = Path.of(filePath.replace('\\', '/')).normalize()
    if (!file.startsWith(base)) return null
    return EntryPoint(
        path = base.relativize(file).toString().replace('\\', '/'),
        line = line,
        symbol = name,
        kind = EntryPointKind.OTHER,
        title = listOf(container, name).filter { it.isNotBlank() }.joinToString("."),
        description = "Manually selected entrypoint",
        confidence = 1.0,
    )
}
