# Summaries flow

Summaries provide a hierarchical guide to a review scope—organized by folders or topics—from an overall view down to individual files and sections. Every generated claim records citations to the child summaries or source/diff lines it was written from, letting readers trace summaries directly back to evidence.

Diff Review and Code Review each have separate **Hierarchy** and **Document** tiles and editor tabs. Hierarchy lays out folders and files from broad to specific. Document shows files in folder order alongside their summaries and source or diffs. Both use the same summaries as the **Summaries** tab.

Readers can regenerate entries, track reviewed status, filter to **Show only unviewed** entries, and browse cited branches in a navigable tree alongside source or diff viewers.

Choose **Summarize** on a missing file or folder in its context menu or on its summary card. A folder run fills the child summaries it needs and reuses any that already exist. The results are saved with the review’s original summaries. The **Summarize** control at the top still fills the whole review.

A review tab supplies Summaries, Charts, and the wall with one [summary snapshot](../src/main/kotlin/dev/insight/review/ReviewSummarySnapshot.kt) per refresh. Views read file and directory prose from this snapshot, including the folder summary displayed at an absorbed root; views of files, such as the walls, take only its per-file slice. New views can request missing entries through the tab’s context summary actions. [ScopedSummaryFill](../src/main/kotlin/dev/insight/review/ScopedSummaryFill.kt) queues those requests and saves results into the original review tree.

Code and diff reviews supply source-specific evidence to the shared [ReviewSummarizer](../src/main/kotlin/dev/insight/review/ReviewSummarizer.kt). Declaration previews in Charts and Canvas sessions use the [declaration summary cache](../src/main/kotlin/dev/insight/canvas/SymbolSummaries.kt), keyed by declaration source and prompt.

Entry points for further exploration:

- [ReviewSummaryModel.kt](../src/main/kotlin/dev/insight/review/ReviewSummaryModel.kt) — summary structure and citations
- [ChangeSummarizer.kt](../src/main/kotlin/dev/insight/diff/ChangeSummarizer.kt) — summary generation
