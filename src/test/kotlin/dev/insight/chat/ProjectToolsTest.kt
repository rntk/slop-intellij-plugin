package dev.insight.chat

import com.google.gson.JsonParser
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.*

class ProjectToolsTest : BasePlatformTestCase() {
    private fun call(name: String, arguments: String): String {
        // Tools can switch to the EDT; blocking it with runBlocking deadlocks those calls.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var result: Result<String>? = null
        val job = scope.launch {
            result = runCatching {
                projectTools(project).single { it.definition.name == name }.call(JsonParser.parseString(arguments).asJsonObject)
            }
        }
        try {
            PlatformTestUtil.waitWithEventsDispatching("$name should finish", { job.isCompleted }, 5 /* seconds */)
            return result!!.getOrThrow()
        } finally { scope.cancel() }
    }

    private fun invalid(name: String, arguments: String): String =
        org.junit.Assert.assertThrows(ToolInputException::class.java) { call(name, arguments) }.message!!

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("app/Main.kt", "fun main() {\n    greet(\"World\")\n}\n")
        myFixture.addFileToProject("app/Greeter.kt", "fun greet(name: String) = println(\"Hello, \$name\")\n")
        myFixture.addFileToProject("docs/notes.md", "Greeting conventions\n")
    }

    fun `test tool definitions are valid function schemas`() {
        val tools = projectTools(project)
        assertEquals(listOf("list_files", "read_file", "search_text", "open_file"), tools.map { it.definition.name })
        tools.forEach { assertEquals("object", it.definition.parameters["type"].asString) }
    }

    fun `test list files returns project relative paths with filters and limits`() {
        assertEquals("app/Greeter.kt\napp/Main.kt\ndocs/notes.md", call("list_files", "{}"))
        assertEquals("app/Greeter.kt\napp/Main.kt", call("list_files", """{"directory":"app"}"""))
        assertEquals("docs/notes.md", call("list_files", """{"glob":"*.md"}"""))
        assertEquals("app/Greeter.kt\n[2 more files not shown.]", call("list_files", """{"max_results":"1"}"""))
        assertEquals("No matching files.", call("list_files", """{"glob":"*.java"}"""))
        assertTrue(invalid("list_files", """{"directory":"app/Main.kt"}""").contains("not a directory"))
    }

    fun `test read file returns numbered line ranges`() {
        assertEquals(
            "app/Main.kt (lines 1-4 of 4)\n1| fun main() {\n2|     greet(\"World\")\n3| }\n4| ",
            call("read_file", """{"path":"app/Main.kt"}"""),
        )
        assertEquals("app/Main.kt (lines 2-2 of 4)\n2|     greet(\"World\")", call("read_file", """{"path":"./app/Main.kt","start_line":2,"end_line":2}"""))
        assertTrue(invalid("read_file", """{"path":"app/Main.kt","start_line":9}""").contains("only 4 lines"))
        assertTrue(invalid("read_file", """{"path":"app"}""").contains("directory"))
        assertTrue(invalid("read_file", "{}").contains("path is required"))
        assertTrue(invalid("read_file", """{"path":"app/Main.kt","start_line":"two"}""").contains("integer"))
    }

    fun `test default read range is eighty lines`() {
        myFixture.addFileToProject("app/Long.txt", (1..150).joinToString("\n") { "line $it" })
        val result = call("read_file", """{"path":"app/Long.txt"}""")
        assertTrue(result.startsWith("app/Long.txt (lines 1-80 of 150)"))
        assertTrue(result.endsWith("80| line 80"))
        assertFalse(result.contains("81|"))
    }

    fun `test paths outside the project are rejected`() {
        assertTrue(invalid("read_file", """{"path":"app/../../etc/passwd"}""").contains(".."))
        assertTrue(invalid("read_file", """{"path":"missing.kt"}""").contains("No project file"))
    }

    fun `test search text finds case insensitive matches`() {
        assertEquals(
            "app/Greeter.kt:1: fun greet(name: String) = println(\"Hello, \$name\")\napp/Main.kt:2: greet(\"World\")\ndocs/notes.md:1: Greeting conventions",
            call("search_text", """{"query":"GREET"}"""),
        )
        assertEquals("app/Main.kt:2: greet(\"World\")", call("search_text", """{"query":"world","glob":"*.kt"}"""))
        assertTrue(call("search_text", """{"query":"greet","max_results":1}""").endsWith("[More matches not shown; narrow the search.]"))
        assertEquals("No matches for 'absent'.", call("search_text", """{"query":"absent"}"""))
    }

    fun `test search text supports regular expressions`() {
        assertEquals("app/Greeter.kt:1: fun greet(name: String) = println(\"Hello, \$name\")", call("search_text", """{"query":"^fun\\s+GREET\\(","regex":true}"""))
        assertEquals("No matches for 'greet\\('.", call("search_text", """{"query":"greet\\(","regex":"true","glob":"*.md"}"""))
        assertTrue(invalid("search_text", """{"query":"greet(","regex":true}""").contains("regular expression"))
        assertTrue(invalid("search_text", """{"query":"greet","regex":"yes"}""").contains("true or false"))
    }

    fun `test open file shows the location in the editor`() {
        assertEquals("Opened app/Main.kt at line 2 in the user's editor.", call("open_file", """{"path":"app/Main.kt","line":2,"column":5}"""))
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals("app/Main.kt", editor.virtualFile.path.substringAfter("/src/"))
        assertEquals(com.intellij.openapi.editor.LogicalPosition(1, 4), editor.caretModel.logicalPosition)
        assertFalse(editor.selectionModel.hasSelection())

        assertEquals("Opened app/Main.kt at lines 1-3 in the user's editor.", call("open_file", """{"path":"app/Main.kt","line":1,"end_line":3}"""))
        assertEquals("fun main() {\n    greet(\"World\")\n}", editor.selectionModel.selectedText)

        // Columns past the end of the line stop at the line end.
        call("open_file", """{"path":"app/Greeter.kt","column":500}""")
        val greeter = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(greeter.document.getLineEndOffset(0), greeter.caretModel.offset)

        assertTrue(invalid("open_file", """{"path":"app/Main.kt","line":9}""").contains("only 4 lines"))
        assertTrue(invalid("open_file", """{"path":"app/Main.kt","line":3,"end_line":2}""").contains("end_line"))
        assertTrue(invalid("open_file", """{"path":"app"}""").contains("directory"))
        assertTrue(invalid("open_file", """{"path":"missing.kt"}""").contains("No project file"))
    }
}
