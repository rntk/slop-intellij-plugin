package dev.insight.diff.walk.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.diff.ReviewFile
import dev.insight.diff.walk.HunkStatus
import dev.insight.diff.walk.WalkCoverage
import dev.insight.diff.walk.WalkStepKind
import dev.insight.diff.walk.Walkthrough
import dev.insight.review.ui.LinkedTextPane
import dev.insight.review.wall.WallLine
import kotlinx.coroutines.CoroutineScope
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import kotlin.math.roundToInt

/**
 * What the Walkthrough page shows: [walkthrough] of [files] (all pending before a run), whether a run is [running] with its
 * [progress] line, a [message] such as a missing model, and whether a run can start ([canRun]). [built] is false while the
 * walkthrough is only the pending preview of every hunk.
 */
internal data class WalkthroughContent(
    val walkthrough: Walkthrough,
    val files: List<ReviewFile>,
    val running: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
    val canRun: Boolean = true,
    val built: Boolean = false,
)

/** What the page asks its review to do. */
internal class WalkthroughActions(
    /** Starts a run with an optional reviewer focus. */
    val run: (focus: String?) -> Unit = {},
    val stop: () -> Unit = {},
    /** Opens the native diff of a file, at a double-clicked row when there is one. */
    val openDiff: (ReviewFile, WallLine?) -> Unit = { _, _ -> },
)

/**
 * The Walkthrough page: a header with the run controls, coverage bar and warnings; the steps and their hunks on the left;
 * and on the right a [WalkCanvas] of the hunks the reader opened, with a trail from the step's root to the focused card.
 * One page lives for the review tab, so a run's updates and canvas rebuilds keep its cards, pan and zoom.
 */
