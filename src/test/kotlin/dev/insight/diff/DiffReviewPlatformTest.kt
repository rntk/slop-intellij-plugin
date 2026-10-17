package dev.insight.diff

import com.google.gson.JsonParser
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.callgraph.DiffService
import dev.insight.chat.ChatPanel
import dev.insight.chat.ToolInputException
import dev.insight.chat.ToolChatRunner
import dev.insight.llm.Completion
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.*
import java.awt.Container
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JSplitPane
import com.intellij.ui.InplaceButton
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.TabLabel
import javax.swing.JTree
import javax.swing.JRootPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class DiffReviewPlatformTest : BasePlatformTestCase() {
    fun `test source tool renders snapshot lines and rejects stale out of scope and closed requests`() {
        val tab = DiffReviewTab(project, setOf("src/a.kt"), { emptyList() }, {})
        try {
            val snapshot = ReviewSnapshot(listOf(file("src/a.kt").copy(after = "val a = 1\nprintln(a)\n"),
                file("src/b.kt").copy(after = "outside")), emptyList())
            tab.update(snapshot)
            val context = tab.captureContext()
            val range = DiffSourceRange("src/a.kt", 2, 2)
            runWithEvents {
                context.showSource(range)
                val viewer = descendants(tab).filterIsInstance<DiffSourceEditor>().single().editor
                assertEquals("println(a)", viewer.document.text)
                assertEquals(listOf("src/a.kt"), context.uncovered())
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
                context.addNotes(listOf(DiffNote(listOf("src/a.kt"), "Explains A.")))
                val showButton = descendants(tab).filterIsInstance<InplaceButton>()
                    .single { it.toolTipText == "Show diff on canvas" }
                showButton.doClick()
                assertEquals(listOf(fileA), tab.state.diffs)
                val diffViewer = descendants(tab).filterIsInstance<DiffFileEditor>().single()
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
            val notes = (0..19).map { index -> DiffNote(
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
                context.addNotes(listOf(DiffNote(listOf("src/a.kt"), "Still valid during refresh")))
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

    fun `test untracked inclusion and exclusion leave unrelated tabs usable during panel refresh`() {
        val panel = DiffReviewPanel(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Local changes should load", {
                descendants(panel).filterIsInstance<JLabel>().any { it.text.startsWith("0 files") }
            }, 10000)
            val state = DiffReviewPanel::class.java.getDeclaredField("state").apply { isAccessible = true }.get(panel) as DiffReviewState
            state.update(ReviewSnapshot(listOf(file("src/a.kt")), listOf(UntrackedFile("new.kt", "/project/new.kt"))))
            DiffReviewPanel::class.java.getDeclaredMethod("renderFiles").apply { isAccessible = true }.invoke(panel)
            val tree = descendants(panel).filterIsInstance<JTree>().single()
            val root = tree.model.root as DefaultMutableTreeNode
            val node = root.depthFirstEnumeration().toList().filterIsInstance<DefaultMutableTreeNode>().single { it.userObject is ReviewFile }
            tree.selectionPath = TreePath(node.path)
            descendants(panel).filterIsInstance<JButton>().single { it.text == "New tab from selection" }.doClick()
            val tabs = descendants(panel).filterIsInstance<JBTabs>().single()
            val scoped = tabs.selectedInfo!!.component as DiffReviewTab
            assertEquals(setOf("src/a.kt"), scoped.state.scopePaths)
            val checkbox = descendants(panel).filterIsInstance<javax.swing.JCheckBox>().single { it.text == "new.kt" }
            repeat(2) {
                checkbox.doClick()
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
            }, 10000)
            val buttons = descendants(panel).filterIsInstance<JButton>()
            assertFalse(buttons.single { it.text == "Description" }.isEnabled)
            assertTrue(buttons.any { it.text == "Send" })
            assertTrue(buttons.any { it.text == "LLM settings" })
            assertFalse(buttons.any { it.text == "Open dashboard" })
            assertTrue(descendants(panel).filterIsInstance<JLabel>().any { it.text == "Untracked · include in review" })
        } finally { Disposer.dispose(panel) }
    }

    fun `test full height global files sit beside tabs with independent drafts notes and chat resets`() {
        val panel = DiffReviewPanel(project)
        JRootPane().contentPane.add(panel)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Local changes should load", {
                descendants(panel).filterIsInstance<JLabel>().any { it.text.startsWith("0 files") }
            }, 10000)
            val split = (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER) as JSplitPane
            assertEquals(JSplitPane.HORIZONTAL_SPLIT, split.orientation)
            assertTrue(descendants(split.leftComponent as Container).any { it is JTree })
            assertFalse(descendants(split.leftComponent as Container).any { it is ChatPanel })
            val tabs = descendants(split.rightComponent as Container).filterIsInstance<JBTabs>().single()
            assertFalse(tabs.presentation.isHideTabs)
            assertEquals("All changes", tabs.selectedInfo!!.text)
            assertVisibleHeader(panel, tabs, tabs.selectedInfo!!)
            val first = tabs.selectedInfo!!.component as DiffReviewTab
            val firstChat = descendants(first).filterIsInstance<ChatPanel>().single()
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
            val secondChat = descendants(second).filterIsInstance<ChatPanel>().single()
            assertEquals("", secondChat.input.text)
            secondChat.input.text = "Second topic draft"
            second.update(snapshot)
            second.state.addNote(second.state.revision, listOf("src/a.kt"), "Different note")
            PlatformTestUtil.waitForCallback(tabs.select(tabs.tabs.single { it.component === first }, false))
            assertEquals("First topic draft", firstChat.input.text)
            assertEquals("First note", first.state.notes.single().text)
            descendants(first).filterIsInstance<JButton>().single { it.text == "New chat" }.doClick()
            descendants(first).filterIsInstance<JButton>().single { it.text == "Clear canvas" }.doClick()
            assertTrue(first.session.messages.isEmpty())
            assertTrue(first.state.notes.isEmpty())
            assertFalse(second.session.messages.isEmpty())
            assertEquals("Different note", second.state.notes.single().text)
            PlatformTestUtil.waitForCallback(tabs.select(tabs.tabs.single { it.component === second }, false))
            assertEquals("Second topic draft", secondChat.input.text)

            // Folder selection creates a fixed scope; subsequent tree selections do not change it.
            val tree = descendants(panel).filterIsInstance<JTree>().single()
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
                rejected { context.addNotes(listOf(DiffNote(listOf("src/a.kt", "src/b.kt"), "Out of scope"))) }
                context.addNotes(listOf(DiffNote(listOf("src/a.kt"), "Scoped note")))
                assertTrue(other.state.notes.isEmpty())
                Disposer.dispose(tab)
                rejected { context.state(0) }
                rejected { context.read(listOf("src/a.kt"), 0) }
                rejected { context.addNotes(listOf(DiffNote(listOf("src/a.kt"), "Closed tab"))) }
                otherContext.addNotes(listOf(DiffNote(listOf("src/b.kt"), "Still open")))
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
                assertEquals(big.substring(6000), rest["patch"].asString)
                assertFalse(rest.has("next_offset"))

                val added = context.addNotes(listOf(DiffNote(listOf("src/a.kt", "src/b.kt"), "Changes both.")))
                assertTrue(added, added.contains("1 files still have no note: src/c.kt"))
                assertEquals(listOf("src/c.kt"), context.uncovered())
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
        return buttons.single().also { assertEquals("Close tab", it.toolTipText) }
    }

    private fun file(path: String) = ReviewFile(path, "/project/$path", null, "modified", "-old\n+new\n", "1")

    private fun runWithEvents(action: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var failure: Throwable? = null
        val job = scope.launch { try { action() } catch (e: Throwable) { failure = e } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Review tools should finish", { job.isCompleted }, 5000)
            failure?.let { throw it }
        } finally { scope.cancel() }
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Expected a rejected tool call") } catch (_: ToolInputException) { }
    }

    private fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
