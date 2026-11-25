package dev.insight.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeLedgerTest {
    private val a = claim("a")
    private val b = claim("b")

    private fun entry(seq: Long, op: KnowledgeOp, accepted: Boolean = true) =
        LedgerEntry(seq, op, "user", seq * 10, if (accepted) Verdict.Accepted() else Verdict.Rejected("no"))

    @Test fun `apply moves statuses and never deletes`() {
        val s = KnowledgeState.EMPTY.apply(KnowledgeOp.Add(a)).apply(KnowledgeOp.Add(b))
        val merged = claim("m", "A and B hold.")
        val after = s.apply(KnowledgeOp.Merge(listOf("a", "b"), merged))
        assertEquals(ClaimStatus.Superseded(listOf("m")), after["a"]!!.status)
        assertEquals(listOf("m"), after.active().map { it.id })
        assertEquals(3, after.claims.size)
        val contradicted = s.apply(KnowledgeOp.Contradict("a", "b", "disagree"))
        assertTrue(contradicted["a"]!!.status is ClaimStatus.Suspect)
        assertEquals(2, contradicted.active().size)
        val confirmed = contradicted.apply(KnowledgeOp.Confirm("a", listOf(Anchor(AnchorKind.FILE, "src/a.kt", "new"))))
        assertEquals(ClaimStatus.Live, confirmed["a"]!!.status)
        assertEquals("new", confirmed["a"]!!.anchors.single().fingerprint)
        val restored = after.apply(KnowledgeOp.Restore("a"))
        assertTrue(restored["a"]!!.isActive)
    }

    @Test fun `apply rejects invalid ops`() {
        val s = stateOf(a, b)
        assertThrows(KnowledgeOpException::class.java) { s.apply(KnowledgeOp.Add(a)) }
        assertThrows(KnowledgeOpException::class.java) { s.apply(KnowledgeOp.MarkSuspect("zzz", "r")) }
        assertThrows(KnowledgeOpException::class.java) { s.apply(KnowledgeOp.Merge(listOf("a", "a"), claim("m"))) }
        assertThrows(KnowledgeOpException::class.java) { s.apply(KnowledgeOp.Contradict("a", "a", "r")) }
        assertThrows(KnowledgeOpException::class.java) { s.apply(KnowledgeOp.Restore("a")) }
        assertThrows(KnowledgeOpException::class.java) {
            s.apply(KnowledgeOp.Add(claim("c").copy(status = ClaimStatus.Suspect("x"))))
        }
        val retired = s.apply(KnowledgeOp.Retire("a", "gone", RetireEvidence.AnchorsGone(listOf("src/a.kt"))))
        assertThrows(KnowledgeOpException::class.java) { retired.apply(KnowledgeOp.Reanchor("a", emptyList())) }
    }

    @Test fun `replay is deterministic and skips rejected entries`() {
        val entries = listOf(
            entry(1, KnowledgeOp.Add(a)),
            entry(2, KnowledgeOp.Add(b)),
            entry(3, KnowledgeOp.Retire("a", "x", RetireEvidence.ByUser("n")), accepted = false),
            entry(4, KnowledgeOp.Rewrite("b", claim("b2", "B holds, restated."))),
            entry(5, KnowledgeOp.MarkSuspect("zzz", "unknown"), accepted = true), // no longer applies: skipped
            entry(6, KnowledgeOp.MarkSuspect("a", "drift")),
        )
        val once = KnowledgeState.replay(entries)
        assertEquals(once, KnowledgeState.replay(entries))
        assertEquals(once, KnowledgeState.replay(entries.shuffled(java.util.Random(7))))
        assertEquals(ClaimStatus.Suspect("drift"), once["a"]!!.status)
        assertEquals(listOf("a", "b", "b2"), once.claims.keys.toList())
        assertEquals(listOf("a", "b2"), once.active().map { it.id })
    }

    @Test fun `ratchet catches a bad apply`() {
        val before = stateOf(a, b)
        val retireA = KnowledgeOp.Retire("a", "x", RetireEvidence.ByUser("n"))
        // Dropping b silently while retiring a.
        val sneaky = KnowledgeState(mapOf("a" to a.copy(status = ClaimStatus.Retired("x", RetireEvidence.ByUser("n"))),
            "b" to b.copy(status = ClaimStatus.Superseded(listOf("a")))))
        assertTrue(checkRatchet(before, sneaky, retireA)!!.contains("Claim b"))
        assertTrue(checkRatchet(before, stateOf(a), KnowledgeOp.Add(claim("c")))!!.contains("disappeared"))
        assertNotNull(checkRatchet(before, stateOf(a.copy(text = "Other."), b), KnowledgeOp.Reanchor("a", emptyList())))
        assertNull(checkRatchet(before, before.apply(retireA), retireA))
        assertNull(checkRatchet(before, before.apply(KnowledgeOp.Contradict("a", "b", "r")), KnowledgeOp.Contradict("a", "b", "r")))
    }

    @Test fun `claim ids are stable and fresh ids avoid collisions`() = runBlocking<Unit> {
        val id = ClaimIds.of("The cache is  LRU.", "src")
        assertEquals(id, ClaimIds.of("the cache is lru", "src"))
        assertNotEquals(id, ClaimIds.of("the cache is lru", "lib"))
        assertTrue(Regex("c-[0-9a-f]{12}").matches(id))
        val s = stateOf(claim(id, "The cache is LRU."))
        assertNotEquals(id, s.newId("The cache is LRU.", "src"))
        assertEquals(AnchorState.Gone, FakeAnchorResolver(emptyMap()).resolve(a.anchors.single()))
    }

    @Test fun `area queries`() {
        val s = stateOf(claim("r", area = ""), claim("x", area = "src"), claim("y", area = "src/sub"), claim("z", area = "srcx"))
        assertEquals(listOf("x"), s.inArea("src").map { it.id })
        assertEquals(listOf("x", "y"), s.inArea("src", nested = true).map { it.id })
        assertEquals(4, s.inArea("", nested = true).size)
        assertEquals(setOf("", "src", "src/sub", "srcx"), s.byArea().keys)
    }
}
