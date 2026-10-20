# Diff review flow

Diff Review is a standalone, read-only workspace for reviewing code changes with AI assistance, independent of the application graph. It lives in `src/main/kotlin/dev/insight/diff`.

At a high level: the workspace loads an immutable snapshot of a selected comparison (such as local uncommitted changes or a Git revision comparison) and presents the changed files for reading. Each review tab is an independent session over that snapshot, optionally scoped to a subset of files. Within a tab, the user chats with an assistant that can only inspect the snapshot; it can place explanatory notes, source excerpts, diagrams, and file diffs alongside the changed files for closer reading.

Generated views help navigate a large change — for example summaries of what changed, topics grouping files by responsibility, and flows showing how changed code connects. Code builds the underlying structure from the snapshot; the assistant only writes or labels the interpretation. Everything in a review is tied to its snapshot, so it is refreshed or discarded when the comparison changes.

Entry points for further exploration:

- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — tool window entry point: comparison and file selection, review tabs
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — per-tab session: chat, workspace, and generated views

From there, neighbouring files in the same package cover snapshot loading, assistant tools, and the individual generated views. Diff Review also shares workspace and summary machinery with [Code Review](code-review.md).
