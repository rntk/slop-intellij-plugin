package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
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

/**
 * A folder, group or the root: the branches that levels and "Files" open. A file with parts is a branch too, but is opened by
 * hand, so reading by level never unfolds every part of every large file.
 */
internal fun isFolderBranch(node: SummaryNode): Boolean =
    node.children.isNotEmpty() && node.kind != SummaryKind.FILE && node.kind != SummaryKind.CHUNK

/**
 * The outline reads the tree top-down as indented summaries; the hierarchy reads the folders left to right; the document reads
 * every file in tree order with its summary and the folder rail, as topics do.
 */
internal enum class ChangeSummariesMode { OUTLINE, HIERARCHY, DOCUMENT }

/**
 * Word contexts and the current trace of one card's summaries. Complete word contexts are retained without LRU eviction so
 * hovering never needs a request. [traceRoot] is false where the root summary is not shown, so its words are not scored.
 */
internal abstract class SummaryTraceState(private val traceRoot: Boolean = true) {
    val scored = mutableMapOf<Pair<String, Int>, SummaryContext>()
    var scoring = false
    var scoringError: String? = null
    var tracing: SummaryTracing? = null
    /** A node to scroll into view once the card is laid out again. */
    var reveal: String? = null

    fun cached(identity: String, summary: String, word: Int): SummaryContext? =
        scored[identity to word]?.takeIf { it.summary == summary }

    /** Score only the nodes on screen: a huge tree has far too many words to score at once. */
    var visibleOnly = false
    private var pendingFor: ChangeSummaries? = null
    private var pendingScored = -1
    private var pendingAll = emptyList<Pair<Int, Int>>()

    /** The nodes whose summaries are on screen, or null where every node counts. */
    protected open fun visibility(tree: ChangeSummaryTree): ((SummaryNode) -> Boolean)? = null

    /**
     * The words still to score, as node id and word index. Only nodes [visible] count when given, else those on screen when
     * [visibleOnly]. Finding the words of every summary is the costly part, so it is kept for the same summaries and cache size.
     */
    fun pending(summaries: ChangeSummaries, visible: ((SummaryNode) -> Boolean)? = null): List<Pair<Int, Int>> {
        if (pendingFor !== summaries || pendingScored != scored.size) {
            pendingAll = summaries.tree.nodes
                .filter { it.children.isNotEmpty() && (traceRoot || it.kind != SummaryKind.ROOT) }
                .flatMap { node ->
                    val summary = summaries.text(node.id) ?: return@flatMap emptyList()
                    SummaryWord.split(summary).filter { it.meaningful && cached(node.identity, summary, it.index) == null }
                        .map { node.id to it.index }
                }
            pendingFor = summaries
            pendingScored = scored.size
        }
        val filter = visible ?: (if (visibleOnly) visibility(summaries.tree) else null) ?: return pendingAll
        val shown = HashMap<Int, Boolean>()
        return pendingAll.filter { (id, _) -> shown.getOrPut(id) { filter(summaries.tree[id]) } }
    }

