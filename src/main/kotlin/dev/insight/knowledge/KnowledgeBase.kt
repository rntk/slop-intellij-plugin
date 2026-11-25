package dev.insight.knowledge

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

/** Notified after every submitted op, accepted or rejected, with the entry and the resulting state. */
fun interface KnowledgeListener {
    fun onEntry(entry: LedgerEntry, state: KnowledgeState)
}

/**
 * Loads and replays [store] on construction. [submit] refreshes from the store before asking the [guard] to judge an op
 * and retries if the ledger changes before append. An appended entry advances the state and notifies listeners.
 * Reads of the in-memory view never block.
 */
class KnowledgeBase(
    private val store: KnowledgeStore,
    private val guard: RatchetGuard,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val listeners = CopyOnWriteArrayList<KnowledgeListener>()
    private val distillOffsets = mutableMapOf<List<String>, Int>()
    @Synchronized internal fun distillOffset(scope: List<String>): Int = distillOffsets[scope] ?: 0
    @Synchronized internal fun setDistillOffset(scope: List<String>, offset: Int) {
        if (offset == 0) distillOffsets.remove(scope) else distillOffsets[scope.toList()] = offset
    }
    @Volatile private var view = store.load().sortedBy { it.seq }.let { View(it, KnowledgeState.replay(it)) }

    fun snapshot(): KnowledgeState = view.state
    fun entries(): List<LedgerEntry> = view.entries

    /** Judges, records and (when accepted) applies [op] by [actor]; returns the recorded entry. Store failures propagate. */
    suspend fun submit(op: KnowledgeOp, actor: String): LedgerEntry = mutex.withLock {
        var before: View
        var entry: LedgerEntry
        do {
            val stored = store.load()
            val ordered = stored.sortedBy { it.seq }
            before = view.takeIf { it.entries == ordered } ?: View(ordered, KnowledgeState.replay(ordered))
            view = before
            val verdict = guard.check(before.state, op, actor)
            currentCoroutineContext().ensureActive()
            entry = LedgerEntry((before.entries.lastOrNull()?.seq ?: 0) + 1, op, actor, clock(), verdict)
        } while (!store.appendIfUnchanged(stored, entry))

        val after = if (entry.verdict.accepted) before.state.apply(op) else before.state
        view = View(before.entries + entry, after)
        listeners.forEach { runCatching { it.onEntry(entry, after) } }
        entry
    }

    /** Adds [listener]; the returned function removes it. */
    fun addListener(listener: KnowledgeListener): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private data class View(val entries: List<LedgerEntry>, val state: KnowledgeState)
}
