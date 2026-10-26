package dev.insight.diff

import com.intellij.openapi.util.Key
import com.intellij.ui.ClientProperty
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.text.DefaultHighlighter
import javax.swing.text.JTextComponent
import kotlinx.coroutines.Job
import kotlin.math.roundToInt

/**
 * Word contexts and the current trace of one card's summaries. Complete word contexts are retained without LRU eviction so
 * hovering never needs a request. [traceRoot] is false where the root summary is not shown, so its words are not scored;
 * [traceLeaves] scores the summaries of files and parts too, whose highlights search the file's lines. Views of the same
 * summaries may share [scored], so words scored in one are ready in the others.
 */
internal abstract class SummaryTraceState(
    private val traceRoot: Boolean = true,
    val traceLeaves: Boolean = false,
    val scored: MutableMap<Pair<String, Int>, SummaryContext> = mutableMapOf(),
) {
    var scoring = false
    var scoringError: String? = null
    var tracing: SummaryTracing? = null
    open val resultTrace: SummaryTrace? get() = tracing?.trace
    /** A node to scroll into view once the card is laid out again. */
    var reveal: String? = null

    /** The words of the last hover or selection, highlighted wherever they recur in the summaries below and above it. */
    var echo: SummaryEcho? = null
        private set
    /** Set by the session that owns this state: the current tree, and how to repaint highlights in place. */
    var echoTree: () -> ChangeSummaryTree? = { null }
    var echoChanged: () -> Unit = {}

    /** Echoes the meaningful [words] of [identity]'s summary in its descendants and ancestors; repaints only when they change. */
    fun echo(identity: String, words: Collection<SummaryWord>, selection: Boolean = false) {
        val texts = words.filter { it.meaningful }.mapTo(hashSetOf()) { it.text.lowercase() }
        if (texts.isEmpty()) return clearEcho()
        val current = echo
        if (current != null && current.identity == identity && current.words == texts && current.selection == selection) return
        val node = if (current?.identity == identity) null else echoTree()?.let { tree ->
            tree.nodes.firstOrNull { it.identity == identity }?.let { tree to it }
        }
        val below = if (current?.identity == identity) current.below else node?.let { (tree, node) ->
            generateSequence(node.children) { ids -> ids.flatMap { tree[it].children }.takeIf { it.isNotEmpty() } }
                .flatten().mapTo(hashSetOf()) { tree[it].identity }
        }.orEmpty()
        val above = if (current?.identity == identity) current.above else node?.let { (tree, node) ->
            generateSequence(node.parent) { tree[it].parent }.mapTo(hashSetOf()) { tree[it].identity }
        }.orEmpty()
        echo = SummaryEcho(identity, below, above, texts, selection)
        echoChanged()
    }

    /** Clears the echo; with [identity], only a selection's echo from that summary. */
    fun clearEcho(identity: String? = null) {
        val current = echo ?: return
        if (identity != null && (current.identity != identity || !current.selection)) return
        echo = null
        echoChanged()
    }

    fun cached(identity: String, summary: String, word: Int): SummaryContext? =
        scored[identity to word]?.takeIf { it.summary == summary }

    /** Whether [node]'s words are scored: branches, and with [traceLeaves] the files and parts whose lines can be searched. */
    fun traced(node: SummaryNode): Boolean = (traceRoot || node.kind != SummaryKind.ROOT) &&
        (node.children.isNotEmpty() || traceLeaves && node.file != null)

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
                .filter { traced(it) }
                .flatMap { pendingWords(summaries, it) }
            pendingFor = summaries
            pendingScored = scored.size
        }
        val filter = visible ?: (if (visibleOnly) visibility(summaries.tree) else null) ?: return pendingAll
        val shown = HashMap<Int, Boolean>()
        return pendingAll.filter { (id, _) -> shown.getOrPut(id) { filter(summaries.tree[id]) } }
    }

    /** Only this summary's missing words, independent of folding and visibility. */
    fun pending(summaries: ChangeSummaries, identity: String): List<Pair<Int, Int>> =
        summaries.tree.nodes.firstOrNull { it.identity == identity && traced(it) }
            ?.let { pendingWords(summaries, it) }.orEmpty()

    private fun pendingWords(summaries: ChangeSummaries, node: SummaryNode): List<Pair<Int, Int>> {
        val summary = summaries.text(node.id) ?: return emptyList()
        return SummaryWord.split(summary).filter { it.meaningful && cached(node.identity, summary, it.index) == null }
            .map { node.id to it.index }
    }

    /** A trace stays only while the traced summary is unchanged; matches whose node is gone are dropped. */
    fun retain(summaries: ChangeSummaries) {
        pendingFor = null
        val nodes = summaries.tree.nodes.associateBy { it.identity }
        scored.entries.removeIf { (_, context) ->
            val node = nodes[context.identity]
            node == null || !traced(node) || summaries.text(node.id) != context.summary
        }
        val current = tracing ?: return
        val node = nodes[current.identity]
        tracing = if (node == null || summaries.text(node.id) != current.summary) null
            else current.copy(trace = current.trace?.let { trace -> trace.copy(matches = trace.matches.filter { it.identity in nodes }) })
    }

    /** Opens the levels between the traced summary and its related ones, so every match is visible, and selects the best. */
    abstract fun showTrace(tree: ChangeSummaryTree, trace: SummaryTrace)

    /** Clears the current word context and result decorations. */
    open fun clearTrace() { tracing = null; echo = null }
}

