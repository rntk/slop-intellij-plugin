# Code review flow

Code Review is a read-only workspace for exploring an existing codebase scope. It opens as a hub in the editor area, where each review tab offers a grid of tiles: choosing the scope and optionally scheduling background generation, then navigating the code through hierarchical summaries, topics, vocabulary views, a pannable Code wall, and finally chat.

Focused tabs and wall chats let readers ask questions grounded in the captured scope, while still allowing supporting reads from the wider project. The tool window holds only the active tab's chat and stays hidden until it is opened. Every page offers a way back to the hub.

Entry points for further exploration:

- [ReviewHubScreen.kt](../src/main/kotlin/dev/insight/review/ui/ReviewHubScreen.kt) — hub page with the review tabs and their tiles
- [ReviewTileGrid.kt](../src/main/kotlin/dev/insight/review/ui/ReviewTileGrid.kt) — tile grid
- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — review workspace
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-scope review experience
