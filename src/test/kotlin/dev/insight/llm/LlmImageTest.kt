package dev.insight.llm

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class LlmImageTest {
    private val request = CompletionRequest(listOf(LlmMessage.user("Explain the attached screenshot")
        .copy(images = listOf(LlmImage("image/png", "snapshot")))))

    @Test fun `OpenAI compatible requests encode text and image blocks`() {
        val body = JsonParser.parseString(LlmJson.encode(request, "vision", false)).asJsonObject
        val blocks = body.getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
        assertEquals("Explain the attached screenshot", blocks[0].asJsonObject["text"].asString)
        assertEquals("data:image/png;base64,snapshot", blocks[1].asJsonObject.getAsJsonObject("image_url")["url"].asString)
    }

    @Test fun `Anthropic requests encode base64 image sources`() {
        val body = JsonParser.parseString(AnthropicJson.encode(request, "vision", 1024, "", false)).asJsonObject
        val blocks = body.getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
        assertEquals("Explain the attached screenshot", blocks[0].asJsonObject["text"].asString)
        val image = blocks[1].asJsonObject
        assertEquals("image", image["type"].asString)
        assertEquals("image/png", image.getAsJsonObject("source")["media_type"].asString)
        assertEquals("snapshot", image.getAsJsonObject("source")["data"].asString)
    }

    @Test fun `context estimates account for image tokens independent of transport length`() {
        val larger = request.copy(messages = request.messages.map { it.copy(images = listOf(LlmImage("image/png", "A".repeat(1_000_000)))) })
        assertEquals(request.forContextEstimate(), larger.forContextEstimate())
        assertEquals("snapshot", request.messages.single().images.single().base64)
    }
}
