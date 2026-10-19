package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.util.Disposer
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import kotlin.math.pow
import kotlin.math.roundToInt
import javax.swing.*

/**
 * A zoomable, pannable board of movable note cards; positions survive chat replies and VCS refreshes.
 * Cards retain their own logical size; resizing reveals more content without changing zoom.
 */
internal class NoteCanvas(
    private val open: (String) -> Unit,
    private val remove: (DiffNote) -> Unit,
    private val openSymbol: (DiffSymbol) -> Unit = {},
    private val removeSource: (DiffSourceRange) -> Unit = {},
    private val showDiffCard: (String) -> Unit = {},
    private val removeDiff: (String) -> Unit = {},
    private val project: Project? = null,
    private val removeFlows: () -> Unit = {},
    private val summarize: () -> Unit = {},
    private val generateTopics: () -> Unit = {},
    private val removeTopics: () -> Unit = {},
    private val removeSummaries: () -> Unit = {},
) : JPanel(null), Disposable {
    private val positions = mutableMapOf<DiffNoteKey, Point2D.Double>()
    private val cards = mutableMapOf<DiffNoteKey, JPanel>()
    private val sizes = mutableMapOf<DiffNoteKey, Point2D.Double>()
    private val resizeHandles = mutableMapOf<DiffNoteKey, JComponent>()

    private var notes = emptyList<DiffNote>()
    private var sources = emptyList<DiffSourceBlock>()
    private var diffs = emptyList<ReviewFile>()
    private var flows: ChangeFlows? = null
    internal val flowsView = ChangeFlowsViewState()
    private var flowsScroll = 0
    private var topics: TopicsCard? = null
    internal val topicsView = ChangeTopicsViewState()
    private var topicsScroll = 0
    private var summaries: SummariesCard? = null
    internal val summariesView = ChangeSummariesViewState()
    private var summariesScroll = 0
    private val editors = mutableMapOf<DiffNoteKey, DiffSourceEditor>()
    private val diffEditors = mutableMapOf<DiffNoteKey, DiffFileEditor>()
    private var highlighted = emptySet<String>()
    private var focused: DiffNoteKey? = null
    internal var zoom = 1.0
        private set
    private val origin = Point2D.Double()
    var onZoomChanged: (Double) -> Unit = {}

    init {
        minimumSize = Dimension(100, 100)
        installNavigation(this)
        toolTipText = "Wheel to zoom · Drag background or middle-drag to pan"
        background = UIUtil.getPanelBackground()
    }

    fun showNotes(
        notes: List<DiffNote>,
        sources: List<DiffSourceBlock> = emptyList(),
        diffs: List<ReviewFile> = emptyList(),
        flows: ChangeFlows? = null,
        summaries: SummariesCard? = null,
        topics: TopicsCard? = null,
    ) {
        if (topics == null) topicsScroll = 0
        this.topics = topics
        this.notes = notes
        this.sources = sources
        this.diffs = diffs
        if (flows?.graph !== this.flows?.graph) flowsScroll = 0
        this.flows = flows
        flowsView.reset(flows)
        this.summaries = summaries
        summaries?.let { summariesView.update(it.summaries.tree) }
        val sourcesByKey = sources.associateBy { it.key }
        editors.entries.removeIf { (key, editor) ->
            (sourcesByKey[key] != editor.block).also { if (it) editor.dispose() }
        }
        val diffsByKey = diffs.associateBy { it.diffKey }
        diffEditors.entries.removeIf { (key, editor) ->
            (diffsByKey[key] != editor.file).also { if (it) Disposer.dispose(editor) }
        }
        removeAll(); cards.clear()
        if (notes.isEmpty() && sources.isEmpty() && diffs.isEmpty() && flows == null && summaries == null && topics == null) add(emptyState().also { installNavigation(it) })
        val flow = changeFlow(notes).takeIf { it.rows.isNotEmpty() }
        positions.keys.retainAll(notes.map { it.key }.toSet() + sourcesByKey.keys + diffsByKey.keys + listOfNotNull(flow?.let { FLOW_KEY }) +
            listOfNotNull(flows?.let { FLOWS_KEY }, summaries?.let { SUMMARIES_KEY }, topics?.let { TOPICS_KEY }))
        sizes.keys.retainAll(positions.keys)
        resizeHandles.keys.retainAll(positions.keys)
        // The overviews come first so a fresh canvas starts with them.
        val built = listOfNotNull(topics?.let { TOPICS_KEY to topicsCard(it) }, flows?.let { FLOWS_KEY to flowsCard(it) }, summaries?.let { SUMMARIES_KEY to summariesCard(it) }, flow?.let { FLOW_KEY to flowCard(it) }).toMap() +
            notes.associate { it.key to card(it) } +
            sources.associate { it.key to sourceCard(it) } +
            diffs.associate { it.diffKey to diffCard(it) }
        // Remembered cards keep their place; new ones fill the first free slot in reading order.
        built.filterKeys { it in positions }.forEach { (paths, card) -> card.location = screen(positions.getValue(paths)) }
        val placed = built.filterKeys { it in positions }.values.mapTo(mutableListOf()) { it.bounds }
        built.filterKeys { it !in positions }.forEach { (paths, card) ->
            card.location = freeSlot(card.size, placed)
            positions[paths] = world(card.location)
            placed += card.bounds
        }
        built.forEach { (paths, card) -> cards[paths] = card; add(card); installNavigation(card) }
        highlight(highlighted)
        revalidate(); repaint()
    }

    fun highlight(paths: Set<String>) {
        highlighted = paths
        cards.forEach { (key, card) -> card.border = cardBorder(key == focused || key.paths.any { it in paths }) }
        repaint()
    }

    /** Brings a note card to the centre and outlines it briefly. */
    internal fun focusNote(note: DiffNote) {
        focusCard(note.key)
    }

    internal fun focusDiff(path: String) {
        focusCard(diffNoteKey(path))
    }

    internal fun focusTopics() { focusCard(TOPICS_KEY) }

    internal fun focusSummaries() {
        focusCard(SUMMARIES_KEY)
    }

    private fun focusCard(key: DiffNoteKey) {
        val card = cards[key] ?: return
        origin.setLocation(origin.x + width / 2.0 - card.bounds.centerX, origin.y + height / 2.0 - card.bounds.centerY)
        focused = key
        showNotes(notes, sources, diffs, flows, summaries, topics)
        cards[key]?.let { setComponentZOrder(it, 0) }
        Timer(1500) { if (focused == key) { focused = null; highlight(highlighted) } }.apply { isRepeats = false }.start()
    }

    private fun flowCard(flow: ChangeFlow): JPanel {
        val width = cardWidth(FLOW_KEY, CARD_WIDTH)
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Drag to move"
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Change flow · Verified calls between symbol notes").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Steps are the assistant's symbol notes; each arrow is a resolved call in the source snapshot. " +
                    "Order follows the caller's source, not runtime; conditions and intermediate calls are not shown."
            }, BorderLayout.CENTER)
        }
        dragBy(header, FLOW_KEY)
        val body = JPanel(VerticalLayout(scaled(6))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(ChangeFlowDiagram(flow, width - scaled(2 * PADDING + 4), zoom, ::focusNote))
        }
        card.add(header, BorderLayout.NORTH)
        card.add(scrollBody(body, width, scaled(MAX_BODY_HEIGHT)), BorderLayout.CENTER)
        return prepareCard(FLOW_KEY, card, width)
    }

    /** The change-flow graph; expanding or collapsing rebuilds only this card, in place. */
    private fun flowsCard(flows: ChangeFlows): JPanel {
        val width = cardWidth(FLOWS_KEY, FLOWS_WIDTH)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Drag to move"
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Change flows").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Functions, calls, clusters, counts and outlines come from comparing and resolving the snapshot's source. " +
                    "Arrows are statically resolved calls, not runtime order. Themes, summaries and intents are the assistant's interpretation."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                val group = ButtonGroup()
                ChangeFlowsMode.entries.forEach { mode ->
                    add(JToggleButton(if (mode == ChangeFlowsMode.MAP) "Map" else "Outline", flowsView.mode == mode).apply {
                        font = scaledFont(JBFont.small())
                        cursor = Cursor.getDefaultCursor()
                        toolTipText = if (mode == ChangeFlowsMode.MAP) "Connected steps with function details on selection" else "Expandable call tree with inline details"
                        group.add(this)
                        addActionListener { flowsView.mode = mode; flowsScroll = 0; refreshFlows() }
                    })
                }
                add(InplaceButton(IconButton("Remove change flows", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeFlows() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        dragBy(header, FLOWS_KEY)
        val actions = ChangeFlowActions(
            toggle = { key -> flowsView.toggle(key); refreshFlows() },
            focus = { node -> flowsView.focus(flows, node); refreshFlows() },
            openNode = { node ->
                if (node.offset != null) openSymbol(DiffSymbol(node.path, node.absolutePath, node.name, node.qualifiedName,
                    dev.insight.callgraph.SymbolKind.FUNCTION, node.line, node.offset))
                else open(node.path)
            },
            openCaller = { caller ->
                if (caller.absolutePath != null && caller.offset != null) openSymbol(DiffSymbol(caller.path, caller.absolutePath,
                    caller.qualifiedName.substringAfterLast('.'), caller.qualifiedName, dev.insight.callgraph.SymbolKind.FUNCTION, caller.line, caller.offset))
            },
            openFile = open,
        )
        val inner = width - scaled(2 * PADDING + 4)
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(ChangeFlowsPanel(flows, flowsView, inner - scaled(16), zoom, actions), BorderLayout.CENTER)
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        card.add(header, BorderLayout.NORTH)
        val maxBody = scaled(MAX_FLOWS_HEIGHT)
        card.add(JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(width, minOf(body.preferredSize.height, maxBody))
            verticalScrollBar.unitIncrement = scaled(16)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = flowsScroll
            verticalScrollBar.addAdjustmentListener { flowsScroll = it.value }
            SwingUtilities.invokeLater { verticalScrollBar.value = restore }
        }, BorderLayout.CENTER)
        return prepareCard(FLOWS_KEY, card, width)
    }

    private fun refreshFlows() {
        val flows = flows ?: return
        val old = cards[FLOWS_KEY] ?: return
        val index = components.indexOf(old)
        val card = flowsCard(flows)
        card.location = old.location
        remove(old)
        add(card, index.coerceAtLeast(0))
        cards[FLOWS_KEY] = card
        installNavigation(card)
        card.border = cardBorder(FLOWS_KEY == focused)
        revalidate(); repaint()
        flowsView.reveal?.let { node ->
            flowsView.reveal = null
            SwingUtilities.invokeLater {
                card.validate()
                val target = descendantsOf(card).firstOrNull { it.name == "node:$node" } as? JComponent ?: return@invokeLater
                target.scrollRectToVisible(Rectangle(0, 0, target.width, minOf(target.height, scaled(120))))
            }
        }
    }

    /**
     * The change as one document with its topic rail, or as the topic hierarchy with each file's summary; mode changes,
     * folding and summary updates rebuild only this card, in place.
     */
    private fun topicsCard(content: TopicsCard): JPanel {
        val topics = content.topics
        val outline = topicOutline(topics)
        val hierarchy = topicsView.mode == ChangeTopicsMode.HIERARCHY
        val width = cardWidth(TOPICS_KEY,
            if (hierarchy) ChangeTopicHierarchyPanel.defaultWidth(outline.depth) else ChangeTopicsPanel.defaultWidth(outline.depth))
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Drag to move"
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Change topics · ${topics.groups.size} topics · ${topics.labels.size} of ${files(topics.files.size)}").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Every changed diff in one document, ordered by topic. File boundaries, order and alignment come from code; " +
                    "topics and summaries are the assistant's interpretation."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                if (hierarchy && outline.depth > 0) add(topicLevels(outline))
                val group = ButtonGroup()
                ChangeTopicsMode.entries.forEach { mode ->
                    add(JToggleButton(if (mode == ChangeTopicsMode.DOCUMENT) "Document" else "Hierarchy", topicsView.mode == mode).apply {
                        font = scaledFont(JBFont.small())
                        cursor = Cursor.getDefaultCursor()
                        toolTipText = if (mode == ChangeTopicsMode.DOCUMENT) "Every diff in topic order, with its summary and the topic rail"
                            else "Topics broad to specific, left to right, with each file's summary"
                        group.add(this)
                        addActionListener { topicsView.mode = mode; topicsScroll = 0; refreshCard(TOPICS_KEY) }
                    })
                }
                add(JButton(when {
                    topics.labels.isEmpty() -> "Classify"
                    topics.complete -> "Regenerate"
                    else -> "Continue"
                }).apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && topics.files.isNotEmpty()
                    toolTipText = if (topics.complete) "Classify every file again (/topics)" else "Give every remaining file a topic (/topics)"
                    addActionListener { generateTopics() }
                })
                val summaries = content.summaries
                if (summaries.total > 0 && !summaries.complete) add(JButton(if (summaries.count == 0) "Summarize" else "Continue summaries").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun
                    toolTipText = "Write the file summaries shown beside each diff (/summaries)"
                    addActionListener { summarize() }
                })
                add(InplaceButton(IconButton("Remove change topics", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeTopics() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        dragBy(header, TOPICS_KEY)
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            val actions = ChangeTopicActions(openFile = open, showDiffCard = showDiffCard, refresh = { refreshCard(TOPICS_KEY) })
            val inner = width - scaled(2 * PADDING + 4) - scaled(16)
            add(if (hierarchy) ChangeTopicHierarchyPanel(content, topicsView, inner, zoom, actions)
                else ChangeTopicsPanel(content, topicsView, inner, zoom, actions), BorderLayout.CENTER)
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        card.add(header, BorderLayout.NORTH)
        card.add(JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            // A deep hierarchy may scroll sideways; leave room for that scroll bar.
            val sideways = if (body.preferredSize.width > width) horizontalScrollBar.preferredSize.height else 0
            preferredSize = Dimension(width, minOf(body.preferredSize.height + sideways, scaled(MAX_TOPICS_HEIGHT)))
            verticalScrollBar.unitIncrement = scaled(16)
            horizontalScrollBar.unitIncrement = scaled(16)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = topicsScroll
            verticalScrollBar.addAdjustmentListener { topicsScroll = it.value }
            SwingUtilities.invokeLater { verticalScrollBar.value = restore }
        }, BorderLayout.CENTER)
        return prepareCard(TOPICS_KEY, card, width)
    }

    /** Level n shows topic levels 1..n+1 and folds the rest, as in the article hierarchy; the last level shows every file. */
    private fun topicLevels(outline: TopicOutline): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        add(JLabel("Levels").apply {
            font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
        })
        val group = ButtonGroup()
        val current = topicsView.effectiveLevel(outline)
        for (level in 0..outline.depth) add(JToggleButton(if (level == outline.depth) "Files" else "${level + 1}", level == current).apply {
            name = "level:$level"
            font = scaledFont(JBFont.small())
            cursor = Cursor.getDefaultCursor()
            toolTipText = if (level == outline.depth) "Show every topic level and file" else "Show topic levels 1–${level + 1}"
            group.add(this)
            addActionListener { topicsView.selectLevel(level, outline); refreshCard(TOPICS_KEY) }
        })
    }

    /** Follows topic assignments, summaries and chat state without rebuilding the other cards while the card stays on the canvas. */
    fun updateTopics(content: TopicsCard?) {
        if ((content == null) != (topics == null) || (content != null && cards[TOPICS_KEY] == null)) {
            showNotes(notes, sources, diffs, flows, summaries, content)
            return
        }
        if (content == topics) return
        topics = content
        refreshCard(TOPICS_KEY)
    }

    /** The summary tree as a wide reading card; expanding, selecting and new summaries rebuild only this card, in place. */
    private fun summariesCard(content: SummariesCard): JPanel {
        val width = cardWidth(SUMMARIES_KEY, SUMMARIES_WIDTH)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Drag to move"
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Change summaries · From the whole change down to each file").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "The tree comes from the changed paths. Each summary is the assistant's interpretation, written from the files up to the root."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, scaled(4), 0)).apply {
                isOpaque = false
                val summaries = content.summaries
                add(JButton(when {
                    summaries.total == 0 || summaries.count == 0 -> "Summarize"
                    summaries.complete -> "Regenerate"
                    else -> "Continue"
                }).apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun && summaries.total > 0
                    toolTipText = if (summaries.complete) "Write every summary again (/summaries)"
                        else "Summarize the files, then the folders above them, up to the whole change (/summaries)"
                    addActionListener { summarize() }
                })
                add(InplaceButton(IconButton("Remove change summaries", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { removeSummaries() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.EAST)
        }
        dragBy(header, SUMMARIES_KEY)
        val actions = ChangeSummaryActions(
            toggle = { identity -> summariesView.toggle(identity); refreshSummaries() },
            select = { identity -> summariesView.select(identity); refreshSummaries() },
            openFile = open,
            showDiffCard = showDiffCard,
        )
        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(ChangeSummariesPanel(content, summariesView, width - scaled(2 * PADDING + 4) - scaled(16), zoom, actions), BorderLayout.CENTER)
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        card.add(header, BorderLayout.NORTH)
        val maxBody = scaled(MAX_SUMMARIES_HEIGHT)
        card.add(JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(width, minOf(body.preferredSize.height, maxBody))
            verticalScrollBar.unitIncrement = scaled(16)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = summariesScroll
            verticalScrollBar.addAdjustmentListener { summariesScroll = it.value }
            SwingUtilities.invokeLater { verticalScrollBar.value = restore }
        }, BorderLayout.CENTER)
        return prepareCard(SUMMARIES_KEY, card, width)
    }

    /** Follows every summary and chat update without rebuilding the other cards while the card stays on the canvas. */
    fun updateSummaries(content: SummariesCard?) {
        if ((content == null) != (summaries == null) || cards[SUMMARIES_KEY] == null) {
            showNotes(notes, sources, diffs, flows, content, topics)
            return
        }
        if (content == summaries) return
        summaries = content
        content?.let { summariesView.update(it.summaries.tree) }
        refreshSummaries()
    }

    private fun refreshSummaries() {
        val content = summaries ?: return
        val old = cards[SUMMARIES_KEY] ?: return
        val index = components.indexOf(old)
        val card = summariesCard(content)
        card.location = old.location
        remove(old)
        add(card, index.coerceAtLeast(0))
        cards[SUMMARIES_KEY] = card
        installNavigation(card)
        card.border = cardBorder(SUMMARIES_KEY == focused)
        revalidate(); repaint()
    }

    private fun descendantsOf(component: Container): List<Component> =
        component.components.flatMap { listOf(it) + ((it as? Container)?.let(::descendantsOf) ?: emptyList()) }

    private fun cardWidth(key: DiffNoteKey, default: Int) =
        sizes[key]?.let { (it.x * zoom).roundToInt() } ?: scaled(default)

    private fun scrollBody(body: JComponent, width: Int, maxHeight: Int) = JBScrollPane(body).apply {
        border = null; isOpaque = false; viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        preferredSize = Dimension(width, minOf(body.preferredSize.height, maxHeight))
        verticalScrollBar.unitIncrement = scaled(16)
    }

    /** The same grip survives rebuilding width-dependent content during a drag. */
    private fun prepareCard(key: DiffNoteKey, card: JPanel, width: Int): JPanel {
        val grip = resizeHandles.getOrPut(key) {
            object : JComponent() {
                override fun paintComponent(g: java.awt.Graphics) {
                    g.color = UIUtil.getContextHelpForeground()
                    for (inset in listOf(4, 8, 12))
                        g.drawLine(this.width - JBUI.scale(inset), height - JBUI.scale(3),
                            this.width - JBUI.scale(3), height - JBUI.scale(inset))
                }
            }.apply {
                name = "Resize block"
                toolTipText = "Drag to resize block"
                cursor = Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
                val resize = object : MouseAdapter() {
                    private var anchor: Point? = null
                    private var initial: Dimension? = null

                    override fun mousePressed(e: MouseEvent) {
                        if (!SwingUtilities.isLeftMouseButton(e)) return
                        anchor = SwingUtilities.convertPoint(this@apply, e.point, this@NoteCanvas)
                        initial = cards[key]?.size
                        cards[key]?.let { this@NoteCanvas.setComponentZOrder(it, 0) }
                    }

                    override fun mouseDragged(e: MouseEvent) {
                        val start = anchor ?: return
                        val size = initial ?: return
                        val now = SwingUtilities.convertPoint(this@apply, e.point, this@NoteCanvas)
                        sizes[key] = Point2D.Double(
                            maxOf(scaled(280), size.width + now.x - start.x) / zoom,
                            maxOf(scaled(140), size.height + now.y - start.y) / zoom,
                        )
                        refreshCard(key)
                    }

                    override fun mouseReleased(e: MouseEvent) { anchor = null; initial = null }
                }
                addMouseListener(resize)
                addMouseMotionListener(resize)
            }
        }
        grip.preferredSize = Dimension(scaled(18), scaled(18))
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(grip, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        card.size = sizes[key]?.let { Dimension((it.x * zoom).roundToInt(), (it.y * zoom).roundToInt()) }
            ?: Dimension(width, card.preferredSize.height)
        return card
    }

    private fun refreshCard(key: DiffNoteKey) {
        val old = cards[key] ?: return
        val index = components.indexOf(old)
        val card = when (key) {
            FLOWS_KEY -> flows?.let(::flowsCard)
            TOPICS_KEY -> topics?.let(::topicsCard)
            SUMMARIES_KEY -> summaries?.let(::summariesCard)
            FLOW_KEY -> flowCard(changeFlow(notes))
            else -> notes.firstOrNull { it.key == key }?.let(::card)
                ?: sources.firstOrNull { it.key == key }?.let(::sourceCard)
                ?: diffs.firstOrNull { it.diffKey == key }?.let(::diffCard)
        } ?: return
        card.location = old.location
        remove(old)
        add(card, index.coerceAtLeast(0))
        cards[key] = card
        installNavigation(card)
        highlight(highlighted)
        // Lay out the retained grip before the next drag event is converted to canvas coordinates.
        fun layout(component: Container) {
            component.doLayout()
            component.components.filterIsInstance<Container>().forEach(::layout)
        }
        layout(card)
        revalidate(); repaint()
    }

    private fun diffCard(file: ReviewFile): JPanel {
        val width = cardWidth(file.diffKey, 720)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Drag to move"
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(JLabel("Diff snapshot · ${file.status}").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove diff block", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                removeDiff(file.path)
            }.apply { cursor = Cursor.getDefaultCursor() }, BorderLayout.EAST)
        }
        dragBy(header, file.diffKey)
        val editor = diffEditors.getOrPut(file.diffKey) { DiffFileEditor(file, project) }
        editor.resizeFor(width - scaled(4), zoom)
        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder(false)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(header, BorderLayout.NORTH)
                add(fileRow(file.path, showDiffButton = false).apply { border = JBUI.Borders.empty(0, 12, 8, 8) }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
        }
        return prepareCard(file.diffKey, card, width)
    }

    private fun sourceCard(block: DiffSourceBlock): JPanel {
        val range = block.range
        val width = cardWidth(block.key, 640)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(JLabel("Source snapshot · ${range.side} · Lines ${range.startLine}–${range.endLine}").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove source block", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                removeSource(range)
            }, BorderLayout.EAST)
        }
        dragBy(header, block.key)
        val editor = editors.getOrPut(block.key) { DiffSourceEditor(block, project) }
        editor.resizeFor(width - scaled(4), zoom)
        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder(false)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(header, BorderLayout.NORTH)
                add(fileRow(range.path).apply { border = JBUI.Borders.empty(0, 12, 8, 8) }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
        }
        return prepareCard(block.key, card, width)
    }

    private fun card(note: DiffNote): JPanel {
        val width = cardWidth(note.key, CARD_WIDTH)
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder(false) }
        val header = JPanel(BorderLayout(scaled(8), 0)).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
            toolTipText = "Drag to move"
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("${files(note.paths.size)} · LLM interpretation").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove note", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { remove(note) }
                .apply { cursor = Cursor.getDefaultCursor() }, BorderLayout.EAST)
        }
        dragBy(header, note.key)
        val textWidth = width - scaled(2 * PADDING + 4)
        val body = JPanel(VerticalLayout(scaled(10))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(JPanel(VerticalLayout(scaled(2))).apply {
                isOpaque = false
                note.paths.forEach { add(fileRow(it)) }
            })
            note.evidence?.let { evidence ->
                add(JLabel("Verified symbol · Static links · Source snapshot").apply {
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                    toolTipText = "In-project links resolved from current source when this note was added. Direct links only; no runtime order is implied. Callers are limited to this file."
                })
                add(DiffSymbolDiagram(evidence, textWidth, zoom, openSymbol))
            }
            add(JTextArea(note.text).apply {
                isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
                font = scaledFont(JBFont.label()); border = null
                // A wrapping text area reports its height for the width it currently has.
                setSize(textWidth, Short.MAX_VALUE.toInt())
            })
        }
        card.add(header, BorderLayout.NORTH)
        val maxBody = scaled(MAX_BODY_HEIGHT)
        card.add(scrollBody(body, width, maxBody), BorderLayout.CENTER)
        return prepareCard(note.key, card, width)
    }

    /** File name as a link, its folder dimmed next to it; the full path stays in the tooltip. */
    private fun fileRow(path: String, showDiffButton: Boolean = true): JComponent = JPanel(BorderLayout(scaled(6), 0)).apply {
        isOpaque = false
        toolTipText = "View changes in $path"
        val name = path.substringAfterLast('/')
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JLabel(FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Any_type))
            add(Box.createHorizontalStrut(scaled(6)))
            add(ActionLink(name) { open(path) }.apply {
                font = scaledFont(JBFont.label()); toolTipText = "View changes in $path"
                minimumSize = Dimension(0, preferredSize.height)
            })
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { folder ->
                add(Box.createHorizontalStrut(scaled(6)))
                add(JLabel(folder).apply {
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); toolTipText = path
                    minimumSize = Dimension(0, preferredSize.height)
                })
            }
        }, BorderLayout.CENTER)
        if (showDiffButton) {
            add(InplaceButton(IconButton("Show diff on canvas", AllIcons.Actions.Diff, AllIcons.Actions.Diff)) {
                showDiffCard(path)
            }.apply { cursor = Cursor.getDefaultCursor(); toolTipText = "Show diff on canvas" }, BorderLayout.EAST)
        }
    }

    private fun dragBy(handle: JComponent, paths: DiffNoteKey) {
        val drag = object : MouseAdapter() {
            var anchor: Point? = null
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                anchor = SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas)
                cards[paths]?.let { setComponentZOrder(it, 0); repaint() }
            }
            override fun mouseReleased(e: MouseEvent) { anchor = null }
            override fun mouseDragged(e: MouseEvent) {
                val before = anchor ?: return
                val now = SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas)
                val target = cards[paths] ?: return
                target.setLocation(target.x + now.x - before.x, target.y + now.y - before.y)
                positions[paths] = world(target.location)
                anchor = now
                repaint()
            }
        }
        fun attach(component: Component) {
            if (component is InplaceButton) return
            component.addMouseListener(drag); component.addMouseMotionListener(drag)
            if (component is Container) component.components.forEach(::attach)
        }
        attach(handle)
    }

    private fun freeSlot(size: Dimension, placed: List<Rectangle>): Point {
        val gap = scaled(GAP)
        val visible = width.takeIf { it > 0 } ?: scaled(1000)
        val columns = maxOf(1, (visible - gap) / (size.width + gap))
        var y = gap
        while (true) {
            for (column in 0 until columns) {
                val candidate = Rectangle(origin.x.roundToInt() + gap + column * (size.width + gap), origin.y.roundToInt() + y, size.width, size.height)
                val padded = Rectangle(candidate).apply { grow(gap / 2, gap / 2) }
                if (placed.none { it.intersects(padded) }) return candidate.location
            }
            y += scaled(8)
        }
    }

    private fun emptyState(): JComponent = JPanel(VerticalLayout(scaled(6))).apply {
        isOpaque = false
        add(JLabel("Diff review canvas").apply { font = scaledFont(JBFont.label().asBold().biggerOn(2f)) })
        listOf(
            "Select files on the left to inspect changes.",
            "Click Description or type /description below to add notes.",
            "Click Flows or type /flows to map the change from overview to code.",
            "Click Summaries or type /summaries to read the change from the whole down to each file.",
            "Click Topics or type /topics to read every diff as one document, grouped by topic, with each file's summary.",
            "Drag a block's header to move it or its bottom-right grip to resize it.",
            "Drag the background to pan.",
            "Use the mouse wheel to zoom, or middle-drag anywhere to pan.",
        ).forEach { add(JLabel(it).apply { foreground = UIUtil.getContextHelpForeground() }) }
        val gap = scaled(GAP)
        setBounds(gap, gap, scaled(600), preferredSize.height)
    }

    private fun cardBorder(highlighted: Boolean) =
        if (highlighted) JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2)
        else JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))

    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"

    private fun scaled(value: Int) = (JBUI.scale(value) * zoom).roundToInt().coerceAtLeast(1)
    private fun scaledFont(font: java.awt.Font) = font.deriveFont((font.size2D * zoom).toFloat())
    private fun screen(point: Point2D.Double) = Point((point.x * zoom + origin.x).roundToInt(), (point.y * zoom + origin.y).roundToInt())
    private fun world(point: Point) = Point2D.Double((point.x - origin.x) / zoom, (point.y - origin.y) / zoom)

    internal fun zoomAt(value: Double, anchor: Point = Point(width / 2, height / 2)) {
        val next = value.coerceIn(0.25, 2.5)
        if (next == zoom) return
        val fixed = world(anchor)
        zoom = next
        origin.setLocation(anchor.x - fixed.x * zoom, anchor.y - fixed.y * zoom)
        showNotes(notes, sources, diffs, flows, summaries, topics)
        onZoomChanged(zoom)
    }

    fun resetView() {
        zoom = 1.0
        origin.setLocation(0.0, 0.0)
        showNotes(notes, sources, diffs, flows, summaries, topics)
        onZoomChanged(zoom)
    }

    fun fitView() {
        if (cards.isEmpty() || width <= 0 || height <= 0) { resetView(); return }
        val bounds = cards.values.map { it.bounds }.reduce { a, b -> a.union(b) }
        val center = world(Point(bounds.centerX.roundToInt(), bounds.centerY.roundToInt()))
        val margin = JBUI.scale(GAP) * 2
        zoom = (zoom * minOf((width - margin).toDouble() / bounds.width,
            (height - margin).toDouble() / bounds.height)).coerceIn(0.25, 2.5)
        origin.setLocation(width / 2.0 - center.x * zoom, height / 2.0 - center.y * zoom)
        showNotes(notes, sources, diffs, flows, summaries, topics)
        onZoomChanged(zoom)
    }

    private fun installNavigation(component: Component) {
        // Native editor owns selection, wheel scrolling and mouse gestures within its body.
        if (component is DiffSourceEditor || component is DiffFileEditor) return
        if (component is JComponent) {
            if (component.getClientProperty("noteCanvas.navigation") == true) return
            component.putClientProperty("noteCanvas.navigation", true)
        }
        val navigation = object : MouseAdapter() {
            private var anchor: Point? = null
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isMiddleMouseButton(e) ||
                    (cards.values.none { SwingUtilities.isDescendingFrom(e.component, it) } && SwingUtilities.isLeftMouseButton(e))) {
                    anchor = SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas)
                    this@NoteCanvas.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }
            override fun mouseReleased(e: MouseEvent) {
                anchor = null
                this@NoteCanvas.cursor = Cursor.getDefaultCursor()
            }
            override fun mouseDragged(e: MouseEvent) {
                val before = anchor ?: return
                val now = SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas)
                origin.setLocation(origin.x + now.x - before.x, origin.y + now.y - before.y)
                components.forEach { it.setLocation(it.x + now.x - before.x, it.y + now.y - before.y) }
                anchor = now
                repaint()
            }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                zoomAt(zoom * 1.12.pow(-e.preciseWheelRotation),
                    SwingUtilities.convertPoint(e.component, e.point, this@NoteCanvas))
                e.consume()
            }
        }
        component.addMouseListener(navigation)
        component.addMouseMotionListener(navigation)
        // Leave wheel scrolling inside long notes to their scroll pane.
        if (component !is JScrollPane && SwingUtilities.getAncestorOfClass(JScrollPane::class.java, component) == null)
            component.addMouseWheelListener(navigation)
        if (component is Container) component.components.forEach(::installNavigation)
    }

    override fun dispose() {
        editors.values.forEach { it.dispose() }
        editors.clear()
        diffEditors.values.forEach { Disposer.dispose(it) }
        diffEditors.clear()
    }

    private companion object {
        const val CARD_WIDTH = 460
        const val GAP = 20
        const val PADDING = 12
        const val MAX_BODY_HEIGHT = 520
        val FLOW_KEY = DiffNoteKey(emptyList(), "change-flow")
        val FLOWS_KEY = DiffNoteKey(emptyList(), "change-flows")
        val TOPICS_KEY = DiffNoteKey(emptyList(), "change-topics")
        val SUMMARIES_KEY = DiffNoteKey(emptyList(), "change-summaries")
        const val SUMMARIES_WIDTH = 840
        const val MAX_SUMMARIES_HEIGHT = 820
        const val MAX_TOPICS_HEIGHT = 900
        const val FLOWS_WIDTH = 920
        const val MAX_FLOWS_HEIGHT = 760
    }
}
