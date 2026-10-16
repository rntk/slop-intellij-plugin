package dev.insight.chat

import com.intellij.openapi.ui.DialogPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.llm.LlmProvider
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JPasswordField
import javax.swing.JTextField

class ChatSettingsConfigurableTest : BasePlatformTestCase() {
    private lateinit var settings: ChatSettings
    private lateinit var originalState: ChatSettings.State
    private lateinit var originalKeys: Map<LlmProvider, String?>
    private lateinit var configurable: ChatSettingsConfigurable
    private lateinit var panel: DialogPanel

    override fun setUp() {
        super.setUp()
        settings = ChatSettings.getInstance()
        originalState = settings.state
        originalKeys = LlmProvider.entries.associateWith(settings::apiKey)
        settings.loadState(ChatSettings.State().apply {
            serverUrl = "http://localhost:8080"
            model = "local-model"
            openAi.model = "cloud-model"
        })
        for (provider in LlmProvider.entries) settings.setApiKey(provider, null)
        settings.setApiKey(LlmProvider.LLAMA_CPP, "local-key")
        settings.setApiKey(LlmProvider.OPENAI, "cloud-key")
        configurable = ChatSettingsConfigurable()
        panel = configurable.createComponent() as DialogPanel
        configurable.reset()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            settings.loadState(originalState)
            originalKeys.forEach { (provider, key) -> settings.setApiKey(provider, key) }
        } finally { super.tearDown() }
    }

    private fun descendants(component: Component = panel): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap { descendants(it) }

    private fun providerChoice() = descendants().filterIsInstance<JComboBox<*>>()
        .single { it.getItemAt(0) is LlmProvider }

    private fun field(value: String) = descendants().filterIsInstance<JTextField>().single { it.text == value }

    fun `test credentials participate in Apply Reset and modification tracking`() {
        assertFalse(configurable.isModified)
        val key = field("local-key") as JPasswordField
        key.text = "edited-key"
        assertTrue(configurable.isModified)
        assertEquals("local-key", settings.apiKey(LlmProvider.LLAMA_CPP))
        configurable.reset()
        assertEquals("local-key", String(key.password))
        assertFalse(configurable.isModified)
        key.text = "edited-key"
        configurable.apply()
        assertEquals("edited-key", settings.apiKey(LlmProvider.LLAMA_CPP))
        assertEquals("cloud-key", settings.apiKey(LlmProvider.OPENAI))
        assertFalse(configurable.isModified)
    }

    fun `test switching providers keeps draft values and applies the chosen profile`() {
        val localModel = field("local-model")
        localModel.text = "edited-local"
        providerChoice().selectedItem = LlmProvider.OPENAI
        val cloudModel = field("cloud-model")
        cloudModel.text = "edited-cloud"
        field("cloud-key").text = "edited-cloud-key"
        providerChoice().selectedItem = LlmProvider.LLAMA_CPP
        assertEquals("edited-local", localModel.text)
        assertEquals(LlmProvider.LLAMA_CPP, settings.state.provider)
        providerChoice().selectedItem = LlmProvider.OPENAI
        assertEquals("edited-cloud", cloudModel.text)
        assertTrue(panel.validateAll().isEmpty())
        configurable.apply()
        assertEquals(LlmProvider.OPENAI, settings.state.provider)
        assertEquals("edited-cloud", settings.state.configuration().model)
        assertEquals("edited-local", settings.state.model)
        assertEquals("edited-cloud-key", settings.apiKey(LlmProvider.OPENAI))
    }

    fun `test active provider requires credentials model and secure URL while inactive profiles do not`() {
        assertTrue(panel.validateAll().isEmpty())
        providerChoice().selectedItem = LlmProvider.DEEPSEEK
        assertTrue(panel.validateAll().any { "Model is required" in it.message })
        assertTrue(panel.validateAll().any { "API key is required" in it.message })
        providerChoice().selectedItem = LlmProvider.OPENAI
        assertTrue(panel.validateAll().isEmpty())
        providerChoice().selectedItem = LlmProvider.LLAMA_CPP
        field("http://localhost:8080").text = "http://remote.example"
        assertTrue(panel.validateAll().any { "non-HTTPS" in it.message })
        configurable.reset()
        assertTrue(panel.validateAll().isEmpty())
    }
}
