package dev.insight.canvas

import dev.insight.callgraph.GapUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasGapsTest {
    private val source = CanvasSource("import a\n\nval x = 1\nfun f() {}", emptyList(), 10, "src/A.kt", null,
        gaps = listOf(GapUnit(10, 10), GapUnit(11, 11), GapUnit(12, 12)))

    @Test
    fun `a summary is shown only while its lines read as they did`() {
        val hash = source.gapHash(12, 12)!!
        assertNull("Lines outside the source have no hash", source.gapHash(9, 10))
        assertNull(source.gapHash(13, 14))
        val current = GapSummary(12, 12, hash, "Sets x.")
        val stale = GapSummary(10, 10, hash, "Old.")
        assertEquals(listOf(current), source.shownGapSummaries(listOf(stale, current)))
        val moved = CanvasSource("import a\n\n// added\nval x = 1", emptyList(), 10, "src/A.kt", null)
        assertEquals("A line inserted above moves the code off its summary", emptyList<GapSummary>(), moved.shownGapSummaries(listOf(current)))
    }

    @Test
    fun `gaps still to summarize skip summarized and blank ones`() {
        val summarized = GapSummary(12, 12, source.gapHash(12, 12)!!, "Sets x.")
        assertEquals(listOf(GapUnit(10, 10)), source.unsummarizedGaps(listOf(summarized)))
        assertEquals(listOf(GapUnit(10, 10), GapUnit(12, 12)), source.unsummarizedGaps(listOf(summarized.copy(hash = 0))))
    }

    @Test
    fun `a new summary replaces those of overlapping lines and keeps line order`() {
        val a = GapSummary(1, 2, 1, "a")
        val b = GapSummary(5, 6, 2, "b")
        val c = GapSummary(2, 4, 3, "c")
        assertEquals(listOf(c, b), withGapSummary(listOf(b, a), c))
        assertEquals(listOf(a, b), withGapSummary(listOf(b), a))
    }

    @Test
    fun `the model adds and clears gap summaries of cards and the root file`() {
        val model = CanvasSessionModel(CanvasSession.new("src/A.kt", now = 0), clock = { 0 }, ids = { "c1" })
        val card = model.open(null, CardKind.SYMBOL, CardAnchor(0, 1, 0), "src/A.kt", 0, "fun f() {}", SymbolRef("src/A.kt", "f", "FUNCTION"))
        val changes = mutableListOf<CanvasChange>()
        model.subscribe { changes += it }
        model.addGapSummary(null, GapSummary(0, 0, 1, "root"))
        model.addGapSummary(card.id, GapSummary(0, 0, 2, "card"))
        assertEquals(listOf("root"), model.session.root.gapSummaries.map { it.text })
        assertEquals(listOf("card"), model.card(card.id)!!.gapSummaries.map { it.text })
        model.clearGapSummaries(card.id)
        assertEquals(emptyList<GapSummary>(), model.card(card.id)!!.gapSummaries)
        assertEquals(listOf(CanvasChange.RootChanged::class, CanvasChange.CardChanged::class, CanvasChange.CardChanged::class),
            changes.map { it::class })
    }
}
