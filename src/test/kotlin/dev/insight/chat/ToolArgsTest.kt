package dev.insight.chat

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolArgsTest {
    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    private fun rejects(message: String, block: () -> Unit) {
        try { block(); fail("expected $message") }
        catch (e: ToolInputException) { assertTrue(e.message, e.message!!.contains(message)) }
    }

    @Test fun `lenient ints accept quoted numbers and treat null as missing`() {
        assertEquals(5, json("""{"n":" 5 "}""").lenientInt("n"))
        assertEquals(-2, json("""{"n":-2}""").lenientInt("n"))
        assertNull(json("""{"n":null}""").lenientInt("n"))
        assertNull(json("{}").lenientInt("n"))
        rejects("n must be an integer") { json("""{"n":"five"}""").lenientInt("n") }
        rejects("nodes[1].id must be a nonnegative integer") { json("""{"id":-1}""").lenientInt("id", "nodes[1].id", nonnegative = true) }
    }

    @Test fun `lenient booleans accept quoted flags`() {
        assertEquals(true, json("""{"b":"TRUE"}""").lenientBoolean("b"))
        assertEquals(false, json("""{"b":false}""").lenientBoolean("b"))
        assertNull(json("""{"b":null}""").lenientBoolean("b"))
        rejects("b must be true or false") { json("""{"b":"yes"}""").lenientBoolean("b") }
    }

    @Test fun `strict optional booleans reject quoted flags`() {
        assertNull(json("""{"b":null}""").optionalBooleanArg("b"))
        assertEquals(false, json("{}").booleanArg("b"))
        rejects("b must be true or false") { json("""{"b":"true"}""").optionalBooleanArg("b") }
    }

    @Test fun `lenient ids are bounded, nonnegative and distinct`() {
        assertEquals(listOf(3, 1), json("""{"ids":[3,"1",3]}""").lenientIds("ids", 1..5))
        rejects("ids must be an array of 1 to 5 IDs") { json("""{"ids":3}""").lenientIds("ids", 1..5) }
        rejects("ids must contain 1 to 5 IDs") { json("""{"ids":[]}""").lenientIds("ids", 1..5) }
        rejects("ids must contain 1 to 2 IDs") { json("""{"ids":[1,2,3]}""").lenientIds("ids", 1..2) }
        rejects("groups[0].nodes must contain nonnegative integers") {
            json("""{"nodes":[-1]}""").lenientIds("nodes", 1..5, "groups[0].nodes")
        }
    }
}
