# Schematic flow

Schematic is a lossy reading of one file through the call graph. It starts from a file chosen in the editor or project view. The file is shown line by line, but the functions it declares fold into blocks, and lines that call project code become blocks too. Everything else stays plain code.

Next to each block is a summary of the symbol it leads to. Summaries are written bottom-up: leaf functions first, then each caller from the summaries of what it calls, so a summary condenses the whole tree below it. Unfolding a summary shows the same reading of that function or class beneath it, so a reader can walk down the graph and keep several branches open at once. Deeper levels are summarized as they are opened.

The view is a canvas: drag to move it, zoom with Ctrl/⌘ + wheel or the + and − keys, and scroll with the arrow, Page Up/Down, Home and End keys.

Calls come from static analysis and summaries from a model; use the source links to check them.

Entry points for further exploration:

- [Schematic.kt](../src/main/kotlin/dev/insight/callgraph/Schematic.kt) — rows and blocks of one level
- [SchematicEditor.kt](../src/main/kotlin/dev/insight/callgraph/SchematicEditor.kt) — the view and its action
