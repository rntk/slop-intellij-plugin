package dev.insight.diff.walk

import dev.insight.agent.ToolInputException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkLedgerTest {
    private val updates = mutableListOf<Walkthrough>()
    private val ledger = WalkLedger(walkHunks(RenameFixture.files)) { updates += it }
    private val root = "src/User.kt#1"
    private val a = "src/A.kt#1"
    private val b = "src/B.kt#1"
    private val test = "src/test/UserTest.kt#1"

    private fun causes(to: String) = WalkLink(root, to, WalkRelation.CAUSES, "calls the renamed fetchUser")

    @Test fun `record step links root and hunks and covers mechanical sites`() = runBlocking<Unit> {
        val change = ledger.recordStep("Rename loadUser", "`loadUser` is now `fetchUser`.", root, listOf(a, b, test),
            listOf(causes(a), causes(b), WalkLink(root, test, WalkRelation.TESTS, "asserts fetchUser")), covered = listOf(a, b))
        assertEquals("S1", change.step.id)
        assertEquals(listOf(root, a, b, test), change.step.hunks)
        assertEquals(4, change.newlyHandled)
        assertEquals(3, change.linksAdded)
        val walk = ledger.snapshot()
        assertEquals(HunkStatus.LINKED, walk.statusOf(root))
        assertEquals(HunkStatus.COVERED, walk.statusOf(a))
        assertEquals(HunkStatus.LINKED, walk.statusOf(test))
        assertEquals(1, updates.size)
        assertEquals(walk, updates.last())
    }

    @Test fun `invalid input is rejected without changing anything`() = runBlocking<Unit> {
        val unknown = assertThrows(ToolInputException::class.java) {
            runBlocking { ledger.recordStep("t", "e", root, listOf(a, "src/Missing.kt#1"), emptyList(), emptyList()) }
        }
        assertTrue(unknown.message!!.contains("src/Missing.kt#1"))
        assertThrows(ToolInputException::class.java) {
            runBlocking { ledger.recordStep("t", "e", root, listOf(a), listOf(WalkLink(a, "nope#9", WalkRelation.CAUSES, "r")), emptyList()) }
        }
        // Covered hunks must be in the step, and the analyzed root cannot be covered.
        assertThrows(ToolInputException::class.java) {
            runBlocking { ledger.recordStep("t", "e", root, listOf(a), emptyList(), covered = listOf(b)) }
        }
        assertThrows(ToolInputException::class.java) {
            runBlocking { ledger.recordStep("t", "e", root, listOf(a), emptyList(), covered = listOf(root)) }
        }
        // A step's links must touch it; self-links and blank reasons are invalid.
        assertThrows(ToolInputException::class.java) {
            runBlocking { ledger.recordStep("t", "e", root, listOf(a), listOf(WalkLink(b, test, WalkRelation.CAUSES, "r")), emptyList()) }
        }
        assertThrows(ToolInputException::class.java) {
            runBlocking { ledger.addLinks(listOf(WalkLink(a, a, WalkRelation.RELATED, "r")), emit = true) }
        }
        assertThrows(ToolInputException::class.java) {
            runBlocking { ledger.addLinks(listOf(WalkLink(a, b, WalkRelation.RELATED, " ")), emit = true) }
        }
        assertEquals(0, ledger.stepCount)
        assertEquals(RenameFixture.hunkIds.size, ledger.pendingCount)
        assertTrue(updates.isEmpty())
    }

    @Test fun `links are deduplicated and status never drops`() = runBlocking<Unit> {
        ledger.recordStep("Call site", "A adapts.", a, emptyList(), emptyList(), emptyList())
        val change = ledger.recordStep("Rename", "x", root, listOf(a, b), listOf(causes(a), causes(a).copy(reason = "again"),
            WalkLink(a, b, WalkRelation.SAME_EDIT, "same")), covered = listOf(a, b))
        assertEquals(2, change.linksAdded)
        assertEquals(1, change.linksKnown)
        assertEquals(HunkStatus.LINKED, ledger.status(a)) // analyzed in S1, so not downgraded to covered
        assertEquals(HunkStatus.COVERED, ledger.status(b))
        val (added, known) = ledger.addLinks(listOf(WalkLink(b, a, WalkRelation.SAME_EDIT, "symmetric duplicate"),
            WalkLink(b, test, WalkRelation.RELATED, "r")), emit = true)
        assertEquals(1 to 1, added to known)
        assertEquals(listOf("S1", "S2"), ledger.snapshot().stepsOf(a).map { it.id })
    }

    @Test fun `extend step appends hunks and turns a standalone step into a chain`() = runBlocking<Unit> {
        val standalone = ledger.markStandalone(root, "Renames the loader.")
        assertEquals(WalkStepKind.STANDALONE, standalone.step.kind)
        assertEquals(HunkStatus.STANDALONE, ledger.status(root))
        assertThrows(ToolInputException::class.java) { runBlocking { ledger.markStandalone(root, "again") } }
        val extended = ledger.extendStep(standalone.step.id, listOf(a, b), listOf(causes(a)), covered = listOf(a, b), explanation = "Sites follow.")
        assertEquals(WalkStepKind.CHAIN, extended.step.kind)
        assertEquals(listOf(root, a, b), extended.step.hunks)
        assertTrue(extended.step.explanation.endsWith("Sites follow."))
        assertEquals(HunkStatus.LINKED, ledger.status(root))
        assertEquals(HunkStatus.COVERED, ledger.status(a))
        assertEquals(2, extended.newlyHandled)
        assertThrows(ToolInputException::class.java) { runBlocking { ledger.extendStep("S9", listOf(test), emptyList(), emptyList()) } }
        assertThrows(ToolInputException::class.java) { runBlocking { ledger.extendStep("S1", emptyList(), emptyList(), emptyList()) } }
    }

    @Test fun `finalize shows every pending hunk as unlinked and closes the ledger`() = runBlocking<Unit> {
        ledger.recordStep("Rename", "x", root, listOf(a), emptyList(), covered = listOf(a))
        val final = ledger.finalize()
        assertTrue(final.missingHunks().isEmpty())
        assertEquals(RenameFixture.hunkIds.toSet(), final.steps.flatMap { it.hunks }.toSet())
        assertEquals(5, final.coverage()[HunkStatus.UNLINKED])
        assertThrows(ToolInputException::class.java) { runBlocking { ledger.markStandalone(b, "late") } }
        ledger.setConfidence(mapOf())
        val done = ledger.markComplete()
        assertTrue(done.complete)
        assertEquals(done, updates.last())
    }
}
