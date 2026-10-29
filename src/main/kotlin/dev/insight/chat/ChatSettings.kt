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
import dev.insight.core.ApiRequestLimits
import dev.insight.core.ApiTrafficLog
import dev.insight.core.http.apiUri
import dev.insight.decisions.DEFAULT_DECISIONS_URL
import dev.insight.decisions.DecisionsClient
import dev.insight.llm.LlmClient
import dev.insight.llm.LlmConfiguration
import dev.insight.llm.LlmProvider
import java.time.Duration

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
        var maxConcurrentLlmRequests by property(ApiRequestLimits.DEFAULT_MAX_CONCURRENT_REQUESTS)
        var maxConcurrentDecisionRequests by property(ApiRequestLimits.DEFAULT_MAX_CONCURRENT_REQUESTS)
        /** Server address; blank uses [DEFAULT_DECISIONS_URL]. */
        var decisionsUrl by string("")

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
        var requestTimeoutSeconds by property(600)
        var maxRetries by property(6)
        var maxOutputTokens by property(dev.insight.llm.DEFAULT_OUTPUT_TOKENS)
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

    /** Reading the password safe can block; call on IO outside settings dialogs. */
    fun createDecisionsClient(requestTimeout: Duration = Duration.ofSeconds(120), maxRetries: Int = 4): DecisionsClient =
        decisionsClient(state.decisionsUrl.orEmpty(), decisionsApiKey, requestTimeout, maxRetries, ApiLogs.decisions)

    override fun loadState(state: State) {
        super.loadState(state)
        updateRequestLimits()
    }

    /** Keeps the same limiters alive so Apply also affects clients already running. */
    internal fun updateRequestLimits() {
        ApiRequestLimits.llm.resize(state.maxConcurrentLlmRequests.coerceAtLeast(1))
        ApiRequestLimits.decisions.resize(state.maxConcurrentDecisionRequests.coerceAtLeast(1))
    }

    var apiKey: String?
        get() = apiKey(state.provider)
        set(value) = setApiKey(state.provider, value)

    fun apiKey(provider: LlmProvider): String? = PasswordSafe.instance.getPassword(credentials(provider))?.takeIf { it.isNotBlank() }

    fun setApiKey(provider: LlmProvider, value: String?) =
        PasswordSafe.instance.setPassword(credentials(provider), value?.trim()?.takeIf { it.isNotEmpty() })

    var decisionsApiKey: String?
        get() = PasswordSafe.instance.getPassword(decisionsCredentials)?.takeIf { it.isNotBlank() }
        set(value) = PasswordSafe.instance.setPassword(decisionsCredentials, value?.trim()?.takeIf { it.isNotEmpty() })

    companion object {
        private fun credentials(provider: LlmProvider) = CredentialAttributes(generateServiceName("Insight Workbench",
            if (provider == LlmProvider.LLAMA_CPP) "LLM server API key" else "${provider.name} API key"))
        private val decisionsCredentials = CredentialAttributes(generateServiceName("Insight Workbench", "Decisions API key"))

        /** Validates without making a request. Also used by the settings dialog on Apply. Blank [url] uses the default. */
        fun decisionsClient(url: String, apiKey: String?, requestTimeout: Duration = Duration.ofSeconds(120),
                            maxRetries: Int = 4, traffic: ApiTrafficLog? = null): DecisionsClient {
            val key = apiKey?.trim()?.ifEmpty { null }
            val trimmed = url.trim().ifEmpty { DEFAULT_DECISIONS_URL }
            val baseUrl = if ("://" in trimmed) trimmed else "http://$trimmed"
            val endpoint = apiUri(baseUrl, "/systemone")
            // Same rule as LLM keys: a real key only travels over HTTPS or to localhost.
            require(key == null || endpoint.scheme == "https" || endpoint.host in localHosts) {
                "Refusing to send an API key to a non-HTTPS server URL"
            }
            // DecisionsClient requires a bearer token; local servers accept any, so a blank key sends "local".
            return DecisionsClient(key ?: "local", baseUrl, requestTimeout, maxRetries, traffic = traffic)
        }
        private val localHosts = setOf("localhost", "127.0.0.1", "[::1]", "::1")

        fun getInstance(): ChatSettings = service()
    }
}
