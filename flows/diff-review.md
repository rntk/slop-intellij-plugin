# Diff review flow

Diff Review is a read-only workspace for understanding a code change. It opens on a Start page in the editor area, where the comparison is chosen (uncommitted changes by default) and its changed files are browsed, with untracked files to include, and continues from there to the summaries in Split view. The side panel, which keeps the review tabs and chat, and every view lead back to the Start page through a button floating on their edge that names the current comparison; it takes no room from the page and keeps off its controls, shrinking to its icon or a thin handle where the edge is busy. It presents a comparison and its changed files, with independent review sessions for focused exploration. Conversation, notes, summaries, topics, change grouping, and other generated views help readers organize questions and connect evidence to the change. Topics classify files by code subsystem first and narrow down to the specific capability, whereas Grouping organizes files by the shared change cause or refactoring first and narrows down to the affected code area. A changed file, folder or summary entry can be taken to chat: a new session focused on it, with its summaries and diff in context and the rest of the change reachable on demand.

The workspace is separate from the application graph, though some views can relate changed code to broader structure. Interpretations are grounded in the comparison and can be checked against the source and diff.

Chat can also read current project files outside the change for supporting context, keeping that context distinct from the comparison.

Source and diff views offer the IDE's declaration navigation and usage search for snapshot files that match current project source.

A changed file's context menu offers **Open on canvas**. It starts a clickable source exploration with related changed files already open, following resolved code relationships in both directions across the comparison. Files whose reviewed source is unavailable or differs from the project are reported rather than used as relationship evidence.

**Open on flowchart** reads the same evidence one step above the source: a flow chart of the changed functions in that file and its related changed files, with the calls between changes opened and unchanged code shown as closed context. Conditions, loops and grouped calls replace raw lines, changed steps stand out, and changed code is never grouped away. Labels may be model interpretations; the calls come from static analysis.

The **Diffs wall** lays out the raw diffs of many files at once, one quiet block per file, spread over columns to suit the page's shape, on a canvas the reader can pan, zoom and rearrange by dragging blocks. The keyboard scrolls it and steps through blocks, zooming in to read each and back out to the overview; tall blocks can be made compact so their diff scrolls inside them, and a block whose file has a summary can show it in place of the diff at the same size. It opens on the whole change from the view selector, and summary and topic entries can open just their files on it. Arrangements last while the wall shows the same files; a different set is laid out afresh. A block opens its file's full diff in the IDE. Each block can also unfold its own chat about that file: a simpler, file-scoped companion to the review chat, with independent conversations and history, that reads further into the change and project only when a question needs it.

Hierarchy views place the chart beside diff blocks. Hovering or selecting a topic or summary shows one block for each changed file within that entry.

Entry points for further exploration:

- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — review workspace
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — focused review session
