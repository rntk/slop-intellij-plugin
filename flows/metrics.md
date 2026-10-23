# Metrics flow

Metrics in Diff Review compares code before and after a change. It offers a high-level view of structural change, with paths from the overview back to the relevant diffs. The results belong to the selected comparison.

Entry points for further exploration:

- [SemanticChanges.kt](../src/main/kotlin/dev/insight/diff/SemanticChanges.kt) — comparison data and analysis
- [SemanticChangesView.kt](../src/main/kotlin/dev/insight/diff/SemanticChangesView.kt) — metrics views and navigation
