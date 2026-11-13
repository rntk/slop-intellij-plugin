# Hierarchy view flow

Hierarchy is a structural reading view shared by Code Review and Diff Review. Readers explore folders, topics, or change groups from broad branches on the left to files and their summaries on the right. Expanding a branch reveals its contents; folding it brings its own summary into focus.

Readers can adjust the visible depth, trace summary claims back to supporting evidence, preview the files beneath an entry, and continue into focused chats or source and diff views. Hierarchy and [Document](document.md) present the same review evidence from different reading perspectives.

Entry points for further exploration:

- [HierarchyColumns.kt](../src/main/kotlin/dev/insight/review/ui/HierarchyColumns.kt) — shared hierarchy presentation
- [ChangeSummaryHierarchyView.kt](../src/main/kotlin/dev/insight/review/ui/ChangeSummaryHierarchyView.kt) — folder and file summary hierarchy
- [ChangeTopicHierarchyView.kt](../src/main/kotlin/dev/insight/review/ui/ChangeTopicHierarchyView.kt) — topic and change-group hierarchy
