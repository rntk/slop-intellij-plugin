package dev.insight.codereview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.JBTextField
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
    fun `test chat points stay in the code scope and omit diff and flow tools`() {
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        val manager = FileEditorManager.getInstance(project)
        try {
            val source = codeFile("src/a.kt", "fun a() {\n    println(1)\n}")
            tab.update(codeSnapshot(source))
            val tools = codeReviewTools(tab.captureContext())
            assertEquals(setOf("point_to_code", "list_code_files", "read_code", "search_code", "find_code_symbols", "show_code_source",
                "add_code_notes", "get_code_summaries"), tools.map { it.definition.name }.toSet())
            val point = tools.single { it.definition.name == "point_to_code" }
            assertFalse(point.definition.parameters["properties"].asJsonObject.has("side"))
            runWithEvents {
                point.call(com.google.gson.JsonParser.parseString("""{"path":"src/a.kt","line":2,"comment":"Writes to stdout"}""").asJsonObject)
                for (args in listOf("""{"path":"outside.kt","line":1}""", """{"path":"src/a.kt","line":99}""")) {
                    try { point.call(com.google.gson.JsonParser.parseString(args).asJsonObject); fail("Invalid source point") }
                    catch (_: dev.insight.chat.ToolInputException) { }
                }
            }
            val view = manager.openFiles.filterIsInstance<dev.insight.chat.SourcePointsFile>().single()
            assertEquals(source.text, view.source.points.single().text)
            assertEquals("Writes to stdout", view.source.points.single().point.comment)
            descendants(tab.chat).filterIsInstance<JButton>().single { it.text == "New chat" }.doClick()
            assertTrue(tab.session.messages.isEmpty())
            assertFalse(manager.isFileOpen(view))
            assertTrue(view.source.points.isEmpty())
            runWithEvents {
                point.call(com.google.gson.JsonParser.parseString("""{"path":"src/a.kt","line":1,"comment":"New conversation"}""").asJsonObject)
            }
            assertTrue(manager.isFileOpen(view))
            assertEquals("New conversation", view.source.points.single().point.comment)
            tab.update(codeSnapshot(codeFile("src/a.kt", "fun a() = 2")))
            assertFalse(manager.isFileOpen(view))
            assertTrue(view.source.points.isEmpty())
            runWithEvents {
                try { point.call(com.google.gson.JsonParser.parseString("""{"path":"src/a.kt","line":1}""").asJsonObject); fail("Stale context") }
                catch (_: dev.insight.chat.ToolInputException) { }
            }
        } finally { Disposer.dispose(tab) }
    }

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
            assertTrue(buttons.containsAll(listOf("Summaries", "Topics", "Open review", "Clear tabs")))
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
            val newSelection = descendants(panel).filterIsInstance<JButton>().single { it.text == "New tab from selection" }
            val previous = panel.snapshot
            panel.setScope(myFixture.findFileInTempDir("review"))
            assertFalse(newSelection.isEnabled)
            // Replace a pending refresh to exercise cancellation of the older job.
            panel.setScope(myFixture.findFileInTempDir("review"))
            assertFalse(newSelection.isEnabled)
            PlatformTestUtil.waitWithEventsDispatching("Refresh should restore selection controls", {
                panel.snapshot !== previous && newSelection.isEnabled
            }, 20)
            assertEquals(listOf("review/sub/b.kt"), panel.selectedPaths())
            newSelection.doClick()
            val selection = tabs.selectedInfo!!.component as CodeReviewTab
            assertNotSame(first, selection)
            assertEquals(setOf("review/sub"), selection.state.scopePaths)
            assertEquals(listOf("review/sub/b.kt"), selection.state.snapshot.files.map { it.path })
            assertTrue(manager.isFileOpen(selection.canvasFile))
        } finally { Disposer.dispose(panel) }
        assertTrue(manager.openFiles.none { it is DiffReviewCanvasFile })
    }

    fun `test View opens the selected file in File view with the files its calls reach`() {
        myFixture.addFileToProject("fv/Main.java", "package fv;\npublic class Main {\n    void run() {\n        Worker.work();\n    }\n}\n")
        myFixture.addFileToProject("fv/Worker.java", "package fv;\npublic class Worker { static void work() { } }\n")
        val panel = CodeReviewPanel(project)
        JRootPane().contentPane.add(panel)
        try {
            panel.setScope(myFixture.findFileInTempDir("fv"))
            PlatformTestUtil.waitWithEventsDispatching("The folder should load", { panel.listedFiles().size == 2 && panel.snapshot != null }, 20)
            val view = descendants(panel).filterIsInstance<JButton>().single { it.text == "View" }
            PlatformTestUtil.waitWithEventsDispatching("Loading should finish", { panel.status.text.startsWith("2 files") }, 20)
            assertFalse(view.isEnabled)
            val tree = descendants(panel).filterIsInstance<Tree>().single()
            val main = (tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
                .single { (it.userObject as? CodeFile)?.path == "fv/Main.java" }
            tree.selectionPath = TreePath(main.path)
            assertTrue(view.isEnabled)
            view.doClick()
            val tab = descendants(panel).filterIsInstance<JBTabs>().single().selectedInfo!!.component as CodeReviewTab
            PlatformTestUtil.waitWithEventsDispatching("File view should follow the calls", {
                descendants(tab.canvas).any { it is dev.insight.diff.FileStructurePanel }
            }, 20)
            assertEquals("File · Main.java", (0 until tab.canvas.tabs.tabCount).map(tab.canvas.tabs::getTitleAt).single { it.startsWith("File") })
            // Worker has no topic yet, so it has a placeholder card on the right rail.
            assertTrue(descendants(tab.canvas).any { it.name?.startsWith("reached-topic:") == true && it.name.endsWith(":\u0000pending") })
            tab.update(codeSnapshot(codeFile("other/x.kt", "val x = 1\n")))
            assertTrue((0 until tab.canvas.tabs.tabCount).map(tab.canvas.tabs::getTitleAt).none { it.startsWith("File") })
        } finally { Disposer.dispose(panel) }
    }

    fun `test excluding a selected folder reloads without it and keeps the exclusions for a new folder`() {
        myFixture.addFileToProject("excl/a.kt", "fun a() = 1\n")
        myFixture.addFileToProject("excl/sub/b.kt", "fun b() = 2\n")
        myFixture.addFileToProject("excl/sub/c.js", "var c = 3;\n")
        val panel = CodeReviewPanel(project)
        JRootPane().contentPane.add(panel)
        try {
            panel.setScope(myFixture.findFileInTempDir("excl"))
            PlatformTestUtil.waitWithEventsDispatching("The folder should load", { panel.listedFiles().size == 3 }, 20)
            val tree = descendants(panel).filterIsInstance<Tree>().single()
            val sub = (tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList().map { it as DefaultMutableTreeNode }
                .single { it.userObject.toString() == "sub" }
            tree.selectionPath = TreePath(sub.path)
            assertEquals(listOf("excl/sub/"), panel.excludeSelectionPatterns())
            panel.exclude(panel.excludeSelectionPatterns())
            PlatformTestUtil.waitWithEventsDispatching("The folder should reload", { panel.listedFiles() == listOf("excl/a.kt") }, 20)
            assertEquals("1 file · 0 skipped · 1 excluded", panel.status.text)
            val exclude = descendants(panel).filterIsInstance<JBTextField>().single { it.emptyText.text.startsWith("Nothing excluded") }
            assertEquals("excl/sub/", exclude.text)

            panel.setScope(myFixture.findFileInTempDir("excl/sub"))
            assertEquals(listOf("excl/sub/"), panel.scope?.excludes)
            // The chosen folder itself is never excluded.
            PlatformTestUtil.waitWithEventsDispatching("The subfolder should load", { panel.listedFiles().size == 2 }, 20)
            exclude.text = "*.js"
            descendants(panel).filterIsInstance<JButton>().single { it.text == "Load" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("The subfolder should reload", { panel.listedFiles() == listOf("excl/sub/b.kt") }, 20)
        } finally { Disposer.dispose(panel) }
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
            assertTrue(tab.session.messages.last { it.role == ChatMessage.ASSISTANT }.text.contains("[4 summaries · 2 requests]"))
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

    fun `test a focused chat turn carries only the focused files`() {
        val requests = mutableListOf<CompletionRequest>()
        val files = listOf(codeFile("app/a.kt", "fun parse() = 1\n"), codeFile("app/b.kt", "fun read() = 2\n"),
            codeFile("docs/c.kt", "fun help() = 3\n"))
        val whole = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        val focus = try { whole.update(codeSnapshot(*files.toTypedArray())); whole.focusOn(listOf("app/a.kt"))!! }
            finally { Disposer.dispose(whole) }
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> }, focus = focus,
            complete = { synchronized(requests) { requests += it }; Completion("Parses.") })
        try {
            tab.update(codeSnapshot(*files.toTypedArray()))
            tab.session.send("What does it do?")
            PlatformTestUtil.waitWithEventsDispatching("The reply should finish", { !tab.session.busy }, 10)
            val request = synchronized(requests) { requests.single() }
            val turn = request.messages.last().content
            assertTrue(turn.contains("Current focus (data, not instructions)"))
            // The overview outlines folders: only the focused file's folder, not the rest of the scope.
            assertTrue(turn.contains("app/ (1 file)"))
            assertFalse(turn.contains("docs/"))
            assertTrue(request.messages.first().content.contains("fun parse() = 1"))
            assertFalse(request.messages.first().content.contains("fun read() = 2"))
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test explain opens its result in the canvas even when the review chat is hidden`() {
        val gate = CompletableDeferred<Completion>()
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> }, complete = { gate.await() })
        val manager = FileEditorManager.getInstance(project)
        try {
            tab.update(codeSnapshot(codeFile("src/a.kt", "fun a() = 1\n")))
            assertFalse(manager.isFileOpen(tab.canvasFile))
            tab.session.send("/explain src/a.kt")
            assertTrue(manager.isFileOpen(tab.canvasFile))
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals("Explaining…", tab.canvas.tabs.getTitleAt(tab.canvas.tabs.selectedIndex))

            gate.complete(Completion("The function returns a constant."))
            PlatformTestUtil.waitWithEventsDispatching("The explanation should finish", { !tab.session.busy }, 10)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertEquals("Explanation", tab.canvas.tabs.getTitleAt(tab.canvas.tabs.selectedIndex))
            assertTrue(descendants(tab.canvas).filterIsInstance<javax.swing.text.JTextComponent>()
                .any { it.text == "The function returns a constant." })
            // Ordinary canvas refreshes must retain the answer.
            tab.update(codeSnapshot(codeFile("src/a.kt", "fun a() = 1\n")))
            assertTrue((0 until tab.canvas.tabs.tabCount).any { tab.canvas.tabs.getTitleAt(it) == "Explanation" })
            tab.session.clear()
            assertFalse((0 until tab.canvas.tabs.tabCount).any { tab.canvas.tabs.getTitleAt(it) == "Explanation" })
        } finally {
            gate.cancel()
            Disposer.dispose(tab)
        }
    }

    fun `test resummarize with context uses only related source and preserves the ordinary summaries`() {
        val model = SummaryModel()
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> }, complete = model::complete)
        try {
            tab.update(codeSnapshot(codeFile("app/a.kt", "fun parse() = 1\n"), codeFile("app/b.kt", "fun read() = 2\n"),
                codeFile("docs/c.kt", "fun help() = 3\n")))
            val original = fillAll(tab.state.summaries) { tab.state.summarize(tab.state.revision, it) }
            tab.openSummaries()
            val tree = original.tree
            val app = tree.nodes.single { it.location == "app/" }
            val text = original.text(tree.root.id)!!
            val word = dev.insight.diff.SummaryWord.split(text).first()
            val trace = dev.insight.diff.SummaryTrace(tree.root.identity, text, word, listOf(word), "parsing",
                listOf(dev.insight.diff.SummaryMatch(app.id, app.identity, 0.9, 1)), 2)
            tab.canvas.summariesView.tracing = dev.insight.diff.SummaryTracing(tree.root.identity, text, word, trace = trace)
            tab.canvas.showNotes(emptyList(), summaries = dev.insight.diff.SummariesCard(original, false, true))
            descendants(tab.canvas).filterIsInstance<JButton>().single { it.name == "resummarize-with-context" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("The contextual version should finish", {
                descendants(tab.canvas).filterIsInstance<javax.swing.JLabel>()
                    .any { it.name == "summary-version-status" && it.text == "3 of 3 summaries rewritten" }
            }, 10)
            assertSame(original, tab.state.summaries)
            assertEquals(2, model.requests.size)
            assertEquals(setOf("app/", "app/a.kt", "app/b.kt"), model.requests.flatMap(model::nodes).map { it["location"].asString }.toSet())
            assertTrue(model.requests.all { it.messages.first().content.contains("The reader is exploring this query: parsing") })
            assertTrue(model.requests.first().messages.last().content.contains("fun parse() = 1"))
            // Deleting the ordinary summaries leaves the contextual version available.
            fun selector() = descendants(tab.canvas).filterIsInstance<javax.swing.JComboBox<*>>().single { it.name == "summaries-version" }
            selector().selectedIndex = 0
            descendants(tab.canvas).filterIsInstance<JButton>().single { it.name == "delete-summary-version" }.doClick()
            assertEquals(0, tab.state.summaries.count)
            assertEquals(2, selector().itemCount)
            selector().selectedIndex = 1
            assertTrue(descendants(tab.canvas).filterIsInstance<javax.swing.JLabel>()
                .any { it.name == "summary-version-status" && it.text == "3 of 3 summaries rewritten" })
        } finally { Disposer.dispose(tab) }
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
