package dev.insight.board.ui

import com.intellij.openapi.util.Disposer
import dev.insight.board.Annotation
import dev.insight.board.Board
import dev.insight.board.BoardAnchor
import dev.insight.board.BoardEdge
import dev.insight.board.BoardModel
import dev.insight.board.Cell
import dev.insight.board.ChainBlock
import dev.insight.board.ChainStep
import dev.insight.board.CodeBlock
import dev.insight.board.CodeKind
import dev.insight.board.GroupBlock
import dev.insight.board.HeadingBlock
import dev.insight.board.Severity
import dev.insight.board.Step
import dev.insight.board.StepsBlock
import dev.insight.board.SymbolBlock
import dev.insight.board.TableBlock
import dev.insight.board.TextBlock
import dev.insight.board.Tone
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JScrollPane
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardPanelTest {
    private class Recorded : BoardPanelActions {
        val navigated = mutableListOf<BoardAnchor>()
        val asked = mutableListOf<String>()
        val removed = mutableListOf<String>()
        override fun navigate(anchor: BoardAnchor) { navigated += anchor }
        override fun askAbout(blockId: String) { asked += blockId }
        override fun remove(blockId: String) { removed += blockId }
    }

    private val code = BoardAnchor("src/Service.kt", 10, 13)
    private val actions = Recorded()
    private val panels = mutableListOf<BoardPanel>()

    @After
    fun dispose() = panels.forEach { Disposer.dispose(it) }

    private fun model(): BoardModel {
        val model = BoardModel(Board.new("Explainer", now = 0), clock = { 0 })
        model.add(HeadingBlock("h", "How a request is handled"))
        model.add(TextBlock("t", "The service validates input first.\n\nThen it stores the result.", title = "Why", tone = Tone.INSIGHT))
        model.add(TextBlock("p", "Plain explanation that should wrap over several lines when the column is narrow enough."))
        model.add(CodeBlock("c", code, "fun handle() {\n    validate()\n    store()\n}", annotations = listOf(
            Annotation(11, "Throws on bad input", Severity.WARNING))))
        model.add(CodeBlock("d", BoardAnchor("src/Store.kt", 5, 6), "@@ -5,2 +5,2 @@\n-old()\n+new()\n keep()", kind = CodeKind.DIFF))
        model.add(SymbolBlock("s", 1, "Service.handle", meta = "Service.kt:10", summary = "Entry point of requests.", anchor = code))
        model.add(ChainBlock("ch", listOf(ChainStep(0, "Controller.post", BoardAnchor("src/Controller.kt", 3, 3)),
            ChainStep(1, "Service.handle", code), ChainStep(2, "Store.save")), complete = false))
        model.add(StepsBlock("st", listOf(Step("Validate", BoardAnchor("src/Service.kt", 11, 11)), Step("Store"))))
        model.add(TableBlock("tb", listOf("Name", "Where"), listOf(listOf(Cell("handle"), Cell("Service.kt", code)))))
        model.add(GroupBlock("g", listOf("s", "st"), title = "Service", tone = Tone.NOTE))
        model.connect(BoardEdge("c", "ch", "calls"))
        return model
    }

    private fun panel(model: BoardModel = model()) = BoardPanel(model, actions).also {
        panels += it
        it.setSize(1400, 900)
    }

    private fun BoardPanel.layoutNow() {
        doLayout()
        fun layOut(c: Component) { if (c is Container) { c.doLayout(); c.components.forEach(::layOut) } }
        components.forEach(::layOut)
    }

    private fun descendants(c: Component): List<Component> =
        if (c is Container) c.components.flatMap { listOf(it) + descendants(it) } else emptyList()

    private fun BoardPanel.named(name: String): JComponent =
        descendants(this).filterIsInstance<JComponent>().single { it.name == name }

    private fun click(c: Component, x: Int = 2, y: Int = 2) {
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
    }

    @Test
    fun everyBlockGetsPlacedCard() {
        val model = model()
        val panel = panel(model)
        panel.layoutNow()
        assertEquals(model.board.blocks.map { it.id }, panel.blockComponents.keys.toList())
        for ((id, card) in panel.blockComponents) {
            assertTrue("$id is shown", card.isVisible)
            assertTrue("$id has bounds ${card.bounds}", card.width > 0 && card.height > 0)
        }
        val group = panel.blockComponents.getValue("g").bounds
        for (child in listOf("s", "st")) assertTrue("$child inside its group", group.contains(panel.blockComponents.getValue(child).bounds))
        // Group frames paint under the cards they hold.
        assertTrue(panel.getComponentZOrder(panel.blockComponents.getValue("g")) > panel.getComponentZOrder(panel.blockComponents.getValue("s")))
        assertTrue(panel.preferredSize.height >= panel.blockComponents.values.maxOf { it.y + it.height })

        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try { panel.paint(g) } finally { g.dispose() }
    }

    @Test
    fun cardsCaptionEvidenceAndInterpretation() {
        val panel = panel()
        fun caption(id: String) = (panel.named("board-caption-$id") as JLabel).text
        assertEquals("evidence", caption("c"))
        assertEquals("evidence", caption("ch"))
        assertEquals("model interpretation", caption("p"))
        assertEquals("interpretation", caption("t"))
        assertEquals("INSIGHT", (panel.named("board-tone-t") as JLabel).text)
    }

    @Test
    fun modelChangesUpdateTheCards() {
        val model = model()
        val panel = panel(model)
        model.add(TextBlock("n", "New"))
        assertTrue("n" in panel.blockComponents)
        model.remove("t")
        assertFalse("t" in panel.blockComponents)
        val before = panel.blockComponents.getValue("p")
        val unchanged = panel.blockComponents.getValue("c")
        model.update(TextBlock("p", "Changed"))
        assertTrue(before !== panel.blockComponents.getValue("p"))
        assertTrue(unchanged === panel.blockComponents.getValue("c"))
        panel.layoutNow()
        assertTrue(panel.blockComponents.getValue("n").width > 0)
        model.reset(Board.new("Empty", now = 0))
        assertTrue(panel.blockComponents.isEmpty())
        assertEquals(0, panel.componentCount)
    }

    @Test
    fun focusAndClicksSelect() {
        val model = model()
        val panel = panel(model)
        panel.layoutNow()
        assertNull(panel.selectedBlockId)
        panel.focus("c")
        assertEquals("c", panel.selectedBlockId)
        click(panel.named("board-body-tb-0"))
        assertEquals("tb", panel.selectedBlockId)
        model.remove("tb")
        assertNull(panel.selectedBlockId)
    }

    @Test
    fun headerLinksAndActionsCallTheHost() {
        val panel = panel()
        panel.layoutNow()
        click(panel.named("board-anchor-c"))
        assertEquals(listOf(code), actions.navigated)
        assertEquals("c", panel.selectedBlockId)
        click(panel.named("board-open-ch"))
        assertEquals(BoardAnchor("src/Controller.kt", 3, 3), actions.navigated.last())
        click(panel.named("board-ask-t"))
        click(panel.named("board-remove-p"))
        assertEquals(listOf("t"), actions.asked)
        assertEquals(listOf("p"), actions.removed)
        // Prose has no code to open.
        assertTrue(descendants(panel).none { it.name == "board-open-p" })
    }

    @Test
    fun codeLinesAndChipsNavigate() {
        val panel = panel()
        panel.layoutNow()
        val lines = panel.named("board-body-c-0") as CodeView
        assertEquals(listOf(10, 11, 12, 13), lines.rows.map { it.number })
        assertTrue(lines.clickAt(lines.width / 2, lines.height / 8))
        assertEquals(BoardAnchor("src/Service.kt", 10, 10), actions.navigated.last())
        val chain = panel.named("board-body-ch-0") as ChainView
        assertTrue(chain.clickAt(chain.width.coerceAtMost(20), 4))
        assertEquals(BoardAnchor("src/Controller.kt", 3, 3), actions.navigated.last())
    }

    @Test
    fun zoomIsClampedAndScalesTheBoard() {
        val panel = panel()
        panel.layoutNow()
        val base = panel.preferredSize
        panel.zoom = 5.0
        assertEquals(BoardPanel.MAX_ZOOM, panel.zoom, 0.0)
        panel.zoom = 0.01
        assertEquals(BoardPanel.MIN_ZOOM, panel.zoom, 0.0)
        assertTrue(panel.preferredSize.height < base.height)
        panel.zoom = 1.0
        val scroll = JScrollPane(panel)
        scroll.setSize(1000, 400)
        scroll.doLayout()
        scroll.viewport.doLayout()
        panel.zoomToFit()
        assertTrue(panel.zoom < 1.0)
        val fitted = panel.preferredSize
        assertTrue("$fitted fits", fitted.height <= 400 || panel.zoom == BoardPanel.MIN_ZOOM)
        assertNotNull(panel.blockComponents["c"])
    }
}
