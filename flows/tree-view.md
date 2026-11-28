# Tree view flow

The Tree view in the [Charts tab](charts-tab.md) helps readers explore a review as a hierarchy of directories and files. Readers can navigate the structure, change the perspective on its contents, and use search or focus to narrow their exploration.

The tree also connects structure to meaning: readers can inspect relationships between files, explore available summaries and review themes, and follow relevant context into the source or other review views. What is available depends on the review and its data.

## One canvas

The summary and the topic or group cards are part of the tree's canvas, not fixed side panels. The tree, the active summary to its left, and the card columns to its right share one coordinate space, so panning and zooming move them together. This lets readers move freely around the whole board and keep every part where they expect it.

Card columns extend to the right as far as the hierarchy needs. Each level gets its own column, wide enough for readable titles even with deep topic or group hierarchies, and the board grows instead of squeezing titles into a fixed width. Cards stay aligned with the tree rows they cover when directories fold, unfold, or are filtered.

The summary follows the file, folder, or card that is hovered or selected. By default it sits next to that element on the canvas to the left of the tree and stays in view while its anchor is visible. As readers zoom out, cards and summaries widen and their text stays legible on screen. Readers can also drag the summary card by its header to place it anywhere on the canvas—for example, on the right side after moving the tree—where it stays put across hover updates, panning, and tab switches, and click its reset-position button to return it to its default place to the left of the tree. Layout changes, such as toggling the cards, changing the depth level, or switching between topics and groups, keep the tree where it was so the view doesn't jump.

Escape or a click on the canvas background clears the active selection. Clicking a topic or group card clears the tree selection and lets readers explore the card, and right-clicking a card opens the [context picker](context-picker.md) menu for its files. Dragging to pan preserves the selection. Row actions appear on hover or when the row is active, and hovering a nested card also highlights its parents.

Row checkboxes mark files **Viewed** or **Unviewed**. A folder checkbox marks its files in the current scope, including folded rows. These marks are shared with Summaries' **Reviewed** label. **Show only unviewed** keeps unviewed files and their ancestor folders in the tree.

## Source preview

A control panel toggle lets the source or diff of the hovered or active file or folder follow the reader in a floating window. Unlike the summary and cards, the window is docked to the board's edge rather than placed on the canvas, so it stays readable and steady while the tree pans and zooms. Short content opens beside its row; taller content moves toward the top of the board to use its full height, and a folder stacks the first few of its files. Changes wait briefly, and longer while the pointer heads for the window, so readers can reach it across other rows. Each file and folder row also has a preview icon that pins its window, even while the toggle is off.

Links between files open a window the same way: it docks beside the linked row, follows the hovered link with the same waits, stays while the pointer is inside, and a click on the link pins it. While a link window is open, the source preview steps aside, so only one window holds the dock.

The [example canvas](../example/canvas/) shows the intended interaction model: one transformed viewport holding the content, the hierarchy rail, and the floating summary.

Entry points for further exploration:

- [Charts tab flow](charts-tab.md) — the surrounding experience and review-specific behavior
- [ReviewTreeBoard.kt](../src/main/kotlin/dev/insight/review/ReviewTreeBoard.kt) — tree board model
- [TreeBoardView.kt](../src/main/kotlin/dev/insight/review/ui/TreeBoardView.kt) — tree board view
- [TreeBoardSidePanels.kt](../src/main/kotlin/dev/insight/review/ui/TreeBoardSidePanels.kt) — summary and cards on the canvas
- [TreeSourcePreview.kt](../src/main/kotlin/dev/insight/review/ui/TreeSourcePreview.kt) — the floating source or diff preview
- [TreeConnectionPreview.kt](../src/main/kotlin/dev/insight/review/ui/TreeConnectionPreview.kt) — the floating link preview
