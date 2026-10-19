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
import javax.swing.Icon
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
 * A hierarchy read left to right, like the article hierarchy: broad nodes in the first column, each card as tall as the rows
 * beneath it, and leaves with their summary beside them. Subclasses place the cards; this class draws them.
 */
internal abstract class HierarchyColumnsPanel(protected val availableWidth: Int, protected val zoom: Double) : JPanel(null) {
    /** One shared width keeps the columns aligned; subclasses set it with [measureCardWidth] before placing cards. */
    protected var cardWidth = 0
    private var right = 0
    protected fun scaled(n: Int) = (JBUI.scale(n) * zoom).roundToInt().coerceAtLeast(1)
    protected fun font(base: Font) = base.deriveFont((base.size2D * zoom).toFloat())
    protected val help: Color get() = UIUtil.getContextHelpForeground()
    protected val step get() = cardWidth + scaled(COLUMN_GAP)

    init { isOpaque = false }

    /** Fits the widest name within bounds; longer names wrap. */
    protected fun measureCardWidth(names: List<String>): Int {
        val metrics = getFontMetrics(font(JBFont.label().asBold()))
        val widest = names.maxOfOrNull(metrics::stringWidth) ?: 0
        val chrome = scaled(RAIL_ACCENT + 2 * CARD_PAD + TOGGLE + 2 * INNER_GAP + DIFF_BUTTON) + 4
        return (widest + chrome).coerceIn(scaled(MIN_CARD_WIDTH), scaled(MAX_CARD_WIDTH))
    }

    protected fun showEmpty(message: String) {
        val empty = text(message, scaled(320), font(JBFont.label()), help)
        add(empty.apply { setBounds(0, 0, scaled(320), preferredSize.height) })
        preferredSize = Dimension(scaled(320), empty.height)
    }

    /** Sizes the panel to the placed rows, [bottom] being the end of the last one plus its gap. */
    protected fun finish(bottom: Int) {
        preferredSize = Dimension(maxOf(availableWidth, right), (bottom - scaled(ROW_GAP)).coerceAtLeast(0))
    }

