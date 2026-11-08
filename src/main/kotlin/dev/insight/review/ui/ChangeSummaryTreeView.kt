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
import dev.insight.review.PatchSource
import dev.insight.review.ReviewSource
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
import javax.swing.plaf.basic.BasicHTML
import javax.swing.text.View

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
    private val wordWeights = SummaryWordWeights(card.summaries)
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
                rebuild(animate = true)
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
        fun leaf(node: SummaryLinkNode): String? =
            if (node.file && node.key in keys) node.key else node.children.firstNotNullOfOrNull(::leaf)
        // An animated rebuild scrolls as it goes; otherwise the new layout is in place and can be scrolled to now.
        if (!rebuild(animate = true, reveal = leaf(root))) {
            viewport?.viewSize = preferredSize
            entries.firstOrNull { it.node.file && it.node.key in keys }?.label?.let { scrollRectToVisible(it.bounds) }
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
    /** A branch from a part to one of its children, drawn between their currently shown positions. */
    private data class Edge(val parent: String, val child: String, val alpha: Double = 1.0)

    /** A part's button; [alpha] fades it while it appears or leaves, and a [leaving] button no longer reacts. */
    private class NodeButton : JButton() {
        var alpha = 1f
        var leaving = false
        override fun paint(g: Graphics) {
            if (alpha >= 1f) return super.paint(g)
            val faded = g.create() as Graphics2D
            faded.composite = AlphaComposite.SrcOver.derive(alpha.coerceIn(0f, 1f))
            super.paint(faded)
            faded.dispose()
        }
    }
    private class Entry(val node: SummaryLinkNode, val label: NodeButton, val folded: Boolean)

    /** One layout's parts: where each ends up and its parent, which is what a transition to the next layout is built from. */
    private class Layout {
        val targets = mutableMapOf<String, Rectangle>()
        val parents = mutableMapOf<String, String?>()
        val branches = mutableListOf<Pair<String, String>>()
    }

    /** The branches to draw now; during a transition they include the ones that are fading in or out. */
    private var edges = emptyList<Edge>()
    private val entries = mutableListOf<Entry>()
    private var layout = Layout()
    /** Every shown part's current bounds, mid-transition included, so a new transition starts from what the reader sees. */
    private val shown = mutableMapOf<String, Rectangle>()
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
        // A claim is a segment of its owner's summary, so it is weighted against the owner's child summaries or leaf source, as in the split view.
        val cloud = if (state.tagsCloud && (!node.file || node.parts.isNotEmpty())) cloudHtml(node.text, wordWeights[node.owner.id], label.font.size2D) else null
        if (directory.isEmpty() && node.text.length <= 60 && cloud == null) { label.text = marker + node.text; return }
        val width = if (node.text.length > 60) " style='width:${scaled(WRAP_WIDTH)}px'" else ""
        val below = if (directory.isEmpty()) "" else "<br><small><font color='" + ColorUtil.toHtmlColor(help) + "'>" +
            XmlStringUtil.escapeString(directory) + "</font></small>"
        label.text = "<html><div" + width + ">" + marker + (cloud ?: XmlStringUtil.escapeString(node.text)) + below + "</div></html>"
    }

    /**
     * Lays the tree out again. With [animate], parts glide from where they were shown to their new places and the viewport
     * follows [anchor] (by default the part nearest the middle of the view), so the reader keeps their place. Otherwise the new
     * layout appears at once. [reveal] is a part to scroll into view as the transition runs. Returns whether it is animating.
     */
    private fun rebuild(animate: Boolean = false, anchor: String? = null, reveal: String? = null): Boolean {
        val animating = animate && canAnimate()
        val before = if (animating) snapshot() else null
        stopMotion()
        val unused = editors.keys.toMutableSet()
        removeAll(); entries.clear()
        val next = Layout()
        val branches = next.branches
        var bottom = 0
        var right = 0
        fun place(node: SummaryLinkNode, x: Int, top: Int, depth: Int, parent: String?): Pair<Rectangle, Int> {
            val folded = folded(node, depth)
            val label = NodeButton().apply {
                name = "summary-tree:${node.key}"
                font = UIUtil.getLabelFont().zoomed(zoom * FONT_SCALE)
                horizontalAlignment = SwingConstants.LEFT
                isContentAreaFilled = false
                isBorderPainted = false
                toolTipText = node.owner.location + if (node.text.isEmpty()) "" else " — " + node.text
                installPointer(this)
                addActionListener {
                    if (panned || leaving) return@addActionListener
                    if (node.file) {
                        if (!state.treeOpenedFiles.add(node.key)) state.treeOpenedFiles.remove(node.key)
                    } else if (folded) {
                        state.treeFolded.remove(node.key)
                        state.treeExpanded.add(node.key)
                    } else {
                        state.treeExpanded.remove(node.key)
                        state.treeFolded.add(node.key)
                    }
                    rebuild(animate = true, anchor = node.key)
                }
            }
            entries += Entry(node, label, folded)
            style(label, node, folded)
            label.componentPopupMenu = summaryEntryMenu(node.owner, card, state, actions, vocabulary, "summary-tree-action", canFold = false).apply {
                for ((index, text) in listOf("Unfold all", "Unfold all source/diff").withIndex()) {
                    insert(JMenuItem(text).apply {
                        name = "summary-tree-action:$text"
                        addActionListener { unfold(node, index == 1); rebuild(animate = true, anchor = node.key) }
                    }, index)
                }
                insert(JMenuItem("Full summary").apply {
                    name = "summary-tree-action:Full summary"
                    toolTipText = "Open a movable block with every summary from the root down to this part"
                    addActionListener { showFullSummary(node) }
                }, 2)
            }
            val width = if (node.text.isEmpty()) scaled(20) else minOf(scaled(MAX_WIDTH), maxOf(scaled(MIN_WIDTH), label.preferredSize.width))
            val height = heightFor(label, width)
            var y = top
            val children = if (folded) emptyList() else node.children
            for (child in children) y = place(child, x + width + scaled(50), y, depth + 1, node.key).second + scaled(GAP)
            var end = maxOf(top + height, y - scaled(GAP))
            val rect = Rectangle(x, top + (end - top - height) / 2, width, height)
            add(label); label.bounds = rect
            next.targets[node.key] = rect
            next.parents[node.key] = parent
            children.forEach { branches += node.key to it.key }
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
                    // The box scales with the zoom, so its text must too, or a zoomed-out tree shows a few oversized lines.
                    when (val content = editor.editor) {
                        is DiffFileEditor -> content.zoomText(zoom)
                        is DiffSourceEditor -> content.zoomText(zoom)
                    }
                    val size = state.treeDiffSizes[node.key] ?: defaultEditorSize(file)
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
        place(root, scaled(16), scaled(16), 0, null)
        unused.forEach { editors.remove(it)?.editor?.let { editor -> Disposer.dispose(editor as Disposable) } }
        placeBlocks()
        raised.retainAll { it.parent === this }
        raised.forEach { setComponentZOrder(it, 0) }
        restyle()
        treeExtent = Dimension(right, bottom)
        updateExtent()
        val previous = layout
        layout = next
        shown.clear()
        shown += next.targets.mapValues { Rectangle(it.value) }
        edges = branches.map { Edge(it.first, it.second) }
        if (before == null) return false
        return beginMotion(before, previous, anchor, reveal)
    }

    /** The bounds of every shown part, and the viewport and extent they are seen in, as a transition's starting point. */
    private class Snapshot(val shown: Map<String, Rectangle>, val labels: Map<String, NodeButton>, val editorAt: Map<String, Point>,
                           val view: Point, val port: Dimension, val extent: Dimension)

    private fun snapshot() = Snapshot(shown.mapValues { Rectangle(it.value) }, entries.associate { it.node.key to it.label } +
        ghosts.associate { it.key to it.label }, editors.mapValues { Point(it.value.location) },
        viewport?.viewPosition ?: Point(), viewport?.extentSize ?: Dimension(), preferredSize)

    /** A part that is leaving: it glides toward the part that absorbs it while fading out. */
    private class Ghost(val key: String, val label: NodeButton, val from: Point, val to: Point)

    /** A transition between two layouts, advanced by [motionFrame]. */
    private inner class Motion(
        val from: Map<String, Point>, val ghosts: List<Ghost>, val appearing: Set<String>, val branches: List<Edge>,
        val editors: List<Triple<EditorFrame, Point, Point>>, val extent: Pair<Dimension, Dimension>,
        val anchor: String?, val anchorOffset: Point, val reveal: Pair<Point, Point>?,
    ) {
        val started = System.nanoTime()
        val timer = Timer(SummaryTreeMotion.FRAME_MS) { tick() }.apply { isCoalesce = true }
        private fun tick() {
            val elapsed = (System.nanoTime() - started) / 1_000_000.0
            motionFrame(elapsed / SummaryTreeMotion.DURATION_MS)
        }
    }
    private var motion: Motion? = null
    private val ghosts get() = motion?.ghosts.orEmpty()

    /** Animations need a visible tree; a hidden one, including in headless tests, shows each layout at once. */
    internal var animated: Boolean? = null
    private fun canAnimate() = animated ?: (isShowing && viewport != null)

    private fun beginMotion(before: Snapshot, previous: Layout, anchorKey: String?, revealKey: String?): Boolean {
        val targets = layout.targets
        val stays = targets.keys.filter { it in before.shown }.toSet()
        val leaving = before.shown.keys.filter { it !in targets && it in before.labels }
        val appearing = targets.keys - stays
        if (appearing.isEmpty() && leaving.isEmpty() && stays.all { before.shown[it]!!.location == targets[it]!!.location } &&
            before.extent == preferredSize) return false
        val port = before.port
        val anchor = anchorKey?.takeIf { it in stays } ?: SummaryTreeMotion.pickAnchor(before.shown, stays, Rectangle(before.view, port))
        val offset = anchor?.let { Point(before.shown[it]!!.x - before.view.x, before.shown[it]!!.y - before.view.y) } ?: Point()
        val from = targets.mapValues { (key, rect) ->
            before.shown[key]?.location ?: SummaryTreeMotion.origin(key, rect.location, layout.parents, before.shown)
        }
        val gone = leaving.map { key ->
            val here = before.shown[key]!!
            Ghost(key, before.labels.getValue(key), here.location,
                SummaryTreeMotion.destination(key, here.location, previous.parents, targets))
        }
        val staying = previous.branches.toSet()
        val fading = previous.branches.filter { it.second !in targets }.map { Edge(it.first, it.second, 0.0) }
        val branches = layout.branches.map { Edge(it.first, it.second, if (it in staying) 1.0 else 0.0) } + fading
        val movingEditors = editors.values.mapNotNull { frame ->
            val was = before.editorAt[frame.key] ?: return@mapNotNull null
            if (was == frame.location) null else Triple(frame, was, frame.location)
        }
        val view = revealKey?.let { key ->
            targets[key]?.let { SummaryTreeMotion.reveal(it, before.view, port, preferredSize, scaled(REVEAL_MARGIN)) }
        }?.let { before.view to it }
        val next = Motion(from, gone, appearing, branches, movingEditors, before.extent to Dimension(preferredSize),
            anchor.takeIf { view == null }, offset, view)
        motion = next
        for (ghost in gone) {
            ghost.label.leaving = true
            ghost.label.bounds = Rectangle(ghost.from, ghost.label.size)
            add(ghost.label)
        }
        motionFrame(0.0)
        next.timer.start()
        return true
    }

    /** Shows the transition at [progress] from 0 to 1; at 1 it ends and the final layout stays. */
    internal fun motionFrame(progress: Double) {
        val current = motion ?: return
        val t = SummaryTreeMotion.ease(progress)
        val done = progress >= 1.0
        for (entry in entries) {
            val key = entry.node.key
            val target = layout.targets[key] ?: continue
            val bounds = Rectangle(SummaryTreeMotion.lerp(current.from[key] ?: target.location, target.location, t), target.size)
            entry.label.bounds = bounds
            entry.label.alpha = if (key in current.appearing) t.toFloat() else 1f
            shown[key] = bounds
        }
        for (ghost in current.ghosts) {
            val bounds = Rectangle(SummaryTreeMotion.lerp(ghost.from, ghost.to, t), ghost.label.size)
            ghost.label.bounds = bounds
            ghost.label.alpha = (1 - t).toFloat()
            shown[ghost.key] = bounds
        }
        for ((frame, from, to) in current.editors) frame.location = SummaryTreeMotion.lerp(from, to, t)
        edges = current.branches.map { edge ->
            if (edge.alpha == 1.0) edge else Edge(edge.parent, edge.child, if (edge.child in layout.targets) t else 1 - t)
        }
        val extent = SummaryTreeMotion.lerp(current.extent.first, current.extent.second, t)
        viewport?.let { port ->
            preferredSize = extent
            if (port.viewSize != extent) port.viewSize = extent
            val target = current.reveal?.let { SummaryTreeMotion.lerp(it.first, it.second, t) }
                ?: current.anchor?.let { key ->
                    shown[key]?.let { SummaryTreeMotion.followAnchor(it.location, current.anchorOffset, extent, port.extentSize) }
                }
            if (target != null && port.viewPosition != target) port.viewPosition = target
        }
        if (done) endMotion()
        repaint()
    }

    private fun endMotion() {
        val current = motion ?: return
        current.timer.stop()
        motion = null
        current.ghosts.forEach { remove(it.label); shown.remove(it.key) }
        edges = layout.branches.map { Edge(it.first, it.second) }
        updateExtent()
    }

    /** Ends a running transition at once, leaving the parts of its target layout where they are going. */
    private fun stopMotion() {
        val current = motion ?: return
        current.timer.stop()
        motion = null
        current.ghosts.forEach { remove(it.label) }
    }

    /** A button tall enough for its text wrapped at [width], with room above and below, so nothing hides behind its edges. */
    private fun heightFor(label: JButton, width: Int): Int {
        val insets = label.insets
        val view = label.getClientProperty(BasicHTML.propertyKey) as? View
        val text = if (view == null) label.getFontMetrics(label.font).height else {
            view.setSize((width - insets.left - insets.right).coerceAtLeast(1).toFloat(), 0f)
            Math.ceil(view.getPreferredSpan(View.Y_AXIS).toDouble()).toInt()
        }
        return maxOf(scaled(MIN_HEIGHT), maxOf(text + insets.top + insets.bottom, label.preferredSize.height) + 2 * scaled(PADDING))
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
            val fits = block.fill(full, card, width, 1.0, actions, vocabulary, if (state.tagsCloud) wordWeights else null)
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
        edges.forEach { (parent, child, alpha) ->
            val from = shown[parent] ?: return@forEach
            val to = shown[child] ?: return@forEach
            val color = if (child in reviewed) dimmed else branch
            graphics.color = if (alpha >= 1.0) color else ColorUtil.withAlpha(color, alpha.coerceIn(0.0, 1.0) * (color.alpha / 255.0))
            val x = (from.x + from.width).toDouble(); val y = from.centerY
            graphics.draw(CubicCurve2D.Double(x, y, x + scaled(25), y, to.x - scaled(25).toDouble(), to.centerY, to.x.toDouble(), to.centerY))
        }
        graphics.dispose()
    }
    override fun dispose() {
        stopMotion()
        editors.values.forEach { Disposer.dispose(it.editor as Disposable) }; editors.clear()
    }

    private companion object {
        /** An editor's size in unzoomed pixels before the reader resizes it: wide enough for a side-by-side diff, tall enough for much of the file. */
        fun defaultEditorSize(file: ReviewSource): Dimension {
            val lines = maxOf(file.text.orEmpty().lines().size, (file as? PatchSource)?.patch.orEmpty().lines().size, 1)
            val width = if (file is ReviewFile) DIFF_WIDTH else SOURCE_WIDTH
            return Dimension(width, (lines * LINE_HEIGHT + EDITOR_CHROME).coerceIn(MIN_EDITOR_HEIGHT, MAX_EDITOR_HEIGHT))
        }

        const val DIFF_WIDTH = 960
        const val SOURCE_WIDTH = 800
        const val LINE_HEIGHT = 20
        /** Title bar, resize handle and editor margins. */
        const val EDITOR_CHROME = 72
        const val MIN_EDITOR_HEIGHT = 300
        const val MAX_EDITOR_HEIGHT = 640
        const val MAX_DIRECTORY = 48
        const val FULL_SUMMARY_WIDTH = 520
        const val FULL_SUMMARY_HEIGHT = 560
        /** The tree's text is 10% larger than the IDE's label font; the box sizes below follow it. */
        const val FONT_SCALE = 1.1
        const val WRAP_WIDTH = 330
        const val MIN_WIDTH = 120
        const val MAX_WIDTH = 484
        const val MIN_HEIGHT = 33
        /** Space above and below a part's text, inside its box. */
        const val PADDING = 4
        /** Space between sibling parts. */
        const val GAP = 14
        const val REVEAL_MARGIN = 40
    }
}