/**
 * Words of [identity]'s summary, lowercased, to highlight in the summaries [below] it and in the ancestors [above] it up to the
 * root; [selection] when selected by hand.
 */
internal data class SummaryEcho(
    val identity: String, val below: Set<String>, val above: Set<String>, val words: Set<String>, val selection: Boolean,
) {
    fun reaches(other: String): Boolean = other in below || other in above
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
    /** Hovered, scored or selected words recurring in the summaries below and above theirs. */
    val ECHO_WORD = JBColor(Color(0xE6DAFF), Color(0x463A66))
    /** The words of a match that continue the search below it, clicked to follow it. */
    val TRAIL_WORD = JBColor(Color(0xCDEFC4), Color(0x2F5A33))
    /** The outline of the traced card and every card on the way to a match. */
    val PATH = JBColor(Color(0xE0A800), Color(0xC9A227))
}

/** What scoring has done, or what to do next, in the status area; [selection] mentions searching selected text. */
internal fun scoringStatus(state: SummaryTraceState, summaries: ChangeSummaries, selection: Boolean = false): String = when {
    state.scoring -> "Scoring words… ${state.scored.size} cached · ${state.pending(summaries).size} remaining"
    state.scoringError != null -> "Scoring failed: ${state.scoringError}. Click Score to continue."
    state.scored.isEmpty() -> "Click Score to precalculate word contexts for " + scoredSummaries(state) +
        if (selection) ", or select text and right-click Search." else "."
    state.traceLeaves -> "Hover over a scored word to highlight its context. Right-click a scored word and choose Search to search related summaries, " +
        "or a file's lines."
    else -> "Hover over a scored word to highlight its context. Right-click a scored word and choose Search to search related summaries."
}

/** Which summaries Score covers: "the summaries shown now", "summaries with children". */
internal fun scoredSummaries(state: SummaryTraceState): String = when {
    state.traceLeaves -> if (state.visibleOnly) "the summaries shown now" else "every summary"
    else -> if (state.visibleOnly) "the summaries with children shown now" else "summaries with children"
}

