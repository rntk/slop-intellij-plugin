package dev.insight.callgraph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `calls at separate places on a line are independent links`() {
        val row = schematicRows(0, 0, emptyList(), listOf(
            SchematicCall(5, 0, columns = 12..14),
            SchematicCall(6, 0, columns = 2..4),
            SchematicCall(7, 0, columns = 2..4),
            SchematicCall(8, 0),
        )).single() as BlockRow
        assertEquals(listOf(5, 6, 7, 8), row.calls)
        assertEquals(listOf(
            SchematicLink(listOf(6, 7), 2..4),
            SchematicLink(listOf(5), 12..14),
            SchematicLink(listOf(8), null),
        ), row.links)
        assertTrue(row.split)
    }

    @Test
    fun `a line whose calls share one place keeps the whole row as its link`() {
        val row = schematicRows(0, 0, emptyList(), listOf(SchematicCall(5, 0, columns = 2..6), SchematicCall(6, 0, columns = 4..9)))
            .single() as BlockRow
        assertEquals(listOf(SchematicLink(listOf(5, 6), null)), row.links)
        assertFalse(row.split)
    }

    @Test
    fun `types and properties are links beside calls, on their own lines, and on declaration headlines`() {
        val rows = schematicRows(0, 6, listOf(SchematicDeclaration(1, first = 3, last = 6, headline = 3, columns = 9..12)),
            listOf(SchematicCall(5, line = 0, columns = 10..13), SchematicCall(9, line = 0)),
            listOf(
                SchematicReference(20, line = 0, columns = 4..7),
                SchematicReference(21, line = 0, columns = 11..12),
                SchematicReference(9, line = 0, columns = 20..22),
                SchematicReference(22, line = 1, columns = 2..5),
                SchematicReference(22, line = 1, columns = 2..5),
                SchematicReference(23, line = 3, columns = 16..21),
                SchematicReference(1, line = 3, columns = 9..12),
                SchematicReference(24, line = 4, columns = 2..5),
            ))
        val (calls, mentions, declaration) = rows.filterIsInstance<BlockRow>()
        assertEquals("A name inside a call and a callee already linked are left out", listOf(
            SchematicLink(listOf(20), 4..7, reference = true),
            SchematicLink(listOf(5), 10..13),
            SchematicLink(listOf(9), null),
        ), calls.links)
        assertEquals(listOf(5, 9), calls.targets)
        assertEquals(BlockRow(1, 1, 1, null, emptyList(), links = listOf(SchematicLink(listOf(22), 2..5, reference = true))), mentions)
        assertTrue("A single type is a link of its own, not the whole line", mentions.split)
        assertEquals("References inside a folded body are hidden", listOf(
            SchematicLink(listOf(1), 9..12),
            SchematicLink(listOf(23), 16..21, reference = true),
        ), declaration.links)
        assertEquals(listOf(1), declaration.targets)
        assertEquals(CodeRow(2, 2), rows[2])
    }

    @Test
    fun `a declaration without a placed name keeps the whole row as its link`() {
        val row = schematicRows(0, 2, listOf(SchematicDeclaration(1, 0, 2, 0)), emptyList(), listOf(SchematicReference(7, 0, 4..6)))
            .single() as BlockRow
        assertFalse(row.split)
        assertEquals(listOf(SchematicLink(listOf(1), null)), row.links)
    }

    @Test
    fun `callee columns cover the called name on the call's first line`() {
        val line = "    val x = store.save(a).load<Int>(b)"
        val lineStart = 100
        fun columns(site: String, callee: String) =
            calleeColumns(lineStart + line.indexOf(site), site, callee, lineStart, lineStart + line.length)
        assertEquals(line.indexOf("save").let { it until it + 4 }, columns("store.save(a)", "store.save"))
        assertEquals(line.indexOf("load").let { it until it + 4 }, columns("store.save(a).load<Int>(b)", "store.save(a).load<Int>"))
        assertNull(columns("store.save(a)", "missing"))
        assertNull("A name past the line is unknown", calleeColumns(lineStart, "f(\n  g)", "g", lineStart, lineStart + 2))
    }

    @Test
    fun `reachable functions are breadth first, nearest kept within the limit`() {
        val calls = mapOf(0 to listOf(1, 2), 1 to listOf(3, 0), 2 to listOf(3), 3 to listOf(4))
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 1, 3 to 2, 4 to 3), reachable(listOf(0), 10) { calls[it].orEmpty() })
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 1), reachable(listOf(0), 3) { calls[it].orEmpty() })
        assertEquals(mapOf(2 to 0, 1 to 0, 3 to 1, 0 to 1, 4 to 2), reachable(listOf(2, 1), 10) { calls[it].orEmpty() })
    }
}
