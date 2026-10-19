package dev.insight.codereview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.treeStructure.Tree
import dev.insight.chat.ChatMessage
import dev.insight.diff.ChangeSummaries
import dev.insight.diff.ChangeSummarizer
import dev.insight.diff.ChangeTopics
import dev.insight.diff.DiffNote
import dev.insight.diff.DiffReviewCanvasFile
import dev.insight.diff.DiffReviewEditorProvider
import dev.insight.diff.DiffSourceRange
import dev.insight.diff.SummaryKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.awt.Container
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JRootPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class CodeReviewPlatformTest : FileEditorManagerTestCase() {
    fun `test canvas opens closes and reopens as a code review editor`() {
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        val manager = FileEditorManager.getInstance(project)
        try {
            val file = codeFile("src/a.kt", "val a = 1\nval b = 2\n")
            tab.update(codeSnapshot(file))
            runWithEvents {
                tab.captureContext().addNotes(listOf(DiffNote(listOf(file.path), "Keep this card")))
                tab.captureContext().showSource(DiffSourceRange(file.path, 1, 2))
            }
            tab.openCanvas()
            tab.openCanvas()
            assertEquals(1, manager.getEditors(tab.canvasFile).size)
            val editor = manager.getEditors(tab.canvasFile).single()
            assertEquals("Code Review", editor.name)
            assertTrue(tab.canvasFile.name.startsWith("Code Review"))
            assertSame(tab.canvas, editor.preferredFocusedComponent)
            manager.closeFile(tab.canvasFile)
            assertFalse(manager.isFileOpen(tab.canvasFile))
            assertEquals("Keep this card", tab.state.notes.single().text)
            assertEquals(1, tab.state.sources.size)
            tab.openCanvas()
            assertTrue(manager.isFileOpen(tab.canvasFile))
            Disposer.dispose(tab)
            assertFalse(manager.isFileOpen(tab.canvasFile))
            assertFalse(DiffReviewEditorProvider().accept(project, tab.canvasFile))
        } finally { Disposer.dispose(tab) }
    }

    fun `test the tab offers summaries and topics but no description or flows`() {
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            tab.update(codeSnapshot(codeFile("src/a.kt", "val a = 1\n")))
            val buttons = descendants(tab).filterIsInstance<JButton>().map { it.text }
            assertTrue(buttons.containsAll(listOf("Summaries", "Topics", "Open canvas", "Clear canvas")))
            assertFalse(buttons.any { it == "Description" || it == "Flows" })
            assertTrue(descendants(tab).filterIsInstance<JButton>().single { it.text == "Summaries" }.isEnabled)
        } finally { Disposer.dispose(tab) }
    }

    fun `test panel loads a folder scope lists its files and opens a tab from a selected folder`() {
        myFixture.addFileToProject("review/a.kt", "fun a() = 1\n")
        myFixture.addFileToProject("review/sub/b.kt", "fun b() = 2\n")
        myFixture.addFileToProject("review/min.txt", "var a=1;".repeat(1_000))
        myFixture.addFileToProject("other/c.kt", "fun c() = 3\n")
        val panel = CodeReviewPanel(project)
        JRootPane().contentPane.add(panel)
        val manager = FileEditorManager.getInstance(project)
        try {
            panel.setScope(myFixture.findFileInTempDir("review"))
            PlatformTestUtil.waitWithEventsDispatching("The folder should load", {
                panel.snapshot?.scope?.path == "review" && panel.status.text.endsWith("skipped")
            }, 20)
            assertEquals(listOf("review/a.kt", "review/sub/b.kt"), panel.listedFiles())
            assertEquals("2 files · 1 skipped", panel.status.text)
            val tabs = descendants(panel).filterIsInstance<JBTabs>().single()
            val first = tabs.selectedInfo!!.component as CodeReviewTab
            assertEquals("Whole scope", tabs.selectedInfo!!.text)
            assertEquals("Code Review: Whole scope", first.canvasFile.name)
            assertTrue(manager.isFileOpen(first.canvasFile))
            assertEquals(listOf("review/a.kt", "review/sub/b.kt"), first.state.snapshot.files.map { it.path })

            val tree = descendants(panel).filterIsInstance<Tree>().single()
            val root = tree.model.root as DefaultMutableTreeNode
            val sub = root.depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
                .single { it.userObject.toString() == "sub" }
            tree.selectionPath = TreePath(sub.path)
            assertEquals(listOf("review/sub/b.kt"), panel.selectedPaths())
            descendants(panel).filterIsInstance<JButton>().single { it.text == "New tab from selection" }.doClick()
            val selection = tabs.selectedInfo!!.component as CodeReviewTab
            assertNotSame(first, selection)
            assertEquals(setOf("review/sub"), selection.state.scopePaths)
            assertEquals(listOf("review/sub/b.kt"), selection.state.snapshot.files.map { it.path })
            assertTrue(manager.isFileOpen(selection.canvasFile))
        } finally { Disposer.dispose(panel) }
        assertTrue(manager.openFiles.none { it is DiffReviewCanvasFile })
    }

    fun `test snapshot updates wait for a summaries run and apply when it ends`() {
        val gate = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val model = SummaryModel()
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> }, complete = { request ->
            started.incrementAndGet()
            gate.await()
            model.complete(request)
        })
        try {
            val first = codeSnapshot(codeFile("src/a.kt", "fun a() = 1\n"), codeFile("src/b.kt", "fun b() = 2\n"))
            tab.update(first)
            tab.session.send("/summaries")
            PlatformTestUtil.waitWithEventsDispatching("The run should start", { started.get() > 0 }, 10)
            val edited = codeSnapshot(codeFile("src/a.kt", "fun a() = 1\n"), codeFile("src/b.kt", "fun b() = 3\n"),
                codeFile("src/c.kt", "fun c() = 4\n"))
            tab.update(edited)
            assertEquals(first.files, tab.state.snapshot.files)
            assertSame(edited, tab.pendingSnapshot)
            assertTrue(tab.session.busy)

            gate.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("The run should finish", { !tab.session.busy }, 10)
            assertNull(tab.pendingSnapshot)
            assertEquals(edited.files, tab.state.snapshot.files)
            assertTrue("A held update must not stop the run", tab.session.messages.none { it.text == "Stopped." })
            assertTrue(tab.session.messages.last { it.role == ChatMessage.ASSISTANT }.text.contains("[3 summaries · 2 requests]"))
            // The unchanged file keeps its summary; the edited one and the folder above it are missing again.
            val tree = tab.state.summaries.tree
            assertNotNull(tab.state.summaries.text(tree.nodes.single { it.kind == SummaryKind.FILE && it.path == "src/a.kt" }.id))
            assertNull(tab.state.summaries.text(tree.nodes.single { it.kind == SummaryKind.FILE && it.path == "src/b.kt" }.id))
        } finally {
            gate.complete(Unit)
            Disposer.dispose(tab)
        }
    }

    fun `test an ordinary chat turn stops when the files change`() {
        val gate = CompletableDeferred<Completion>()
        val started = AtomicInteger()
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> }, complete = { started.incrementAndGet(); gate.await() })
        try {
            tab.update(codeSnapshot(codeFile("src/a.kt", "fun a() = 1\n")))
            tab.session.send("What does a do?")
            PlatformTestUtil.waitWithEventsDispatching("The reply should start", { started.get() > 0 }, 10)
            val edited = codeSnapshot(codeFile("src/a.kt", "fun a() = 2\n"))
            tab.update(edited)
            assertEquals(edited.files, tab.state.snapshot.files)
            PlatformTestUtil.waitWithEventsDispatching("The reply should stop", { !tab.session.busy }, 10)
            assertTrue(tab.session.messages.any { it.text == "Stopped." })
        } finally {
            gate.cancel()
            Disposer.dispose(tab)
        }
    }

    private fun runWithEvents(action: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var failure: Throwable? = null
        val job = scope.launch { try { action() } catch (e: Throwable) { failure = e } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Review tools should finish", { job.isCompleted }, 5)
            failure?.let { throw it }
        } finally { scope.cancel() }
    }

    private fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}

