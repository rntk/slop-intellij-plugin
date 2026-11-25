package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTabbedPane
import dev.insight.codereview.CodeFile
import dev.insight.diff.ReviewFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewVocabulary
import dev.insight.review.Summary
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import dev.insight.review.buildTopicSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.KeyStroke
import kotlin.math.abs

class TreeBoardSidePanelsTest : BasePlatformTestCase() {
    fun testThreeColumnLayoutAndBidirectionalHighlightingInCodeReview() {
        val files = listOf(
            CodeFile("src/auth/Login.kt", "/repo/src/auth/Login.kt", "fun login() = true\n"),
            CodeFile("src/auth/Session.kt", "/repo/src/auth/Session.kt", "fun session() = true\n"),
            CodeFile("src/ui/Panel.kt", "/repo/src/ui/Panel.kt", "fun panel() = true\n"),
        )
        val tree = buildSummaryTree(files)
        val rootNode = tree.root
        val authDirNode = tree.scopeNode("src/auth", SummaryKind.DIRECTORY)!!
        val loginNode = tree.scopeNode("src/auth/Login.kt", SummaryKind.FILE)!!
        val summaries = ReviewSummaries(
            tree,
            mapOf(
                rootNode.id to Summary.plain("Overview of the review scope."),
                authDirNode.id to Summary.plain("Authentication package files."),
                loginNode.id to Summary.plain("Handles login validation."),
            ),
        )
        val topics = ReviewTopics(
            files = files,
            labels = mapOf(
                "src/auth/Login.kt" to "Security > Login Flow",
                "src/auth/Session.kt" to "Security > Session Store",
                "src/ui/Panel.kt" to "UI > View Rendering",
            ),
        )
        val topicTree = buildTopicSummaryTree(topics)
        val securityTopic = topicTree.nodes.first { it.identity == "topic:security" }
        val topicSummaries = ReviewSummaries(
            topicTree,
            mapOf(securityTopic.id to Summary.plain("Coordinates authentication and session security.")),
        )

        val canvas = NoteCanvas({}, {}, project = project, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
        fun components() = descendants(canvas)
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        fun tabs() = components().filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }
        fun boardPanel() = components().filterIsInstance<TreeBoardPanel>().single()
        fun textOf(name: String) = components().filterIsInstance<JTextArea>().single { it.name == name }.text

        try {
            canvas.showNotes(
                CanvasState(
                    emptyList(),
                    charts = ChartsCard(
                        summaries = summaries,
                        topics = topics,
                        topicSummaries = topicSummaries,
                    ),
                ),
            )
            selector().selectedItem = "Tree"

            val board = boardPanel().apply {
                setSize(1360, 680)
                layoutTree(this)
            }

            val summaryPanel = checkNotNull(board.summaryPanel)
            val railPanel = checkNotNull(board.railPanel)
            assertTrue(summaryPanel.width > 0)
            assertTrue(railPanel.width > 0)
            assertTrue(board.canvas.width > 0)
            assertEquals(summaryPanel.x + summaryPanel.width, board.canvas.x)
            assertEquals(board.canvas.x + board.canvas.width, railPanel.x)
            // Code Review shows a static "Topics" label without a Groups switcher.
            assertTrue(components().any { it.name == "charts-tree-rail-title" })
            assertTrue(components().none { it.name == "charts-tree-rail-mode" })

            // Initial cursor at root shows scope summary.
            assertEquals("Overview of the review scope.", textOf("charts-tree-summary-text"))

            // Hovering a file updates the left summary panel and topic badge.
            val loginBounds = checkNotNull(board.rowScreenBounds("src/auth/Login.kt"))
            board.canvas.dispatchEvent(
                MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0, loginBounds.centerX.toInt(), loginBounds.centerY.toInt(), 0, false),
            )
            assertEquals("Handles login validation.", textOf("charts-tree-summary-text"))
            assertEquals("Topic: Security > Login Flow", textOf("charts-tree-summary-topic"))

            // Hovering a folder shows the folder summary.
            val authBounds = checkNotNull(board.rowScreenBounds("src/auth"))
            board.canvas.dispatchEvent(
                MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0, authBounds.centerX.toInt(), authBounds.centerY.toInt(), 0, false),
            )
            assertEquals("Authentication package files.", textOf("charts-tree-summary-text"))

