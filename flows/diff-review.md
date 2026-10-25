# Diff review flow

Diff Review is a read-only workspace for understanding a code change. It presents a comparison and its changed files, with independent review sessions for focused exploration. Conversation, notes, summaries, topics, and other generated views help readers organize questions and connect evidence to the change. A changed file, folder or summary entry can be taken to chat: a new session focused on it, with its summaries and diff in context and the rest of the change reachable on demand.

The workspace is separate from the application graph, though some views can relate changed code to broader structure. Interpretations are grounded in the comparison and can be checked against the source and diff.

Chat can also read current project files outside the change for supporting context, keeping that context distinct from the comparison.

Entry points for further exploration:

- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — review workspace
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — focused review session
