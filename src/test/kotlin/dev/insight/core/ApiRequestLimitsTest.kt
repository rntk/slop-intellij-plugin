package dev.insight.core

import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionsClient
import dev.insight.llm.AnthropicClient
import dev.insight.llm.CompletionRequest
import dev.insight.llm.DeepSeekClient
import dev.insight.llm.LlamaCppClient
import dev.insight.llm.LlmMessage
import dev.insight.llm.OpenAiClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

class ApiRequestLimitsTest {
    @get:Rule val timeout = Timeout.seconds(10)
    private val transport = ControlledTransport()
    private val llmRequest = CompletionRequest(listOf(LlmMessage.user("Hi")))
    private val decisionRequest = DecisionRequest("Hello", mapOf("ok" to DecisionQuestion.Noul("OK?")))
    private val baseUrl = "http://localhost"
    private val decisionUrl = baseUrl

    @Before fun limitRequests() {
        ApiRequestLimits.llm.resize(1)
        ApiRequestLimits.decisions.resize(1)
    }

    @After fun restoreDefaults() {
        ApiRequestLimits.llm.resize(ApiRequestLimits.DEFAULT_MAX_CONCURRENT_REQUESTS)
        ApiRequestLimits.decisions.resize(ApiRequestLimits.DEFAULT_MAX_CONCURRENT_REQUESTS)
    }

    @Test fun `all LLM providers and client instances share the same budget`() = runBlocking {
        ApiRequestLimits.llm.resize(2)
        val clients = listOf(
            LlamaCppClient(baseUrl, httpClient = transport),
            OpenAiClient("key", "model", baseUrl, httpClient = transport),
            DeepSeekClient("key", "model", baseUrl, httpClient = transport),
            AnthropicClient("key", "model", baseUrl, httpClient = transport),
        )
        val jobs = clients.map { client -> async(start = CoroutineStart.UNDISPATCHED) { client.complete(llmRequest) } }
        val first = transport.sent.receive()
        val second = transport.sent.receive()
        assertTrue(transport.sent.tryReceive().isFailure)
        first.respond()
        transport.sent.receive().respond()
        transport.sent.receive().respond()
        second.respond()
        jobs.forEach { assertEquals("Hi", it.await().content) }
    }

    @Test fun `Decisions clients share a separate budget and queue cancellation sends nothing`() = runBlocking {
        val llm = async(start = CoroutineStart.UNDISPATCHED) {
            LlamaCppClient(baseUrl, httpClient = transport).complete(llmRequest)
        }
        val llmCall = transport.sent.receive()
        val log = ApiTrafficLog("Decisions")
        val jobs = List(3) {
            async(start = CoroutineStart.UNDISPATCHED) {
                DecisionsClient("key", decisionUrl, httpClient = transport, traffic = log).evaluate(decisionRequest)
            }
        }
        val first = transport.sent.receive()
        assertEquals(URI("$decisionUrl/v1/systemone"), first.request.uri())
        assertTrue(transport.sent.tryReceive().isFailure)
        assertEquals(1, log.entries().size)
        jobs[1].cancelAndJoin()
        assertEquals(1, log.entries().size)
        first.respond()
        transport.sent.receive().respond()
        jobs[0].await()
        jobs[2].await()
        assertEquals(2, log.entries().size)
        llmCall.respond()
        assertEquals("Hi", llm.await().content)
    }

    @Test fun `cancelling an active request cancels HTTP and frees its slot`() = runBlocking {
        for (decisions in listOf(false, true)) {
            val invoke: suspend () -> Unit = if (decisions) ({
                DecisionsClient("key", decisionUrl, httpClient = transport).evaluate(decisionRequest)
            }) else ({ LlamaCppClient(baseUrl, httpClient = transport).complete(llmRequest) })
            val first = async(start = CoroutineStart.UNDISPATCHED) { invoke() }
            val call = transport.sent.receive()
            val next = async(start = CoroutineStart.UNDISPATCHED) { invoke() }
            assertTrue(transport.sent.tryReceive().isFailure)
            first.cancelAndJoin()
            assertTrue(call.future.isCancelled)
            transport.sent.receive().respond()
            next.await()
        }
    }

    @Test fun `retry backoff releases capacity and retries rejoin the budget`() = runBlocking {
        for (decisions in listOf(false, true)) {
            val log = ApiTrafficLog("test")
            val invoke: suspend () -> Unit = if (decisions) ({
                DecisionsClient("key", decisionUrl, maxRetries = 1, httpClient = transport, traffic = log).evaluate(decisionRequest)
            }) else ({
                LlamaCppClient(baseUrl, maxRetries = 1, httpClient = transport, traffic = log).complete(llmRequest)
            })
            val retrying = async(start = CoroutineStart.UNDISPATCHED) { invoke() }
            val first = transport.sent.receive()
            val other = async(start = CoroutineStart.UNDISPATCHED) { invoke() }
            first.respond(429)
            transport.sent.receive().respond()
            other.await()
            assertEquals(listOf(0, 0), log.entries().map { it.attempt })
            transport.sent.receive().respond()
            retrying.await()
            assertEquals(listOf(0, 0, 1), log.entries().map { it.attempt })
        }
    }

    private class Pending(val request: HttpRequest, val future: CompletableFuture<HttpResponse<String>>) {
        fun respond(status: Int = 200) {
            val body = when {
                status != 200 -> "busy"
                request.uri().path.endsWith("/systemone") -> """{"model":"test","answers":{"ok":{"type":"noul","noul":0.9}},"usage":{"input_tokens":1,"output_tokens":1}}"""
                request.uri().path.endsWith("/messages") -> """{"stop_reason":"end_turn","content":[{"type":"text","text":"Hi"}]}"""
                else -> """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Hi"}}]}"""
            }
            future.complete(object : HttpResponse<String> {
                override fun statusCode() = status
                override fun request() = request
                override fun previousResponse() = Optional.empty<HttpResponse<String>>()
                override fun headers() = HttpHeaders.of(emptyMap()) { _, _ -> true }
                override fun body() = body
                override fun sslSession() = Optional.empty<SSLSession>()
                override fun uri() = request.uri()
                override fun version() = HttpClient.Version.HTTP_1_1
            })
        }
    }

    /** Leaves HTTP attempts pending until the test responds; no timing or external server needed. */
    private class ControlledTransport : HttpClient() {
        val sent = Channel<Pending>(Channel.UNLIMITED)
        override fun cookieHandler() = Optional.empty<CookieHandler>()
        override fun connectTimeout() = Optional.of(Duration.ofSeconds(10))
        override fun followRedirects() = Redirect.NEVER
        override fun proxy() = Optional.empty<ProxySelector>()
        override fun sslContext(): SSLContext = SSLContext.getDefault()
        override fun sslParameters() = SSLParameters()
        override fun authenticator() = Optional.empty<Authenticator>()
        override fun version() = Version.HTTP_1_1
        override fun executor() = Optional.empty<Executor>()
        override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
            error("Only async requests are supported")

        @Suppress("UNCHECKED_CAST")
        override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> {
            val future = CompletableFuture<HttpResponse<String>>()
            check(sent.trySend(Pending(request, future)).isSuccess)
            return future as CompletableFuture<HttpResponse<T>>
        }

        override fun <T> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>,
            push: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> = sendAsync(request, handler)
    }
}
