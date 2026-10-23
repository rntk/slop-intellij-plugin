# Code review flow

Code Review is a read-only workspace for exploring an existing codebase. A user chooses a project scope, then uses conversation, summaries, topics, and source views to understand how that code is organized and what it does. Assistant explanations stay tied to the selected code so readers can inspect the source themselves.

Entry points for further exploration:

- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — review workspace
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-scope review experience
