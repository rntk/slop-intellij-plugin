package dev.insight.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.llm.LlmTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolDecoratorsTest {
    private fun args(json: String) = JsonParser.parseString(json).asJsonObject

    private class SourceTool(var result: String) : ChatTool {
        val received = mutableListOf<JsonObject>()
        override val definition = LlmTool("source", "Source", JsonObject().apply {
            add("properties", JsonObject().apply { add("end_line", JsonObject()) })
        })
        override suspend fun call(arguments: JsonObject): String {
            received += arguments.deepCopy()
            if (arguments.has("fail")) throw ToolInputException("failed")
            return result
        }
    }

    @Test fun `schema override leaves original definition intact and delegates calls`() = runBlocking {
        val source = SourceTool("ok")
        val wrapped = SchemaOverride(source) {
            it.getAsJsonObject("properties").getAsJsonObject("end_line").addProperty("description", "bounded")
        }
        assertFalse(source.definition.parameters.getAsJsonObject("properties").getAsJsonObject("end_line").has("description"))
        assertEquals("bounded", wrapped.definition.parameters.getAsJsonObject("properties").getAsJsonObject("end_line")["description"].asString)
        assertEquals("ok", wrapped.call(JsonObject()))
        assertEquals(1, source.received.size)
    }

    @Test fun `read and search share allowance and recorder sees bounded query before output clipping`() = runBlocking {
        val budget = SourceReadBudget(3, 1000)
        val read = SourceTool("1| first\n2| second")
        val search = SourceTool("a.kt:1: first\na.kt:2: second")
        val recorded = mutableListOf<Pair<JsonObject, String>>()
        val readTool = BudgetedTool(read, budget, BudgetedTool.Format.READ)
        val searchTool = BudgetedTool(RecordingTool(search) { arguments, result -> recorded += arguments to result }, budget, BudgetedTool.Format.SEARCH)
        val original = args("""{"start_line":1,"end_line":2}""")
        assertEquals(read.result, readTool.call(original))
        assertEquals(2, budget.lines)
        assertEquals("a.kt:1: first\n[Source line budget reached.]", searchTool.call(JsonObject()))
        assertEquals(1, recorded.single().first["max_results"].asInt)
        assertEquals(search.result, recorded.single().second)
        assertFalse(original.has("max_results"))
        assertEquals(3, budget.lines)
        try {
            readTool.call(JsonObject())
            fail("Expected exhausted budget")
        } catch (expected: ToolInputException) {
            assertTrue(expected.message!!.contains("budget reached"))
        }
        assertEquals(1, read.received.size)
    }

    @Test fun `reads bound ranges without mutating arguments and reject invalid ranges`() = runBlocking {
        val source = SourceTool("source without numbered lines")
        val tool = BudgetedTool(source, SourceReadBudget(1000, 1000), BudgetedTool.Format.READ)
        val original = args("""{"start_line":10,"end_line":200}""")
        assertTrue(tool.call(original).endsWith("[Read limited to 10-89.]"))
        assertEquals(89, source.received.single()["end_line"].asInt)
        assertEquals(200, original["end_line"].asInt)
        try {
            tool.call(args("""{"start_line":10,"end_line":9}"""))
            fail("Expected invalid range")
        } catch (expected: ToolInputException) {
            assertEquals("Invalid source line range", expected.message)
        }
        assertEquals(1, source.received.size)
    }

    @Test fun `default end line does not overflow at maximum source line`() = runBlocking {
        val source = SourceTool("2147483647| last")
        val tool = BudgetedTool(source, SourceReadBudget(100, 1000), BudgetedTool.Format.READ)
        assertEquals(source.result, tool.call(args("""{"start_line":2147483647}""")))
        assertEquals(Int.MAX_VALUE, source.received.single()["end_line"].asInt)
    }

    @Test fun `character exhaustion trims complete lines and failures are not recorded or charged`() = runBlocking {
        val source = SourceTool("1| x\n2| too long")
        val budget = SourceReadBudget(10, 6)
        var records = 0
        val tool = BudgetedTool(RecordingTool(source) { _, _ -> records++ }, budget, BudgetedTool.Format.READ)
        try {
            tool.call(args("""{"fail":true}"""))
            fail("Expected tool failure")
        } catch (expected: ToolInputException) {
            assertEquals("failed", expected.message)
        }
        assertEquals(0, records)
        assertEquals(0, budget.chars)
        assertEquals("1| x\n[Source character budget reached.]\n[Read limited to 1-10.]", tool.call(JsonObject()))
        assertEquals(6, budget.chars)
        assertEquals(1, records)
    }
}
