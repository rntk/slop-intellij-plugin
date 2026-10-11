package dev.insight.enrichment

import dev.insight.callgraph.DetailGraph
import dev.insight.callgraph.FooterItem
import dev.insight.callgraph.GraphHierarchy
import dev.insight.callgraph.GraphSections
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.Section
import dev.insight.callgraph.SectionFooter
import dev.insight.callgraph.SymbolKind
import java.awt.Color
import kotlin.math.roundToInt

/** What the columns of the sections view stand for. [enriched] groupings need an enrichment run. */
enum class SectionBy(val label: String, val enriched: Boolean) {
    FILE("File", false), FOLDER("Folder", false), DOMAIN("Domain", true), LAYER("Layer", true), PHASE("Phase", true),
    IMPORTANCE("Importance", true);
    override fun toString() = label
}

/**
 * Groups the nodes of a displayed graph into titled, annotated sections for the canvas. Works without enrichment for
 * files and folders; with a snapshot, sections are annotated with group labels, domain descriptions and judgment
 * summaries, and the footer lists the main flows. PSI-free and unit-testable.
 */
object SectionLens {
    const val MAX_FOOTER_ITEMS = 4
    private const val UNCLASSIFIED = ""

    /** Sections for `view`, built from [hierarchy] (over the current build); the snapshot may come from an earlier build. */
    fun sections(
        hierarchy: GraphHierarchy,
        view: DetailGraph,
        by: SectionBy,
        snapshot: EnrichmentSnapshot?,
        projectName: String,
        options: LensOptions = LensOptions(),
    ): GraphSections {
        val graph = hierarchy.graph
        val lens = snapshot?.let { Remapped.of(it, graph) }
        val domains = snapshot?.enrichment?.domains.orEmpty()
        val paths = Paths(hierarchy, snapshot)

        // Hierarchy ids folded into each displayed node, the node itself included.
        val folded = HashMap<Int, MutableList<Int>>()
        for (id in hierarchy.nodes.indices) view.shownId(id)?.let { folded.getOrPut(it) { mutableListOf() } += id }
        // Expanded directories and files are represented by the sections themselves, not drawn as nodes.
        val drawn = view.graph.nodes.filterNot { it.expanded && (it.kind == SymbolKind.DIRECTORY || it.kind == SymbolKind.FILE) }.map { it.id }

        fun ancestor(source: Int, kind: SymbolKind): Int? =
            generateSequence(source) { hierarchy.parent[it].takeIf { p -> p >= 0 } }.firstOrNull { hierarchy.nodes[it].kind == kind }

        fun value(annotation: NodeAnnotation): String? = when (by) {
            SectionBy.DOMAIN -> annotation.domain?.choice
            SectionBy.LAYER -> annotation.layer?.choice
            SectionBy.PHASE -> annotation.phase?.choice
            SectionBy.IMPORTANCE -> annotation.importance?.let { bucket(it).toString() }
            else -> null
        }

        val keyOf = drawn.associateWith { displayed ->
            val source = view.sources[displayed]
            when (by) {
                SectionBy.FILE -> ancestor(source, SymbolKind.FILE)?.let { "file:$it" }
                    ?: ancestor(source, SymbolKind.DIRECTORY)?.let { "dir:$it" } ?: UNCLASSIFIED
                SectionBy.FOLDER -> ancestor(source, SymbolKind.DIRECTORY)?.let { "dir:$it" } ?: UNCLASSIFIED
                // An expanded class without its own judgment goes with most of its members.
                else -> lens?.let { l ->
                    majority(folded[displayed].orEmpty().mapNotNull { l.nodes[it]?.let(::value) })
                        ?: majority(descendants(hierarchy, source).mapNotNull { l.nodes[it]?.let(::value) })
                } ?: UNCLASSIFIED
            }
        }
        val members = drawn.groupBy { keyOf.getValue(it) }
        val meanLevel = members.mapValues { (_, ids) -> ids.map { view.graph.nodes[it].level }.average() }
        val title = members.keys.associateWith { key -> title(key, by, hierarchy, domains, lens != null) }
        val keys = members.keys.sortedWith(order(by, meanLevel, title))
        val index = keys.withIndex().associate { it.value to it.index }
        val sectionOf = drawn.associateWith { index.getValue(keyOf.getValue(it)) }

        // Section of every hierarchy id, through the displayed node it is folded into.
        val sectionOfSource = HashMap<Int, Int>()
        for ((displayed, ids) in folded) sectionOf[displayed]?.let { section -> ids.forEach { sectionOfSource[it] = section } }
        val outgoing = IntArray(keys.size)
        val incoming = IntArray(keys.size)
        val between = LinkedHashMap<Pair<Int, Int>, Int>()
        for (edge in graph.edges) {
            if (edge.kind != RelationKind.CALL) continue
            val from = sectionOfSource[edge.caller] ?: continue
            val to = sectionOfSource[edge.callee] ?: continue
            if (from == to) continue
            outgoing[from]++
            incoming[to]++
            between.merge(from to to, 1, Int::plus)
        }

        val domainColours = EnrichmentLens.Colouring(ColorBy.DOMAIN, domains)
        val sections = keys.mapIndexed { i, key ->
            val sources = members.getValue(key).flatMap { folded[it].orEmpty() }
            val symbols = sources.filter { hierarchy.nodes[it].kind.let { k -> k != SymbolKind.DIRECTORY && k != SymbolKind.FILE } }
            val annotations = sources.mapNotNull { lens?.nodes?.get(it) }
            val facts = buildList {
                add(plural(symbols.size, "symbol"))
                if (by != SectionBy.FILE) {
                    val files = sources.mapNotNullTo(hashSetOf()) { id -> hierarchy.nodes[id].filePath.takeIf { hierarchy.nodes[id].kind != SymbolKind.DIRECTORY } }
                    if (files.isNotEmpty()) add("in ${plural(files.size, "file")}")
                }
                annotations.count { (it.entryPoint ?: 0.0) >= EnrichmentLens.ENTRY_POINT }.takeIf { it > 0 }?.let { add(plural(it, "entry point")) }
                if (outgoing[i] + incoming[i] > 0) add("calls ${outgoing[i]} out · ${incoming[i]} in")
                if (by == SectionBy.FILE || by == SectionBy.FOLDER) {
                    majority(annotations.mapNotNull { it.domain?.choice?.takeIf { d -> d != Domain.NONE } })
                        ?.let { id -> add("mostly ${domains.firstOrNull { it.id == id }?.name ?: id}") }
                    majority(annotations.mapNotNull { it.layer?.choice })?.let { add("$it layer") }
                }
            }
            val colourBy = when (by) {
                SectionBy.DOMAIN -> ColorBy.DOMAIN
                SectionBy.LAYER -> ColorBy.LAYER
                SectionBy.IMPORTANCE -> ColorBy.IMPORTANCE
                else -> options.colorBy
            }
            val colouring = if (colourBy == ColorBy.DOMAIN) domainColours else EnrichmentLens.Colouring(colourBy, domains)
            val color: Color? = when {
                key == UNCLASSIFIED -> null
                by.enriched -> if (by == SectionBy.PHASE) null else colouring.color(key)
                else -> majority(annotations.mapNotNull { colouring.value(it)?.first })?.let(colouring::color)
            }
            annotate(key, by, hierarchy, paths, snapshot, domains, lens != null).copy(title = title.getValue(key), facts = facts, color = color)
        }

        val totalSymbols = drawn.flatMap { folded[it].orEmpty() }.count { hierarchy.nodes[it].kind.let { k -> k != SymbolKind.DIRECTORY && k != SymbolKind.FILE } }
        val subtitle = listOfNotNull(
            plural(keys.size, "section"), plural(totalSymbols, "symbol"), "${between.values.sum()} calls between sections",
            if (by.enriched && lens == null) "use Enrich to classify symbols" else null,
        ).joinToString(" · ")

        val names = keys.indices.associateWith { sections[it].title }
        val footer = lens?.flows?.takeIf { it.isNotEmpty() }?.let { flows ->
            SectionFooter("Flows", flows.sortedByDescending { it.nodes.size }.take(MAX_FOOTER_ITEMS).map { flow ->
                val entry = graph.nodes.getOrNull(flow.entry)?.name
                FooterItem(flow.name, flow.description ?: listOfNotNull(plural(flow.nodes.size, "step"), entry?.let { "from $it" }).joinToString(" "))
            })
        } ?: between.entries.sortedByDescending { it.value }.take(MAX_FOOTER_ITEMS).takeIf { it.isNotEmpty() }?.let { pairs ->
            SectionFooter("Calls between sections", pairs.map { (pair, count) ->
                FooterItem("${names[pair.first]} → ${names[pair.second]}", plural(count, "call"))
            })
        }
        return GraphSections("$projectName · by ${by.label.lowercase()}", subtitle, sections, sectionOf, footer)
    }

