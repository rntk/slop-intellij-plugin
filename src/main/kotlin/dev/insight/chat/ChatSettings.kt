package dev.insight.chat

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import dev.insight.llm.chatCompletionsUri

/** LLM server used by the chat. An empty [State.serverUrl] keeps the local demo backend. */
@Service(Service.Level.APP)
@State(name = "InsightChatSettings", storages = [Storage("insight-chat.xml", roamingType = RoamingType.DISABLED)])
class ChatSettings : SimplePersistentStateComponent<ChatSettings.State>(State()) {
    class State : BaseState() {
        var serverUrl by string("")
        var model by string("")
        var requestTimeoutSeconds by property(120)
    }

    val configured: Boolean get() = !state.serverUrl.isNullOrBlank()

    /** Stored in the IDE password safe, not in the settings file. Reading it can block; avoid the EDT outside dialogs. */
    var apiKey: String?
        get() = PasswordSafe.instance.getPassword(credentials)?.takeIf { it.isNotBlank() }
        set(value) = PasswordSafe.instance.setPassword(credentials, value?.trim()?.takeIf { it.isNotEmpty() })

    companion object {
        private val credentials = CredentialAttributes(generateServiceName("Insight Workbench", "LLM server API key"))
        fun getInstance(): ChatSettings = service()
    }
}

class ChatSettingsConfigurable : BoundConfigurable("Insight Chat") {
    private val settings = ChatSettings.getInstance()
    private var apiKey = ""
    private var storedApiKey = ""

    override fun createPanel(): DialogPanel {
        storedApiKey = settings.apiKey.orEmpty()
        apiKey = storedApiKey
        val state = settings.state
        return panel {
            group("llama.cpp Server") {
                row("Server URL:") {
                    textField()
                        .bindText({ state.serverUrl.orEmpty() }, { state.serverUrl = it.trim() })
                        .columns(COLUMNS_LARGE)
                        .comment("For example http://127.0.0.1:8080 for <code>llama-server</code>. Any OpenAI-compatible server works. Leave empty to use the local demo.")
                        .validationOnApply {
                            val error = it.text.takeIf { text -> text.isNotBlank() }
                                ?.let { text -> runCatching { chatCompletionsUri(text) }.exceptionOrNull()?.message }
                            error?.let { message -> error(message) }
                        }
                }
                row("Model:") {
                    textField()
                        .bindText({ state.model.orEmpty() }, { state.model = it.trim() })
                        .comment("Optional. llama-server answers with its loaded model when this is empty.")
                }
                row("API key:") {
                    passwordField()
                        .bindText(::apiKey)
                        .comment("Optional; matches <code>llama-server --api-key</code>. Stored in the IDE password safe and sent only over HTTPS or to localhost.")
                }
                row("Request timeout (seconds):") {
                    intTextField(10..3600).bindIntText({ state.requestTimeoutSeconds }, { state.requestTimeoutSeconds = it })
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        // Only touch the password safe on change; some OS keychains prompt on every write.
        if (apiKey != storedApiKey) {
            settings.apiKey = apiKey
            storedApiKey = apiKey
        }
    }
}
