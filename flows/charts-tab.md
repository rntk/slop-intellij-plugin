# Charts tab flow

The Charts tab is a shared way to explore the file and directory structure of a review through visual summaries. In Code Review, it describes the selected code scope; in Diff Review, it describes the selected comparison. Readers move between a hierarchical line view, whose folders unfold in place and whose rows can be followed with the mouse or keyboard, and a radar view, then can open relevant summaries or source/diff context from the chart. The tab is presented by shared review UI and receives review-specific data from its caller.

The hierarchy has metric tabs for source lines, captured source size in bytes, and file counts in Code Review. Diff Review uses green Added lines and red Removed lines tabs. Changing the metric keeps the current directory and open preview.

In Code Review, the tabs also act as semantic lenses over the code: classes, functions, constants, tests, imports and other kinds recognized syntactically through PSI by each supported language. A lens counts only its kind and hides entries without it; only the kinds present in the scope get a tab. Parsed files unfold into declarations and members. Readers can preview their source, open them in the editor, or request a summary of a single declaration. The chart and [Canvas sessions](canvas-session.md) reuse declaration summaries for matching source. File and directory summaries keep their own review workflow.

Entry points for further exploration:

- [Code review flow](code-review.md) and [Diff review flow](diff-review.md) — how each review reaches Charts
- [ChartsCanvasCard.kt](../src/main/kotlin/dev/insight/review/ui/ChartsCanvasCard.kt) — shared Charts page
- [CodeSymbolCounts.kt](../src/main/kotlin/dev/insight/codereview/CodeSymbolCounts.kt) — the PSI walk behind lenses and file outlines; languages add their kinds through the call graph language support
- [ReviewSummaryModel.kt](../src/main/kotlin/dev/insight/review/ReviewSummaryModel.kt) — shared summary structure used by the charts