    private fun bucket(score: ScoreValue): Int = (score.normalized.coerceIn(0.0, 1.0) * (Questions.importance.size - 1)).roundToInt()

    private fun descendants(hierarchy: GraphHierarchy, id: Int): List<Int> = buildList {
        val stack = ArrayDeque(hierarchy.children[id])
        while (stack.isNotEmpty()) stack.removeLast().also { add(it) }.let { stack.addAll(hierarchy.children[it]) }
    }

    /** Most frequent value; ties go to the value seen first. */
    private fun majority(values: List<String>): String? = values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"

    private fun title(key: String, by: SectionBy, hierarchy: GraphHierarchy, domains: List<Domain>, enriched: Boolean): String = when {
        key == UNCLASSIFIED -> when {
            by == SectionBy.FILE || by == SectionBy.FOLDER -> "Project root"
            !enriched -> "Not enriched"
            else -> "Unclassified"
        }
        key.startsWith("file:") || key.startsWith("dir:") -> hierarchy.nodes[key.substringAfter(':').toInt()].let {
            if (it.kind == SymbolKind.DIRECTORY && by == SectionBy.FOLDER) it.container.ifEmpty { it.name } + "/" else it.name
        }.replace("//", "/")
        by == SectionBy.DOMAIN -> if (key == Domain.NONE) "Cross-cutting" else domains.firstOrNull { it.id == key }?.name ?: key
        by == SectionBy.IMPORTANCE -> EnrichmentLens.importanceNames.getOrElse(key.toIntOrNull() ?: 0) { key }
        else -> key.replaceFirstChar { it.uppercase() }
    }

