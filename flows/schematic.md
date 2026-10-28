# Schematic flow

Schematic is a lossy reading of one file through the call graph. It starts from a file chosen in the editor or project view. The file is shown line by line, but the functions it declares fold into blocks, and lines with calls or callable references become blocks too. Everything else stays plain code.

Summary cards stay hidden until a block is focused or hovered. Tab and Shift+Tab move the cursor through clickable blocks; Enter or a left click on a block pins its cards so they stay visible when the cursor moves away. Cards can be unpinned individually. Summaries are written bottom-up, so each condenses the tree below its symbol. Unfolding a summary pins it and shows the same reading of that function or class beneath it, so a reader can keep several branches open. Deeper levels are summarized as they are opened.

The view is a canvas: drag to move it, zoom with Ctrl/⌘ + wheel or the + and − keys, and scroll with the arrow, Page Up/Down, Home and End keys.

Calls come from static analysis and summaries from a model; use the source links to check them. External and unresolved calls remain visible. “Explain link” shows candidate declarations and receiver bindings, so readers can inspect the evidence even when the implementation is unknown.

Entry points for further exploration:

- [Schematic.kt](../src/main/kotlin/dev/insight/callgraph/Schematic.kt) — rows and blocks of one level
- [SchematicEditor.kt](../src/main/kotlin/dev/insight/callgraph/SchematicEditor.kt) — the view and its action
