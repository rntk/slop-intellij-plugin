# Code review flow

Code Review is a read-only workspace for exploring an existing codebase. It opens on a Start page in the editor area, where the scope is chosen (the project root by default) and its files and skipped files are browsed, and continues from there to the summaries in Split view. The side panel, which keeps the review tabs and chat, and every view lead back to the Start page through a button floating on their edge that names the current scope; it takes no room from the page and keeps off its controls, shrinking to its icon or a thin handle where the edge is busy. Readers then use conversation, summaries, topics, and source views to understand how that code is organized and what it does. Assistant explanations stay tied to the selected code so readers can inspect the source themselves.

A file, folder or summary entry can be taken to chat: a new review tab whose conversation is focused on it, with its summaries and source in context, while the rest of the scope stays reachable on demand.

Chat can also read current project files outside the review scope for supporting context, keeping that context distinct from the captured code.

A Words view gives a compact picture of the vocabulary in scope: the most frequent words as a cloud, sized by frequency. Selecting a word opens the files where it appears. Like the diff Words view, it is computed from the captured code and does not depend on an assistant response.

Source views offer the IDE's declaration navigation and usage search when the captured file matches current project source.

The **Code wall** lays out the source of many files at once, one quiet block per file with numbered lines, on the same pannable, zoomable, rearrangeable canvas as the diff review's Diffs wall, with the same keyboard stepping, compact blocks and summaries in place. It opens on the whole scope from the view selector, and summary and topic entries can open just their files on it. A line opens its file there in the IDE. Each block can unfold its own chat about that file, with its source in context, and **Chat about these files** opens a chat about every file on the wall. Both keep their own conversations and history and read the rest of the scope and the project only when a question needs it.

Hierarchy views place the chart beside source blocks. Hovering or selecting a topic or summary shows one block for each captured file within that entry.

Document view reveals the full summary when a source block or topic/group card is hovered or focused, with room for the summary to grow.

Document and Hierarchy views of topics offer Tags cloud, sizing summary words by their weight in child summaries or captured source, as in Summaries.

Entry points for further exploration:

- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — review workspace
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-scope review experience
