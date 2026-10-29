package dev.insight.codereview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CodeFileStructureTest : BasePlatformTestCase() {
    fun `test each call line reaches neighboring files rather than transitive leaves`() {
        myFixture.addFileToProject("app/Controller.java", """
            package app;

            import app.svc.Mailer;
            import app.svc.OrderService;

            public class Controller {
                private final OrderService orders = new OrderService();
                private final Mailer mailer = null;

                public void order() {
                    orders.place();
                }

                public void notifyUser() {
                    mailer.send();
                    helper();
                }

                private void helper() {
                }
            }
        """.trimIndent() + "\n")
        myFixture.addFileToProject("app/svc/OrderService.java",
            "package app.svc;\npublic class OrderService { public void place() { app.repo.Repository.save(); } }\n")
        myFixture.addFileToProject("app/repo/Repository.java", "package app.repo;\npublic class Repository { public static void save() { } }\n")
        myFixture.addFileToProject("app/svc/Mailer.java", "package app.svc;\npublic interface Mailer { void send(); }\n")
        myFixture.addFileToProject("app/svc/MailerImpl.java",
            "package app.svc;\npublic class MailerImpl implements Mailer { public void send() { SmtpClient.deliver(); } }\n")
        myFixture.addFileToProject("app/svc/SmtpClient.java", "package app.svc;\npublic class SmtpClient { static void deliver() { } }\n")

        val snapshot = load(ReadAction.compute<CodeScope, Throwable> { codeScope(project, "app") })
        val file = snapshot.byPath.getValue("app/Controller.java")
        val structure = ReadAction.compute<dev.insight.review.ui.FileStructure, Throwable> { CodeFileStructureBuilder(project, snapshot).build(file) }

        // Every line belongs to exactly one block, top to bottom.
        assertEquals(1, structure.blocks.first().lines.first)
        assertEquals(file.lineCount, structure.blocks.last().lines.last)
        structure.blocks.zipWithNext().forEach { (a, b) -> assertEquals(a.lines.last + 1, b.lines.first) }

        val byTitle = structure.blocks.filter { it.title != null }.associateBy { it.title }
        assertEquals(setOf("orders, mailer", "order()", "notifyUser()", "helper()"), byTitle.keys)
        // Stop at the neighboring service/interface instead of following them down to repository/SMTP internals.
        assertEquals(listOf("app/svc/OrderService.java"), byTitle.getValue("order()").reaches)
        assertEquals(listOf("app/svc/Mailer.java"), byTitle.getValue("notifyUser()").reaches)
        val orderLine = file.text.lines().indexOfFirst { "orders.place()" in it } + 1
        val mailLine = file.text.lines().indexOfFirst { "mailer.send()" in it } + 1
        assertEquals(mapOf(orderLine to listOf("app/svc/OrderService.java")), byTitle.getValue("order()").lineReaches)
        assertEquals(mapOf(mailLine to listOf("app/svc/Mailer.java")), byTitle.getValue("notifyUser()").lineReaches)
        // Work done in the viewed file itself is not listed.
        assertEquals(emptyList<String>(), byTitle.getValue("helper()").reaches)
        // The class header joins the first declaration; the imports stay a block of their own.
        assertTrue(file.lines(byTitle.getValue("orders, mailer").lines).startsWith("public class Controller"))
        assertNull(structure.blocks.first().title)

        assertFalse(structure.truncated)
    }

    fun `test local helpers and recursion preserve neighbors on each calling line`() {
        myFixture.addFileToProject("local/Entry.java", """
            package local;
            class Entry {
                void run() {
                    helper();
                    helper();
                    Second.work(); First.work();
                }
                void helper() { recursive(); }
                void recursive() { helper(); First.work(); }
            }
        """.trimIndent() + "\n")
        myFixture.addFileToProject("local/First.java", "package local; class First { static void work() { Second.work(); } }\n")
        myFixture.addFileToProject("local/Second.java", "package local; class Second { static void work() {} }\n")
        val snapshot = load(ReadAction.compute<CodeScope, Throwable> { codeScope(project, "local") })
        val file = snapshot.byPath.getValue("local/Entry.java")
        val structure = ReadAction.compute<dev.insight.review.ui.FileStructure, Throwable> { CodeFileStructureBuilder(project, snapshot).build(file) }
        val run = structure.blocks.single { it.title == "run()" }
        assertEquals(mapOf(
            4 to listOf("local/First.java"),
            5 to listOf("local/First.java"),
            6 to listOf("local/Second.java", "local/First.java"),
        ), run.lineReaches)
        assertEquals(listOf("local/First.java", "local/Second.java"), run.reaches)
        assertFalse(structure.truncated)
    }

    fun `test a file that differs from the snapshot is not analyzed`() {
        myFixture.addFileToProject("stale/A.java", "package stale;\nclass A {}\n")
        val snapshot = load(ReadAction.compute<CodeScope, Throwable> { codeScope(project, "stale") })
        val file = snapshot.byPath.getValue("stale/A.java")
        val changed = CodeFile(file.path, file.absolutePath, "package stale;\nclass A { void a() {} }\n")
        assertThrows(IllegalStateException::class.java, "differs from the review snapshot") {
            ReadAction.compute<dev.insight.review.ui.FileStructure, Throwable> { CodeFileStructureBuilder(project, snapshot).build(changed) }
        }
    }

    private fun load(scope: CodeScope): CodeSnapshot {
        val coroutines = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var result: CodeSnapshot? = null
        var failure: Throwable? = null
        val job = coroutines.launch {
            try { result = CodeReviewLoader(project).load(scope, null) } catch (e: Throwable) { failure = e }
        }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Code review loading did not finish", { job.isCompleted }, 30)
        } finally { coroutines.cancel() }
        failure?.let { throw it }
        return result!!
    }
}
