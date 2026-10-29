package dev.insight.agent

import com.intellij.openapi.Disposable

/** What a view follows of a conversation and sends to it. The chat's session implements it, so review tabs need no chat UI. */
interface ChatConversation {
    val messages: List<ChatMessage>
    /** True while a turn runs; [send] is ignored meanwhile. */
    val busy: Boolean
    /** UI callers and notifications run on the EDT. */
    fun send(text: String)
    fun subscribe(listener: () -> Unit): Disposable
    /** Permanently releases an owned conversation, including a turn cancelled before it starts. */
    fun discard()
}
