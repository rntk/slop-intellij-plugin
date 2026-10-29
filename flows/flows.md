# Flows flow

Flows organizes a call graph around likely execution starts. It groups related calls into smaller, navigable views so readers can follow the main responsibilities of an operation without starting from the entire project graph.

The groupings are guides to code structure, not guarantees about runtime behavior. Start with Entrypoints to find likely roots, then use a flow to explore the surrounding calls.

Entry points for further exploration:

- [ApplicationFlows.kt](../src/main/kotlin/dev/insight/enrichment/ApplicationFlows.kt) — flow construction
- [ApplicationFlowsPanel.kt](../src/main/kotlin/dev/insight/callgraph/ApplicationFlowsPanel.kt) — flow browser
