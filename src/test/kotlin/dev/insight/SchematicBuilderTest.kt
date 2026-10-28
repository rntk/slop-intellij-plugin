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
            assertEquals(maxOf(0, canvas.components.single().height + canvas.insets.top + canvas.insets.bottom - viewport.height),
                viewport.viewPosition.y)
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

    private fun layOut(component: java.awt.Component) {
        component.doLayout()
        (component as? java.awt.Container)?.components?.forEach(::layOut)
    }

    fun `test only titles on the breadcrumb chain stay readable when zoomed out`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            fun title(name: String) = descendants(canvas).filterIsInstance<javax.swing.JLabel>()
                .firstOrNull { it.text.orEmpty().contains("<code>$name(") }
            fun renderedSize(label: javax.swing.JLabel, name: String): Float {
                fun glyphs(view: javax.swing.text.View): List<javax.swing.text.GlyphView> =
                    if (view is javax.swing.text.GlyphView) listOf(view)
                    else (0 until view.viewCount).flatMap { glyphs(view.getView(it)) }
                val html = label.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) as javax.swing.text.View
                return glyphs(html).first { view ->
                    view.document.getText(view.startOffset, view.endOffset - view.startOffset).contains("$name(")
                }.font.size2D
            }
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { title("entry") != null }, 10)
            val entry = title("entry")!!
            val minimumSize = com.intellij.util.ui.JBUI.scale(14).toFloat()
            val blockTitle = descendants(canvas).filterIsInstance<javax.swing.JLabel>()
                .single { it.text == "void entry(int kind) {" }
            val unclicked = descendants(canvas).filterIsInstance<javax.swing.JLabel>()
                .single { it.text == "void check(int kind) {}" }
            val normalBlockSize = blockTitle.font.size2D
            panel.setSize(700, 600)
            layOut(panel)
            panel.zoomTo(0.5)
            assertEquals(normalBlockSize * 0.5f, blockTitle.font.size2D, 0.1f)
            assertEquals(entry.font.size.toFloat(), renderedSize(entry, "entry"), 0.1f)
            blockTitle.parent.dispatchEvent(java.awt.event.MouseEvent(blockTitle.parent,
                java.awt.event.MouseEvent.MOUSE_ENTERED, 0, 0, 1, 1, 0, false))
            assertEquals("Hover alone must not enlarge a title", entry.font.size.toFloat(), renderedSize(entry, "entry"), 0.1f)
            click(blockTitle.parent)
            for (zoom in listOf(0.5, 0.25)) {
                panel.zoomTo(zoom)
                assertEquals("The clicked card title stays readable", minimumSize, renderedSize(entry, "entry"), 0.1f)
                assertEquals("Its source block stays readable too", minimumSize, blockTitle.font.size2D, 0.1f)
                assertEquals("Unclicked blocks scale normally", normalBlockSize * zoom.toFloat(), unclicked.font.size2D, 0.1f)
                layOut(editor.component)
                assertTrue("Readable block titles must fit their source column", blockTitle.parent.parent.width >= blockTitle.parent.preferredSize.width)
            }
            click(entry)
            PlatformTestUtil.waitWithEventsDispatching("The method should unfold", {
                descendants(canvas).count { it.javaClass.simpleName == "SummaryCard" } == 7
            }, 10)
            val check = title("check")!!
            val nestedBlock = descendants(canvas).filterIsInstance<javax.swing.JLabel>().single { it.text == "check(" }
            assertEquals("New calls scale normally until selected", check.font.size.toFloat(), renderedSize(check, "check"), 0.1f)
            assertEquals(normalBlockSize * 0.25f, nestedBlock.font.size2D, 0.1f)
            click(check)
            assertEquals("Following a call enlarges its title", minimumSize, renderedSize(check, "check"), 0.1f)
            assertEquals(minimumSize, nestedBlock.font.size2D, 0.1f)
            assertEquals("Ancestors on the chain stay readable", minimumSize, renderedSize(entry, "entry"), 0.1f)
            panel.zoomTo(2.0)
            assertEquals(entry.font.size.toFloat(), renderedSize(entry, "entry"), 0.1f)
            assertEquals(check.font.size.toFloat(), renderedSize(check, "check"), 0.1f)
            panel.zoomTo(0.25)
            click(unclicked.parent)
            assertEquals("Switching branches enlarges the new selection", minimumSize, unclicked.font.size2D, 0.1f)
            assertEquals("Previously clicked blocks outside the chain scale normally", normalBlockSize * 0.25f, blockTitle.font.size2D, 0.1f)
            assertEquals("Unfolded cards outside the chain scale normally", entry.font.size.toFloat(), renderedSize(entry, "entry"), 0.1f)
            assertEquals(check.font.size.toFloat(), renderedSize(check, "check"), 0.1f)
            val trail = descendants(editor.component).single { it.name == "schematic-breadcrumbs" } as java.awt.Container
            descendants(trail).filterIsInstance<com.intellij.ui.components.ActionLink>().first().doClick()
            assertEquals("Returning to the root clears the title emphasis", normalBlockSize * 0.25f, unclicked.font.size2D, 0.1f)
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test breadcrumbs follow the selected branch stay readable and return to ancestors`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            val trail = descendants(panel).single { it.name == "schematic-breadcrumbs" } as java.awt.Container
            fun links() = descendants(trail).filterIsInstance<com.intellij.ui.components.ActionLink>()
            fun path() = links().map { it.text.substringBefore('(') }
            fun title(name: String) = descendants(canvas).filterIsInstance<javax.swing.JLabel>()
                .first { it.text.orEmpty().contains("<code>$name(") }
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 5 }, 10)
            val root = result.graph.nodes[schematic.scope].name
            assertEquals(listOf(root), path())
            panel.setSize(700, 600)
            layOut(panel)
            val viewport = canvas.parent as javax.swing.JViewport
            val extent = java.awt.Dimension(viewport.extentSize)
            val trailFont = links().first().font.size2D
            press(canvas, "TAB")
            press(canvas, "TAB")
            click(title("entry"))
            PlatformTestUtil.waitWithEventsDispatching("Entry should unfold", { cards().size == 7 }, 10)
            assertEquals(listOf(root, "entry"), path())
            press(canvas, "TAB")
            press(canvas, "ENTER")
            click(title("check"))
            assertEquals(listOf(root, "entry", "check"), path())

            // Previewing another branch must not replace the chain the reader selected.
            val other = descendants(canvas).first { it.javaClass.simpleName == "BlockView" }
            other.dispatchEvent(java.awt.event.MouseEvent(other, java.awt.event.MouseEvent.MOUSE_ENTERED,
                0, 0, 1, 1, 0, false))
            assertEquals(listOf(root, "entry", "check"), path())
            press(canvas, "ESCAPE")
            panel.zoomTo(0.5)
            layOut(panel)
            assertEquals(listOf(root, "entry", "check"), path())
            assertTrue(links().all { it.font.size2D == trailFont })
            assertEquals("The trail must not resize the canvas viewport", extent, viewport.extentSize)

            repeat(5) { press(canvas, "shift PAGE_DOWN") }
            layOut(panel)
            links()[1].doClick()
            layOut(panel)
            assertEquals(listOf(root, "entry"), path())
            val header = title("entry").parent
            assertTrue("An ancestor link brings its header back into view", viewport.viewRect.intersects(
                javax.swing.SwingUtilities.convertRectangle(header.parent, header.bounds, canvas)))
            assertTrue("Jumping back preserves the open branch", title("check").isShowingInTree())
            links().first().doClick()
            assertEquals(java.awt.Point(), viewport.viewPosition)
            assertEquals(listOf(root), path())

            click(title("check"))
            assertEquals(listOf(root, "entry", "check"), path())
            descendants(title("entry").parent).filterIsInstance<com.intellij.ui.components.ActionLink>()
                .single { it.text == "Unpin" }.doClick()
            assertEquals("Unpinning removes hidden descendants from the trail", listOf(root), path())
            assertFalse(title("check").isShowingInTree())
            click((other as java.awt.Container).components.single())
            assertEquals("A sibling starts its own branch", listOf(root, "open"), path())
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test breadcrumbs retain recursive calls as distinct steps`() {
        val (result, schematic) = build("class Flow { void entry() { step(); } void step() { entry(); } }") {
            it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry")
        }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            fun titles() = descendants(canvas).filterIsInstance<javax.swing.JLabel>()
                .filter { it.text.orEmpty().contains("<code>") }
            fun count() = descendants(canvas).count { it.javaClass.simpleName == "SummaryCard" }
            PlatformTestUtil.waitWithEventsDispatching("Entry should load", { count() == 1 }, 10)
            panel.setSize(260, 600)
            layOut(panel)
            val viewport = canvas.parent as javax.swing.JViewport
            val extent = java.awt.Dimension(viewport.extentSize)
            val calls = listOf("step", "entry", "step", "entry", "step")
            calls.forEachIndexed { index, name ->
                press(canvas, "TAB")
                click(titles().last { it.text.contains("<code>$name(") })
                PlatformTestUtil.waitWithEventsDispatching("$name should unfold", { count() == index + 2 }, 10)
            }
            val trail = descendants(editor.component).single { it.name == "schematic-breadcrumbs" } as java.awt.Container
            assertEquals(listOf("entry") + calls, descendants(trail)
                .filterIsInstance<com.intellij.ui.components.ActionLink>().map { it.text.substringBefore('(') })
            layOut(panel)
            val trailScroll = trail.parent.parent as javax.swing.JScrollPane
            assertTrue("Long paths remain accessible with horizontal scrolling", trailScroll.horizontalScrollBar.isVisible)
            assertEquals("Long paths must not resize the canvas", extent, viewport.extentSize)
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test cards and unfolded branches keep the viewport and explored space stable`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 5 }, 10)
            panel.setSize(700, 600)
            layOut(panel)
            val viewport = canvas.parent as javax.swing.JViewport
            val block = descendants(canvas).filter { it.javaClass.simpleName == "BlockView" }[1]
            fun hover(target: java.awt.Component = block) = target.dispatchEvent(java.awt.event.MouseEvent(target, java.awt.event.MouseEvent.MOUSE_ENTERED,
                0, 0, 1, 1, 0, false))
            hover()
            layOut(panel)
            val card = cards()[1] as java.awt.Container
            val entry = descendants(card).filterIsInstance<javax.swing.JLabel>()
                .single { it.text.orEmpty().contains("<code>entry(") }
            viewport.viewPosition = java.awt.Point(canvas.width - viewport.width, canvas.height - viewport.height)
            layOut(panel)
            val position = java.awt.Point(viewport.viewPosition)
            val sourcePosition = javax.swing.SwingUtilities.convertPoint(block, 0, 0, viewport)
            var space = java.awt.Dimension(canvas.size)
            fun assertStable() {
                layOut(panel)
                assertEquals("Card changes must preserve the camera", position, viewport.viewPosition)
                assertEquals("The source must stay at the same screen position", sourcePosition,
                    javax.swing.SwingUtilities.convertPoint(block, 0, 0, viewport))
                assertTrue("Explored canvas space must not shrink", canvas.width >= space.width && canvas.height >= space.height)
                space = java.awt.Dimension(canvas.size)
            }
            press(canvas, "ESCAPE")
            assertFalse(card.isVisible)
            assertStable()
            hover()
            assertTrue(card.isVisible)
            assertStable()
            click(entry)
            assertStable()
            PlatformTestUtil.waitWithEventsDispatching("The branch should load without moving the view", { cards().size == 7 }, 10)
            assertStable()
            val compact = descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>()
                .single { it.text == "Compact" && it.isShowingInTree() }
            compact.doClick()
            assertStable()
            compact.doClick()
            assertStable()
            click(entry) // Fold the loaded branch.
            assertStable()
            click(entry)
            descendants(panel).filterIsInstance<javax.swing.JButton>().single { it.text == "Fold all" }.doClick()
            assertStable()
            press(canvas, "ESCAPE")
            descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Unpin" }.doClick()
            assertFalse(card.isVisible)
            assertStable()

            hover(descendants(canvas).first { it.javaClass.simpleName == "BlockView" })
            val details = descendants(cards().first() as java.awt.Container)
                .filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Explain link" }
            details.doClick()
            assertStable()
            details.doClick()
            assertStable()
            press(canvas, "ESCAPE")

            // Navigation extends blank canvas space even when all cards are hidden.
            val previousBottom = canvas.height
            repeat(12) { press(canvas, "PAGE_DOWN"); layOut(panel) }
            assertTrue("Panning can continue beyond the old canvas bounds", viewport.viewPosition.y > previousBottom)
            val explored = java.awt.Dimension(canvas.size)
            press(canvas, "HOME")
            layOut(panel)
            assertEquals(java.awt.Point(), viewport.viewPosition)
            assertEquals("Returning home preserves explored space", explored, canvas.size)

            repeat(5) { press(canvas, "PAGE_DOWN"); press(canvas, "shift PAGE_DOWN") }
            val beforeZoom = java.awt.Point(viewport.viewPosition)
            val anchor = java.awt.Point(100, 50)
            panel.zoomTo(0.5, anchor)
            layOut(panel)
            assertEquals("Zoom keeps the horizontal anchor", (beforeZoom.x + anchor.x) * 0.5 - anchor.x,
                viewport.viewPosition.x.toDouble(), 1.0)
            assertEquals("Zoom keeps the vertical anchor", (beforeZoom.y + anchor.y) * 0.5 - anchor.y,
                viewport.viewPosition.y.toDouble(), 1.0)
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
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
