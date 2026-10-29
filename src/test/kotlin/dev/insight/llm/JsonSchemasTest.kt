package dev.insight.llm

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class JsonSchemasTest {
    @Test fun `optional tool fields and nested limits survive serialization with special characters`() {
        val description = "Use \"quoted\" paths\\names\nfrom the snapshot."
        val schema = JsonSchemas.obj(
            "paths" to JsonSchemas.array(JsonSchemas.string(minLength = 1, maxLength = 500, pattern = "^.+\\.kt$"),
                minItems = 1, maxItems = 30, description = description),
            "offset" to JsonSchemas.integer(minimum = 0, maximum = 100, description = description),
            "side" to JsonSchemas.enum(listOf("before", "after"), description = description),
            "expand" to JsonSchemas.boolean(description = description),
            required = listOf("paths"), additionalProperties = false,
        )
        val parsed = JsonParser.parseString(schema.toString()).asJsonObject
        assertEquals(listOf("paths"), parsed.getAsJsonArray("required").map { it.asString })
        assertFalse(parsed["additionalProperties"].asBoolean)
        val properties = parsed.getAsJsonObject("properties")
        val paths = properties.getAsJsonObject("paths")
        assertEquals(description, paths["description"].asString)
        assertEquals(1, paths["minItems"].asInt)
        assertEquals(30, paths["maxItems"].asInt)
        val item = paths.getAsJsonObject("items")
        assertEquals(1, item["minLength"].asInt)
        assertEquals(500, item["maxLength"].asInt)
        assertEquals("^.+\\.kt$", item["pattern"].asString)
        val offset = properties.getAsJsonObject("offset")
        assertEquals(0, offset["minimum"].asInt)
        assertEquals(100, offset["maximum"].asInt)
        assertEquals(listOf("before", "after"), properties.getAsJsonObject("side").getAsJsonArray("enum").map { it.asString })
        assertEquals(description, properties.getAsJsonObject("expand")["description"].asString)
    }

    @Test fun `generative objects retain required fields and open object default`() {
        val schema = JsonSchemas.obj("name" to JsonSchemas.string(), "count" to JsonSchemas.integer())
        assertEquals(listOf("name", "count"), schema.getAsJsonArray("required").map { it.asString })
        assertFalse(schema.has("additionalProperties"))
    }

    @Test fun `reusing a schema does not share mutable children`() {
        val item = JsonSchemas.obj("name" to JsonSchemas.string())
        val array = JsonSchemas.array(item)
        val first = JsonSchemas.obj("items" to array)
        val second = JsonSchemas.obj("items" to array)
        item.getAsJsonObject("properties").addProperty("extra", true)
        first.getAsJsonObject("properties").getAsJsonObject("items").addProperty("maxItems", 1)
        assertFalse(array.getAsJsonObject("items").getAsJsonObject("properties").has("extra"))
        assertFalse(second.getAsJsonObject("properties").getAsJsonObject("items").has("maxItems"))
    }
}
