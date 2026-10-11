package dev.insight.enrichment

import com.intellij.ui.JBColor
import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.CallGraphEdge
import dev.insight.callgraph.DetailGraph
import dev.insight.callgraph.Emphasis
import dev.insight.callgraph.EdgeStyle
import dev.insight.callgraph.GraphDecorations
import dev.insight.callgraph.GraphHierarchy
import dev.insight.callgraph.LegendEntry
import dev.insight.callgraph.NodeDecoration
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.callgraph.stableKey
import java.awt.Color
import kotlin.math.roundToInt

enum class ColorBy(val label: String) {
    NONE("No colour"), DOMAIN("Domain"), LAYER("Layer"), IMPORTANCE("Importance");
    override fun toString() = label
}

enum class EdgeFilter(val label: String) {
    ALL("All calls"), MAIN_PATH("Main path only"), ERROR_PATHS("Error paths only");
    override fun toString() = label
}

data class LensOptions(
    val colorBy: ColorBy = ColorBy.DOMAIN,
    val hideTests: Boolean = false,
    val hideUtilities: Boolean = false,
    val edges: EdgeFilter = EdgeFilter.ALL,
    /** [Flow.id] to spotlight, or null. */
    val flowId: String? = null,
)

/** Turns an [EnrichmentSnapshot] into canvas overlays for the current graph build. PSI-free and unit-testable. */
object EnrichmentLens {
    /** Test phase judgments count only from this confidence; a `test` layer choice or tag always counts. */
    const val TEST_PHASE_CONFIDENCE = 0.6
    const val TEST_CONCERN = 0.5
    const val UTILITY = 0.7
    const val ENTRY_POINT = 0.7
    const val BADGE_CONCERN = 0.6
    const val MAX_BADGES = 3
    /** Shares of enriched symbols, by visibility percentile, drawn strong (top) and faint (bottom). */
    const val STRONG_SHARE = 0.1
    const val FAINT_SHARE = 0.5
    const val ERROR_PATH = 0.5
    const val CONTINUES = 0.5

    /** Badge glyph per [Concern.id]; `test` has none because tests can be hidden instead. */
    val badges = linkedMapOf(
        "network" to 'N', "storage" to 'S', "errors" to 'E', "security" to 'L', "config" to 'C', "validation" to 'V',
        "serialization" to 'Z', "logging" to 'G', "ui" to 'U', "concurrency" to 'T',
    )

    private val palette = listOf(
        0x4E79A7 to 0x6A9FD4, 0xF28E2B to 0xF5A55A, 0x59A14F to 0x7BC271, 0xE15759 to 0xEE7D7F, 0x76B7B2 to 0x8FD1CC,
        0xEDC948 to 0xF0D466, 0xB07AA1 to 0xC897BA, 0xFF9DA7 to 0xFFB3BB, 0x9C755F to 0xB89180, 0xD37295 to 0xE091AE,
    ).map { (light, dark) -> JBColor(Color(light), Color(dark)) }
    private val neutral = JBColor(Color(0xB0B0B0), Color(0x6E6E6E))
    /** Low to high importance; brighter on dark themes. */
    private val ramp = listOf(
        0xDCE9F5 to 0x2B3A4A, 0xA9C9E6 to 0x34557A, 0x6FA3D2 to 0x4A78AA, 0x3B7BBF to 0x6EA0D6, 0x1A4F8F to 0xA6CBF2,
    ).map { (light, dark) -> JBColor(Color(light), Color(dark)) }
    internal val importanceNames = listOf("Trivial", "Minor", "Supporting", "Important", "Core")

    /**
     * Ids in `graph` (the current build's [CallGraph], whose ids equal [dev.insight.callgraph.GraphHierarchy] ids for
     * symbols) to exclude from the view under [options]. The snapshot may come from an earlier build.
     */
    fun hiddenSymbols(snapshot: EnrichmentSnapshot, graph: CallGraph, options: LensOptions): Set<Int> {
        if (!options.hideTests && !options.hideUtilities) return emptySet()
        val lens = Remapped.of(snapshot, graph)
        val candidates = (lens.nodes.keys + lens.tags.keys).sorted()
        val hidden = candidates.filterTo(linkedSetOf()) { id ->
            options.hideTests && lens.isTest(id) || options.hideUtilities && (lens.nodes[id]?.utility ?: 0.0) >= UTILITY
        }
        val flow = lens.flows.firstOrNull { it.id == options.flowId } ?: return hidden
        // Keep the flow visible even when it runs through a hidden class or file.
        val parent = GraphHierarchy(graph).parent
        for (id in flow.nodes + flow.entry) {
            var current = id
            while (current >= 0) {
                hidden -= current
                current = parent.getOrElse(current) { -1 }
            }
        }
        return hidden
    }

