package dev.insight.diff

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import dev.insight.graph.FlowStep
import dev.insight.graph.buildFlowChart
import dev.insight.graph.children
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class DiffFlowChartSessionTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() = IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    override fun setUp() {
        super.setUp()
        PsiTestUtil.addSourceRoot(module, myFixture.tempDirFixture.findOrCreateDir(""))
    }

    private fun <T> background(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Diff flowchart work should finish", { task.isCompleted }, 60)
            return runBlocking { task.await() }.getOrThrow()
        } finally { task.cancel() }
    }

    private fun file(path: String, before: String?, after: String): ReviewFile {
        val psi = myFixture.addFileToProject(path, after)
        return ReviewFile(path, psi.virtualFile.path, null, if (before == null) "added" else "modified", "", path, before, psi.text)
    }

    fun testChartsChangedFunctionsAcrossRelatedFilesWithUnchangedCalleesAsContext() {
        myFixture.addFileToProject("Store.java", "class Store { static void save() {} }")
        val root = file("Root.java",
            "class Root {\n  static void run() {}\n  static void idle() {}\n}\n",
            "class Root {\n  static void run() { Helper.help(); }\n  static void idle() {}\n}\n")
        val helper = file("Helper.java",
            "class Helper {\n  static void help() {}\n}\n",
            "class Helper {\n  static void help() { Store.save(); }\n  static void added() {}\n}\n")
        val unrelated = file("Unrelated.java", null, "class Unrelated { void go() {} }")
        val prepared = background { prepareDiffFlowChart(project, ReviewSnapshot(listOf(root, helper, unrelated), emptyList()), root.path, null) }
        val chart = prepared.chart
        fun name(id: Int) = chart.graph.nodes[id].name.substringBefore('(')

        assertEquals(listOf("run", "help", "added"), chart.changed.map(::name))
        assertEquals(setOf(root.absolutePath, helper.absolutePath), prepared.stamps.keys)
        assertEquals(emptyList<String>(), prepared.unavailable)
        val source = chart.source()
        val built = buildFlowChart(chart.board(source, compact = false), source)
        assertEquals(listOf("run", "added"), built.lanes.map { name(it.root.node) })
        val run = built.lanes.first().root
        val help = run.items.filterIsInstance<FlowStep>().single()
        assertEquals("help", name(help.node))
        assertEquals(listOf("save"), help.items.filterIsInstance<FlowStep>().map { name(it.node) })
        assertTrue(chart.changes.getValue(help.node).label.startsWith("Δ +1"))
        assertTrue(chart.changes.getValue(built.lanes[1].root.node).new)
        assertNull(chart.changes[help.items.filterIsInstance<FlowStep>().single().node])
        assertTrue(built.steps.none { name(it.node) == "idle" || name(it.node) == "go" })
        assertTrue(children(run).isNotEmpty())

        val editor = DiffFlowChartEditorProvider().createEditor(project, prepared)
        Disposer.register(testRootDisposable, editor)
        assertEquals("Flowchart: Root.java", prepared.name)
    }

    fun testRejectsSourceThatDiffersFromTheReviewedAfterSide() {
        val root = file("Root.java", "class Root {}", "class Root { void run() {} }").copy(after = "class Root { void other() {} }")
        try {
            background { prepareDiffFlowChart(project, ReviewSnapshot(listOf(root), emptyList()), root.path, null) }
            fail("Must reject unavailable reviewed source")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("matching the reviewed after-side"))
        }
    }
}
