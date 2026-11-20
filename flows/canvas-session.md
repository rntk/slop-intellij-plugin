# Canvas session flow

A canvas session is a visual walk through the code graph starting from a file or a Diff Review comparison. Readers open connected cards from symbols, usages, or attached local files, building navigable chains and optional side-by-side source views. Chains run both ways: a name used in code leads down to its declaration, and a card's own declared name leads up to the callers and users the IDE's reference search finds. Usages are listed one entry per file; opening one shows the whole file on a card that lists its usage sites, each scrolling the card to its line. A trail leads from the root file to the current card, and on request a model summarizes the code between a source's symbols in place.

The canvas has no edges: panning past one extends the space there. Dragging empty space pans it, and Alt + drag pans from anywhere, over cards and code included. Clicking a card or stepping with Tab focuses it; the wheel and scroll keys move through the focused card's code on axes that scroll (keeping ownership at their edges) and scroll the canvas on other axes, while Esc or clicking empty space returns navigation to the canvas. Cards, the root included, can be resized by their edges and keep that size in the session. Moves the canvas makes on the reader's behalf, to a card, a usage site, or a new zoom, glide there rather than jump, so the reader can follow where they went.

Each card carries its own conversations grounded in the chain from the root, along with read-only project and call-graph tools and canvas actions to open related cards or whole call chains. A declaration card can request a summary of its source and reuse one generated in the [Charts tab](charts-tab.md). Sessions—including cards, layouts, summaries, and chats—persist in the project so explorations can be resumed later. The graph behind a session is valid only for the code it read; when that code changes, the session is refreshed in place, which resolves every card again against the current code.

Entry points for further exploration:

- [CanvasSessionState.kt](../src/main/kotlin/dev/insight/canvas/CanvasSessionState.kt) — the session model and persistence
- [CanvasChainContext.kt](../src/main/kotlin/dev/insight/canvas/CanvasChainContext.kt) — chain context and chat integration
- [Schematic.kt](../src/main/kotlin/dev/insight/callgraph/Schematic.kt) — the reading of a source into links and gaps that cards are built from
