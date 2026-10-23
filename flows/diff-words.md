# Words diff flow

The Words view gives a compact picture of the vocabulary in a change. It counts words in removed and added lines, positions them by their relative frequency on each side, and sizes them by frequency. Selecting a word opens the changed files where it appears. The view is computed from the review snapshot and does not depend on an assistant response.

Entry points for further exploration:

- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — opens the Words view in a review
- [WordChangeChart.kt](../src/main/kotlin/dev/insight/diff/WordChangeChart.kt) — counts and presents changed words
