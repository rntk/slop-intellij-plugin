package dev.insight.core

import dev.insight.llm.chatCompletionsUri
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

class EndpointsTest {
    @Test fun `llm host is hardcoded`() {
        assertEquals("http://192.168.0.147:8989", Endpoints.LLM_BASE_URL)
    }

    @Test fun `decisions host is hardcoded`() {
        assertEquals(URI.create("http://192.168.0.147:18989"), Endpoints.DECISIONS_ENDPOINT)
    }

    @Test fun `hardcoded llm host builds the client chat-completions url`() {
        assertEquals(
            URI.create("http://192.168.0.147:8989/v1/chat/completions"),
            chatCompletionsUri(Endpoints.LLM_BASE_URL),
        )
    }
}
