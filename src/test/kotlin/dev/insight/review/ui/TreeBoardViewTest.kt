package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import dev.insight.codereview.CodeFile
import dev.insight.diff.ReviewFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewVocabulary
import dev.insight.review.Summary
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class TreeBoardViewTest : BasePlatformTestCase() {
    fun testTreeToolbarKeepsSearchAndBoardControlsOnOneRow() {
        val file = CodeFile("a.kt", "/repo/a.kt", "one\n")
        val scope = CoroutineScope(SupervisorJob())
        val builder = ChartsCanvasCard(ReviewVocabulary.CODE, {}, {}, search = ChartSearchActions(scope) { error("Unused") })
        try {
            builder.view.chart = "Tree"
            builder.view.searchStatus = "The search failed. ${"Long message. ".repeat(20)}"
            val card = builder.card(ChartsCard(ReviewSummaries(buildSummaryTree(listOf(file)))))
            card.setSize(JBUI.scale(860), JBUI.scale(740))
            layoutTree(card)
            val components = descendants(card)
            fun component(name: String) = components.single { it.name == name }
            val tabs = component("charts-lines-metric-tabs")
            val toolbar = component("charts-controls")
            val controls = listOf("charts-type-selector", "charts-search-field", "charts-search-send",
                "charts-tree-fit", "charts-tree-zoom-in", "charts-tree-total").map(::component)
            val bounds = controls.map { SwingUtilities.convertRectangle(it.parent, it.bounds, card) }

            assertTrue(bounds.all { it.width > 0 && it.x >= 0 && it.maxX <= card.width && it.maxY <= tabs.y })
            assertTrue(bounds.maxOf { it.y } < bounds.minOf { it.maxY })
            assertEquals(toolbar.y + toolbar.height, tabs.y)
            val board = components.filterIsInstance<TreeBoardPanel>().single()
            assertEquals(board.insets.top, board.canvas.y)

            val boardBounds = board.canvas.bounds
            board.connectionSelection = TreeConnectionSelection("${"nested/".repeat(30)}a.kt", message = "Resolving code connections…")
            layoutTree(card)
            val status = component("charts-tree-connections") as JLabel
            assertTrue(status.isVisible)
            assertEquals(status.text, status.toolTipText)
            assertEquals(boardBounds, board.canvas.bounds)
        } finally {
            builder.dispose()
            scope.cancel()
        }
    }

    fun testTreeBoardSharesTabsFoldsDirectoriesNavigatesHighlightsAndZooms() {
        val files = listOf(
            CodeFile("src/a.kt", "/repo/src/a.kt", "one\ntwo\n"),
            CodeFile("src/nested/b.kt", "/repo/src/nested/b.kt", "three\nfour\nfive\n"),
            CodeFile("readme.md", "/repo/readme.md", "six\n"),
        )
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
        fun components() = descendants(canvas)
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        fun tabs() = components().filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }
        fun boardPanel() = components().filterIsInstance<TreeBoardPanel>().single()
        fun button(name: String) = components().filterIsInstance<JButton>().single { it.name == name }

        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
            selector().selectedItem = "Tree"

            assertNotNull(tabs())
            val panel = boardPanel().apply {
                setSize(900, 600)
                doLayout()
            }
            assertEquals("6 lines · 3 files", components().filterIsInstance<JLabel>().single { it.name == "charts-tree-total" }.text)
            assertEquals("", panel.cursorPath)

            // Hover updates hoveredPath and status label.
            val bBounds = checkNotNull(panel.rowScreenBounds("src/nested/b.kt"))
            panel.canvas.dispatchEvent(MouseEvent(panel.canvas, MouseEvent.MOUSE_MOVED, 0, 0, bBounds.centerX.toInt(), bBounds.centerY.toInt(), 0, false))
            assertEquals("src/nested/b.kt", panel.hoveredPath)
            assertTrue(components().filterIsInstance<JLabel>().single { it.name == "charts-tree-status" }.text.contains("src/nested/b.kt"))

            // Double-click opens a file.
            panel.canvas.dispatchEvent(MouseEvent(panel.canvas, MouseEvent.MOUSE_CLICKED, 0, 0, bBounds.centerX.toInt(), bBounds.centerY.toInt(), 2, false, MouseEvent.BUTTON1))
            assertEquals(listOf("src/nested/b.kt"), opened)

            // Clicking chevron folds and unfolds directory; Fold all / Unfold all also work.
            val srcToggle = checkNotNull(panel.toggleScreenBounds("src"))
            panel.canvas.dispatchEvent(MouseEvent(panel.canvas, MouseEvent.MOUSE_CLICKED, 0, 0, srcToggle.centerX.toInt(), srcToggle.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
            assertEquals(-1, panel.board.indexOf("src/nested/b.kt"))
            assertEquals(setOf("src"), canvas.chartsView.treeCollapsed)

            // Switching metric tabs keeps collapsed directories and cursor.
            tabs().selectedIndex = 1
            val afterMetricSwitch = boardPanel().apply { setSize(900, 600); doLayout() }
            assertEquals(setOf("src"), canvas.chartsView.treeCollapsed)
            assertEquals(-1, afterMetricSwitch.board.indexOf("src/nested/b.kt"))
            assertEquals("src", afterMetricSwitch.cursorPath)

            // Unfold all restores full tree; Fold all collapses directories.
            button("charts-tree-unfold-all").doClick()
            assertTrue(afterMetricSwitch.board.indexOf("src/nested/b.kt") >= 0)
            button("charts-tree-fold-all").doClick()
            assertEquals(-1, afterMetricSwitch.board.indexOf("src/nested/b.kt"))
            button("charts-tree-unfold-all").doClick()

            // Keyboard navigation: move down, collapse, move to file, press Enter to open.
            triggerKey(afterMetricSwitch, KeyEvent.VK_HOME)
            assertEquals("", afterMetricSwitch.cursorPath)
            triggerKey(afterMetricSwitch, KeyEvent.VK_DOWN)
            assertEquals("src", afterMetricSwitch.cursorPath)
            triggerKey(afterMetricSwitch, KeyEvent.VK_LEFT)
            assertEquals(-1, afterMetricSwitch.board.indexOf("src/a.kt"))
            triggerKey(afterMetricSwitch, KeyEvent.VK_RIGHT)
            assertTrue(afterMetricSwitch.board.indexOf("src/a.kt") >= 0)
            triggerKey(afterMetricSwitch, KeyEvent.VK_END)
            assertEquals("readme.md", afterMetricSwitch.cursorPath)
            triggerKey(afterMetricSwitch, KeyEvent.VK_ENTER)
            assertEquals(listOf("src/nested/b.kt", "readme.md"), opened)

            // Zoom controls and fit.
            val baseZoom = afterMetricSwitch.zoom
            button("charts-tree-zoom-in").doClick()
            assertTrue(afterMetricSwitch.zoom > baseZoom)
            button("charts-tree-zoom-out").doClick()
            assertEquals(baseZoom, afterMetricSwitch.zoom, 1e-4)
            button("charts-tree-fit").doClick()
            assertTrue(afterMetricSwitch.zoom > 0.0)

            // Paint in normal, hovered, and collapsed states without error.
            val image = BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { afterMetricSwitch.paint(it) }.dispose()
            afterMetricSwitch.foldAll()
            image.createGraphics().also { afterMetricSwitch.paint(it) }.dispose()
        } finally {
            canvas.dispose()
        }
    }

    fun testTreeBoardDiffSummariesTogglePerFileAndAll() {
        val files = listOf(
            ReviewFile("src/a.kt", "/repo/src/a.kt", null, "modified", "@@ -1 +1,2 @@\n-old\n+one\n+two\n", "fp1"),
            ReviewFile("src/nested/b.kt", "/repo/src/nested/b.kt", null, "added", "@@ -0,0 +1 @@\n+three\n", "fp2"),
            ReviewFile("readme.md", "/repo/readme.md", null, "modified", "@@ -1 +1 @@\n-a\n+b\n", "fp3"),
        )
        val tree = buildSummaryTree(files)
        val emptySummaries = ReviewSummaries(tree)
        val aNode = tree.scopeNode("src/a.kt", SummaryKind.FILE)!!
        val bNode = tree.scopeNode("src/nested/b.kt", SummaryKind.FILE)!!
        val withSummaries = emptySummaries.summarize(
            mapOf(
                aNode.id to Summary.plain("Adds validation for incoming diff payloads and updates the handler."),
                bNode.id to Summary.plain("Introduces helper utilities for nested diff parsing."),
            ),
        )
        val canvas = NoteCanvas({}, {}, project = project, vocabulary = ReviewVocabulary.DIFF).apply { setSize(1400, 900) }
        fun components() = descendants(canvas)
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        fun tabs() = components().filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }
        fun boardPanel() = components().filterIsInstance<TreeBoardPanel>().single()
        fun button(name: String) = components().filterIsInstance<JButton>().single { it.name == name }

        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(emptySummaries)))
            selector().selectedItem = "Tree"
            val emptyBoard = boardPanel().apply { setSize(1000, 700); doLayout() }
            assertFalse(button("charts-tree-toggle-summaries").isVisible)
            assertNull(emptyBoard.summaryButtonScreenBounds("src/a.kt"))

            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(withSummaries, revision = 1)))
            val board = boardPanel().apply { setSize(1000, 700); doLayout() }
            val toggleAll = button("charts-tree-toggle-summaries")
            assertTrue(toggleAll.isVisible)
            assertEquals("Show summaries", toggleAll.text)
            assertNull(board.summaryButtonScreenBounds("src"))
            assertNull(board.summaryButtonScreenBounds("readme.md"))
            val aSummaryBtn = checkNotNull(board.summaryButtonScreenBounds("src/a.kt"))
            assertNotNull(board.summaryButtonScreenBounds("src/nested/b.kt"))
            board.connectionButtonScreenBounds("src/a.kt")?.let { assertFalse(aSummaryBtn.intersects(it)) }
            board.focusButtonScreenBounds("src/a.kt")?.let { assertFalse(aSummaryBtn.intersects(it)) }
            assertTrue(board.canvas.getToolTipText(MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0,
                aSummaryBtn.centerX.toInt(), aSummaryBtn.centerY.toInt(), 0, false)).orEmpty().contains("Show diff summary"))

            val bRowYBefore = checkNotNull(board.rowScreenBounds("src/nested/b.kt")).y
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_CLICKED, 0, 0,
                aSummaryBtn.centerX.toInt(), aSummaryBtn.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
            assertEquals(setOf("src/a.kt"), board.shownSummaryPaths)
            assertNotNull(board.summaryBlockScreenBounds("src/a.kt"))
            assertTrue(checkNotNull(board.rowScreenBounds("src/nested/b.kt")).y > bRowYBefore)
            assertEquals("Show summaries", toggleAll.text)
            assertTrue(board.canvas.getToolTipText(MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0,
                aSummaryBtn.centerX.toInt(), aSummaryBtn.centerY.toInt(), 0, false)).orEmpty().contains("Hide diff summary"))

            // Click again collapses the file summary and restores row positions.
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_CLICKED, 0, 0,
                aSummaryBtn.centerX.toInt(), aSummaryBtn.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
            assertTrue(board.shownSummaryPaths.isEmpty())
            assertNull(board.summaryBlockScreenBounds("src/a.kt"))
            assertEquals(bRowYBefore, checkNotNull(board.rowScreenBounds("src/nested/b.kt")).y)

            // Pressing D toggles the summary for the cursor file.
            board.moveCursor(board.board.indexOf("src/a.kt"))
            triggerKey(board, KeyEvent.VK_D)
            assertEquals(setOf("src/a.kt"), board.shownSummaryPaths)

            // Control panel button shows all available summaries and switches to "Hide summaries".
            toggleAll.doClick()
            assertEquals(setOf("src/a.kt", "src/nested/b.kt"), board.shownSummaryPaths)
            assertEquals("Hide summaries", button("charts-tree-toggle-summaries").text)
            assertNotNull(board.summaryBlockScreenBounds("src/a.kt"))
            assertNotNull(board.summaryBlockScreenBounds("src/nested/b.kt"))

            // Survives metric tab switch (Added lines -> Removed lines) and paints cleanly at normal & zoomed scales.
            tabs().selectedIndex = 1
            val afterTab = boardPanel().apply { setSize(1000, 700); doLayout() }
            assertEquals(setOf("src/a.kt", "src/nested/b.kt"), afterTab.shownSummaryPaths)
            assertEquals("Hide summaries", button("charts-tree-toggle-summaries").text)
            val image = BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { afterTab.paint(it) }.dispose()
            afterTab.zoomTo(1.5)
            image.createGraphics().also { afterTab.paint(it) }.dispose()

            // Control panel button hides all summaries again.
            button("charts-tree-toggle-summaries").doClick()
            assertTrue(afterTab.shownSummaryPaths.isEmpty())
            assertEquals("Show summaries", button("charts-tree-toggle-summaries").text)
            assertNull(afterTab.summaryBlockScreenBounds("src/a.kt"))
        } finally {
            canvas.dispose()
        }
    }

    private fun triggerKey(panel: TreeBoardPanel, keyCode: Int) {
        val stroke = KeyStroke.getKeyStroke(keyCode, 0)
        val actionId = panel.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke)
            ?: panel.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(stroke)
            ?: error("No binding for $keyCode")
        panel.actionMap.get(actionId).actionPerformed(java.awt.event.ActionEvent(panel, 0, actionId.toString()))
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()

    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }
}
