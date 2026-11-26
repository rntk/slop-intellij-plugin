# Context picker flow

The context picker is the shared hub that connects the plugin's views. From any code view, such as an editor, an IDE diff, or the Diffs or Code wall, the reader opens a small popup. It offers candidate scopes around the position, from the selection or line through the enclosing symbols and diff hunk up to the whole file, file diff, or change, and previews each one. Below the scopes it lists follow-up actions for the picked scope, narrowed by a fuzzy filter as the reader types, so the same context can move into another way of working with it: an editor or IDE diff, a canvas session, [Diffs wall](diffs-wall.md) or Code wall, call graph, flow chart, summaries, or chat.

A picked scope can also carry the links it mentions. The picker opens not only on a single recognized path or link but on any selected raw text, such as a paragraph of prose with file paths, directories, or line ranges mixed in, from a pasted agent report, a log, a Markdown note, or code comments. It is reachable from the context menu of that selection as well as by its shortcut. The picker scans the selection for links, resolves them the same way the [Report viewer](report-viewer.md) does, and offers everything it found as one more scope. Actions applied to that scope open all the linked targets at once: together on a [Diffs wall](diffs-wall.md) or Code wall, as cards in a [canvas session](canvas-session.md), as editors at their lines, or as context for summaries and chat. When a link matches more than one file, the reader chooses which one to use before the action runs.

When a picker belongs to a review session, it also offers that review’s summaries and reading modes, topics, words, and applicable diff views. These actions open the whole originating review.

In the [Report viewer](report-viewer.md), the picker previews the selected report text. **Ask about selected text** opens a temporary chat with that excerpt and read-only tools to list and read project files. **Delete selected text** saves the deletion to the report file. For pasted reports, it edits the current view. When the selection contains file links, a second scope previews the list of linked files and opens them all at once: on the review's [Diffs wall](diffs-wall.md) or Code wall, in editors at their lines, or as cards of one [canvas session](canvas-session.md). Ambiguous links and directories are listed but left out.

The code and diff review charts offer the picker from file and directory context menus, including line chart breadcrumbs and radar spokes. In the Tree view, topic and group cards also offer the picker for their files (for example, to open them together on the Diffs wall, Code wall, editors, or canvas). In Code Review, opening the picker from a declaration row selects that declaration's captured source range. The whole file is also available.

The picker can generate missing summaries for a directory or a file. A selection, symbol, line, or hunk offers a summary of its containing file or file diff. In a review, the results are saved in that review’s [Summaries tree](summaries.md). From an editor, Project View, or standalone two-sided text diff, the action opens Summaries in a matching review. If it needs a new review tab, that tab keeps the source or comparison captured for the request.

Hosts turn a position into candidate scopes plus typed extras, and symbol borders come from PSI. Actions are contributed through an extension point or registered by a view, and each one decides for itself which scopes it applies to. A new view or tool joins the hub by adding a host, an action, or both.

Entry points for further exploration:

- [context](../src/main/kotlin/dev/insight/context/): context model, scopes, picker popup, and action registry
- [context/actions](../src/main/kotlin/dev/insight/context/actions/): built-in follow-up actions
