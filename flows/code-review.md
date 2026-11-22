# Code review flow

Code Review is a read-only workspace for exploring an existing codebase scope. It opens as a hub in the editor area, where each review tab offers a grid of tiles: choosing the scope and optionally scheduling background generation, then navigating the code through [summaries](summaries.md), dedicated Hierarchy and Document tabs, topics, charts, a [code map](code-map.md) of flows read from their roots, vocabulary views, a pannable Code wall, a [Report viewer](report-viewer.md) for Markdown and text reports, and finally chat. The Charts view gathers charts about the scope; its lines-of-code chart explores one directory at a time with drill-down and path breadcrumbs and lets readers toggle each file or directory's summary or source preview in a second column on the right while highlighting the active entry, while its radar compares how much code each folder or file holds at a chosen level, using source lines or IDE-parsed symbols and comments, and the topics view has a radar of its own. Symbol views distinguish declaration kinds and show which files have symbol data; clicking a legend item isolates that type on the chart.

Focused tabs and wall chats let readers ask questions grounded in the captured scope, while still allowing supporting reads from the wider project. The Hierarchy and Document views build very large scopes progressively, a page at a time with a link to load more, so huge codebases stay responsive. The tool window holds only the active tab's chat and stays hidden until it is opened.

Entry points for further exploration:

- [ReviewHubScreen.kt](../src/main/kotlin/dev/insight/review/ui/ReviewHubScreen.kt) — hub page with the review tabs and their tiles
- [ReviewTileGrid.kt](../src/main/kotlin/dev/insight/review/ui/ReviewTileGrid.kt) — tile grid
- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — review workspace
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-scope review experience
