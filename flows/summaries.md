# Summaries flow

Summaries provide a hierarchical guide to a review scope—organized by folders or topics—from an overall view down to individual files and sections. Every generated claim records citations to the child summaries or source/diff lines it was written from, letting readers trace summaries directly back to evidence.

Readers can regenerate entries, track reviewed status across the hierarchy, and browse cited branches in a navigable tree alongside source or diff viewers.

Entry points for further exploration:

- [ReviewSummaryModel.kt](../src/main/kotlin/dev/insight/review/ReviewSummaryModel.kt) — summary structure and citations
- [ChangeSummarizer.kt](../src/main/kotlin/dev/insight/diff/ChangeSummarizer.kt) — summary generation
