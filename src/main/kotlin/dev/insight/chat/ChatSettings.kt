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
import dev.insight.apilog.ApiLogs
import dev.insight.llm.LlmClient
import dev.insight.llm.LlmConfiguration
import dev.insight.llm.LlmProvider

/** Shared by chat and background analyses. Each provider keeps its own options and credentials. */
@Service(Service.Level.APP)
@State(name = "InsightChatSettings", storages = [Storage("insight-chat.xml", roamingType = RoamingType.DISABLED)])
class ChatSettings : SimplePersistentStateComponent<ChatSettings.State>(State()) {
    // Inherit the original fields so existing llama.cpp settings continue to load without migration.
    class State : ProviderState() {
        var provider by enum(LlmProvider.LLAMA_CPP)
        var openAi by property(ProviderState())
        var deepSeek by property(ProviderState())
        var anthropic by property(ProviderState())

        fun options(provider: LlmProvider): ProviderState = when (provider) {
            LlmProvider.LLAMA_CPP -> this
            LlmProvider.OPENAI -> openAi
            LlmProvider.DEEPSEEK -> deepSeek
            LlmProvider.ANTHROPIC -> anthropic
        }

        fun configuration(): LlmConfiguration = options(provider).configuration(provider)
    }

    open class ProviderState : BaseState() {
        var serverUrl by string("")
        var model by string("")
        var requestTimeoutSeconds by property(120)
        var maxRetries by property(2)
        var maxOutputTokens by property(0)
        var cachePrompt by property(true)
        var serviceTier by string("")
        var promptCacheKey by string("")

        fun configuration(provider: LlmProvider) = LlmConfiguration(provider, serverUrl.orEmpty(), model.orEmpty(),
            requestTimeoutSeconds, maxRetries, maxOutputTokens, cachePrompt, serviceTier.orEmpty(), promptCacheKey.orEmpty())
    }

    /** Reading the password safe can block; call on IO outside settings dialogs. */
    fun createClient(): LlmClient {
        val configuration = state.configuration()
        return configuration.createClient(apiKey(configuration.provider), ApiLogs.llm)
    }

    var apiKey: String?
        get() = apiKey(state.provider)
        set(value) = setApiKey(state.provider, value)

    fun apiKey(provider: LlmProvider): String? = PasswordSafe.instance.getPassword(credentials(provider))?.takeIf { it.isNotBlank() }

    fun setApiKey(provider: LlmProvider, value: String?) =
        PasswordSafe.instance.setPassword(credentials(provider), value?.trim()?.takeIf { it.isNotEmpty() })

    companion object {
        private fun credentials(provider: LlmProvider) = CredentialAttributes(generateServiceName("Insight Workbench",
            if (provider == LlmProvider.LLAMA_CPP) "LLM server API key" else "${provider.name} API key"))
        fun getInstance(): ChatSettings = service()
    }
}
