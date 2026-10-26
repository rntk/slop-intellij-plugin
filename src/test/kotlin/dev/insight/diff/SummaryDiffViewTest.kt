package dev.insight.diff

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBScrollPane
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.ReviewedFiles
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JToggleButton
import javax.swing.event.PopupMenuEvent

class SummaryDiffViewTest : BasePlatformTestCase() {
    private val files get() = listOf("src/Alpha.kt", "src/Beta.kt", "docs/readme.md").map { path ->
        ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", path, "old\n", "new\n")
    }

    fun testNativeDiffSelectionAndDisposalAcrossRebuildsAndModes() {
        val summaries = ReviewSummaries(buildSummaryTree(files))
        val canvas = NoteCanvas({}, {}, project = project).apply {
            setSize(1600, 900)
            summariesView.mode = ReviewSummariesMode.DIFF
            summariesView.selected = "file:src/Alpha.kt"
        }
        fun viewer() = descendants(canvas).filterIsInstance<DiffFileEditor>().single()
        val content = CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true))
        try {
            canvas.showNotes(content)
            val first = viewer()
            assertEquals(files.first(), first.file)
            assertTrue(first.editors.isNotEmpty())
            assertTrue(first.editors.all { it.isViewer })
            click(canvas, "summary-diff-tree:file:src/Beta.kt")
            assertTrue(first.isDisposed)
            val second = viewer()
            assertEquals(files[1], second.file)
            click(canvas, "summary-diff-tree:file:src/Beta.kt")
            assertSame(second, viewer())
            click(canvas, "summary-diff-tree:directory:docs")
            assertTrue(second.isDisposed)
            val third = viewer()
            assertEquals(files[2], third.file)
            assertEquals("directory:docs", canvas.summariesView.selected)
            canvas.showNotes(content)
            assertTrue(third.isDisposed)
            val rebuilt = viewer()
            assertEquals(files[2], rebuilt.file)
            (named(canvas, "summaries-mode:outline") as JToggleButton).doClick()
            assertTrue(rebuilt.isDisposed)
            assertTrue(descendants(canvas).none { it is DiffFileEditor })
            (named(canvas, "summaries-mode:diff") as JToggleButton).doClick()
            val restored = viewer()
            assertEquals(files[2], restored.file)
            canvas.showNotes(CanvasState())
            assertTrue(restored.isDisposed)
            canvas.showNotes(content)
            val last = viewer()
            canvas.dispose()
            assertTrue(last.isDisposed)
        } finally { canvas.dispose() }
    }

    fun testFilteringUsesOnlyMatchingFilesAndEmptyResultsReleaseTheViewer() {
        val summaries = ReviewSummaries(buildSummaryTree(files))
        val canvas = NoteCanvas({}, {}, project = project).apply { summariesView.mode = ReviewSummariesMode.DIFF }
        val content = CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true))
        try {
            canvas.summariesView.fuzzy = "Beta.kt"
            canvas.showNotes(content)
            val viewer = descendants(canvas).filterIsInstance<DiffFileEditor>().single()
            assertEquals(files[1], viewer.file)
            click(canvas, "summary-diff-tree:directory:src")
            assertSame(viewer, descendants(canvas).filterIsInstance<DiffFileEditor>().single())
            canvas.summariesView.fuzzy = "no-such-file"
            canvas.showNotes(content)
            assertTrue(viewer.isDisposed)
            assertTrue(descendants(canvas).none { it is DiffFileEditor })
        } finally { canvas.dispose() }
    }

    fun testContextMenusKeepSummaryActionsWithoutReplacingNativeEditorMenus() {
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree)
        val state = ChangeSummariesViewState().apply { mode = ReviewSummariesMode.DIFF; update(tree); selected = "file:src/Alpha.kt" }
        val calls = mutableListOf<Pair<String, String>>()
        val marks = ReviewedFiles().apply { restore(files) }
        val review = SummaryReviewPresentation(summaries, marks, emptyMap()) { node, value -> marks.set(node.files, value) }
        val actions = ChangeSummaryActions({}, {}, {}, {},
            openNode = { calls += "diff" to it.identity },
            showNode = { calls += "show" to it.identity },
            openWholeFile = { calls += "file" to it.path },
            openDirectory = { calls += "directory" to it.identity },
            explain = { calls += "explain" to it.identity },
            toChat = { calls += "chat" to it.identity },
            resummarize = { node, recursive -> calls += "resummarize:$recursive" to node.identity },
            review = review,
        )
        val panel = ChangeSummaryDiffPanel(SummariesCard(summaries, false, true), state, 1.0, actions, project)
        val split = panel.withTree(1000, 600, null) {}
        fun menu(name: String) = named(split, name).componentPopupMenu.also { popup ->
            popup.popupMenuListeners.forEach { it.popupMenuWillBecomeVisible(PopupMenuEvent(popup)) }
        }
        fun item(name: String, text: String) = menu(name).components.filterIsInstance<JMenuItem>().single { it.text == text }
        val file = "file:src/Alpha.kt"
        val folder = "directory:src"
        val row = "summary-diff-tree:$file"
        val heading = "summary-diff:$file"
        val folderRow = "summary-diff-tree:$folder"
        try {
            for (name in listOf(row, heading)) {
                item(name, "To Chat").doClick()
                assertEquals("chat" to file, calls.last())
            }
            item(row, "Open diff").doClick()
            assertEquals("diff" to file, calls.last())
            item(heading, "Open file").doClick()
            assertEquals("file" to "src/Alpha.kt", calls.last())
            item(heading, "Explain").doClick()
            assertEquals("explain" to file, calls.last())
            item(heading, "Resummarize").doClick()
            assertEquals("resummarize:false" to file, calls.last())
            assertFalse(menu(row).components.filterIsInstance<JMenuItem>().any { it.text in listOf("Fold", "Resummarize all") })
            item(folderRow, "Open diff").doClick()
            assertEquals("directory" to folder, calls.last())
            item(folderRow, "Resummarize all").doClick()
            assertEquals("resummarize:true" to folder, calls.last())
            val viewer = panel.viewers.single()
            item(folderRow, "Fold").doClick()
            assertTrue(descendants(split).none { it.name == row })
            assertSame(viewer, panel.viewers.single())
            item(folderRow, "Unfold").doClick()
            assertNotNull(named(split, row))
            item(heading, "Mark as reviewed").doClick()
            assertTrue(marks.isReviewed(tree.nodes.single { it.identity == file }))
            item(heading, "Mark as not reviewed").doClick()
            assertFalse(marks.isReviewed(tree.nodes.single { it.identity == file }))
            val checkbox = descendants(split).filterIsInstance<JCheckBox>().single { it.name == "reviewed:$file" }
            checkbox.doClick()
            assertNotNull(item(heading, "Mark as not reviewed"))
            assertNull(viewer.componentPopupMenu)
            assertFalse(viewer.inheritsPopupMenu)
        } finally { Disposer.dispose(panel) }
    }

    fun testRootAndDirectoriesStackEveryMatchingDiffAndBuildDistantOnesWhenScrolledTo() {
        val many = files + (1..6).map { ReviewFile("lib/Gen$it.kt", "/repo/lib/Gen$it.kt", null, "modified",
            "@@ -1 +1 @@\n-old\n+new\n", "lib/Gen$it.kt", "old\n", "new\n") }
        val summaries = ReviewSummaries(buildSummaryTree(many))
        val canvas = NoteCanvas({}, {}, project = project).apply {
            setSize(1600, 900)
            summariesView.mode = ReviewSummariesMode.DIFF
            summariesView.selected = "file:src/Alpha.kt"
        }
        fun viewers() = descendants(canvas).filterIsInstance<DiffFileEditor>()
        try {
            canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
            val alpha = viewers().single()
            click(canvas, "summary-diff-tree:directory:src")
            assertEquals(listOf(many[0], many[1]), viewers().map { it.file })
            assertSame(alpha, viewers().first())
            assertNotNull(named(canvas, "summary-diff:directory:src"))
            assertNotNull(named(canvas, "summary-diff:file:src/Beta.kt"))
            click(canvas, "summary-diff-tree:root")
            assertEquals("root", canvas.summariesView.selected)
            val slots = descendants(canvas).filter { it.name?.startsWith("summary-diff-slot:") == true }
            assertEquals(many.map { it.path }.toSet(), slots.map { it.name.removePrefix("summary-diff-slot:") }.toSet())
            val eager = viewers()
            assertEquals(3, eager.size)
            val scroll = named(canvas, "summary-diff-scroll") as JBScrollPane
            scroll.setSize(800, 400)
            fun layout(c: Component) { (c as? Container)?.let { it.doLayout(); it.components.forEach(::layout) } }
            layout(scroll)
            val last = slots.last()
            scroll.viewport.viewPosition = Point(0, last.y)
            assertTrue(viewers().size > eager.size)
            assertTrue(descendants(last).any { it is DiffFileEditor })
            click(canvas, "summary-diff-tree:file:docs/readme.md")
            assertEquals(listOf("docs/readme.md"), viewers().map { it.file.path })
            assertTrue(eager.filter { it.file.path != "docs/readme.md" }.all { it.isDisposed })
        } finally { canvas.dispose() }
    }

    private fun named(root: Component, name: String) = descendants(root).single { it.name == name } as JComponent
    private fun click(root: Component, name: String) {
        named(root, name).dispatchEvent(MouseEvent(named(root, name), MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1))
    }
    private fun descendants(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::descendants) else emptyList()
}
