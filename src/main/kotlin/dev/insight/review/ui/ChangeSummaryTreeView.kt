package dev.insight.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.xml.util.XmlStringUtil
import dev.insight.diff.DiffFileEditor
import dev.insight.diff.ReviewFile
import dev.insight.ui.zoomed
import java.awt.*
import java.awt.geom.CubicCurve2D
import javax.swing.*

/** Left-to-right claim tree, with native diff viewers unfolded at file leaves. */
internal class ChangeSummaryTreePanel(
    private val card: SummariesCard,
    private val state: ChangeSummariesViewState,
    private val zoom: Double,
    private val actions: ChangeSummaryActions,
    private val project: Project?,
    private val vocabulary: dev.insight.review.ReviewVocabulary = dev.insight.review.ReviewVocabulary.DIFF,
) : JPanel(null), Disposable {
    private val root = summaryLinkTree(card.summaries) { state.shows(it, card.summaries) }
    private val editors = mutableListOf<DiffFileEditor>()
    private data class Edge(val from: Rectangle, val to: Rectangle, val child: String)
    private class Entry(val node: SummaryLinkNode, val label: JButton, val folded: Boolean)

    private val edges = mutableListOf<Edge>()
    private val entries = mutableListOf<Entry>()
    private var reviewed = emptySet<String>()
    init {
        name = "summary-link-tree"; background = UIUtil.getListBackground(); rebuild()
        installReviewPresentationUpdate(this) { restyle(); repaint() }
    }

    /** Reviewed parts and branches dim; the marks are read live, so the tree keeps its folds and viewport. */
    private fun restyle() {
        val review = actions.review
        reviewed = if (review == null) emptySet() else root.reviewedKeys(review::isReviewed)
        entries.forEach { style(it.label, it.node, it.folded) }
    }

    private fun style(label: JButton, node: SummaryLinkNode, folded: Boolean) {
        val dimmed = node.key in reviewed
        val help = UIUtil.getContextHelpForeground()
        label.foreground = if (dimmed) help else UIUtil.getLabelForeground()
        val marker = if (node.children.isNotEmpty() || node.file) if (folded) "▸ " else "▾ " else ""
        val directory = node.directory().let { if (it.length > MAX_DIRECTORY) "…" + it.takeLast(MAX_DIRECTORY - 1) else it }
        if (directory.isEmpty() && node.text.length <= 60) { label.text = marker + node.text; return }
        val width = if (node.text.length > 60) " style='width:300px'" else ""
        val below = if (directory.isEmpty()) "" else "<br><small><font color='" + ColorUtil.toHtmlColor(help) + "'>" +
            XmlStringUtil.escapeString(directory) + "</font></small>"
        label.text = "<html><div" + width + ">" + marker + XmlStringUtil.escapeString(node.text) + below + "</div></html>"
    }

    private fun rebuild() {
        editors.forEach(Disposer::dispose); editors.clear()
        removeAll(); edges.clear(); entries.clear()
        var bottom = 0
        var right = 0
        fun place(node: SummaryLinkNode, x: Int, top: Int): Pair<Rectangle, Int> {
            val folded = if (node.file) node.key !in state.treeOpenedFiles else node.key in state.treeFolded
            val label = JButton().apply {
                name = "summary-tree:${node.key}"
                font = UIUtil.getLabelFont().zoomed(zoom)
                horizontalAlignment = SwingConstants.LEFT
                isContentAreaFilled = false
                isBorderPainted = false
                toolTipText = node.owner.location + if (node.text.isEmpty()) "" else " — " + node.text
                addActionListener {
                    if (node.file && node.owner.file !is ReviewFile) {
                        actions.showNode?.invoke(node.owner)
                        return@addActionListener
                    }
                    val set = if (node.file) state.treeOpenedFiles else state.treeFolded
                    if (!set.add(node.key)) set.remove(node.key)
                    rebuild()
                }
            }
            entries += Entry(node, label, folded)
            style(label, node, folded)
            label.componentPopupMenu = summaryEntryMenu(node.owner, card, state, actions, vocabulary, "summary-tree-action", canFold = false)
            val width = if (node.text.isEmpty()) 20 else minOf(440, maxOf(110, label.preferredSize.width))
            val height = maxOf(30, label.preferredSize.height)
            var y = top
            val children = if (folded) emptyList() else node.children
            val childRects = children.map { child ->
                val (rect, end) = place(child, x + width + 50, y)
                y = end + 12
                rect
            }
            var end = maxOf(top + height, y - 12)
            val rect = Rectangle(x, top + (end - top - height) / 2, width, height)
            add(label); label.bounds = rect
            childRects.forEachIndexed { i, child -> edges += Edge(rect, child, children[i].key) }
            right = maxOf(right, x + width)
            if (node.file && !folded && node.key in state.treeOpenedFiles) {
                val file = node.owner.file as? ReviewFile
                if (file != null) {
                    val editor = DiffFileEditor(file, project)
                    editors += editor; add(editor)
                    editor.bounds = Rectangle(x + width + 50, top, 760, 360)
                    right = maxOf(right, editor.x + editor.width)
                    end = maxOf(end, top + 360)
                }
            }
            bottom = maxOf(bottom, end)
            return rect to end
        }
        place(root, 16, 16)
        restyle()
        preferredSize = Dimension(right + 20, bottom + 20)
        revalidate(); repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graphics = g.create() as Graphics2D
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val branch = JBUI.CurrentTheme.Link.Foreground.ENABLED
        val dimmed = ColorUtil.withAlpha(branch, 0.3)
        edges.forEach { (from, to, child) ->
            graphics.color = if (child in reviewed) dimmed else branch
            val x = (from.x + from.width).toDouble(); val y = from.centerY
            graphics.draw(CubicCurve2D.Double(x, y, x + 25, y, to.x - 25.0, to.centerY, to.x.toDouble(), to.centerY))
        }
        graphics.dispose()
    }
    override fun dispose() { editors.forEach(Disposer::dispose); editors.clear() }

    private companion object { const val MAX_DIRECTORY = 48 }
}
