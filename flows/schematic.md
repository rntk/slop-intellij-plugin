# Schematic flow

A schematic is a lossy, explorable reading of one file through the application's call graph. The reader starts from a file and opens its declarations and the calls they make, one level at a time, reading a summary of each symbol as it opens.

## Opening

Opening builds or reuses the project's call graph. If the file cannot be shown, the reader gets a notification; details go to the IDE log. Analysis runs locally.

## Exploring

- Declarations and calls appear as clickable blocks, shown on a canvas or in a read-only IDE editor. Switching views keeps the exploration.
- Summaries appear on demand and can be pinned. Each summary condenses the tree below its symbol, and unfolding one shows that reading beneath it, so several branches stay open at once.
- A breadcrumb trail shows the path from the root and lets the reader return to earlier symbols.
- Exploration is never discarded; the reader can move freely beyond the initial graph.
- Find usages on a symbol searches the project for callers and other references. Their source locations stay linked, and referencing symbols unfold alongside the existing exploration, so the initial file is a starting point rather than a boundary.
- Summarize gaps describes meaningful source between known symbols on demand. Each gap grows to the complete statements or declarations the language parses around it, within a size bound; a construct too long to read whole is described by its header. A gap may hold known symbols, which stay clickable inside its frame while its folded bodies stay out of the summary. Original code stays visible, framed and linked by an arrow to an external summary card with a source link, alongside known-symbol cards; summaries have no generated title; the model uses relevant IDE facts and bounded surrounding source, and can read and search the file.

## Trust

Calls come from static analysis and summaries from a model, so source links are the way to verify them. External and unresolved calls stay visible rather than being hidden.

## Chat

A chat is attached to the canvas and lasts as long as the schematic. It investigates the graph and project on its own, then answers by unfolding call chains as ordinary cards the reader can keep exploring or fold away.

## Entry points

- [Schematic.kt](../src/main/kotlin/dev/insight/callgraph/Schematic.kt) — the model of rows and blocks
- [SchematicEditor.kt](../src/main/kotlin/dev/insight/callgraph/SchematicEditor.kt) — the view and its actions
