package dev.insight.chat

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourcePointToolTest {
    @Test fun `single lines ranges comments and diff sides dispatch without losing coordinates`() = runBlocking {
        val seen = mutableListOf<SourcePoint>()
        val code = sourcePointTool { seen += it; "shown" }
        val diff = sourcePointTool(diff = true) { seen += it; "shown" }
        assertEquals("shown", code.call(args("""{"path":"src/a.kt","line":48}""")))
        code.call(args("""{"path":"src/a.kt","line":4,"end_line":8,"comment":"Why this matters\nSecond line"}"""))
        diff.call(args("""{"path":"old.kt","line":2,"side":"before","comment":"Removed"}"""))
        diff.call(args("""{"path":"new.kt","line":3}"""))
        assertEquals(listOf(SourcePoint("src/a.kt", 48), SourcePoint("src/a.kt", 4, 8, "Why this matters\nSecond line"),
            SourcePoint("old.kt", 2, 2, "Removed", SourceSide.BEFORE), SourcePoint("new.kt", 3, 3, side = SourceSide.AFTER)), seen)
        assertFalse(code.definition.parameters["properties"].asJsonObject.has("side"))
        assertTrue(diff.definition.parameters["properties"].asJsonObject.has("side"))
    }

    @Test fun `malformed coordinates comments and unsupported sides never dispatch`() = runBlocking {
        var calls = 0
        val code = sourcePointTool { calls++; "" }
        for (input in listOf("{}", """{"path":"a"}""", """{"path":"","line":1}""",
            """{"path":"a","line":0}""", """{"path":"a","line":-1}""", """{"path":"a","line":1.5}""",
            """{"path":"a","line":"1"}""", """{"path":"a","line":2147483648}""",
            """{"path":"a","line":2,"end_line":1}""", """{"path":"a","line":1,"comment":false}""",
            """{"path":"a","line":1,"comment":null}""", """{"path":"a","line":1,"side":"before"}""",
            """{"path":"a","line":1,"comment":"${"x".repeat(2001)}"}""")) {
            try { code.call(args(input)); fail(input) } catch (_: ToolInputException) { }
        }
        val diff = sourcePointTool(diff = true) { calls++; "" }
        for (side in listOf("null", "1", "\"current\"")) {
            try { diff.call(args("""{"path":"a","line":1,"side":$side}""")); fail(side) } catch (_: ToolInputException) { }
        }
        assertEquals(0, calls)
    }

    private fun args(json: String) = JsonParser.parseString(json).asJsonObject
}
