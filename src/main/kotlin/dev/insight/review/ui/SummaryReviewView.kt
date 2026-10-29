package dev.insight.review.ui

import com.intellij.openapi.util.Key
import com.intellij.ui.ClientProperty
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewedFiles
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.text.JTextComponent
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/** Review dimming of one displayed summary version, read from its citations whenever it or the marks change. */
internal class SummaryReviewSession(private val reviewed: ReviewedFiles) {
    private var content: ReviewSummaries? = null
    private var revision = -1
    private var dimming: ReviewDimming = emptyMap()

    fun presentation(summaries: ReviewSummaries, toggle: (SummaryNode, Boolean) -> Unit): SummaryReviewPresentation {
        sync(summaries)
        return SummaryReviewPresentation(summaries, reviewed, { if (content === summaries) dimming else emptyMap() }, toggle)
    }

    /** Follows the current marks for [summaries]; live presentations read the result. */
    fun sync(summaries: ReviewSummaries) {
        if (content === summaries && revision == reviewed.revision) return
        content = summaries
        revision = reviewed.revision
        dimming = if (reviewed.keys.isEmpty()) emptyMap() else SummaryLinks(summaries).dimming(reviewed.keys)
    }

    fun stop() { content = null; revision = -1; dimming = emptyMap() }
}

private val REVIEW_PRESENTATION_UPDATE = Key.create<() -> Unit>("insight.review.presentationUpdate")

/** Follow new marks and links in a live checkbox, summary or status line, keeping the card's geometry and viewports. */
internal fun updateReviewPresentation(component: JComponent) {
    ClientProperty.get(component, REVIEW_PRESENTATION_UPDATE)?.invoke()
}

internal fun installReviewPresentationUpdate(component: JComponent, update: () -> Unit) {
    ClientProperty.put(component, REVIEW_PRESENTATION_UPDATE, update)
}

/** Applies foreground attributes without changing the prose, word sizes, selection or trace offsets. */
internal class SummaryReviewPresentation(
    summaries: ReviewSummaries,
    private val reviewed: ReviewedFiles,
    private val dimming: () -> ReviewDimming,
    private val toggle: (SummaryNode, Boolean) -> Unit,
) {
    constructor(summaries: ReviewSummaries, reviewed: ReviewedFiles, dimming: ReviewDimming, toggle: (SummaryNode, Boolean) -> Unit) :
        this(summaries, reviewed, { dimming }, toggle)

    private val nodes = summaries.tree.nodes.associateBy { it.identity }

    fun control(node: SummaryNode, font: Font): JComponent? {
        if (node.files.isEmpty() || node.kind == SummaryKind.CHUNK) return null
        return JCheckBox("Reviewed", reviewed.isReviewed(node)).apply {
            name = "reviewed:${node.identity}"
            isOpaque = false
            this.font = font
            toolTipText = "Mark or unmark every file in this entry as reviewed"
            addActionListener { toggle(node, isSelected) }
            installReviewPresentationUpdate(this) { isSelected = reviewed.isReviewed(node) }
        }
    }

    /** The context-menu twin of [control]; [refresh] relabels it for the entry's current state before the menu shows. */
    fun menuItem(node: SummaryNode): JMenuItem? {
        if (node.files.isEmpty() || node.kind == SummaryKind.CHUNK) return null
        return JMenuItem().apply {
            name = "reviewed-menu:${node.identity}"
            refresh(this, node)
            addActionListener { toggle(node, !reviewed.isReviewed(node)) }
        }
    }

    fun refresh(item: JMenuItem, node: SummaryNode) {
        item.text = if (reviewed.isReviewed(node)) "Mark as not reviewed" else "Mark as reviewed"
    }

    fun decorate(area: JTextComponent, node: SummaryNode) {
        val doc = area.document as? StyledDocument ?: return
        val update = {
            clearForeground(doc)
            val attributes = SimpleAttributeSet().apply { StyleConstants.setForeground(this, UIUtil.getContextHelpForeground()) }
            if (reviewed.isReviewed(node)) doc.setCharacterAttributes(0, doc.length, attributes, false)
            else {
                val dimmed = dimming()[node.id].orEmpty()
                for (word in SummaryWord.split(area.text)) if (word.index in dimmed)
                    doc.setCharacterAttributes(word.start, word.end - word.start, attributes, false)
            }
        }
        installReviewPresentationUpdate(area, update)
        update()
    }

    /** Only review marks set foregrounds; word sizes and other run attributes stay. */
    private fun clearForeground(doc: StyledDocument) {
        var offset = 0
        while (offset < doc.length) {
            val element = doc.getCharacterElement(offset)
            val end = minOf(element.endOffset, doc.length)
            if (element.attributes.isDefined(StyleConstants.Foreground)) {
                val attributes = SimpleAttributeSet(element.attributes).apply { removeAttribute(StyleConstants.Foreground) }
                doc.setCharacterAttributes(element.startOffset, end - element.startOffset, attributes, true)
            }
            offset = end
        }
    }

    fun body(identity: String, area: JComponent): JComponent {
        val node = nodes[identity] ?: return area
        (area as? JTextComponent)?.let { decorate(it, node) }
        val control = control(node, area.font) ?: return area
        return JPanel(VerticalLayout(3)).apply { isOpaque = false; add(control); add(area) }
    }
}