    /** Headline, description and tooltip; title, facts and colour are filled in by the caller. */
    private fun annotate(
        key: String, by: SectionBy, hierarchy: GraphHierarchy, paths: Paths, snapshot: EnrichmentSnapshot?, domains: List<Domain>, enriched: Boolean,
    ): Section {
        if (key == UNCLASSIFIED) return Section("", description = when {
            by == SectionBy.FILE || by == SectionBy.FOLDER -> "Symbols outside any folder"
            !enriched -> "Use Enrich to group symbols by ${by.label.lowercase()}"
            else -> "Symbols without a ${by.label.lowercase()} judgment"
        })
        return when (by) {
            SectionBy.FILE, SectionBy.FOLDER -> {
                val node = hierarchy.nodes[key.substringAfter(':').toInt()]
                val path = node.filePath
                val label = path?.let { snapshot?.enrichment?.groupLabels?.get(paths.project(it)) }
                val shown = path?.let(paths::display).orEmpty()
                val collapsed = if (by == SectionBy.FILE && node.kind == SymbolKind.DIRECTORY) "Folder (collapsed) · ${node.location}" else null
                // A path that only repeats the title adds nothing.
                Section("", headline = label?.label, description = label?.summary ?: collapsed ?: shown.takeIf { it != node.name && it.isNotEmpty() },
                    tooltip = listOfNotNull(shown.takeIf { label != null || collapsed != null }, collapsed?.let { "Expand the folder to see its files" })
                        .joinToString("\n").ifEmpty { null })
            }
            SectionBy.DOMAIN -> Section("", description = if (key == Domain.NONE) "Generic or cross-cutting code outside the project's functional areas"
                else domains.firstOrNull { it.id == key }?.description)
            SectionBy.LAYER -> Section("", description = Questions.layers[key]?.replaceFirstChar { it.uppercase() })
            SectionBy.PHASE -> Section("", description = Questions.phases[key]?.replaceFirstChar { it.uppercase() })
            SectionBy.IMPORTANCE -> Section("", description = Questions.importance.getOrNull(key.toIntOrNull() ?: -1))
        }
    }

    /** Project-relative paths for group label lookups (from the snapshot) and for display (relative to the hierarchy's top folder). */
    private class Paths(hierarchy: GraphHierarchy, snapshot: EnrichmentSnapshot?) {
        private val relative = snapshot?.let(EnrichmentLens::RelativePaths)
        /** The deepest folder shared by all files, as in [GraphHierarchy]. */
        private val base = hierarchy.nodes.filter { it.kind == SymbolKind.FILE }.mapNotNull { it.filePath?.substringBeforeLast('/', "")?.split('/') }
            .reduceOrNull { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }?.joinToString("/")

        fun project(path: String): String = relative?.relative(path) ?: display(path)

        fun display(path: String): String = relative?.relative(path)
            ?: base?.takeIf { path.startsWith("$it/") }?.let { path.removePrefix("$it/") } ?: path
    }

    /** Users left of what they use; ordered taxonomies keep their own order. Unclassified and cross-cutting go last. */
    private fun order(by: SectionBy, meanLevel: Map<String, Double>, title: Map<String, String>): Comparator<String> {
        val fixed: List<String>? = when (by) {
            SectionBy.LAYER -> listOf("test") + Questions.layers.keys.filter { it != "test" }
            SectionBy.PHASE -> Questions.phases.keys.toList()
            SectionBy.IMPORTANCE -> Questions.importance.indices.reversed().map { it.toString() }
            else -> null
        }
        return compareBy<String> { it == UNCLASSIFIED || (by == SectionBy.DOMAIN && it == Domain.NONE) }
            .thenBy { key -> fixed?.indexOf(key)?.let { if (it < 0) fixed.size else it } ?: 0 }
            .thenBy { meanLevel.getValue(it) }
            .thenBy { title.getValue(it) }
    }
}
