package dev.insight.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
    private val edges = mutableListOf<Pair<Rectangle, Rectangle>>()
    init { name = "summary-link-tree"; background = UIUtil.getListBackground(); rebuild() }

    private fun rebuild() {
        editors.forEach(Disposer::dispose); editors.clear()
        removeAll(); edges.clear()
        var bottom = 0
        var right = 0
        fun place(node: SummaryLinkNode, x: Int, top: Int): Pair<Rectangle, Int> {
            val folded = if (node.file) node.key !in state.treeOpenedFiles else node.key in state.treeFolded
            val label = JButton((if (node.children.isNotEmpty() || node.file) if (folded) "▸ " else "▾ " else "") + node.text).apply {
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
            if (node.text.length > 60) label.text = "<html><div style='width:300px'>" +
                (if (folded) "▸ " else "▾ ") + com.intellij.xml.util.XmlStringUtil.escapeString(node.text) + "</div></html>"
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
            childRects.forEach { edges += rect to it }
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
        preferredSize = Dimension(right + 20, bottom + 20)
        revalidate(); repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graphics = g.create() as Graphics2D
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = JBUI.CurrentTheme.Link.Foreground.ENABLED
        edges.forEach { (from, to) ->
            val x = (from.x + from.width).toDouble(); val y = from.centerY
            graphics.draw(CubicCurve2D.Double(x, y, x + 25, y, to.x - 25.0, to.centerY, to.x.toDouble(), to.centerY))
        }
        graphics.dispose()
    }
    override fun dispose() { editors.forEach(Disposer::dispose); editors.clear() }
}
