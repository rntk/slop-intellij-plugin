package dev.insight.review

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class TreeFileConnectionIndexTest : LightJavaCodeInsightFixtureTestCase() {
    fun testCancellingOneWaiterKeepsSharedBuildAlive() {
        val file = myFixture.addFileToProject("A.java", "class A {}")
        val executor = Executors.newSingleThreadExecutor()
        val blocked = CountDownLatch(1)
        executor.execute { blocked.await() }
        val dispatcher = executor.asCoroutineDispatcher()
        val owner = SupervisorJob()
        val scope = CoroutineScope(owner + dispatcher)
        val waiters = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val index = TreeFileConnectionIndex(project, mapOf("A.java" to file.virtualFile.path), scope)
        try {
            val first = waiters.async(start = CoroutineStart.UNDISPATCHED) { index.links() }
            val indexJob = owner.children.single()
            val build = indexJob.children.single()
            first.cancel()
            val second = waiters.async(start = CoroutineStart.UNDISPATCHED) { index.links() }
            assertTrue(build.isActive)
            assertSame(build, indexJob.children.single())
            blocked.countDown()
            PlatformTestUtil.waitWithEventsDispatching("Remaining waiter should receive the graph", { second.isCompleted }, 20)
            assertTrue(runBlocking { second.await() }.containsKey("A.java"))
        } finally {
            blocked.countDown()
            index.cancel()
            waiters.cancel()
            scope.cancel()
            dispatcher.close()
        }
    }

    fun testConcurrentRequestsShareSnapshotAndSourceEditInvalidatesIt() {
        val aText = "class A { void call() { B.run(); } }"
        val bText = "class B { static void run() {} }"
        val a = myFixture.addFileToProject("A.java", aText)
        val b = myFixture.addFileToProject("B.java", bText)
        val notes = myFixture.addFileToProject("notes.txt", "before")
        val sources = mapOf("A.java" to a.virtualFile.path, "B.java" to b.virtualFile.path)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val index = TreeFileConnectionIndex(project, sources, scope)
        try {
            val first = scope.async { index.links() }
            val second = scope.async { index.links() }
            PlatformTestUtil.waitWithEventsDispatching("Concurrent requests should resolve", { first.isCompleted && second.isCompleted }, 20)
            val before = runBlocking { first.await() }
            assertSame(before, runBlocking { second.await() })
            assertTrue(before.getValue("A.java").outgoing.containsKey("B.java"))
            assertSame(before.getValue("A.java").outgoing.getValue("B.java"), before.getValue("B.java").incoming.getValue("A.java"))

            val cached = scope.async { index.links() }
            PlatformTestUtil.waitWithEventsDispatching("Cached request should resolve", { cached.isCompleted }, 20)
            assertSame(before, runBlocking { cached.await() })

            WriteCommandAction.runWriteCommandAction(project) {
                val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(notes))
                document.setText("after")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            val unrelated = scope.async { index.links() }
            PlatformTestUtil.waitWithEventsDispatching("Unrelated edit should keep the graph", { unrelated.isCompleted }, 20)
            assertSame(before, runBlocking { unrelated.await() })

            WriteCommandAction.runWriteCommandAction(project) {
                val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(a))
                document.setText("class A { void call() {} }")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
            val changed = scope.async { index.links() }
            PlatformTestUtil.waitWithEventsDispatching("Updated sources should be indexed", { changed.isCompleted }, 20)
            val after = runBlocking { changed.await() }
            assertNotSame(before, after)
            assertFalse(after.getValue("A.java").outgoing.containsKey("B.java"))
            assertFalse(after.getValue("B.java").incoming.containsKey("A.java"))
        } finally {
            index.cancel()
            scope.cancel()
        }
    }
}
