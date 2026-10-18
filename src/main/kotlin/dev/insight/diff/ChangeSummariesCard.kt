package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
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
import javax.swing.border.EmptyBorder
import kotlin.math.roundToInt

/** What the canvas shows for a tab's summaries: the tree so far and whether a /summaries turn can run. */
internal data class SummariesCard(val summaries: ChangeSummaries, val running: Boolean, val canRun: Boolean)

/** Expansion and selection survive re-rendering and snapshot changes; they are keyed by node identity. */
internal class ChangeSummariesViewState {
    val expanded = hashSetOf<String>()
    private val seen = hashSetOf<String>()
    var selected: String? = null
    private var tree: ChangeSummaryTree? = null

    /** New nodes open by default in a small tree, only the root in a large one; nodes the reader has seen keep their state. */
    fun update(tree: ChangeSummaryTree) {
        if (tree === this.tree) return
        this.tree = tree
        val small = tree.nodes.size <= SMALL_TREE
        for (node in tree.nodes) if (seen.add(node.identity) && node.children.isNotEmpty() && (small || node.kind == SummaryKind.ROOT))
            expanded += node.identity
        if (tree.nodes.none { it.identity == selected }) selected = null
    }

    fun toggle(identity: String) { if (!expanded.remove(identity)) expanded += identity }
    fun select(identity: String) { selected = if (selected == identity) null else identity }

    companion object { const val SMALL_TREE = 30 }
}

internal class ChangeSummaryActions(
    val toggle: (String) -> Unit,
    val select: (String) -> Unit,
    val openFile: (String) -> Unit,
    val showDiffCard: (String) -> Unit,
)

/**
 * The summary tree as an outline: every file, folder and group reads as its summary, so the top levels tell the change
 * and deeper levels how it was done. Selecting a summary shows the real name and path beneath it.
 */
internal class ChangeSummariesPanel(
    private val card: SummariesCard,
    private val state: ChangeSummariesViewState,
    private val cardWidth: Int,
    private val zoom: Double,
    private val actions: ChangeSummaryActions,
) : JPanel(VerticalLayout(0)) {
    private val summaries = card.summaries
    private val tree = summaries.tree
    private val files = tree.nodes.mapNotNull { it.file }.associateBy { it.path }
    private fun scaled(n: Int) = (JBUI.scale(n) * zoom).roundToInt().coerceAtLeast(1)
    private fun font(base: Font) = base.deriveFont((base.size2D * zoom).toFloat())
    private val help get() = UIUtil.getContextHelpForeground()

    init {
        isOpaque = false
        add(text(status(), cardWidth, font(JBFont.small()), help).apply { border = pad(bottom = scaled(8)) })
        if (summaries.total > 0) addNode(tree.root, 0)
        add(text("The tree comes from the changed paths; single-entry folders are merged and large folders split into groups. " +
            "Summaries are LLM interpretation. Select one to see its file or folder.", cardWidth, font(JBFont.small()), help)
            .apply { border = pad(top = scaled(10)) })
    }

    private fun status(): String {
        val count = summaries.count
        val total = summaries.total
        val fileCount = plural(tree.root.files.size, "file")
        return when {
            total == 0 -> "No changed files"
            card.running && !summaries.complete -> "Summarizing… $count of $total summaries · $fileCount"
            summaries.complete -> "$total summaries · $fileCount"
            else -> "$count of $total summarized · $fileCount"
        }
    }

    private fun addNode(node: SummaryNode, depth: Int) {
        add(row(node, depth))
        if (node.identity in state.expanded) node.children.forEach { addNode(tree[it], depth + 1) }
    }

    private fun row(node: SummaryNode, depth: Int): JComponent {
        val selected = node.identity == state.selected
        val gutter = scaled(TOGGLE) + scaled(ICON) + scaled(8)
        val textWidth = (cardWidth - depth * scaled(INDENT) - gutter - scaled(8)).coerceAtLeast(scaled(160))
        val summary = summaries.text(node.id)
        val content = JPanel(VerticalLayout(scaled(4))).apply {
            isOpaque = false
            add(if (summary != null) text(summary, textWidth, font(if (node.kind == SummaryKind.ROOT) JBFont.label().asBold() else JBFont.label()), null)
                else text("${node.label} · ${if (card.running) "summarizing…" else "no summary yet"}", textWidth, italic(JBFont.label()), help))
            if (selected) add(details(node, textWidth))
        }
        val gutterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(if (node.children.isEmpty()) JLabel().apply { preferredSize = Dimension(scaled(TOGGLE), 1) }
                else label(if (node.identity in state.expanded) "▾" else "▸", font(JBFont.label()), help).apply {
                    preferredSize = Dimension(scaled(TOGGLE), preferredSize.height)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = if (node.identity in state.expanded) "Collapse" else "Expand"
                    name = "toggle:${node.identity}"
                    addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { actions.toggle(node.identity) } })
                })
            add(JLabel(icon(node)).apply { preferredSize = Dimension(scaled(ICON) + scaled(8), preferredSize.height) })
        }
        return JPanel(BorderLayout()).apply {
            name = "summary:${node.identity}"
            isOpaque = selected
            if (selected) background = UIUtil.getListSelectionBackground(false)
            border = pad(scaled(4), depth * scaled(INDENT), scaled(4), scaled(4))
            toolTipText = node.location
            add(gutterPanel, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
            // The summary text covers most of the row, so it selects too; details keep their own links and text selection.
            val select = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 1) actions.select(node.identity) } }
            listOf(this, gutterPanel, content, content.getComponent(0)).forEach { it.addMouseListener(select) }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    /** The real name and location of a node, which its summary replaces in the outline. */
    private fun details(node: SummaryNode, width: Int): JComponent = JPanel(VerticalLayout(scaled(2))).apply {
        isOpaque = false
        name = "details:${node.identity}"
        border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), pad(top = scaled(4)))
        val count = plural(node.files.size, "file")
        val title = when (node.kind) {
            SummaryKind.FILE -> "${node.path.substringAfterLast('/')} · ${node.file?.status ?: "changed"} file" +
                (node.file?.previousPath?.let { " · renamed from $it" } ?: "")
            SummaryKind.DIRECTORY -> "${node.path.substringAfterLast('/')}/ · folder · $count"
            SummaryKind.GROUP -> "${node.label} · ${node.children.size} entries of ${node.path.ifEmpty { "." }}/ · $count"
            SummaryKind.ROOT -> "All changes · $count"
        }
        add(text(title, width, font(JBFont.label().asBold()), null))
        // Folders have no review file of their own; their location follows from any file beneath them.
        val absolute = node.file?.absolutePath ?: files[node.files.firstOrNull()]?.let { file ->
            val base = file.absolutePath.removeSuffix(file.path).removeSuffix("/")
            if (node.path.isEmpty()) "$base/" else "$base/${node.path}/"
        }
        listOfNotNull(node.location, absolute?.takeIf { it != node.location }).forEach { add(text(it, width, font(JBFont.small()), help)) }
        if (node.kind == SummaryKind.FILE) add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(12), 0)).apply {
            isOpaque = false
            add(ActionLink("View diff") { actions.openFile(node.path) }.apply { font = font(JBFont.small()) })
            add(ActionLink("Show diff on canvas") { actions.showDiffCard(node.path) }.apply { font = font(JBFont.small()) })
        })
    }

    private fun icon(node: SummaryNode): Icon = when (node.kind) {
        SummaryKind.FILE -> FileTypeManager.getInstance().getFileTypeByFileName(node.path.substringAfterLast('/')).icon ?: AllIcons.FileTypes.Any_type
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
        const val ICON = 16    }
}
