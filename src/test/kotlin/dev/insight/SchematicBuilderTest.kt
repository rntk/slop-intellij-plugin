package dev.insight

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*
import dev.insight.enrichment.liftCalls

class SchematicBuilderTest : LightJavaCodeInsightFixtureTestCase() {
    private val source = """
        class Flow {
            private final Store store = Store.open();

            void entry(int kind) {
                int x = 1;
                if (kind > 0) {
                    check(
                        kind);
                }
                store.save(x);
            }

            void check(int kind) {}
        }

        class Store {
            static Store open() { return new Store(); }
            void save(int x) {}
        }
    """.trimIndent()

    private fun build(text: String = source, name: (CallGraphNode) -> Boolean): Pair<CallGraphResult, Schematic> {
        myFixture.configureByText("Flow.java", text)
        val result = ProjectGraphAnalyzer.build(project)
        val calls = liftCalls(result.graph).edges.filter { it.kind == RelationKind.CALL }.groupBy { it.caller }
        val id = result.graph.nodes.single(name).id
        return result to SchematicBuilder.build(result, result.graph.hierarchy(), calls, id)!!
    }

    private fun CallGraphResult.name(id: Int) = graph.nodes[id].name.substringBefore('(')

    fun `test a file folds its methods and shows field initializers calling project code as blocks`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        assertEquals(source.lines(), schematic.lines)
        val blocks = schematic.rows.filterIsInstance<BlockRow>()
        assertEquals(listOf("open", "entry", "check", "open", "save"), blocks.flatMap { it.targets }.map { result.name(it) })
        val entry = blocks.single { it.declared?.let { id -> result.name(id) } == "entry" }
        assertEquals("    void entry(int kind) {", schematic.text(entry.headline))
        assertEquals(source.lines().indexOfFirst { it.trim() == "}" && it.startsWith("    }") }, entry.last)
        // Every line is covered once, in order.
        assertEquals((0 until source.lines().size).toList(), schematic.rows.flatMap { (it.first..it.last).toList() })
    }

    fun `test the editor shows the file and unfolds a summary into the next level`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            fun labels(component: java.awt.Container): List<javax.swing.JLabel> = component.components.flatMap {
                (if (it is javax.swing.JLabel && it.isShowingInTree()) listOf(it) else emptyList()) +
                    ((it as? java.awt.Container)?.let(::labels) ?: emptyList())
            }
            fun cards() = labels(editor.component).filter { it.text.orEmpty().contains("<code>") }
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", {
                descendants(editor.component).count { it.javaClass.simpleName == "SummaryCard" } == 5
            }, 10)
            assertTrue("Cards start hidden", cards().isEmpty())
            val canvas = (editor.component as SchematicPanel).canvas
            press(canvas, "TAB") // Store.open field initializer
            press(canvas, "TAB") // entry declaration
            press(canvas, "ENTER")
            assertEquals(1, cards().size)
            val entry = cards().first { it.text.contains("<code>entry(") }
            entry.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(entry, java.awt.event.MouseEvent.MOUSE_CLICKED,
                0, 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1)) }
            // The unfolded method loads its own calls, whose cards still start hidden.
            PlatformTestUtil.waitWithEventsDispatching("The method should unfold", {
                descendants(editor.component).count { it.javaClass.simpleName == "SummaryCard" } == 7
            }, 10)
            assertEquals(1, cards().size)
            fun descendants(component: java.awt.Container): List<java.awt.Component> = component.components.flatMap {
                listOf(it) + ((it as? java.awt.Container)?.let(::descendants) ?: emptyList())
            }
            val header = entry.parent
            val toggle = descendants(header).filterIsInstance<com.intellij.ui.components.ActionLink>()
                .single { it.text == "Compact" }
            val card = header.parent
            val sourceRows = descendants(card).filterIsInstance<javax.swing.JTextArea>()
            assertTrue(sourceRows.any { it.text.contains("int x = 1;") })
            val fullHeight = card.preferredSize.height
            val level = sourceRows.first().parent
            val firstBlock = level.components.first { it.javaClass.simpleName == "BlockView" }
            level.doLayout()
            val fullBlockY = firstBlock.y
            toggle.doClick()
            assertEquals("Full", toggle.text)
            assertTrue(sourceRows.all { !it.isVisible })
            assertEquals(1, cards().size)
            level.doLayout()
            assertTrue("Compact layout should omit source row heights", firstBlock.y < fullBlockY)
            assertEquals(0, firstBlock.y)

            // Folding and reopening preserves compact mode and the same loaded blocks.
            fun clickEntry() = entry.mouseListeners.forEach {
                it.mouseClicked(java.awt.event.MouseEvent(entry, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    0, 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1))
            }
            clickEntry()
            assertFalse(toggle.isVisible)
            assertEquals(1, cards().size)
            clickEntry()
            assertTrue(toggle.isVisible)
            assertEquals("Full", toggle.text)
            assertEquals(1, cards().size)
            assertTrue(sourceRows.all { !it.isVisible })
            toggle.doClick()
            assertEquals("Compact", toggle.text)
            assertTrue(sourceRows.all { it.isVisible })
            assertEquals(fullHeight, card.preferredSize.height)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    fun `test the canvas zooms with keys and scrolls with page and end keys`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            assertSame(canvas, editor.preferredFocusedComponent)
            fun code() = generateSequence(listOf<java.awt.Component>(canvas)) { level ->
                level.flatMap { (it as? java.awt.Container)?.components.orEmpty().toList() }.ifEmpty { null }
            }.flatten().filterIsInstance<javax.swing.JTextArea>().firstOrNull()
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { code() != null }, 10)
            fun press(stroke: String) {
                val key = canvas.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .get(javax.swing.KeyStroke.getKeyStroke(stroke))
                canvas.actionMap.get(key).actionPerformed(java.awt.event.ActionEvent(canvas, 0, stroke))
            }
            val fontSize = code()!!.font.size2D
            val width = canvas.preferredSize.width
            press("typed +")
            assertEquals(1.2, panel.zoom, 1e-9)
            assertEquals(fontSize * 1.2f, code()!!.font.size2D, 0.01f)
            assertTrue(canvas.preferredSize.width > width)
            press("typed -")
            press("typed -")
            assertTrue(panel.zoom < 1.0)
            press("typed 0")
            assertEquals(1.0, panel.zoom, 1e-9)

            // A small viewport, so the source does not fit and can be scrolled.
            panel.setSize(300, 200)
            fun layOut(component: java.awt.Component) {
                component.doLayout()
                (component as? java.awt.Container)?.components?.forEach(::layOut)
            }
            layOut(panel)
            val viewport = canvas.parent as javax.swing.JViewport
            assertTrue("${canvas.size} in ${viewport.size}", canvas.height > viewport.height && viewport.height > 0)
            press("PAGE_DOWN")
            assertTrue(viewport.viewPosition.y > 0)
            press("END")
            assertEquals(canvas.height - viewport.height, viewport.viewPosition.y)
            press("HOME")
            assertEquals(java.awt.Point(0, 0), viewport.viewPosition)
            press("shift TAB")
            PlatformTestUtil.waitWithEventsDispatching("The focused block should scroll into view", { viewport.viewPosition.y > 0 }, 5)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    /** Visible here and in every ancestor up to the editor component, which itself is not in a window. */
    private fun java.awt.Component.isShowingInTree(): Boolean = generateSequence(this) { it.parent }.all { it.isVisible }

    private fun descendants(component: java.awt.Container): List<java.awt.Component> = component.components.flatMap {
        listOf(it) + ((it as? java.awt.Container)?.let(::descendants) ?: emptyList())
    }

    private fun press(canvas: javax.swing.JComponent, stroke: String) {
        val key = canvas.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
            .get(javax.swing.KeyStroke.getKeyStroke(stroke))
        canvas.actionMap.get(key).actionPerformed(java.awt.event.ActionEvent(canvas, 0, stroke))
    }

    private fun click(component: java.awt.Component) {
        component.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(component, java.awt.event.MouseEvent.MOUSE_CLICKED,
            0, 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1)) }
    }

    fun `test tab previews one block at a time and enter pins cards until unpinned`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val canvas = (editor.component as SchematicPanel).canvas
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            fun shown() = cards().filter { it.isShowingInTree() }
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 5 }, 10)
            assertTrue(shown().isEmpty())
            val level = cards().first().parent
            val sourceSize = level.preferredSize
            level.doLayout()
            assertTrue(cards().all { it.bounds.isEmpty })
            press(canvas, "ENTER")
            assertTrue(shown().isEmpty())
            press(canvas, "TAB")
            assertEquals(listOf(cards()[0]), shown())
            assertTrue(level.preferredSize.width > sourceSize.width)
            press(canvas, "TAB")
            assertEquals(listOf(cards()[1]), shown())
            press(canvas, "ENTER")
            press(canvas, "TAB")
            assertEquals(listOf(cards()[1], cards()[2]), shown())
            press(canvas, "shift TAB")
            assertEquals(listOf(cards()[1]), shown())
            press(canvas, "ESCAPE")
            assertEquals(listOf(cards()[1]), shown())
            val unpin = descendants(cards()[1] as java.awt.Container)
                .filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Unpin" }
            assertTrue(unpin.getFocusTraversalKeys(java.awt.KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS).isEmpty())
            unpin.doClick()
            assertTrue(shown().isEmpty())
            assertEquals(sourceSize, level.preferredSize)
            press(canvas, "shift TAB")
            assertEquals(listOf(cards().last()), shown())
            press(canvas, "TAB") // Wrap to the first block.
            assertEquals(listOf(cards().first()), shown())
            repeat(5) { press(canvas, "TAB") }
            assertEquals(listOf(cards().first()), shown())
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test hover previews survive crossing to the card and clicks pin without unfolding`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val canvas = (editor.component as SchematicPanel).canvas
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 5 }, 10)
            val blocks = descendants(canvas).filter { it.javaClass.simpleName == "BlockView" }
            fun hover(component: java.awt.Component, event: Int = java.awt.event.MouseEvent.MOUSE_ENTERED) {
                component.dispatchEvent(java.awt.event.MouseEvent(component, event, 0, 0, 1, 1, 0, false))
            }
            hover(blocks[0])
            assertTrue(cards()[0].isVisible)
            hover(blocks[0], java.awt.event.MouseEvent.MOUSE_EXITED)
            hover(cards()[0])
            // Dispatch the pending exit timer if it was not cancelled on entering the card.
            val exitTimer = javax.swing.Timer(300, null).apply { isRepeats = false; start() }
            PlatformTestUtil.waitWithEventsDispatching("Pointer should reach the card", { !exitTimer.isRunning }, 5)
            assertTrue(cards()[0].isVisible)
            hover(cards()[0], java.awt.event.MouseEvent.MOUSE_EXITED)
            PlatformTestUtil.waitWithEventsDispatching("Preview should hide on leaving", { !cards()[0].isVisible }, 5)
            hover(blocks[1])
            assertTrue(cards()[1].isVisible)
            click((blocks[1] as java.awt.Container).components.single())
            hover(blocks[2])
            assertEquals(listOf(cards()[1], cards()[2]), cards().filter { it.isShowingInTree() })
            assertEquals("Pinning does not unfold source", 5, cards().size)
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test a block previews and pins all targets while each card can be unpinned separately`() {
        val (result, schematic) = build(source.replace("store.save(x);", "check(kind); store.save(x);")) {
            it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry")
        }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val canvas = (editor.component as SchematicPanel).canvas
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            fun shown() = cards().filter { it.isShowingInTree() }
            PlatformTestUtil.waitWithEventsDispatching("The method should be read", { cards().size == 3 }, 10)
            press(canvas, "TAB")
            assertEquals(1, shown().size)
            press(canvas, "TAB")
            assertEquals(listOf(cards()[1], cards()[2]), shown())
            press(canvas, "ENTER")
            press(canvas, "TAB")
            assertEquals(3, shown().size)
            press(canvas, "ESCAPE")
            assertEquals(listOf(cards()[1], cards()[2]), shown())
            val unpin = descendants(cards()[1] as java.awt.Container)
                .filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Unpin" }
            unpin.doClick()
            assertEquals(listOf(cards()[2]), shown())
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test tab enters unfolded branches and skips their blocks after folding`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val canvas = (editor.component as SchematicPanel).canvas
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            fun shownNames() = descendants(canvas).filterIsInstance<javax.swing.JLabel>()
                .filter { it.isShowingInTree() && it.text.orEmpty().contains("<code>") }.map { it.text }
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 5 }, 10)
            press(canvas, "TAB")
            press(canvas, "TAB")
            val entry = descendants(cards()[1] as java.awt.Container).filterIsInstance<javax.swing.JLabel>()
                .single { it.text.orEmpty().contains("<code>entry(") }
            click(entry)
            PlatformTestUtil.waitWithEventsDispatching("Entry should unfold", { cards().size == 7 }, 10)
            press(canvas, "TAB")
            assertTrue(shownNames().any { it.contains("<code>check(") })
            press(canvas, "TAB")
            assertTrue(shownNames().any { it.contains("<code>save(") })
            click(entry) // Folding returns the cursor from the hidden call to entry.
            press(canvas, "TAB")
            val visibleCards = cards().filter { it.isShowingInTree() }
            assertEquals(2, visibleCards.size)
            assertTrue("Tab should now reach the next root declaration", visibleCards.last().parent === entry.parent.parent.parent)
            press(canvas, "ESCAPE")
            assertEquals(1, cards().count { it.isShowingInTree() })
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test a method shows its calls on the lines where they start`() {
        val (result, schematic) = build { it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry") }
        val blocks = schematic.rows.filterIsInstance<BlockRow>()
        assertEquals(listOf("check" to "check(", "save" to "store.save(x);"),
            blocks.map { block -> result.name(block.calls.single()) to schematic.text(block.headline).trim() })
        assertTrue(blocks.all { it.declared == null && it.first == it.last })
        assertEquals("    void entry(int kind) {", schematic.text(schematic.firstLine))
    }
}
