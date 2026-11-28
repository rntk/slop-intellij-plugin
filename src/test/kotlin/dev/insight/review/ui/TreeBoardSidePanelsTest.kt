package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBFont
import dev.insight.codereview.CodeFile
import dev.insight.context.ContextActionRegistry
import dev.insight.context.ContextKeys
import dev.insight.context.ContextRequest
import dev.insight.context.ContextScopeKind
import dev.insight.context.ReviewDestination
import dev.insight.diff.ReviewFile
import dev.insight.review.ReviewLineChart
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewVocabulary
import dev.insight.review.Summary
import dev.insight.review.SummaryKind
import dev.insight.review.TreeRailCard
import dev.insight.review.TreeRailMode
import dev.insight.review.buildSummaryTree
import dev.insight.review.buildTopicSummaryTree
import dev.insight.review.buildTreeRailModel
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import kotlin.math.abs

class TreeBoardSidePanelsTest : BasePlatformTestCase() {
    fun testVisibleCardsGroupSiblingFilesBeforeApplyingMetricOrder() {
        val paths = listOf("a.kt", "b.kt", "c.kt", "d.kt", "e.kt")
        val files = paths.mapIndexed { index, path -> CodeFile(path, "/repo/$path", "line\n".repeat(paths.size - index)) }
        val tree = buildSummaryTree(files)
        val topics = ReviewTopics(files, mapOf(
            "a.kt" to "Core > Store",
            "b.kt" to "UI > Panel",
            "c.kt" to "Core > Parser",
            "d.kt" to "core > store",
        ))
        fun panel(maxLevel: Int?) = TreeBoardPanel(
            ReviewLineChart(tree), "Root", {}, cursorPath = "b.kt",
            railModel = buildTreeRailModel(TreeRailMode.TOPICS, topics, null, ReviewSummaries(tree), paths.toSet(), maxLevel),
        )
        fun fileOrder(board: TreeBoardPanel) = board.board.nodes.drop(1).map { it.entry.path }

        val board = panel(maxLevel = null)
        assertEquals(listOf("c.kt", "a.kt", "d.kt", "b.kt", "e.kt"), fileOrder(board))
        assertEquals(listOf("a.kt", "c.kt", "d.kt", "b.kt", "e.kt"), fileOrder(panel(maxLevel = 0)))
        board.toggleRailVisible()
        assertEquals(paths, fileOrder(board))
        assertEquals("b.kt", board.cursorPath)
        board.toggleRailVisible()
        assertEquals(listOf("c.kt", "a.kt", "d.kt", "b.kt", "e.kt"), fileOrder(board))
    }

