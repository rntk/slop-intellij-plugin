# Diffs wall flow

The Diffs wall lays out many changed files at once as blocks on a pannable, zoomable canvas, showing either an entire comparison or a filtered subset from summaries, topics, or moves. Readers can rearrange blocks, swap in file summaries, and jump to full IDE diffs.

Blocks are lexically syntax-highlighted in the background, and both individual files and the wall as a whole support scoped chats with read-only access to the change and project. The canvas and chat mechanics are shared with Code Review's Code wall.

Entry points for further exploration:

- [review/wall](../src/main/kotlin/dev/insight/review/wall/) — shared wall canvas, highlighting, and chats
- [DiffsWallView.kt](../src/main/kotlin/dev/insight/diff/DiffsWallView.kt) — diff blocks and navigation to diffs
