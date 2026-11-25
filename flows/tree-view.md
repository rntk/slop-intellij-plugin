# Tree view flow

The Tree view in the [Charts tab](charts-tab.md) helps readers explore a review as a hierarchy of directories and files. Readers can navigate the structure, change the perspective on its contents, and use search or focus to narrow their exploration.

The tree also connects structure to meaning: readers can inspect relationships between files, explore available summaries and review themes, and follow relevant context into the source or other review views. What is available depends on the review and its data.

## One canvas

The summary and the topic or group cards are part of the tree's canvas, not fixed side panels. The tree, the active summary to its left, and the card columns to its right share one coordinate space, so panning and zooming move them together. This lets readers move freely around the whole board and keep every part where they expect it.

Card columns extend to the right as far as the hierarchy needs. Each level gets its own column, wide enough for readable titles even with deep topic or group hierarchies, and the board grows instead of squeezing titles into a fixed width. Cards stay aligned with the tree rows they cover when directories fold, unfold, or are filtered.

The summary follows the file, folder, or card that is hovered or selected. It sits next to that element on the canvas and stays in view while its anchor is visible. As readers zoom out, cards and summaries widen and their text stays legible on screen. Layout changes, such as toggling the cards, changing the depth level, or switching between topics and groups, keep the tree where it was so the view doesn't jump.

The [example canvas](../example/canvas/) shows the intended interaction model: one transformed viewport holding the content, the hierarchy rail, and the floating summary.

Entry points for further exploration:

- [Charts tab flow](charts-tab.md) — the surrounding experience and review-specific behavior
- [ReviewTreeBoard.kt](../src/main/kotlin/dev/insight/review/ReviewTreeBoard.kt) — tree board model
- [TreeBoardView.kt](../src/main/kotlin/dev/insight/review/ui/TreeBoardView.kt) — tree board view
- [TreeBoardSidePanels.kt](../src/main/kotlin/dev/insight/review/ui/TreeBoardSidePanels.kt) — summary and cards on the canvas
