# Chat flow

Insight Chat is a read-only guide to the open project. A user asks a question, and the assistant explores relevant project context before responding. In chart conversations, it can also inspect and adjust the chart to make relationships easier to follow; it does not modify project source.

Entry points for further exploration:

- [ChatService.kt](../src/main/kotlin/dev/insight/chat/ChatService.kt) — conversation flow
- [ChatToolWindowFactory.kt](../src/main/kotlin/dev/insight/chat/ChatToolWindowFactory.kt) — chat interface
