package dev.insight.context

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextActionsTest {
    private class Action(override val id: String, override val group: String, override val order: Int = 0, override val title: String = id) :
        ContextAction {
        override fun isAvailable(request: ContextRequest) = true
        override fun perform(request: ContextRequest) {}
    }

    @Test
    fun knownGroupsComeFirstThenOthersAlphabetically() {
        val actions = listOf(Action("copy", "Copy"), Action("zeta", "Zeta"), Action("chat", "Ask"), Action("beta", "Beta"),
            Action("graph", "Explore"), Action("diff", "Open", order = 2), Action("editor", "Open", order = 1),
            Action("canvas", "Open", order = 2, title = "Canvas"))
        val arranged = arrangeActions(actions)
        assertEquals(listOf("Open", "Explore", "Ask", "Copy", "Beta", "Zeta"), arranged.map { it.first })
        assertEquals(listOf("editor", "canvas", "diff"), arranged.first().second.map { it.id })
    }

    @Test
    fun noActionsNoSections() {
        assertEquals(emptyList<Pair<String, List<ContextAction>>>(), arrangeActions(emptyList()))
    }
}
