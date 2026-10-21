package dev.insight.chat

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.*

class ChatSessionTest : BasePlatformTestCase() {
    fun `test cancelling progress clears busy state and allows another turn`() {
        val scope = CoroutineScope(SupervisorJob())
        val backendJob = CompletableDeferred<Job>()
        var requests = 0
        val session = object : ChatSession(project, scope) {
            override fun createBackend() = object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    if (++requests == 1) {
                        backendJob.complete(currentCoroutineContext().job)
                        awaitCancellation()
                    }
                    return "Second reply"
                }
            }
        }
        try {
            session.send("First question")
            PlatformTestUtil.waitWithEventsDispatching("Backend should start", { backendJob.isCompleted }, 5)
            // Native progress cancels a child scope, leaving the owning conversation scope alive.
            runBlocking { backendJob.await() }.cancel()
            PlatformTestUtil.waitWithEventsDispatching("Progress cancellation should end the turn", { !session.busy }, 5)
            assertTrue(scope.isActive)
            assertEquals("Stopped.", session.messages.last().text)
            assertTrue(session.messages.none { it.role == ChatMessage.ASSISTANT })

            session.send("Second question")
            PlatformTestUtil.waitWithEventsDispatching("The next turn should finish", { !session.busy }, 5)
            assertEquals("Second reply", session.messages.last().text)
        } finally {
            session.discard()
            scope.cancel()
        }
    }

    fun `test stop cancels the backend and leaves one stopped notice`() {
        val scope = CoroutineScope(SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val session = object : ChatSession(project, scope) {
            override fun createBackend() = object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
        }
        try {
            session.send("Question")
            PlatformTestUtil.waitWithEventsDispatching("Backend should start", { started.isCompleted }, 5)
            session.stop()
            PlatformTestUtil.waitWithEventsDispatching("Stop should finish", { cancelled.isCompleted && !session.busy }, 5)
            assertEquals(1, session.messages.count { it.text == "Stopped." })
            assertTrue(session.messages.none { it.role == ChatMessage.ASSISTANT })
        } finally {
            session.discard()
            scope.cancel()
        }
    }

    fun `test discarding a running conversation cancels its backend and forgets its history`() {
        val scope = CoroutineScope(SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val session = object : ChatSession(project, scope) {
            override fun createBackend() = object : ChatBackend {
                override suspend fun reply(messages: List<ChatMessage>, onToolCall: suspend (ToolCallEvent) -> Unit): String {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }
        }
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
        val session = object : ChatSession(project, scope) {
            override fun createBackend(): ChatBackend {
                requested = true
                error("A discarded turn must never reach its backend")
            }
        }
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
}
