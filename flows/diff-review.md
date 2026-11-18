# Diff review flow

Diff Review is a read-only workspace for understanding a selected code comparison. It opens as a hub in the editor area, where each review tab offers a grid of tiles: choosing the comparison and any untracked files, then exploring the change through summaries, topics, change grouping, charts, a change map of related declarations, metric views, a Diffs wall, and finally chat. The Charts view gathers charts about the change; its directory chart supports drill-down and path breadcrumbs, with a switch between added lines in green and removed lines in red, and toggles on each file or directory to preview its summary or diffs in a second column on the right while highlighting the active entry. Its radar compares how much changed in each folder or file at a chosen level, and topic and grouping views have radars of their own.

Focused review tabs, wall chats, and graph-backed canvas or flowchart views connect assistant explanations and call relationships back to the underlying diff, while keeping interpretations grounded in the comparison. The tool window holds only the active tab's chat and stays hidden until it is opened.

Entry points for further exploration:

- [ReviewHubScreen.kt](../src/main/kotlin/dev/insight/review/ui/ReviewHubScreen.kt) — hub page with the review tabs and their tiles
- [ReviewTileGrid.kt](../src/main/kotlin/dev/insight/review/ui/ReviewTileGrid.kt) — tile grid
- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — review workspace
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — focused review session
