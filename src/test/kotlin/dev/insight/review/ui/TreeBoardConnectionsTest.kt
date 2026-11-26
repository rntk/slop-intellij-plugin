package dev.insight.review.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import dev.insight.codereview.CodeFile
import dev.insight.context.ContextKeys
import dev.insight.graph.RelationKind
import dev.insight.llm.Completion
import dev.insight.review.ReviewLineChart
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewVocabulary
import dev.insight.review.TreeBrief
import dev.insight.review.TreeBriefer
import dev.insight.review.TreeBriefs
import dev.insight.review.TreeConnectionSite
import dev.insight.review.TreeFileConnections
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Rectangle
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
    private fun clickRowAction(board: TreeBoardPanel, bounds: Rectangle) {
        val x = bounds.centerX.toInt()
        val y = bounds.centerY.toInt()
        board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false))
        board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1))
    }

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

    fun testExplanationSectionResizesWithWindowAndSplitter() {
        fun layoutRecursively(component: Component) {
            component.doLayout()
            if (component is Container) {
                component.components.forEach(::layoutRecursively)
            }
        }

        val siteWithExplanation = TreeConnectionSite(1, 1).apply {
            explanation = "Calls `B`."
        }
        val panel = TreeConnectionPreviewPanel(
            "src/A.java", "src/B.java", siteWithExplanation, pinned = true,
            project = project,
            explain = { "Calls `B`." },
        )
        try {
            val splitter = descendants(panel).filterIsInstance<OnePixelSplitter>()
                .single { it.name == "charts-tree-connection-split" }
            assertTrue(splitter.orientation)
            val explanation = descendants(panel).filterIsInstance<JComponent>()
                .single { it.name == "charts-tree-connection-explanation" }
            assertTrue(explanation.isVisible)

            panel.setSize(640, 420)
            layoutRecursively(panel)
            val smallHeight = explanation.height
            assertTrue("Explanation height should be positive at 420px window height", smallHeight > 0)

            panel.setSize(640, 720)
            layoutRecursively(panel)
            val largeHeight = explanation.height
            assertTrue("Explanation height ($largeHeight) should grow with window height ($smallHeight)", largeHeight > smallHeight)

            splitter.proportion = 0.65f
            layoutRecursively(panel)
            val smallerProportionHeight = explanation.height
            splitter.proportion = 0.30f
            layoutRecursively(panel)
            val largerProportionHeight = explanation.height
            assertTrue(
                "Moving splitter up should grow explanation height ($largerProportionHeight vs $smallerProportionHeight)",
                largerProportionHeight > smallerProportionHeight,
            )
        } finally {
            panel.cancelExplanation()
        }

        val hiddenPanel = TreeConnectionPreviewPanel(
            "src/A.java", "src/B.java", TreeConnectionSite(1, 1), pinned = true,
            project = project,
            explain = { "Calls `B`." },
        )
        try {
            val hiddenSplitter = descendants(hiddenPanel).filterIsInstance<OnePixelSplitter>()
                .single { it.name == "charts-tree-connection-split" }
            val snippetsScroll = hiddenSplitter.firstComponent as JBScrollPane
            val hiddenExplanation = hiddenSplitter.secondComponent as JComponent
            assertFalse(hiddenExplanation.isVisible)

            hiddenPanel.setSize(640, 420)
            layoutRecursively(hiddenPanel)
            assertEquals(hiddenSplitter.height, snippetsScroll.height)
        } finally {
            hiddenPanel.cancelExplanation()
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
            clickRowAction(board, button)
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

    fun testConnectionsStartRightOfExpandedSummaryBlocks() {
        val a = CodeFile("a.kt", "/src/a.kt", "val a = 1")
        val b = CodeFile("b.kt", "/src/b.kt", "val b = a")
        val chart = ReviewLineChart(buildSummaryTree(listOf(a, b)))
        val site = TreeConnectionSite(1, 1)
        val board = TreeBoardPanel(
            chart,
            "Project",
            {},
            fileSummaries = mapOf(
                a.path to "Adds a detailed diff summary explaining the validation and formatting changes across the file so the summary block extends well past the file row.",
            ),
            expandedSummaries = setOf(a.path),
            diffMode = true,
        )
        val canvas = board.canvas
        canvas.setSize(1000, 500)
        board.connectionSelection = TreeConnectionSelection(a.path, TreeFileConnections(emptyMap(), mapOf(b.path to site)))

        val aRow = checkNotNull(board.rowScreenBounds(a.path))
        val aSummary = checkNotNull(board.summaryBlockScreenBounds(a.path))
        assertTrue(aSummary.x + aSummary.width > aRow.x + aRow.width)

        val insideSummary = MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0, 0, aSummary.centerX.toInt(), aSummary.centerY.toInt(), 0, false)
        assertNull(canvas.getToolTipText(insideSummary))

        val onLane = MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0, 0, aSummary.x + aSummary.width + JBUI.scale(24), aSummary.centerY.toInt(), 0, false)
        assertTrue(canvas.getToolTipText(onLane).orEmpty().contains("a.kt → b.kt"))
    }

    fun testFocusModeReducesTreeToLinkedSubtreeAcrossTabsConnectionsAndExitPaths() {
        val api = "api/Api.java"
        val client = "client/Client.java"
        val model = "model/Model.java"
        val helper = "model/Helper.java"
        val unrelated = "unrelated/Other.java"
        val files = mapOf(
            api to """
                package api;
                public class Api {
                    public static void run() { new model.Model(); }
                }
            """.trimIndent(),
            client to """
                package client;
                import api.Api;
                class Client {
                    void go() { Api.run(); }
                }
            """.trimIndent(),
            model to """
                package model;
                public class Model {
                    public Model() { new Helper(); }
                }
            """.trimIndent(),
            helper to "package model; public class Helper {}",
            unrelated to "package unrelated; public class Other {}",
        ).map { (path, source) ->
            val psi = myFixture.addFileToProject(path, source)
            CodeFile(path, psi.virtualFile.path, source)
        }
        val canvas = NoteCanvas({}, {}, project = project, vocabulary = ReviewVocabulary.CODE)
        fun panel() = descendants(canvas).filterIsInstance<TreeBoardPanel>().single()
        fun paths(board: TreeBoardPanel) = board.board.nodes.map { it.entry.path }
        fun clickFocus(board: TreeBoardPanel, path: String) {
            val button = checkNotNull(board.focusButtonScreenBounds(path))
            clickRowAction(board, button)
        }
        fun clickConnections(board: TreeBoardPanel, path: String) {
            val button = checkNotNull(board.connectionButtonScreenBounds(path))
            clickRowAction(board, button)
        }
        fun triggerKey(board: TreeBoardPanel, keyCode: Int) {
            val stroke = KeyStroke.getKeyStroke(keyCode, 0)
            val actionId = board.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke)
                ?: error("No binding for $keyCode")
            board.actionMap.get(actionId).actionPerformed(ActionEvent(board, 0, actionId.toString()))
        }
        try {
            canvas.setSize(1200, 800)
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
            descendants(canvas).filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }.selectedItem = "Tree"
            val initial = panel()

            // Root has no focus button; directories and files have non-overlapping focus & connection buttons.
            assertNull(initial.focusButtonScreenBounds(""))
            assertNotNull(initial.focusButtonScreenBounds("api"))
            val apiFocusBounds = checkNotNull(initial.focusButtonScreenBounds(api))
            val apiConnBounds = checkNotNull(initial.connectionButtonScreenBounds(api))
            assertFalse(apiFocusBounds.intersects(apiConnBounds))

            // Collapse `unrelated` beforehand; exiting focus mode should restore prior collapsed folders.
            initial.toggleCollapsed("unrelated")
            assertEquals(setOf("unrelated"), canvas.chartsView.treeCollapsed)

            // Focus on file `api/Api.java`: keeps api, client, model and their parent folders; hides helper and unrelated.
            clickFocus(initial, api)
            PlatformTestUtil.waitWithEventsDispatching("Tree focus should resolve for file",
                { initial.focusSelection?.links != null }, 20)
            assertEquals(listOf("", "client", client, "model", model, "api", api), paths(initial))

            val exitButton = descendants(canvas).filterIsInstance<JButton>()
                .single { it.name == "charts-tree-exit-focus" }
            assertTrue(exitButton.isVisible)
            val focusLabel = descendants(canvas).filterIsInstance<JLabel>()
                .single { it.name == "charts-tree-focus-status" }
            assertTrue(focusLabel.text.contains("Focused: $api"))

            // Focus persists across metric tab switches and composes with code connections.
            descendants(canvas).filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }.selectedIndex = 1
            val afterTab = panel()
            assertEquals(listOf("", "model", model, "api", api, "client", client), paths(afterTab))
            clickConnections(afterTab, api)
            PlatformTestUtil.waitWithEventsDispatching("Connections should resolve while focused",
                { afterTab.connectionSelection?.connections != null }, 20)
            assertEquals(api, afterTab.connectionSelection?.path)

            // Exit via clicking the focus icon again.
            clickFocus(afterTab, api)
            assertNull(afterTab.focusSelection)
            assertFalse(descendants(canvas).filterIsInstance<JButton>().single { it.name == "charts-tree-exit-focus" }.isVisible)
            assertTrue(paths(afterTab).containsAll(listOf("api", api, "model", model, helper, "unrelated")))
            // Prior collapsed directory `unrelated` is restored (its child `Other.java` remains hidden).
            assertFalse(unrelated in paths(afterTab))
            assertEquals(setOf("unrelated"), canvas.chartsView.treeCollapsed)

            // Focus on directory `model`: includes `model/Model.java`, `model/Helper.java`, and incoming `api/Api.java`.
            clickFocus(afterTab, "model")
            PlatformTestUtil.waitWithEventsDispatching("Tree focus should resolve for directory",
                { afterTab.focusSelection?.links != null }, 20)
            assertEquals(listOf("", "model", model, helper, "api", api), paths(afterTab))

            // Exit via "Show all" button.
            descendants(canvas).filterIsInstance<JButton>().single { it.name == "charts-tree-exit-focus" }.doClick()
            assertNull(afterTab.focusSelection)
            assertFalse(unrelated in paths(afterTab))

            // Toggle focus on and off via `F` key, and exit via `Esc` key.
            afterTab.moveCursor(afterTab.board.indexOf(api))
            triggerKey(afterTab, KeyEvent.VK_F)
            PlatformTestUtil.waitWithEventsDispatching("F key should enable focus",
                { afterTab.focusSelection?.links != null }, 20)
            assertEquals(listOf("", "model", model, "api", api, "client", client), paths(afterTab))
            triggerKey(afterTab, KeyEvent.VK_F)
            assertNull(afterTab.focusSelection)

            triggerKey(afterTab, KeyEvent.VK_F)
            PlatformTestUtil.waitWithEventsDispatching("F key should re-enable focus",
                { afterTab.focusSelection?.links != null }, 20)
            triggerKey(afterTab, KeyEvent.VK_ESCAPE)
            assertNull(afterTab.focusSelection)
        } finally {
            canvas.dispose()
        }
    }

    fun testBriefButtonSummarizesFileAndLinksBesideRowsAndLinksStartAfterThem() {
        val api = "api/Api.java"
        val client = "client/Client.java"
        val model = "model/Model.java"
        val files = mapOf(
            api to "package api;\npublic class Api {\n    public static void run() { new model.Model(); }\n}",
            client to "package client;\nclass Client {\n    void go() { api.Api.run(); }\n}",
            model to "package model; public class Model {}",
        ).map { (path, source) ->
            val psi = myFixture.addFileToProject(path, source)
            CodeFile(path, psi.virtualFile.path, source)
        }
        val requests = AtomicInteger()
        val briefer = TreeBriefer({ request ->
            requests.incrementAndGet()
            val input = request.messages.last().content
            Completion(when {
                input.startsWith("File:") -> "Starts the service\nand creates models"
                input.lineSequence().first().endsWith("Client.java") -> "calls `Api.run` to start at api/Api.java:L3"
                else -> "- creates `Model` instances"
            })
        }, emptyList())
        val canvas = NoteCanvas({}, {}, project = project, vocabulary = ReviewVocabulary.CODE,
            summaryActions = SummaryCanvasActions(treeBriefer = { briefer }))
        fun panel() = descendants(canvas).filterIsInstance<TreeBoardPanel>().single()
        try {
            canvas.setSize(1200, 800)
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)))))
            descendants(canvas).filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }.selectedItem = "Tree"
            val board = panel()
            assertNull(board.briefButtonScreenBounds("api"))
            val button = checkNotNull(board.briefButtonScreenBounds(api))
            assertFalse(button.intersects(checkNotNull(board.connectionButtonScreenBounds(api))))
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0,
                button.centerX.toInt(), button.centerY.toInt(), 0, false))
            assertTrue(board.canvas.getToolTipText(MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0,
                button.centerX.toInt(), button.centerY.toInt(), 0, false)).orEmpty().contains("Summarize"))
            clickRowAction(board, button)
            PlatformTestUtil.waitWithEventsDispatching("Briefs should finish",
                { board.briefs?.let { !it.running && it.total == 3 } == true }, 20)
            // Clicking briefs also shows the file's links, so the briefs explain what is drawn.
            assertEquals(api, board.connectionSelection?.path)
            assertEquals(3, requests.get())
            val summarizedClientSite = checkNotNull(board.connectionSelection?.connections?.incoming?.get(client))

            val apiBrief = board.canvas.briefSegments(api).single()
            assertEquals("Starts the service and creates models", apiBrief.text)
            val clientBrief = board.canvas.briefSegments(client).single()
            assertEquals("→ ", clientBrief.prefix)
            assertEquals("calls `Api.run` to start at api/Api.java:L3", clientBrief.text)
            val modelBrief = board.canvas.briefSegments(model).single()
            assertEquals("← ", modelBrief.prefix)
            assertEquals("creates `Model` instances", modelBrief.text)
            assertTrue(board.canvas.briefSegments("").isEmpty())
            assertTrue(descendants(canvas).filterIsInstance<JLabel>()
                .single { it.name == "charts-tree-connections" }.text.contains("Summarized"))

            val clientRow = checkNotNull(board.rowScreenBounds(client))
            val y = clientRow.centerY.toInt()
            fun linkX(text: String): Int? = (clientRow.x + clientRow.width until clientRow.x + clientRow.width + JBUI.scale(600))
                .firstOrNull { x ->
                    val event = MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false)
                    board.canvas.getToolTipText(event).orEmpty().contains(text)
                }

            board.addNotify()
            try {
                PlatformTestUtil.waitWithEventsDispatching("Brief file and symbol links should resolve", {
                    linkX("Symbol: Api.run") != null && linkX("api/Api.java:L3") != null
                }, 20)
                val fileLinkX = checkNotNull(linkX("api/Api.java:L3"))
                board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0, fileLinkX, y, 0, false))
                assertEquals(Cursor.HAND_CURSOR, board.canvas.cursor.type)
                board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_CLICKED, 0, 0,
                    fileLinkX, y, 1, false, MouseEvent.BUTTON1))
                PlatformTestUtil.waitWithEventsDispatching("Clicking a brief link should open Api.java", {
                    FileEditorManager.getInstance(project).selectedFiles.any { it.path.endsWith(api) }
                }, 20)
            } finally {
                board.removeNotify()
            }

            // Connection routes start to the right of the longest row brief.
            val justAfterRow = MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0,
                board.board.nodes.maxOf { checkNotNull(board.rowScreenBounds(it.entry.path)).let { r -> r.x + r.width } } + JBUI.scale(12),
                clientRow.centerY.toInt() - JBUI.scale(4), 0, false)
            assertFalse(board.canvas.getToolTipText(justAfterRow).orEmpty().contains("→"))

            // Briefs survive a metric change, reuse cached results, and S toggles them off.
            descendants(canvas).filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }.selectedIndex = 1
            val rebuilt = panel()
            assertEquals(board.briefs, rebuilt.briefs)
            rebuilt.moveCursor(rebuilt.board.indexOf(api))
            val binding = rebuilt.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0))
            rebuilt.actionMap.get(binding).actionPerformed(ActionEvent(rebuilt, 0, binding.toString()))
            assertNull(rebuilt.briefs)
            rebuilt.actionMap.get(binding).actionPerformed(ActionEvent(rebuilt, 0, binding.toString()))
            PlatformTestUtil.waitWithEventsDispatching("Cached briefs should reappear",
                { rebuilt.briefs?.running == false }, 20)
            assertEquals(3, requests.get())

            // Showing another file's links drops the briefs, which describe only the briefed file's links.
            checkNotNull(rebuilt.connectionButtonScreenBounds(client)).let {
                clickRowAction(rebuilt, it)
            }
            assertNull(rebuilt.briefs)
            PlatformTestUtil.waitWithEventsDispatching("Client links should resolve", {
                rebuilt.connectionSelection?.path == client && rebuilt.connectionSelection?.connections != null
            }, 20)
            assertSame(summarizedClientSite, rebuilt.connectionSelection?.connections?.outgoing?.get(api))
            assertEquals("calls `Api.run` to start at api/Api.java:L3", summarizedClientSite.brief)

            // A new review revision with the same file scope retains the live-source graph and its cached briefs.
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(ReviewSummaries(buildSummaryTree(files)), revision = 1)))
            val refreshed = panel()
            checkNotNull(refreshed.connectionButtonScreenBounds(client)).let {
                clickRowAction(refreshed, it)
            }
            PlatformTestUtil.waitWithEventsDispatching("Links should resolve after review refresh", {
                refreshed.connectionSelection?.path == client && refreshed.connectionSelection?.connections != null
            }, 20)
            assertSame(summarizedClientSite, refreshed.connectionSelection?.connections?.outgoing?.get(api))

            // Removing a file from the review scope discards the old graph, including links to that file.
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(
                ReviewSummaries(buildSummaryTree(files.filter { it.path != client })), revision = 2,
            )))
            val narrower = panel()
            checkNotNull(narrower.connectionButtonScreenBounds(api)).let {
                clickRowAction(narrower, it)
            }
            PlatformTestUtil.waitWithEventsDispatching("Links should reflect the changed scope", {
                narrower.connectionSelection?.path == api && narrower.connectionSelection?.connections != null
            }, 20)
            assertFalse(checkNotNull(narrower.connectionSelection?.connections).incoming.containsKey(client))
        } finally {
            canvas.dispose()
        }
    }

    fun testBriefsAlignPerDirectoryWithLeaderLinesAndFullRowHoverBand() {
        val shortA = CodeFile("dirA/a.kt", "/src/dirA/a.kt", "val a = 1")
        val longA = CodeFile("dirA/very_long_target_file_name_for_alignment.kt", "/src/dirA/very_long_target_file_name_for_alignment.kt", "val l = 1")
        val targetA = CodeFile("dirA/target.kt", "/src/dirA/target.kt", "val t = 1")
        val shortB = CodeFile("dirB/b.kt", "/src/dirB/b.kt", "val b = 1")
        val chart = ReviewLineChart(buildSummaryTree(listOf(shortA, longA, targetA, shortB)))
        val board = TreeBoardPanel(chart, "Project", {})
        board.setSize(1200, 600)
        board.doLayout()
        board.canvas.setSize(1200, 600)

        val site = TreeConnectionSite(1, 1)
        board.connectionSelection = TreeConnectionSelection(
            targetA.path,
            TreeFileConnections(
                incoming = mapOf(shortA.path to site, shortB.path to site),
                outgoing = mapOf(longA.path to site),
            ),
        )
        board.briefs = TreeBriefs(
            path = targetA.path,
            file = TreeBrief.Ready("Coordinates dirA flow"),
            incoming = mapOf(
                shortA.path to TreeBrief.Ready("Calls target"),
                shortB.path to TreeBrief.Ready("Calls target from dirB"),
            ),
            outgoing = mapOf(longA.path to TreeBrief.Ready("Reads aligned helper")),
        )

        val shortABriefX = checkNotNull(board.briefStartScreenX(shortA.path))
        val longABriefX = checkNotNull(board.briefStartScreenX(longA.path))
        val targetABriefX = checkNotNull(board.briefStartScreenX(targetA.path))
        val shortBBriefX = checkNotNull(board.briefStartScreenX(shortB.path))

        assertEquals(longABriefX, shortABriefX)
        assertEquals(longABriefX, targetABriefX)
        assertTrue("Separate directory should align tighter ($shortBBriefX < $shortABriefX)", shortBBriefX < shortABriefX)

        fun render(): BufferedImage = BufferedImage(1200, 600, BufferedImage.TYPE_INT_ARGB).also { img ->
            val g = img.createGraphics()
            try {
                board.canvas.paint(g)
            } finally {
                g.dispose()
            }
        }

        val beforeHover = render()
        val aRow = checkNotNull(board.rowScreenBounds(shortA.path))
        val rootRow = checkNotNull(board.rowScreenBounds(""))
        val bgRgb = beforeHover.getRGB(0, aRow.centerY.toInt())

        val leaderProbeStart = aRow.x + aRow.width + JBUI.scale(8)
        val leaderProbeEnd = shortABriefX - JBUI.scale(8)
        assertTrue(leaderProbeEnd > leaderProbeStart)
        val hasLeaderPixels = (leaderProbeStart..leaderProbeEnd).any { x ->
            beforeHover.getRGB(x, aRow.centerY.toInt()) != bgRgb
        }
        assertTrue("Gap between short sibling row and aligned brief should contain leader line", hasLeaderPixels)

        val bandProbeX = rootRow.x.coerceAtLeast(0)
        val bandProbeY = aRow.y + JBUI.scale(4)
        val beforeBandRgb = beforeHover.getRGB(bandProbeX, bandProbeY)

        board.canvas.dispatchEvent(
            MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0, shortABriefX + JBUI.scale(12), aRow.centerY.toInt(), 0, false),
        )
        assertEquals(shortA.path, board.hoveredPath)

        val afterHover = render()
        assertFalse(
            "Hovering over a row's brief should paint the full-row highlight band from the tree's left edge",
            beforeBandRgb == afterHover.getRGB(bandProbeX, bandProbeY),
        )
    }

    fun testMetricNumbersRenderInLeftDotLeaderColumnAndAreInteractive() {
        val a = CodeFile("src/a.kt", "/src/dir/a.kt", "one\ntwo\nthree\n")
        val b = CodeFile("b.kt", "/src/b.kt", "four\n")
        val chart = ReviewLineChart(buildSummaryTree(listOf(a, b)))
        val board = TreeBoardPanel(chart, "Project", {})
        board.setSize(900, 400)
        board.doLayout()
        board.canvas.setSize(900, 400)

        val rootRow = checkNotNull(board.rowScreenBounds(""))
        assertTrue("Left metric lane should reserve space left of root row", rootRow.x > JBUI.scale(20))

        val image = BufferedImage(900, 400, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            board.canvas.paint(g)
        } finally {
            g.dispose()
        }
        val bgRgb = image.getRGB(0, rootRow.centerY.toInt())
        val hasMetricPixels = (0 until (rootRow.x - JBUI.scale(4))).any { x ->
            image.getRGB(x, rootRow.centerY.toInt()) != bgRgb
        }
        assertTrue("Left column should render dot-leader metric numbers", hasMetricPixels)

        val aRow = checkNotNull(board.rowScreenBounds("src/a.kt"))
        board.canvas.dispatchEvent(
            MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0, rootRow.x - JBUI.scale(16), aRow.centerY.toInt(), 0, false),
        )
        assertEquals("src/a.kt", board.hoveredPath)
    }

    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
