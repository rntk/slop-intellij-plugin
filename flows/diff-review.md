# Diff review flow

Diff Review is a read-only workspace for understanding a selected code comparison. It opens as a hub in the editor area, where each review tab offers a grid of tiles: choosing the comparison and any untracked files, then exploring the change through summaries, topics, change grouping, metric views, a Diffs wall, and finally chat.

Focused review tabs, wall chats, and graph-backed canvas or flowchart views connect assistant explanations and call relationships back to the underlying diff, while keeping interpretations grounded in the comparison. The tool window holds only the active tab's chat and stays hidden until it is opened.

Entry points for further exploration:

- [ReviewHubScreen.kt](../src/main/kotlin/dev/insight/review/ui/ReviewHubScreen.kt) — hub page with the review tabs and their tiles
- [ReviewTileGrid.kt](../src/main/kotlin/dev/insight/review/ui/ReviewTileGrid.kt) — tile grid
- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — review workspace
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — focused review session
