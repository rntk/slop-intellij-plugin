package dev.insight.canvas

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.agent.ChatBackend
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import dev.insight.agent.ToolCallEvent
import dev.insight.llm.Completion
import dev.insight.llm.FinishReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.nio.file.Files

class CanvasSessionControllerTest : LightJavaCodeInsightFixtureTestCase() {
    private val source = """
        class Flow {
            void entry() {
                helper();
            }
            void helper() {
                int x = 1;
            }
        }
    """.trimIndent()

    private fun <T> background(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Canvas work should finish", { task.isCompleted }, 30)
            return runBlocking { task.await() }.getOrThrow()
        } finally {
            task.cancel()
        }
    }

    private fun sessionFile(file: PsiFile, session: CanvasSession? = null): CanvasSessionFile {
        val opened = background { openCanvasSessionFile(project, file.virtualFile, store = null) }
        assertNotNull("The file must be shown", opened)
        session?.let { opened!!.session = it }
        return opened!!
    }

    private fun controller(file: PsiFile, session: CanvasSession): CanvasSessionController {
        val opened = sessionFile(file)
        return CanvasSessionController(project, file.virtualFile, opened.exploration, opened.root, CanvasSessionModel(session))
            .also { Disposer.register(testRootDisposable, it) }
    }

    private fun CanvasSessionController.link(source: CanvasSource, name: String): Pair<CanvasLink, Int> {
        for (link in source.links) {
            val target = link.targets.firstOrNull { node(it)?.name?.substringBefore('(') == name } ?: continue
            return link to target
        }
        error("No link to $name among ${source.links.map { it.targets.map { id -> node(id)?.name } }}")
    }

    private fun CanvasSessionController.open(parentId: String?, name: String): CanvasCard {
        val (link, target) = link(source(parentId)!!, name)
        return background { openSymbol(parentId, link.range.first, link.range.last + 1, target) }
    }

    fun testCardsOpenFromLinksAndSelectionsAndResolveAgainOnRestore() {
        val file = myFixture.configureByText("Flow.java", source)
        val canvas = controller(file, CanvasSession.new("Flow.java"))
        val root = background { canvas.loadRoot() }
        assertEquals(source, root.text)
        assertEquals(0, root.firstLine)

        val entry = canvas.open(null, "entry")
        assertEquals(CardKind.SYMBOL, entry.kind)
        assertEquals("entry", entry.symbol!!.name.substringBefore('('))
        assertEquals(1, entry.anchor.line)
        assertEquals(1, entry.firstLine)
        assertTrue(entry.text, entry.text.contains("helper();"))
        assertEquals("Opening the same link again shows the open card", entry.id, canvas.open(null, "entry").id)
        assertEquals(1, canvas.model.cards.size)

        val entrySource = canvas.source(entry.id)!!
        assertEquals(entry.text, entrySource.text)
        val helper = canvas.open(entry.id, "helper")
        assertEquals(entry.id, helper.parentId)
        assertTrue(helper.text.contains("int x = 1;"))
        assertEquals(listOf(entry.id, helper.id), canvas.model.chain(helper.id).map { it.id })

        val start = entrySource.text.indexOf("helper();")
        val selection = canvas.openSelection(entry.id, start, start + "helper();".length)!!
        assertEquals(CardKind.SELECTION, selection.kind)
        assertEquals("helper();", selection.text)
        assertEquals(2, selection.anchor.line)
        assertEquals(2, selection.firstLine)
        assertTrue("The selection keeps the call's link", canvas.source(selection.id)!!.links.isNotEmpty())
        assertNull("Blank selections open nothing", canvas.openSelection(null, 0, 0))

        // Reopening resolves each symbol again from a fresh graph.
        val restored = controller(file, canvas.model.session)
        background { restored.loadRoot(); restored.restore() }
        for (card in listOf(entry, helper)) {
            val shown = restored.source(card.id)!!
            assertNull(shown.notice)
            assertEquals(card.symbol!!.name, restored.node(shown.node!!)!!.name)
            assertEquals(card.text, shown.text)
            assertTrue(shown.links.isNotEmpty() || card.id == helper.id)
        }
        assertNull(restored.source(selection.id)!!.notice)
        assertEquals(canvas.source(selection.id)!!.links, restored.source(selection.id)!!.links)

        // After a rename the helper is gone: it keeps its saved text, and its parent shows the changed code.
        WriteCommandAction.runWriteCommandAction(project) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
            document.setText(source.replace("helper", "assist").replace("void entry() {", "void entry() {\n        int y = 2;"))
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val changed = controller(file, canvas.model.session)
        background { changed.loadRoot(); changed.restore() }
        val entryNow = changed.source(entry.id)!!
        assertEquals(CANVAS_SOURCE_CHANGED, entryNow.notice)
        assertTrue(entryNow.text.contains("assist();"))
        assertTrue("The saved snapshot follows the current code", changed.model.card(entry.id)!!.text.contains("assist();"))
        val helperNow = changed.source(helper.id)!!
        assertEquals(CANVAS_SYMBOL_MISSING, helperNow.notice)
        assertEquals(helper.text, helperNow.text)
        assertTrue(helperNow.links.isEmpty())
        assertEquals(CANVAS_SELECTION_CHANGED, changed.source(selection.id)!!.notice)
        assertEquals("helper();", changed.model.card(selection.id)!!.text)
    }

    fun testPanelOpensCardsSummarizesChatsAndSavesTheSession() {
        val file = myFixture.configureByText("Flow.java", source)
        val directory = Files.createTempDirectory("canvas-sessions")
        val store = CanvasSessionStore(directory)
        val asked = mutableListOf<List<ChatMessage>>()
        val services = CanvasSessionServices(store, { { Completion("Calls the helper.", finishReason = FinishReason.COMPLETE) } }) { _, _ ->
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    asked += messages
                    return "It calls helper."
                }
            }
        }
        val session = CanvasSession.new("Flow.java")
        val opened = sessionFile(file, session)
        var switched: CanvasSession? = null
        val panel = CanvasSessionPanel(project, opened, session, services) { switched = it }
        try {
            panel.setSize(1400, 900)
            PlatformTestUtil.waitWithEventsDispatching("The root file should be shown", { panel.ready }, 30)
            val (link, target) = panel.controller.link(panel.controller.source(null)!!, "entry")
            val job = panel.openSymbol(null, link, target)
            PlatformTestUtil.waitWithEventsDispatching("The card should open", { job.isCompleted }, 30)
            val card = panel.model.cards.single()
            val view = panel.cardViews.getValue(card.id)
            assertNotNull(view.sourcePane)
            PlatformTestUtil.waitWithEventsDispatching("The summary should arrive", { panel.model.card(card.id)!!.summary != null }, 30)
            assertEquals("Calls the helper.", panel.model.card(card.id)!!.summary)

            view.send("What does entry do?")
            PlatformTestUtil.waitWithEventsDispatching("The chat should answer",
                { panel.model.card(card.id)!!.messages.any { it.role == ChatRole.ASSISTANT } }, 30)
            assertEquals("What does entry do?", asked.single().last().text)
            assertEquals(listOf(ChatRole.NOTICE, ChatRole.USER, ChatRole.ASSISTANT), panel.model.card(card.id)!!.messages.map { it.role })

            panel.model.toggleFolded(card.id, CardSection.SOURCE)
            assertFalse("Folding hides the source", view.sourcePane!!.isVisible)
            panel.doLayout()
            panel.canvas.doLayout()
            assertTrue("The card sits right of the root file", view.x > 0 && view.width > 0)

            val start = panel.controller.source(null)!!.text.indexOf("void helper")
            panel.controller.openSelection(null, start, start + 11)
            assertEquals(2, panel.cardViews.size)
            panel.close(card.id)
            assertEquals(1, panel.cardViews.size)
        } finally {
            Disposer.dispose(panel)
        }
        assertNull(switched)
        val saved = store.load(session.id)!!
        assertEquals(1, saved.cards.size)
        assertEquals(CardKind.SELECTION, saved.cards.single().kind)
        assertEquals("void helper", saved.cards.single().text)
    }
}
