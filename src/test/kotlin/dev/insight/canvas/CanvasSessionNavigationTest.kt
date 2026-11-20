package dev.insight.canvas

import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.awt.Container
import java.awt.Dimension
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.KeyStroke

class CanvasSessionNavigationTest : LightJavaCodeInsightFixtureTestCase() {
    fun testUnfocusedWheelAndKeysScrollCanvasNotCards() = withLongPanel { panel, cards, viewport ->
        val (first, _) = cards
        val editor = first.sourcePane!!.editor
        assertFalse(panel.hasFocusedCard)
        assertNull(panel.focusedCard)
        assertTrue(panel.cardViews.values.none { it.focused })
        assertFalse(panel.canvas.actionMap.get("escape").isEnabled)

        wheel(editor.contentComponent)
        assertTrue("Vertical wheel on unfocused card scrolls canvas", viewport.viewPosition.y > 0)
        assertEquals(Point(0, 0), editor.scrollingModel.visibleArea.location)

        wheel(first, modifiers = InputEvent.SHIFT_DOWN_MASK)
        assertTrue("Shift+wheel on unfocused card scrolls canvas horizontally", viewport.viewPosition.x > 0)
        assertEquals(Point(0, 0), editor.scrollingModel.visibleArea.location)

        viewport.viewPosition = Point(0, 0)
        press(panel, "DOWN")
        press(panel, "RIGHT")
        assertTrue(viewport.viewPosition.y > 0)
        assertTrue(viewport.viewPosition.x > 0)
        assertEquals(Point(0, 0), editor.scrollingModel.visibleArea.location)
    }

    fun testFocusedCardWheelAndKeysOwnScrollableAxesIncludingEdges() = withLongPanel { panel, cards, viewport ->
        val (first, second) = cards
        val editor = first.sourcePane!!.editor
        val secondEditor = second.sourcePane!!.editor

        press(panel, "TAB")
        assertTrue(panel.hasFocusedCard)
        assertEquals(first.id, panel.focusedCard)
        assertTrue(first.focused)
        assertFalse(second.focused)

        val canvas = viewport.viewPosition
        fun stationary(stroke: String) {
            press(panel, stroke)
            assertEquals("$stroke must keep the canvas stationary", canvas, viewport.viewPosition)
        }

        stationary("DOWN")
        val line = editor.scrollingModel.visibleArea.y
        assertTrue(line > 0)
        stationary("UP")
        assertEquals(0, editor.scrollingModel.visibleArea.y)

        stationary("SPACE")
        val page = editor.scrollingModel.visibleArea.y
        assertTrue(page > line)
        stationary("shift SPACE")
        assertEquals(0, editor.scrollingModel.visibleArea.y)

        stationary("PAGE_DOWN")
        assertEquals(page, editor.scrollingModel.visibleArea.y)
        stationary("PAGE_UP")
        assertEquals(0, editor.scrollingModel.visibleArea.y)

        stationary("END")
        val bottom = editor.scrollingModel.visibleArea.y
        assertTrue(bottom > page)
        stationary("DOWN")
        stationary("SPACE")
        stationary("END")
        assertEquals(bottom, editor.scrollingModel.visibleArea.y)

        stationary("HOME")
        stationary("UP")
        stationary("PAGE_UP")
        assertEquals(0, editor.scrollingModel.visibleArea.y)

        stationary("RIGHT")
        val horizontal = editor.scrollingModel.visibleArea.x
        assertTrue(horizontal > 0)
        stationary("LEFT")
        assertEquals(0, editor.scrollingModel.visibleArea.x)
        stationary("shift RIGHT")
        assertEquals(horizontal, editor.scrollingModel.visibleArea.x)
        stationary("shift LEFT")
        assertEquals(0, editor.scrollingModel.visibleArea.x)
        stationary("shift DOWN")
        assertEquals(horizontal, editor.scrollingModel.visibleArea.x)
        stationary("shift UP")
        assertEquals(0, editor.scrollingModel.visibleArea.x)

        stationary("shift PAGE_DOWN")
        assertTrue(editor.scrollingModel.visibleArea.x > horizontal)
        stationary("shift PAGE_UP")
        assertEquals(0, editor.scrollingModel.visibleArea.x)

        stationary("ctrl END")
        val end = editor.scrollingModel.visibleArea.location
        assertEquals(bottom, end.y)
        assertTrue(end.x > horizontal)
        stationary("RIGHT")
        assertEquals(end, editor.scrollingModel.visibleArea.location)
        stationary("ctrl HOME")
        assertEquals(Point(0, 0), editor.scrollingModel.visibleArea.location)

        // Wheel over another card or empty space follows the focused card and stops at its edges.
        wheel(second)
        assertTrue(editor.scrollingModel.visibleArea.y > 0)
        wheel(panel.canvas, modifiers = InputEvent.SHIFT_DOWN_MASK)
        assertTrue(editor.scrollingModel.visibleArea.x > 0)
        assertEquals(Point(0, 0), secondEditor.scrollingModel.visibleArea.location)
        assertEquals(canvas, viewport.viewPosition)

        press(panel, "ctrl END")
        assertEquals(end, editor.scrollingModel.visibleArea.location)
        wheel(second)
        wheel(panel.canvas, modifiers = InputEvent.SHIFT_DOWN_MASK)
        assertEquals(end, editor.scrollingModel.visibleArea.location)
        assertEquals(canvas, viewport.viewPosition)
    }

