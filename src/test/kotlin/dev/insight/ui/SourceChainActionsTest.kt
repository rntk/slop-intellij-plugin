package dev.insight.ui

import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.ActionLink
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.SchematicEditorProvider
import dev.insight.callgraph.SchematicFile
import dev.insight.callgraph.SchematicPanel
import dev.insight.callgraph.schematicCanvasTools
import dev.insight.canvas.*
import dev.insight.graph.SymbolKind
import dev.insight.llm.Completion
import dev.insight.llm.FinishReason
import java.awt.Component
import java.awt.Container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class SourceChainActionsTest : FileEditorManagerTestCase() {
    private val source = """
        class Flow {
            void entry() {
                helper();
            }
            void helper() {}
        }
    """.trimIndent()

    private fun descendants(component: Component): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap(::descendants)

    private fun <T> background(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Source chain should open", { task.isCompleted }, 30)
            return runBlocking { task.await() }.getOrThrow()
        } finally {
            task.cancel()
        }
    }

    fun testCanvasCardLinkOpensOnlyItsChainAtCallSitesAndKeepsTheViewAfterClosingTheCanvas() {
        val file = myFixture.configureByText("Flow.java", source).virtualFile
        val opened = background { openCanvasSessionFile(project, file, store = null) }!!
        val services = CanvasSessionServices(null, { { Completion("Summary", finishReason = FinishReason.COMPLETE) } }) { _, _ ->
            error("No chat is requested")
        }
        val panel = CanvasSessionPanel(project, opened, opened.session, services) {}
        val manager = FileEditorManager.getInstance(project)
        try {
            PlatformTestUtil.waitWithEventsDispatching("Canvas should load", { panel.ready }, 30)
            fun open(parentId: String?, name: String): CanvasCard {
                val link = panel.controller.source(parentId)!!.links.first { link ->
                    link.targets.any { panel.controller.node(it)?.name?.substringBefore('(') == name }
                }
                val target = link.targets.first { panel.controller.node(it)?.name?.substringBefore('(') == name }
                return background { panel.controller.openSymbol(parentId, link.range.first, link.range.last + 1, target) }
            }
            val entry = open(null, "entry")
            val helper = open(entry.id, "helper")
            open(null, "helper") // A sibling chain must stay out of the new tab.
            PlatformTestUtil.waitWithEventsDispatching("Summaries should arrive", {
                panel.model.card(entry.id)!!.summary != null && panel.model.card(helper.id)!!.summary != null
            }, 30)
            panel.model.setSummary(null, "File summary")
            panel.model.setSummary(entry.id, "Entry summary")
            panel.model.setSummary(helper.id, "Helper summary")
            descendants(panel.cardViews.getValue(helper.id)).filterIsInstance<ActionLink>()
                .single { it.text == "Side by side" }.doClick()
            val tab = manager.openFiles.filterIsInstance<SourceChainFile>().single()
            assertEquals(listOf(1, 2, 4), tab.locations.map { it.line })
            assertEquals(3, tab.locations.size)
            assertTrue(tab.locations.all { it.file == file })
            assertEquals(listOf("File summary", "Entry summary", "Helper summary"), tab.locations.map { it.summary })
            panel.close(entry.id)
            assertEquals("The opened view retains the chosen chain", 3, tab.locations.size)
        } finally {
            Disposer.dispose(panel)
            manager.openFiles.filterIsInstance<SourceChainFile>().forEach(manager::closeFile)
        }
    }

    fun testSchematicCardLinkUsesTheUnfoldedBranchIncludingItsCallSite() {
        myFixture.configureByText("Flow.java", source)
        val result = ProjectGraphAnalyzer.build(project)
        val root = result.graph.nodes.single { it.kind == SymbolKind.FILE }.id
        val entry = result.graph.nodes.single { it.kind == SymbolKind.FUNCTION && it.name.startsWith("entry(") }.id
        val helper = result.graph.nodes.single { it.kind == SymbolKind.FUNCTION && it.name.startsWith("helper(") }.id
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, root))
        val manager = FileEditorManager.getInstance(project)
        try {
            val panel = editor.component as SchematicPanel
            PlatformTestUtil.waitWithEventsDispatching("Schematic should load", {
                descendants(panel).filterIsInstance<ActionLink>().any { it.text == "Side by side" }
            }, 30)
            val show = schematicCanvasTools(panel.chatCanvas).single { it.definition.name == "show_call_chain" }
            background { show.call(JsonParser.parseString("""{"symbol_ids":[$entry,$helper]}""").asJsonObject) }
            val nested = descendants(panel).filterIsInstance<Container>().single { card ->
                card.javaClass.simpleName == "SummaryCard" &&
                    generateSequence(card.parent) { it.parent }.any { it.javaClass.simpleName == "SummaryCard" }
            }
            descendants(nested).filterIsInstance<ActionLink>().single { it.text == "Side by side" }.doClick()
            val tab = manager.openFiles.filterIsInstance<SourceChainFile>().single()
            assertEquals(listOf(1, 2, 4), tab.locations.map { it.line })
            assertTrue(tab.locations.all { it.path.endsWith("Flow.java") })
        } finally {
            Disposer.dispose(editor)
            manager.openFiles.filterIsInstance<SourceChainFile>().forEach(manager::closeFile)
        }
    }
}
