package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.diff.atoms.ChangeAtom
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/** What the change map card does: send /change-map, take the card off the canvas, and open an atom's lines in its diff. */
internal data class ChangeMapActions(
    val generate: () -> Unit = {},
    val remove: () -> Unit = {},
    val openAtom: (ChangeAtom) -> Unit = {},
)

/**
 * The change map card: a list of logical changes beside the selected one read as a chain from origin to tests. The
 * selection is kept by entry id across rebuilds; everything the card shows is decided by [ChangeMapPresentation].
 */
internal class ChangeMapView(private val actions: ChangeMapActions) {
    /** The selected list entry: a cluster id or [STANDALONE_ENTRY]. */
    var selected: String? = null
        private set

    fun card(content: ChangeMapCard): JPanel {
        val model = ChangeMapPresentation(content)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(small(model.header + (if (content.running) " · working…" else model.progress?.let { " · $it" }.orEmpty())).apply {
                name = "change-map-header"
                toolTipText = "Atoms are changed declarations, a file's top-level code, or whole files. Code finds how they relate and orders " +
                    "each logical change from its origin to the code that follows it and its tests; titles, merge judgments and summaries are the assistant's."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                add(JButton(model.action.label).apply {
                    name = "change-map-action"
                    font = scaledFont(JBFont.small()); cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && !content.running
                    toolTipText = model.action.tooltip
                    addActionListener { actions.generate() }
                })
                add(InplaceButton(IconButton("Remove change map", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { actions.remove() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.WEST)
        }
        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            model.rootSummary?.let { add(text(it, scaled(WIDTH - 2 * PADDING)).apply {
                name = "change-map-root-summary"; border = JBUI.Borders.empty(8, PADDING, 0, PADDING)
            }, BorderLayout.CENTER) }
        }
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))
            add(top, BorderLayout.NORTH)
            add(body(content, model), BorderLayout.CENTER)
        }
    }

