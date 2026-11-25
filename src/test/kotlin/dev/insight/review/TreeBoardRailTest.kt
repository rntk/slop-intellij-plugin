package dev.insight.review

import dev.insight.codereview.CodeFile
import org.junit.Assert.*
import org.junit.Test

class TreeBoardRailTest {
    private fun file(path: String, text: String = "line\n") = CodeFile(path, "/repo/$path", text)

    @Test fun `buildTreeRailModel scopes topics, preserves hierarchy order, attaches summaries, and adds pending card`() {
        val files = listOf(
            file("src/auth/Login.kt"),
            file("src/auth/Session.kt"),
            file("src/ui/Dashboard.kt"),
            file("docs/README.md"),
        )
        val tree = buildSummaryTree(files)
        val loginNode = tree.scopeNode("src/auth/Login.kt", SummaryKind.FILE)!!
        val sharedSummaries = ReviewSummaries(
            tree,
            mapOf(loginNode.id to Summary.plain("Validates login tokens.")),
        )

        val topics = ReviewTopics(
            files = files,
            labels = mapOf(
                "src/auth/Login.kt" to "Security > Authentication > Login",
                "src/auth/Session.kt" to "Security > Authentication > Session",
                "src/ui/Dashboard.kt" to "Presentation > Web UI",
            ),
        )
        val topicTree = buildTopicSummaryTree(topics)
        val securityNode = topicTree.nodes.first { it.identity == "topic:security" }
        val authNode = topicTree.nodes.first { it.identity == "topic:security > authentication" }
        val topicSummaries = ReviewSummaries(
            topicTree,
            mapOf(
                securityNode.id to Summary.plain("Handles all security flows."),
                authNode.id to Summary.plain("Manages user login and sessions."),
            ),
        )

        // Scope chartFiles to a subset (e.g. Search tab) excludes Presentation > Web UI
        val chartFiles = setOf("src/auth/Login.kt", "src/auth/Session.kt", "docs/README.md")
        val model = buildTreeRailModel(
            mode = TreeRailMode.TOPICS,
            topics = topics,
            topicSummaries = topicSummaries,
            sharedSummaries = sharedSummaries,
            chartFiles = chartFiles,
        )

        assertEquals(TreeRailMode.TOPICS, model.mode)
        assertEquals(3, model.depth)
        assertEquals(3, model.visibleDepth)
        assertEquals(
            listOf("Security", "Authentication", "Login", "Session", "Awaiting topic"),
            model.cards.map { it.name },
        )
        assertEquals(listOf(0, 1, 2, 2, 2), model.cards.map { it.level })

        val securityCard = model.cards[0]
        assertEquals("Handles all security flows.", securityCard.summary)
        assertEquals(setOf("src/auth/Login.kt", "src/auth/Session.kt"), securityCard.files)
        assertEquals("Security", securityCard.family)
        assertFalse(securityCard.placeholder)

        // Leaf topic card matching single file falls back to shared file summary when no topic summary was set.
        val loginCard = model.cards[2]
        assertEquals("Validates login tokens.", loginCard.summary)
        assertEquals(setOf("src/auth/Login.kt"), loginCard.files)
        assertEquals(setOf("0:security", "1:security > authentication"), model.ancestorCardKeys(loginCard.key))

        val pendingCard = model.cards.last()
        assertTrue(pendingCard.placeholder)
        assertTrue(model.ancestorCardKeys(pendingCard.key).isEmpty())
        assertEquals(2, pendingCard.level)
        assertEquals(setOf("docs/README.md"), pendingCard.files)

        assertEquals(
            setOf("0:security", "1:security > authentication", "2:security > authentication > login"),
            model.matchingCardKeys("src/auth/Login.kt"),
        )
        assertEquals("Security", model.fileFamilies["src/auth/Login.kt"])
        assertEquals("Security > Authentication > Login", model.fileLabels["src/auth/Login.kt"])

        // Level filter limits depth
        val levelOne = buildTreeRailModel(TreeRailMode.TOPICS, topics, topicSummaries, sharedSummaries, chartFiles, maxLevel = 1)
        assertEquals(2, levelOne.visibleDepth)
        assertEquals(listOf("Security", "Authentication", "Awaiting topic"), levelOne.cards.map { it.name })
        assertEquals(1, levelOne.cards.last().level)
    }

