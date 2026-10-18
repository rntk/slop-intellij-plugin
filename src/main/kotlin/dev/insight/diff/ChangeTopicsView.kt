package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.IconButton
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
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import kotlin.math.roundToInt

/** What the canvas shows for a tab's topics: the labels so far, the summaries beside each file, and whether a turn can run. */
internal data class TopicsCard(val topics: ChangeTopics, val summaries: ChangeSummaries, val canRun: Boolean)

/** The selected topic survives re-rendering and snapshot changes while a node with the same level and path exists. */
internal class ChangeTopicsViewState {
    var selected: String? = null
        private set

    fun update(outline: TopicOutline) { if (outline.nodes.none { key(it) == selected }) selected = null }
    fun select(node: TopicNode) { selected = key(node).takeUnless { it == selected } }
    fun isSelected(node: TopicNode) = key(node) == selected

    private fun key(node: TopicNode) = "${node.level}:${node.identity}"
}

internal class ChangeTopicActions(val openFile: (String) -> Unit, val showDiffCard: (String) -> Unit)

/**
 * The change read as one document, like an article with its topic rail: every file's diff in topic order down the middle,
 * its summary in the left margin and the topic hierarchy on the right, broad topics outermost. Each rail card spans exactly
 * the diffs of its files, and a section is as tall as the taller of its diff and summary, so the three columns stay aligned.
 */
