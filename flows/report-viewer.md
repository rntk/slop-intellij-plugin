# Report viewer flow

Report viewer is a reading view shared by Code Review and Diff Review for Markdown, plain-text, and log reports, including pasted agent output. Readers open project report files, external files from disk, or clipboard text without leaving the review.

The viewer resolves project file paths, directories, line numbers, multi-ranges, and contextual line references (`L...`, `line ...`) against the project index, narrowing ambiguous matches using surrounding directories, section history, and the active review scope. Code symbols named in the text (functions, classes, properties, and other declarations the IDE indexes) link too: in code spans and blocks any declared name does, while prose links only names written the way code is, so ordinary words stay plain. Following a symbol opens its declaration, or lists the candidates, ranked by the files the report referred to and the review scope, the way Find Usages lists usages. Symbol detection and lookup are shared, so other views of generated text such as summaries and chat replies can link the same way. Clicking a resolved file link opens it in the editor at the target lines; directories, Shift+click, right-click, and the [Context picker](context-picker.md) shortcut connect any referenced file, range, or directory to the review's other views and actions. Both views support keyboard navigation: the project file filter/list handles Up/Down, Page Up/Down, and Enter to open a report, while the rendered viewer scrolls via arrow keys, Shift+arrows, Page Up/Down, Space/Shift+Space, Home/End, and Ctrl/Cmd+Home/End.

Select any text and right-click or use the [Context picker](context-picker.md) shortcut to preview the excerpt and open a temporary chat about it, or to open every file it links to together on a wall, in editors, or on a canvas. Choose **Copy selected source text** from the right-click menu to keep the original link targets and file paths. Deleting selected text saves the change to the report file and updates both Markdown and raw text views. For pasted reports, each mode keeps its own display edits until the report is reloaded or reopened.

Entry points for further exploration:

- [ReportLinkParser.kt](../src/main/kotlin/dev/insight/review/ReportLinkParser.kt) — project index, path/directory/line-range matching, and contextual disambiguation
- [links](../src/main/kotlin/dev/insight/links/) — view-independent symbol mention detection, declaration lookup and ranking, and the candidate chooser
- [ReportRenderer.kt](../src/main/kotlin/dev/insight/review/ReportRenderer.kt) — Markdown and plain-text HTML rendering with embedded link targets
- [ReportViewerCanvasCard.kt](../src/main/kotlin/dev/insight/review/ui/ReportViewerCanvasCard.kt) — shared viewer UI, file browsing, and context picker integration
- [ReportViewerNavigation.kt](../src/main/kotlin/dev/insight/review/ui/ReportViewerNavigation.kt) — keyboard navigation and focus handling across the file picker and rendered report
