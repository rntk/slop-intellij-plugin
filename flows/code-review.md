# Code review flow

Code Review is a read-only workspace for understanding existing code with AI assistance. It is the counterpart of Diff Review for a codebase instead of a change: the same style of review workspace, scoped to the code the user chooses to explore.

The user picks a scope — a folder, or a single entry file together with the code it depends on — and the plugin loads a snapshot of the text files in that scope. Each review tab is an independent session over that snapshot, optionally narrowed to a subset of files. Within a tab, the user chats with an assistant that can only read the snapshot; it answers with citations and can place explanatory notes and source excerpts in the workspace for closer reading.

Two generated views help navigate a large scope: hierarchical summaries describe the scope from the whole down to individual files, and topics group files by responsibility rather than by folder. Summaries and topics are tied to file contents, so they survive reloads for code that has not changed. Code decides what is read and in what order; the assistant only writes text.

Entry points for further exploration:

- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — tool window entry point: scope selection, file tree, and review tabs
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-tab session: chat, workspace, summaries, and topics

From there, neighbouring files in the same package cover loading, tools, and state; shared workspace and summary machinery lives with Diff Review.

Summaries, topics, topic hierarchy texts (Fill summaries), and word scores are saved in `.insight/review-cache/` in the project directory and survive IDE restarts, one slot per scope. Entries are keyed by content, not by the whole snapshot: a summary by its node key, which covers every file and part beneath it, a topic by its file’s path and content hash, and a word score by its full judgment request. An edited file regenerates only its own results and those of the folders, groups, topics and root above it; the rest of the scope is restored. A slot also keeps entries of earlier snapshots, oldest dropped first. Partial runs are saved so they can continue. Regenerate drops the saved entries of that kind. The directory is excluded from code scopes and contains its own Git ignore file; delete it while the IDE is closed to clear the disk cache.
