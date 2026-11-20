package dev.insight.canvas

import dev.insight.graph.RelationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasUsageSitesTest {
    private fun usage(path: String, line: Int, owner: String, kind: RelationKind = RelationKind.CALL, offset: Int = line * 10) =
        CanvasUsage(owner.length, owner, owner, "/project/$path", path, offset, line, kind)

    @Test
    fun `usages join by file in finding order with each file's sites by line`() {
        val files = canvasUsageFiles(listOf(
            usage("src/b/Client.kt", 30, "again()"),
            usage("src/a/Api.kt", 4, "main()"),
            usage("src/b/Client.kt", 12, "run()"),
            usage("src/b/Client.kt", 18, "run()", RelationKind.REFERENCE),
        ))
        assertEquals(listOf("Client.kt", "Api.kt"), files.map { it.fileName })
        val client = files.first()
        assertEquals("src/b", client.directory)
        assertEquals(listOf(12, 18, 30), client.usages.map { it.line })
        assertEquals("2 calls, 1 use in run(), again()", client.detail)
        assertEquals("1 call in main()", files.last().detail)

        val sites = client.sites("work")
        assertEquals("work", sites.targetName)
        assertEquals(listOf(CardUsageSite(11, true, "run()"), CardUsageSite(17, false, "run()"), CardUsageSite(29, true, "again()")), sites.sites)
        assertEquals("Line 12 · Call in run()", canvasUsageSiteLabel(sites.sites.first()))
        assertEquals("Usages of work (3)", canvasUsageSitesTitle(sites))
    }

    @Test
    fun `the used-by title counts sites and files`() {
        val one = CanvasUsageSearch(1, "work", listOf(usage("A.kt", 1, "a()")), truncated = false)
        assertEquals("Used by 1 site in 1 file", canvasUsedByTitle(one))
        val many = CanvasUsageSearch(1, "work", listOf(usage("A.kt", 1, "a()"), usage("A.kt", 2, "b()"), usage("B.kt", 1, "c()")), truncated = true)
        assertEquals("Used by 3+ sites in 2 files", canvasUsedByTitle(many))
    }

    @Test
    fun `a card keeps its usage sites until cleared`() {
        val model = CanvasSessionModel(CanvasSession.new("src/Api.kt"), ids = { "c1" })
        val card = model.open(CANVAS_ROOT, CardKind.SELECTION, CardAnchor(0, 3, 0), "src/Api.kt", 0, "fun")
        val sites = CardUsages("work", listOf(CardUsageSite(2, true, "run()")))
        model.setUsages(card.id, sites)
        assertEquals(sites, model.card(card.id)!!.usages)
        model.setUsages(card.id, CardUsages("work", emptyList()))
        assertNull(model.card(card.id)!!.usages)
    }

    @Test
    fun `the chat context names the usage sites listed on a card`() {
        val card = CanvasCard("c1", CANVAS_ROOT, CardKind.SYMBOL, CardAnchor(0, 0, 0), SymbolRef("src/Client.kt", "Client.kt", "FILE"),
            "src/Client.kt", 0, "class Client", wholeFile = true,
            usages = CardUsages("work", listOf(CardUsageSite(2, true, "run()"), CardUsageSite(9, false, "Client"))))
        val context = canvasChainContext("src/Api.kt", listOf(card))
        assertEquals(true, context.contains("Usages of work in this file: line 3 (call in run()), line 10 (use in Client)"))
    }
}
