package dev.insight.knowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

class KnowledgeBaseTest {
    @get:Rule val temp = TemporaryFolder()

    private val clock = AtomicLong(1000)
    private val slowDecisions = FakeEntailment { 0.95 }

    @Test fun `concurrent submits get gapless ordered seqs and match replay`() = runBlocking<Unit> {
        val store = InMemoryKnowledgeStore()
        val guard = RatchetGuard({ delay(1); slowDecisions.decide(it) })
        val kb = KnowledgeBase(store, guard, clock::incrementAndGet)
        val seen = mutableListOf<Long>()
        kb.addListener { entry, _ -> synchronized(seen) { seen += entry.seq } }
        val results = (1..40).map { i ->
            async(Dispatchers.Default) {
                val c = claim("c$i", "Fact number $i.")
                kb.submit(KnowledgeOp.Add(c), "worker:src")
                kb.submit(KnowledgeOp.Rewrite("c$i", claim("r$i", "Fact $i, restated.")), "worker:src")
            }
        }.awaitAll()
        assertEquals(40, results.size)
        val entries = kb.entries()
        assertEquals((1L..80L).toList(), entries.map { it.seq })
        assertEquals(entries.map { it.seq }, seen)
        assertEquals(entries, store.load())
        assertTrue(entries.zipWithNext().all { (a, b) -> a.at < b.at })
        assertTrue(entries.all { it.verdict.accepted })
        assertEquals(40, kb.snapshot().active().size)
        assertEquals(kb.snapshot(), KnowledgeState.replay(entries))
    }

    @Test fun `rejected ops are logged but not applied, and state reloads from file`() = runBlocking<Unit> {
        val store = FileKnowledgeStore(temp.root.toPath())
        val kb = KnowledgeBase(store, RatchetGuard(null))
        kb.submit(KnowledgeOp.Add(claim("a", "A holds.")), "user")
        val rejected = kb.submit(KnowledgeOp.Rewrite("a", claim("b", "A holds, really.")), "organizer")
        assertFalse(rejected.verdict.accepted)
        kb.submit(KnowledgeOp.MarkSuspect("a", "drift"), "drift")
        assertEquals(3, kb.entries().size)
        assertEquals(listOf("a"), kb.snapshot().claims.keys.toList())

        val reopened = KnowledgeBase(FileKnowledgeStore(temp.root.toPath()), RatchetGuard(null))
        assertEquals(kb.snapshot(), reopened.snapshot())
        assertEquals(4L, reopened.submit(KnowledgeOp.Confirm("a", emptyList()), "drift").seq)
    }

    @Test fun `listeners can be removed and failing listeners do not break submits`() = runBlocking<Unit> {
        val kb = KnowledgeBase(InMemoryKnowledgeStore(), RatchetGuard(null))
        var calls = 0
        kb.addListener { _, _ -> error("boom") }
        val remove = kb.addListener { _, _ -> calls++ }
        kb.submit(KnowledgeOp.Add(claim("a")), "user")
        remove()
        kb.submit(KnowledgeOp.Add(claim("b")), "user")
        assertEquals(1, calls)
        assertEquals(2, kb.snapshot().active().size)
    }

    @Test fun `submit sees entries appended by another file store`() = runBlocking<Unit> {
        val dir = temp.root.toPath()
        val kb = KnowledgeBase(FileKnowledgeStore(dir), RatchetGuard(null))
        val other = KnowledgeBase(FileKnowledgeStore(dir), RatchetGuard(null))
        val added = other.submit(KnowledgeOp.Add(claim("a", "A holds.")), "user")

        val marked = kb.submit(KnowledgeOp.MarkSuspect("a", "drift"), "drift")

        assertEquals(2L, marked.seq)
        assertTrue(marked.verdict.accepted)
        assertEquals(listOf(added, marked), kb.entries())
        assertEquals(kb.entries(), FileKnowledgeStore(dir).load())
        assertEquals(KnowledgeState.replay(kb.entries()), kb.snapshot())
    }

    @Test fun `submit rechecks the verdict when the ledger is replaced during the guard check`() = runBlocking<Unit> {
        val store = FileKnowledgeStore(temp.root.toPath())
        val replacement = LedgerEntry(1, KnowledgeOp.Add(claim("a", "A changed.").copy(origin = ClaimOrigin.HUMAN)),
            "user", clock.incrementAndGet(), Verdict.Accepted())
        val guard = RatchetGuard({
            Files.writeString(store.file, KnowledgeJson.encodeEntry(replacement) + "\n")
            slowDecisions.decide(it)
        })
        val kb = KnowledgeBase(store, guard, clock::incrementAndGet)
        kb.submit(KnowledgeOp.Add(claim("a", "A holds.")), "worker:src")

        val rejected = kb.submit(KnowledgeOp.Rewrite("a", claim("b", "A holds, restated.")), "worker:src")

        assertEquals(2L, rejected.seq)
        assertFalse(rejected.verdict.accepted)
        assertEquals(listOf(replacement, rejected), store.load())
        assertEquals(store.load(), kb.entries())
        assertEquals(KnowledgeState.replay(store.load()), kb.snapshot())
    }
}