    fun testHorizontalOnlyAndVerticalOnlyCardsRouteOtherAxisToCanvas() {
        withLongPanel { panel, _, viewport ->
            val root = panel.canvas.components.single { it.name == "canvas-root" } as JComponent
            val rootEditor = root.components.filterIsInstance<CanvasSourceSection>().single()
                .let { (it.body as Container).components.single() as CanvasSourcePane }.editor
            assertTrue(canScrollCanvasViewer(rootEditor, horizontal = true))
            assertFalse(canScrollCanvasViewer(rootEditor, horizontal = false))

            // Focus the root card (Shift+Tab from unfocused or Tab through cards).
            clickAt(panel.canvas, Point(root.x + 20, root.y + 8))
            assertTrue(panel.hasFocusedCard)
            assertNull(panel.focusedCard)

            viewport.viewPosition = Point(0, 0)
            press(panel, "DOWN")
            val afterKey = viewport.viewPosition
            assertTrue(afterKey.y > 0)
            assertEquals(0, rootEditor.scrollingModel.visibleArea.y)
            wheel(root)
            assertTrue(viewport.viewPosition.y > afterKey.y)
            assertEquals(0, rootEditor.scrollingModel.visibleArea.y)

            val canvas = viewport.viewPosition
            press(panel, "RIGHT")
            val afterRight = rootEditor.scrollingModel.visibleArea.x
            assertTrue(afterRight > 0)
            wheel(panel.canvas, modifiers = InputEvent.SHIFT_DOWN_MASK)
            assertTrue(rootEditor.scrollingModel.visibleArea.x > afterRight)
            assertEquals(canvas, viewport.viewPosition)

            press(panel, "ctrl END")
            val end = rootEditor.scrollingModel.visibleArea.location
            val canvasEnd = viewport.viewPosition
            assertTrue(canvasEnd.y > canvas.y)
            assertEquals(canvas.x, canvasEnd.x)
            wheel(root, modifiers = InputEvent.SHIFT_DOWN_MASK)
            press(panel, "RIGHT")
            assertEquals(end, rootEditor.scrollingModel.visibleArea.location)
            assertEquals(canvasEnd, viewport.viewPosition)
        }

        val shortText = "class ShortFile {\n" + (1..100).joinToString("\n") { "    // line $it" } + "\n}"
        withPanel(shortText, cardCount = 1) { panel, cards, viewport ->
            val card = cards.single()
            val editor = card.sourcePane!!.editor
            assertFalse(canScrollCanvasViewer(editor, horizontal = true))
            assertTrue(canScrollCanvasViewer(editor, horizontal = false))

            press(panel, "TAB")
            assertEquals(card.id, panel.focusedCard)
            viewport.viewPosition = Point(0, 0)

            press(panel, "RIGHT")
            val afterKey = viewport.viewPosition
            assertTrue(afterKey.x > 0)
            wheel(card, modifiers = InputEvent.SHIFT_DOWN_MASK)
            assertTrue(viewport.viewPosition.x > afterKey.x)
            assertEquals(0, editor.scrollingModel.visibleArea.x)

            val canvas = viewport.viewPosition
            press(panel, "END")
            val end = editor.scrollingModel.visibleArea.location
            assertTrue(end.y > 0)
            wheel(card)
            press(panel, "DOWN")
            assertEquals(end, editor.scrollingModel.visibleArea.location)
            assertEquals(canvas, viewport.viewPosition)
        }
    }

