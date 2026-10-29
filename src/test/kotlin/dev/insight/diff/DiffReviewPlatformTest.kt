package dev.insight.diff

import dev.insight.review.summarize
import com.google.gson.JsonParser
import dev.insight.chat.SourceSide
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.vcsUtil.VcsUtil
import dev.insight.callgraph.DiffService
import dev.insight.chat.ChatMessage
import dev.insight.chat.ChatRole
import dev.insight.chat.ChatPanel
import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException
import dev.insight.llm.Completion
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmToolCall
import dev.insight.review.ReviewCanvasEditorProvider
import dev.insight.review.ReviewCanvasFile
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewSourceRange
import java.awt.BorderLayout
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JRootPane
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlinx.coroutines.*

class DiffReviewPlatformTest : FileEditorManagerTestCase() {
    fun testSummariesAndTopicsUseIndependentNativeEditorTabs() {
        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        val manager = FileEditorManager.getInstance(project)
        try {
            tab.update(ReviewSnapshot(listOf(file("native-tabs.kt")), emptyList()))
            tab.state.summarize(tab.state.revision, tab.state.summaries.tree.nodes.associate { it.id to "Summary" })
            tab.state.assignTopic(tab.state.revision, "native-tabs.kt", "Review>Editors")
            tab.openSummaries()
            val summariesEditor = manager.getEditors(tab.canvasFile).single()
            tab.canvas.summariesView.fuzzy = "preserve this filter"
            tab.openTopics()
            val topicsFile = manager.openFiles.filterIsInstance<ReviewCanvasFile>().single { it.name.endsWith("Topics") }
            val topicsEditor = manager.getEditors(topicsFile).single()
            assertTrue(topicsEditor.preferredFocusedComponent!!.isVisible)
            assertNotSame(tab.canvasFile, topicsFile)
            assertTrue(manager.isFileOpen(tab.canvasFile))
            assertFalse(descendants(summariesEditor.component).any { it is javax.swing.JTabbedPane })
            assertFalse(descendants(topicsEditor.component).any { it is javax.swing.JTabbedPane })
            assertSame(tab.canvas.editorPage(dev.insight.review.CanvasTabKey.Topics), topicsEditor.preferredFocusedComponent)

            // Rebuilding the review updates both open editors without moving their pages into each other.
            tab.update(ReviewSnapshot(listOf(file("native-tabs.kt")), emptyList()))
            assertTrue(descendants(summariesEditor.component).any { it.name == "summary-split-panels" })
            assertTrue(descendants(topicsEditor.component).filterIsInstance<JLabel>().any { it.text.startsWith("Change topics ·") })
            tab.rename("Focused review")
            assertEquals("Diff Review: Focused review · Summaries", tab.canvasFile.name)
            assertEquals("Diff Review: Focused review · Topics", topicsFile.name)

            manager.closeFile(topicsFile)
            assertTrue(manager.isFileOpen(tab.canvasFile))
            tab.openTopics()
            assertTrue(manager.isFileOpen(topicsFile))
            assertEquals("preserve this filter", tab.canvas.summariesView.fuzzy)
            assertFalse(tab.session.busy)
            tab.state.hideTopics()
            tab.update(ReviewSnapshot(listOf(file("native-tabs.kt")), emptyList()))
            assertFalse(manager.isFileOpen(topicsFile))
            assertFalse(topicsFile.isValid)
            assertTrue(manager.isFileOpen(tab.canvasFile))
        } finally { Disposer.dispose(tab) }
        assertTrue(manager.openFiles.none { it is ReviewCanvasFile })
    }

