package dev.insight.diff.walk

import dev.insight.decisions.DecisionQuestion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WalkTriageTest {
    private val hunks = walkHunks(RenameFixture.files)

    @Test fun `heuristics put declarations first and mechanical edits last`() {
        val kinds = hunks.associate { it.id to heuristicTriage(it).kind }
        assertEquals(HunkKind.API_OR_SIGNATURE_CHANGE, kinds["src/User.kt#1"])
        assertEquals(HunkKind.NEW_CODE, kinds["src/User.kt#2"])
        assertEquals(HunkKind.RENAME_OR_MECHANICAL, kinds["src/A.kt#1"])
        assertEquals(HunkKind.TESTS, kinds["src/test/UserTest.kt#1"])
        assertEquals(HunkKind.RENAME_OR_MECHANICAL, kinds["src/New.kt#1"])
        assertEquals(HunkKind.LOGIC, kinds["images/logo.png#1"])
        val imports = walkHunks(reviewFile("src/I.kt", "@@ -1,1 +1,1 @@\n-import a.B\n+import a.C\n")).single()
        assertEquals(HunkKind.FORMATTING_OR_IMPORTS, heuristicTriage(imports).kind)
        val order = seedOrder(hunks, null).map { it.id }
        assertEquals("src/User.kt#1", order.first())
        assertTrue(order.indexOf("src/A.kt#1") > order.indexOf("src/test/UserTest.kt#1"))
    }

    @Test fun `decisions classify hunks in bounded batches and order seeds`() = runBlocking<Unit> {
        val decisions = FakeDecisions { id, question, request ->
            val ref = id.substringBefore('.')
            val path = request.state.asJsonObject.getAsJsonArray("hunks").map { it.asJsonObject }
                .first { it["ref"].asString == ref }["path"].asString
            when (question) {
                is DecisionQuestion.Choice -> choice(if (path == "src/B.kt") "api_or_signature_change" else "rename_or_mechanical")
                is DecisionQuestion.Noul -> noul(if (path == "src/B.kt") 0.95 else 0.1)
                else -> null
            }
        }
        val result = WalkTriage(decisions::decide, batchSize = 3).classify(hunks)
        assertEquals(3, decisions.requests.size) // 7 hunks in batches of 3
        assertEquals(6, decisions.requests.first().questions.size)
        assertTrue(result.warnings.isEmpty())
        assertEquals(TriageSource.DECISIONS, result.triage.getValue("src/B.kt#1").source)
        assertEquals(HunkKind.API_OR_SIGNATURE_CHANGE, result.triage.getValue("src/B.kt#1").kind)
        assertEquals("src/B.kt#1", seedOrder(hunks, result.triage).first().id)

        val limited = WalkTriage(FakeDecisions { _, _, _ -> null }::decide, batchSize = 2, maxRequests = 1).classify(hunks)
        assertEquals(TriageSource.HEURISTIC, limited.triage.getValue("src/New.kt#1").source)
        assertEquals(hunks.size, limited.triage.size)
        assertEquals(2, limited.warnings.size)
        assertTrue(limited.warnings[0], limited.warnings[0].startsWith("Decisions gave no usable answer for 2 hunks"))
        assertTrue(limited.warnings[1], limited.warnings[1].startsWith("${hunks.size - 2} hunks were triaged by heuristics beyond"))

        val unusable = WalkTriage(FakeDecisions { _, _, _ -> null }::decide, batchSize = 3).classify(hunks)
        assertEquals(listOf("Decisions gave no usable answer for ${hunks.size} hunks; they were ordered by heuristics."), unusable.warnings)
    }

    @Test fun `failing decisions fall back to heuristics with a warning`() = runBlocking<Unit> {
        val result = WalkTriage({ throw IOException("decisions down") }).classify(hunks)
        assertEquals(hunks.size, result.triage.size)
        assertTrue(result.triage.values.all { it.source == TriageSource.HEURISTIC })
        assertTrue(result.warnings.single().contains("decisions down"))
        assertTrue(WalkTriage(null).classify(hunks).let { it.warnings.isEmpty() && it.requests == 0 })
    }

    @Test fun `link verification stores confidence and marks weak links`() = runBlocking<Unit> {
        val links = listOf(
            WalkLink("src/User.kt#1", "src/A.kt#1", WalkRelation.CAUSES, "calls fetchUser"),
            WalkLink("src/User.kt#1", "images/logo.png#1", WalkRelation.CAUSES, "unrelated"),
        )
        val decisions = FakeDecisions { id, _, request ->
            val link = request.state.asJsonObject.getAsJsonArray("links").map { it.asJsonObject }.first { it["ref"].asString == id }
            noul(if (link["reason"].asString == "unrelated") 0.1 else 0.9)
        }
        val verified = WalkLinkVerifier(decisions::decide).verify(links, hunks.associateBy { it.id })
        assertEquals(1, verified.requests)
        val ledger = WalkLedger(hunks)
        ledger.addLinks(links, emit = false)
        ledger.setConfidence(verified.confidence)
        val stored = ledger.snapshot().links
        assertEquals(listOf(0.9, 0.1), stored.map { it.confidence })
        assertEquals(listOf(false, true), stored.map { it.weak })

        val failed = WalkLinkVerifier({ throw IOException("down") }).verify(links, hunks.associateBy { it.id })
        assertTrue(failed.confidence.isEmpty())
        assertTrue(failed.warnings.single().contains("down"))
        assertNull(WalkLink("a", "b", WalkRelation.CAUSES, "r").confidence)
    }
}
