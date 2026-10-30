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
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import dev.insight.ui.zoomed
import dev.insight.ui.zoomScaled
import java.awt.event.*
import kotlin.math.pow
import kotlin.math.roundToInt
import java.awt.*
import java.awt.geom.CubicCurve2D
import javax.swing.*

/** Left-to-right claim tree, with native source or diff viewers unfolded at file leaves. */
internal class ChangeSummaryTreePanel(
    private val card: SummariesCard,
    private val state: ChangeSummariesViewState,
    zoom: Double,
    private val actions: ChangeSummaryActions,
    private val project: Project?,
    private val vocabulary: dev.insight.review.ReviewVocabulary = dev.insight.review.ReviewVocabulary.DIFF,
) : JPanel(null), Disposable {
    private val root = summaryLinkTree(card.summaries) { state.shows(it, card.summaries) }
    internal var zoom = state.treeZoom ?: zoom
        private set
    private val editors = mutableMapOf<String, EditorFrame>()
    /** Retained while resizing so the native editor and active drag keep their state. */
    private inner class EditorFrame(val key: String, val editor: JComponent) : JPanel(BorderLayout()) {
        init {
            add(editor, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JLabel("◢").apply {
                    name = "summary-diff-resize:$key"
                    toolTipText = "Drag to resize the editor"
                    cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
                    val resize = object : MouseAdapter() {
                        private var pressed: Point? = null
                        private var initial = Dimension()
                        override fun mousePressed(e: MouseEvent) {
                            if (!SwingUtilities.isLeftMouseButton(e)) return
                            pressed = e.locationOnScreen
                            initial = Dimension(this@EditorFrame.size)
                            e.consume()
                        }
                        override fun mouseDragged(e: MouseEvent) {
                            val start = pressed ?: return
                            val now = e.locationOnScreen
                            val scale = scaled(1000) / 1000.0
                            resizeDiff(key, ((initial.width + now.x - start.x) / scale).roundToInt(),
                                ((initial.height + now.y - start.y) / scale).roundToInt())
                            e.consume()
                        }
                        override fun mouseReleased(e: MouseEvent) { pressed = null }
                    }
                    addMouseListener(resize)
                    addMouseMotionListener(resize)
                }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
    }

    internal fun resizeDiff(key: String, width: Int, height: Int) {
        state.treeDiffSizes[key] = Dimension(width.coerceAtLeast(320), height.coerceAtLeast(160))
        rebuild()
    }

    private fun scaled(n: Int) = zoomScaled(n, zoom)
    private val viewport get() = SwingUtilities.getAncestorOfClass(JViewport::class.java, this) as? JViewport
    private var start: Point? = null
    private var origin: Point? = null
    private var panned = false
    private val pointer = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(e) && !SwingUtilities.isMiddleMouseButton(e)) return
            panned = false
            start = e.locationOnScreen
            origin = viewport?.viewPosition
            requestFocusInWindow()
        }
        override fun mouseDragged(e: MouseEvent) {
            val a = start ?: return
            val b = origin ?: return
            val now = e.locationOnScreen
            if (!panned && a.distance(now) < JBUI.scale(3)) return
            panned = true
            (e.component as? AbstractButton)?.model?.isArmed = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            scrollTo(b.x + a.x - now.x, b.y + a.y - now.y)
        }
        override fun mouseReleased(e: MouseEvent) {
            start = null; origin = null
            cursor = Cursor.getDefaultCursor()
        }
    }
    private fun installPointer(c: JComponent) {
        c.addMouseListener(pointer); c.addMouseMotionListener(pointer)
    }
    private fun scrollTo(x: Int, y: Int) {
        val v = viewport ?: return
        v.viewPosition = Point(x.coerceIn(0, maxOf(0, preferredSize.width - v.width)),
            y.coerceIn(0, maxOf(0, preferredSize.height - v.height)))
    }
    internal fun zoomTo(requested: Double, anchor: Point? = null) {
        if (!requested.isFinite()) return
        val next = requested.coerceIn(0.25, 3.0)
        if (next == zoom) return
        val v = viewport
        val a = anchor ?: Point((v?.width ?: 0) / 2, (v?.height ?: 0) / 2)
        val old = v?.viewPosition ?: Point()
        val ratio = next / zoom
        zoom = next; state.treeZoom = next
        rebuild()
        v?.viewSize = preferredSize
        scrollTo(((old.x + a.x) * ratio - a.x).roundToInt(),
            ((old.y + a.y) * ratio - a.y).roundToInt())
    }
    private fun installNavigation() {
        isFocusable = true
        installPointer(this)
        toolTipText = "Drag to move; Ctrl/⌘ + wheel to zoom; +/− zoom, 0 resets; arrows and Page Up/Down scroll."
        addMouseWheelListener { e ->
            if (e.isControlDown || e.isMetaDown) {
                zoomTo(zoom * 1.15.pow(-e.preciseWheelRotation),
                    viewport?.let { SwingUtilities.convertPoint(this, e.point, it) })
                e.consume()
            } else {
                val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, this) as? JScrollPane
                scroll?.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, scroll))
            }
        }
        fun bind(key: String, action: () -> Unit) {
            for (condition in listOf(WHEN_FOCUSED, WHEN_ANCESTOR_OF_FOCUSED_COMPONENT))
                getInputMap(condition).put(KeyStroke.getKeyStroke(key), key)
            actionMap.put(key, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = action()
            })
        }
        fun move(x: Int, y: Int) {
            val p = viewport?.viewPosition ?: return
            scrollTo(p.x + x, p.y + y)
        }
        bind("typed +") { zoomTo(zoom * 1.2) }
        bind("typed =") { zoomTo(zoom * 1.2) }
        bind("typed -") { zoomTo(zoom / 1.2) }
        bind("typed 0") { zoomTo(1.0) }
        for (prefix in listOf("", "KP_")) {
            bind("${prefix}LEFT") { move(-scaled(48), 0) }
            bind("${prefix}RIGHT") { move(scaled(48), 0) }
            bind("${prefix}UP") { move(0, -scaled(48)) }
            bind("${prefix}DOWN") { move(0, scaled(48)) }
        }
        bind("PAGE_UP") { move(0, -(viewport?.height ?: 0)) }
        bind("PAGE_DOWN") { move(0, viewport?.height ?: 0) }
        bind("shift PAGE_UP") { move(-(viewport?.width ?: 0), 0) }
        bind("shift PAGE_DOWN") { move(viewport?.width ?: 0, 0) }
        bind("HOME") { scrollTo(0, 0) }
        bind("END") { scrollTo(0, preferredSize.height) }
    }
    private data class Edge(val from: Rectangle, val to: Rectangle, val child: String)
    private class Entry(val node: SummaryLinkNode, val label: JButton, val folded: Boolean)

    private val edges = mutableListOf<Edge>()
    private val entries = mutableListOf<Entry>()
    private var reviewed = emptySet<String>()
    init {
        name = "summary-link-tree"; background = UIUtil.getListBackground(); installNavigation(); rebuild()
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
        val width = if (node.text.length > 60) " style='width:${scaled(300)}px'" else ""
        val below = if (directory.isEmpty()) "" else "<br><small><font color='" + ColorUtil.toHtmlColor(help) + "'>" +
            XmlStringUtil.escapeString(directory) + "</font></small>"
        label.text = "<html><div" + width + ">" + marker + XmlStringUtil.escapeString(node.text) + below + "</div></html>"
    }

    private fun rebuild() {
        val unused = editors.keys.toMutableSet()
        removeAll(); edges.clear(); entries.clear()
        var bottom = 0
        var right = 0
        fun place(node: SummaryLinkNode, x: Int, top: Int, depth: Int): Pair<Rectangle, Int> {
            val folded = if (node.file) node.key !in state.treeOpenedFiles
                else node.key in state.treeFolded || depth >= INITIAL_LEVELS - 1 && node.key !in state.treeExpanded
            val label = JButton().apply {
                name = "summary-tree:${node.key}"
                font = UIUtil.getLabelFont().zoomed(zoom)
                horizontalAlignment = SwingConstants.LEFT
                isContentAreaFilled = false
                isBorderPainted = false
                toolTipText = node.owner.location + if (node.text.isEmpty()) "" else " — " + node.text
                installPointer(this)
                addActionListener {
                    if (panned) return@addActionListener
                    if (node.file) {
                        if (!state.treeOpenedFiles.add(node.key)) state.treeOpenedFiles.remove(node.key)
                    } else if (folded) {
                        state.treeFolded.remove(node.key)
                        state.treeExpanded.add(node.key)
                    } else {
                        state.treeExpanded.remove(node.key)
                        state.treeFolded.add(node.key)
                    }
                    rebuild()
                }
            }
            entries += Entry(node, label, folded)
            style(label, node, folded)
            label.componentPopupMenu = summaryEntryMenu(node.owner, card, state, actions, vocabulary, "summary-tree-action", canFold = false)
            val width = if (node.text.isEmpty()) scaled(20) else minOf(scaled(440), maxOf(scaled(110), label.preferredSize.width))
            val height = maxOf(scaled(30), label.preferredSize.height)
            var y = top
            val children = if (folded) emptyList() else node.children
            val childRects = children.map { child ->
                val (rect, end) = place(child, x + width + scaled(50), y, depth + 1)
                y = end + scaled(12)
                rect
            }
            var end = maxOf(top + height, y - scaled(12))
            val rect = Rectangle(x, top + (end - top - height) / 2, width, height)
            add(label); label.bounds = rect
            childRects.forEachIndexed { i, child -> edges += Edge(rect, child, children[i].key) }
            right = maxOf(right, x + width)
            if (node.file && !folded && node.key in state.treeOpenedFiles) {
                val file = node.owner.file
                if (file != null) {
                    val editor = editors.getOrPut(node.key) {
                        val content = if (file is ReviewFile) DiffFileEditor(file, project) else {
                            val text = file.text.orEmpty()
                            DiffSourceEditor(ReviewSourceBlock(
                                ReviewSourceRange(file.path, 1, maxOf(1, text.lines().size)),
                                text, fullText = text, absolutePath = file.absolutePath,
                            ), project)
                        }
                        EditorFrame(node.key, content)
                    }
                    unused.remove(node.key)
                    add(editor)
                    val size = state.treeDiffSizes[node.key] ?: Dimension(760, 360)
                    editor.bounds = Rectangle(x + width + scaled(50), top, scaled(size.width), scaled(size.height))
                    right = maxOf(right, editor.x + editor.width)
                    end = maxOf(end, top + editor.height)
                }
            }
            bottom = maxOf(bottom, end)
            return rect to end
        }
        place(root, scaled(16), scaled(16), 0)
        unused.forEach { editors.remove(it)?.editor?.let { editor -> Disposer.dispose(editor as Disposable) } }
        restyle()
        preferredSize = Dimension(right + scaled(20), bottom + scaled(20))
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
            graphics.draw(CubicCurve2D.Double(x, y, x + scaled(25), y, to.x - scaled(25).toDouble(), to.centerY, to.x.toDouble(), to.centerY))
        }
        graphics.dispose()
    }
    override fun dispose() { editors.values.forEach { Disposer.dispose(it.editor as Disposable) }; editors.clear() }

    private companion object {
        const val MAX_DIRECTORY = 48
        const val INITIAL_LEVELS = 3
    }
}