    /** Decorations for `view`, built from the hierarchy over `graph` (the current build). */
    fun decorate(snapshot: EnrichmentSnapshot, graph: CallGraph, view: DetailGraph, options: LensOptions): GraphDecorations {
        val lens = Remapped.of(snapshot, graph)
        val hierarchy = GraphHierarchy(graph)
        val paths = RelativePaths(snapshot)
        val labels = snapshot.enrichment.groupLabels
        val colours = Colouring(options.colorBy, snapshot.enrichment.domains)
        val shownVisibility = lens.visibility.filterKeys { view.shownId(it) != null }
        val emphasis = Percentiles(shownVisibility.values)

        fun members(source: Int): List<Int> = buildList {
            val stack = ArrayDeque(listOf(source))
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (view.shownId(id) == null) continue
                add(id)
                hierarchy.children[id].asReversed().forEach(stack::addLast)
            }
        }.sorted()

        val nodes = LinkedHashMap<Int, NodeDecoration>()
        val legendValues = hashSetOf<String>()
        for ((displayed, source) in view.sources.withIndex()) {
            val group = hierarchy.isGroup(source)
            val ids = if (group) members(source) else listOf(source)
            val coloured = ids.mapNotNull { id -> lens.nodes[id]?.let(colours::value) }
            coloured.forEach { legendValues += it.first }
            val shares = coloured.groupingBy { it.first }.eachCount()
            val colors = shares.keys.sortedWith(colours.order).map { colours.color(it) to shares.getValue(it).toDouble() }
            val confidence = if (coloured.isEmpty()) 1.0 else coloured.sumOf { it.second } / coloured.size
            val visibility = ids.mapNotNull { shownVisibility[it] }.maxOrNull()
            val node = graph.nodes.getOrNull(source)
            val annotation = lens.nodes[source]
            val kind = hierarchy.nodes[source].kind
            val groupLabel = if (kind == SymbolKind.DIRECTORY || kind == SymbolKind.FILE)
                hierarchy.nodes[source].filePath?.let(paths::relative)?.let(labels::get) else null
            val tooltip = listOfNotNull(groupLabel?.summary, annotation?.let { judgments(it, snapshot.enrichment.domains) })
                .joinToString("\n").ifEmpty { null }
            val decoration = NodeDecoration(
                colors = colors,
                confidence = confidence,
                emphasis = visibility?.let(emphasis::of) ?: Emphasis.NORMAL,
                badges = if (node == null) "" else badgesOf(annotation),
                entryPoint = node != null && (annotation?.entryPoint ?: 0.0) >= ENTRY_POINT,
                label = groupLabel?.label,
                tooltip = tooltip,
            )
            if (decoration != NodeDecoration()) nodes[displayed] = decoration
        }

        val edgeIndex = view.graph.edges.associateBy { it.caller to it.callee }
        fun displayed(edge: CallGraphEdge): CallGraphEdge? {
            val from = view.shownId(edge.caller) ?: return null
            val to = view.shownId(edge.callee) ?: return null
            return if (from == to) null else edgeIndex[from to to]
        }
        val lifted = liftedEdges(graph)
        val underlying = LinkedHashMap<CallGraphEdge, MutableList<EdgeAnnotation>>()
        for (edge in graph.edges) {
            if (edge.kind != RelationKind.CALL) continue
            val annotation = lens.edges[lifted[edge]] ?: continue
            val shown = displayed(edge)?.takeIf { it.kind == RelationKind.CALL } ?: continue
            underlying.getOrPut(shown) { mutableListOf() } += annotation
        }
        val styles = underlying.mapValues { (_, annotations) ->
            when {
                annotations.all { it.errorPath >= ERROR_PATH } -> EdgeStyle.ERROR
                annotations.any { it.continuesOperation >= CONTINUES } -> EdgeStyle.MAIN
                else -> EdgeStyle.INCIDENTAL
            }
        }

        val flow = options.flowId?.let { id -> lens.flows.firstOrNull { it.id == id } }
        val spotlight = flow?.nodes?.mapNotNullTo(linkedSetOf(), view::shownId)
        val originals = lifted.entries.groupBy({ it.value }, { it.key })
        val spotlightEdges = flow?.edges?.flatMap { edge ->
            if (edge.kind == RelationKind.INHERITS) {
                // Flow dispatch points base → implementation; source inheritance points implementation → base.
                listOfNotNull(displayed(edge.copy(caller = edge.callee, callee = edge.caller))
                    ?.takeIf { it.kind == RelationKind.INHERITS })
            } else {
                originals[edge].orEmpty().mapNotNull(::displayed)
            }
        }?.toSet().orEmpty()
        if (flow != null) {
            val stepped = hashSetOf<Int>()
            steps(flow, graph).forEachIndexed { index, id ->
                val shown = view.shownId(id)?.takeIf { view.sources[it] == id } ?: return@forEachIndexed
                if (stepped.add(shown)) nodes[shown] = (nodes[shown] ?: NodeDecoration()).copy(step = index + 1)
            }
        }