            // Clear canvas hover before interacting with right rail cards.
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_EXITED, 0, 0, 0, 0, 0, false))

            // Verify hierarchical columns (leaf innermost, parent outermost) and vertical alignment with tree rows.
            val securityBounds = checkNotNull(railPanel.cardScreenBounds("0:security"))
            val loginFlowBounds = checkNotNull(railPanel.cardScreenBounds("1:security > login flow"))
            assertTrue(loginFlowBounds.x < securityBounds.x)
            val sessionBounds = checkNotNull(board.rowScreenBounds("src/auth/Session.kt"))
            val railOriginY = railPanel.y + railPanel.railCanvas.y
            val treeOriginY = board.canvas.y
            assertTrue(abs((railOriginY + loginFlowBounds.y) - (treeOriginY + loginBounds.y)) <= 2)
            val expectedAuthTop = minOf(loginBounds.y, sessionBounds.y)
            val expectedAuthBottom = maxOf(loginBounds.y + loginBounds.height, sessionBounds.y + sessionBounds.height)
            assertTrue(abs((railOriginY + securityBounds.y) - (treeOriginY + expectedAuthTop)) <= 2)
            assertTrue(abs((railOriginY + securityBounds.y + securityBounds.height) - (treeOriginY + expectedAuthBottom)) <= 2)

            // Hovering a topic card shows the topic summary on the left and activates the card.
            val railCanvas = railPanel.railCanvas
            railCanvas.dispatchEvent(
                MouseEvent(railCanvas, MouseEvent.MOUSE_MOVED, 0, 0, securityBounds.centerX.toInt(), securityBounds.centerY.toInt(), 0, false),
            )
            assertEquals("0:security", board.activeRailCard?.key)
            assertEquals("Coordinates authentication and session security.", textOf("charts-tree-summary-text"))
            railCanvas.dispatchEvent(MouseEvent(railCanvas, MouseEvent.MOUSE_EXITED, 0, 0, -10, -10, 0, false))

            // Clicking a topic card selects it; selection survives metric tab switches.
            railCanvas.dispatchEvent(
                MouseEvent(railCanvas, MouseEvent.MOUSE_CLICKED, 0, 0, securityBounds.centerX.toInt(), securityBounds.centerY.toInt(), 1, false, MouseEvent.BUTTON1),
            )
            assertEquals("0:security", board.selectedRailCardKey)
            assertEquals("Coordinates authentication and session security.", textOf("charts-tree-summary-text"))

            tabs().selectedIndex = 1
            val afterMetricSwitch = boardPanel().apply { setSize(1360, 680); layoutTree(this) }
            assertEquals("0:security", afterMetricSwitch.selectedRailCardKey)
            assertEquals("Coordinates authentication and session security.", textOf("charts-tree-summary-text"))

            // Control panel button hides and shows the Topics rail and persists across metric tab switches.
            val toggleRailBtn = components().filterIsInstance<JButton>().single { it.name == "charts-tree-toggle-rail" }
            assertTrue(toggleRailBtn.isVisible)
            assertEquals("Hide topics", toggleRailBtn.text)
            assertTrue(afterMetricSwitch.isRailVisible)
            toggleRailBtn.doClick()
            assertFalse(afterMetricSwitch.isRailVisible)
            assertFalse(checkNotNull(afterMetricSwitch.railPanel).isVisible)
            assertNull(afterMetricSwitch.activeRailCard)
            assertEquals("Show topics", toggleRailBtn.text)

            tabs().selectedIndex = 0
            val afterHideSwitch = boardPanel().apply { setSize(1360, 680); layoutTree(this) }
            assertFalse(afterHideSwitch.isRailVisible)
            assertFalse(checkNotNull(afterHideSwitch.railPanel).isVisible)
            val toggleBtnAfterSwitch = components().filterIsInstance<JButton>().single { it.name == "charts-tree-toggle-rail" }
            assertEquals("Show topics", toggleBtnAfterSwitch.text)
            toggleBtnAfterSwitch.doClick()
            assertTrue(afterHideSwitch.isRailVisible)
            assertTrue(checkNotNull(afterHideSwitch.railPanel).isVisible)
            assertEquals("Hide topics", toggleBtnAfterSwitch.text)

            // Pressing Escape clears the rail selection.
            triggerKey(afterHideSwitch, KeyEvent.VK_ESCAPE)
            assertNull(afterHideSwitch.selectedRailCardKey)

            // Paint in normal and zoomed states without error, keeping alignment after zoom and fold.
            val image = BufferedImage(1360, 680, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { afterHideSwitch.paint(it) }.dispose()
            afterHideSwitch.zoomTo(1.4)
            image.createGraphics().also { afterHideSwitch.paint(it) }.dispose()

            val zoomedRail = checkNotNull(afterHideSwitch.railPanel)
            val zoomedCardBounds = checkNotNull(zoomedRail.cardScreenBounds("1:security > login flow"))
            val zoomedRowBounds = checkNotNull(afterHideSwitch.rowScreenBounds("src/auth/Login.kt"))
            val zoomedRailY = zoomedRail.y + zoomedRail.railCanvas.y
            val zoomedTreeY = afterHideSwitch.canvas.y
            assertTrue(abs((zoomedRailY + zoomedCardBounds.y) - (zoomedTreeY + zoomedRowBounds.y)) <= 2)

            afterHideSwitch.toggleCollapsed("src/auth")
            val collapsedAuthBounds = checkNotNull(afterHideSwitch.rowScreenBounds("src/auth"))
            val collapsedSecurityBounds = checkNotNull(zoomedRail.cardScreenBounds("0:security"))
            assertTrue(abs((zoomedRailY + collapsedSecurityBounds.y) - (zoomedTreeY + collapsedAuthBounds.y)) <= 2)
            assertNull(zoomedRail.cardScreenBounds("1:security > login flow"))
        } finally {
            canvas.dispose()
        }
    }

    fun testDiffReviewSwitchesBetweenTopicsAndGroupsAndFiltersLevels() {
        val files = listOf(
            ReviewFile("src/a.kt", "/repo/src/a.kt", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "fp1"),
            ReviewFile("src/b.kt", "/repo/src/b.kt", null, "added", "@@ -0,0 +1 @@\n+added\n", "fp2"),
        )
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree)
        val topics = ReviewTopics(
            files = files,
            labels = mapOf(
                "src/a.kt" to "Core > Parsing > Tokens",
                "src/b.kt" to "Core > Formatting",
            ),
        )
        val grouping = ReviewTopics(
            files = files,
            labels = mapOf(
                "src/a.kt" to "Fix bug > Null handling",
                "src/b.kt" to "Fix bug > Tests",
            ),
        )
        val groupingTree = buildTopicSummaryTree(grouping)
        val fixGroup = groupingTree.nodes.first { it.identity == "topic:fix bug" }
        val groupingSummaries = ReviewSummaries(
            groupingTree,
            mapOf(fixGroup.id to Summary.plain("Guards against missing tokens.")),
        )

        val canvas = NoteCanvas({}, {}, project = project, vocabulary = ReviewVocabulary.DIFF).apply { setSize(1600, 900) }
        fun components() = descendants(canvas)
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        fun modeCombo() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-tree-rail-mode" }
        fun levelCombo() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-tree-rail-level" }
        fun boardPanel() = components().filterIsInstance<TreeBoardPanel>().single()

        try {
            canvas.showNotes(
                CanvasState(
                    emptyList(),
                    charts = ChartsCard(
                        summaries = summaries,
                        topics = topics,
                        grouping = grouping,
                        groupingSummaries = groupingSummaries,
                    ),
                ),
            )
            selector().selectedItem = "Tree"
            boardPanel().apply { setSize(1360, 680); layoutTree(this) }

            // Initially shows Topics with all 3 levels (Core, Parsing, Tokens, Formatting).
            assertEquals("Topics", modeCombo().selectedItem)
            assertNotNull(checkNotNull(boardPanel().railPanel).cardScreenBounds("2:core > parsing > tokens"))

            // Level selector filters depth to level 1 (index 0).
            levelCombo().selectedIndex = 0
            val filteredBoard = boardPanel().apply { setSize(1360, 680); layoutTree(this) }
            val filteredRail = checkNotNull(filteredBoard.railPanel)
            assertNull(filteredRail.cardScreenBounds("2:core > parsing > tokens"))
            assertNotNull(filteredRail.cardScreenBounds("0:core"))

            // Switching to Groups shows the grouping cards, updates the rail toggle label, and shows group summaries.
            modeCombo().selectedItem = "Groups"
            assertEquals("Groups", modeCombo().selectedItem)
            assertEquals("Hide groups", components().filterIsInstance<JButton>().single { it.name == "charts-tree-toggle-rail" }.text)
            val groupBoard = boardPanel().apply { setSize(1360, 680); layoutTree(this) }
            val groupRail = checkNotNull(groupBoard.railPanel)
            val groupBounds = checkNotNull(groupRail.cardScreenBounds("0:fix bug"))
            groupRail.railCanvas.dispatchEvent(
                MouseEvent(groupRail.railCanvas, MouseEvent.MOUSE_CLICKED, 0, 0, groupBounds.centerX.toInt(), groupBounds.centerY.toInt(), 1, false, MouseEvent.BUTTON1),
            )
            val summaryText = components().filterIsInstance<JTextArea>().single { it.name == "charts-tree-summary-text" }.text
            assertEquals("Guards against missing tokens.", summaryText)
            assertEquals("Group summary", components().filterIsInstance<JLabel>().single { it.name == "charts-tree-summary-kind" }.text)
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
