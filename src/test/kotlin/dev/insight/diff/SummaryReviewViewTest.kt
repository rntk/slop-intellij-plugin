package dev.insight.diff

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.insight.review.CanvasTabKey
import dev.insight.review.ReviewSummaries
import dev.insight.review.Summary
import dev.insight.review.SummarySegment
import dev.insight.review.SummarySource
import dev.insight.review.summarize
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewedFiles
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.Font
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.text.StyleConstants
import dev.insight.review.ui.CanvasState
import dev.insight.review.ui.ChangeTopicActions
import dev.insight.review.ui.ChangeTopicHierarchyPanel
import dev.insight.review.ui.ChangeTopicsMode
import dev.insight.review.ui.ChangeTopicsPanel
import dev.insight.review.ui.ChangeTopicsViewState
import dev.insight.review.ui.NoteCanvas
import dev.insight.review.ui.SummariesCard
import dev.insight.review.ui.SummaryCanvasActions
import dev.insight.review.ui.SummaryReviewPresentation
import dev.insight.review.ui.SummaryReviewSession
import dev.insight.review.ui.SummaryWord
import dev.insight.review.ui.TopicsCard
import dev.insight.review.ui.WrappedSummaryText
import dev.insight.review.wall.WallScope
import dev.insight.review.wall.WallView
import com.intellij.ui.components.ActionLink
import java.awt.event.ActionEvent
import javax.swing.KeyStroke

