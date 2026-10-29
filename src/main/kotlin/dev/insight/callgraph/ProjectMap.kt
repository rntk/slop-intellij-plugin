package dev.insight.callgraph

import com.google.gson.Gson
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphNode
import dev.insight.graph.SymbolKind

/** An entry point on the project map: discovered (with its title and kind) or judged by enrichment. */
data class MapEntryPoint(val node: Int, val title: String, val kind: String, val source: String)

/** A named flow the chart's flow selector offers, by its entry function. */
data class MapFlow(val title: String, val description: String?, val entry: Int, val functions: Int)

/**
 * What the project map can add to the graph; a null field is reported as unavailable. [effects] gives a function's own
 * rule effects, [summary] its enrichment summary label. PSI-free; the lambdas must be safe off the EDT.
 */
data class ProjectMapData(
    val entryPoints: List<MapEntryPoint>? = null,
    val flows: List<MapFlow>? = null,
    val effects: ((Int) -> Set<String>?)? = null,
    val summary: ((Int) -> String?)? = null,
    val basePath: String? = null,
)

/**
 * Groups in-project functions into areas by directory. Source roots (`src`, `src/main/kotlin`, `src/test/python`, ...)
 * and test directories are dropped so `src/main/kotlin/com/acme/app/orders` reads `com/acme/app/orders`; tests form
 * their own areas. An area is the deepest directory prefix shared by all non-test functions plus one level: the shared
 * prefix is organisational (`com/acme/app`), and the first level below it is where a project names its modules.
 */
internal class ProjectAreas(graph: CallGraph, basePath: String?) {
    val functions = graph.nodes.filter { it.inProject && it.kind == SymbolKind.FUNCTION }
    val prefix: List<String>
    val areaOf: Map<Int, String>
    /** Project-relative directories of each area, for list_files and read_file. */
    val directories: Map<String, List<String>>

    private class Place(val dirs: List<String>, val logical: List<String>, val test: Boolean)

    init {
        val places = functions.associate { node -> node.id to node.filePath?.let { place(it, basePath) } }
        val main = places.values.filterNotNull().filter { !it.test }.ifEmpty { places.values.filterNotNull() }
        prefix = main.map { it.logical }.reduceOrNull { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }.orEmpty()
        areaOf = places.mapValues { (_, place) ->
            if (place == null) UNKNOWN
            else place.logical.take(prefix.size + 1).joinToString("/").ifEmpty { ROOT } + if (place.test) TESTS else ""
        }
        directories = places.entries.filter { it.value != null }.groupBy({ areaOf.getValue(it.key) }, { it.value!!.dirs.joinToString("/") })
            .mapValues { (_, dirs) -> dirs.distinct().sortedBy { it.length } }
    }

    private fun place(path: String, basePath: String?): Place {
        val relative = path.replace('\\', '/').let { p -> basePath?.replace('\\', '/')?.let { p.removePrefix("$it/") } ?: p }
        val dirs = relative.split('/').filter { it.isNotEmpty() }.dropLast(1)
        var test = false
        val src = dirs.indexOf("src")
        val logical = if (src < 0) dirs else {
            val set = dirs.getOrNull(src + 1)
            // src/<source set>/<language>/... (Gradle, Maven) or plain src/...
            if (set != null && set.matches(SOURCE_SET) && src + 2 < dirs.size) {
                test = set.contains("test", ignoreCase = true)
                dirs.take(src) + dirs.drop(src + 3)
            } else dirs.take(src) + dirs.drop(src + 1)
        }
        if (logical.any { it.lowercase() in TEST_DIRS }) test = true
        return Place(dirs, logical.filter { it.lowercase() !in TEST_DIRS }, test)
    }

    companion object {
        const val ROOT = "(root)"
        const val UNKNOWN = "(unknown location)"
        const val TESTS = " (tests)"
        private val SOURCE_SET = Regex("main|test|tests|androidTest|testFixtures|integrationTest|commonMain|jvmMain|jsMain")
        private val TEST_DIRS = setOf("test", "tests", "__tests__", "testing")
    }
}

/**
 * The zoomed-out view of the project: areas with function, entry point and rule effect counts and their hub functions,
 * static calls between areas, entry points and named flows. Deterministic, bounded and paged over areas; the global
 * sections are on offset 0. PSI-free and safe off the EDT on an immutable graph.
 */
