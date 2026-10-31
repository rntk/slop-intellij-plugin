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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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

    fun testExpandToFileKeepsChatAndBranchesAndRestoresFullFile() {
        val file = myFixture.configureByText("Flow.java", source)
        val canvas = controller(file, CanvasSession.new("Flow.java"))
        background { canvas.loadRoot() }
        val entry = canvas.open(null, "entry")
        val helper = canvas.open(entry.id, "helper")
        val start = canvas.source(entry.id)!!.text.indexOf("helper();")
        val selection = canvas.openSelection(entry.id, start, start + "helper();".length)!!
        val messages = listOf(SavedMessage(ChatRole.USER, "Why?"), SavedMessage(ChatRole.ASSISTANT, "It delegates."))
        canvas.model.setMessages(entry.id, messages)
        canvas.model.setDraft(entry.id, "What else?")
        canvas.model.setSummary(entry.id, "Calls helper.")
        canvas.model.setFolded(entry.id, CardSection.SOURCE, true)
        background { canvas.expandToFile(entry.id) }

        val expanded = canvas.model.card(entry.id)!!
        assertTrue(expanded.wholeFile)
        assertEquals("Flow.java", expanded.title)
        assertEquals(source, canvas.source(entry.id)!!.text)
        assertEquals(0, expanded.firstLine)
        assertEquals(messages, expanded.messages)
        assertEquals("What else?", expanded.draft)
        assertNull(expanded.summary)
        assertFalse(CardSection.SOURCE in expanded.folded)
        assertEquals("The original link still finds this card", entry.id, canvas.open(null, "entry").id)
        assertEquals("The child moves to the same call in the full file", source.indexOf("helper();"),
            canvas.model.card(helper.id)!!.anchor.startOffset)
        assertEquals("The same call still finds its existing child", helper.id, canvas.open(entry.id, "helper").id)
        val another = canvas.open(entry.id, "Flow")
        assertEquals(entry.id, another.parentId)
        assertTrue(canvasChainContext(canvas.model.session, entry.id).contains("Current card: file Flow.java"))

        val restored = controller(file, canvas.model.session)
        background { restored.loadRoot(); restored.restore() }
        assertEquals(source, restored.source(entry.id)!!.text)
        assertTrue(restored.model.card(entry.id)!!.wholeFile)
        assertEquals(messages, restored.model.card(entry.id)!!.messages)
        assertNull(restored.source(selection.id)!!.notice)
        assertTrue(restored.source(selection.id)!!.links.isNotEmpty())
    }

    fun testExpandCrossFileSymbolDiscoversLinksOutsideItsOriginalScope() {
        val other = myFixture.addFileToProject("Other.java", """
            class Other {
                static void target() {}
                static void sibling() { target(); }
            }
        """.trimIndent())
        val file = myFixture.configureByText("Flow.java", "class Flow { void entry() { Other.target(); } }")
        val canvas = controller(file, CanvasSession.new("Flow.java"))
        background { canvas.loadRoot() }
        val target = canvas.open(null, "target")
        assertFalse(canvas.source(target.id)!!.text.contains("sibling"))
        background { canvas.expandToFile(target.id) }
        assertEquals(other.text, canvas.source(target.id)!!.text)
        val sibling = canvas.open(target.id, "sibling")
        assertEquals(target.id, sibling.parentId)
        assertTrue(sibling.text.contains("target();"))

        val restored = controller(file, canvas.model.session)
        background { restored.loadRoot(); restored.restore() }
        assertEquals(other.text, restored.source(target.id)!!.text)
        assertNotNull(restored.source(sibling.id)!!.node)
    }

    fun testSelectionCanExpandToFileWithItsChildStillAnchored() {
        val file = myFixture.configureByText("Flow.java", source)
        val canvas = controller(file, CanvasSession.new("Flow.java"))
        background { canvas.loadRoot() }
        val start = source.indexOf("helper();")
        val selection = canvas.openSelection(null, start, start + "helper();".length)!!
        val child = canvas.open(selection.id, "helper")
        background { canvas.expandToFile(selection.id) }
        assertEquals(source, canvas.source(selection.id)!!.text)
        assertEquals(start, canvas.model.card(child.id)!!.anchor.startOffset)
        assertEquals(child.id, canvas.open(selection.id, "helper").id)
    }

    fun testFullFileBeyondSnapshotLimitRemainsVisibleAndLinked() {
        val large = "class Flow {\n    void entry() {}\n" + "    // More file context.\n".repeat(2000) +
            "    void last() {}\n}"
        val file = myFixture.configureByText("Flow.java", large)
        val canvas = controller(file, CanvasSession.new("Flow.java"))
        background { canvas.loadRoot() }
        val entry = canvas.open(null, "entry")
        background { canvas.expandToFile(entry.id) }
        val expanded = canvas.model.card(entry.id)!!
        assertTrue(expanded.textTruncated)
        assertTrue(expanded.text.length <= CANVAS_CARD_TEXT_LIMIT)
        assertEquals(large, canvas.source(entry.id)!!.text)
        val last = canvas.open(entry.id, "last")
        assertTrue(canvas.source(last.id)!!.text.contains("void last()"))
        assertTrue(canvasChainContext(canvas.model.session, entry.id).contains("use read_file"))
    }

    fun testPanelOpensCardsSummarizesChatsAndSavesTheSession() {
        val file = myFixture.configureByText("Flow.java", source)
        val directory = Files.createTempDirectory("canvas-sessions")
        val store = CanvasSessionStore(directory)
        val asked = java.util.Collections.synchronizedList(mutableListOf<List<ChatMessage>>())
        val slowReply = CompletableDeferred<Unit>()
        val closedReply = CompletableDeferred<Unit>()
        val services = CanvasSessionServices(store, { { Completion("Calls the helper.", finishReason = FinishReason.COMPLETE) } }, summaryTools = { emptyList() }) { _, _ ->
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    asked += messages
                    if (messages.last().text == "Slow question") slowReply.await()
                    if (messages.last().text == "Close question") {
                        try { awaitCancellation() } finally { closedReply.complete(Unit) }
                    }
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

            val oldPane = view.sourcePane
            panel.expandToFile(card.id)
            PlatformTestUtil.waitWithEventsDispatching("The card should show the whole file", { panel.model.card(card.id)!!.wholeFile }, 30)
            assertEquals(source, view.sourcePane!!.source.text)
            assertNotSame(oldPane, view.sourcePane)
            view.send("What else is in this file?")
            PlatformTestUtil.waitWithEventsDispatching("The existing chat should answer again",
                { panel.model.card(card.id)!!.messages.count { it.role == ChatRole.ASSISTANT } == 2 }, 30)
            assertEquals(2, asked.last().count { it.role == ChatRole.USER })

            val firstChat = panel.model.card(card.id)!!.activeChatId
            view.input.text = "First draft"
            view.send("Slow question")
            PlatformTestUtil.waitWithEventsDispatching("The first tab should start its reply",
                { asked.any { it.last().text == "Slow question" } }, 30)
            val secondChat = panel.model.addChat(card.id)!!
            assertEquals("", view.input.text)
            view.input.text = "Second draft"
            view.send("Independent question")
            PlatformTestUtil.waitWithEventsDispatching("The second tab should answer while the first is busy",
                { panel.model.card(card.id)!!.messages.any { it.role == ChatRole.ASSISTANT } }, 30)
            assertEquals(listOf("Independent question"), asked.last().map { it.text })
            slowReply.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("The first tab should save its background reply",
                { panel.model.card(card.id)!!.chats.first().messages.count { it.role == ChatRole.ASSISTANT } == 3 }, 30)
            assertEquals(secondChat.id, view.card.activeChatId)
            assertEquals(1, view.card.messages.count { it.role == ChatRole.USER })
            panel.model.selectChat(card.id, firstChat)
            assertEquals("First draft", view.input.text)
            panel.model.selectChat(card.id, secondChat.id)
            assertEquals("Second draft", view.input.text)
            assertTrue(store.save(panel.model.session))
            assertEquals(panel.model.card(card.id)!!.chats, store.load(session.id)!!.card(card.id)!!.chats)
            view.send("Close question")
            PlatformTestUtil.waitWithEventsDispatching("The tab to close should start its reply",
                { asked.any { it.last().text == "Close question" } }, 30)
            panel.model.removeChat(card.id, secondChat.id)
            PlatformTestUtil.waitWithEventsDispatching("Closing a tab should cancel its reply", { closedReply.isCompleted }, 30)
            assertEquals(firstChat, view.card.activeChatId)
            assertEquals("First draft", view.input.text)

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
