package dev.insight.diff

import com.intellij.diff.DiffManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ui.EvidenceLine
import dev.insight.review.ui.LineSide
import dev.insight.review.ui.LineTrace
import dev.insight.review.ui.ReviewTab
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.HierarchyEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ToolTipManager
import javax.swing.text.JTextComponent
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/** What the Diffs wall shows: [scope] names it and keeps its layout; [files] are its current snapshot files, in order. */
internal data class DiffsWallContent(val scope: DiffsWallScope, val files: List<ReviewFile>)

/**
 * The Diffs wall page: one custom-painted `git diff` block per file on a pannable, zoomable surface. Blocks drag anywhere and come
 * to front; their positions last while the scope stays the same, so the one [card] is reused across canvas rebuilds. Painting
 * rather than embedding editors keeps a wall of many files light; positions are kept at 100% and painted at the zoom, with the
 * editor font scaled, so text stays crisp and hit-testing stays plain Swing.
 */
internal class DiffsWallView(
    /** Opens [ReviewFile]'s native diff, at the line when one was double-clicked. */
    private val openDiff: (ReviewFile, WallLine?) -> Unit,
    /** Shown as a link when the wall holds a subset of the change. */
    private val showAllChanges: () -> Unit,
) {
    private val label = JLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBFont.small()
        toolTipText = CONTROLS
    }
    private val zoomLabel = JLabel("100%").apply {
        name = "diffs-wall-zoom"
        foreground = ColorUtil.withAlpha(UIUtil.getContextHelpForeground(), 0.8)
        font = JBFont.small()
        toolTipText = "Zoom; click for 100% (${MENU}0)"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { zoomTo(1.0) }
        })
    }
    private val allLink = ActionLink("Show all changes") { showAllChanges() }.apply { font = JBFont.small() }
    private val surface = Surface()
    private val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
        isOpaque = false
        add(label)
        add(zoomLabel)
        add(allLink)
    }
    val card: JPanel = JPanel(BorderLayout()).apply {
        name = "diffs-wall"
        background = UIUtil.getPanelBackground()
        add(header, BorderLayout.NORTH)
        add(surface, BorderLayout.CENTER)
    }
    private var scope: DiffsWallScope? = null
    private var files: List<ReviewFile> = emptyList()
    /** Where each block sits at 100%, by path. */
    private val positions = mutableMapOf<String, Point>()
    private var views: List<BlockView> = emptyList()
    /** Compact blocks by path, with the height (at 100%) each is capped at. */
    private val compact = mutableMapOf<String, Int>()
    /** Blocks the reader turned compact off for, which a pack must not make compact again. */
    private val expanded = mutableSetOf<String>()
    /** How far (at 100%) each compact block's body is scrolled. */
    private val scrolls = mutableMapOf<String, Int>()
    /** Set once the reader moves a block: the layout is theirs and viewport changes no longer repack it. */
    private var arranged = false
    /** The viewport size the blocks were last packed for; null asks for a pack. */
    private var packedFor: Dimension? = null
    private var zoom = 1.0
    /** Set once the reader zooms, so a later pack keeps their zoom. */
    private var zoomTouched = false
    private var activePath: String? = null
    /** The zoom and view position from before Tab navigation zoomed in, restored by Escape. */
    private var overview: Pair<Double, Point>? = null
    private var animation: Timer? = null
    private var watched: JScrollPane? = null
    private val resized = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            if (!arranged) { surface.revalidate(); surface.repaint() }
        }
    }

    /** The blocks in the scope's file order. */
    internal val blocks: List<BlockView> get() = views

    /** Shows [content]; a new scope relays the blocks out, the same scope keeps where they were moved. */
    fun show(content: DiffsWallContent) {
        val count = content.files.size
        label.text = "Diffs wall · ${content.scope.title} · $count file${if (count == 1) "" else "s"}"
        allLink.isVisible = content.scope.paths != null
        if (content.scope == scope && content.files == files) return
        if (content.scope != scope) {
            stopAnimation()
            positions.clear()
            compact.clear()
            expanded.clear()
            scrolls.clear()
            arranged = false
            zoomTouched = false
            setZoom(1.0)
            activePath = null
            overview = null
            // The canvas restores the page's previous scroll after its rebuild; a new layout starts at the top instead.
            SwingUtilities.invokeLater { SwingUtilities.invokeLater { viewport()?.viewPosition = Point(0, 0); focusWhenOpened() } }
        }
        packedFor = null
        scope = content.scope
        files = content.files
        val metrics = metrics()
        val wall = content.files.map { wallBlock(it) }
        val width = wallBlockWidth(wall, metrics)
        surface.removeAll()
        views = wall.map { BlockView(it, width, metrics) }
        views.forEach(surface::add)
        // Files that join an arranged scope go below the others rather than reshuffling them.
        if (arranged) views.filter { it.block.file.path !in positions }.forEach { joined ->
            val bottom = views.filter { it.block.file.path in positions }
                .maxOfOrNull { positions.getValue(it.block.file.path).y + it.logical.height } ?: 0
            positions[joined.block.file.path] = Point(metrics.gap, bottom + metrics.gap)
        }
        surface.revalidate()
        surface.repaint()
    }

    private fun viewport(): JViewport? = SwingUtilities.getAncestorOfClass(JViewport::class.java, surface) as? JViewport

    private fun scrollPane(): JScrollPane? = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, surface) as? JScrollPane

    private fun scheme(): EditorColorsScheme = EditorColorsManager.getInstance().globalScheme

    private fun textFont(): Font = scheme().let { it.getFont(EditorFontType.PLAIN).deriveFont(it.editorFontSize2D) }

    private fun headerFont(): Font = textFont().let { it.deriveFont(it.size2D * 0.92f) }

    /** Measures at 100%. */
    private fun metrics(): WallMetrics {
        val text = surface.getFontMetrics(textFont())
        val header = surface.getFontMetrics(headerFont())
        val padding = JBUI.scale(6)
        return WallMetrics(text.charWidth('m'), text.height, header.height + 2 * padding, padding, JBUI.scale(24))
    }

    private val gap: Int get() = JBUI.scale(24)

    /** Each block's place and size at 100%, in file order. */
    private fun rects(): List<WallRect> = views.map { view ->
        val at = positions[view.block.file.path] ?: Point(0, 0)
        WallRect(at.x, at.y, view.logical.width, view.logical.height)
    }

    /**
     * The size the wall has to spread over: the page's scroll pane less a scrollbar each way and the label row, which stays put
     * whether scrollbars show; null while the page is too small to be real yet.
     */
    private fun available(): Dimension? {
        val pane = scrollPane() ?: return null
        val insets = pane.insets
        val width = pane.width - insets.left - insets.right - pane.verticalScrollBar.preferredSize.width
        val height = pane.height - insets.top - insets.bottom - pane.horizontalScrollBar.preferredSize.height - header.preferredSize.height
        return Dimension(width, height).takeIf { width >= JBUI.scale(240) && height >= JBUI.scale(120) }
    }

    /**
     * Packs the blocks for the space the page offers and picks a zoom that shows several columns, until the reader moves a block;
     * before the page has a real size a wide default keeps the preferred size sensible.
     */
    private fun ensureLayout() {
        if (arranged || views.isEmpty()) return
        val available = available()
        val target = available ?: Dimension(JBUI.scale(1600), 0)
        if (target == packedFor) return
        packedFor = target
        if (available != null) {
            // A huge diff opens compact rather than towering over the canvas.
            val cap = wallCompactHeight(available.height, zoom, views.first().metrics)
            views.filter { val path = it.block.file.path; path !in compact && path !in expanded }
                .filter { it.fullHeight > cap && wallDefaultCompact(it.fullHeight, available.height, zoom) }
                .forEach { compact[it.block.file.path] = cap }
        }
        val heights = views.map { it.logical.height }
        val width = views.first().logical.width
        val rects = wallLayout(heights, width, wallColumns(heights, width, gap, target.width, target.height), gap)
        views.zip(rects).forEach { (view, rect) -> positions[view.block.file.path] = Point(rect.x, rect.y) }
        if (available != null && !zoomTouched) setZoom(wallInitialZoom(wallExtent(rects, gap), available.width, available.height))
    }

    private fun setZoom(value: Double) {
        zoom = value
        zoomLabel.text = "${(value * 100).roundToInt()}%"
    }

    /** Where the surface starts in the scrolled view, below the label row. */
    private fun surfaceOffset(viewport: JViewport): Point =
        viewport.view?.let { SwingUtilities.convertPoint(surface, 0, 0, it) } ?: Point(0, 0)

    private fun clamped(viewport: JViewport, x: Int, y: Int): Point {
        val view = viewport.view ?: return Point(0, 0)
        return Point(x.coerceIn(0, (view.width - viewport.width).coerceAtLeast(0)), y.coerceIn(0, (view.height - viewport.height).coerceAtLeast(0)))
    }

    private fun scrollTo(x: Int, y: Int) {
        val viewport = viewport() ?: return
        viewport.viewPosition = clamped(viewport, x, y)
    }

    private fun scrollBy(dx: Int, dy: Int) {
        stopAnimation()
        val viewport = viewport() ?: return
        val at = viewport.viewPosition
        scrollTo(at.x + dx, at.y + dy)
    }

    /** Paints the wall at [value] and scrolls to ([x], [y]) once the page has measured it again. */
    private fun place(value: Double, x: Int, y: Int) {
        setZoom(value)
        surface.revalidate()
        (scrollPane() ?: viewport())?.validate()
        scrollTo(x, y)
        surface.repaint()
    }

    /** Zooms to [target], keeping the content under [anchor] (in the viewport; its centre by default) where it is. */
    private fun zoomTo(target: Double, anchor: Point? = null) {
        stopAnimation()
        val next = wallClampZoom(target)
        zoomTouched = true
        val viewport = viewport() ?: return place(next, 0, 0)
        val at = anchor ?: Point(viewport.width / 2, viewport.height / 2)
        val offset = surfaceOffset(viewport)
        val position = viewport.viewPosition
        place(next, wallZoomAround(position.x.toDouble(), at.x.toDouble(), offset.x.toDouble(), zoom, next).roundToInt(),
            wallZoomAround(position.y.toDouble(), at.y.toDouble(), offset.y.toDouble(), zoom, next).roundToInt())
    }

    /** Shows the whole wall from its top-left corner. */
    private fun fit() {
        val viewport = viewport() ?: return
        val offset = surfaceOffset(viewport)
        zoomTouched = true
        animate(wallFitZoom(wallExtent(rects(), gap), viewport.width - offset.x, viewport.height - offset.y), Point(0, 0))
    }

    /** The height (at 100%) a block turned compact now would be capped at, or null before the page has a viewport. */
    private fun compactCap(metrics: WallMetrics): Int? =
        viewport()?.height?.takeIf { it > 0 }?.let { wallCompactHeight(it, zoom, metrics) }

    /** Turns [view] compact or back; an auto-packed wall packs again, an arranged one keeps its positions. */
    private fun toggleCompact(view: BlockView) {
        val path = view.block.file.path
        if (compact.remove(path) != null) expanded += path
        else {
            val cap = compactCap(view.metrics)?.takeIf { view.fullHeight > it } ?: return
            compact[path] = cap
            expanded -= path
            scrolls.remove(path)
        }
        if (!arranged) packedFor = null
        surface.revalidate()
        surface.repaint()
    }

    private fun activate(view: BlockView) {
        val previous = views.firstOrNull { it.block.file.path == activePath }
        activePath = view.block.file.path
        surface.setComponentZOrder(view, 0)
        previous?.repaint()
        view.repaint()
    }

    /** Activates the next block in reading order, or the previous one, and zooms and scrolls to read it. */
    private fun step(forward: Boolean) {
        if (views.isEmpty()) return
        val rects = rects()
        val current = views.indexOfFirst { it.block.file.path == activePath }.takeIf { it >= 0 }
        val next = wallNextBlock(wallReadingOrder(rects, gap), current, forward) ?: return
        activate(views[next])
        val viewport = viewport() ?: return
        if (overview == null) overview = zoom to viewport.viewPosition
        val margin = gap
        val capped = views[next].block.file.path in compact
        val target = wallFocusZoom(rects[next].width, viewport.width, zoom, margin,
            blockHeight = rects[next].height.takeIf { capped }, viewportHeight = viewport.height)
        val offset = surfaceOffset(viewport)
        zoomTouched = true
        animate(target, wallFocusPosition(rects[next], target, offset.x, offset.y, viewport.width, viewport.height, margin))
    }

    /** Returns to the zoom and scroll from before Tab navigation, keeping the active block. */
    private fun backToOverview() {
        val (value, position) = overview ?: return
        overview = null
        animate(value, position)
    }

    /** Eases the zoom and scroll to [target] and [position] over a moment; at once when not on screen. */
    private fun animate(target: Double, position: Point) {
        stopAnimation()
        val viewport = viewport()
        if (viewport == null || !surface.isShowing || ApplicationManager.getApplication()?.isUnitTestMode != false) {
            place(target, position.x, position.y)
            return
        }
        val fromZoom = zoom
        val from = viewport.viewPosition
        val started = System.nanoTime()
        animation = Timer(15) {
            val t = ((System.nanoTime() - started) / 1e6 / ANIMATION_MS).coerceAtMost(1.0)
            val eased = 1 - (1 - t).pow(3)
            place(fromZoom + (target - fromZoom) * eased, (from.x + (position.x - from.x) * eased).roundToInt(),
                (from.y + (position.y - from.y) * eased).roundToInt())
            if (t >= 1.0) stopAnimation()
        }.apply { start() }
    }

    private fun stopAnimation() {
        animation?.stop()
        animation = null
    }

    /** Focuses the surface when the wall is opened from its own review tab, but not away from text the reader is typing. */
    private fun focusWhenOpened() {
        if (!surface.isShowing) return
        val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val tab = SwingUtilities.getAncestorOfClass(ReviewTab::class.java, surface)
        if (owner == null || owner !is JTextComponent && tab != null && SwingUtilities.isDescendingFrom(owner, tab)) surface.requestFocusInWindow()
    }

    private fun watch(pane: JScrollPane?) {
        if (pane === watched) return
        watched?.removeComponentListener(resized)
        watched = pane
        pane?.addComponentListener(resized)
    }

    /** One keyboard command of the wall, bound to its [strokes] while the surface has focus. */
    private class Key(val name: String, val strokes: List<KeyStroke>, val enabled: () -> Boolean = { true }, val run: () -> Unit)

    private fun keys(): List<Key> {
        fun key(code: Int, modifiers: Int = 0) = KeyStroke.getKeyStroke(code, modifiers)
        fun menu(code: Int, extra: Int = 0) = listOf(key(code, InputEvent.CTRL_DOWN_MASK or extra), key(code, InputEvent.META_DOWN_MASK or extra))
        val shift = InputEvent.SHIFT_DOWN_MASK
        val step = JBUI.scale(48)
        fun page(vertical: Boolean) = viewport()?.let { if (vertical) it.height else it.width }?.minus(step)?.coerceAtLeast(step) ?: step
        fun far() = viewport()?.view?.let { maxOf(it.width, it.height) } ?: 0
        return listOf(
            Key("up", listOf(key(KeyEvent.VK_UP))) { scrollBy(0, -step) },
            Key("down", listOf(key(KeyEvent.VK_DOWN))) { scrollBy(0, step) },
            Key("left", listOf(key(KeyEvent.VK_LEFT), key(KeyEvent.VK_LEFT, shift), key(KeyEvent.VK_UP, shift))) { scrollBy(-step, 0) },
            Key("right", listOf(key(KeyEvent.VK_RIGHT), key(KeyEvent.VK_RIGHT, shift), key(KeyEvent.VK_DOWN, shift))) { scrollBy(step, 0) },
            Key("page-up", listOf(key(KeyEvent.VK_PAGE_UP))) { scrollBy(0, -page(true)) },
            Key("page-down", listOf(key(KeyEvent.VK_PAGE_DOWN))) { scrollBy(0, page(true)) },
            Key("page-left", listOf(key(KeyEvent.VK_PAGE_UP, shift))) { scrollBy(-page(false), 0) },
            Key("page-right", listOf(key(KeyEvent.VK_PAGE_DOWN, shift))) { scrollBy(page(false), 0) },
            Key("top", listOf(key(KeyEvent.VK_HOME))) { scrollBy(0, -far()) },
            Key("bottom", listOf(key(KeyEvent.VK_END))) { scrollBy(0, far()) },
            Key("start", menu(KeyEvent.VK_HOME)) { scrollBy(-far(), -far()) },
            Key("end", menu(KeyEvent.VK_END)) { scrollBy(far(), far()) },
            Key("zoom-in", menu(KeyEvent.VK_EQUALS) + menu(KeyEvent.VK_EQUALS, shift) + menu(KeyEvent.VK_PLUS) + menu(KeyEvent.VK_ADD)) {
                zoomTo(wallZoomBy(zoom, KEY_ZOOM))
            },
            Key("zoom-out", menu(KeyEvent.VK_MINUS) + menu(KeyEvent.VK_SUBTRACT)) { zoomTo(wallZoomBy(zoom, 1 / KEY_ZOOM)) },
            Key("zoom-reset", menu(KeyEvent.VK_0) + menu(KeyEvent.VK_NUMPAD0)) { zoomTo(1.0) },
            Key("zoom-fit", listOf(key(KeyEvent.VK_F)) + menu(KeyEvent.VK_0, shift)) { fit() },
            Key("next-block", listOf(key(KeyEvent.VK_TAB))) { step(forward = true) },
            Key("previous-block", listOf(key(KeyEvent.VK_TAB, shift))) { step(forward = false) },
            Key("overview", listOf(key(KeyEvent.VK_ESCAPE)), { overview != null }) { backToOverview() },
            Key("open-diff", listOf(key(KeyEvent.VK_ENTER)), { activePath != null }) {
                views.firstOrNull { it.block.file.path == activePath }?.let { openDiff(it.block.file, null) }
            },
            Key("compact", listOf(key(KeyEvent.VK_C)), { activePath != null }) {
                views.firstOrNull { it.block.file.path == activePath }?.let(::toggleCompact)
            },
        )
    }

    /** Pans the page while a mouse button [accepts] is dragged on [target]; the pointer's screen travel is zoom-independent. */
    private inner class Pan(private val target: JComponent, private val accepts: (MouseEvent) -> Boolean) : MouseAdapter() {
        private var pressed: Point? = null
        private var origin = Point()
        override fun mousePressed(e: MouseEvent) {
            if (!accepts(e)) return
            stopAnimation()
            origin = viewport()?.viewPosition ?: return
            pressed = e.locationOnScreen
            target.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        }
        override fun mouseDragged(e: MouseEvent) {
            val start = pressed ?: return
            scrollTo(origin.x - (e.locationOnScreen.x - start.x), origin.y - (e.locationOnScreen.y - start.y))
        }
        override fun mouseReleased(e: MouseEvent) {
            if (pressed == null) return
            pressed = null
            target.cursor = Cursor.getDefaultCursor()
        }
    }

    /** The null-layout, focusable surface: empty space pans the enclosing page, Ctrl/Cmd + wheel zooms, keys scroll and step. */
    private inner class Surface : JComponent() {
        init {
            name = "diffs-wall-surface"
            layout = null
            isFocusable = true
            // Tab and Shift+Tab step through blocks rather than leaving the wall.
            focusTraversalKeysEnabled = false
            val pan = Pan(this) { SwingUtilities.isLeftMouseButton(it) || SwingUtilities.isMiddleMouseButton(it) }
            addMouseListener(pan)
            addMouseMotionListener(pan)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { requestFocusInWindow() }
            })
            addMouseWheelListener { e ->
                if (e.isControlDown || e.isMetaDown) {
                    e.consume()
                    val viewport = viewport()
                    zoomTo(wallZoomBy(zoom, WHEEL_ZOOM.pow(-e.preciseWheelRotation)), viewport?.let { SwingUtilities.convertPoint(this, e.point, it) })
                } else {
                    // Listening for the wheel takes it from the page; plain scrolling goes back to it.
                    scrollPane()?.let { it.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, it)) }
                }
            }
            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) { repaintActive() }
                override fun focusLost(e: FocusEvent) { repaintActive() }
            })
            addHierarchyListener { e ->
                watch(scrollPane())
                if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) SwingUtilities.invokeLater { focusWhenOpened() }
            }
            keys().forEach { key ->
                val action = object : AbstractAction(key.name) {
                    override fun actionPerformed(e: ActionEvent?) { if (key.enabled()) key.run() }
                    override fun isEnabled() = key.enabled()
                }
                actionMap.put(key.name, action)
                key.strokes.forEach { getInputMap(WHEN_FOCUSED).put(it, key.name) }
                // Component shortcuts come before the IDE keymap, whose editor actions would otherwise take keys like Tab or Escape.
                object : DumbAwareAction() {
                    override fun actionPerformed(e: AnActionEvent) { key.run() }
                    override fun update(e: AnActionEvent) { e.presentation.isEnabled = key.enabled() }
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                }.registerCustomShortcutSet(CustomShortcutSet(*key.strokes.map { KeyboardShortcut(it, null) }.toTypedArray()), this)
            }
        }

        private fun repaintActive() { views.firstOrNull { it.block.file.path == activePath }?.repaint() }

        override fun doLayout() {
            ensureLayout()
            views.forEach { view ->
                val at = positions[view.block.file.path] ?: Point(0, 0)
                val size = view.preferredSize
                view.setBounds((at.x * zoom).roundToInt(), (at.y * zoom).roundToInt(), size.width, size.height)
            }
        }

        override fun getPreferredSize(): Dimension {
            ensureLayout()
            if (views.isEmpty()) return Dimension(gap, gap)
            val extent = wallExtent(rects(), gap)
            return Dimension((extent.width * zoom).roundToInt(), (extent.height * zoom).roundToInt())
        }

        override fun paintComponent(g: Graphics) {
            if (views.isNotEmpty()) return
            g.color = UIUtil.getContextHelpForeground()
            g.font = JBFont.label()
            g.drawString("No changed files in this scope", JBUI.scale(24), JBUI.scale(24) + g.fontMetrics.ascent)
        }
    }

    /**
     * One file's diff, painted as `git diff` prints it under a quiet path header; dragging it anywhere moves it. A compact block is
     * capped in height and its body scrolls under the fixed header.
     */
    internal inner class BlockView(val block: WallBlock, private val blockWidth: Int, internal val metrics: WallMetrics) : JComponent() {
        /** The block's whole height at 100%. */
        val fullHeight = wallBlockHeight(block, metrics)
        /** The cap (at 100%) while the block is compact. */
        private val cap: Int? get() = compact[block.file.path]?.takeIf { it < fullHeight }
        internal val isCompact: Boolean get() = cap != null
        /** The block's painted size at 100%. */
        val logical: Dimension get() = Dimension(blockWidth, cap ?: fullHeight)
        /** Whether Tab, Enter, C and the focus outline are on this block. */
        internal val active: Boolean get() = activePath == block.file.path
        private val offset: Int get() = if (isCompact) scrolls[block.file.path] ?: 0 else 0
        /** Where the Compact switch was last painted, or null when the block is too short for it. */
        private var toggle: Rectangle? = null

        init {
            name = "diffs-wall-block:${block.file.path}"
            val drag = object : MouseAdapter() {
                /** Where the block was grabbed at 100%, so it moves with the pointer rather than jumping to it. */
                private var grab: Point2D.Double? = null
                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    surface.requestFocusInWindow()
                    activate(this@BlockView)
                    if (onToggle(e.point)) {
                        toggleCompact(this@BlockView)
                        return
                    }
                    grab = Point2D.Double(e.x / zoom, e.y / zoom)
                }
                override fun mouseDragged(e: MouseEvent) {
                    val offset = grab ?: return
                    stopAnimation()
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                    // Measured on the surface, so the block stays under the pointer while the page auto-scrolls.
                    val pointer = SwingUtilities.convertPoint(this@BlockView, e.point, surface)
                    val at = Point((pointer.x / zoom - offset.x).roundToInt().coerceAtLeast(0), (pointer.y / zoom - offset.y).roundToInt().coerceAtLeast(0))
                    positions[block.file.path] = at
                    // A moved block is the reader's layout; a later viewport size must not repack it.
                    arranged = true
                    location = Point((at.x * zoom).roundToInt(), (at.y * zoom).roundToInt())
                    surface.revalidate()
                    surface.scrollRectToVisible(Rectangle(pointer.x, pointer.y, 1, 1))
                }
                override fun mouseReleased(e: MouseEvent) {
                    if (grab == null) return
                    grab = null
                    cursor = Cursor.getDefaultCursor()
                }
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2 && !onToggle(e.point)) openDiff(block.file, lineAt(e.y))
                }
                override fun mouseMoved(e: MouseEvent) {
                    cursor = if (onToggle(e.point)) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                }
            }
            addMouseListener(drag)
            addMouseMotionListener(drag)
            val pan = Pan(this) { SwingUtilities.isMiddleMouseButton(it) }
            addMouseListener(pan)
            addMouseMotionListener(pan)
            // A compact body takes the wheel while it has more to show; the canvas gets the rest, and Ctrl/Cmd for zoom.
            addMouseWheelListener { e ->
                val cap = cap
                val moved = if (cap == null || e.isControlDown || e.isMetaDown || e.isShiftDown) null
                else wallBodyScroll(offset, (e.preciseWheelRotation * WHEEL_ROWS * metrics.lineHeight).roundToInt(), fullHeight, cap)
                if (moved != null && moved != offset) {
                    e.consume()
                    scrolls[block.file.path] = moved
                    repaint()
                } else surface.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, surface))
            }
            autoscrolls = true
            ToolTipManager.sharedInstance().registerComponent(this)
        }

        private fun onToggle(point: Point) = toggle?.contains(point) == true

        override fun getToolTipText(event: MouseEvent): String? = when {
            !onToggle(event.point) -> null
            isCompact -> "Show the whole diff (C)"
            else -> "Cap the block's height; its diff scrolls inside (C)"
        }

        override fun getPreferredSize() = logical.let { Dimension((it.width * zoom).roundToInt(), (it.height * zoom).roundToInt()) }

        /** The row at [y] (painted, at the current zoom), or null on the header and padding. */
        internal fun lineAt(y: Int): WallLine? = wallRowAt(y / zoom, offset, metrics)?.let(block.lines::getOrNull)

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            val z = zoom
            val arc = (JBUI.scale(8) * z).roundToInt().coerceAtLeast(2)
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // Zoomed text keeps its advance in proportion, so rows stay as wide as the block was sized for.
                if (z != 1.0) g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                val scheme = scheme()
                val background = scheme.defaultBackground
                val muted = UIUtil.getContextHelpForeground()
                g2.color = background
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = ColorUtil.withAlpha(JBColor.border(), 0.6)
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.clipRect(1, 1, width - 2, height - 2)
                val text = textFont().let { it.deriveFont(it.size2D * z.toFloat()) }
                // Below a legible size rows are drawn as their shapes, which reads better in an overview and paints faster.
                val sketch = text.size2D < SKETCH_FONT_SIZE
                paintHeader(g2, scheme, muted, sketch)
                // The header stays put while a compact body scrolls beneath it.
                val bodyTop = (metrics.headerHeight * z).roundToInt()
                g2.clipRect(0, bodyTop, width, height - bodyTop)
                g2.font = text
                val ascent = g2.fontMetrics.ascent
                val pad = metrics.padding * z
                val lineHeight = metrics.lineHeight * z
                val top = (metrics.headerHeight + metrics.padding - offset) * z
                fun rowTop(index: Int) = (top + index * lineHeight).roundToInt()
                val clip = g2.clipBounds ?: Rectangle(0, 0, width, height)
                val from = floor((clip.y - top) / lineHeight).toInt().coerceAtLeast(0)
                val to = (ceil((clip.y + clip.height - top) / lineHeight).toInt() + 1).coerceAtMost(block.lines.size)
                val added = tint(scheme, DiffColors.DIFF_INSERTED, ADDED, background)
                val removed = tint(scheme, DiffColors.DIFF_DELETED, REMOVED, background)
                for (index in from until to) {
                    val line = block.lines[index]
                    val y = rowTop(index)
                    val next = rowTop(index + 1)
                    when (line.kind) {
                        WallLine.Kind.ADDED -> added
                        WallLine.Kind.REMOVED -> removed
                        else -> null
                    }?.let { g2.color = it; g2.fillRect(1, y, width - 2, next - y) }
                    g2.color = when (line.kind) {
                        WallLine.Kind.HUNK, WallLine.Kind.NOTE -> muted
                        else -> scheme.defaultForeground
                    }
                    if (sketch) sketch(g2, line.text, pad, y, next - y)
                    else g2.drawString(line.text, pad.toFloat(), (top + index * lineHeight + ascent).toFloat())
                }
                val footer = when {
                    block.lines.isEmpty() -> "No textual changes"
                    block.hiddenLines > 0 -> "⋯ ${block.hiddenLines} more lines; double-click to open the diff"
                    else -> null
                }
                if (footer != null && !sketch) {
                    g2.color = muted
                    g2.drawString(footer, pad.toFloat(), (top + block.lines.size * lineHeight + ascent).toFloat())
                }
                cap?.let { paintScrollIndicator(g2, it, bodyTop, muted) }
            } finally {
                g2.dispose()
            }
            if (active && surface.isFocusOwner) paintFocus(g, arc)
        }

        /** A thin, quiet thumb at the right edge showing which part of a compact body is in view. */
        private fun paintScrollIndicator(g2: Graphics2D, cap: Int, bodyTop: Int, muted: Color) {
            val inset = (metrics.padding * zoom).roundToInt().coerceAtLeast(1)
            val track = height - bodyTop - 2 * inset
            val body = fullHeight - metrics.headerHeight
            if (track <= 0 || body <= 0) return
            val thumb = (track.toDouble() * (cap - metrics.headerHeight) / body).roundToInt().coerceIn(JBUI.scale(8).coerceAtMost(track), track)
            val y = bodyTop + inset + ((track - thumb).toDouble() * offset / (fullHeight - cap)).roundToInt()
            val thickness = JBUI.scale(3)
            g2.color = ColorUtil.withAlpha(muted, 0.35)
            g2.fillRoundRect(width - thickness - JBUI.scale(2), y, thickness, thumb, thickness, thickness)
        }

        /** A thin focus-coloured outline in place of the border. */
        private fun paintFocus(g: Graphics, arc: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val stroke = JBUIScale.scale(1.5f)
                g2.color = JBUI.CurrentTheme.Focus.focusColor()
                g2.stroke = BasicStroke(stroke)
                g2.draw(RoundRectangle2D.Float(stroke / 2, stroke / 2, width - stroke, height - stroke, arc.toFloat(), arc.toFloat()))
            } finally {
                g2.dispose()
            }
        }

        /** [text]'s shape as a soft bar from its indent to its end, for zooms too small to read. */
        private fun sketch(g2: Graphics2D, text: String, pad: Double, y: Int, height: Int) {
            val indent = text.drop(1).takeWhile { it == ' ' }.length + 1
            val length = text.trimEnd().length - indent
            if (length <= 0) return
            val char = metrics.charWidth * zoom
            val x = (pad + indent * char).roundToInt()
            val color = g2.color
            g2.color = ColorUtil.withAlpha(color, 0.35)
            g2.fillRect(x, y + height / 4, (length * char).roundToInt().coerceAtMost(width - x).coerceAtLeast(1), (height / 2).coerceAtLeast(1))
            g2.color = color
        }

        /** The path with its folder muted, then the line counts and the Compact switch, in a smaller editor font. */
        private fun paintHeader(g2: Graphics2D, scheme: EditorColorsScheme, muted: Color, sketch: Boolean) {
            val z = zoom
            val pad = metrics.padding * z
            val headerHeight = metrics.headerHeight * z
            val title = block.title
            val split = title.lastIndexOf('/') + 1
            toggle = null
            if (sketch) {
                g2.color = scheme.defaultForeground
                sketch(g2, " " + title, pad - metrics.charWidth * z, (headerHeight / 4).roundToInt(), (headerHeight / 2).roundToInt())
                return
            }
            g2.font = headerFont().let { it.deriveFont(it.size2D * z.toFloat()) }
            val fm = g2.fontMetrics
            val baseline = ((headerHeight - fm.height) / 2 + fm.ascent + JBUI.scale(1) * z).toFloat()
            var x = pad.toFloat()
            g2.color = muted
            g2.drawString(title.substring(0, split), x, baseline)
            x += fm.stringWidth(title.substring(0, split))
            g2.color = scheme.defaultForeground
            g2.drawString(title.substring(split), x, baseline)
            x += fm.stringWidth(title.substring(split)) + fm.charWidth(' ') * 2
            g2.color = ColorUtil.withAlpha(muted, 0.8)
            val counts = "+${block.added} −${block.removed}"
            g2.drawString(counts, x, baseline)
            x += fm.stringWidth(counts) + fm.charWidth(' ') * 2
            // Only a block taller than the cap has anything to compact.
            if (!isCompact && compactCap(metrics)?.let { fullHeight > it } != true) return
            g2.font = g2.font.deriveFont(g2.font.size2D * 0.9f)
            val small = g2.fontMetrics
            g2.color = if (isCompact) JBUI.CurrentTheme.Link.Foreground.ENABLED else ColorUtil.withAlpha(muted, 0.7)
            g2.drawString(COMPACT, x, baseline)
            toggle = Rectangle(x.toInt() - JBUI.scale(2), 0, small.stringWidth(COMPACT) + JBUI.scale(4), headerHeight.roundToInt())
        }
    }

    private companion object {
        val ADDED = JBColor(0xDDF5E3, 0x2D4535)
        val REMOVED = JBColor(0xF9DEDE, 0x4D3134)
        const val KEY_ZOOM = 1.25
        const val WHEEL_ZOOM = 1.1
        const val ANIMATION_MS = 180.0
        const val SKETCH_FONT_SIZE = 4.5f
        const val WHEEL_ROWS = 3
        const val COMPACT = "Compact"
        val MENU = if (SystemInfo.isMac) "⌘" else "Ctrl+"
        val CONTROLS = """<html>Drag a block to move it; drag the empty canvas, or middle-drag anywhere, to pan<br>
            |Double-click a line, or press Enter on the active block, to open its diff<br>
            |${MENU}wheel or $MENU= / $MENU− zooms, ${MENU}0 resets to 100%, F or ${MENU}Shift+0 fits the whole wall<br>
            |Arrows, Page Up/Down and Home/End scroll; Shift scrolls sideways<br>
            |Tab / Shift+Tab step through the blocks, Esc returns to the overview<br>
            |Compact in a block's header, or C, caps a tall block; its diff scrolls inside</html>""".trimMargin()

        /** The scheme's diff colour softened toward the background, so dozens of tinted blocks stay calm. */
        fun tint(scheme: EditorColorsScheme, key: TextAttributesKey, fallback: Color, background: Color): Color =
            ColorUtil.mix(background, scheme.getAttributes(key)?.backgroundColor ?: fallback, 0.7)
    }
}

/** Opens [file]'s native diff, at [line] with it highlighted when the line has a number on a side that has text. */
internal fun openWallDiff(project: Project, file: ReviewFile, line: WallLine?) {
    val (side, number) = when {
        line?.new != null -> LineSide.AFTER to line.new
        line?.old != null -> LineSide.BEFORE to line.old
        else -> null to 0
    }
    // Without both sides the native diff shows the patch itself, whose lines are not the file's.
    if (side == null || file.before == null || file.after == null) {
        DiffManager.getInstance().showDiff(project, createDiffRequest(project, file))
        return
    }
    val evidence = EvidenceLine(side, number, line!!.text.drop(1), line.text.firstOrNull() ?: ' ', diff = true)
    showDiffLines(project, file, LineTrace(file.path, file.path, "", emptyList(), listOf(evidence)))
}
