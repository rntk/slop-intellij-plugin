package dev.insight.context.actions

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.InsightBundle
import dev.insight.agent.findSourceFile
import dev.insight.canvas.openFilesOnCanvas
import dev.insight.context.ContextAction
import dev.insight.context.ContextKeys
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.context.LinkedFile

/** The most editors one action opens, so a long report does not flood the editor tabs. */
internal const val MAX_LINKED_EDITORS = 20

/** The linked files of a multi-file scope, in the order its text names them; empty for any other scope. */
internal fun ContextRequest.linkedFiles(): List<LinkedFile> {
    if (context.kind != ContextScopeKind.CHANGE) return emptyList()
    val paths = context.files.toSet()
    return data[ContextKeys.LINKED_FILES].orEmpty().filter { it.path in paths }
}

private fun ContextRequest.resolve(files: List<LinkedFile>): List<Pair<LinkedFile, VirtualFile>> =
    ReadAction.compute<List<Pair<LinkedFile, VirtualFile>>, RuntimeException> {
        files.mapNotNull { file -> findSourceFile(project, file.path, file.absolutePath)?.let { file to it } }
    }

/** Every linked file in an editor, at the lines its link names. */
internal class OpenLinkedFilesInEditorsAction : ContextAction {
    override val id = "open.linkedEditors"
    override val title get() = InsightBundle.message("context.action.linked.editors")
    override val group = "Open"
    override val order = 15

    override fun isAvailable(request: ContextRequest) = request.linkedFiles().isNotEmpty()

    override fun perform(request: ContextRequest) {
        val linked = request.linkedFiles()
        val found = request.resolve(linked.take(MAX_LINKED_EDITORS))
        if (found.isEmpty()) return report(request.project, InsightBundle.message("context.action.missing.file", linked.first().path))
        val manager = FileEditorManager.getInstance(request.project)
        // The first link opens last, so its editor is the one in front.
        for ((index, entry) in found.withIndex().reversed()) {
            val (file, vf) = entry
            val lines = file.lines
            val descriptor = OpenFileDescriptor(request.project, vf, ((lines?.first ?: 1) - 1).coerceAtLeast(0), 0)
            val editor = manager.openTextEditor(descriptor, index == 0)
            if (editor != null && lines != null) selectLines(editor, lines)
        }
        val missing = linked.size.coerceAtMost(MAX_LINKED_EDITORS) - found.size
        when {
            linked.size > MAX_LINKED_EDITORS ->
                report(request.project, InsightBundle.message("context.action.linked.editors.capped", found.size, linked.size))
            missing > 0 -> report(request.project, InsightBundle.message("context.action.linked.missing", missing))
        }
    }
}

/** Every linked file as a card of one new canvas session. */
internal class OpenLinkedFilesOnCanvasAction : ContextAction {
    override val id = "explore.linkedCanvas"
    override val title get() = InsightBundle.message("context.action.linked.canvas")
    override val group = "Explore"
    override val order = 10

    override fun isAvailable(request: ContextRequest) = request.linkedFiles().isNotEmpty()

    override fun perform(request: ContextRequest) {
        val linked = request.linkedFiles()
        val found = request.resolve(linked).map { it.second }.distinct()
        if (found.isEmpty()) return report(request.project, InsightBundle.message("context.action.missing.file", linked.first().path))
        openFilesOnCanvas(request.project, found, request.context.label)
    }
}