    fun testClickCardHeaderEmptySpaceEscapeAndCloseManageFocus() {
        val text = "class Flow {\n" +
            "    void entry() { helper(); }\n" +
            "    void helper() {\n" +
            (1..100).joinToString("\n") { "        // line $it " + "long code ".repeat(60) } +
            "\n    }\n}"
        withPanel(text, cardCount = 2) { panel, cards, viewport ->
            val (first, second) = cards
            val root = panel.canvas.components.single { it.name == "canvas-root" } as JComponent
            assertFalse(panel.hasFocusedCard)
            assertFalse(panel.canvas.actionMap.get("escape").isEnabled)

            // Clicking a passive card header focuses the card and updates the trail.
            clickAt(panel.canvas, Point(second.x + second.header.x + second.header.width / 2,
                second.y + second.header.y + second.header.height / 2))
            assertTrue(panel.hasFocusedCard)
            assertEquals(second.id, panel.focusedCard)
            assertTrue(second.focused)
            assertFalse(first.focused)
            assertEquals(second.card.title, panel.trailTitles.last())
            assertTrue(panel.canvas.actionMap.get("escape").isEnabled)

            val canvas = viewport.viewPosition
            press(panel, "DOWN")
            assertTrue(second.sourcePane!!.editor.scrollingModel.visibleArea.y > 0)
            assertEquals(canvas, viewport.viewPosition)

            // Clicking empty canvas space clears card focus and returns wheel/keys to the canvas.
            clickAt(panel.canvas, Point(2, 2))
            assertFalse(panel.hasFocusedCard)
            assertNull(panel.focusedCard)
            assertFalse(second.focused)
            assertFalse(panel.canvas.actionMap.get("escape").isEnabled)
            val beforeWheel = second.sourcePane!!.editor.scrollingModel.visibleArea.location
            wheel(second)
            assertEquals(beforeWheel, second.sourcePane!!.editor.scrollingModel.visibleArea.location)
            assertTrue(viewport.viewPosition.y > canvas.y)

            // Clicking the root file focuses root while preserving the existing trail.
            clickAt(panel.canvas, Point(root.x + 20, root.y + 8))
            assertTrue(panel.hasFocusedCard)
            assertNull(panel.focusedCard)
            assertEquals(second.card.title, panel.trailTitles.last())
            assertTrue(panel.canvas.actionMap.get("escape").isEnabled)

            // Escape restores canvas navigation and disables escape when already unfocused.
            press(panel, "ESCAPE")
            assertFalse(panel.hasFocusedCard)
            assertFalse(panel.canvas.actionMap.get("escape").isEnabled)

            // Opening a symbol card focuses it; closing the focused card exits focus cleanly.
            val (link, target) = panel.controller.link(panel.controller.source(null)!!, "entry")
            val job = panel.openSymbol(null, link, target)
            PlatformTestUtil.waitWithEventsDispatching("Symbol card should open", { job.isCompleted }, 30)
            val entry = panel.model.cards.last()
            assertTrue(panel.hasFocusedCard)
            assertEquals(entry.id, panel.focusedCard)
            assertTrue(panel.cardViews.getValue(entry.id).focused)
            panel.close(entry.id)
            assertFalse(panel.hasFocusedCard)
            assertNull(panel.focusedCard)

            // Ctrl/Cmd zoom keys step smoothly and snap back to 100% across 1.0.
            press(panel, "ctrl EQUALS")
            assertTrue(panel.zoom > 1.0)
            press(panel, "ctrl MINUS")
            assertEquals(1.0, panel.zoom, 0.0001)
            press(panel, "ctrl EQUALS")
            press(panel, "ctrl EQUALS")
            assertTrue(panel.zoom > 1.2)
            press(panel, "ctrl 0")
            assertEquals(1.0, panel.zoom, 0.0001)
        }
    }

