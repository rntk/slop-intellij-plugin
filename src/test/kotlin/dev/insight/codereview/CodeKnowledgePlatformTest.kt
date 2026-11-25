package dev.insight.codereview

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.JBList
import com.intellij.ui.treeStructure.Tree
import dev.insight.knowledge.Claim
import dev.insight.knowledge.ClaimLevel
import dev.insight.knowledge.ClaimOrigin
import dev.insight.knowledge.KnowledgeOp
import dev.insight.knowledge.KnowledgeService
import dev.insight.knowledge.ui.KnowledgeColors
import dev.insight.llm.Completion
import dev.insight.review.CanvasTabKey
import dev.insight.review.ui.NoteCanvas
import dev.insight.review.ui.chooseView
import dev.insight.review.ui.shownView
import dev.insight.review.ui.tileStats
import kotlinx.coroutines.runBlocking
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JEditorPane
import javax.swing.JLabel

/** The Knowledge view of the Code Review hub: tile, page, runs over a temp flows doc and the ledger it writes. */
class CodeKnowledgePlatformTest : FileEditorManagerTestCase() {
    private lateinit var knowledgeDir: Path
    private val service get() = project.service<KnowledgeService>()

    private val widget = """
        package demo;

        public class Widget {
            public int compute(int x) { return x * 2 + 1; }
        }
    """.trimIndent() + "\n"

    private val flowDoc = """
        # Widget flow

        The widget doubles its input and adds one, see [Widget.java](../src/demo/Widget.java).

        It never reads from the disk.
    """.trimIndent() + "\n"

    override fun setUp() {
        super.setUp()
        knowledgeDir = Files.createTempDirectory("knowledge-test")
        service.useDirectory(knowledgeDir.resolve("knowledge"))
        service.decisions = { null }
    }

