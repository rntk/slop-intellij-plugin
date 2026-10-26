package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.ViewportLayout
import javax.swing.border.EmptyBorder
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.math.roundToInt

/**
 * The summary tree split in two aligned columns: a foldable file tree on the left and, on the right, each visible entry's
 * summary beside its tree row, optionally wrapping to the viewport width. A resizable divider separates horizontally
 * scrolling panels, with a shared vertical scroll position. Hovering either side highlights both halves of the row;
 * folding a folder hides the rows, and so the summaries, beneath it.
 */
internal class ChangeSummarySplitPanel(
    private val card: SummariesCard,
    private val state: ChangeSummariesViewState,
    private val zoom: Double,
    private val actions: ChangeSummaryActions,
    private val vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
) {
    private val summaries = card.summaries
    private val wordWeights = SummaryWordWeights(summaries)
    private val tree = summaries.tree
    private fun scaled(n: Int) = zoomScaled(n, zoom)
    private fun font(base: Font) = base.zoomed(1.08 * zoom)
    private val help get() = UIUtil.getContextHelpForeground()
    private val trace = state.resultTrace
    private val matches = trace?.related.orEmpty().associateBy { it.identity }
    private val best = trace?.best?.identity
    private val pairs = mutableListOf<Pair<SplitRow, SplitRow>>()
    private val treeRows = SplitColumn("summary-split-tree")
    private val summaryRows = SplitColumn("summary-split-summaries", state.softWrap)
    /** Each summary shares its full height with the corresponding tree row. */
    val summariesColumn: JComponent get() = summaryRows

    init {
        state.update(tree)
        when {
            summaries.total == 0 -> message(vocabulary.noFiles)
            !state.shows(tree.root, summaries) -> message("No summaries match the search.")
            else -> addNode(tree.root, 0)
        }
        treeRows.rows += pairs.map { it.first }
        summaryRows.rows += pairs.map { it.second }
        pairs.forEach { (left, right) ->
            treeRows.add(left.apply { trackHover() })
            summaryRows.add(right.apply { trackHover() })
        }
    }

    /** Both horizontal bars reserve the same height, keeping the two vertical viewports exactly aligned. */
    fun withTree(
        summaryScroll: JScrollPane,
        width: Int,
        treeWidth: Int?,
        treeScrollX: Int,
        rememberTreeWidth: (Int) -> Unit,
        rememberTreeScrollX: (Int) -> Unit,
    ): JSplitPane {
        summaryScroll.name = "summary-split-summary-scroll"
        summaryScroll.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
        summaryScroll.minimumSize = Dimension(scaled(180), 0)
        val treeScroll = JBScrollPane(treeRows).apply {
            name = "summary-split-tree-scroll"
            border = JBUI.Borders.customLineRight(JBColor.border())
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            // IntelliJ's viewport fits the view to its height when the vertical bar is hidden, clipping the lower rows.
            // The standard layout keeps the tree at its full height, so it can follow the summaries to the bottom.
            viewport.layout = ViewportLayout()
            horizontalScrollBar.unitIncrement = scaled(16)
            verticalScrollBar.unitIncrement = summaryScroll.verticalScrollBar.unitIncrement
            minimumSize = Dimension(scaled(120), 0)
            horizontalScrollBar.addAdjustmentListener { rememberTreeScrollX(it.value) }
            // A hidden vertical bar makes Swing send ordinary wheel events sideways. Forward them to the summaries.
            isWheelScrollingEnabled = false
            addMouseWheelListener { event ->
                if (event.isShiftDown) {
                    val increment = if (event.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL)
                        horizontalScrollBar.blockIncrement else horizontalScrollBar.unitIncrement * event.scrollAmount
                    horizontalScrollBar.value += (event.preciseWheelRotation * increment).roundToInt()
                } else summaryScroll.dispatchEvent(SwingUtilities.convertMouseEvent(this, event, summaryScroll))
                event.consume()
            }
        }
        if (state.softWrap) {
            var wrapWidth = 0
            fun resizeRows() {
                val available = summaryScroll.viewport.extentSize.width
                if (available <= 0 || available == wrapWidth) return
                wrapWidth = available
                pairs.forEach { (_, right) ->
                    val insets = right.insets
                    right.components.filterIsInstance<WrappedSummaryText>().forEach {
                        it.wrapAt(available - insets.left - insets.right)
                    }
                }
                // Both preferred heights derive from the same pairs, including the newly wrapped text.
                treeRows.revalidate()
                summaryRows.revalidate()
                summaryScroll.parent?.revalidate()
                treeRows.repaint()
                summaryRows.repaint()
            }
            summaryScroll.viewport.addChangeListener { resizeRows() }
            resizeRows()
        }
        // The summaries own vertical scrolling. Swing can reset a hidden scrollbar during layout, so the tree follows
        // the summary viewport even when its own layout changes. Horizontal positions remain independent.
        var synchronizing = false
        fun synchronize(source: JScrollPane, target: JScrollPane) {
            if (synchronizing || source.viewport.viewPosition.y == target.viewport.viewPosition.y) return
            synchronizing = true
            try {
                target.viewport.viewPosition = Point(target.viewport.viewPosition.x, source.viewport.viewPosition.y)
            } finally { synchronizing = false }
        }
        summaryScroll.viewport.addChangeListener { synchronize(summaryScroll, treeScroll) }
        treeScroll.viewport.addChangeListener { synchronize(summaryScroll, treeScroll) }
        SwingUtilities.invokeLater { treeScroll.horizontalScrollBar.value = treeScrollX }
        return object : JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, summaryScroll) {
            override fun getPreferredSize(): Dimension = Dimension(width, summaryScroll.preferredSize.height)
        }.apply {
            name = "summary-split-panels"
            border = null
            isOpaque = false
            isContinuousLayout = true
            dividerSize = scaled(5)
            resizeWeight = 0.0
            dividerLocation = (treeWidth ?: minOf(treeRows.preferredSize.width, (width * 0.3).roundToInt()))
                .coerceIn(scaled(120), (width - scaled(180) - dividerSize).coerceAtLeast(scaled(120)))
            addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
                if (dividerLocation > 0) rememberTreeWidth(dividerLocation)
            }
        }
    }

    private fun addNode(node: SummaryNode, depth: Int) {
        pairs += row(node, depth)
        if (node.children.isEmpty() || !state.isOpen(node, summaries)) return
        val children = tree.shownChildren(node).filter { state.shows(it, summaries) }
        for (child in children) addNode(child, depth + 1)
    }

    private fun row(node: SummaryNode, depth: Int): Pair<SplitRow, SplitRow> {
        val branch = node.children.isNotEmpty()
        val open = branch && state.isOpen(node, summaries)
        val activate = { if (branch) actions.toggle(node.identity) else actions.select(node.identity) }
        val match = matches[node.identity]
        val mark = when {
            match == null -> null
            match.identity == best -> TraceStyle.BEST_MATCH
            else -> TraceStyle.RELATED
        }
        val selected = node.identity == state.selected
        val navigate = if (node.file != null) ({ actions.openNode(node) }) else actions.directoryAction(node)
        val clickDelay = (Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int) ?: 500
        val activateLater = Timer(clickDelay) { activate() }.apply { isRepeats = false }
        val left = SplitRow("summary-tree:${node.identity}", selected, mark).apply {
            border = EmptyBorder(scaled(4), scaled(4) + depth * scaled(INDENT), scaled(4), scaled(6))
            toolTipText = "${node.location} · Level ${tree.shownDepth(node.id) + 1}" + (match?.let { " · " + matchLabel(it, trace!!) } ?: "")
            add(JLabel(if (!branch) "" else if (open) "▾" else "▸").apply {
                font = font(JBFont.label())
                foreground = help
                preferredSize = Dimension(scaled(TOGGLE), preferredSize.height)
                if (branch) {
                    name = "toggle:${node.identity}"
                    toolTipText = if (open) "Fold" else "Unfold"
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) {
                                activateLater.stop()
                                activate()
                            }
                        }
                    })
                }
            }, BorderLayout.WEST)
            add(JLabel(summaryNodeTitle(node, tree), summaryNodeIcon(node), JLabel.LEADING).apply {
                name = "summary-name:${node.identity}"
                font = font(if (branch) JBFont.label().asBold() else JBFont.label())
                iconTextGap = scaled(4)
                border = EmptyBorder(0, 0, 0, scaled(12))
            }, BorderLayout.CENTER)
            val badge = match?.let {
                val label = if (node.identity == best) "● Best match" else "● Related"
                JLabel("$label · ${(it.probability * 100).roundToInt()}%").apply {
                    name = "match:${node.identity}"
                    font = font(JBFont.small())
                    foreground = TraceStyle.MATCH_TEXT
                    border = EmptyBorder(0, 0, 0, scaled(6))
                    installTraceDecorationReset(this) { preferredSize = preferredSize; text = ""; name = null }
                }
            }
            // A bare checkbox keeps the column narrow; its tooltip says what it marks.
            val review = actions.review?.control(node, font(JBFont.small()))?.apply { (this as? AbstractButton)?.text = null }
            if (badge != null || review != null) add(JPanel(BorderLayout()).apply {
                isOpaque = false
                badge?.let { add(it, BorderLayout.WEST) }
                review?.let { add(it, BorderLayout.EAST) }
            }, BorderLayout.EAST)
            componentPopupMenu = menu(node)
            descendants(this).filterIsInstance<JComponent>().filter { it !== this }.forEach { it.inheritsPopupMenu = true }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            onClick { clicks ->
                activateLater.stop()
                if (clicks == 2) navigate?.invoke() else if (clicks == 1) activateLater.start()
            }
            addHierarchyListener { if (!isShowing) activateLater.stop() }
            if (state.softWrap) alignTop()
        }
        val summary = summaries.text(node.id)
        val read = Timer(clickDelay) {
            if (summary != null) SummaryTextDialog(summaryRows, node, summary,
                font(JBFont.label()).forSummaryText(), state, actions, tree) { menu(node) }.show()
        }.apply { isRepeats = false }
        fun summaryClick(clicks: Int) {
            read.stop()
            when (clicks) {
                1 -> if (summary != null) read.start()
                2 -> navigate?.invoke()
            }
        }
        // An open folder with a single file would repeat the file's summary on the next row, so its cell stays blank.
        val repeated = open && tree.holdsOnlyFile(node.id) && state.shows(tree[node.children.single()], summaries)
        // Replace each break with one character so tracing and review offsets still refer to the original prose.
        val text: JComponent = if (repeated) JLabel(" ").apply { font = font(JBFont.label()).forSummaryText() }
            else if (summary != null) WrappedSummaryText(
            summary.replace('\n', ' ').replace('\r', ' ').replace('\u2028', ' ').replace('\u2029', ' '), null,
            font(if (node.kind == SummaryKind.ROOT) JBFont.label().asBold() else JBFont.label()).forSummaryText(),
            if (state.tagsCloud) wordWeights[node.id] else emptyMap(), breakLongWords = state.softWrap).also { area ->
                if (!state.traced(node)) area.componentPopupMenu = menu(node)
                actions.review?.decorate(area, node)
                if (state.traced(node)) installWordTracing(area, node.identity, summary, state, actions.trace, actions.searchSelection,
                    actions.searchTab.takeIf { branch }, actions.score, actions.follow,
                    click = { summaryClick(it.clickCount) }, entryMenu = { menu(node) })
            }
            else JLabel(if (card.running) "summarizing…" else "no summary yet").apply {
                font = font(JBFont.label()).let { it.deriveFont(it.style or Font.ITALIC) }
                foreground = help
            }
        val right = SplitRow("summary:${node.identity}", selected, mark).apply {
            layout = GridBagLayout()
            border = EmptyBorder(scaled(4), scaled(8), scaled(4), scaled(12))
            add(text, GridBagConstraints().apply {
                anchor = GridBagConstraints.NORTHWEST
                weightx = 1.0
                weighty = 1.0
                if (state.softWrap) fill = GridBagConstraints.HORIZONTAL
            })
            componentPopupMenu = menu(node)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            onClick(listOf(text).filter { summary == null || !state.traced(node) }, ::summaryClick)
            addHierarchyListener { if (!isShowing) read.stop() }
        }
        left.partner = right
        right.partner = left
        return left to right
    }

    /** Entry actions are available from either half of the row. */
    private fun menu(node: SummaryNode): JPopupMenu = JPopupMenu().apply {
        fun item(text: String, enabled: Boolean = true, action: () -> Unit) = add(JMenuItem(text).apply {
            name = "summary-split-action:$text"
            isEnabled = enabled
            addActionListener { action() }
        })
        if (node.children.isNotEmpty()) item(if (state.isOpen(node, summaries)) "Fold" else "Unfold") { actions.toggle(node.identity) }
        if (node.kind == SummaryKind.FILE || node.kind == SummaryKind.CHUNK) {
            item(if (vocabulary == ReviewVocabulary.DIFF) "Open diff" else "Open file") { actions.openNode(node) }
            val show = actions.showNode
            val showText = vocabulary.showOnCanvas
            if (show != null && showText != null) item(showText) { show(node) }
        }
        actions.wholeFileAction(node)?.let { item("Open file", action = it) }
        actions.directoryAction(node)?.let { item("Open diff", action = it) }
        actions.explainAction(node)?.let { item("Explain", card.canRun, it) }
        actions.addEntryItems(this, node)
        val review = actions.review ?: return@apply
        review.menuItem(node)?.let { reviewed ->
            addSeparator()
            add(reviewed)
            // The menu outlives toggles made with the row's checkbox, so its label follows the state when it opens.
            addPopupMenuListener(object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) = review.refresh(reviewed, node)
                override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) {}
                override fun popupMenuCanceled(e: PopupMenuEvent) {}
            })
        }
    }

    private fun message(text: String, name: String = "no-matches") {
        val left = SplitRow(name, false, null).apply {
            border = EmptyBorder(scaled(4), scaled(4), scaled(4), scaled(6))
            add(JLabel(text).apply { font = font(JBFont.label()).let { it.deriveFont(it.style or Font.ITALIC) }; foreground = help })
        }
        pairs += left to SplitRow("$name:summary", false, null)
    }

    /** Hovering either half of a row, or anything inside it, highlights both halves. */
    private inner class SplitRow(name: String, private val selected: Boolean, private var mark: Color?) : JPanel(BorderLayout()) {
        var partner: SplitRow? = null
        private var hovered = false

        init {
            this.name = name
            paintBackground()
            if (mark != null) installTraceDecorationReset(this) { mark = null; paintBackground() }
        }

        /** Keep the filename, fold control and review marker beside the summary's first line. */
        fun alignTop() {
            val layout = layout as BorderLayout
            val entries = components.map { it to layout.getConstraints(it) }
            removeAll()
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                inheritsPopupMenu = true
                entries.forEach { (component, position) -> add(component, position) }
            }, BorderLayout.NORTH)
        }

        fun hover(on: Boolean) {
            if (hovered == on) return
            hovered = on
            paintBackground()
            partner?.hover(on)
        }

        private fun paintBackground() {
            val color = when {
                hovered -> JBUI.CurrentTheme.List.Hover.background(true)
                mark != null -> mark
                selected -> UIUtil.getListSelectionBackground(false)
                else -> null
            }
            isOpaque = color != null
            color?.let { background = it }
            repaint()
        }

        /** Clicks on the row itself, its labels and [also]; traced summary text handles its own clicks. */
        fun onClick(also: List<Component> = emptyList(), handle: (Int) -> Unit) {
            val click = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) handle(e.clickCount) }
            }
            (listOf(this) + components.filterIsInstance<JLabel>().filter { it.name?.startsWith("toggle:") != true } + also)
                .distinct().forEach { it.addMouseListener(click) }
        }

        /** Called once the row is complete. */
        fun trackHover() {
            // Children receive their own mouse events, so each of them reports hover for the whole row.
            val tracker = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = hover(true)
                override fun mouseExited(e: MouseEvent) {
                    if (!isShowing || !contains(SwingUtilities.convertPoint(e.component, e.point, this@SplitRow))) hover(false)
                }
            }
            descendants(this).forEach { it.addMouseListener(tracker) }
        }
    }

    /** Rows stacked at their shared heights and stretched to the column's width, so both columns line up exactly. */
    private inner class SplitColumn(name: String, private val wrap: Boolean = false) : JPanel(null), Scrollable {
        val rows = mutableListOf<JComponent>()

        init {
            this.name = name
            isOpaque = false
        }

        override fun getPreferredSize(): Dimension {
            val insets = insets
            return Dimension((rows.maxOfOrNull { it.preferredSize.width } ?: 0) + insets.left + insets.right,
                pairs.sumOf { (left, right) -> maxOf(left.preferredSize.height, right.preferredSize.height) } + insets.top + insets.bottom)
        }

        override fun doLayout() {
            val insets = insets
            var y = insets.top
            val width = maxOf(width - insets.left - insets.right, 0)
            for ((index, row) in rows.withIndex()) {
                val (left, right) = pairs[index]
                val height = maxOf(left.preferredSize.height, right.preferredSize.height)
                row.setBounds(insets.left, y, width, height)
                y += height
            }
        }

        override fun getScrollableTracksViewportWidth(): Boolean = wrap
        override fun getScrollableTracksViewportHeight(): Boolean = false
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int = scaled(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            if (orientation == SwingUtilities.VERTICAL) visibleRect.height else visibleRect.width
    }

    companion object {
        const val INDENT = 16
        const val TOGGLE = 14

        private fun descendants(root: Container): List<Component> =
            listOf<Component>(root) + root.components.flatMap { if (it is Container) descendants(it) else listOf(it) }
    }
}
