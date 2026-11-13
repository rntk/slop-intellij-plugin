# Code review flow

Code Review is a read-only workspace for exploring an existing codebase scope. After choosing a scope and optionally scheduling background generation, readers navigate the code through hierarchical summaries, topics, vocabulary views, a pannable Code wall, and IDE-integrated source views.

Focused tabs and wall chats let readers ask questions grounded in the captured scope, while still allowing supporting reads from the wider project.

Entry points for further exploration:

- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — review workspace
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-scope review experience
