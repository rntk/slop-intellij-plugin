package dev.insight.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import dev.insight.llm.LlmProvider
import org.jdom.Element

class ChatSettingsTest : BasePlatformTestCase() {
    fun `test legacy llama settings load and provider profiles survive serialization`() {
        val legacy = Element("state")
            .addContent(Element("option").setAttribute("name", "serverUrl").setAttribute("value", "http://localhost:8080"))
            .addContent(Element("option").setAttribute("name", "model").setAttribute("value", "local-model"))
            .addContent(Element("option").setAttribute("name", "requestTimeoutSeconds").setAttribute("value", "300"))
        val state = XmlSerializer.deserialize(legacy, ChatSettings.State::class.java)
        assertEquals(LlmProvider.LLAMA_CPP, state.provider)
        assertEquals("http://localhost:8080", state.configuration().baseUrl)
        assertEquals("local-model", state.configuration().model)
        assertEquals(300, state.configuration().requestTimeoutSeconds)
        assertEquals(4, state.maxConcurrentLlmRequests)
        assertEquals(4, state.maxConcurrentDecisionRequests)

        state.provider = LlmProvider.OPENAI
        state.openAi.model = "cloud-model"
        state.openAi.serverUrl = "https://gateway.example/v1"
        state.openAi.maxOutputTokens = 4096
        state.openAi.maxRetries = 4
        state.openAi.serviceTier = "flex"
        state.deepSeek.model = "other-model"
        state.maxConcurrentLlmRequests = 3
        state.maxConcurrentDecisionRequests = 7
        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), ChatSettings.State::class.java)
        assertEquals(state.configuration(), restored.configuration())
        assertEquals("other-model", restored.deepSeek.model)
        assertEquals(3, restored.maxConcurrentLlmRequests)
        assertEquals(7, restored.maxConcurrentDecisionRequests)
        restored.provider = LlmProvider.LLAMA_CPP
        assertEquals("local-model", restored.configuration().model)
        assertEquals("http://localhost:8080", restored.configuration().baseUrl)
        assertEquals(300, restored.configuration().requestTimeoutSeconds)
    }

    fun `test nested profile edits mark settings modified`() {
        val state = ChatSettings.State()
        val before = state.modificationCount
        state.openAi.model = "edited-model"
        assertTrue(state.modificationCount > before)
    }
}
