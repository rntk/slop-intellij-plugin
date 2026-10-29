package dev.insight.graph

import java.awt.Color

/**
 * Optional overlay for a displayed [CallGraph] (usually a [DetailGraph.graph]), derived from enrichment by
 * `dev.insight.enrichment.EnrichmentLens`. All ids are displayed ids; the canvas only paints what it is given.
 */
data class GraphDecorations(
    val nodes: Map<Int, NodeDecoration> = emptyMap(),
    val edges: Map<CallGraphEdge, EdgeStyle> = emptyMap(),
    /** Edges the canvas skips entirely (for example incidental calls under "main path only"). */
    val hiddenEdges: Set<CallGraphEdge> = emptySet(),
    /** When non-null, nodes outside this set are muted like nodes outside a file highlight. */
    val spotlight: Set<Int>? = null,
    /** Edges kept at full opacity and drawn with the accent while [spotlight] is set, for example a flow's calls. */
    val spotlightEdges: Set<CallGraphEdge> = emptySet(),
    /** Colour legend for the current "colour by" choice, in display order. */
    val legend: List<LegendEntry> = emptyList(),
) {
    companion object { val NONE = GraphDecorations() }
}

data class LegendEntry(val label: String, val color: Color)

/**
 * [colors] are proportional shares (weights need not sum to 1): one entry for a symbol, the mix of members for a
 * group. [confidence] (0..1) scales the colour's opacity. [step] is the 1-based position in the spotlighted flow.
 */
data class NodeDecoration(
    val colors: List<Pair<Color, Double>> = emptyList(),
    val confidence: Double = 1.0,
    val emphasis: Emphasis = Emphasis.NORMAL,
    /** Short glyphs for strong concerns, for example "N" network, "S" storage, "E" errors. */
    val badges: String = "",
    val entryPoint: Boolean = false,
    val step: Int? = null,
    /** Replaces the second line of a group node, for example an LLM group label. */
    val label: String? = null,
    /** Extra tooltip lines (summary, judgments). */
    val tooltip: String? = null,
)

enum class Emphasis { STRONG, NORMAL, FAINT }

/** How a call edge is painted: MAIN continues the caller's operation, INCIDENTAL is a helper call, ERROR a failure path. */
enum class EdgeStyle { MAIN, INCIDENTAL, ERROR }
