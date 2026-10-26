package dev.insight.diff

/**
 * A folder, group or the root: the branches that levels and "Files" open. A file with parts is a branch too, but is opened by
 * hand, so reading by level never unfolds every part of every large file.
 */
internal fun isFolderBranch(node: SummaryNode): Boolean =
    node.children.isNotEmpty() && node.kind != SummaryKind.FILE && node.kind != SummaryKind.CHUNK

/**
 * The outline reads the tree top-down as indented summaries; the hierarchy reads the folders left to right; the document reads
 * every file in tree order with its summary and the folder rail, as topics do. Explorer shows one page of siblings at a time.
 * Split keeps a foldable file tree on the left and each entry's optionally wrapped summary beside its row.
 */
internal enum class ChangeSummariesMode { OUTLINE, HIERARCHY, DOCUMENT, EXPLORER, SPLIT }

/** One search of a search tab: the summary searched, its query, and the nodes still shown after it. */
internal data class SearchCrumb(
    val identity: String, val query: String, val shown: Set<String>,
    /** Related nodes and their unsearched descendants, excluding ancestors kept only for navigation. */
    val related: Set<String> = shown,
    val trace: SummaryTrace? = null,
)

/** What the shown nodes were computed for: the summaries, the trimmed search field and the last search. */
private data class SummariesFilter(val summaries: ChangeSummaries, val query: String, val crumb: SearchCrumb?)

/**
 * Expansion and selection survive re-rendering and snapshot changes; they are keyed by node identity and shared by both
 * modes, so folding a folder in the hierarchy collapses it in the outline too. A search tab ([filtering]) hides what its
 * searches found unrelated and highlights the matches; the search field hides what does not match the typed text.
 */