    override fun tearDown() {
        try {
            service.stop()
            service.useDirectory(null)
            knowledgeDir.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun openTab(): CodeReviewTab {
        myFixture.addFileToProject("src/demo/Widget.java", widget)
        myFixture.addFileToProject("flows/widget.md", flowDoc)
        val tab = CodeReviewTab(project, null, { emptyList() }, { _, _ -> }, complete = { Completion("Nothing to add.") })
        tab.update(codeSnapshot(codeFile("src/demo/Widget.java", widget)))
        return tab
    }

    private fun descendants(container: Container): List<Component> = (if (container is NoteCanvas)
        container.pageKeys.map(container::editorPage) else container.components.toList()).flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    private inline fun <reified T : Component> NoteCanvas.named(name: String): T =
        descendants(this).filterIsInstance<T>().single { it.name == name }

    private fun wait(message: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching(message, condition, 30)

    fun `test the Knowledge tile opens a page that shows the counts of the project's knowledge`() {
        val tab = openTab()
        try {
            assertTrue(tileStats(tab, "view-knowledge").isEmpty())
            chooseView(tab, "Knowledge")
            assertEquals(CanvasTabKey.Knowledge, tab.canvas.selectedKey)
            assertEquals("Knowledge", shownView(tab))
            assertTrue(tab.state.knowledgeVisible)
            assertEquals("Knowledge", tab.canvas.pageTitle(CanvasTabKey.Knowledge))
            wait("The ledger should load") { service.peek() != null }
            val counts = tab.canvas.named<JLabel>("knowledge-counts")
            assertEquals("Knowledge · 0 live · 0 suspect · 0 retired · 0 superseded", counts.text)
            assertTrue(tab.canvas.named<JButton>("knowledge-check").isEnabled)
            assertTrue(tab.canvas.named<JButton>("knowledge-distill").isEnabled)
            assertFalse(tab.canvas.named<JButton>("knowledge-stop").isEnabled)
            assertFalse("Nothing to render yet", tab.canvas.named<JButton>("knowledge-render").isEnabled)
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test checking docs runs offline of decisions, writes the ledger and shows counts and a linked report`() {
        val tab = openTab()
        try {
            chooseView(tab, "Knowledge")
            wait("The ledger should load") { service.peek() != null }
            tab.canvas.named<JButton>("knowledge-check").doClick()
            wait("The drift check should finish") { tab.canvas.named<JLabel>("knowledge-status").text.startsWith("Checked") }

            val state = service.peek()!!.snapshot()
            val claims = state.claims.values.filter { it.origin == ClaimOrigin.HUMAN }
            assertEquals(2, claims.size)
            assertTrue(claims.all { it.source == "flows/widget.md" && it.level == ClaimLevel.FLOW })
            assertTrue("The ledger is written under the knowledge directory", Files.size(knowledgeDir.resolve("knowledge/ledger.jsonl")) > 0)

            val counts = tab.canvas.named<JLabel>("knowledge-counts").text
            assertTrue(counts, counts.startsWith("Knowledge · 2 live"))
            assertEquals(listOf("2 live"), tileStats(tab, "view-knowledge"))
            assertFalse(tab.canvas.named<JButton>("knowledge-stop").isEnabled)
            assertTrue(tab.canvas.named<JButton>("knowledge-check").isEnabled)

            // The drift report is Markdown in the linked report pane, and the page switched to it.
            val report = tab.canvas.named<JEditorPane>("knowledge-report") as dev.insight.review.ui.LinkedTextPane
            wait("The report should render") { report.renderedLinks.isNotEmpty() }
            assertTrue(report.markdown, report.markdown.startsWith("# Doc drift report"))
            assertTrue(report.markdown, report.markdown.contains("flows/widget.md:5"))
            assertTrue(report.renderedLinks.any { link -> link.candidates.any { it.endsWith("flows/widget.md") } })
            assertEquals(2, tab.canvas.named<com.intellij.ui.components.JBTabbedPane>("knowledge-tabs").selectedIndex)

            // The activity of the run was streamed.
            val activity = tab.canvas.named<javax.swing.JTextArea>("knowledge-activity").text
            assertTrue(activity, activity.contains("flows/widget.md"))
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test rejected ledger entries are highlighted with their reason`() {
        val tab = openTab()
        try {
            chooseView(tab, "Knowledge")
            wait("The ledger should load") { service.peek() != null }
            val base = service.peek()!!
            val claim = Claim("c-1", "The widget doubles its input.", ClaimLevel.FILE, ClaimOrigin.GENERATED, "src/demo", emptyList())
            runBlocking {
                base.submit(KnowledgeOp.Add(claim), "worker:src/demo")
                // An unknown source cannot be rewritten: the ratchet records the rejection and applies nothing.
                base.submit(KnowledgeOp.Rewrite("c-missing", claim.copy(id = "c-2", text = "It doubles.")), "worker:src/demo")
            }
            @Suppress("UNCHECKED_CAST") val ledger = tab.canvas.named<JBList<*>>("knowledge-ledger") as JBList<Any?>
            wait("The ledger list should show both entries") { ledger.model.size == 2 }
            // Newest first: the rejected entry, then the accepted one.
            fun row(index: Int) = ledger.cellRenderer.getListCellRendererComponent(ledger, ledger.model.getElementAt(index), index, false, false)
            assertEquals(KnowledgeColors.REJECTED_BG, row(0).background)
            assertTrue((row(0) as com.intellij.ui.SimpleColoredComponent).getCharSequence(false).toString().contains("Unknown claim id 'c-missing'"))
            assertTrue(row(1).background != KnowledgeColors.REJECTED_BG)
            assertTrue(tab.canvas.named<JLabel>("knowledge-ledger-summary").text, tab.canvas.named<JLabel>("knowledge-ledger-summary").text.contains("2 entries · 1 rejected"))
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test the user retires and restores a claim from the page`() {
        val tab = openTab()
        try {
            chooseView(tab, "Knowledge")
            wait("The ledger should load") { service.peek() != null }
            val claim = Claim("c-1", "The widget doubles its input.", ClaimLevel.FILE, ClaimOrigin.HUMAN, "src/demo", emptyList())
            runBlocking { service.peek()!!.submit(KnowledgeOp.Add(claim), "user") }
            val tree = tab.canvas.named<Tree>("knowledge-claims")
            wait("The claim should be listed") { tree.rowCount == 2 }
            tree.setSelectionRow(1)
            val retire = tab.canvas.named<JButton>("knowledge-retire")
            val restore = tab.canvas.named<JButton>("knowledge-restore")
            assertTrue(retire.isEnabled)
            assertFalse(restore.isEnabled)
            retire.doClick()
            wait("The claim should be retired") { service.peek()!!.snapshot()["c-1"]?.isActive == false }
            assertEquals("Retired c-1", wait2 { tab.canvas.named<JLabel>("knowledge-status").text.takeIf { it.startsWith("Retired") } })
            wait("The page should count it") { tab.canvas.named<JLabel>("knowledge-counts").text.contains("0 live · 0 suspect · 1 retired") }
            // Retired claims leave the default list; choose them to restore.
            val filter = tab.canvas.named<JComboBox<*>>("knowledge-claim-filter")
            filter.selectedIndex = 2
            wait("The retired claim should be listed") { tree.rowCount == 2 }
            tree.setSelectionRow(1)
            assertTrue(restore.isEnabled)
            assertFalse(retire.isEnabled)
            restore.doClick()
            wait("The claim should be live again") { service.peek()!!.snapshot()["c-1"]?.isActive == true }
            assertEquals(listOf("1 live"), tileStats(tab, "view-knowledge"))
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test rendering a doc shows a draft and a diff and never writes the file`() {
        val tab = openTab()
        try {
            chooseView(tab, "Knowledge")
            wait("The ledger should load") { service.peek() != null }
            tab.canvas.named<JButton>("knowledge-check").doClick()
            wait("The drift check should finish") { tab.canvas.named<JLabel>("knowledge-status").text.startsWith("Checked") }
            val render = tab.canvas.named<JButton>("knowledge-render")
            wait("Rendering should be possible") { render.isEnabled }
            render.doClick()
            wait("The draft should render") { tab.canvas.named<JLabel>("knowledge-status").text.startsWith("Rendered a draft") }

            val choice = tab.canvas.named<JComboBox<*>>("knowledge-report-select")
            assertEquals(listOf("Drift report", "Draft flows/widget.md", "Diff flows/widget.md"), (0 until choice.itemCount).map { choice.getItemAt(it).toString() })
            assertTrue(tab.canvas.named<JButton>("knowledge-copy-draft").isEnabled)
            val report = tab.canvas.named<JEditorPane>("knowledge-report") as dev.insight.review.ui.LinkedTextPane
            assertTrue(report.markdown, report.markdown.contains("Nothing was written to disk"))
            choice.selectedIndex = 2
            assertTrue(report.markdown, report.markdown.contains("```diff"))
            assertEquals("The flows doc is only proposed, never written", flowDoc, String(
                myFixture.findFileInTempDir("flows/widget.md").contentsToByteArray()))
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test distilling the scope runs the workflow with the injected completion`() {
        val tab = openTab()
        try {
            chooseView(tab, "Knowledge")
            wait("The ledger should load") { service.peek() != null }
            tab.canvas.named<JButton>("knowledge-distill").doClick()
            wait("The distillation should finish") {
                tab.canvas.named<JLabel>("knowledge-status").text.let { it.startsWith("Distilled") || it.contains("failed") }
            }
            val status = tab.canvas.named<JLabel>("knowledge-status").text
            assertTrue(status, status.startsWith("Distilled 1 of 1 areas"))
            assertTrue(tab.canvas.named<JButton>("knowledge-distill").isEnabled)
            assertTrue(tab.canvas.named<javax.swing.JTextArea>("knowledge-activity").text.contains("src/demo"))
        } finally {
            Disposer.dispose(tab)
        }
    }

    fun `test a second run does not start while one holds the slot`() {
        val tab = openTab()
        try {
            chooseView(tab, "Knowledge")
            wait("The ledger should load") { service.peek() != null }
            val busy = kotlinx.coroutines.Job()
            assertTrue(service.tryBegin(busy))
            try {
                tab.openKnowledge()
                assertFalse("A run holds the slot", tab.canvas.named<JButton>("knowledge-check").isEnabled)
                assertTrue(tab.canvas.named<JButton>("knowledge-stop").isEnabled)
            } finally {
                busy.cancel()
                service.end(busy)
            }
        } finally {
            Disposer.dispose(tab)
        }
    }

    private fun <T : Any> wait2(find: () -> T?): T {
        var found: T? = null
        wait("Waiting for a value") { find().also { found = it } != null }
        return found!!
    }
}
