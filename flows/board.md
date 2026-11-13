# Explainer board flow

An explainer board is a free-form but grounded page a model composes to explain a change or a piece of code. It opens as its own editor from a review tab's tile. The model arranges typed blocks—headings, prose, callouts, steps, tables, groups and arrows—around evidence blocks whose content the host resolves itself: source excerpts with margin annotations, diff excerpts, call graph symbols and call chains.

The split is the point: the model decides what to show and writes the interpretation, the host renders the facts, and the two are visibly distinguished. Blocks are added through a bounded set of chat tools, auto-laid out into columns, and every anchored block links back to the code. Boards persist in the project and can be reopened, asked about block by block, or exported as Markdown.

Entry points for further exploration:

- [Board.kt](../src/main/kotlin/dev/insight/board/Board.kt) — the block vocabulary and board model
- [BoardTools.kt](../src/main/kotlin/dev/insight/board/BoardTools.kt) — the tools a chat uses to build a board
- [BoardPanel.kt](../src/main/kotlin/dev/insight/board/ui/BoardPanel.kt) — the rendered board
- [BoardEditor.kt](../src/main/kotlin/dev/insight/board/ui/BoardEditor.kt) — the editor tab with its chat
