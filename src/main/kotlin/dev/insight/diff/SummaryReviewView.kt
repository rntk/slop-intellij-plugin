package dev.insight.diff

import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Job
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

internal interface SummaryReviewLauncher {
    fun launch(summaries: ChangeSummaries, reviewed: Set<String>, done: (Result<ReviewDimming>) -> Unit): Job
}

/** One displayed summary version; stale requests cannot change a newer mark or summary. */
internal class SummaryReviewSession(
    private val reviewed: ReviewedFiles,
    private val launcher: SummaryReviewLauncher?,
    private val changed: () -> Unit,
) {
    private var content: ChangeSummaries? = null
    private var revision = -1
    private var generation = 0
    private var job: Job? = null
    private var dimming: ReviewDimming = emptyMap()
    var error: String? = null
        private set
    var running = false
        private set

    fun presentation(summaries: ChangeSummaries, toggle: (SummaryNode, Boolean) -> Unit): SummaryReviewPresentation {
        sync(summaries)
        return SummaryReviewPresentation(summaries, reviewed, { if (content === summaries) dimming else emptyMap() }, toggle)
    }

    /** Follows the current marks for [summaries], relinking when they changed; live presentations read the result. */
    fun sync(summaries: ChangeSummaries) {
        if (content === summaries && revision == reviewed.revision) return
        job?.cancel()
        val requestGeneration = ++generation
        content = summaries
        revision = reviewed.revision
        dimming = emptyMap()
        error = null
        running = launcher != null && reviewed.keys.isNotEmpty()
        val expected = revision
        val keys = reviewed.keys
        if (running) SwingUtilities.invokeLater {
            if (generation != requestGeneration || content !== summaries || revision != expected) return@invokeLater
            job = launcher!!.launch(summaries, keys) { result ->
                if (generation != requestGeneration || content !== summaries || revision != expected || reviewed.revision != expected) return@launch
                running = false
                result.onSuccess { dimming = it }.onFailure { error = it.message ?: "Could not link reviewed summaries" }
                changed()
            }
        }
    }

    fun stop() { generation++; job?.cancel(); content = null; revision = -1; running = false }
}

private val REVIEW_PRESENTATION_UPDATE = Any()

/** Follow new marks and links in a live checkbox, summary or status line, keeping the card's geometry and viewports. */
internal fun updateReviewPresentation(component: JComponent) {
    @Suppress("UNCHECKED_CAST")
    (component.getClientProperty(REVIEW_PRESENTATION_UPDATE) as? (() -> Unit))?.invoke()
}

internal fun installReviewPresentationUpdate(component: JComponent, update: () -> Unit) {
    component.putClientProperty(REVIEW_PRESENTATION_UPDATE, update)
}

/** Applies foreground attributes without changing the prose, word sizes, selection or trace offsets. */
internal class SummaryReviewPresentation(
    private val summaries: ChangeSummaries,
    private val reviewed: ReviewedFiles,
    private val dimming: () -> ReviewDimming,
    private val toggle: (SummaryNode, Boolean) -> Unit,
) {
    constructor(summaries: ChangeSummaries, reviewed: ReviewedFiles, dimming: ReviewDimming, toggle: (SummaryNode, Boolean) -> Unit) :
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