/** The query and progress of a trace; [where] says where its matches are shown. */
internal fun traceStatus(tracing: SummaryTracing, where: String = "highlighted below. Right-click a scored word and choose Search to jump to the best."): String {
    val trace = tracing.trace
    val levels = "the ${SummaryTracer.LEVELS} levels below"
    val lines = tracing.lines
    val file = tracing.file?.substringAfterLast('/')
    return when {
        tracing.error != null -> "Tracing “${tracing.word.text}” failed: ${tracing.error}"
        tracing.context == null -> "Scoring the words around “${tracing.word.text}”…"
        file != null && lines == null && !tracing.searchRequested -> "“${tracing.context.query}” · Right-click a scored word and choose Search to find its lines in $file."
        file != null && lines == null -> "“${tracing.context.query}” · Searching the lines of $file…"
        lines != null && lines.blocks.isEmpty() -> "“${lines.query}” · $file has no lines to search."
        lines != null && lines.best == null -> "“${lines.query}” · None of the ${lines.blocks.size} blocks of $file is related."
        lines != null -> "“${lines.query}” · Best match at ${lines.label} · ${(lines.best!!.probability * 100).roundToInt()}%, " +
            "opened and highlighted. Right-click a scored word and choose Search to open it again."
        trace == null && !tracing.searchRequested -> "“${tracing.context.query}” · Right-click a scored word and choose Search to search related summaries."
        trace == null -> "“${tracing.context.query}” · Searching the summaries in $levels…"
        trace.candidates == 0 -> "“${trace.query}” · There are no summaries below this one to search."
        trace.related.isEmpty() -> "“${trace.query}” · None of the ${trace.candidates} summaries in $levels is related."
        else -> "“${trace.query}” · ${trace.related.size} of ${trace.candidates} summaries in $levels are related, $where" +
            if (trace.related.any { it.next != null }) " Click a green word to follow the search further down." else ""
    }
}

internal fun matchLabel(match: SummaryMatch, trace: SummaryTrace): String {
    val percent = (match.probability * 100).roundToInt()
    return if (match.identity == trace.best?.identity) "● Best match for “${trace.query}” · $percent%" else "● Related to “${trace.query}” · $percent%"
}

/**
 * Moving the pointer over a scored word of [summary] immediately shows its cached context. A left click on a scored word
 * does not search, so the reader can place the caret or select words there; searching starts only from the context menu:
 * right-clicking a word offers searches for that word, its whole scored query, and selected text. The traced word is marked
 * strongly and its context, the words the query keeps, lightly. A click beside the words calls [select]. With
 * [searchSelection], right-clicking selected text offers to search for it, scored or not. With [searchTab], both menus
 * also offer Search tab, which searches in a new tab of its own. [score] offers to score only this summary's missing words.
 * With [follow], a summary the current search found related marks the words that continue it, and clicking one, or
 * choosing Search on it, searches further down from them.
 * [click] overrides left clicks for views with a summary reader; [entryMenu] adds navigation to the word menu.
 * Hovering a word, or selecting text, echoes it in the summaries shown below this one and in its folders up to the root,
 * so a file's words can be found in the summaries that condense them: a scored word echoes its whole
 * context, any other meaningful word just itself, and a selection its meaningful words.
 */