class CodeReviewEstimateTest {
    private val big = codeFile("src/lib/Big.kt", largeSource(300))
    private val files = (1..30).map { codeFile("src/app/F$it.kt", "fun f$it() = $it\n") } +
        listOf(codeFile("docs/README.md", "# Steps\n"), big)
    private val snapshot = codeSnapshot(*files.toTypedArray())

    @Test fun `the estimate matches the requests of a full run`() = runBlocking {
        val tree = codeSummaryTree(snapshot.files)
        val evidence = CodeSummaryEvidence(snapshot)
        val options = CODE_SUMMARY_OPTIONS.copy(concurrency = 1)
        val estimate = estimateSummaryRequests(ChangeSummaries(tree), options, evidence::size)
        val model = SummaryModel()
        var saved = ChangeSummaries(tree)
        val run = ChangeSummarizer(model::complete, null, options, evidence).run(saved, "", { saved.summarize(it).also { s -> saved = s } })
        Assert.assertTrue(run.summaries.complete)
        Assert.assertEquals(run.requests, estimate)
    }

    @Test fun `the estimate counts only missing work and restarts complete trees`() {
        val tree = codeSummaryTree(snapshot.files)
        val evidence = CodeSummaryEvidence(snapshot)
        val full = estimateSummaryRequests(ChangeSummaries(tree), CODE_SUMMARY_OPTIONS, evidence::size)
        val complete = fillAll(ChangeSummaries(tree))
        Assert.assertEquals(full, estimateSummaryRequests(complete, CODE_SUMMARY_OPTIONS, evidence::size))
        Assert.assertEquals(0, estimateSummaryRequests(complete, CODE_SUMMARY_OPTIONS, evidence::size, restartComplete = false))
        // Only the root is missing: one branch request.
        val rootless = ChangeSummaries(tree, tree.nodes.filter { it.id != tree.root.id }.associate { it.id to "x" })
        Assert.assertEquals(1, estimateSummaryRequests(rootless, CODE_SUMMARY_OPTIONS, evidence::size))
        Assert.assertEquals(0, estimateSummaryRequests(ChangeSummaries(codeSummaryTree(emptyList())), CODE_SUMMARY_OPTIONS, evidence::size))
    }

