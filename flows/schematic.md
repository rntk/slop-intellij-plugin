# Schematic flow

A schematic is a lossy, explorable reading of a file through the application's call graph. Starting from a file, readers unfold declarations, calls, project usages, and on-demand summaries—including surrounding code gaps—while keeping the original source visible and verifiable.

Calls come from static analysis and summaries from a model, with source links bridging the two. An attached chat can investigate the graph and project and unfold call chains directly onto the schematic.

Entry points for further exploration:

- [Schematic.kt](../src/main/kotlin/dev/insight/callgraph/Schematic.kt) — the model of rows and blocks
- [SchematicEditor.kt](../src/main/kotlin/dev/insight/callgraph/SchematicEditor.kt) — the view and its actions
