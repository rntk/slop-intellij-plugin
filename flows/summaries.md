# Summaries flow

Summaries provide a hierarchical guide to a review scope, from an overall view down to individual files and relevant sections. They help readers navigate a large body of source or a code change while keeping the underlying evidence close at hand.

The descriptions are generated from the content they summarize. Use them to find areas worth exploring, then check the source or diff directly.

Entry points for further exploration:

- [ChangeSummaryModel.kt](../src/main/kotlin/dev/insight/diff/ChangeSummaryModel.kt) — summary structure
- [ChangeSummarizer.kt](../src/main/kotlin/dev/insight/diff/ChangeSummarizer.kt) — summary generation