class SummaryReviewViewTest : BasePlatformTestCase() {
    private val files = listOf("src/a.kt", "src/b.kt", "docs/readme.md").map {
        ReviewFile(it, "/repo/$it", null, "modified", "patch", "1")
    }
    private fun descendants(root: Container): List<Component> = root.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }

    // Tree renders claim occurrences; its branch and diff interactions are covered by SummaryTreeViewTest.
    fun testReviewControlsDimFilesInEverySummaryModeAndCanUnmark() {
        for (mode in ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF && it != ReviewSummariesMode.TREE }) {
            val state = DiffReviewState().apply { update(ReviewSnapshot(files, emptyList())) }
            val tree = state.summaries.tree
            val node = tree.nodes.single { it.path == "docs/readme.md" }
            val texts = tree.nodes.associate { it.id to "Summary for ${it.path.ifEmpty { "root" }}" }
            val summaries = ReviewSummaries(tree, texts)
            val canvas = NoteCanvas({}, {}, reviewed = state.reviewed, summaryActions = SummaryCanvasActions(markReviewed = state::setReviewedPaths)).apply {
                setSize(1600, 900)
                summariesView.mode = mode
                if (mode == ReviewSummariesMode.EXPLORER) summariesView.browse("directory:docs", tree)
            }
            try {
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
                fun control() = descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "reviewed:${node.identity}" }
                fun area() = descendants(canvas).filterIsInstance<WrappedSummaryText>().single { it.text == texts[node.id] }
                assertFalse(control().isSelected)
                val live = control()
                live.doClick()
                assertSame("$mode: a toggle restyles the live card, keeping its reading position", live, control())
                assertTrue(state.reviewed.isReviewed(node))
                assertTrue(control().isSelected)
                assertEquals(UIUtil.getContextHelpForeground(), StyleConstants.getForeground(area().styledDocument.getCharacterElement(0).attributes))
                assertEquals(texts[node.id], area().text)
                control().doClick()
                assertFalse(state.reviewed.isReviewed(node))
                assertFalse(control().isSelected)
                assertFalse(area().styledDocument.getCharacterElement(0).attributes.isDefined(StyleConstants.Foreground))
            } finally { canvas.dispose() }
        }
    }

    fun testDimmingUsesOccurrencesAndPreservesTextWordSizesAndSelection() {
        val tree = buildSummaryTree(files)
        val text = "Add cache; add retries."
        val summaries = ReviewSummaries(tree, mapOf(0 to text))
        val marks = ReviewedFiles().apply { restore(files) }
        val review = SummaryReviewPresentation(summaries, marks, mapOf(0 to setOf(0, 1))) { _, _ -> }
        val area = WrappedSummaryText(text, 500, Font(Font.DIALOG, Font.PLAIN, 14), mapOf("cache" to 1.0))
        val document = area.styledDocument
        val size = document.getFont(document.getCharacterElement(text.indexOf("cache")).attributes).size
        area.select(text.indexOf("cache"), text.indexOf("cache") + 5)
        review.decorate(area, tree.root)
        assertEquals(text, area.text)
        assertEquals("cache", area.selectedText)
        assertEquals(size, document.getFont(document.getCharacterElement(text.indexOf("cache")).attributes).size)
        assertEquals(UIUtil.getContextHelpForeground(), StyleConstants.getForeground(document.getCharacterElement(0).attributes))
        assertFalse(document.getCharacterElement(text.indexOf("add")).attributes.isDefined(StyleConstants.Foreground))
    }

    fun testTopicViewsShareReviewMarksAndDimFileSummaries() {
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree, tree.nodes.associate { it.id to "Summary ${it.path}" })
        var topics = ReviewTopics(files)
        files.forEach { topics = topics.assign(it.path, "Application > Behavior") }
        val card = TopicsCard(topics, summaries, true)
        val reading = card.reading
        val node = reading.tree.nodes.single { it.path == files[0].path && it.kind == SummaryKind.FILE }
        val reviewed = ReviewedFiles().apply { restore(files); set(node.files, true) }
        for (mode in ChangeTopicsMode.entries) {
            val state = ChangeTopicsViewState(mode = mode)
            val review = SummaryReviewPresentation(reading, reviewed, emptyMap()) { entry, value -> reviewed.set(entry.files, value) }
            val actions = ChangeTopicActions({}, {}, review = review)
            val panel = if (mode == ChangeTopicsMode.DOCUMENT) ChangeTopicsPanel(card, state, 1800, 1.0, actions)
                else ChangeTopicHierarchyPanel(card, state, 1800, 1.0, actions)
            val area = descendants(panel).filterIsInstance<WrappedSummaryText>().single { it.text == summaries.text(tree.nodes.single { it.path == files[0].path }.id) }
            assertEquals(UIUtil.getContextHelpForeground(), StyleConstants.getForeground(area.styledDocument.getCharacterElement(0).attributes))
            assertTrue(descendants(panel).filterIsInstance<JCheckBox>().single { it.name == "reviewed:${node.identity}" }.isSelected)
        }
    }

    fun testDimmingFollowsMarksAndSummaryVersions() {
        val tree = buildSummaryTree(files)
        val src = tree.nodes.single { it.path == "src" }
        val a = tree.nodes.single { it.path == "src/a.kt" }
        val b = tree.nodes.single { it.path == "src/b.kt" }
        val summaries = ReviewSummaries(tree)
            .summarize(tree.nodes.filter { it.children.isEmpty() }
                .associate { it.id to Summary(listOf(SummarySegment("Edits ${it.path}.", listOf(SummarySource.Rows(1, 1))))) })
            .summarize(mapOf(src.id to Summary(listOf(
                SummarySegment("Caching", listOf(SummarySource.Claim(0, 0))), SummarySegment(" and "),
                SummarySegment("retries", listOf(SummarySource.Claim(1, 0))),
            ))))
        val reviewed = ReviewedFiles().apply { restore(files) }
        val session = SummaryReviewSession(reviewed)
        fun dimmed(value: ReviewSummaries): List<String> {
            val presentation = session.presentation(value) { _, _ -> }
            val area = javax.swing.JTextPane().apply { text = value.text(src.id) }
            presentation.decorate(area, src)
            val doc = area.styledDocument
            return SummaryWord.split(area.text).filter { word ->
                doc.getCharacterElement(word.start).attributes.isDefined(StyleConstants.Foreground)
            }.map { it.text }
        }
        assertEquals(emptyList<String>(), dimmed(summaries))
        reviewed.set(listOf(a.path), true)
        assertEquals(listOf("Caching"), dimmed(summaries))
        reviewed.set(listOf(b.path), true)
        assertEquals(listOf("Caching", "and", "retries"), dimmed(summaries))
        reviewed.set(listOf(a.path), false)
        assertEquals(listOf("retries"), dimmed(summaries))
        // A version without citations dims only what is reviewed as a whole.
        assertEquals(emptyList<String>(), dimmed(ReviewSummaries(tree, mapOf(src.id to "Caching and retries"))))
    }

    fun testShowOnlyUnviewedFiltersSummariesHierarchyAndDocumentViews() {
        val cases = listOf(
            CanvasTabKey.Summaries to ReviewSummariesMode.OUTLINE,
            CanvasTabKey.Summaries to ReviewSummariesMode.SPLIT,
            CanvasTabKey.Summaries to ReviewSummariesMode.EXPLORER,
            CanvasTabKey.Summaries to ReviewSummariesMode.TREE,
            CanvasTabKey.Hierarchy to ReviewSummariesMode.HIERARCHY,
            CanvasTabKey.Document to ReviewSummariesMode.DOCUMENT,
        )
        for ((page, mode) in cases) {
            val state = DiffReviewState().apply { update(ReviewSnapshot(files, emptyList())) }
            val tree = state.summaries.tree
            val texts = tree.nodes.associate { it.id to "Summary for ${it.path.ifEmpty { "root" }}" }
            val summaries = ReviewSummaries(tree, texts)
            val a = tree.nodes.single { it.path == "src/a.kt" }
            val b = tree.nodes.single { it.path == "src/b.kt" }
            val docs = tree.nodes.single { it.path == "docs/readme.md" }
            val canvas = NoteCanvas({}, {}, reviewed = state.reviewed, summaryActions = SummaryCanvasActions(markReviewed = state::setReviewedPaths)).apply {
                setSize(1600, 900)
                if (page == CanvasTabKey.Summaries) {
                    summariesView.mode = mode
                    if (mode == ReviewSummariesMode.TREE) summariesView.treeLevel = 100
                    if (mode == ReviewSummariesMode.EXPLORER) summariesView.browse("directory:src", tree)
                }
            }
            try {
                val initialHidden = if (mode == ReviewSummariesMode.EXPLORER) a.path else docs.path
                val stillVisible = if (mode == ReviewSummariesMode.EXPLORER) b.path else a.path
                state.setReviewedPaths(listOf(initialHidden), true)
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true, pages = setOf(page))))
                fun toggle() = descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "summaries-show-only-unviewed" }
                fun hasEntry(path: String): Boolean = when (mode) {
                    ReviewSummariesMode.TREE -> descendants(canvas).filterIsInstance<javax.swing.JButton>().any {
                        it.name?.startsWith("summary-tree:") == true && it.text.contains("Summary for $path")
                    }
                    ReviewSummariesMode.DOCUMENT -> descendants(canvas).any { it.name == "file:$path" }
                    else -> descendants(canvas).any { it.name == "reviewed:file:$path" }
                }

                assertTrue("$page/$mode should show $initialHidden before filtering", hasEntry(initialHidden))
                toggle().doClick()
                assertTrue("$page/$mode should keep toggle selected", toggle().isSelected)
                assertFalse("$page/$mode should hide reviewed file $initialHidden", hasEntry(initialHidden))
                assertTrue("$page/$mode should keep unviewed $stillVisible", hasEntry(stillVisible))
                if (mode != ReviewSummariesMode.EXPLORER) {
                    assertFalse("$page/$mode should hide fully reviewed folder docs", descendants(canvas).any { it.name == "reviewed:directory:docs" })
                    // Marking another file reviewed while filtered hides it live.
                    val aControl = descendants(canvas).filterIsInstance<JCheckBox>().firstOrNull { it.name == "reviewed:file:${a.path}" }
                    if (aControl != null) aControl.doClick()
                    else {
                        state.setReviewedPaths(listOf(a.path), true)
                        canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true, pages = setOf(page))))
                    }
                    assertFalse("$page/$mode should hide newly reviewed src/a.kt", hasEntry(a.path))
                    assertTrue("$page/$mode should still show src/b.kt", hasEntry(b.path))
                }

                // Marking every remaining file reviewed shows the empty state.
                state.setReviewedPaths(listOf(docs.path, a.path, b.path), true)
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true, pages = setOf(page))))
                assertFalse("$page/$mode should hide src/b.kt once all files are reviewed", hasEntry(b.path))
                assertTrue("$page/$mode should show empty unviewed message",
                    descendants(canvas).any { it.name == "no-matches" })
            } finally { canvas.dispose() }
        }
    }

    fun testShowOnlyUnviewedFiltersTopicsAndGroupsViews() {
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree, tree.nodes.associate { it.id to "Summary for ${it.path.ifEmpty { "root" }}" })
        var topics = ReviewTopics(files)
        topics = topics.assign("src/a.kt", "Core > Engine")
        topics = topics.assign("src/b.kt", "Core > Engine")
        topics = topics.assign("docs/readme.md", "Docs > Guide")

        for (isGrouping in listOf(false, true)) {
            for (mode in ChangeTopicsMode.entries) {
                val state = DiffReviewState().apply { update(ReviewSnapshot(files, emptyList())) }
                val card = TopicsCard(topics, summaries, canRun = true)
                val canvas = NoteCanvas({}, {}, reviewed = state.reviewed, summaryActions = SummaryCanvasActions(markReviewed = state::setReviewedPaths)).apply {
                    setSize(1600, 900)
                    if (isGrouping) groupingView.mode = mode else topicsView.mode = mode
                }
                try {
                    state.setReviewedPaths(listOf("docs/readme.md"), true)
                    canvas.showNotes(
                        if (isGrouping) CanvasState(emptyList(), grouping = card)
                        else CanvasState(emptyList(), topics = card)
                    )
                    fun toggle() = descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "topics-show-only-unviewed" }
                    fun hasTopic(identity: String) = descendants(canvas).any { it.name?.contains(identity) == true }
                    fun hasFile(path: String) = when (mode) {
                        ChangeTopicsMode.DOCUMENT -> descendants(canvas).any { it.name == "file:$path" }
                        ChangeTopicsMode.HIERARCHY -> descendants(canvas).any { it.name == "reviewed:file:$path" }
                        ChangeTopicsMode.RADAR -> true
                    }

                    toggle().doClick()
                    assertTrue(toggle().isSelected)
                    if (mode != ChangeTopicsMode.RADAR) {
                        assertFalse("reviewed file should be hidden", hasFile("docs/readme.md"))
                        assertTrue("unviewed file should stay visible", hasFile("src/a.kt"))
                        assertFalse("fully reviewed topic/group should be hidden", hasTopic("Docs"))
                    }

                    // Marking another file reviewed via Canvas action updates the filtered card immediately.
                    val aControl = descendants(canvas).filterIsInstance<JCheckBox>().firstOrNull { it.name == "reviewed:file:src/a.kt" }
                    if (aControl != null) {
                        aControl.doClick()
                        assertFalse("newly reviewed file should disappear immediately", hasFile("src/a.kt"))
                        assertTrue("remaining unviewed file should stay", hasFile("src/b.kt"))
                    }

                    state.setReviewedPaths(listOf("src/a.kt", "src/b.kt"), true)
                    canvas.showNotes(
                        if (isGrouping) CanvasState(emptyList(), grouping = card)
                        else CanvasState(emptyList(), topics = card)
                    )
                    assertEquals("No unviewed files.",
                        descendants(canvas).filterIsInstance<JLabel>().single { it.name == "topics-no-matches" }.text)
                } finally { canvas.dispose() }
            }
        }
    }

    fun testHierarchyPreviewsFilterOutReviewedFiles() {
        val state = DiffReviewState().apply { update(ReviewSnapshot(files, emptyList())) }
        val tree = state.summaries.tree
        val summaries = ReviewSummaries(tree, tree.nodes.associate { it.id to "Summary for ${it.path.ifEmpty { "root" }}" })
        val canvas = NoteCanvas({}, {}, reviewed = state.reviewed, summaryActions = SummaryCanvasActions(markReviewed = state::setReviewedPaths)).apply {
            setSize(1600, 900)
        }
        try {
            state.setReviewedPaths(listOf("src/a.kt"), true)
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true, pages = setOf(CanvasTabKey.Hierarchy))))
            descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "summaries-show-diffs" }.doClick()
            descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "summaries-show-only-unviewed" }.doClick()

            val srcCard = descendants(canvas).single { it.name == "node:directory:src" }
            srcCard.dispatchEvent(java.awt.event.MouseEvent(srcCard, java.awt.event.MouseEvent.MOUSE_ENTERED, 0, 0, 2, 2, 0, false))
            fun previewedPaths() = descendants(canvas).mapNotNull { it.name?.takeIf { name -> name.startsWith("hierarchy-preview:") } }
                .map { it.removePrefix("hierarchy-preview:") }

            assertEquals(listOf("src/b.kt"), previewedPaths())

            descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "reviewed:file:src/b.kt" }.doClick()
            assertEquals(emptyList<String>(), previewedPaths())
        } finally { canvas.dispose() }
    }

    fun testDiffsWallAndSummariesSyncViewedStateAndUnviewedFilter() {
        val state = DiffReviewState().apply { update(ReviewSnapshot(files, emptyList())) }
        val tree = state.summaries.tree
        val summaries = ReviewSummaries(tree, tree.nodes.associate { it.id to "Summary for ${it.path.ifEmpty { "root" }}" })
        val diffCards = DiffCanvasCards(open = {}, project = project).apply {
            content = DiffCanvasContent(wall = DiffsWallContent(WallScope(null, "All changes"), files))
        }
        val canvas = NoteCanvas(
            open = {},
            remove = {},
            project = project,
            reviewed = state.reviewed,
            summaryActions = SummaryCanvasActions(markReviewed = state::setReviewedPaths),
            extension = diffCards,
        ).apply {
            setSize(1600, 900)
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true, pages = setOf(CanvasTabKey.Summaries))))
            canvas.selectPage(CanvasTabKey.Wall)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

            val unviewedLink = descendants(canvas).filterIsInstance<ActionLink>().single { it.name == "diffs-wall-unviewed" }
            val surface = descendants(canvas).filterIsInstance<WallView.Surface>().single()
            assertEquals("Only unviewed", unviewedLink.text)
            unviewedLink.doClick()
            assertEquals("Show viewed", unviewedLink.text)

            // Marking a file viewed from Summaries hides it on the filtered Diffs wall immediately.
            descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "reviewed:file:docs/readme.md" }.doClick()
            val wallBlocks = descendants(canvas).filterIsInstance<WallView.BlockView>()
            assertEquals(listOf("src/a.kt", "src/b.kt"), wallBlocks.map { it.block.path })

            // Marking the active file viewed on the wall via V updates ReviewedFiles, the wall, and Summaries live.
            assertTrue(wallBlocks.first().active)
            surface.getActionForKeyStroke(KeyStroke.getKeyStroke("V"))!!.actionPerformed(ActionEvent(surface, 0, null))
            assertTrue(state.reviewed.isReviewed("src/a.kt"))
            assertEquals(listOf("src/b.kt"), descendants(canvas).filterIsInstance<WallView.BlockView>().map { it.block.path })
            assertTrue(descendants(canvas).filterIsInstance<JCheckBox>().single { it.name == "reviewed:file:src/a.kt" }.isSelected)
        } finally {
            canvas.dispose()
        }
    }
}
