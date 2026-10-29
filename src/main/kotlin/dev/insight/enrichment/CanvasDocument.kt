package dev.insight.enrichment

import dev.insight.graph.CallGraph
import dev.insight.graph.RelationKind
import dev.insight.llm.clipLine

/** One physical line and its exact source origin. Synthetic headers navigate to the declaration. */
data class CanvasLine(
    val text: String,
    val nodeId: Int,
    val filePath: String?,
    val fileLine: Int,
    val header: Boolean = false,
    val stableKey: String = "",
    val qualifiedName: String = "",
)

data class CanvasDocument(val lines: List<CanvasLine>)
data class CanvasRange(val start: Int, val end: Int)
data class CanvasTopic(
    val path: String,
    val ranges: List<CanvasRange>,
    val summary: String = "",
    val inferred: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('>')
    val level: Int get() = path.split('>').size
}
data class CanvasData(val document: CanvasDocument, val topics: List<CanvasTopic>, val failures: Int = 0)

/** Full declaration source copied under a read action; no PSI reaches the pipeline. */
data class CanvasSource(
    val nodeId: Int,
    val text: String,
    val filePath: String?,
    val relativeFile: String,
    val qualifiedName: String,
    val startLine: Int,
    val stableKey: String,
)

object CanvasDocumentBuilder {
    /** Caller-before-callee depth-first traversal, retaining disconnected functions and cycles deterministically. */
    fun build(graph: CallGraph, sources: Map<Int, CanvasSource>, nodeIds: List<Int>? = null,
        fileRoles: Map<String, String> = emptyMap()): CanvasDocument {
        val selected = sources.keys.filterTo(hashSetOf()) { nodeIds == null || it in nodeIds }
        val calls = graph.edges.filter { it.kind == RelationKind.CALL }
        val outgoing = calls.groupBy { it.caller }
        val incoming = calls.groupBy { it.callee }
        val names = graph.nodes.associate { it.id to it.name }
        val levels = graph.nodes.associate { it.id to it.level }
        val order = mutableListOf<Int>()
        val seen = hashSetOf<Int>()
        fun visit(root: Int) {
            val stack = ArrayDeque<Int>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (id !in selected || !seen.add(id)) continue
                order += id
                outgoing[id].orEmpty().asReversed().forEach { stack.add(it.callee) }
            }
        }
        val sorted = selected.sortedWith(compareBy({ sources.getValue(it).relativeFile }, { sources.getValue(it).startLine }, { it }))
        val roots = sorted.filter { id -> incoming[id].orEmpty().none { it.caller in selected } }
        (nodeIds?.take(1).orEmpty() + roots).forEach(::visit)
        sorted.forEach(::visit)
        val lines = mutableListOf<CanvasLine>()
        val filesSeen = hashSetOf<String>()
        for (id in order) {
            val source = sources.getValue(id)
            val body = source.text.trimEnd('\n', '\r')
            if (body.isBlank()) continue
            val physical = body.split('\n').map { it.removeSuffix("\r") }
            val header = buildString {
                append("=== ${source.relativeFile} :: ${source.qualifiedName} (L${source.startLine}-${source.startLine + physical.size - 1}) | depth=${levels[id] ?: 0}")
                fun neighbours(label: String, ids: List<Int>) {
                    val values = ids.mapNotNull { names[it] }.distinct()
                    if (values.isNotEmpty()) append(" | $label: ${values.take(6).joinToString(", ")}${if (values.size > 6) "…" else ""}")
                }
                neighbours("calls", outgoing[id].orEmpty().map { it.callee })
                neighbours("called-by", incoming[id].orEmpty().map { it.caller })
                if (filesSeen.add(source.relativeFile)) fileRoles[source.relativeFile]?.takeIf { it.isNotBlank() }?.let { append(" | file role: ${it.clipLine(240)}") }
                append(" ===")
            }
            fun line(text: String, number: Int, header: Boolean) = CanvasLine(text, id, source.filePath,
                number, header, source.stableKey, source.qualifiedName)
            lines += line(header, source.startLine, true)
            physical.forEachIndexed { offset, text -> lines += line(text, source.startLine + offset, false) }
        }
        return CanvasDocument(lines)
    }

    fun fallbackTopics(document: CanvasDocument): List<CanvasTopic> {
        val starts = document.lines.indices.filter { document.lines[it].header }
        return starts.mapIndexed { index, start ->
            val line = document.lines[start]
            CanvasTopic("${line.filePath?.substringAfterLast('/') ?: "Source"}>${line.qualifiedName}",
                listOf(CanvasRange(start, (starts.getOrNull(index + 1) ?: document.lines.size) - 1)))
        }
    }
}
