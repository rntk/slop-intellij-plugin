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
    /** Retained while moving and resizing so the native editor and active drag keep their state. */
    private inner class EditorFrame(val key: String, val editor: JComponent, title: String) : JPanel(BorderLayout()) {
        init {
            add(editor, BorderLayout.CENTER)
            add(JLabel(title).apply {
                name = "summary-diff-move:$key"
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
                foreground = UIUtil.getContextHelpForeground()
                background = UIUtil.getPanelBackground()
                isOpaque = true
                border = JBUI.Borders.empty(2, 6)
                toolTipText = "Drag to move the editor; double-click to return it beside its file"
                cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                val move = object : MouseAdapter() {
                    private var pressed: Point? = null
                    private var initial = Point()
                    override fun mousePressed(e: MouseEvent) {
                        raise(this@EditorFrame)
                        if (!SwingUtilities.isLeftMouseButton(e)) return
                        pressed = e.locationOnScreen
                        initial = this@EditorFrame.location
                        e.consume()
                    }
                    override fun mouseDragged(e: MouseEvent) {
                        val start = pressed ?: return
                        moveDiff(key, Point(maxOf(0, initial.x + e.locationOnScreen.x - start.x),
                            maxOf(0, initial.y + e.locationOnScreen.y - start.y)))
                        e.consume()
                    }
                    override fun mouseReleased(e: MouseEvent) { pressed = null }
                    override fun mouseClicked(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) dockDiff(key)
                    }
                }
                addMouseListener(move)
                addMouseMotionListener(move)
            }, BorderLayout.NORTH)
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
                            raise(this@EditorFrame)
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

    /** Moves without rebuilding the tree; a moved editor no longer takes room beside its file leaf. */
    private fun moveDiff(key: String, location: Point) {
        val frame = editors[key] ?: return
        val moved = key in state.treeDiffPositions
        state.treeDiffPositions[key] = Point(unscaled(location.x), unscaled(location.y))
        // The first move frees the leaf's room, which shifts the tree once.
        if (!moved) rebuild() else { frame.location = location; updateExtent() }
    }

    private fun dockDiff(key: String) {
        if (state.treeDiffPositions.remove(key) != null) rebuild()
    }

    private val blocks = mutableMapOf<String, FullSummaryBlock>()
    /** Blocks and editors raised by a click, bottom to top. */
    private val raised = mutableListOf<JComponent>()
    /** The tree and its editors, without the blocks. */
    private var treeExtent = Dimension()

    /** Opens the full summary of [node] beside its part, or raises it when it is already open. */
    internal fun showFullSummary(node: SummaryLinkNode) {
        val bounds = state.treeSummaryBlocks.remove(node.key) ?: run {
            val label = entries.firstOrNull { it.node.key == node.key }?.label?.bounds ?: Rectangle()
            Rectangle(unscaled(label.x + label.width + scaled(50)), unscaled(label.y), FULL_SUMMARY_WIDTH, 0)
        }
        state.treeSummaryBlocks[node.key] = bounds
        rebuild()
        viewport?.viewSize = preferredSize
        blocks[node.key]?.let { scrollRectToVisible(it.bounds) }
    }

    private fun raise(component: JComponent) {
        if (component.parent !== this) return
        (component as? FullSummaryBlock)?.key?.let { key ->
            state.treeSummaryBlocks.remove(key)?.let { state.treeSummaryBlocks[key] = it }
        }
        raised.remove(component); raised.add(component)
        setComponentZOrder(component, 0)
        repaint()
    }

    /** Moves without rebuilding the tree, so dragging stays smooth over open editors. */
    private fun moveBlock(key: String, location: Point) {
        val bounds = state.treeSummaryBlocks[key] ?: return
        val block = blocks[key] ?: return
        bounds.location = Point(unscaled(location.x), unscaled(location.y))
        block.location = location
        updateExtent()
    }

    private fun resizeBlock(key: String, size: Dimension) {
        val bounds = state.treeSummaryBlocks[key] ?: return
        bounds.size = Dimension(JBUI.unscale(size.width).coerceAtLeast(240), JBUI.unscale(size.height).coerceAtLeast(120))
        rebuild()
    }

    private fun closeBlock(key: String) {
        state.treeSummaryBlocks.remove(key)
        rebuild()
    }

    private fun scaled(n: Int) = zoomScaled(n, zoom)
    private fun unscaled(n: Int) = (n / (scaled(1000) / 1000.0)).roundToInt()

    private fun folded(node: SummaryLinkNode, depth: Int): Boolean =
        if (node.file) node.key !in state.treeOpenedFiles
        else node.key in state.treeFolded || depth >= state.treeLevel && node.key !in state.treeExpanded

    private fun levelSelector(): JComboBox<Int>? {
        val branches = mutableListOf<Pair<SummaryLinkNode, Int>>()
        fun collect(node: SummaryLinkNode, depth: Int) {
            if (!node.file && node.children.isNotEmpty()) branches += node to depth
            node.children.forEach { collect(it, depth + 1) }
        }
        collect(root, 0)
        val levels = branches.maxOfOrNull { it.second + 1 } ?: return null
        return JComboBox((1..levels).toList().toTypedArray()).apply {
            name = "summary-tree-level-selector"
            selectedItem = ((1..levels).firstOrNull { level ->
                branches.all { (node, depth) -> !folded(node, depth) == (depth < level) }
            } ?: state.treeLevel).coerceIn(1, levels)
            toolTipText = "Unfold branches through this level (root is level 1) and fold deeper branches"
            addActionListener {
                state.treeLevel = selectedItem as Int
                state.treeExpanded.clear()
                state.treeFolded.clear()
                rebuild()
            }
        }
    }

    private fun unfold(node: SummaryLinkNode, withSource: Boolean) {
        if (node.file) {
            if (withSource) state.treeOpenedFiles.add(node.key)
        } else {
            state.treeFolded.remove(node.key)
            state.treeExpanded.add(node.key)
        }
        node.children.forEach { unfold(it, withSource) }
    }

    internal fun navigate(path: String, withSource: Boolean): Boolean {
        val keys = root.pathKeys(path)
        if (keys.isEmpty()) return false
        fun reveal(node: SummaryLinkNode, depth: Int, hidden: Boolean) {
            if (node.key !in keys) return
            val childrenHidden = hidden || folded(node, depth)
            // A level can implicitly open hidden siblings. Revealing this route must not unfold them too.
            if (childrenHidden) node.children.filter { it.key !in keys && !it.file && it.key !in state.treeExpanded }
                .forEach { state.treeFolded.add(it.key) }
            if (node.file) {
                if (withSource) state.treeOpenedFiles.add(node.key)
            } else {
                state.treeFolded.remove(node.key)
                state.treeExpanded.add(node.key)
            }
            node.children.forEach { reveal(it, depth + 1, childrenHidden) }
        }
        reveal(root, 0, false)
        rebuild()
        viewport?.viewSize = preferredSize
        entries.firstOrNull { it.node.file && it.node.key in keys }?.label?.let {
            scrollRectToVisible(it.bounds)
        }
        return true
    }

    internal fun navigationControls(field: JTextField, controlFont: Font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)): JComponent =
        JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            name = "summary-tree-controls"
            isOpaque = false
            val leading = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                val selector = levelSelector()
                if (selector != null) {
                    add(JLabel("Level").apply { font = controlFont; labelFor = selector })
                    add(selector.apply { font = controlFont })
                }
                add(JLabel("Navigate").apply { font = controlFont; labelFor = field })
            }
            add(leading, BorderLayout.WEST)
            val status = JLabel().apply {
                font = controlFont
                foreground = UIUtil.getContextHelpForeground()
                isVisible = false
            }
            fun go(withSource: Boolean) {
                val found = navigate(field.text, withSource)
                status.text = if (found) "" else "Path not found in this tree"
                status.isVisible = !found
                revalidate()
                repaint()
            }
            add(field.apply {
                name = "summary-tree-navigate"
                font = controlFont
                columns = 12
                minimumSize = Dimension(JBUI.scale(80), preferredSize.height)
                toolTipText = "Paste a relative or absolute file path"
                addActionListener { go(false) }
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(JButton("Unfold").apply {
                    name = "summary-tree-navigate-unfold"
                    font = controlFont
                    addActionListener { go(false) }
                })
                add(JButton("Unfold source/diff").apply {
                    name = "summary-tree-navigate-source"
                    font = controlFont
                    addActionListener { go(true) }
                })
            }, BorderLayout.EAST)
            add(status, BorderLayout.SOUTH)
        }

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
            val folded = folded(node, depth)
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
            label.componentPopupMenu = summaryEntryMenu(node.owner, card, state, actions, vocabulary, "summary-tree-action", canFold = false).apply {
                for ((index, text) in listOf("Unfold all", "Unfold all source/diff").withIndex()) {
                    insert(JMenuItem(text).apply {
                        name = "summary-tree-action:$text"
                        addActionListener { unfold(node, index == 1); rebuild() }
                    }, index)
                }
                insert(JMenuItem("Full summary").apply {
                    name = "summary-tree-action:Full summary"
                    toolTipText = "Open a movable block with every summary from the root down to this part"
                    addActionListener { showFullSummary(node) }
                }, 2)
            }
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
                        EditorFrame(node.key, content, file.path)
                    }
                    unused.remove(node.key)
                    add(editor)
                    val size = state.treeDiffSizes[node.key] ?: Dimension(760, 360)
                    val moved = state.treeDiffPositions[node.key]
                    editor.bounds = Rectangle(moved?.let { scaled(it.x) } ?: (x + width + scaled(50)),
                        moved?.let { scaled(it.y) } ?: top, scaled(size.width), scaled(size.height))
                    right = maxOf(right, editor.x + editor.width)
                    if (moved == null) end = maxOf(end, top + editor.height)
                    else bottom = maxOf(bottom, editor.y + editor.height)
                }
            }
            bottom = maxOf(bottom, end)
            return rect to end
        }
        place(root, scaled(16), scaled(16), 0)
        unused.forEach { editors.remove(it)?.editor?.let { editor -> Disposer.dispose(editor as Disposable) } }
        placeBlocks()
        raised.retainAll { it.parent === this }
        raised.forEach { setComponentZOrder(it, 0) }
        restyle()
        treeExtent = Dimension(right, bottom)
        updateExtent()
    }

    /** Blocks stay open while their part is filtered out, and come back with it. */
    private fun placeBlocks() {
        val unused = blocks.keys.toMutableSet()
        for ((key, bounds) in state.treeSummaryBlocks) {
            val full = root.fullSummary(card.summaries.tree, key).ifEmpty { null } ?: continue
            val block = blocks.getOrPut(key) {
                FullSummaryBlock(key, front = { blocks[key]?.let(::raise) }, onMove = { moveBlock(key, it) },
                    onResize = { resizeBlock(key, it) }, close = { closeBlock(key) })
            }
            unused.remove(key)
            // Only the position follows the zoom: like the native editors, the text stays readable when zoomed out.
            val width = JBUI.scale(bounds.width)
            val fits = block.fill(full, card, width, 1.0, actions, vocabulary)
            // Later blocks sit on top: Swing paints lower indices last.
            add(block, 0)
            block.bounds = Rectangle(scaled(bounds.x), scaled(bounds.y), width,
                if (bounds.height > 0) JBUI.scale(bounds.height) else minOf(fits, JBUI.scale(FULL_SUMMARY_HEIGHT)))
            block.revalidate()
        }
        unused.forEach { blocks.remove(it) }
    }

    private fun updateExtent() {
        var right = treeExtent.width
        var bottom = treeExtent.height
        (blocks.values + editors.values).filter { it.parent === this }.forEach { right = maxOf(right, it.x + it.width); bottom = maxOf(bottom, it.y + it.height) }
        preferredSize = Dimension(right + scaled(20), bottom + scaled(20))
        viewport?.let { if (it.viewSize.width < preferredSize.width || it.viewSize.height < preferredSize.height) it.viewSize = preferredSize }
        revalidate(); repaint()
    }

    /** Blocks and editors may overlap. */
    override fun isOptimizedDrawingEnabled(): Boolean = false

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
        const val FULL_SUMMARY_WIDTH = 520
        const val FULL_SUMMARY_HEIGHT = 560
    }
}
