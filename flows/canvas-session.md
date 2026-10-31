# Canvas session flow

A canvas session is a visual walk through the code graph with many separate chats, each with its own context. The reader starts from a file and opens cards from symbols or selected text, building chains whose steps become the context of each card's chat.

## Opening

Opening a file starts a new session or resumes a saved one. The file is shown as source on a canvas, with the symbols that the IDE can resolve made clickable. Analysis runs locally, and if the file can't be shown, the reader gets a notification.

## Walking

- Clicking a symbol or selecting text opens a card beside it, linked by an arrow. Every project name on every shown line is a symbol: calls, types, type parameters, properties, parameters and variables, inside nested functions too.
- A card shows meta information, a summary, the selection or the symbol's source, the chat history and a chat input. Every part folds.
- A card's source works like the file: its symbols open further cards, so one chain can follow the code graph as far as the reader likes.
- Expand to file replaces a card's excerpt with its whole file, keeping its chat and branches. The reader can follow links anywhere in that file or chat about it; reopening the session keeps the expanded view.
- Closing a card also closes the cards opened from it.

## Chat

Each card is a separate conversation. Its context is the chain from the file to the card: the files involved, and the names, summaries and bounded sources of each step. The model reads more of the project with read-only tools instead of receiving whole files, so the context stays small.

## Persistence

A session is saved in the project as the reader works: cards, summaries, folded parts and chat histories. Reopening resolves its symbols again; a card whose symbol is gone keeps its saved text and chat.

The Canvas Sessions side panel lists the project's saved sessions. Open it from the canvas's Sessions button or the IDE's tool windows, filter by root file name with fuzzy search, and open a session to resume it.

## Entry points

- [CanvasSessionState.kt](../src/main/kotlin/dev/insight/canvas/CanvasSessionState.kt): the session model
- [CanvasChainContext.kt](../src/main/kotlin/dev/insight/canvas/CanvasChainContext.kt): the chain as chat context
