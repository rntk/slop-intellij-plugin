# Document view flow

Document is a reading view shared by Code Review and Diff Review. Readers follow source files or diffs as one continuous document, with file summaries alongside the evidence and folder, topic, or change-group context aligned in a hierarchy of side columns.

Readers choose how much hierarchy to show while keeping the files visible, and can navigate from entries to focused chats, walls, or IDE source and diff views. Document and [Hierarchy](hierarchy.md) present the same review evidence from different reading perspectives.

Entry points for further exploration:

- [ChangeTopicsView.kt](../src/main/kotlin/dev/insight/review/ui/ChangeTopicsView.kt) — shared document layout and topic or grouping documents
- [ChangeSummaryHierarchyView.kt](../src/main/kotlin/dev/insight/review/ui/ChangeSummaryHierarchyView.kt) — document view of the folder and file summary tree
- [SummariesCanvasCards.kt](../src/main/kotlin/dev/insight/review/ui/SummariesCanvasCards.kt) — summary view selection and review actions
