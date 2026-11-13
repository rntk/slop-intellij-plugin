# Document view flow

Document is a reading view shared by Code Review and Diff Review. Readers follow source files or diffs as one continuous document, with file summaries alongside the evidence and folder, topic, or change-group context aligned in a hierarchy of side columns.

Readers choose how much hierarchy to show while keeping the files visible, and can navigate from entries to focused chats, walls, or IDE source and diff views. Document and [Hierarchy](hierarchy.md) present the same review evidence from different reading perspectives.

Clicking a context card or evidence block brings its first file into a readable view. Back or Escape returns to the previous scale and reading position. Both moves ease smoothly between detail and overview so readers can keep their bearings.

Zooming keeps the file row and column under the pointer (or the view's center, for the zoom buttons) in place, even though cards and diffs scale at different rates. Ctrl+wheel scales the current picture immediately and swaps in the real layout as the zoom grows, so long gestures stay smooth without drifting away from where the reader is looking.

Navigation keys pan the document. When an evidence block has focus, its scrollable axes move its content instead, keeping the canvas still even at the content’s edges.

Entry points for further exploration:

- [ChangeTopicsView.kt](../src/main/kotlin/dev/insight/review/ui/ChangeTopicsView.kt) — shared document layout and topic or grouping documents
- [ChangeSummaryHierarchyView.kt](../src/main/kotlin/dev/insight/review/ui/ChangeSummaryHierarchyView.kt) — document view of the folder and file summary tree
- [SummariesCanvasCards.kt](../src/main/kotlin/dev/insight/review/ui/SummariesCanvasCards.kt) — summary view selection and review actions
