package dev.insight.review.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import dev.insight.agent.SourceSide
import dev.insight.context.*
import dev.insight.diff.ReviewFile
import dev.insight.review.PatchSource
import dev.insight.review.ReviewSource
import dev.insight.review.SummaryNode
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JComponent

/** Summary headings keep their evidence snapshots when moving into the shared context hub. */
internal fun addSummaryContextItem(menu: JPopupMenu, project: Project?, node: SummaryNode,
    sources: List<ReviewSource>, openOnCanvas: ((SummaryNode) -> Unit)? = null) {
    if (project == null) return
    val files = sources.filter { it.path in node.files }.distinctBy { it.path }
    if (files.isEmpty()) return
    menu.add(JMenuItem("Context…").apply {
        name = "summary-action:Context"
        addActionListener {
            val data = ContextData()
            openOnCanvas?.takeIf { files.size == 1 }?.let { open -> data.put(ContextKeys.OPEN_ON_CANVAS) { open(node) } }
            val scopes = if (files.size == 1) {
                val file = files.single()
                when (file) {
                    is ReviewFile -> reviewDiffPick(project, ContextOrigin.OTHER, file, node.lines?.first,
                        if (file.after == null) SourceSide.BEFORE else SourceSide.AFTER, files.filterIsInstance<ReviewFile>(), data).candidates
                    is PatchSource -> listOf(CodeContext(ContextScopeKind.FILE_DIFF, "File diff", file.path, file.absolutePath,
                        null, SourceSide.AFTER, file.patch, true))
                    else -> {
                        val text = file.text ?: return@addActionListener
                        val candidates = capturedSourcePick(project, ContextOrigin.OTHER, file.path, file.absolutePath,
                            text, node.lines?.first, data).candidates
                        val lines = node.lines
                        if (lines == null) candidates else listOf(CodeContext(ContextScopeKind.SELECTION, "Summary source",
                            file.path, file.absolutePath, lines, SourceSide.CURRENT,
                            text.lineSequence().drop(lines.first - 1).take(lines.last - lines.first + 1).joinToString("\n"))) + candidates
                    }
                }
            } else listOf(CodeContext(ContextScopeKind.CHANGE, "${files.size} files", null, null, null, SourceSide.CURRENT,
                files.joinToString("\n\n") { file -> "--- ${file.path} ---\n${(file as? PatchSource)?.patch ?: file.text.orEmpty()}" },
                files.all { it is PatchSource }, files = files.map { it.path }))
            val owner = menu.invoker as? JComponent ?: return@addActionListener
            showContextPicker(project, scopes, defaultScopeIndex(scopes), ContextOrigin.OTHER, data, owner, RelativePoint.getSouthWestOf(owner))
        }
    })
}
