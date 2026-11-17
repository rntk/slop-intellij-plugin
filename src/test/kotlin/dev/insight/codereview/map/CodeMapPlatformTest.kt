package dev.insight.codereview.map

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import dev.insight.codereview.CodeCanvasCards
import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ui.CanvasState
import dev.insight.review.ui.NoteCanvas
import java.awt.Component
import java.awt.Container
import javax.swing.JButton

/** Units and references read from real Kotlin PSI, and the card that shows the map. */
class CodeMapPlatformTest : BasePlatformTestCase() {
    private fun file(path: String, text: String): CodeFile {
        val live = myFixture.addFileToProject(path, text)
        return CodeFile(path, live.virtualFile.path, text)
    }

    private fun files() = listOf(
        file("app/Main.kt", "fun main() {\n    Service().run()\n}\n"),
        file("app/Service.kt", "class Service {\n    fun run(): Int = helper()\n    private fun helper() = Store().load()\n}\n"),
        file("app/Store.kt", "class Store {\n    fun load(): Int = 1\n}\n"),
        file("app/Unused.kt", "class Unused {\n    fun idle() = 0\n}\n"),
        file("app/test/ServiceTest.kt", "class ServiceTest {\n    fun check() { Service().run() }\n}\n"),
        file("docs/flow.md", "Start at Main.kt, then read Service.kt.\n"),
    )

    private fun <T> read(action: () -> T): T = ApplicationManager.getApplication().runReadAction<T> { action() }

    fun testClassesTopLevelCodeAndReferencesAreReadFromPsi() {
        val files = files()
        val facts = read { collectCodeMapFacts(project, files) }
        assertEquals(listOf("app/Main.kt#<top-level>", "app/Service.kt#Service", "app/Store.kt#Store", "app/Unused.kt#Unused",
            "app/test/ServiceTest.kt#ServiceTest", "docs/flow.md"), facts.units.map { it.id })
        val service = facts.units.single { it.id == "app/Service.kt#Service" }
        assertEquals(UnitKind.CLASS, service.kind)
        assertEquals(1..4, service.lines)
        assertEquals(listOf("Service.run" to 2..2, "Service.helper" to 3..3), service.members.map { it.name to it.lines })
        val main = facts.units.first()
        assertEquals(UnitKind.TOP_LEVEL, main.kind)
        assertEquals(listOf("main"), main.members.map { it.name })

        val pairs = facts.references.map { it.from to it.to }.toSet()
        assertTrue(pairs.toString(), "app/Main.kt#<top-level>" to "app/Service.kt#Service" in pairs)
        assertTrue(pairs.toString(), "app/Service.kt#Service" to "app/Store.kt#Store" in pairs)
        assertTrue(pairs.toString(), "app/test/ServiceTest.kt#ServiceTest" to "app/Service.kt#Service" in pairs)
        assertTrue(pairs.toString(), "docs/flow.md" to "app/Service.kt#Service" in pairs)
        val call = facts.references.first { it.from == "app/Main.kt#<top-level>" && it.toMember == "app/Service.kt#Service.run" }
        assertEquals("app/Main.kt#main", call.fromMember)
        assertTrue(facts.references.any { it.from == "app/Service.kt#Service" && it.fromMember == "app/Service.kt#Service.helper" && it.to == "app/Store.kt#Store" })
        assertEquals(6, facts.analyzed)
    }

    fun testTheMapReadsAFlowFromItsRootThroughWhatItUsesToItsTestAndDocs() {
        val files = files()
        val map = read { codeMap(project, files) }
        val flow = map.flowOf("app/Main.kt#<top-level>")!!
        assertEquals(listOf("app/Main.kt#<top-level>", "app/Service.kt#Service", "app/Store.kt#Store", "app/test/ServiceTest.kt#ServiceTest", "docs/flow.md"),
            flow.units)
        assertEquals(listOf(FlowRole.ROOT, FlowRole.FOLLOWS, FlowRole.FOLLOWS, FlowRole.TEST, FlowRole.DOCUMENTATION), flow.steps.map { it.role })
        assertTrue(flow.entry)
        assertTrue(map.flowOf("app/Unused.kt#Unused")!!.standalone)
        assertEquals(1, read { collectCodeMapFacts(project, files, maxFiles = 1) }.analyzed)
    }

    fun testTheCardListsTheFlowsAndOpensAUnitsMembers() {
        val files = files()
        val map = read { codeMap(project, files) }
        var generated = 0
        val opened = mutableListOf<Pair<String, Int?>>()
        val cards = CodeCanvasCards({}, codeMapActions = CodeMapActions({ generated++ }, {}, { path, line -> opened += path to line }))
        val canvas = NoteCanvas({}, {}, extension = cards)
        try {
            cards.codeMap = CodeMapCard(null, ReviewSummaries(buildCodeMapTree(map, files)), running = false, canRun = true)
            canvas.showNotes(CanvasState())
            assertEquals("Code map", canvas.tabs.getTitleAt(0))
            val button = named<JButton>(canvas, "code-map-action").single()
            assertEquals("Generate", button.text)
            button.doClick()
            assertEquals(1, generated)

            assertTrue(cards.updateCodeMap(CodeMapCard(map, ReviewSummaries(buildCodeMapTree(map, files)), running = false, canRun = true)))
            assertEquals("Fill summaries", named<JButton>(canvas, "code-map-action").single().text)
            val list = named<JBList<*>>(canvas, "code-map-list").single()
            assertEquals(2, list.model.size)
            assertEquals(map.flowOf("app/Main.kt#<top-level>")!!.id, cards.codeMapSelection)
            named<ActionLink>(canvas, "code-map-open").first().doClick()
            assertEquals("app/Main.kt" to null, opened.single())
        } finally {
            com.intellij.openapi.util.Disposer.dispose(canvas)
        }
    }

    private inline fun <reified T : Component> named(root: Component, name: String): List<T> =
        descendants(root).filterIsInstance<T>().filter { it.name == name }

    private fun descendants(root: Component): List<Component> =
        listOf(root) + ((root as? Container)?.components?.flatMap(::descendants) ?: emptyList())
}
