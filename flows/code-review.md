# Code review flow

Code Review is a read-only workspace for exploring an existing codebase scope. It opens as a hub in the editor area, where each review tab offers a grid of tiles: choosing the scope and optionally scheduling background generation, then navigating the code through hierarchical summaries, topics, a [code map](code-map.md) of flows read from their roots, vocabulary views, a pannable Code wall, and finally chat. Summary and topic views include a radar that compares how much code each folder, file or topic holds at a chosen level, using source lines or IDE-parsed symbols and comments. Symbol views distinguish declaration kinds and show which files have symbol data; clicking a legend item isolates that type on the chart.

Focused tabs and wall chats let readers ask questions grounded in the captured scope, while still allowing supporting reads from the wider project. The tool window holds only the active tab's chat and stays hidden until it is opened.

Entry points for further exploration:

- [ReviewHubScreen.kt](../src/main/kotlin/dev/insight/review/ui/ReviewHubScreen.kt) — hub page with the review tabs and their tiles
- [ReviewTileGrid.kt](../src/main/kotlin/dev/insight/review/ui/ReviewTileGrid.kt) — tile grid
- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — review workspace
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-scope review experience
