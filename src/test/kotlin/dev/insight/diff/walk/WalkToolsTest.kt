package dev.insight.diff.walk

import dev.insight.agent.ChatTool
import dev.insight.agent.ToolCallEvent
import dev.insight.agent.ToolInputException
import dev.insight.llm.Completion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkToolsTest {
    private val ledger = WalkLedger(walkHunks(RenameFixture.files))
    private val tools = WalkTools(RenameFixture.files, ledger)

    private fun ChatTool.run(arguments: String): String = runBlocking { call(json(arguments)) }
    private fun hunkIdsIn(text: String) = Regex("^(?:- )?(\\S+#\\d+) \\[", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()

    @Test fun `search_diff finds every site of a rename by side`() {
        val removed = tools.searchDiff.run("""{"pattern":"loadUser","side":"removed"}""")
        assertEquals(listOf("src/User.kt#1", "src/A.kt#1", "src/B.kt#1", "src/test/UserTest.kt#1"), hunkIdsIn(removed))
        assertTrue(removed, removed.startsWith("'loadUser' in removed rows: 4 matches in 4 hunks (4 pending)."))
        assertTrue(removed.contains("-    users.loadUser(1)"))
        assertTrue(tools.searchDiff.run("""{"pattern":"loadUser","side":"added"}""").contains("0 matches"))
        val regex = tools.searchDiff.run("""{"pattern":"\\bfetchUser\\(\\d\\)","regex":true,"side":"added","path_glob":"src/*.kt"}""")
        assertEquals(listOf("src/A.kt#1", "src/B.kt#1"), hunkIdsIn(regex))
        assertEquals(listOf("src/test/UserTest.kt#1"), hunkIdsIn(tools.searchDiff.run("""{"pattern":"fetchUser","path_glob":"*Test.kt"}""")))
        assertThrows(ToolInputException::class.java) { tools.searchDiff.run("""{"pattern":"(","regex":true}""") }
        assertThrows(ToolInputException::class.java) { tools.searchDiff.run("""{"pattern":"x","side":"sideways"}""") }
    }

    @Test fun `search_diff filters by status and pages`() = runBlocking<Unit> {
        ledger.recordStep("Rename", "x", "src/User.kt#1", listOf("src/A.kt#1"), emptyList(), listOf("src/A.kt#1"))
        val pending = tools.searchDiff.run("""{"pattern":"loadUser","status":"pending"}""")
        assertEquals(listOf("src/B.kt#1", "src/test/UserTest.kt#1"), hunkIdsIn(pending))
        val many = (1..40).map { reviewFile("src/F$it.kt", "@@ -1,1 +1,1 @@\n-old()\n+new()\n") }
        val big = WalkTools(many, WalkLedger(walkHunks(many)))
        val first = big.searchDiff.run("""{"pattern":"new","side":"added"}""")
        assertEquals(WalkTools.SEARCH_PAGE, hunkIdsIn(first).size)
        assertTrue(first.endsWith("next_offset: ${WalkTools.SEARCH_PAGE}"))
        assertEquals(40 - WalkTools.SEARCH_PAGE, hunkIdsIn(big.searchDiff.run("""{"pattern":"new","side":"added","offset":25}""")).size)
    }

    @Test fun `read_hunks numbers rows and pages huge hunks`() {
        val text = tools.readHunks.run("""{"ids":["src/User.kt#2"]}""")
        assertTrue(text, text.startsWith("src/User.kt#2 [pending] +1 -0 @@ -20,3 +20,4 @@ object Cache, rows 1-5 of 5"))
        assertTrue(text, text.contains("         21 +val ttl = 60"))
        assertTrue(text.contains("   21    22  fun clear()"))
        val rows = (1..600).joinToString("") { "+line number $it with some padding text\n" }
        val huge = reviewFile("src/Huge.kt", "@@ -0,0 +1,600 @@\n$rows", status = "added")
        val hugeTools = WalkTools(listOf(huge), WalkLedger(walkHunks(huge)), resultChars = 4_000)
        val page = hugeTools.readHunks.run("""{"ids":["src/Huge.kt#1"]}""")
        assertTrue(page.length <= 4_200)
        val offset = Regex("offset (\\d+)").find(page)!!.groupValues[1].toInt()
        assertTrue(offset in 10..599)
        val next = hugeTools.readHunks.run("""{"ids":["src/Huge.kt#1"],"offset":$offset}""")
        assertTrue(next.contains("rows ${offset + 1}-"))
        assertTrue(next.contains("line number $offset with"))
        assertThrows(ToolInputException::class.java) { tools.readHunks.run("""{"ids":["src/Nope.kt#1"]}""") }
        assertThrows(ToolInputException::class.java) { tools.readHunks.run("""{"ids":[]}""") }
        assertTrue(tools.readHunks.run("""{"ids":["src/New.kt#1"]}""").contains("no textual changes; renamed from src/Old.kt"))
    }

    @Test fun `record_step and extend_step tools write the ledger`() {
        val recorded = tools.recordStep.run("""{"title":"Rename loadUser to fetchUser","explanation":"See src/User.kt:2 (after).",
            "root":"src/User.kt#1","hunks":["src/A.kt#1"],"covered":["src/A.kt#1"],
            "links":[{"from":"src/User.kt#1","to":"src/A.kt#1","relation":"causes","reason":"calls fetchUser"}]}""")
        assertTrue(recorded, recorded.startsWith("Recorded step S1 with 2 hunks (1 linked, 1 covered); 2 newly handled. Links added: 1."))
        assertTrue(recorded.contains("pending 5"))
        val extended = tools.extendStep.run("""{"step_id":"S1","hunks":["src/B.kt#1"],"covered":["src/B.kt#1"],
            "links":[{"from":"src/A.kt#1","to":"src/B.kt#1","relation":"same_edit","reason":"same rename"}]}""")
        assertTrue(extended, extended.startsWith("Extended step S1 with 3 hunks (1 linked, 2 covered)"))
        assertEquals(HunkStatus.COVERED, ledger.status("src/B.kt#1"))
        assertThrows(ToolInputException::class.java) {
            tools.addLinks.run("""{"links":[{"from":"src/A.kt#1","to":"src/B.kt#1","relation":"leads","reason":"r"}]}""")
        }
        assertTrue(tools.addLinks.run("""{"links":[{"from":"src/User.kt#1","to":"src/test/UserTest.kt#1","relation":"tests","reason":"r"}]}""")
            .startsWith("Added 1 link."))
        assertTrue(tools.markStandalone.run("""{"id":"images/logo.png#1","note":"New logo."}""").startsWith("Marked standalone step S2"))
        assertEquals("logo.png", ledger.step("S2")!!.title)
        assertThrows(ToolInputException::class.java) { tools.recordStep.run("""{"title":"t","explanation":"e","root":"invented#3"}""") }
    }

    @Test fun `walk_status and list_hunks show progress and filter`() = runBlocking<Unit> {
        ledger.recordStep("Rename", "x", "src/User.kt#1", listOf("src/A.kt#1"), emptyList(), listOf("src/A.kt#1"))
        val status = tools.walkStatus.run("{}")
        assertTrue(status, status.startsWith("Hunks: 7; pending 5, linked 1, covered 1, standalone 0."))
        assertTrue(status.contains("- S1 \"Rename\" root src/User.kt#1, 2 hunks"))
        assertTrue(status.contains("Pending hunks, best seeds first (5):"))
        assertEquals(listOf("src/A.kt#1"), hunkIdsIn(tools.listHunks.run("""{"status":"covered"}""")))
        assertEquals(listOf("src/User.kt#1", "src/User.kt#2"), hunkIdsIn(tools.listHunks.run("""{"path":"src/User.kt"}""")))
        assertTrue(tools.listHunks.run("""{"path":"src/User.kt"}""").contains("src/User.kt#1 [linked in S1]"))
        assertEquals(listOf("src/test/UserTest.kt#1"), hunkIdsIn(tools.listHunks.run("""{"path_glob":"**/test/**"}""")))
        assertThrows(ToolInputException::class.java) { tools.listHunks.run("""{"path":"src/Nope.kt"}""") }
    }

    @Test fun `read_snapshot_source reads either side`() {
        assertTrue(tools.readSnapshotSource.run("""{"path":"src/User.kt","side":"before"}""").contains("loadUser"))
        assertTrue(tools.readSnapshotSource.run("""{"path":"src/User.kt","start_line":2,"end_line":2}""").contains("fetchUser"))
        assertThrows(ToolInputException::class.java) { tools.readSnapshotSource.run("""{"path":"src/Missing.kt"}""") }
    }

    @Test fun `ask_subagent runs a read-only sub-run and caps its answer`() = runBlocking<Unit> {
        val model = RoutedModel(sub = listOf(
            toolCall("search_diff", """{"pattern":"fetchUser","side":"added"}"""),
            Completion("Call sites: src/A.kt#1 and src/B.kt#1, not invented#7. " + "x".repeat(500)),
        ))
        val events = mutableListOf<ToolCallEvent>()
        val project = dev.insight.agent.objectTool("read_file", "Read a project file.", dev.insight.llm.JsonSchemas.obj()) { "text" }
        val ask = AskSubagentTool(model::complete, tools, projectTools = listOf(project), maxRounds = 3, answerChars = 120,
            knownHunk = { ledger.hunk(it) != null }, onToolCall = { events += it })
        val result = ask.call(json("""{"task":"Which hunks call fetchUser?","hunk_ids":["src/User.kt#1"]}"""))
        assertTrue(result.contains("[Answer cut at 120 characters.]"))
        assertTrue(result, result.endsWith("Cited hunks: src/A.kt#1, src/B.kt#1"))
        val offered = model.subRequests.first().tools.map { it.name }.toSet()
        assertEquals(setOf("walk_status", "list_hunks", "read_hunks", "search_diff", "read_snapshot_source", "search_tools"), offered)
        assertFalse(offered.any { it in WalkToolNames.RECORDING || it == WalkToolNames.ASK_SUBAGENT })
        assertTrue(model.subRequests.first().messages.last().content.contains("fun fetchUser"))
        assertEquals(listOf("ask_subagent/search_diff"), events.map { it.name })
        assertEquals(0, ledger.stepCount)
        assertThrows(ToolInputException::class.java) { runBlocking { ask.call(json("""{"task":"t","hunk_ids":["nope#1"]}""")) } }
        // A subagent never receives recording tools or itself, so it cannot recurse.
        assertThrows(IllegalArgumentException::class.java) {
            AskSubagentTool(model::complete, tools, readTools = tools.readOnly() + ask, knownHunk = { true })
        }
        assertThrows(IllegalArgumentException::class.java) {
            AskSubagentTool(model::complete, tools, readTools = tools.recording(), knownHunk = { true })
        }
    }

    @Test fun `globs match folders and file names`() {
        assertTrue(globRegex("src/**/*.kt").matches("src/a/b/C.kt"))
        assertTrue(globRegex("src/**/*.kt").matches("src/C.kt"))
        assertFalse(globRegex("src/*.kt").matches("src/a/C.kt"))
        assertTrue(globRegex("*Test.kt").matches("src/test/UserTest.kt"))
        assertFalse(globRegex("*.kt").matches("src/a.kts"))
    }
}
