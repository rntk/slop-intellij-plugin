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
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder
import javax.swing.text.DefaultHighlighter
import kotlinx.coroutines.Job
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

    /** Complete word contexts are retained without LRU eviction so hovering never needs a request. */
    val scored = mutableMapOf<Pair<String, Int>, SummaryContext>()
    var scoring = false
    var scoringError: String? = null

    fun cached(identity: String, summary: String, word: Int): SummaryContext? =
        scored[identity to word]?.takeIf { it.summary == summary }

    fun pending(summaries: ChangeSummaries): List<Pair<Int, Int>> = summaries.tree.nodes
        .filter { it.children.isNotEmpty() }
        .flatMap { node ->
            val summary = summaries.text(node.id) ?: return@flatMap emptyList()
            SummaryWord.split(summary).filter { it.meaningful && cached(node.identity, summary, it.index) == null }
                .map { node.id to it.index }
        }
    var tracing: SummaryTracing? = null
    /** A row to scroll into view once the card is laid out again. */
    var reveal: String? = null

    /** A trace stays only while the traced summary is unchanged; matches whose node is gone are dropped. */
    fun retain(summaries: ChangeSummaries) {
        val nodes = summaries.tree.nodes.associateBy { it.identity }
        scored.entries.removeIf { (_, context) ->
            val node = nodes[context.identity]
            node == null || node.children.isEmpty() || summaries.text(node.id) != context.summary
        }
        val current = tracing ?: return
        val node = nodes[current.identity]
        tracing = if (node == null || summaries.text(node.id) != current.summary) null
            else current.copy(trace = current.trace?.let { trace -> trace.copy(matches = trace.matches.filter { it.identity in nodes }) })
    }

    /** Opens the levels between the traced summary and its related ones, so every match is visible. */
    fun show(tree: ChangeSummaryTree, trace: SummaryTrace) {
        val byIdentity = tree.nodes.associateBy { it.identity }
        val traced = byIdentity[trace.identity] ?: return
        for (match in trace.related) {
            var parent = byIdentity[match.identity]?.parent
            while (parent != null) {
                expanded += tree[parent].identity
                if (parent == traced.id) break
                parent = tree[parent].parent
            }
        }
    }

    companion object { const val SMALL_TREE = 30 }
}

/** Scoring publishes [context] first. A click requests the separate search and selects its best match. */
internal data class SummaryTracing(
    val identity: String,
    val summary: String,
    val word: SummaryWord,
    val context: SummaryContext? = null,
    val trace: SummaryTrace? = null,
    val error: String? = null,
    val searchRequested: Boolean = false,
    val token: Any = Any(),
)

/** Runs each stage off the EDT and reports back on it; newer words cancel the returned job. */
internal interface SummaryTraceLauncher {
    fun prescore(summaries: ChangeSummaries, words: List<Pair<Int, Int>>,
                 scored: (SummaryContext) -> Unit, done: (Result<Unit>) -> Unit): Job
    fun search(summaries: ChangeSummaries, id: Int, context: SummaryContext, done: (Result<SummaryTrace>) -> Unit): Job
}

