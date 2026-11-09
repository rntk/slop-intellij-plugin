# Diffs wall flow

The Diffs wall lays out the raw diffs of many changed files at once, one block per file, arranged across columns to fit the page on a pannable, zoomable canvas. It can show the entire change from the view selector, or just the files of a chosen summary or topic entry.

Readers can pan and zoom the surface, drag blocks to rearrange them while viewing the same set of files, or step through blocks with the keyboard to zoom in on each and back out to the overview. Tall blocks can be made compact so their diff scrolls inside them, and any block can open its file's full diff in the IDE.

Entry points for further exploration:

- [DiffsWallView.kt](../src/main/kotlin/dev/insight/diff/DiffsWallView.kt) — the wall canvas, interaction, and navigation to diffs
- [DiffsWallModel.kt](../src/main/kotlin/dev/insight/diff/DiffsWallModel.kt) — diff block preparation, layout, and zoom calculations
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — opening the wall from a review session
