package dev.insight.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorFontType
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
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/**
 * A native read-only viewer of one [source]: the plain text with the file's highlighting and its real line numbers,
 * links underlined, and native selection and copying. [owner] is the card the source belongs to, or null for the root
 * file. The canvas owns vertical scrolling unless the source is taller than [maxHeight]; a line wider than [maxWidth] scrolls sideways within the pane.
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
        /** Right-click: the 0-based document line and link (if any) under the pointer. */
        fun contextMenu(pane: CanvasSourcePane, event: MouseEvent, line: Int, link: CanvasLink? = null)
    }

    val editor = EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument(source.text), project) as EditorEx
    private val baseFontSize = editor.colorsScheme.editorFontSize2D
    private val anchorHighlighters = mutableListOf<RangeHighlighter>()
    private val gapHighlighters = mutableListOf<RangeHighlighter>()
    private val gapInlays = mutableListOf<Inlay<*>>()
    private var hovered: CanvasLink? = null
    /** The widest the pane grows before its lines scroll sideways, in pixels. */
    var maxWidth = Int.MAX_VALUE
    /** The tallest the pane grows, in pixels; longer sources scroll vertically inside the pane. */
    var maxHeight = Int.MAX_VALUE
        set(value) {
            field = value
            capCanvasViewerHeight(editor, value)
        }

    init {
        editor.highlighter = source.file?.let { EditorHighlighterFactory.getInstance().createEditorHighlighter(project, it) }
            ?: EditorHighlighterFactory.getInstance().createEditorHighlighter(project,
                FileTypeManager.getInstance().getFileTypeByFileName(source.path.substringAfterLast('/')))
        configureCanvasViewer(editor, listener::wheel)
        editor.gutter.setLineNumberConverter(object : LineNumberConverter {
            override fun convert(editor: Editor, lineNumber: Int) = source.firstLine + lineNumber
            override fun getMaxLineNumber(editor: Editor) = source.firstLine + editor.document.lineCount
        })

        val linkAttributes = TextAttributes().apply {
            effectType = EffectType.LINE_UNDERSCORE
            effectColor = LINK_UNDERLINE
        }
        for (link in source.links) {
            editor.markupModel.addRangeHighlighter(link.range.first, link.range.last + 1, HighlighterLayer.ADDITIONAL_SYNTAX,
                linkAttributes, HighlighterTargetArea.EXACT_RANGE)
        }

        editor.installPopupHandler { event ->
            val link = if (event.isOverText) linkAt(source.links, event.offset) else null
            listener.contextMenu(this, event.mouseEvent, source.firstLine + event.logicalPosition.line, link)
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
                editor.contentComponent.toolTipText = if (link?.definition == true) "Show callers and usages" else null
            }
        }, this)
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = listener.selectionChanged(this@CanvasSourcePane)
        }, this)
        add(editor.component, BorderLayout.CENTER)
    }

    fun applyZoom(zoom: Double) {
        editor.setFontSize((baseFontSize * zoom).toFloat())
        // Gap summaries wrap and measure in the editor's font, so they resize with it.
        gapInlays.forEach { it.update() }
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

    private val siteHighlighters = mutableListOf<RangeHighlighter>()
    private var pickedHighlighter: RangeHighlighter? = null
    /** The 0-based document lines tinted as usage sites, as last passed to [showUsageSites]. */
    var usageSites: List<Int> = emptyList()
        private set

    /** Tints the 0-based document [lines] of usage sites; replaces what was shown before. */
    fun showUsageSites(lines: List<Int>) {
        if (lines == usageSites) return
        usageSites = lines
        siteHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        siteHighlighters.clear()
        for (line in lines.distinct()) {
            val range = lineRange(line) ?: continue
            siteHighlighters += editor.markupModel.addRangeHighlighter(range.first, range.last, HighlighterLayer.SELECTION - 3,
                TextAttributes().apply { backgroundColor = SITE_BACKGROUND }, HighlighterTargetArea.LINES_IN_RANGE)
        }
    }

    /**
     * Scrolls the pane to 0-based document [line] and marks it as the picked usage site; returns the middle of the line
     * in this pane's y, or null when the line is not in the shown text.
     */
    fun scrollToLine(line: Int): Int? {
        val range = lineRange(line) ?: return null
        pickedHighlighter?.let { editor.markupModel.removeHighlighter(it) }
        pickedHighlighter = editor.markupModel.addRangeHighlighter(range.first, range.last, HighlighterLayer.SELECTION - 1,
            TextAttributes().apply { backgroundColor = PICKED_BACKGROUND }, HighlighterTargetArea.LINES_IN_RANGE)
        centerOn(range.first)
        return anchorY(range.first)
    }

    /**
     * Scrolls the pane at once so the line holding [offset] is in the middle, waiting until the pane has a size if it
     * has none yet; then calls [scrolled].
     */
    fun centerOnceSized(offset: Int, scrolled: () -> Unit) {
        val viewport = editor.scrollPane.viewport
        if (viewport.height > 0) {
            centerOn(offset)
            return scrolled()
        }
        viewport.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                if (viewport.height <= 0 || editor.isDisposed) return
                viewport.removeComponentListener(this)
                centerOn(offset)
                scrolled()
            }
        })
    }

    /** The start offset of 0-based document [line] in the shown text, or null when it is not shown. */
    fun lineStart(line: Int): Int? = lineRange(line)?.first

    /** Scrolls the pane at once so the line holding [offset] is in the middle. */
    fun centerOn(offset: Int) {
        val scrolling = editor.scrollingModel
        scrolling.disableAnimation()
        try {
            scrolling.scrollTo(editor.offsetToLogicalPosition(offset.coerceIn(0, editor.document.textLength)), ScrollType.CENTER)
        } finally {
            scrolling.enableAnimation()
        }
    }

    /** The start and end offsets of 0-based document [line] in the shown text, or null when it is not shown. */
    private fun lineRange(line: Int): IntRange? {
        val index = line - source.firstLine
        if (index < 0 || index >= editor.document.lineCount) return null
        return editor.document.getLineStartOffset(index)..editor.document.getLineEndOffset(index)
    }

    /** The gap summaries shown, as last passed to [showGapSummaries]. */
    var gapSummaries: List<GapSummary> = emptyList()
        private set

    /** Tints the lines of each of [summaries] and shows its text above them; replaces what was shown before. */
    fun showGapSummaries(summaries: List<GapSummary>) {
        if (summaries == gapSummaries) return
        gapSummaries = summaries
        gapHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        gapHighlighters.clear()
        gapInlays.forEach(Disposer::dispose)
        gapInlays.clear()
        val document = editor.document
        for (summary in summaries) {
            val first = summary.first - source.firstLine
            val last = summary.last - source.firstLine
            if (first < 0 || last < first || last >= document.lineCount) continue
            val start = document.getLineStartOffset(first)
            gapHighlighters += editor.markupModel.addRangeHighlighter(start, document.getLineEndOffset(last), HighlighterLayer.SELECTION - 2,
                TextAttributes().apply { backgroundColor = GAP_BACKGROUND }, HighlighterTargetArea.LINES_IN_RANGE)
            editor.inlayModel.addBlockElement(start, false, true, 0, GapSummaryRenderer(summary.text))?.let { gapInlays += it }
        }
        revalidate()
        repaint()
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

    /**
     * Like [anchorY], but kept within the part of the text the pane shows, so a card opened from a line scrolled out
     * of view hangs at the pane's edge rather than far beyond its owner.
     */
    fun shownAnchorY(offset: Int): Int {
        val y = anchorY(offset)
        val viewport = editor.scrollPane.viewport
        if (viewport.height <= 0) return y
        val top = SwingUtilities.convertPoint(viewport, 0, 0, this).y
        val half = editor.lineHeight / 2
        return y.coerceIn(top + half, maxOf(top + half, top + viewport.height - half))
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

    override fun getPreferredSize(): Dimension = canvasViewerSize(editor, width, maxWidth, maxHeight)

    override fun dispose() {
        if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
    }

    private companion object {
        val LINK_UNDERLINE = JBColor(Color(120, 150, 225), Color(105, 135, 200))
        val ANCHOR_BACKGROUND = JBColor(Color(255, 236, 179), Color(84, 72, 40))
        val SITE_BACKGROUND = JBColor(Color(232, 240, 255), Color(44, 52, 70))
        val PICKED_BACKGROUND = JBColor(Color(204, 222, 255), Color(56, 72, 108))
    }
}

internal val GAP_BACKGROUND = JBColor(Color(255, 248, 225), Color(58, 52, 38))
private val GAP_ACCENT = JBColor(Color(214, 160, 40), Color(190, 145, 60))

/**
 * A gap summary above its lines: larger than the editor's font, as wide as the source's longest line within limits,
 * with an amber rule on the left like the tint of the lines it describes.
 */
internal class GapSummaryRenderer(val text: String) : EditorCustomElementRenderer {
    private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC).let {
        it.deriveFont(it.size2D * SUMMARY_FONT_SCALE)
    }
    private fun textWidth(editor: Editor): Int = columns(editor) *
        editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.ITALIC)).charWidth(' ')
    private fun lineHeight(editor: Editor) = editor.contentComponent.getFontMetrics(font(editor)).height
    private fun columns(editor: Editor): Int {
        val document = editor.document
        val longest = (0 until document.lineCount).maxOfOrNull { document.getLineEndOffset(it) - document.getLineStartOffset(it) } ?: 0
        return longest.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }
    private fun padding(editor: Editor) = maxOf(2, editor.lineHeight / 4)

    /** [text] wrapped at word boundaries to the renderer's width in the editor's italic font. */
    private fun lines(editor: Editor): List<String> {
        val metrics = editor.contentComponent.getFontMetrics(font(editor))
        val width = textWidth(editor)
        return text.lines().flatMap { paragraph ->
            val wrapped = mutableListOf<String>()
            var line = StringBuilder()
            for (word in paragraph.split(' ').filter { it.isNotEmpty() }) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && metrics.stringWidth(next) > width) {
                    wrapped += line.toString()
                    line = StringBuilder(word)
                } else line = StringBuilder(next)
            }
            if (line.isNotEmpty() || wrapped.isEmpty()) wrapped += line.toString()
            wrapped
        }
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        return textWidth(editor) + 4 * padding(editor)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = lines(inlay.editor).size * lineHeight(inlay.editor) + 2 * padding(inlay.editor)

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val pad = padding(editor)
        g.color = GAP_BACKGROUND
        g.fillRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
        g.color = GAP_ACCENT
        g.fillRect(targetRegion.x, targetRegion.y, maxOf(2, pad), targetRegion.height)
        val font = font(editor)
        g.font = font
        g.color = UIUtil.getContextHelpForeground()
        val ascent = editor.contentComponent.getFontMetrics(font).ascent
        val left = targetRegion.x + 3 * pad
        (g as? Graphics2D)?.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        lines(editor).forEachIndexed { index, line ->
            g.drawString(line, left, targetRegion.y + pad + index * lineHeight(editor) + ascent)
        }
    }

    private companion object {
        const val MIN_COLUMNS = 48
        const val MAX_COLUMNS = 100
    }
}

