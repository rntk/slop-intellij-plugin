package dev.insight.review.ui

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.ui.components.JBTabbedPane
import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewVocabulary
import dev.insight.review.TreeFileConnections
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.KeyStroke

class TreeBoardConnectionsTest : LightJavaCodeInsightFixtureTestCase() {
    fun testFileButtonShowsResolvedConnectionsAcrossMetricChangesAndFolds() {
        val api = "api/Api.java"
        val client = "client/Client.java"
        val model = "model/Model.java"
        val imported = "model/Imported.java"
        myFixture.addFileToProject("outside/Outside.java", "package outside; public class Outside {}")
        val files = mapOf(
            api to """
                package api;
                import model.Imported;
                import outside.Outside;
                public class Api {
                    public static void run() { new model.Model(); new model.Model(); }
                }
            """.trimIndent(),
            client to "package client; class Client { void run() { api.Api.run(); } }",
            model to "package model; public class Model {}",
            imported to "package model; public class Imported {}",
        ).map { (path, source) ->
            val psi = myFixture.addFileToProject(path, source)
            CodeFile(path, psi.virtualFile.path, source)
        }
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, project = project, vocabulary = ReviewVocabulary.CODE)
        fun panel() = descendants(canvas).filterIsInstance<TreeBoardPanel>().single()
        fun status() = descendants(panel()).filterIsInstance<JLabel>().single { it.name == "charts-tree-connections" }.text
        fun clickConnections(board: TreeBoardPanel, path: String) {
            val button = checkNotNull(board.connectionButtonScreenBounds(path))
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_CLICKED, 0, 0,
                button.centerX.toInt(), button.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
        }
        try {
            canvas.setSize(1200, 800)
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
            descendants(canvas).filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }.selectedItem = "Tree"
            val initial = panel()
            assertNull(initial.connectionButtonScreenBounds("api"))
            clickConnections(initial, api)
            PlatformTestUtil.waitWithEventsDispatching("Tree code connections should resolve",
                { initial.connectionSelection?.connections != null }, 20)
            assertEquals(TreeFileConnections(setOf(client), setOf(model, imported)), initial.connectionSelection?.connections)
            assertTrue(opened.isEmpty())

            descendants(canvas).filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }.selectedIndex = 1
            val resized = panel()
            assertEquals(initial.connectionSelection, resized.connectionSelection)
            resized.toggleCollapsed("model")
            assertTrue(status().contains("2 hidden"))
            resized.toggleCollapsed("model")
            assertFalse(status().contains("hidden"))

            resized.canvas.setSize(900, 500)
            val gutter = resized.board.nodes.maxOf {
                checkNotNull(resized.rowScreenBounds(it.entry.path)).let { bounds -> bounds.x + bounds.width }
            } + 10
            fun paintGutter(): IntArray {
                val image = BufferedImage(900, 500, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    resized.canvas.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                return image.getRGB(gutter, 0, 900 - gutter, 500, null, 0, 900 - gutter)
            }
            val connected = paintGutter()
            val binding = resized.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0))
            resized.actionMap.get(binding).actionPerformed(ActionEvent(resized, 0, binding.toString()))
            assertNull(resized.connectionSelection)
            assertFalse("Connections should render to the right of the file rows", connected.contentEquals(paintGutter()))
            assertTrue(opened.isEmpty())
        } finally {
            canvas.dispose()
        }
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
