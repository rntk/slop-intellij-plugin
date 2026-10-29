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
import dev.insight.diff.CanvasState
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.review.ReviewCanvasEditorProvider
import dev.insight.review.ReviewCanvasFile
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewSourceRange
import dev.insight.review.SummaryKind
import dev.insight.review.summarize
import java.awt.Container
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JRootPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CodeReviewPlatformTest : FileEditorManagerTestCase() {
    fun testCapturedCanvasOperationsRejectRefreshingStaleAndClosedTurns() {
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        try {
            val snapshot = codeSnapshot(codeFile("src/a.kt", "fun a() = 1\n"))
            tab.update(snapshot)
            val context = tab.captureContext()
            suspend fun rejectsAll(captured: dev.insight.review.ReviewContext, message: String) {
                val calls: List<suspend () -> Any> = listOf(
                    { captured.showSource(ReviewSourceRange("src/a.kt", 1, 1)) },
                    { captured.startSummaries() }, { captured.summarize(emptyMap()) },
                    { captured.startTopics() }, { captured.assignTopic("src/a.kt", "Architecture>Review") },
                    { captured.startTopicSummaries() }, { captured.summarizeTopics(emptyMap()) },
                )
                for (call in calls) {
                    try { call(); fail("Invalid captured context must reject every canvas operation") }
                    catch (e: dev.insight.chat.ToolInputException) { assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(message)) }
                }
            }
            runWithEvents {
                context.startSummaries()
                assertTrue(tab.state.summariesVisible)
                context.startTopics()
                context.assignTopic("src/a.kt", "Architecture>Review")
                assertTrue(tab.state.topics.complete)
                context.startTopicSummaries()
                tab.state.refreshing = true
                rejectsAll(context, "The code is refreshing")
                tab.state.refreshing = false
                tab.update(codeSnapshot(codeFile("src/a.kt", "fun a() = 2\n")))
                rejectsAll(context, "The files changed")
                val current = tab.captureContext()
                Disposer.dispose(tab)
                rejectsAll(current, "This review tab is closed")
            }
        } finally { Disposer.dispose(tab) }
    }

    fun testTopicsOpenBesideSummariesInTheEditor() {
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> })
        val manager = FileEditorManager.getInstance(project)
        try {
            tab.update(codeSnapshot(codeFile("native-tabs.kt", "fun a() = 1\n")))
            tab.state.summarize(tab.state.revision, tab.state.summaries.tree.nodes.associate { it.id to "Summary" })
            tab.state.assignTopic(tab.state.revision, "native-tabs.kt", "Review>Editors")
            tab.openSummaries()
            tab.openTopics()
            val topics = manager.openFiles.filterIsInstance<ReviewCanvasFile>().single { it.name.endsWith("Topics") }
            assertEquals("Code Review: Topics", topics.name)
            assertTrue(manager.isFileOpen(tab.canvasFile))
            assertFalse(descendants(manager.getEditors(topics).single().component).any { it is javax.swing.JTabbedPane })
            manager.closeFile(tab.canvasFile)
            assertTrue(manager.isFileOpen(topics))
            tab.openSummaries()
            assertEquals(2, manager.openFiles.filterIsInstance<ReviewCanvasFile>().size)
            assertFalse(tab.session.busy)
        } finally { Disposer.dispose(tab) }
        assertTrue(manager.openFiles.none { it is ReviewCanvasFile })
    }

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
                tab.captureContext().addNotes(listOf(ReviewNote(listOf(file.path), "Keep this card")))
                tab.captureContext().showSource(ReviewSourceRange(file.path, 1, 2))
            }
            tab.canvas.selectPage(dev.insight.review.CanvasTabKey.Summaries)
            tab.openCanvas()
            tab.openCanvas()
            assertEquals(1, manager.getEditors(tab.canvasFile).size)
            val editor = manager.getEditors(tab.canvasFile).single()
            assertEquals("Code Review", editor.name)
            assertTrue(tab.canvasFile.name.startsWith("Code Review"))
            assertSame(tab.canvas.editorPage(dev.insight.review.CanvasTabKey.Summaries), editor.preferredFocusedComponent)
            manager.closeFile(tab.canvasFile)
            assertFalse(manager.isFileOpen(tab.canvasFile))
            assertEquals("Keep this card", tab.state.notes.single().text)
            assertEquals(1, tab.state.sources.size)
            tab.openCanvas()
            assertTrue(manager.isFileOpen(tab.canvasFile))
            Disposer.dispose(tab)
            assertFalse(manager.isFileOpen(tab.canvasFile))
            assertFalse(ReviewCanvasEditorProvider().accept(project, tab.canvasFile))
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
            assertTrue(tab.state.summariesVisible)
            assertEquals(dev.insight.review.ReviewSummariesMode.SPLIT, tab.canvas.summariesView.mode)
            assertTrue(descendants(tab.canvas).any { it.name == "summary-split-panels" })
            assertFalse(tab.session.busy)
            assertTrue(tab.session.messages.none { it.role == ChatMessage.USER })
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
            assertEquals("Code Review: Whole scope · Summaries", first.canvasFile.name)
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
        assertTrue(manager.openFiles.none { it is ReviewCanvasFile })
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
            assertTrue(manager.openFiles.filterIsInstance<ReviewCanvasFile>().any { it.name.endsWith("Explaining…") })
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
                listOf(dev.insight.diff.SummaryMatch(app.id, app.identity, true, 1)))
            val context = dev.insight.diff.SummaryContext(tree.root.identity, text, word, listOf(word), "parsing")
            tab.canvas.summariesView.tracing = dev.insight.diff.SummaryTracing(tree.root.identity, text, word, context, trace = trace)
            tab.canvas.showNotes(CanvasState(emptyList(), summaries = dev.insight.diff.SummariesCard(original, false, true)))
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

    private fun descendants(container: Container): List<java.awt.Component> = (if (container is dev.insight.diff.NoteCanvas)
        container.pageKeys.map(container::editorPage) else container.components.toList()).flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
