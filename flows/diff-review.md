# Diff review flow

Diff Review is a standalone, read-only workspace for reviewing code changes with AI assistance, independent of the application graph. It lives in `src/main/kotlin/dev/insight/diff`.

At a high level: the workspace loads an immutable snapshot of a selected comparison (such as local uncommitted changes or a Git revision comparison) and presents the changed files for reading. Each review tab is an independent session over that snapshot, optionally scoped to a subset of files. Within a tab, the user chats with an assistant that can only inspect the snapshot; it can place explanatory notes, source excerpts, diagrams, and file diffs alongside the changed files for closer reading.

The assistant can also point to specific snapshot lines in native, read-only editors, with an optional comment. Each point opens its own side-by-side column. Chat tools follow the review context; dedicated generation commands expose their own tools only when needed.

Generated views help navigate a large change — for example summaries of what changed, topics grouping files by responsibility, and flows showing how changed code connects. Code builds the underlying structure from the snapshot; the assistant only writes or labels the interpretation. Everything in a review is tied to its snapshot, so it is refreshed or discarded when the comparison changes.

The Summaries outline always shows file and folder names, with metadata and file actions on the same header line. Clicking a summary expands or collapses its children, like its arrow; scored words retain their context and search actions. Selecting text in a summary and choosing Search from its context menu runs the same search for the selection, without needing Score first. File and part summaries are scored too: clicking a highlighted word in one scores the blocks of the file's changed lines against the word's query, then the lines of the best block, and opens the diff scrolled to those lines with them highlighted. Outline and Hierarchy show scoring and hover status in a fixed footer so hints do not move the summaries.

Entry points for further exploration:

- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — tool window entry point: comparison and file selection, review tabs
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — per-tab session: chat, workspace, and generated views

From there, neighbouring files in the same package cover snapshot loading, assistant tools, and the individual generated views. Diff Review also shares workspace and summary machinery with [Code Review](code-review.md).

Summaries, topics, topic hierarchy texts (Fill summaries), and word scores persist across IDE restarts in the project’s `.insight/review-cache/` directory, one slot per comparison request and tab scope. Saved entries are keyed by their evidence rather than by the whole snapshot: a summary by its node key, which covers every file and part beneath the node, a topic by its file’s path and fingerprint, and a word score by its full judgment request (model, rules, summary texts and question). A changed file therefore regenerates only its own summary, topic and scores and those of the folders, groups, topics and root above it; everything else is restored. File fingerprints use project-relative paths and content, or, for unreadable files, revisions and the on-disk time and length, so they survive a restart. A slot keeps entries of earlier snapshots too, oldest dropped first, so reverting an edit restores its results. Partial runs are saved incrementally. Regenerate drops the saved entries of that kind for every snapshot of the slot. Chat, notes, source cards, and flows are not persisted. The cache contains its own Git ignore file; delete it while the IDE is closed to clear saved results.
