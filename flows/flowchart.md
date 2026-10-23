# Flow chart flow

The Flow Chart presents an application’s structure as a readable overview. It combines code-derived relationships with concise labels to show major responsibilities and how they connect. Readers can expand areas of interest and follow them back to the underlying code.

Chart Chat lets a reader ask questions about the graph and arrange relevant parts into explanatory views. The chart supports exploration; its summaries and paths are aids to understanding, not proof of runtime behavior.

Entry points for further exploration:

- [FlowChartLens.kt](../src/main/kotlin/dev/insight/enrichment/FlowChartLens.kt) — chart content and labels
- [FlowChartPanel.kt](../src/main/kotlin/dev/insight/callgraph/FlowChartPanel.kt) — chart interface
