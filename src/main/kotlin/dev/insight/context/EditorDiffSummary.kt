package dev.insight.context

import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.agent.SourceSide
import dev.insight.callgraph.DiffService
import dev.insight.canvas.relativePath
import dev.insight.diff.ReviewFile
import dev.insight.diff.reviewPatch
import dev.insight.review.reviewFingerprint

internal val EDITOR_DIFF_SUMMARY = ContextKey<EditorDiffSummary>("editorDiffSummary")

/** The text in a standalone IDE comparison when the picker opened. */
internal class EditorDiffSummary(
    private val path: String,
    private val absolutePath: String?,
    private val before: String,
    private val after: String,
    private val status: String,
    private val beforeLabel: String,
    private val afterLabel: String,
) {
    fun reviewFile(): ReviewFile = ReviewFile(path, absolutePath ?: path, null, status,
        reviewPatch(before, after, DiffService.lineChanges(before, after)),
        reviewFingerprint(path, "", status, before, after, ""), before, after, beforeLabel, afterLabel)
}

/**
 * Captures text and coordinates directly from the two contents of an IDE diff request.
 * [editorPick] builds the richer PSI-backed pick, used only when the caret is in the right-side editor of a
 * side-by-side viewer showing a local file; the unified viewer and the left side map lines from the captured text.
 */
internal fun standaloneDiffPick(project: Project, event: AnActionEvent, editor: Editor, request: ContentDiffRequest,
    editorPick: (VirtualFile) -> ContextPick?): ContextPick? {
    val contents = request.contents.takeIf { it.size == 2 } ?: return null
    val (left, right) = contents
    val before = left.capturedText() ?: return null
    val after = right.capturedText() ?: return null
    val file = right.sourceFile() ?: left.sourceFile()
    val name = request.getUserData(DiffUserDataKeysEx.FILE_NAME) ?: request.title?.substringAfterLast('/') ?: "Diff"
    val path = file?.path?.let { relativePath(project, it) } ?: name
    val status = when {
        left is EmptyContent -> "added"
        right is EmptyContent -> "deleted"
        else -> "modified"
    }
    val evidence = EditorDiffSummary(path, file?.path, before, after, status,
        request.contentTitles.getOrNull(0) ?: "Before", request.contentTitles.getOrNull(1) ?: "After")
    val editorLine = editor.caretModel.logicalPosition.line
    val viewer = event.getData(DiffDataKeys.DIFF_VIEWER)
    val (index, line) = if (viewer is UnifiedDiffViewer && viewer.isContentGood) {
        val side = viewer.transferLineFromOneside(editorLine).second
        side.index to viewer.transferLineFromOnesideStrict(side, editorLine)
    } else {
        val side = contents.indexOfFirst { (it as? DocumentContent)?.document === editor.document }
        side to if (side >= 0) editorLine else -1
    }
    if (viewer !is UnifiedDiffViewer && index == 1) {
        val editorFile = FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.isLocalSource() }
        editorFile?.let(editorPick)?.let { existing ->
            existing.data.put(EDITOR_DIFF_SUMMARY, evidence)
            return existing
        }
    }
    val side = if (index == 0 || index < 0 && right is EmptyContent) SourceSide.BEFORE else SourceSide.AFTER
    val text = if (side == SourceSide.BEFORE) before else after
    val data = ContextData().put(EDITOR_DIFF_SUMMARY, evidence)
        .put(ContextKeys.EDITOR, editor).put(ContextKeys.OFFSET, editor.caretModel.offset)
    val pick = capturedSourcePick(project, ContextOrigin.IDE_DIFF, path, file?.path, text,
        line.takeIf { it >= 0 }?.plus(1), data)
    return ContextPick(pick.origin, pick.candidates.map { it.copy(side = side) }, pick.data)
}

private fun DiffContent.capturedText(): String? = when (this) {
    is DocumentContent -> document.text
    is EmptyContent -> ""
    else -> null
}

private fun DiffContent.sourceFile(): VirtualFile? =
    ((this as? FileContent)?.file ?: (this as? DocumentContent)?.let {
        FileDocumentManager.getInstance().getFile(it.document) ?: it.highlightFile
    })?.takeIf { it.isLocalSource() }

private fun VirtualFile.isLocalSource() = isInLocalFileSystem && !isDirectory
