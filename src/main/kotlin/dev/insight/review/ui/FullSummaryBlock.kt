package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.ui.pixelBorder
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.DefaultHighlighter

/**
 * A movable outline of the summaries from the root down to one Tree occurrence, read beside source and diff blocks. The
 * parts the occurrence's branch passes through are highlighted. Its header moves the block and the corner resizes it; the
 * block itself is kept across tree rebuilds so a drag continues.
 */
internal class FullSummaryBlock(
    val key: String,
    private val front: () -> Unit,
    /** The new top-left corner in screen pixels; the canvas decides whether to rebuild. */
    private val onMove: (Point) -> Unit,
    /** The new size in screen pixels. */
    private val onResize: (Dimension) -> Unit,
    close: () -> Unit,
) : JPanel(BorderLayout()) {
    private val title = JLabel()
    private val body = JPanel(VerticalLayout(0)).apply { isOpaque = false }
    private val scroll = JBScrollPane(body).apply {
        border = null; isOpaque = false; viewport.isOpaque = false
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    init {
        name = "summary-full:$key"
        background = UIUtil.getListBackground()
        val header = JPanel(BorderLayout()).apply {
            name = "summary-full-header:$key"
            background = UIUtil.getPanelBackground()
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Drag to move"
            add(title, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Close full summary", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { close() }
                .apply { name = "summary-full-close:$key"; cursor = Cursor.getDefaultCursor() }, BorderLayout.EAST)
        }
        val drag = object : MouseAdapter() {
            private var pressed: Point? = null
            private var initial = Point()
            override fun mousePressed(e: MouseEvent) {
                front()
                if (!SwingUtilities.isLeftMouseButton(e)) return
                pressed = e.locationOnScreen
                initial = location
                e.consume()
            }
            override fun mouseDragged(e: MouseEvent) {
                val start = pressed ?: return
                onMove(Point(maxOf(0, initial.x + e.locationOnScreen.x - start.x), maxOf(0, initial.y + e.locationOnScreen.y - start.y)))
                e.consume()
            }
            override fun mouseReleased(e: MouseEvent) { pressed = null }
        }
        listOf(header, title).forEach { it.addMouseListener(drag); it.addMouseMotionListener(drag) }
        val corner = JLabel("◢").apply {
            name = "summary-full-resize:$key"
            toolTipText = "Drag to resize the summary"
            cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
            val resizing = object : MouseAdapter() {
                private var pressed: Point? = null
                private var initial = Dimension()
                override fun mousePressed(e: MouseEvent) {
                    front()
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    pressed = e.locationOnScreen
                    initial = Dimension(this@FullSummaryBlock.size)
                    e.consume()
                }
                override fun mouseDragged(e: MouseEvent) {
                    val start = pressed ?: return
                    onResize(Dimension(initial.width + e.locationOnScreen.x - start.x, initial.height + e.locationOnScreen.y - start.y))
                    e.consume()
                }
                override fun mouseReleased(e: MouseEvent) { pressed = null }
            }
            addMouseListener(resizing)
            addMouseMotionListener(resizing)
        }
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply { isOpaque = false; add(corner, BorderLayout.EAST) }, BorderLayout.SOUTH)
        // Clicking anywhere in the block raises it over overlapping blocks and editors.
        addMouseListener(object : MouseAdapter() { override fun mousePressed(e: MouseEvent) = front() })
    }

    /** Lays the entries out for [width] at [zoom]; returns the height that fits them without scrolling. */
    fun fill(entries: List<FullSummaryEntry>, card: SummariesCard, width: Int, zoom: Double,
             actions: ChangeSummaryActions, vocabulary: ReviewVocabulary): Int {
        fun scaled(n: Int) = zoomScaled(n, zoom)
        val tree = card.summaries.tree
        val help = UIUtil.getContextHelpForeground()
        border = BorderFactory.createLineBorder(JBColor.border())
        title.apply {
            text = "Full summary · " + entries.last().node.let { if (it.kind == SummaryKind.ROOT) vocabulary.rootName else it.location }
            font = JBFont.small().asBold().zoomed(zoom)
            border = pixelBorder(scaled(6), scaled(8), scaled(6), scaled(4))
            toolTipText = entries.joinToString(" › ") { name(it.node, vocabulary, tree) }
        }
        body.removeAll()
        body.border = pixelBorder(scaled(6), scaled(8), scaled(6), scaled(8))
        val gutter = scaled(16) + scroll.verticalScrollBar.preferredSize.width
        for ((index, entry) in entries.withIndex()) {
            val node = entry.node
            val indent = entry.depth * scaled(ChangeSummariesPanel.INDENT)
            val textWidth = (width - indent - gutter).coerceAtLeast(scaled(120))
            val summary = card.summaries.summary(node.id)
            // An open folder holding a single file leaves its summary to the file, as in the outline.
            val repeated = index < entries.lastIndex && tree.holdsOnlyFile(node.id)
            val text: JComponent? = when {
                repeated -> null
                summary == null -> WrappedText(if (card.running) "summarizing…" else "no summary yet", textWidth).apply {
                    isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; border = null
                    font = JBFont.label().zoomed(zoom).let { it.deriveFont(it.style or Font.ITALIC) }
                    foreground = help
                }
                else -> WrappedSummaryText(summary.text, textWidth,
                    (if (node.kind == SummaryKind.ROOT) JBFont.label().asBold() else JBFont.label()).zoomed(zoom).forSummaryText(), emptyMap())
                    .also { area ->
                        actions.review?.decorate(area, node)
                        if (area.text == summary.text) for (segment in entry.route.sorted()) {
                            val range = summary.ranges.getOrNull(segment) ?: continue
                            if (!range.isEmpty()) area.highlighter.addHighlight(range.first, range.last + 1,
                                DefaultHighlighter.DefaultHighlightPainter(TraceStyle.CONTEXT_WORD))
                        }
                    }
            }
            body.add(JPanel(VerticalLayout(scaled(2))).apply {
                name = "summary-full-entry:${node.identity}"
                isOpaque = false
                border = pixelBorder(scaled(4), indent, scaled(4), 0)
                toolTipText = node.location
                add(JLabel(name(node, vocabulary, tree)).apply {
                    font = JBFont.label().asBold().zoomed(zoom).forReviewName()
                    foreground = help
                    toolTipText = node.location
                })
                text?.let(::add)
                actions.installEntryMenu(this, node)
            })
        }
        body.revalidate()
        body.repaint()
        val chrome = (layout as BorderLayout).let { layout ->
            listOf(BorderLayout.NORTH, BorderLayout.SOUTH).sumOf { layout.getLayoutComponent(it)?.preferredSize?.height ?: 0 }
        }
        return body.preferredSize.height + chrome + insets.top + insets.bottom
    }

    private fun name(node: SummaryNode, vocabulary: ReviewVocabulary, tree: dev.insight.review.ReviewSummaryTree) = when (node.kind) {
        SummaryKind.ROOT -> vocabulary.rootName
        SummaryKind.DIRECTORY -> tree.shownLabel(node) + "/"
        SummaryKind.FILE -> node.path.substringAfterLast('/')
        SummaryKind.GROUP, SummaryKind.CHUNK -> node.label
    }
}