internal class WalkthroughPage(
    private val project: Project?,
    private val scope: CoroutineScope?,
    private val actions: WalkthroughActions,
) : Disposable {
    private var content = WalkthroughContent(Walkthrough(emptyList()), emptyList())
    private var files: Map<String, ReviewFile> = emptyMap()
    private val expanded = HashSet<String>()
    private var selectedStep: String? = null

    internal val canvas: WalkCanvas = WalkCanvas(project, WalkCanvasActions(
        openDiff = { hunk, line -> files[hunk.path]?.let { actions.openDiff(it, line) } },
        focusChanged = { focusChanged(it) },
        boardChanged = { if (filter.selectedItem == WalkListFilter.NOT_ON_CANVAS) refreshList() },
        zoomChanged = { zoomLabel.text = "${(it * 100).roundToInt()}%" },
    ))

    private val build = JButton("Build walkthrough", AllIcons.Actions.Execute).apply {
        name = "walkthrough-build"
        toolTipText = "Ask the assistant to walk the change: what caused each change and what it leads to"
        addActionListener { actions.run(focusField.text.trim().ifEmpty { null }) }
    }
    private val stop = JButton("Stop", AllIcons.Actions.Suspend).apply {
        name = "walkthrough-stop"
        toolTipText = "Stop the run; what it reached stays, the rest is listed as not linked"
        addActionListener { actions.stop() }
    }
    private val focusField = JBTextField(18).apply {
        name = "walkthrough-focus"
        emptyText.text = "Optional focus, e.g. error handling"
        toolTipText = "What the assistant should pay most attention to"
    }
    internal val progressLabel = JLabel().apply { name = "walkthrough-progress"; foreground = UIUtil.getContextHelpForeground() }
    internal val coverageBar = CoverageBar { status -> filter.selectedItem = WalkListFilter.of(status) }
    private val coverageLabel = JLabel().apply { font = JBFont.small(); foreground = UIUtil.getContextHelpForeground() }
    internal val messageLabel = JLabel().apply { name = "walkthrough-message"; isVisible = false }
    internal val filter = ComboBox(WalkListFilter.entries.toTypedArray()).apply {
        name = "walkthrough-filter"
        toolTipText = "Show every step, the changes not on the canvas yet, or the changes of one status"
        addActionListener { refreshList() }
    }
    private val listModel = DefaultListModel<WalkListRow>()
    internal val list = JBList(listModel).apply {
        name = "walkthrough-steps"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RowRenderer()
        emptyText.text = "No changes match"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!UIUtil.isActionClick(e, MouseEvent.MOUSE_CLICKED)) return
                val index = locationToIndex(e.point).takeIf { it >= 0 && getCellBounds(it, it)?.contains(e.point) == true } ?: return
                activate(listModel[index])
            }
        })
    }
    private val explanation: JEditorPane = (if (project != null && scope != null) LinkedTextPane(project, scope) else
        JEditorPane("text/html", "").apply { isEditable = false; isOpaque = false }).apply { name = "walkthrough-explanation" }
    private val explanationTitle = JLabel().apply { font = JBFont.label().asBold(); border = JBUI.Borders.empty(6, 6, 2, 6) }
    private val explanationPanel = JPanel(BorderLayout()).apply {
        isVisible = false
        add(explanationTitle, BorderLayout.NORTH)
        add(JBScrollPane(explanation).apply { border = JBUI.Borders.empty(0, 6, 6, 6); preferredSize = Dimension(JBUI.scale(200), JBUI.scale(170)) }, BorderLayout.CENTER)
    }
    internal val trail = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(2))).apply { name = "walkthrough-trail"; isOpaque = false }
    private val zoomLabel: JLabel = JLabel("100%").apply {
        font = JBFont.small()
        toolTipText = "Reset zoom (0)"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = canvas.zoomTo(1.0) })
    }

    val card: JPanel = JPanel(BorderLayout()).apply {
        name = "walkthrough"
        // Small, so the page's scroll pane hands it the whole viewport; the canvas pans on its own.
        preferredSize = Dimension(JBUI.scale(640), JBUI.scale(420))
        add(header(), BorderLayout.NORTH)
        add(JBSplitter(false, 0.3f).apply {
            firstComponent = left()
            secondComponent = right()
        }, BorderLayout.CENTER)
    }

    init {
        Disposer.register(this, canvas)
    }

    private fun header(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(6, 8, 4, 8)
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(build); add(focusField); add(stop); add(progressLabel)
            alignmentX = 0f
        })
        add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.emptyTop(6)
            add(coverageBar, BorderLayout.CENTER)
            add(coverageLabel, BorderLayout.EAST)
            alignmentX = 0f
        })
        add(messageLabel.apply { border = JBUI.Borders.emptyTop(4); alignmentX = 0f })
    }

    private fun left(): JComponent = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(JLabel("Show: "), BorderLayout.WEST)
            add(filter, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(explanationPanel, BorderLayout.SOUTH)
    }

    private fun right(): JComponent = JPanel(BorderLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 6)
            add(trail, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(smallButton("−", "Zoom out (-)") { canvas.zoomTo(canvas.zoom / WalkCanvas.KEY_ZOOM) })
                add(zoomLabel)
                add(smallButton("+", "Zoom in (+)") { canvas.zoomTo(canvas.zoom * WalkCanvas.KEY_ZOOM) })
                add(smallButton("Fit", "Show every card (F)") { canvas.fit() })
                add(smallButton("Clear", "Remove every card from the canvas") { canvas.clear() })
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(canvas, BorderLayout.CENTER)
    }

    private fun smallButton(text: String, tooltip: String, run: () -> Unit) = JLabel(text).apply {
        font = JBFont.small()
        toolTipText = tooltip
        foreground = UIUtil.getLabelForeground()
        border = JBUI.Borders.empty(0, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = run() })
    }

    /** Shows [content] in place: the canvas keeps its cards, pan and zoom, and the list its selection and expanded steps. */
    fun show(content: WalkthroughContent) {
        val previous = this.content
        this.content = content
        files = content.files.associateBy { it.path }
        if (previous.walkthrough !== content.walkthrough || previous.files !== content.files) canvas.show(content.walkthrough, content.files)
        val walkthrough = content.walkthrough
        expanded.retainAll(walkthrough.steps.mapTo(HashSet()) { it.id } + WALK_PENDING_GROUP)
        if (selectedStep != null && walkthrough.step(selectedStep!!) == null) selectedStep = null
        build.text = if (content.built) "Rebuild walkthrough" else "Build walkthrough"
        build.isEnabled = content.canRun && !content.running && walkthrough.hunks.isNotEmpty()
        stop.isVisible = content.running
        focusField.isEnabled = !content.running
        progressLabel.text = content.progress.orEmpty()
        progressLabel.isVisible = !content.progress.isNullOrBlank()
        val coverage = walkthrough.coverage()
        coverageBar.coverage = coverage
        coverageLabel.text = walkCoverageText(coverage)
        val notes = buildList {
            content.message?.takeIf { it.isNotBlank() }?.let(::add)
            if (!content.built && !content.running && content.message == null && walkthrough.hunks.isNotEmpty())
                add("Not built yet: every change is listed as pending, so you can still browse them. Build the walkthrough to link them.")
            addAll(walkthrough.warnings)
        }
        messageLabel.isVisible = notes.isNotEmpty()
        messageLabel.icon = if (walkthrough.warnings.isNotEmpty() || content.message != null) AllIcons.General.Warning else AllIcons.General.Information
        messageLabel.text = "<html>" + notes.joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</html>"
        refreshList()
        showExplanation()
        refreshTrail()
    }

    private fun refreshList() {
        val selected = list.selectedValue
        val rows = walkListRows(content.walkthrough, filter.selectedItem as? WalkListFilter ?: WalkListFilter.ALL, expanded,
            canvas.board.cards.mapTo(HashSet()) { it.ref })
        if (rows == (0 until listModel.size()).map(listModel::get)) return
        listModel.clear()
        listModel.addAll(rows)
        val keep = rows.indexOfFirst { sameRow(it, selected) }
        if (keep >= 0) list.selectedIndex = keep
    }

    private fun sameRow(a: WalkListRow, b: WalkListRow?): Boolean = when {
        a is WalkListRow.Group && b is WalkListRow.Group -> a.id == b.id
        a is WalkListRow.Hunk && b is WalkListRow.Hunk -> a.hunk.id == b.hunk.id && a.group == b.group
        else -> false
    }

    /** A step row opens its root and note and folds its hunks in or out; a hunk row opens that hunk. */
    internal fun activate(row: WalkListRow) {
        when (row) {
            is WalkListRow.Group -> {
                if (!expanded.add(row.id)) expanded.remove(row.id)
                row.step?.let { step ->
                    selectedStep = step.id
                    canvas.openStep(step.id)
                }
                refreshList()
                showExplanation()
            }
            is WalkListRow.Hunk -> {
                val step = content.walkthrough.step(row.group)
                if (step != null) selectedStep = step.id
                if (step != null && step.rootHunk == row.hunk.id) canvas.openStep(step.id) else canvas.openHunk(row.hunk.id)
                showExplanation()
            }
        }
    }

    /** Opens [stepId] as a click on its row does. */
    internal fun openStep(stepId: String) {
        content.walkthrough.step(stepId)?.let { activate(WalkListRow.Group(it.id, it.title, it.kind, it.hunks.size, it.hunks.size, false, it)) }
    }

    private fun focusChanged(cardId: String?) {
        val card = cardId?.let(canvas.board::card)
        if (card != null) {
            val step = if (card.kind == WalkCard.Kind.NOTE) content.walkthrough.step(card.ref)
                else canvas.board.trail(card.id).firstNotNullOfOrNull { it.step }?.let(content.walkthrough::step)
                    ?: content.walkthrough.primaryStep(card.ref)
            if (step != null && step.id != selectedStep) {
                selectedStep = step.id
                showExplanation()
            }
        }
        refreshTrail()
    }

    private fun showExplanation() {
        val step = selectedStep?.let(content.walkthrough::step)
        explanationPanel.isVisible = step != null
        if (step == null) return
        explanationTitle.text = step.title
        explanationTitle.foreground = if (step.kind == WalkStepKind.UNLINKED) WalkCanvas.statusColor(HunkStatus.UNLINKED) else UIUtil.getLabelForeground()
        val pane = explanation
        if (pane is LinkedTextPane) pane.markdown = step.explanation
        else pane.text = "<html><body>" + StringUtil.escapeXmlEntities(step.explanation).replace("\n", "<br>") + "</body></html>"
        explanationPanel.revalidate()
    }

    /** The trail from the focused card's root to it: a crumb each, which moves the canvas there. */
    private fun refreshTrail() {
        trail.removeAll()
        val focused = canvas.board.focused
        val chain = focused?.let(canvas.board::trail).orEmpty()
        if (chain.isEmpty()) {
            trail.add(JLabel("Drag to pan, Alt+drag over cards, Ctrl/Cmd+wheel to zoom; chips open linked changes").apply {
                font = JBFont.small(); foreground = UIUtil.getContextHelpForeground()
            })
        }
        chain.forEachIndexed { index, card ->
            if (index > 0) trail.add(JLabel("›").apply { foreground = UIUtil.getContextHelpForeground() })
            val text = when (card.kind) {
                WalkCard.Kind.NOTE -> content.walkthrough.step(card.ref)?.title ?: card.ref
                WalkCard.Kind.HUNK -> content.walkthrough.hunk(card.ref)?.let(::walkHunkName) ?: card.ref
            }
            val edge = chain.getOrNull(index - 1)?.let { parent -> canvas.board.edges.firstOrNull { it.from == parent.id && it.to == card.id } }
            trail.add(JLabel((edge?.group?.label?.let { "$it " }.orEmpty()) + text).apply {
                name = "walkthrough-crumb:${card.id}"
                font = if (card.id == focused) JBFont.small().asBold() else JBFont.small()
                foreground = if (card.id == focused) UIUtil.getLabelForeground() else JBUI.CurrentTheme.Link.Foreground.ENABLED
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = content.walkthrough.hunk(card.ref)?.let { "${walkHunkTitle(it)} · ${walkHunkPlace(it)}" }
                addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = canvas.reveal(card.id) })
            })
        }
        trail.revalidate()
        trail.repaint()
    }

    /** The rows the list shows now; for tests. */
    internal fun listRows(): List<WalkListRow> = (0 until listModel.size()).map(listModel::get)

    override fun dispose() {}

    private inner class RowRenderer : ColoredListCellRenderer<WalkListRow>() {
        override fun customizeCellRenderer(list: JList<out WalkListRow>, value: WalkListRow?, index: Int, selected: Boolean, hasFocus: Boolean) {
            when (value) {
                is WalkListRow.Group -> {
                    icon = when {
                        value.kind == WalkStepKind.UNLINKED -> AllIcons.General.Warning
                        value.expanded -> AllIcons.General.ArrowDown
                        else -> AllIcons.General.ArrowRight
                    }
                    val flagged = value.flagged
                    append(value.title, if (flagged) SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, WalkCanvas.statusColor(HunkStatus.UNLINKED))
                        else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${value.countText}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${value.badge}", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, badgeColor(value.kind)))
                    toolTipText = value.step?.let { "${it.id}: ${it.title}" } ?: "Changes the assistant has not put in a step yet"
                }
                is WalkListRow.Hunk -> {
                    ipad = JBUI.insetsLeft(22)
                    icon = null
                    val hunk = value.hunk
                    append(if (value.onCanvas) "◆ " else "◇ ", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, WalkCanvas.statusColor(value.status)))
                    append(walkHunkName(hunk), if (value.root) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ${walkHunkPlace(hunk)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ${walkStatusLabel(value.status)}", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, WalkCanvas.statusColor(value.status)))
                    toolTipText = "${walkHunkTitle(hunk)} · ${walkStatusTooltip(value.status)}" + if (value.onCanvas) " · on the canvas" else ""
                }
                null -> {}
            }
        }

        private fun badgeColor(kind: WalkStepKind?) = when (kind) {
            WalkStepKind.CHAIN -> WalkCanvas.statusColor(HunkStatus.LINKED)
            WalkStepKind.STANDALONE -> WalkCanvas.statusColor(HunkStatus.STANDALONE)
            WalkStepKind.UNLINKED -> WalkCanvas.statusColor(HunkStatus.UNLINKED)
            null -> WalkCanvas.statusColor(HunkStatus.PENDING)
        }
    }

    /** The coverage bar: a segment per status, sized by its hunks; clicking one shows those hunks in the list. */
    internal class CoverageBar(private val select: (HunkStatus) -> Unit) : JComponent() {
        var coverage: WalkCoverage = WalkCoverage(0, emptyMap())
            set(value) { field = value; segments = walkCoverageSegments(value); repaint() }
        private var segments: List<WalkCoverageSegment> = emptyList()

        init {
            name = "walkthrough-coverage"
            preferredSize = Dimension(JBUI.scale(200), JBUI.scale(12))
            minimumSize = Dimension(JBUI.scale(60), JBUI.scale(12))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = ""
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { walkSegmentAt(segments, width, e.x)?.let { select(it.status) } }
            })
        }

        override fun getToolTipText(event: MouseEvent): String? = walkSegmentAt(segments, width, event.x)?.let {
            "${it.label}: ${walkStatusTooltip(it.status)}. Click to list them."
        } ?: walkCoverageText(coverage)

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = UIUtil.getPanelBackground().darker()
                g2.fillRoundRect(0, 0, width, height, height, height)
                walkSegmentSpans(segments, width).forEachIndexed { index, span ->
                    g2.color = WalkCanvas.statusColor(segments[index].status)
                    g2.fillRect(span.first, 0, span.last - span.first + 1, height)
                }
            } finally {
                g2.dispose()
            }
        }
    }
}