/** Shows [editor] without editor chrome; the wheel scrolls its code, then the canvas at the edge. */
internal fun configureCanvasViewer(editor: EditorEx, wheel: (MouseWheelEvent) -> Unit) {
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
    editor.setVerticalScrollbarVisible(false)
    editor.scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
    editor.setBorder(null)
    editor.scrollPane.isWheelScrollingEnabled = false
    val forwardWheel = MouseWheelListener { event ->
        if (!scrollInside(editor, event)) wheel(event)
        event.consume()
    }
    listOf(editor.contentComponent, editor.gutterComponentEx, editor.scrollPane).forEach { it.addMouseWheelListener(forwardWheel) }
    editor.contentComponent.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, emptySet())
    editor.contentComponent.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, emptySet())
}

/** Lets [editor] scroll vertically within a height cap, or not at all when [maxHeight] is unbounded. */
internal fun capCanvasViewerHeight(editor: EditorEx, maxHeight: Int) {
    editor.scrollPane.verticalScrollBarPolicy =
        if (maxHeight == Int.MAX_VALUE) ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER else ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
}

/** Scrolls inside the viewer, returning axes the canvas should handle. Keys retain ownership at an edge;
 * wheel events spill back to the canvas when the viewer cannot move further.
 */
internal fun scrollCanvasViewer(editor: EditorEx, dx: Int, dy: Int, page: Boolean = false, edge: Boolean = false,
                                wheel: Boolean = false): Point {
    val model = editor.scrollingModel
    val visible = model.visibleArea
    val text = editor.contentComponent.preferredSize
    fun target(at: Int, delta: Int, size: Int, maximum: Int): Int {
        val amount = when {
            delta == 0 -> 0
            edge -> if (delta < 0) -maximum else maximum
            page -> (size - editor.lineHeight).coerceAtLeast(editor.lineHeight) * if (delta < 0) -1 else 1
            else -> delta
        }
        return (at.toLong() + amount).coerceIn(0, maximum.toLong()).toInt()
    }
    val maxX = (text.width - visible.width).coerceAtLeast(0)
    val maxY = if (editor.scrollPane.verticalScrollBarPolicy == ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER) 0
        else (text.height - visible.height).coerceAtLeast(0)
    val x = target(visible.x, dx, visible.width, maxX)
    val y = target(visible.y, dy, visible.height, maxY)
    model.disableAnimation()
    try {
        if (dx != 0 && maxX > 0) model.scrollHorizontally(x)
        if (dy != 0 && maxY > 0) model.scrollVertically(y)
    } finally {
        model.enableAnimation()
    }
    return Point(if (maxX > 0 && (!wheel || x != visible.x)) 0 else dx,
        if (maxY > 0 && (!wheel || y != visible.y)) 0 else dy)
}

