package dev.insight.canvas

import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.awt.Container
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JViewport

class CanvasDynamicLoadingTest : LightJavaCodeInsightFixtureTestCase() {
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
        assertNotNull(opened)
        session?.let { opened!!.session = it }
        return opened!!
    }

    private fun buttons(container: Container): List<JButton> = container.components.flatMap {
        (if (it is JButton) listOf(it) else emptyList()) + (if (it is Container) buttons(it) else emptyList())
    }

    private fun labels(container: Container): List<JLabel> = container.components.flatMap {
        (if (it is JLabel) listOf(it) else emptyList()) + (if (it is Container) labels(it) else emptyList())
    }

    fun testChunkedDirectoryLoadingConnectsAcrossBatchesAndPersistsCursor() {
        val f1 = myFixture.addFileToProject("scope/F1.java", "class F1 { void call() { F5.help(); } }")
        myFixture.addFileToProject("scope/F2.java", "class F2 {}")
        myFixture.addFileToProject("scope/F3.java", "class F3 {}")
        myFixture.addFileToProject("scope/F4.java", "class F4 {}")
        myFixture.addFileToProject("scope/F5.java", "class F5 { static void help() {} }")
        myFixture.addFileToProject("scope/F6.java", "class F6 {}")
        myFixture.addFileToProject("scope/F7.java", "class F7 {}")
        myFixture.addFileToProject("scope/F8.java", "class F8 {}")

        val directory = f1.virtualFile.parent
        val opened = background { openCanvasSessionFile(project, directory, store = null) }!!
        val storePath = Files.createTempDirectory("dir-chunked")
        try {
            val store = CanvasSessionStore(storePath)
            val services = CanvasSessionServices(store, { error("No summary") }) { _, _ -> error("No chat") }
            val panel = CanvasSessionPanel(project, opened, opened.session, services, loadChunk = 3) {}
            val saved = try {
                panel.setSize(1200, 800)
                PlatformTestUtil.waitWithEventsDispatching("Directory chunk 1 should load", { panel.ready }, 30)
                assertEquals(4, panel.cardViews.size)
                assertEquals(4, panel.model.cards.size)
                assertNotNull(panel.model.session.directoryCursor)
                assertEquals(1, panel.moreCards.size)
                val loadMoreBtn = buttons(panel).single { it.name == "canvas-load-more" }
                assertTrue(loadMoreBtn.isVisible)
                assertEquals("Load more (3 / 8)", loadMoreBtn.text)

                val job = panel.loadMore()!!
                PlatformTestUtil.waitWithEventsDispatching("Directory chunk 2 should load", { job.isCompleted }, 30)
                assertEquals(7, panel.cardViews.size)
                assertEquals(7, panel.model.cards.size)
                val f1Card = panel.model.cards.single { it.title == "F1.java" }
                val f5Card = panel.model.cards.single { it.title == "F5.java" }
                assertEquals("Batch 2 file connects to Batch 1 caller", f1Card.id, f5Card.parentId)
                assertNotNull(panel.model.session.directoryCursor)
                assertTrue(store.save(panel.model.session))
                panel.model.session
            } finally {
                Disposer.dispose(panel)
            }

            val restored = store.load(saved.id)!!
            assertEquals(saved.directoryCursor, restored.directoryCursor)
            val reopened = CanvasSessionPanel(project, opened, restored, services, loadChunk = 10) {}
            try {
                reopened.setSize(1200, 800)
                PlatformTestUtil.waitWithEventsDispatching("Reopened directory should load", { reopened.ready }, 30)
                assertEquals(7, reopened.cardViews.size)
                assertEquals(2, reopened.controller.pendingDirectoryCount)
                assertEquals(1, reopened.moreCards.size)

                val finalJob = reopened.loadMore()!!
                PlatformTestUtil.waitWithEventsDispatching("Remaining directory files should load", { finalJob.isCompleted }, 30)
                assertEquals(9, reopened.cardViews.size)
                assertNull(reopened.model.session.directoryCursor)
                assertTrue(reopened.moreCards.isEmpty())
                assertFalse(buttons(reopened).single { it.name == "canvas-load-more" }.isVisible)
            } finally {
                Disposer.dispose(reopened)
            }
        } finally {
            storePath.toFile().deleteRecursively()
        }
    }

    fun testChunkedSavedSessionLoadsOnDemandForJumpAndToolsAndRefreshesLoadedOnly() {
        val code = """
            class Flow {
                void entry() { a(); b(); c(); d(); }
                void a() { a1(); }
                void a1() { a2(); }
                void a2() { int v = 1; }
                void b() { b1(); }
                void b1() { int v = 2; }
                void c() { c1(); }
                void c1() { c2(); }
                void c2() { int v = 3; }
                void d() { int v = 4; }
            }
        """.trimIndent()
        val file = myFixture.configureByText("Flow.java", code)
        val initialOpened = sessionFile(file)
        val builderModel = CanvasSessionModel(CanvasSession.new("Flow.java"))
        val builder = CanvasSessionController(project, file.virtualFile, initialOpened.exploration, initialOpened.root, builderModel)
        Disposer.register(testRootDisposable, builder)
        background { builder.loadRoot() }
        fun openChild(parentId: String, name: String): CanvasCard {
            val src = builder.source(parentId)!!
            val link = src.links.first { l -> l.targets.any { builder.node(it)?.name?.substringBefore('(') == name } }
            val target = link.targets.first { builder.node(it)?.name?.substringBefore('(') == name }
            return background { builder.openSymbol(parentId, link.range.first, link.range.last + 1, target) }
        }
        val a = openChild(CANVAS_ROOT, "a")
        val b = openChild(CANVAS_ROOT, "b")
        val c = openChild(CANVAS_ROOT, "c")
        val d = openChild(CANVAS_ROOT, "d")
        val a1 = openChild(a.id, "a1")
        val a2 = openChild(a1.id, "a2")
        val b1 = openChild(b.id, "b1")
        val c1 = openChild(c.id, "c1")
        val c2 = openChild(c1.id, "c2")
        val savedSession = builderModel.session
        assertEquals(10, savedSession.cards.size)

        val services = CanvasSessionServices(null, { error("No summary") }) { _, _ -> error("No chat") }
        val panel = CanvasSessionPanel(project, sessionFile(file, savedSession), savedSession, services, loadChunk = 3) {}
        try {
            panel.setSize(1200, 800)
            PlatformTestUtil.waitWithEventsDispatching("Initial chunk should load", { panel.ready }, 30)
            assertEquals("Only root + 3 initial cards are mounted", 4, panel.cardViews.size)
            assertEquals("All 10 cards remain in session model", 10, panel.model.cards.size)
            assertTrue(setOf(CANVAS_ROOT, a.id, b.id, c.id).all { it in panel.cardViews })
            assertFalse(d.id in panel.cardViews)
            assertEquals(setOf("more:$CANVAS_ROOT", "more:${a.id}", "more:${b.id}", "more:${c.id}"), panel.moreCards.keys)

            // Branch-specific loadMore loads only b's deferred subtree.
            val branchJob = panel.loadMore(b.id)!!
            PlatformTestUtil.waitWithEventsDispatching("Branch b should load", { branchJob.isCompleted }, 30)
            assertTrue(b1.id in panel.cardViews)
            assertFalse(a1.id in panel.cardViews)
            assertFalse("more:${b.id}" in panel.moreCards)

            // Edit file and refresh: refreshes loaded subset only while leaving deferred c1/c2 unloaded.
            WriteCommandAction.runWriteCommandAction(project) {
                val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
                doc.setText(code.replace("int v = 1;", "int v = 99;"))
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
            val refreshJob = panel.refresh()!!
            PlatformTestUtil.waitWithEventsDispatching("Refresh should finish", { refreshJob.isCompleted && panel.ready }, 30)
            assertFalse(c1.id in panel.cardViews)
            assertFalse(c2.id in panel.cardViews)

            // On-demand chain load for deep deferred card a2.
            val loadedA2 = background { panel.ensureCardLoaded(a2.id) }
            assertTrue(loadedA2)
            assertTrue(a1.id in panel.cardViews)
            assertTrue(a2.id in panel.cardViews)
            assertTrue(panel.controller.source(a2.id)!!.text.contains("int v = 99;"))
            assertFalse("more:${a.id}" in panel.moreCards)

            // LLM tools dynamically load deferred card c2 when queried/shown.
            val tools = canvasTools(panel.actions, CANVAS_ROOT)
            val showArgs = JsonObject().apply { addProperty("card", c2.id) }
            val shownMsg = background { tools.first { it.definition.name == "show_card" }.call(showArgs) }
            assertTrue(shownMsg.contains(c2.id))
            assertTrue(c1.id in panel.cardViews)
            assertTrue(c2.id in panel.cardViews)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testChunkedLinkedFilesDefersRemainingAndLoadsViaPanel() {
        val root = myFixture.addFileToProject("LRoot.java", "class LRoot {}")
        val f1 = myFixture.addFileToProject("L1.java", "class L1 {}")
        val f2 = myFixture.addFileToProject("L2.java", "class L2 {}")
        val f3 = myFixture.addFileToProject("L3.java", "class L3 {}")
        val f4 = myFixture.addFileToProject("L4.java", "class L4 {}")

        val prepared = background {
            prepareLinkedFilesCanvas(project, listOf(root.virtualFile, f1.virtualFile, f2.virtualFile, f3.virtualFile, f4.virtualFile),
                "Linked", initialLimit = 2)
        }!!
        assertEquals(3, prepared.file.session.cards.size)
        assertEquals(2, prepared.file.session.pendingFiles.size)

        val services = CanvasSessionServices(null, { error("No summary") }) { _, _ -> error("No chat") }
        val panel = CanvasSessionPanel(project, prepared.file, prepared.file.session, services, loadChunk = 2) {}
        try {
            panel.setSize(1200, 800)
            PlatformTestUtil.waitWithEventsDispatching("Linked files session should load", { panel.ready }, 30)
            assertEquals(3, panel.cardViews.size)
            assertEquals(1, panel.moreCards.size)
            val job = panel.loadMore()!!
            PlatformTestUtil.waitWithEventsDispatching("Pending linked files should load", { job.isCompleted }, 30)
            assertEquals(5, panel.cardViews.size)
            assertTrue(panel.moreCards.isEmpty())
            assertTrue(panel.model.session.pendingFiles.isEmpty())
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testPendingFilesPersistPerSessionAndDoNotLeakAcrossSessionSwitches() {
        val root = myFixture.addFileToProject("PRoot.java", "class PRoot {}")
        val f1 = myFixture.addFileToProject("P1.java", "class P1 {}")
        val f2 = myFixture.addFileToProject("P2.java", "class P2 {}")
        val f3 = myFixture.addFileToProject("P3.java", "class P3 {}")

        val prepared = background {
            prepareLinkedFilesCanvas(project, listOf(root.virtualFile, f1.virtualFile, f2.virtualFile, f3.virtualFile),
                "Linked", initialLimit = 1)
        }!!
        assertEquals(2, prepared.file.session.cards.size)
        assertEquals(2, prepared.file.session.pendingFiles.size)

        val storePath = Files.createTempDirectory("pending-sessions")
        try {
            val store = CanvasSessionStore(storePath)
            val services = CanvasSessionServices(store, { error("No summary") }) { _, _ -> error("No chat") }
            val panel1 = CanvasSessionPanel(project, prepared.file, prepared.file.session, services, loadChunk = 1) {}
            try {
                panel1.setSize(1200, 800)
                PlatformTestUtil.waitWithEventsDispatching("Initial linked session should load", { panel1.ready }, 30)
                val job1 = panel1.loadMore()!!
                PlatformTestUtil.waitWithEventsDispatching("First pending batch should load", { job1.isCompleted }, 30)
                assertEquals(3, panel1.cardViews.size)
                assertEquals(1, panel1.controller.pendingLinkedFiles.size)
            } finally {
                Disposer.dispose(panel1)
            }

            // Switching to an unrelated session on the same CanvasSessionFile must not inherit pendingFiles.
            val unrelated = CanvasSession.new(prepared.file.rootPath, "Unrelated")
            val panelOther = CanvasSessionPanel(project, prepared.file, unrelated, services, loadChunk = 1) {}
            try {
                panelOther.setSize(1200, 800)
                PlatformTestUtil.waitWithEventsDispatching("Unrelated session should load", { panelOther.ready }, 30)
                assertTrue(panelOther.controller.pendingLinkedFiles.isEmpty())
                assertTrue(panelOther.moreCards.isEmpty())
                assertEquals(1, panelOther.cardViews.size)
            } finally {
                Disposer.dispose(panelOther)
            }

            // Reopening the first session from store preserves only the remaining unconsumed pending file.
            val reloaded = store.load(prepared.file.session.id)!!
            assertEquals(3, reloaded.cards.size)
            assertEquals(1, reloaded.pendingFiles.size)
            val panelReopened = CanvasSessionPanel(project, prepared.file, reloaded, services, loadChunk = 10) {}
            try {
                panelReopened.setSize(1200, 800)
                PlatformTestUtil.waitWithEventsDispatching("Reopened linked session should load", { panelReopened.ready }, 30)
                assertEquals(3, panelReopened.cardViews.size)
                assertEquals(1, panelReopened.moreCards.size)
                val job2 = panelReopened.loadMore()!!
                PlatformTestUtil.waitWithEventsDispatching("Remaining pending file should load", { job2.isCompleted }, 30)
                assertEquals(4, panelReopened.cardViews.size)
                assertTrue(panelReopened.controller.pendingLinkedFiles.isEmpty())
                assertTrue(panelReopened.moreCards.isEmpty())
            } finally {
                Disposer.dispose(panelReopened)
            }
        } finally {
            storePath.toFile().deleteRecursively()
        }
    }

    fun testPendingLinkedFilesRetainRetryableErrorsAndConsumeUnavailable() {
        val root = myFixture.addFileToProject("RRoot.java", "class RRoot { int x = 1; }")
        val f1 = myFixture.addFileToProject("R1.java", "class R1 {}")
        val f2 = myFixture.addFileToProject("R2.java", "class R2 {}")
        val notes = myFixture.addFileToProject("notes.txt", "plain text")
        val f3 = myFixture.addFileToProject("R3.java", "class R3 {}")

        val prepared = background {
            prepareLinkedFilesCanvas(
                project,
                listOf(root.virtualFile, f1.virtualFile, f2.virtualFile, notes.virtualFile, f3.virtualFile),
                "Retryable",
                initialLimit = 1,
            )
        }!!
        assertEquals(3, prepared.file.session.pendingFiles.size)

        val services = CanvasSessionServices(null, { error("No summary") }) { _, _ -> error("No chat") }
        val panel = CanvasSessionPanel(project, prepared.file, prepared.file.session, services, loadChunk = 2) {}
        try {
            panel.setSize(1200, 800)
            PlatformTestUtil.waitWithEventsDispatching("Initial session should load", { panel.ready }, 30)

            WriteCommandAction.runWriteCommandAction(project) {
                val doc = PsiDocumentManager.getInstance(project).getDocument(root)!!
                doc.setText("class RRoot { int x = 2; }")
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }

            val staleJob = panel.loadMore()!!
            PlatformTestUtil.waitWithEventsDispatching("Stale loadMore should finish", { staleJob.isCompleted }, 30)
            assertEquals("Retryable error keeps all pending files", 3, panel.controller.pendingLinkedFiles.size)
            assertEquals(2, panel.cardViews.size)
            assertTrue("Continuation card stays visible", panel.moreCards.isNotEmpty())
            val staleStatus = labels(panel).single { it.name == "canvas-status" }.text
            assertTrue(staleStatus, staleStatus.contains("Refresh the canvas"))

            val refreshJob = panel.refresh()!!
            PlatformTestUtil.waitWithEventsDispatching("Refresh should finish", { refreshJob.isCompleted && panel.ready }, 30)

            val batchJob = panel.loadMore()!!
            PlatformTestUtil.waitWithEventsDispatching("Retried loadMore should finish", { batchJob.isCompleted }, 30)
            assertEquals("R2 opens; notes.txt is skipped as unavailable", 3, panel.cardViews.size)
            assertEquals(1, panel.controller.pendingLinkedFiles.size)
            val unavailableStatus = labels(panel).single { it.name == "canvas-status" }.text
            assertTrue(unavailableStatus, unavailableStatus.contains("Not shown:") && unavailableStatus.contains("notes.txt"))

            val finalJob = panel.loadMore()!!
            PlatformTestUtil.waitWithEventsDispatching("Final pending file should load", { finalJob.isCompleted }, 30)
            assertEquals(4, panel.cardViews.size)
            assertTrue(panel.controller.pendingLinkedFiles.isEmpty())
            assertTrue(panel.moreCards.isEmpty())
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testDirectoryBatchStillOpensWholeFileWhenExcerptCardIsOpen() {
        val f1 = myFixture.addFileToProject("scope2/F1.java", "class F1 { void call() { F5.help(); } }")
        myFixture.addFileToProject("scope2/F2.java", "class F2 {}")
        myFixture.addFileToProject("scope2/F5.java", "class F5 { static void help() {} }")

        val directory = f1.virtualFile.parent
        val opened = background { openCanvasSessionFile(project, directory, store = null) }!!
        val services = CanvasSessionServices(null, { error("No summary") }) { _, _ -> error("No chat") }
        val panel = CanvasSessionPanel(project, opened, opened.session, services, loadChunk = 2) {}
        try {
            panel.setSize(1200, 800)
            PlatformTestUtil.waitWithEventsDispatching("Directory batch 1 should load", { panel.ready }, 30)
            val f1Card = panel.model.cards.single { it.title == "F1.java" }
            val f1Source = panel.controller.source(f1Card.id)!!
            val helpLink = f1Source.links.first { l -> l.targets.any { panel.controller.node(it)?.name?.startsWith("help") == true } }
            val helpTarget = helpLink.targets.first { panel.controller.node(it)?.name?.startsWith("help") == true }
            val openJob = panel.openSymbol(f1Card.id, helpLink, helpTarget)
            PlatformTestUtil.waitWithEventsDispatching("Excerpt card should open", { openJob.isCompleted }, 30)
            assertFalse(panel.model.cards.last().wholeFile)

            val moreJob = panel.loadMore()!!
            PlatformTestUtil.waitWithEventsDispatching("Directory batch 2 should load", { moreJob.isCompleted }, 30)
            assertTrue("Whole-file card for F5.java must still be created",
                panel.model.cards.any { it.wholeFile && it.filePath.endsWith("F5.java") })
            assertEquals(1, panel.model.cards.count { it.wholeFile && it.filePath.endsWith("F5.java") })
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testDirectoryBatchReconcilesClosedParentBeforeAppending() {
        val f1 = myFixture.addFileToProject("scope3/A.java", "class A { void call() { C.help(); } }")
        myFixture.addFileToProject("scope3/B.java", "class B {}")
        myFixture.addFileToProject("scope3/C.java", "class C { static void help() {} }")

        val directory = f1.virtualFile.parent
        val opened = background { openCanvasSessionFile(project, directory, store = null) }!!
        val model = CanvasSessionModel(opened.session)
        val controller = CanvasSessionController(project, opened.source, opened.exploration, opened.root, model)
        Disposer.register(testRootDisposable, controller)

        background {
            controller.loadRoot()
            controller.initializeDirectory(limit = 2) { _, _ -> }
        }
        val aCard = model.cards.single { it.title == "A.java" }
        var removed = false
        val added = background {
            controller.loadMoreDirectoryFiles(limit = 1) { _, _ ->
                if (!removed) {
                    removed = true
                    model.remove(aCard.id)
                }
            }
        }
        val cCard = added.single()
        assertEquals(CANVAS_ROOT, cCard.parentId)
        assertEquals(CANVAS_ROOT, model.card(cCard.id)!!.parentId)
    }

    fun testDeferredCardViewMountsWithUpdatedSnapshotMetadataAfterRestore() {
        val code = """
            class Moved {
                void entry() { a(); b(); }
                void a() {}
                void b() {
                    int v = 1;
                }
            }
        """.trimIndent()
        val file = myFixture.configureByText("Moved.java", code)
        val initialOpened = sessionFile(file)
        val builderModel = CanvasSessionModel(CanvasSession.new("Moved.java"))
        val builder = CanvasSessionController(project, file.virtualFile, initialOpened.exploration, initialOpened.root, builderModel)
        Disposer.register(testRootDisposable, builder)
        background { builder.loadRoot() }
        fun openChild(name: String): CanvasCard {
            val src = builder.source(CANVAS_ROOT)!!
            val link = src.links.first { l -> l.targets.any { builder.node(it)?.name?.substringBefore('(') == name } }
            val target = link.targets.first { builder.node(it)?.name?.substringBefore('(') == name }
            return background { builder.openSymbol(CANVAS_ROOT, link.range.first, link.range.last + 1, target) }
        }
        openChild("a")
        val b = openChild("b")
        val savedSession = builderModel.session

        WriteCommandAction.runWriteCommandAction(project) {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            doc.setText(code.replace("void b()", "\n\n\n\n\n    void b()"))
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        }

        val services = CanvasSessionServices(null, { error("No summary") }) { _, _ -> error("No chat") }
        val panel = CanvasSessionPanel(project, sessionFile(file, savedSession), savedSession, services, loadChunk = 1) {}
        try {
            panel.setSize(1200, 800)
            PlatformTestUtil.waitWithEventsDispatching("Initial chunk should load", { panel.ready }, 30)
            assertFalse(b.id in panel.cardViews)

            assertTrue(background { panel.ensureCardLoaded(b.id) })
            val updated = panel.model.card(b.id)!!
            assertEquals(b.firstLine + 5, updated.firstLine)
            assertEquals(updated.firstLine, panel.cardViews.getValue(b.id).card.firstLine)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testHibernatingAndMaterializingFoldedDiffCardPreservesFoldState() {
        val code = """
            class DiffFold {
                void entry() { helper(); }
                void helper() {
                    int x = 1;
                }
            }
        """.trimIndent()
        val file = myFixture.configureByText("DiffFold.java", code)
        val plain = sessionFile(file)
        val patch = "@@ -4,1 +4,1 @@\n-        int x = 0;\n+        int x = 1;\n"
        val opened = CanvasSessionFile(plain.source, plain.rootPath, plain.exploration, plain.root, plain.session,
            mapOf(file.virtualFile.path to patch))
        val services = CanvasSessionServices(null, { error("No summary") }) { _, _ -> error("No chat") }
        val panel = CanvasSessionPanel(project, opened, plain.session, services) {}
        try {
            panel.setSize(1200, 800)
            PlatformTestUtil.waitWithEventsDispatching("Canvas should open", { panel.ready }, 30)
            val rootSrc = panel.controller.source(CANVAS_ROOT)!!
            val link = rootSrc.links.first { l -> l.targets.any { panel.controller.node(it)?.name?.startsWith("helper") == true } }
            val target = link.targets.first { panel.controller.node(it)?.name?.startsWith("helper") == true }
            val job = panel.openSymbol(CANVAS_ROOT, link, target)
            PlatformTestUtil.waitWithEventsDispatching("Helper card should open", { job.isCompleted }, 30)

            val helperId = panel.model.cards.last().id
            val view = panel.cardViews.getValue(helperId)
            val section = view.components.filterIsInstance<CanvasSourceSection>().single()
            fun click(label: JLabel) = label.mouseListeners.forEach {
                it.mouseClicked(MouseEvent(label, MouseEvent.MOUSE_CLICKED, 0, InputEvent.BUTTON1_DOWN_MASK, 1, 1, 1, false, MouseEvent.BUTTON1))
            }
            click(section.diffTab)
            assertTrue(section.showsDiff)
            click(section.diffTab)
            assertTrue(CardSection.SOURCE in view.card.folded)
            assertFalse(section.body.isVisible)

            var modelChanges = 0
            val sub = panel.model.subscribe { modelChanges++ }
            try {
                view.hibernateSource()
                assertTrue(view.hibernated)
                view.materializeSource()
                assertFalse(view.hibernated)
                assertEquals("Materializing must not modify the model", 0, modelChanges)
                assertTrue(CardSection.SOURCE in panel.model.card(helperId)!!.folded)
                assertTrue(section.showsDiff)
                assertFalse(section.body.isVisible)
                assertNotNull(view.diffPane)
            } finally {
                sub.dispose()
            }
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testViewportEditorHibernationFreesOffscreenEditorsAndRematerializesOnFocus() {
        val code = buildString {
            append("class Many {\n")
            for (i in 1..7) {
                append("    void m$i() {\n")
                for (line in 1..15) append("        int x_${i}_$line = $line;\n")
                append("    }\n")
            }
            append("}\n")
        }
        val file = myFixture.configureByText("Many.java", code)
        val services = CanvasSessionServices(null, { error("No summary") }) { _, _ -> error("No chat") }
        val session = CanvasSession.new("Many.java")
        val panel = CanvasSessionPanel(
            project,
            sessionFile(file, session),
            session,
            services,
            loadChunk = 20,
            activeEditorLimit = 4,
            activeEditorTarget = 3,
        ) {}
        try {
            panel.setSize(1200, 450)
            PlatformTestUtil.waitWithEventsDispatching("Root should load", { panel.ready }, 30)
            val rootSource = panel.controller.source(CANVAS_ROOT)!!
            for (i in 1..7) {
                val link = rootSource.links.first { l -> l.targets.any { panel.controller.node(it)?.name?.substringBefore('(') == "m$i" } }
                val target = link.targets.first { panel.controller.node(it)?.name?.substringBefore('(') == "m$i" }
                val job = panel.openSymbol(CANVAS_ROOT, link, target)
                PlatformTestUtil.waitWithEventsDispatching("Card m$i should open", { job.isCompleted }, 30)
            }
            fun relayout(container: Container) {
                container.doLayout()
                container.components.filterIsInstance<Container>().forEach(::relayout)
            }
            repeat(2) { relayout(panel) }
            val viewport = panel.canvas.parent as JViewport
            viewport.viewPosition = Point(0, 0)
            panel.reconcileActiveEditors()

            val materializedCount = panel.cardViews.values.count { !it.hibernated && it.sourcePane != null }
            assertTrue("Active editors ($materializedCount) must not exceed limit 4", materializedCount <= 4)
            assertFalse("Root never hibernates", panel.cardViews.getValue(CANVAS_ROOT).hibernated)
            val hibernatedView = panel.cardViews.values.firstOrNull { it.hibernated }
            assertNotNull("Far off-screen cards should hibernate", hibernatedView)
            assertNull(hibernatedView!!.sourcePane)

            background { panel.actions.show(hibernatedView.id) }
            assertFalse("Showing a hibernated card must re-materialize its editor", hibernatedView.hibernated)
            assertNotNull(hibernatedView.sourcePane)
            assertEquals(panel.controller.source(hibernatedView.id)!!.text, hibernatedView.sourcePane!!.source.text)
        } finally {
            Disposer.dispose(panel)
        }
    }

    fun testLinkedFilesCanvasWithLinesScrollsRootChildAndDeferredCards() {
        fun longClass(name: String): String = buildString {
            append("class $name {\n")
            for (i in 1..100) append("    int item$i = $i;\n")
            append("}\n")
        }
        val root = myFixture.addFileToProject("LLRoot.java", longClass("LLRoot"))
        val f1 = myFixture.addFileToProject("LL1.java", longClass("LL1"))
        val f2 = myFixture.addFileToProject("LL2.java", longClass("LL2"))

        val prepared = background {
            prepareLinkedFilesCanvas(
                project,
                listOf(
                    CanvasLinkedFile(root.virtualFile, line = 70),
                    CanvasLinkedFile(f1.virtualFile, line = 55),
                    CanvasLinkedFile(f2.virtualFile, line = 80),
                ),
                "Linked with lines",
                initialLimit = 1,
            )
        }!!
        assertEquals(69, prepared.file.session.root.symbol?.line)
        assertEquals(54, prepared.file.session.cards.last().symbol?.line)
        assertEquals(79, prepared.file.session.pendingFiles.single().line)

        val storePath = Files.createTempDirectory("linked-lines-session")
        try {
            val store = CanvasSessionStore(storePath)
            assertTrue(store.save(prepared.file.session))
            val reloaded = store.load(prepared.file.session.id)!!
            assertEquals(69, reloaded.root.symbol?.line)
            assertEquals(54, reloaded.cards.last().symbol?.line)
            assertEquals(79, reloaded.pendingFiles.single().line)

            val services = CanvasSessionServices(store, { error("No summary") }) { _, _ -> error("No chat") }
            val panel = CanvasSessionPanel(project, prepared.file, reloaded, services, loadChunk = 1) {}
            try {
                panel.setSize(1200, 600)
                PlatformTestUtil.waitWithEventsDispatching("Linked session should load", { panel.ready }, 30)
                fun relayout(container: Container) {
                    container.doLayout()
                    container.components.filterIsInstance<Container>().forEach(::relayout)
                }
                repeat(2) { relayout(panel) }
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

                val rootPane = panel.cardViews.getValue(CANVAS_ROOT).sourcePane!!
                assertEquals(69, rootPane.editor.document.getLineNumber(rootPane.pickedRange!!.first))
                assertTrue("Root card should scroll to line 70", rootPane.editor.scrollingModel.visibleArea.y > 0)

                val childView = panel.cardViews.values.single { it.card.filePath.endsWith("LL1.java") }
                childView.materializeSource()
                repeat(2) { relayout(panel) }
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                val childPane = childView.sourcePane!!
                assertEquals(54, childPane.editor.document.getLineNumber(childPane.pickedRange!!.first))
                assertTrue("Child card should scroll to line 55", childPane.editor.scrollingModel.visibleArea.y > 0)

                val job = panel.loadMore()!!
                PlatformTestUtil.waitWithEventsDispatching("Pending card should load", { job.isCompleted }, 30)
                val deferredView = panel.cardViews.values.single { it.card.filePath.endsWith("LL2.java") }
                deferredView.materializeSource()
                repeat(2) { relayout(panel) }
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                val deferredPane = deferredView.sourcePane!!
                assertEquals(79, deferredPane.editor.document.getLineNumber(deferredPane.pickedRange!!.first))
                assertTrue("Deferred card should scroll to line 80", deferredPane.editor.scrollingModel.visibleArea.y > 0)
            } finally {
                Disposer.dispose(panel)
            }
        } finally {
            storePath.toFile().deleteRecursively()
        }
    }
}
