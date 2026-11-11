# Diffs wall flow

The Diffs wall lays out the raw diffs of many changed files at once, one block per file, arranged across columns to fit the page on a pannable, zoomable canvas. It can show the entire change from the view selector, or just the files of a chosen summary or topic entry.

Readers can pan and zoom the surface, drag blocks to rearrange them while viewing the same set of files, or step through blocks with the keyboard to zoom in on each and back out to the overview. The first block starts active in the overview; the first Tab press zooms into it, and further presses step through the blocks. Tall blocks can be made compact so their diff scrolls inside them, and any block can open its file's full diff in the IDE.

Rows are syntax highlighted with the IDE's lexer for the file's type, in the editor's colour scheme, over the added and removed tints. Each side is lexed as a whole file, so a row inside a comment or string that opens outside its hunk is still coloured right; a row whose side is missing or does not match it stays plain, as do file types the IDE cannot highlight. Highlighting is lexical only, without the IDE's code analysis, and is worked out in the background, block by block. The Code wall colours its source blocks the same way.

Each block also has a Chat tab on its right edge that unfolds a small chat about that file. Its diff is already in the chat's context, and the model can read and search the rest of the change, both sides of changed files, and current project files when an answer needs them. Each file keeps its own independent chats, with a history to switch between; they last through folding and changes to what the wall shows.

**Chat about these changes**, beside **Show all changes** above the wall, opens a chat about the wall as a whole in the visible top-right corner of the wall. Every diff the wall shows when a question is asked is in its context (a very large wall pins as many as fit and lists the rest for the model to read), with the same tools as a file's chat. It keeps its own chats and history, and stays open while the wall changes scope.

Entry points for further exploration:

- [review/wall](../src/main/kotlin/dev/insight/review/wall/) — the wall canvas, layout, and chats shared with Code Review's Code wall
- [WallHighlighting.kt](../src/main/kotlin/dev/insight/review/wall/WallHighlighting.kt) and [WallSyntax.kt](../src/main/kotlin/dev/insight/review/wall/WallSyntax.kt) — mapping lexer tokens onto wall rows, and the IDE lexer adapter
- [DiffsWallView.kt](../src/main/kotlin/dev/insight/diff/DiffsWallView.kt) — diff blocks and navigation to diffs
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — opening the wall from a review session
