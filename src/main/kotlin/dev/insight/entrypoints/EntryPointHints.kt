package dev.insight.entrypoints

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind

/**
 * Static candidates from the application graph, given to the model as leads so it reads the right files first.
 * A function nobody in the project calls is either an entry point, a framework callback, or dead code; the ones
 * named like entry points, and the ones that reach the most code, are the likely starts.
 */
/** [key] leaves out line numbers and counts, which shift with unrelated edits, so it can key the answer cache. */
data class EntryPointHint(val path: String, val line: Int?, val symbol: String, val reason: String) {
    val text: String get() = "$path${line?.let { ":$it" } ?: ""} $symbol: $reason"
    val key: String get() = "$path $symbol"
}

object EntryPointHints {
    private val ENTRY_NAME = Regex("(?i)^(main|run|start|serve|bootstrap|launch|handler|handle|lambda_handler|cli|create_?app|app|execute|init)$")
    private val TEST_DIR = Regex("(^|/)(test|tests|__tests__|spec|specs|androidTest|testFixtures|testData)/")
    private val TEST_FILE = Regex("(Test|Tests|Spec|IT|_test|\\.test|\\.spec)\\.[^.]+$|(^|/)test_[^/]+$")

    fun isTest(path: String) = TEST_DIR.containsMatchIn(path) || TEST_FILE.containsMatchIn(path)

    /**
     * At most [limit] one-line hints. [relative] maps an absolute file path to a project-relative one, or null for
     * files outside the project.
     */
    fun fromGraph(graph: CallGraph, relative: (String) -> String?, limit: Int = 25): List<EntryPointHint> {
        val calls = graph.edges.filter { it.kind == RelationKind.CALL && it.caller != it.callee }
        val called = calls.mapTo(hashSetOf()) { it.callee }
        val callees = calls.groupBy({ it.caller }, { it.callee })
        data class Candidate(val id: Int, val path: String, val line: Int?, val name: String)
        val candidates = graph.nodes.mapNotNull { node ->
            if (node.kind != SymbolKind.FUNCTION || !node.inProject || node.id in called) return@mapNotNull null
            val path = node.filePath?.let(relative)?.takeUnless(::isTest) ?: return@mapNotNull null
            val name = listOf(node.container, node.name).filter { it.isNotBlank() }.joinToString(".")
            Candidate(node.id, path, node.location.substringAfterLast(':', "").toIntOrNull(), name)
        }
        val named = candidates.filter { ENTRY_NAME.matches(graph.nodes[it.id].name.substringBefore('(').trim()) }
        val hints = named.map { EntryPointHint(it.path, it.line, it.name, "named like an entry point; nothing in the project calls it") }
            .toMutableList()
        val namedIds = named.mapTo(hashSetOf()) { it.id }
        // Reach is costly on large graphs, so it is measured only for the uncalled functions that call the most.
        val reach = candidates.asSequence()
            .filter { it.id !in namedIds && callees[it.id].orEmpty().isNotEmpty() }
            .sortedByDescending { callees[it.id].orEmpty().size }
            .take(300)
            .map { it to reachable(it.id, callees) }
            .filter { it.second >= 3 }
            .sortedByDescending { it.second }
            .toList()
        reach.take((limit - hints.size).coerceAtLeast(0)).forEach { (c, n) ->
            hints += EntryPointHint(c.path, c.line, c.name, "nothing in the project calls it; it reaches $n functions")
        }
        return hints.take(limit)
    }

    private fun reachable(start: Int, callees: Map<Int, List<Int>>, cap: Int = 5_000): Int {
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty() && seen.size < cap) callees[queue.removeFirst()].orEmpty().forEach { if (seen.add(it)) queue.add(it) }
        return seen.size - 1
    }
}
