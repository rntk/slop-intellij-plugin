package dev.insight.prompts

import org.junit.Assert.*
import org.junit.Test

class PromptTemplatesTest {
    @Test fun `bundled resources load as UTF8 without trailing line endings`() {
        val text = PromptTemplates.load("review-summary-writing.md")
        assertTrue(text.startsWith("State the main purpose"))
        assertFalse(text.endsWith("\n"))
    }

    @Test fun `substitution is literal single pass and supports repeated placeholders`() {
        val evidence = "{{other}} \\$1 \\path\nsource"
        assertEquals("$evidence / $evidence", PromptTemplates.renderText("{{data}} / {{data}}", mapOf("data" to evidence)))
    }

    @Test fun `missing and unexpected placeholders fail before sending a prompt`() {
        assertThrows(IllegalArgumentException::class.java) {
            PromptTemplates.renderText("{{required}}", emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PromptTemplates.renderText("plain", mapOf("unused" to "value"))
        }
        assertThrows(IllegalArgumentException::class.java) { PromptTemplates.load("../secret.md") }
        assertThrows(IllegalStateException::class.java) { PromptTemplates.load("missing-template.md") }
    }

    @Test fun `entrypoint answer template renders its enum without unresolved placeholders`() {
        val prompt = PromptTemplates.render("entrypoint-answer.md", mapOf("kinds" to "application|cli"))
        assertTrue(prompt.contains("\"kind\": \"application|cli\""))
        assertFalse(prompt.contains("{{"))
    }
}
