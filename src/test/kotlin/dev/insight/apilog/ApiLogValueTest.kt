package dev.insight.apilog

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ApiLogValueTest {
    @Test fun `decodes embedded objects and arrays recursively`() {
        val root = ApiLogValue("body", JsonPrimitive("""{"content":"[{\"message\":\"hello\\nworld\"}]"}"""))
        assertTrue(root.decoded)
        val content = root.children().single()
        assertTrue(content.decoded)
        assertEquals("content (decoded JSON) · Array · 1 items", content.title)
        assertEquals("hello\nworld", content.children().single().children().single().text)
    }

    @Test fun `preserves invalid JSON plain text and scalar types`() {
        for (text in listOf("{invalid}", "[broken]", "hello\nworld", "123", "null", "")) {
            val value = ApiLogValue("text", JsonPrimitive(text))
            assertFalse(value.decoded)
            assertFalse(value.expandable)
            assertEquals(text, value.text)
        }
        val root = ApiLogValue("body", JsonParser.parseString("""[null,true,42,{},[]]"""))
        assertEquals(listOf("null", "true", "42", "{}", "[]"), root.children().map { it.text })
        assertTrue(root.children().last().expandable)
        assertTrue(root.children().last().children().isEmpty())
    }
}
