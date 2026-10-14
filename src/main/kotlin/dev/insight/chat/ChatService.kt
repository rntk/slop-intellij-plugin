package dev.insight.chat

import dev.insight.apilog.ApiLogs
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.insight.core.Endpoints
import dev.insight.llm.LlamaCppClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class ChatService(project: Project, scope: CoroutineScope) : ChatSession(project, scope)

/** An independently owned conversation, reusable by the sidebar and chart. */
open class ChatSession(
    private val project: Project,
    private val scope: CoroutineScope,
    private val chart: ChartChatContext? = null,
) {
    private val history = mutableListOf(ChatMessage(ChatMessage.NOTICE, if (chart == null)
        "Welcome to Insight. Ask about this project, or open the dashboard to explore the sample visualizations."
        else "Ask about this flow chart. I can highlight steps, add project functions, and show calls or callers."))
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var turn: Job? = null
    var busy: Boolean = false
        private set
    val messages: List<ChatMessage> get() = history.toList()

    /** Short description of the active backend for the chat status line. */
    val backendLabel: String
        get() = ChatSettings.getInstance().state.let { state ->
            "LLM · ${state.model?.takeIf { it.isNotBlank() } ?: Endpoints.LLM_BASE_URL}"
        }

    /** UI callers and notifications run on the EDT; backend work runs on IO. */
    fun send(text: String) {
        if (busy || text.isBlank()) return
        history += ChatMessage(ChatMessage.USER, text.trim())
        busy = true
        notifyViews()
        val request = messages.filter { it.role == ChatMessage.USER || it.role == ChatMessage.ASSISTANT }
        turn = scope.launch(Dispatchers.EDT) {
            try {
                val reply = withContext(Dispatchers.IO) {
                    createBackend().reply(request) { event ->
                        withContext(Dispatchers.EDT) {
                            history += ChatMessage(ChatMessage.TOOL, describe(event))
                            notifyViews()
                        }
                    }
                }
                history += ChatMessage(ChatMessage.ASSISTANT, reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Chat reply failed", e)
                history += ChatMessage(ChatMessage.NOTICE, "The response failed: ${describeChatFailure(e)}")
            } finally {
                busy = false
                turn = null
                notifyViews()
            }
        }
    }

    /** Cancels the running turn, including its in-flight HTTP request. */
    fun stop() {
        val running = turn ?: return
        running.cancel()
        history += ChatMessage(ChatMessage.NOTICE, "Stopped.")
        notifyViews()
    }

    fun clear() {
        if (busy) return
        history.clear()
        notifyViews()
    }

    fun subscribe(listener: () -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    private fun notifyViews() = listeners.forEach { it() }

    /** Runs on IO: reading the API key from the password safe can block. */
    private fun createBackend(): ChatBackend {
        val settings = ChatSettings.getInstance()
        val state = settings.state
        // Host is hardcoded for now (see Endpoints); model, timeout, and key still come from settings.
        val client = LlamaCppClient(
            baseUrl = Endpoints.LLM_BASE_URL,
            model = state.model.orEmpty(),
            apiKey = settings.apiKey,
            requestTimeout = Duration.ofSeconds(state.requestTimeoutSeconds.coerceIn(10, 3600).toLong()),
            traffic = ApiLogs.llm,
        )
        return if (chart == null) LlmChatBackend(client::complete, projectTools(project), projectChatSystemPrompt(project.name))
        else LlmChatBackend(client::complete, chartTools(chart) + projectTools(project).filter { it.definition.name in setOf("read_file", "list_files", "search_text") },
            chartChatSystemPrompt(project.name), context = chart::snapshot)
    }

    private fun describe(event: ToolCallEvent): String {
        val arguments = event.arguments.entrySet().joinToString(", ") { (key, value) ->
            "$key=${if (value.isJsonPrimitive) value.asString else value.toString()}"
        }.let { if (it.length > 120) it.take(119) + "…" else it }
        val outcome = if (event.failed) " — ${event.result.lineSequence().first().take(160)}" else ""
        return "${event.name}($arguments)$outcome"
    }

    private companion object {
        val LOG = logger<ChatService>()
    }
}
