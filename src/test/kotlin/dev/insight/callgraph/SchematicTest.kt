package dev.insight.callgraph

import org.junit.Assert.assertEquals
import org.junit.Test

class SchematicTest {
    @Test
    fun `declarations fold, call lines become blocks and the rest stays code`() {
        val rows = schematicRows(0, 9,
            listOf(SchematicDeclaration(1, first = 2, last = 5, headline = 3)),
            listOf(SchematicCall(7, line = 4), SchematicCall(8, line = 7), SchematicCall(9, line = 7), SchematicCall(8, line = 7)))
        assertEquals(listOf(
            CodeRow(0, 1),
            BlockRow(2, 5, 3, declared = 1, calls = emptyList()),
            CodeRow(6, 6),
            BlockRow(7, 7, 7, declared = null, calls = listOf(8, 9)),
            CodeRow(8, 9),
        ), rows)
    }

    @Test
    fun `nested declarations keep the outermost and everything is clipped to the scope`() {
        val rows = schematicRows(2, 6,
            listOf(SchematicDeclaration(1, 3, 6, 3), SchematicDeclaration(2, 4, 5, 4), SchematicDeclaration(3, 0, 1, 0)),
            listOf(SchematicCall(5, 2), SchematicCall(6, 9)))
        assertEquals(listOf(BlockRow(2, 2, 2, null, listOf(5)), BlockRow(3, 6, 3, 1, emptyList())), rows)
    }

    @Test
    fun `reachable functions are breadth first, nearest kept within the limit`() {
        val calls = mapOf(0 to listOf(1, 2), 1 to listOf(3, 0), 2 to listOf(3), 3 to listOf(4))
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 1, 3 to 2, 4 to 3), reachable(listOf(0), 10) { calls[it].orEmpty() })
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 1), reachable(listOf(0), 3) { calls[it].orEmpty() })
        assertEquals(mapOf(2 to 0, 1 to 0, 3 to 1, 0 to 1, 4 to 2), reachable(listOf(2, 1), 10) { calls[it].orEmpty() })
    }
}