    /** A trace stays only while the traced summary is unchanged; matches whose node is gone are dropped. */
    fun retain(summaries: ChangeSummaries) {
        pendingFor = null
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

    /** Opens the levels between the traced summary and its related ones, so every match is visible, and selects the best. */
    abstract fun showTrace(tree: ChangeSummaryTree, trace: SummaryTrace)
}

/**
 * The nodes a trace marks: the traced one, every node on the way down to a related one, and the related ones. [open] must be
 * expanded for every match to show; [fold] are related branches with no related node beneath, which a hierarchy folds so
 * their own summary shows beside them.
 */
internal class TracePath(val traced: String, val path: Set<String>, val open: Set<String>, val fold: Set<String>)

internal fun tracePath(tree: ChangeSummaryTree, trace: SummaryTrace): TracePath? {
    val byIdentity = tree.nodes.associateBy { it.identity }
    val traced = byIdentity[trace.identity] ?: return null
    val path = linkedSetOf(traced.identity)
    val open = hashSetOf<String>()
    for (match in trace.related) {
        val node = byIdentity[match.identity] ?: continue
        val above = generateSequence(node.parent) { tree[it].parent }.map(tree::get).toList()
        val toTraced = above.indexOfFirst { it.id == traced.id }.takeIf { it >= 0 } ?: continue
        path += node.identity
        above.take(toTraced + 1).forEach { path += it.identity; open += it.identity }
    }
    val fold = trace.related.mapNotNullTo(hashSetOf()) { match ->
        match.identity.takeIf { it in path && it !in open && byIdentity[it]?.children?.isNotEmpty() == true }
    }
    return TracePath(traced.identity, path, open, fold)
}

/** How a trace marks a hierarchy card: its traced summary, a node on the way to a match, or a match. */
internal enum class TraceMark { TRACED, PATH, RELATED, BEST }

/** The marks of [trace] by node identity. */
internal fun traceMarks(tree: ChangeSummaryTree, trace: SummaryTrace?): Map<String, TraceMark> {
    val path = trace?.let { tracePath(tree, it) } ?: return emptyMap()
    val related = trace.related.map { it.identity }.toSet()
    return path.path.associateWith { identity ->
        when {
            identity == trace.best?.identity -> TraceMark.BEST
            identity in related -> TraceMark.RELATED
            identity == path.traced -> TraceMark.TRACED
            else -> TraceMark.PATH
        }
    }
}

/** Colors of word tracing, shared by the outline and both hierarchies. */
internal object TraceStyle {
    val TRACED_WORD = JBColor(Color(0xFFD866), Color(0x7A6420))
    val CONTEXT_WORD = JBColor(Color(0xD6E8FF), Color(0x2F4A6B))
    val BEST_MATCH = JBColor(Color(0xFFF1C2), Color(0x4A4126))
    val RELATED = JBColor(Color(0xEEF5FF), Color(0x2B3545))
    val MATCH_TEXT = JBColor(Color(0x8A6100), Color(0xD9B44A))
    /** The outline of the traced card and every card on the way to a match. */
    val PATH = JBColor(Color(0xE0A800), Color(0xC9A227))
}

/** What scoring has done, or what to do next, above the summaries. */
internal fun scoringStatus(state: SummaryTraceState, summaries: ChangeSummaries): String = when {
    state.scoring -> "Scoring words… ${state.scored.size} cached · ${state.pending(summaries).size} remaining"
    state.scoringError != null -> "Scoring failed: ${state.scoringError}. Click Score to continue."
    state.scored.isEmpty() -> "Click Score to precalculate word contexts for " +
        (if (state.visibleOnly) "the summaries with children shown now." else "summaries with children.")
    else -> "Hover over a scored word to highlight its context. Click a highlight to search related summaries."
}

/** The query and progress of a trace; [where] says where its matches are shown. */
internal fun traceStatus(tracing: SummaryTracing, where: String = "highlighted below. Click a highlighted word to jump to the best."): String {
    val trace = tracing.trace
    val levels = "the ${SummaryTracer.LEVELS} levels below"
    return when {
        tracing.error != null -> "Tracing “${tracing.word.text}” failed: ${tracing.error}"
        tracing.context == null -> "Scoring the words around “${tracing.word.text}”…"
        trace == null && !tracing.searchRequested -> "“${tracing.context.query}” · Click a highlighted word to search related summaries."
        trace == null -> "“${tracing.context.query}” · Searching the summaries in $levels…"
        trace.candidates == 0 -> "“${trace.query}” · There are no summaries below this one to search."
        trace.related.isEmpty() -> "“${trace.query}” · None of the ${trace.candidates} summaries in $levels is related."
        else -> "“${trace.query}” · ${trace.related.size} of ${trace.candidates} summaries in $levels are related, $where"
    }
}

internal fun matchLabel(match: SummaryMatch, trace: SummaryTrace): String {
    val percent = (match.probability * 100).roundToInt()
    return if (match.identity == trace.best?.identity) "● Best match for “${trace.query}” · $percent%" else "● Related to “${trace.query}” · $percent%"
}

/**
 * Moving the pointer over a scored word of [summary] immediately shows its cached context; clicking a highlight searches for
 * matches. The traced word is marked strongly and its context, the words the query keeps, lightly. A click beside the words
 * calls [select].
 */
internal fun installWordTracing(
    area: JTextArea, identity: String, summary: String, state: SummaryTraceState,
    trace: (String, Int, Boolean) -> Unit, select: () -> Unit = {},
) {
    val words = SummaryWord.split(summary)
    val tracing = state.tracing?.takeIf { it.identity == identity && it.summary == summary }
    tracing?.let { current ->
        val context = current.context?.context.orEmpty().filter { it.index != current.word.index }
        context.forEach { area.highlighter.addHighlight(it.start, it.end, DefaultHighlighter.DefaultHighlightPainter(TraceStyle.CONTEXT_WORD)) }
        area.highlighter.addHighlight(current.word.start, current.word.end, DefaultHighlighter.DefaultHighlightPainter(TraceStyle.TRACED_WORD))
    }
    fun wordAt(point: Point): SummaryWord? {
        val offset = area.viewToModel2D(point)
        val word = words.firstOrNull { offset in it.start..it.end }?.takeIf { it.meaningful && state.cached(identity, summary, it.index) != null } ?: return null
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
            if (word != null && !highlighted(word)) trace(identity, word.index, false)
        }

        override fun mouseExited(e: MouseEvent) { pending = null }

        override fun mouseClicked(e: MouseEvent) {
            if (e.clickCount != 1) return
            val word = wordAt(e.point)
            if (word == null) select() else trace(identity, word.index, highlighted(word))
        }
    }
    area.addMouseListener(pointer)
    area.addMouseMotionListener(pointer)
}