    fun testClosingCardMovesCanvasToParentAndThenRoot() = withLongPanel { panel, cards, viewport ->
        val parent = cards.first()
        val child = panel.controller.openSelection(parent.id, 0, parent.card.text.length)!!
        val childView = panel.cardViews.getValue(child.id)
        val root = panel.canvas.components.single { it.name == "canvas-root" }
        repeat(2) { relayout(panel) }
        viewport.viewPosition = childView.location
        assertFalse(viewport.viewRect.contains(parent.location))

        panel.close(child.id)

        assertTrue("Closing the child should bring the parent into view", viewport.viewRect.contains(parent.location))
        assertFalse(viewport.viewRect.contains(root.location))

        panel.close(parent.id)

        assertTrue("Closing a card opened from the root should bring the root into view", viewport.viewRect.contains(root.location))
    }

    fun testPanningPastLeftAndTopEdgesGrowsCanvas() = withLongPanel { panel, cards, viewport ->
        val root = panel.canvas.components.single { it.name == "canvas-root" }
        fun onScreen() = Point(root.x - viewport.viewPosition.x, root.y - viewport.viewPosition.y)
        val before = onScreen()

        press(panel, "LEFT")
        press(panel, "UP")

        val after = onScreen()
        assertTrue("Stepping left at the edge moves the content right: $before -> $after", after.x > before.x)
        assertTrue("Stepping up at the edge moves the content down: $before -> $after", after.y > before.y)
        assertTrue("The canvas grows before its old left edge", root.x > before.x)

        val card = cards.first().sourcePane!!.editor.contentComponent
        viewport.viewPosition = Point(0, viewport.viewPosition.y)
        val atEdge = onScreen()
        wheel(card, rotation = -1, modifiers = InputEvent.SHIFT_DOWN_MASK)
        assertTrue("The wheel goes on past the left edge", onScreen().x > atEdge.x)
    }

    fun testAltDragOverCardSourcePansCanvas() = withLongPanel { panel, cards, viewport ->
        val editor = cards.first().sourcePane!!.editor
        val target = editor.contentComponent
        val pressed = javax.swing.SwingUtilities.convertPoint(target, Point(20, 20), panel.canvas)
        viewport.viewPosition = Point(pressed.x - 300, pressed.y - 300)
        val start = viewport.viewPosition
        val queue = com.intellij.ide.IdeEventQueue.getInstance()
        fun mouse(id: Int, x: Int, y: Int, mask: Int) = MouseEvent(target, id, 0L, mask, 20 + x, 20 + y, 500 + x, 500 + y,
            1, false, MouseEvent.BUTTON1)
        val alt = InputEvent.ALT_DOWN_MASK or InputEvent.BUTTON1_DOWN_MASK
        queue.dispatchEvent(mouse(MouseEvent.MOUSE_PRESSED, 0, 0, alt))
        queue.dispatchEvent(mouse(MouseEvent.MOUSE_DRAGGED, -60, -40, alt))
        queue.dispatchEvent(mouse(MouseEvent.MOUSE_RELEASED, -60, -40, InputEvent.ALT_DOWN_MASK))

        assertEquals("Alt + drag moves the canvas with the pointer", Point(start.x + 60, start.y + 40), viewport.viewPosition)
        assertFalse("Alt + drag selects no text", editor.selectionModel.hasSelection())
    }

