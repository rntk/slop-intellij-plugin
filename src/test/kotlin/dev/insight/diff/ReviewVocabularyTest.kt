package dev.insight.diff

import dev.insight.review.Summary
import dev.insight.review.summarize
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.ActionLink
import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewCanvasFile
import dev.insight.review.ReviewCanvasOwner
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.ReviewTopics
import dev.insight.review.ReviewVocabulary
import dev.insight.review.SourceChunk
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.buildSummaryTree
import dev.insight.review.topicOutline
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.RepaintManager
import javax.swing.text.JTextComponent

/** The canvas serves a code base as well as a diff: other words, parts of large files, and a canvas owned by another tab. */
class ReviewVocabularyTest : BasePlatformTestCase() {
    private data class Source(override val path: String, override val facts: List<String> = listOf("420 lines")) : ReviewSource {
        override val absolutePath: String get() = "/repo/$path"
        override val fingerprint: String get() = "1"
        override val text: String get() = "val answer = 42\n"
    }

    private fun parts() = List(3) { SourceChunk(it, (it * 10 + 1)..(it * 10 + 10), "h$it") }

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

    fun testCodeTopicsDefaultToACompleteSourceDocumentAndSpeakOfSourceFiles() {
        val files = listOf(Source("src/wallet.kt"), Source("src/auth.kt"), Source("src/pending.kt"))
        val topics = ReviewTopics(files).assign("src/wallet.kt", "Storage>Wallet").assign("src/auth.kt", "Storage>Auth")
        val summaries = ReviewSummaries(buildSummaryTree(files, rootLabel = ReviewVocabulary.CODE.rootName))
        val opened = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val canvas = NoteCanvas({ opened += it }, {}, vocabulary = ReviewVocabulary.CODE, sourceActions = SourceCanvasActions(showDiffCard = { shown += it })).apply { setSize(1600, 900) }
        try {
            canvas.showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, summaries, canRun = true)))
            layoutTree(canvas)
            assertEquals(ChangeTopicsMode.DOCUMENT, canvas.topicsView.mode)
            val panel = descendants(canvas).filterIsInstance<ChangeTopicsPanel>().single()
            assertEquals(listOf("src/auth.kt", "src/wallet.kt", "src/pending.kt"), panel.outline.files.map { it.path })
            assertEquals(3, descendants(panel).filterIsInstance<SourceTextView>().size)
            assertTrue(descendants(canvas).none { it is PatchView || it is ChangeTopicHierarchyPanel })
            assertTrue(panel.components.any { it.name == "topic:1:storage > auth" })
            assertTrue(panel.components.any { it.name == "topic:pending" })
            val buttons = descendants(canvas).filterIsInstance<InplaceButton>().filter { it.toolTipText == "Show source in tab" }
            assertEquals(3, buttons.size)
            assertTrue(descendants(canvas).filterIsInstance<InplaceButton>().none { it.toolTipText == "Show diff in tab" })
            val link = descendants(canvas).filterIsInstance<ActionLink>().first { it.text == "src/auth.kt" }
            assertEquals("Open src/auth.kt in the editor", link.toolTipText)
            link.doClick()
            assertEquals(listOf("src/auth.kt"), opened)
            // Without a node callback the canvas shows the file by path, as before.
            buttons.first().doClick()
            assertEquals(1, shown.size)
            selectTopicsMode(descendants(canvas), "Hierarchy")
            layoutTree(canvas)
            assertEquals(ChangeTopicsMode.HIERARCHY, canvas.topicsView.mode)
            assertEquals(1, descendants(canvas).filterIsInstance<ChangeTopicHierarchyPanel>().size)
            assertTrue(descendants(canvas).none { it is SourceTextView })
        } finally { canvas.dispose() }
    }

    fun testCodeSummariesLeafCardsUseOpenFileAndDimFacts() {
        val files = listOf(Source("src/a.kt"), Source("src/b.kt"), Source("lib/c.kt"))
        val tree = buildSummaryTree(files, rootLabel = ReviewVocabulary.CODE.rootName)
        val opened = mutableListOf<SummaryNode>()
        val canvas = NoteCanvas({}, {}, vocabulary = ReviewVocabulary.CODE, summaryActions = SummaryCanvasActions(openNode = { opened += it })).apply { setSize(1600, 900) }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(ReviewSummaries(tree), running = false, canRun = true)))
            assertEquals(ReviewSummariesMode.SPLIT, canvas.summariesView.mode)
            selectSummariesMode(descendants(canvas), "hierarchy")
            layoutTree(canvas)
            val panel = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
            val card = panel.components.single { it.name == "file:lib/c.kt" } as Container
            assertTrue(descendants(card).filterIsInstance<javax.swing.JLabel>().any { it.text == "lib · 420 lines" })
            assertTrue(descendants(card).filterIsInstance<InplaceButton>().any { it.toolTipText == "Show source in tab" })
            descendants(card).filterIsInstance<ActionLink>().single().doClick()
            assertEquals(listOf("file:lib/c.kt"), opened.map { it.identity })
        } finally { canvas.dispose() }
    }

    fun testCodeSummaryDocumentShowsWholeFilesAndPathCardsIncludingChunkedAndEmptyFiles() {
        val big = CodeFile("src/Big.kt", "/repo/src/Big.kt", (1..620).joinToString("\n", postfix = "\n") { "val line$it = $it" },
            chunks = listOf(SourceChunk(0, 1..300, "first"), SourceChunk(1, 301..620, "second")))
        val files = listOf(big, CodeFile("src/Small.kt", "/repo/src/Small.kt", "val small = 1\n"),
            CodeFile("docs/empty.txt", "/repo/docs/empty.txt", ""))
        val tree = buildSummaryTree(files, rootLabel = ReviewVocabulary.CODE.rootName, chunks = { (it as CodeFile).chunks })
        val bigNode = tree.nodes.single { it.identity == "file:src/Big.kt" }
        val summaries = ReviewSummaries(tree).summarize((bigNode.children + bigNode.id).associateWith { Summary.plain("Explains the complete file.") })
        val opened = mutableListOf<SummaryNode>()
        val canvas = NoteCanvas({}, {}, vocabulary = ReviewVocabulary.CODE, summaryActions = SummaryCanvasActions(openNode = { opened += it })).apply { setSize(1600, 900) }
        fun panel() = descendants(canvas).filterIsInstance<ChangeSummaryDocumentPanel>().single()
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = false)))
            layoutTree(canvas)
            assertEquals(ReviewSummariesMode.SPLIT, canvas.summariesView.mode)
            selectSummariesMode(descendants(canvas), "document")
            layoutTree(canvas)
            assertEquals(files.map { it.path }.toSet(), panel().files.map { it.path }.toSet())
            assertEquals(3, descendants(panel()).filterIsInstance<SourceTextView>().size)
            assertTrue(descendants(canvas).none { it is PatchView })
            for (file in files) {
                val section = panel().components.single { it.name == "file:${file.path}" } as Container
                val card = panel().components.single { it.name == "node:file:${file.path}" } as Container
                val summary = panel().components.single { it.name == "summary:${file.path}" }
                assertEquals(section.y, card.y)
                assertEquals(section.height, card.height)
                assertEquals(section.y, summary.y)
                assertTrue(card.x >= section.x + section.width)
                assertTrue(descendants(card).filterIsInstance<JTextComponent>().any { it.text == file.path })
                assertTrue(descendants(section).filterIsInstance<ActionLink>().any { it.text == file.path })
                val source = descendants(section).filterIsInstance<SourceTextView>().single()
                assertEquals(file.lineCount, source.lineCount)
                if (file == big) assertEquals("val line620 = 620", source.line(619))
            }
            // Level controls keep every file and path card while limiting folder depth.
            (descendants(canvas).single { it.name == "summary-level-selector" } as javax.swing.JComboBox<*>).selectedItem = 1
            layoutTree(canvas)
            assertEquals(3, descendants(panel()).filterIsInstance<SourceTextView>().size)
            assertEquals(3, panel().components.count { it.name?.startsWith("node:file:") == true })
            descendants(panel()).filterIsInstance<ActionLink>().single { it.text == big.path }.doClick()
            assertEquals(listOf(bigNode), opened)

            val smallNode = tree.nodes.single { it.identity == "file:src/Small.kt" }
            canvas.updateSummaries(SummariesCard(summaries.summarize(mapOf(smallNode.id to "A new small file summary.")), false, false))
            assertTrue(descendants(panel()).filterIsInstance<JTextComponent>().any { it.text == "A new small file summary." })
            assertEquals(620, descendants(panel()).filterIsInstance<SourceTextView>().maxOf { it.lineCount })
        } finally { canvas.dispose() }
    }

    fun testSourceDocumentPreservesBlankLinesAndPatchLikeSourceWithoutTruncation() {
        val text = "+source\n-ordinary source\n\n\tindented\n" + "line\n".repeat(800) + "last"
        val view = SourceTextView(text, 600, 1.0)
        assertEquals(805, view.lineCount)
        assertEquals("+source", view.line(0))
        assertEquals("-ordinary source", view.line(1))
        assertEquals("", view.line(2))
        assertEquals("\tindented", view.line(3))
        assertEquals("last", view.line(804))
        assertEquals(1, SourceTextView("\n", 600, 1.0).lineCount)
        assertEquals(2, SourceTextView("\n\n", 600, 1.0).lineCount)
        assertEquals(0, SourceTextView("", 600, 1.0).lineCount)
    }

    fun testIdleSourceDocumentLayoutAndPaintingDoNotKeepInvalidatingText() {
        val files = listOf(CodeFile("src/main/kotlin/example/Document.kt", "/repo/Document.kt", "val answer = 42\n".repeat(620)))
        val tree = buildSummaryTree(files, rootLabel = ReviewVocabulary.CODE.rootName)
        val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "A summary that wraps across several lines. ".repeat(8) })
        val canvas = NoteCanvas({}, {}, vocabulary = ReviewVocabulary.CODE).apply { setSize(1600, 900) }
        val previous = RepaintManager.currentManager(canvas)
        val invalidated = mutableListOf<String>()
        val tracking = object : RepaintManager() {
            override fun addInvalidComponent(component: JComponent) {
                if (component is WrappedText) invalidated += component.text
                super.addInvalidComponent(component)
            }
        }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, false),
                topics = TopicsCard(ReviewTopics(files).assign(files.single().path, "Example>Document"), summaries, false)))
            layoutTree(canvas)
            val texts = descendants(canvas).filterIsInstance<WrappedText>()
            fun paintText() = texts.forEach { text ->
                val image = java.awt.image.BufferedImage(text.width, text.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try { text.paint(graphics) } finally { graphics.dispose() }
            }
            paintText()
            RepaintManager.setCurrentManager(tracking)
            repeat(3) { layoutTree(canvas); paintText() }
            assertTrue("Idle layout/paint keeps invalidating wrapped text: $invalidated", invalidated.isEmpty())
        } finally {
            RepaintManager.setCurrentManager(previous)
            canvas.dispose()
        }
    }

    fun testChunkedFileIsABranchAndItsChunkLeavesOpenTheirNode() {
        val big = Source("src/Big.kt", listOf("30 lines", "3 parts"))
        val tree = buildSummaryTree(listOf(big, Source("src/Small.kt")), rootLabel = "Whole scope",
            chunks = { if (it == big) parts() else emptyList() })
        val chunk = tree.nodes.single { it.identity == "chunk:src/Big.kt:1" }
        val opened = mutableListOf<SummaryNode>()
        val shown = mutableListOf<SummaryNode>()
        val canvas = NoteCanvas({}, {}, vocabulary = ReviewVocabulary.CODE, summaryActions = SummaryCanvasActions(openNode = { opened += it }, showNode = { shown += it }))
            .apply { setSize(1600, 900) }
        fun panel() = descendants(canvas).filterIsInstance<ChangeSummaryHierarchyPanel>().single()
        fun names() = panel().components.mapNotNull { it.name }
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(ReviewSummaries(tree), running = false, canRun = true)))
            assertEquals(ReviewSummariesMode.SPLIT, canvas.summariesView.mode)
            selectSummariesMode(descendants(canvas), "hierarchy")
            layoutTree(canvas)
            // The chunked file is a branch card, folded until the reader opens it; the small file stays a leaf.
            assertTrue("node:file:src/Big.kt" in names())
            assertTrue("file:src/Small.kt" in names())
            assertTrue(names().none { it.startsWith("chunk:") })
            val branch = panel().components.single { it.name == "node:file:src/Big.kt" }
            assertTrue(descendants(branch as Container).filterIsInstance<javax.swing.JLabel>().any { it.text == "1 file" })

            click(canvas, panel().named("toggle:file:src/Big.kt"))
            assertEquals(3, names().count { it.startsWith("chunk:src/Big.kt:") })
            val leaf = panel().components.single { it.name == "chunk:src/Big.kt:1" } as JComponent
            val link = descendants(leaf).filterIsInstance<ActionLink>().single()
            assertEquals("Lines 11–20", link.text)
            assertEquals("src/Big.kt:11-20", leaf.toolTipText)
            link.doClick()
            assertEquals(listOf(chunk), opened)
            assertEquals(SummaryKind.CHUNK, opened.single().kind)
            descendants(leaf).filterIsInstance<InplaceButton>().single().doClick()
            assertEquals(listOf(chunk), shown)

            // Each part always reads "part 2 of 3" in its header and offers its lines.
            click(canvas, leaf)
            selectSummariesMode(descendants(canvas), "outline")
            layoutTree(canvas)
            val header = canvas.named("summary-header:${chunk.identity}") as Container
            val text = (canvas.named("summary-name:${chunk.identity}") as javax.swing.JLabel).text
            assertTrue(text, text.startsWith("Lines 11–20 of Big.kt · part 2 of 3"))
            assertTrue(descendants(canvas).none { it.name?.startsWith("details:") == true })
            assertTrue(descendants(header).filterIsInstance<ActionLink>().any { it.text == "Open lines" })
            descendants(header).filterIsInstance<ActionLink>().first { it.text == "Open lines" }.doClick()
            assertEquals(listOf(chunk, chunk), opened)
        } finally { canvas.dispose() }
    }

    fun testLevelsAndFilesOpenOnlyFolderBranchesNotChunkedFiles() {
        val big = Source("a/Big.kt")
        val files = listOf(big, Source("a/Other.kt"), Source("b/Small.kt"), Source("b/Other2.kt"))
        val chunked = buildSummaryTree(files, chunks = { if (it == big) parts() else emptyList() })
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

    fun testLevelSelectionOverridesSearchExpansionAndAppliesToNewFolders() {
        val files = listOf(Source("root.kt")) + (1..6).map { depth ->
            Source((1..depth).joinToString("/") { "dir$it" } + "/file$depth.kt")
        }
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree)
        val state = ChangeSummariesViewState().apply { update(tree); fuzzy = "file6" }
        val deep = tree.nodes.single { it.kind == SummaryKind.DIRECTORY && tree.depth(it.id) == 4 }
        assertTrue(state.isOpen(deep, summaries))
        state.selectLevel(4, tree)
        assertFalse("Search must not reopen a deeper folder", state.isOpen(deep, summaries))
        state.selectLevel(3, tree)
        for (node in tree.nodes.filter(::isFolderBranch)) {
            assertEquals(node.identity, tree.depth(node.id) < 3, state.isOpen(node, summaries))
        }
        assertEquals(3, state.fork().selectedLevel)

        val updated = buildSummaryTree(files + listOf(Source("dir1/new/one.kt"), Source("dir1/new/two.kt"),
            Source("dir1/dir2/dir3/new/one.kt"), Source("dir1/dir2/dir3/new/two.kt")))
        state.update(updated)
        assertTrue("directory:dir1/new" in state.expanded)
        assertFalse("directory:dir1/dir2/dir3/new" in state.expanded)
    }

    fun testCanvasFileTitleComesFromTheOwner() {
        val owner = object : ReviewCanvasOwner {
            override val canvas = NoteCanvas({}, {})
            override val isDisposed = false
            override val canvasTitle = "Code Review"
            override fun canvasSelected() {}
        }
        try {
            val file = ReviewCanvasFile(owner)
            assertEquals("Code Review", file.name)
            file.renameReview("src")
            assertEquals("Code Review: src", file.name)
            assertEquals("Diff Review", ReviewVocabulary.DIFF.canvasTitle)
        } finally { owner.canvas.dispose() }
    }

    fun testLargeTopicOutlinesStartAtTheFirstLevelOnlyWhenAsked() {
        val files = List(250) { Source("src/f$it.kt") }
        var topics = ReviewTopics(files)
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

        val small = topicOutline(ReviewTopics(files.take(20)).assign(files[0].path, "A>B"))
        val smallState = ChangeTopicsViewState(foldLargeOutline = true)
        smallState.update(small)
        assertNull(smallState.level)
    }

    fun testHugeOutlinesStopAtTheRowCapWithANote() {
        val files = List(2100) { Source("pkg/f$it.kt") }
        val tree = buildSummaryTree(files)
        val state = ChangeSummariesViewState()
        state.update(tree)
        state.selectLevel(99, tree)
        val panel = ChangeSummariesPanel(SummariesCard(ReviewSummaries(tree), running = false, canRun = true), state, 900, 1.0, ChangeSummaryActions({}, {}, {}, {}))
        val rows = panel.components.count { it.name?.startsWith("summary:") == true }
        assertEquals(HierarchyColumnsPanel.MAX_ROWS, rows)
        assertEquals(1, panel.components.count { it.name?.startsWith("more") == true })
        val note = descendants(panel).filterIsInstance<JTextComponent>().single { it.name?.startsWith("more") == true }
        assertTrue(note.text, note.text.endsWith("more — fold or pick a level"))

        val small = ChangeSummariesPanel(SummariesCard(ReviewSummaries(buildSummaryTree(files.take(10))), false, true), ChangeSummariesViewState(),
            900, 1.0, ChangeSummaryActions({}, {}, {}, {}))
        assertTrue(small.components.none { it.name?.startsWith("more") == true })
    }
}
