# Change map flow

The change map reads a comparison as logical changes rather than files. Its unit is a change atom: a changed declaration, the loose top-level code of a file, or a whole file without code analysis. Code relates atoms by typed relations, such as using a new symbol, adapting to a changed signature, testing, moving or repeating an edit, then clusters them and orders each cluster as a chain from its origin through the code that follows it to tests and documentation.

Models only judge ambiguous merges, title the chains and write summaries; they never add, drop or move atoms. The view lists the logical changes beside the selected chain, whose steps open the diff at their lines. A run continues missing summaries, and a run over a complete map builds it again.

Entry points for further exploration:

- [ChangeAtoms.kt](../src/main/kotlin/dev/insight/diff/atoms/ChangeAtoms.kt) — atoms, relations, clusters and the map
- [ChangeMapWorkflow.kt](../src/main/kotlin/dev/insight/diff/atoms/ChangeMapWorkflow.kt) — judgments and titles
- [ChangeMapTurn.kt](../src/main/kotlin/dev/insight/diff/ChangeMapTurn.kt) — the `/change-map` command of a review tab
- [ChangeMapPresentation.kt](../src/main/kotlin/dev/insight/diff/ChangeMapPresentation.kt) — what the view shows
