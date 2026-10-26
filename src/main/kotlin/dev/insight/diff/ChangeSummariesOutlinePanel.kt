package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder
import kotlin.math.roundToInt

/**
 * The summary tree as an outline: every file, folder and group reads as its summary, so the top levels tell the change
 * and deeper levels how it was done. Names, metadata and file actions stay in the header; clicking a summary folds or
 * unfolds its children.
 */
internal class ChangeSummariesPanel(
    private val card: SummariesCard,
    private val state: ChangeSummariesViewState,
    private val cardWidth: Int,
    private val zoom: Double,
    private val actions: ChangeSummaryActions,
    private val vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
    private val browse: ((String) -> Unit)? = null,
) : JPanel(VerticalLayout(0)) {
    private val summaries = card.summaries
    private val wordWeights = SummaryWordWeights(summaries)
    private val tree = summaries.tree
    private val files = tree.nodes.mapNotNull { it.file }.associateBy { it.path }
    private fun scaled(n: Int) = zoomScaled(n, zoom)
    private fun font(base: Font) = base.zoomed(zoom)
    private val help get() = UIUtil.getContextHelpForeground()
    private val trace = state.resultTrace
    private val matches = trace?.related.orEmpty().associateBy { it.identity }
    private val best = trace?.best?.identity
    /** Rows added so far; a huge tree stops at [HierarchyColumnsPanel.MAX_ROWS] and says how many entries it left out. */
    private var rows = 0
    private var skipped = 0

    init {
        isOpaque = false
        add(text(summariesStatus(card, vocabulary.noFiles), cardWidth, font(JBFont.small()), help).apply { border = pad(bottom = scaled(8)) })
        if (summaries.total > 0 && state.shows(tree.root, summaries)) {
            if (browse != null) {
                val entries = state.explorerEntries(summaries)
                entries.forEach { add(row(it, 0)) }
                if (entries.isEmpty()) add(text("No summaries match the search.", cardWidth, italic(JBFont.label()), help).apply { name = "no-matches" })
            }
            else addNode(tree.root, 0)
        }
        else if (summaries.total > 0) add(text("No summaries match the search.", cardWidth, italic(JBFont.label()), help).apply { name = "no-matches" })
        if (skipped > 0) add(text("+$skipped more — fold or pick a level", cardWidth, italic(JBFont.small()), help)
            .apply { name = "more"; border = pad(scaled(4), scaled(TOGGLE), scaled(4), 0) })
        add(text(if (browse != null) "Click a folder to browse its summaries, or a file to open it. Use Back or the breadcrumbs to return."
            else vocabulary.summariesFooter, cardWidth, font(JBFont.small()), help)
            .apply { border = pad(top = scaled(10)) })
    }

    private fun addNode(node: SummaryNode, depth: Int) {
        rows++
        add(row(node, depth))
        if (!state.isOpen(node, summaries)) return
        val children = tree.shownChildren(node).filter { state.shows(it, summaries) }
        for ((index, child) in children.withIndex()) {
            if (rows >= HierarchyColumnsPanel.MAX_ROWS) {
                skipped += children.size - index
                return
            }
            addNode(child, depth + 1)
        }
    }

    private fun row(node: SummaryNode, depth: Int): JComponent {
        val selected = node.identity == state.selected
        val open = state.isOpen(node, summaries)
        val gutter = scaled(TOGGLE) + scaled(ICON) + scaled(8)
        val textWidth = (cardWidth - depth * scaled(INDENT) - gutter - scaled(8)).coerceAtLeast(scaled(160))
        val summary = summaries.text(node.id)
        val match = matches[node.identity]
        val activate = {
            if (browse != null) {
                if (node.file != null) actions.openNode(node) else if (node.children.isNotEmpty()) browse.invoke(node.identity)
            } else if (node.children.isNotEmpty()) actions.toggle(node.identity) else actions.select(node.identity)
        }
        val header = header(node, textWidth)
        val missing = if (card.running) "summarizing…" else "no summary yet"
        val summaryText = if (summary != null) WrappedSummaryText(summary, textWidth,
                font(if (node.kind == SummaryKind.ROOT) JBFont.label().asBold() else JBFont.label()).forSummaryText(),
                if (state.tagsCloud) wordWeights[node.id] else emptyMap())
                .also { area ->
                    actions.review?.decorate(area, node)
                    if (state.traced(node)) installWordTracing(area, node.identity, summary, state, actions.trace, actions.searchSelection,
                        actions.searchTab.takeIf { node.children.isNotEmpty() }, actions.score, actions.follow, select = activate)
                }
            else text(missing, textWidth, italic(JBFont.label()), help)
        val content = JPanel(VerticalLayout(scaled(4))).apply {
            isOpaque = false
            add(header)
            add(summaryText)
        }
        val gutterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(if (node.children.isEmpty() || browse != null && node.file != null) JLabel().apply { preferredSize = Dimension(scaled(TOGGLE), 1) }
                else label(if (browse == null && open) "▾" else "▸", font(JBFont.label()), help).apply {
                    preferredSize = Dimension(scaled(TOGGLE), preferredSize.height)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = if (browse != null) "Open folder" else if (open) "Collapse" else "Expand"
                    name = "toggle:${node.identity}"
                    addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) {
                            if (browse != null) browse.invoke(node.identity) else actions.toggle(node.identity)
                        }
                    } })
                })
            add(JLabel(icon(node)).apply { preferredSize = Dimension(scaled(ICON) + scaled(8), preferredSize.height) })
        }
        return JPanel(BorderLayout()).apply {
            name = "summary:${node.identity}"
            isOpaque = selected || match != null
            background = when {
                match != null && match.identity == best -> TraceStyle.BEST_MATCH
                match != null -> TraceStyle.RELATED
                else -> UIUtil.getListSelectionBackground(false)
            }
            if (match != null) installTraceDecorationReset(this) {
                isOpaque = selected
                background = UIUtil.getListSelectionBackground(false)
                repaint()
            }
            border = pad(scaled(4), depth * scaled(INDENT), scaled(4), scaled(4))
            toolTipText = node.location
            add(gutterPanel, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
            // Scored words keep their trace action; the rest of the row opens the entry or toggles the outline.
            val click = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) activate()
            } }
            (listOf(this, gutterPanel, content, header) + header.components.filterIsInstance<JLabel>() +
                gutterPanel.components.filterIsInstance<JLabel>().filter { it.name == null } +
                listOfNotNull(summaryText.takeIf { summary == null || !state.traced(node) }))
                .forEach { it.addMouseListener(click) }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            actions.installEntryMenu(this, node)
        }
    }

    /** A file or folder name, a group's range, or the root's label. */
    private fun displayName(node: SummaryNode): String = when (node.kind) {
        SummaryKind.FILE, SummaryKind.GROUP, SummaryKind.CHUNK -> node.label
        SummaryKind.DIRECTORY -> "${tree.shownLabel(node)}/"
        SummaryKind.ROOT -> node.location
    }

    /** Headers use local names; full paths and metadata remain available in the tooltip. */
    private fun header(node: SummaryNode, width: Int): JPanel {
        val count = plural(node.files.size, "file")
        val title = when (node.kind) {
            SummaryKind.FILE -> "${displayName(node)} · ${vocabulary.describeFile(node.file)}"
            SummaryKind.CHUNK -> "${displayName(node)} of ${node.path.substringAfterLast('/')}" + (partOf(node)?.let { " · $it" } ?: "")
            SummaryKind.DIRECTORY -> "${displayName(node)} · folder · $count"
            SummaryKind.GROUP -> "${displayName(node)} · ${node.children.size} entries of ${node.path.ifEmpty { "." }}/ · $count"
            SummaryKind.ROOT -> "${displayName(node)} · ${vocabulary.rootName} · $count"
        }
        // Folders have no review file of their own; their location follows from any file beneath them.
        val absolute = node.file?.absolutePath ?: files[node.files.firstOrNull()]?.let { file ->
            val base = file.absolutePath.removeSuffix(file.path).removeSuffix("/")
            if (node.path.isEmpty()) "$base/" else "$base/${node.path}/"
        }
        val metadata = (listOf(title) + listOfNotNull(node.location, absolute).distinct()
            .filter { it != displayName(node) }).joinToString(" · ")
        val heading = when (node.kind) {
            SummaryKind.FILE -> node.path.substringAfterLast('/')
            SummaryKind.DIRECTORY -> "${tree.shownLabel(node)}/ · folder · $count"
            SummaryKind.ROOT -> "${node.path.substringAfterLast('/').ifEmpty { "." }}/ · ${vocabulary.rootName} · $count"
            SummaryKind.GROUP -> "${node.label} · ${node.children.size} entries · $count"
            SummaryKind.CHUNK -> title
        }
        val nameText = label(heading, font(JBFont.label().asBold()).forReviewName(), help).apply {
            name = "summary-name:${node.identity}"
            toolTipText = metadata
        }
        val links = JPanel(FlowLayout(FlowLayout.RIGHT, scaled(8), 0)).apply {
            isOpaque = false
            actions.review?.control(node, font(JBFont.small()))?.let(::add)
            add(label("Level ${tree.shownDepth(node.id) + 1}", font(JBFont.small()), help).apply {
                name = "summary-level-label:${node.identity}"
            })
            matches[node.identity]?.let { match ->
                val badge = if (node.identity == best) "● Best match" else "● Related"
                add(label("$badge · ${(match.probability * 100).roundToInt()}%", font(JBFont.small()), TraceStyle.MATCH_TEXT).apply {
                    name = "match:${node.identity}"
                    toolTipText = matchLabel(match, trace!!)
                    installTraceDecorationReset(this) {
                        preferredSize = preferredSize
                        text = ""
                        name = null
                        toolTipText = null
                    }
                })
            }
            if (node.kind == SummaryKind.FILE || node.kind == SummaryKind.CHUNK) {
                if (browse != null && node.children.isNotEmpty()) add(ActionLink("Browse parts") { browse.invoke(node.identity) }
                    .apply { font = font(JBFont.small()) })
                add(ActionLink(if (node.kind == SummaryKind.CHUNK) "Open lines" else vocabulary.openLabel) { actions.openNode(node) }
                    .apply { font = font(JBFont.small()) })
                val show = actions.showNode
                val showText = vocabulary.showOnCanvas
                if (show != null && showText != null) add(ActionLink(showText) { show(node) }.apply { font = font(JBFont.small()) })
            }
            actions.wholeFileAction(node)?.let { open ->
                add(ActionLink("Open file") { open() }.apply { font = font(JBFont.small()) })
            }
            actions.directoryAction(node)?.let { open ->
                add(ActionLink("View diff") { open() }.apply { font = font(JBFont.small()) })
            }
            actions.explainAction(node)?.let { explain ->
                add(explainLink(explain, card.canRun, font(JBFont.small())))
            }
        }
        return JPanel(BorderLayout(scaled(8), 0)).apply {
            name = "summary-header:${node.identity}"
            isOpaque = false
            add(nameText, BorderLayout.CENTER)
            add(links, BorderLayout.EAST)
            preferredSize = Dimension(width, maxOf(nameText.preferredSize.height, links.preferredSize.height))
        }
    }

    /** "part i of n" among the parts of a file, or "n parts" for a group of them; null when the tree cannot say. */
    private fun partOf(node: SummaryNode): String? {
        val leaves = partLeaves(node)
        if (leaves.isEmpty()) return null
        if (node.children.isNotEmpty()) return "${leaves.size} parts"
        val file = generateSequence(node.parent) { tree[it].parent }.map(tree::get).firstOrNull { it.kind == SummaryKind.FILE } ?: return null
        val index = partLeaves(file).indexOfFirst { it.id == node.id }
        return if (index < 0) null else "part ${index + 1} of ${partLeaves(file).size}"
    }

    /** The leaf parts at or beneath [node], in line order. */
    private fun partLeaves(node: SummaryNode): List<SummaryNode> {
        if (node.children.isEmpty()) return if (node.kind == SummaryKind.CHUNK) listOf(node) else emptyList()
        return node.children.flatMap { partLeaves(tree[it]) }
    }

    private fun icon(node: SummaryNode): Icon = when (node.kind) {
        SummaryKind.FILE -> FileTypeManager.getInstance().getFileTypeByFileName(node.path.substringAfterLast('/')).icon ?: AllIcons.FileTypes.Any_type
        SummaryKind.CHUNK -> AllIcons.FileTypes.Text
        SummaryKind.DIRECTORY -> AllIcons.Nodes.Folder
        SummaryKind.GROUP -> AllIcons.Actions.GroupBy
        SummaryKind.ROOT -> AllIcons.Vcs.Changelist
    }

    /** Pixel insets: values here are already scaled for the screen and the canvas zoom, unlike JBUI's self-scaling borders. */
    private fun pad(top: Int = 0, left: Int = 0, bottom: Int = 0, right: Int = 0) = EmptyBorder(top, left, bottom, right)

    private fun italic(base: Font) = font(base).deriveFont(font(base).style or Font.ITALIC)

    private fun label(text: String, font: Font, color: Color? = null) = JLabel(text).apply {
        this.font = font
        color?.let { foreground = it }
    }

    private fun text(value: String, width: Int, font: Font, color: Color?): JComponent = WrappedText(value, width).apply {
        isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; border = null
        this.font = font
        color?.let { foreground = it }
    }

    private fun plural(count: Int, word: String) = "$count $word${if (count == 1) "" else "s"}"

    companion object {
        const val INDENT = 20
        const val TOGGLE = 14
        const val ICON = 16
    }
}
