# Diff moves flow

Diff moves shows code that was relocated within a review rather than written or deleted outright. It finds runs of lines removed from one changed file that reappear in another, and presents them as flows from where the code left to where it arrived. A reviewer can see at a glance which files exchanged code and how much of each file moved, with the rest split into code that stayed and code that was removed.

Choosing a move opens the diffs of its source and destination side by side, scrolled to the moved code and highlighted, so the two places can be compared. The view follows the review's comparison and is one of its metric views.

The view is a guide, not proof: matches are textual, so a move may be coincidental and should be checked against the source.

Entry points for further exploration:

- [CodeMoves.kt](../src/main/kotlin/dev/insight/diff/CodeMoves.kt) — finding moved code in a comparison
- [CodeMoveChart.kt](../src/main/kotlin/dev/insight/diff/CodeMoveChart.kt) — the moves chart and its selection
