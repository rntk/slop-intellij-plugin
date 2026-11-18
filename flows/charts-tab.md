# Charts tab flow

The Charts tab is a shared way to explore the file and directory structure of a review through visual summaries. In Code Review, it describes the selected code scope; in Diff Review, it describes the selected comparison. Readers move between a hierarchical line view, whose folders unfold in place and whose rows can be followed with the mouse or keyboard, and a radar view, then can open relevant summaries or source/diff context from the chart. The tab is presented by shared review UI and receives review-specific data from its caller.

Entry points for further exploration:

- [Code review flow](code-review.md) and [Diff review flow](diff-review.md) — how each review reaches Charts
- [ChartsCanvasCard.kt](../src/main/kotlin/dev/insight/review/ui/ChartsCanvasCard.kt) — shared Charts page
- [ReviewSummaryModel.kt](../src/main/kotlin/dev/insight/review/ReviewSummaryModel.kt) — shared summary structure used by the charts