    private fun body(content: ChangeMapCard, model: ChangeMapPresentation): JComponent {
        val panel = JPanel(BorderLayout(scaled(PADDING), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
        }
        when {
            content.map == null -> panel.add(note("Build a map of the change: related declarations across files grouped into logical changes, " +
                "each ordered from where it starts to what follows it and its tests. Click Generate or type /change-map.").apply { name = "change-map-empty" }, BorderLayout.NORTH)
            model.empty -> panel.add(note("No changed declarations found.").apply { name = "change-map-empty" }, BorderLayout.NORTH)
            else -> {
                val entries = model.entries
                selected = model.selection(selected)
                val detail = JPanel(VerticalLayout(scaled(8))).apply { isOpaque = false; name = "change-map-detail" }
                val list = JBList(DefaultListModel<ChangeEntry>().apply { entries.forEach(::addElement) }).apply {
                    name = "change-map-list"
                    selectionMode = ListSelectionModel.SINGLE_SELECTION
                    cellRenderer = EntryRenderer()
                    selectedIndex = entries.indexOfFirst { it.id == selected }
                }
                fun show(entry: String?) {
                    selected = entry
                    detail.removeAll()
                    entry?.let(model::detail)?.let { fillDetail(detail, it) { target -> list.selectedIndex = entries.indexOfFirst { e -> e.id == target } } }
                    detail.revalidate(); detail.repaint()
                }
                list.addListSelectionListener { if (!it.valueIsAdjusting) list.selectedValue?.let { entry -> if (entry.id != selected || detail.componentCount == 0) show(entry.id) } }
                show(selected)
                panel.add(scroll(list, scaled(LIST_WIDTH)), BorderLayout.WEST)
                panel.add(scroll(detail, scaled(WIDTH - LIST_WIDTH - 3 * PADDING)), BorderLayout.CENTER)
            }
        }
        return panel
    }

    private fun fillDetail(panel: JPanel, detail: ChangeDetail, select: (String) -> Unit) {
        val width = scaled(WIDTH - LIST_WIDTH - 4 * PADDING - 16)
        panel.add(JLabel(detail.title).apply { name = "change-map-title"; font = scaledFont(JBFont.label().asBold().biggerOn(2f)) })
        panel.add(detail.summary?.let { text(it, width).apply { name = "change-map-summary" } }
            ?: note("Summaries are not written yet; run /change-map to write them.").apply { name = "change-map-summary-missing" })
        detail.rows.forEach { panel.add(stepRow(it, width, select)) }
    }

    private fun stepRow(row: StepRow, width: Int, select: (String) -> Unit): JComponent = JPanel(VerticalLayout(scaled(3))).apply {
        name = "change-map-step"
        isOpaque = false
        border = JBUI.Borders.compound(JBUI.Borders.emptyLeft(18 * row.depth),
            JBUI.Borders.compound(JBUI.Borders.customLineLeft(roleColor(row.role)), JBUI.Borders.empty(2, 8, 2, 0)))
        add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
            isOpaque = false
            add(JLabel(row.role).apply {
                name = "change-map-role"
                font = scaledFont(JBFont.small().asBold()); foreground = roleColor(row.role)
                border = JBUI.Borders.compound(JBUI.Borders.customLine(roleColor(row.role), 1), JBUI.Borders.empty(0, 4))
            })
            add(JLabel(row.name).apply { name = "change-map-symbol"; font = scaledFont(JBFont.label().asBold()) })
            add(small(row.detail))
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
            isOpaque = false
            add(ActionLink(row.location) { actions.openAtom(row.atom) }.apply {
                name = "change-map-open"; font = scaledFont(JBFont.small()); toolTipText = "Open the diff of ${row.atom.path} at this change"
            })
            add(small(row.counts))
        })
        row.relations.forEach { add(small("↳ $it").apply { name = "change-map-relation" }) }
        row.summary?.let { add(text(it, width - scaled(18) * row.depth).apply { name = "change-map-step-summary" }) }
        if (row.links.isNotEmpty()) add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
            isOpaque = false
            add(small("also linked to:"))
            row.links.forEach { link -> add(ActionLink(link.title) { select(link.entry) }.apply { name = "change-map-link"; font = scaledFont(JBFont.small()) }) }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 1) actions.openAtom(row.atom) }
        })
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private inner class EntryRenderer : ListCellRenderer<ChangeEntry> {
        override fun getListCellRendererComponent(list: JList<out ChangeEntry>, value: ChangeEntry, index: Int, selected: Boolean, focused: Boolean): Component =
            JPanel(VerticalLayout(scaled(2))).apply {
                background = if (selected) UIUtil.getListSelectionBackground(focused) else UIUtil.getListBackground()
                border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 8))
                val foreground = if (selected) UIUtil.getListSelectionForeground(focused) else UIUtil.getLabelForeground()
                add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
                    isOpaque = false
                    add(JLabel(value.title).apply { font = scaledFont(JBFont.label().asBold()); this.foreground = foreground })
                    if (value.foundation) add(small("foundation").apply {
                        toolTipText = "A widely used change, kept on its own so it does not join unrelated changes"
                        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(0, 4))
                    })
                })
                add(small(value.stats).apply { if (selected) this.foreground = foreground })
            }
    }

    private fun roleColor(role: String): Color = when (role) {
        "Origin" -> JBColor(Color(0x2E7D32), Color(0x81C784))
        "Follows" -> JBColor(Color(0x1565C0), Color(0x64B5F6))
        "Test" -> JBColor(Color(0x8E24AA), Color(0xCE93D8))
        "Docs" -> JBColor(Color(0x6D4C41), Color(0xBCAAA4))
        else -> JBColor.GRAY
    }

    private fun scroll(view: JComponent, width: Int) = JBScrollPane(view).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1); isOpaque = false; viewport.isOpaque = false
        preferredSize = Dimension(width, scaled(HEIGHT))
        verticalScrollBar.unitIncrement = scaled(16)
    }

    private fun small(text: String) = JLabel(text).apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() }
    private fun note(text: String) = JLabel("<html>${escape(text)}</html>").apply { foreground = UIUtil.getContextHelpForeground() }
    /** Summary prose wraps at [width], like the other cards' summaries. */
    private fun text(value: String, width: Int) = JLabel("<html><div style='width:${width}px'>${escape(value)}</div></html>").apply {
        font = scaledFont(JBFont.label())
    }
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    private fun scaled(value: Int) = zoomScaled(value, 1.0)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(1.0)

    private companion object {
        const val PADDING = 12
        const val WIDTH = 1300
        const val LIST_WIDTH = 380
        const val HEIGHT = 720
    }
}
