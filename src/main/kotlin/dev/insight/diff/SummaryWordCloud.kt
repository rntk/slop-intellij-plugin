package dev.insight.diff

import java.awt.Dimension
import java.awt.Font
import javax.swing.JTextPane
import javax.swing.text.AbstractDocument
import javax.swing.text.LabelView
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledEditorKit
import javax.swing.text.ViewFactory
import kotlin.math.ln
import kotlin.math.roundToInt

/** TF-IDF within a node's direct child summaries, independently of folding, filtering and other branches. */
internal class SummaryWordWeights(private val summaries: ChangeSummaries) {
    private val weights = mutableMapOf<Int, Map<String, Double>>()

    operator fun get(id: Int): Map<String, Double> = weights.getOrPut(id) {
        val documents = summaries.tree[id].children
            .mapNotNull(summaries::text)
            .map { text -> SummaryWord.split(text).filter { it.meaningful }.map { it.text.lowercase() } }
        val counts = documents.flatten().groupingBy { it }.eachCount()
        val documentCounts = documents.flatMap { it.toSet() }.groupingBy { it }.eachCount()
        // Smoothing retains emphasis for words shared by all children, including when there is only one child.
        counts.mapValues { (word, count) ->
            ln(1.0 + count) * (1.0 + ln((documents.size + 1.0) / (documentCounts.getValue(word) + 1.0)))
        }
    }
}

/**
 * Original prose and character offsets, with word sizes grounded in the direct children's word counts. A null [wrapWidth]
 * keeps the summary on one line, as wide as it needs.
 */
internal class WrappedSummaryText(
    value: String,
    private var wrapWidth: Int?,
    baseFont: Font,
    weights: Map<String, Double>,
    breakLongWords: Boolean = false,
) : JTextPane() {
    private var measurement: WrappedTextMeasurement? = null

    init {
        isEditable = false
        isOpaque = false
        border = null
        putClientProperty("caretWidth", 0)
        if (breakLongWords) editorKit = object : StyledEditorKit() {
            override fun getViewFactory(): ViewFactory {
                val standard = super.getViewFactory()
                return ViewFactory { element ->
                    if (element.name == AbstractDocument.ContentElementName) object : LabelView(element) {
                        override fun getMinimumSpan(axis: Int): Float = if (axis == X_AXIS) 0f else super.getMinimumSpan(axis)
                    } else standard.create(element)
                }
            }
        }
        font = baseFont
        text = value
        val maximum = weights.values.maxOrNull() ?: 0.0
        for (word in SummaryWord.split(value)) {
            val weight = weights[word.text.lowercase()] ?: continue
            // Bound the emphasis so the summary remains readable as a sentence.
            val scale = 1f + 0.8f * (weight / maximum).toFloat()
            val attributes = SimpleAttributeSet()
            StyleConstants.setFontSize(attributes, (baseFont.size2D * scale).roundToInt().coerceAtLeast(1))
            styledDocument.setCharacterAttributes(word.start, word.end - word.start, attributes, false)
        }
        caretPosition = 0
    }

    override fun getPreferredSize(): Dimension {
        val measure = measurement ?: WrappedTextMeasurement(this, wrapWidth).also { measurement = it }
        return measure.preferredSize() ?: super.getPreferredSize()
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    /** Remeasure only when the viewport width changes; keep the document and its character offsets intact. */
    fun wrapAt(width: Int) {
        val next = width.coerceAtLeast(1)
        if (wrapWidth == next) return
        wrapWidth = next
        measurement?.wrapAt(next)
        revalidate()
    }

    /** Include enlarged words when the document reserves enough width to keep identifiers on one line. */
    fun longestWordWidth(): Int = Regex("\\S+").findAll(text).maxOfOrNull { match ->
        var width = 0
        var offset = match.range.first
        while (offset <= match.range.last) {
            val element = styledDocument.getCharacterElement(offset)
            val end = minOf(element.endOffset, match.range.last + 1)
            val runFont = styledDocument.getFont(element.attributes)
            width += getFontMetrics(runFont).stringWidth(text.substring(offset, end))
            offset = end
        }
        width
    } ?: 0
}
