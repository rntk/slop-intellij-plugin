package dev.insight.board

import com.intellij.openapi.Disposable
import java.util.concurrent.CopyOnWriteArrayList

/** What changed on a board, for views and stores. */
sealed interface BoardChange {
    data class BlockAdded(val block: Block) : BoardChange
    data class BlockChanged(val block: Block) : BoardChange
    data class BlocksRemoved(val ids: Set<String>) : BoardChange
    data class EdgeAdded(val edge: BoardEdge) : BoardChange
    data class EdgesRemoved(val edges: List<BoardEdge>) : BoardChange
    data object Reordered : BoardChange
    data class TitleChanged(val title: String) : BoardChange
    /** The whole board was replaced, e.g. restored from disk. */
    data object Reset : BoardChange
}

/** An edit that would break the board's invariants; the message is fit for the model. */
class BoardEditException(message: String) : IllegalArgumentException(message)

/**
 * The mutable board behind an editor: every edit validates ids and limits, bumps [revision], stamps `updated` and
 * notifies listeners on the calling thread. Call from one thread at a time (the EDT in the IDE).
 */
class BoardModel(initial: Board, private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { newBoardId().take(6) }) {
    var board: Board = initial
        private set
    var revision: Int = 0
        private set
    private val listeners = CopyOnWriteArrayList<(BoardChange) -> Unit>()

    fun block(id: String): Block? = board.block(id)

    /** A block id no block on the board has. */
    fun newId(prefix: String = "b"): String {
        while (true) {
            val id = prefix + ids()
            if (board.block(id) == null) return id
        }
    }

    /**
     * Adds [block] at the end, or before the block [before] when given. A block added before a grouped block joins
     * that group at the same place (groups themselves stay at the top level).
     */
    fun add(block: Block, before: String? = null): Block {
        if (board.block(block.id) != null) throw BoardEditException("Block '${block.id}' already exists.")
        if (board.blocks.size >= BoardLimits.MAX_BLOCKS)
            throw BoardEditException("The board holds ${BoardLimits.MAX_BLOCKS} blocks, the most it can; remove blocks first.")
        validate(block)
        val blocks = board.blocks.toMutableList()
        val at = before?.let { id -> blocks.indexOfFirst { it.id == id }.also { if (it < 0) throw unknown(id) } } ?: blocks.size
        blocks.add(at, block)
        val group = before?.let(board::parent)
        val joined = if (group != null && block !is GroupBlock) blocks.map { b ->
            if (b.id == group.id) group.copy(children = group.children.flatMap { if (it == before) listOf(block.id, it) else listOf(it) }) else b
        } else blocks
        commit(board.copy(blocks = joined), BoardChange.BlockAdded(block))
        return block
    }

    /** Replaces the block with [block]'s id. */
    fun update(block: Block): Block {
        if (board.block(block.id) == null) throw unknown(block.id)
        validate(block)
        commit(board.copy(blocks = board.blocks.map { if (it.id == block.id) block else it }), BoardChange.BlockChanged(block))
        return block
    }

    /** Removes [id], its edges and its membership in groups; removing a group keeps its children at the top level. */
    fun remove(id: String): Block {
        val block = board.block(id) ?: throw unknown(id)
        val blocks = board.blocks.filter { it.id != id }.map { b ->
            if (b is GroupBlock && id in b.children) b.copy(children = b.children - id) else b
        }
        val edges = board.edges.filter { it.from != id && it.to != id }
        commit(board.copy(blocks = blocks, edges = edges), BoardChange.BlocksRemoved(setOf(id)))
        return block
    }

    /** Moves [id] before [before], or to the end when null. Groups are left as they are. */
    fun move(id: String, before: String?) {
        val block = board.block(id) ?: throw unknown(id)
        if (before != null && board.block(before) == null) throw unknown(before)
        if (before == id) return
        val rest = board.blocks.filter { it.id != id }.toMutableList()
        val at = before?.let { b -> rest.indexOfFirst { it.id == b } } ?: rest.size
        rest.add(at, block)
        commit(board.copy(blocks = rest), BoardChange.Reordered)
    }

    fun connect(edge: BoardEdge): BoardEdge {
        if (board.block(edge.from) == null) throw unknown(edge.from)
        if (board.block(edge.to) == null) throw unknown(edge.to)
        if (edge.from == edge.to) throw BoardEditException("An arrow needs two different blocks.")
        if (board.edges.any { it.from == edge.from && it.to == edge.to }) throw BoardEditException("Blocks '${edge.from}' and '${edge.to}' are already connected.")
        commit(board.copy(edges = board.edges + edge), BoardChange.EdgeAdded(edge))
        return edge
    }

    fun disconnect(from: String, to: String) {
        val removed = board.edges.filter { it.from == from && it.to == to }
        if (removed.isEmpty()) throw BoardEditException("No arrow from '$from' to '$to'.")
        commit(board.copy(edges = board.edges - removed.toSet()), BoardChange.EdgesRemoved(removed))
    }

    fun setTitle(title: String) {
        val clean = title.trim().ifEmpty { throw BoardEditException("A title must not be blank.") }
        if (clean == board.title) return
        commit(board.copy(title = clean), BoardChange.TitleChanged(clean))
    }

    /** Replaces the whole board, e.g. after loading. */
    fun reset(board: Board) {
        this.board = board
        revision++
        notify(BoardChange.Reset)
    }

    fun subscribe(listener: (BoardChange) -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    private fun validate(block: Block) {
        when (block) {
            is GroupBlock -> {
                for (child in block.children) {
                    if (child == block.id) throw BoardEditException("A group cannot contain itself.")
                    if (board.block(child) == null) throw unknown(child)
                    val other = board.parent(child)
                    if (other != null && other.id != block.id) throw BoardEditException("Block '$child' is already in group '${other.id}'.")
                    if (board.block(child) is GroupBlock) throw BoardEditException("Groups cannot be nested; '$child' is a group.")
                }
                if (block.children.toSet().size != block.children.size) throw BoardEditException("A group lists each block once.")
            }
            is TableBlock -> {
                if (block.columns.isEmpty()) throw BoardEditException("A table needs columns.")
                block.rows.firstOrNull { it.size != block.columns.size }?.let {
                    throw BoardEditException("Every table row needs ${block.columns.size} cells, like the columns.")
                }
            }
            is CodeBlock -> block.annotations.firstOrNull { it.line !in block.anchor.first..block.anchor.last }?.let {
                throw BoardEditException("Annotation line ${it.line} is outside ${block.anchor}.")
            }
            else -> {}
        }
        // A block that joins a group through the group's edit must not also be grouped elsewhere; checked above.
    }

    private fun unknown(id: String) = BoardEditException("No block '$id' is on the board.")

    private fun commit(next: Board, change: BoardChange) {
        board = next.copy(updated = clock())
        revision++
        notify(change)
    }

    private fun notify(change: BoardChange) = listeners.forEach { it(change) }
}
