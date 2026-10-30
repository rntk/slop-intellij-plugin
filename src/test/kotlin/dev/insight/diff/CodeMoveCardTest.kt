package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.InplaceButton
import dev.insight.callgraph.LineChange
import dev.insight.review.ui.NoteCanvas
import java.awt.Container
import javax.swing.JComboBox

class CodeMoveCardTest : BasePlatformTestCase() {
    private val code = "fun validate(order: Order): Boolean {\n    val total = order.items.sumOf { it.price }\n    return total >= 0\n}\n"

    fun testCardShowsAllFilesThenOneFileAndRemovesItself() {
        val files = listOf(
            ReviewFile("Old.kt", "/Old.kt", null, "deleted", reviewPatch(code, "", listOf(LineChange(0, 4, 0, 0))), "Old.kt", code, ""),
            ReviewFile("New.kt", "/New.kt", null, "added", reviewPatch("", code, listOf(LineChange(0, 0, 0, 4))), "New.kt", "", code),
        )
        var removed = 0
        val cards = DiffCanvasCards({}, metricActions = MetricCardActions(removeMoves = { removed++ }))
        val canvas = NoteCanvas({}, {}, extension = cards)
        try {
            canvas.showNotes(cards, DiffCanvasContent(wordFiles = files, moves = codeMoves(files)))
            assertEquals("Moved code", canvas.tabs.getTitleAt(0))
            assertNull(descendants(canvas).filterIsInstance<CodeMoveChart>().single().focus)
            @Suppress("UNCHECKED_CAST")
            (descendants(canvas).single { it.name == "move-focus" } as JComboBox<String?>).selectedItem = "Old.kt"
            val fan = descendants(canvas).filterIsInstance<CodeMoveChart>().single()
            assertEquals("Old.kt", fan.focus)
            assertEquals(listOf("100%  New.kt"), fan.nodes.filterNot { it.left }.map { it.label })
            descendants(canvas).filterIsInstance<InplaceButton>().single { it.toolTipText == "Remove moved code" }.doClick()
            assertEquals(1, removed)
            canvas.showNotes(cards, DiffCanvasContent(wordFiles = files, moves = CodeMoves(emptyList())))
            assertTrue(descendants(canvas).none { it is CodeMoveChart })
        } finally { canvas.dispose() }
    }

    fun testVisibilityIsClearedWithTheOtherViews() {
        val state = DiffReviewState()
        state.showMoves()
        assertTrue(state.movesVisible)
        state.clearNotes()
        assertFalse(state.movesVisible)
    }

    private fun descendants(component: Container): List<java.awt.Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
