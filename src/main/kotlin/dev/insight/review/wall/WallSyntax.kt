package dev.insight.review.wall

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import java.awt.Font

/**
 * Colours a wall block's rows; the wall asks once per block and colour scheme. [prepare] runs on the EDT and captures what the
 * block needs; the work it returns runs in a background read action and yields each row's spans, or null for a plain block.
 */
internal fun interface WallHighlighter {
    fun prepare(block: WallBlock): (() -> List<List<WallSpan>?>?)?
}

/** The IDE's lexer highlighting of file texts for the wall: keywords, strings, comments and the like, without code analysis. */
internal object WallSyntax {
    /**
     * [text] of a file named like [path], lexed through its line [lastLine] with the IDE's highlighter for its file type and
     * coloured by [scheme]; null when the IDE has no highlighting for the type, or there is no text.
     */
    fun side(project: Project?, path: String, text: String?, lastLine: Int,
             scheme: EditorColorsScheme = EditorColorsManager.getInstance().globalScheme): WallSideHighlight? {
        if (text == null || lastLine <= 0) return null
        val type = FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/'))
        if (type.isBinary || type == PlainTextFileType.INSTANCE || type is UnknownFileType) return null
        val body = wallTextThrough(text, lastLine)
        if (body.isEmpty()) return null
        val highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, type)
        highlighter.setColorScheme(scheme)
        highlighter.setText(body)
        val plain = scheme.defaultForeground
        val tokens = mutableListOf<WallToken>()
        val iterator = highlighter.createIterator(0)
        while (!iterator.atEnd()) {
            val attributes = iterator.textAttributes
            val color = attributes?.foregroundColor?.takeIf { it != plain }
            val style = (attributes?.fontType ?: Font.PLAIN) and (Font.BOLD or Font.ITALIC)
            if (color != null || style != Font.PLAIN) tokens += WallToken(iterator.start, iterator.end, WallStyle(color, style))
            iterator.advance()
        }
        return WallSideHighlight.of(body, tokens)
    }
}
