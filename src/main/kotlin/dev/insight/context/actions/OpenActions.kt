package dev.insight.context.actions

import com.intellij.diff.DiffManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import dev.insight.InsightBundle
import dev.insight.context.ContextAction
import dev.insight.context.ContextKeys
import dev.insight.context.ContextRequest
import dev.insight.context.location
import dev.insight.diff.createDiffRequest
import dev.insight.diff.showDiffLines

/** The context's file in the editor, with its lines selected. */
internal class OpenInEditorAction : ContextAction {
    override val id = "open.editor"
    override val title get() = InsightBundle.message("context.action.open.editor")
    override val group = "Open"
    override val order = 10

    override fun isAvailable(request: ContextRequest) = canOpenInEditor(request.facts())

    override fun perform(request: ContextRequest) {
        val file = request.sourceFile() ?: return report(request.project, InsightBundle.message("context.action.missing.file", request.context.location()))
        val lines = request.context.lines
        val descriptor = OpenFileDescriptor(request.project, file, ((lines?.first ?: 1) - 1).coerceAtLeast(0), 0)
        val editor = FileEditorManager.getInstance(request.project).openTextEditor(descriptor, true)
        if (editor != null && lines != null) selectLines(editor, lines)
    }
}

/** The captured diff in the IDE's diff viewer, scrolled to and highlighting the context's lines. */
internal class OpenInIdeDiffAction : ContextAction {
    override val id = "open.diff"
    override val title get() = InsightBundle.message("context.action.open.diff")
    override val group = "Open"
    override val order = 20

    override fun isAvailable(request: ContextRequest) = canOpenInIdeDiff(request.facts())

    override fun perform(request: ContextRequest) {
        val file = request.data[ContextKeys.REVIEW_FILE] ?: return
        // Without both sides the diff shows the patch itself, whose lines are not the file's.
        val trace = diffLineTrace(file.path, request.context).takeIf { file.before != null && file.after != null }
        if (trace == null) DiffManager.getInstance().showDiff(request.project, createDiffRequest(request.project, file))
        else showDiffLines(request.project, file, trace)
    }
}
