package dev.insight.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * A native read-only viewer of one [source]: the plain text with the file's highlighting and its real line numbers,
 * links underlined, and native selection and copying. [owner] is the card the source belongs to, or null for the root
 * file. The canvas owns vertical scrolling; a line wider than [maxWidth] scrolls sideways within the pane.
 */
internal class CanvasSourcePane(
    project: Project,
    val owner: String?,
    val source: CanvasSource,
    private val listener: Listener,
) : JPanel(BorderLayout()), Disposable {
    interface Listener {
        fun wheel(event: MouseWheelEvent)
        /** A plain left click on [link], without a selection. */
        fun linkClicked(pane: CanvasSourcePane, link: CanvasLink, event: MouseEvent)
        fun selectionChanged(pane: CanvasSourcePane)
        /** Right-click: the 0-based document line under the pointer. */
        fun contextMenu(pane: CanvasSourcePane, event: MouseEvent, line: Int)
    }

    val editor = EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument(source.text), project) as EditorEx
    private val baseFontSize = editor.colorsScheme.editorFontSize2D
    private val anchorHighlighters = mutableListOf<RangeHighlighter>()
    private var hovered: CanvasLink? = null
    /** The widest the pane grows before its lines scroll sideways, in pixels. */
    var maxWidth = Int.MAX_VALUE

    init {
        editor.highlighter = source.file?.let { EditorHighlighterFactory.getInstance().createEditorHighlighter(project, it) }
            ?: EditorHighlighterFactory.getInstance().createEditorHighlighter(project,
                FileTypeManager.getInstance().getFileTypeByFileName(source.path.substringAfterLast('/')))
        editor.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            isAutoCodeFoldingEnabled = false
            isLineMarkerAreaShown = false
            isIndentGuidesShown = false
            isRightMarginShown = false
            isCaretRowShown = false
            isUseSoftWraps = false
            isWheelFontChangeEnabled = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isAdditionalPageAtBottom = false
        }
        editor.gutter.setLineNumberConverter(object : LineNumberConverter {
            override fun convert(editor: Editor, lineNumber: Int) = source.firstLine + lineNumber
            override fun getMaxLineNumber(editor: Editor) = source.firstLine + editor.document.lineCount
        })
        editor.setVerticalScrollbarVisible(false)
        editor.scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        editor.setBorder(null)
        editor.scrollPane.isWheelScrollingEnabled = false
        val forwardWheel = MouseWheelListener { event ->
            listener.wheel(event)
            event.consume()
        }
        listOf(editor.contentComponent, editor.gutterComponentEx, editor.scrollPane).forEach { it.addMouseWheelListener(forwardWheel) }
        editor.contentComponent.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
        editor.contentComponent.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())

        val linkAttributes = TextAttributes().apply {
            backgroundColor = LINK_BACKGROUND
            effectType = EffectType.LINE_UNDERSCORE
            effectColor = LINK_UNDERLINE
        }
        for (link in source.links) {
            editor.markupModel.addRangeHighlighter(link.range.first, link.range.last + 1, HighlighterLayer.ADDITIONAL_SYNTAX,
                linkAttributes, HighlighterTargetArea.EXACT_RANGE)
        }

        editor.installPopupHandler { event ->
            listener.contextMenu(this, event.mouseEvent, source.firstLine + event.logicalPosition.line)
            true
        }
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val mouse = event.mouseEvent
                if (!SwingUtilities.isLeftMouseButton(mouse) || mouse.clickCount != 1 || editor.selectionModel.hasSelection()) return
                if (!event.isOverText) return
                linkAt(source.links, event.offset)?.let { listener.linkClicked(this@CanvasSourcePane, it, mouse) }
            }
        }, this)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(event: EditorMouseEvent) {
                val link = if (event.isOverText) linkAt(source.links, event.offset) else null
                if (link == hovered) return
                hovered = link
                editor.setCustomCursor(this@CanvasSourcePane, link?.let { Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) })
                editor.contentComponent.toolTipText = null
            }
        }, this)
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = listener.selectionChanged(this@CanvasSourcePane)
        }, this)
        add(editor.component, BorderLayout.CENTER)
    }

    fun applyZoom(zoom: Double) {
        editor.setFontSize((baseFontSize * zoom).toFloat())
        revalidate()
    }

    /** Tints [anchors]: where the cards opened from this source are anchored. */
    fun highlightAnchors(anchors: List<CardAnchor>) {
        anchorHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        anchorHighlighters.clear()
        val length = editor.document.textLength
        for (anchor in anchors) {
            val start = anchor.startOffset.coerceIn(0, length)
            val end = anchor.endOffset.coerceIn(start, length)
            if (end <= start) continue
            anchorHighlighters += editor.markupModel.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1,
                TextAttributes().apply { backgroundColor = ANCHOR_BACKGROUND }, HighlighterTargetArea.EXACT_RANGE)
        }
    }

    /** The selected range (end exclusive) when it holds more than whitespace. */
    fun selection(): IntRange? {
        val model = editor.selectionModel
        if (!model.hasSelection()) return null
        val text = model.selectedText ?: return null
        return if (text.isBlank()) null else model.selectionStart until model.selectionEnd
    }

    fun clearSelection() = editor.selectionModel.removeSelection()

    /** The middle of the line holding [offset], in this pane's y. */
    fun anchorY(offset: Int): Int {
        val clamped = offset.coerceIn(0, editor.document.textLength)
        return textOrigin().y + editor.offsetToXY(clamped).y + editor.lineHeight / 2
    }

    /** The bottom-right of the selection's last character, in this pane's coordinates. */
    fun selectionEnd(): Point? {
        val range = selection() ?: return null
        val end = editor.offsetToXY(range.last + 1)
        val origin = textOrigin()
        return Point(origin.x + end.x, origin.y + end.y + editor.lineHeight)
    }

    /** The 0-based document line at [offset]. */
    fun line(offset: Int): Int = source.firstLine + editor.document.getLineNumber(offset.coerceIn(0, editor.document.textLength))

    private fun textOrigin(): Point = SwingUtilities.convertPoint(editor.contentComponent, 0, 0, this)

    override fun getPreferredSize(): Dimension {
        val text = editor.contentComponent.preferredSize
        val natural = text.width + editor.gutterComponentEx.preferredSize.width
        val width = minOf(natural, maxWidth)
        // Laid out narrower than its text (a card's width), the pane scrolls sideways and needs room for the bar.
        val shown = if (this.width > 0) minOf(this.width, width) else width
        val bar = if (natural > shown) editor.scrollPane.horizontalScrollBar.preferredSize.height else 0
        return Dimension(width, text.height + bar)
    }

    override fun dispose() {
        if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
    }

    private companion object {
        val LINK_BACKGROUND = JBColor(Color(238, 243, 255), Color(45, 52, 66))
        val LINK_UNDERLINE = JBColor(Color(120, 150, 225), Color(105, 135, 200))
        val ANCHOR_BACKGROUND = JBColor(Color(255, 236, 179), Color(84, 72, 40))
    }
}
