package dev.insight.callgraph

import org.junit.Assert.assertEquals
import org.junit.Test

class SchematicGapUnitsTest {
    private fun units(rows: List<SchematicRow>, constructs: Map<Int, List<GapConstruct>>, blank: Set<Int> = emptySet(),
        budget: GapBudget = GapBudget()) =
        gapUnits(rows, { "code $it" }, { it !in blank }, { constructs[it].orEmpty() }, budget)

    @Test
    fun `a condition is read whole with the call inside it and its closing brace`() {
        // if err != nil { / return wrap(err) / }
        val rows = listOf(CodeRow(0, 0), BlockRow(1, 1, 1, null, listOf(7)), CodeRow(2, 2))
        val gaps = units(rows, mapOf(0 to listOf(GapConstruct(0, 2, 0))), blank = setOf(2))
        assertEquals(listOf(GapUnit(0, 2, symbols = listOf(1))), gaps)
        assertEquals(rows, splitAtUnits(rows, gaps))
    }

    @Test
    fun `a small statement grows into what holds it while that reads at a glance`() {
        val rows = listOf(BlockRow(0, 0, 0, null, listOf(7)), CodeRow(1, 40))
        val chain = listOf(GapConstruct(2, 2), GapConstruct(1, 3, 1), GapConstruct(1, 30, 1))
        val gaps = units(rows, mapOf(2 to chain), blank = (1..40).toSet() - 2)
        assertEquals(listOf(GapUnit(1, 3)), gaps)
    }

    @Test
    fun `an oversized construct falls back to its header and lines of its body to themselves`() {
        val rows = listOf(CodeRow(0, 79))
        val big = GapConstruct(0, 79, headerLast = 1)
        val gaps = units(rows, mapOf(0 to listOf(big), 5 to listOf(big)), blank = (0..79).toSet() - setOf(0, 5))
        assertEquals(listOf(GapUnit(0, 1, construct = 0..79), GapUnit(5, 5)), gaps)
        assertEquals(listOf(CodeRow(0, 1), CodeRow(2, 4), CodeRow(5, 5), CodeRow(6, 79)), splitAtUnits(rows, gaps))
    }

    @Test
    fun `statements read on together within one code row but never across a block`() {
        val rows = listOf(CodeRow(0, 2), BlockRow(3, 3, 3, null, listOf(7)), CodeRow(4, 4))
        val single = (0..4).associateWith { listOf(GapConstruct(it, it)) }
        assertEquals(listOf(GapUnit(0, 2), GapUnit(4, 4)), units(rows, single))
        // A statement holding a block stays apart from the plain ones beside it.
        val holding = listOf(CodeRow(0, 1), BlockRow(2, 2, 2, null, listOf(7)), CodeRow(3, 4))
        val constructs = mapOf(0 to listOf(GapConstruct(0, 0)), 1 to listOf(GapConstruct(1, 3)), 4 to listOf(GapConstruct(4, 4)))
        assertEquals(listOf(GapUnit(0, 0), GapUnit(1, 3, symbols = listOf(2)), GapUnit(4, 4)), units(holding, constructs))
    }

    @Test
    fun `folded bodies do not count toward the size but many declarations make a container`() {
        fun declaration(id: Int, first: Int) = BlockRow(first, first + 19, first, id, emptyList())
        val rows = listOf(CodeRow(0, 0), declaration(1, 1), declaration(2, 21), CodeRow(41, 41))
        val type = GapConstruct(0, 41, headerLast = 0)
        assertEquals(listOf(GapUnit(0, 41, folded = listOf(2..20, 22..40), symbols = listOf(1, 21))),
            units(rows, mapOf(0 to listOf(type), 41 to listOf(type))))
        val crowded = listOf(CodeRow(0, 0)) + (0 until 4).map { declaration(it, 1 + it * 20) } + CodeRow(81, 81)
        val container = GapConstruct(0, 81, headerLast = 0)
        assertEquals(listOf(GapUnit(0, 0, construct = 0..81)),
            units(crowded, mapOf(0 to listOf(container), 81 to listOf(container)), blank = setOf(81)))
    }

    @Test
    fun `declaration-only containers stay whole from an inner construct regardless of every limit`() {
        val rows = listOf(CodeRow(0, 1)) + (2..5).map { BlockRow(it, it, it, it, emptyList()) } + CodeRow(6, 80)
        val container = GapConstruct(1, 79, headerLast = 1, keepWhole = true)
        val constructs = mapOf(10 to listOf(GapConstruct(10, 10), container), 80 to listOf(GapConstruct(80, 80)))
        assertEquals(listOf(GapUnit(1, 79, symbols = (2..5).toList()), GapUnit(80, 80)),
            units(rows, constructs, blank = (0..79).toSet() - 10,
                budget = GapBudget(lines = 1, chars = 1, declarations = 0, glance = 0)))
    }

    @Test
    fun `a unit touching a folded declaration covers it whole`() {
        val rows = listOf(CodeRow(0, 1), BlockRow(2, 5, 2, 1, emptyList()), CodeRow(6, 6))
        assertEquals(listOf(GapUnit(1, 5, folded = listOf(3..5), symbols = listOf(2))),
            units(rows, mapOf(1 to listOf(GapConstruct(1, 3))), blank = setOf(0, 6)))
    }
}
