package dev.insight.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class ChatService(private val scope: CoroutineScope) {
    private val backend: ChatBackend = DemoChatBackend()
    private val history = mutableListOf(ChatMessage("assistant", "Welcome to Insight. Ask about this scaffold, or open the dashboard to explore the sample visualizations."))
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    var busy: Boolean = false
        private set
    val messages: List<ChatMessage> get() = history.toList()

    /** UI callers and notifications run on the EDT; backend work runs on IO. */
    fun send(text: String) {
        if (busy || text.isBlank()) return
        history += ChatMessage("user", text.trim())
        busy = true
        notifyViews()
        val request = messages
        scope.launch(Dispatchers.EDT) {
            try {
                val reply = withContext(Dispatchers.IO) { backend.reply(request) }
                history += ChatMessage("assistant", reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                history += ChatMessage("assistant", "The response failed. Please try again.")
            } finally {
                busy = false
                notifyViews()
            }
        }
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
}
