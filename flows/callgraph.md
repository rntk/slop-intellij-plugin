# Call graph flow

The call graph feature lives in `src/main/kotlin/dev/insight/callgraph`. It inspects code structure to visualize call relationships (callers and callees) as interactive graphs.

At a high level: an action or tool window requests a graph for a selected target, a service orchestrates the analysis in the background, language-aware analyzers traverse references and call sites to construct the graph model, and a layout engine positions nodes to render the graph onto a canvas.

Entry points for further exploration:

- [CallGraphService.kt](../src/main/kotlin/dev/insight/callgraph/CallGraphService.kt) — graph construction orchestration
- [CallGraphEditor.kt](../src/main/kotlin/dev/insight/callgraph/CallGraphEditor.kt) — UI and action entry point

Available [entrypoint search results](entrypoints.md) from both APIs are shared with the application and function graphs. Matching source nodes have an accent fill, a stronger outline and a **▶ Start** label; their tooltips describe the entrypoint and its source location. Collapsed groups containing entrypoints carry the same marker, so starting points remain visible at overview and file detail levels. Open graphs update when a search succeeds, without another graph analysis or model request. Each API replaces its own previous results; failed or cancelled searches retain them. Entry points decorate existing nodes and do not change call relationships.

## Split flows

After finding starts in **Entry Points**, click **Split flows** in the **Flows** tab (see [flows.md](flows.md)). Each logical operation gets its own card with a separate graph that retains its starting symbol, source navigation and entrypoint marker. A start can also be picked manually with **Choose entrypoint…**.

[ApplicationFlows.kt](../src/main/kotlin/dev/insight/callgraph/ApplicationFlows.kt) matches discovered source locations using the same rules as the markers. The LLM receives bounded source excerpts and call neighbourhoods, and groups an entrypoint's immediate call branches by purpose. Only supplied branch IDs are accepted; duplicate or invented memberships are rejected, and omitted branches receive structural fallbacks. Cached judgments are keyed by the supplied evidence. A failed model request also falls back to one flow per branch, so splitting does not require a successful full enrichment run.

Membership comes from static call walks (including method dispatch), with another entrypoint included as a leaf. When matching enrichment exists, its Decisions API judgments stop calls to generic utilities, incidental operations and error paths. Shared helpers can appear in several flows: these are overlapping logical views, not a claim that the application has independent components. Displayed edges come from the analyzed graph; unrelated class members are excluded rather than folded into a selected node.

Each flow is bounded to 40 nodes and 8 call levels, and the UI marks views limited by traversal or incomplete analysis as partial. Class entrypoints seed their declared functions; file-only entrypoints, such as configuration or container files without an executable graph symbol, are reported as unmatched. Flows describe call structure, not proven runtime sequencing or data movement. **Cancel** stops pending work. Graph rebuilds and changed entrypoint results leave the flows visible but marked out of date, and actions needing current node IDs are disabled until the next split.

## Role: inspection, not overview

On large codebases a raw call graph is a dense tangle of low-level nodes: connecting, writing, checking errors, committing, logging. It is slow to lay out and says little about what the application does. The call graph is therefore an **inspection tool**: the neighbourhood of a symbol, dependency questions, and the source of truth that every abstraction is checked against. The readable view of the application is the **Flow Chart**, built on the same graph:

1. **Deterministic structure.** Calls, control frames, exits and dispatch come from static analysis only.
2. **LLM markup.** The model labels what code already found and picks from closed vocabularies: per-function summaries written bottom-up (summaries of summaries), and labels for bounded call regions. It never adds nodes, edges or ordering.
3. **Deterministic assembly.** Rules fold helpers, keep side-effect badges from code rules and open a flow within a visible-item budget. Every compact node can be drilled into, down to the raw calls and source.

**Side by side** opens a symbol as full source columns: its transitive callers on the left (farthest first), the symbol itself, then what it calls on the right. The view opens scrolled to the symbol; each column links **← Called by** and **Calls →** to its neighbours. It is in the application graph's node menu, and as **Open Side by Side** in the editor and structure view context menus, so it works without opening the tool window: it reuses the last application graph while the code is unchanged, otherwise builds one in the background.

The function call graph has **Explore flow**, which opens the selected in-project function (or the root) in the **Flow Chart** tab, and the chart's **Show in graph** comes back here. See [flowchart.md](flowchart.md) for the grounding rules, efficiency measures and current limits.
