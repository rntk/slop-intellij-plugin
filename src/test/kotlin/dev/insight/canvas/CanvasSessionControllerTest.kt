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
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
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

    fun testExternalDocumentationAttachesWithoutLanguageSupportAndRestoresItsSnapshot() {
        val sourceFile = myFixture.configureByText("Flow.java", source)
        val canvas = controller(sourceFile, CanvasSession.new("Flow.java"))
        background { canvas.loadRoot() }
        val method = canvas.open(null, "entry")
        val path = Files.createTempFile("canvas-documentation-", ".md")
        try {
            Files.writeString(path, "# Documentation\nThis method starts the workflow.")
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
            val attachment = background { canvas.attachFile(method.id, file) }
            assertEquals(CardKind.ATTACHMENT, attachment.kind)
            assertEquals(method.id, attachment.parentId)
            assertEquals(path.toString(), attachment.filePath)
            assertTrue(attachment.text.contains("This method starts the workflow."))
            assertEquals(file, canvas.source(attachment.id)!!.file)
            assertTrue(canvas.source(attachment.id)!!.links.isEmpty())
            val reopened = controller(sourceFile, canvas.model.session)
            background { reopened.loadRoot(); reopened.restore() }
            assertEquals(attachment.text, reopened.source(attachment.id)!!.text)
            assertEquals(file, reopened.source(attachment.id)!!.file)
            assertTrue(canvasChainContext(reopened.model.session, method.id).contains("This method starts the workflow."))
        } finally { Files.deleteIfExists(path) }
    }

    fun testAttachmentCardsShowPictureAndTextPreviewsAndOpenOriginalTextInTheIde() {
        val file = myFixture.configureByText("Flow.java", source)
        val document = myFixture.addFileToProject("notes.md", "# Workflow documentation")
        val bitmap = java.awt.image.BufferedImage(80, 40, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(bitmap, "png", it) }.toByteArray()
        val image = java.util.Base64.getEncoder().encodeToString(bytes)
        val model = CanvasSessionModel(CanvasSession.new("Flow.java"))
        val picture = model.attach(null, "/missing/screenshot.png", "", CanvasAttachment("image/png", bytes.size.toLong(), image))
        val text = model.attach(null, document.virtualFile.path, document.text, CanvasAttachment("text/markdown", document.textLength.toLong()))
        val services = CanvasSessionServices(null, { error("Attachments should not request code summaries") }) { _, _ ->
            error("No chat requested")
        }
        val panel = CanvasSessionPanel(project, sessionFile(file, model.session), model.session, services) {}
        try {
            panel.setSize(1400, 900)
            PlatformTestUtil.waitWithEventsDispatching("Attachment previews should load", { panel.ready }, 30)
            fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
                listOf(it) + if (it is Container) descendants(it) else emptyList()
            }
            val pictureView = panel.cardViews.getValue(picture.id)
            assertEquals(1, descendants(pictureView).filterIsInstance<CanvasImagePreview>().size)
            assertEquals(document.text, panel.cardViews.getValue(text.id).sourcePane!!.source.text)
            assertTrue(pictureView.menuActions().any { it.label == "Open file" })
            assertTrue(panel.cardViews.values.all { view -> view.menuActions().any { it.name == "canvas-attach-file" } })
            panel.navigate(panel.controller.source(text.id), text.filePath, 0)
            PlatformTestUtil.waitWithEventsDispatching("Open file should open the original in the IDE",
                { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).isFileOpen(document.virtualFile) }, 30)
        } finally { Disposer.dispose(panel) }
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

    fun testRestoresSmallZoomAndCanZoomBackIn() {
        val file = myFixture.configureByText("Flow.java", source)
        val card = CanvasCard("selection", null, CardKind.SELECTION, CardAnchor(0, source.length, 0),
            null, "Flow.java", 0, source, summary = "A saved summary with wrapping text. ".repeat(50),
            chats = listOf(CanvasChat(messages = listOf(SavedMessage(ChatRole.ASSISTANT,
                "A saved answer with wrapping text. ".repeat(50))))))
        val session = CanvasSession.new("Flow.java").copy(view = CanvasView(0.05, 300, 200), cards = listOf(card))
        val services = CanvasSessionServices(null, { error("No summary requested") },
            summaryTools = { emptyList() }) { _, _ -> error("No chat requested") }
        val panel = CanvasSessionPanel(project, sessionFile(file, session), session, services) {}
        try {
            panel.setSize(1400, 900)
            PlatformTestUtil.waitWithEventsDispatching("The zoomed file should load", { panel.ready }, 30)
            assertEquals(0.05, panel.zoom, 0.001)
            val viewport = panel.canvas.parent as javax.swing.JViewport
            PlatformTestUtil.waitWithEventsDispatching("The saved scroll position should be restored",
                { viewport.viewPosition == java.awt.Point(300, 200) }, 30)
            assertEquals(card.summary, panel.model.cards.single().summary)
            assertEquals(card.messages, panel.model.cards.single().messages)
            panel.zoomTo(1.0)
            assertEquals(1.0, panel.zoom, 0.001)
        } finally {
            Disposer.dispose(panel)
        }
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
            assertEquals(listOf(CardSection.SUMMARY, CardSection.SOURCE, CardSection.CHAT),
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
            opened.model.setFolded(null, CardSection.CHAT, true)
            val saved = opened.model.session
            assertTrue(store.save(saved))
            Disposer.dispose(opened)
            panel = null
            val restored = open(store.load(saved.id)!!)
            assertEquals(saved.root, restored.model.session.root)
            assertFalse(sections(restored).single { it.section == CardSection.SOURCE }.body.isVisible)
            assertFalse(sections(restored).single { it.section == CardSection.CHAT }.body.isVisible)
            assertEquals(1, calls.get())
        } finally {
            panel?.let(Disposer::dispose)
        }
    }

    fun testDiffSourceShowsOnlyForFilesFromADiffAndOnlyHunksWithinACard() {
        val file = myFixture.configureByText("Flow.java", source)
        val services = CanvasSessionServices(null, { { Completion("Summary.", finishReason = FinishReason.COMPLETE) } },
            summaryTools = { emptyList() }) { _, _ -> error("No chat requested") }
        val session = CanvasSession.new("Flow.java")
        val plain = sessionFile(file, session)
        val patch = "@@ -6,1 +6,1 @@\n-        int x = 0;\n+        int x = 1;\n"
        val opened = CanvasSessionFile(plain.source, plain.rootPath, plain.exploration, plain.root, session,
            mapOf(file.virtualFile.path to patch))
        val panel = CanvasSessionPanel(project, opened, session, services) {}
        try {
            panel.setSize(1400, 900)
            PlatformTestUtil.waitWithEventsDispatching("The root file should be shown", { panel.ready }, 30)
            val rootSections = (panel.canvas.components.single { it.name == "canvas-root" } as Container)
                .components.filterIsInstance<CanvasFoldSection>()
            assertEquals(listOf(CardSection.SUMMARY, CardSection.SOURCE, CardSection.CHAT),
                rootSections.map { it.section })
            val tabs = rootSections.single { it.section == CardSection.SOURCE } as CanvasSourceSection
            val body = tabs.body as Container
            fun click(label: JLabel) = label.mouseListeners.forEach {
                it.mouseClicked(MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON1_DOWN_MASK, 1, 1, 1, false, MouseEvent.BUTTON1))
            }
            assertEquals("Diff · +1 −1", tabs.diffTab.text)
            assertTrue(tabs.diffTab.isVisible)
            assertFalse("The source shows first", tabs.showsDiff)
            assertTrue(body.components.single() is CanvasSourcePane)
            click(tabs.diffTab)
            assertTrue("One tab shows at a time", body.components.single() is CanvasDiffPane)
            click(tabs.diffTab)
            assertFalse("The shown tab folds the section", body.isVisible)
            click(tabs.title)
            assertTrue("The other tab shows, unfolded", body.isVisible && body.components.single() is CanvasSourcePane)

            fun open(name: String): CanvasCardView {
                val (link, target) = panel.controller.link(panel.controller.source(null)!!, name)
                val job = panel.openSymbol(null, link, target)
                PlatformTestUtil.waitWithEventsDispatching("The card should open", { job.isCompleted }, 30)
                return panel.cardViews.getValue(panel.model.cards.last().id)
            }
            val entry = open("entry")
            assertNull("entry has no changed lines", entry.diffPane)
            val helper = open("helper")
            assertEquals(listOf("        int x = 0;", "        int x = 1;"), helper.diffPane!!.rows.map { it.text })
            panel.expandToFile(entry.id)
            PlatformTestUtil.waitWithEventsDispatching("The card should show the whole file", { entry.diffPane != null }, 30)
            assertEquals(2, entry.diffPane!!.rows.size)
        } finally {
            Disposer.dispose(panel)
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
            assertEquals(listOf("Independent question"), asked.last().filter { it.role != ChatRole.NOTICE }.map { it.text })
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

    fun testFindUsagesOnLeafCardOpensCallerAndFileCardsToContinueExploration() {
        val file = myFixture.configureByText("Api.java", "class Api { static void work() {} }")
        myFixture.addFileToProject("Client.java", "class Client { void run() { Api.work(); } }")
        val services = CanvasSessionServices(null, { { Completion("Leaf symbol.", finishReason = FinishReason.COMPLETE) } }, summaryTools = { emptyList() }) { _, _ ->
            error("No chat requested")
        }
        val session = CanvasSession.new("Api.java")
        val panel = CanvasSessionPanel(project, sessionFile(file, session), session, services) {}
        try {
            panel.setSize(1400, 900)
            PlatformTestUtil.waitWithEventsDispatching("The root file should load", { panel.ready }, 30)
            val (link, target) = panel.controller.link(panel.controller.source(null)!!, "work")
            val openWork = panel.openSymbol(null, link, target)
            PlatformTestUtil.waitWithEventsDispatching("The work card should open", { openWork.isCompleted }, 30)
            val workCard = panel.model.cards.single()
            val workView = panel.cardViews.getValue(workCard.id)

            fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
                listOf(it) + if (it is Container) descendants(it) else emptyList()
            }
            fun links(container: Container = workView) = descendants(container).filterIsInstance<com.intellij.ui.components.ActionLink>()

            fun findUsages() = workView.menuActions().single { it.name == "canvas-find-usages" }
            assertEquals("Find usages", findUsages().label)
            findUsages().run()
            PlatformTestUtil.waitWithEventsDispatching("Indexed caller should appear on the card", {
                links().any { it.text.startsWith("Explore run") }
            }, 30)
            assertEquals("Hide usages", findUsages().label)
            val branch = descendants(workView).single { it.name == "canvas-usage-branch" } as Container
            assertTrue(links(branch).any { it.text.startsWith("Call: ") && it.text.contains("Client.java:1") })

            links(branch).single { it.text.startsWith("Explore run") }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Caller symbol card should open beside the leaf card", {
                panel.model.cards.size == 2
            }, 30)
            val callerCard = panel.model.cards.last()
            assertEquals(workCard.id, callerCard.parentId)
            assertTrue(callerCard.text.contains("Api.work();"))

            links(branch).single { it.text.startsWith("Call: ") && it.text.contains("Client.java:1") }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Caller file card should open when clicking the usage site", {
                panel.model.cards.size == 3
            }, 30)
            val fileCard = panel.model.cards.last()
            assertEquals(workCard.id, fileCard.parentId)
            assertTrue(fileCard.wholeFile)
            assertTrue(fileCard.text.contains("class Client"))
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testDeclaredNamesLeadUpToCallersWhoseCallsLeadBackDown() {
        val file = myFixture.configureByText("Api.java", "interface Shape {}\nclass Api implements Shape {\n    void work() {}\n}")
        myFixture.addFileToProject("Client.java", "class Client { Shape shape; void run() { new Api().work(); } }")
        val canvas = controller(file, CanvasSession.new("Api.java"))
        background { canvas.loadRoot() }
        assertTrue("The root file links nested declarations down, not to their callers",
            canvas.source(null)!!.links.none { it.definition })

        fun definition(card: CanvasCard): CanvasLink {
            val source = canvas.source(card.id)!!
            val link = source.links.single { it.definition }
            assertEquals(listOf(source.node), link.targets)
            return link
        }
        val work = canvas.open(null, "work")
        val workName = definition(work)
        assertEquals("work", work.text.substring(workName.range.first, workName.range.last + 1))
        val callers = background { canvas.findUsages(workName.targets.single()) }
        val call = callers.usages.single()
        assertEquals(dev.insight.graph.RelationKind.CALL, call.kind)
        assertEquals("run", canvas.node(call.owner)!!.name.substringBefore('('))

        val caller = background { canvas.openSymbol(work.id, workName.range.first, workName.range.last + 1, call.owner) }
        assertEquals(work.id, caller.parentId)
        assertEquals("The caller's call leads back down to the declaration", workName.targets,
            canvas.link(canvas.source(caller.id)!!, "work").second.let(::listOf))

        val shape = canvas.open(null, "Shape")
        val users = background { canvas.findUsages(definition(shape).targets.single()) }
        assertEquals("A type with no body still leads to its users", setOf("Api", "shape"),
            users.usages.map { canvas.node(it.owner)!!.name.substringBefore('(') }.toSet())

        val kotlin = myFixture.addFileToProject("Port.kt", "interface Port\nclass Svc(val port: Port) {\n    fun go() {}\n}")
        val kotlinCanvas = controller(kotlin, CanvasSession.new("Port.kt"))
        background { kotlinCanvas.loadRoot() }
        for (name in listOf("Port", "Svc", "go")) {
            val (declared, target) = kotlinCanvas.source(null)!!.links.firstNotNullOf { link ->
                link.targets.firstOrNull { id -> kotlinCanvas.node(id)!!.let { it.name.substringBefore('(') == name &&
                    (name == "go" || it.kind == dev.insight.graph.SymbolKind.CLASS) } }?.let { link to it }
            }
            val card = background { kotlinCanvas.openSymbol(null, declared.range.first, declared.range.last + 1, target) }
            val link = kotlinCanvas.source(card.id)!!.links.singleOrNull { it.definition }
            assertNotNull("The $name card should link its own name to its callers: ${card.text}", link)
            assertEquals(name, card.text.substring(link!!.range.first, link.range.last + 1))
        }
    }

    fun testImportsParametersAndTypeScriptBindingsStayClickableAcrossCards() {
        myFixture.addFileToProject("types.ts", """
            export interface Config {
                host: string;
            }
            export class Router {
                dispatch(config: Config): string {
                    return config.host;
                }
            }
        """.trimIndent())
        val tsFile = myFixture.configureByText("app.ts", """
            import { Config, Router } from './types';

            export function start(config: Config, router: Router): string {
                return router.dispatch(config);
            }
        """.trimIndent())
        val canvas = controller(tsFile, CanvasSession.new("app.ts"))
        val root = background { canvas.loadRoot() }
        val importEnd = root.text.indexOf('\n')
        val importLinks = root.links.filter { it.range.last < importEnd }
        val importedNames = importLinks.flatMap { it.targets }.mapNotNull { canvas.node(it)?.name?.substringBefore('(') }.toSet()
        assertTrue("Imports in the root file should link to their target declarations and file: $importedNames",
            importedNames.containsAll(setOf("Config", "Router", "types.ts")))

        val startCard = canvas.open(null, "start")
        val paramLink = canvas.source(startCard.id)!!.links.first {
            startCard.text.substring(it.range.first, it.range.last + 1) == "config" &&
                it.targets.any { id -> canvas.node(id)?.kind == dev.insight.graph.SymbolKind.PARAMETER }
        }
        val paramId = paramLink.targets.first { canvas.node(it)?.kind == dev.insight.graph.SymbolKind.PARAMETER }
        val paramCard = background { canvas.openSymbol(startCard.id, paramLink.range.first, paramLink.range.last + 1, paramId) }
        val paramSource = canvas.source(paramCard.id)!!
        val paramCardTargets = paramSource.links.flatMap { it.targets }
            .mapNotNull { canvas.node(it)?.name?.substringBefore('(')?.substringBefore(':')?.trim() }.toSet()
        assertTrue("Opening a parameter card should keep its type, sibling parameters and enclosing function clickable: $paramCardTargets",
            paramCardTargets.containsAll(setOf("start", "Config", "router", "Router")))
    }

    fun testPanelSummarizesGapsFollowsTheTrailTakesKeysAndAnswersGraphQuestions() {
        val file = myFixture.configureByText("Flow.java", source)
        val prompts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val services = CanvasSessionServices(null, { { request ->
            prompts += request.messages.joinToString("\n") { it.content }
            Completion("Sets x to one.", finishReason = FinishReason.COMPLETE)
        } }, summaryTools = { emptyList() }) { _, _ -> error("No chat requested") }
        val session = CanvasSession.new("Flow.java")
        val panel = CanvasSessionPanel(project, sessionFile(file, session), session, services) {}
        try {
            panel.setSize(1400, 900)
            PlatformTestUtil.waitWithEventsDispatching("The root file should be shown", { panel.ready }, 30)
            assertEquals(listOf("Flow.java"), panel.trailTitles)
            fun open(parentId: String?, name: String): CanvasCard {
                val (link, target) = panel.controller.link(panel.controller.source(parentId)!!, name)
                val job = panel.openSymbol(parentId, link, target)
                PlatformTestUtil.waitWithEventsDispatching("The card should open", { job.isCompleted }, 30)
                return panel.model.cards.last()
            }
            val entry = open(null, "entry")
            val helper = open(entry.id, "helper")
            assertEquals("Opening a card leads the trail to it", listOf("Flow.java", entry.title, helper.title), panel.trailTitles)

            // Gaps: the helper's body is code between symbols, summarized on request and shown above its lines.
            val view = panel.cardViews.getValue(helper.id)
            val source = panel.controller.source(helper.id)!!
            assertTrue(source.gaps.isNotEmpty())
            val menu = { view.menuActions().associateBy { it.name } }
            assertTrue(menu().getValue("canvas-summarize-gaps").enabled)
            assertNull(menu()["canvas-clear-gaps"])
            panel.summarizeGaps(helper.id)
            PlatformTestUtil.waitWithEventsDispatching("The gaps should be summarized",
                { !panel.summarizingGaps(helper.id) && panel.model.card(helper.id)!!.gapSummaries.isNotEmpty() }, 30)
            val gaps = panel.model.card(helper.id)!!.gapSummaries
            assertTrue(gaps.all { it.text == "Sets x to one." })
            assertTrue(prompts.any { it.contains("int x = 1;") })
            assertTrue(gaps.any { gap -> (gap.first..gap.last).any { source.text.split('\n')[it - source.firstLine].contains("int x = 1;") } })
            assertEquals(gaps, view.sourcePane!!.gapSummaries)
            assertEquals(gaps.size, view.sourcePane!!.editor.inlayModel.getBlockElementsInRange(0, source.text.length).size)
            assertFalse("Every gap is summarized", menu().getValue("canvas-summarize-gaps").enabled)
            menu().getValue("canvas-clear-gaps").run()
            assertTrue(panel.model.card(helper.id)!!.gapSummaries.isEmpty())
            assertTrue(view.sourcePane!!.editor.inlayModel.getBlockElementsInRange(0, source.text.length).isEmpty())

            // Keys: the canvas binds zoom and Tab between cards.
            val keys = panel.canvas.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
            fun press(stroke: String) = panel.canvas.actionMap.get(keys.get(javax.swing.KeyStroke.getKeyStroke(stroke)))
                .actionPerformed(java.awt.event.ActionEvent(panel.canvas, 0, stroke))
            val zoom = panel.zoom
            press("typed +")
            assertTrue(panel.zoom > zoom)
            press("typed 0")
            assertEquals(1.0, panel.zoom, 0.0)
            press("TAB")
            assertEquals("Tab wraps from the last card to the root file", listOf("Flow.java"), panel.trailTitles)
            press("TAB")
            assertEquals(listOf("Flow.java", entry.title), panel.trailTitles)
            press("shift TAB")
            assertEquals(listOf("Flow.java"), panel.trailTitles)
            panel.stepCard(forward = false)
            assertEquals(listOf("Flow.java", entry.title, helper.title), panel.trailTitles)

            // Closing the current card steps the trail back to its parent.
            panel.close(helper.id)
            assertEquals(listOf("Flow.java", entry.title), panel.trailTitles)

            PlatformTestUtil.waitWithEventsDispatching("The entry card should be summarized", { panel.model.card(entry.id)!!.summary != null }, 30)
            // The chats' graph tools read the explored graph without changing the canvas.
            val graph = panel.actions.graph!!
            val entryId = panel.controller.source(entry.id)!!.node!!
            val helperId = panel.controller.link(panel.controller.source(entry.id)!!, "helper").second
            assertTrue(graph.findSymbols("helper", null, 10).startsWith("#$helperId function helper"))
            val inspected = background { graph.inspect(entryId) }
            assertTrue(inspected, inspected.contains("Calls (1):\n  #$helperId function helper"))
            assertTrue(inspected, inspected.contains("Summary (model-written): Sets x to one."))
            assertEquals("Chain 1: #$entryId entry() → #$helperId helper()", graph.callPaths(entryId, emptyList(), helperId, 8))
            assertEquals(1, panel.model.cards.size)
        } finally {
            Disposer.dispose(panel)
        }
    }
}
