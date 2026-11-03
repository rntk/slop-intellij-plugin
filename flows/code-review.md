# Code review flow

Code Review is a read-only workspace for exploring an existing codebase. It opens in Split view with the project root as its scope. A user can choose another scope, then use conversation, summaries, topics, and source views to understand how that code is organized and what it does. Assistant explanations stay tied to the selected code so readers can inspect the source themselves.

A file, folder or summary entry can be taken to chat: a new review tab whose conversation is focused on it, with its summaries and source in context, while the rest of the scope stays reachable on demand.

Chat can also read current project files outside the review scope for supporting context, keeping that context distinct from the captured code.

A Words view gives a compact picture of the vocabulary in scope: the most frequent words as a cloud, sized by frequency. Selecting a word opens the files where it appears. Like the diff Words view, it is computed from the captured code and does not depend on an assistant response.

Source views offer the IDE's declaration navigation and usage search when the captured file matches current project source.

Hierarchy views place the chart beside source blocks. Hovering or selecting a topic or summary shows one block for each captured file within that entry.

Entry points for further exploration:

- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — review workspace
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-scope review experience
