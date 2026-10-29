package dev.insight.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import dev.insight.agent.ChatConversation
import dev.insight.agent.ChatMessage
import dev.insight.agent.describeChatFailure
import dev.insight.agent.ChatRole
import dev.insight.agent.FileTrace
import dev.insight.agent.FileTraceContext
import dev.insight.agent.ToolCallEvent
import dev.insight.settings.ChatSettings

/** An independently owned conversation, reusable by the sidebar and chart. */
open class ChatSession(
    private val project: Project,
    private val scope: CoroutineScope,
    private val backendFactory: ChatBackendFactory,
    welcome: String? = null,
    private val onClear: () -> Unit = {},
) : ChatConversation {
    private val chart = backendFactory.chartContext()
    private val history = mutableListOf(ChatMessage(ChatRole.NOTICE, welcome ?: if (chart == null)
        "Welcome to Insight. Ask about this project, or open the dashboard to explore the sample visualizations."
        else "Ask about this flow chart. I show the answer on the chart: paths, entry points, data flows and the steps involved."))
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var turn: Job? = null
    private var discarded = false
    final override var busy: Boolean = false
        private set
    final override val messages: List<ChatMessage> get() = history.toList()
    internal val fileTraces = mutableListOf<FileTrace>()
    /** Follow-up questions the latest reply suggested; cleared when a message is sent or the chat is cleared. */
    var followups: List<String> = emptyList()
        private set
    /** Written by suggest_followups during a turn, published when the reply succeeds. */
    @Volatile private var proposed: List<String> = emptyList()
    /** The chart this session explains, for resolving references in replies (EDT); null when references are unsupported. */
    internal val chartReferences: ChartReferences? get() = chart as? ChartReferences

    /** Short description of the active backend for the chat status line. */
    val backendLabel: String
        get() = ChatSettings.getInstance().state.configuration().label

    /** UI callers and notifications run on the EDT; backend work runs on IO. */
    final override fun send(text: String) {
        if (discarded || busy || text.isBlank()) return
        history += ChatMessage(ChatRole.USER, text.trim())
        val fileTrace = FileTrace(text.trim()) {
            withContext(NonCancellable + Dispatchers.EDT) { if (!discarded) notifyViews() }
        }
        fileTraces += fileTrace
        followups = emptyList()
        proposed = emptyList()
        busy = true
        chart?.beginReply()
        notifyViews()
        val request = messages.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
        turn = scope.launch(Dispatchers.EDT) {
            try {
                val reply = withBackgroundProgress(project, replyProgressTitle(text.trim())) {
                    withContext(Dispatchers.IO + FileTraceContext(fileTrace)) {
                        backendFactory.create { proposed = it }.reply(request) { event ->
                            withContext(Dispatchers.EDT) {
                                fileTrace.tool(event)
                                history += ChatMessage(ChatRole.TOOL, describe(event), toolCall = event)
                                notifyViews()
                            }
                        }
                    }
                }
                history += ChatMessage(ChatRole.ASSISTANT, reply, chartReferences?.revision, chartReferences?.storyId)
                followups = proposed
            } catch (e: CancellationException) {
                // The IDE cancels the progress scope; Stop/discard cancel this entire turn instead.
                if (coroutineContext.isActive) history += ChatMessage(ChatRole.NOTICE, "Stopped.")
                throw e
            } catch (e: Exception) {
                LOG.warn("Chat reply failed", e)
                history += ChatMessage(ChatRole.NOTICE, "The response failed: ${describeChatFailure(e)}")
            } finally {
                fileTrace.finish()
                if (discarded) {
                    history.clear()
                    fileTraces.clear()
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
        history += ChatMessage(ChatRole.NOTICE, "Stopped.")
        notifyViews()
    }

    /** Whether the chart changes of the latest reply can be undone; EDT. */
    val chartRestorable: Boolean get() = !busy && chart?.restorable == true

    fun restoreChart() {
        if (busy || chart == null) return
        history += ChatMessage(ChatRole.NOTICE, if (chart.restore()) "Chart restored to before the last reply."
            else "Nothing to restore: this chat made no chart change since, or another chat or a rebuild changed the chart after it.")
        notifyViews()
    }

    /** Shows the function a reply referenced on the chart, or a notice when it is stale or unknown; EDT. */
    fun openReference(revision: Int, node: Int) {
        val notice = chartReferences?.openReference(revision, node) ?: return
        history += ChatMessage(ChatRole.NOTICE, notice)
        notifyViews()
    }

    /** Steps the active story on the chart to [beat] (1-based), or shows a notice when it cannot; EDT. */
    fun openBeat(revision: Int, beat: Int, storyId: Int? = null) {
        val notice = chartReferences?.openBeat(revision, beat, storyId) ?: return
        history += ChatMessage(ChatRole.NOTICE, notice)
        notifyViews()
    }

    fun clear() {
        if (busy) return
        backendFactory.clear()
        onClear()
        history.clear()
        fileTraces.clear()
        followups = emptyList()
        notifyViews()
    }

    /** Permanently releases an owned conversation, including a turn cancelled before it starts. */
    final override fun discard() {
        discarded = true
        backendFactory.dispose()
        turn?.cancel()
        turn = null
        busy = false
        history.clear()
        fileTraces.clear()
        followups = emptyList()
        proposed = emptyList()
        listeners.clear()
    }

    final override fun subscribe(listener: () -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    private fun notifyViews() = listeners.forEach { it() }

    /** The whole turn, including tool calls and retries, shares one native IDE progress task. */
    protected open fun replyProgressTitle(text: String): String = "Insight chat"

    private fun describe(event: ToolCallEvent): String {
        val arguments = event.arguments.entrySet().joinToString(", ") { (key, value) ->
            "$key=${if (value.isJsonPrimitive) value.asString else value.toString()}"
        }.let { if (it.length > 120) it.take(119) + "…" else it }
        val outcome = if (event.failed) " — ${event.result.lineSequence().first().take(160)}" else ""
        return "${event.name}($arguments)$outcome"
    }

    private companion object {
        val LOG = logger<ChatSession>()
    }
}
