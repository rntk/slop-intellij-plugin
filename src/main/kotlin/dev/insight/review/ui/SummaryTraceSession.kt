package dev.insight.review.ui

import com.intellij.openapi.util.Key
import com.intellij.ui.ClientProperty
import com.intellij.ui.JBColor
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.text.DefaultHighlighter
import javax.swing.text.JTextComponent

/**
 * The current trace and hover echo of one card's summaries. Every word context comes from the summaries' own citations
 * ([links]), so hovering and searching never need a request. [traceRoot] is false where the root summary is not shown.
 */
internal abstract class SummaryTraceState(private val traceRoot: Boolean = true) {
    var tracing: SummaryTracing? = null
    open val resultTrace: SummaryTrace? get() = tracing?.trace
    /** A node to scroll into view once the card is laid out again. */
    var reveal: String? = null

    /** The segments linked to the last hover or selection, highlighted in the summaries below and above it. */
    var echo: SummaryEcho? = null
        private set
    /** Set by the session that owns this state: the citations of the current summaries, and how to repaint in place. */
    var links: () -> SummaryLinks? = { null }
    var echoChanged: () -> Unit = {}
    /** Set by the owning session: opens, selects and scrolls to the file or folder [String] that a context cites. */
    var goTo: (SummaryContext, SummaryKind, String) -> Unit = { _, _, _ -> }

    /**
     * Echoes the segments of [identity]'s summary that hold [words]: every segment they cite below, at any depth, and every
     * segment above that cites them. Repaints only when that changes.
     */
    fun echo(identity: String, words: Collection<SummaryWord>, selection: Boolean = false) {
        val links = links() ?: return clearEcho()
        val node = links.node(identity) ?: return clearEcho()
        val segments = links.segmentsOf(node.id, words.filter { it.meaningful })
        if (segments.isEmpty()) return clearEcho()
        val current = echo
        if (current != null && current.identity == identity && current.segments == segments && current.selection == selection) return
        val refs = segments.map { SegmentRef(node.id, it) }
        val ranges = HashMap<String, MutableList<IntRange>>()
        for (ref in links.descendants(refs) + links.ancestors(refs)) {
            val range = links.summaries.summary(ref.id)?.ranges?.getOrNull(ref.segment) ?: continue
            ranges.getOrPut(links.summaries.tree[ref.id].identity) { mutableListOf() } += range
        }
        echo = SummaryEcho(identity, segments, ranges, selection)
        echoChanged()
    }

    /** Clears the echo; with [identity], only a selection's echo from that summary. */
    fun clearEcho(identity: String? = null) {
        val current = echo ?: return
        if (identity != null && (current.identity != identity || !current.selection)) return
        echo = null
        echoChanged()
    }

    /** The context of [word] in [identity]'s [summary]: the words of the cited segment it lies in, if any. */
    fun cached(identity: String, summary: String, word: Int): SummaryContext? {
        val links = links() ?: return null
        val node = links.node(identity)?.takeIf { links.summaries.text(it.id) == summary } ?: return null
        return links.context(node, word)
    }

    /** Whether [node]'s summary takes part in tracing: every summary, except the root where it is not shown. */
    fun traced(node: SummaryNode): Boolean = traceRoot || node.kind != SummaryKind.ROOT

    /** A trace stays only while the traced summary is unchanged; matches whose node is gone are dropped. */
    fun retain(summaries: ReviewSummaries) {
        val nodes = summaries.tree.nodes.associateBy { it.identity }
        val current = tracing ?: return
        val node = nodes[current.identity]
        tracing = if (node == null || summaries.text(node.id) != current.summary) null
            else current.copy(trace = current.trace?.let { trace -> trace.copy(matches = trace.matches.filter { it.identity in nodes }) })
    }

    /** Opens the levels between the traced summary and its related ones, so every match is visible, and selects the best. */
    abstract fun showTrace(tree: ReviewSummaryTree, trace: SummaryTrace)

    /** Clears the current word context and result decorations. */
    open fun clearTrace() { tracing = null; echo = null }
}

/**
 * The [segments] of [identity]'s summary under the pointer or selection, and the character [ranges], by node identity, of
 * every segment linked to them in the summaries below and above; [selection] when selected by hand.
 */
