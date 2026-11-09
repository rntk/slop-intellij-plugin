# Diff overview flow

Diff overview gives a compact view of a selected code comparison. It brings together the words removed and added with a comparison of deleted and added lines (two half-circles whose areas follow the counts) and a word balance bar showing how much of the vocabulary only moved between sides (a hint of refactoring), helping reviewers spot themes and open the affected file diffs. The view follows the review’s comparison and can be focused on a file or folder.

Entry points for further exploration:

- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — how the overview fits into a review
- [DiffOverviewChart.kt](../src/main/kotlin/dev/insight/diff/DiffOverviewChart.kt) — overview display and navigation to diffs
- [WordBalanceChart.kt](../src/main/kotlin/dev/insight/diff/WordBalanceChart.kt) — word balance calculation and bar
