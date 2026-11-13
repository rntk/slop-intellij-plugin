# Canvas session flow

A canvas session is a visual walk through the code graph starting from a file or a Diff Review comparison. Readers open connected cards from symbols, usages, or attached local files, building navigable chains and optional side-by-side source views.

Each card carries its own conversations grounded in the chain from the root, along with read-only project tools and canvas actions to open related cards. Sessions—including cards, layouts, summaries, and chats—persist in the project so explorations can be resumed later.

Entry points for further exploration:

- [CanvasSessionState.kt](../src/main/kotlin/dev/insight/canvas/CanvasSessionState.kt) — the session model and persistence
- [CanvasChainContext.kt](../src/main/kotlin/dev/insight/canvas/CanvasChainContext.kt) — chain context and chat integration
