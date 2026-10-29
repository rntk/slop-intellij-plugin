package dev.insight.review

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReviewToolSchemasTest {
    @Test fun `schema descriptions preserve quotes backslashes and newlines`() {
        val description = "Paths under \"src\\main\"\nfrom the snapshot."
        val findTool = "find_\"symbols\\code\n"
        val scope = "the \"selected\" paths\\files\n"
        val tool = addNotesTool("notes", findTool, "code",
            { max -> pathListSchema(max, description) }, scope) { "ok" }
        val schema = JsonParser.parseString(tool.definition.parameters.toString()).asJsonObject
        val note = schema.getAsJsonObject("properties").getAsJsonObject("notes").getAsJsonObject("items")
        val properties = note.getAsJsonObject("properties")

        assertEquals(description, properties.getAsJsonObject("paths")["description"].asString)
        assertEquals("Optional declaration name, qualified name, or exact selector from $findTool. Must resolve uniquely within $scope.",
            properties.getAsJsonObject("symbol")["description"].asString)
        assertEquals(listOf("paths", "text"), note.getAsJsonArray("required").map { it.asString })
        assertFalse(note["additionalProperties"].asBoolean)
    }
}
