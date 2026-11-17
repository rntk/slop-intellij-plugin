package dev.insight.codereview.map

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
import dev.insight.review.ui.cloudHtml
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.Scrollable

/** What the code map card does: send /code-map, take the card off the canvas, and open a unit or member in the editor. */
internal data class CodeMapActions(
    val generate: () -> Unit = {},
    val remove: () -> Unit = {},
    val open: (path: String, line: Int?) -> Unit = { _, _ -> },
)

/**
 * The code map card: a list of flows beside the selected one read from its roots to its tests, with the shared code it
 * uses. A step expands to its members, each opening at its line. The selection is kept by entry id across rebuilds;
 * everything the card shows is decided by [CodeMapPresentation].
 */
internal class CodeMapView(private val actions: CodeMapActions) {
    var selected: String? = null
        private set
    private val expanded = mutableMapOf<String, MutableMap<String, Boolean>>()
    var softWrap = true
        private set
    var tagsCloud = false
        private set
    var refresh: () -> Unit = {}
    private val detailProse = mutableListOf<Prose>()
    private var detailWidth = scaled(DETAIL_WIDTH)

    fun card(content: CodeMapCard): JPanel {
        val model = CodeMapPresentation(content, tagsCloud)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(small(model.header + (if (content.running) " · working…" else model.progress?.let { " · $it" }.orEmpty())).apply {
                name = "code-map-header"
                toolTipText = "Units are classes, a file's top-level code, or whole files. Code finds how they use each other and groups them into " +
                    "flows read from their roots; widely used code is shared by the flows that use it. Titles, merge judgments and summaries are the assistant's."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                add(option("code-map-soft-wrap", "Soft-wrap", softWrap, "Wrap summaries at the width of the pane; off, they stay on one line") { softWrap = it; refresh() })
                add(option("code-map-tags-cloud", "Tags cloud", tagsCloud, "Size summary words by their importance among the summaries beneath them") { tagsCloud = it; refresh() })
                add(JButton(model.action.label).apply {
                    name = "code-map-action"
                    font = scaledFont(JBFont.small()); cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && !content.running
                    toolTipText = model.action.tooltip
                    addActionListener { actions.generate() }
                })
                add(InplaceButton(IconButton("Remove code map", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { actions.remove() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.WEST)
        }
        val top = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            model.rootSummary?.let { summary ->
                val root = Prose(summary, model.rootWeights, 2 * scaled(PADDING), wrap = true, inDetail = false).apply {
                    name = "code-map-root-summary"; border = JBUI.Borders.empty(8, PADDING, 0, PADDING)
                }
                root.fit(scaled(CARD_WIDTH))
                val holder = DetailPanel(true).apply { isOpaque = false; add(root) }
                add(object : JBScrollPane(holder) {
                    override fun getPreferredSize(): Dimension =
                        Dimension(0, minOf(holder.preferredSize.height + insets.top + insets.bottom, scaled(ROOT_SUMMARY_HEIGHT)))
                }.apply {
                    border = null; isOpaque = false; viewport.isOpaque = false
                    horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                    verticalScrollBar.unitIncrement = scaled(16)
                    viewport.addComponentListener(object : ComponentAdapter() {
                        override fun componentResized(e: ComponentEvent) { if (viewport.width > 0) { root.fit(viewport.width); revalidate() } }
                    })
                }, BorderLayout.CENTER)
            }
        }
        return JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground()
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))
            add(top, BorderLayout.NORTH)
            add(body(content, model), BorderLayout.CENTER)
        }
    }