/**
 * Expansion and selection survive re-rendering and snapshot changes; they are keyed by node identity and shared by both
 * modes, so folding a folder in the hierarchy collapses it in the outline too.
 */
internal class ChangeSummariesViewState : SummaryTraceState() {
    var mode = ChangeSummariesMode.OUTLINE
    val expanded = hashSetOf<String>()
    private val seen = hashSetOf<String>()
    var selected: String? = null
    /** Shows each node's file or folder name above its summary. */
    var showNames = false
    /** The level picked in the hierarchy's switcher; nodes that appear later open to that level too. */
    private var level: Int? = null
    private var tree: ChangeSummaryTree? = null

    /**
     * New nodes open by default in a small tree, only the root in a large one, or down to the picked level; nodes the reader
     * has seen keep their state.
     */
    fun update(tree: ChangeSummaryTree) {
        if (tree === this.tree) return
        this.tree = tree
        val small = tree.nodes.size <= SMALL_TREE
        val level = level
        for (node in tree.nodes) if (seen.add(node.identity) && isFolderBranch(node) &&
            (if (level != null) tree.depth(node.id) < level else small || node.kind == SummaryKind.ROOT))
            expanded += node.identity
        if (tree.nodes.none { it.identity == selected }) selected = null
    }

    fun toggle(identity: String) { if (!expanded.remove(identity)) expanded += identity }
    fun select(identity: String) { selected = if (selected == identity) null else identity }

    /** Level n opens the folders above depth n and folds the rest; [branchLevels] opens every folder down to the files. */
    fun selectLevel(level: Int, tree: ChangeSummaryTree) {
        this.level = level
        for (node in tree.nodes) if (isFolderBranch(node)) {
            if (tree.depth(node.id) < level) expanded += node.identity else expanded -= node.identity
        }
    }

    /** The level the current folding matches, if any; folding by hand can leave it between levels. */
    fun shownLevel(tree: ChangeSummaryTree): Int? {
        val branches = tree.nodes.filter(::isFolderBranch)
        return (0..branchLevels(tree)).firstOrNull { level -> branches.all { (it.identity in expanded) == (tree.depth(it.id) < level) } }
    }

    /** Document mode keeps every file visible and limits the rail to the picked levels. */
    fun railDepth(tree: ChangeSummaryTree): Int =
        minOf(branchLevels(tree), (shownLevel(tree) ?: branchLevels(tree)) + 1)

    /** A node is on screen when every folder above it is open. */
    override fun visibility(tree: ChangeSummaryTree): (SummaryNode) -> Boolean =
        { node -> generateSequence(node.parent) { tree[it].parent }.all { tree[it].identity in expanded } }