internal fun chartProjectMap(graph: CallGraph, revision: Int, offset: Int, data: ProjectMapData): String {
    val areas = ProjectAreas(graph, data.basePath)
    val byArea = areas.functions.groupBy { areas.areaOf.getValue(it.id) }
    val order = byArea.entries.sortedWith(compareByDescending<Map.Entry<String, List<CallGraphNode>>> { it.value.size }.thenBy { it.key })
        .map { it.key }
    val rank = order.withIndex().associate { it.value to it.index }
    val calls = StaticCalls(graph)
    val entries = data.entryPoints?.filter { areas.areaOf.containsKey(it.node) }?.distinctBy { it.node }
    val entryAreas = entries.orEmpty().groupingBy { areas.areaOf.getValue(it.node) }.eachCount()
    fun area(id: Int) = areas.areaOf.getValue(id)
    fun name(id: Int) = graph.nodes[id].let { node ->
        val owner = node.container.substringBefore(" in ").substringAfterLast('.').substringAfterLast('/').trim()
        val short = node.name.substringBefore('(')
        (if (owner.isEmpty() || owner == short) short else "$owner.$short").take(120)
    }
    val page = order.drop(offset).take(MAP_PAGE)
    val effectsKnown = data.effects != null
    val result = linkedMapOf<String, Any?>("revision" to revision, "functions_total" to areas.functions.size,
        "areas_total" to order.size, "offset" to offset, "next_offset" to (offset + MAP_PAGE).takeIf { it < order.size },
        "area_heuristic" to "Directory of each function's file without source roots (src/main/<lang>) or test directories; an " +
            "area is the shared prefix plus one level. Test code forms separate '(tests)' areas.",
        "common_prefix" to areas.prefix.joinToString("/").ifEmpty { null },
        "areas" to page.map { label ->
            val members = byArea.getValue(label)
            val ids = members.mapTo(hashSetOf()) { it.id }
            val hubs = members.map { node -> node.id to calls.callers[node.id].orEmpty().count { it in ids } }
                .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }
                    .thenByDescending { calls.callers[it.first].orEmpty().size }.thenBy { name(it.first) }).take(MAP_HUBS)
            linkedMapOf("area" to label, "directories" to areas.directories[label].orEmpty().take(3),
                "functions" to members.size, "files" to members.mapNotNull { it.filePath }.distinct().size,
                "entry_points" to if (entries == null) null else entryAreas[label] ?: 0,
                "effects" to if (!effectsKnown) null else listOf("storage", "network").associateWith { effect ->
                    members.count { effect in data.effects.invoke(it.id).orEmpty() } },
                "hubs" to hubs.map { (id, fanIn) -> linkedMapOf("node_id" to id, "name" to name(id), "in_area_callers" to fanIn) +
                    (data.summary?.let { mapOf("summary" to it(id)?.take(120)) } ?: emptyMap()) })
        })
    if (offset == 0) {
        val between = calls.callees.flatMap { (caller, callees) -> callees.map { area(caller) to area(it) } }
            .filter { it.first != it.second }.groupingBy { it }.eachCount()
        result["area_calls_total"] = between.size
        result["area_calls"] = between.entries.sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }
            .thenBy { it.key.first }.thenBy { it.key.second }).take(MAP_AREA_CALLS)
            .map { mapOf("from" to it.key.first, "to" to it.key.second, "calls" to it.value) }
        result["entry_points_total"] = entries?.size
        result["entry_points"] = entries?.sortedWith(compareBy<MapEntryPoint> { rank.getValue(area(it.node)) }.thenBy { name(it.node) })
            ?.take(MAP_ENTRIES)?.map { mapOf("node_id" to it.node, "name" to name(it.node), "title" to it.title.take(120),
                "kind" to it.kind, "source" to it.source, "area" to area(it.node)) }
            ?: "unavailable: run the Entry Points search or Enrich"
        val flows = data.flows?.filter { areas.areaOf.containsKey(it.entry) }
        result["flows_total"] = flows?.size
        result["flows"] = flows?.take(MAP_FLOWS)?.map { mapOf("title" to it.title.take(120), "description" to it.description?.take(160),
            "entry_node_id" to it.entry, "entry_name" to name(it.entry), "area" to area(it.entry), "functions" to it.functions) }
            ?: "unavailable: run Enrich to name flows"
    }
    result["unavailable"] = listOfNotNull(
        "entry points: run the Entry Points search or Enrich".takeIf { entries == null },
        "rule effects (storage/network counts): run Enrich".takeIf { !effectsKnown },
        "function summaries of hubs: run Enrich".takeIf { data.summary == null },
        "named flows: run Enrich".takeIf { data.flows == null })
    result["evidence"] = "Areas group files by directory, not by runtime behaviour. Call counts are static in-project calls; " +
        "entry points come from discovery and enrichment judgments; effects are rule-derived from imports; summaries are " +
        "model interpretations. Node IDs belong to this revision."
    return Gson().toJson(result)
}

private const val MAP_PAGE = 10
private const val MAP_HUBS = 3
private const val MAP_AREA_CALLS = 15
private const val MAP_ENTRIES = 25
private const val MAP_FLOWS = 12
