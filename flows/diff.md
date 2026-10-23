# Diff flow

Diff Review presents a selected code comparison as a read-only workspace. Reviewers can explore changed files, ask questions about the change, and add explanatory notes or generated views alongside the source.

The application graph also offers a view of how changed code relates to surrounding calls. These views help navigate a change; they do not establish runtime behavior or modify files.

Entry points for further exploration:

- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — comparison workspace
- [DiffService.kt](../src/main/kotlin/dev/insight/callgraph/DiffService.kt) — changes in the application graph
