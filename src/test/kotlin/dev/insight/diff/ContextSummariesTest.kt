package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JMenuItem
import javax.swing.text.JTextComponent
import kotlinx.coroutines.Job

class ContextSummariesTest : BasePlatformTestCase() {
    private val files = listOf("app/a.kt", "app/b.kt", "docs/c.kt", "docs/d.kt").map {
        ReviewFile(it, "/repo/$it", null, "modified", "-old\n+new\n", "1")
    }
    private val tree = buildSummaryTree(files)
    private val app = tree.nodes.single { it.location == "app/" }
    private val original = ChangeSummaries(tree).summarize(tree.nodes.associate { it.id to "Original ${it.location}" })

    private class ContextLauncher : ContextSummaryLauncher {
        lateinit var start: ChangeSummaries
        lateinit var request: ContextSummaryRequest
        lateinit var updated: (ChangeSummaries) -> Unit
        lateinit var done: (Result<ChangeSummaryRun>) -> Unit
        lateinit var job: Job
        override fun launch(start: ChangeSummaries, request: ContextSummaryRequest,
                            updated: (ChangeSummaries) -> Unit, done: (Result<ChangeSummaryRun>) -> Unit): Job {
            this.start = start; this.request = request; this.updated = updated; this.done = done
            return Job().also { job = it }
        }
        fun finish() {
            val rewritten = start.summarize(request.nodeIds.associateWith { "Context ${start.tree[it].location}" }, reuseSingleChildren = false)
            updated(rewritten)
            done(Result.success(ChangeSummaryRun(rewritten, 2, null)))
        }
    }

    private fun canvas(launcher: ContextLauncher): NoteCanvas {
        val tracer = object : SummaryTraceLauncher {
            override fun prescore(summaries: ChangeSummaries, words: List<Pair<Int, Int>>,
                                  scored: (SummaryContext) -> Unit, done: (Result<Unit>) -> Unit): Job = Job()
            override fun search(summaries: ChangeSummaries, id: Int, context: SummaryContext, done: (Result<SummaryTrace>) -> Unit): Job {
                done(Result.success(SummaryTrace(context.identity, context.summary, context.word, context.context, context.query,
                    listOf(SummaryMatch(app.id, app.identity, 0.9, 1)), 2)))
                return Job()
            }
            override fun searchLines(summaries: ChangeSummaries, id: Int, context: SummaryContext, done: (Result<LineTrace>) -> Unit): Job = Job()
        }
        return NoteCanvas({}, {}, traceSummaries = tracer, contextSummaries = launcher).apply {
            showNotes(emptyList(), summaries = SummariesCard(original, running = false, canRun = true))
        }
    }