    fun testWordChartsOpenAndCloseIndependently() {
        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            tab.update(ReviewSnapshot(listOf(file("a.kt").copy(patch = "@@ -1 +1 @@\n-oldWord\n+newWord\n")), emptyList()))
            descendants(tab).filterIsInstance<JButton>().single { it.text == "Words" }.doClick()
            assertEquals(1, descendants(tab.canvas).filterIsInstance<WordChangeChart>().size)
            assertTrue(descendants(tab.canvas).none { it is DiffOverviewChart })
            descendants(tab).filterIsInstance<JButton>().single { it.text == "Diff overview" }.doClick()
            assertEquals(1, descendants(tab.canvas).filterIsInstance<WordChangeChart>().size)
            assertEquals(1, descendants(tab.canvas).filterIsInstance<DiffOverviewChart>().size)
            descendants(tab.canvas).filterIsInstance<InplaceButton>().single { it.toolTipText == "Remove diff overview" }.doClick()
            assertTrue(tab.state.wordsVisible)
            assertFalse(tab.state.diffOverviewVisible)
            assertTrue(descendants(tab.canvas).none { it is DiffOverviewChart })
            tab.openDiffOverview()
            tab.update(ReviewSnapshot(listOf(file("a.kt").copy(patch = "@@ -1 +1 @@\n-oldWord\n+updatedWord\n", fingerprint = "updated")), emptyList()))
            assertTrue(descendants(tab.canvas).filterIsInstance<DiffOverviewChart>().single().changes.words.any { it.word == "updatedWord" })
            descendants(tab).filterIsInstance<JButton>().single { it.text == "Clear tabs" }.doClick()
            assertFalse(tab.state.wordsVisible)
            assertFalse(tab.state.diffOverviewVisible)
        } finally { Disposer.dispose(tab) }
    }

    fun `test chat tools point at exact snapshot sides and flow tools require an explicit flow turn`() {
        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        val manager = FileEditorManager.getInstance(project)
        try {
            val source = file("src/deleted-or-historical.kt").copy(before = "old first\nold second", after = "new first\nnew second")
            tab.update(ReviewSnapshot(listOf(source), emptyList()))
            val context = tab.captureContext()
            val tools = diffChatTools(context)
            val names = tools.map { it.definition.name }.toSet()
            assertEquals(setOf("point_to_code", "get_current_diff", "read_diff_files", "find_diff_symbols", "show_diff_source",
                "add_diff_notes", "get_change_summaries"), names)
            val point = tools.single { it.definition.name == "point_to_code" }
            runWithEvents {
                point.call(JsonParser.parseString("""{"path":"${source.path}","line":2,"side":"before","comment":"Removed behavior"}""").asJsonObject)
                point.call(JsonParser.parseString("""{"path":"${source.path}","line":1,"end_line":2}""").asJsonObject)
                try {
                    point.call(JsonParser.parseString("""{"path":"outside.kt","line":1}""").asJsonObject)
                    fail("Out-of-scope source must be rejected")
                } catch (_: ToolInputException) { }
            }
            val view = manager.openFiles.filterIsInstance<dev.insight.chat.SourcePointsFile>().single()
            assertEquals(listOf(source.before, source.after), view.source.points.map { it.text })
            assertEquals(listOf(SourceSide.BEFORE, SourceSide.AFTER), view.source.points.map { it.point.side })
            assertEquals("Removed behavior", view.source.points.first().point.comment)
            descendants(tab.chat).filterIsInstance<JButton>().single { it.text == "New chat" }.doClick()
            assertTrue(tab.session.messages.isEmpty())
            assertFalse(manager.isFileOpen(view))
            assertTrue(view.source.points.isEmpty())
            runWithEvents {
                point.call(JsonParser.parseString("""{"path":"${source.path}","line":1,"comment":"New conversation"}""").asJsonObject)
            }
            assertTrue(manager.isFileOpen(view))
            assertEquals("New conversation", view.source.points.single().point.comment)
            tab.update(ReviewSnapshot(listOf(source.copy(fingerprint = "changed")), emptyList()))
            assertFalse(manager.isFileOpen(view))
            assertTrue(view.source.points.isEmpty())
            runWithEvents {
                try { point.call(JsonParser.parseString("""{"path":"${source.path}","line":1}""").asJsonObject); fail("Stale context") }
                catch (_: ToolInputException) { }
            }
            assertFalse(manager.isFileOpen(view))
        } finally { Disposer.dispose(tab) }
    }

    fun `test canvas fills native editor and survives closing and reopening independently of chat`() {
        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        val manager = FileEditorManager.getInstance(project)
        try {
            val file = file("src/a.kt").copy(after = "val a = 1\n")
            tab.update(ReviewSnapshot(listOf(file), emptyList()))
            runWithEvents {
                tab.captureContext().addNotes(listOf(ReviewNote(listOf(file.path), "Keep this card")))
                tab.captureContext().showSource(ReviewSourceRange(file.path, 1, 1))
            }
            val sourceEditor = descendants(tab.canvas).filterIsInstance<DiffSourceEditor>().single().editor
            val chat = tab.chat
            chat.input.text = "Keep this draft"
            tab.canvas.tabs.selectedIndex = 0
            val bounds = tab.canvas.components.map { it.bounds }
            tab.openCanvas()
            tab.openCanvas()
            assertEquals(1, manager.getEditors(tab.canvasFile).size)
            val editor = manager.getEditors(tab.canvasFile).single()
            assertEquals(listOf(tab.canvas.editorPage(dev.insight.review.CanvasTabKey.Summaries)), editor.component.components.toList())
            assertSame(tab.canvas.editorPage(dev.insight.review.CanvasTabKey.Summaries), editor.preferredFocusedComponent)
            assertFalse(descendants(tab).any { it is NoteCanvas })
            assertFalse(descendants(editor.component).any { it is ChatPanel })

            // Closing the view must not stop a queued reply or dispose the review's source viewers.
            tab.session.send("Keep this queued reply")
            manager.closeFile(tab.canvasFile)
            assertFalse(manager.isFileOpen(tab.canvasFile))
            assertFalse(sourceEditor.isDisposed)
            assertTrue(tab.session.busy)
            assertEquals("Keep this draft", chat.input.text)
            assertEquals("Keep this card", tab.state.notes.single().text)
            tab.openCanvas()
            assertEquals(0, tab.canvas.tabs.selectedIndex)
            assertEquals(bounds, tab.canvas.components.map { it.bounds })
            assertSame(sourceEditor, descendants(tab.canvas).filterIsInstance<DiffSourceEditor>().single().editor)
            tab.session.stop()
            Disposer.dispose(tab)
            assertFalse(manager.isFileOpen(tab.canvasFile))
            assertTrue(sourceEditor.isDisposed)
            assertFalse(editor.isValid)
            assertFalse(ReviewCanvasEditorProvider().accept(project, tab.canvasFile))
        } finally { Disposer.dispose(tab) }
    }

    fun `test native canvas selection follows review controls and review deletion closes its canvas`() {
        val panel = DiffReviewPanel(project)
        JRootPane().contentPane.add(panel)
        val manager = FileEditorManager.getInstance(project)
        try {
            val tabs = descendants(panel).filterIsInstance<JBTabs>().single()
            val firstInfo = tabs.selectedInfo!!
            val first = firstInfo.component as DiffReviewTab
            assertTrue(manager.isFileOpen(first.canvasFile))
            assertTrue(first.state.summariesVisible)
            assertEquals(dev.insight.review.ReviewSummariesMode.SPLIT, first.canvas.summariesView.mode)
            assertEquals(ReviewDiffMode.LOCAL, first.state.snapshot.comparison.request.mode)
            assertFalse(first.session.busy)
            assertTrue(first.session.messages.none { it.role == ChatRole.USER })
            descendants(panel).filterIsInstance<JButton>().single { it.text == "New tab" }.doClick()
            val second = tabs.selectedInfo!!.component as DiffReviewTab
            assertTrue(manager.isFileOpen(second.canvasFile))
            assertNotSame(first.canvas.editorPage(dev.insight.review.CanvasTabKey.Summaries).parent,
                second.canvas.editorPage(dev.insight.review.CanvasTabKey.Summaries).parent)
            manager.openFile(first.canvasFile, true)
            PlatformTestUtil.waitWithEventsDispatching("Canvas selection should select the matching review controls", {
                tabs.selectedInfo === firstInfo
            }, 10 /* seconds */)
            assertSame(firstInfo, tabs.selectedInfo)
            assertTrue(descendants(panel).any { it === first })
            closeButton(tabs, firstInfo).doClick()
            assertFalse(manager.isFileOpen(first.canvasFile))
            assertTrue(first.isDisposed)
            assertTrue(manager.isFileOpen(second.canvasFile))
        } finally { Disposer.dispose(panel) }
        assertTrue(manager.openFiles.none { it is ReviewCanvasFile })
    }

    fun `test startup waits for editor restoration and open canvas heals detached windows and reopens closed tabs`() {
        val startup = com.intellij.openapi.startup.StartupManager.getInstance(project)
        val freezeField = startup.javaClass.getDeclaredField("freezePostStartupActivities").apply { isAccessible = true }
        val activitiesField = startup.javaClass.getDeclaredField("postStartupActivities").apply { isAccessible = true }
        val manager = com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project)
        freezeField.setBoolean(startup, false)
        val panel = DiffReviewPanel(project, startupReady = false)
        JRootPane().contentPane.add(panel)
        try {
            val tabs = descendants(panel).filterIsInstance<JBTabs>().single()
            val firstInfo = tabs.selectedInfo!!
            val first = firstInfo.component as DiffReviewTab
            assertFalse("Tool window restored during IDE startup must wait for editor restoration before opening canvas",
                manager.isFileOpen(first.canvasFile))
            val pending = activitiesField.get(startup) as java.util.ArrayDeque<*>
            while (pending.isNotEmpty()) (pending.removeFirst() as Runnable).run()
            assertTrue("Canvas must open once post-startup activities run", manager.isFileOpen(first.canvasFile))

            // Closing the canvas tab and clicking "Open review", the tab header, or showing the tool window must reopen it.
            val openCanvasButton = descendants(first).filterIsInstance<JButton>().single { it.text == "Open review" }
            manager.closeFile(first.canvasFile)
            assertFalse(manager.isFileOpen(first.canvasFile))
            openCanvasButton.doClick()
            assertTrue("Open review button must reopen a closed canvas tab", manager.isFileOpen(first.canvasFile))

            manager.closeFile(first.canvasFile)
            assertFalse(manager.isFileOpen(first.canvasFile))
            val label = tabs.getTabLabel(firstInfo)!!
            val click = java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1)
            tabs.component.mouseListeners.forEach { it.mouseClicked(click) }
            label.mouseListeners.forEach { it.mouseClicked(click) }
            assertTrue("Clicking the selected review tab must reopen its closed canvas", manager.isFileOpen(first.canvasFile))

            manager.closeFile(first.canvasFile)
            assertFalse(manager.isFileOpen(first.canvasFile))
            val toolWindow = object : com.intellij.toolWindow.ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
                override fun getId() = "Diff Review"
            }
            project.messageBus.syncPublisher(com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC).toolWindowShown(toolWindow)
            assertTrue("Showing the Diff Review tool window must reopen a closed canvas", manager.isFileOpen(first.canvasFile))
            Disposer.dispose(toolWindow.disposable)

            // Simulate EditorsSplitters.restoreEditors replacing BorderLayout.CENTER with a new EditorWindow while
            // leaving the earlier EditorWindow (with canvasFile open) detached in splitters.windows and currentWindow.
            val orphaned = manager.currentWindow!!
            val splitters = orphaned.owner
            val scope = orphaned.javaClass.getDeclaredField("coroutineScope").apply { isAccessible = true }.get(orphaned)
            val visible = orphaned.javaClass.declaredConstructors.single().apply { isAccessible = true }
                .newInstance(splitters, scope) as com.intellij.openapi.fileEditor.impl.EditorWindow
            splitters.add(visible.tabbedPane.component, BorderLayout.CENTER)
            @Suppress("UNCHECKED_CAST")
            val windows = splitters.javaClass.getDeclaredField("windows").apply { isAccessible = true }
                .get(splitters) as MutableCollection<Any>
            windows.add(visible)
            assertTrue(orphaned.isFileOpen(first.canvasFile))
            assertFalse(visible.isFileOpen(first.canvasFile))
            openCanvasButton.doClick()
            assertFalse("Detached window must no longer hold the canvas tab", orphaned.isFileOpen(first.canvasFile))
            assertTrue("Visible editor window must own the reopened canvas tab", visible.isFileOpen(first.canvasFile))
            assertSame(visible, manager.currentWindow)
            assertTrue(javax.swing.SwingUtilities.isDescendingFrom(
                first.canvas.editorPage(dev.insight.review.CanvasTabKey.Summaries), visible.tabbedPane.component))
        } finally { Disposer.dispose(panel) }
    }

    fun `test untracked tree groups paths toggles folders and retains collapsed folders on refresh`() {
        val included = linkedSetOf("/project/src/a.kt")
        var changes = 0
        val tree = UntrackedFilesTree { file, selected ->
            changes++
            if (selected) included += file.absolutePath else included -= file.absolutePath
        }
        val files = listOf(
            UntrackedFile("src/a.kt", "/project/src/a.kt"),
            UntrackedFile("src/nested/b.kt", "/project/src/nested/b.kt"),
            UntrackedFile("README.md", "/project/README.md"),
        )
        fun folder(name: String) = (tree.model.root as com.intellij.ui.CheckedTreeNode)
            .depthFirstEnumeration().toList().filterIsInstance<com.intellij.ui.CheckedTreeNode>()
            .single { it.userObject == name }
        tree.showFiles(files, included)
        assertEquals(0, changes)
        val src = folder("src")
        assertEquals(2, src.childCount)
        assertTrue(tree.isExpanded(TreePath(src.path)))
        assertSame(src, folder("nested").parent)
        tree.setNodeState(src, true)
        assertEquals(files.take(2).map { it.absolutePath }.toSet(), included)
        tree.setNodeState(src, false)
        assertTrue(included.isEmpty())
        tree.collapsePath(TreePath(src.path))
        val previousChanges = changes
        tree.showFiles(files + UntrackedFile("src/new.kt", "/project/src/new.kt"), included)
        assertEquals(previousChanges, changes)
        assertFalse(tree.isExpanded(TreePath(folder("src").path)))
        tree.showFiles(emptyList(), emptySet())
        assertEquals(0, (tree.model.root as com.intellij.ui.CheckedTreeNode).childCount)
    }

    fun `test repository controls default to current root and preserve explicit selection on reload`() {
        val controls = DiffReviewSourceControls { }
        val repositories = listOf(ReviewRepository("/other", listOf("HEAD")), ReviewRepository("/current", listOf("HEAD", "main")))
        controls.setRepositories(repositories, "/current")
        val combo = descendants(controls).filterIsInstance<JComboBox<*>>().single { it.name == "diffRepository" }
        assertEquals("/current", (combo.selectedItem as ReviewRepository).path)
        combo.selectedItem = repositories.first()
        controls.setRepositories(repositories, "/current")
        assertEquals("/other", (combo.selectedItem as ReviewRepository).path)
        controls.setRepositories(repositories.drop(1), "/current")
        assertEquals("/current", (combo.selectedItem as ReviewRepository).path)
    }

    fun `test repository list reloads when VCS mappings change after opening the panel`() {
        val available = java.util.concurrent.atomic.AtomicReference(emptyList<ReviewRepository>())
        val loads = java.util.concurrent.atomic.AtomicInteger()
        ExtensionTestUtil.maskExtensions(ReviewDiffProvider.EP_NAME, listOf(object : ReviewDiffProvider {
            override suspend fun repositories(project: com.intellij.openapi.project.Project): List<ReviewRepository> {
                loads.incrementAndGet()
                return available.get()
            }
            override fun changes(project: com.intellij.openapi.project.Project, request: ReviewDiffRequest): ReviewChanges? = null
        }), testRootDisposable)
        val panel = DiffReviewPanel(project)
        try {
            val combo = descendants(panel).filterIsInstance<JComboBox<*>>().single { it.name == "diffRepository" }
            PlatformTestUtil.waitWithEventsDispatching("Initial discovery should finish", {
                loads.get() > 0 && descendants(panel).filterIsInstance<JLabel>().any { it.text.startsWith("0 files") }
            }, 10 /* seconds */)
            assertEquals(0, combo.itemCount)
            val current = ReviewRepository(project.basePath!!, listOf("HEAD", "main"))
            available.set(listOf(ReviewRepository("/another-root", listOf("HEAD")), current))
            project.messageBus.syncPublisher(com.intellij.openapi.vcs.ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED).directoryMappingChanged()
            PlatformTestUtil.waitWithEventsDispatching("New roots should appear without a manual refresh", { combo.itemCount == 2 }, 10 /* seconds */)
            assertEquals(current.path, (combo.selectedItem as ReviewRepository).path)
        } finally { Disposer.dispose(panel) }
    }

    fun `test historical loader uses revision content while working tree loader includes editor text`() {
        val psi = myFixture.configureByText("sample.txt", "unsaved working copy\n")
        // The fixture uses an in-memory file, which a local FilePath cannot rediscover by path.
        val path = object : com.intellij.openapi.vcs.FilePath by VcsUtil.getFilePath(psi.virtualFile) {
            override fun getVirtualFile() = psi.virtualFile
        }
        fun revision(text: String) = object : ContentRevision {
            override fun getFile() = path
            override fun getRevisionNumber() = VcsRevisionNumber.NULL
            override fun getContent() = text
        }
        val change = Change(revision("base content\n"), revision("committed target\n"))
        ExtensionTestUtil.maskExtensions(ReviewDiffProvider.EP_NAME, listOf(object : ReviewDiffProvider {
            override suspend fun repositories(project: com.intellij.openapi.project.Project) = emptyList<ReviewRepository>()
            override fun changes(project: com.intellij.openapi.project.Project, request: ReviewDiffRequest) =
                ReviewChanges(listOf(change), ReviewComparison(request, "base-sha", if (request.mode.usesWorkingTree) null else "target-sha"))
        }), testRootDisposable)
        runWithEvents {
            for (mode in listOf(ReviewDiffMode.REVISIONS, ReviewDiffMode.MERGE_BASE, ReviewDiffMode.WORKING_TREE)) {
                val request = ReviewDiffRequest(mode, "/repo", "main", "topic")
                val snapshot = withContext(Dispatchers.IO) { DiffReviewLoader(project).load(emptySet(), request) }
                val file = snapshot.files.single()
                assertEquals("base content\n", file.before)
                assertEquals(if (mode.usesWorkingTree) "unsaved working copy\n" else "committed target\n", file.after)
                assertTrue(file.patch.contains("-base content"))
                assertEquals(mode, snapshot.comparison.request.mode)
                assertTrue(snapshot.untracked.isEmpty())
                val diff = createDiffRequest(project, file)
                assertTrue(diff.contentTitles[0]!!.contains("base-sha"))
                assertTrue(diff.contentTitles[1]!!.contains(if (mode.usesWorkingTree) "Working tree" else "target-sha"))
                val tab = DiffReviewTab(project, setOf(file.path), { emptyList() }, {})
                try {
                    tab.update(snapshot)
                    val context = tab.captureContext()
                    val data = JsonParser.parseString(context.state(0)).asJsonObject
                    assertEquals(mode.name, data["comparison"].asJsonObject["request"].asJsonObject["mode"].asString)
                    context.showSource(ReviewSourceRange(file.path, 1, 1))
                    assertEquals(file.after!!.trimEnd(), tab.state.sources.single().text)
                    context.addNotes(listOf(ReviewNote(listOf(file.path), "Snapshot note")))
                    assertEquals(1, tab.state.notes.size)
                    tab.update(snapshot.copy(comparison = snapshot.comparison.copy(baseRevision = "another-base")))
                    assertEquals(1, tab.state.notes.size)
                    assertEquals(1, tab.state.sources.size)
                    rejected { context.read(listOf(file.path), 0) }
                    tab.update(snapshot.copy(comparison = snapshot.comparison.copy(request = request.copy(base = "another-ref"))))
                    assertTrue(tab.state.notes.isEmpty())
                    assertTrue(tab.state.sources.isEmpty())
                } finally { Disposer.dispose(tab) }
            }
        }
    }

    fun `test diff source controls apply drafts and preserve typed commit expressions`() {
        var applied: ReviewDiffRequest? = null
        val controls = DiffReviewSourceControls { applied = it.validated() }
        controls.setRepositories(listOf(ReviewRepository("/repo", listOf("HEAD", "main", "origin/topic"))))
        val combos = descendants(controls).filterIsInstance<JComboBox<*>>().associateBy { it.name }
        val mode = combos.getValue("diffMode")
        val base = combos.getValue("diffBase")
        val target = combos.getValue("diffTarget")
        mode.selectedItem = ReviewDiffMode.REVISIONS
        base.editor.item = "HEAD~3"
        target.editor.item = "origin/topic"
        assertNull(applied)
        descendants(controls).filterIsInstance<JButton>().single { it.text == "Apply diff" }.doClick()
        assertEquals(ReviewDiffRequest(ReviewDiffMode.REVISIONS, "/repo", "HEAD~3", "origin/topic"), applied)
        // Refreshing the suggestions must retain a manually entered commit expression.
        controls.setRepositories(listOf(ReviewRepository("/repo", listOf("HEAD", "main"))))
        assertEquals("HEAD~3", base.editor.item)
        mode.selectedItem = ReviewDiffMode.LOCAL
        descendants(controls).filterIsInstance<JButton>().single { it.text == "Apply diff" }.doClick()
        assertEquals(ReviewDiffRequest(), applied)
    }

    fun `test source tool renders snapshot lines and rejects stale out of scope and closed requests`() {
        val tab = DiffReviewTab(project, setOf("src/a.kt"), { emptyList() }, {})
        try {
            val snapshot = ReviewSnapshot(listOf(file("src/a.kt").copy(after = "val a = 1\nprintln(a)\n"),
                file("src/b.kt").copy(after = "outside")), emptyList())
            tab.update(snapshot)
            val context = tab.captureContext()
            val range = ReviewSourceRange("src/a.kt", 2, 2)
            runWithEvents {
                context.showSource(range)
                val viewer = descendants(tab.canvas).filterIsInstance<DiffSourceEditor>().single().editor
                assertEquals("println(a)", viewer.document.text)
                val data = JsonParser.parseString(context.state(0)).asJsonObject
                assertEquals(1, data["source_blocks_count"].asInt)
                rejected { context.showSource(range.copy(path = "src/b.kt")) }
                tab.update(snapshot.copy(files = listOf(snapshot.files.first().copy(fingerprint = "2", after = "changed"))))
                assertTrue(viewer.isDisposed)
                assertTrue(tab.state.sources.isEmpty())
                rejected { context.showSource(range) }
                val current = tab.captureContext()
                Disposer.dispose(tab)
                rejected { current.showSource(range) }
            }
        } finally { Disposer.dispose(tab) }
    }

    fun `test block diff button adds native diff card to tab canvas and expires on file change`() {
        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            val fileA = file("src/a.kt").copy(before = "val a = 1\n", after = "val a = 2\n")
            val fileB = file("src/b.kt").copy(before = "val b = 1\n", after = "val b = 2\n")
            val snapshot = ReviewSnapshot(listOf(fileA, fileB), emptyList())
            tab.update(snapshot)
            val context = tab.captureContext()
            runWithEvents {
                context.addNotes(listOf(ReviewNote(listOf("src/a.kt"), "Explains A.")))
                val showButton = descendants(tab.canvas).filterIsInstance<InplaceButton>()
                    .single { it.toolTipText == "Show diff in tab" }
                showButton.doClick()
                assertEquals(listOf(fileA), tab.state.diffs)
                val diffViewer = descendants(tab.canvas).filterIsInstance<DiffFileEditor>().single()
                assertEquals(setOf("val a = 1\n", "val a = 2\n"), diffViewer.editors.map { it.document.text }.toSet())

                // Unchanged evidence for src/a.kt keeps the diff card alive when another file changes.
                tab.update(snapshot.copy(files = listOf(fileA, fileB.copy(fingerprint = "2"))))
                assertEquals(listOf(fileA), tab.state.diffs)
                assertFalse(diffViewer.isDisposed)

                // Changed evidence for src/a.kt removes and disposes the diff card.
                tab.update(snapshot.copy(files = listOf(fileA.copy(fingerprint = "2"), fileB.copy(fingerprint = "2"))))
                assertTrue(tab.state.diffs.isEmpty())
                assertTrue(diffViewer.isDisposed)
            }
        } finally { Disposer.dispose(tab) }
    }

    fun `test grouped notes and selected paths cannot truncate file discovery through the tool runner`() {
        val files = (1..60).map { file("src/" + "long-directory/".repeat(5) + "$it.kt") }
        val selected = files.map { it.path }
        val tab = DiffReviewTab(project, null, { selected }, {})
        try {
            tab.update(ReviewSnapshot(files, emptyList()))
            val notes = (0..19).map { index -> ReviewNote(
                listOf(files.first().path) + files.drop(index + 1).take(29).map { it.path },
                "\"\\\t\u0001".repeat(40),
            ) }
            tab.state.addNotes(tab.state.revision, notes)
            val context = tab.captureContext()
            runWithEvents {
                val discovered = mutableListOf<String>()
                val runner = ToolChatRunner({ request ->
                    val last = request.messages.last()
                    val offset = if (last.role == LlmRole.TOOL) {
                        val json = JsonParser.parseString(last.content).asJsonObject
                        assertTrue(last.content.length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS)
                        val page = json["files"].asJsonArray.map { it.asJsonObject }
                        assertEquals(files.size, json["selected_count"].asInt)
                        assertTrue(page.all { it["selected"].asBoolean })
                        if (discovered.isEmpty()) {
                            assertEquals(20, json["notes_count"].asInt)
                            assertTrue((json["notes"]?.asJsonArray?.size() ?: 0) < 20)
                            assertTrue(page.first()["has_note"].asBoolean)
                        }
                        discovered += page.map { it["path"].asString }
                        json["next_offset"]?.asInt ?: return@ToolChatRunner Completion("Done.")
                    } else 0
                    Completion("", toolCalls = listOf(LlmToolCall("page-$offset", "get_current_diff",
                        JsonParser.parseString("""{"offset":$offset}""").asJsonObject)))
                }, diffReviewTools(context))
                runner.run(listOf(LlmMessage.user("List all changed files")))
                assertEquals(selected, discovered)
            }
        } finally { Disposer.dispose(tab) }
    }

    fun `test unrelated edits preserve scoped tools while changed revisions invalidate them`() {
        val scoped = DiffReviewTab(project, setOf("src/a.kt"), { emptyList() }, {})
        val all = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            val initial = ReviewSnapshot(listOf(file("src/a.kt"), file("src/b.kt")), emptyList())
            scoped.update(initial)
            all.update(initial)
            val context = scoped.captureContext()
            scoped.invalidate(stop = true, setOf("/project/src/b.kt"))
            all.invalidate(stop = true, setOf("/project/src/b.kt"))
            assertFalse(scoped.state.refreshing)
            assertTrue(all.state.refreshing)
            runWithEvents {
                context.read(listOf("src/a.kt"), 0)
                context.addNotes(listOf(ReviewNote(listOf("src/a.kt"), "Still valid during refresh")))
                val revision = scoped.state.revision
                scoped.update(initial.copy(files = initial.files + file("new.kt")))
                assertEquals(revision, scoped.state.revision)
                context.read(listOf("src/a.kt"), 0)
                // A refresh with no paths must still invalidate evidence when its revision changes.
                scoped.update(initial.copy(files = listOf(file("src/a.kt").copy(patch = "+changed", fingerprint = "2"))))
                rejected { context.read(listOf("src/a.kt"), 0) }
                val current = scoped.captureContext()
                scoped.invalidate(stop = true, setOf("/project/src/a.kt"))
                rejected { current.read(listOf("src/a.kt"), 0) }
                scoped.update(ReviewSnapshot(emptyList(), emptyList()))
                scoped.invalidate(stop = true, setOf("src/a.kt", "/project/src/a.kt"))
                assertTrue("Re-including a scoped file also invalidates the tab", scoped.state.refreshing)
            }
        } finally { Disposer.dispose(scoped); Disposer.dispose(all) }
    }

    fun `test only intersecting tabs stop queued replies`() {
        val scoped = DiffReviewTab(project, setOf("src/a.kt"), { emptyList() }, {})
        try {
            scoped.update(ReviewSnapshot(listOf(file("src/a.kt"), file("src/b.kt")), emptyList()))
            // Stay on the EDT so the queued turn cannot contact a backend during this test.
            scoped.session.send("Review A")
            assertTrue(scoped.session.busy)
            scoped.invalidate(stop = true, setOf("/project/src/b.kt"))
            assertFalse(scoped.session.messages.any { it.text == "Stopped." })
            scoped.invalidate(stop = true, setOf("/project/src/a.kt", "/project/src/b.kt"))
            assertTrue(scoped.session.messages.any { it.text == "Stopped." })
            scoped.update(scoped.state.snapshot)
            scoped.invalidate(stop = false)
            assertTrue(scoped.state.refreshing)
        } finally { Disposer.dispose(scoped) }
    }

    fun `test a chat turn uses the injected completion`() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<dev.insight.llm.CompletionRequest>()
        val tab = DiffReviewTab(project, null, { emptyList() }, {}, complete = { requests += it; Completion("Injected reply.") })
        try {
            tab.update(ReviewSnapshot(listOf(file("src/a.kt")), emptyList()))
            tab.session.send("What changed?")
            PlatformTestUtil.waitWithEventsDispatching("The reply should finish", { !tab.session.busy }, 10 /* seconds */)
            assertEquals(1, requests.size)
            assertTrue(tab.session.messages.any { it.text == "Injected reply." })
        } finally { Disposer.dispose(tab) }
    }

    fun `test untracked inclusion and exclusion leave unrelated tabs usable during panel refresh`() {
        val panel = DiffReviewPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Local changes should load", {
                descendants(panel).filterIsInstance<JLabel>().any { it.text.startsWith("0 files") }
            }, 10 /* seconds */)
            val state = DiffReviewPanel::class.java.getDeclaredField("state").apply { isAccessible = true }.get(panel) as DiffReviewState
            state.update(ReviewSnapshot(listOf(file("src/a.kt")), listOf(UntrackedFile("new.kt", "/project/new.kt"))))
            DiffReviewPanel::class.java.getDeclaredMethod("renderFiles").apply { isAccessible = true }.invoke(panel)
            val tree = descendants(panel).filterIsInstance<JTree>().single { it !is UntrackedFilesTree && it.name != "Change summaries" }
            val root = tree.model.root as DefaultMutableTreeNode
            val node = root.depthFirstEnumeration().toList().filterIsInstance<DefaultMutableTreeNode>().single { it.userObject is ReviewFile }
            tree.selectionPath = TreePath(node.path)
            descendants(panel).filterIsInstance<JButton>().single { it.text == "New tab from selection" }.doClick()
            val tabs = descendants(panel).filterIsInstance<JBTabs>().single()
            val scoped = tabs.selectedInfo!!.component as DiffReviewTab
            assertEquals(setOf("src/a.kt"), scoped.state.scopePaths)
            val untracked = descendants(panel).filterIsInstance<UntrackedFilesTree>().single()
            val untrackedRoot = untracked.model.root as com.intellij.ui.CheckedTreeNode
            val checkbox = untrackedRoot.firstChild as com.intellij.ui.CheckedTreeNode
            repeat(2) {
                untracked.setNodeState(checkbox, !checkbox.isChecked)
                assertFalse(scoped.state.refreshing)
                scoped.captureContext()
            }
        } finally { Disposer.dispose(panel) }
    }

    fun `test real IDE comparisons produce evidence for empty files EOF changes and deletions`() {
        val cases = listOf(
            "" to "one\ntwo\n",
            "one\ntwo\n" to "",
            "a\nb\n" to "a\nnew\nb\n",
            "a\nb\n" to "a\n",
            "a\n" to "a",
            "a" to "a\n",
            "" to "a",
            "a" to "",
            "\n" to "",
        )
        for ((before, after) in cases) {
            val patch = reviewPatch(before, after, DiffService.lineChanges(before, after))
            assertTrue("Missing evidence for <$before> -> <$after>", patch.isNotEmpty())
        }
        val patch = reviewPatch("a\nremove\nb\n", "a\nb\n", DiffService.lineChanges("a\nremove\nb\n", "a\nb\n"))
        assertTrue(patch, patch.contains("-remove\n"))
    }

    fun `test diff panel loads an empty project and owns its chat controls`() {
        val panel = DiffReviewPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Local changes should load", {
                descendants(panel).filterIsInstance<JLabel>().any { it.text.startsWith("0 files") }
            }, 10 /* seconds */)
            val buttons = descendants(panel).filterIsInstance<JButton>()
            assertFalse(buttons.single { it.text == "Topics" }.isEnabled)
            assertTrue(buttons.any { it.text == "Send" })
            assertTrue(buttons.any { it.text == "LLM settings" })
            assertFalse(buttons.any { it.text == "Open dashboard" })
            assertTrue(descendants(panel).filterIsInstance<JLabel>().any { it.text == "Untracked · include in review" })
        } finally { Disposer.dispose(panel) }
    }

    fun `test global files sit above review controls with independent drafts notes and chat resets`() {
        val panel = DiffReviewPanel(project)
        JRootPane().contentPane.add(panel)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Local changes should load", {
                descendants(panel).filterIsInstance<JLabel>().any { it.text.startsWith("0 files") }
            }, 10 /* seconds */)
            val pages = (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as ReviewSidePages
            assertEquals(listOf("Main", "Chat"), (0 until pages.tabCount).map(pages::getTitleAt))
            val main = pages.getComponentAt(0) as Container
            val chatPage = pages.getComponentAt(1) as Container
            assertTrue(descendants(main).any { it is JTree })
            assertFalse(descendants(main).any { it is ChatPanel })
            val tabs = descendants(main).filterIsInstance<JBTabs>().single()
            assertFalse(tabs.presentation.isHideTabs)
            assertEquals("All changes", tabs.selectedInfo!!.text)
            assertVisibleHeader(panel, tabs, tabs.selectedInfo!!)
            val first = tabs.selectedInfo!!.component as DiffReviewTab
            assertTrue(descendants(first).any { it is JButton && it.text == "Summaries" })
            assertFalse(descendants(first).any { it is ChatPanel })
            val firstChat = first.chat
            assertSame(firstChat, descendants(chatPage).filterIsInstance<ChatPanel>().single())
            firstChat.input.text = "First topic draft"
            val snapshot = ReviewSnapshot(listOf(file("src/a.kt"), file("src/b.kt")), emptyList())
            first.update(snapshot)
            first.state.addNote(first.state.revision, listOf("src/a.kt"), "First note")

            descendants(panel).filterIsInstance<JButton>().single { it.text == "New tab" }.doClick()
            assertEquals("Review 2", tabs.selectedInfo!!.text)
            tabs.tabs.forEach { assertVisibleHeader(panel, tabs, it) }
            val second = tabs.selectedInfo!!.component as DiffReviewTab
            assertNotSame(first, second)
            assertNotSame(first.session, second.session)
            assertTrue(second.state.notes.isEmpty())
            val secondChat = second.chat
            assertSame(secondChat, descendants(chatPage).filterIsInstance<ChatPanel>().single())
            assertEquals("", secondChat.input.text)
            secondChat.input.text = "Second topic draft"
            second.update(snapshot)
            second.state.addNote(second.state.revision, listOf("src/a.kt"), "Different note")
            PlatformTestUtil.waitForCallback(tabs.select(tabs.tabs.single { it.component === first }, false))
            assertSame(firstChat, descendants(chatPage).filterIsInstance<ChatPanel>().single())
            assertEquals("First topic draft", firstChat.input.text)
            assertEquals("First note", first.state.notes.single().text)
            descendants(first.chat).filterIsInstance<JButton>().single { it.text == "New chat" }.doClick()
            descendants(first).filterIsInstance<JButton>().single { it.text == "Clear tabs" }.doClick()
            assertTrue(first.session.messages.isEmpty())
            assertTrue(first.state.notes.isEmpty())
            assertFalse(second.session.messages.isEmpty())
            assertEquals("Different note", second.state.notes.single().text)
            PlatformTestUtil.waitForCallback(tabs.select(tabs.tabs.single { it.component === second }, false))
            assertEquals("Second topic draft", secondChat.input.text)

            // Folder selection creates a fixed scope; subsequent tree selections do not change it.
            val tree = descendants(panel).filterIsInstance<JTree>().single { it !is UntrackedFilesTree && it.name != "Change summaries" }
            val root = DefaultMutableTreeNode("Changes")
            val folder = DefaultMutableTreeNode("src").also(root::add)
            folder.add(DefaultMutableTreeNode(file("src/a.kt")))
            folder.add(DefaultMutableTreeNode(file("src/b.kt")))
            tree.model = DefaultTreeModel(root)
            tree.selectionPath = TreePath(folder.path)
            val selectedTabButton = descendants(panel).filterIsInstance<JButton>().single { it.text == "New tab from selection" }
            assertTrue(selectedTabButton.isEnabled)
            selectedTabButton.doClick()
            val scoped = tabs.selectedInfo!!.component as DiffReviewTab
            assertEquals(setOf("src/a.kt", "src/b.kt"), scoped.state.scopePaths)
            tree.clearSelection()
            assertFalse(selectedTabButton.isEnabled)
            assertEquals(setOf("src/a.kt", "src/b.kt"), scoped.state.scopePaths)

            // Click the actual native close control, including the final tab's control.
            repeat(3) {
                val closed = tabs.selectedInfo!!.component as DiffReviewTab
                closeButton(tabs, tabs.selectedInfo!!).doClick()
                assertTrue(closed.session.messages.isEmpty())
                assertTrue(closed.state.notes.isEmpty())
                assertTrue(closed.state.snapshot.files.isEmpty())
                assertEquals(0, closed.componentCount)
            }
            assertEquals("", firstChat.input.text)
            assertEquals("", secondChat.input.text)
            assertEquals(1, tabs.tabCount)
            val fresh = tabs.selectedInfo!!.component as DiffReviewTab
            assertNull(fresh.state.scopePaths)
            assertTrue(fresh.state.notes.isEmpty())
        } finally { Disposer.dispose(panel) }
    }

    fun `test summaries button opens a canvas card and preserves completed summaries when reopened`() {
        val panel = DiffReviewPanel(project)
        JRootPane().contentPane.add(panel)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Local changes should load", {
                descendants(panel).filterIsInstance<JLabel>().any { it.text.startsWith("0 files") }
            }, 10 /* seconds */)
            val tabs = descendants(panel).filterIsInstance<JBTabs>().single()
            val tab = tabs.selectedInfo!!.component as DiffReviewTab
            val snapshot = ReviewSnapshot(listOf(file("src/app/a.kt"), file("src/app/b.kt"), file("docs/guide.md")), emptyList())
            (DiffReviewPanel::class.java.getDeclaredField("state").apply { isAccessible = true }.get(panel) as DiffReviewState).update(snapshot)
            tab.update(snapshot)
            assertTrue(descendants(panel).filterIsInstance<ChangeSummariesPanel>().isEmpty())

            val nodes = tab.state.summaries.tree.nodes
            assertEquals(listOf("./", "docs/", "docs/guide.md", "src/", "src/app/", "src/app/a.kt", "src/app/b.kt"), nodes.map { it.location })
            tab.state.summarize(tab.state.revision, nodes.associate { it.id to "Summary of ${it.location} & more" })
            val open = descendants(tab).filterIsInstance<JButton>().single { it.text == "Summaries" }
            val manager = FileEditorManager.getInstance(project)
            manager.closeFile(tab.canvasFile)
            open.doClick()
            assertTrue("Summaries must show the canvas editor", manager.isFileOpen(tab.canvasFile))
            assertTrue(tab.state.summariesVisible)
            assertFalse(tab.session.busy)
            assertEquals(1, descendants(tab.canvas).count { it.name == "summary-split-panels" })
            assertTrue(descendants(tab.canvas).filterIsInstance<JButton>().single { it.text == "Regenerate" }.isEnabled)
            descendants(tab).filterIsInstance<JButton>().single { it.text == "Clear tabs" }.doClick()
            assertFalse(tab.state.summariesVisible)
            assertTrue(descendants(tab.canvas).none { it.name == "summary-split-panels" })
            open.doClick()
            assertTrue(tab.state.summariesVisible)
            assertTrue(tab.state.summaries.complete)
            assertEquals(1, descendants(tab.canvas).count { it.name == "summary-split-panels" })
            assertEquals("Reopening completed summaries must not send a turn", 0, tab.session.messages.count { it.text == "/summaries" })

            // Stay on the EDT so the queued turn cannot contact a backend during this test.
            descendants(tab.canvas).filterIsInstance<JButton>().single { it.text == "Regenerate" }.doClick()
            assertTrue(tab.session.busy)
            assertEquals("/summaries", tab.session.messages.last { it.role == ChatRole.USER }.text)
            open.doClick()
            assertEquals("Opening while busy must not send another turn", 1, tab.session.messages.count { it.text == "/summaries" })
            tab.session.stop()
        } finally { Disposer.dispose(panel) }
    }

    fun `test scoped tools cannot read or annotate other files and closed tabs reject captured tools`() {
        var selection = listOf("src/a.kt", "src/b.kt")
        val tab = DiffReviewTab(project, setOf("src/a.kt"), { selection }, {})
        val other = DiffReviewTab(project, null, { selection }, {})
        try {
            val snapshot = ReviewSnapshot(listOf(file("src/a.kt"), file("src/b.kt")), emptyList())
            tab.update(snapshot)
            other.update(snapshot)
            val context = tab.captureContext()
            val otherContext = other.captureContext()
            selection = listOf("src/b.kt")
            runWithEvents {
                val data = JsonParser.parseString(context.state(0)).asJsonObject
                assertEquals(1, data["total_files"].asInt)
                assertEquals(listOf("src/a.kt"), data["selected_paths"].asJsonArray.map { it.asString })
                assertEquals(listOf("src/a.kt"), data["files"].asJsonArray.map { it.asJsonObject["path"].asString })
                rejected { context.read(listOf("src/b.kt"), 0) }
                rejected { context.addNotes(listOf(ReviewNote(listOf("src/a.kt", "src/b.kt"), "Out of scope"))) }
                context.addNotes(listOf(ReviewNote(listOf("src/a.kt"), "Scoped note")))
                assertTrue(other.state.notes.isEmpty())
                Disposer.dispose(tab)
                rejected { context.state(0) }
                rejected { context.read(listOf("src/a.kt"), 0) }
                rejected { context.addNotes(listOf(ReviewNote(listOf("src/a.kt"), "Closed tab"))) }
                otherContext.addNotes(listOf(ReviewNote(listOf("src/b.kt"), "Still open")))
                assertEquals("Still open", other.state.notes.single().text)
            }
        } finally {
            Disposer.dispose(tab)
            Disposer.dispose(other)
        }
    }

    fun `test snapshot inlines small patches omits defaults and reads several files within a budget`() {
        val big = "+" + "x".repeat(9000) + "\n"
        val tab = DiffReviewTab(project, null, { listOf("src/b.kt") }, {})
        try {
            tab.update(ReviewSnapshot(listOf(file("src/a.kt"), file("src/b.kt"), ReviewFile("src/c.kt", "/project/src/c.kt", "src/old.kt", "renamed", big, "1")), emptyList()))
            val context = tab.captureContext()
            runWithEvents {
                val data = JsonParser.parseString(context.state(0, 100)).asJsonObject
                assertFalse(data.has("next_offset") || data.has("notes") || data.has("revision"))
                val files = data["files"].asJsonArray.map { it.asJsonObject }
                assertEquals("-old\n+new\n", files[0]["patch"].asString)
                assertFalse(files[0].has("selected") || files[0].has("has_note") || files[0].has("previous_path"))
                assertTrue(files[1]["selected"].asBoolean)
                assertEquals(big.length, files[2]["patch_characters"].asInt)
                assertEquals("src/old.kt", files[2]["previous_path"].asString)

                val read = JsonParser.parseString(context.read(listOf("src/c.kt", "src/a.kt", "src/b.kt"), 0)).asJsonObject
                val slices = read["files"].asJsonArray.map { it.asJsonObject }
                assertEquals(listOf("src/c.kt", "src/a.kt", "src/b.kt"), slices.map { it["path"].asString })
                assertEquals(6000, slices[0]["next_offset"].asInt)
                assertFalse(read.has("not_read"))
                val rest = JsonParser.parseString(context.read(listOf("src/c.kt"), 6000)).asJsonObject["files"].asJsonArray.single().asJsonObject
                // A line longer than a page is split; its rest keeps the line's number.
                assertEquals("1|" + big.substring(6000).removeSuffix("\n"), rest["patch"].asString)
                assertTrue(rest["first_line_continues"].asBoolean)
                assertFalse(rest.has("next_offset"))

                val added = context.addNotes(listOf(ReviewNote(listOf("src/a.kt", "src/b.kt"), "Changes both.")))
                assertEquals("Added 1 note.", added)
            }
        } finally { Disposer.dispose(tab) }
    }

    private fun assertVisibleHeader(panel: Container, tabs: JBTabs, info: TabInfo) {
        // Run the same action update as visible native tabs before laying out this offscreen root pane.
        (tabs.getTabLabel(info) as TabLabel).updateTabActions()
        val root = javax.swing.SwingUtilities.getRootPane(panel)
        root.setSize(1200, 900)
        fun layout(container: Container) {
            container.doLayout()
            container.components.filterIsInstance<Container>().forEach(::layout)
        }
        layout(root)
        val header = tabs.getTabLabel(info) as Container
        assertTrue("Tab header must have visible bounds", header.isVisible && header.width > 0 && header.height > 0)
        val title = descendants(header).filterIsInstance<SimpleColoredComponent>()
            .single { it.getCharSequence(false).toString() == info.text }
        assertTrue("Title must have space to render", title.isVisible && title.width >= title.preferredSize.width && title.height > 0)
        val close = closeButton(tabs, info)
        assertTrue("Close control must always be visible", close.isVisible && close.width > 0 && close.height > 0)
    }

    private fun closeButton(tabs: JBTabs, info: TabInfo): InplaceButton {
        val header = tabs.getTabLabel(info) as TabLabel
        header.updateTabActions()
        val buttons = descendants(header).filterIsInstance<InplaceButton>()
        assertEquals("Expected a close control in ${header.javaClass.name}: ${descendants(header).map { it.javaClass.simpleName }}", 1, buttons.size)
        return buttons.single().also { assertEquals("Close Tab", it.toolTipText) }
    }

    private fun file(path: String) = ReviewFile(path, "/project/$path", null, "modified", "-old\n+new\n", "1")

    private fun runWithEvents(action: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var failure: Throwable? = null
        val job = scope.launch { try { action() } catch (e: Throwable) { failure = e } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Review tools should finish", { job.isCompleted }, 5 /* seconds */)
            failure?.let { throw it }
        } finally { scope.cancel() }
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected a rejected tool call") } catch (_: ToolInputException) { }
    }

    private fun descendants(container: Container): List<java.awt.Component> = (if (container is dev.insight.diff.NoteCanvas)
        container.pageKeys.map(container::editorPage) else container.components.toList()).flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
