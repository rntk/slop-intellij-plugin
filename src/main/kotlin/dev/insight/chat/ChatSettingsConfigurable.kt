package dev.insight.chat

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selectedValueIs
import dev.insight.llm.LlmConfiguration
import dev.insight.llm.LlmProvider
import javax.swing.JTextField

class ChatSettingsConfigurable : BoundConfigurable("Insight Chat") {
    private val settings = ChatSettings.getInstance()
    private val storedApiKeys = mutableMapOf<LlmProvider, String>()
    private val apiKeys = mutableMapOf<LlmProvider, String>()

    override fun createPanel(): DialogPanel {
        storedApiKeys.clear()
        apiKeys.clear()
        val state = settings.state
        return panel {
            lateinit var selection: ComboBox<LlmProvider>
            row("Current provider:") {
                selection = comboBox(LlmProvider.entries)
                    .bindItem({ state.provider }, { state.provider = it ?: LlmProvider.LLAMA_CPP })
                    .comment("Used by chat, entry point discovery, enrichment, and flow analysis. Each provider keeps its own settings.")
                    .component
            }
            for (provider in LlmProvider.entries) {
                val options = state.options(provider)
                storedApiKeys[provider] = settings.apiKey(provider).orEmpty()
                apiKeys[provider] = storedApiKeys.getValue(provider)
                lateinit var urlField: JTextField
                group(provider.displayName) {
                    row("Server URL:") {
                        urlField = textField()
                            .bindText({ options.serverUrl.orEmpty() }, { options.serverUrl = it.trim() })
                            .columns(COLUMNS_LARGE)
                            .comment("Leave empty to use ${provider.defaultBaseUrl}.")
                            .validationOnApply { field ->
                                if (selection.selectedItem != provider) null else {
                                    val failure = runCatching {
                                        LlmConfiguration(provider, serverUrl = field.text).endpoint
                                    }.exceptionOrNull()?.message
                                    failure?.let { error(it) }
                                }
                            }.component
                    }
                    row("Model:") {
                        textField()
                            .bindText({ options.model.orEmpty() }, { options.model = it.trim() })
                            .columns(COLUMNS_LARGE)
                            .comment(if (provider == LlmProvider.LLAMA_CPP) "Optional; uses the server's loaded model when empty." else "Enter the model ID supported by your provider.")
                            .validationOnApply {
                                if (selection.selectedItem == provider && provider != LlmProvider.LLAMA_CPP && it.text.isBlank())
                                    error("Model is required") else null
                            }
                    }
                    row("API key:") {
                        passwordField()
                            .bindText({ apiKeys.getValue(provider) }, { apiKeys[provider] = it })
                            .columns(COLUMNS_LARGE)
                            .comment((if (provider == LlmProvider.LLAMA_CPP) "Optional. " else "Required. ") +
                                "Stored in the IDE password safe; sent only over HTTPS or to localhost.")
                            .validationOnApply { field ->
                                if (selection.selectedItem != provider) null else {
                                    val failure = runCatching {
                                        LlmConfiguration(provider, serverUrl = urlField.text, model = "validation")
                                            .createClient(String(field.password))
                                    }.exceptionOrNull()?.message
                                    failure?.let { error(it) }
                                }
                            }
                    }
                    row("Max output tokens:") {
                        intTextField(0..Int.MAX_VALUE)
                            .bindIntText({ options.maxOutputTokens }, { options.maxOutputTokens = it })
                            .comment("Default for requests without an explicit token budget, including chat. 0 uses the provider default (8192 for Anthropic). Analysis requests keep their own budgets.")
                    }
                    row("Request timeout (seconds):") {
                        intTextField(10..86400).bindIntText({ options.requestTimeoutSeconds }, { options.requestTimeoutSeconds = it })
                    }
                    row("Max retries:") {
                        intTextField(0..100).bindIntText({ options.maxRetries }, { options.maxRetries = it })
                    }
                    if (provider == LlmProvider.LLAMA_CPP) {
                        row {
                            checkBox("Cache prompt").bindSelected({ options.cachePrompt }, { options.cachePrompt = it })
                        }
                    }
                    if (provider == LlmProvider.OPENAI || provider == LlmProvider.ANTHROPIC) {
                        row("Service tier:") {
                            comboBox(if (provider == LlmProvider.OPENAI) listOf("", "auto", "default", "flex", "priority")
                                else listOf("", "auto", "standard_only"))
                                .bindItem({ options.serviceTier.orEmpty() }, { options.serviceTier = it.orEmpty() })
                                .comment("Leave empty to use the provider default.")
                        }
                    }
                    if (provider == LlmProvider.OPENAI) {
                        row("Prompt cache key:") {
                            textField().bindText({ options.promptCacheKey.orEmpty() }, { options.promptCacheKey = it.trim() })
                                .columns(COLUMNS_LARGE).comment("Optional; groups requests that share a prompt prefix.")
                        }
                    }
                }.visibleIf(selection.selectedValueIs(provider))
            }
        }
    }

    override fun apply() {
        super.apply()
        apiKeys.forEach { (provider, value) ->
            val key = value.trim()
            if (key != storedApiKeys[provider]) {
                settings.setApiKey(provider, key)
                storedApiKeys[provider] = key
                apiKeys[provider] = key
            }
        }
    }

    // Don't keep plaintext keys around after the dialog closes.
    override fun disposeUIResources() {
        super.disposeUIResources()
        storedApiKeys.clear()
        apiKeys.clear()
    }
}
