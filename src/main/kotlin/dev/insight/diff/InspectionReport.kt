package dev.insight.diff

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.JBUI
import dev.insight.review.ReviewSource
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent

/** One error or warning, with a 1-based position. */
internal data class InspectionProblem(val path: String, val line: Int, val column: Int, val severity: String, val message: String)

/**
 * Lists problems one per line in the `path:line:column: SEVERITY: message` form compilers use,
 * so a coding agent can act on each line on its own.
 */
internal fun formatInspectionReport(scope: String, root: String?, inspected: Int, skipped: List<String>, problems: List<InspectionProblem>): String = buildString {
    val counts = problems.groupingBy { it.severity }.eachCount().entries.sortedByDescending { it.value }
        .joinToString(", ") { (severity, count) -> plural(count, severity.lowercase()) }
    appendLine("# IntelliJ IDEA inspection results for $scope")
    if (root != null) appendLine("# Paths are relative to $root; positions are 1-based line:column.")
    appendLine("# Inspected ${plural(inspected, "file")}: ${counts.ifEmpty { "no errors or warnings" }}.")
    if (skipped.isNotEmpty()) appendLine("# Not inspected (missing from the working tree): ${skipped.joinToString(", ")}")
    if (problems.isNotEmpty()) appendLine()
    problems.sortedWith(compareBy({ it.path }, { it.line }, { it.column })).forEach {
        appendLine("${it.path}:${it.line}:${it.column}: ${it.severity}: ${it.message.replace(Regex("\\s+"), " ").trim()}")
    }
}

private fun plural(count: Int, word: String) = "$count $word${if (count == 1) "" else "s"}"

/** Runs the IDE's highlighting and local inspections over the working-tree copies of [sources], then lists what they found. */
internal fun inspectReviewFiles(project: Project, scope: String, sources: List<ReviewSource>) {
    if (DumbService.isDumb(project)) {
        Messages.showInfoMessage(project, "Inspections need the project indexes. Try again when indexing finishes.", "Inspect Code")
        return
    }
    val files = LocalFileSystem.getInstance()
    val found = sources.associateWith { files.refreshAndFindFileByPath(it.absolutePath.replace('\\', '/'))?.takeIf { file -> file.isValid && !file.isDirectory } }
    val virtualFiles = found.values.filterNotNull().distinct()
    val skipped = found.filterValues { it == null }.keys.map { it.path }
    if (virtualFiles.isEmpty()) {
        Messages.showInfoMessage(project, "None of these files are in the working tree, so there is nothing to inspect.", "Inspect Code")
        return
    }
    // Shows its own cancellable modal progress, so it runs from the event thread.
    val smells = try { CodeSmellDetector.getInstance(project).findCodeSmells(virtualFiles) }
        catch (_: ProcessCanceledException) { return }
    val root = project.basePath
    val documents = FileDocumentManager.getInstance()
    val problems = smells.map { smell ->
        val path = documents.getFile(smell.document)?.path?.let { relativeTo(root, it) } ?: "?"
        InspectionProblem(path, smell.startLine + 1, smell.startColumn + 1, smell.severity.name, smell.description)
    }
    InspectionReportDialog(project, scope, formatInspectionReport(scope, root, virtualFiles.size, skipped, problems)).show()
}

private fun relativeTo(root: String?, path: String): String {
    if (root == null) return path
    val base = Path.of(root)
    val file = Path.of(path)
    return if (file.startsWith(base)) base.relativize(file).toString().replace('\\', '/') else path
}

/** An editable plain-text report, so a reader can trim it before handing it to an agent. */
private class InspectionReportDialog(project: Project, scope: String, report: String) : DialogWrapper(project, false) {
    private val editor: Editor = EditorFactory.getInstance().let { factory ->
        factory.createEditor(factory.createDocument(report), project, PlainTextFileType.INSTANCE, false)
    }.apply {
        settings.isUseSoftWraps = true
        settings.isLineNumbersShown = false
        settings.isFoldingOutlineShown = false
        component.name = "inspection-report-editor"
    }

    init {
        title = "Inspection Results · $scope"
        setCancelButtonText("Close")
        init()
    }

    override fun createCenterPanel(): JComponent = editor.component.apply {
        preferredSize = Dimension(JBUI.scale(820), JBUI.scale(420))
    }

    override fun createActions(): Array<Action> = arrayOf(object : AbstractAction("Copy") {
        override fun actionPerformed(e: ActionEvent) = CopyPasteManager.getInstance().setContents(StringSelection(editor.document.text))
    }, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = editor.contentComponent

    override fun dispose() {
        if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
        super.dispose()
    }
}
