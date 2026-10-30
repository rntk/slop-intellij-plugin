package dev.insight.diff

import com.intellij.diff.util.Side
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.InplaceButton
import dev.insight.callgraph.LineChange
import dev.insight.review.ui.NoteCanvas
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JComboBox

class CodeMoveCardTest : BasePlatformTestCase() {
    private val code = "fun validate(order: Order): Boolean {\n    val total = order.items.sumOf { it.price }\n    return total >= 0\n}\n"
    private val other = "fun format(order: Order): String {\n    val name = order.customer.name.uppercase()\n    return \"order for \$name\"\n}\n"
    private val kept = "class Service {\n    fun keep() = 1\n}\n"

    private fun files(): List<ReviewFile> {
        val before = kept + code + other
        return listOf(
            ReviewFile("Service.kt", "/Service.kt", null, "modified", reviewPatch(before, kept, listOf(LineChange(3, 11, 3, 3))), "Service.kt", before, kept),
            ReviewFile("Validator.kt", "/Validator.kt", null, "added", reviewPatch("", code, listOf(LineChange(0, 0, 0, 4))), "Validator.kt", "", code),
            ReviewFile("Format.kt", "/Format.kt", null, "added", reviewPatch("", other, listOf(LineChange(0, 0, 0, 4))), "Format.kt", "", other),
        )
    }

    fun testClickingTheChartShowsTheSourceAndDestinationDiffsBesideIt() {
        val files = files()
        var removed = 0
        val opened = mutableListOf<String>()
        val cards = DiffCanvasCards({ opened += it }, project, metricActions = MetricCardActions(removeMoves = { removed++ }))
        val canvas = NoteCanvas({}, {}, extension = cards)
        try {
            val moves = codeMoves(files)
            canvas.showNotes(cards, DiffCanvasContent(wordFiles = files, moves = moves))
            assertEquals("Moved code", canvas.tabs.getTitleAt(0))
            val chart = descendants(canvas).filterIsInstance<CodeMoveChart>().single()
            assertNull(chart.focus)
            // The largest move is shown first: the source file's diff above the destination's, at the moved lines.
            val first = detail(canvas)
            assertEquals(MoveSelection("Service.kt", "Validator.kt"), chart.selected)
            assertEquals(listOf("Service.kt", "Validator.kt"), first.shown.map { it.file.path })
            assertEquals(4..6, first.block?.fromLines)
            assertTrue(first.shown[0].editors(Side.LEFT).single().markupModel.allHighlighters.isNotEmpty())
            assertTrue(first.shown[1].editors(Side.RIGHT).single().markupModel.allHighlighters.isNotEmpty())

            val link = chart.links.single { it.selection.target == "Format.kt" }
            chart.dispatchEvent(MouseEvent(chart, MouseEvent.MOUSE_CLICKED, 0, 0, chart.preferredSize.width / 2, (link.from.y + link.to.y) / 2, 1, false, MouseEvent.BUTTON1))
            assertEquals(MoveSelection("Service.kt", "Format.kt"), chart.selected)
            val second = detail(canvas)
            assertEquals(listOf("Service.kt", "Format.kt"), second.shown.map { it.file.path })
            assertTrue("The replaced viewers are released", first.shown.isEmpty())
            assertEquals("In place, without another tab", 1, canvas.tabs.tabCount)

            // A rebuild keeps the chosen move and its viewers.
            canvas.showNotes(cards, DiffCanvasContent(wordFiles = files, moves = moves))
            assertSame(second, detail(canvas))
            assertEquals(MoveSelection("Service.kt", "Format.kt"), descendants(canvas).filterIsInstance<CodeMoveChart>().single().selected)

            @Suppress("UNCHECKED_CAST")
            (descendants(canvas).single { it.name == "move-focus" } as JComboBox<String?>).selectedItem = "Service.kt"
            val fan = descendants(canvas).filterIsInstance<CodeMoveChart>().single()
            assertEquals("Service.kt", fan.focus)
            val stayed = fan.links.single { it.to.label.endsWith("Stayed") }
            fan.dispatchEvent(MouseEvent(fan, MouseEvent.MOUSE_CLICKED, 0, 0, stayed.to.bounds.x + 2, stayed.to.y, 1, false, MouseEvent.BUTTON1))
            assertEquals(listOf("Service.kt"), detail(canvas).shown.map { it.file.path })

            descendants(canvas).filterIsInstance<InplaceButton>().single { it.toolTipText == "Remove moved code" }.doClick()
            assertEquals(1, removed)
            val last = detail(canvas)
            canvas.showNotes(cards, DiffCanvasContent(wordFiles = files, moves = CodeMoves(emptyList())))
            assertTrue(descendants(canvas).none { it is CodeMoveChart })
            assertTrue("Viewers are released when the moves change", last.shown.isEmpty())
        } finally {
            cards.dispose()
            canvas.dispose()
        }
    }

    fun testVisibilityIsClearedWithTheOtherViews() {
        val state = DiffReviewState()
        state.showMoves()
        assertTrue(state.movesVisible)
        state.clearNotes()
        assertFalse(state.movesVisible)
    }

    private fun detail(canvas: Container) = descendants(canvas).filterIsInstance<CodeMoveDetail>().single()

    private fun descendants(component: Container): List<java.awt.Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