    /**
     * The outline only opens the levels down to the matches. The hierarchy shows a branch's summary only while it is folded,
     * so it also folds related branches with no related node beneath them.
     */
    override fun showTrace(tree: ChangeSummaryTree, trace: SummaryTrace) {
        tracePath(tree, trace)?.let { path ->
            expanded += path.open
            if (mode == ChangeSummariesMode.HIERARCHY) expanded -= path.fold
        }
        trace.best?.let { selected = it.identity; reveal = it.identity }
    }

    companion object {
        const val SMALL_TREE = 30

        /** Columns of folders, groups and the root; an empty diff has none. */
        fun branchLevels(tree: ChangeSummaryTree): Int =
            tree.nodes.filter(::isFolderBranch).maxOfOrNull { tree.depth(it.id) + 1 } ?: 0
    }
}

/** How far summarizing has come, shown at the top of both modes. */
internal fun summariesStatus(card: SummariesCard, noFiles: String = ReviewVocabulary.DIFF.noFiles): String {
    val summaries = card.summaries
    val count = summaries.count
    val total = summaries.total
    val files = summaries.tree.root.files.size
    val fileCount = "$files file${if (files == 1) "" else "s"}"
    return when {
        total == 0 -> noFiles
        card.running && !summaries.complete -> "Summarizing… $count of $total summaries · $fileCount"
        summaries.complete -> "$total summaries · $fileCount"
        else -> "$count of $total summarized · $fileCount"
    }
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

/**
 * Prescoring and searching for one card's [content], kept in [state]; [refresh] rebuilds the card. Hover reads saved
 * contexts synchronously; clicking a highlight searches the levels below. Late results of a replaced run are ignored.
 */
internal class SummaryTraceSession(
    private val launcher: SummaryTraceLauncher?,
    private val state: SummaryTraceState,
    private val content: () -> ChangeSummaries?,
    private val refresh: () -> Unit,
) {
    private var traceJob: Job? = null
    private var scoreJob: Job? = null
    private var scoreToken: Any? = null
    private var scoringSummaries: ChangeSummaries? = null

    val available: Boolean get() = launcher != null

    fun prescore() {
        val launcher = launcher ?: return
        val content = content() ?: return
        val pending = state.pending(content)
        if (state.scoring || pending.isEmpty()) return
        val token = Any()
        scoreToken = token
        scoringSummaries = content
        state.scoring = true
        state.scoringError = null
        refresh()
        scoreJob = launcher.prescore(content, pending, { context ->
            if (scoreToken === token) {
                state.scored[context.identity to context.word.index] = context
                refresh()
            }
        }) { result ->
            if (scoreToken === token) {
                state.scoring = false
                state.scoringError = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                scoringSummaries = null
                scoreToken = null
                refresh()
            }
        }
    }

    fun trace(identity: String, word: Int, search: Boolean) {
        val content = content() ?: return
        val node = content.tree.nodes.firstOrNull { it.identity == identity } ?: return
        val text = content.text(node.id) ?: return
        val words = SummaryWord.split(text)
        if (word !in words.indices) return
        val current = state.tracing?.takeIf { it.identity == identity && it.summary == text }
        val highlighted = current != null && (current.word.index == word || current.context?.context?.any { it.index == word } == true)
        if (current != null && highlighted && (current.error == null || current.context != null)) {
            if (!search) return
            if (current.trace != null) state.showTrace(content.tree, current.trace)
            else if (!current.searchRequested || current.error != null) {
                state.tracing = current.copy(searchRequested = true, error = null)
                if (current.context != null) search()
            }
            refresh()
            return
        }
        traceJob?.cancel()
        val context = state.cached(identity, text, word) ?: return
        state.tracing = SummaryTracing(identity, text, words[word], context = context)
        refresh()
    }

    private fun search() {
        val launcher = launcher ?: return
        val content = content() ?: return
        val current = state.tracing ?: return
        val context = current.context ?: return
        val node = content.tree.nodes.firstOrNull { it.identity == current.identity } ?: return
        traceJob = launcher.search(content, node.id, context) { result ->
            val now = state.tracing
            if (now == null || now.token !== current.token) return@search
            result.onSuccess { trace ->
                state.tracing = now.copy(trace = trace)
                content()?.let { state.showTrace(it.tree, trace) }
            }.onFailure { error -> state.tracing = now.copy(error = error.message ?: error.javaClass.simpleName) }
            refresh()
        }
    }

    /** Summary updates cancel an active scoring run; a trace stays while its summary is unchanged. */
    fun retain(content: ChangeSummaries) {
        scoringSummaries?.let { if (!sameSummaries(it, content)) stopScoring() }
        val before = state.tracing
        state.retain(content)
        if (before != null && state.tracing == null) traceJob?.cancel()
    }

    /** Drops the current trace and its marks; saved word contexts stay. */
    fun clear() {
        traceJob?.cancel()
        traceJob = null
        state.tracing = null
        refresh()
    }

    fun stop() {
        stopScoring()
        traceJob?.cancel()
        traceJob = null
        state.tracing = null
    }

    private fun stopScoring() {
        scoreToken = null
        scoringSummaries = null
        scoreJob?.cancel()
        scoreJob = null
        state.scoring = false
        state.scoringError = null
    }

    /** Equal trees and texts: a rebuilt but unchanged value keeps a scoring run going. */
    private fun sameSummaries(a: ChangeSummaries, b: ChangeSummaries): Boolean = a === b ||
        a.tree.nodes.size == b.tree.nodes.size && a.tree.nodes.indices.all { a.tree[it].identity == b.tree[it].identity && a.text(it) == b.text(it) }
}

internal class ChangeSummaryActions(
    val toggle: (String) -> Unit,
    val select: (String) -> Unit,
    val openFile: (String) -> Unit,
    val showDiffCard: (String) -> Unit,
    val showNames: (Boolean) -> Unit = {},
    /** Scores word [Int]; with `true`, clicking a highlight requests a search and selects the best match. */
    val trace: (String, Int, Boolean) -> Unit = { _, _, _ -> },
    /** Open the file of a node, at the node's first line for a part; by default the file by its path. */
    val openNode: (SummaryNode) -> Unit = { openFile(it.path) },
    /** Put a node's source on the canvas; null hides the buttons. */
    val showNode: ((SummaryNode) -> Unit)? = { showDiffCard(it.path) },
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
    private val vocabulary: ReviewVocabulary = ReviewVocabulary.DIFF,
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
    /** Rows added so far; a huge tree stops at [HierarchyColumnsPanel.MAX_ROWS] and says how many entries it left out. */
    private var rows = 0
    private var skipped = 0

    init {
        isOpaque = false
        add(JBCheckBox("Show names", state.showNames).apply {
            name = "summaries-show-names"
            font = font(JBFont.small())
            isOpaque = false
            toolTipText = "Show each file and folder name above its summary"
            border = pad(bottom = scaled(6))
            addActionListener { actions.showNames(isSelected) }
        })
        add(text(summariesStatus(card, vocabulary.noFiles), cardWidth, font(JBFont.small()), help).apply { border = pad(bottom = scaled(8)) })
        add(text(scoringStatus(state, summaries), cardWidth, font(JBFont.small()), help)
            .apply { name = "scoring-status"; border = pad(bottom = scaled(8)) })
        if (summaries.total > 0) addNode(tree.root, 0)
        if (skipped > 0) add(text("+$skipped more — fold or pick a level", cardWidth, italic(JBFont.small()), help)
            .apply { name = "more"; border = pad(scaled(4), scaled(TOGGLE), scaled(4), 0) })
        add(text(vocabulary.summariesFooter, cardWidth, font(JBFont.small()), help)
            .apply { border = pad(top = scaled(10)) })
    }

    private fun addNode(node: SummaryNode, depth: Int) {
        rows++
        add(row(node, depth))
        if (node.identity !in state.expanded) return
        for ((index, child) in node.children.withIndex()) {
            if (rows >= HierarchyColumnsPanel.MAX_ROWS) {
                skipped += node.children.size - index
                return
            }
            addNode(tree[child], depth + 1)
        }
    }

    private fun row(node: SummaryNode, depth: Int): JComponent {
        val selected = node.identity == state.selected
        val gutter = scaled(TOGGLE) + scaled(ICON) + scaled(8)
        val textWidth = (cardWidth - depth * scaled(INDENT) - gutter - scaled(8)).coerceAtLeast(scaled(160))
        val summary = summaries.text(node.id)
        val tracing = state.tracing?.takeIf { it.identity == node.identity && it.summary == summary }
        val match = matches[node.identity]
        val nameText = if (state.showNames) text(displayName(node), textWidth, font(JBFont.small().asBold()), help) else null
        val missing = if (card.running) "summarizing…" else "no summary yet"
        val summaryText = if (summary != null) text(summary, textWidth, font(if (node.kind == SummaryKind.ROOT) JBFont.label().asBold() else JBFont.label()), null)
                .also { area ->
                    if (node.children.isNotEmpty())
                        installWordTracing(area as JTextArea, node.identity, summary, state, actions.trace) { actions.select(node.identity) }
                }
            else text(if (nameText != null) missing else "${node.label} · $missing", textWidth, italic(JBFont.label()), help)
        val content = JPanel(VerticalLayout(scaled(4))).apply {
            isOpaque = false
            nameText?.let { add(it) }
            add(summaryText)
            tracing?.let { add(text(traceStatus(it), textWidth, font(JBFont.small()), help).apply { name = "trace:${node.identity}" }) }
            match?.let { add(text(matchLabel(it, trace!!), textWidth, font(JBFont.small()), TraceStyle.MATCH_TEXT).apply { name = "match:${node.identity}" }) }
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
                match != null && match.identity == best -> TraceStyle.BEST_MATCH
                else -> TraceStyle.RELATED
            }
            border = pad(scaled(4), depth * scaled(INDENT), scaled(4), scaled(4))
            toolTipText = node.location
            add(gutterPanel, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
            // The summary text covers most of the row, so it selects too; details keep their own links and text selection.
            // In interactive reading, a click on a highlighted word searches and jumps; the rest of the row still selects.
            val select = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 1) actions.select(node.identity) } }
            listOfNotNull(this, gutterPanel, content, nameText, summaryText.takeIf { node.children.isEmpty() || summary == null })
                .forEach { it.addMouseListener(select) }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    /** The name shown above a summary with Show names: a file name, a merged folder chain, a group's range, or the root's folder. */
    private fun displayName(node: SummaryNode): String = when (node.kind) {
        SummaryKind.FILE, SummaryKind.GROUP, SummaryKind.CHUNK -> node.label
        SummaryKind.DIRECTORY -> "${node.label}/"
        SummaryKind.ROOT -> node.location
    }

    /** The real name and location of a node, which its summary replaces in the outline. */
    private fun details(node: SummaryNode, width: Int): JComponent = JPanel(VerticalLayout(scaled(2))).apply {
        isOpaque = false
        name = "details:${node.identity}"
        border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), pad(top = scaled(4)))
        val count = plural(node.files.size, "file")
        val title = when (node.kind) {
            SummaryKind.FILE -> "${node.path.substringAfterLast('/')} · ${vocabulary.describeFile(node.file)}"
            SummaryKind.CHUNK -> "${node.label} of ${node.path.substringAfterLast('/')}" + (partOf(node)?.let { " · $it" } ?: "")
            SummaryKind.DIRECTORY -> "${node.path.substringAfterLast('/')}/ · folder · $count"
            SummaryKind.GROUP -> "${node.label} · ${node.children.size} entries of ${node.path.ifEmpty { "." }}/ · $count"
            SummaryKind.ROOT -> "${vocabulary.rootName} · $count"
        }
        add(text(title, width, font(JBFont.label().asBold()), null))
        // Folders have no review file of their own; their location follows from any file beneath them.
        val absolute = node.file?.absolutePath ?: files[node.files.firstOrNull()]?.let { file ->
            val base = file.absolutePath.removeSuffix(file.path).removeSuffix("/")
            if (node.path.isEmpty()) "$base/" else "$base/${node.path}/"
        }
        listOfNotNull(node.location, absolute?.takeIf { it != node.location }).forEach { add(text(it, width, font(JBFont.small()), help)) }
        if (node.kind == SummaryKind.FILE || node.kind == SummaryKind.CHUNK) add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(12), 0)).apply {
            isOpaque = false
            add(ActionLink(if (node.kind == SummaryKind.CHUNK) "Open lines" else vocabulary.openLabel) { actions.openNode(node) }
                .apply { font = font(JBFont.small()) })
            val show = actions.showNode
            val showText = vocabulary.showOnCanvas
            if (show != null && showText != null) add(ActionLink(showText) { show(node) }.apply { font = font(JBFont.small()) })
        })
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