    @Test fun `buildTreeRailModel returns empty model when no scoped files have labels`() {
        val files = listOf(file("src/A.kt"))
        val shared = ReviewSummaries(buildSummaryTree(files))
        val model = buildTreeRailModel(TreeRailMode.GROUPING, ReviewTopics(files), null, shared, setOf("src/A.kt"))
        assertTrue(model.cards.isEmpty())
        assertEquals(0, model.depth)
        assertEquals(0, model.visibleDepth)
        assertTrue(model.fileFamilies.isEmpty())
    }

    @Test fun `computeTreeRailPlacements places hierarchical columns innermost-to-outermost and bridges directory headers`() {
        val files = listOf(
            file("src/auth/Login.kt"),
            file("src/auth/Session.kt"),
            file("src/ui/Panel.kt"),
            file("docs/README.md"),
        )
        val chart = ReviewLineChart(buildSummaryTree(files))
        val shared = ReviewSummaries(buildSummaryTree(files))
        val topics = ReviewTopics(
            files = files,
            labels = mapOf(
                "src/auth/Login.kt" to "Security > Auth > Login",
                "src/auth/Session.kt" to "Security > Auth > Session",
                "src/ui/Panel.kt" to "UI > Rendering > Panel",
            ),
        )
        val model = buildTreeRailModel(TreeRailMode.TOPICS, topics, null, shared, files.map { it.path }.toSet())
        val board = ReviewTreeBoard(chart)
        val placements = computeTreeRailPlacements(model, board) { dir -> chart.files(dir).map { it.path } }

        val loginRow = board.indexOf("src/auth/Login.kt")
        val sessionRow = board.indexOf("src/auth/Session.kt")
        val panelRow = board.indexOf("src/ui/Panel.kt")
        val readmeRow = board.indexOf("docs/README.md")

        // Deepest level (level 2) + pending sit in column 0 (innermost); level 1 in column 1; level 0 in column 2.
        val loginP = placements.single { it.card.key == "2:security > auth > login" }
        assertEquals(0, loginP.column)
        assertEquals(loginRow..loginRow, loginP.firstRow..loginP.lastRow)
        assertEquals(1, loginP.fileCountInSpan)

        val pendingP = placements.single { it.card.key == "pending" }
        assertEquals(0, pendingP.column)
        assertEquals(readmeRow..readmeRow, pendingP.firstRow..pendingP.lastRow)

        val authP = placements.single { it.card.key == "1:security > auth" }
        assertEquals(1, authP.column)
        assertEquals(minOf(loginRow, sessionRow)..maxOf(loginRow, sessionRow), authP.firstRow..authP.lastRow)
        assertEquals(2, authP.fileCountInSpan)

        val securityP = placements.single { it.card.key == "0:security" }
        assertEquals(2, securityP.column)
        assertEquals(authP.firstRow..authP.lastRow, securityP.firstRow..securityP.lastRow)
        assertEquals(2, securityP.fileCountInSpan)

        val uiP = placements.single { it.card.key == "0:ui" }
        assertEquals(2, uiP.column)
        assertEquals(panelRow..panelRow, uiP.firstRow..uiP.lastRow)

        // Level filter shrinks visibleDepth and shifts column indices accordingly
        val levelOne = buildTreeRailModel(TreeRailMode.TOPICS, topics, null, shared, files.map { it.path }.toSet(), maxLevel = 1)
        val filteredPlacements = computeTreeRailPlacements(levelOne, board) { dir -> chart.files(dir).map { it.path } }
        assertEquals(0, filteredPlacements.single { it.card.key == "1:security > auth" }.column)
        assertEquals(1, filteredPlacements.single { it.card.key == "0:security" }.column)
        assertEquals(0, filteredPlacements.single { it.card.key == "pending" }.column)
    }

