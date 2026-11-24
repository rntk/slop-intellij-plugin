# Charts tab flow

The Charts tab is a shared way to explore the file and directory structure of a review through visual summaries. In Code Review, it describes the selected code scope; in Diff Review, it describes the selected comparison. Readers move between a hierarchical line view, whose folders unfold in place and whose rows can be followed with the mouse or keyboard, a pannable/zoomable tree board view that lays out the whole project structure as a foldable ASCII-style diagram with active and hovered branch highlighting, a circles view that packs the same directory into nested circles sized by the current metric and walks into a folder on click, and a radar view, then can open relevant summaries or source/diff context from the chart. The tab is presented by shared review UI and receives review-specific data from its caller.

The hierarchy has metric tabs for source lines, captured source size in bytes, and file counts in Code Review. Diff Review uses green Added lines and red Removed lines tabs. The hierarchy, the tree board, and the circles share these tabs, the search and the current directory. Changing the metric keeps the current directory, collapsed folders, cursor, and open preview. Circles and radar icons on each file and directory open the corresponding chart to the right of the hierarchy using the current metric.

In the Tree chart, each file has a code connections control. It draws incoming and outgoing links between file rows on the right, using imports and references resolved from the IDE's current source. The links are scoped to files in the review. Folders, metric tabs, and search still determine which rows are visible.

A one-line search above the hierarchy asks the model which files match a short query. The model explores with the review's own read-only tools and answers only with paths. The answer becomes a Search tab, not a filter on the current tab. That tab shows the usual hierarchy and drill-down with every unrelated file and folder hidden; returned paths are matched leniently against the review's files and folders.

In Code Review, the tabs also act as semantic lenses over the code: classes, functions, constants, tests, imports and other kinds recognized syntactically through PSI by each supported language. A lens counts only its kind and hides entries without it; only the kinds present in the scope get a tab. Parsed files unfold into declarations and members. Readers can preview their source, open them in the editor, or request a summary of a single declaration. The row's [context picker](context-picker.md) opens on the declaration's source range. The chart and [Canvas sessions](canvas-session.md) reuse declaration summaries for matching source. File and directory summaries keep their own review workflow.

Entry points for further exploration:

- [Code review flow](code-review.md) and [Diff review flow](diff-review.md) — how each review reaches Charts
- [ChartsCanvasCard.kt](../src/main/kotlin/dev/insight/review/ui/ChartsCanvasCard.kt) — shared Charts page
- [ReviewTreeBoard.kt](../src/main/kotlin/dev/insight/review/ReviewTreeBoard.kt) and [TreeBoardView.kt](../src/main/kotlin/dev/insight/review/ui/TreeBoardView.kt) — whole-project foldable tree board model and canvas
- [CodeSymbolCounts.kt](../src/main/kotlin/dev/insight/codereview/CodeSymbolCounts.kt) — the PSI walk behind lenses and file outlines; languages add their kinds through the call graph language support
- [ReviewSummaryModel.kt](../src/main/kotlin/dev/insight/review/ReviewSummaryModel.kt) — shared summary structure used by the charts
