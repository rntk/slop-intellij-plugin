package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.ColorUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import kotlin.math.roundToInt

/**
 * The topic hierarchy read left to right, like the article hierarchy: broad topics in the first column, each card as tall as
 * the rows beneath it, and the changed files as leaves with their summary beside them. A folded topic takes a single row.
 */
internal class ChangeTopicHierarchyPanel(
    card: TopicsCard,
    private val state: ChangeTopicsViewState,
    private val availableWidth: Int,
    private val zoom: Double,
    private val actions: ChangeTopicActions,
) : JPanel(null) {
    val outline = topicOutline(card.topics)
    private val summaries = card.summaries
    private val fileSummaries = summaries.tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.path to summaries.text(it.id) }
    /** Summaries by the exact files beneath their node, so a folded topic covering the same files can show one. */
    private val summariesByFiles = summaries.tree.nodes.mapNotNull { node -> summaries.text(node.id)?.let { node.files.toSet() to it } }.toMap()
    private val active = outline.nodes.firstOrNull(state::isSelected)?.let { it.first..it.last }
    private val cardWidth: Int
    private var right = 0
    private fun scaled(n: Int) = (JBUI.scale(n) * zoom).roundToInt().coerceAtLeast(1)
    private fun font(base: Font) = base.deriveFont((base.size2D * zoom).toFloat())
    private val help get() = UIUtil.getContextHelpForeground()
    private val step get() = cardWidth + scaled(COLUMN_GAP)

    init {
        isOpaque = false
        state.update(outline)
        cardWidth = measureCardWidth()
        if (outline.files.isEmpty()) {
            val empty = text("No changed files", scaled(320), font(JBFont.label()), help)
            add(empty.apply { setBounds(0, 0, scaled(320), preferredSize.height) })
            preferredSize = Dimension(scaled(320), empty.height)
        } else layoutHierarchy()
    }

    /** One shared width keeps the columns aligned; it fits the widest name within bounds, and longer names wrap. */
    private fun measureCardWidth(): Int {
        val metrics = getFontMetrics(font(JBFont.label().asBold()))
        val widest = (outline.nodes.map { it.name } + outline.files.map { it.path.substringAfterLast('/') }).maxOfOrNull(metrics::stringWidth) ?: 0
        val chrome = scaled(RAIL_ACCENT + 2 * CARD_PAD + TOGGLE + 2 * INNER_GAP + DIFF_BUTTON) + 4
        return (widest + chrome).coerceIn(scaled(MIN_CARD_WIDTH), scaled(MAX_CARD_WIDTH))
    }

    private fun layoutHierarchy() {
        val gap = scaled(ROW_GAP)
        var y = 0
        outline.children(null).forEach { y += place(it, 0, y) + gap }
        outline.pending?.let { pending ->
            var childY = y
            for (index in pending) childY += fileRow(index, 1, childY, null) + gap
            val placeholder = topicCard(null, collapsed = false, column = 0, files = pending.count())
            val height = maxOf(childY - gap - y, placeholder.preferredSize.height)
            add(placeholder.apply { setBounds(0, y, cardWidth, height) })
            y += height + gap
        }
        preferredSize = Dimension(maxOf(availableWidth, right), (y - gap).coerceAtLeast(0))
    }

    /** Lays out [entry] with its top-left at column [column] and row [y], and returns its height. */
    private fun place(entry: TopicEntry, column: Int, y: Int, topLevel: String? = null): Int = when (entry) {
        is TopicEntry.File -> fileRow(entry.index, column, y, topLevel)
        is TopicEntry.Topic -> topicNode(entry.node, column, y)
    }

    private fun topicNode(node: TopicNode, column: Int, y: Int): Int {
        val collapsed = state.isCollapsed(node)
        val component = topicCard(node, collapsed, column, node.size)
        val height = if (collapsed) {
            val (summary, written) = foldedSummary(node)
            val note = note("summary:${node.level}:${node.identity}", summary, written, column + 1, y)
            maxOf(component.preferredSize.height, note.preferredSize.height)
        } else {
            val gap = scaled(ROW_GAP)
            var childY = y
            outline.children(node).forEach { childY += place(it, column + 1, childY, node.path.first()) + gap }
            maxOf(childY - gap - y, component.preferredSize.height)
        }
        add(component.apply { setBounds(column * step, y, cardWidth, height) })
        return height
    }

    private fun fileRow(index: Int, column: Int, y: Int, topLevel: String?): Int {
        val file = outline.files[index]
        val summary = fileSummaries[file.path]
        val leaf = fileCard(file, column, topLevel, active?.contains(index) == true)
        val note = note("summary:${file.path}", summary ?: "No summary yet", summary != null, column + 1, y)
        val height = maxOf(leaf.preferredSize.height, note.preferredSize.height)
        add(leaf.apply { setBounds(column * step, y, cardWidth, height) })
        return height
    }

    /** The summary of a folded topic's exact file set when one exists, else the names of the files it hides. */
    private fun foldedSummary(node: TopicNode): Pair<String, Boolean> {
        val paths = (node.first..node.last).map { outline.files[it].path }
        summariesByFiles[paths.toSet()]?.let { return it to true }
        val names = paths.map { it.substringAfterLast('/') }
        return (names.take(FOLDED_NAMES).joinToString(", ") + if (names.size > FOLDED_NAMES) ", +${names.size - FOLDED_NAMES} more" else "") to false
    }

    /** Text beside a leaf or folded card; it starts one column to the right and takes the rest of the width. */
    private fun note(name: String, value: String, written: Boolean, column: Int, y: Int): JComponent {
        val x = column * step
        val width = (availableWidth - x).coerceAtLeast(scaled(MIN_SUMMARY_WIDTH))
        // The top inset matches the cards' so the first line sits level with the card title.
        val inset = scaled(CARD_PAD) + 2
        val note = text(value, width - scaled(NOTE_INSET), if (written) font(JBFont.label()) else italic(JBFont.small()), if (written) null else help).apply {
            this.name = name
            border = EmptyBorder(inset, scaled(NOTE_INSET), inset, 0)
            toolTipText = if (written) "Summary · LLM interpretation" else null
        }
        val height = note.preferredSize.height
        add(note.apply { setBounds(x, y, width, height) })
        right = maxOf(right, x + width)
        return note
    }

    private fun topicCard(node: TopicNode?, collapsed: Boolean, column: Int, files: Int): JComponent = JPanel(BorderLayout(scaled(INNER_GAP), 0)).apply {
        name = node?.let { "topic:${it.level}:${it.identity}" } ?: "topic:pending"
        val topLevel = node?.path?.first()
        isOpaque = true
        background = tint(topLevel, column)
        border = cardBorder(topLevel, node != null && state.isSelected(node))
        toolTipText = node?.fullPath ?: "Run /topics to classify these files"
        val inner = cardWidth - scaled(RAIL_ACCENT + 2 * CARD_PAD + TOGGLE + INNER_GAP) - 4
        add(if (node != null) toggle(node, collapsed) else gutter(null), BorderLayout.WEST)
        add(JPanel(VerticalLayout(scaled(2))).apply {
            isOpaque = false
            add(text(node?.name ?: "Awaiting topic", inner, if (node != null) font(JBFont.label().asBold()) else italic(JBFont.label()), if (node == null) help else null))
            add(JLabel(if (files == 1) "1 file" else "$files files").apply { font = font(JBFont.small()); foreground = help })
        }, BorderLayout.CENTER)
        if (node == null) return@apply
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // Children cover most of the card, so each forwards clicks; the toggle keeps its own.
        val select = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) { state.select(node); actions.refresh() } }
        }
        fun attach(component: Component) {
            if (component.name?.startsWith("toggle:") == true) return
            component.addMouseListener(select)
            (component as? JComponent)?.toolTipText = node.fullPath
            if (component is Container) component.components.forEach(::attach)
        }
        attach(this)
    }

    /** Folding hides a topic's children to the right, so the arrows point along the hierarchy as in the article view. */
    private fun toggle(node: TopicNode, collapsed: Boolean): JComponent = gutter(if (collapsed) "›" else "‹").apply {
        name = "toggle:${node.level}:${node.identity}"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = if (collapsed) "Show sub-topics and files" else "Fold sub-topics and files"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) { state.toggle(node); actions.refresh() } }
        })
    }

    /** The toggle's footprint; leaves reserve it too, so every title in a column starts at the same x. */
    private fun gutter(text: String?): JLabel = JLabel(text).apply {
        font = font(JBFont.label().biggerOn(4f).asBold())
        foreground = help
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.TOP
        preferredSize = Dimension(scaled(TOGGLE), preferredSize.height)
    }

    /** A leaf: the file name opens its diff; the folder and status are dimmed beneath it, and the full path is in the tooltip. */
    private fun fileCard(file: ReviewFile, column: Int, topLevel: String?, active: Boolean): JComponent = JPanel(BorderLayout(scaled(INNER_GAP), 0)).apply {
        name = "file:${file.path}"
        isOpaque = true
        background = tint(topLevel, column)
        border = cardBorder(topLevel, active)
        toolTipText = file.path
        val fileName = file.path.substringAfterLast('/')
        add(JLabel(FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon ?: AllIcons.FileTypes.Any_type).apply {
            verticalAlignment = SwingConstants.TOP
            preferredSize = Dimension(scaled(TOGGLE), preferredSize.height)
        }, BorderLayout.WEST)
        add(JPanel(VerticalLayout(scaled(2))).apply {
            isOpaque = false
            add(ActionLink(fileName) { actions.openFile(file.path) }.apply {
                font = font(JBFont.label()); toolTipText = "View changes in ${file.path}"
                minimumSize = Dimension(0, preferredSize.height)
            })
            val details = listOfNotNull(file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }, file.status,
                file.previousPath?.let { "renamed from $it" })
            add(JLabel(details.joinToString(" · ")).apply {
                font = font(JBFont.small()); foreground = help; toolTipText = file.path
                minimumSize = Dimension(0, preferredSize.height)
            })
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(InplaceButton(IconButton("Show diff on canvas", AllIcons.Actions.Diff, AllIcons.Actions.Diff)) { actions.showDiffCard(file.path) }
                .apply { cursor = Cursor.getDefaultCursor(); toolTipText = "Show diff on canvas" }, BorderLayout.NORTH)
        }, BorderLayout.EAST)
    }

    /** A light wash of the top-level topic's hue that fades with depth, so a subsystem reads as one family. */
    private fun tint(topLevel: String?, column: Int): Color {
        val base = UIUtil.getListBackground()
        return topLevel?.let { ColorUtil.mix(base, ChangeTopicsPanel.accent(it), (0.16 - 0.03 * column).coerceAtLeast(0.05)) } ?: base
    }

    /** Both states keep the same insets, so selecting never shifts the text. */
    private fun cardBorder(topLevel: String?, active: Boolean): Border? {
        val line = if (active) 2 else 1
        return JBUI.Borders.compound(
            MatteBorder(line, line, line, line, if (active) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()),
            MatteBorder(0, scaled(RAIL_ACCENT), 0, 0, topLevel?.let(ChangeTopicsPanel::accent) ?: JBColor.border()),
            EmptyBorder(scaled(CARD_PAD) + 2 - line, scaled(CARD_PAD) + 2 - line, scaled(CARD_PAD) + 2 - line, scaled(CARD_PAD) + 2 - line),
        )
    }

    private fun italic(base: Font) = font(base).deriveFont(font(base).style or Font.ITALIC)

    private fun text(value: String, width: Int, font: Font, color: Color?): JComponent = WrappedText(value, width).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; border = null
        this.font = font
        color?.let { foreground = it }
    }

    companion object {
        const val MIN_CARD_WIDTH = 140
        const val MAX_CARD_WIDTH = 240
        const val CARD_WIDTH = 180
        const val SUMMARY_WIDTH = 420
        const val MIN_SUMMARY_WIDTH = 240
        const val COLUMN_GAP = 8
        const val ROW_GAP = 6
        const val RAIL_ACCENT = 4
        const val CARD_PAD = 5
        const val TOGGLE = 18
        const val INNER_GAP = 6
        const val DIFF_BUTTON = 22
        const val NOTE_INSET = 4
        const val FOLDED_NAMES = 3

        /** Unscaled width that fits every topic level, the file column and a readable summary. */
        fun defaultWidth(depth: Int): Int {
            val columns = maxOf(depth, 1) + 1
            return columns * (CARD_WIDTH + COLUMN_GAP) + SUMMARY_WIDTH
        }
    }
}
