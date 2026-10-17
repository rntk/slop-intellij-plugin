package dev.insight.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class ChatService(project: Project, scope: CoroutineScope) : ChatSession(project, scope)

/** An independently owned conversation, reusable by the sidebar and chart. */
open class ChatSession(
    private val project: Project,
    private val scope: CoroutineScope,
    private val chart: ChartChatContext? = null,
    welcome: String? = null,
) {
    private val history = mutableListOf(ChatMessage(ChatMessage.NOTICE, welcome ?: if (chart == null)
        "Welcome to Insight. Ask about this project, or open the dashboard to explore the sample visualizations."
        else "Ask about this flow chart. I show the answer on the chart: paths, entry points, data flows and the steps involved."))
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var turn: Job? = null
    private var discarded = false
    var busy: Boolean = false
        private set
    val messages: List<ChatMessage> get() = history.toList()
    /** Follow-up questions the latest reply suggested; cleared when a message is sent or the chat is cleared. */
    var followups: List<String> = emptyList()
        private set
    /** Written by suggest_followups during a turn, published when the reply succeeds. */
    @Volatile private var proposed: List<String> = emptyList()
    /** The chart this session explains, for resolving references in replies (EDT); null outside the chart chat. */
    internal val chartContext: ChartChatContext? get() = chart

    /** Short description of the active backend for the chat status line. */
    val backendLabel: String
        get() = ChatSettings.getInstance().state.configuration().label

    /** UI callers and notifications run on the EDT; backend work runs on IO. */
    fun send(text: String) {
        if (discarded || busy || text.isBlank()) return
        history += ChatMessage(ChatMessage.USER, text.trim())
        followups = emptyList()
        proposed = emptyList()
        busy = true
        chart?.beginReply()
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
                history += ChatMessage(ChatMessage.ASSISTANT, reply, chart?.revision, chart?.storyId)
                followups = proposed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Chat reply failed", e)
                history += ChatMessage(ChatMessage.NOTICE, "The response failed: ${describeChatFailure(e)}")
            } finally {
                if (discarded) {
                    history.clear()
                    followups = emptyList()
                    proposed = emptyList()
                }
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

    /** Whether the chart changes of the latest reply can be undone; EDT. */
    val chartRestorable: Boolean get() = !busy && chart?.restorable == true

    fun restoreChart() {
        if (busy || chart == null) return
        history += ChatMessage(ChatMessage.NOTICE, if (chart.restore()) "Chart restored to before the last reply."
            else "Nothing to restore: this chat made no chart change since, or another chat or a rebuild changed the chart after it.")
        notifyViews()
    }

    /** Shows the function a reply referenced on the chart, or a notice when it is stale or unknown; EDT. */
    fun openReference(revision: Int, node: Int) {
        val notice = chart?.openReference(revision, node) ?: return
        history += ChatMessage(ChatMessage.NOTICE, notice)
        notifyViews()
    }

    /** Steps the active story on the chart to [beat] (1-based), or shows a notice when it cannot; EDT. */
    fun openBeat(revision: Int, beat: Int, storyId: Int? = null) {
        val notice = chart?.openBeat(revision, beat, storyId) ?: return
        history += ChatMessage(ChatMessage.NOTICE, notice)
        notifyViews()
    }

    fun clear() {
        if (busy) return
        history.clear()
        followups = emptyList()
        notifyViews()
    }

    /** Permanently releases an owned conversation, including a turn cancelled before it starts. */
    internal fun discard() {
        discarded = true
        turn?.cancel()
        turn = null
        busy = false
        history.clear()
        followups = emptyList()
        proposed = emptyList()
        listeners.clear()
    }

    fun subscribe(listener: () -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    private fun notifyViews() = listeners.forEach { it() }

    /** Chart tools whose follow-up suggestions belong to this session. */
    internal fun sessionChartTools(chart: ChartChatContext) = chartTools(chart) { proposed = it }

    /** Runs on IO: reading the API key from the password safe can block. */
    internal open fun createBackend(): ChatBackend {
        val client = ChatSettings.getInstance().createClient()
        return if (chart == null) LlmChatBackend(client::complete, projectTools(project), projectChatSystemPrompt(project.name))
        else LlmChatBackend(client::complete, sessionChartTools(chart) + projectTools(project).filter { it.definition.name in setOf("read_file", "list_files", "search_text") },
            chartChatSystemPrompt(project.name), maxToolRounds = 100, context = chart::snapshot)
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
