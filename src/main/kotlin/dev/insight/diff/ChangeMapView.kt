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

    /** Per list entry, the parts of its detail the reader expanded or collapsed, by [ChangeDetail.keys]; the rest keep their defaults. */
    private val expanded = mutableMapOf<String, MutableMap<String, Boolean>>()

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
                    entry?.let(model::detail)?.let {
                        fillDetail(detail, it, { target -> list.selectedIndex = entries.indexOfFirst { e -> e.id == target } }) { show(entry) }
                    }
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

    /**
     * The selected change, read top-down: its summary, then its steps by role. Sections, fan-outs and steps expand on a
     * click; a collapsed step shows one line and the first sentence of its summary.
     */
    private fun fillDetail(panel: JPanel, detail: ChangeDetail, select: (String) -> Unit, refresh: () -> Unit) {
        val width = scaled(WIDTH - LIST_WIDTH - 4 * PADDING - 16)
        val state = expanded.getOrPut(detail.entry) { mutableMapOf() }
        panel.add(JLabel(detail.title).apply { name = "change-map-title"; font = scaledFont(JBFont.label().asBold().biggerOn(2f)) })
        panel.add(detail.summary?.let { text(it, width).apply { name = "change-map-summary" } }
            ?: note("Summaries are not written yet; run /change-map to write them.").apply { name = "change-map-summary-missing" })
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(8), 0)).apply {
            isOpaque = false
            add(small("${detail.rows.size} steps"))
            add(ActionLink("Expand all") { detail.keys.forEach { state[it] = true }; refresh() }.apply { name = "change-map-expand-all"; font = scaledFont(JBFont.small()) })
            add(ActionLink("Collapse all") { detail.keys.forEach { state[it] = false }; refresh() }.apply { name = "change-map-collapse-all"; font = scaledFont(JBFont.small()) })
        })
        detail.sections.forEach { section -> panel.add(sectionPanel(section, state, width, select)) }
    }

    private fun sectionPanel(section: StepSection, state: MutableMap<String, Boolean>, width: Int, select: (String) -> Unit): JComponent {
        val color = roleColor(section.title)
        val head = JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
            isOpaque = false
            add(JLabel("${section.title} (${section.rows.size})").apply { name = "change-map-section-title"; font = scaledFont(JBFont.label().asBold()); foreground = color })
            add(small(section.stats))
        }
        // Depth is shown relative to the section's shallowest step, so a section does not start indented.
        val base = section.rows.minOf { it.depth }
        return disclosure("change-map-section", section.key, state, section.open, head) {
            JPanel(VerticalLayout(scaled(6))).apply {
                isOpaque = false
                border = JBUI.Borders.compound(JBUI.Borders.customLineLeft(color), JBUI.Borders.emptyLeft(8))
                section.items.forEach { item ->
                    when (item) {
                        is SingleStep -> add(stepRow(item.row, (item.row.depth - base).coerceIn(0, MAX_INDENT), state, width - scaled(16), select))
                        is FanOut -> add(fanOut(item, state, width - scaled(16), select))
                    }
                }
            }
        }.apply { border = JBUI.Borders.emptyTop(4) }
    }

    private fun fanOut(fan: FanOut, state: MutableMap<String, Boolean>, width: Int, select: (String) -> Unit): JComponent {
        val head = JPanel(VerticalLayout(scaled(1))).apply {
            isOpaque = false
            add(JLabel(fan.title).apply { name = "change-map-fan-title"; font = scaledFont(JBFont.label().asBold()) })
            add(small(fan.stats + " · " + fan.rows.joinToString(", ") { it.name }.let { if (it.length > 140) it.take(140).substringBeforeLast(',') + ", …" else it }))
        }
        return disclosure("change-map-fan", fan.key, state, false, head) {
            JPanel(VerticalLayout(scaled(6))).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(16)
                fan.rows.forEach { add(stepRow(it, 0, state, width - scaled(16), select)) }
            }
        }
    }

    private fun stepRow(row: StepRow, indent: Int, state: MutableMap<String, Boolean>, width: Int, select: (String) -> Unit): JComponent {
        val textWidth = width - scaled(18) * indent - scaled(24)
        val head = JPanel(VerticalLayout(scaled(2))).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
                isOpaque = false
                add(JLabel(row.name).apply { name = "change-map-symbol"; font = scaledFont(JBFont.label().asBold()) })
                add(small(row.counts))
                add(ActionLink(row.location) { actions.openAtom(row.atom) }.apply {
                    name = "change-map-open"; font = scaledFont(JBFont.small()); toolTipText = "Open the diff of ${row.atom.path} at this change"
                })
            })
            row.gist?.let { add(text(it, textWidth).apply { name = "change-map-step-gist" }) }
        }
        return disclosure("change-map-step", row.key, state, false, head, open = { actions.openAtom(row.atom) }) {
            JPanel(VerticalLayout(scaled(3))).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(20)
                add(small(listOf(row.role, row.detail).joinToString(" · ")).apply { name = "change-map-role" })
                row.relations.forEach { add(small("↳ $it").apply { name = "change-map-relation" }) }
                row.more?.let { add(text(it, textWidth).apply { name = "change-map-step-summary" }) }
                if (row.links.isNotEmpty()) add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
                    isOpaque = false
                    add(small("also linked to:"))
                    row.links.forEach { link -> add(ActionLink(link.title) { select(link.entry) }.apply { name = "change-map-link"; font = scaledFont(JBFont.small()) }) }
                })
            }
        }.apply { border = JBUI.Borders.emptyLeft(18 * indent) }
    }

    /**
     * A [head] behind a chevron whose [body] is built when first expanded. A click on the head toggles it and keeps the
     * choice in [state] under [key]; a double click runs [open], if given. Links inside the head handle their own clicks.
     */
    private fun disclosure(name: String, key: String, state: MutableMap<String, Boolean>, default: Boolean, head: JComponent,
                           open: (() -> Unit)? = null, body: () -> JComponent): JPanel {
        val panel = JPanel(VerticalLayout(scaled(3))).apply { isOpaque = false; this.name = name }
        val chevron = JLabel().apply { this.name = "$name-toggle"; verticalAlignment = JLabel.TOP; border = JBUI.Borders.emptyTop(2) }
        var built: JComponent? = null
        fun apply(expand: Boolean) {
            chevron.icon = if (expand) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            if (expand && built == null) built = body().also(panel::add)
            built?.isVisible = expand
            panel.revalidate(); panel.repaint()
        }
        val line = JPanel(BorderLayout(scaled(4), 0)).apply {
            isOpaque = false
            add(chevron, BorderLayout.WEST)
            add(head, BorderLayout.CENTER)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        val toggle = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && open != null) { open(); return }
                if (e.clickCount != 1) return
                val next = !(state[key] ?: default)
                state[key] = next
                apply(next)
            }
        }
        // Labels and panels without listeners of their own pass clicks up to the line.
        line.addMouseListener(toggle)
        panel.add(line)
        apply(state[key] ?: default)
        return panel
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

    /** By a section's title. */
    private fun roleColor(role: String): Color = when (role) {
        "Origin" -> JBColor(Color(0x2E7D32), Color(0x81C784))
        "Follows" -> JBColor(Color(0x1565C0), Color(0x64B5F6))
        "Tests" -> JBColor(Color(0x8E24AA), Color(0xCE93D8))
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
        /** Steps deeper than this in a section are indented no further. */
        const val MAX_INDENT = 4
    }
}
