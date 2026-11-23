package dev.insight.diff.walk

import dev.insight.agent.ToolCallEvent
import dev.insight.decisions.DecisionQuestion
import dev.insight.llm.Completion
import dev.insight.llm.LlmRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WalkthroughWorkflowTest {
    private val files = RenameFixture.files
    private val allIds = RenameFixture.hunkIds.toSet()

    private val renameStep = toolCall("record_step", """{"title":"Rename loadUser to fetchUser",
        "explanation":"`loadUser` became `fetchUser` (src/User.kt:2 (after)); every caller follows.",
        "root":"src/User.kt#1","hunks":["src/A.kt#1","src/B.kt#1","src/test/UserTest.kt#1"],"covered":["src/A.kt#1","src/B.kt#1"],
        "links":[{"from":"src/User.kt#1","to":"src/A.kt#1","relation":"causes","reason":"calls the renamed fetchUser"},
                 {"from":"src/User.kt#1","to":"src/B.kt#1","relation":"causes","reason":"calls the renamed fetchUser"},
                 {"from":"src/User.kt#1","to":"src/test/UserTest.kt#1","relation":"tests","reason":"asserts fetchUser"}]}""")

    private fun assertEveryHunkShown(walk: Walkthrough) {
        assertEquals(allIds, walk.steps.flatMap { it.hunks }.toSet())
        assertTrue(walk.status.values.none { it == HunkStatus.PENDING })
        assertTrue(walk.missingHunks().isEmpty())
    }

    @Test fun `a rename is linked across files and leftovers are shown as not linked`() = runBlocking<Unit> {
        val model = RoutedModel(main = listOf(
            toolCall("search_diff", """{"pattern":"loadUser","side":"removed"}"""),
            renameStep,
            Completion("Recorded the rename."),
            toolCall("mark_standalone", """{"id":"src/User.kt#2","note":"Adds a cache TTL constant."}"""),
            Completion("Standalone."),
        ))
        val updates = mutableListOf<Walkthrough>()
        val progress = mutableListOf<WalkProgress>()
        val calls = mutableListOf<ToolCallEvent>()
        val result = WalkthroughWorkflow(model::complete, null, options = WalkOptions(maxFollowUps = 1))
            .run(files, "HEAD → Working tree", focus = "API changes", onUpdate = { updates += it }, onProgress = { progress += it },
                onToolCall = { calls += it })

        assertTrue(result.complete)
        assertEquals(result, updates.last())
        assertEveryHunkShown(result)
        val rename = result.step("S1")!!
        assertEquals(listOf("src/User.kt#1", "src/A.kt#1", "src/B.kt#1", "src/test/UserTest.kt#1"), rename.hunks)
        assertEquals(HunkStatus.LINKED, result.statusOf("src/User.kt#1"))
        assertEquals(HunkStatus.COVERED, result.statusOf("src/A.kt#1"))
        assertEquals(HunkStatus.COVERED, result.statusOf("src/B.kt#1"))
        assertEquals(HunkStatus.STANDALONE, result.statusOf("src/User.kt#2"))
        assertEquals(listOf("src/A.kt#1", "src/B.kt#1", "src/test/UserTest.kt#1"), result.neighbors("src/User.kt#1"))
        assertEquals(listOf("caused by"), result.linksOf("src/A.kt#1").map { it.label })
        // The model never handled the logo or the moved file: they are shown, not lost.
        val unlinked = result.steps.last()
        assertEquals(WalkStepKind.UNLINKED, unlinked.kind)
        assertEquals(listOf("images/logo.png#1", "src/New.kt#1"), unlinked.hunks)
        assertEquals(2, result.coverage()[HunkStatus.UNLINKED])

        // The first seed is the declaration, batched with the small hunk of its file; covered sites are never seeds.
        val turns = model.turnMessages
        assertEquals(3, turns.size)
        assertTrue(turns[0].contains("Seed hunks for this turn: src/User.kt#1, src/User.kt#2"))
        assertTrue(turns[0].contains("Reviewer focus (user preference): API changes"))
        assertTrue(turns[0].contains("Comparison: HEAD → Working tree"))
        assertFalse(turns.any { it.contains("Seed hunk for this turn: src/A.kt#1") })
        assertTrue(turns[1].contains("- S1 \"Rename loadUser to fetchUser\""))
        // Turns are independent: each starts from the system prompt and its own seed message.
        assertTrue(model.mainRequests.all { it.messages.first().role == LlmRole.SYSTEM })
        assertEquals(2, model.mainRequests.last().messages.count { it.role == LlmRole.USER }) // seed plus one nudge
        assertTrue(model.mainRequests.last().messages.last().content.startsWith("Still pending: "))
        assertTrue("read_snapshot_source" !in model.mainRequests.first().tools.map { it.name })
        assertTrue("search_tools" in model.mainRequests.first().tools.map { it.name })
        assertEquals(listOf("search_diff", "record_step", "mark_standalone"), calls.map { it.name })
        assertEquals(WalkPhase.DONE, progress.last().phase)
        assertEquals(5, progress.last().handled)
        assertTrue(progress.any { it.phase == WalkPhase.WALKING && it.seed == listOf("src/User.kt#1", "src/User.kt#2") })
    }

    @Test fun `triage decisions order seeds and verification marks weak links`() = runBlocking<Unit> {
        val decisions = FakeDecisions { id, question, request ->
            if (request.state.asJsonObject.has("links")) return@FakeDecisions noul(if (id == "l3") 0.2 else 0.9)
            val ref = id.substringBefore('.')
            val path = request.state.asJsonObject.getAsJsonArray("hunks").map { it.asJsonObject }.first { it["ref"].asString == ref }["path"].asString
            when (question) {
                is DecisionQuestion.Choice -> choice(if (path == "src/B.kt") "logic" else "formatting_or_imports")
                is DecisionQuestion.Noul -> noul(if (path == "src/B.kt") 0.99 else 0.0)
                else -> null
            }
        }
        val model = RoutedModel(main = listOf(renameStep, Completion("Done.")))
        val result = WalkthroughWorkflow(model::complete, decisions::decide, options = WalkOptions(maxFollowUps = 0)).run(files, "")
        assertTrue(model.turnMessages.first().contains("Seed hunk for this turn: src/B.kt#1"))
        assertEquals(TriageSource.DECISIONS, result.triage!!.getValue("src/B.kt#1").source)
        assertEquals(listOf(0.9, 0.9, 0.2), result.links.map { it.confidence })
        assertEquals(listOf(false, false, true), result.links.map { it.weak })
        assertEveryHunkShown(result)
    }

    @Test fun `failing decisions and failing turns never fail the run`() = runBlocking<Unit> {
        val updates = mutableListOf<Walkthrough>()
        val result = WalkthroughWorkflow({ throw IOException("model down") }, { throw IOException("decisions down") },
            options = WalkOptions(maxConsecutiveFailures = 2)).run(files, "", onUpdate = { updates += it })
        assertTrue(result.complete)
        assertEveryHunkShown(result)
        assertTrue(result.status.values.all { it == HunkStatus.UNLINKED })
        assertTrue(result.warnings.any { it.contains("decisions down") })
        assertTrue(result.warnings.any { it.contains("2 failed turns in a row") && it.contains("model down") })
        assertTrue(updates.first().status.values.all { it == HunkStatus.PENDING })
    }

    @Test fun `request budget stops the run and leftovers stay visible`() = runBlocking<Unit> {
        val model = RoutedModel(fallback = { toolCall("walk_status", "{}") })
        val result = WalkthroughWorkflow(model::complete, null, options = WalkOptions(maxRequests = 3)).run(files, "")
        assertEquals(3, model.mainRequests.size)
        assertTrue(result.warnings.any { it.contains("budget of 3 requests") })
        assertEveryHunkShown(result)
    }

    @Test fun `ask_subagent is limited per turn and reports nested tool calls`() = runBlocking<Unit> {
        val model = RoutedModel(
            main = listOf(
                toolCall("ask_subagent", """{"task":"Find callers of fetchUser","hunk_ids":["src/User.kt#1"]}"""),
                toolCall("ask_subagent", """{"task":"Again"}"""),
                renameStep, Completion("Done."),
            ),
            sub = listOf(toolCall("search_diff", """{"pattern":"fetchUser","side":"added"}"""), Completion("src/A.kt#1 and src/B.kt#1 call it.")),
        )
        val calls = mutableListOf<ToolCallEvent>()
        WalkthroughWorkflow(model::complete, null, options = WalkOptions(subagentCallsPerTurn = 1, maxSeedTurns = 1))
            .run(files, "", onToolCall = { calls += it })
        assertEquals(listOf("ask_subagent/search_diff", "ask_subagent", "ask_subagent", "record_step"), calls.map { it.name })
        assertTrue(calls[1].result.endsWith("Cited hunks: src/A.kt#1, src/B.kt#1"))
        assertTrue(calls[2].failed && calls[2].result.contains("No ask_subagent calls left"))
        assertEquals(2, model.subRequests.size)
        assertTrue(model.subRequests.all { request -> request.tools.none { it.name in WalkToolNames.RECORDING || it.name == "ask_subagent" } })
    }

    @Test fun `cancellation emits a finalized partial walkthrough`() = runBlocking<Unit> {
        val second = CompletableDeferred<Unit>()
        val model = RoutedModel(main = listOf(renameStep, Completion("Done."))) {
            second.complete(Unit)
            awaitCancellation()
        }
        val updates = mutableListOf<Walkthrough>()
        val job = launch { WalkthroughWorkflow(model::complete, null).run(files, "", onUpdate = { updates += it }) }
        second.await()
        job.cancelAndJoin()
        val last = updates.last()
        assertFalse(last.complete)
        assertEveryHunkShown(last)
        assertEquals(HunkStatus.COVERED, last.statusOf("src/A.kt#1"))
        assertTrue(last.warnings.any { it.contains("cancelled") })
    }
}