    private fun withLongPanel(test:(CanvasSessionPanel, List<CanvasCardView>, JViewport) -> Unit) {
        val text = "class LongFile {\n" + (1..100).joinToString("\n") {
            "    // line $it " + "long code ".repeat(60)
        } + "\n}"
        withPanel(text, cardCount = 2, test = test)
    }

    private fun withPanel(text: String, cardCount: Int, test: (CanvasSessionPanel, List<CanvasCardView>, JViewport) -> Unit) {
        val file = myFixture.configureByText("LongFile.java", text)
        val session = CanvasSession.new("LongFile.java")
        val services = CanvasSessionServices(null, { error("No completion requested") }) { _, _ -> error("No chat requested") }
        val panel = CanvasSessionPanel(project, sessionFile(file, session), session, services) {}
        try {
            panel.setSize(1200, 800)
            PlatformTestUtil.waitWithEventsDispatching("The root file should be shown", { panel.ready }, 30)
            val cards = (1..cardCount).map { index ->
                val start = if (index == 1) 0 else text.indexOf('\n') + 1
                val card = panel.controller.openSelection(null, start, text.length)!!
                panel.cardViews.getValue(card.id)
            }
            repeat(2) { relayout(panel) }
            val viewport = panel.canvas.parent as JViewport
            // Supply scrollable canvas space explicitly in headless tests so canvas scroll assertions are unbounded by panel timing.
            val viewSize = viewport.viewSize
            viewport.viewSize = Dimension(maxOf(viewSize.width, viewport.width + 400), maxOf(viewSize.height, viewport.height + 400))
            viewport.viewPosition = Point(0, 0)
            test(panel, cards, viewport)
        } finally {
            Disposer.dispose(panel)
        }
    }

    private fun <T> background(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Canvas work should finish", { task.isCompleted }, 30)
            return runBlocking { task.await() }.getOrThrow()
        } finally {
            task.cancel()
        }
    }

    private fun sessionFile(file: PsiFile, session: CanvasSession? = null): CanvasSessionFile {
        val opened = background { openCanvasSessionFile(project, file.virtualFile, store = null) }
        assertNotNull("The file must be shown", opened)
        session?.let { opened!!.session = it }
        return opened!!
    }

    private fun CanvasSessionController.link(source: CanvasSource, name: String): Pair<CanvasLink, Int> {
        for (link in source.links) {
            val target = link.targets.firstOrNull { node(it)?.name?.substringBefore('(') == name } ?: continue
            return link to target
        }
        error("No link to $name")
    }

    private fun relayout(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::relayout)
    }

    private fun press(panel: CanvasSessionPanel, stroke: String) {
        val key = panel.canvas.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(stroke))
        assertNotNull("Missing key binding for $stroke", key)
        panel.canvas.actionMap.get(key).actionPerformed(ActionEvent(panel.canvas, 0, stroke))
    }

    private fun wheel(target: JComponent, rotation: Int = 1, modifiers: Int = 0) {
        target.dispatchEvent(MouseWheelEvent(target, MouseEvent.MOUSE_WHEEL, 0L, modifiers, 10, 10, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation))
    }

    private fun clickAt(target: JComponent, point: Point) {
        for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            target.dispatchEvent(MouseEvent(target, id, 0L, InputEvent.BUTTON1_DOWN_MASK, point.x, point.y, 1, false, MouseEvent.BUTTON1))
        }
    }
}
