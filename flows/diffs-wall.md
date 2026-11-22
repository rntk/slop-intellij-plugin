# Diffs wall flow

The Diffs wall lays out many changed files at once as blocks on a pannable, zoomable canvas, showing either an entire comparison or a filtered subset from summaries, topics, or moves. Readers can rearrange blocks, swap in file summaries, and jump to full IDE diffs. A file without a summary has one written on request through the ordinary summaries pipeline, saved into the review's Summaries tree so the Summaries views and later runs reuse it. A skim mode instead stacks every file whole in one page-wide column, read top to bottom like `git diff`, with sticky headers, folding, and hunk-to-hunk jumps, while keeping the same summaries, context menu, and chats.

Blocks are lexically syntax-highlighted in the background, and both individual files and the wall as a whole support scoped chats with read-only access to the change and project. File summaries and chat messages render as Markdown whose file paths, line references, and code symbols link the way they do in the [Report viewer](report-viewer.md), keeping the reader on the wall: links scroll to cards already shown, or add missing changed or project files as cards and scroll to them, while Ctrl/Cmd+click opens the editor. The canvas and chat mechanics are shared with Code Review's Code wall.

Entry points for further exploration:

- [review/wall](../src/main/kotlin/dev/insight/review/wall/) — shared wall canvas, highlighting, and chats
- [WallTextLinks.kt](../src/main/kotlin/dev/insight/review/wall/WallTextLinks.kt) — context-aware link resolution, card placement, and line scrolling on a wall
- [DiffsWallView.kt](../src/main/kotlin/dev/insight/diff/DiffsWallView.kt) — diff blocks and navigation to diffs