    @Test fun `topic estimates count missing files or every file of a complete result`() {
        val topics = ChangeTopics(files)
        Assert.assertEquals(files.size, estimateTopicRequests(topics))
        val partial = topics.assign(files[0].path, "Code>Steps").assign(files[1].path, "Code>Steps")
        Assert.assertEquals(files.size - 2, estimateTopicRequests(partial))
        val complete = files.fold(topics) { acc, file -> acc.assign(file.path, "Code>Steps") }
        Assert.assertEquals(files.size, estimateTopicRequests(complete))
    }

    @Test fun `source blocks show a part or the head of a file within the limits`() {
        val part = big.chunks[1].lines
        Assert.assertEquals(DiffSourceRange(big.path, part.first, minOf(part.last, part.first + 199), CODE_SOURCE_SIDE), sourceBlockRange(big, part))
        val head = sourceBlockRange(big, null)!!
        Assert.assertEquals(1, head.startLine)
        Assert.assertTrue(head.endLine <= 200 && big.lines(1..head.endLine).length <= CODE_SOURCE_CHARS)
        val wide = codeFile("src/Wide.kt", (1..200).joinToString("\n") { "x".repeat(300) })
        val cut = sourceBlockRange(wide, null)!!
        Assert.assertTrue(wide.lines(1..cut.endLine).length <= CODE_SOURCE_CHARS)
        Assert.assertTrue(wide.lines(1..cut.endLine + 1).length > CODE_SOURCE_CHARS)
        Assert.assertNull(sourceBlockRange(codeFile("src/Line.kt", "y".repeat(CODE_SOURCE_CHARS + 1)), null))
        Assert.assertNull(sourceBlockRange(codeFile("src/Empty.kt", ""), null))
    }

    @Test fun `status line counts files skipped files and parts and suggests a subdirectory when cut`() {
        val loaded = snapshot.copy(skipped = listOf(SkippedFile("src/a.png", SkipReason.BINARY), SkippedFile("src/z.kt", SkipReason.LIMIT)),
            truncated = true, overLimit = 1_200)
        Assert.assertEquals("32 files · 1,201 skipped · 1 large file split into ${big.chunks.size} parts · Stopped at the scope limit: " +
            "1,200 files not loaded. Choose a subdirectory.", codeStatusText(loaded))
    }
}
