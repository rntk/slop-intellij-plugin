# Chats flow

Every interactive chat across the plugin—sidebar, chart, canvas, review tabs, and walls—is a session-owned conversation answered one turn at a time over a shared tool loop. Each chat's backend decides its system prompt, pinned context, per-question snapshot, and available read-only tools.

All chats manage the model's context window through a shared budget that balances pinned data, snapshots, conversation history, and in-turn tool rounds, compacting or evicting older context when needed.

Entry points for further exploration:

- [ChatSession.kt](../src/main/kotlin/dev/insight/chat/ChatSession.kt) — session conversation lifecycle
- [ToolChat.kt](../src/main/kotlin/dev/insight/agent/ToolChat.kt) — shared tool loop and context management