internal class ChangeTopicsPanel(
    card: TopicsCard,
    private val state: ChangeTopicsViewState,
    width: Int,
    private val zoom: Double,
    private val actions: ChangeTopicActions,
) : JPanel(null) {
    val outline = topicOutline(card.topics)
    private val sections = mutableListOf<JComponent>()
    private val notes = mutableListOf<JComponent>()
    private val rail = mutableListOf<Pair<TopicNode?, JComponent>>()
    private var hovered: TopicNode? = null
    private fun scaled(n: Int) = (JBUI.scale(n) * zoom).roundToInt().coerceAtLeast(1)
    private fun font(base: Font) = base.deriveFont((base.size2D * zoom).toFloat())
    private val help get() = UIUtil.getContextHelpForeground()

    init {
        isOpaque = false
        state.update(outline)
        if (outline.files.isEmpty()) {
            val empty = text("No changed files", scaled(320), font(JBFont.label()), help)
            add(empty.apply { setBounds(0, 0, scaled(320), preferredSize.height) })
            preferredSize = Dimension(scaled(320), empty.height)
        } else layoutDocument(card, width)
        applyActive()
    }

    private fun layoutDocument(card: TopicsCard, width: Int) {
        // Files awaiting a topic still get a placeholder in the leaf column.
        val depth = maxOf(outline.depth, if (outline.pending != null) 1 else 0)
        val railWidth = railWidth(depth, zoom)
        val summaryWidth = scaled(SUMMARY_WIDTH)
        val gap = scaled(COLUMN_GAP)
        val docWidth = (width - summaryWidth - railWidth - 2 * gap).coerceAtLeast(scaled(MIN_DOC_WIDTH))
        val docX = summaryWidth + gap
        val railX = docX + docWidth + gap
        val summaries = card.summaries.tree.nodes.filter { it.kind == SummaryKind.FILE }.associate { it.path to card.summaries.text(it.id) }
        val tops = IntArray(outline.files.size)
        val bottoms = IntArray(outline.files.size)
        var y = 0
        outline.files.forEachIndexed { index, file ->
            val section = section(file, docWidth)
            val note = note(file, summaries[file.path], summaryWidth)
            val height = maxOf(section.preferredSize.height, note.preferredSize.height)
            section.setBounds(docX, y, docWidth, height)
            note.setBounds(0, y, summaryWidth, note.preferredSize.height)
            add(section); add(note)
            sections += section; notes += note
            tops[index] = y; bottoms[index] = y + height
            y += height + scaled(SECTION_GAP)
        }
        val cardWidth = scaled(RAIL_CARD_WIDTH)
        val step = cardWidth + scaled(RAIL_GAP)
        // The leaf level sits next to the diffs; the broadest level is outermost, as in the article canvas.
        fun place(node: TopicNode?, level: Int, first: Int, last: Int) {
            val component = railCard(node, last - first + 1, cardWidth)
            component.setBounds(railX + (depth - 1 - level) * step, tops[first], cardWidth, bottoms[last] - tops[first])
            add(component)
            rail += node to component
        }
        outline.nodes.forEach { place(it, it.level, it.first, it.last) }
        outline.pending?.let { place(null, depth - 1, it.first, it.last) }
        preferredSize = Dimension(railX + railWidth, (y - scaled(SECTION_GAP)).coerceAtLeast(0))
    }

    /** The hovered topic, else the selected one, outlines its card and the diffs and summaries of its files. */
    private fun applyActive() {
        val active = hovered ?: outline.nodes.firstOrNull(state::isSelected)
        val range = active?.let { it.first..it.last }
        sections.forEachIndexed { index, section -> section.border = frame(range != null && index in range) }
        notes.forEachIndexed { index, note -> note.border = noteBorder(range != null && index in range) }
        rail.forEach { (node, component) ->
            component.border = railBorder(node, node != null && node == active)
            component.background = if (node != null && state.isSelected(node)) UIUtil.getListSelectionBackground(false) else UIUtil.getListBackground()
        }
        repaint()
    }

    private fun section(file: ReviewFile, width: Int): JComponent = JPanel(BorderLayout()).apply {
        name = "file:${file.path}"
        background = UIUtil.getListBackground()
        border = frame(false)
        val inner = width - scaled(4)
        add(fileHeader(file), BorderLayout.NORTH)
        val patch = PatchView(file.patch, inner, zoom)
        add(patch, BorderLayout.CENTER)
        if (patch.hiddenLines > 0) add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(8), scaled(4))).apply {
            isOpaque = false
            add(JLabel("… ${patch.hiddenLines} more lines").apply { font = font(JBFont.small()); foreground = help })
            add(ActionLink("View full diff") { actions.openFile(file.path) }.apply { font = font(JBFont.small()) })
        }, BorderLayout.SOUTH)
        preferredSize = Dimension(width, preferredSize.height)
    }

    /** File name as a link, its folder and status dimmed beside it; the full path stays in the tooltip. */
    private fun fileHeader(file: ReviewFile): JComponent = JPanel(BorderLayout(scaled(6), 0)).apply {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), EmptyBorder(scaled(4), scaled(8), scaled(4), scaled(4)))
        toolTipText = file.path
        val name = file.path.substringAfterLast('/')
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JLabel(FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Any_type))
            add(Box.createHorizontalStrut(scaled(6)))
            add(ActionLink(name) { actions.openFile(file.path) }.apply {
                font = font(JBFont.label().asBold()); toolTipText = "View changes in ${file.path}"
                minimumSize = Dimension(0, preferredSize.height)
            })
            val details = listOfNotNull(file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }, file.status,
                file.previousPath?.let { "renamed from $it" })
            add(Box.createHorizontalStrut(scaled(6)))
            add(JLabel(details.joinToString(" · ")).apply {
                font = font(JBFont.small()); foreground = help; toolTipText = file.path
                minimumSize = Dimension(0, preferredSize.height)
            })
        }, BorderLayout.CENTER)
        add(InplaceButton(IconButton("Show diff on canvas", AllIcons.Actions.Diff, AllIcons.Actions.Diff)) { actions.showDiffCard(file.path) }
            .apply { cursor = Cursor.getDefaultCursor(); toolTipText = "Show diff on canvas" }, BorderLayout.EAST)
    }

    private fun note(file: ReviewFile, summary: String?, width: Int): JComponent = JPanel(BorderLayout()).apply {
        name = "summary:${file.path}"
        isOpaque = false
        border = noteBorder(false)
        val inner = width - scaled(NOTE_INSET) - scaled(2)
        add(if (summary != null) text(summary, inner, font(JBFont.label()), null)
            else text("No summary yet", inner, italic(JBFont.small()), help), BorderLayout.CENTER)
        toolTipText = "Summary of ${file.path} · LLM interpretation"
        preferredSize = Dimension(width, preferredSize.height)
    }

    private fun railCard(node: TopicNode?, files: Int, width: Int): JComponent = JPanel(VerticalLayout(scaled(2))).apply {
        name = node?.let { "topic:${it.level}:${it.identity}" } ?: "topic:pending"
        isOpaque = true
        background = UIUtil.getListBackground()
        border = railBorder(node, false)
        val inner = width - scaled(RAIL_ACCENT) - scaled(14) - 2
        add(text(node?.name ?: "Awaiting topic", inner, if (node != null) font(JBFont.label().asBold()) else italic(JBFont.label()), if (node == null) help else null))
        add(JLabel(if (files == 1) "1 file" else "$files files").apply { font = font(JBFont.small()); foreground = help })
        toolTipText = node?.fullPath ?: "Run /topics to classify these files"
        if (node == null) return@apply
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val card = this
        // Children cover most of the card, so each forwards hover and clicks; leaving into a child is not leaving the card.
        val listener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { if (hovered != node) { hovered = node; applyActive() } }
            override fun mouseExited(e: MouseEvent) {
                if (hovered == node && !card.contains(SwingUtilities.convertPoint(e.component, e.point, card))) { hovered = null; applyActive() }
            }
            override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) { state.select(node); applyActive() } }
        }
        fun attach(component: Component) {
            component.addMouseListener(listener)
            (component as? JComponent)?.toolTipText = node.fullPath
            if (component is Container) component.components.forEach(::attach)
        }
        attach(this)
    }

    private fun frame(active: Boolean): Border? =
        if (active) JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2)
        else JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))

    /** A left rule beside the summary; both states keep the same insets so the text never shifts. */
    private fun noteBorder(active: Boolean): Border? = JBUI.Borders.compound(
        MatteBorder(0, scaled(2), 0, 0, if (active) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()),
        EmptyBorder(scaled(2), scaled(NOTE_INSET), scaled(2), 0),
    )

    private fun railBorder(node: TopicNode?, active: Boolean): Border? = JBUI.Borders.compound(
        MatteBorder(1, 1, 1, 1, if (active) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()),
        MatteBorder(0, scaled(RAIL_ACCENT), 0, 0, node?.let { accent(it.path.first()) } ?: JBColor.border()),
        EmptyBorder(scaled(6), scaled(8), scaled(6), scaled(6)),
    )

    private fun italic(base: Font) = font(base).deriveFont(font(base).style or Font.ITALIC)

    private fun text(value: String, width: Int, font: Font, color: Color?): JComponent = WrappedText(value, width).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; border = null
        this.font = font
        color?.let { foreground = it }
    }

    companion object {
        const val SUMMARY_WIDTH = 240
        const val MIN_DOC_WIDTH = 360
        const val DOC_WIDTH = 620
        const val COLUMN_GAP = 16
        const val SECTION_GAP = 12
        const val RAIL_CARD_WIDTH = 150
        const val RAIL_GAP = 6
        const val RAIL_ACCENT = 4
        const val NOTE_INSET = 10

        private fun scaledBy(zoom: Double, n: Int) = (JBUI.scale(n) * zoom).roundToInt().coerceAtLeast(1)
        fun railWidth(depth: Int, zoom: Double) =
            if (depth <= 0) 0 else depth * scaledBy(zoom, RAIL_CARD_WIDTH) + (depth - 1) * scaledBy(zoom, RAIL_GAP)
        /** Unscaled width that fits the summaries, a readable diff column and every topic level. */
        fun defaultWidth(depth: Int) = SUMMARY_WIDTH + DOC_WIDTH + 2 * COLUMN_GAP +
            maxOf(depth, 1) * RAIL_CARD_WIDTH + (maxOf(depth, 1) - 1) * RAIL_GAP

        /** One hue per top-level topic, so a subsystem reads as one family down the rail. */
        private val ACCENTS = listOf(
            JBColor(0x4C7DD9, 0x5F8FE6), JBColor(0x2E9E6B, 0x4DB383), JBColor(0xD9822B, 0xE09A50), JBColor(0xB04FC2, 0xC27AD1),
            JBColor(0xD04A5A, 0xDB6B78), JBColor(0x1F9AA8, 0x45B4C0), JBColor(0x8A7A2E, 0xB0A050), JBColor(0x6A62D4, 0x8A84E0),
        )
        internal fun accent(topLevel: String): Color = ACCENTS[Math.floorMod(topLevel.lowercase().hashCode(), ACCENTS.size)]
    }
}

