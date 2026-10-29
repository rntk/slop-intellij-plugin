package dev.insight.chat

import com.google.gson.JsonParser
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.codereview.CodeReviewTab
import dev.insight.codereview.codeFile
import dev.insight.codereview.codeReviewPrompt
import dev.insight.codereview.codeSnapshot
import dev.insight.diff.DiffReviewTab
import dev.insight.diff.ReviewFile
import dev.insight.diff.ReviewSnapshot
import dev.insight.diff.diffReviewPrompt
import dev.insight.llm.Completion
import dev.insight.llm.LlmToolCall
import dev.insight.review.ChatFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReviewProjectContextToolsTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("app/Main.kt", "fun main() = currentBehavior()")
        val helper = myFixture.addFileToProject("context/Helper.kt", "fun helper() = savedContext()")
        val document = FileDocumentManager.getInstance().getDocument(helper.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText("fun helper() = unsavedContext()") }
    }

    fun `test focused diff chat can read unchanged project files outside its selected changes`() {
        val file = ReviewFile("app/Main.kt", "/project/app/Main.kt", null, "modified", "-old\n+snapshotBehavior\n", "1",
            before = "old", after = "snapshotBehavior")
        val tab = DiffReviewTab(project, setOf(file.path), { emptyList() }, {}, focus = ChatFocus(file.path, listOf(file.path)))
        try {
            tab.update(ReviewSnapshot(listOf(file), emptyList()))
            val tools = tab.chatTools(tab.captureContext())
            runWithEvents {
                assertProjectContext(tools)
                val snapshot = call(tools, "read_diff_files", """{"paths":["app/Main.kt"]}""")
                assertTrue(snapshot, snapshot.contains("snapshotBehavior"))
                assertFalse(snapshot, snapshot.contains("currentBehavior"))
                val shown = call(tools, "point_to_code", """{"path":"app/Main.kt","line":1,"end_line":200}""")
                assertTrue(shown, shown.contains("(after)"))
                assertEquals("snapshotBehavior", sourceViews().last().text)
                assertRejected(tools, "point_to_code", """{"path":"context/Helper.kt","line":1,"side":"before"}""")
                assertRejected(tools, "read_diff_files", """{"paths":["context/Helper.kt"]}""")
                assertRejected(tools, "add_diff_notes", """{"notes":[{"paths":["context/Helper.kt"],"text":"Outside scope"}]}""")
            }
            assertEquals(listOf(file.path), tab.state.snapshot.files.map { it.path })
            assertTrue(diffReviewPrompt().contains(projectContextPrompt))
        } finally { Disposer.dispose(tab) }
    }

    fun `test focused code chat can read project files outside its selected scope`() {
        val file = codeFile("app/Main.kt", "fun main() = snapshotBehavior()")
        val tab = CodeReviewTab(project, setOf(file.path), { emptyList() }, { _, _ -> },
            focus = ChatFocus(file.path, listOf(file.path)))
        try {
            tab.update(codeSnapshot(file))
            val tools = tab.chatTools(tab.captureContext())
            runWithEvents {
                assertProjectContext(tools)
                val snapshot = call(tools, "read_code", """{"ranges":[{"path":"app/Main.kt"}]}""")
                assertTrue(snapshot, snapshot.contains("snapshotBehavior"))
                assertFalse(snapshot, snapshot.contains("currentBehavior"))
                call(tools, "point_to_code", """{"path":"app/Main.kt","line":1,"end_line":200}""")
                assertEquals("fun main() = snapshotBehavior()", sourceViews().last().text)
                assertRejected(tools, "read_code", """{"ranges":[{"path":"context/Helper.kt"}]}""")
                assertRejected(tools, "add_code_notes", """{"notes":[{"paths":["context/Helper.kt"],"text":"Outside scope"}]}""")
            }
            assertEquals(listOf(file.path), tab.state.snapshot.files.map { it.path })
            assertTrue(codeReviewPrompt("app/").contains(projectContextPrompt))
        } finally { Disposer.dispose(tab) }
    }

    fun `test review chat records project reads searches and previews at accepted completion boundaries`() {
        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        val trace = FileTrace("Compare with project context")
        try {
            tab.update(ReviewSnapshot(emptyList(), emptyList()))
            val tools = tab.chatTools(tab.captureContext())
            val calls = listOf(
                "point_to_code" to """{"path":"context/Helper.kt","line":1,"end_line":200}""",
                "read_file" to """{"path":"context/Helper.kt","start_line":1,"end_line":200}""",
                "search_text" to """{"query":"currentBehavior"}""",
            )
            var round = 0
            val events = mutableListOf<ToolCallEvent>()
            runWithEvents {
                withContext(FileTraceContext(trace)) {
                    LlmChatBackend({
                        calls.getOrNull(round++)?.let { (name, arguments) ->
                            Completion("", toolCalls = listOf(LlmToolCall("call$round", name, JsonParser.parseString(arguments).asJsonObject)))
                        } ?: Completion("Done")
                    }, tools, diffReviewPrompt()).reply(listOf(ChatMessage(ChatRole.USER, "Compare"))) {
                        events += it
                        trace.tool(it)
                    }
                }
            }
            assertTrue(events.toString(), events.none { it.failed })
            val data = trace.snapshot()
            assertEquals(4, data.requests.size)
            assertTrue("Showing source is not reading it into the model", data.requests[1].evidence.isEmpty())
            val helper = TraceKey("context/Helper.kt", "Project")
            assertEquals(listOf(0.."fun helper() = unsavedContext()".lastIndex), data.requests[2].evidence.getValue(helper))
            assertTrue(data.requests.last().evidence.containsKey(TraceKey("app/Main.kt", "Project")))
            assertEquals(setOf("context/Helper.kt", "app/Main.kt"), data.reads.keys)
            assertTrue(data.documents.getValue(helper).text.contains("unsavedContext"))
        } finally { Disposer.dispose(tab) }
    }

    private fun sourceViews(): List<SourcePointView> = FileEditorManager.getInstance(project).openFiles
        .filterIsInstance<SourcePointsFile>().flatMap { it.source.points }

    private suspend fun assertProjectContext(tools: List<ChatTool>) {
        assertEquals(tools.size, tools.map { it.definition.name }.distinct().size)
        assertFalse(tools.any { it.definition.name == "open_file" })
        assertTrue(call(tools, "list_files", "{}").contains("context/Helper.kt"))
        assertEquals("context/Helper.kt:1: fun helper() = unsavedContext()",
            call(tools, "search_text", """{"query":"unsavedContext"}"""))
        assertEquals("context/Helper.kt (lines 1-1 of 1)\n1| fun helper() = unsavedContext()",
            call(tools, "read_file", """{"path":"context/Helper.kt"}"""))
        assertTrue(call(tools, "read_file", """{"path":"app/Main.kt"}""").contains("currentBehavior"))
        val shown = call(tools, "point_to_code", """{"path":"context/Helper.kt","line":1,"end_line":200}""")
        assertTrue(shown, shown.contains("Current project content"))
        assertEquals("fun helper() = unsavedContext()", sourceViews().last().text)
        assertRejected(tools, "read_file", """{"path":"../outside.kt"}""")
    }

    private suspend fun call(tools: List<ChatTool>, name: String, args: String): String =
        tools.single { it.definition.name == name }.call(JsonParser.parseString(args).asJsonObject)

    private suspend fun assertRejected(tools: List<ChatTool>, name: String, args: String) {
        try { call(tools, name, args); fail("$name should reject $args") }
        catch (_: ToolInputException) { }
    }

    private fun runWithEvents(action: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var failure: Throwable? = null
        val job = scope.launch { try { action() } catch (e: Throwable) { failure = e } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Review tools should finish", { job.isCompleted }, 5)
            failure?.let { throw it }
        } finally { scope.cancel() }
    }
}