internal class ChangeSummaryActions(
    val toggle: (String) -> Unit,
    val select: (String) -> Unit,
    val openFile: (String) -> Unit,
    val showDiffCard: (String) -> Unit,
    /** Scores word [Int]; with `true`, clicking a highlight requests a search and selects the best match. */
    val trace: (String, Int, Boolean) -> Unit = { _, _, _ -> },
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
    private val trace = state.tracing?.trace
    private val matches = trace?.related.orEmpty().associateBy { it.identity }
    private val best = trace?.best?.identity

    init {
        isOpaque = false
        add(text(status(), cardWidth, font(JBFont.small()), help).apply { border = pad(bottom = scaled(8)) })
        add(text(when {
            state.scoring -> "Scoring words… ${state.scored.size} cached · ${state.pending(summaries).size} remaining"
            state.scoringError != null -> "Scoring failed: ${state.scoringError}. Click Score to continue."
            state.scored.isEmpty() -> "Click Score to precalculate word contexts for summaries with children."
            else -> "Hover over a scored word to highlight its context. Click a highlight to search related summaries."
        }, cardWidth, font(JBFont.small()), help).apply { name = "scoring-status"; border = pad(bottom = scaled(8)) })
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
        val tracing = state.tracing?.takeIf { it.identity == node.identity && it.summary == summary }
        val match = matches[node.identity]
        val content = JPanel(VerticalLayout(scaled(4))).apply {
            isOpaque = false
            add(if (summary != null) text(summary, textWidth, font(if (node.kind == SummaryKind.ROOT) JBFont.label().asBold() else JBFont.label()), null)
                .also { area -> if (node.children.isNotEmpty()) interactive(area as JTextArea, node, summary, tracing) }
                else text("${node.label} · ${if (card.running) "summarizing…" else "no summary yet"}", textWidth, italic(JBFont.label()), help))
            tracing?.let { add(text(traceStatus(it), textWidth, font(JBFont.small()), help).apply { name = "trace:${node.identity}" }) }
            match?.let { add(text(matchLabel(it), textWidth, font(JBFont.small()), MATCH_TEXT).apply { name = "match:${node.identity}" }) }
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
            isOpaque = selected || match != null
            background = when {
                selected -> UIUtil.getListSelectionBackground(false)
                match != null && match.identity == best -> BEST_MATCH
                else -> RELATED
            }
            border = pad(scaled(4), depth * scaled(INDENT), scaled(4), scaled(4))
            toolTipText = node.location
            add(gutterPanel, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
            // The summary text covers most of the row, so it selects too; details keep their own links and text selection.
            // In interactive reading, a click on a highlighted word searches and jumps; the rest of the row still selects.
            val select = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 1) actions.select(node.identity) } }
            listOfNotNull(this, gutterPanel, content, content.getComponent(0).takeIf { node.children.isEmpty() || summary == null })
                .forEach { it.addMouseListener(select) }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    /**
     * Moving the pointer over a scored word immediately shows its cached context; clicking a highlight searches for matches.
     * The traced word is marked strongly and its context, the words the query keeps, lightly.
     */
    private fun interactive(area: JTextArea, node: SummaryNode, summary: String, tracing: SummaryTracing?) {
        val words = SummaryWord.split(summary)
        tracing?.let { current ->
            val context = current.context?.context.orEmpty().filter { it.index != current.word.index }
            context.forEach { area.highlighter.addHighlight(it.start, it.end, DefaultHighlighter.DefaultHighlightPainter(CONTEXT_WORD)) }
            area.highlighter.addHighlight(current.word.start, current.word.end, DefaultHighlighter.DefaultHighlightPainter(TRACED_WORD))
        }
        fun wordAt(point: Point): SummaryWord? {
            val offset = area.viewToModel2D(point)
            val word = words.firstOrNull { offset in it.start..it.end }?.takeIf { it.meaningful && state.cached(node.identity, summary, it.index) != null } ?: return null
            // The nearest offset is not enough: the pointer must be on the word, not beside it or past the end of its line.
            val start = area.modelToView2D(word.start) ?: return null
            val end = area.modelToView2D(word.end) ?: return null
            val onLine = point.y >= start.y && point.y <= end.maxY
            val inWord = if (start.y == end.y) point.x >= start.x && point.x <= end.x else point.y <= start.maxY && point.x >= start.x || point.y >= end.y && point.x <= end.x
            return word.takeIf { onLine && inWord }
        }
        fun highlighted(word: SummaryWord) = tracing?.let { current ->
            word.index == current.word.index || current.context?.context?.any { it.index == word.index } == true
        } == true
        var pending: SummaryWord? = null
        val pointer = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val word = wordAt(e.point)
                area.cursor = Cursor.getPredefinedCursor(if (word != null) Cursor.HAND_CURSOR else Cursor.TEXT_CURSOR)
                if (word?.index == pending?.index) return
                pending = word
                if (word != null && !highlighted(word)) actions.trace(node.identity, word.index, false)
            }

            override fun mouseExited(e: MouseEvent) { pending = null }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 1) return
                val word = wordAt(e.point)
                if (word == null) actions.select(node.identity) else actions.trace(node.identity, word.index, highlighted(word))
            }
        }
        area.addMouseListener(pointer)
        area.addMouseMotionListener(pointer)
    }

    private fun traceStatus(tracing: SummaryTracing): String {
        val trace = tracing.trace
        val levels = "the ${SummaryTracer.LEVELS} levels below"
        return when {
            tracing.error != null -> "Tracing “${tracing.word.text}” failed: ${tracing.error}"
            tracing.context == null -> "Scoring the words around “${tracing.word.text}”…"
            trace == null && !tracing.searchRequested -> "“${tracing.context.query}” · Click a highlighted word to search related summaries."
            trace == null -> "“${tracing.context.query}” · Searching the summaries in $levels…"
            trace.candidates == 0 -> "“${trace.query}” · There are no summaries below this one to search."
            trace.related.isEmpty() -> "“${trace.query}” · None of the ${trace.candidates} summaries in $levels is related."
            else -> "“${trace.query}” · ${trace.related.size} of ${trace.candidates} summaries in $levels are related, highlighted below. " +
                "Click a highlighted word to jump to the best."
        }
    }

    private fun matchLabel(match: SummaryMatch): String {
        val percent = (match.probability * 100).roundToInt()
        val query = trace?.query.orEmpty()
        return if (match.identity == best) "● Best match for “$query” · $percent%" else "● Related to “$query” · $percent%"
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
        const val ICON = 16
        private val TRACED_WORD = JBColor(Color(0xFFD866), Color(0x7A6420))
        private val CONTEXT_WORD = JBColor(Color(0xD6E8FF), Color(0x2F4A6B))
        private val BEST_MATCH = JBColor(Color(0xFFF1C2), Color(0x4A4126))
        private val RELATED = JBColor(Color(0xEEF5FF), Color(0x2B3545))
        private val MATCH_TEXT = JBColor(Color(0x8A6100), Color(0xD9B44A))
    }
}