/**
 * A unified patch painted as editor-styled lines with old and new line numbers. It paints only the visible rows, so a long
 * document of many files stays cheap; very long patches stop at [MAX_LINES] and link to the full diff.
 */
internal class PatchView(patch: String, private val viewWidth: Int, zoom: Double) : JComponent() {
    private enum class Kind { CONTEXT, ADDED, REMOVED, HUNK, NOTE }
    private class Line(val text: String, val kind: Kind, val old: Int?, val new: Int?)

    private val lines: List<Line>
    val hiddenLines: Int
    private val scheme = EditorColorsManager.getInstance().globalScheme
    private val lineFont: Font = scheme.getFont(EditorFontType.PLAIN).deriveFont((scheme.editorFontSize2D * zoom).toFloat())
    private val metrics = getFontMetrics(lineFont)
    private val lineHeight = metrics.height
    private val pad = (JBUI.scale(4) * zoom).roundToInt()
    private val numberWidth: Int

    init {
        val all = patch.split('\n').let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
        var old = 0
        var new = 0
        var inHunk = false
        val parsed = all.map { raw ->
            val text = raw.replace("\t", "    ")
            val hunk = HUNK.find(raw)
            when {
                hunk != null -> {
                    old = hunk.groupValues[1].toInt(); new = hunk.groupValues[2].toInt(); inHunk = true
                    Line(text, Kind.HUNK, null, null)
                }
                !inHunk || raw.startsWith("[") -> Line(text, Kind.NOTE, null, null)
                raw.startsWith("+") -> Line(text, Kind.ADDED, null, new++)
                raw.startsWith("-") -> Line(text, Kind.REMOVED, old++, null)
                else -> Line(text, Kind.CONTEXT, old++, new++)
            }
        }
        lines = parsed.take(MAX_LINES)
        hiddenLines = parsed.size - lines.size
        val largest = lines.maxOfOrNull { maxOf(it.old ?: 0, it.new ?: 0) } ?: 0
        numberWidth = if (lines.none { it.old != null || it.new != null }) 0 else metrics.stringWidth("0".repeat(maxOf(3, largest.toString().length)))
        font = lineFont
        preferredSize = Dimension(viewWidth, maxOf(lines.size, 1) * lineHeight + 2 * pad)
    }

