# Canvas session flow

A canvas session is a visual walk through the code graph with many separate chats, each with its own context. The reader starts from a file and opens cards from symbols or selected text, building chains whose steps become the context of each card's chat.

## Opening

Opening a file starts a new session or resumes a saved one. The file is shown as source on a canvas, with the symbols that the IDE can resolve made clickable. Analysis runs locally, and if the file can't be shown, the reader gets a notification.

Opening from Diff Review starts a new exploration with related changed files already shown as connected cards. The reader can follow symbols and continue those chains as in any other session.

## Walking

- Clicking a symbol or selecting text opens a card beside it, linked by an arrow. Every project name on every shown line is a symbol: imports, calls, types, type parameters, properties, parameters and variables, inside nested functions too.
- A card shows a header with its title and a compact menu of its commands (also on right-click), then meta information, a summary, the selection or the symbol's source, the chat and a chat input. Every part folds. Titles stay readable when zoomed out and shrink to fit their card.
- A card's source works like the file: its symbols open further cards, so one chain can follow the code graph as far as the reader likes.  Cards are wide enough for most lines, and a long source scrolls inside its card instead of stretching it.
- Find usages on a symbol card, or in the context menu on any symbol, searches the project for callers and other references. Referencing declarations and source locations open as connected cards beside the current card so the walk can continue in either direction, even when a card's own text has no outgoing links.
- Expand to file replaces a card's excerpt with its whole file, keeping its chat and branches. The reader can follow links anywhere in that file or chat about it; reopening the session keeps the expanded view.
- Closing a card also closes the cards opened from it.
- Side by side, from a card's menu, opens its chain from the root in a separate source view, with each file positioned at that step and any generated summary above its source.
- Attach file on the root file or any card opens a file picker. Confirming adds connected attachment cards for documentation, screenshots or other local files, including files outside the project. Text and pictures have inline previews; Open file uses the IDE's editor or installed viewer for the original. Closing an attachment removes its canvas link.

## Chat

Each card has chat tabs, shown only once there are several, and an input that is always at hand. Readers can start a new conversation, switch tabs, or close a tab, keeping at least one. Every tab has its own history, draft and running reply; switching tabs keeps replies running, and closing a tab stops its reply. Tabs share the card's context: the chain from the file to the card, with the name, place, opening line, summary and bounded source of each step, and the cards opened from the card. Other chains on the canvas are left out so unrelated exploration doesn't crowd the context; the model can list and read them on demand, and reads the rest of the project with read-only tools instead of receiving whole files.

The model can also act on the canvas as the reader does, to show the code it talks about: click a symbol in any shown source, select lines, or open a card for a declaration or file the chain doesn't reach, attached to its own card. Each reply opens only a few cards. The chat starts with the tools a walk needs most and finds the others by search when a question calls for them, so their descriptions don't fill every request.

Attachments linked to the current chain are shared by its chat tabs. Chats receive bounded text snapshots and supported image snapshots; other file types are identified by name and type. Images require a model that supports vision. Attachments on unrelated branches stay out of the context.

## Persistence

A session is saved in the project as the reader works: cards, summaries, folded parts, chat tabs, histories, drafts and the selected tab. Reopening resolves its symbols again; a card whose symbol is gone keeps its saved text and chats. Existing single-chat cards reopen with their conversation in the first tab.

Attachment links and preview snapshots are saved too. They keep their saved previews when the original moves or disappears; opening the original requires that file to remain available.

The Canvas Sessions side panel lists the project's saved sessions. Open it from the canvas's Sessions button or the IDE's tool windows, filter by root file name with fuzzy search, and open a session to resume it.

## Entry points

- [CanvasSessionState.kt](../src/main/kotlin/dev/insight/canvas/CanvasSessionState.kt): the session model
- [CanvasChainContext.kt](../src/main/kotlin/dev/insight/canvas/CanvasChainContext.kt): the chain as chat context, and the chat prompt
- [CanvasTools.kt](../src/main/kotlin/dev/insight/canvas/CanvasTools.kt): what a card's chat can read and open on the canvas
