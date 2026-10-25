package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.UiInterceptors
import com.intellij.openapi.util.Disposer
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.event.PopupMenuEvent

class SummarySplitViewTest : BasePlatformTestCase() {
    private fun file(path: String) = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", "1")
    private val files get() = listOf("src/a.kt", "src/nested/b.kt", "docs/readme.md").map(::file)

    fun testTreeAndOneLineSummariesShareRowsHoverAndFolding() {
        val tree = buildSummaryTree(files)
        val long = "A long summary that stays on one line however wide it gets, never wrapping. ".repeat(12).trim()
        val summaries = ChangeSummaries(tree, tree.nodes.associate { it.id to if (it.path == "src/a.kt") long else "Summary of ${it.identity}." })
        val canvas = NoteCanvas({}, {}).apply { setSize(1600, 900); summariesView.mode = ChangeSummariesMode.SPLIT }
        fun named(name: String) = descendants(canvas).single { it.name == name } as JComponent
        fun treeRows() = descendants(canvas).mapNotNull { it.name }.filter { it.startsWith("summary-tree:") }.map { it.removePrefix("summary-tree:") }
        fun summaryRows() = descendants(canvas).mapNotNull { it.name }.filter { it.startsWith("summary:") }.map { it.removePrefix("summary:") }
        fun click(component: Component) {
            component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1))
            layoutTree(canvas)
        }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, false, true))
            layoutTree(canvas)
            val all = listOf("root", "directory:src", "file:src/a.kt", "directory:src/nested", "file:src/nested/b.kt", "directory:docs", "file:docs/readme.md")
                .filter { identity -> tree.nodes.any { it.identity == identity } }
            assertEquals(treeRows(), summaryRows())
            assertTrue(treeRows().containsAll(listOf("root", "file:src/a.kt", "file:docs/readme.md")))
            assertTrue(all.size <= treeRows().size)

            // Separate, resizable panels scroll horizontally on their own and vertically together.
            val scroll = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, named("summary:root")) as JScrollPane
            val treeScroll = named("summary-split-tree-scroll") as JScrollPane
            val panels = named("summary-split-panels") as JSplitPane
            assertSame(treeScroll, panels.leftComponent)
            assertSame(scroll, panels.rightComponent)
            assertSame(named("summary-split-tree"), treeScroll.viewport.view)
            assertSame(named("summary-split-summaries"), scroll.viewport.view)
            val treeX = treeScroll.viewport.viewPosition.x
            scroll.horizontalScrollBar.value = 200
            assertEquals(200, scroll.viewport.viewPosition.x)
            assertEquals(treeX, treeScroll.viewport.viewPosition.x)
            panels.dividerLocation = 240
            layoutTree(canvas)
            assertEquals(240, panels.dividerLocation)

            // Each summary sits at exactly the height and position of its tree row, on a single line.
            for (identity in treeRows()) {
                val left = named("summary-tree:$identity")
                val right = named("summary:$identity")
                assertEquals(identity, left.y, right.y)
                assertEquals(identity, left.height, right.height)
            }
            val area = descendants(named("summary:file:src/a.kt")).filterIsInstance<WrappedSummaryText>().single()
            assertEquals(long, area.text)
            val lineHeight = area.getFontMetrics(area.font).height
            assertTrue("one line, not wrapped", area.preferredSize.height < lineHeight * 2)
            assertTrue("wider than the viewport, so it scrolls", area.preferredSize.width > scroll.viewport.width)
            assertEquals(area.text.length, area.document.length)
            assertEquals(area.modelToView2D(0).y, area.modelToView2D(area.text.length - 1).y)

            // Hovering a file highlights its summary, and the other way round.
            val fileRow = named("summary-tree:file:src/a.kt")
            val summaryRow = named("summary:file:src/a.kt")
            assertFalse(summaryRow.isOpaque)
            val fileName = named("summary-name:file:src/a.kt")
            fileName.dispatchEvent(MouseEvent(fileName, MouseEvent.MOUSE_ENTERED, 0, 0, 2, 2, 0, false))
            assertTrue(summaryRow.isOpaque)
            assertEquals(fileRow.background, summaryRow.background)
            assertFalse(named("summary:file:docs/readme.md").isOpaque)
            fileName.dispatchEvent(MouseEvent(fileName, MouseEvent.MOUSE_EXITED, 0, 0, -500, -500, 0, false))
            assertFalse(summaryRow.isOpaque)
            assertFalse(fileRow.isOpaque)
            area.dispatchEvent(MouseEvent(area, MouseEvent.MOUSE_ENTERED, 0, 0, 2, 2, 0, false))
            assertTrue(named("summary-tree:file:src/a.kt").isOpaque)

            // Folding a folder hides its entries on both sides; unfolding brings them back.
            click(named("toggle:directory:src"))
            assertEquals("divider width survives folding", 240, (named("summary-split-panels") as JSplitPane).dividerLocation)
            assertFalse("directory:src" in canvas.summariesView.expanded)
            assertEquals(treeRows(), summaryRows())
            assertTrue("directory:src" in treeRows())
            assertTrue(treeRows().none { it.startsWith("file:src/") || it.startsWith("directory:src/") })
            click(named("summary-name:directory:src"))
            PlatformTestUtil.waitWithEventsDispatching("Unfold the folder after the double-click interval",
                { "file:src/a.kt" in summaryRows() }, 5)
            assertTrue("file:src/a.kt" in summaryRows())
            assertEquals(treeRows(), summaryRows())
        } finally { canvas.dispose() }
    }

    fun testSummariesWithLineBreaksStayOnOneLine() {
        val tree = buildSummaryTree(files)
        val text = "First sentence.\r\nSecond sentence.\u2028Third sentence.\u2029Last sentence on the same line."
        val summaries = ChangeSummaries(tree, mapOf(tree.root.id to text))
        val canvas = NoteCanvas({}, {}).apply { setSize(1600, 900); summariesView.mode = ChangeSummariesMode.SPLIT }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, false, true))
            layoutTree(canvas)
            val area = descendants(canvas).filterIsInstance<WrappedSummaryText>().single()
            assertEquals(text.map { if (it in "\r\n\u2028\u2029") ' ' else it }.joinToString(""), area.text)
            assertEquals(area.modelToView2D(0).y, area.modelToView2D(text.length - 1).y)
        } finally { canvas.dispose() }
    }

    fun testBothPanelsKeepRowsAlignedWhileScrollingAndResizingWithTagsCloud() {
        val tree = buildSummaryTree((1..80).map { file("src/${"longFileName".repeat(8)}$it.kt") })
        val summaries = ChangeSummaries(tree, tree.nodes.associate { it.id to "Summary words. ".repeat(40) })
        val canvas = NoteCanvas({}, {}).apply {
            setSize(1600, 900)
            summariesView.mode = ChangeSummariesMode.SPLIT
            summariesView.tagsCloud = true
            summariesView.selectLevel(ChangeSummariesViewState.branchLevels(tree), tree)
        }
        fun named(name: String) = descendants(canvas).single { it.name == name } as JComponent
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, false, true))
            repeat(3) { layoutTree(canvas) }
            val left = named("summary-split-tree-scroll") as JScrollPane
            val right = named("summary-split-summary-scroll") as JScrollPane
            val panels = named("summary-split-panels") as JSplitPane
            right.verticalScrollBar.value = 200
            assertEquals(200, right.viewport.viewPosition.y)
            assertEquals(right.viewport.viewPosition.y, left.viewport.viewPosition.y)
            right.verticalScrollBar.value = 400
            assertEquals(400, right.viewport.viewPosition.y)
            left.dispatchEvent(MouseWheelEvent(left, MouseEvent.MOUSE_WHEEL, 0, 0, 2, 2, 0, false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1))
            assertTrue("wheel over the tree scrolls down", right.viewport.viewPosition.y > 400)
            assertEquals(right.viewport.viewPosition.y, left.viewport.viewPosition.y)
            assertEquals("ordinary wheel keeps the tree's horizontal position", 0, left.viewport.viewPosition.x)
            left.horizontalScrollBar.value = 100
            right.horizontalScrollBar.value = 300
            assertEquals(100, left.viewport.viewPosition.x)
            assertEquals(300, right.viewport.viewPosition.x)
            panels.dividerLocation = 300
            repeat(3) { layoutTree(canvas) }
            for (node in tree.nodes) {
                val treeRow = named("summary-tree:${node.identity}")
                val summaryRow = named("summary:${node.identity}")
                assertEquals(node.identity, SwingUtilities.convertPoint(treeRow, 0, 0, canvas).y,
                    SwingUtilities.convertPoint(summaryRow, 0, 0, canvas).y)
                assertEquals(node.identity, treeRow.height, summaryRow.height)
            }
            for (area in descendants(right).filterIsInstance<WrappedSummaryText>()) {
                assertEquals(area.modelToView2D(0).y, area.modelToView2D(area.text.length - 1).y)
            }
        } finally { canvas.dispose() }
    }

    fun testEveryUnfoldedEntryHasASummaryBeyondTheOtherViewsRowLimit() {
        val tree = buildSummaryTree((1..HierarchyColumnsPanel.MAX_ROWS + 1).map { file("file$it.kt") },
            maxChildren = HierarchyColumnsPanel.MAX_ROWS + 1)
        val state = ChangeSummariesViewState().apply {
            mode = ChangeSummariesMode.SPLIT
            selectLevel(ChangeSummariesViewState.branchLevels(tree), tree)
        }
        val split = ChangeSummarySplitPanel(SummariesCard(ChangeSummaries(tree), false, true), state, 1.0,
            ChangeSummaryActions({}, {}, {}, {}))
        assertEquals(tree.nodes.size, split.summariesColumn.componentCount)
        assertTrue(split.summariesColumn.components.any { it.name == "summary:${tree.nodes.last().identity}" })
    }

    fun testContextMenuMarksAndUnmarksAnEntryAsReviewed() {
        val tree = buildSummaryTree(files)
        val node = tree.nodes.single { it.identity == "directory:src" }
        val reviewed = ReviewedFiles().apply { restore(files) }
        val canvas = NoteCanvas({}, {}, reviewed = reviewed).apply { setSize(1600, 900); summariesView.mode = ChangeSummariesMode.SPLIT }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(ChangeSummaries(tree), false, true))
            val row = descendants(canvas).single { it.name == "summary-tree:${node.identity}" } as JComponent
            val menu = row.componentPopupMenu
            fun item() = menu.components.filterIsInstance<JMenuItem>().single { it.name == "reviewed-menu:${node.identity}" }
            fun open() = menu.popupMenuListeners.forEach { it.popupMenuWillBecomeVisible(PopupMenuEvent(menu)) }
            fun checkbox() = descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "reviewed:${node.identity}" }
            assertEquals("Mark as reviewed", item().text)
            item().doClick()
            assertTrue(reviewed.isReviewed(node))
            assertTrue(checkbox().isSelected)
            open()
            assertEquals("Mark as not reviewed", item().text)
            item().doClick()
            assertFalse(reviewed.isReviewed(node))
            // A checkbox toggle relabels the menu the next time it opens.
            checkbox().doClick()
            open()
            assertEquals("Mark as not reviewed", item().text)
        } finally { canvas.dispose() }
    }

    fun testClickOpensACompactReaderWithOriginalParagraphsAndWrapping() {
        val tree = buildSummaryTree(files)
        val original = "A readable summary with words that should wrap naturally. ".repeat(20) +
            "\n\n- First detail\n- Second detail\n" + "LongIdentifier".repeat(40)
        val summaries = ChangeSummaries(tree, tree.nodes.associate { it.id to original })
        val canvas = NoteCanvas({}, {}).apply { setSize(1600, 900); summariesView.mode = ChangeSummariesMode.SPLIT }
        var dialogs = 0
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, false, true))
            layoutTree(canvas)
            for (identity in listOf("root", "file:src/a.kt")) {
                UiInterceptors.register(object : UiInterceptors.UiInterceptor<SummaryTextDialog>(SummaryTextDialog::class.java) {
                    override fun doIntercept(dialog: SummaryTextDialog) {
                        try {
                            val scroll = dialog.createCenterPanel() as JScrollPane
                            val area = scroll.viewport.view as JTextPane
                            assertEquals(original, area.text)
                            assertFalse(area.isEditable)
                            assertTrue(area.scrollableTracksViewportWidth)
                            assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)
                            assertTrue(scroll.preferredSize.width <= 700)
                            assertTrue(scroll.preferredSize.height <= 450)
                            scroll.setSize(scroll.preferredSize)
                            repeat(3) { layoutTree(scroll) }
                            assertTrue("Prose wraps to multiple lines", area.modelToView2D(0).y < area.modelToView2D(250).y)
                            assertTrue("Long summaries scroll vertically", area.preferredSize.height > scroll.viewport.height)
                            assertTrue("Long identifiers wrap inside the reader", area.modelToView2D(area.document.length - 1).maxX <= area.width)
                            dialogs++
                        } finally { dialog.close(0) }
                    }
                })
                val row = descendants(canvas).single { it.name == "summary:$identity" } as Container
                val area = descendants(row).filterIsInstance<WrappedSummaryText>().single()
                area.dispatchEvent(MouseEvent(area, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
                val expected = dialogs + 1
                PlatformTestUtil.waitWithEventsDispatching("Open summary reader", { dialogs == expected }, 5)
            }
            assertNull("Reading a summary does not select or fold its tree entry", canvas.summariesView.selected)
        } finally { UiInterceptors.clear(); canvas.dispose() }
    }

    fun testDoubleClickAndContextMenusNavigateInDiffAndCodeReviews() {
        for (vocabulary in listOf(ReviewVocabulary.DIFF, ReviewVocabulary.CODE)) {
            val sources = if (vocabulary == ReviewVocabulary.DIFF) files.map { it.copy(before = "old\n", after = "new\n") } else files
            val tree = buildSummaryTree(sources)
            val opened = mutableListOf<String>()
            val whole = mutableListOf<String>()
            val folders = mutableListOf<List<ReviewFile>>()
            val summaries = ChangeSummaries(tree, tree.nodes.associate { it.id to "Summary of ${it.identity}." })
            val canvas = NoteCanvas({}, {}, vocabulary = vocabulary, openNode = { opened += it.identity },
                openWholeFile = { whole += it.path },
                openDirectoryDiff = if (vocabulary == ReviewVocabulary.DIFF) ({ folders += it }) else null)
                .apply { setSize(1600, 900); summariesView.mode = ChangeSummariesMode.SPLIT }
            var dialogOpened = false
            val interception = Disposer.newDisposable()
            UiInterceptors.registerPossible(interception,
                object : UiInterceptors.UiInterceptor<SummaryTextDialog>(SummaryTextDialog::class.java) {
                    override fun doIntercept(dialog: SummaryTextDialog) { dialogOpened = true; dialog.close(0) }
                })
            try {
                canvas.showNotes(emptyList(), summaries = SummariesCard(summaries, false, true))
                layoutTree(canvas)
                val row = descendants(canvas).single { it.name == "summary:file:src/a.kt" } as Container
                val area = descendants(row).filterIsInstance<WrappedSummaryText>().single()
                for (clicks in 1..2) area.dispatchEvent(MouseEvent(area, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5,
                    clicks, false, MouseEvent.BUTTON1))
                assertEquals(listOf("file:src/a.kt"), opened)
                PlatformTestUtil.waitForAlarm(650)
                assertFalse("Double-click cancels the reader", dialogOpened)
                val menu = selectionMenu(area)!!
                val items = menu.components.filterIsInstance<JMenuItem>()
                items.single { it.text == if (vocabulary == ReviewVocabulary.DIFF) "Open diff" else "Open file" }.doClick()
                assertEquals(2, opened.size)
                if (vocabulary == ReviewVocabulary.DIFF) {
                    items.single { it.text == "Open file" }.doClick()
                    assertEquals(listOf("src/a.kt"), whole)
                }
                if (vocabulary == ReviewVocabulary.DIFF) {
                    val folderRow = descendants(canvas).single { it.name == "summary:directory:src" } as Container
                    val folderText = descendants(folderRow).filterIsInstance<WrappedSummaryText>().single()
                    for (clicks in 1..2) folderText.dispatchEvent(MouseEvent(folderText, MouseEvent.MOUSE_CLICKED, 0, 0,
                        5, 5, clicks, false, MouseEvent.BUTTON1))
                    assertEquals(setOf("src/a.kt", "src/nested/b.kt"), folders.single().map { it.path }.toSet())
                }
            } finally { Disposer.dispose(interception); UiInterceptors.clear(); canvas.dispose() }
        }
    }

    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }
}
