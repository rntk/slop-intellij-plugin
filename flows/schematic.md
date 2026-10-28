# Schematic flow

Schematic is a lossy reading of one file through the call graph. It starts from a file chosen in the editor or project view. Source can use the original canvas rendering or a read-only IDE editor with syntax highlighting, line numbers, and text selection. The controls switch the whole exploration between them without discarding open branches or pinned cards. In either view, declared functions fold into blocks, and lines with calls or callable references become blocks too.

Summary cards stay hidden until a block is focused or hovered. Tab and Shift+Tab move the cursor through clickable blocks; Enter or a left click on a block pins its cards so they stay visible when the cursor moves away. Cards can be unpinned individually. Summaries are written bottom-up, so each condenses the tree below its symbol. Unfolding a summary pins it and shows the same reading of that function or class beneath it, so a reader can keep several branches open. Deeper levels are summarized as they are opened.

The canvas fills the whole tab; the controls and the breadcrumb trail float over its top edge instead of taking rows of their own, and the help text lives in a tooltip. Drag to move the canvas; in IDE source text, dragging selects text instead, and middle-dragging moves the canvas. Zoom with Ctrl/⌘ + wheel; when the canvas has focus, + and − zoom, and the arrow, Page Up/Down, Home and End keys scroll. Zoom scales the code and cards while keeping titles on the selected breadcrumb chain large enough to read in an overview. Other callable titles scale normally. A floating breadcrumb trail stays readable at every zoom, follows the selected branch from its root, and lets the reader return to earlier symbols. Showing, hiding, or unfolding cards keeps the current view in place. The canvas preserves explored space and lets the reader move beyond the graph; new content can appear off screen without moving the view. Tab brings the selected block into view, while Home and End return to the start and end of the reading.

Calls come from static analysis and summaries from a model; use the source links to check them. External and unresolved calls remain visible. “Explain link” shows candidate declarations and receiver bindings, so readers can inspect the evidence even when the implementation is unknown.

Entry points for further exploration:

- [Schematic.kt](../src/main/kotlin/dev/insight/callgraph/Schematic.kt) — rows and blocks of one level
- [SchematicEditor.kt](../src/main/kotlin/dev/insight/callgraph/SchematicEditor.kt) — the view and its action
