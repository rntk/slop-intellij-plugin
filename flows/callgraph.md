# Call graph flow

The call graph shows how functions and other code elements call one another. It helps trace a symbol’s callers, callees, and nearby dependencies in the project.

Entry points, when available, provide useful starting places. The separate **Flows** view organizes work around those starts, while the **Flow Chart** gives a more summarized view of the application. Call relationships are based on code analysis and may not describe every runtime path.

Entry points for further exploration:

- [CallGraphService.kt](../src/main/kotlin/dev/insight/callgraph/CallGraphService.kt) — call graph analysis
- [CallGraphEditor.kt](../src/main/kotlin/dev/insight/callgraph/CallGraphEditor.kt) — graph UI
