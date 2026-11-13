# Diff review flow

Diff Review is a read-only workspace for understanding a selected code comparison. After choosing a comparison and any untracked files to include, readers explore the change through summaries, topics, change grouping, metric views, a Diffs wall, and source/diff views.

Focused review tabs, wall chats, and graph-backed canvas or flowchart views connect assistant explanations and call relationships back to the underlying diff, while keeping interpretations grounded in the comparison.

Entry points for further exploration:

- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — review workspace
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — focused review session