    /** Text beside a leaf or folded card; it starts one column to the right and takes the rest of the width. */
    protected fun note(name: String, value: String, written: Boolean, column: Int, y: Int): JComponent {
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

    /**
     * A branch card: the [toggle] (or an empty gutter), an optional icon, the [title] and its file count. Without [select] the
     * card is a placeholder.
     */
    protected fun branchCard(
        name: String, title: String, family: String?, column: Int, files: Int, active: Boolean, tooltip: String,
        toggle: JComponent?, icon: Icon? = null, placeholder: Boolean = false, select: (() -> Unit)? = null,
    ): JComponent = JPanel(BorderLayout(scaled(INNER_GAP), 0)).apply {
        this.name = name
        isOpaque = true
        background = tint(family, column)
        border = cardBorder(family, active)
        toolTipText = tooltip
        val inner = cardWidth - scaled(RAIL_ACCENT + 2 * CARD_PAD + TOGGLE + INNER_GAP) - 4
        add(toggle ?: gutter(null), BorderLayout.WEST)
        add(JPanel(VerticalLayout(scaled(2))).apply {
            isOpaque = false
            val heading = text(title, inner - if (icon != null) icon.iconWidth + scaled(4) else 0,
                if (placeholder) italic(JBFont.label()) else font(JBFont.label().asBold()), if (placeholder) help else null)
            add(if (icon == null) heading else JPanel(BorderLayout(scaled(4), 0)).apply {
                isOpaque = false
                add(JLabel(icon).apply { verticalAlignment = SwingConstants.TOP }, BorderLayout.WEST)
                add(heading, BorderLayout.CENTER)
            })
            add(JLabel(if (files == 1) "1 file" else "$files files").apply { font = font(JBFont.small()); foreground = help })
        }, BorderLayout.CENTER)
        if (select == null) return@apply
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // Children cover most of the card, so each forwards clicks; the toggle keeps its own.
        val listener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) select() }
        }
        fun attach(component: Component) {
            if (component.name?.startsWith("toggle:") == true) return
            component.addMouseListener(listener)
            (component as? JComponent)?.toolTipText = tooltip
            if (component is Container) component.components.forEach(::attach)
        }
        attach(this)
    }

    /** Folding hides a node's children to the right, so the arrows point along the hierarchy as in the article view. */
    protected fun toggle(name: String, collapsed: Boolean, showTip: String, foldTip: String, toggle: () -> Unit): JComponent =
        gutter(if (collapsed) "›" else "‹").apply {
            this.name = name
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (collapsed) showTip else foldTip
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) toggle() }
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

    /**
     * A leaf: [title] opens the file's diff; the folder and status are dimmed beneath it, and the full path is in the tooltip.
     * Clicking elsewhere on the card calls [select] when given.
     */
    protected fun fileCard(
        file: ReviewFile, title: String, family: String?, column: Int, active: Boolean,
        openFile: (String) -> Unit, showDiffCard: (String) -> Unit, select: (() -> Unit)? = null,
    ): JComponent = JPanel(BorderLayout(scaled(INNER_GAP), 0)).apply {
        name = "file:${file.path}"
        isOpaque = true
        background = tint(family, column)
        border = cardBorder(family, active)
        toolTipText = file.path
        val fileName = file.path.substringAfterLast('/')
        add(JLabel(FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon ?: AllIcons.FileTypes.Any_type).apply {
            verticalAlignment = SwingConstants.TOP
            preferredSize = Dimension(scaled(TOGGLE), preferredSize.height)
        }, BorderLayout.WEST)
        val details = listOfNotNull(file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }, file.status,
            file.previousPath?.let { "renamed from $it" })
        val detailsLabel = JLabel(details.joinToString(" · ")).apply {
            font = font(JBFont.small()); foreground = help; toolTipText = file.path
            minimumSize = Dimension(0, preferredSize.height)
        }
        val center = JPanel(VerticalLayout(scaled(2))).apply {
            isOpaque = false
            add(ActionLink(title) { openFile(file.path) }.apply {
                font = font(JBFont.label()); toolTipText = "View changes in ${file.path}"
                minimumSize = Dimension(0, preferredSize.height)
            })
            add(detailsLabel)
        }
        add(center, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(InplaceButton(IconButton("Show diff on canvas", AllIcons.Actions.Diff, AllIcons.Actions.Diff)) { showDiffCard(file.path) }
                .apply { cursor = Cursor.getDefaultCursor(); toolTipText = "Show diff on canvas" }, BorderLayout.NORTH)
        }, BorderLayout.EAST)
        if (select == null) return@apply
        val listener = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) select() }
        }
        listOf(this, center, detailsLabel).forEach { it.addMouseListener(listener) }
    }

    /** A light wash of the family's hue that fades with depth, so a subsystem reads as one family. */
    private fun tint(family: String?, column: Int): Color {
        val base = UIUtil.getListBackground()
        return family?.let { ColorUtil.mix(base, ChangeTopicsPanel.accent(it), (0.16 - 0.03 * column).coerceAtLeast(0.05)) } ?: base
    }

    /** Both states keep the same insets, so selecting never shifts the text. */
    private fun cardBorder(family: String?, active: Boolean): Border? {
        val line = if (active) 2 else 1
        return JBUI.Borders.compound(
            MatteBorder(line, line, line, line, if (active) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()),
            MatteBorder(0, scaled(RAIL_ACCENT), 0, 0, family?.let(ChangeTopicsPanel::accent) ?: JBColor.border()),
            EmptyBorder(scaled(CARD_PAD) + 2 - line, scaled(CARD_PAD) + 2 - line, scaled(CARD_PAD) + 2 - line, scaled(CARD_PAD) + 2 - line),
        )
    }

    protected fun italic(base: Font) = font(base).deriveFont(font(base).style or Font.ITALIC)

    protected fun text(value: String, width: Int, font: Font, color: Color?): JComponent = WrappedText(value, width).apply {
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

        /** Unscaled width that fits [depth] branch columns, the leaf column and a readable summary. */
        fun defaultWidth(depth: Int): Int {
            val columns = maxOf(depth, 1) + 1
            return columns * (CARD_WIDTH + COLUMN_GAP) + SUMMARY_WIDTH
        }
    }
}
