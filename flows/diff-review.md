# Diff review flow

Diff Review is a read-only workspace for understanding a selected code comparison. It opens as a hub in the editor area, where each review tab offers a grid of tiles: choosing the comparison and any untracked files, then exploring the change through [summaries](summaries.md), dedicated Hierarchy and Document tabs, topics, change grouping, charts, a [Words tab](diff-words.md), metric views, a Diffs wall, a [Walkthrough](diff-walkthrough.md), a [Report viewer](report-viewer.md) for Markdown and text reports, and finally chat. The Charts view gathers charts about the change; its directory chart supports drill-down and path breadcrumbs, with a switch between added lines in green and removed lines in red, and toggles on each file or directory to preview its summary or diffs in a second column on the right while highlighting the active entry. Its radar compares how much changed in each folder or file at a chosen level, and topic and grouping views have radars of their own.

The Walkthrough tells the change as steps of linked diff hunks: from a step's root, a canvas opens what caused each change and what it leads to, and every change stays reachable, including those the assistant did not link.

In the Words tab, the **View** selector switches between **Word changes** and **Diff overview**. Both use the same file or folder scope.

Focused review tabs, wall chats, and graph-backed canvas or flowchart views connect assistant explanations and call relationships back to the underlying diff, while keeping interpretations grounded in the comparison. The Hierarchy and Document views build very large comparisons progressively, a page at a time with a link to load more, so huge diffs stay responsive. Their summaries link file paths, line references, and code symbols as the [Report viewer](report-viewer.md) does, with its link and selection context menus, while keeping word tracing. The tool window holds only the active tab's chat and stays hidden until it is opened.

Entry points for further exploration:

- [ReviewHubScreen.kt](../src/main/kotlin/dev/insight/review/ui/ReviewHubScreen.kt) — hub page with the review tabs and their tiles
- [ReviewTileGrid.kt](../src/main/kotlin/dev/insight/review/ui/ReviewTileGrid.kt) — tile grid
- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — review workspace
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — focused review session
