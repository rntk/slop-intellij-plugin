package dev.insight.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel

/**
 * A native read-only viewer of a file's changed hunks, [rows], highlighted like [path]'s file type. Added and removed lines
 * are tinted as in the IDE's diff; the gutter numbers new-side lines, leaving removed lines unnumbered.
 */
internal class CanvasDiffPane(project: Project, path: String, val rows: List<CanvasDiffRow>, wheel: (MouseWheelEvent) -> Unit) :
    JPanel(BorderLayout()), Disposable {
    val editor = EditorFactory.getInstance().createViewer(
        EditorFactory.getInstance().createDocument(rows.joinToString("\n") { it.text }), project) as EditorEx
    private val baseFontSize = editor.colorsScheme.editorFontSize2D
    /** The widest the pane grows before its lines scroll sideways, in pixels. */
    var maxWidth = Int.MAX_VALUE

    init {
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project,
            FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/')))
        configureCanvasViewer(editor, wheel)
        editor.gutter.setLineNumberConverter(object : LineNumberConverter {
            override fun convert(editor: Editor, lineNumber: Int): Int? = rows.getOrNull(lineNumber - 1)?.new
            override fun getMaxLineNumber(editor: Editor): Int = rows.maxOfOrNull { it.new ?: 0 } ?: 0
        })
        val scheme = editor.colorsScheme
        rows.forEachIndexed { line, row ->
            val background = when (row.kind) {
                CanvasDiffRow.Kind.ADDED -> scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor ?: ADDED
                CanvasDiffRow.Kind.REMOVED -> scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor ?: REMOVED
                CanvasDiffRow.Kind.GAP -> GAP
                CanvasDiffRow.Kind.CONTEXT -> null
            } ?: return@forEachIndexed
            editor.markupModel.addLineHighlighter(line, HighlighterLayer.SELECTION - 2,
                TextAttributes().apply { backgroundColor = background })
        }
        add(editor.component, BorderLayout.CENTER)
    }

    fun applyZoom(zoom: Double) {
        editor.setFontSize((baseFontSize * zoom).toFloat())
        revalidate()
    }

    override fun getPreferredSize(): Dimension = canvasViewerSize(editor, width, maxWidth)

    override fun dispose() {
        if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
    }

    private companion object {
        val ADDED = JBColor(0xDDF5E3, 0x2D4535)
        val REMOVED = JBColor(0xF9DEDE, 0x4D3134)
        val GAP = JBColor(0xEEF2F8, 0x2E3239)
    }
}
