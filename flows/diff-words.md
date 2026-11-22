# Words diff flow

The **Words** tile in Diff Review opens one tab with a **View** selector for **Word changes** and [**Diff overview**](diff-overview.md). The Word changes chart counts words in removed and added lines, positions them by their relative frequency on each side, and sizes them by frequency. Selecting a word opens the changed files where it appears. Both views share the same file or folder scope and use counts from the review snapshot.

Entry points for further exploration:

- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — opens the Words view in a review
- [WordChangeChart.kt](../src/main/kotlin/dev/insight/diff/WordChangeChart.kt) — counts and presents changed words
