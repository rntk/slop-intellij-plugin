package dev.insight.chat

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.*

class ChatSessionTest : BasePlatformTestCase() {
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
            PlatformTestUtil.waitWithEventsDispatching("Backend should start", { started.isCompleted }, 5000)
            session.discard()
            assertTrue(session.messages.isEmpty())
            assertFalse(session.busy)
            PlatformTestUtil.waitWithEventsDispatching("Backend should be cancelled", { cancelled.isCompleted }, 5000)
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
