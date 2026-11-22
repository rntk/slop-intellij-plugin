# Diff overview flow

Choose **Diff overview** from the **View** selector in the [**Words** tab](diff-words.md) of Diff Review. It brings together removed and added vocabulary, overall line change balance, and how much of the vocabulary shifted between sides, helping reviewers spot themes and open the affected file diffs. It uses the same file or folder scope as Word changes.

Entry points for further exploration:

- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — how the overview fits into a review
- [DiffOverviewChart.kt](../src/main/kotlin/dev/insight/diff/DiffOverviewChart.kt) — overview display and navigation to diffs