        val hiddenEdges = view.graph.edges.filterTo(linkedSetOf()) { edge ->
            edge.kind == RelationKind.CALL && edge !in spotlightEdges && when (options.edges) {
                EdgeFilter.ALL -> false
                EdgeFilter.MAIN_PATH -> styles[edge] == EdgeStyle.INCIDENTAL || styles[edge] == EdgeStyle.ERROR
                EdgeFilter.ERROR_PATHS -> styles[edge] != EdgeStyle.ERROR
            }
        }
        return GraphDecorations(
            nodes = nodes,
            edges = styles,
            hiddenEdges = hiddenEdges,
            spotlight = spotlight,
            spotlightEdges = spotlightEdges,
            legend = legendValues.sortedWith(colours.order).map { LegendEntry(colours.label(it), colours.color(it)) },
        )
    }

    /**
     * Ids in `graph` (the current build) of the numbered steps of flow [flowId]: its functions in [Flow.nodes] order,
     * without classes or properties and without symbols that no longer exist. Step `n` of the canvas is element `n - 1`.
     */
    fun flowSteps(snapshot: EnrichmentSnapshot, graph: CallGraph, flowId: String): List<Int> =
        Remapped.of(snapshot, graph).flows.firstOrNull { it.id == flowId }?.let { steps(it, graph) }.orEmpty()

    private fun steps(flow: Flow, graph: CallGraph): List<Int> = flow.nodes.filter { graph.nodes.getOrNull(it)?.kind == SymbolKind.FUNCTION }

    private fun badgesOf(annotation: NodeAnnotation?): String {
        val strong = annotation?.concerns.orEmpty().filter { (id, p) -> p >= BADGE_CONCERN && id in badges }
        return strong.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { badges.keys.indexOf(it.key) })
            .take(MAX_BADGES).joinToString("") { badges.getValue(it.key).toString() }
    }

    private fun judgments(annotation: NodeAnnotation, domains: List<Domain>): String? = listOfNotNull(
        annotation.domain?.let { d -> "domain: ${domains.firstOrNull { it.id == d.choice }?.name ?: d.choice}" },
        annotation.layer?.let { "layer: ${it.choice}" },
        annotation.importance?.let { "importance: ${"%.1f".format(java.util.Locale.ROOT, it.score)}/${it.max}" },
        annotation.concerns.filterValues { it >= BADGE_CONCERN }.entries.sortedByDescending { it.value }
            .takeIf { it.isNotEmpty() }?.joinToString(", ", "concerns: ") { it.key },
    ).joinToString(" · ").ifEmpty { null }

    /** Colour value of each annotated symbol under one [ColorBy], with its legend order. */
    internal class Colouring(private val by: ColorBy, domains: List<Domain>) {
        private val domainIds = domains.map { it.id }
        private val domainNames = domains.associate { it.id to it.name }
        private val layers = Questions.layers.keys.toList()

        /** Value and confidence, or null when the annotation does not answer this question. */
        fun value(annotation: NodeAnnotation): Pair<String, Double>? = when (by) {
            ColorBy.NONE -> null
            ColorBy.DOMAIN -> annotation.domain?.let { it.choice to it.confidence }
            ColorBy.LAYER -> annotation.layer?.let { it.choice to it.confidence }
            ColorBy.IMPORTANCE -> annotation.importance?.let {
                (it.normalized.coerceIn(0.0, 1.0) * (ramp.size - 1)).roundToInt().toString() to it.confidence
            }
        }

        private fun rank(value: String): Int = when (by) {
            ColorBy.DOMAIN -> if (value == Domain.NONE) Int.MAX_VALUE else domainIds.indexOf(value).let { if (it < 0) domainIds.size else it }
            ColorBy.LAYER -> layers.indexOf(value).let { if (it < 0) layers.size else it }
            else -> value.toIntOrNull() ?: 0
        }

        val order: Comparator<String> = compareBy<String> { rank(it) }.thenBy { it }

        fun color(value: String): Color = when (by) {
            ColorBy.IMPORTANCE -> ramp[(value.toIntOrNull() ?: 0).coerceIn(ramp.indices)]
            ColorBy.DOMAIN -> if (value == Domain.NONE) neutral else palette[slot(domainIds, value)]
            else -> palette[slot(layers, value)]
        }

        /** Known values take palette slots in order; unknown ones get a stable slot from their name. */
        private fun slot(known: List<String>, value: String): Int =
            known.indexOf(value).let { if (it >= 0) it % palette.size else Math.floorMod(value.hashCode(), palette.size) }

        fun label(value: String): String = when (by) {
            ColorBy.DOMAIN -> if (value == Domain.NONE) "None" else domainNames[value] ?: value
            ColorBy.IMPORTANCE -> importanceNames.getOrElse(value.toIntOrNull() ?: 0) { value }
            else -> value.replaceFirstChar { it.uppercase() }
        }
    }

    /** Midrank percentiles of visibility values: ties share a position, so the result does not depend on order. */
    private class Percentiles(values: Collection<Double>) {
        private val sorted = values.sorted().toDoubleArray()

        fun of(value: Double): Emphasis {
            if (sorted.isEmpty()) return Emphasis.NORMAL
            val below = bound(value, strict = true)
            val equal = bound(value, strict = false) - below
            val percentile = (below + equal / 2.0) / sorted.size
            return when {
                percentile >= 1 - STRONG_SHARE -> Emphasis.STRONG
                percentile < FAINT_SHARE -> Emphasis.FAINT
                else -> Emphasis.NORMAL
            }
        }

        /** Number of values below [value] ([strict]) or at most [value]. */
        private fun bound(value: Double, strict: Boolean): Int {
            var low = 0
            var high = sorted.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (if (strict) sorted[middle] < value else sorted[middle] <= value) low = middle + 1 else high = middle
            }
            return low
        }
    }

    /** Maps absolute node paths to the project-relative paths that [GroupLabel]s are keyed by, using the snapshot's facts. */
    internal class RelativePaths(snapshot: EnrichmentSnapshot) {
        private val prefixes = snapshot.input.facts.values.mapNotNull { facts ->
            val absolute = snapshot.graph.nodes.getOrNull(facts.id)?.filePath ?: return@mapNotNull null
            val relative = facts.path ?: return@mapNotNull null
            if (absolute.endsWith("/$relative")) absolute.removeSuffix(relative) else null
        }.distinct().sortedByDescending { it.length }

        fun relative(path: String): String = prefixes.firstOrNull { path.startsWith(it) }?.let(path::removePrefix) ?: path
    }
}