    fun testSummaryAndTopicCardsArePartOfTheCanvasAndHighlightBothWays() {
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
        val longLeaf = "Login Flow With Remembered Devices"
        val topics = ReviewTopics(
            files = files,
            labels = mapOf(
                "src/auth/Login.kt" to "Security > $longLeaf",
                "src/auth/Session.kt" to "Security > Session Store",
                "src/ui/Panel.kt" to "UI > View Rendering",
            ),
        )
        val topicTree = buildTopicSummaryTree(topics)
        val securityTopic = topicTree.nodes.first { it.identity == "topic:security" }
        val loginTopicPath = "security > ${longLeaf.lowercase()}"
        val loginKey = "1:$loginTopicPath"
        val loginTopic = topicTree.nodes.first { it.identity == "topic:$loginTopicPath" }
        val topicSummaries = ReviewSummaries(
            topicTree,
            mapOf(
                securityTopic.id to Summary.plain("Coordinates authentication and session security."),
                loginTopic.id to Summary.plain("Validates login and remembers devices."),
            ),
        )

        val canvas = NoteCanvas({}, {}, project = project, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
        fun components() = descendants(canvas)
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        fun tabs() = components().filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }
        fun boardPanel() = components().filterIsInstance<TreeBoardPanel>().single()
        fun textOf(name: String) = components().filterIsInstance<JTextArea>().single { it.name == name }.text
        fun TreeBoardPanel.hoverAt(x: Int, y: Int) =
            this.canvas.dispatchEvent(MouseEvent(this.canvas, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false))
        fun TreeBoardPanel.card(key: String): Rectangle = checkNotNull(checkNotNull(rail).cardScreenBounds(key)) { key }
        fun TreeBoardPanel.row(path: String): Rectangle = checkNotNull(rowScreenBounds(path)) { path }

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

            // One canvas: the summary is a child of the tree canvas, left of the tree; the cards sit right of it.
            val summaryPanel = checkNotNull(board.summaryPanel)
            assertSame(board.canvas, summaryPanel.parent)
            assertEquals(board.width - board.insets.left - board.insets.right, board.canvas.width)
            assertTrue(summaryPanel.isVisible)
            val treeLeft = board.row("src/auth/Login.kt").x
            assertTrue(summaryPanel.x >= 0)
            assertTrue(summaryPanel.x + summaryPanel.width <= board.row(board.board.nodes.first().entry.path).x)
            val treeRight = board.board.nodes.map { it.entry.path }
                .mapNotNull(board::rowScreenBounds).maxOf { it.x + it.width }
            assertTrue(board.card("1:security > ${longLeaf.lowercase()}").x > treeRight)
            assertTrue(treeLeft > 0)
            // Code Review has no Groups switcher.
            assertTrue(components().none { it.name == "charts-tree-rail-mode" })

            // Card columns grow to fit their titles instead of cutting them.
            val titleWidth = board.canvas.getFontMetrics(JBFont.label().asBold().forReviewName()).stringWidth(longLeaf)
            val leafCard = board.card("1:security > ${longLeaf.lowercase()}")
            assertTrue("${leafCard.width} < $titleWidth", leafCard.width > titleWidth)
            // Leaf level innermost, broadest level outermost.
            val securityBounds = board.card("0:security")
            assertTrue(leafCard.x + leafCard.width <= securityBounds.x)

            // Initial cursor at root shows the scope summary, anchored at the root row.
            assertEquals("Overview of the review scope.", textOf("charts-tree-summary-text"))

            // Hovering a file updates the summary, its topic badge, and moves the card beside the file.
            val loginBounds = board.row("src/auth/Login.kt")
            board.hoverAt(loginBounds.centerX.toInt(), loginBounds.centerY.toInt())
            assertEquals("Handles login validation.", textOf("charts-tree-summary-text"))
            assertEquals("Topic: Security > $longLeaf", textOf("charts-tree-summary-topic"))
            assertEquals(loginBounds.y, summaryPanel.y)

            // Hovering a folder shows the folder summary.
            val authBounds = board.row("src/auth")
            board.hoverAt(authBounds.centerX.toInt(), authBounds.centerY.toInt())
            assertEquals("Authentication package files.", textOf("charts-tree-summary-text"))

            // Cards stay aligned with the rows they cover.
            val sessionBounds = board.row("src/auth/Session.kt")
            assertTrue(abs(leafCard.y - loginBounds.y) <= 2)
            assertTrue(abs(securityBounds.y - minOf(loginBounds.y, sessionBounds.y)) <= 2)
            assertTrue(abs(securityBounds.y + securityBounds.height - (sessionBounds.y + sessionBounds.height)) <= 2)

            // Hovering a topic card shows the topic summary next to the card and activates it.
            board.hoverAt(securityBounds.centerX.toInt(), securityBounds.centerY.toInt())
            assertEquals("0:security", board.activeRailCard?.key)
            assertEquals("Coordinates authentication and session security.", textOf("charts-tree-summary-text"))
            assertEquals(securityBounds.y, summaryPanel.y)
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_EXITED, 0, 0, -10, -10, 0, false))
            assertNull(board.activeRailCard?.takeIf { board.selectedRailCardKey == null })

            // Panning moves the tree, the cards, and the summary together.
            val beforeRow = board.row("src/auth/Login.kt")
            val beforeCard = board.card("0:security")
            val beforeSummary = summaryPanel.bounds
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, 600, 400, 1, false, MouseEvent.BUTTON1))
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, 640, 430, 1, false, MouseEvent.BUTTON1))
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_RELEASED, 0, 0, 640, 430, 1, false, MouseEvent.BUTTON1))
            assertEquals(40, board.row("src/auth/Login.kt").x - beforeRow.x)
            assertEquals(30, board.row("src/auth/Login.kt").y - beforeRow.y)
            assertEquals(40, board.card("0:security").x - beforeCard.x)
            assertEquals(30, board.card("0:security").y - beforeCard.y)
            assertEquals(40, summaryPanel.x - beforeSummary.x)

            // Selecting a nested card keeps its parent highlighted after hover ends and the metric changes.
            val selectBounds = board.card(loginKey)
            for (id in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
                board.canvas.dispatchEvent(
                    MouseEvent(board.canvas, id, 0, 0, selectBounds.centerX.toInt(), selectBounds.centerY.toInt(), 1, false, MouseEvent.BUTTON1),
                )
            }
            assertEquals(loginKey, board.selectedRailCardKey)
            assertNull(board.cursorPath)
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_EXITED, 0, 0, -10, -10, 0, false))
            assertTrue(checkNotNull(board.rail).isCardHighlighted("0:security"))
            assertFalse(checkNotNull(board.rail).isCardHighlighted("0:ui"))
            assertEquals("Validates login and remembers devices.", textOf("charts-tree-summary-text"))

            tabs().selectedIndex = 1
            val afterMetricSwitch = boardPanel().apply { setSize(1360, 680); layoutTree(this) }
            assertEquals(loginKey, afterMetricSwitch.selectedRailCardKey)
            assertTrue(checkNotNull(afterMetricSwitch.rail).isCardHighlighted("0:security"))
            assertEquals("Validates login and remembers devices.", textOf("charts-tree-summary-text"))

            // Hiding the cards keeps the tree where it was.
            val toggleRailBtn = components().filterIsInstance<JToggleButton>().single { it.name == "charts-tree-toggle-rail" }
            assertTrue(toggleRailBtn.isSelected)
            val rowBeforeHide = afterMetricSwitch.row("src/ui/Panel.kt")
            toggleRailBtn.doClick()
            assertFalse(afterMetricSwitch.isRailVisible)
            assertNull(afterMetricSwitch.activeRailCard)
            assertFalse(toggleRailBtn.isSelected)
            assertEquals(rowBeforeHide.location, afterMetricSwitch.row("src/ui/Panel.kt").location)

            tabs().selectedIndex = 0
            val afterHideSwitch = boardPanel().apply { setSize(1360, 680); layoutTree(this) }
            assertFalse(afterHideSwitch.isRailVisible)
            val toggleBtnAfterSwitch = components().filterIsInstance<JToggleButton>().single { it.name == "charts-tree-toggle-rail" }
            assertFalse(toggleBtnAfterSwitch.isSelected)
            toggleBtnAfterSwitch.doClick()
            assertTrue(afterHideSwitch.isRailVisible)
            assertTrue(toggleBtnAfterSwitch.isSelected)

            // Pressing Escape clears the card selection.
            triggerKey(afterHideSwitch, KeyEvent.VK_ESCAPE)
            assertNull(afterHideSwitch.selectedRailCardKey)
            assertFalse(checkNotNull(afterHideSwitch.rail).isCardHighlighted("0:security"))
            assertNull(checkNotNull(afterHideSwitch.summaryPanel).display)

            // Paint in normal and zoomed states without error, keeping alignment after zoom and fold.
            val image = BufferedImage(1360, 680, BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().also { afterHideSwitch.paint(it) }.dispose()
            afterHideSwitch.zoomTo(1.4)
            image.createGraphics().also { afterHideSwitch.paint(it) }.dispose()
            val zoomedLeaf = afterHideSwitch.card("1:security > ${longLeaf.lowercase()}")
            assertTrue(abs(zoomedLeaf.y - afterHideSwitch.row("src/auth/Login.kt").y) <= 2)
            // Zooming in scales the cards with the canvas.
            assertTrue(zoomedLeaf.width > leafCard.width)

            // Zooming out keeps the cards' on-screen width, so titles stay readable.
            afterHideSwitch.zoomTo(0.5)
            assertEquals(leafCard.width.toDouble(), afterHideSwitch.card("1:security > ${longLeaf.lowercase()}").width.toDouble(), 2.0)
            image.createGraphics().also { afterHideSwitch.paint(it) }.dispose()

            afterHideSwitch.zoomTo(1.0)
            val cardXBeforeFold = afterHideSwitch.card("0:security").x
            afterHideSwitch.toggleCollapsed("src/auth")
            val collapsedAuthBounds = afterHideSwitch.row("src/auth")
            val collapsedSecurityBounds = afterHideSwitch.card("0:security")
            assertTrue(abs(collapsedSecurityBounds.y - collapsedAuthBounds.y) <= 2)
            // Folding doesn't move the cards sideways.
            assertEquals(cardXBeforeFold, collapsedSecurityBounds.x)
            assertNull(checkNotNull(afterHideSwitch.rail).cardScreenBounds("1:security > ${longLeaf.lowercase()}"))
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
        fun railSegment(name: String) = components().filterIsInstance<JToggleButton>().single { it.name == name }
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
            assertTrue(railSegment("charts-tree-rail-topics").isSelected)
            assertFalse(railSegment("charts-tree-rail-groups").isSelected)
            assertFalse(railSegment("charts-tree-rail-off").isSelected)
            assertNotNull(checkNotNull(boardPanel().rail).cardScreenBounds("2:core > parsing > tokens"))

            // Level selector filters depth to level 1 (index 0).
            levelCombo().selectedIndex = 0
            val filteredBoard = boardPanel().apply { setSize(1360, 680); layoutTree(this) }
            val filteredRail = checkNotNull(filteredBoard.rail)
            assertNull(filteredRail.cardScreenBounds("2:core > parsing > tokens"))
            assertNotNull(filteredRail.cardScreenBounds("0:core"))

            // Switching to Groups shows the grouping cards, updates the segmented control, and shows group summaries.
            railSegment("charts-tree-rail-groups").doClick()
            assertTrue(railSegment("charts-tree-rail-groups").isSelected)
            assertTrue(levelCombo().isEnabled)
            val groupBoard = boardPanel().apply { setSize(1360, 680); layoutTree(this) }
            val groupBounds = checkNotNull(checkNotNull(groupBoard.rail).cardScreenBounds("0:fix bug"))
            groupBoard.canvas.dispatchEvent(
                MouseEvent(groupBoard.canvas, MouseEvent.MOUSE_CLICKED, 0, 0, groupBounds.centerX.toInt(), groupBounds.centerY.toInt(), 1, false, MouseEvent.BUTTON1),
            )
            val summaryText = components().filterIsInstance<JTextArea>().single { it.name == "charts-tree-summary-text" }.text
            assertEquals("Guards against missing tokens.", summaryText)
            assertEquals("Group summary", components().filterIsInstance<JLabel>().single { it.name == "charts-tree-summary-kind" }.text)

            // Hiding the groups disables the depth selector without shifting the toolbar.
            railSegment("charts-tree-rail-off").doClick()
            assertTrue(railSegment("charts-tree-rail-off").isSelected)
            assertFalse(boardPanel().isRailVisible)
            assertFalse(levelCombo().isEnabled)
        } finally {
            canvas.dispose()
        }
    }

    fun testSummaryCardCloseButtonHidesAndToolbarRestoresItWithoutMovingTree() {
        val files = listOf(
            CodeFile("src/a.kt", "/repo/src/a.kt", "fun a() = 1\n"),
            CodeFile("src/b.kt", "/repo/src/b.kt", "fun b() = 2\n"),
        )
        val tree = buildSummaryTree(files)
        val aNode = tree.scopeNode("src/a.kt", SummaryKind.FILE)!!
        val summaries = ReviewSummaries(
            tree,
            mapOf(
                tree.root.id to Summary.plain("Root overview."),
                aNode.id to Summary.plain("File A overview."),
            ),
        )
        val canvas = NoteCanvas({}, {}, project = project, vocabulary = ReviewVocabulary.CODE).apply { setSize(1400, 800) }
        fun components() = descendants(canvas)
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        fun boardPanel() = components().filterIsInstance<TreeBoardPanel>().single()
        fun toggleButton(name: String) = components().filterIsInstance<JToggleButton>().single { it.name == name }

        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(summaries)))
            selector().selectedItem = "Tree"
            val board = boardPanel().apply {
                setSize(1200, 680)
                layoutTree(this)
            }
            val summaryPanel = checkNotNull(board.summaryPanel)
            val showSummaryBtn = toggleButton("charts-tree-show-summary")
            val closeSummaryBtn = descendants(summaryPanel).filterIsInstance<JButton>().single { it.name == "charts-tree-summary-close" }

            assertTrue(summaryPanel.isVisible)
            assertTrue(showSummaryBtn.isVisible)
            assertTrue(showSummaryBtn.isSelected)

            val rowBeforeClose = checkNotNull(board.rowScreenBounds("src/a.kt"))
            closeSummaryBtn.doClick()

            assertFalse(summaryPanel.isVisible)
            assertTrue(showSummaryBtn.isVisible)
            assertFalse(showSummaryBtn.isSelected)
            assertEquals(rowBeforeClose, checkNotNull(board.rowScreenBounds("src/a.kt")))

            // Hovering another row keeps the summary dismissed until explicitly restored.
            board.canvas.dispatchEvent(
                MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0, rowBeforeClose.centerX.toInt(), rowBeforeClose.centerY.toInt(), 0, false),
            )
            assertEquals("src/a.kt", board.hoveredPath)
            assertFalse(summaryPanel.isVisible)

            showSummaryBtn.doClick()
            assertTrue(summaryPanel.isVisible)
            assertTrue(showSummaryBtn.isSelected)
        } finally {
            canvas.dispose()
        }
    }

    fun testTopicCardContextPickSupportsDiffsWallEditorsAndCanvas() {
        val diffFiles = listOf(
            ReviewFile("src/a.kt", "/repo/src/a.kt", "val a = 1", "modified", "@@ -1 +1 @@\n-val a = 1\n+val a = 2\n", "fp1", "val a = 2"),
            ReviewFile("src/b.kt", "/repo/src/b.kt", "val b = 1", "added", "@@ -0,0 +1 @@\n+val b = 2\n", "fp2", "val b = 2"),
        )
        val diffTree = buildSummaryTree(diffFiles)
        val multiCard = TreeRailCard(
            key = "0:core",
            name = "Core",
            fullPath = "Core > Auth",
            family = "Core",
            level = 0,
            files = linkedSetOf("src/b.kt", "src/a.kt"),
            summary = "Auth updates",
        )
        val openedWall = mutableListOf<Pair<List<ReviewSource>, String>>()
        val diffPick = checkNotNull(
            treeRailCardContextPick(
                project = project,
                card = multiCard,
                sources = diffFiles,
                tree = diffTree,
                openOnWall = { files, title -> openedWall += files to title },
                reviewDestinations = mapOf(ReviewDestination.TREE to {}),
            ),
        )
        val diffScope = diffPick.candidates.single()
        assertEquals(ContextScopeKind.CHANGE, diffScope.kind)
        assertEquals("Core > Auth", diffScope.label)
        assertEquals(listOf("src/b.kt", "src/a.kt"), diffScope.files)
        assertTrue(diffScope.isPatch)
        assertEquals(listOf("src/b.kt", "src/a.kt"), diffPick.data[ContextKeys.LINKED_FILES]?.map { it.path })
        assertNotNull(diffPick.data[ContextKeys.REVIEW_DESTINATIONS])
        assertNull(diffPick.data[ContextKeys.OPEN_ON_CODE_WALL])

        checkNotNull(diffPick.data[ContextKeys.OPEN_ON_DIFFS_WALL]).invoke(diffScope)
        assertEquals(listOf(listOf(diffFiles[1], diffFiles[0]) to "Core > Auth"), openedWall)

        val diffActionIds = ContextActionRegistry.getInstance()
            .actions(ContextRequest(project, diffScope, diffPick.candidates, diffPick.origin, diffPick.data, null))
            .map { it.id }
            .toSet()
        assertTrue("explore.diffsWall" in diffActionIds)
        assertTrue("open.linkedEditors" in diffActionIds)
        assertTrue("explore.linkedCanvas" in diffActionIds)
        assertTrue("ask.chat" in diffActionIds)
        assertTrue("copy.markdown" in diffActionIds)
        assertFalse("open.editor" in diffActionIds)
        assertFalse("explore.canvas" in diffActionIds)

        val codeFiles = listOf(
            CodeFile("src/a.kt", "/repo/src/a.kt", "fun a() = 1\n"),
            CodeFile("src/b.kt", "/repo/src/b.kt", "fun b() = 2\n"),
        )
        val codeTree = buildSummaryTree(codeFiles)
        openedWall.clear()
        val codePick = checkNotNull(
            treeRailCardContextPick(
                project = project,
                card = multiCard,
                sources = codeFiles,
                tree = codeTree,
                openOnWall = { files, title -> openedWall += files to title },
            ),
        )
        val codeScope = codePick.candidates.single()
        assertFalse(codeScope.isPatch)
        assertNull(codePick.data[ContextKeys.OPEN_ON_DIFFS_WALL])
        checkNotNull(codePick.data[ContextKeys.OPEN_ON_CODE_WALL]).invoke(codeScope)
        assertEquals(listOf(listOf(codeFiles[1], codeFiles[0]) to "Core > Auth"), openedWall)

        val codeActionIds = ContextActionRegistry.getInstance()
            .actions(ContextRequest(project, codeScope, codePick.candidates, codePick.origin, codePick.data, null))
            .map { it.id }
            .toSet()
        assertTrue("explore.codeWall" in codeActionIds)
        assertTrue("open.linkedEditors" in codeActionIds)
        assertTrue("explore.linkedCanvas" in codeActionIds)
        assertFalse("open.editor" in codeActionIds)
        assertFalse("explore.canvas" in codeActionIds)

        val singleCard = multiCard.copy(key = "1:single", files = setOf("src/a.kt"))
        var openedCanvasPath: String? = null
        val singleDiffPick = checkNotNull(
            treeRailCardContextPick(
                project = project,
                card = singleCard,
                sources = diffFiles,
                tree = diffTree,
                openOnCanvas = { node -> openedCanvasPath = node.path },
                openOnWall = { files, title -> openedWall += files to title },
            ),
        )
        assertTrue(singleDiffPick.candidates.any { it.kind == ContextScopeKind.FILE_DIFF })
        assertNotNull(singleDiffPick.data[ContextKeys.OPEN_ON_DIFFS_WALL])
        checkNotNull(singleDiffPick.data[ContextKeys.OPEN_ON_CANVAS]).invoke(singleDiffPick.candidates.first())
        assertEquals("src/a.kt", openedCanvasPath)
        val singleDiffActionIds = ContextActionRegistry.getInstance()
            .actions(ContextRequest(project, singleDiffPick.candidates.first(), singleDiffPick.candidates, singleDiffPick.origin, singleDiffPick.data, null))
            .map { it.id }
            .toSet()
        assertFalse("open.linkedEditors" in singleDiffActionIds)
        assertFalse("explore.linkedCanvas" in singleDiffActionIds)

        val singleCodePick = checkNotNull(
            treeRailCardContextPick(
                project = project,
                card = singleCard,
                sources = codeFiles,
                tree = codeTree,
                openOnWall = { files, title -> openedWall += files to title },
            ),
        )
        assertTrue(singleCodePick.candidates.any { it.kind == ContextScopeKind.FILE })
        assertNotNull(singleCodePick.data[ContextKeys.OPEN_ON_CODE_WALL])
        val singleCodeActionIds = ContextActionRegistry.getInstance()
            .actions(ContextRequest(project, singleCodePick.candidates.first(), singleCodePick.candidates, singleCodePick.origin, singleCodePick.data, null))
            .map { it.id }
            .toSet()
        assertTrue("open.editor" in singleCodeActionIds)
        assertTrue("explore.canvas" in singleCodeActionIds)
        assertFalse("open.linkedEditors" in singleCodeActionIds)
        assertFalse("explore.linkedCanvas" in singleCodeActionIds)
    }

    fun testRightClickingTopicOrGroupCardSelectsCardAndOpensContextMenu() {
        val files = listOf(
            ReviewFile("src/a.kt", "/repo/src/a.kt", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "fp1"),
            ReviewFile("src/b.kt", "/repo/src/b.kt", null, "added", "@@ -0,0 +1 @@\n+added\n", "fp2"),
            ReviewFile("src/c.kt", "/repo/src/c.kt", null, "added", "@@ -0,0 +1 @@\n+other\n", "fp3"),
        )
        val tree = buildSummaryTree(files)
        val topics = ReviewTopics(
            files = files,
            labels = mapOf(
                "src/a.kt" to "Core > Auth",
                "src/b.kt" to "Core > Auth",
            ),
        )
        val railModel = buildTreeRailModel(TreeRailMode.TOPICS, topics, null, ReviewSummaries(tree), files.map { it.path }.toSet())
        val menuCards = mutableListOf<TreeRailCard>()
        var shownAt: Pair<Int, Int>? = null
        val board = TreeBoardPanel(
            ReviewLineChart(tree),
            "Root",
            {},
            cursorPath = "src/c.kt",
            railModel = railModel,
            railContextMenu = { card, _ ->
                menuCards += card
                object : JPopupMenu() {
                    override fun show(invoker: Component?, x: Int, y: Int) {
                        shownAt = x to y
                    }
                }.apply {
                    addTreeRailCardContextItem(this, project, card, files, tree)
                }
            },
        ).apply {
            setSize(1360, 680)
            layoutTree(this)
        }

        val topicCard = checkNotNull(railModel.cardByKey("0:core"))
        val topicBounds = checkNotNull(checkNotNull(board.rail).cardScreenBounds(topicCard.key))
        val cx = topicBounds.centerX.toInt()
        val cy = topicBounds.centerY.toInt()
        board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_PRESSED, 0, 0, cx, cy, 1, false, MouseEvent.BUTTON3))
        board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_RELEASED, 0, 0, cx, cy, 1, true, MouseEvent.BUTTON3))

        assertEquals(listOf(topicCard), menuCards)
        assertEquals(cx to cy, shownAt)
        assertEquals(topicCard.key, board.selectedRailCardKey)
        assertNull(board.cursorPath)

        // Placeholder ("Awaiting topic") cards do not offer the context menu.
        val pendingBounds = checkNotNull(checkNotNull(board.rail).cardScreenBounds("pending"))
        board.canvas.dispatchEvent(
            MouseEvent(board.canvas, MouseEvent.MOUSE_RELEASED, 0, 0, pendingBounds.centerX.toInt(), pendingBounds.centerY.toInt(), 1, true, MouseEvent.BUTTON3),
        )
        assertEquals(1, menuCards.size)

        val groupMenu = JPopupMenu().apply {
            addTreeRailCardContextItem(this, project, topicCard, files, tree)
        }
        val item = groupMenu.components.filterIsInstance<JMenuItem>().single()
        assertEquals("summary-action:Context", item.name)
        assertEquals("Context…", item.text)
    }

    fun testSummaryCardCanBeDraggedToCustomPositionAndReset() {
        val files = listOf(
            CodeFile("src/a.kt", "/repo/src/a.kt", "fun a() = 1\n"),
            CodeFile("src/b.kt", "/repo/src/b.kt", "fun b() = 2\n"),
        )
        val tree = buildSummaryTree(files)
        val aNode = tree.scopeNode("src/a.kt", SummaryKind.FILE)!!
        val summaries = ReviewSummaries(
            tree,
            mapOf(
                tree.root.id to Summary.plain("Root overview."),
                aNode.id to Summary.plain("File A overview."),
            ),
        )
        val canvas = NoteCanvas({}, {}, project = project, vocabulary = ReviewVocabulary.CODE).apply { setSize(1400, 800) }
        fun components() = descendants(canvas)
        fun selector() = components().filterIsInstance<JComboBox<*>>().single { it.name == "charts-type-selector" }
        fun tabs() = components().filterIsInstance<JBTabbedPane>().single { it.name == "charts-lines-metric-tabs" }
        fun boardPanel() = components().filterIsInstance<TreeBoardPanel>().single()
        fun summaryText() = components().filterIsInstance<JTextArea>().single { it.name == "charts-tree-summary-text" }.text
        fun resetButton(panel: Container) =
            descendants(panel).filterIsInstance<JButton>().single { it.name == "charts-tree-summary-reset-position" }

        try {
            canvas.showNotes(CanvasState(emptyList(), charts = ChartsCard(summaries)))
            selector().selectedItem = "Tree"
            val board = boardPanel().apply {
                setSize(1200, 680)
                layoutTree(this)
            }
            val summaryPanel = checkNotNull(board.summaryPanel)
            val resetBtn = resetButton(summaryPanel)
            val header = descendants(summaryPanel).single { it.name == "charts-tree-summary-header" }

            assertFalse(board.isSummaryCustomPositioned)
            assertFalse(resetBtn.isVisible)
            val rootPath = board.board.nodes.first().entry.path
            val rootRow = checkNotNull(board.rowScreenBounds(rootPath))
            val rootRight = rootRow.x + rootRow.width
            assertTrue(summaryPanel.x + summaryPanel.width <= rootRow.x)

            // Drag the summary card by its header to the right side of the canvas.
            val startX = summaryPanel.x
            val startY = summaryPanel.y
            header.dispatchEvent(MouseEvent(header, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, 20, 10, 1, false, MouseEvent.BUTTON1))
            header.dispatchEvent(MouseEvent(header, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, 20 + 700, 10 + 120, 1, false, MouseEvent.BUTTON1))
            header.dispatchEvent(MouseEvent(header, MouseEvent.MOUSE_RELEASED, 0, 0, 20 + 700, 10 + 120, 1, false, MouseEvent.BUTTON1))

            assertTrue(board.isSummaryCustomPositioned)
            assertTrue(resetBtn.isVisible)
            assertTrue(summaryPanel.x > rootRight)
            assertEquals(startX + 700, summaryPanel.x)
            assertEquals(startY + 120, summaryPanel.y)
            val customLocation = summaryPanel.location

            // Hovering another row updates the summary text without moving the custom-placed card.
            val rowA = checkNotNull(board.rowScreenBounds("src/a.kt"))
            board.canvas.dispatchEvent(
                MouseEvent(board.canvas, MouseEvent.MOUSE_MOVED, 0, 0, rowA.centerX.toInt(), rowA.centerY.toInt(), 0, false),
            )
            assertEquals("File A overview.", summaryText())
            assertEquals(customLocation, summaryPanel.location)

            // Panning the tree left keeps the custom-placed summary card in place.
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, 500, 400, 1, false, MouseEvent.BUTTON1))
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, 350, 430, 1, false, MouseEvent.BUTTON1))
            board.canvas.dispatchEvent(MouseEvent(board.canvas, MouseEvent.MOUSE_RELEASED, 0, 0, 350, 430, 1, false, MouseEvent.BUTTON1))
            assertEquals(rowA.x - 150, checkNotNull(board.rowScreenBounds("src/a.kt")).x)
            assertEquals(customLocation, summaryPanel.location)

            // Switching metric tabs preserves the custom summary position and reset button visibility.
            tabs().selectedIndex = 1
            val rebuilt = boardPanel().apply {
                setSize(1200, 680)
                layoutTree(this)
            }
            val rebuiltSummary = checkNotNull(rebuilt.summaryPanel)
            val rebuiltReset = resetButton(rebuiltSummary)
            assertTrue(rebuilt.isSummaryCustomPositioned)
            assertTrue(rebuiltReset.isVisible)
            assertEquals(customLocation, rebuiltSummary.location)

            // Clicking Reset position returns the card to the left of the tree and brings the tree back into view if needed.
            rebuiltReset.doClick()
            assertFalse(rebuilt.isSummaryCustomPositioned)
            assertFalse(rebuiltReset.isVisible)
            assertTrue(rebuiltSummary.x >= 0)
            val rebuiltRootPath = rebuilt.board.nodes.first().entry.path
            assertTrue(rebuiltSummary.x + rebuiltSummary.width <= checkNotNull(rebuilt.rowScreenBounds(rebuiltRootPath)).x)
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
