package dev.insight.review

import com.google.gson.JsonParser
import dev.insight.agent.objectTool
import dev.insight.codereview.CodeFile
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.JsonSchemas
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChartPathSearchTest {
    private fun file(path: String, text: String = "x\n") = CodeFile(path, "/repo/$path", text)

    private val files = listOf(file("src/main/review/ui/Charts.kt"), file("src/main/review/Model.kt"),
        file("src/main/chat/Chat.kt"), file("README.md"))

    @Test fun `the model explores with the review's tools and answers with paths`() = runBlocking {
        val requests = mutableListOf<CompletionRequest>()
        val listed = mutableListOf<String>()
        val list = objectTool("list_code_files", "List files.", JsonSchemas.obj(required = emptyList())) {
            files.joinToString("\n") { it.path }.also(listed::add)
        }
        val replies = listOf(
            Completion("", toolCalls = listOf(LlmToolCall("c1", "list_code_files", JsonParser.parseString("{}").asJsonObject))),
            Completion("Found them:\n```json\n{\"paths\": [\"src/main/review/ui\", \"./README.md\"]}\n```"),
        )
        val search = ChartPathSearch({ request -> requests += request; replies[requests.size - 1] }, listOf(list), "files in scope")
        val paths = search.find("charts UI")
        assertEquals(listOf("src/main/review/ui", "README.md"), paths)
        assertEquals(1, listed.size)
        assertEquals(listOf("list_code_files"), requests.first().tools.map { it.name })
        assertEquals("charts UI", requests.first().messages.last().content)
        assertTrue(requests.first().messages.first().content!!.contains("files in scope"))
        assertEquals(setOf("src/main/review/ui/Charts.kt", "README.md"), matchChartPaths(paths, files))
    }

    @Test fun `replies without JSON fall back to path-like lines`() {
        assertEquals(listOf("src/a.kt", "src/b"), parseChartSearchPaths("These match:\n- `src/a.kt:12`\n* src/b/\nnothing else"))
        assertEquals(emptyList<String>(), parseChartSearchPaths("""{"paths": []}"""))
    }

    @Test fun `paths that differ from the review's still name its files and folders`() {
        // An absolute path, a longer prefix, a trailing folder part and a bare file name.
        assertEquals(setOf("src/main/review/Model.kt"), matchChartPaths(listOf("/repo/src/main/review/Model.kt"), files))
        assertEquals(setOf("src/main/chat/Chat.kt"), matchChartPaths(listOf("app/src/main/chat"), files))
        assertEquals(setOf("src/main/review/ui/Charts.kt", "src/main/review/Model.kt"), matchChartPaths(listOf("main/review"), files))
        assertEquals(setOf("src/main/review/ui/Charts.kt"), matchChartPaths(listOf("Charts.kt"), files))
        // Partial names never match: "view" is not "review".
        assertEquals(emptySet<String>(), matchChartPaths(listOf("view", "", "."), files))
    }

    @Test fun `an exact path wins over files it is only the trailing part of`() {
        val nested = listOf("src/Foo.kt", "vendor/src/Foo.kt", "lib/vendor/src/Foo.kt").map { CodeFile(it, "/repo/$it", "x\n") }
        assertEquals(setOf("src/Foo.kt"), matchChartPaths(listOf("src/Foo.kt"), nested))
        assertEquals(setOf("src/Foo.kt"), matchChartPaths(listOf("src"), nested))
        assertEquals(setOf("vendor/src/Foo.kt"), matchChartPaths(listOf("vendor/src"), nested))
        // A longer path names the longest review path it ends with.
        assertEquals(setOf("vendor/src/Foo.kt"), matchChartPaths(listOf("app/vendor/src/Foo.kt"), nested.take(2)))
        // Without an exact or longer match, a trailing part names every file it ends.
        assertEquals(setOf("src/Foo.kt", "vendor/src/Foo.kt", "lib/vendor/src/Foo.kt"), matchChartPaths(listOf("Foo.kt"), nested))
    }

    @Test fun `the hierarchy keeps only the found files and the folders leading to them`() {
        val tree = buildSummaryTree(files)
        val found = matchChartPaths(listOf("src/main/review/ui", "README.md"), files)
        val chart = ReviewLineChart(tree, LineChartMetric.FOUND, only = found)
        assertEquals(2L, chart.root.value)
        assertEquals(2, chart.root.files)
        val main = chart.directory("src/main")
        assertEquals(listOf("src/main/review"), chart.children(main).map { it.path })
        assertEquals(listOf("src/main/review/ui"), chart.children(chart.directory("src/main/review")).map { it.path })
        // A hidden folder falls back to its nearest shown ancestor.
        assertEquals("src/main", chart.directory("src/main/chat").path)
        assertEquals(listOf("src/main/review/ui/Charts.kt"), chart.files("src/main").map { it.path })
    }
}