/** Shift+wheel scrolls horizontally; Ctrl/Cmd+wheel stays with canvas zoom. */
private fun scrollInside(editor: EditorEx, event: MouseWheelEvent): Boolean {
    if (event.isControlDown || event.isMetaDown) return false
    val delta = (event.preciseWheelRotation * editor.lineHeight * 3).toInt()
    val remaining = scrollCanvasViewer(editor, if (event.isShiftDown) delta else 0,
        if (event.isShiftDown) 0 else delta, wheel = true)
    return delta != 0 && remaining.x == 0 && remaining.y == 0
}

/**
 * A canvas viewer's size: its text, at most [maxWidth] wide and [maxHeight] tall. Laid out narrower than its text
 * ([width], a card's), the viewer scrolls sideways; taller than [maxHeight], vertically. Each needs room for its bar.
 */
internal fun canvasViewerSize(editor: EditorEx, width: Int, maxWidth: Int, maxHeight: Int = Int.MAX_VALUE): Dimension {
    val text = editor.contentComponent.preferredSize
    val verticalBar = if (text.height > maxHeight) editor.scrollPane.verticalScrollBar.preferredSize.width else 0
    val natural = text.width + editor.gutterComponentEx.preferredSize.width + verticalBar
    val preferred = minOf(natural, maxWidth)
    val shown = if (width > 0) minOf(width, preferred) else preferred
    val bar = if (natural > shown) editor.scrollPane.horizontalScrollBar.preferredSize.height else 0
    return Dimension(preferred, minOf(text.height, maxHeight) + bar)
}
