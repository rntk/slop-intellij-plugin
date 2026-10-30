# Summaries flow

Summaries provide a hierarchical guide to a review scope, from an overall view down to individual files and relevant sections. They help readers navigate a large body of source or a code change while keeping the underlying evidence close at hand.

The same summary process describes both the folder hierarchy and an existing topic hierarchy; topic classification happens separately. In the views, a chain of folders that each hold only another folder reads as one entry with one summary; the summary tree itself keeps every folder. Likewise, an open folder holding a single file leaves its row's summary blank, since the file's row repeats it.

File and summary context menus can regenerate one entry or its entire subtree, with optional custom instructions. Results open as a separate summary version for comparison. In the topic hierarchy, entries can likewise fill their missing summaries or regenerate their topics at any level, without waiting for the whole hierarchy; those results are saved in place.

The Diff view keeps the file tree beside the IDE’s native diff viewer. Select a file to inspect its changes, or a directory or All changes to read every matching file’s diff one after another. In both views an entry's menu can also run the IDE's own inspections over its working-tree files and list the errors and warnings as plain text ready to hand to a coding agent.

The descriptions are generated from the content they summarize, and every claim in one cites what it was written from: a file's claims cite its lines, and a folder's or topic's claims cite the claims of the summaries directly below it. These citations are recorded while the summaries are written, so the views read the links directly instead of matching text afterwards. Hovering or selecting part of a summary highlights what it condenses below and what condenses it above. Searching from it opens the cited summaries, or a file's cited lines. To file and To directory open the levels down to the files or folders it cites at any depth, highlight them, and select and scroll to the chosen one. Use the summaries to find areas worth exploring, then check the source or diff directly.

Choosing a level unfolds folders through that level and folds everything deeper. Readers can also open or close individual entries.

Readers can mark files or directories as reviewed. Their summaries dim, and a claim in a higher summary dims once every file it cites, directly or through the levels between, is reviewed. Review marks follow the underlying evidence.

Summaries favor a short statement of purpose or behavior, with only essential distinctions supported by the evidence. They omit file names, paths, code, and implementation inventories. Simple items need very little text; subtle behavior may need more explanation. Higher levels condense the main themes rather than repeat the details below.

Entry points for further exploration:

- [ReviewSummaryModel.kt](../src/main/kotlin/dev/insight/review/ReviewSummaryModel.kt) — summary structure
- [Summary.kt](../src/main/kotlin/dev/insight/review/Summary.kt) — segments and their citations
- [ChangeSummarizer.kt](../src/main/kotlin/dev/insight/diff/ChangeSummarizer.kt) — summary generation

The Tree view follows the recorded citations from summary parts to the parts below them, with curved branches read from left to right. A single shared part forms the root; independent parts branch immediately from an empty root. Parts can be folded individually. File leaves show the file name and unfold the native diff viewer on click.
