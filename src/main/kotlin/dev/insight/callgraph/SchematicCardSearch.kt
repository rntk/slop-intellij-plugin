package dev.insight.callgraph

import dev.insight.enrichment.FunctionSummary
import dev.insight.graph.CallGraphNode
import dev.insight.ui.CardCandidate

/**
 * A schematic card as the card search offers it: the symbol it shows by name, then its container and file name, then
 * its summary. [usageOf] names the symbol a usage card was found from.
 */
internal fun <K> schematicCardCandidate(key: K, node: CallGraphNode, summary: FunctionSummary?, usageOf: String? = null): CardCandidate<K> {
    val kind = node.kind.name.lowercase().replaceFirstChar { it.uppercase() }
    return CardCandidate(
        key,
        node.name,
        listOfNotNull(usageOf?.let { "Usage of $it" }, kind, node.container.takeIf { it.isNotBlank() }, node.location.takeIf { it.isNotBlank() })
            .joinToString(" · "),
        listOfNotNull(node.container, node.filePath?.substringAfterLast('/')),
        listOfNotNull(schematicSourceSummary(summary), node.filePath).joinToString("\n"),
    )
}
