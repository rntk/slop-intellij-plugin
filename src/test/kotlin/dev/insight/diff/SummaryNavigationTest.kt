package dev.insight.diff

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container

class SummaryNavigationTest : BasePlatformTestCase() {
    private fun file(path: String, status: String = "modified", before: String? = "old\n", after: String? = "new\n") =
        ReviewFile(path, "/repo/$path", null, status, "@@ -1 +1 @@\n-old\n+new\n", path, before, after, "base revision", "target revision")

    fun testAllSummaryLayoutsOpenWholeFilesAndDirectoryChangesWithinTheTreeScope() {
        val files = listOf(file("src/a.kt"), file("src/nested/b.kt"), file("docs/readme.md"))
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree)
        val folder = tree.nodes.single { it.kind == SummaryKind.DIRECTORY && it.path == "src" }
        for (mode in ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }) {
            val opened = mutableListOf<ReviewFile>()
            val directories = mutableListOf<List<ReviewFile>>()
            val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(openWholeFile = { opened += it }, openDirectoryDiff = { directories += it }))
                .apply {
                    setSize(1600, 900); summariesView.mode = mode
                    if (mode == ReviewSummariesMode.EXPLORER) summariesView.browse(folder.identity, tree)
                }
            try {
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, running = false, canRun = false)))
                fun named(name: String) = descendants(canvas).single { it.name == name } as Container
                val fileHeader = named(when (mode) {
                    ReviewSummariesMode.OUTLINE, ReviewSummariesMode.EXPLORER -> "summary-header:file:src/a.kt"
                    ReviewSummariesMode.SPLIT -> "summary-tree:file:src/a.kt"
                    else -> "file:src/a.kt"
                })
                actions(fileHeader).single { it.text == "Open file" }.doClick()
                assertEquals(listOf(files[0]), opened)
                if (mode == ReviewSummariesMode.EXPLORER)
                    (descendants(canvas).single { it.name == "summary-explorer-back" } as javax.swing.JButton).doClick()
                val folderHeader = named(when (mode) {
                    ReviewSummariesMode.OUTLINE, ReviewSummariesMode.EXPLORER -> "summary-header:${folder.identity}"
                    ReviewSummariesMode.SPLIT -> "summary-tree:${folder.identity}"
                    else -> "node:${folder.identity}"
                })
                actions(folderHeader).single { it.text == if (mode == ReviewSummariesMode.SPLIT) "Open diff" else "View diff" }.doClick()
                assertEquals(folder.files, directories.single().map { it.path })
                assertEquals(setOf("src/a.kt", "src/nested/b.kt"), directories.single().map { it.path }.toSet())
                assertSame(files[0], directories.single().single { it.path == "src/a.kt" })
            } finally { canvas.dispose() }
        }
    }

    fun testUnreadableFilesKeepDiffNavigationWithoutOfferingWholeSource() {
        val unavailable = file("src/binary.bin", before = null, after = null)
        val tree = buildSummaryTree(listOf(unavailable))
        for (mode in ReviewSummariesMode.entries.filter { it != ReviewSummariesMode.DIFF }) {
            val canvas = NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(openWholeFile = { fail("No full text") }, openDirectoryDiff = {}))
                .apply { setSize(1600, 900); summariesView.mode = mode }
            try {
                canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(ReviewSummaries(tree), false, false)))
                assertTrue(actions(canvas).none { it.text == "Open file" })
                assertTrue(actions(canvas).any { it.text == if (mode == ReviewSummariesMode.SPLIT) "Open diff" else "View diff" })
            } finally { canvas.dispose() }
        }
    }

    fun testCompleteSnapshotsPreserveTextAndUseTheBeforeSideForDeletedFiles() {
        val original = "unchanged context\nold\nmore context\n"
        val target = "unchanged context\nnew\nmore context\n"
        for (status in listOf("modified", "added", "untracked", "renamed", "deleted")) {
            val source = file("src/sample.kt", status, original, if (status == "deleted") "" else target)
            val snapshot = createFullFileSnapshot(source)
            assertFalse(snapshot.isWritable)
            assertEquals("Kotlin", snapshot.fileType.name)
            assertEquals(if (status == "deleted") original else target, FileDocumentManager.getInstance().getDocument(snapshot)!!.text)
            assertTrue(snapshot.name.contains(if (status == "deleted") source.beforeLabel else source.afterLabel))
        }
        val emptied = createFullFileSnapshot(file("empty.txt", after = ""))
        assertEquals("", FileDocumentManager.getInstance().getDocument(emptied)!!.text)
    }

    fun testDirectoryDiffChainKeepsEverySnapshotAndSupportsUnavailableContent() {
        val files = listOf(file("src/a.kt"), file("src/removed.kt", "deleted", after = ""), file("src/binary.bin", before = null, after = null))
        val chain = createDirectoryDiffChain(project, files)
        val producers = chain.listSelection.list
        assertEquals(files.map { it.path }, producers.map { it.name })
        assertEquals(0, chain.listSelection.selectedIndex)
        producers.forEachIndexed { index, producer ->
            val request = producer.process(UserDataHolderBase(), EmptyProgressIndicator()) as SimpleDiffRequest
            assertEquals(files[index].path, request.title)
            assertEquals(true, request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
            val text = (request.contents[1] as DocumentContent).document.text
            assertEquals(files[index].after ?: files[index].patch, text)
        }
    }

    fun testDiffReviewOpenFileUsesTheReviewSnapshotAndReusesItsEditor() {
        val live = myFixture.addFileToProject("src/sample.kt", "live content\n").virtualFile
        val reviewed = file("src/sample.kt").copy(absolutePath = live.path, after = "review snapshot\n")
        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        val editors = FileEditorManager.getInstance(project)
        try {
            tab.update(ReviewSnapshot(listOf(reviewed), emptyList()))
            tab.openSummaries()
            val link = links(tab.canvas).single { it.text == "Open file" }
            link.doClick()
            val snapshot = editors.selectedFiles.single()
            assertFalse(snapshot.isWritable)
            assertEquals("review snapshot\n", FileDocumentManager.getInstance().getDocument(snapshot)!!.text)
            assertEquals("live content\n", FileDocumentManager.getInstance().getDocument(live)!!.text)
            link.doClick()
            assertSame(snapshot, editors.selectedFiles.single())
        } finally {
            editors.openFiles.filter { it !== live }.forEach(editors::closeFile)
            Disposer.dispose(tab)
        }
    }

    private fun links(container: Container) = descendants(container).filterIsInstance<ActionLink>()
    /** Entry actions: links in most views, the tree row's context menu in the split view. */
    private fun actions(container: Container): List<javax.swing.AbstractButton> = links(container) +
        (listOf(container) + descendants(container)).mapNotNull { (it as? javax.swing.JComponent)?.componentPopupMenu }.distinct()
            .flatMap { it.components.filterIsInstance<javax.swing.JMenuItem>() }
    private fun descendants(container: Container): List<Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
