package dev.insight.knowledge.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceBudgetTest {
    @Test fun `everything fits when under budget`() {
        assertEquals(listOf(10, 20), allocateBudget(listOf(10, 20), 100))
    }

    @Test fun `short texts stay whole and long ones share the rest`() {
        assertEquals(listOf(10, 45, 45), allocateBudget(listOf(10, 500, 900), 100))
    }

    @Test fun `allocation never exceeds the budget`() {
        val a = allocateBudget(listOf(7, 300, 300, 3), 50)
        assertTrue(a.sum() <= 50)
        assertEquals(listOf(7, 20, 20, 3), a)
    }

    @Test fun `capText cuts with an ellipsis within the limit`() {
        assertEquals("abc", capText("abc", 5))
        assertEquals("ab…", capText("abcdef", 3))
        assertEquals("line1\nline2…", capText("line1\nline2\nline3 is long", 14))
        assertEquals("", capText("abc", 0))
    }
}
