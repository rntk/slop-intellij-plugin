package dev.insight.canvas

import kotlin.math.roundToInt

/** A short, retargetable transition. Repeated layouts with the same destination do not restart it. */
internal class CanvasMotion(
    private val durationNanos: Long = 220_000_000L,
) {
    private data class Move(val from: CanvasPlacement, val to: CanvasPlacement, val started: Long, val appeared: Long?)
    private val moves = HashMap<String, Move>()

    fun target(id: String, to: CanvasPlacement, now: Long, animate: Boolean, lift: Int = 12) {
        val old = moves[id]
        if (!animate) {
            moves[id] = Move(to, to, now - durationNanos, null)
        } else if (old == null) {
            moves[id] = Move(to.copy(y = to.y + lift), to, now, now)
        } else if (old.to != to) {
            moves[id] = Move(position(id, now), to, now, old.appeared)
        }
    }

    fun position(id: String, now: Long): CanvasPlacement {
        val move = moves.getValue(id)
        val t = progress(move.started, now)
        val eased = 1 - (1 - t) * (1 - t) * (1 - t)
        return CanvasPlacement(
            (move.from.x + (move.to.x - move.from.x) * eased).roundToInt(),
            (move.from.y + (move.to.y - move.from.y) * eased).roundToInt(),
        )
    }

    fun opacity(id: String, now: Long): Float = moves[id]?.appeared?.let { progress(it, now).toFloat() } ?: 1f
    fun active(now: Long) = moves.values.any { progress(it.started, now) < 1 || (it.appeared?.let { start -> progress(start, now) < 1 } == true) }
    fun retain(ids: Set<String>) { moves.keys.retainAll(ids) }
    fun clear() = moves.clear()
    private fun progress(start: Long, now: Long) = ((now - start).toDouble() / durationNanos).coerceIn(0.0, 1.0)
}
