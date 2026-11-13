package dev.insight.board

import java.util.UUID

/*
 * The explainer board: a read-only, auto-laid-out page of typed blocks that a model composes to explain code. The model
 * chooses what to show, how to arrange it and writes the prose; every block that cites code is resolved and rendered
 * by the host from real sources, diffs or the call graph, so the facts on the board cannot be invented. Prose blocks
 * are rendered visibly as interpretation. This file is the contract shared by the layout, codec, tools and views; it
 * is PSI-free and Swing-free.
 */

/** Which text of a reviewed file an anchor reads: the before side of a comparison, or the after (current) side. */
enum class BoardSide { BEFORE, AFTER }

/** 1-based inclusive lines [first]..[last] of project-relative [path] on [side]. [side] is AFTER outside comparisons. */
data class BoardAnchor(val path: String, val first: Int, val last: Int, val side: BoardSide = BoardSide.AFTER) {
    init {
        require(path.isNotBlank()) { "An anchor needs a path" }
        require(first >= 1 && last >= first) { "Anchor lines must be 1-based and ordered: $first..$last" }
    }

    val lineCount: Int get() = last - first + 1
    override fun toString() = "$path:$first-$last" + if (side == BoardSide.BEFORE) " (before)" else ""
}

/** How many layout columns a block spans: one, two, or the whole row. */
enum class BlockWidth { NARROW, WIDE, FULL }

/** The voice of a prose block; null is plain explanation. */
enum class Tone { NOTE, INSIGHT, WARNING, QUESTION }

enum class Severity { INFO, WARNING, ERROR }

/** A margin note of a code block at 1-based document [line] (within the block's anchor). */
data class Annotation(val line: Int, val text: String, val severity: Severity = Severity.INFO)

/** What a code block shows: project or reviewed source, or the lines of a comparison (both sides, as a patch). */
enum class CodeKind { SOURCE, DIFF }

/** One item of a numbered walkthrough; [anchor] points at the code it talks about. */
data class Step(val text: String, val anchor: BoardAnchor? = null)

/** One cell of a table; [anchor] makes the cell a link to code. */
data class Cell(val text: String, val anchor: BoardAnchor? = null)

/** A function or class on the board, from the call graph node [node]; [anchor] is its declaration when known. */
data class ChainStep(val node: Int, val title: String, val anchor: BoardAnchor? = null, val summary: String? = null)

/**
 * A block on the board. [id]s are unique on a board. Blocks hold both what was requested (anchors, node ids) and what
 * the host resolved (source text, titles), so a saved board shows what the reader saw even when the code moves on.
 */
sealed interface Block {
    val id: String
    val width: BlockWidth

    /** Whether the block's text is a model interpretation rather than host-resolved evidence. */
    val interpretation: Boolean
}

/** A section title; [level] 1 is the board's largest. */
data class HeadingBlock(override val id: String, val text: String, val level: Int = 1,
    override val width: BlockWidth = BlockWidth.FULL) : Block {
    override val interpretation get() = true
}

/** Model prose, plain or as a callout in [tone]. The text is plain text with blank lines between paragraphs. */
data class TextBlock(override val id: String, val text: String, val title: String? = null, val tone: Tone? = null,
    override val width: BlockWidth = BlockWidth.NARROW) : Block {
    override val interpretation get() = true
}

/**
 * Host-resolved code: the [text] of [anchor] as read when the block was added (a patch excerpt for [CodeKind.DIFF]),
 * with the model's margin [annotations]. [title] defaults to the anchor in views.
 */
data class CodeBlock(override val id: String, val anchor: BoardAnchor, val text: String, val kind: CodeKind = CodeKind.SOURCE,
    val title: String? = null, val annotations: List<Annotation> = emptyList(),
    override val width: BlockWidth = BlockWidth.WIDE) : Block {
    override val interpretation get() = false
}

/** A call graph symbol: [title] and [meta] come from the graph, [summary] from enrichment or the model (interpretation). */
data class SymbolBlock(override val id: String, val node: Int, val title: String, val meta: String? = null,
    val summary: String? = null, val anchor: BoardAnchor? = null,
    override val width: BlockWidth = BlockWidth.NARROW) : Block {
    override val interpretation get() = false
}

/** A host-computed call path through [steps]; [complete] is false when the path was cut at a budget. */
data class ChainBlock(override val id: String, val steps: List<ChainStep>, val title: String? = null, val complete: Boolean = true,
    override val width: BlockWidth = BlockWidth.WIDE) : Block {
    override val interpretation get() = false
}

/** A numbered walkthrough written by the model; anchored steps link to code. */
data class StepsBlock(override val id: String, val steps: List<Step>, val title: String? = null,
    override val width: BlockWidth = BlockWidth.NARROW) : Block {
    override val interpretation get() = true
}

/** A table written by the model; [rows] have as many cells as [columns]. */
data class TableBlock(override val id: String, val columns: List<String>, val rows: List<List<Cell>>, val title: String? = null,
    override val width: BlockWidth = BlockWidth.WIDE) : Block {
    override val interpretation get() = true
}

/**
 * A titled frame around [children] (ids of other blocks), laid out left to right and wrapping within the group's width.
 * A block belongs to at most one group; grouped blocks are not laid out at the top level.
 */
data class GroupBlock(override val id: String, val children: List<String>, val title: String? = null, val tone: Tone? = null,
    override val width: BlockWidth = BlockWidth.FULL) : Block {
    override val interpretation get() = true
}

/** An arrow from block [from] to block [to], optionally labelled. */
data class BoardEdge(val from: String, val to: String, val label: String? = null)

/** What the board explains: a comparison or a project scope, so sources resolve the same way when the board is reopened. */
data class BoardOrigin(val kind: String, val title: String, val paths: List<String> = emptyList())

/** A board: ordered [blocks] (top-level order is layout order), [edges] between them and when it was last changed. */
data class Board(
    val id: String,
    val title: String,
    val blocks: List<Block> = emptyList(),
    val edges: List<BoardEdge> = emptyList(),
    val origin: BoardOrigin? = null,
    val created: Long = 0,
    val updated: Long = created,
) {
    private val byId: Map<String, Block> by lazy { blocks.associateBy { it.id } }
    private val parents: Map<String, String> by lazy {
        blocks.filterIsInstance<GroupBlock>().flatMap { group -> group.children.map { it to group.id } }.toMap()
    }

    fun block(id: String): Block? = byId[id]

    /** The group holding [id], or null at the top level. */
    fun parent(id: String): GroupBlock? = parents[id]?.let { byId[it] as? GroupBlock }

    /** Blocks laid out directly on the board, in order: those in no group. */
    val topLevel: List<Block> get() = blocks.filter { it.id !in parents }

    /** The resolved children of [group], in its order, skipping ids of missing blocks. */
    fun children(group: GroupBlock): List<Block> = group.children.mapNotNull { byId[it] }

    companion object {
        fun new(title: String, origin: BoardOrigin? = null, now: Long = System.currentTimeMillis(), id: String = newBoardId()) =
            Board(id, title, origin = origin, created = now, updated = now)
    }
}

fun newBoardId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

/** Limits that keep one board readable and one reply affordable. */
object BoardLimits {
    /** Blocks one reply may add. */
    const val ADDS_PER_REPLY = 12
    /** Blocks a board holds at most. */
    const val MAX_BLOCKS = 80
    /** Lines a code block shows at most. */
    const val MAX_CODE_LINES = 80
    /** Characters of one prose block. */
    const val MAX_TEXT_CHARS = 2_000
    /** Steps of a chain block. */
    const val MAX_CHAIN_STEPS = 12
}
