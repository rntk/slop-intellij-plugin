package dev.insight.chat

import com.intellij.openapi.ui.DialogPanel
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.llm.LlmProvider
import dev.insight.core.ApiRequestLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.awt.Component
import java.awt.Container
import javax.swing.JComboBox
import javax.swing.JPasswordField
import javax.swing.JLabel
import javax.swing.JTextField

class ChatSettingsConfigurableTest : BasePlatformTestCase() {
    private lateinit var settings: ChatSettings
    private lateinit var originalState: ChatSettings.State
    private lateinit var originalKeys: Map<LlmProvider, String?>
    private var originalDecisionsKey: String? = null
    private lateinit var configurable: ChatSettingsConfigurable
    private lateinit var panel: DialogPanel

    override fun setUp() {
        super.setUp()
        settings = ChatSettings.getInstance()
        originalState = settings.state
        originalKeys = LlmProvider.entries.associateWith(settings::apiKey)
        originalDecisionsKey = settings.decisionsApiKey
        settings.loadState(ChatSettings.State().apply {
            serverUrl = "http://localhost:8080"
            model = "local-model"
            openAi.model = "cloud-model"
        })
        for (provider in LlmProvider.entries) settings.setApiKey(provider, null)
        settings.setApiKey(LlmProvider.LLAMA_CPP, "local-key")
        settings.setApiKey(LlmProvider.OPENAI, "cloud-key")
        settings.decisionsApiKey = null
        configurable = ChatSettingsConfigurable()
        panel = configurable.createComponent() as DialogPanel
        configurable.reset()
    }

    override fun tearDown() {
        try {
            configurable.disposeUIResources()
            settings.loadState(originalState)
            originalKeys.forEach { (provider, key) -> settings.setApiKey(provider, key) }
            settings.decisionsApiKey = originalDecisionsKey
        } finally { super.tearDown() }
    }

    private fun descendants(component: Component = panel): List<Component> = listOf(component) +
        (component as? Container)?.components.orEmpty().flatMap { descendants(it) }

    private fun providerChoice() = descendants().filterIsInstance<JComboBox<*>>()
        .single { it.getItemAt(0) is LlmProvider }

    private fun field(value: String) = descendants().filterIsInstance<JTextField>().single { it.text == value }

    private fun concurrencyField(label: String) = descendants().filterIsInstance<JLabel>()
        .single { it.text == label }.labelFor as JTextField

    // The Decisions group precedes the provider groups, so its "API key:" label comes first.
    private fun labeledField(label: String) = descendants().filterIsInstance<JLabel>()
        .first { it.text == label }.labelFor as JTextField

    fun `test Decisions URL and key validate and participate in Apply and Reset`() {
        val url = labeledField("Server URL:")
        val key = labeledField("API key:") as JPasswordField
        assertEquals("", url.text)
        assertEquals("", String(key.password))
        url.text = "https://decisions.example"
        key.text = "decisions-key"
        assertTrue(configurable.isModified)
        configurable.reset()
        assertEquals("", url.text)
        assertEquals("", String(key.password))
        assertFalse(configurable.isModified)

        url.text = "ftp://decisions.example"
        assertFalse(panel.validateAll().isEmpty())
        url.text = "https://decisions.example"
        key.text = "decisions-key"
        assertTrue(panel.validateAll().isEmpty())
        configurable.apply()
        assertEquals("https://decisions.example", settings.state.decisionsUrl)
        assertEquals("decisions-key", settings.decisionsApiKey)
        assertEquals("local-key", settings.apiKey(LlmProvider.LLAMA_CPP))
        assertFalse(configurable.isModified)

        key.text = ""
        configurable.apply()
        assertNull(settings.decisionsApiKey)
    }

    fun `test concurrency limits validate and participate in Apply and Reset`() {
        val llm = concurrencyField("Max concurrent LLM requests:")
        val decisions = concurrencyField("Max concurrent Decisions requests:")
        llm.text = "2"
        decisions.text = "7"
        assertTrue(configurable.isModified)
        assertEquals(4, settings.state.maxConcurrentLlmRequests)
        configurable.reset()
        assertEquals("4", llm.text)
        assertEquals("4", decisions.text)
        assertFalse(configurable.isModified)

        llm.text = "0"
        decisions.text = "-1"
        assertFalse(panel.validateAll().isEmpty())
        llm.text = "2"
        decisions.text = "7"
        assertTrue(panel.validateAll().isEmpty())
        configurable.apply()
        assertEquals(2, settings.state.maxConcurrentLlmRequests)
        assertEquals(7, settings.state.maxConcurrentDecisionRequests)
        assertFalse(configurable.isModified)
        runBlocking {
            for ((limiter, limit) in listOf(ApiRequestLimits.llm to 2, ApiRequestLimits.decisions to 7)) {
                val gate = CompletableDeferred<Unit>()
                var entered = 0
                val requests = List(limit + 1) {
                    async(start = CoroutineStart.UNDISPATCHED) {
                        limiter.withPermit { entered++; gate.await() }
                    }
                }
                assertEquals(limit, entered)
                gate.complete(Unit)
                requests.forEach { it.await() }
            }
        }
    }

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
