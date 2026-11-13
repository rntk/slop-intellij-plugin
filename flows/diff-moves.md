# Diff moves flow

Diff moves highlights code relocated within a comparison rather than newly written or deleted. It matches runs of lines removed from one changed file that reappear in another, showing how much code moved between files versus what stayed or was removed.

Selecting a move lets reviewers compare the source and destination diffs or inspect the involved files on the wall. Matching is textual and tolerates minor edits, so results are a guide to verify against the source.

Entry points for further exploration:

- [CodeMoves.kt](../src/main/kotlin/dev/insight/diff/CodeMoves.kt) — finding moved code in a comparison
- [CodeMoveChart.kt](../src/main/kotlin/dev/insight/diff/CodeMoveChart.kt) — the moves chart and its selection