internal data class SummaryEcho(
    val identity: String, val segments: List<Int>, val ranges: Map<String, List<IntRange>>, val selection: Boolean,
)
/**
 * The nodes a trace marks: the traced one, every node on the way down to a related one, and the related ones. [open] must be
 * expanded for every match to show; [fold] are related branches with no related node beneath, which a hierarchy folds so
 * their own summary shows beside them.
 */
internal class TracePath(val traced: String, val path: Set<String>, val open: Set<String>, val fold: Set<String>)

internal fun tracePath(tree: ReviewSummaryTree, trace: SummaryTrace): TracePath? {
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
internal fun traceMarks(tree: ReviewSummaryTree, trace: SummaryTrace?): Map<String, TraceMark> {
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
    /** The words of segments linked to a hovered or selected part, in the summaries below and above it. */
    val ECHO_WORD = JBColor(Color(0xE6DAFF), Color(0x463A66))
    /** The words of a match that continue the search below it, clicked to follow it. */
    val TRAIL_WORD = JBColor(Color(0xCDEFC4), Color(0x2F5A33))
    /** The outline of the traced card and every card on the way to a match. */
    val PATH = JBColor(Color(0xE0A800), Color(0xC9A227))
}


/** What hovering and searching do, for the status area; [selection] mentions searching selected text. */
internal fun tracingHint(selection: Boolean = false): String =
    "Hover over a summary to highlight what it condenses below and what condenses it above. " +
        "Right-click a highlighted part and choose Search to open the summaries or lines it cites" +
        if (selection) ", or select text and right-click Search." else "."

/** The query and result of a trace; [where] says where its matches are shown. */
internal fun traceStatus(tracing: SummaryTracing, where: String = "highlighted below. Right-click and choose Search to jump to the best."): String {
    val trace = tracing.trace
    val levels = "the ${SummaryLinks.LEVELS} levels below"
    val lines = tracing.lines
    val file = tracing.file?.substringAfterLast('/')
    val query = tracing.context.query
    val target = tracing.target
    return when {
        trace != null && target != null -> {
            val count = trace.related.size
            val noun = if (target == SummaryKind.FILE) "file" else "folder"
            "“${trace.query}” · cites $count $noun${if (count == 1) "" else "s"}, opened and highlighted; " +
                "the chosen one is selected."
        }
        file != null && lines == null -> "“$query” · Right-click and choose Search to open the lines it cites in $file."
        lines != null && lines.blocks.isEmpty() -> "“${lines.query}” · cites no lines of $file."
        lines != null -> "“${lines.query}” · cites ${lines.label}, opened and highlighted. Right-click and choose Search to open it again."
        trace == null -> "“$query” · Right-click and choose Search to show the summaries it cites."
        trace.candidates == 0 -> "“${trace.query}” · There are no summaries below this one."
        trace.related.isEmpty() -> "“${trace.query}” · cites none of the ${trace.candidates} summaries in $levels."
        else -> "“${trace.query}” · cites ${trace.related.size} of ${trace.candidates} summaries in $levels, $where" +
            if (trace.related.any { it.next != null }) " Click a green word to follow the citations further down." else ""
    }
}

internal fun matchLabel(match: SummaryMatch, trace: SummaryTrace): String =
    (if (match.identity == trace.best?.identity) "● Best match for “${trace.query}”" else "● Cited by “${trace.query}”") +
        " · " + levelsBelow(match)

/** "1 level below", "2 levels below". */
internal fun levelsBelow(match: SummaryMatch): String = "${match.levelsBelow} level${if (match.levelsBelow == 1) "" else "s"} below"

/**
 * Moving the pointer over a word of [summary] immediately shows its context: the words of the cited segment it lies in. A
 * left click on such a word does not search, so the reader can place the caret or select words there; searching starts
 * only from the context menu: right-clicking a word offers searches for its whole segment, that word alone, and selected
 * text. The traced word is marked strongly and its context lightly. A click beside the words calls [select]. With
 * [searchSelection], right-clicking selected text offers to search for it. With [searchTab], both menus also offer Search
 * tab, which searches in a new tab of its own. With [follow], a summary the current search found related marks the words
 * that continue it, and clicking one, or choosing Search on it, searches further down from them.
 * [click] overrides left clicks for views with a summary reader; [entryMenu] adds navigation to the word menu.
 * Hovering a word, or selecting text, echoes its segments: the segments they cite in the summaries below, at any depth,
 * and the segments citing them in the summaries above, up to the root.
 */
internal fun installWordTracing(
    area: JTextComponent, identity: String, summary: String, state: SummaryTraceState,
    trace: (String, Int, Boolean) -> Unit, searchSelection: ((String, Int, Int) -> Unit)? = null,
    searchTab: ((SummaryContext) -> Unit)? = null,
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
    var paintedEcho: List<IntRange>? = null
    fun updateHighlights() {
        val current = tracing()
        val next = trail()
        val echo = state.echo?.ranges?.get(identity)
        if (current === painted && next === paintedTrail && echo == paintedEcho) return
        painted = current
        paintedTrail = next
        paintedEcho = echo
        highlights.forEach(area.highlighter::removeHighlight)
        highlights.clear()
        fun mark(start: Int, end: Int, color: Color) {
            if (start < end && end <= area.document.length)
                highlights += area.highlighter.addHighlight(start, end, DefaultHighlighter.DefaultHighlightPainter(color))
        }
        // Only the words of a segment are marked, so its spaces and punctuation stay plain.
        echo?.let { ranges -> words.filter { word -> ranges.any { word.start <= it.last && word.end > it.first } } }
            ?.forEach { mark(it.start, it.end, TraceStyle.ECHO_WORD) }
        next?.context?.forEach { mark(it.start, it.end, TraceStyle.TRAIL_WORD) }
        current?.let {
            current.context.context.filter { it.index != current.word.index }.forEach { mark(it.start, it.end, TraceStyle.CONTEXT_WORD) }
            mark(current.word.start, current.word.end, TraceStyle.TRACED_WORD)
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
        val context = state.cached(identity, summary, word.index) ?: return@let false
        current.context.context == context.context && current.context.query == context.query
    } == true
    fun JPopupMenu.addSearchTab(context: SummaryContext?) {
        if (searchTab == null) return
        add(JMenuItem("Search tab").apply {
            name = "search-tab"
            isEnabled = context != null
            toolTipText = context?.let { "Show what “${it.query}” cites in a new tab that hides everything else" }
                ?: "Show what a part of this summary cites in a new tab"
            addActionListener { context?.let(searchTab) }
        })
    }
    /** To file and To directory: one item for a single cited file or folder, else a submenu naming each. */
    fun JPopupMenu.addGoTo(context: SummaryContext) {
        val links = state.links() ?: return
        val node = links.node(identity)?.takeIf { links.summaries.text(it.id) == summary } ?: return
        for ((kind, key, noun) in listOf(Triple(SummaryKind.FILE, "file", "file"), Triple(SummaryKind.DIRECTORY, "directory", "folder"))) {
            val targets = links.targets(node.id, context, kind)
            val label = "To $key"
            fun item(text: String, target: SummaryNode) = JMenuItem(text).apply {
                toolTipText = "Open, highlight and scroll to the $noun ${target.path} that “${context.query}” cites"
                addActionListener { state.goTo(context, kind, target.identity) }
            }
            when (targets.size) {
                0 -> continue
                1 -> add(item(label, targets.single()).apply { name = "to-$key" })
                else -> add(JMenu(label).apply {
                    name = "to-$key"
                    toolTipText = "The ${targets.size} ${noun}s “${context.query}” cites"
                    targets.take(GO_TO_LIMIT).forEachIndexed { index, target ->
                        add(item(target.path.ifEmpty { target.label }, target).apply { name = "to-$key:$index" })
                    }
                    if (targets.size > GO_TO_LIMIT) add(JMenuItem("${targets.size - GO_TO_LIMIT} more…").apply { isEnabled = false })
                })
            }
        }
    }
    fun contextMenu(point: Point?): JPopupMenu? {
        val menu = entryMenu?.invoke() ?: summaryEntryMenu(area)?.invoke() ?: JPopupMenu()
        if (menu.componentCount > 0) menu.addSeparator()
        val start = area.selectionStart
        val end = area.selectionEnd
        // Resolve the pointed word independently of selected text so all applicable scopes remain available.
        val word = if (point != null) hit(point) { it.meaningful } else
            (if (start == end) tracing()?.word?.let { words.getOrNull(it.index) } else null)
                ?: words.firstOrNull { (if (start != end) start else area.caretPosition) in it.start until it.end }
                    ?.takeIf { it.meaningful }
        val linked = word?.let { state.cached(identity, summary, it.index) }
        val selected = selectionContext(identity, summary, start, end).takeIf { start != end }
        val next = point?.let(::trailAt)
        if (linked != null) menu.add(JMenuItem("Search").apply {
            name = "search-segment"
            toolTipText = "Show what “${linked.query}” cites"
            addActionListener { trace(identity, linked.word.index, true) }
        })
        if (searchSelection != null && start != end) menu.add(JMenuItem("Search selected text").apply {
            name = "search-selection"
            isEnabled = selected != null
            toolTipText = selected?.let { "Show what the parts holding “${it.query}” cite" } ?: "Select text containing a meaningful word"
            addActionListener { searchSelection(identity, start, end) }
        })
        if (follow != null && next != null) menu.add(JMenuItem("Search continuation").apply {
            name = "search-trail"
            toolTipText = "Follow the citations further down: “${next.query}”"
            addActionListener { follow(next) }
        })
        val tabContext = if (start != end) selected else next ?: linked
        if (linked != null || start != end || next != null) menu.addSearchTab(tabContext)
        (if (start != end) selected else linked)?.let { menu.addGoTo(it) }
        return menu.takeIf { it.componentCount > 0 }
    }
    val tooltip = area.toolTipText
    val pointer = object : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            // A word that continues the search stays put on hover, so reaching it never replaces the search it belongs to.
            val next = trailAt(e.point)
            area.toolTipText = next?.let { "Click to follow the citations further down: “${it.query}”" } ?: tooltip
            if (next != null) { area.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR); return }
            val word = wordAt(e.point)
            area.cursor = Cursor.getPredefinedCursor(if (word != null) Cursor.HAND_CURSOR else Cursor.TEXT_CURSOR)
            if (word != null && !highlighted(word)) trace(identity, word.index, false)
            // A manual selection keeps its echo until it is cleared.
            if (area.selectionStart != area.selectionEnd && state.echo?.let { it.identity == identity && it.selection } == true) return
            if (word != null) state.echo(identity, listOf(word))
        }

        override fun mouseClicked(e: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(e)) return
            if (click != null) { click(e); return }
            if (e.clickCount != 1) return
            val next = trailAt(e.point)
            if (follow != null && next != null && area.selectionStart == area.selectionEnd) { follow(next); return }
            // A linked word is left alone so the click can place the caret or start a manual selection instead of searching.
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

/** Files or folders a To file or To directory submenu names; a menu cannot scroll. */
private const val GO_TO_LIMIT = 25

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

/** The context menu for a summary: search the pointed segment, selected text, or a continuation. Null when no actions are available. */
internal fun selectionMenu(area: JTextComponent): JPopupMenu? = selectionMenu(area, null)

/** The context menu for a summary at [point]; see [selectionMenu]. */
internal fun selectionMenu(area: JTextComponent, point: Point?): JPopupMenu? {
    return ClientProperty.get(area, SELECTION_MENU)?.invoke(point)
}

private val WORD_TRACING_UPDATE = Key.create<() -> Unit>("insight.summary.wordTracingUpdate")

/** Repaint word contexts and echoes without replacing text components or their scroll panes. */
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
 * A word's [context] and, once searched, what it cites: the summaries below ([trace]), or for the summary of a [file] or
 * part, its [lines].
 */
internal data class SummaryTracing(
    val identity: String,
    val summary: String,
    val word: SummaryWord,
    val context: SummaryContext,
    val trace: SummaryTrace? = null,
    val file: String? = null,
    val lines: LineTrace? = null,
    /** Set when [trace] holds the files or folders the context cites, from To file or To directory. */
    val target: SummaryKind? = null,
    val token: Any = Any(),
)

/**
 * Tracing for one card's [content], kept in [state]; [update] repaints contexts in place, [refresh] rebuilds changed search
 * results. Everything is read from the summaries' citations on the EDT: choosing Search from a word's context menu shows
 * the summaries below that its segment cites, or the lines of a file, which [openLines] opens.
 */
internal class SummaryTraceSession(
    private val state: SummaryTraceState,
    private val content: () -> ReviewSummaries?,
    private val openLines: (SummaryNode, LineTrace) -> Unit = { _, _ -> },
    private val update: (() -> Unit)? = null,
    private val refresh: () -> Unit,
) {
    private var links: SummaryLinks? = null

    init {
        state.links = ::currentLinks
        state.echoChanged = ::updatePresentation
        state.goTo = ::goTo
    }

    /**
     * Marks every [kind] of node that [context] cites and opens the levels down to them, selecting and scrolling to the one
     * with [target] identity, as long as the context's summary is unchanged.
     */
    fun goTo(context: SummaryContext, kind: SummaryKind, target: String) {
        val links = currentLinks() ?: return
        val node = links.node(context.identity) ?: return
        val text = links.summaries.text(node.id)?.takeIf { it == context.summary } ?: return
        val targets = links.targets(node.id, context, kind)
        val best = targets.firstOrNull { it.identity == target } ?: return
        val trace = links.reach(node.id, context, targets, best)
        state.tracing = SummaryTracing(context.identity, text, context.word, context, trace = trace, target = kind)
        state.showTrace(links.summaries.tree, trace)
        refresh()
    }

    /** The citations of the current summaries, built once per summaries value. */
    private fun currentLinks(): SummaryLinks? {
        val summaries = content() ?: return null
        return links?.takeIf { it.summaries === summaries } ?: SummaryLinks(summaries).also { links = it }
    }

    fun trace(identity: String, word: Int, search: Boolean) {
        val links = currentLinks() ?: return
        val node = links.node(identity) ?: return
        val text = links.summaries.text(node.id) ?: return
        val current = state.tracing?.takeIf { it.identity == identity && it.summary == text }
        val context = links.context(node, word) ?: return
        if (current != null && current.word.index == word && current.context == context && !search) return
        val file = node.path.takeIf { node.children.isEmpty() && node.file != null }
        state.tracing = if (current != null && current.word.index == word && current.context == context) current
            else SummaryTracing(identity, text, context.word, context, file = file)
        if (search) search() else updatePresentation()
    }

    /** Searches what the segments holding the words selected in [identity]'s summary cite. */
    fun searchSelection(identity: String, start: Int, end: Int) {
        val links = currentLinks() ?: return
        val text = links.node(identity)?.let { links.summaries.text(it.id) } ?: return
        searchContext(selectionContext(identity, text, start, end) ?: return)
    }

    /** Searches a word's or a selection's [context], as long as its summary is unchanged. */
    fun searchContext(context: SummaryContext) {
        val links = currentLinks() ?: return
        val node = links.node(context.identity) ?: return
        val text = links.summaries.text(node.id)?.takeIf { it == context.summary } ?: return
        val file = node.path.takeIf { node.children.isEmpty() && node.file != null }
        state.tracing = SummaryTracing(context.identity, text, context.word, context, file = file)
        search()
    }

    private fun search() {
        val links = currentLinks() ?: return
        val current = state.tracing ?: return
        val node = links.node(current.identity) ?: return
        if (current.file != null) {
            val lines = links.searchLines(node.id, current.context)
            state.tracing = current.copy(lines = lines)
            if (lines.best != null) openLines(node, lines)
            updatePresentation()
            return
        }
        val trace = links.search(node.id, current.context)
        state.tracing = current.copy(trace = trace)
        state.showTrace(links.summaries.tree, trace)
        refresh()
    }

    /** Summary updates keep a trace while its summary is unchanged. */
    fun retain(content: ReviewSummaries) = state.retain(content)

    /** Drops the current trace and its marks. */
    fun clear() {
        state.clearTrace()
        updatePresentation()
    }

    private fun updatePresentation() { (update ?: refresh)() }

    fun stop() { state.tracing = null }
}
