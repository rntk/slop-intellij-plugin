# Diffs wall flow

The Diffs wall lays out the raw diffs of many changed files at once, one block per file, arranged across columns to fit the page on a pannable, zoomable canvas. It can show the entire change from the view selector, or just the files of a chosen summary or topic entry.

Readers can pan and zoom the surface, drag blocks to rearrange them while viewing the same set of files, or step through blocks with the keyboard to zoom in on each and back out to the overview. Tall blocks can be made compact so their diff scrolls inside them, and any block can open its file's full diff in the IDE.

Each block also has a Chat tab on its right edge that unfolds a small chat about that file. Its diff is already in the chat's context, and the model can read and search the rest of the change, both sides of changed files, and current project files when an answer needs them. Each file keeps its own independent chats, with a history to switch between; they last through folding and changes to what the wall shows.

**Chat about these changes**, beside **Show all changes** above the wall, opens a chat about the wall as a whole in the visible top-right corner of the wall. Every diff the wall shows when a question is asked is in its context (a very large wall pins as many as fit and lists the rest for the model to read), with the same tools as a file's chat. It keeps its own chats and history, and stays open while the wall changes scope.

Entry points for further exploration:

- [DiffsWallView.kt](../src/main/kotlin/dev/insight/diff/DiffsWallView.kt) — the wall canvas, interaction, and navigation to diffs
- [DiffsWallModel.kt](../src/main/kotlin/dev/insight/diff/DiffsWallModel.kt) — diff block preparation, layout, and zoom calculations
- [DiffsWallChat.kt](../src/main/kotlin/dev/insight/diff/DiffsWallChat.kt) — per-file chats and their window on the wall
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — opening the wall from a review session
