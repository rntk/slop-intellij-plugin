package dev.insight.diff

import com.intellij.ui.InplaceButton
import com.intellij.ui.components.ActionLink
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JTextArea
import javax.swing.JToggleButton

/** The canvas serves a code base as well as a diff: other words, parts of large files, and a canvas owned by another tab. */
class ReviewVocabularyTest : BasePlatformTestCase() {
    private data class Source(override val path: String, override val facts: List<String> = listOf("420 lines")) : ReviewSource {
        override val absolutePath: String get() = "/repo/$path"
        override val fingerprint: String get() = "1"
    }

    private fun parts(count: Int) = List(count) { SourceChunk(it, (it * 10 + 1)..(it * 10 + 10), "h$it") }

    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }

    private fun Container.named(name: String): Component =
        descendants(this).firstOrNull { it.name == name } ?: error("No component $name in ${descendants(this).mapNotNull { it.name }}")

    private fun click(canvas: Container, component: Component) {
        component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_CLICKED, 0, 0, 5, 5, 1, false, MouseEvent.BUTTON1))
        layoutTree(canvas)
    }

    fun testCodeVocabularyHasNoDocumentModeAndSpeaksOfSourceFiles() {
        val files = listOf(Source("src/wallet.kt"), Source("src/auth.kt"))
        val topics = ChangeTopics(files).assign("src/wallet.kt", "Storage>Wallet").assign("src/auth.kt", "Storage>Auth")
        val summaries = ChangeSummaries(buildSummaryTree(files, rootLabel = ReviewVocabulary.CODE.rootName))
        val opened = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, showDiffCard = { shown += it }, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
        try {
            canvas.showNotes(emptyList(), topics = TopicsCard(topics, summaries, canRun = true))
            layoutTree(canvas)
            // There is no Document mode to pick; the hierarchy is the only view.
            assertTrue(descendants(canvas).filterIsInstance<JToggleButton>().none { it.text == "Document" || it.text == "Hierarchy" })
            assertEquals(ChangeTopicsMode.HIERARCHY, canvas.topicsView.mode)
            assertEquals(1, descendants(canvas).filterIsInstance<ChangeTopicHierarchyPanel>().size)
            assertTrue(descendants(canvas).none { it is ChangeTopicsPanel })
            val buttons = descendants(canvas).filterIsInstance<InplaceButton>().filter { it.toolTipText == "Show source on canvas" }
            assertEquals(2, buttons.size)
            assertTrue(descendants(canvas).filterIsInstance<InplaceButton>().none { it.toolTipText == "Show diff on canvas" })
            val link = descendants(canvas).filterIsInstance<ActionLink>().first { it.text == "auth.kt" }
            assertEquals("Open src/auth.kt in the editor", link.toolTipText)
            link.doClick()
            assertEquals(listOf("src/auth.kt"), opened)
            // Without a node callback the canvas shows the file by path, as before.
            buttons.first().doClick()
            assertEquals(1, shown.size)
        } finally { canvas.dispose() }
    }

    fun testCodeSummariesLeafCardsUseOpenFileAndDimFacts() {
        val files = listOf(Source("src/a.kt"), Source("src/b.kt"), Source("lib/c.kt"))
        val tree = buildSummaryTree(files, rootLabel = ReviewVocabulary.CODE.rootName)
        val opened = mutableListOf<SummaryNode>()
        val canvas = NoteCanvas({}, {}, vocabulary = ReviewVocabulary.CODE, openNode = { opened += it }).apply { setSize(1600, 900) }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(ChangeSummaries(tree), running = false, canRun = true))
            descendants(canvas).filterIsInstance<JToggleButton>().single { it.name == "summaries-mode:hierarchy" }.doClick()
            layoutTree(canvas)
            val panel = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
            val card = panel.components.single { it.name == "file:lib/c.kt" } as Container
            assertTrue(descendants(card).filterIsInstance<javax.swing.JLabel>().any { it.text == "lib · 420 lines" })
            assertTrue(descendants(card).filterIsInstance<InplaceButton>().any { it.toolTipText == "Show source on canvas" })
            descendants(card).filterIsInstance<ActionLink>().single().doClick()
            assertEquals(listOf("file:lib/c.kt"), opened.map { it.identity })
        } finally { canvas.dispose() }
    }

    fun testChunkedFileIsABranchAndItsChunkLeavesOpenTheirNode() {
        val big = Source("src/Big.kt", listOf("30 lines", "3 parts"))
        val tree = buildSummaryTree(listOf(big, Source("src/Small.kt")), rootLabel = "Whole scope",
            chunks = { if (it == big) parts(3) else emptyList() })
        val chunk = tree.nodes.single { it.identity == "chunk:src/Big.kt:1" }
        val opened = mutableListOf<SummaryNode>()
        val shown = mutableListOf<SummaryNode>()
        val canvas = NoteCanvas({}, {}, vocabulary = ReviewVocabulary.CODE, openNode = { opened += it }, showNode = { shown += it })
            .apply { setSize(1600, 900) }
        fun panel() = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
        fun names() = panel().components.mapNotNull { it.name }
        try {
            canvas.showNotes(emptyList(), summaries = SummariesCard(ChangeSummaries(tree), running = false, canRun = true))
            descendants(canvas).filterIsInstance<JToggleButton>().single { it.name == "summaries-mode:hierarchy" }.doClick()
            layoutTree(canvas)
            // The chunked file is a branch card, folded until the reader opens it; the small file stays a leaf.
            assertTrue("node:file:src/Big.kt" in names())
            assertTrue("file:src/Small.kt" in names())
            assertTrue(names().none { it.startsWith("chunk:") })
            val branch = panel().components.single { it.name == "node:file:src/Big.kt" }
            assertTrue(descendants(branch as Container).filterIsInstance<javax.swing.JLabel>().any { it.text == "1 file" })

            click(canvas, panel().named("toggle:file:src/Big.kt"))
            assertEquals(3, names().count { it.startsWith("chunk:src/Big.kt:") })
            val leaf = panel().components.single { it.name == "chunk:src/Big.kt:1" } as javax.swing.JComponent
            val link = descendants(leaf).filterIsInstance<ActionLink>().single()
            assertEquals("Lines 11–20", link.text)
            assertEquals("src/Big.kt:11-20", leaf.toolTipText)
            link.doClick()
            assertEquals(listOf(chunk), opened)
            assertEquals(SummaryKind.CHUNK, opened.single().kind)
            descendants(leaf).filterIsInstance<InplaceButton>().single().doClick()
            assertEquals(listOf(chunk), shown)

            // Selecting a part reads "part 2 of 3" and offers its lines.
            click(canvas, leaf)
            descendants(canvas).filterIsInstance<JToggleButton>().single { it.name == "summaries-mode:outline" }.doClick()
            layoutTree(canvas)
            val details = canvas.named("details:${chunk.identity}") as Container
            val texts = descendants(details).filterIsInstance<JTextArea>().map { it.text }
            assertTrue(texts.toString(), "Lines 11–20 of Big.kt · part 2 of 3" in texts)
            assertTrue(descendants(details).filterIsInstance<ActionLink>().any { it.text == "Open lines" })
            descendants(details).filterIsInstance<ActionLink>().first { it.text == "Open lines" }.doClick()
            assertEquals(listOf(chunk, chunk), opened)
        } finally { canvas.dispose() }
    }

    fun testLevelsAndFilesOpenOnlyFolderBranchesNotChunkedFiles() {
        val big = Source("a/Big.kt")
        val files = listOf(big, Source("a/Other.kt"), Source("b/Small.kt"), Source("b/Other2.kt"))
        val chunked = buildSummaryTree(files, chunks = { if (it == big) parts(3) else emptyList() })
        val plain = buildSummaryTree(files)
        val file = chunked.nodes.single { it.identity == "file:a/Big.kt" }
        assertTrue(file.children.isNotEmpty())
        assertFalse(isFolderBranch(file))
        assertTrue(chunked.nodes.filter { it.kind == SummaryKind.CHUNK }.none(::isFolderBranch))
        assertTrue(isFolderBranch(chunked.root))
        assertEquals(ChangeSummariesViewState.branchLevels(plain), ChangeSummariesViewState.branchLevels(chunked))

        val state = ChangeSummariesViewState()
        state.update(chunked)
        // A small tree opens its folders, but a file with parts stays folded.
        assertTrue(state.expanded.containsAll(chunked.nodes.filter(::isFolderBranch).map { it.identity }))
        assertFalse(file.identity in state.expanded)
        val levels = ChangeSummariesViewState.branchLevels(chunked)
        assertEquals(levels, state.shownLevel(chunked))
        state.selectLevel(1, chunked)
        assertEquals(setOf("root"), state.expanded)
        assertEquals(1, state.shownLevel(chunked))
        state.selectLevel(levels, chunked)
        assertFalse(file.identity in state.expanded)
        assertEquals(levels, state.shownLevel(chunked))
        // Opening a file by hand does not change the level the folders show.
        state.toggle(file.identity)
        assertEquals(levels, state.shownLevel(chunked))
    }

    fun testCanvasFileTitleComesFromTheOwner() {
        val owner = object : ReviewCanvasOwner {
            override val canvas = NoteCanvas({}, {})
            override val isDisposed = false
            override val canvasTitle = "Code Review"
            override fun canvasSelected() {}
        }
        try {
            val file = DiffReviewCanvasFile(owner)
            assertEquals("Code Review", file.name)
            file.renameReview("src")
            assertEquals("Code Review: src", file.name)
            assertEquals("Diff Review", ReviewVocabulary.DIFF.canvasTitle)
        } finally { owner.canvas.dispose() }
    }

    fun testLargeTopicOutlinesStartAtTheFirstLevelOnlyWhenAsked() {
        val files = List(250) { Source("src/f$it.kt") }
        var topics = ChangeTopics(files)
        files.forEachIndexed { index, file -> topics = topics.assign(file.path, "Area${index % 5}>Part${index % 10}") }
        val outline = topicOutline(topics)
        val deep = outline.nodes.first { it.level == 1 }

        val folding = ChangeTopicsViewState(foldLargeOutline = true)
        folding.update(outline)
        assertEquals(1, folding.level)
        assertTrue(folding.isCollapsed(deep))
        assertFalse(folding.isCollapsed(outline.nodes.first { it.level == 0 }))

        val plain = ChangeTopicsViewState()
        plain.update(outline)
        assertNull(plain.level)
        assertFalse(plain.isCollapsed(deep))

        val small = topicOutline(ChangeTopics(files.take(20)).also { topics = it }.assign(files[0].path, "A>B"))
        val smallState = ChangeTopicsViewState(foldLargeOutline = true)
        smallState.update(small)
        assertNull(smallState.level)
    }

    fun testScoringOnlyVisibleNodesIsCachedPerSummariesAndCacheSize() {
        val files = listOf(Source("a/x.kt"), Source("a/y.kt"), Source("b/z.kt"), Source("b/w.kt"))
        val tree = buildSummaryTree(files)
        val summaries = ChangeSummaries(tree).summarize(tree.nodes.associate { it.id to "Handles ${it.identity.length} careful requests." })
        val state = ChangeSummariesViewState()
        state.update(tree)
        val all = state.pending(summaries)
        assertTrue(all.map { it.first }.toSet().size > 1)
        // The same summaries and cache give the same list without recomputing.
        assertSame(all, state.pending(summaries))
        state.visibleOnly = true
        assertEquals(all, state.pending(summaries))
        state.selectLevel(0, tree)
        assertEquals(setOf(tree.root.id), state.pending(summaries).map { it.first }.toSet())
        state.selectLevel(1, tree)
        val shown = state.pending(summaries).map { it.first }.toSet()
        assertTrue(tree.root.id in shown)
        assertTrue(tree.root.children.all { it in shown })
        // An explicit filter wins.
        assertEquals(setOf(tree.root.id), state.pending(summaries) { it.id == tree.root.id }.map { it.first }.toSet())
    }

    fun testHugeOutlinesStopAtTheRowCapWithANote() {
        val files = List(2100) { Source("pkg/f$it.kt") }
        val tree = buildSummaryTree(files)
        val state = ChangeSummariesViewState()
        state.update(tree)
        state.selectLevel(99, tree)
        val panel = ChangeSummariesPanel(SummariesCard(ChangeSummaries(tree), running = false, canRun = true), state, 900, 1.0, ChangeSummaryActions({}, {}, {}, {}))
        val rows = panel.components.count { it.name?.startsWith("summary:") == true }
        assertEquals(HierarchyColumnsPanel.MAX_ROWS, rows)
        assertEquals(1, panel.components.count { it.name?.startsWith("more") == true })
        val note = descendants(panel).filterIsInstance<JTextArea>().single { it.name?.startsWith("more") == true }
        assertTrue(note.text, note.text.endsWith("more — fold or pick a level"))

        val small = ChangeSummariesPanel(SummariesCard(ChangeSummaries(buildSummaryTree(files.take(10))), false, true), ChangeSummariesViewState(),
            900, 1.0, ChangeSummaryActions({}, {}, {}, {}))
        assertTrue(small.components.none { it.name?.startsWith("more") == true })
    }
}
