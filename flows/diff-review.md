# Diff review flow

Diff Review is a read-only workspace for understanding a code change. It opens in Split view with the files from uncommitted changes. It presents a comparison and its changed files, with independent review sessions for focused exploration. Conversation, notes, summaries, topics, change grouping, and other generated views help readers organize questions and connect evidence to the change. Topics classify files by code subsystem first and narrow down to the specific capability, whereas Grouping organizes files by the shared change cause or refactoring first and narrows down to the affected code area. A changed file, folder or summary entry can be taken to chat: a new session focused on it, with its summaries and diff in context and the rest of the change reachable on demand.

The workspace is separate from the application graph, though some views can relate changed code to broader structure. Interpretations are grounded in the comparison and can be checked against the source and diff.

Chat can also read current project files outside the change for supporting context, keeping that context distinct from the comparison.

Source and diff views offer the IDE's declaration navigation and usage search for snapshot files that match current project source.

A changed file's context menu offers **Open on canvas**. It starts a clickable source exploration with related changed files already open, following resolved code relationships in both directions across the comparison. Files whose reviewed source is unavailable or differs from the project are reported rather than used as relationship evidence.

**Open on flowchart** reads the same evidence one step above the source: a flow chart of the changed functions in that file and its related changed files, with the calls between changes opened and unchanged code shown as closed context. Conditions, loops and grouped calls replace raw lines, changed steps stand out, and changed code is never grouped away. Labels may be model interpretations; the calls come from static analysis.

Hierarchy views place the chart beside diff blocks. Hovering or selecting a topic or summary shows one block for each changed file within that entry.

Entry points for further exploration:

- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — review workspace
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — focused review session