    val lineCount: Int get() = lines.size

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = lineFont
            g2.color = scheme.defaultBackground
            g2.fillRect(0, 0, width, height)
            val clip = g2.clipBounds ?: java.awt.Rectangle(0, 0, width, height)
            val gutter = if (numberWidth == 0) 0 else 2 * numberWidth + 3 * pad
            val from = ((clip.y - pad) / lineHeight).coerceAtLeast(0)
            val to = ((clip.y + clip.height - pad) / lineHeight + 1).coerceAtMost(lines.size)
            if (lines.isEmpty()) {
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString("No textual changes", pad, pad + metrics.ascent)
            }
            for (index in from until to) {
                val line = lines[index]
                val top = pad + index * lineHeight
                background(line.kind)?.let { g2.color = it; g2.fillRect(0, top, width, lineHeight) }
                val baseline = top + metrics.ascent
                if (gutter > 0) {
                    g2.color = UIUtil.getContextHelpForeground()
                    line.old?.let { g2.drawString(it.toString(), pad + numberWidth - metrics.stringWidth(it.toString()), baseline) }
                    line.new?.let { g2.drawString(it.toString(), 2 * pad + 2 * numberWidth - metrics.stringWidth(it.toString()), baseline) }
                }
                g2.color = when (line.kind) {
                    Kind.HUNK, Kind.NOTE -> UIUtil.getContextHelpForeground()
                    else -> scheme.defaultForeground
                }
                // Long lines are clipped at the column; resizing the card or the full diff shows the rest.
                g2.drawString(line.text, gutter + pad, baseline)
            }
        } finally { g2.dispose() }
    }

    private fun background(kind: Kind): Color? = when (kind) {
        Kind.ADDED -> scheme.getAttributes(DiffColors.DIFF_INSERTED)?.backgroundColor ?: JBColor(0xDDF5E3, 0x2D4535)
        Kind.REMOVED -> scheme.getAttributes(DiffColors.DIFF_DELETED)?.backgroundColor ?: JBColor(0xF9DEDE, 0x4D3134)
        Kind.HUNK -> JBColor(0xEEF2F8, 0x2E3239)
        else -> null
    }

    /** Test hook: the painted rows as `kind old new text`. */
    internal fun describe(): List<String> = lines.map { "${it.kind.name.lowercase()} ${it.old ?: "-"} ${it.new ?: "-"} ${it.text}" }

    companion object {
        const val MAX_LINES = 400
        private val HUNK = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
    }
}