    private fun body(content: CodeMapCard, model: CodeMapPresentation): JComponent {
        val panel = JPanel(BorderLayout(scaled(PADDING), 0)).apply { isOpaque = false; border = JBUI.Borders.empty(10, PADDING, 12, PADDING) }
        when {
            content.map == null -> panel.add(note("Map the code in scope: classes and files that use each other grouped into flows, each read from " +
                "its roots to the code it uses and its tests, with the shared code many flows rely on. Click Generate or type /code-map.")
                .apply { name = "code-map-empty" }, BorderLayout.NORTH)
            model.empty -> panel.add(note("No classes or files to map.").apply { name = "code-map-empty" }, BorderLayout.NORTH)
            else -> {
                val entries = model.entries
                selected = model.selection(selected)
                val detail = DetailPanel(softWrap).apply {
                    isOpaque = false; name = "code-map-detail"
                    addComponentListener(object : ComponentAdapter() {
                        override fun componentResized(e: ComponentEvent) { if (softWrap && width > 0) fitDetail(width) }
                    })
                }
                val list = JBList(DefaultListModel<FlowEntry>().apply { entries.forEach(::addElement) }).apply {
                    name = "code-map-list"
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
                panel.add(scroll(detail, scaled(PANE_WIDTH)), BorderLayout.CENTER)
            }
        }
        return panel
    }

    private fun fillDetail(panel: JPanel, detail: FlowDetail, select: (String) -> Unit, refresh: () -> Unit) {
        detailProse.clear()
        val state = expanded.getOrPut(detail.entry) { mutableMapOf() }
        panel.add(JLabel(detail.title).apply { name = "code-map-title"; font = scaledFont(JBFont.label().asBold().biggerOn(2f)) })
        panel.add(detail.summary?.let { Prose(it, detail.weights, 0).apply { name = "code-map-summary" } }
            ?: note("Summaries are not written yet; run /code-map to write them.").apply { name = "code-map-summary-missing" })
        panel.add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(8), 0)).apply {
            isOpaque = false
            add(small("${detail.rows.size} units"))
            add(ActionLink("Expand all") { detail.keys.forEach { state[it] = true }; refresh() }.apply { name = "code-map-expand-all"; font = scaledFont(JBFont.small()) })
            add(ActionLink("Collapse all") { detail.keys.forEach { state[it] = false }; refresh() }.apply { name = "code-map-collapse-all"; font = scaledFont(JBFont.small()) })
        })
        detail.sections.forEach { section -> panel.add(sectionPanel(section, state, select)) }
        if (detail.shared.isNotEmpty()) panel.add(sharedPanel(detail.shared, state, select))
        if (detail.sharers.isNotEmpty()) panel.add(JPanel(VerticalLayout(scaled(3))).apply {
            isOpaque = false; name = "code-map-sharers"
            add(JLabel("Shared by (${detail.sharers.size})").apply { font = scaledFont(JBFont.label().asBold()); foreground = SHARED_COLOR })
            detail.sharers.forEach { link -> add(ActionLink(link.title) { select(link.entry) }.apply { name = "code-map-sharer"; font = scaledFont(JBFont.small()) }) }
        })
    }

    private fun sectionPanel(section: UnitSection, state: MutableMap<String, Boolean>, select: (String) -> Unit): JComponent {
        val color = roleColor(section.title)
        val head = JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
            isOpaque = false
            add(JLabel("${section.title} (${section.rows.size})").apply { name = "code-map-section-title"; font = scaledFont(JBFont.label().asBold()); foreground = color })
            add(small(section.stats))
        }
        val base = section.rows.minOf { it.depth }
        return disclosure("code-map-section", section.key, state, section.open, head) {
            JPanel(VerticalLayout(scaled(6))).apply {
                isOpaque = false
                border = JBUI.Borders.compound(JBUI.Borders.customLineLeft(color), JBUI.Borders.emptyLeft(8))
                section.items.forEach { item ->
                    when (item) {
                        is SingleUnit -> add(unitRow(item.row, (item.row.depth - base).coerceIn(0, MAX_INDENT), state, scaled(16), select))
                        is UnitFan -> add(fanOut(item, state, select))
                    }
                }
            }
        }.apply { border = JBUI.Borders.emptyTop(4) }
    }

    /** The shared code of other flows that this flow uses; each opens its flow. */
    private fun sharedPanel(shared: List<SharedRow>, state: MutableMap<String, Boolean>, select: (String) -> Unit): JComponent {
        val head = JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
            isOpaque = false
            add(JLabel("Shared (${shared.size})").apply { name = "code-map-section-title"; font = scaledFont(JBFont.label().asBold()); foreground = SHARED_COLOR })
            add(small("widely used code this flow relies on"))
        }
        return disclosure("code-map-shared", "section:SHARED", state, shared.size <= FLOW_SECTION_OPEN_MAX, head) {
            JPanel(VerticalLayout(scaled(4))).apply {
                isOpaque = false
                border = JBUI.Borders.compound(JBUI.Borders.customLineLeft(SHARED_COLOR), JBUI.Borders.emptyLeft(8))
                shared.forEach { row ->
                    add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
                        isOpaque = false; name = "code-map-shared-row"
                        add(JLabel(displayName(row.unit)).apply { font = scaledFont(JBFont.label().asBold()) })
                        add(ActionLink(unitLocation(row.unit)) { actions.open(row.unit.path, row.unit.lines?.first) }.apply { font = scaledFont(JBFont.small()) })
                        row.owner?.let { owner -> add(ActionLink(owner.title) { select(owner.entry) }.apply { name = "code-map-shared-flow"; font = scaledFont(JBFont.small()) }) }
                        if (row.users.isNotEmpty()) add(small("used by " + row.users.joinToString(", ")))
                    })
                }
            }
        }.apply { border = JBUI.Borders.emptyTop(4) }
    }

    private fun fanOut(fan: UnitFan, state: MutableMap<String, Boolean>, select: (String) -> Unit): JComponent {
        val head = JPanel(VerticalLayout(scaled(1))).apply {
            isOpaque = false
            add(JLabel(fan.title).apply { name = "code-map-fan-title"; font = scaledFont(JBFont.label().asBold()) })
            add(small(fan.stats + " · " + fan.rows.joinToString(", ") { it.name }.let { if (it.length > 140) it.take(140).substringBeforeLast(',') + ", …" else it }))
        }
        return disclosure("code-map-fan", fan.key, state, false, head) {
            JPanel(VerticalLayout(scaled(6))).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(16)
                fan.rows.forEach { add(unitRow(it, 0, state, scaled(32), select)) }
            }
        }
    }

    private fun unitRow(row: UnitRow, indent: Int, state: MutableMap<String, Boolean>, inset: Int, select: (String) -> Unit): JComponent {
        val textInset = inset + scaled(18) * indent + scaled(24)
        val open = { actions.open(row.unit.path, row.unit.lines?.first) }
        val head = JPanel(VerticalLayout(scaled(2))).apply {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
                isOpaque = false
                add(JLabel(row.name).apply { name = "code-map-unit"; font = scaledFont(JBFont.label().asBold()) })
                add(small(row.size))
                add(ActionLink(row.location) { open() }.apply { name = "code-map-open"; font = scaledFont(JBFont.small()); toolTipText = "Open ${row.unit.path}" })
            })
            row.gist?.let { add(Prose(it, row.weights, textInset).apply { name = "code-map-step-gist" }) }
        }
        return disclosure("code-map-step", row.key, state, false, head, open = open) {
            JPanel(VerticalLayout(scaled(3))).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(20)
                add(small(listOf(row.role, row.detail).joinToString(" · ")).apply { name = "code-map-role" })
                row.relations.forEach { add(small("↳ $it").apply { name = "code-map-relation" }) }
                row.more?.let { add(Prose(it, row.weights, textInset).apply { name = "code-map-step-summary" }) }
                if (row.members.isNotEmpty()) add(membersPanel(row, state))
                if (row.links.isNotEmpty()) add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
                    isOpaque = false
                    add(small("also linked to:"))
                    row.links.forEach { link -> add(ActionLink(link.title) { select(link.entry) }.apply { name = "code-map-link"; font = scaledFont(JBFont.small()) }) }
                })
            }
        }.apply { border = JBUI.Borders.emptyLeft(18 * indent) }
    }

    /** The drill-down of a unit: its members, each opening at its line, with who uses it and what it uses. */
    private fun membersPanel(row: UnitRow, state: MutableMap<String, Boolean>): JComponent {
        val head = JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
            isOpaque = false
            add(JLabel("Members (${row.members.size})").apply { name = "code-map-members-title"; font = scaledFont(JBFont.small().asBold()) })
        }
        return disclosure("code-map-members", "members:${row.unit.id}", state, row.members.size <= MEMBERS_OPEN_MAX, head) {
            JPanel(VerticalLayout(scaled(2))).apply {
                isOpaque = false
                border = JBUI.Borders.emptyLeft(12)
                row.members.forEach { member ->
                    add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
                        isOpaque = false; name = "code-map-member"
                        add(ActionLink(member.member.name.substringAfter('.').ifEmpty { member.member.name } + member.location) {
                            actions.open(row.unit.path, member.member.lines.first)
                        }.apply { name = "code-map-member-open"; font = scaledFont(JBFont.small()); toolTipText = member.member.header })
                        add(small(member.member.kind))
                        if (member.usedBy.isNotEmpty()) add(small("used by " + member.usedBy.joinToString(", ")))
                        if (member.uses.isNotEmpty()) add(small("uses " + member.uses.joinToString(", ")))
                    })
                }
            }
        }
    }

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
        line.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && open != null) { open(); return }
                if (e.clickCount != 1) return
                val next = !(state[key] ?: default)
                state[key] = next
                apply(next)
            }
        })
        panel.add(line)
        apply(state[key] ?: default)
        return panel
    }

    private inner class EntryRenderer : ListCellRenderer<FlowEntry> {
        override fun getListCellRendererComponent(list: JList<out FlowEntry>, value: FlowEntry, index: Int, selected: Boolean, focused: Boolean): Component =
            JPanel(VerticalLayout(scaled(2))).apply {
                background = if (selected) UIUtil.getListSelectionBackground(focused) else UIUtil.getListBackground()
                border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 8))
                val foreground = if (selected) UIUtil.getListSelectionForeground(focused) else UIUtil.getLabelForeground()
                add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(6), 0)).apply {
                    isOpaque = false
                    add(JLabel(value.title).apply { font = scaledFont(JBFont.label().asBold()); this.foreground = foreground })
                    if (value.entry) add(badge("entry", "Its root is used by nothing else in scope: a likely entry point"))
                    if (value.foundation) add(badge("shared", "Widely used code that other flows share instead of joining"))
                })
                add(small(value.stats).apply { if (selected) this.foreground = foreground })
            }

        private fun badge(text: String, tooltip: String) = small(text).apply {
            toolTipText = tooltip
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(0, 4))
        }
    }

    private fun roleColor(role: String): Color = when (role) {
        "Roots" -> JBColor(Color(0x2E7D32), Color(0x81C784))
        "Uses" -> JBColor(Color(0x1565C0), Color(0x64B5F6))
        "Tests" -> JBColor(Color(0x8E24AA), Color(0xCE93D8))
        "Docs" -> JBColor(Color(0x6D4C41), Color(0xBCAAA4))
        else -> JBColor.GRAY
    }

    private fun fitDetail(width: Int) {
        if (width == detailWidth) return
        detailWidth = width
        detailProse.forEach { it.fit(width) }
    }

    private class DetailPanel(private val soft: Boolean) : JPanel(VerticalLayout(JBUI.scale(8))), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) =
            if (orientation == javax.swing.SwingConstants.VERTICAL) visibleRect.height else visibleRect.width
        override fun getScrollableTracksViewportWidth() = soft
        override fun getScrollableTracksViewportHeight() = false
    }

    /** Summary prose as an HTML label that wraps at the pane's width less [inset]; with the tags cloud on, weighted words are larger. */
    private inner class Prose(private val value: String, private val weights: Map<String, Double>, private val inset: Int,
                              private val wrap: Boolean = softWrap, private val inDetail: Boolean = true) : JLabel() {
        private var width = detailWidth

        init {
            font = scaledFont(JBFont.label())
            render()
            if (inDetail && wrap) detailProse += this
        }

        fun fit(total: Int) { if (total == width) return; width = total; render() }

        override fun getPreferredSize(): Dimension = super.getPreferredSize().let { if (inDetail) it else Dimension(0, it.height) }

        private fun render() {
            val body = (cloudHtml(value, weights, font.size2D) ?: escape(value)).replace("\n", "<br>")
            text = if (wrap) "<html><div style='width:${(width - inset).coerceAtLeast(scaled(80))}px'>$body</div></html>" else "<html><nobr>$body</nobr></html>"
        }
    }

    private fun scroll(view: JComponent, width: Int) = JBScrollPane(view).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1); isOpaque = false; viewport.isOpaque = false
        preferredSize = Dimension(width, scaled(HEIGHT))
        verticalScrollBar.unitIncrement = scaled(16)
    }

    private fun small(text: String) = JLabel(text).apply { font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() }
    private fun note(text: String) = JLabel("<html>${escape(text)}</html>").apply { font = scaledFont(JBFont.label()); foreground = UIUtil.getContextHelpForeground() }
    private fun option(name: String, label: String, selected: Boolean, tooltip: String, change: (Boolean) -> Unit) = JCheckBox(label, selected).apply {
        this.name = name; isOpaque = false; font = scaledFont(JBFont.small()); cursor = Cursor.getDefaultCursor(); toolTipText = tooltip
        addActionListener { change(isSelected) }
    }
    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    private fun scaled(value: Int) = zoomScaled(value, 1.0)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(FONT_SCALE)

    private companion object {
        val SHARED_COLOR = JBColor(Color(0xEF6C00), Color(0xFFB74D))
        const val PADDING = 12
        const val PANE_WIDTH = 720
        const val CARD_WIDTH = 440 + PANE_WIDTH + 3 * PADDING
        const val LIST_WIDTH = 440
        const val DETAIL_WIDTH = PANE_WIDTH - 16
        const val FONT_SCALE = 1.25
        const val HEIGHT = 720
        const val ROOT_SUMMARY_HEIGHT = 150
        const val MAX_INDENT = 4
        /** A unit with more members than this lists them collapsed. */
        const val MEMBERS_OPEN_MAX = 12
    }
}