internal class ChangeSummariesViewState(
    val filtering: Boolean = false,
    scored: MutableMap<Pair<String, Int>, SummaryContext> = mutableMapOf(),
) : SummaryTraceState(traceLeaves = true, scored = scored) {
    var mode = ChangeSummariesMode.OUTLINE
    var tagsCloud = false
    var softWrap = false
    val expanded = hashSetOf<String>()
    private val seen = hashSetOf<String>()
    var selected: String? = null
    /** The last applied unfolding depth; nodes that appear later follow it too. */
    private var level: Int? = null
    /** The 1-based level picked for the next Fold or Unfold action. Picking it does not change the tree. */
    var selectedLevel = 1
    private var tree: ChangeSummaryTree? = null
    /** Open containers, from the root to the current page; an empty path shows only the scope summary. */
    private var explorerPath = emptyList<String>()

    fun explorerBreadcrumbs(tree: ChangeSummaryTree): List<SummaryNode> {
        val nodes = tree.nodes.associateBy { it.identity }
        return explorerPath.mapNotNull(nodes::get)
    }

    fun browse(identity: String?, tree: ChangeSummaryTree) {
        val node = tree.nodes.firstOrNull { it.identity == identity }?.let { tree.shown(it.id) }
        explorerPath = node?.let { generateSequence(it) { current -> tree.shownParent(current) }
            .toList().asReversed().map { it.identity } }.orEmpty()
    }

    fun explorerBack() { explorerPath = explorerPath.dropLast(1) }

    fun explorerEntries(summaries: ChangeSummaries): List<SummaryNode> {
        val tree = summaries.tree
        val current = explorerBreadcrumbs(tree).lastOrNull { shows(it, summaries) }
        return (current?.let(tree::shownChildren) ?: listOf(tree.root)).filter { shows(it, summaries) }
    }

    /** A filter that removes the current folder returns to its closest visible ancestor. */
    fun updateExplorer(summaries: ChangeSummaries) {
        explorerPath = explorerBreadcrumbs(summaries.tree).takeWhile { shows(it, summaries) }.map { it.identity }
    }

    /** A search tab's searches, broadest first; each narrows the one before it. */
    val crumbs = mutableListOf<SearchCrumb>()

    /** Search tabs keep the current breadcrumb's results while hovering or returning to an earlier query. */
    override val resultTrace: SummaryTrace? get() = if (filtering) crumbs.lastOrNull()?.trace else super.resultTrace

    override fun clearTrace() {
        super.clearTrace()
        if (filtering && crumbs.isNotEmpty()) crumbs[crumbs.lastIndex] = crumbs.last().copy(trace = null)
    }

    /** Returns to unfiltered summaries without dropping scored contexts or the reader's view settings. */
    fun clearSearch() {
        clearTrace()
        crumbs.clear()
        fuzzy = ""
        selected = null
        explorerPath = emptyList()
        reveal = null
        filterFor = null
        shown = null
        opened = emptySet()
    }

    fun resummaryRequest(summaries: ChangeSummaries): ContextSummaryRequest? {
        val crumb = crumbs.lastOrNull()
        val trace = tracing?.trace
        val query = if (filtering) crumb?.query else trace?.query
        val related = if (filtering) crumb?.related else trace?.let {
            relatedSummaryNodes(summaries.tree, it.related.map { match -> match.identity })
        }
        if (query.isNullOrBlank() || related.isNullOrEmpty()) return null
        val ids = summaries.tree.summarizable.filter { it.identity in related }.mapTo(hashSetOf()) { it.id }
        return ContextSummaryRequest(query, ids).takeIf { ids.isNotEmpty() }
    }

    /** The text of the search field. Folders above a match open while it is typed, unless folded by hand meanwhile. */
    var fuzzy = ""
        set(value) { if (field.trim() != value.trim()) folded.clear(); field = value }
    private val folded = hashSetOf<String>()
    private var filterFor: SummariesFilter? = null
    private var shown: Set<String>? = null
    private var opened = emptySet<String>()

    /** The nodes left by the searches and the search field, every match with the nodes above it; null hides nothing. */
    fun shownNodes(summaries: ChangeSummaries): Set<String>? {
        val query = fuzzy.trim()
        val crumb = crumbs.lastOrNull()
        val key = SummariesFilter(summaries, query, crumb)
        if (filterFor == key) return shown
        filterFor = key
        val searched = crumb?.shown
        opened = emptySet()
        shown = if (query.isEmpty()) searched else {
            val tree = summaries.tree
            val above = hashSetOf<String>()
            val kept = hashSetOf<String>()
            for (node in tree.nodes) {
                if (searched != null && node.identity !in searched || !fuzzyMatches(query, searchableText(node, summaries))) continue
                // A merged ancestor can have its own summary; its match belongs to the displayed folder.
                val entry = tree.shown(node.id)
                if (searched != null && entry.identity !in searched) continue
                kept += entry.identity
                generateSequence(tree.shownParent(entry)) { tree.shownParent(it) }.forEach { above += it.identity }
            }
            opened = above
            kept + above
        }
        return shown
    }

    fun shows(node: SummaryNode, summaries: ChangeSummaries): Boolean = shownNodes(summaries)?.contains(node.identity) ?: true

    /** Whether [node]'s children show: unfolded by hand, or above a match of the search field. */
    fun isOpen(node: SummaryNode, summaries: ChangeSummaries): Boolean {
        shownNodes(summaries)
        return node.identity in expanded || node.identity in opened && node.identity !in folded
    }

    /** Drops the searches after the first [count]; zero shows every summary again. */
    fun backTo(count: Int) { while (crumbs.size > count) crumbs.removeAt(crumbs.lastIndex) }

    /**
     * New nodes open by default in a small tree, only the root in a large one, or down to the picked level; nodes the reader
     * has seen keep their state.
     */
    fun update(tree: ChangeSummaryTree) {
        if (tree === this.tree) return
        this.tree = tree
        val small = tree.nodes.size <= SMALL_TREE
        val level = level
        for (node in tree.nodes) if (seen.add(node.identity) && isFolderBranch(node)) {
            if (if (level != null) tree.shownDepth(node.id) < level else small || node.kind == SummaryKind.ROOT)
                expanded += node.identity
            else if (level != null) folded += node.identity
        }
        if (tree.nodes.none { it.identity == selected }) selected = null
        val identities = tree.nodes.mapTo(hashSetOf()) { it.identity }
        // Refreshes can merge away the current folder or introduce newly visible parents.
        browse(explorerPath.takeWhile { it in identities }.lastOrNull(), tree)
    }

    fun toggle(identity: String) {
        if (identity in opened && identity !in folded) { folded += identity; expanded -= identity; return }
        folded -= identity
        if (!expanded.remove(identity)) expanded += identity
    }
    fun select(identity: String) { selected = if (selected == identity) null else identity }

    /** Level n opens the folders above depth n and folds the rest; [branchLevels] opens every folder down to the files. */
    fun selectLevel(level: Int, tree: ChangeSummaryTree) {
        this.level = level
        for (node in levelBranches(tree)) {
            if (tree.shownDepth(node.id) < level) {
                expanded += node.identity
                folded -= node.identity
            } else {
                expanded -= node.identity
                folded += node.identity
            }
        }
    }

    /** Folds the selected 1-based level and everything deeper, keeping shallower folders as they are. */
    fun foldLevel(level: Int, tree: ChangeSummaryTree) {
        for (node in levelBranches(tree)) if (tree.shownDepth(node.id) >= level - 1) {
            expanded -= node.identity
            folded += node.identity
        }
        this.level = minOf(this.level ?: branchLevels(tree), level - 1)
    }

    /** The level the current folding matches, if any; folding by hand can leave it between levels. */
    fun shownLevel(tree: ChangeSummaryTree): Int? {
        val branches = levelBranches(tree)
        return (0..branchLevels(tree)).firstOrNull { level -> branches.all { (it.identity in expanded) == (tree.shownDepth(it.id) < level) } }
    }

    /** Document mode keeps every file visible and limits the rail to the picked levels. */
    fun railDepth(tree: ChangeSummaryTree): Int =
        minOf(branchLevels(tree), (shownLevel(tree) ?: branchLevels(tree)) + 1)

    /** Documents keep every file visible; outlines and hierarchies hide nodes under folded folders. */
    override fun visibility(tree: ChangeSummaryTree): (SummaryNode) -> Boolean {
        // The filter of the last rendering; scoring follows what is on screen.
        val filter = shown.takeIf { filterFor?.summaries?.tree === tree }
        val explorerEntries = if (mode == ChangeSummariesMode.EXPLORER) {
            val current = explorerBreadcrumbs(tree).lastOrNull { filter == null || it.identity in filter }
            current?.let { tree.shownChildren(it).mapTo(hashSetOf(), SummaryNode::id) } ?: setOf(tree.root.id)
        } else emptySet()
        return { node ->
            !tree.isMerged(node.id) && (filter == null || node.identity in filter) && if (mode == ChangeSummariesMode.EXPLORER) node.id in explorerEntries
            else if (mode == ChangeSummariesMode.DOCUMENT) node.kind == SummaryKind.FILE ||
                isFolderBranch(node) && tree.shownDepth(node.id) < railDepth(tree)
            else generateSequence(node.parent) { tree[it].parent }.filterNot(tree::isMerged).all { tree[it].identity in expanded || tree[it].identity in opened && tree[it].identity !in folded }
        }
    }

    /**
     * The outline only opens the levels down to the matches. The hierarchy shows a branch's summary only while it is folded,
     * so it also folds related branches with no related node beneath them. A search tab instead keeps only the traced
     * summary, the nodes above it, its related summaries and their unsearched descendants, and the way down to them.
     * Explicitly rejected descendants stay hidden even when their parent is related; no matches leaves an empty tab.
     */
    override fun showTrace(tree: ChangeSummaryTree, trace: SummaryTrace) {
        if (mode == ChangeSummariesMode.EXPLORER) trace.best?.let { best ->
            tree.nodes.firstOrNull { it.identity == best.identity }?.let { browse(tree.shownParent(it)?.identity, tree) }
        }
        val path = tracePath(tree, trace)
        path?.let {
            expanded += path.open
            if (mode == ChangeSummariesMode.HIERARCHY) expanded -= path.fold
        }
        if (!filtering) {
            trace.best?.let { selected = it.identity; reveal = it.identity }
            return
        }
        if (path == null) return
        if (crumbs.lastOrNull()?.let { it.identity == trace.identity && it.query == trace.query } == true) {
            crumbs[crumbs.lastIndex] = crumbs.last().copy(trace = trace)
            return
        }
        val byIdentity = tree.nodes.associateBy { it.identity }
        val rejected = trace.matches.filter { it.probability < SummaryTrace.MATCH_THRESHOLD }.mapTo(hashSetOf()) { it.identity }
        val related = hashSetOf<String>()
        val below = trace.related.filter { it.identity in path.path }.mapNotNull { byIdentity[it.identity] }.toCollection(ArrayDeque())
        while (below.isNotEmpty()) {
            val node = below.removeFirst()
            if (!related.add(node.identity)) continue
            node.children.map(tree::get).filterTo(below) { it.identity !in rejected }
        }
        val shown = hashSetOf<String>()
        if (related.isNotEmpty()) {
            shown += path.path
            shown += related
            generateSequence(byIdentity[path.traced]?.parent) { tree[it].parent }.forEach { shown += tree[it].identity; expanded += tree[it].identity }
        }
        val previous = crumbs.lastOrNull()?.shown
        val kept = if (previous == null) shown else shown.filterTo(hashSetOf()) { it in previous }
        related.retainAll(kept)
        crumbs += SearchCrumb(trace.identity, trace.query, kept, related, trace)
    }

    /** A search tab's state starting from this one: the same mode, folding, scored words and searches so far. */
    fun fork(): ChangeSummariesViewState = ChangeSummariesViewState(filtering = true, scored = scored).also {
        it.mode = mode
        it.tagsCloud = tagsCloud
        it.softWrap = softWrap
        it.visibleOnly = visibleOnly
        it.expanded += expanded
        it.seen += seen
        it.level = level
        it.selectedLevel = selectedLevel
        it.folded += folded
        it.tree = tree
        it.explorerPath = explorerPath
        it.crumbs += crumbs
    }

    companion object {
        const val SMALL_TREE = 30

        /** Columns of folders, groups and the root; an empty diff has none. */
        fun branchLevels(tree: ChangeSummaryTree): Int =
            levelBranches(tree).maxOfOrNull { tree.shownDepth(it.id) + 1 } ?: 0

        /** The branches that levels fold and unfold; a merged folder shows as the folder it holds. */
        private fun levelBranches(tree: ChangeSummaryTree): List<SummaryNode> =
            tree.nodes.filter { isFolderBranch(it) && !tree.isMerged(it.id) }
    }
}

