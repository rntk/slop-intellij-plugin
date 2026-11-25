package dev.insight.knowledge

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList

/** Notified after every submitted op, accepted or rejected, with the entry and the resulting state. */
fun interface KnowledgeListener {
    fun onEntry(entry: LedgerEntry, state: KnowledgeState)
}

/**
 * The single writer of a project's knowledge. Loads and replays [store] on construction; [submit] runs ops one at a time:
 * the [guard] judges the op against the current state, the entry (accepted or rejected) is appended to [store], and only
 * then is the state advanced and listeners notified. Seqs are gapless and in submission order. Reads never block.
 */
class KnowledgeBase(
    private val store: KnowledgeStore,
    private val guard: RatchetGuard,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val listeners = CopyOnWriteArrayList<KnowledgeListener>()
    @Volatile private var state: KnowledgeState
    @Volatile private var log: List<LedgerEntry>

    init {
        log = store.load().sortedBy { it.seq }
        state = KnowledgeState.replay(log)
    }

    fun snapshot(): KnowledgeState = state
    fun entries(): List<LedgerEntry> = log

    /** Judges, records and (when accepted) applies [op] by [actor]; returns the recorded entry. Store failures propagate. */
    suspend fun submit(op: KnowledgeOp, actor: String): LedgerEntry = mutex.withLock {
        val before = state
        val verdict = guard.check(before, op, actor)
        val entry = LedgerEntry((log.lastOrNull()?.seq ?: 0) + 1, op, actor, clock(), verdict)
        store.append(entry)
        val after = if (verdict.accepted) before.apply(op) else before
        log = log + entry
        state = after
        listeners.forEach { runCatching { it.onEntry(entry, after) } }
        entry
    }

    /** Adds [listener]; the returned function removes it. */
    fun addListener(listener: KnowledgeListener): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
}
