package dev.insight.review

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.agent.findSourceFile
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** Coordinates in captured source, never in a rendered patch or a unified diff document. */
internal data class ReviewCodeLocation(
    val path: String,
    val text: String,
    val line: Int,
    val column: Int,
    val absolutePath: String? = null,
) {
    /** Caller holds read access. Unsaved changes count as changes too. */
    fun resolve(project: Project): Pair<VirtualFile, Int>? {
        if (project.isDisposed || line < 1 || column < 0) return null
        val file = findSourceFile(project, path, absolutePath) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        if (document.text != StringUtil.convertLineSeparators(text)) return null
        val row = line - 1
        if (row !in 0 until document.lineCount) return null
        val offset = document.getLineStartOffset(row) + column
        if (offset >= document.getLineEndOffset(row)) return null
        return file to offset
    }
}

internal object ReviewCodeNavigation {
    private val installed = Key.create<Boolean>("insight.review.navigation.installed")

    fun actions(project: Project?, location: (AnActionEvent) -> ReviewCodeLocation?): List<AnAction> =
        listOf(IdeActions.ACTION_GOTO_DECLARATION to "Go to Declaration", IdeActions.ACTION_FIND_USAGES to "Find Usages").map { (id, title) ->
            object : AnAction(title, "Use current project source; available when the displayed snapshot still matches", null) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = project != null && !project.isDisposed &&
                        location(e)?.resolve(project) != null && ActionManager.getInstance().getAction(id) != null
                }

                override fun actionPerformed(e: AnActionEvent) {
                    if (project == null || project.isDisposed) return
                    val captured = location(e) ?: return
                    WriteIntentReadAction.run {
                        val (file, offset) = captured.resolve(project) ?: return@run
                        val action = ActionManager.getInstance().getAction(id) ?: return@run
                        val editor = FileEditorManager.getInstance(project)
                            .openTextEditor(OpenFileDescriptor(project, file, offset), true) as? EditorEx ?: return@run
                        editor.caretModel.moveToOffset(offset)
                        editor.selectionModel.removeSelection()
                        val event = AnActionEvent.createEvent(action, editor.dataContext, null,
                            "InsightReviewNavigation", ActionUiKind.POPUP, e.inputEvent)
                        ActionUtil.performAction(action, event)
                    }
                }
            }
        }

    fun install(component: JComponent, project: Project?, location: (Point) -> ReviewCodeLocation?) {
        if (project == null) return
        component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val target = location(e.point) ?: return
                val group = DefaultActionGroup(actions(project) { target })
                ActionManager.getInstance().createActionPopupMenu("InsightReviewNavigation", group).component.show(component, e.x, e.y)
                e.consume()
            }
        })
    }

    /** Keep native selection/copy actions alongside navigation in embedded snapshot editors. */
    fun install(editor: EditorEx, project: Project?, location: (Int, Int) -> ReviewCodeLocation?) {
        if (project == null || editor.getUserData(installed) == true) return
        editor.putUserData(installed, true)
        editor.installPopupHandler { event ->
            val point = editor.xyToLogicalPosition(event.mouseEvent.point)
            val offset = editor.logicalPositionToOffset(point)
            val target = if (point.line in 0 until editor.document.lineCount)
                location(point.line, offset - editor.document.getLineStartOffset(point.line)) else null
            val group = DefaultActionGroup()
            group.addAll(actions(project) { target })
            group.addSeparator()
            (ActionManager.getInstance().getAction(editor.contextMenuGroupId ?: "EditorPopupMenu") as? ActionGroup)?.let(group::add)
            ActionManager.getInstance().createActionPopupMenu("InsightReviewNavigation", group).component
                .show(editor.contentComponent, event.mouseEvent.x, event.mouseEvent.y)
            true
        }
    }

    /** Character under a painted glyph, accounting for the four spaces used to display each tab. */
    fun columnAt(text: String, x: Int, width: (String) -> Int): Int? {
        if (x < 0) return null
        val painted = StringBuilder()
        for (column in text.indices) {
            painted.append(if (text[column] == '\t') "    " else text[column].toString())
            if (x < width(painted.toString())) return column
        }
        return null
    }
}