    @Test fun `computeTreeRailPlacements splits non-contiguous spans and handles collapsed directories`() {
        val files = listOf(
            file("a/1.kt", "1\n2\n3\n4\n5\n"),
            file("b/2.kt", "1\n2\n3\n4\n"),
            file("c/3.kt", "1\n2\n3\n"),
            file("d/4.kt", "1\n"),
            file("d/5.kt", "1\n"),
            file("e/6.kt", "1\n"),
            file("e/7.kt", "1\n"),
        )
        val chart = ReviewLineChart(buildSummaryTree(files))
        val shared = ReviewSummaries(buildSummaryTree(files))
        val topics = ReviewTopics(
            files = files,
            labels = mapOf(
                "a/1.kt" to "TopicA",
                "b/2.kt" to "TopicB",
                "c/3.kt" to "TopicA",
                "d/4.kt" to "TopicA",
                "d/5.kt" to "TopicB",
                "e/6.kt" to "TopicA",
                "e/7.kt" to "TopicA",
            ),
        )
        val model = buildTreeRailModel(TreeRailMode.TOPICS, topics, null, shared, files.map { it.path }.toSet())

        // Expanded: TopicA spans a/1.kt (1 file) and c/3.kt..d/4.kt (2 files, bridging directory d) and e/6.kt..e/7.kt (2 files)
        val openBoard = ReviewTreeBoard(chart)
        val openPlacements = computeTreeRailPlacements(model, openBoard) { dir -> chart.files(dir).map { it.path } }
        val topicAOpen = openPlacements.filter { it.card.key == "0:topica" }
        assertEquals(3, topicAOpen.size)
        assertEquals(listOf(1, 2, 2), topicAOpen.map { it.fileCountInSpan })
        val secondSpan = topicAOpen[1]
        assertEquals(openBoard.indexOf("c/3.kt"), secondSpan.firstRow)
        assertEquals(openBoard.indexOf("d/4.kt"), secondSpan.lastRow)

        // Collapsed: mixed dir `d` breaks the run after `c/3.kt`; uniform dir `e` maps to `TopicA` with 2 files
        val collapsedBoard = ReviewTreeBoard(chart, collapsed = setOf("d", "e"))
        val collapsedPlacements = computeTreeRailPlacements(model, collapsedBoard) { dir -> chart.files(dir).map { it.path } }
        val topicACollapsed = collapsedPlacements.filter { it.card.key == "0:topica" }
        assertEquals(3, topicACollapsed.size)
        assertEquals(collapsedBoard.indexOf("c/3.kt")..collapsedBoard.indexOf("c/3.kt"), topicACollapsed[1].firstRow..topicACollapsed[1].lastRow)
        assertEquals(1, topicACollapsed[1].fileCountInSpan)
        assertEquals(collapsedBoard.indexOf("e")..collapsedBoard.indexOf("e"), topicACollapsed[2].firstRow..topicACollapsed[2].lastRow)
        assertEquals(2, topicACollapsed[2].fileCountInSpan)
    }

