package dev.insight.board

/** Text read for an anchor: the lines of [anchor] (clamped to the file), as [text] without a trailing line break. */
data class ResolvedSource(val anchor: BoardAnchor, val text: String, val notice: String? = null)

/** A call graph symbol as the board sees it. */
data class ResolvedSymbol(val node: Int, val title: String, val meta: String?, val summary: String?, val anchor: BoardAnchor?)

/** A host-computed call path; [steps] from caller to callee, empty when none exists within the budget. */
data class ResolvedChain(val steps: List<ChainStep>, val complete: Boolean)

/**
 * What the board's tools may resolve, the host side of the "model arranges, host renders truth" rule. Implementations
 * read a review snapshot or the project and the call graph; tests use fakes. Failures the model can fix (unknown path,
 * lines outside the file, ambiguous name) throw `dev.insight.agent.ToolInputException`. May run on any thread.
 */
interface BoardSources {
    /** The lines of [anchor]; [BoardSide.BEFORE] only inside comparisons. Clamps lines to [BoardLimits.MAX_CODE_LINES]. */
    suspend fun read(anchor: BoardAnchor): ResolvedSource

    /** The patch lines of [path] (whole or within after-side lines [first]..[last]); throws outside comparisons. */
    suspend fun diff(path: String, first: Int? = null, last: Int? = null): ResolvedSource

    /** Symbols named [name] (without parameters), optionally only in [path]; empty when the graph knows none. */
    suspend fun symbols(name: String, path: String? = null): List<ResolvedSymbol>

    /** The symbol [node], or null when the graph has no such node. */
    suspend fun symbol(node: Int): ResolvedSymbol?

    /** A shortest call path from [from] to [to] in the call graph, up to [BoardLimits.MAX_CHAIN_STEPS] steps. */
    suspend fun chain(from: Int, to: Int): ResolvedChain

    /** Direct callees of [node] (step budget [limit]), for walking outward from a symbol. */
    suspend fun callees(node: Int, limit: Int = BoardLimits.MAX_CHAIN_STEPS): List<ResolvedSymbol>

    /** Whether this source has a before side (a comparison). */
    val hasComparison: Boolean
}
