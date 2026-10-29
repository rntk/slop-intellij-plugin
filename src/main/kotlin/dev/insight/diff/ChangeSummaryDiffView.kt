package dev.insight.diff

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import dev.insight.review.ui.ChangeSummariesViewState
import dev.insight.review.ui.ChangeSummaryActions
import dev.insight.review.ui.SummariesCard
import dev.insight.review.ui.SummaryDiffView
import dev.insight.review.ui.summaryEntryMenu
import dev.insight.review.ui.summaryNodeIcon
import dev.insight.review.ui.summaryNodeTitle

/** A summary tree beside native diff viewers: one for a selected file, or each matching file of a selected directory in turn. */
internal class ChangeSummaryDiffPanel(
    private val card: SummariesCard,
    private val state: ChangeSummariesViewState,
    private val zoom: Double,
    private val actions: ChangeSummaryActions,
    private val project: Project? = null,
) : SummaryDiffView {
    private val tree = card.summaries.tree
    private fun scaled(value: Int) = zoomScaled(value, zoom)
    private val visible = linkedMapOf<Int, SummaryNode>()
    private val treeRows = JPanel(VerticalLayout(0)).apply {
        name = "summary-diff-tree"
        background = UIUtil.getListBackground()
    }
    private val heading = JPanel(BorderLayout())
    private val content = JPanel(BorderLayout()).apply {
        name = "summary-diff-content"
        background = UIUtil.getListBackground()
        add(heading, BorderLayout.NORTH)
    }
    /** Viewers by file path; a directory's diffs are created as they scroll into view, so this may cover only some of them. */
    private val editors = linkedMapOf<String, DiffFileEditor>()
    internal val viewers: List<DiffFileEditor> get() = editors.values.toList()
    private var shown: List<ReviewFile>? = null

    init {
        fun collect(node: SummaryNode) {
            if (!state.shows(node, card.summaries) || node.kind == SummaryKind.CHUNK) return
            visible[node.id] = node
            if (node.file == null) tree.shownChildren(node).forEach(::collect)
        }
        collect(tree.root)
        val initial = visible.values.firstOrNull { it.identity == state.selected }
            ?: visible.values.firstOrNull { it.file is ReviewFile }
        if (initial != null) select(initial) else {
            content.add(JLabel("No matching file diffs", SwingConstants.CENTER), BorderLayout.CENTER)
            rebuildTree()
        }
    }

    /** The matching file nodes under [node] in tree order. */
    private fun fileNodes(node: SummaryNode): List<SummaryNode> {
        if (node.id !in visible) return emptyList()
        if (node.file is ReviewFile) return listOf(node)
        return tree.shownChildren(node).flatMap(::fileNodes)
    }

    private fun select(node: SummaryNode) {
        state.selected = node.identity
        // A directory, or All changes at the root, shows each matching file's diff one after another.
        val nodes = fileNodes(node)
        val files = nodes.map { it.file as ReviewFile }
        if (files != shown) {
            shown = files
            val keep = files.map { it.path }.toSet()
            editors.keys.filter { it !in keep }.forEach { path -> editors.remove(path)?.let(Disposer::dispose) }
            content.components.filter { it !== heading }.forEach(content::remove)
            when {
                nodes.size == 1 -> content.add(editor(files.single()), BorderLayout.CENTER)
                nodes.isNotEmpty() -> content.add(stacked(nodes), BorderLayout.CENTER)
            }
        }
        heading.removeAll()
        if (nodes.isNotEmpty()) {
            // A directory's heading names it and counts its files; each file below has its own heading.
            val entry = if (nodes.size == 1) nodes.single() else node
            heading.add(JLabel(if (nodes.size == 1) entry.path else "${summaryNodeTitle(entry, tree)} · ${nodes.size} files",
                summaryNodeIcon(entry), JLabel.LEADING).apply {
                name = "summary-diff:${entry.identity}"
                font = JBFont.label().asBold().zoomed(zoom)
                border = JBUI.Borders.empty(6, 8)
                installMenu(this, entry)
            }, BorderLayout.CENTER)
        }
        rebuildTree()
        content.revalidate()
        content.repaint()
    }

    private fun editor(file: ReviewFile): DiffFileEditor = editors.getOrPut(file.path) {
        DiffFileEditor(file, project).apply {
            name = "summary-diff-viewer:${file.path}"
            minimumSize = Dimension(0, 0)
        }
    }

    /** Every file's diff one after another, each under its own heading; native viewers are built only near the viewport. */
    private fun stacked(nodes: List<SummaryNode>): JComponent {
        val slots = nodes.map { node ->
            val file = node.file as ReviewFile
            node to JPanel(BorderLayout()).apply {
                name = "summary-diff-slot:${file.path}"
                background = UIUtil.getListBackground()
                preferredSize = Dimension(0, DiffFileEditor.heightFor(file, zoom))
            }
        }
        val list = JPanel(VerticalLayout(scaled(8))).apply {
            name = "summary-diff-files"
            background = UIUtil.getListBackground()
            border = JBUI.Borders.emptyBottom(8)
            slots.forEach { (node, slot) ->
                add(JLabel(node.path, summaryNodeIcon(node), JLabel.LEADING).apply {
                    name = "summary-diff:${node.identity}"
                    font = JBFont.label().asBold().zoomed(zoom)
                    border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
                        JBUI.Borders.empty(6, 8))
                    installMenu(this, node)
                })
                add(slot)
            }
        }
        val scroll = JBScrollPane(list).apply {
            name = "summary-diff-scroll"
            border = null
            verticalScrollBar.unitIncrement = scaled(24)
        }
        fun fill(slot: JPanel, file: ReviewFile) {
            if (slot.componentCount > 0) return
            slot.add(editor(file).also { it.resizeFor(0, zoom) }, BorderLayout.CENTER)
            slot.preferredSize = null
            slot.revalidate()
        }
        fun reveal() {
            val view = scroll.viewport.viewRect.apply { grow(0, height) }
            slots.forEach { (node, slot) -> if (slot.bounds.intersects(view)) fill(slot, node.file as ReviewFile) }
        }
        // Before the first layout nothing is measured, so the leading diffs are built up front.
        slots.take(EAGER_DIFFS).forEach { (node, slot) -> fill(slot, node.file as ReviewFile) }
        scroll.viewport.addChangeListener { reveal() }
        return scroll
    }

    private fun rebuildTree() {
        treeRows.removeAll()
        fun add(node: SummaryNode, depth: Int) {
            if (node.id !in visible) return
            val branch = node.file == null && node.children.isNotEmpty()
            val open = branch && state.isOpen(node, card.summaries)
            val row = JPanel(BorderLayout()).apply {
                name = "summary-diff-tree:${node.identity}"
                background = if (state.selected == node.identity) UIUtil.getListSelectionBackground(false) else UIUtil.getListBackground()
                border = JBUI.Borders.empty(6, 4 + depth * 16, 6, 8)
                add(JLabel(if (branch) if (open) "▾" else "▸" else " ").apply {
                    name = "summary-diff-toggle:${node.identity}"
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (branch && SwingUtilities.isLeftMouseButton(e)) { state.toggle(node.identity); rebuildTree() }
                        }
                    })
                }, BorderLayout.WEST)
                val label = JLabel(summaryNodeTitle(node, tree), summaryNodeIcon(node), JLabel.LEADING).apply {
                    font = JBFont.label().zoomed(zoom)
                    toolTipText = node.location
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
                val navigate = object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(e)) return
                        select(node)
                    }
                }
                label.addMouseListener(navigate)
                addMouseListener(navigate)
                add(label, BorderLayout.CENTER)
                actions.review?.control(node, JBFont.small().zoomed(zoom))?.let {
                    (it as? AbstractButton)?.text = null
                    add(it, BorderLayout.EAST)
                }
            }
            installMenu(row, node)
            treeRows.add(row)
            if (open) tree.shownChildren(node).forEach { add(it, depth + 1) }
        }
        add(tree.root, 0)
        treeRows.revalidate()
        treeRows.doLayout()
        treeRows.repaint()
    }

    /** Summary actions stay on the tree and heading; editor menus belong to the IDE. */
    private fun installMenu(component: JComponent, node: SummaryNode) {
        component.componentPopupMenu = summaryEntryMenu(
            node, card, state, actions, ReviewVocabulary.DIFF, "summary-diff-action",
            canFold = node.file == null && node.children.isNotEmpty(),
            toggle = { identity -> state.toggle(identity); rebuildTree() },
        )
        fun inherit(parent: Container) {
            parent.components.forEach { child ->
                (child as? JComponent)?.inheritsPopupMenu = true
                if (child is Container) inherit(child)
            }
        }
        inherit(component)
    }

    override fun withTree(width: Int, height: Int, treeWidth: Int?, rememberTreeWidth: (Int) -> Unit): JSplitPane =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(treeRows).apply {
            name = "summary-diff-tree-scroll"
            minimumSize = Dimension(scaled(120), 0)
        }, content).apply {
            name = "summary-diff-panels"
            border = null
            preferredSize = Dimension(width, height)
            isContinuousLayout = true
            dividerLocation = treeWidth ?: (width * 0.3).toInt()
            addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
                if (dividerLocation > 0) rememberTreeWidth(dividerLocation)
            }
        }

    override fun dispose() {
        editors.values.forEach(Disposer::dispose)
        editors.clear()
    }

    private companion object {
        const val EAGER_DIFFS = 3
    }
}
