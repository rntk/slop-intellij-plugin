package dev.insight.review.ui

import com.intellij.xml.util.XmlStringUtil
import dev.insight.review.PatchSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.SummaryNode
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

/**
 * TF-IDF within a node's direct child summaries, or from a leaf's own source when it has no children, independently of
 * folding, filtering and other branches.
 */
internal class SummaryWordWeights(private val summaries: ReviewSummaries) {
    private val weights = mutableMapOf<Int, Map<String, Double>>()

    operator fun get(id: Int): Map<String, Double> = weights.getOrPut(id) {
        val node = summaries.tree[id]
        val documents = if (node.children.isNotEmpty()) {
            node.children.mapNotNull(summaries::text).map(::words)
        } else {
            val source = leafSource(node)
            if (source.isBlank()) emptyList() else listOf(words(source))
        }
        val counts = documents.flatten().groupingBy { it }.eachCount()
        val documentCounts = documents.flatMap { it.toSet() }.groupingBy { it }.eachCount()
        // Smoothing retains emphasis for words shared by all children, including when there is only one child.
        counts.mapValues { (word, count) ->
            ln(1.0 + count) * (1.0 + ln((documents.size + 1.0) / (documentCounts.getValue(word) + 1.0)))
        }
    }

    private fun words(text: String): List<String> =
        SummaryWord.split(text).filter { it.meaningful }.map { it.text.lowercase() }

    private fun leafSource(node: SummaryNode): String {
        val file = node.file ?: return ""
        file.text?.let { text ->
            val range = node.lines ?: return text
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').removeSuffix("\n").split('\n')
            return range.filter { it - 1 in lines.indices }.joinToString("\n") { lines[it - 1] }
        }
        val diff = file as? PatchSource ?: return ""
        return patchLines(diff.patch).joinToString("\n") { it.text }
    }
}

/** How much larger than its base size the heaviest word is drawn, as a fraction of the base size. */
private const val CLOUD_EMPHASIS = 0.8f

/**
 * [text] as escaped HTML for a Swing label, with each weighted word sized as in [WrappedSummaryText]; null when no word
 * carries a weight, so the caller can keep its plain markup. Sizes are points derived from [baseSize].
 */
internal fun cloudHtml(text: String, weights: Map<String, Double>, baseSize: Float): String? {
    val maximum = weights.values.maxOrNull() ?: return null
    val sized = SummaryWord.split(text).mapNotNull { word -> weights[word.text.lowercase()]?.let { word to it } }
    if (sized.isEmpty() || maximum <= 0.0) return null
    val html = StringBuilder()
    var offset = 0
    for ((word, weight) in sized) {
        html.append(XmlStringUtil.escapeString(text.substring(offset, word.start)))
        val size = (baseSize * (1f + CLOUD_EMPHASIS * (weight / maximum).toFloat())).roundToInt().coerceAtLeast(1)
        html.append("<span style='font-size:").append(size).append("pt'>")
            .append(XmlStringUtil.escapeString(text.substring(word.start, word.end))).append("</span>")
        offset = word.end
    }
    return html.append(XmlStringUtil.escapeString(text.substring(offset))).toString()
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
            val scale = 1f + CLOUD_EMPHASIS * (weight / maximum).toFloat()
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