internal fun installWordTracing(
    area: JTextComponent, identity: String, summary: String, state: SummaryTraceState,
    trace: (String, Int, Boolean) -> Unit, searchSelection: ((String, Int, Int) -> Unit)? = null,
    searchTab: ((SummaryContext) -> Unit)? = null, score: ((String) -> Unit)? = null,
    follow: ((SummaryContext) -> Unit)? = null,
    click: ((MouseEvent) -> Unit)? = null, entryMenu: (() -> JPopupMenu)? = null,
    select: () -> Unit = {},
) {
    val words = SummaryWord.split(summary)
    fun tracing() = state.tracing?.takeIf { it.identity == identity && it.summary == summary }
    fun trail() = follow?.let { state.resultTrace?.related?.firstOrNull { it.identity == identity }?.next?.takeIf { it.summary == summary } }
    val highlights = mutableListOf<Any>()
    var painted: SummaryTracing? = null
    var paintedTrail: SummaryContext? = null
    var paintedEcho: SummaryEcho? = null
    fun updateHighlights() {
        val current = tracing()
        val next = trail()
        val echo = state.echo?.takeIf { it.reaches(identity) }
        if (current === painted && next === paintedTrail && echo === paintedEcho) return
        painted = current
        paintedTrail = next
        paintedEcho = echo
        highlights.forEach(area.highlighter::removeHighlight)
        highlights.clear()
        echo?.let { words.filter { it.text.lowercase() in echo.words } }
            ?.forEach { highlights += area.highlighter.addHighlight(it.start, it.end, DefaultHighlighter.DefaultHighlightPainter(TraceStyle.ECHO_WORD)) }
        next?.context?.forEach { highlights += area.highlighter.addHighlight(it.start, it.end, DefaultHighlighter.DefaultHighlightPainter(TraceStyle.TRAIL_WORD)) }
        current?.let {
            val context = current.context?.context.orEmpty().filter { it.index != current.word.index }
            context.forEach { highlights += area.highlighter.addHighlight(it.start, it.end, DefaultHighlighter.DefaultHighlightPainter(TraceStyle.CONTEXT_WORD)) }
            highlights += area.highlighter.addHighlight(current.word.start, current.word.end, DefaultHighlighter.DefaultHighlightPainter(TraceStyle.TRACED_WORD))
        }
    }
    ClientProperty.put(area, WORD_TRACING_UPDATE, ::updateHighlights)
    updateHighlights()
    /** The word of [candidates] under [point]: the nearest offset is not enough, the pointer must be on the word itself. */
    fun hit(point: Point, candidates: (SummaryWord) -> Boolean): SummaryWord? {
        val offset = area.viewToModel2D(point)
        val word = words.firstOrNull { offset in it.start..it.end }?.takeIf(candidates) ?: return null
        val start = area.modelToView2D(word.start) ?: return null
        val end = area.modelToView2D(word.end) ?: return null
        val onLine = point.y >= start.y && point.y <= end.maxY
        val inWord = if (start.y == end.y) point.x >= start.x && point.x <= end.x else point.y <= start.maxY && point.x >= start.x || point.y >= end.y && point.x <= end.x
        return word.takeIf { onLine && inWord }
    }
    fun wordAt(point: Point): SummaryWord? = hit(point) { it.meaningful && state.cached(identity, summary, it.index) != null }
    /** The search to follow when [point] is on one of the words that continue it. */
    fun trailAt(point: Point): SummaryContext? = trail()?.takeIf { next -> hit(point) { word -> next.context.any { it.index == word.index } } != null }
    fun highlighted(word: SummaryWord) = tracing()?.let { current ->
        word.index == current.word.index || current.context?.context?.any { it.index == word.index } == true
    } == true
    fun JPopupMenu.addSearchTab(context: SummaryContext?) {
        if (searchTab == null) return
        add(JMenuItem("Search tab").apply {
            name = "search-tab"
            isEnabled = context != null
            toolTipText = context?.let { "Search for “${it.query}” in a new tab that shows only the related summaries" }
                ?: "Search in a new tab that shows only the related summaries"
            addActionListener { context?.let(searchTab) }
        })
    }
    fun contextMenu(point: Point?): JPopupMenu? {
        val menu = entryMenu?.invoke() ?: summaryEntryMenu(area)?.invoke() ?: JPopupMenu()
        if (menu.componentCount > 0) menu.addSeparator()
        if (score != null) menu.add(JMenuItem("Score").apply {
            name = "score-summary"
            isEnabled = !state.scoring && words.any { it.meaningful && state.cached(identity, summary, it.index) == null }
            toolTipText = "Precalculate word contexts for this summary only. Uses the Decisions API."
            addActionListener { score(identity) }
        })
        val start = area.selectionStart
        val end = area.selectionEnd
        // Resolve the pointed word independently of selected text so all applicable scopes remain available.
        val word = if (point != null) hit(point) { it.meaningful } else
            (if (start == end) tracing()?.word?.let { words.getOrNull(it.index) } else null)
                ?: words.firstOrNull { (if (start != end) start else area.caretPosition) in it.start until it.end }
                    ?.takeIf { it.meaningful }
        // A highlighted context word belongs to the active scored query. Manual selections are not scored queries.
        val scored = tracing()?.takeIf { current ->
            word != null && highlighted(word) && current.context == state.cached(identity, summary, current.word.index)
        }?.context ?: word?.let { state.cached(identity, summary, it.index) }
        val selected = selectionContext(identity, summary, start, end).takeIf { start != end }
        val next = point?.let(::trailAt)
        if (searchSelection != null && word != null) menu.add(JMenuItem("Search current word").apply {
            name = "search-word"
            toolTipText = "Search only for “${word.text}”"
            addActionListener { searchSelection(identity, word.start, word.end) }
        })
        if (scored != null) menu.add(JMenuItem("Search whole scored query").apply {
            name = "search-scored-query"
            toolTipText = "Search for “${scored.query}”"
            addActionListener { trace(identity, scored.word.index, true) }
        })
        if (searchSelection != null && start != end) menu.add(JMenuItem("Search selected query").apply {
            name = "search-selection"
            isEnabled = selected != null
            toolTipText = selected?.let { "Search for “${it.query}”" } ?: "Select text containing a meaningful word"
            addActionListener { searchSelection(identity, start, end) }
        })
        if (follow != null && next != null) menu.add(JMenuItem("Search continuation query").apply {
            name = "search-trail"
            toolTipText = "Follow the search further down: search for “${next.query}”"
            addActionListener { follow(next) }
        })
        val tabContext = if (start != end) selected else next ?: scored
            ?: word?.let { selectionContext(identity, summary, it.start, it.end) }
        if (word != null || start != end || next != null) menu.addSearchTab(tabContext)
        return menu.takeIf { it.componentCount > 0 }
    }
    val tooltip = area.toolTipText
    val pointer = object : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            // A word that continues the search stays put on hover, so reaching it never replaces the search it belongs to.
            val next = trailAt(e.point)
            area.toolTipText = next?.let { "Click to follow the search further down: “${it.query}”" } ?: tooltip
            if (next != null) { area.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); return }
            val word = wordAt(e.point)
            area.cursor = Cursor.getPredefinedCursor(if (word != null) Cursor.HAND_CURSOR else Cursor.TEXT_CURSOR)
            if (word != null && !highlighted(word)) trace(identity, word.index, false)
            // A manual selection keeps its echo until it is cleared.
            if (area.selectionStart != area.selectionEnd && state.echo?.let { it.identity == identity && it.selection } == true) return
            if (word != null) {
                val context = tracing()?.takeIf { highlighted(word) }?.context ?: state.cached(identity, summary, word.index)
                state.echo(identity, context?.context.orEmpty() + word)
            } else hit(e.point) { it.meaningful }?.let { state.echo(identity, listOf(it)) }
        }

        override fun mouseClicked(e: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(e)) return
            if (click != null) { click(e); return }
            if (e.clickCount != 1) return
            val next = trailAt(e.point)
            if (follow != null && next != null && area.selectionStart == area.selectionEnd) { follow(next); return }
            // A scored word is left alone so the click can place the caret or start a manual selection instead of searching.
            if (wordAt(e.point) == null) select()
        }

        override fun mousePressed(e: MouseEvent) = popup(e)
        override fun mouseReleased(e: MouseEvent) = popup(e)

        private fun popup(e: MouseEvent) {
            if (!e.isPopupTrigger || !area.isShowing) return
            val menu = contextMenu(e.point) ?: return
            menu.show(area, e.x, e.y)
            e.consume()
        }
    }
    ClientProperty.put(area, SELECTION_MENU) { point: Point? -> contextMenu(point) }
    area.addCaretListener { event ->
        val start = minOf(event.dot, event.mark)
        val end = maxOf(event.dot, event.mark)
        if (start == end) state.clearEcho(identity)
        else state.echo(identity, words.filter { it.end > start && it.start < end }, selection = true)
    }
    area.addMouseListener(pointer)
    area.addMouseMotionListener(pointer)
}

