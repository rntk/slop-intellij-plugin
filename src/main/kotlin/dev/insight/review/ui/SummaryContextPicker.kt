package dev.insight.review.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import dev.insight.agent.SourceSide
import dev.insight.context.*
import dev.insight.diff.ReviewFile
import dev.insight.review.PatchSource
import dev.insight.review.ReviewSource
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JComponent

/** Summary headings keep their evidence snapshots when moving into the shared context hub. */
internal fun addSummaryContextItem(menu: JPopupMenu, project: Project?, node: SummaryNode,
    sources: List<ReviewSource>, openOnCanvas: ((SummaryNode) -> Unit)? = null,
    openOnWall: ((List<ReviewSource>, String) -> Unit)? = null,
    reviewDestinations: Map<ReviewDestination, () -> Unit> = emptyMap(), summaryActions: ContextSummaryActions? = null) {
    if (project == null) return
    val files = sources.filter { it.path in node.files }.distinctBy { it.path }
    if (files.isEmpty()) return
    menu.add(JMenuItem("Context…").apply {
        name = "summary-action:Context"
        addActionListener {
            val data = ContextData()
            if (reviewDestinations.isNotEmpty()) data.put(ContextKeys.REVIEW_DESTINATIONS, reviewDestinations)
            summaryActions?.let { data.put(ContextKeys.SUMMARY_GENERATION, it) }
            if (files.all { it is ReviewFile }) openOnWall?.let { open ->
                data.put(ContextKeys.OPEN_ON_DIFFS_WALL) { context ->
                    val paths = context.files.toSet()
                    val selected = files.filter { it.path in paths }
                    if (selected.isNotEmpty()) open(selected, context.label)
                }
            }
            if (files.none { it is PatchSource }) openOnWall?.let { open ->
                data.put(ContextKeys.OPEN_ON_CODE_WALL) { context ->
                    val paths = context.files.toSet()
                    val selected = files.filter { it.path in paths }
                    if (selected.isNotEmpty()) open(selected, context.label)
                }
            }
            openOnCanvas?.takeIf { files.size == 1 }?.let { open -> data.put(ContextKeys.OPEN_ON_CANVAS) { open(node) } }
            val scopes = summaryPickerScopes(project, node, files, data)
            val owner = menu.invoker as? JComponent ?: return@addActionListener
            showContextPicker(project, ContextPick(ContextOrigin.OTHER, scopes, data), owner, RelativePoint.getSouthWestOf(owner))
        }
    })
}

internal fun summaryPickerScopes(project: Project, node: SummaryNode, files: List<ReviewSource>, data: ContextData): List<CodeContext> {
    val directory = !node.isTopicBranch && node.kind in setOf(SummaryKind.ROOT, SummaryKind.DIRECTORY)
    val file = files.singleOrNull()
    if (file == null) return listOf(if (directory) directoryScope(node.path, files) else CodeContext(
        ContextScopeKind.CHANGE, "${files.size} files", null, null, null, SourceSide.CURRENT,
        reviewFilesPreview(files), files.all { it is PatchSource }, files = files.map { it.path },
    ))
    val candidates = when (file) {
        is ReviewFile -> reviewDiffPick(project, ContextOrigin.OTHER, file, node.lines?.first,
            if (file.after == null) SourceSide.BEFORE else SourceSide.AFTER, listOf(file), data).candidates
        is PatchSource -> listOf(CodeContext(ContextScopeKind.FILE_DIFF, "File diff", file.path, file.absolutePath,
            null, SourceSide.AFTER, capContext(file.patch), true))
        else -> file.text?.let { text ->
            val source = capturedSourcePick(project, ContextOrigin.OTHER, file.path, file.absolutePath, text, node.lines?.first, data)
            val lines = node.lines
            if (lines == null) source.candidates else listOf(CodeContext(ContextScopeKind.SELECTION, "Summary source",
                file.path, file.absolutePath, lines, SourceSide.CURRENT,
                capContext(text.lineSequence().drop(lines.first - 1).take(lines.last - lines.first + 1).joinToString("\n")))) + source.candidates
        }.orEmpty()
    }
    return if (directory) listOf(directoryScope(node.path, files)) + candidates else candidates
}
