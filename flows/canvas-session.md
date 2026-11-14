# Canvas session flow

A canvas session is a visual walk through the code graph starting from a file or a Diff Review comparison. Readers open connected cards from symbols, usages, or attached local files, building navigable chains and optional side-by-side source views. Chains run both ways: a name used in code leads down to its declaration, and a card's own declared name leads up to the callers and users the IDE's reference search finds. A trail leads from the root file to the current card, and on request a model summarizes the code between a source's symbols in place.

Each card carries its own conversations grounded in the chain from the root, along with read-only project and call-graph tools and canvas actions to open related cards or whole call chains. Sessions—including cards, layouts, summaries, and chats—persist in the project so explorations can be resumed later. The graph behind a session is valid only for the code it read; when that code changes, the session is refreshed in place, which resolves every card again against the current code.

Entry points for further exploration:

- [CanvasSessionState.kt](../src/main/kotlin/dev/insight/canvas/CanvasSessionState.kt) — the session model and persistence
- [CanvasChainContext.kt](../src/main/kotlin/dev/insight/canvas/CanvasChainContext.kt) — chain context and chat integration
- [Schematic.kt](../src/main/kotlin/dev/insight/callgraph/Schematic.kt) — the reading of a source into links and gaps that cards are built from
