package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.JLabel

class NoteCanvasTest : BasePlatformTestCase() {
    private val note = DiffNote(listOf("src/a.kt"), "A short explanation.")

    fun testLongPathsKeepDiffButtonsVisibleAndClickableAtEveryZoom() {
        val paths = listOf(
            "src/main/kotlin/dev/insight/deeply/nested/module/implementation/LongFeatureService.kt",
            "VeryLongFeatureServiceName".repeat(12) + ".kt",
        )
        val requested = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, showDiffCard = { requested += it }, project = project)
            .apply { setSize(1400, 900) }
        try {
            val sources = paths.map { DiffSourceBlock(DiffSourceRange(it, 1, 1), "val a = 1") }
            canvas.showNotes(paths.map { DiffNote(listOf(it), "Explanation.") }, sources)
            for (zoom in listOf(0.25, 0.5, 1.0, 2.5)) {
                canvas.zoomAt(zoom, Point())
                layoutTree(canvas)
                val buttons = descendants(canvas).filterIsInstance<com.intellij.ui.InplaceButton>()
                    .filter { it.toolTipText == "Show diff on canvas" }
                assertEquals(4, buttons.size)
                requested.clear()
                buttons.forEach { button ->
                    assertTrue("Diff button must retain its width at $zoom", button.width >= button.preferredSize.width)
                    var child: Component = button
                    while (child.parent !== canvas) {
                        val parent = child.parent
                        assertTrue("Diff button must fit inside every ancestor at $zoom", parent.contains(
                            javax.swing.SwingUtilities.convertPoint(button, Point(button.width - 1, button.height - 1), parent)))
                        child = parent
                    }
                    button.doClick()
                }
                assertEquals(paths + paths, requested)
                opened.clear()
                descendants(canvas).filterIsInstance<com.intellij.ui.components.ActionLink>().forEach { it.doClick() }
                assertEquals(paths + paths, opened)
            }
        } finally { canvas.dispose() }
    }

    fun testDiffViewerModeChangesKeepCurrentCanvasZoom() {
        val file = ReviewFile("src/a.kt", "/project/src/a.kt", null, "modified", "-old\n+new\n", "1", "val a = 1\n", "val a = 2\n")
        val settings = com.intellij.diff.impl.DiffSettingsHolder.DiffSettings.getSettings()
        val originalToolOrder = settings.diffToolsOrder.toList()
        val canvas = NoteCanvas({}, {}, project = project).apply { setSize(1400, 900) }
        try {
            canvas.showNotes(emptyList(), diffs = listOf(file))
            val viewer = descendants(canvas).filterIsInstance<DiffFileEditor>().single()
            val switchTool = com.intellij.diff.impl.DiffRequestProcessor::class.java
                .getDeclaredMethod("switchToDiffTool", com.intellij.diff.DiffTool::class.java).apply { isAccessible = true }
            switchTool.invoke(viewer.panel, com.intellij.diff.tools.simple.SimpleDiffTool.INSTANCE)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            for (zoom in listOf(0.5, 1.5, 0.25)) {
                canvas.zoomAt(zoom, Point())
                val expectedFontSize = viewer.editors.first().colorsScheme.editorFontSize2D
                val cardBounds = canvas.components.single().bounds
                for (tool in listOf(com.intellij.diff.tools.fragmented.UnifiedDiffTool.INSTANCE, com.intellij.diff.tools.simple.SimpleDiffTool.INSTANCE)) {
                    val previousEditors = viewer.editors
                    switchTool.invoke(viewer.panel, tool)
                    com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                    val editors = viewer.editors
                    assertEquals(if (tool is com.intellij.diff.tools.fragmented.UnifiedDiffTool) 1 else 2, editors.size)
                    assertTrue(previousEditors.all { it.isDisposed })
                    editors.forEach { assertEquals(expectedFontSize, it.colorsScheme.editorFontSize2D, 0.01f) }
                    assertEquals(cardBounds, canvas.components.single().bounds)
                }
            }
        } finally {
            canvas.dispose()
            settings.diffToolsOrder = originalToolOrder
        }
    }

    fun testSourceCardsUseNativeViewersAndReleaseOnlyRemovedEditors() {
        val source = DiffSourceBlock(DiffSourceRange("src/a.kt", 8, 9), "val x = 1\n  println(x)")
        val canvas = NoteCanvas({}, {}, project = project).apply { setSize(1000, 700) }
        try {
            canvas.showNotes(listOf(note), listOf(source))
            assertEquals(2, canvas.componentCount)
            val viewer = descendants(canvas).filterIsInstance<DiffSourceEditor>().single().editor
            assertTrue(viewer.isViewer)
            assertEquals(source.text, viewer.document.text)
            assertEquals(8, viewer.gutterComponentEx.primaryLineNumberConverter.convert(viewer, 1))
            assertEquals(9, viewer.gutterComponentEx.primaryLineNumberConverter.convert(viewer, 2))
            assertTrue(viewer.settings.isLineNumbersShown)
            viewer.selectionModel.setSelection(0, 3)
            canvas.zoomAt(1.5, Point())
            assertSame(viewer, descendants(canvas).filterIsInstance<DiffSourceEditor>().single().editor)
            assertEquals("val", viewer.selectionModel.selectedText)
            canvas.showNotes(listOf(note), listOf(source, source.copy(range = source.range.copy(side = "before"))))
            val other = descendants(canvas).filterIsInstance<DiffSourceEditor>().single { it.editor !== viewer }.editor
            canvas.showNotes(listOf(note), listOf(source.copy(range = source.range.copy(side = "before"))))
            assertTrue(viewer.isDisposed)
            assertFalse(other.isDisposed)
            canvas.dispose()
            assertTrue(other.isDisposed)
        } finally { canvas.dispose() }
    }

    fun testDiffCardsOpenFromBlockButtonsUseNativeDiffViewersAndReleaseWhenRemoved() {
        val fileA = ReviewFile("src/a.kt", "/project/src/a.kt", null, "modified", "-old\n+new\n", "1", "val a = 1\n", "val a = 2\n")
        val fileB = ReviewFile("src/b.kt", "/project/src/b.kt", null, "added", "+added\n", "1", "", "val b = 3\n")
        val requested = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val canvas = NoteCanvas({}, {}, showDiffCard = { requested += it }, removeDiff = { removed += it }, project = project)
            .apply { setSize(1400, 900) }
        try {
            val source = DiffSourceBlock(DiffSourceRange("src/b.kt", 1, 1), "val b = 3")
            canvas.showNotes(listOf(note), listOf(source))
            val diffButtons = descendants(canvas).filterIsInstance<com.intellij.ui.InplaceButton>()
                .filter { it.toolTipText == "Show diff on canvas" }
            assertEquals(2, diffButtons.size)
            diffButtons.forEach { it.doClick() }
            assertEquals(listOf("src/a.kt", "src/b.kt"), requested)

            canvas.showNotes(listOf(note), listOf(source), listOf(fileA, fileB))
            assertEquals(4, canvas.componentCount)
            val diffViewers = descendants(canvas).filterIsInstance<DiffFileEditor>()
            assertEquals(2, diffViewers.size)
            val viewerA = diffViewers.single { it.file == fileA }
            val viewerB = diffViewers.single { it.file == fileB }
            assertTrue(viewerA.editors.isNotEmpty() && viewerA.editors.all { it.isViewer })
            assertEquals(setOf("val a = 1\n", "val a = 2\n"), viewerA.editors.map { it.document.text }.toSet())

            canvas.zoomAt(1.25, Point())
            assertSame(viewerA, descendants(canvas).filterIsInstance<DiffFileEditor>().single { it.file == fileA })

            val removeButtons = descendants(canvas).filterIsInstance<com.intellij.ui.InplaceButton>()
                .filter { it.toolTipText == "Remove diff block" }
            assertEquals(2, removeButtons.size)
            removeButtons.first().doClick()
            assertEquals(listOf("src/a.kt"), removed)

            canvas.showNotes(listOf(note), listOf(source), listOf(fileB))
            assertTrue(viewerA.isDisposed && viewerA.editors.all { it.isDisposed })
            assertFalse(viewerB.isDisposed)
            canvas.dispose()
            assertTrue(viewerB.isDisposed)
        } finally { canvas.dispose() }
    }

    fun testZoomKeepsCursorAnchorAndRefreshKeepsView() {
        val canvas = canvas()
        val before = canvas.components.single().bounds
        val anchor = Point(200, 150)
        canvas.zoomAt(2.0, anchor)
        val after = canvas.components.single().bounds
        assertEquals(2 * before.x - anchor.x, after.x)
        assertEquals(2 * before.y - anchor.y, after.y)
        assertEquals(2 * before.width, after.width)
        canvas.showNotes(listOf(note.copy(text = "Updated explanation.")))
        assertEquals(after.location, canvas.components.single().location)
        canvas.zoomAt(100.0)
        assertEquals(2.5, canvas.zoom)
        canvas.zoomAt(0.01)
        assertEquals(0.25, canvas.zoom)
    }

    fun testPanAndHeaderDragAtZoomPreserveWorldPositions() {
        val canvas = canvas()
        val initial = canvas.components.single().location
        canvas.zoomAt(2.0, Point())
        drag(canvas, MouseEvent.BUTTON1, Point(700, 500), Point(620, 440))
        val panned = canvas.components.single().location
        assertEquals(Point(initial.x * 2 - 80, initial.y * 2 - 60), panned)
        // The header label must also act as a drag handle.
        val header = descendants(canvas).filterIsInstance<JLabel>().first { it.text == "1 file · LLM interpretation" }
        drag(header, MouseEvent.BUTTON1, Point(10, 10), Point(70, 50))
        canvas.showNotes(listOf(note))
        assertEquals(Point(panned.x + 60, panned.y + 40), canvas.components.single().location)
        canvas.resetView()
        assertEquals(Point(initial.x + 30, initial.y + 20), canvas.components.single().location)
    }

    fun testMiddleDragPansWithoutMovingCardAndFitRecoversView() {
        val canvas = canvas()
        val initial = canvas.components.single().location
        val header = descendants(canvas).filterIsInstance<JLabel>().first { it.text == "1 file · LLM interpretation" }
        drag(header, MouseEvent.BUTTON2, Point(10, 10), Point(-1500, -1000))
        assertTrue(canvas.components.single().x < 0)
        canvas.fitView()
        assertTrue(canvas.bounds.contains(canvas.components.single().bounds))
        canvas.resetView()
        assertEquals(initial, canvas.components.single().location)
    }

    fun testSymbolCardsKeepIndependentPositionsAndDiagramBounds() {
        val symbol = DiffSymbol("src/a.kt", "/src/a.kt", "save", "Store.save", dev.insight.callgraph.SymbolKind.FUNCTION, 2, 10)
        val evidence = DiffSymbolEvidence(symbol, listOf(DiffSymbolLinks("Calls", (1..3).map {
            symbol.copy(name = "callee$it", qualifiedName = "Store.callee$it", offset = 10 + it)
        }, 20)))
        val semantic = note.copy(symbol = symbol.selector, evidence = evidence)
        val canvas = canvas()
        canvas.showNotes(listOf(note, semantic))
        assertEquals(2, canvas.componentCount)
        val locations = canvas.components.map { it.location }
        assertFalse(canvas.components[0].bounds.intersects(canvas.components[1].bounds))
        val diagram = descendants(canvas).filterIsInstance<DiffSymbolDiagram>().single()
        assertEquals(5, diagram.componentCount) // Root, three callees, one omitted count.
        assertTrue(descendants(diagram).filterIsInstance<JLabel>().any { it.text == "+17 calls not shown" })
        canvas.zoomAt(1.5, Point())
        canvas.showNotes(listOf(note, semantic.copy(text = "Updated")))
        canvas.resetView()
        assertEquals(locations, canvas.components.map { it.location })
        canvas.showNotes(listOf(semantic))
        assertEquals(locations[1], canvas.components.single().location)
    }

    fun testDiagramBoundsMixedRelationshipsAndDistinguishesOwners() {
        val symbol = DiffSymbol("src/a.kt", "/src/a.kt", "save", "Store.save", dev.insight.callgraph.SymbolKind.FUNCTION, 2, 10)
        fun neighbors(owner: String) = (1..3).map {
            symbol.copy(qualifiedName = "$owner$it.save", offset = 10 + it)
        }
        val diagram = DiffSymbolDiagram(DiffSymbolEvidence(symbol, listOf(
            DiffSymbolLinks("Callers in this file", neighbors("Caller"), 5, incoming = true),
            DiffSymbolLinks("Calls", neighbors("Target"), 4),
            DiffSymbolLinks("References", neighbors("Other"), 3),
        )), 360, 1.0, {})
        val links = descendants(diagram).filterIsInstance<com.intellij.ui.components.ActionLink>()
        assertEquals(7, links.size) // Root plus six neighbors across all relationship groups.
        assertTrue(links.any { it.text == "Caller1.save" })
        assertTrue(links.any { it.text == "Target1.save" })
        assertFalse(links.any { it.text.startsWith("Other") })
        val labels = descendants(diagram).filterIsInstance<JLabel>().map { it.text }
        assertTrue("+2 callers in this file not shown" in labels)
        assertTrue("+1 calls not shown" in labels)
        assertTrue("+3 references not shown" in labels)
        diagram.setSize(diagram.preferredSize)
        assertTrue(diagram.components.all { diagram.bounds.contains(it.bounds) })
    }

    fun testChangeFlowCardDrawsCallTreeWithRepeatsAndFocusesNotes() {
        val base = DiffSymbol("src/a.kt", "/src/a.kt", "a", "A.a", dev.insight.callgraph.SymbolKind.FUNCTION, 1, 0)
        fun step(name: String, vararg calls: String) = DiffNote(listOf("src/a.kt"), "$name does work. More detail.", "s:$name",
            DiffSymbolEvidence(base.copy(name = name, qualifiedName = "A.$name", offset = name.hashCode()), emptyList(),
                calls.map { "src/a.kt#A.$it:1@${it.hashCode()}" }.toSet()))
        // a → b → c → a is a cycle, a → c shares c, and d is unrelated.
        val notes = listOf(step("a", "b", "c", "external"), step("b", "c", "b"), step("c", "a"), step("d"))
        val flow = changeFlow(notes)
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2, "a" to 3, "c" to 1),
            flow.rows.map { it.note.evidence!!.symbol.name to it.depth })
        assertEquals(listOf(false, false, false, true, true), flow.rows.map { it.repeat })
        assertEquals(1, flow.unlinked)
        assertTrue(changeFlow(listOf(step("a"), note)).rows.isEmpty())

        val canvas = canvas()
        canvas.showNotes(notes)
        assertEquals(5, canvas.componentCount) // Flow card plus four notes.
        val diagram = descendants(canvas).filterIsInstance<ChangeFlowDiagram>().single()
        val links = descendants(diagram).filterIsInstance<com.intellij.ui.components.ActionLink>()
        assertEquals(listOf("A.a", "A.b", "A.c", "↺ A.a", "↺ A.c"), links.map { it.text })
        assertTrue(descendants(diagram).filterIsInstance<JLabel>().any { it.text == "b does work" })
        assertTrue(descendants(diagram).filterIsInstance<JLabel>().any { it.text == "1 other symbol notes have no calls to or from these" })
        diagram.setSize(diagram.preferredSize)
        assertTrue(diagram.components.all { diagram.bounds.contains(it.bounds) })

        links[2].doClick()
        val target = canvas.components.first { card -> descendants(card as Container).filterIsInstance<javax.swing.JTextArea>().any { it.text.startsWith("c ") } }
        assertEquals(canvas.width / 2.0, target.bounds.centerX, 1.0)
        canvas.showNotes(notes.take(3))
        assertEquals(4, canvas.componentCount)
        canvas.showNotes(listOf(notes[3]))
        assertEquals(1, canvas.componentCount) // The flow disappears once no calls connect notes.
    }

    private fun canvas() =NoteCanvas({}, {}).apply { setSize(1000, 700); showNotes(listOf(note)) }
    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }
    private fun drag(target: Component, button: Int, from: Point, to: Point) {
        val mask = if (button == MouseEvent.BUTTON1) InputEvent.BUTTON1_DOWN_MASK else InputEvent.BUTTON2_DOWN_MASK
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_PRESSED, 0, mask, from.x, from.y, 1, false, button))
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_DRAGGED, 1, mask, to.x, to.y, 0, false, MouseEvent.NOBUTTON))
        target.dispatchEvent(MouseEvent(target, MouseEvent.MOUSE_RELEASED, 2, 0, to.x, to.y, 1, false, button))
    }
}
