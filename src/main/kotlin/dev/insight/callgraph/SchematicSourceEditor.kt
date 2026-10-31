package dev.insight.callgraph

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.editor.CustomFoldRegion
import com.intellij.openapi.editor.CustomFoldRegionRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/** One native viewer per level. Its private document keeps source and graph coordinates in the same snapshot. */
internal class SchematicSourceEditor(
    project: Project,
    private val schematic: Schematic,
    blocks: List<Pair<SchematicRow, JComponent>>,
    onWheel: (MouseWheelEvent) -> Unit,
    onNavigate: (String) -> Unit,
    /** Right-click on source: the mouse event, the 0-based document line, and the selected text, if any. */
    onContextMenu: (MouseEvent, Int, String?) -> Unit,
    /** A plain left click on source, without a selection: the 0-based document line. */
    onLineClick: (Int) -> Unit = {},
) : JPanel(BorderLayout()), Disposable {
    internal val editor = EditorFactory.getInstance().createViewer(
        EditorFactory.getInstance().createDocument(schematic.lines.joinToString("\n")), project,
    ) as EditorEx
    private val baseFontSize = editor.colorsScheme.editorFontSize2D
    private val folds = linkedMapOf<SchematicRow, CustomFoldRegion>()

    init {
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, schematic.file)
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
            override fun convert(editor: Editor, lineNumber: Int) = schematic.firstLine + lineNumber
            override fun getMaxLineNumber(editor: Editor) = schematic.firstLine + editor.document.lineCount
        })
        editor.setVerticalScrollbarVisible(false)
        editor.setHorizontalScrollbarVisible(false)
        editor.setBorder(null)
        editor.scrollPane.isWheelScrollingEnabled = false
        val forwardWheel = MouseWheelListener { event ->
            onWheel(event)
            event.consume()
        }
        listOf(editor.contentComponent, editor.gutterComponentEx, editor.scrollPane).forEach {
            it.addMouseWheelListener(forwardWheel)
        }
        editor.contentComponent.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
        editor.contentComponent.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())
        for (stroke in listOf("TAB", "shift TAB", "ENTER", "ESCAPE")) {
            object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) = onNavigate(stroke)
            }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(stroke)), editor.contentComponent, this)
        }
        editor.installPopupHandler { event ->
            onContextMenu(event.mouseEvent, schematic.firstLine + event.logicalPosition.line, editor.selectionModel.selectedText)
            true
        }
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val mouse = event.mouseEvent
                if (SwingUtilities.isLeftMouseButton(mouse) && mouse.clickCount == 1 && !editor.selectionModel.hasSelection()) {
                    onLineClick(schematic.firstLine + event.logicalPosition.line)
                }
            }
        }, this)
        add(editor.component, BorderLayout.CENTER)

        editor.foldingModel.runBatchFoldingOperation {
            for ((row, block) in blocks) {
                // A block covers only the line naming it; the rest of a declaration stays native source.
                val (first, last) = if (row is BlockRow) row.headline to row.headline else row.first to row.last
                val fold = editor.foldingModel.addCustomLinesFolding(
                    first - schematic.firstLine, last - schematic.firstLine,
                    object : CustomFoldRegionRenderer {
                        override fun calcWidthInPixels(region: CustomFoldRegion) = block.preferredSize.width.coerceAtLeast(1)
                        override fun calcHeightInPixels(region: CustomFoldRegion) = block.preferredSize.height.coerceAtLeast(1)
                        // The interactive Swing block is laid over this reserved region by LevelView.
                        override fun paint(region: CustomFoldRegion, g: Graphics2D, targetRegion: Rectangle2D, textAttributes: TextAttributes) = Unit
                    },
                )
                checkNotNull(fold) { "Cannot fold schematic block $first..$last" }
                folds[row] = fold
            }
        }
    }

    fun applyZoom(zoom: Double) {
        editor.setFontSize((baseFontSize * zoom).toFloat())
        refreshBlocks()
    }

    /** Readable titles can change a folded row's height independently of the native code font. */
    fun refreshBlocks() {
        editor.foldingModel.runBatchFoldingOperation { folds.values.forEach { it.update() } }
        revalidate()
    }

    fun blockY(row: SchematicRow): Int = folds.getValue(row).location?.y ?: 0

    /** The top of document [line] in the text area, like [blockY]. */
    fun lineY(line: Int): Int =
        editor.visualLineToY(editor.offsetToVisualLine(editor.document.getLineStartOffset(line - schematic.firstLine), false))

    /** The 0-based document line at [point] of the text area. */
    fun lineAt(point: Point): Int = schematic.firstLine + editor.xyToLogicalPosition(point).line

    /** Tints document lines [first]..[last], blocks folded among them included; higher [layer]s paint over lower ones. */
    fun tint(first: Int, last: Int, color: Color, layer: Int = HighlighterLayer.SELECTION - 1) {
        val document = editor.document
        editor.markupModel.addRangeHighlighter(document.getLineStartOffset(first - schematic.firstLine),
            document.getLineEndOffset(last - schematic.firstLine), layer,
            TextAttributes().apply { backgroundColor = color }, HighlighterTargetArea.LINES_IN_RANGE)
    }

    /** Document lines [first]..[last] in this component's coordinates, as wide as their text and folded blocks. */
    fun linesBounds(first: Int, last: Int): Rectangle {
        val document = editor.document
        val top = lineY(first)
        var bottom = top
        var right = 0
        for (line in first - schematic.firstLine..last - schematic.firstLine) {
            val fold = folds.values.firstOrNull { line in document.getLineNumber(it.startOffset)..document.getLineNumber(it.endOffset) }
            if (fold != null) {
                fold.location?.let {
                    bottom = maxOf(bottom, it.y + fold.heightInPixels)
                    right = maxOf(right, it.x + fold.widthInPixels)
                }
                continue
            }
            val end = editor.offsetToXY(document.getLineEndOffset(line))
            bottom = maxOf(bottom, end.y + editor.lineHeight)
            right = maxOf(right, end.x)
        }
        val origin = textOrigin()
        return Rectangle(origin.x, origin.y + top, right, bottom - top)
    }

    /** Where the text of document lines [first]..[last] begins, in this component's x, leaving out folded and blank lines. */
    fun textLeft(first: Int, last: Int): Int? {
        val document = editor.document
        val folded = folds.values.map { document.getLineNumber(it.startOffset)..document.getLineNumber(it.endOffset) }
        return (first - schematic.firstLine..last - schematic.firstLine).filter { line -> folded.none { line in it } }.mapNotNull { line ->
            val start = document.getLineStartOffset(line)
            val indent = document.charsSequence.subSequence(start, document.getLineEndOffset(line)).indexOfFirst { !it.isWhitespace() }
            if (indent < 0) null else editor.offsetToXY(start + indent).x
        }.minOrNull()?.plus(textOrigin().x)
    }

    /** Editor text and gutter origins are measured after layout; the outer canvas owns all scrolling. */
    fun textOrigin(): Point = SwingUtilities.convertPoint(editor.contentComponent, 0, 0, this)

    override fun getPreferredSize(): Dimension {
        val text = editor.contentComponent.preferredSize
        return Dimension(text.width + editor.gutterComponentEx.preferredSize.width, text.height)
    }

    override fun dispose() {
        if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
    }
}