/** Enrichment keyed by ids of the current graph; symbols that no longer exist are dropped. */
internal class Remapped(
    val nodes: Map<Int, NodeAnnotation>,
    val tags: Map<Int, Set<String>>,
    val visibility: Map<Int, Double>,
    val edges: Map<CallGraphEdge, EdgeAnnotation>,
    val flows: List<Flow>,
) {
    fun isTest(id: Int): Boolean {
        if ("test" in tags[id].orEmpty()) return true
        val annotation = nodes[id] ?: return false
        return annotation.layer?.choice == "test" ||
            annotation.phase?.let { it.choice == "test" && it.confidence >= EnrichmentLens.TEST_PHASE_CONFIDENCE } == true ||
            (annotation.concerns["test"] ?: 0.0) >= EnrichmentLens.TEST_CONCERN
    }

    companion object {
        /** Matches nodes by [stableKey]; among duplicates (overloads) the first of each build wins. */
        fun of(snapshot: EnrichmentSnapshot, graph: CallGraph): Remapped {
            val enrichment = snapshot.enrichment
            if (snapshot.graph === graph) {
                return Remapped(enrichment.nodes, enrichment.heuristics.mapValues { it.value.tags }, enrichment.visibility,
                    enrichment.edges, enrichment.flows)
            }
            val current = HashMap<String, Int>()
            graph.nodes.forEach { current.putIfAbsent(stableKey(it), it.id) }
            val seen = hashSetOf<String>()
            val ids = HashMap<Int, Int>()
            snapshot.graph.nodes.forEach { node ->
                val key = stableKey(node)
                if (seen.add(key)) current[key]?.let { ids[node.id] = it }
            }
            fun <V> Map<Int, V>.moved(): Map<Int, V> = entries.mapNotNull { (id, value) -> ids[id]?.let { it to value } }.toMap()
            fun CallGraphEdge.moved(): CallGraphEdge? {
                val caller = ids[caller] ?: return null
                val callee = ids[callee] ?: return null
                return copy(caller = caller, callee = callee)
            }
            val flows = enrichment.flows.mapNotNull { flow ->
                val nodes = flow.nodes.mapNotNull(ids::get)
                val entry = ids[flow.entry] ?: nodes.firstOrNull() ?: return@mapNotNull null
                flow.copy(entry = entry, nodes = nodes, edges = flow.edges.mapNotNullTo(linkedSetOf()) { it.moved() })
            }
            return Remapped(
                enrichment.nodes.moved(),
                enrichment.heuristics.mapValues { it.value.tags }.moved(),
                enrichment.visibility.moved(),
                enrichment.edges.entries.mapNotNull { (edge, value) -> edge.moved()?.let { it to value } }.toMap(),
                flows,
            )
        }
    }
}
