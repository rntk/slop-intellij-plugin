# Code review flow

Code Review is a read-only workspace for understanding existing code with AI assistance. It is the counterpart of Diff Review for a codebase instead of a change: the same style of review workspace, scoped to the code the user chooses to explore.

The user picks a scope — a folder, or a single entry file together with the code it depends on — and the plugin loads a snapshot of the text files in that scope. Each review tab is an independent session over that snapshot, optionally narrowed to a subset of files. Within a tab, the user chats with an assistant that can only read the snapshot; it answers with citations and can place explanatory notes and source excerpts in the workspace for closer reading.

Two generated views help navigate a large scope: hierarchical summaries describe the scope from the whole down to individual files, and topics group files by responsibility rather than by folder. Summaries and topics are tied to file contents, so they survive reloads for code that has not changed. Code decides what is read and in what order; the assistant only writes text.

Entry points for further exploration:

- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — tool window entry point: scope selection, file tree, and review tabs
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-tab session: chat, workspace, summaries, and topics

From there, neighbouring files in the same package cover loading, tools, and state; shared workspace and summary machinery lives with Diff Review.