private val SELECTION_MENU = Key.create<(Point?) -> JPopupMenu?>("insight.summary.selectionMenu")
private val SUMMARY_ENTRY_MENU = Key.create<() -> JPopupMenu>("insight.summary.entryMenu")

private fun summaryEntryMenu(area: JComponent): (() -> JPopupMenu)? = ClientProperty.get(area, SUMMARY_ENTRY_MENU)

/** Gives [component] and everything in it the entry's [menu]; a traced summary puts it above its own word actions. */
internal fun installSummaryEntryMenu(component: JComponent, menu: () -> JPopupMenu) {
    fun install(part: java.awt.Component) {
        if (part is JComponent) {
            ClientProperty.put(part, SUMMARY_ENTRY_MENU, menu)
            if (part !is JTextComponent || ClientProperty.get(part, SELECTION_MENU) == null)
                part.componentPopupMenu = menu()
        }
        if (part is java.awt.Container) part.components.forEach(::install)
    }
    install(component)
}

/**
 * The context menu for a summary: search the current word, its whole scored query, selected text, or a continuation query,
 * and Score when supported. Null when no actions are available.
 */
internal fun selectionMenu(area: JTextComponent): JPopupMenu? = selectionMenu(area, null)

/** The context menu for a summary at [point]; see [selectionMenu]. */
internal fun selectionMenu(area: JTextComponent, point: Point?): JPopupMenu? {
    return ClientProperty.get(area, SELECTION_MENU)?.invoke(point)
}

