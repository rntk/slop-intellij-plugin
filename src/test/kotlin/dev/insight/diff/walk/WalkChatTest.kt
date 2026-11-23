package dev.insight.diff.walk

import dev.insight.diff.walk.ui.WalkedFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkChatTest {
    private val files = RenameFixture.files
    private val walk = WalkedFixture.finished

    private fun call(name: String, args: String): String =
        runBlocking { walkChatTools(files, walk).all.single { it.definition.name == name }.call(json(args)) }

    @Test fun `a card's chat pins its hunk, steps, links and the linked hunks' rows`() {
        val prompt = walkCardChatPrompt(walk, WalkedFixture.DECL, "main..feature")!!
        assertTrue(prompt.contains("one hunk of a code change"))
        assertTrue(prompt.contains("read_links"))
        assertTrue(prompt.contains("comparison: main..feature"))
        assertTrue(prompt.contains("Root of step S1 \"Rename loadUser to fetchUser\""))
        assertTrue("The step's note is pinned", prompt.contains("`loadUser` became **fetchUser**"))
        assertTrue("Its own rows are pinned", prompt.contains("fun fetchUser(id: Int)"))
        assertTrue(prompt.contains("leads to ${WalkedFixture.A}: calls the renamed function"))
        assertTrue("Weak links say so", prompt.contains("tested by ${WalkedFixture.TEST}: covers the rename (weak, confidence 0.30)"))
        assertTrue("Linked hunks' rows are pinned", prompt.contains("users.fetchUser(2)"))
        assertTrue(prompt.contains("assertEquals(u, users.fetchUser(1))"))
        assertFalse("Unlinked hunks stay out", prompt.contains("val ttl = 60"))
        assertNull(walkCardChatPrompt(walk, "src/Gone.kt#1", ""))
    }

    @Test fun `linked hunks past the budget are listed for read_hunks`() {
        val prompt = walkCardChatPrompt(walk, WalkedFixture.DECL, "", pinnedChars = 500)!!
        assertTrue(prompt.contains("not pinned for size"))
        assertTrue(prompt.contains(WalkedFixture.TEST))
    }

    @Test fun `the canvas chat pins the steps, the cards on the canvas and the links between them`() {
        val view = WalkCanvasView(listOf(WalkedFixture.DECL, WalkedFixture.A), listOf("S1"), focused = WalkedFixture.A)
        val prompt = walkCanvasChatPrompt(walk, view, "")
        assertTrue(prompt.contains("the diff Walkthrough's own chat"))
        assertTrue(prompt.contains("- S2 \"Cache TTL\""))
        assertTrue(prompt.contains("Note of S1"))
        assertTrue(prompt.contains("Cards on the canvas (2)"))
        assertTrue(prompt.contains("${WalkedFixture.A} [covered in S1] +1 -1"))
        assertTrue(prompt.contains("(focused)"))
        assertTrue(prompt.contains("- ${WalkedFixture.DECL} leads to ${WalkedFixture.A}: calls the renamed function"))
        assertFalse("Links leaving the canvas are read through tools", prompt.contains("- ${WalkedFixture.DECL} tested by"))
        assertTrue(prompt.contains("users.fetchUser(1)"))
        assertFalse(prompt.contains("users.fetchUser(2)"))

        val empty = walkCanvasChatPrompt(Walkthrough.pending(walk.hunks), WalkCanvasView(emptyList(), emptyList()), "")
        assertTrue(empty.contains("Not built yet"))
        assertTrue(empty.contains("the canvas shows no hunk cards"))
    }

    @Test fun `the chat's tools only read`() {
        val tools = walkChatTools(files, walk)
        assertEquals("Only read_hunks goes with every turn", listOf(WalkToolNames.READ_HUNKS), tools.tools.map { it.definition.name })
        assertEquals(listOf(WalkChatToolNames.READ_STEPS, WalkChatToolNames.READ_LINKS, WalkToolNames.LIST_HUNKS,
            WalkToolNames.SEARCH_DIFF, WalkToolNames.READ_SNAPSHOT_SOURCE), tools.registry.tools.map { it.definition.name })
        val names = tools.all.map { it.definition.name }
        assertTrue(names.none { it in WalkToolNames.RECORDING })
        assertTrue("The run's hunk readers see the built walkthrough", call(WalkToolNames.LIST_HUNKS, "{\"status\": \"covered\"}")
            .let { it.contains(WalkedFixture.A) && it.contains("in S1") && !it.contains(WalkedFixture.DECL) })
        assertTrue(call(WalkToolNames.SEARCH_DIFF, "{\"pattern\": \"loadUser\", \"side\": \"removed\"}").contains("4 hunks"))
    }

    @Test fun `read_steps lists the steps and reads one`() {
        val list = call(WalkChatToolNames.READ_STEPS, "{}")
        assertTrue(list.startsWith("Built."))
        assertTrue(list.contains("- S1 \"Rename loadUser to fetchUser\" (chain; root ${WalkedFixture.DECL}; 4 hunks)"))
        assertTrue(list.contains("- unlinked \"${WalkStep.UNLINKED_TITLE}\" (unlinked;"))
        val step = call(WalkChatToolNames.READ_STEPS, "{\"step_id\": \"S1\"}")
        assertTrue(step.contains("see src/A.kt:6"))
        assertTrue(step.contains("- ${WalkedFixture.B} [covered in S1]"))
        assertTrue(runCatching { call(WalkChatToolNames.READ_STEPS, "{\"step_id\": \"S9\"}") }.exceptionOrNull()?.message!!.contains("Unknown step"))
    }

    @Test fun `read_links shows each hunk's links as seen from it`() {
        val links = call(WalkChatToolNames.READ_LINKS, "{\"ids\": [\"${WalkedFixture.A}\", \"${WalkedFixture.TTL}\"]}")
        assertTrue(links.contains("caused by ${WalkedFixture.DECL}: calls the renamed function"))
        assertTrue(links.contains("same edit as ${WalkedFixture.B}: same rename"))
        assertTrue(links.contains("root of S2"))
        assertTrue(links.contains("no links"))
    }

    @Test fun `a ledger of a built walkthrough keeps it and records nothing`() {
        val ledger = WalkLedger.of(walk)
        assertEquals(walk, ledger.snapshot())
        val error = runCatching { runBlocking { ledger.markStandalone(WalkedFixture.LOGO, "note") } }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("finished"))
    }

    /** A hub hunk linked to [fanOut] others with long reasons, each in its own file, all in one step with a long note. */
    private fun hub(fanOut: Int): Walkthrough {
        fun hunk(path: String, rows: Int) = WalkHunk("$path#1", path, 1, "@@ -1,$rows +1,$rows @@",
            "@@ -1,$rows +1,$rows @@\n" + (1..rows).joinToString("\n") { "+val v$it = $it" }, 1, rows, 1, rows, rows, 0, "modified")
        val center = hunk("src/Hub.kt", 5_000)
        val others = (1..fanOut).map { hunk("src/gen/Leaf$it.kt", 3) }
        val links = others.map { WalkLink(center.id, it.id, WalkRelation.CAUSES, "x".repeat(400), confidence = 0.9) }
        val step = WalkStep("S1", "Hub", "n".repeat(10_000), center.id, listOf(center.id) + others.map { it.id })
        return Walkthrough(listOf(center) + others, listOf(step), links, complete = true,
            warnings = List(fanOut) { "warning $it " + "w".repeat(200) })
    }

    @Test fun `pinned metadata and omission notices stay within the budget`() {
        val walk = hub(2_000)
        val card = walkCardChatPrompt(walk, walk.hunks[0].id, "c".repeat(5_000), pinnedChars = 8_000)!!
        val policy = card.substringBefore("\n\nWalkthrough (data")
        assertTrue("card prompt is ${card.length - policy.length} past the policy", card.length - policy.length < 8_000 + 1_500)
        assertTrue(card.contains("more links: call read_links with ids [src/Hub.kt#1] and offset"))
        assertTrue(card.contains("more rows: call read_hunks"))

        val view = WalkCanvasView(walk.hunks.map { it.id }, listOf("S1"))
        val canvas = walkCanvasChatPrompt(walk, view, "", pinnedChars = 8_000)
        assertTrue("canvas prompt is ${canvas.length - policy.length} past the policy", canvas.length - policy.length < 8_000 + 2_500)
        assertTrue(canvas.contains("more cards not listed for size"))
    }

    @Test fun `read_links pages through one hunk's links`() {
        val walk = hub(200)
        val tool = walkChatTools(emptyList(), walk, resultChars = 2_000).all.single { it.definition.name == WalkChatToolNames.READ_LINKS }
        var offset: Int? = 0
        val seen = mutableSetOf<String>()
        var pages = 0
        while (offset != null) {
            val page = runBlocking { tool.call(json("{\"ids\": [\"src/Hub.kt#1\"], \"offset\": $offset}")) }
            assertTrue(page.length <= 2_000)
            Regex("leads to (src/gen/Leaf\\d+\\.kt#1)").findAll(page).forEach { seen += it.groupValues[1] }
            val next = Regex("and offset (\\d+)").find(page)?.groupValues?.get(1)?.toInt()
            if (next != null) assertTrue(next > offset)
            offset = next
            assertTrue(++pages < 500)
        }
        assertTrue(pages > 1)
        assertEquals(200, seen.size)
    }

    @Test fun `link confidence ignores the default locale`() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertTrue(walkCardChatPrompt(walk, WalkedFixture.DECL, "")!!.contains("confidence 0.30"))
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    @Test fun `a hunk is the same change when only its line numbers moved`() {
        val hunk = walk.hunk(WalkedFixture.A)!!
        val moved = hunk.copy(header = hunk.header.replace("-", "-9"), patch = hunk.patch.replaceFirst(hunk.header, hunk.header.replace("-", "-9")))
        assertTrue(hunk.sameChangeAs(moved))
        assertFalse(hunk.sameChangeAs(walk.hunk(WalkedFixture.B)!!))
        assertFalse(hunk.sameChangeAs(hunk.copy(patch = hunk.patch + "\n+extra")))
    }
}
