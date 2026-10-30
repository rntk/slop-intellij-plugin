package dev.insight

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.callgraph.*
import dev.insight.enrichment.liftCalls
import dev.insight.graph.*

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

    fun `test gap context includes PSI knowledge and rejects changed source`() {
        val (_, schematic) = build { it.name.substringBefore('(') == "entry" }
        val gap = schematic.rows.filterIsInstance<CodeRow>().first { row ->
            (row.first..row.last).any { schematic.text(it).contains("int x = 1") }
        }
        val context = schematicGapSource(project, schematic, gap)
        assertEquals(source.lines(), context.lines)
        assertTrue(context.facts.contains("Language: Java", ignoreCase = true))
        assertTrue(context.facts.contains("Declared: variable x"))
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "// changed\n")
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        try {
            schematicGapSource(project, schematic, gap)
            fail("Changed source must not be mixed with snapshot excerpts")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Source changed"))
        }
    }

    fun `test summarized gaps can reserve native fold space beside symbol blocks`() {
        val (_, schematic) = build { it.kind == SymbolKind.FILE }
        val gap = schematic.rows.filterIsInstance<CodeRow>().first()
        val block = schematic.rows.filterIsInstance<BlockRow>().first()
        val viewer = SchematicSourceEditor(project, schematic, listOf(
            gap to javax.swing.JLabel("Gap summary"), block to javax.swing.JLabel("Symbol"),
        ), {}, {}, { _, _, _ -> })
        try {
            assertEquals(2, viewer.editor.foldingModel.allFoldRegions.size)
            assertTrue(viewer.blockY(gap) >= 0)
            viewer.refreshBlocks()
            assertEquals(schematic.lines.joinToString("\n"), viewer.editor.document.text)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(viewer)
        }
    }

    fun `test gap summaries use external cards across source renderers`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val toggle = descendants(panel).filterIsInstance<javax.swing.JCheckBox>().single { it.name == "schematic-native-editor" }
            PlatformTestUtil.waitWithEventsDispatching("File should load", {
                descendants(panel.canvas).any { it.javaClass.simpleName == "GapView" }
            }, 10)
            toggle.doClick()
            val gap = descendants(panel.canvas).first { it.javaClass.simpleName == "GapView" } as java.awt.Container
            gap.javaClass.getMethod("showSummary", String::class.java).apply {
                isAccessible = true
                invoke(gap, "Defines the file's surrounding structure.")
            }
            val code = descendants(gap).filterIsInstance<javax.swing.JTextArea>().single()
            val originalCode = code.text
            fun clickCode() {
                val event = java.awt.event.MouseEvent(code, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1)
                code.mouseListeners.forEach { it.mouseClicked(event) }
            }
            val card = descendants(panel.canvas).single { it.javaClass.simpleName == "GapSummaryCard" } as java.awt.Container
            assertSame("Gap cards belong to the external card column", gap.parent, card.parent)
            assertFalse("Summary text must stay outside the source row", descendants(gap).filterIsInstance<javax.swing.JLabel>()
                .any { it.text.orEmpty().contains("Defines the file") })
            val sourceLink = descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Source" }
            assertFalse(sourceLink.isShowingInTree())
            val symbolBox = descendants(panel.canvas).first { it.javaClass.simpleName == "BlockView" }
                .let { (it as java.awt.Container).components.single() } as javax.swing.JComponent
            val gapBox = code.parent as javax.swing.JComponent
            assertFalse("Gaps have a different background from symbols", gapBox.background == symbolBox.background)
            val gapInsets = gapBox.border.getBorderInsets(gapBox)
            assertTrue("The gap's left accent rule also distinguishes its shape", gapInsets.left > gapInsets.right)
            fun leave(component: java.awt.Component, visible: Boolean) {
                hoverMouse(component, java.awt.event.MouseEvent.MOUSE_EXITED)
                val timer = javax.swing.Timer(300, null).apply { isRepeats = false; start() }
                PlatformTestUtil.waitWithEventsDispatching("Hover exit should settle", { !timer.isRunning }, 5)
                assertEquals(visible, sourceLink.isShowingInTree())
            }
            fun checkHover() {
                hoverMouse(code)
                assertTrue("Hovering a gap previews its summary", sourceLink.isShowingInTree())
                hoverMouse(code, java.awt.event.MouseEvent.MOUSE_EXITED)
                hoverMouse(card)
                val timer = javax.swing.Timer(300, null).apply { isRepeats = false; start() }
                PlatformTestUtil.waitWithEventsDispatching("Pointer should reach gap card", { !timer.isRunning }, 5)
                assertTrue("Crossing the arrow keeps the preview open", sourceLink.isShowingInTree())
                leave(card, false)
            }
            checkHover()
            hoverMouse(code)
            descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Pin" }.doClick()
            leave(code, true)
            descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Unpin" }.doClick()
            assertFalse(sourceLink.isShowingInTree())
            clickCode()
            assertTrue(sourceLink.isShowingInTree())
            // Keep a known symbol's card visible too, to exercise shared column spacing.
            press(panel.canvas, "TAB")
            fun checkCardColumn() {
                panel.setSize(1400, 900)
                layOut(panel)
                val level = gap.parent
                val visibleCards = level.components.filter {
                    it.isVisible && it.javaClass.simpleName in listOf("SummaryCard", "GapSummaryCard")
                }.sortedBy { it.y }
                assertEquals(2, visibleCards.size)
                assertTrue("Connector arrows need space outside the file", card.x > gap.x + gap.width)
                assertEquals("Both summaries share the same column", 1, visibleCards.map { it.x }.distinct().size)
                assertTrue("External cards must not overlap", visibleCards[0].y + visibleCards[0].height < visibleCards[1].y)
            }
            checkCardColumn()
            toggle.doClick()
            checkCardColumn()
            assertTrue(code.isShowingInTree())
            assertEquals(originalCode, code.text)
            assertTrue(sourceLink.isShowingInTree())
            clickCode()
            assertFalse(sourceLink.isShowingInTree())
            clickCode()
            descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Unpin" }.doClick()
            assertFalse(sourceLink.isShowingInTree())
            val viewer = descendants(panel.canvas).filterIsInstance<SchematicSourceEditor>().single()
            assertEquals(schematic.rows.filterIsInstance<BlockRow>().size + 1, viewer.editor.foldingModel.allFoldRegions.size)
            assertEquals(schematic.lines.joinToString("\n"), viewer.editor.document.text)
            checkHover()
            hoverMouse(code)
            press(panel.canvas, "ENTER")
            leave(code, true)
            press(panel.canvas, "ESCAPE")
            assertTrue("Pinned gap cards survive clearing the hover", sourceLink.isShowingInTree())
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

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

    fun `test native viewers preserve snapshots line numbers selection fold geometry and editor lifetime`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val fileEditor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        val viewers = mutableListOf<SchematicSourceEditor>()
        try {
            val panel = fileEditor.component as SchematicPanel
            PlatformTestUtil.waitWithEventsDispatching("The native file viewer should load", {
                descendants(panel).filterIsInstance<SchematicSourceEditor>().size == 1
            }, 10)
            val sourceView = descendants(panel).filterIsInstance<SchematicSourceEditor>().single()
            viewers += sourceView
            val native = sourceView.editor
            assertTrue(native.isViewer)
            assertNotSame(myFixture.editor.document, native.document)
            assertEquals(source, native.document.text)
            assertEquals(com.intellij.psi.JavaTokenType.CLASS_KEYWORD, native.highlighter.createIterator(0).tokenType)
            native.selectionModel.setSelection(0, "class Flow".length)
            assertEquals("class Flow", native.selectionModel.selectedText)

            panel.setSize(900, 600)
            val blocks = sourceView.parent.components.filter { it.javaClass.simpleName == "LineView" }
            assertEquals(blocks.size, native.foldingModel.allFoldRegions.size)
            for (zoom in listOf(1.0, 0.5, 2.0)) {
                panel.zoomTo(zoom)
                layOut(panel)
                val folds = native.foldingModel.allFoldRegions.map { it as com.intellij.openapi.editor.CustomFoldRegion }
                    .sortedBy { it.startOffset }
                for ((block, fold) in blocks.zip(folds)) {
                    assertFalse(fold.isExpanded)
                    assertEquals(sourceView.textOrigin().y + fold.location!!.y, block.y)
                    assertEquals(fold.heightInPixels, block.height)
                }
            }

            val tab = com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(native.contentComponent).single { action ->
                action.shortcutSet.shortcuts.filterIsInstance<com.intellij.openapi.actionSystem.KeyboardShortcut>()
                    .any { it.firstKeyStroke == javax.swing.KeyStroke.getKeyStroke("TAB") }
            }
            repeat(2) {
                tab.actionPerformed(com.intellij.openapi.actionSystem.AnActionEvent.createEvent(tab, native.dataContext, null,
                    "SchematicTest", com.intellij.openapi.actionSystem.ActionUiKind.NONE, null))
            }
            panel.zoomTo(1.0)
            press(panel.canvas, "HOME")
            layOut(panel)
            val image = java.awt.image.BufferedImage(panel.width, panel.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try { panel.paint(graphics) } finally { graphics.dispose() }
            val entry = descendants(panel).filterIsInstance<javax.swing.JLabel>().first { it.text.orEmpty().contains("<code>entry(") }
            click(entry)
            PlatformTestUtil.waitWithEventsDispatching("The branch should have its own native viewer", {
                descendants(panel).filterIsInstance<SchematicSourceEditor>().size == 2
            }, 10)
            val nested = descendants(panel).filterIsInstance<SchematicSourceEditor>().single { it !== sourceView }
            viewers += nested
            assertTrue(nested.editor.document.text.contains("int x = 1;"))
            val converter = nested.editor.gutterComponentEx.primaryLineNumberConverter
            assertEquals(source.lines().indexOfFirst { it.contains("void entry") } + 1, converter.convert(nested.editor, 1))

            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, "// changed after opening\n")
            }
            assertEquals("Project edits must not change the displayed graph snapshot", source, native.document.text)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(fileEditor)
        }
        assertEquals(2, viewers.size)
        assertTrue("Closing the schematic must release every native editor", viewers.all { it.editor.isDisposed })
    }

    fun `test source renderer switches preserve exploration compact mode zoom snapshots and viewport`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            val toggle = descendants(panel).filterIsInstance<javax.swing.JCheckBox>().single { it.name == "schematic-native-editor" }
            fun viewers() = descendants(canvas).filterIsInstance<SchematicSourceEditor>()
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            fun title(name: String) = descendants(canvas).filterIsInstance<javax.swing.JLabel>()
                .first { it.text.orEmpty().contains("<code>$name(") }
            fun classicRows() = descendants(canvas).filterIsInstance<javax.swing.JTextArea>()
            PlatformTestUtil.waitWithEventsDispatching("The file should load", { viewers().size == 1 }, 10)
            assertTrue(toggle.isSelected)
            panel.setSize(900, 600)
            layOut(panel)
            press(canvas, "TAB")
            press(canvas, "TAB")
            click(title("entry"))
            PlatformTestUtil.waitWithEventsDispatching("Entry should unfold", { viewers().size == 2 }, 10)
            val entryCard = generateSequence<java.awt.Component>(title("entry")) { it.parent }
                .first { it.javaClass.simpleName == "SummaryCard" } as java.awt.Container
            val compact = descendants(entryCard).filterIsInstance<com.intellij.ui.components.ActionLink>()
                .single { it.text == "Compact" && it.isShowingInTree() }
            compact.doClick()
            panel.zoomTo(0.5)
            layOut(panel)
            val viewport = canvas.parent as javax.swing.JViewport
            val position = java.awt.Point(viewport.viewPosition)
            val originalCards = cards()
            val originalViewers = viewers()
            val trail = descendants(panel).single { it.name == "schematic-breadcrumbs" } as java.awt.Container
            val path = descendants(trail).filterIsInstance<com.intellij.ui.components.ActionLink>().map { it.text }

            toggle.doClick()
            layOut(panel)
            assertTrue(viewers().isEmpty())
            assertTrue("Classic mode releases native editors, including folded branches", originalViewers.all { it.editor.isDisposed })
            assertEquals(originalCards, cards())
            assertEquals(position, viewport.viewPosition)
            assertEquals(0.5, panel.zoom, 1e-9)
            assertEquals(path, descendants(trail).filterIsInstance<com.intellij.ui.components.ActionLink>().map { it.text })
            assertEquals("Full", compact.text)
            assertTrue(classicRows().any { it.isShowingInTree() && it.text.contains("class Flow") })
            assertTrue(descendants(entryCard).filterIsInstance<javax.swing.JTextArea>().all { !it.isVisible })
            compact.doClick()
            val entryCode = descendants(entryCard).filterIsInstance<javax.swing.JTextArea>().first { it.text.contains("int x = 1;") }
            assertTrue("Classic code restores common-indent removal", entryCode.text.startsWith("void entry"))
            assertFalse(entryCode.isFocusable)
            val fontSize = entryCode.font.size2D
            panel.zoomTo(0.75)
            assertEquals(fontSize * 1.5f, entryCode.font.size2D, 0.01f)

            press(canvas, "TAB")
            click(title("check"))
            PlatformTestUtil.waitWithEventsDispatching("Classic mode should load new branches", {
                descendants(canvas).count { it.javaClass.simpleName == "LevelView" } == 3
            }, 10)
            assertTrue(viewers().isEmpty())
            val exploredCards = cards()
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, "// changed after opening\n")
            }
            layOut(panel)
            val beforeNative = java.awt.Point(viewport.viewPosition)
            toggle.doClick()
            layOut(panel)
            assertEquals(3, viewers().size)
            assertTrue(classicRows().all { !it.isVisible })
            assertEquals(exploredCards, cards())
            assertEquals(beforeNative, viewport.viewPosition)
            assertEquals(0.75, panel.zoom, 1e-9)
            assertTrue("Switching renderers must keep the captured source", viewers().any { it.editor.document.text == source })
            assertTrue(title("entry").isShowingInTree())
            assertTrue(title("check").isShowingInTree())
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test choosing classic before loading avoids native editors and applies to new branches`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val toggle = descendants(panel).filterIsInstance<javax.swing.JCheckBox>().single { it.name == "schematic-native-editor" }
            toggle.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Classic source should load", {
                descendants(panel.canvas).filterIsInstance<javax.swing.JTextArea>().any { it.isShowingInTree() }
            }, 10)
            assertTrue(descendants(panel).filterIsInstance<SchematicSourceEditor>().isEmpty())
            repeat(4) { press(panel.canvas, "TAB") } // Store, Store, open, entry
            click(descendants(panel).filterIsInstance<javax.swing.JLabel>().first { it.text.orEmpty().contains("<code>entry(") })
            PlatformTestUtil.waitWithEventsDispatching("Classic source should unfold", {
                descendants(panel.canvas).count { it.javaClass.simpleName == "SummaryCard" } == 12
            }, 10)
            assertTrue(descendants(panel).filterIsInstance<SchematicSourceEditor>().isEmpty())
            assertTrue(descendants(panel).filterIsInstance<javax.swing.JTextArea>().any { it.isShowingInTree() && it.text.contains("int x = 1;") })
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
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
                descendants(editor.component).count { it.javaClass.simpleName == "SummaryCard" } == 9
            }, 10)
            assertTrue("Cards start hidden", cards().isEmpty())
            val canvas = (editor.component as SchematicPanel).canvas
            repeat(3) { press(canvas, "TAB") } // The field line: its type, the class it calls, and open
            press(canvas, "TAB") // entry declaration
            press(canvas, "ENTER")
            assertEquals(1, cards().size)
            val entry = cards().first { it.text.contains("<code>entry(") }
            entry.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(entry, java.awt.event.MouseEvent.MOUSE_CLICKED,
                0, 0, 1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1)) }
            // The unfolded method loads its own calls, whose cards still start hidden.
            PlatformTestUtil.waitWithEventsDispatching("The method should unfold", {
                descendants(editor.component).count { it.javaClass.simpleName == "SummaryCard" } == 12
            }, 10)
            assertEquals(1, cards().size)
            fun descendants(component: java.awt.Container): List<java.awt.Component> = component.components.flatMap {
                listOf(it) + ((it as? java.awt.Container)?.let(::descendants) ?: emptyList())
            }
            val header = entry.parent
            val toggle = descendants(header).filterIsInstance<com.intellij.ui.components.ActionLink>()
                .single { it.text == "Compact" }
            val card = header.parent
            val sourceRows = descendants(card).filterIsInstance<SchematicSourceEditor>()
            assertTrue(sourceRows.any { it.editor.document.text.contains("int x = 1;") })
            val fullHeight = card.preferredSize.height
            val level = sourceRows.first().parent
            val firstBlock = level.components.first { it.javaClass.simpleName == "LineView" }
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

    fun `test a constructor card promotes to its file and continues through another method`() {
        val worker = """
            class Worker {
                Worker() { setup(); }
                void setup() {}
                void run() { algorithm(); }
                void algorithm() {}
            }
        """.trimIndent()
        myFixture.addFileToProject("Worker.java", worker)
        val (result, schematic) = build("class Entry { void entry() { new Worker(); } }") {
            it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry")
        }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            fun viewers() = descendants(canvas).filterIsInstance<SchematicSourceEditor>()
            fun cards() = descendants(canvas).filterIsInstance<java.awt.Container>().filter { it.javaClass.simpleName == "SummaryCard" }
            fun links(card: java.awt.Container) = descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>()
            fun title(card: java.awt.Container) = descendants(card).filterIsInstance<javax.swing.JLabel>()
                .first { it.text.orEmpty().contains("<code>") }
            PlatformTestUtil.waitWithEventsDispatching("The constructor call should load", { cards().size == 1 }, 10)
            val card = cards().single()
            val rootViewer = viewers().single()
            press(canvas, "TAB")
            click(title(card))
            PlatformTestUtil.waitWithEventsDispatching("Only the constructor should unfold", { viewers().size == 2 }, 10)
            val constructorViewer = viewers().single { it !== rootViewer }
            assertEquals("    Worker() { setup(); }", constructorViewer.editor.document.text)
            links(card).single { it.text == "Compact" && it.isShowingInTree() }.doClick()
            press(canvas, "TAB") // The selected constructor call will be replaced by file declarations.
            val menu = javax.swing.JPopupMenu()
            card.javaClass.getMethod("addContextActions", javax.swing.JPopupMenu::class.java).apply {
                isAccessible = true
                invoke(card, menu)
            }
            menu.components.filterIsInstance<javax.swing.JMenuItem>().single { it.text == "Show whole file" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("The card should show the whole target file", {
                viewers().any { it.editor.document.text == worker }
            }, 10)
            assertTrue("The caller stays in place", rootViewer in viewers())
            assertTrue("Replaced native editors must be released", constructorViewer.editor.isDisposed)
            val fileViewer = viewers().single { it.editor.document.text == worker }
            assertTrue("Promotion restores plain source even from compact mode", fileViewer.isVisible)
            assertTrue(title(card).text.contains("<code>Worker.java</code>"))
            assertTrue(title(card).text.contains("Whole file expanded from"))
            val trail = descendants(panel).single { it.name == "schematic-breadcrumbs" } as java.awt.Container
            assertEquals(listOf(result.graph.nodes[schematic.scope].name, "Worker.java"),
                descendants(trail).filterIsInstance<com.intellij.ui.components.ActionLink>().map { it.text })

            // Tab walks the new file's declarations, including methods unreachable from the constructor.
            press(canvas, "TAB") // constructor
            press(canvas, "TAB") // setup
            press(canvas, "TAB") // run
            val run = descendants(card).filterIsInstance<javax.swing.JLabel>().single { it.text.orEmpty().contains("<code>run(") }
            assertTrue(run.isShowingInTree())
            click(run)
            PlatformTestUtil.waitWithEventsDispatching("Exploration should continue through run", {
                viewers().any { it.editor.document.text.contains("void run()") && it.editor.document.text != worker }
            }, 10)
            press(canvas, "TAB")
            assertTrue(descendants(card).filterIsInstance<javax.swing.JLabel>().any {
                it.text.orEmpty().contains("<code>algorithm(") && it.isShowingInTree()
            })

            links(card).first { it.text == "Show symbol" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("The constructor scope should return", {
                viewers().size == 2 && viewers().any { it.editor.document.text == "    Worker() { setup(); }" }
            }, 10)
            assertTrue(fileViewer.editor.isDisposed)
            assertTrue(title(card).text.contains("<code>Worker("))
            assertTrue(links(card).first { it.text == "Show whole file" }.isVisible)
            press(canvas, "TAB") // A removed descendant must not retain keyboard focus.
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test an unopened class card can show its whole file in classic mode`() {
        val worker = "class Worker { void run() {} }\nclass Helper { void help() {} }"
        myFixture.addFileToProject("Worker.java", worker)
        val (result, schematic) = build("class Entry { void entry() { new Worker(); } }") {
            it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry")
        }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            descendants(panel).filterIsInstance<javax.swing.JCheckBox>().single { it.name == "schematic-native-editor" }.doClick()
            fun cards() = descendants(canvas).filterIsInstance<java.awt.Container>().filter { it.javaClass.simpleName == "SummaryCard" }
            PlatformTestUtil.waitWithEventsDispatching("The implicit constructor should lead to its class", { cards().size == 1 }, 10)
            val card = cards().single()
            press(canvas, "TAB")
            descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Show whole file" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Both classes' declarations should be available", { cards().size == 3 }, 10)
            assertTrue(descendants(canvas).filterIsInstance<SchematicSourceEditor>().isEmpty())
            assertTrue(descendants(card).filterIsInstance<javax.swing.JLabel>().first { it.text.orEmpty().contains("<code>") }
                .text.contains("<code>Worker.java</code>"))
            // Folding and reopening keeps the promoted scope and loaded declarations.
            val title = descendants(card).filterIsInstance<javax.swing.JLabel>().first { it.text.orEmpty().contains("<code>") }
            val declarations = cards().drop(1)
            click(title)
            click(title)
            assertEquals(declarations, cards().drop(1))
            press(canvas, "TAB")
            press(canvas, "TAB")
            assertTrue(descendants(card).filterIsInstance<javax.swing.JLabel>().any {
                it.text.orEmpty().contains("<code>help(") && it.isShowingInTree()
            })
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
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
            }.flatten().filterIsInstance<SchematicSourceEditor>().firstOrNull()
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { code() != null }, 10)
            fun press(stroke: String) {
                val key = canvas.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .get(javax.swing.KeyStroke.getKeyStroke(stroke))
                canvas.actionMap.get(key).actionPerformed(java.awt.event.ActionEvent(canvas, 0, stroke))
            }
            val fontSize = code()!!.editor.colorsScheme.editorFontSize2D
            val width = canvas.preferredSize.width
            press("typed +")
            assertEquals(1.2, panel.zoom, 1e-9)
            assertEquals(fontSize * 1.2f, code()!!.editor.colorsScheme.editorFontSize2D, 0.01f)
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

    private fun hoverMouse(component: java.awt.Component, event: Int = java.awt.event.MouseEvent.MOUSE_ENTERED) {
        // Real Swing events target an interactive descendant, rather than a passive BlockView or SummaryCard.
        val target = if (component.mouseListeners.isNotEmpty()) component else
            descendants(component as java.awt.Container).first { it.mouseListeners.isNotEmpty() }
        target.dispatchEvent(java.awt.event.MouseEvent(target, event, 0, 0, 1, 1, 0, false))
    }

    private fun layOut(component: java.awt.Component) {
        component.doLayout()
        (component as? java.awt.Container)?.components?.forEach(::layOut)
    }

    fun `test zoom cost with many unopened summaries`() {
        val count = 120
        val text = "class Flow {\n" + (0 until count).joinToString("\n") { "void step$it() {}" } + "\n}"
        val (result, schematic) = build(text) { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", {
                descendants(panel.canvas).count { it.javaClass.simpleName == "SummaryCard" } == count
            }, 20)
            panel.setSize(1000, 700)
            layOut(panel)
            val labels = descendants(panel.canvas).filterIsInstance<javax.swing.JLabel>()
                .filter { it.text.orEmpty().startsWith("<html><head>") }
            val renderers = labels.map { it.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) }
            val elapsed = (0 until 12).map { index ->
                val start = System.nanoTime()
                panel.zoomTo(if (index % 2 == 0) 0.75 else 1.0)
                (System.nanoTime() - start) / 1_000_000.0
            }
            println("Schematic zoom, $count unopened cards: median=${elapsed.sorted()[elapsed.size / 2]} ms, samples=$elapsed")
            panel.zoomTo(0.6)
            labels.forEachIndexed { index, label ->
                assertSame("Zoom must not rebuild hidden HTML", renderers[index],
                    label.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey))
            }
            val originalFont = labels.first().font.size2D
            press(panel.canvas, "TAB")
            press(panel.canvas, "ENTER")
            assertEquals("A revealed card catches up to the current zoom", originalFont * 0.6f, labels.first().font.size2D, 0.01f)
            assertNotSame(renderers.first(), labels.first().getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey))
            assertSame("Other cards stay untouched", renderers.last(), labels.last().getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey))
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test wheel bursts coalesce without losing zoom delta or cursor anchor`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", {
                descendants(canvas).any { it.javaClass.simpleName == "SummaryCard" }
            }, 10)
            panel.setSize(500, 300)
            layOut(panel)
            val native = descendants(canvas).filterIsInstance<SchematicSourceEditor>().single().editor
            val viewport = canvas.parent as javax.swing.JViewport
            native.contentComponent.dispatchEvent(java.awt.event.MouseWheelEvent(native.contentComponent, java.awt.event.MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 5, 5, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 2))
            assertTrue("Wheel input over native source should scroll the canvas", viewport.viewPosition.y > 0)
            assertEquals("The native viewer must not scroll independently", 0, native.scrollingModel.verticalScrollOffset)
            press(canvas, "HOME")
            repeat(3) { press(canvas, "PAGE_DOWN"); press(canvas, "shift PAGE_DOWN") }
            val previous = viewport.viewPosition
            val anchor = java.awt.Point(120, 90)
            fun wheel(target: javax.swing.JComponent = canvas) {
                val point = javax.swing.SwingUtilities.convertPoint(viewport, anchor, target)
                val event = java.awt.event.MouseWheelEvent(target, java.awt.event.MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), java.awt.event.InputEvent.CTRL_DOWN_MASK,
                    point.x, point.y, 0, 0, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1, -0.1)
                target.dispatchEvent(event)
                assertTrue(event.isConsumed)
            }
            repeat(20) { wheel(if (it % 2 == 0) native.contentComponent else canvas) }
            assertEquals("Wheel events defer layout to the next frame", 1.0, panel.zoom, 1e-9)
            val expected = 1.15 * 1.15
            PlatformTestUtil.waitWithEventsDispatching("The accumulated wheel delta should be applied", {
                kotlin.math.abs(panel.zoom - expected) < 1e-9
            }, 5)
            assertTrue(kotlin.math.abs((previous.x + anchor.x) * expected - anchor.x - viewport.viewPosition.x) <= 1)
            assertTrue(kotlin.math.abs((previous.y + anchor.y) * expected - anchor.y - viewport.viewPosition.y) <= 1)
            repeat(5) { wheel() }
            press(canvas, "typed 0")
            assertEquals("Reset clears a pending wheel update", 1.0, panel.zoom, 1e-9)
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test folded source and hidden explanations catch up when revealed after zoom`() {
        fun hover(component: java.awt.Component) = hoverMouse(component)
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val panel = editor.component as SchematicPanel
            val canvas = panel.canvas
            fun entry() = descendants(canvas).filterIsInstance<javax.swing.JLabel>()
                .firstOrNull { it.text.orEmpty().contains("<code>entry(") }
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { entry() != null }, 10)
            repeat(4) { press(canvas, "TAB") } // Store, Store, open, entry
            press(canvas, "ENTER")
            val title = entry()!!
            click(title)
            PlatformTestUtil.waitWithEventsDispatching("The method should unfold", {
                descendants(canvas).count { it.javaClass.simpleName == "SummaryCard" } == 12
            }, 10)
            val card = generateSequence<java.awt.Component>(title) { it.parent }
                .first { it.javaClass.simpleName == "SummaryCard" } as java.awt.Container
            val code = descendants(card).filterIsInstance<SchematicSourceEditor>().first { it.editor.document.text.contains("int x = 1;") }
            val originalFont = code.editor.colorsScheme.editorFontSize2D
            val explain = descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>()
                .first { it.text == "Explain link" }
            val nestedCard = generateSequence<java.awt.Component>(explain) { it.parent }
                .first { it.javaClass.simpleName == "SummaryCard" } as java.awt.Container
            val block = descendants(nestedCard.parent.components.first { it.javaClass.simpleName == "LineView" } as java.awt.Container)
                .first { it.javaClass.simpleName == "BlockView" }
            // Preview the nested call so its Explain link is accessible.
            hover(block)
            explain.doClick()
            val evidence = descendants(nestedCard).filterIsInstance<javax.swing.JLabel>()
                .first { it.text.orEmpty().contains("<head>") && it.text.orEmpty().contains("<br>") && it.isShowingInTree() &&
                    it.parent.layout is javax.swing.BoxLayout }
            val evidenceFont = evidence.font.size2D
            explain.doClick()
            val renderer = evidence.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey)
            // Fold a loaded branch, then change zoom while neither its code nor evidence is visible.
            click(title)
            panel.zoomTo(0.5)
            assertEquals(originalFont, code.editor.colorsScheme.editorFontSize2D, 0.01f)
            assertSame(renderer, evidence.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey))
            click(title)
            assertEquals(originalFont * 0.5f, code.editor.colorsScheme.editorFontSize2D, 0.01f)
            hover(block)
            explain.doClick()
            assertEquals(evidenceFont * 0.5f, evidence.font.size2D, 0.01f)
            assertNotSame(renderer, evidence.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey))
            val compact = descendants(card).filterIsInstance<com.intellij.ui.components.ActionLink>().first { it.text == "Compact" }
            compact.doClick()
            panel.zoomTo(0.75)
            assertEquals("Compact source stays untouched", originalFont * 0.5f, code.editor.colorsScheme.editorFontSize2D, 0.01f)
            compact.doClick()
            assertEquals(originalFont * 0.75f, code.editor.colorsScheme.editorFontSize2D, 0.01f)
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
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
            hoverMouse(blockTitle.parent)
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
                descendants(canvas).count { it.javaClass.simpleName == "SummaryCard" } == 12
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
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 9 }, 10)
            val root = result.graph.nodes[schematic.scope].name
            assertEquals(listOf(root), path())
            panel.setSize(700, 600)
            layOut(panel)
            val viewport = canvas.parent as javax.swing.JViewport
            val extent = java.awt.Dimension(viewport.extentSize)
            val trailFont = links().first().font.size2D
            repeat(4) { press(canvas, "TAB") } // Store, Store, open, entry
            click(title("entry"))
            PlatformTestUtil.waitWithEventsDispatching("Entry should unfold", { cards().size == 12 }, 10)
            assertEquals(listOf(root, "entry"), path())
            press(canvas, "TAB")
            press(canvas, "ENTER")
            click(title("check"))
            assertEquals(listOf(root, "entry", "check"), path())

            // Previewing another branch must not replace the chain the reader selected.
            val other = descendants(canvas).first { block ->
                block.javaClass.simpleName == "BlockView" && descendants(block as java.awt.Container).any { (it as? javax.swing.JLabel)?.text == "open" }
            }
            hoverMouse(other)
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
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 9 }, 10)
            panel.setSize(700, 600)
            layOut(panel)
            val viewport = canvas.parent as javax.swing.JViewport
            val block = descendants(canvas).filter { it.javaClass.simpleName == "BlockView" }[3]
            fun hover(target: java.awt.Component = block) = hoverMouse(target)
            hover()
            layOut(panel)
            val card = cards()[3] as java.awt.Container
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
            PlatformTestUtil.waitWithEventsDispatching("The branch should load without moving the view", { cards().size == 12 }, 10)
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

            // The call to open; the two Store links before it are references with no call evidence.
            hover(descendants(canvas).filter { it.javaClass.simpleName == "BlockView" }[2])
            val details = descendants(cards()[2] as java.awt.Container)
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
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 9 }, 10)
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
            repeat(9) { press(canvas, "TAB") }
            assertEquals(listOf(cards().first()), shown())
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test hover previews survive crossing to the card and clicks pin without unfolding`() {
        val (result, schematic) = build { it.kind == SymbolKind.FILE }
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val canvas = (editor.component as SchematicPanel).canvas
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 9 }, 10)
            val blocks = descendants(canvas).filter { it.javaClass.simpleName == "BlockView" }
            fun hover(component: java.awt.Component, event: Int = java.awt.event.MouseEvent.MOUSE_ENTERED) = hoverMouse(component, event)
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
            assertEquals("Pinning does not unfold source", 9, cards().size)
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test calls on one line are independent links with their own cards`() {
        val (result, schematic) = build(source.replace("store.save(x);", "check(kind); store.save(x);")) {
            it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry")
        }
        val row = schematic.rows.filterIsInstance<BlockRow>().single { it.split }
        val text = schematic.text(row.headline)
        assertEquals(listOf("check", "store", "save"), row.links.map { link -> text.substring(link.columns!!.first, link.columns!!.last + 1) })
        assertEquals(listOf("check", "store", "save"), row.links.map { result.name(it.targets.single()) })
        assertEquals("The field is a reference, not a call", listOf(false, true, false), row.links.map { it.reference })
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val canvas = (editor.component as SchematicPanel).canvas
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            fun shown() = cards().filter { it.isShowingInTree() }
            PlatformTestUtil.waitWithEventsDispatching("The method should be read", { cards().size == 4 }, 10)
            val line = descendants(canvas).filter { it.javaClass.simpleName == "LineView" }[1] as java.awt.Container
            assertEquals(listOf("check", "(kind); ", "store", ".", "save", "(x);"),
                descendants(line).filterIsInstance<javax.swing.JLabel>().map { it.text })
            press(canvas, "TAB")
            assertEquals(1, shown().size)
            press(canvas, "TAB")
            assertEquals("Each call previews only its own card", listOf(cards()[1]), shown())
            press(canvas, "ENTER")
            press(canvas, "TAB")
            assertEquals(listOf(cards()[1], cards()[2]), shown())
            press(canvas, "ESCAPE")
            assertEquals("Only the pinned call stays", listOf(cards()[1]), shown())
            click(descendants(line).filterIsInstance<javax.swing.JLabel>().single { it.text == "save" }.parent)
            assertEquals(listOf(cards()[1], cards()[3]), shown())
            val unpin = descendants(cards()[1] as java.awt.Container)
                .filterIsInstance<com.intellij.ui.components.ActionLink>().single { it.text == "Unpin" }
            unpin.doClick()
            assertEquals(listOf(cards()[3]), shown())
        } finally { com.intellij.openapi.util.Disposer.dispose(editor) }
    }

    fun `test types and constants are links whose cards show their declarations`() {
        val text = """
            import java.util.List;

            class Flow {
                static final int LIMIT = 3;

                void entry(Store store, int kind) {
                    int n = LIMIT + kind;
                    store.save(n);
                }
            }

            class Store {
                void save(int x) {}
            }
        """.trimIndent()
        fun Schematic.mentions(result: CallGraphResult) = rows.filterIsInstance<BlockRow>().flatMap { row ->
            row.links.filter { it.reference }.map { link ->
                text(row.headline).substring(link.columns!!.first, link.columns!!.last + 1) to result.name(link.targets.single())
            }
        }
        val (fileResult, file) = build(text) { it.kind == SymbolKind.FILE }
        assertEquals("A parameter type on a folded headline is a link", listOf("Store" to "Store"), file.mentions(fileResult))
        val entry = file.rows.filterIsInstance<BlockRow>().single { it.declared?.let { id -> fileResult.name(id) } == "entry" }
        assertEquals(listOf("entry", "Store"), entry.links.map { file.text(entry.headline).substring(it.columns!!.first, it.columns!!.last + 1) })
        assertFalse("Types are linked, not summarized", file.targets.any { fileResult.name(it) == "Store" })

        val (result, schematic) = build(text) { it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry") }
        assertEquals("Parameters and locals stay plain", listOf("Store" to "Store", "LIMIT" to "LIMIT"), schematic.mentions(result))
        val limit = result.graph.nodes.single { it.name == "LIMIT" }.id
        assertEquals("static final int LIMIT = 3;", schematic.excerpts[limit])

        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, schematic.scope))
        try {
            val canvas = (editor.component as SchematicPanel).canvas
            fun cards() = descendants(canvas).filter { it.javaClass.simpleName == "SummaryCard" }
            PlatformTestUtil.waitWithEventsDispatching("The method should be read", { cards().size == 3 }, 10)
            click(descendants(canvas).filterIsInstance<javax.swing.JLabel>().single { it.text == "LIMIT" }.parent)
            val shown = cards().filter { it.isShowingInTree() }.flatMap { descendants(it as java.awt.Container) }
                .filterIsInstance<javax.swing.JLabel>().map { it.text.orEmpty() }
            assertTrue(shown.toString(), shown.any { "static final int LIMIT = 3;" in it && "not called" in it })
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
            PlatformTestUtil.waitWithEventsDispatching("The file should be read", { cards().size == 9 }, 10)
            repeat(4) { press(canvas, "TAB") } // Store, Store, open, entry
            val entry = descendants(cards()[3] as java.awt.Container).filterIsInstance<javax.swing.JLabel>()
                .single { it.text.orEmpty().contains("<code>entry(") }
            click(entry)
            PlatformTestUtil.waitWithEventsDispatching("Entry should unfold", { cards().size == 12 }, 10)
            press(canvas, "TAB")
            assertTrue(shownNames().any { it.contains("<code>check(") })
            press(canvas, "TAB")
            assertTrue(shownNames().any { it.contains("<code>store</code>") })
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