    @Test fun `highlightFiles marks visible files, collapsed container directories, and ancestor connectors`() {
        val files = listOf(
            file("src/a.kt"),
            file("src/nested/b.kt"),
            file("src/nested/c.kt"),
            file("docs/readme.md"),
        )
        val chart = ReviewLineChart(buildSummaryTree(files))

        // Unfolded tree: highlights matching files and their ancestor connectors
        val openBoard = ReviewTreeBoard(chart)
        val openHl = checkNotNull(openBoard.highlightFiles(setOf("src/a.kt", "src/nested/b.kt")) { dir ->
            chart.files(dir).map { it.path }
        })
        assertEquals(setOf(openBoard.indexOf("src/a.kt"), openBoard.indexOf("src/nested/b.kt")), openHl.matchingRows)
        assertTrue(openHl.collapsedContainers.isEmpty())
        assertEquals(setOf(openBoard.indexOf(""), openBoard.indexOf("src"), openBoard.indexOf("src/nested")), openHl.ancestorRows)
        assertTrue(openHl.connectorHighlighted(openBoard.nodes[openBoard.indexOf("src/a.kt")]))
        // `src/nested` (2 files) sorts before `src/a.kt` (1 file), so `src/nested/c.kt` has the `src` trunk passing through column 1.
        assertTrue(openHl.verticalLineHighlighted(openBoard.nodes[openBoard.indexOf("src/nested/c.kt")], 1, openBoard.nodes))
        assertFalse(openHl.contains(openBoard.indexOf("docs")))
        assertFalse(openHl.contains(openBoard.indexOf("src/nested/c.kt")))

        // Collapsed directory containing a matching file is marked as a collapsedContainer
        val collapsedBoard = ReviewTreeBoard(chart, collapsed = setOf("src/nested"))
        val collapsedHl = checkNotNull(collapsedBoard.highlightFiles(setOf("src/nested/b.kt")) { dir ->
            chart.files(dir).map { it.path }
        })
        assertTrue(collapsedHl.matchingRows.isEmpty())
        assertEquals(setOf(collapsedBoard.indexOf("src/nested")), collapsedHl.collapsedContainers)
        assertEquals(setOf(collapsedBoard.indexOf(""), collapsedBoard.indexOf("src")), collapsedHl.ancestorRows)

        // Returns null when no visible files match
        assertNull(openBoard.highlightFiles(setOf("missing.kt")) { dir -> chart.files(dir).map { it.path } })
    }

    @Test fun `canvas cards follow zoom-in and keep their screen size on zoom-out`() {
        assertEquals(1.0, treeCanvasCardScale(0.25), 0.0)
        assertEquals(1.0, treeCanvasCardScale(1.0), 0.0)
        assertEquals(2.0, treeCanvasCardScale(2.0), 0.0)
    }

    @Test fun `rail columns sit side by side and scale with the cards`() {
        assertEquals(listOf(100.0, 228.0, 378.0), treeRailColumnLefts(100.0, listOf(120, 142, 90), 8, 1.0))
        assertEquals(listOf(10.0, 266.0), treeRailColumnLefts(10.0, listOf(120, 90), 8, 2.0))
        assertEquals(emptyList<Double>(), treeRailColumnLefts(0.0, emptyList(), 8, 1.0))
    }

    @Test fun `rail column width fits its widest title within limits`() {
        assertEquals(236, treeRailColumnWidth(listOf(80, 216), 20, 140, 560))
        assertEquals(140, treeRailColumnWidth(listOf(40), 20, 140, 560))
        assertEquals(140, treeRailColumnWidth(emptyList(), 20, 140, 560))
        assertEquals(560, treeRailColumnWidth(listOf(900), 20, 140, 560))
    }

    @Test fun `floating card follows its anchor and stays inside the view while the anchor is visible`() {
        assertEquals(120.0, floatingCardTop(120.0, 144.0, 200.0, 600.0, 12.0)!!, 0.0)
        // Near the bottom it rises to stay in view; near the top it stops at the margin.
        assertEquals(388.0, floatingCardTop(560.0, 584.0, 200.0, 600.0, 12.0)!!, 0.0)
        assertEquals(12.0, floatingCardTop(-10.0, 14.0, 200.0, 600.0, 12.0)!!, 0.0)
        // Taller than the view: it keeps its top in view.
        assertEquals(12.0, floatingCardTop(300.0, 324.0, 900.0, 600.0, 12.0)!!, 0.0)
        assertNull(floatingCardTop(-40.0, -16.0, 200.0, 600.0, 12.0))
        assertNull(floatingCardTop(600.0, 624.0, 200.0, 600.0, 12.0))
    }
}
