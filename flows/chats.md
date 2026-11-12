# Chats flow

Every interactive chat (the sidebar, chart and schematic chats, canvas chats, the review tabs' chats and the diff and code walls' file and wall-wide chats) is one conversation owned by a session and answered one turn at a time by a backend over a shared tool loop. The backend decides what a chat knows: its system prompt and the data pinned in it, the fresh snapshot attached to each question, and the tools the model may call.

All of them spend the context window the same way. A chat budget, chosen by profile, turns the provider's window into shares: pinned data, the per-question snapshot, and earlier turns, which together always leave room for the policy, the question and the tool rounds of the current turn. Pinned data that runs past its share says where the tools can read on.

Earlier turns reach the model as text only: tool output and notices are never replayed. The newest turns go whole, user and assistant alternating, with a placeholder where a turn got no reply. Turns beyond the history share fold into a short digest, oldest first and the first turn fullest, that names the tools each used so the model can re-read precisely; the digest rides in the system prompt so in-turn eviction cannot lose it. Within a turn, a moving window evicts older rounds first and tells the model what it omitted, and a request the server still rejects shrinks and retries.

Entry points for further exploration:

- [ChatBudget.kt](../src/main/kotlin/dev/insight/agent/ChatBudget.kt) and [ConversationMemory.kt](../src/main/kotlin/dev/insight/agent/ConversationMemory.kt) — budget profiles, history assembly and the digest
- [ToolChat.kt](../src/main/kotlin/dev/insight/agent/ToolChat.kt) and [ChatContextWindow.kt](../src/main/kotlin/dev/insight/agent/ChatContextWindow.kt) — the tool loop and in-turn eviction
- [LlmChatBackend.kt](../src/main/kotlin/dev/insight/chat/LlmChatBackend.kt) and [ChatSession.kt](../src/main/kotlin/dev/insight/chat/ChatSession.kt) — one turn's request, and the conversation the views follow
