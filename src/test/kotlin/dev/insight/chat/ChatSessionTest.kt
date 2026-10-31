package dev.insight.chat

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.*
import dev.insight.agent.ChatBackend
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import dev.insight.agent.ToolCallEvent

class ChatSessionTest : BasePlatformTestCase() {
    fun `test clear and discard release the injected factory resources`() {
        val scope = CoroutineScope(SupervisorJob())
        val lifecycle = mutableListOf<String>()
        val session = ChatSession(project, scope, object : ChatBackendFactory {
            override fun create(suggestFollowups: (List<String>) -> Unit): ChatBackend =
                error("Lifecycle operations must not create a backend")

            override fun clear() { lifecycle += "clear" }
            override fun dispose() { lifecycle += "dispose" }
        }, onClear = { lifecycle += "onClear" })
        try {
            session.clear()
            session.discard()
            assertEquals(listOf("clear", "onClear", "dispose"), lifecycle)
            assertTrue(session.messages.isEmpty())
        } finally { scope.cancel() }
    }

    fun `test cancelling progress clears busy state and allows another turn`() {
        val scope = CoroutineScope(SupervisorJob())
        val backendJob = CompletableDeferred<Job>()
        var requests = 0
        var factories = 0
        val session = ChatSession(project, scope, backendFactory = { _ ->
            factories++
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    if (++requests == 1) {
                        backendJob.complete(currentCoroutineContext().job)
                        awaitCancellation()
                    }
                    return "Second reply"
                }
            }
        })
        try {
            session.send("First question")
            PlatformTestUtil.waitWithEventsDispatching("Backend should start", { backendJob.isCompleted }, 5)
            // Native progress cancels a child scope, leaving the owning conversation scope alive.
            runBlocking { backendJob.await() }.cancel()
            PlatformTestUtil.waitWithEventsDispatching("Progress cancellation should end the turn", { !session.busy }, 5)
            assertTrue(scope.isActive)
            assertEquals("Stopped.", session.messages.last().text)
            assertTrue(session.messages.none { it.role == ChatRole.ASSISTANT })

            session.send("Second question")
            PlatformTestUtil.waitWithEventsDispatching("The next turn should finish", { !session.busy }, 5)
            assertEquals("Second reply", session.messages.last().text)
            assertEquals("Each turn gets a fresh backend", 2, factories)
        } finally {
            session.discard()
            scope.cancel()
        }
    }

    fun `test stop cancels the backend and leaves one stopped notice`() {
        val scope = CoroutineScope(SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val session = ChatSession(project, scope, backendFactory = { _ ->
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
        })
        try {
            session.send("Question")
            PlatformTestUtil.waitWithEventsDispatching("Backend should start", { started.isCompleted }, 5)
            session.stop()
            PlatformTestUtil.waitWithEventsDispatching("Stop should finish", { cancelled.isCompleted && !session.busy }, 5)
            assertEquals(1, session.messages.count { it.text == "Stopped." })
            assertTrue(session.messages.none { it.role == ChatRole.ASSISTANT })
        } finally {
            session.discard()
            scope.cancel()
        }
    }

    fun `test discarding a running conversation cancels its backend and forgets its history`() {
        val scope = CoroutineScope(SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val session = ChatSession(project, scope, backendFactory = { _ ->
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
        })
        try {
            session.send("Private review topic")
            PlatformTestUtil.waitWithEventsDispatching("Backend should start", { started.isCompleted }, 5 /* seconds */)
            session.discard()
            assertTrue(session.messages.isEmpty())
            assertFalse(session.busy)
            PlatformTestUtil.waitWithEventsDispatching("Backend should be cancelled", { cancelled.isCompleted }, 5 /* seconds */)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertTrue(session.messages.isEmpty())
            assertTrue(session.followups.isEmpty())
            session.send("A closed conversation must stay closed")
            assertTrue(session.messages.isEmpty())
        } finally { scope.cancel() }
    }

    fun `test discarding a queued turn clears history even when its scope is already cancelled`() {
        val scope = CoroutineScope(SupervisorJob())
        var requested = false
        val session = ChatSession(project, scope, backendFactory = { _ ->
            requested = true
            error("A discarded turn must never reach its backend")
        })
        try {
            scope.cancel()
            session.send("Close immediately")
            session.discard()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertFalse(requested)
            assertFalse(session.busy)
            assertTrue(session.messages.isEmpty())
        } finally { scope.cancel() }
    }

    fun `test restored messages follow the welcome and reach the backend`() {
        val scope = CoroutineScope(SupervisorJob())
        val requests = mutableListOf<List<ChatMessage>>()
        val session = ChatSession(project, scope, backendFactory = { _ ->
            object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    requests += messages
                    return "Again."
                }
            }
        }, welcome = "Ask about run.", restored = listOf(ChatMessage(ChatRole.NOTICE, "Ask about run."),
            ChatMessage(ChatRole.USER, "Why?"), ChatMessage(ChatRole.ASSISTANT, "Because."), ChatMessage(ChatRole.NOTICE, "Stopped.")))
        try {
            assertEquals(listOf("Ask about run.", "Why?", "Because.", "Stopped."), session.messages.map { it.text })
            session.send("And?")
            PlatformTestUtil.waitWithEventsDispatching("Reply should arrive", { !session.busy }, 5 /* seconds */)
            assertEquals(listOf("Why?", "Because.", "And?"), requests.single().map { it.text })
        } finally { scope.cancel() }
    }
}
