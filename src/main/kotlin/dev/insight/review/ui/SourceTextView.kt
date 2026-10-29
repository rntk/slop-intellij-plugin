package dev.insight.review.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.Project
import dev.insight.review.ReviewCodeLocation
import dev.insight.review.ReviewCodeNavigation
import dev.insight.review.ReviewSource
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.math.roundToInt

/**
 * Complete, read-only snapshot text with line numbers. Only visible lines are painted, even for a very large file. A block of a
 * file numbers its lines from [firstLine]; [digits] keeps the number gutter as wide as the whole file's.
 */
internal class SourceTextView(
    private val text: String?,
    viewWidth: Int,
    zoom: Double,
    private val firstLine: Int = 1,
    digits: Int = 0,
    private val hoverLine: ((Int?) -> Unit)? = null,
) : JComponent() {
    private val scheme = EditorColorsManager.getInstance().globalScheme
    private val lineFont = scheme.getFont(EditorFontType.PLAIN).deriveFont((scheme.editorFontSize2D * zoom).toFloat())
    private val metrics = getFontMetrics(lineFont)
    private val lineHeight = metrics.height
    private val pad = (JBUI.scale(4) * zoom).roundToInt()
    private val lineStarts: IntArray
    private val numberWidth: Int
    val lineCount: Int get() = lineStarts.size
    var highlightedLines: Set<Int> = emptySet()
        set(value) { if (field != value) { field = value; repaint() } }

    init {
        val source = text.orEmpty()
        val count = if (source.isEmpty()) 0 else source.count { it == '\n' } + if (source.endsWith('\n')) 0 else 1
        lineStarts = IntArray(count)
        var line = 1
        for (index in source.indices) if (source[index] == '\n' && line < count) lineStarts[line++] = index + 1
        numberWidth = metrics.stringWidth("0".repeat(maxOf(3, digits, (firstLine + count - 1).toString().length)))
        font = lineFont
        val contentWidth = (0 until count).maxOfOrNull { metrics.stringWidth(line(it).replace("\t", "    ")) } ?: 0
        preferredSize = Dimension(maxOf(viewWidth, contentWidth + numberWidth + 4 * pad), maxOf(count, 1) * lineHeight + 2 * pad)
        if (hoverLine != null) {
            val listener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = update(e)
                override fun mouseMoved(e: MouseEvent) = update(e)
                override fun mouseExited(e: MouseEvent) { hoverLine.invoke(null) }
                private fun update(e: MouseEvent) { hoverLine.invoke(lineAt(e.y)) }
            }
            addMouseListener(listener)
            addMouseMotionListener(listener)
        }
    }

    /** Snapshot line at this pixel, excluding the padding and empty rows. */
    internal fun lineAt(y: Int): Int? =
        if (y < pad || y >= pad + lineCount * lineHeight) null else firstLine + (y - pad) / lineHeight

    fun enableNavigation(project: Project?, source: ReviewSource) {
        ReviewCodeNavigation.install(this, project) { point ->
            val row = lineAt(point.y)
            val column = row?.let {
                ReviewCodeNavigation.columnAt(line(it - firstLine), point.x - numberWidth - 3 * pad, metrics::stringWidth)
            }
            if (row == null || column == null || source.text == null) null
            else ReviewCodeLocation(source.path, source.text!!, row, column, source.absolutePath)
        }
    }

    /** The snapshot line without its final separator; leading characters, including '+' and '-', stay intact. */
    internal fun line(index: Int): String {
        val source = text.orEmpty()
        val end = if (index + 1 < lineCount) lineStarts[index + 1] - 1 else source.length - if (source.endsWith('\n')) 1 else 0
        return source.substring(lineStarts[index], end)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = lineFont
            g2.color = scheme.defaultBackground
            g2.fillRect(0, 0, width, height)
            val clip = g2.clipBounds ?: java.awt.Rectangle(0, 0, width, height)
            val from = ((clip.y - pad) / lineHeight).coerceAtLeast(0)
            val to = ((clip.y + clip.height - pad) / lineHeight + 1).coerceAtMost(lineCount)
            if (lineCount == 0) {
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString(if (text == null) "Source text unavailable" else "Empty file", pad, pad + metrics.ascent)
            }
            for (index in from until to) {
                if (firstLine + index in highlightedLines) {
                    g2.color = UIUtil.getListSelectionBackground(false)
                    g2.fillRect(0, pad + index * lineHeight, width, lineHeight)
                }
                val baseline = pad + index * lineHeight + metrics.ascent
                val number = (firstLine + index).toString()
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString(number, pad + numberWidth - metrics.stringWidth(number), baseline)
                g2.color = scheme.defaultForeground
                g2.drawString(line(index).replace("\t", "    "), numberWidth + 3 * pad, baseline)
            }
        } finally { g2.dispose() }
    }
}
