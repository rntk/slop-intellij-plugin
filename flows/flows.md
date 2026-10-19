# Flows flow

The flows feature lives in `src/main/kotlin/dev/insight/callgraph`. It breaks down execution starting from entry points into separate, readable logical flows, presenting each operation as its own focused graph rather than an overwhelming whole-application graph.

At a high level: given entry points (either discovered or selected), the splitter maps them to root nodes in the project call graph and groups immediate call branches into distinct operations using model assistance, falling back to structural grouping when needed. Reachable call paths are walked to construct bounded, standalone sub-graphs, which are then presented in the UI as individual navigable flows.

Flows are bounded to 40 nodes and 8 call levels. The **Flows** tab lists each flow as a card with its own graph; **Split flows** (becoming **Cancel** while running) builds them from the discovered entry points, or from one picked with **Choose entrypoint…**. **Show flows** filters by entry point. Cards offer **Show in graph**, **Add to flow chart** and **Canvas**. After a rebuild, existing flows stay visible but are marked out of date until split again.

Entry points for further exploration:

- [ApplicationFlows.kt](../src/main/kotlin/dev/insight/callgraph/ApplicationFlows.kt) — flow splitting, validation, and sub-graph extraction
- [ApplicationFlowsPanel.kt](../src/main/kotlin/dev/insight/callgraph/ApplicationFlowsPanel.kt) — UI panel and tab entry point