private val WORD_TRACING_UPDATE = Key.create<() -> Unit>("insight.summary.wordTracingUpdate")

/** Repaint cached word contexts without replacing text components or their scroll panes. */
internal fun updateWordTracing(area: JTextComponent) {
    ClientProperty.get(area, WORD_TRACING_UPDATE)?.invoke()
}

private val TRACE_DECORATION_RESET = Key.create<() -> Unit>("insight.summary.traceDecorationReset")

/** Search decorations can be cleared by a new hover without changing the row's geometry. */
internal fun installTraceDecorationReset(component: JComponent, reset: () -> Unit) {
    ClientProperty.put(component, TRACE_DECORATION_RESET, reset)
}

internal fun resetTraceDecoration(component: JComponent) {
    val reset = ClientProperty.get(component, TRACE_DECORATION_RESET) ?: return
    ClientProperty.put(component, TRACE_DECORATION_RESET, null)
    reset()
}

/**
 * Scoring publishes [context] first. Choosing Search from a scored word's context menu requests the separate search and
 * selects its best match; for the summary of a [file] or part, it searches the file's [lines] and opens the best ones.
 */
internal data class SummaryTracing(
    val identity: String,
    val summary: String,
    val word: SummaryWord,
    val context: SummaryContext? = null,
    val trace: SummaryTrace? = null,
    val file: String? = null,
    val lines: LineTrace? = null,
    val error: String? = null,
    val searchRequested: Boolean = false,
    val token: Any = Any(),
)

/** Runs each stage off the EDT and reports back on it; newer words cancel the returned job. */
internal interface SummaryTraceLauncher {
    fun prescore(summaries: ChangeSummaries, words: List<Pair<Int, Int>>,
                 scored: (SummaryContext) -> Unit, done: (Result<Unit>) -> Unit): Job
    fun search(summaries: ChangeSummaries, id: Int, context: SummaryContext, done: (Result<SummaryTrace>) -> Unit): Job
    /** Searches the lines of a file or part, whose summary has no summaries below it. */
    fun searchLines(summaries: ChangeSummaries, id: Int, context: SummaryContext, done: (Result<LineTrace>) -> Unit): Job
}

/**
 * Prescoring and searching for one card's [content], kept in [state]; [update] repaints contexts and progress in place,
 * [refresh] rebuilds changed search results. Hover reads saved contexts synchronously; choosing Search from a scored
 * word's context menu searches the levels below, or the lines of a file, which [openLines] opens.
 * Late results of a replaced run are ignored.
 */