/** What the search field matches in a node: its name, its path and its summary. */
private fun searchableText(node: SummaryNode, summaries: ChangeSummaries): String =
    listOfNotNull(node.label, node.path, summaries.text(node.id)).joinToString(" ")

/**
 * A quick, naive fuzzy match: every word of [query] must be in [text], as a substring, as the letters of one of its words
 * in order (`chsum` for `ChangeSummary`), or, from four letters on, with one letter wrong, missing or extra.
 */
internal fun fuzzyMatches(query: String, text: String): Boolean {
    val haystack = text.lowercase()
    val words by lazy { SummaryWord.split(haystack).map { it.text } }
    return query.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }.all { token ->
        token in haystack || words.any { word -> abbreviates(token, word) || token.length >= 4 && nearlyStarts(token, word) }
    }
}

/** [token]'s letters appear in order in [word], starting with its first letter. */
private fun abbreviates(token: String, word: String): Boolean {
    if (token.length < 2 || word.isEmpty() || token[0] != word[0]) return false
    var at = 0
    for (char in word) if (char == token[at] && ++at == token.length) return true
    return false
}

/** [word] starts with [token] but for one letter changed, dropped or added. */
private fun nearlyStarts(token: String, word: String): Boolean =
    (token.length - 1..token.length + 1).any { length -> length <= word.length && editDistance(token, word.substring(0, length)) <= 1 }

private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val current = IntArray(b.length + 1).also { it[0] = i }
        for (j in 1..b.length) current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        previous = current
    }
    return previous[b.length]
}
