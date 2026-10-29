package dev.insight.callgraph

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import dev.insight.core.Hashing

/** Copy source under a background read action; never retain PSI in a conversation snapshot. */
internal fun scopedChartContext(project: Project, result: CallGraphResult, revision: Int, target: FlowChatTarget): String {
    val ids = (listOfNotNull(target.owner) + target.nodes).distinct().take(7)
    val sources = ids.map { id ->
        val node = result.graph.nodes[id]
        val element = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid }
        val file = element?.containingFile
        val document = file?.let { PsiDocumentManager.getInstance(project).getDocument(it) }
        val text = element?.text
        val range = element?.textRange
        val limit = if (id == target.owner) 4000 else 2000
        chartNodeData(node) + mapOf(
            "file_name" to file?.name,
            "project_path" to node.filePath?.let { path -> project.basePath?.let { path.removePrefix("$it/") } ?: path },
            "language" to file?.language?.id,
            "start_line" to range?.let { document?.getLineNumber(it.startOffset)?.plus(1) },
            "end_line" to range?.let { document?.getLineNumber((it.endOffset - 1).coerceAtLeast(it.startOffset))?.plus(1) },
            "declaration_start_offset" to range?.startOffset,
            "declaration_end_offset" to range?.endOffset,
            "source" to text?.take(limit), "source_truncated" to (text != null && text.length > limit),
            "source_available" to (text != null),
            "source_hash" to text?.let(Hashing::sha256Of),
        )
    }
    val calls = result.graph.edges.filter { it.kind == RelationKind.CALL && (it.caller in ids || it.callee in ids) }
    val nearby = calls.take(20).flatMap { listOf(it.caller, it.callee) }.distinct()
    return Gson().toJson(mapOf(
        "captured_revision" to revision, "kind" to target.kind, "label" to target.label?.take(300),
        "owner_node_id" to target.owner, "focus_node_ids" to target.nodes,
        "sources" to sources,
        "region_key" to target.region?.key,
        "call_occurrences" to target.region?.sites?.map { site -> mapOf(
            "callee_node_id" to site.callee, "file_offset" to site.offset, "text" to site.text?.take(300),
            "control_frames" to site.frames,
        ) },
        "static_calls" to calls.take(20).map { listOf(it.caller, it.callee) },
        "nearby_functions" to nearby.map { chartNodeData(result.graph.nodes[it]) },
        "static_calls_truncated" to (calls.size > 20),
        "evidence" to "Captured source and static graph evidence. Visual connectors do not prove direct calls or runtime order. Source and occurrence offsets describe the captured version; read_file supplies current code.",
    ))
}

/** The current declaration of function [id] with its file lines; null without valid source. Callers hold a read action. */
internal fun declarationSource(project: Project, result: CallGraphResult, id: Int): DeclarationSource? {
    val element = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid } ?: return null
    val file = element.containingFile ?: return null
    val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
    val range = element.textRange ?: return null
    val first = document.getLineNumber(range.startOffset)
    val last = document.getLineNumber((range.endOffset - 1).coerceAtLeast(range.startOffset))
    val path = (result.graph.nodes.getOrNull(id)?.filePath ?: file.virtualFile?.path ?: file.name)
        .let { path -> project.basePath?.let { path.removePrefix("$it/") } ?: path }
    return DeclarationSource(path, first + 1, (first..last).map { line ->
        document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
    })
}
