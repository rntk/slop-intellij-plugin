package dev.insight.canvas

import com.google.gson.JsonObject
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
import java.awt.Container
import javax.swing.JLabel

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
        assertTrue(canvasChainContext(canvas.model.session, entry.id).contains("Current card [card ${entry.id}]: file Flow.java"))

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

    fun testDeclarationsAndFilesOutsideTheChainOpenAsCardsAndResolveAgainOnRestore() {
        val other = myFixture.addFileToProject("Other.java", "class Other {\n    static void target() {}\n    static void unused() {}\n}")
        val file = myFixture.configureByText("Flow.java", source)
        val canvas = controller(file, CanvasSession.new("Flow.java"))
        background { canvas.loadRoot() }
        val entry = canvas.open(null, "entry")
        assertFalse("Nothing on the canvas links to it yet", canvas.source(entry.id)!!.links.flatMap { it.targets }
            .any { canvas.node(it)?.name?.startsWith("unused") == true })

        val declared = background { canvas.declarations(other.virtualFile, "unused") }
        assertEquals(1, declared.size)
        assertEquals(3, canvas.target(declared.single().id)!!.line)
        val unused = background { canvas.openSymbol(entry.id, 4, 8, declared.single().id) }
        assertEquals(entry.id, unused.parentId)
        assertTrue(unused.text, unused.text.contains("static void unused()"))
        assertTrue(unused.filePath.endsWith("Other.java"))

        val whole = background { canvas.openFile(entry.id, 4, 8, other.virtualFile) }
        assertTrue(whole.wholeFile)
        assertEquals(entry.id, whole.parentId)
        assertEquals(other.text, canvas.source(whole.id)!!.text)
        assertTrue("The file's symbols are clickable", canvas.source(whole.id)!!.links.isNotEmpty())
        assertEquals(whole.id, background { canvas.openFile(entry.id, 4, 8, other.virtualFile) }.id)

        val restored = controller(file, canvas.model.session)
        background { restored.loadRoot(); restored.restore() }
        assertNull(restored.source(unused.id)!!.notice)
        assertEquals("unused", restored.node(restored.source(unused.id)!!.node!!)!!.name.substringBefore('('))
        assertEquals(other.text, restored.source(whole.id)!!.text)
        assertTrue(restored.model.card(whole.id)!!.wholeFile)
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

    fun testChatOpeningToolsMarkNewCardsAndKeepOriginalOpenerWhenRevealing() {
        val other = myFixture.addFileToProject("Other.java", "class Other { static void unused() {} }")
        val file = myFixture.configureByText("Flow.java", source)
        val session = CanvasSession.new("Flow.java")
        val services = CanvasSessionServices(null, { { Completion("Summary", finishReason = FinishReason.COMPLETE) } },
            summaryTools = { emptyList() }) { _, _ ->
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit) = "Answer"
            }
        }
        val panel = CanvasSessionPanel(project, sessionFile(file, session), session, services) {}
        try {
            panel.setSize(1400, 900)
            PlatformTestUtil.waitWithEventsDispatching("The root file should be shown", { panel.ready }, 30)
            val root = panel.controller.source(null)!!
            val (_, target) = panel.controller.link(root, "entry")
            val link = root.links.filter { target in it.targets }
                .minWith(compareBy({ it.range.first }, { it.range.last - it.range.first }))
            val job = panel.openSymbol(null, link, target)
            PlatformTestUtil.waitWithEventsDispatching("The reader's card should open", { job.isCompleted }, 30)
            val entry = panel.model.cards.single()
            assertEquals(CardOpenedBy.USER, entry.openedBy)
            val tools = canvasTools(panel.actions, entry.id, maxOpened = 10)
            fun call(name: String, arguments: JsonObject = JsonObject()) = background {
                tools.first { it.definition.name == name }.call(arguments)
            }
            fun args(vararg values: Pair<String, String>) = JsonObject().apply {
                values.forEach { (key, value) -> addProperty(key, value) }
            }
            fun badge(cardId: String): JLabel {
                fun labels(container: Container): List<JLabel> = container.components.flatMap {
                    (if (it is JLabel) listOf(it) else emptyList()) + (if (it is Container) labels(it) else emptyList())
                }
                return labels(panel.cardViews.getValue(cardId)).single { it.name == "canvas-opened-by-llm" }
            }

            call("open_symbol", args("name" to "helper"))
            val helper = panel.model.cards.last()
            assertEquals(CardOpenedBy.LLM, helper.openedBy)
            assertTrue(badge(helper.id).isVisible)
            assertEquals("LLM", badge(helper.id).text)
            assertEquals("Opened by the LLM through chat tools", badge(helper.id).toolTipText)
            panel.model.setFolded(helper.id, CardSection.META, true)
            assertTrue("The marker stays visible with folded metadata", badge(helper.id).isVisible)

            call("open_symbol", args("card" to "root", "name" to "entry"))
            call("show_card", args("card" to entry.id))
            assertEquals("Revealing existing cards opens no new cards", 2, panel.model.cards.size)
            assertEquals(CardOpenedBy.USER, panel.model.card(entry.id)!!.openedBy)
            assertFalse(badge(entry.id).isVisible)
            val helperAgain = background { panel.controller.openSymbol(entry.id, helper.anchor.startOffset,
                helper.anchor.endOffset, panel.controller.source(helper.id)!!.node!!) }
            assertEquals(helper.id, helperAgain.id)
            assertEquals(CardOpenedBy.LLM, panel.model.card(helper.id)!!.openedBy)

            call("open_selection", JsonObject().apply { addProperty("start_line", 3) })
            val selection = panel.model.cards.last()
            assertEquals(CardKind.SELECTION, selection.kind)
            assertEquals(CardOpenedBy.LLM, selection.openedBy)
            assertTrue(badge(selection.id).isVisible)

            val path = panel.controller.relative(other.virtualFile.path)!!
            call("open_declaration", args("path" to path, "name" to "unused"))
            assertEquals(CardOpenedBy.LLM, panel.model.cards.last().openedBy)
            call("open_file", args("path" to path))
            val whole = panel.model.cards.last()
            assertTrue(whole.wholeFile)
            assertEquals(CardOpenedBy.LLM, whole.openedBy)
            assertTrue(badge(whole.id).isVisible)

            background { panel.controller.expandToFile(helper.id) }
            assertTrue(badge(helper.id).isVisible)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testRootSectionsMatchCardOrderAndSummaryRunsOnlyOnClick() {
        val file = myFixture.configureByText("Flow.java", source)
        val store = CanvasSessionStore(Files.createTempDirectory("canvas-root-summary"))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val services = CanvasSessionServices(store, { {
            calls.incrementAndGet()
            Completion("The Flow file.", finishReason = FinishReason.COMPLETE)
        } }, summaryTools = { emptyList() }) { _, _ -> error("No chat requested") }
        var panel: CanvasSessionPanel? = null
        try {
            fun open(session: CanvasSession): CanvasSessionPanel {
                val opened = CanvasSessionPanel(project, sessionFile(file, session), session, services) {}
                panel = opened
                opened.setSize(1400, 900)
                PlatformTestUtil.waitWithEventsDispatching("The file should load", { opened.ready }, 30)
                return opened
            }
            fun sections(opened: CanvasSessionPanel): List<CanvasFoldSection> =
                (opened.canvas.components.single { it.name == "canvas-root" } as Container)
                    .components.filterIsInstance<CanvasFoldSection>()
            val opened = open(CanvasSession.new("Flow.java"))
            assertEquals(0, calls.get())
            assertEquals(listOf(CardSection.SUMMARY, CardSection.SOURCE, CardSection.CHAT, CardSection.INPUT),
                sections(opened).map { it.section })
            for (section in sections(opened)) {
                opened.model.toggleFolded(null, section.section)
                assertFalse(section.body.isVisible)
                opened.model.toggleFolded(null, section.section)
                assertTrue(section.body.isVisible)
            }
            assertEquals(0, calls.get())
            val summaryBody = sections(opened).first().body
            fun links(container: Container): List<com.intellij.ui.components.ActionLink> = container.components.flatMap {
                (if (it is com.intellij.ui.components.ActionLink) listOf(it) else emptyList()) +
                    (if (it is Container) links(it) else emptyList())
            }
            links(summaryBody).single { it.text == "Summarize" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("The requested summary should arrive",
                { opened.model.session.root.summary != null }, 30)
            assertEquals(1, calls.get())
            assertEquals("The Flow file.", opened.model.session.root.summary)
            opened.model.setFolded(null, CardSection.SOURCE, true)
            opened.model.setFolded(null, CardSection.INPUT, true)
            val saved = opened.model.session
            assertTrue(store.save(saved))
            Disposer.dispose(opened)
            panel = null
            val restored = open(store.load(saved.id)!!)
            assertEquals(saved.root, restored.model.session.root)
            assertFalse(sections(restored).single { it.section == CardSection.SOURCE }.body.isVisible)
            assertFalse(sections(restored).single { it.section == CardSection.INPUT }.body.isVisible)
            assertEquals(1, calls.get())
        } finally {
            panel?.let(Disposer::dispose)
        }
    }

    fun testTheRootFileChatsLikeACardBeforeAnyCardIsOpen() {
        val file = myFixture.configureByText("Flow.java", source)
        val directory = Files.createTempDirectory("canvas-sessions")
        val store = CanvasSessionStore(directory)
        val asked = java.util.Collections.synchronizedList(mutableListOf<Pair<String?, String>>())
        val services = CanvasSessionServices(store, { { Completion("Summary", finishReason = FinishReason.COMPLETE) } },
            summaryTools = { emptyList() }) { _, cardId ->
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    asked += cardId to messages.last().text
                    return "It is the Flow class."
                }
            }
        }
        val session = CanvasSession.new("Flow.java")
        val panel = CanvasSessionPanel(project, sessionFile(file, session), session, services) {}
        try {
            panel.setSize(1400, 900)
            PlatformTestUtil.waitWithEventsDispatching("The root file should be shown", { panel.ready }, 30)
            val chat = panel.rootChat!!
            assertTrue(panel.model.cards.isEmpty())
            chat.send("What does this file do?")
            PlatformTestUtil.waitWithEventsDispatching("The root chat should answer",
                { panel.model.session.root.messages.any { it.role == ChatRole.ASSISTANT } }, 30)
            assertEquals(listOf<Pair<String?, String>>(null to "What does this file do?"), asked.toList())
            assertEquals(listOf(ChatRole.NOTICE, ChatRole.USER, ChatRole.ASSISTANT), panel.model.session.root.messages.map { it.role })
            assertEquals("Ask about the file Flow.java.", panel.model.session.root.messages.first().text.substringBefore(" I read"))

            val first = panel.model.session.root.activeChatId
            chat.input.text = "Root draft"
            val second = panel.model.addChat(null)!!
            assertEquals("", chat.input.text)
            panel.model.selectChat(null, first)
            assertEquals("Root draft", chat.input.text)
            assertEquals(listOf(first, second.id), panel.model.session.root.chats.map { it.id })

            val tools = canvasTools(panel.actions, null, maxOpened = 10)
            background { tools.first { it.definition.name == "open_symbol" }.call(JsonObject().apply { addProperty("name", "entry") }) }
            val opened = panel.model.cards.single()
            assertNull(opened.parentId)
            assertEquals(CardOpenedBy.LLM, opened.openedBy)
            panel.model.toggleFolded(null, CardSection.CHAT)
            panel.canvas.doLayout()
            assertTrue("The card still sits right of the root file", panel.cardViews.getValue(opened.id).x > 0)
            assertTrue(store.save(panel.model.session))
            assertEquals(panel.model.session.root, store.load(session.id)!!.root)
        } finally {
            Disposer.dispose(panel)
        }
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
