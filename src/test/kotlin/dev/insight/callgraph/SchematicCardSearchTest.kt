package dev.insight.callgraph

import dev.insight.enrichment.FunctionSummary
import dev.insight.graph.CallGraphNode
import dev.insight.ui.searchCards
import org.junit.Assert.assertEquals
import org.junit.Test

class SchematicCardSearchTest {
    private val node = CallGraphNode(7, "openCardSearch", "SchematicPanel", "SchematicEditor.kt:42", 0, true,
        filePath = "/project/src/SchematicEditor.kt")

    @Test fun `a card names its symbol and describes where it is`() {
        val card = schematicCardCandidate("key", node, null, usageOf = "moveTo")
        assertEquals("openCardSearch", card.name)
        assertEquals("Usage of moveTo · Function · SchematicPanel · SchematicEditor.kt:42", card.detail)
    }

    @Test fun `cards match by container, file name and summary`() {
        val cards = listOf(schematicCardCandidate("key", node, FunctionSummary("Shows the card search", "Lists pinned cards.", null, false)))
        assertEquals(1, searchCards(cards, "SP").size)
        assertEquals(1, searchCards(cards, "SchematicEditor").size)
        assertEquals(1, searchCards(cards, "pinned cards").size)
        assertEquals(0, searchCards(cards, "unrelated").size)
    }
}
