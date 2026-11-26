package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.agent.SourceSide
import dev.insight.review.PatchSource
import dev.insight.review.PreviewPlacement
import dev.insight.review.PreviewSide
import dev.insight.review.RadarSeries
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import dev.insight.review.headingToPreview
import dev.insight.review.patchLineCounts
import dev.insight.review.treePreviewPlacement
import dev.insight.diff.DiffFileEditor
import dev.insight.diff.ReviewFile
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.AbstractAction
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

/** A Tree row whose source or diff the floating preview shows. */
internal data class TreeSourcePreviewTarget(val path: String, val directory: Boolean, val title: String)

/**
 * The floating source or diff window beside the Tree board: a header naming the file or folder with its facts and
 * actions, then one viewer for a file or a short stack of viewers for a folder's first files.
 */
internal class TreeSourcePreviewPanel(
    val target: TreeSourcePreviewTarget,
    sources: List<ReviewSource>,
    totalFiles: Int,
    private val project: Project?,
    private val createViewer: ((ReviewSource) -> JComponent)?,
    private val open: ((String) -> Unit)?,
    private val togglePin: () -> Unit,
    private val close: () -> Unit,
    private val resize: (dx: Int, done: Boolean) -> Unit,
    private val escape: () -> Unit = {},
) : JPanel(BorderLayout()), Disposable {
    private val viewers = mutableListOf<JComponent>()
    private val naturalBodyHeight: Int
    private val pinButton = InplaceButton(IconButton("Pin preview (P)", AllIcons.General.Pin_tab, AllIcons.General.Pin_tab)) {
        togglePin()
    }.apply {
        name = "charts-tree-source-preview-pin"
        cursor = Cursor.getDefaultCursor()
    }
    private val grip = ResizeGrip()
    var isPinned: Boolean = false
        set(value) {
            field = value
            pinButton.setIcons(if (value) AllIcons.General.PinSelected else AllIcons.General.Pin_tab,
                null, if (value) AllIcons.General.PinSelected else AllIcons.General.Pin_tab)
            pinButton.toolTipText = if (value) "Unpin preview (P)" else "Pin preview (P), so it stays while you explore"
            header.background = if (value) pinnedHeaderColor else headerColor
            repaint()
        }

    var side: PreviewSide = PreviewSide.RIGHT
        set(value) {
            if (field == value) return
            field = value
            remove(grip)
            add(grip, if (value == PreviewSide.RIGHT) BorderLayout.WEST else BorderLayout.EAST)
            revalidate()
        }

    private val header: JPanel

    init {
        name = "charts-tree-source-preview"
        isOpaque = true
        background = UIUtil.getListBackground()
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        header = header(sources, totalFiles)
        add(header, BorderLayout.NORTH)
        add(grip, BorderLayout.WEST)
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "charts-tree-source-preview-escape")
        actionMap.put("charts-tree-source-preview-escape", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = escape()
        })
        if (sources.isEmpty()) {
            val label = JLabel(if (target.directory) "No files to preview here" else "Source unavailable").apply {
                name = "charts-tree-source-preview-empty"
                foreground = UIUtil.getContextHelpForeground()
                horizontalAlignment = SwingConstants.CENTER
                border = JBUI.Borders.empty(16)
            }
            add(label, BorderLayout.CENTER)
            naturalBodyHeight = label.preferredSize.height
        } else if (!target.directory) {
            val viewer = viewer(sources.single())
            add(viewer, BorderLayout.CENTER)
            naturalBodyHeight = naturalViewerHeight(viewer, sources.single())
        } else {
            val list = WidthTrackingPanel().apply {
                name = "charts-tree-source-preview-files"
                border = JBUI.Borders.empty(6)
            }
            var height = JBUI.scale(12)
            for (source in sources) {
                val viewer = viewer(source)
                val viewerHeight = naturalViewerHeight(viewer, source).coerceAtMost(JBUI.scale(FOLDER_BLOCK_MAX_HEIGHT))
                viewer.preferredSize = Dimension(JBUI.scale(200), viewerHeight)
                val block = JPanel(BorderLayout()).apply {
                    name = "charts-tree-source-preview:${source.path}"
                    border = JBUI.Borders.customLine(JBColor.border(), 1)
                    add(fileTitle(source), BorderLayout.NORTH)
                    add(viewer, BorderLayout.CENTER)
                }
                list.add(block)
                height += block.preferredSize.height + JBUI.scale(FOLDER_GAP)
            }
            if (totalFiles > sources.size) {
                val more = JLabel("+ ${totalFiles - sources.size} more ${if (totalFiles - sources.size == 1) "file" else "files"}" +
                    " · preview a file to see it").apply {
                    name = "charts-tree-source-preview-more"
                    font = JBFont.small()
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.empty(2, 4)
                }
                list.add(more)
                height += more.preferredSize.height
            }
            add(JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                verticalScrollBar.unitIncrement = JBUI.scale(16)
            }, BorderLayout.CENTER)
            naturalBodyHeight = height
        }
    }

    /** The window height that shows all of its content without scrolling. */
    fun naturalHeight(): Int = header.preferredSize.height + naturalBodyHeight + insets.top + insets.bottom

    /** Distance from the window's top to its title's middle, to line the title up with the row. */
    fun titleCenter(): Int = insets.top + header.preferredSize.height / 2

    private fun header(sources: List<ReviewSource>, totalFiles: Int): JPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        name = "charts-tree-source-preview-header"
        isOpaque = true
        background = headerColor
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(5, 8, 5, 4))
        val name = target.path.substringAfterLast('/').ifEmpty { target.title }
        val parent = target.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && it != target.path }
        val title = JLabel(if (target.directory) "${target.title.removeSuffix("/")}/" else name).apply {
            this.name = "charts-tree-source-preview-title"
            icon = if (target.directory) AllIcons.Nodes.Folder else FileTypeManager.getInstance().getFileTypeByFileName(name).icon
            font = JBFont.label().asBold()
            toolTipText = target.path.ifEmpty { target.title }
            if (!target.directory && open != null) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "${target.path} · Double-click to open"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) open.invoke(target.path)
                    }
                })
            }
        }
        val facts = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            parent?.let { add(dim(it)) }
            for (fact in facts(sources, totalFiles)) add(fact)
        }
        val text = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            title.alignmentX = LEFT_ALIGNMENT
            facts.alignmentX = LEFT_ALIGNMENT
            add(title)
            add(facts)
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            if (!target.directory && open != null) add(InplaceButton(IconButton(
                if (sources.singleOrNull() is PatchSource) "Open diff (double-click the title)" else "Open in editor (double-click the title)",
                AllIcons.Actions.EditSource, AllIcons.Actions.EditSource,
            )) { open.invoke(target.path) }.apply {
                this.name = "charts-tree-source-preview-open"
                cursor = Cursor.getDefaultCursor()
            })
            add(pinButton)
            add(InplaceButton(IconButton("Close preview (Esc)", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                close()
            }.apply {
                this.name = "charts-tree-source-preview-close"
                cursor = Cursor.getDefaultCursor()
            })
        }
        add(text, BorderLayout.CENTER)
        add(actions, BorderLayout.EAST)
    }

    private fun facts(sources: List<ReviewSource>, totalFiles: Int): List<JComponent> {
        if (target.directory) {
            val shown = if (totalFiles > sources.size) " · first ${sources.size}" else ""
            return listOf(dim("$totalFiles ${if (totalFiles == 1) "file" else "files"}$shown"))
        }
        val source = sources.singleOrNull() ?: return emptyList()
        return if (source is PatchSource) {
            val (added, removed) = patchLineCounts(source.patch)
            listOfNotNull(
                colored("+$added", RadarChartPanel.color(RadarSeries.ADDED)),
                colored("−$removed", RadarChartPanel.color(RadarSeries.REMOVED)),
            ) + source.facts.map(::dim)
        } else {
            val lines = source.text?.let { if (it.isEmpty()) 0 else it.count { c -> c == '\n' } + if (it.endsWith('\n')) 0 else 1 }
            listOfNotNull(lines?.let { dim("$it ${if (it == 1) "line" else "lines"}") }) + source.facts.map(::dim)
        }
    }

    private fun fileTitle(source: ReviewSource): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        isOpaque = true
        background = headerColor
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(3, 4))
        val name = source.path.substringAfterLast('/')
        add(JLabel(source.path.removePrefix(target.path).removePrefix("/").ifEmpty { name }).apply {
            icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
            font = JBFont.small().asBold()
            toolTipText = source.path + if (open != null) " · Double-click to open" else ""
            if (open != null) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) open.invoke(source.path)
                    }
                })
            }
        })
        if (source is PatchSource) {
            val (added, removed) = patchLineCounts(source.patch)
            add(colored("+$added", RadarChartPanel.color(RadarSeries.ADDED)))
            add(colored("−$removed", RadarChartPanel.color(RadarSeries.REMOVED)))
        }
    }

    private fun dim(text: String) = JLabel(text).apply {
        font = JBFont.small()
        foreground = UIUtil.getContextHelpForeground()
    }

    private fun colored(text: String, color: Color) = JLabel(text).apply {
        font = JBFont.small().asBold()
        foreground = color
    }

    private fun viewer(source: ReviewSource): JComponent = (createViewer?.invoke(source) ?: defaultViewer(source)).also {
        viewers += it
    }

    private fun defaultViewer(source: ReviewSource): JComponent = if (source is ReviewFile) {
        DiffFileEditor(source, project)
    } else {
        val text = source.text.orEmpty()
        DiffSourceEditor(ReviewSourceBlock(ReviewSourceRange(source.path, 1, maxOf(1, lineCount(text)), SourceSide.CURRENT),
            text, fullText = text, absolutePath = source.absolutePath), project)
    }

    private fun naturalViewerHeight(viewer: JComponent, source: ReviewSource): Int = when (viewer) {
        is DiffSourceEditor -> viewer.editor.lineHeight * maxOf(1, viewer.editor.document.lineCount) + JBUI.scale(20)
        is DiffFileEditor -> {
            val patch = source as? PatchSource
            val lines = maxOf(lineCount(patch?.before), lineCount(patch?.after), lineCount(patch?.patch), 1)
            (viewer.editors.firstOrNull { !it.isDisposed }?.lineHeight ?: JBUI.scale(18)) * lines + JBUI.scale(64)
        }
        else -> viewer.preferredSize.height
    }

    override fun dispose() {
        viewers.forEach { (it as? Disposable)?.let(Disposer::dispose) }
        viewers.clear()
    }

    /** Drag the window's inner edge to make it wider or narrower. */
    private inner class ResizeGrip : JComponent() {
        private var start: Int? = null

        init {
            name = "charts-tree-source-preview-grip"
            preferredSize = Dimension(JBUI.scale(5), 0)
            cursor = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
            toolTipText = "Drag to resize"
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { start = e.xOnScreen }
                override fun mouseDragged(e: MouseEvent) {
                    val from = start ?: return
                    val dx = e.xOnScreen - from
                    start = e.xOnScreen
                    // Dragging the left edge left, or the right edge right, widens the window.
                    resize(if (side == PreviewSide.RIGHT) -dx else dx, false)
                }
                override fun mouseReleased(e: MouseEvent) {
                    start = null
                    resize(0, true)
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
        }

        override fun paintComponent(g: Graphics) {
            g.color = if (isPinned) alpha(JBUI.CurrentTheme.Focus.focusColor(), 150) else alpha(JBColor.border(), 120)
            val x = if (side == PreviewSide.RIGHT) 0 else width - JBUI.scale(2)
            g.fillRect(x, 0, JBUI.scale(2), height)
        }
    }

    private class WidthTrackingPanel : JPanel(VerticalLayout(JBUI.scale(FOLDER_GAP))), Scrollable {
        init { isOpaque = false }
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    companion object {
        const val FOLDER_BLOCK_MAX_HEIGHT = 320
        const val FOLDER_GAP = 8
        val headerColor = JBColor(Color(0xF3F5F8), Color(0x2B2D30))
        val pinnedHeaderColor = JBColor(Color(0xE8EEFA), Color(0x2E3440))

        private fun lineCount(text: String?): Int =
            if (text.isNullOrEmpty()) 0 else text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1

        private fun alpha(color: Color, alpha: Int) = Color(color.red, color.green, color.blue, alpha)
    }
}

/**
 * Decides what the Tree's floating source preview shows and where. It follows the hovered row, or else the cursor row,
 * while [following] is on; a pinned row wins over both and shows even while following is off.
 *
 * Changes wait a little, so sweeping the pointer across rows doesn't rebuild editors on every row, and they wait longer
 * while the pointer is heading for the window, so the reader can reach it across other rows. Recently shown windows are
 * kept, so returning to a row is instant.
 */
internal class TreeSourcePreviewController(
    private val host: JComponent,
    private val sources: (TreeSourcePreviewTarget) -> Pair<List<ReviewSource>, Int>,
    /** The anchor row's screen bounds on [host], or null while it is hidden. */
    private val anchorBounds: (String) -> Rectangle?,
    private val project: Project?,
    private val createViewer: ((ReviewSource) -> JComponent)?,
    private val open: ((String) -> Unit)?,
    following: Boolean,
    width: Int?,
    private val followingChanged: (Boolean) -> Unit,
    private val pinnedChanged: (String?) -> Unit,
    private val widthChanged: (Int) -> Unit,
    /** Returns keyboard focus to the board, on Escape inside a window. */
    private val escape: () -> Unit,
    private val changed: () -> Unit,
) : Disposable {
    var following: Boolean = following
        private set
    var pinned: TreeSourcePreviewTarget? = null
        private set
    private var hovered: TreeSourcePreviewTarget? = null
    private var cursor: TreeSourcePreviewTarget? = null
    private var dismissed: String? = null
    private var preferredWidth: Int? = width

    /** The window on screen, if any. */
    var shown: TreeSourcePreviewPanel? = null
        private set
    var placement: PreviewPlacement? = null
        private set
    private var lastAnchor: Rectangle? = null
    private var lastPointer: Point? = null
    private var headingToWindow = false
    private val cache = object : LinkedHashMap<TreeSourcePreviewTarget, TreeSourcePreviewPanel>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TreeSourcePreviewTarget, TreeSourcePreviewPanel>): Boolean {
            if (size <= CACHED_WINDOWS || eldest.value === shown) return false
            release(eldest.value)
            return true
        }
    }
    private val timer = Timer(0) { apply() }.apply { isRepeats = false }

    val shownTarget: TreeSourcePreviewTarget? get() = shown?.target

    /** What the window should show now, ignoring delays. */
    private fun desired(): TreeSourcePreviewTarget? =
        (pinned ?: if (following) hovered ?: cursor else null)?.takeUnless { it.path == dismissed && it != pinned }

    fun setFollowing(on: Boolean) {
        if (following == on) return
        following = on
        dismissed = null
        followingChanged(on)
        apply(force = true)
    }

    /** Pins [target], or unpins it when it is already pinned. */
    fun togglePin(target: TreeSourcePreviewTarget) {
        pin(if (pinned?.path == target.path) null else target)
    }

    fun pin(target: TreeSourcePreviewTarget?) {
        if (pinned == target) return
        pinned = target
        dismissed = null
        pinnedChanged(target?.path)
        apply(force = true)
    }

    /** Updates the rows the window can follow; a change waits for the reader to settle on a row. */
    fun track(hover: TreeSourcePreviewTarget?, cursor: TreeSourcePreviewTarget?) {
        val hoverChanged = hover != hovered
        val cursorChanged = cursor != this.cursor
        if (!hoverChanged && !cursorChanged) return
        hovered = hover
        this.cursor = cursor
        if (dismissed != null && desiredPathIgnoringDismissal() != dismissed) dismissed = null
        if (pinned != null || !following) return
        val next = desired()
        if (next == shownTarget) {
            timer.stop()
            return
        }
        val delay = when {
            !hoverChanged -> KEY_DELAY_MS
            headingToWindow && shown != null -> HOLD_DELAY_MS
            next == null -> LEAVE_DELAY_MS
            shown == null -> OPEN_DELAY_MS
            else -> SWITCH_DELAY_MS
        }
        schedule(delay)
    }

    private fun desiredPathIgnoringDismissal(): String? = (pinned ?: if (following) hovered ?: cursor else null)?.path

    /** Notes where the pointer moved on [host], to tell whether it is heading for the window. */
    fun pointerMoved(point: Point) {
        val previous = lastPointer
        lastPointer = point
        val current = placement
        headingToWindow = previous != null && current != null && shown?.isVisible == true &&
            headingToPreview(previous.x, previous.y, point.x, point.y, current, JBUI.scale(INTENT_SLACK))
        if (headingToWindow && timer.isRunning) schedule(HOLD_DELAY_MS)
    }

    private fun schedule(delay: Int) {
        timer.stop()
        timer.initialDelay = delay
        timer.start()
    }

    /** Applies any pending change now; tests use it in place of waiting. */
    fun flush() {
        if (timer.isRunning) apply()
    }

    /** Hides the window until the reader moves on to another row, and unpins it. */
    fun dismiss() {
        val current = shownTarget ?: return
        if (pinned != null) {
            pinned = null
            pinnedChanged(null)
        }
        dismissed = current.path
        apply(force = true)
    }

    /** Shows what the window should show now; [force] skips the hold while the pointer is inside the window. */
    private fun apply(force: Boolean = false) {
        timer.stop()
        // The reader is inside the window: keep what it shows, and check again once they may have left it.
        if (!force && pinned == null && pointerInside()) {
            schedule(LEAVE_DELAY_MS)
            return
        }
        val next = desired()
        if (next == null) {
            hide()
            return
        }
        val panel = cache[next] ?: build(next).also { cache[next] = it }
        if (shown !== panel) {
            shown?.isVisible = false
            shown = panel
            if (panel.parent !== host) host.add(panel, 0)
        }
        panel.isPinned = pinned == next
        panel.isVisible = true
        lastAnchor = null
        position()
        changed()
    }

    private fun hide() {
        val current = shown ?: return
        current.isVisible = false
        shown = null
        placement = null
        host.repaint()
        changed()
    }

    private fun build(target: TreeSourcePreviewTarget): TreeSourcePreviewPanel {
        val (files, total) = sources(target)
        return TreeSourcePreviewPanel(target, files, total, project, createViewer, open,
            togglePin = { togglePin(target) },
            close = { dismiss() },
            resize = { dx, done -> resizeBy(dx, done) },
            escape = escape,
        )
    }

    private fun defaultWidth(): Int {
        val diff = shownTarget?.let { sources(it).first.any { source -> source is PatchSource } } == true
        val (fraction, max) = if (diff) 0.55 to DIFF_MAX_WIDTH else 0.42 to SOURCE_MAX_WIDTH
        return (host.width * fraction).roundToInt().coerceIn(JBUI.scale(MIN_WIDTH), JBUI.scale(max))
    }

    private fun resizeBy(dx: Int, done: Boolean) {
        val current = placement ?: return
        if (dx != 0) {
            preferredWidth = (current.width + dx).coerceIn(JBUI.scale(MIN_WIDTH), maxOf(JBUI.scale(MIN_WIDTH), host.width - JBUI.scale(MARGIN) * 2))
            position()
        }
        if (done) preferredWidth?.let(widthChanged)
    }

    /** Places the window beside its row; called when it changes, resizes, or the board does. */
    fun position() {
        val panel = shown ?: return
        if (host.width <= 0 || host.height <= 0) return
        val anchor = anchorBounds(panel.target.path)?.also { lastAnchor = it } ?: lastAnchor
            ?: Rectangle(0, host.height / 3, 0, 0)
        val next = treePreviewPlacement(
            viewWidth = host.width,
            viewHeight = host.height,
            anchorTop = anchor.y,
            anchorBottom = anchor.y + anchor.height,
            anchorLeft = anchor.x,
            anchorRight = anchor.x + anchor.width,
            width = preferredWidth ?: defaultWidth(),
            naturalHeight = panel.naturalHeight(),
            margin = JBUI.scale(MARGIN),
            gap = JBUI.scale(GAP),
            minWidth = JBUI.scale(MIN_WIDTH),
            minHeight = JBUI.scale(MIN_HEIGHT),
            headerOffset = panel.titleCenter() - anchor.height / 2,
        )
        placement = next
        panel.side = next.side
        val bounds = Rectangle(next.x, next.y, next.width, next.height)
        if (panel.bounds != bounds) {
            panel.bounds = bounds
            panel.revalidate()
        }
        host.repaint()
    }

    /** Whether the pointer is over the window, which keeps what it shows. */
    fun pointerInside(): Boolean {
        val panel = shown?.takeIf { it.isShowing } ?: return false
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return false
        val origin = runCatching { panel.locationOnScreen }.getOrNull() ?: return false
        return Rectangle(origin.x, origin.y, panel.width, panel.height).contains(pointer)
    }

    /** Whether keyboard focus is inside the window, such as in its editor. */
    fun hasFocus(): Boolean = shown?.let { SwingUtilities.isDescendingFrom(java.awt.KeyboardFocusManager
        .getCurrentKeyboardFocusManager().focusOwner ?: return false, it) } == true

    /** Releases every window's editors; the controller can show windows again afterwards. */
    fun release() {
        timer.stop()
        cache.values.forEach(::release)
        cache.clear()
        shown = null
        placement = null
    }

    /** Shows what it should after [release], such as when the board is shown again. */
    fun restore() = apply(force = true)

    private fun release(panel: TreeSourcePreviewPanel) {
        host.remove(panel)
        Disposer.dispose(panel)
    }

    override fun dispose() = release()

    companion object {
        const val OPEN_DELAY_MS = 320
        const val SWITCH_DELAY_MS = 170
        const val HOLD_DELAY_MS = 450
        const val LEAVE_DELAY_MS = 350
        const val KEY_DELAY_MS = 90
        const val CACHED_WINDOWS = 4
        const val MARGIN = 10
        const val GAP = 16
        const val MIN_WIDTH = 360
        const val MIN_HEIGHT = 120
        const val SOURCE_MAX_WIDTH = 820
        const val DIFF_MAX_WIDTH = 1100
        const val INTENT_SLACK = 24
        /** How many files a folder's window stacks. */
        const val FOLDER_FILES = 8
    }
}
