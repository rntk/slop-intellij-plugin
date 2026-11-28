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
import dev.insight.review.topicRadar
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
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

internal data class TopicsCardLabels(
    val title: String,
    val tooltip: String,
    val command: String = "/topics",
    val singular: String = "topic",
    val plural: String = "topics",
    val closeText: String = "Remove change topics",
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
    private val labels: TopicsCardLabels = TopicsCardLabels(vocabulary.topicsTitle, vocabulary.topicsTooltip),
) : Disposable {
    val view = ChangeTopicsViewState(vocabulary.foldLargeTopics, reviewed)
    var scroll = 0
    private var content: TopicsCard? = null
    private var hierarchyPreview: HierarchyPreviewPanel? = null
    private var showHierarchyDiffs = false
    private var hierarchyPreviewPaths = emptyList<String>()
    private var currentCard: JPanel? = null
    private val review = SummaryReviewSession(reviewed)
    private val trace = SummaryTraceSession(view, { content?.reading }, canvasActions.openLines,
        update = { currentCard?.let { updateCanvasTracePresentation(it, view) } }, refresh = canvasActions.refresh)

    /** Search fields stay alive across rebuilds so typing keeps its focus and caret. */
    private val topicSearchField = searchField("topics-topic-search", "Filter ${labels.plural}",
        "Hide files whose ${labels.singular} does not match the typed words (fuzzy)", { view.topicFilter }) { view.topicFilter = it }
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
            hierarchyPreview?.let(com.intellij.openapi.util.Disposer::dispose)
            hierarchyPreview = null
            hierarchyPreviewPaths = emptyList()
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
        hierarchyPreview?.let(com.intellij.openapi.util.Disposer::dispose)
        hierarchyPreview = null
        val topics = content.topics
        // The header, levels and width follow every file, so typing in the search fields does not move them.
        val outline = view.fullOutline(content)
        val shown = view.outline(content)
        val hierarchy = view.mode == ChangeTopicsMode.HIERARCHY
        val radar = view.mode == ChangeTopicsMode.RADAR
        val document = view.mode == ChangeTopicsMode.DOCUMENT
        val viewZoom = if (document) documentZoom else 1.0
        val width = if (hierarchy) cardWidth(HierarchyColumnsPanel.defaultWidth(outline.depth))
            else if (radar) cardWidth(RadarChartPanel.DEFAULT_WIDTH)
            else ChangeTopicsPanel.defaultWidth(view.railDepth(outline), viewZoom)
        val header = JPanel(BorderLayout(scaled(8), 0)).apply {
            name = "topics-controls"
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 12, 6, 8))
            val matching = if (view.filtering) " · ${shown.files.size} shown" else ""
            add(JLabel("${labels.title} · ${topics.groups.size} ${labels.plural} · ${topics.labels.size} of ${files(topics.files.size)}$matching").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                name = "topics-status"
                toolTipText = labels.tooltip
                installReviewStatus(this, project, status ?: text)
            }, BorderLayout.CENTER)
            val smallFont = scaledFont(JBFont.small())
            val toggles = buildList<JComponent> {
                if (hierarchy) add(PillCheckBox(if (vocabulary == ReviewVocabulary.CODE) "Show source" else "Show diffs",
                    "topics-show-diffs", "Show file previews beside the hierarchy", showHierarchyDiffs).apply {
                    font = smallFont
                    addActionListener { showHierarchyDiffs = isSelected; canvasActions.refresh() }
                })
                if (radar && vocabulary == ReviewVocabulary.CODE) add(PillCheckBox("Symbols", "topics-radar-symbols",
                    "Compare PSI declarations (including constructors) and comment blocks; uncheck to compare lines", view.radarSymbols).apply {
                    font = smallFont
                    addActionListener { view.radarSymbols = isSelected; canvasActions.refresh() }
                })
                if (radar) add(PillCheckBox("Log scale", "topics-radar-log",
                    "Space the rings logarithmically, so small ${labels.plural} stay visible beside a large one", view.radarLog).apply {
                    font = smallFont
                    addActionListener { view.radarLog = isSelected; canvasActions.refresh() }
                })
                if (!radar) add(PillCheckBox("Tags cloud", "topics-tags-cloud",
                    "Size summary words by their importance in the child summaries or file source", view.tagsCloud).apply {
                    font = smallFont
                    addActionListener { view.tagsCloud = isSelected; canvasActions.refresh() }
                })
                add(PillCheckBox("Show only unviewed", "topics-show-only-unviewed",
                    "Show only unviewed files and their ${labels.plural}", view.showOnlyUnviewed).apply {
                    font = smallFont
                    addActionListener { view.showOnlyUnviewed = isSelected; canvasActions.refresh() }
                })
            }
            val views = buildList<JComponent> {
                if (document) add(controls)
                if (outline.depth > 0) add(topicLevels(outline))
                add(viewSelector("topics-mode", ChangeTopicsMode.entries, view.mode, 0, smallFont,
                    { when (it) { ChangeTopicsMode.DOCUMENT -> "Document"; ChangeTopicsMode.HIERARCHY -> "Hierarchy"; ChangeTopicsMode.RADAR -> "Radar" } },
                    { when (it) {
                        ChangeTopicsMode.DOCUMENT -> "Every file in ${labels.singular} order, with its summary and the ${labels.singular} rail"
                        ChangeTopicsMode.HIERARCHY -> "${labels.plural.replaceFirstChar { ch -> ch.titlecase() }} broad to specific, left to right, with each file's summary"
                        ChangeTopicsMode.RADAR -> "How many ${vocabulary.radarLines} the ${labels.plural} at the picked level hold"
                    } }) {
                    view.mode = it; scroll = 0; canvasActions.refresh()
                })
            }
            val actions = buildList<JComponent> {
                add(PillButton(when {
                    topics.labels.isEmpty() -> "Classify"
                    topics.complete -> "Regenerate"
                    else -> "Continue"
                }, tooltip = if (topics.complete) "Classify every file again (${labels.command})" else "Give every remaining file a ${labels.singular} (${labels.command})",
                    primary = true).apply {
                    font = smallFont
                    isEnabled = content.canRun && topics.files.isNotEmpty()
                    addActionListener { topicActions.generateTopics() }
                })
                add(PillButton("Fill summaries",
                    tooltip = "Fill missing summaries for every ${labels.singular} and sub${labels.singular}, including folded ${labels.plural}").apply {
                    font = smallFont
                    isEnabled = content.canRun && !generationRunning && outline.nodes.isNotEmpty() && !content.topicSummaries.complete
                    addActionListener { topicActions.fillTopicSummaries() }
                })
                val summaries = content.summaries
                if (summaries.total > 0 && !summaries.complete) add(PillButton(if (summaries.count == 0) "Summarize" else "Continue summaries",
                    tooltip = vocabulary.topicsSummarizeTooltip).apply {
                    font = smallFont
                    isEnabled = content.canRun
                    addActionListener { summaryActions.summarize() }
                })
                add(InplaceButton(IconButton(labels.closeText, AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { topicActions.removeTopics() })
            }
            add(pillToolbarRow(toggles, views, actions), BorderLayout.WEST)
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
                if (shown.files.isEmpty() && outline.files.isNotEmpty()) {
                    val emptyMessage = if (view.showOnlyUnviewed && view.topicFilter.isBlank() && view.summaryFilter.isBlank())
                        "No unviewed files." else "No files match the search."
                    add(JLabel(emptyMessage).apply {
                        name = "topics-no-matches"
                        font = scaledFont(JBFont.label()).deriveFont(java.awt.Font.ITALIC); foreground = UIUtil.getContextHelpForeground()
                    })
                }
                else if (radar) {
                    val chart = topicRadar(shown, view.radarLevel(outline), "Awaiting ${labels.singular}", view.radarSymbols)
                    val status = JLabel(radarStatus(chart, view.radarSeries)).apply {
                        name = "topics-radar-status"
                        font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                    }
                    add(status)
                    add(RadarChartPanel(chart, inner, viewZoom, view.radarLog, canvasActions.openFile, view.radarSeries) { selected ->
                        view.radarSeries = selected
                        status.text = radarStatus(chart, selected)
                    })
                }
                else add(if (hierarchy) ChangeTopicHierarchyPanel(content, view, inner, viewZoom, panelActions, vocabulary)
                    else ChangeTopicsPanel(content, view, inner, viewZoom, panelActions, vocabulary, project))
            }, BorderLayout.CENTER)
        }
        val search = JPanel(FlowLayout(FlowLayout.RIGHT, scaled(8), 0)).apply {
            isOpaque = false
            for ((field, text) in listOf(topicSearchField to view.topicFilter, topicSummarySearchField to view.summaryFilter))
                add(field.apply {
                    preferredSize = Dimension(scaled(180), preferredSize.height)
                    if (this.text != text) this.text = text
                })
        }
        header.add(search, BorderLayout.EAST)
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder() }
        card.add(header, BorderLayout.NORTH)
        val bodyScroll = JBScrollPane(body).apply {
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
        }
        val chart = if (showHierarchyDiffs) findHierarchyPanel(body) else null
        val readingArea = chart?.let {
            HierarchyPreviewPanel(it, bodyScroll, project, hierarchyPreviewPaths, { paths -> hierarchyPreviewPaths = paths })
                .also { preview -> hierarchyPreview = preview }
        } ?: bodyScroll
        card.add(readingArea, BorderLayout.CENTER)
        currentCard = card
        return card
    }

    /** Level n shows topic levels 1..n+1 and folds the rest, as in the article hierarchy; the last level shows every file. */
    private fun topicLevels(outline: TopicOutline): JComponent = PillComboBox(
        (0..outline.depth).map { level -> if (level == outline.depth) "Files" else "${level + 1}" },
        name = "topic-level-selector", prefix = "Level",
        tooltip = if (view.mode == ChangeTopicsMode.DOCUMENT)
            "Show topic rail levels through the selected level; Files shows every rail level"
        else if (view.mode == ChangeTopicsMode.RADAR) "Compare the ${labels.plural} at the selected level; Files compares every file"
        else "Show topic levels through the selected level; Files shows every topic and file",
    ).apply {
        font = scaledFont(JBFont.small())
        selectedIndex = if (view.mode == ChangeTopicsMode.RADAR) view.radarLevel(outline) else view.effectiveLevel(outline)
        accessibleContext.accessibleName = "Levels"
        addActionListener { view.selectLevel(selectedIndex, outline); canvasActions.refresh() }
    }

    private fun cardWidth(default: Int) = scaled(default)
    private fun cardBorder() =
        JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))
    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"
    private fun scaled(value: Int) = zoomScaled(value, 1.0)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(1.0)

    override fun dispose() {
        hierarchyPreview?.let(com.intellij.openapi.util.Disposer::dispose)
        hierarchyPreview = null
        currentCard = null
        trace.stop()
        review.stop()
    }

    private companion object {
        const val PADDING = 12
        const val MAX_TOPICS_HEIGHT = 900
    }
}
