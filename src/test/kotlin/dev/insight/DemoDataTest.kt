package dev.insight

import dev.insight.dashboard.DemoData
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoDataTest {
    @Test fun `short range retains the most recent samples`() {
        assertEquals(DemoData.activity.takeLast(7), DemoData.activityFor(7))
    }
    @Test fun `long range caps at available history`() {
        assertEquals(DemoData.activity, DemoData.activityFor(30))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `invalid range is rejected`() { DemoData.activityFor(0) }
}
