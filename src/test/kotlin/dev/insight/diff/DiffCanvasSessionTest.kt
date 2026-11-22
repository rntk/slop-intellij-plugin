package dev.insight.diff

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import dev.insight.canvas.CANVAS_ROOT
import dev.insight.canvas.CanvasSessionController
import dev.insight.canvas.CanvasSessionModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class DiffCanvasSessionTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() = IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    override fun setUp() {
        super.setUp()
        PsiTestUtil.addSourceRoot(module, myFixture.tempDirFixture.findOrCreateDir(""))
    }

    private fun <T> background(block: suspend () -> T): T {
        val task = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Diff canvas work should finish", { task.isCompleted }, 60)
            return runBlocking { task.await() }.getOrThrow()
        } finally { task.cancel() }
    }

    private fun file(path: String, text: String): ReviewFile {
        val psi = myFixture.addFileToProject(path, text)
        return ReviewFile(path, psi.virtualFile.path, null, "modified", "", path, "", psi.text)
    }

    fun testIncomingCallsTransitiveReferencesAndInheritanceOpenClickableWholeFiles() {
        val files = listOf(
            file("Root.java", "class Root { static void run() {} }"),
            file("Caller.java", "class Caller { void call() { Root.run(); } Leaf value; }"),
            file("Leaf.java", "class Leaf {}"),
            file("Child.java", "class Child extends Root {}"),
            file("Unrelated.java", "class Unrelated {}"),
        )
        val prepared = background { prepareDiffCanvasSession(project, ReviewSnapshot(files, emptyList()), "Root.java") }
        assertEquals(emptyList<String>(), prepared.unavailable)
        assertEquals(files.map { it.absolutePath }.toSet(), prepared.file.diffs.keys)
        val opened = prepared.file
        val cards = opened.session.cards.drop(1)
        assertEquals(setOf("Caller.java", "Leaf.java", "Child.java"), cards.map { it.filePath.substringAfterLast('/') }.toSet())
        assertTrue(cards.all { it.wholeFile })
        val caller = cards.single { it.filePath.endsWith("Caller.java") }
        val leaf = cards.single { it.filePath.endsWith("Leaf.java") }
        assertEquals(CANVAS_ROOT, caller.parentId)
        assertEquals(caller.id, leaf.parentId)

        val controller = CanvasSessionController(project, opened.source, opened.exploration, opened.root, CanvasSessionModel(opened.session))
        Disposer.register(testRootDisposable, controller)
        background { controller.loadRoot(); controller.restore() }
        assertTrue(controller.source(caller.id)!!.links.isNotEmpty())
        val rootLink = controller.source(caller.id)!!.links.first { link ->
            link.targets.any { controller.node(it)?.filePath == files.first().absolutePath }
        }
        val target = rootLink.targets.first { controller.node(it)?.filePath == files.first().absolutePath }
        val symbol = background { controller.openSymbol(caller.id, rootLink.range.first, rootLink.range.last + 1, target) }
        assertEquals(caller.id, symbol.parentId)
        assertEquals(files.first().absolutePath, controller.source(symbol.id)!!.file?.path)
    }

    fun testOnlyChangedFilesConnectAndUnusedImportsDoNotConnect() {
        val root = file("p/Root.java", "package p; public class Root { public static void run() {} }")
        file("Bridge.java", "class Bridge { static void go() { p.Root.run(); } }")
        val other = file("Other.java", "class Other { void go() { Bridge.go(); } }")
        val imported = file("Imported.java", "import p.Root; class Imported {}")
        val prepared = background { prepareDiffCanvasSession(project, ReviewSnapshot(listOf(root, other, imported), emptyList()), root.path) }
        assertEquals(listOf(CANVAS_ROOT), prepared.file.session.cards.map { it.id })
    }

    fun testStaleDeletedAndUnsupportedFilesAreReportedAndCannotContributeRelations() {
        val root = file("Root.java", "class Root { static void run() {} }")
        val stale = file("Stale.java", "class Stale { void call() { Root.run(); } }")
        val unsupported = file("notes.txt", "Root.run()")
        val deleted = ReviewFile("Gone.java", "/missing/Gone.java", null, "deleted", "", "gone", "class Gone {}", "")
        val psi = myFixture.findFileInTempDir(stale.path)
        WriteCommandAction.runWriteCommandAction(project) {
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(psi)!!
            document.insertString(0, "// unsaved change\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val snapshot = ReviewSnapshot(listOf(root, stale, unsupported, deleted), emptyList())
        val prepared = background { prepareDiffCanvasSession(project, snapshot, root.path) }
        assertEquals(setOf(stale.path, unsupported.path, deleted.path), prepared.unavailable.toSet())
        // Stale and deleted sources would number another text; the unsupported file's patch shows only if it is opened.
        assertEquals(setOf(root.absolutePath, unsupported.absolutePath), prepared.file.diffs.keys)
        assertEquals(listOf(CANVAS_ROOT), prepared.file.session.cards.map { it.id })
        for (path in listOf(stale.path, deleted.path)) {
            try {
                background { prepareDiffCanvasSession(project, snapshot, path) }
                fail("Must reject unavailable reviewed source")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("matching the reviewed after-side"))
            }
        }
    }

    fun testChunkedDiffCanvasDefersRemainingFilesAndLoadsThemViaPanel() {
        val files = listOf(
            file("Root.java", "class Root { static void run() {} }"),
            file("C1.java", "class C1 { void a() { Root.run(); } }"),
            file("C2.java", "class C2 { void a() { Root.run(); } C2Leaf leaf; }"),
            file("C2Leaf.java", "class C2Leaf {}"),
            file("C3.java", "class C3 { void a() { Root.run(); } }"),
        )
        val snapshot = ReviewSnapshot(files, emptyList())
        val prepared = background { prepareDiffCanvasSession(project, snapshot, "Root.java", initialLimit = 2) }
        assertEquals(3, prepared.file.session.cards.size)
        assertEquals(2, prepared.file.session.pendingFiles.size)

        val storePath = java.nio.file.Files.createTempDirectory("diff-pending")
        try {
            val store = dev.insight.canvas.CanvasSessionStore(storePath)
            assertTrue(store.save(prepared.file.session))
            val restored = store.load(prepared.file.session.id)!!
            assertEquals(prepared.file.session.pendingFiles, restored.pendingFiles)

            val services = dev.insight.canvas.CanvasSessionServices(store, { error("No summary") }) { _, _ -> error("No chat") }
            val panel = dev.insight.canvas.CanvasSessionPanel(project, prepared.file, restored, services, loadChunk = 2) {}
            try {
                panel.setSize(1200, 800)
                PlatformTestUtil.waitWithEventsDispatching("Diff canvas should open", { panel.ready }, 30)
                assertEquals(3, panel.cardViews.size)
                assertTrue(panel.moreCards.isNotEmpty())

                val job = panel.loadMore()!!
                PlatformTestUtil.waitWithEventsDispatching("Pending diff files should load", { job.isCompleted }, 30)
                assertEquals(5, panel.cardViews.size)
                assertTrue(panel.moreCards.isEmpty())
                assertTrue(panel.model.session.pendingFiles.isEmpty())
                val leaf = panel.model.cards.single { it.filePath.endsWith("C2Leaf.java") }
                val c2 = panel.model.cards.single { it.filePath.endsWith("C2.java") }
                assertEquals(c2.id, leaf.parentId)
            } finally {
                Disposer.dispose(panel)
            }
        } finally {
            storePath.toFile().deleteRecursively()
        }
    }

    fun testPrepareDiffCanvasSessionWithLineSetsRootTargetLine() {
        val code = buildString {
            append("class DiffTarget {\n")
            for (i in 1..60) append("    int line$i = $i;\n")
            append("}\n")
        }
        val target = file("DiffTarget.java", code)
        val prepared = background {
            prepareDiffCanvasSession(project, ReviewSnapshot(listOf(target), emptyList()), "DiffTarget.java", line = 42)
        }
        assertEquals(41, prepared.file.initialLine)
        assertEquals(41, prepared.file.session.root.symbol?.line)
    }
}
