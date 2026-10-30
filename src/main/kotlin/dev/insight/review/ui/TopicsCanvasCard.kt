package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewVocabulary
import dev.insight.review.ReviewedFiles
import dev.insight.review.SummaryNode
import dev.insight.review.TopicOutline
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.event.DocumentEvent

internal data class TopicsCanvasActions(
    val openFile: (String) -> Unit,
    val openNode: (SummaryNode) -> Unit,
    val showNode: (SummaryNode) -> Unit,
    val openLines: (SummaryNode, LineTrace) -> Unit,
    val toggleReviewed: (SummaryNode, Boolean) -> Unit,
    val refresh: () -> Unit,
)

/** Topics presentation and the search, trace and reading state retained across card rebuilds. */
internal class TopicsCanvasCard(
    private val vocabulary: ReviewVocabulary,
    reviewed: ReviewedFiles,
    private val sourceActions: SourceCanvasActions,
    private val summaryActions: SummaryCanvasActions,
    private val topicActions: TopicCanvasActions,
    private val canvasActions: TopicsCanvasActions,
    private val project: Project? = null,
) : Disposable {
    val view = ChangeTopicsViewState(vocabulary.foldLargeTopics)
    var scroll = 0
    private var content: TopicsCard? = null
    private var currentCard: JPanel? = null
    private val review = SummaryReviewSession(reviewed)
    private val trace = SummaryTraceSession(view, { content?.reading }, canvasActions.openLines,
        update = { currentCard?.let { updateCanvasTracePresentation(it, view) } }, refresh = canvasActions.refresh)

    /** Search fields stay alive across rebuilds so typing keeps its focus and caret. */
    private val topicSearchField = searchField("topics-topic-search", "Filter topics",
        "Hide files whose topic does not match the typed words (fuzzy)", { view.topicFilter }) { view.topicFilter = it }
    private val topicSummarySearchField = searchField("topics-summary-search", "Filter summaries",
        "Hide files whose name, path or summary does not match the typed words (fuzzy)", { view.summaryFilter }) { view.summaryFilter = it }

    private fun searchField(name: String, empty: String, tooltip: String, get: () -> String, set: (String) -> Unit) =
        SearchTextField(false).apply {
            this.name = name
            textEditor.emptyText.text = empty
            toolTipText = tooltip
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (get() == text) return
                    set(text)
                    refresh()
                }
            })
        }

    fun update(content: TopicsCard?) {
        this.content = content
        if (content == null) {
            scroll = 0
            currentCard = null
            review.stop()
            trace.stop()
        } else trace.retain(content.reading)
    }

    fun syncReview() { content?.reading?.let(review::sync) }

    /** Rebuilding moves the search fields into the new card; typing goes on where it was. */
    fun refresh() {
        val typing = listOf(topicSearchField, topicSummarySearchField).firstOrNull { it.textEditor.isFocusOwner }
        canvasActions.refresh()
        typing?.textEditor?.requestFocusInWindow()
    }

    /**
     * The change as one document with its topic rail, or as the topic hierarchy with each file's summary; mode changes,
     * folding and summary updates rebuild only this card, in place.
     */
    fun card(
        content: TopicsCard, documentZoom: Double, controls: JComponent,
        generationRunning: Boolean, status: String?, entryMenu: ((String) -> JPopupMenu)?,
        installWheelZoom: (JScrollPane) -> Unit,
    ): JPanel {
        val topics = content.topics
        // The header, levels and width follow every file, so typing in the search fields does not move them.
        val outline = view.fullOutline(content)
        val shown = view.outline(content)
        val hierarchy = view.mode == ChangeTopicsMode.HIERARCHY
        val document = !hierarchy
        val viewZoom = if (document) documentZoom else 1.0
        val width = if (hierarchy) cardWidth(HierarchyColumnsPanel.defaultWidth(outline.depth))
            else ChangeTopicsPanel.defaultWidth(view.railDepth(outline), viewZoom)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            val matching = if (view.filtering) " · ${shown.files.size} shown" else ""
            add(JLabel("${vocabulary.topicsTitle} · ${topics.groups.size} topics · ${topics.labels.size} of ${files(topics.files.size)}$matching").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = vocabulary.topicsTooltip
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                if (document) add(controls)
                if (outline.depth > 0) add(topicLevels(outline))
                add(viewSelector("topics-mode", ChangeTopicsMode.entries, view.mode, scaled(4), scaledFont(JBFont.small()),
                    { if (it == ChangeTopicsMode.DOCUMENT) "Document" else "Hierarchy" },
                    { if (it == ChangeTopicsMode.DOCUMENT) "Every file in topic order, with its summary and the topic rail" else "Topics broad to specific, left to right, with each file's summary" }) {
                    view.mode = it; scroll = 0; canvasActions.refresh()
                })
                add(JButton(when {
                    topics.labels.isEmpty() -> "Classify"
                    topics.complete -> "Regenerate"
                    else -> "Continue"
                }).apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && topics.files.isNotEmpty()
                    toolTipText = if (topics.complete) "Classify every file again (/topics)" else "Give every remaining file a topic (/topics)"
                    addActionListener { topicActions.generateTopics() }
                })
                if (hierarchy) add(JButton("Fill summaries").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && !generationRunning && outline.nodes.isNotEmpty() && !content.topicSummaries.complete
                    toolTipText = "Fill missing summaries for every topic and subtopic, including folded topics"
                    addActionListener { topicActions.fillTopicSummaries() }
                })
                val summaries = content.summaries
                if (summaries.total > 0 && !summaries.complete) add(JButton(if (summaries.count == 0) "Summarize" else "Continue summaries").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun
                    toolTipText = vocabulary.topicsSummarizeTooltip
                    addActionListener { summaryActions.summarize() }
                })
                add(InplaceButton(IconButton("Remove change topics", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { topicActions.removeTopics() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.WEST)
        }
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            val panelActions = ChangeTopicActions(openFile = canvasActions.openFile, showDiffCard = sourceActions.showDiffCard, refresh = { canvasActions.refresh() },
                trace = trace::trace, openNode = canvasActions.openNode, showNode = canvasActions.showNode,
                review = review.presentation(content.reading, canvasActions.toggleReviewed),
                entryMenu = entryMenu)
            val inner = width - scaled(2 * PADDING + 4) - scaled(16)
            add(JPanel(VerticalLayout(scaled(8))).apply {
                isOpaque = false
                canvasTraceStatusLines(view, trace, inner).forEach(::add)
                status?.let { status ->
                    add(JLabel(status).apply { name = "topic-summary-status"; foreground = UIUtil.getContextHelpForeground(); toolTipText = status })
                }
                if (shown.files.isEmpty() && outline.files.isNotEmpty()) add(JLabel("No files match the search.").apply {
                    name = "topics-no-matches"
                    font = scaledFont(JBFont.label()).deriveFont(java.awt.Font.ITALIC); foreground = UIUtil.getContextHelpForeground()
                })
                else add(if (hierarchy) ChangeTopicHierarchyPanel(content, view, inner, viewZoom, panelActions, vocabulary)
                    else ChangeTopicsPanel(content, view, inner, viewZoom, panelActions, vocabulary, project))
            }, BorderLayout.CENTER)
        }
        val search = JPanel(FlowLayout(FlowLayout.RIGHT, scaled(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 12, 4, 8))
            for ((field, text) in listOf(topicSearchField to view.topicFilter, topicSummarySearchField to view.summaryFilter))
                add(field.apply {
                    preferredSize = Dimension(scaled(220), preferredSize.height)
                    if (this.text != text) this.text = text
                })
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder() }
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(search, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        card.add(JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            // A deep hierarchy may scroll sideways; leave room for that scroll bar.
            val sideways = if (body.preferredSize.width > width) horizontalScrollBar.preferredSize.height else 0
            preferredSize = Dimension(width, minOf(body.preferredSize.height + sideways, scaled(MAX_TOPICS_HEIGHT)))
            verticalScrollBar.unitIncrement = scaled(16)
            horizontalScrollBar.unitIncrement = scaled(16)
            if (document) installWheelZoom(this)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = scroll
            verticalScrollBar.addAdjustmentListener { if (currentCard === card) scroll = it.value }
            SwingUtilities.invokeLater {
                if (currentCard !== card) return@invokeLater
                verticalScrollBar.value = restore
                // A traced match scrolls into view once the rebuilt card is laid out; a newer rebuild owns the reveal.
                val target = view.reveal ?: return@invokeLater
                view.reveal = null
                card.validate()
                revealCanvasTrace(body, target)
            }
        }, BorderLayout.CENTER)
        currentCard = card
        return card
    }

    /** Level n shows topic levels 1..n+1 and folds the rest, as in the article hierarchy; the last level shows every file. */
    private fun topicLevels(outline: TopicOutline): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        val selector = JComboBox((0..outline.depth).map { level ->
            if (level == outline.depth) "Files" else "${level + 1}"
        }.toTypedArray()).apply {
            name = "topic-level-selector"
            font = scaledFont(JBFont.small())
            selectedIndex = view.effectiveLevel(outline)
            toolTipText = if (view.mode == ChangeTopicsMode.DOCUMENT)
                "Show topic rail levels through the selected level; Files shows every rail level"
            else "Show topic levels through the selected level; Files shows every topic and file"
            addActionListener { view.selectLevel(selectedIndex, outline); canvasActions.refresh() }
        }
        add(JLabel("Levels").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
            labelFor = selector
        })
        add(selector)
    }

    private fun cardWidth(default: Int) = scaled(default)
    private fun cardBorder() =
        JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))
    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"
    private fun scaled(value: Int) = zoomScaled(value, 1.0)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(1.0)

    override fun dispose() {
        currentCard = null
        trace.stop()
        review.stop()
    }

    private companion object {
        const val PADDING = 12
        const val MAX_TOPICS_HEIGHT = 900
    }
}
