package dev.insight.chat

import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import dev.insight.agent.TraceDocument
import dev.insight.agent.findSourceFile
import java.util.IdentityHashMap

/** Open live source only when it still matches; historical reads always keep their original coordinates. */
internal class FileTraceNavigator(private val project: Project) {
    private val snapshots = IdentityHashMap<TraceDocument, VirtualFile>()

    fun open(document: TraceDocument, lines: IntRange) {
        if (project.isDisposed || lines.isEmpty() || lines.first < 1 || lines.last > document.starts.size) return
        // Swing link callbacks can run on the EDT without implicit read access.
        WriteIntentReadAction.run {
            val text = StringUtil.convertLineSeparators(document.text)
            val live = if (document.key.kind in setOf("Source", "After") || document.key.kind.startsWith("Project")) {
                findSourceFile(project, document.key.path, document.key.path)?.takeIf {
                    FileDocumentManager.getInstance().getDocument(it)?.text == text
                }
            } else null
            val file = live ?: snapshots.getOrPut(document) {
                val type = FileTypeManager.getInstance().getFileTypeByFileName(
                    if (document.key.kind == "Diff") "read.diff" else document.key.path)
                LightVirtualFile("${document.key.path.substringAfterLast('/')} (${document.key.kind.lowercase()} read snapshot)", type, text).apply {
                    isWritable = false
                    putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, true)
                }
            }
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(OpenFileDescriptor(project, file, lines.first - 1, 0), true) ?: return@run
            val start = editor.document.getLineStartOffset(lines.first - 1)
            val end = editor.document.getLineEndOffset(lines.last - 1)
            editor.caretModel.moveToOffset(start)
            editor.selectionModel.setSelection(start, end)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }
    }
}
