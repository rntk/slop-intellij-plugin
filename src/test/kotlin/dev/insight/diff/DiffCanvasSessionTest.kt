package dev.insight.diff

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
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
        val cards = opened.session.cards
        assertEquals(setOf("Caller.java", "Leaf.java", "Child.java"), cards.map { it.filePath.substringAfterLast('/') }.toSet())
        assertTrue(cards.all { it.wholeFile })
        val caller = cards.single { it.filePath.endsWith("Caller.java") }
        val leaf = cards.single { it.filePath.endsWith("Leaf.java") }
        assertNull(caller.parentId)
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
        assertTrue(prepared.file.session.cards.isEmpty())
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
        assertTrue(prepared.file.session.cards.isEmpty())
        for (path in listOf(stale.path, deleted.path)) {
            try {
                background { prepareDiffCanvasSession(project, snapshot, path) }
                fail("Must reject unavailable reviewed source")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("matching the reviewed after-side"))
            }
        }
    }
}
