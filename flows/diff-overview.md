# Diff overview flow

Diff overview gives a compact view of a selected code comparison. It brings together the words removed and added with a view of the overall line changes, helping reviewers spot themes and open the affected file diffs. The view follows the review’s comparison and can be focused on a file or folder.

Entry points for further exploration:

- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — how the overview fits into a review
- [DiffOverviewChart.kt](../src/main/kotlin/dev/insight/diff/DiffOverviewChart.kt) — overview display and navigation to diffs