internal class SummaryTraceSession(
    private val launcher: SummaryTraceLauncher?,
    private val state: SummaryTraceState,
    private val content: () -> ChangeSummaries?,
    private val openLines: (SummaryNode, LineTrace) -> Unit = { _, _ -> },
    private val update: (() -> Unit)? = null,
    private val refresh: () -> Unit,
) {
    private var traceJob: Job? = null
    private var scoreJob: Job? = null
    private var scoreToken: Any? = null
    private var scoringSummaries: ChangeSummaries? = null

    val available: Boolean get() = launcher != null

    init {
        state.echoTree = { content()?.tree }
        state.echoChanged = ::updatePresentation
    }

    fun prescore(identity: String? = null) {
        val launcher = launcher ?: return
        val content = content() ?: return
        val pending = if (identity == null) state.pending(content) else state.pending(content, identity)
        if (state.scoring || pending.isEmpty()) return
        val token = Any()
        scoreToken = token
        scoringSummaries = content
        state.scoring = true
        state.scoringError = null
        updatePresentation()
        scoreJob = launcher.prescore(content, pending, { context ->
            if (scoreToken === token) {
                state.scored[context.identity to context.word.index] = context
                updatePresentation()
            }
        }) { result ->
            if (scoreToken === token) {
                state.scoring = false
                state.scoringError = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                scoringSummaries = null
                scoreToken = null
                updatePresentation()
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
        val sameQuery = current != null && current.word.index == word && current.context == state.cached(identity, text, word)
        if (current != null && highlighted && (!search || sameQuery) && (current.error == null || current.context != null)) {
            if (!search) return
            if (current.trace != null) state.showTrace(content.tree, current.trace)
            else if (current.lines != null) { if (current.lines.best != null) openLines(node, current.lines) }
            else if (!current.searchRequested || current.error != null) {
                state.tracing = current.copy(searchRequested = true, error = null)
                if (current.context != null) search()
            }
            if (current.trace != null) refresh() else updatePresentation()
            return
        }
        traceJob?.cancel()
        val context = state.cached(identity, text, word) ?: return
        val file = node.path.takeIf { node.children.isEmpty() && node.file != null }
        state.tracing = SummaryTracing(identity, text, words[word], context = context, file = file, searchRequested = search)
        updatePresentation()
        if (search) search()
    }

    /** Searches for the words selected in [identity]'s summary, as Search on a scored word does, without needing Score. */
    fun searchSelection(identity: String, start: Int, end: Int) {
        val text = content()?.tree?.nodes?.firstOrNull { it.identity == identity }?.let { content()?.text(it.id) } ?: return
        searchContext(selectionContext(identity, text, start, end) ?: return)
    }

    /** Searches a scored word's or a selection's [context], as long as its summary is unchanged. */
    fun searchContext(context: SummaryContext) {
        if (launcher == null) return
        val content = content() ?: return
        val node = content.tree.nodes.firstOrNull { it.identity == context.identity } ?: return
        val text = content.text(node.id)?.takeIf { it == context.summary } ?: return
        traceJob?.cancel()
        val file = node.path.takeIf { node.children.isEmpty() && node.file != null }
        state.tracing = SummaryTracing(context.identity, text, context.word, context = context, file = file, searchRequested = true)
        updatePresentation()
        search()
    }

    private fun search() {
        val launcher = launcher ?: return
        val content = content() ?: return
        val current = state.tracing ?: return
        val context = current.context ?: return
        val node = content.tree.nodes.firstOrNull { it.identity == current.identity } ?: return
        if (current.file != null) {
            traceJob = launcher.searchLines(content, node.id, context) { result ->
                val now = state.tracing
                if (now == null || now.token !== current.token) return@searchLines
                result.onSuccess { lines ->
                    state.tracing = now.copy(lines = lines)
                    if (lines.best != null) openLines(node, lines)
                }.onFailure { error -> state.tracing = now.copy(error = error.message ?: error.javaClass.simpleName) }
                updatePresentation()
            }
            return
        }
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
        state.clearTrace()
        updatePresentation()
    }

    private fun updatePresentation() { (update ?: refresh)() }

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
