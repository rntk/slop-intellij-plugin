# Context picker flow

The context picker is the shared hub that connects the plugin's views. From any code view, such as an editor, an IDE diff, or the Diffs or Code wall, the reader opens a small popup. It offers candidate scopes around the position, from the selection or line through the enclosing symbols and diff hunk up to the whole file, file diff, or change, and previews each one. Below the scopes it lists follow-up actions for the picked scope, so the same context can move into another way of working with it: an editor or IDE diff, a canvas session, [Diffs wall](diffs-wall.md) or Code wall, call graph, flow chart, summaries, or chat.

When a picker belongs to a review session, it also offers that review’s summaries and reading modes, topics, words, explainer board, and applicable diff views. These actions open the whole originating review.

Hosts turn a position into candidate scopes plus typed extras, and symbol borders come from PSI. Actions are contributed through an extension point or registered by a view, and each one decides for itself which scopes it applies to. A new view or tool joins the hub by adding a host, an action, or both.

Entry points for further exploration:

- [context](../src/main/kotlin/dev/insight/context/): context model, scopes, picker popup, and action registry
- [context/actions](../src/main/kotlin/dev/insight/context/actions/): built-in follow-up actions
