package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBUI
import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewVocabulary
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JToggleButton
import javax.swing.KeyStroke

class TreeSourcePreviewTest : BasePlatformTestCase() {
    private val files = listOf(
        CodeFile("src/a.kt", "/repo/src/a.kt", "fun a() = 1\n"),
        CodeFile("src/b.kt", "/repo/src/b.kt", (1..400).joinToString("\n") { "val line$it = $it" }),
    )

    fun testPreviewFollowsHoverWhenEnabledAndPinsFromTheRowIcon() {
        val builder = ChartsCanvasCard(ReviewVocabulary.CODE, {}, {}, project = project)
        try {
            builder.view.chart = "Tree"
            val card = builder.card(ChartsCard(ReviewSummaries(buildSummaryTree(files))))
            card.setSize(JBUI.scale(1200), JBUI.scale(800))
            layoutTree(card)
            val board = descendants(card).filterIsInstance<TreeBoardPanel>().single()
            val toggle = descendants(card).filterIsInstance<JToggleButton>().single { it.name == "charts-tree-source-preview-toggle" }
            assertFalse(toggle.isSelected)

            // Off: hovering shows nothing.
            hover(board, "src/a.kt")
            board.sourcePreview.flush()
            assertNull(board.sourcePreview.shown)

            // On: the hovered file shows beside its row, docked right, inside the board.
            toggle.doClick()
            assertTrue(builder.view.treeSourcePreview)
            hover(board, "src/a.kt")
            board.sourcePreview.flush()
            val shown = checkNotNull(board.sourcePreview.shown)
            assertEquals("src/a.kt", shown.target.path)
            val canvas = board.canvas
            assertTrue(canvas.bounds.contains(shown.bounds.apply { translate(canvas.x, canvas.y) }))
            assertEquals(canvas.width - JBUI.scale(TreeSourcePreviewController.MARGIN), shown.bounds.x + shown.bounds.width)
            val shortTop = shown.y

            // A long file takes most of the board and opens nearer its top.
            hover(board, "src/b.kt")
            board.sourcePreview.flush()
            val tall = checkNotNull(board.sourcePreview.shown)
            assertEquals("src/b.kt", tall.target.path)
            assertTrue(tall.height > canvas.height / 2)
            assertTrue(tall.y <= shortTop + JBUI.scale(30))
            val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { board.paint(it) }.dispose()

            // Folders stack their files.
            hover(board, "src")
            board.sourcePreview.flush()
            val folder = checkNotNull(board.sourcePreview.shown)
            assertTrue(folder.target.directory)
            assertEquals(2, descendants(folder).count { it.name?.startsWith("charts-tree-source-preview:") == true })

            // Off again: the row icon still pins a preview, and it stays while other rows are hovered.
            toggle.doClick()
            board.sourcePreview.flush()
            assertNull(board.sourcePreview.shown)
            hover(board, "src/a.kt")
            val eye = checkNotNull(board.canvas.previewButtonBounds(board.board.nodes.single { it.entry.path == "src/a.kt" }))
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_CLICKED, 0, 0,
                eye.centerX.toInt(), eye.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
            assertEquals("src/a.kt", board.pinnedPreviewPath)
            assertEquals("src/a.kt", builder.view.treePinnedPreview)
            assertEquals("src/a.kt", board.sourcePreview.shown?.target?.path)
            assertTrue(checkNotNull(board.sourcePreview.shown).isPinned)
            hover(board, "src/b.kt")
            board.sourcePreview.flush()
            assertEquals("src/a.kt", board.sourcePreview.shown?.target?.path)

            // The pin survives a rebuild of the page.
            val rebuilt = builder.card(ChartsCard(ReviewSummaries(buildSummaryTree(files)), revision = 0))
            rebuilt.setSize(JBUI.scale(1200), JBUI.scale(800))
            layoutTree(rebuilt)
            val again = descendants(rebuilt).filterIsInstance<TreeBoardPanel>().single()
            assertEquals("src/a.kt", again.pinnedPreviewPath)

            // Escape closes the pinned preview first, then P pins the cursor row again.
            triggerKey(again, KeyEvent.VK_ESCAPE)
            assertNull(again.pinnedPreviewPath)
            assertNull(again.sourcePreview.shown?.takeIf { it.isVisible })
            again.moveCursor(again.board.indexOf("src/b.kt"))
            triggerKey(again, KeyEvent.VK_P)
            assertEquals("src/b.kt", again.pinnedPreviewPath)
            triggerKey(again, KeyEvent.VK_P)
            assertNull(again.pinnedPreviewPath)
        } finally {
            builder.dispose()
        }
    }

    private fun hover(board: TreeBoardPanel, path: String) {
        val row = checkNotNull(board.rowScreenBounds(path))
        board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0,
            row.x + JBUI.scale(4), row.centerY.toInt(), 0, false))
    }

    private fun triggerKey(panel: TreeBoardPanel, keyCode: Int) {
        val stroke = KeyStroke.getKeyStroke(keyCode, 0)
        val actionId = panel.getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke)
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