    private fun descendants(root: Container): List<Component> = root.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }
    private fun named(canvas: NoteCanvas, tab: Int, name: String) =
        descendants(canvas.tabs.getComponentAt(tab) as Container).single { it.name == name }
    private fun button(canvas: NoteCanvas, tab: Int, name: String) = named(canvas, tab, name) as JButton
    private fun versions(canvas: NoteCanvas, tab: Int) = named(canvas, tab, "summaries-version") as JComboBox<*>
    private fun summary(canvas: NoteCanvas, tab: Int, identity: String): JTextComponent =
        descendants(named(canvas, tab, "summary:$identity") as Container).filterIsInstance<JTextComponent>().first()

    private fun openSearch(canvas: NoteCanvas) {
        val root = summary(canvas, 0, tree.root.identity)
        root.select(0, "Original".length)
        selectionMenu(root)!!.components.filterIsInstance<JMenuItem>().single { it.name == "search-tab" }.doClick()
        assertEquals(2, canvas.tabs.tabCount)
    }

    fun testEntryActionsSelectScopeAndPassInstructionsInEveryLayout() {
        for (mode in ChangeSummariesMode.entries) {
            val launcher = ContextLauncher()
            val canvas = canvas(launcher)
            try {
                canvas.summariesView.mode = mode
                if (mode == ChangeSummariesMode.EXPLORER) canvas.summariesView.browse(tree.root.identity, tree)
                canvas.showNotes(emptyList(), summaries = SummariesCard(original, false, true))
                canvas.requestResummaryInstructions = { node, recursive, destination ->
                    assertEquals(ResummaryDestination.VERSION, destination)
                    assertEquals(app.identity, node.identity)
                    assertFalse(recursive)
                    "Focus on failures\nPreserve qualifications"
                }
                val name = if (mode == ChangeSummariesMode.SPLIT) "summary-tree:${app.identity}"
                    else if (mode == ChangeSummariesMode.HIERARCHY || mode == ChangeSummariesMode.DOCUMENT) "node:${app.identity}"
                    else "summary:${app.identity}"
                val entry = named(canvas, 0, name) as javax.swing.JComponent
                entry.componentPopupMenu.components.filterIsInstance<JMenuItem>().single { it.text == "Resummarize" }.doClick()
                assertEquals(setOf(app.id), launcher.request.nodeIds)
                assertEquals("Focus on failures\nPreserve qualifications", launcher.request.instructions)
                assertTrue(launcher.request.regenerate)
                assertEquals(original.text(app.children.first()), launcher.start.text(app.children.first()))
                launcher.finish()
                assertEquals(2, versions(canvas, 0).itemCount)
            } finally { canvas.dispose() }
        }
    }

    fun testResummarizeAllIncludesFoldedDescendantsAndCanBeCancelled() {
        val launcher = ContextLauncher()
        val canvas = canvas(launcher)
        try {
            canvas.summariesView.toggle(app.identity)
            canvas.showNotes(emptyList(), summaries = SummariesCard(original, false, true))
            canvas.requestResummaryInstructions = { _, _, _ -> null }
            val entry = named(canvas, 0, "summary:${app.identity}") as javax.swing.JComponent
            fun all() = entry.componentPopupMenu.components.filterIsInstance<JMenuItem>().single { it.text == "Resummarize all" }.doClick()
            all()
            assertEquals(1, versions(canvas, 0).itemCount)
            canvas.requestResummaryInstructions = { node, recursive, destination ->
                assertEquals(ResummaryDestination.VERSION, destination)
                assertEquals(app.identity, node.identity)
                assertTrue(recursive)
                ""
            }
            all()
            assertEquals(setOf(app.id) + app.children, launcher.request.nodeIds)
            assertEquals(original.text(tree.root.id), launcher.start.text(tree.root.id))
            launcher.request.nodeIds.forEach { assertNull(launcher.start.text(it)) }
        } finally { canvas.dispose() }
    }

    fun testTopicHierarchyEntriesFillAndResummarizeOneTopic() {
        val topics = ChangeTopics(files, mapOf("app/a.kt" to "Storage > Wallet", "app/b.kt" to "Storage > Wallet",
            "docs/c.kt" to "UI", "docs/d.kt" to "UI"))
        val topicTree = buildTopicSummaryTree(topics)
        val storage = topicTree.nodes.single { it.identity == "topic:storage" }
        val wallet = topicTree.nodes.single { it.identity == "topic:storage > wallet" }
        val launcher = ContextLauncher()
        val saved = mutableListOf<Pair<ChangeSummaries, Set<Int>>>()
        val canvas = NoteCanvas({}, {}, contextSummaries = launcher, saveTopicSummaries = { summaries, ids -> saved += summaries to ids })
        try {
            canvas.topicsView.mode = ChangeTopicsMode.HIERARCHY
            canvas.showNotes(emptyList(), topics = TopicsCard(topics, ChangeSummaries(tree), canRun = true))
            fun item(entry: String, label: String) = (named(canvas, 0, entry) as javax.swing.JComponent).componentPopupMenu
                .components.filterIsInstance<JMenuItem>().single { it.text == label }
            // File summaries are shared with the Summaries card, so files only fill.
            assertEquals(listOf("Fill summaries"), (named(canvas, 0, "summary:app/a.kt") as javax.swing.JComponent).componentPopupMenu
                .components.filterIsInstance<JMenuItem>().map { it.text })
            item("topic:1:storage > wallet", "Fill summaries").doClick()
            assertEquals(setOf(wallet.id) + wallet.children, launcher.request.nodeIds)
            assertFalse(launcher.request.regenerate)
            assertTrue(canvas.topicSummariesRunning)
            assertFalse(item("topic:0:ui", "Fill summaries").isEnabled)
            launcher.finish()
            assertFalse(canvas.topicSummariesRunning)
            assertEquals(launcher.request.nodeIds, saved.last().second)
            assertEquals("Context Storage > Wallet/", saved.last().first.text(wallet.id))
            assertNull(saved.last().first.text(storage.id))

            val complete = ChangeSummaries(topicTree).summarize(topicTree.nodes.associate { it.id to "Original ${it.identity}" })
            canvas.updateTopics(TopicsCard(topics, ChangeSummaries(tree), canRun = true, topicSummaries = complete))
            assertFalse(item("topic:0:storage", "Fill summaries").isEnabled)
            canvas.requestResummaryInstructions = { node, recursive, destination ->
                assertEquals(ResummaryDestination.TOPICS, destination)
                assertEquals(storage.identity, node.identity)
                assertTrue(recursive)
                "Focus on balances"
            }
            item("topic:0:storage", "Resummarize all").doClick()
            assertEquals(setOf(storage.id, wallet.id), launcher.request.nodeIds)
            assertEquals("Focus on balances", launcher.request.instructions)
            assertTrue(launcher.request.regenerate)
            assertNull(launcher.start.text(wallet.id))
            assertEquals(complete.text(wallet.children.first()), launcher.start.text(wallet.children.first()))
            assertEquals("Resummarize all · Storage…", (named(canvas, 0, "topic-summary-status") as javax.swing.JLabel).text)
        } finally { canvas.dispose() }
    }

    fun testTopicAndVersionRunsExcludeEachOtherIncludingStaleMenus() {
        val launcher = ContextLauncher()
        val canvas = NoteCanvas({}, {}, contextSummaries = launcher, saveTopicSummaries = { _, _ -> })
        val topics = ChangeTopics(files, files.associate { it.path to "Storage" })
        try {
            canvas.topicsView.mode = ChangeTopicsMode.HIERARCHY
            canvas.showNotes(emptyList(), summaries = SummariesCard(original, false, true),
                topics = TopicsCard(topics, ChangeSummaries(tree), canRun = true))
            fun item(name: String, label: String): JMenuItem {
                val entry = descendants(canvas).single { it.name == name } as javax.swing.JComponent
                return entry.componentPopupMenu.components.filterIsInstance<JMenuItem>().single { it.text == label }
            }
            canvas.requestResummaryInstructions = { _, _, _ -> "" }
            val staleFill = item("topic:0:storage", "Fill summaries")
            item("summary:${app.identity}", "Resummarize").doClick()
            val versionRequest = launcher.request
            assertFalse(item("topic:0:storage", "Fill summaries").isEnabled)
            staleFill.doClick()
            assertSame(versionRequest, launcher.request)
            assertFalse(canvas.topicSummariesRunning)
            launcher.finish()
            val staleVersion = item("summary:${app.identity}", "Resummarize")
            item("topic:0:storage", "Fill summaries").doClick()
            val topicRequest = launcher.request
            assertTrue(canvas.topicSummariesRunning)
            assertFalse(item("summary:${app.identity}", "Resummarize").isEnabled)
            staleVersion.doClick()
            assertSame(topicRequest, launcher.request)
            launcher.finish()
            assertTrue(item("summary:${app.identity}", "Resummarize").isEnabled)
        } finally { canvas.dispose() }
    }

    fun testLeavesHaveOnlyOneResummaryAction() {
        val actions = ChangeSummaryActions({}, {}, {}, {}, resummarize = { _, _ -> })
        for (node in tree.nodes) {
            val menu = javax.swing.JPopupMenu()
            actions.addResummaryItems(menu, node)
            assertEquals(if (node.children.isEmpty()) listOf("Resummarize") else listOf("Resummarize", "Resummarize all"),
                menu.components.filterIsInstance<JMenuItem>().map { it.text })
        }
    }

    fun testContextVersionsKeepOriginalSwitchPerTabAndDeleteAcrossTabs() {
        val launcher = ContextLauncher()
        val canvas = canvas(launcher)
        try {
            assertFalse(button(canvas, 0, "resummarize-with-context").isEnabled)
            openSearch(canvas)
            button(canvas, 1, "resummarize-with-context").doClick()
            assertEquals("Original", launcher.request.query)
            assertEquals(setOf("app/", "app/a.kt", "app/b.kt"), launcher.request.nodeIds.map { tree[it].location }.toSet())
            assertEquals(original.text(tree.root.id), launcher.start.text(tree.root.id))
            assertFalse(button(canvas, 1, "resummarize-with-context").isEnabled)
            launcher.finish()
            assertEquals("Original app/", summary(canvas, 0, app.identity).text)
            assertEquals("Context app/", summary(canvas, 1, app.identity).text)
            assertEquals(2, versions(canvas, 0).itemCount)
            assertEquals(0, versions(canvas, 0).selectedIndex)
            assertEquals(1, versions(canvas, 1).selectedIndex)
            versions(canvas, 1).selectedIndex = 0
            assertEquals("Original app/", summary(canvas, 1, app.identity).text)
            versions(canvas, 1).selectedIndex = 1
            versions(canvas, 0).selectedIndex = 1
            assertEquals("Context app/", summary(canvas, 0, app.identity).text)
            // Closing and reopening the summaries card keeps its versions in this review.
            canvas.showNotes(emptyList())
            canvas.showNotes(emptyList(), summaries = SummariesCard(original, false, true))
            assertEquals(2, versions(canvas, 0).itemCount)
            button(canvas, 0, "delete-summary-version").doClick()
            assertEquals(1, versions(canvas, 0).itemCount)
            assertEquals("Original app/", summary(canvas, 0, app.identity).text)
            tree.nodes.forEach { assertEquals("Original ${it.location}", original.text(it.id)) }
        } finally { canvas.dispose() }
    }

    fun testDeletingRunningVersionCancelsAndIgnoresLateResults() {
        val launcher = ContextLauncher()
        val canvas = canvas(launcher)
        try {
            openSearch(canvas)
            button(canvas, 1, "resummarize-with-context").doClick()
            versions(canvas, 0).selectedIndex = 1
            button(canvas, 1, "delete-summary-version").doClick()
            assertTrue(launcher.job.isCancelled)
            launcher.finish()
            assertEquals(1, versions(canvas, 0).itemCount)
            assertEquals(1, versions(canvas, 1).itemCount)
            assertEquals("Original app/", summary(canvas, 1, app.identity).text)
        } finally { canvas.dispose() }
    }

    fun testSourceChangesCancelRewriteAndLateResultsCannotReplaceNewSnapshot() {
        val launcher = ContextLauncher()
        val canvas = canvas(launcher)
        try {
            openSearch(canvas)
            button(canvas, 1, "resummarize-with-context").doClick()
            val nextTree = buildSummaryTree(files.map { if (it.path == "app/a.kt") it.copy(fingerprint = "2") else it })
            canvas.updateSummaries(SummariesCard(original.retainedIn(nextTree), false, true))
            assertTrue(launcher.job.isCancelled)
            assertTrue((named(canvas, 1, "summary-version-status") as javax.swing.JLabel).text.startsWith("Source changed"))
            launcher.finish()
            assertTrue((named(canvas, 1, "summary-version-status") as javax.swing.JLabel).text.startsWith("Source changed"))
            assertFalse(descendants(canvas).filterIsInstance<JTextComponent>().any { it.text == "Context app/" })
        } finally { canvas.dispose() }
    }

    fun testFailedRewriteKeepsOriginalAndAllowsAnotherVersion() {
        val launcher = ContextLauncher()
        val canvas = canvas(launcher)
        try {
            openSearch(canvas)
            button(canvas, 1, "resummarize-with-context").doClick()
            launcher.done(Result.failure(IllegalStateException("Unavailable")))
            assertEquals("Unavailable", (named(canvas, 1, "summary-version-status") as javax.swing.JLabel).text)
            assertEquals("Original app/", summary(canvas, 0, app.identity).text)
            assertTrue(button(canvas, 1, "resummarize-with-context").isEnabled)
            button(canvas, 1, "resummarize-with-context").doClick()
            launcher.finish()
            assertEquals(3, versions(canvas, 1).itemCount)
            assertEquals("Context app/", summary(canvas, 1, app.identity).text)
        } finally { canvas.dispose() }
    }
}
