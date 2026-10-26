package dev.insight.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import dev.insight.review.ReviewSourceBlock
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel
import kotlin.math.roundToInt

/** A private snapshot document: native selection, highlighting and scrolling, with no writes to project files. */
internal class DiffSourceEditor(val block: ReviewSourceBlock, project: Project?) : JPanel(BorderLayout()), Disposable {
    internal val editor = EditorFactory.getInstance().createViewer(
        EditorFactory.getInstance().createDocument(block.text), project,
    ) as EditorEx
    private val baseFontSize = editor.colorsScheme.editorFontSize

    init {
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, block.range.path)
        editor.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            isUseSoftWraps = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        editor.gutter.setLineNumberConverter(object : LineNumberConverter {
            override fun convert(editor: Editor, lineNumber: Int): Int = block.range.startLine + lineNumber - 1
            override fun getMaxLineNumber(editor: Editor): Int = block.range.endLine
        })
        add(editor.component, BorderLayout.CENTER)
    }

    fun resizeFor(width: Int, zoom: Double) {
        editor.setFontSize((baseFontSize * zoom).toFloat())
        val lines = block.range.endLine - block.range.startLine + 1
        preferredSize = Dimension(width, minOf(editor.lineHeight * lines + JBUI.scale(24), (JBUI.scale(400) * zoom).roundToInt()))
    }

    override fun dispose() {
        if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
    }
}
