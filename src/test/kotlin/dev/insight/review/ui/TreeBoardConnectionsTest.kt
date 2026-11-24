package dev.insight.review.ui

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import dev.insight.codereview.CodeFile
import dev.insight.context.ContextKeys
import dev.insight.graph.RelationKind
import dev.insight.review.ReviewLineChart
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewVocabulary
import dev.insight.review.TreeConnectionSite
import dev.insight.review.TreeFileConnections
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.KeyStroke

class TreeBoardConnectionsTest : LightJavaCodeInsightFixtureTestCase() {
    fun testPinnedConnectionExplainsOnDemandWithLinkedMarkdown() {
        myFixture.addFileToProject("src/B.java", "class B {}")
        val requests = AtomicInteger()
        val response = CompletableDeferred<String>()
        val site = TreeConnectionSite(1, 1)
        fun preview(edge: TreeConnectionSite) = TreeConnectionPreviewPanel(
            "src/A.java", "src/B.java", edge, pinned = true,
            project = project,
            explain = { requests.incrementAndGet(); response.await() },
        )
        val panel = preview(site)
        try {
            val button = descendants(panel).filterIsInstance<JButton>()
                .single { it.name == "charts-tree-explain-connection" }
            val explanation = descendants(panel).filterIsInstance<JComponent>()
                .single { it.name == "charts-tree-connection-explanation" }
            assertEquals(0, requests.get())
            assertFalse(explanation.isVisible)

            button.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Explanation should start on request", { requests.get() == 1 }, 10)
            assertTrue(explanation.isVisible)
            assertFalse(button.isEnabled)
            assertTrue(descendants(explanation).filterIsInstance<JLabel>().any { it.text.contains("Explaining") })

            val markdown = "Calls the declaration in `src/B.java:L1`."
            response.complete(markdown)
            PlatformTestUtil.waitWithEventsDispatching("Explanation should display", {
                descendants(explanation).filterIsInstance<LinkedTextPane>().isNotEmpty()
            }, 10)
            val text = descendants(explanation).filterIsInstance<LinkedTextPane>().single()
            assertEquals(markdown, text.markdown)
            text.addNotify()
            try {
                PlatformTestUtil.waitWithEventsDispatching("Explanation should resolve its file link", {
                    text.renderedLinks.any { it.primaryPath == "src/B.java" && it.lines == 1..1 }
                }, 10)
            } finally {
                text.removeNotify()
            }
            panel.cancelExplanation()
            val reopened = preview(site)
            assertEquals(markdown, descendants(reopened).filterIsInstance<LinkedTextPane>().single().markdown)
            assertFalse(descendants(reopened).filterIsInstance<JButton>().single { it.name == button.name }.isEnabled)
            assertTrue(descendants(preview(site.copy())).filterIsInstance<LinkedTextPane>().isEmpty())
            assertEquals(1, requests.get())
        } finally {
            panel.cancelExplanation()
        }
    }

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
            client to """
                package client;
                import api.Api;
                class Client {
                    void run() {
                        Api.run();
                        Api.run();
                    }
                }
            """.trimIndent(),
            model to "package model; public class Model {}",
            imported to "package model;\n\npublic class Imported {}",
        ).map { (path, source) ->
            val psi = myFixture.addFileToProject(path, source)
            CodeFile(path, psi.virtualFile.path, source)
        }
        val opened = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, project = project, vocabulary = ReviewVocabulary.CODE)
        fun panel() = descendants(canvas).filterIsInstance<TreeBoardPanel>().single()
        fun status() = descendants(canvas).filterIsInstance<JLabel>().single { it.name == "charts-tree-connections" }.text
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
            val connections = checkNotNull(initial.connectionSelection?.connections)
            val incomingClient = checkNotNull(connections.incoming[client])
            assertEquals(5, incomingClient.fromLine)
            assertEquals(5, incomingClient.toLine)
            assertEquals(3, incomingClient.totalOccurrences)
            assertEquals(
                listOf(
                    Triple(RelationKind.CALL, 5, 5),
                    Triple(RelationKind.CALL, 6, 5),
                    Triple(RelationKind.IMPORT, 2, 4),
                ),
                incomingClient.occurrences.map { Triple(it.kind, it.fromLine, it.toLine) },
            )
            assertTrue(incomingClient.occurrences[0].fromSnippet?.lines?.any { it.contains("Api.run();") } == true)
            assertTrue(incomingClient.occurrences[0].toSnippet?.lines?.any { it.contains("public static void run()") } == true)
            assertTrue(incomingClient.occurrences[2].fromSnippet?.lines?.any { it.contains("import api.Api;") } == true)
            assertTrue(incomingClient.occurrences[2].toSnippet?.lines?.any { it.contains("public class Api") } == true)

            val outgoingModel = checkNotNull(connections.outgoing[model])
            assertEquals(5, outgoingModel.fromLine)
            assertEquals(1, outgoingModel.toLine)
            assertTrue(outgoingModel.occurrences.isNotEmpty())
            assertTrue(outgoingModel.occurrences.first().fromSnippet?.lines?.any { it.contains("new model.Model()") } == true)
            assertTrue(outgoingModel.occurrences.first().toSnippet?.lines?.any { it.contains("public class Model") } == true)

            val outgoingImported = checkNotNull(connections.outgoing[imported])
            assertEquals(2, outgoingImported.fromLine)
            assertEquals(3, outgoingImported.toLine)
            assertEquals(RelationKind.IMPORT, outgoingImported.occurrences.single().kind)
            assertTrue(outgoingImported.occurrences.single().fromSnippet?.lines?.any { it.contains("import model.Imported;") } == true)
            assertTrue(outgoingImported.occurrences.single().toSnippet?.lines?.any { it.contains("public class Imported") } == true)
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
            val clientRow = checkNotNull(resized.rowScreenBounds(client))
            val nearIncoming = MouseEvent(resized.canvas, MouseEvent.MOUSE_MOVED, 0, 0, gutter,
                clientRow.centerY.toInt() - JBUI.scale(8), 0, false)
            resized.canvas.dispatchEvent(nearIncoming)
            assertTrue(resized.canvas.getToolTipText(nearIncoming).orEmpty().contains("$client → $api"))
            assertFalse("Hover should highlight the incoming link", connected.contentEquals(paintGutter()))
            resized.canvas.dispatchEvent(MouseEvent(resized.canvas, MouseEvent.MOUSE_EXITED, 0, 0, -1, -1, 0, false))
            assertTrue(connected.contentEquals(paintGutter()))
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

    fun testLinkHitAreaAndPickerPreserveDirection() {
        val a = CodeFile("a.kt", "/src/a.kt", "val a = 1")
        val b = CodeFile("b.kt", "/src/b.kt", "val b = a")
        val chart = ReviewLineChart(buildSummaryTree(listOf(a, b)))
        val incoming = TreeConnectionSite(3, 7)
        val outgoing = TreeConnectionSite(4, 9)
        val opened = mutableListOf<Triple<String, String, TreeConnectionSite>>()
        val board = TreeBoardPanel(chart, "Project", {}, openConnection = { from, to, site, _ -> opened += Triple(from, to, site) })
        val canvas = board.canvas
        canvas.setSize(800, 400)
        board.connectionSelection = TreeConnectionSelection(a.path, TreeFileConnections(mapOf(b.path to incoming), mapOf(b.path to outgoing)))
        val bounds = checkNotNull(board.rowScreenBounds(b.path))
        val x = board.board.nodes.maxOf {
            checkNotNull(board.rowScreenBounds(it.entry.path)).let { row -> row.x + row.width }
        } + JBUI.scale(12)
        fun event(id: Int, offset: Int, button: Int, popup: Boolean = false) =
            MouseEvent(canvas, id, 0, 0, x, bounds.centerY.toInt() + JBUI.scale(offset), 1, popup, button)
        // Four pixels beyond either horizontal stroke, including the gaps in the dashed incoming link.
        val hover = event(MouseEvent.MOUSE_MOVED, -8, MouseEvent.NOBUTTON)
        canvas.dispatchEvent(hover)
        assertEquals(Cursor.HAND_CURSOR, canvas.cursor.type)
        assertTrue(canvas.getToolTipText(hover).orEmpty().contains("b.kt → a.kt"))

        // Left-clicking a link pins the snippet preview window rather than immediately opening the context picker.
        canvas.dispatchEvent(event(MouseEvent.MOUSE_CLICKED, -8, MouseEvent.BUTTON1))
        assertTrue(board.connectionPreview.isPinned)
        assertEquals(b.path to a.path, board.connectionPreview.activePair)
        val preview = checkNotNull(board.connectionPreview.activePanel)
        assertTrue(opened.isEmpty())
        descendants(preview).filterIsInstance<ActionLink>()
            .single { it.name == "charts-tree-connection-open-context" }
            .doClick()
        assertNull(board.connectionPreview.activePanel)

        // Right-clicking a link directly opens the context picker for that link.
        canvas.dispatchEvent(event(MouseEvent.MOUSE_PRESSED, 8, MouseEvent.BUTTON3))
        canvas.dispatchEvent(event(MouseEvent.MOUSE_RELEASED, 8, MouseEvent.BUTTON3, popup = true))
        assertEquals(listOf(Triple(b.path, a.path, incoming), Triple(a.path, b.path, outgoing)), opened)

        // Clearing selection also dismisses any pinned preview.
        canvas.dispatchEvent(event(MouseEvent.MOUSE_PRESSED, -8, MouseEvent.BUTTON1))
        canvas.dispatchEvent(event(MouseEvent.MOUSE_RELEASED, -8, MouseEvent.BUTTON1))
        canvas.dispatchEvent(event(MouseEvent.MOUSE_CLICKED, -8, MouseEvent.BUTTON1))
        assertNotNull(board.connectionPreview.activePanel)
        board.connectionSelection = null
        assertNull(board.connectionPreview.activePanel)
        assertFalse(board.connectionPreview.isPinned)

        val pick = treeConnectionPick(b, a, incoming, emptyMap(), null)
        assertEquals(listOf(b.path, a.path), pick.candidates.single().files)
        assertEquals(listOf(b.absolutePath, a.absolutePath), pick.data[ContextKeys.LINKED_FILES]?.map { it.absolutePath })

        assertEquals(listOf(3..3, 7..7), pick.data[ContextKeys.LINKED_FILES]?.map { it.lines })

        canvas.dispatchEvent(hover)
        assertEquals(Cursor.DEFAULT_CURSOR, canvas.cursor.type)
        assertNull(canvas.getToolTipText(hover))
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
