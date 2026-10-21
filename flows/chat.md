# Chat flow

The chat feature lives in `src/main/kotlin/dev/insight/chat`. It is a tool-window
conversation where the user sends a message, sees tool progress, and receives an assistant reply.

At a high level: the tool window collects user input, a service orchestrates the
conversation, a backend produces the assistant reply (backed by the configured LLM endpoint), and the backend can call project tools to read
from the open project before answering.

Across chat modes, the assistant is a read-only guide in a graphical IDE. It uses visual
tools to show relevant source and relationships in context, with chat explaining the
evidence and uncertainty. Presentation may change the views, but never project source.

Entry points for further exploration:

- [ChatService.kt](../src/main/kotlin/dev/insight/chat/ChatService.kt) — conversation orchestration
- [ChatToolWindowFactory.kt](../src/main/kotlin/dev/insight/chat/ChatToolWindowFactory.kt) — UI entry point

The Flow Chart tab embeds the same `ChatPanel` with an independent `ChatSession`.
The sidebar's project service owns regular chat; the chart owns and cancels its own
session scope. Both share settings, context compaction, tool progress, and Stop/New
chat behavior. The backend appends fresh chart context to the current question only;
snapshots are not saved in conversation history.

All chat composers (including scoped chart tabs) offer **Add file…**; typing a standalone
`@` in the prompt opens the same closable file search. Search matches file names and
relative paths case-insensitively, including camel humps and partial letters, and shows
up to 50 ranked suggestions. Use Up/Down and Enter, or click a result, to insert its
relative path at the caret or replace the selected text/`@`. **×** or Escape closes the
search without changing the draft. The assistant receives the path as ordinary prompt
text and can read it with the existing project tools.

`ChatFilePicker.kt` owns the shared UI. `ChatFileSearch.kt` caches project content paths
(excluding build output and other excluded directories), refreshes on file structure or
project root changes, and debounces/cancels background searches so typing does not scan
the filesystem on the EDT. Closing or disposing the picker cancels pending results.

Chart chat uses chart discovery, analysis and scene tools plus `read_file`, `list_files`
and `search_text` (regular chat also has `open_file`). Each **To chat** action opens an
independent scoped conversation with captured source and metadata plus fresh chart state
on each question. See [Chart chat](flowchart.md#chart-chat) for context, tools, validation,
scenes and **Undo chart changes**. Replies arrive after the tool loop completes; tool
progress updates during the turn, but reply text is not streamed.

The LLM endpoint is chosen in settings (`ChatSettings.kt`): llama.cpp, OpenAI, DeepSeek or
Anthropic, with clients in `src/main/kotlin/dev/insight/llm`.

Regular chat allows 12 tool-call rounds per reply (`DEFAULT_MAX_TOOL_ROUNDS`); chart chat
allows 100. After the cap, one final request uses `tool_choice: none`; if the model still
asks for tools they are rejected unexecuted and it is told to answer now. Context
compaction and tool-result size bounds still apply.
