# Summaries flow

Summaries provide a hierarchical guide to a review scope, from an overall view down to individual files and relevant sections. They help readers navigate a large body of source or a code change while keeping the underlying evidence close at hand.

The same summary process describes both the folder hierarchy and an existing topic hierarchy; topic classification happens separately.

The descriptions are generated from the content they summarize. Use them to find areas worth exploring, then check the source or diff directly. Hovering or selecting words in a summary highlights where they recur in the summaries below it and in the folders above it.

Readers can mark files or directories as reviewed. Their summaries dim, and related words in higher summaries dim only when every contributing item is reviewed. Review marks follow the underlying evidence; cached semantic links let repeated toggles reuse earlier judgments.

Summaries favor a short statement of purpose or behavior, with only essential distinctions supported by the evidence. They omit file names, paths, code, and implementation inventories. Simple items need very little text; subtle behavior may need more explanation. Higher levels condense the main themes rather than repeat the details below.

Entry points for further exploration:

- [ChangeSummaryModel.kt](../src/main/kotlin/dev/insight/diff/ChangeSummaryModel.kt) — summary structure
- [ChangeSummarizer.kt](../src/main/kotlin/dev/insight/diff/ChangeSummarizer.kt) — summary generation
