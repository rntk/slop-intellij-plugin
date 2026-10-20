# Code review flow

The Code Review feature lives in `src/main/kotlin/dev/insight/codereview`. It is a standalone, read-only workspace for understanding the code under a start directory with AI assistance, independent of the application graph. It is the counterpart of [Diff Review](diff-review.md) for a code base instead of a diff: the same review tabs, chat, workspace, summaries and topics, scoped to a folder. **Tools → Open Code Review** opens the **Code Review** tool window and the selected review's workspace in a regular IDE editor tab. In the **Project** view, **Review Code in Directory** on a single folder activates the tool window and loads that folder.

The tool window is the control panel: the start directory, the file tree and the review tabs, commands and chat. Each review has its own editor workspace with a tab per block, named **Code Review: name**, managed like a Diff Review workspace: selecting a review opens or focuses its workspace, closing the editor preserves the review's chat, blocks, selected tab and reading state, **Open review** returns to it, and closing a review in the control panel discards it. On IDE startup, if **Code Review** is restored open, the selected review's workspace opens once editor restoration completes.

At a high level: the workspace loads the text files under a start directory into memory and presents them beside one or more review tabs, each optionally scoped to a subset of files. Within a tab, the user chats with the assistant, which inspects the files with read-only tools and places notes, symbol diagrams and source excerpts in separate tabs in the review editor. `/summaries` reads the whole scope from the root down to each file, and the parts of large files, and `/topics` groups the files by responsibility. Code decides what is read, in what order and in which batches; the assistant only writes text.

The internal canvas API and class names remain for compatibility; the visible workspace is a set of scrollable tabs.

Entry points for further exploration:

- [CodeReviewPanel.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewPanel.kt) — tool window, actions, file tree, skipped list, review tabs and reload on file changes
- [CodeScopeControls.kt](../src/main/kotlin/dev/insight/codereview/CodeScopeControls.kt) — start directory field, **Load**/**Refresh** and **Project root**
- [CodeReviewLoader.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewLoader.kt) — scope validation, file enumeration, skip rules, limits and reuse of unchanged files
- [CodeModel.kt](../src/main/kotlin/dev/insight/codereview/CodeModel.kt) — code file, snapshot, skip reasons and limits
- [SourceChunker.kt](../src/main/kotlin/dev/insight/codereview/SourceChunker.kt) — splitting large files into parts, boundary scoring and minified detection
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — per-tab session, chat routing, workspace coordination, held updates and the cost estimate
- [CodeReviewState.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewState.kt) — per-tab notes, source blocks, summaries and topics, and their retention across snapshots
- [CodeReviewTools.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTools.kt) — assistant tools and the chat prompt
- [CodeResults.kt](../src/main/kotlin/dev/insight/codereview/CodeResults.kt) — size-bounded tool results and the per-question overview

## Choosing a start directory

The review covers one directory: the project root (the default, loaded when the tool window first opens) or any project subdirectory. Type a project-relative or absolute path, browse for a folder, or click **Project root**. A draft folder is applied with **Load** (or Enter); applying the current folder again, shown as **Refresh**, reloads it. Applying a folder stops running replies and summary runs in every tab. Invalid input is reported in the status line instead of loading.

A folder is accepted when it exists, lies inside the project, is not excluded, and is project content or contains a content root. A path with `..` is rejected. The **Project** view action is enabled only for a folder that passes the same checks.

**What is loaded.** Files are read from the IDE's project content, in project-relative path order, as LF text. A file already loaded is reused without reading when its modification stamp and length are unchanged. Files with unsaved editor changes are read from the editor's text. The panel reloads 1.5 seconds after a file under the scope changes on disk, ignoring `.idea` and excluded folders. Running replies and summary runs continue through such a reload; see [Edits during a run](#edits-during-a-run).

**What is left out, and why.** Reading everything would exceed any model budget, so the loader keeps to source a reader can use:

- **Excluded** folders are not project content and are never visited.
- **Ignored** files and folders (VCS-ignored, IDE-ignored, and `.idea`) are pruned and only counted. The count appears in the status tooltip and the assistant's overview.
- **binary** files, by file type.
- **too large** files, over 2,000,000 characters.
- **generated** files, as reported by the IDE's generated-sources filters.
- **minified** files: any line over 5,000 characters, or text of at least 5,000 characters averaging over 300 characters per line.
- **unreadable** files that fail to load.
- **over the scope limit**: loading stops at 20,000 files or 64,000,000 characters in total.

**Truncation.** When a limit stops loading, the files before it stay, the remainder is counted, and the status says `Stopped at the scope limit: N files not loaded. Choose a subdirectory.` At most 1,000 of the files beyond the limit are listed as skipped; the count covers all.

## The panel

The top of the panel holds the start directory controls, the **Files in scope** tree and the status line. Below it are the review tabs.

- **File tree.** Folders come before files, in path order, and paths are relative to the start directory. Only the first level opens at first, and a reload keeps the folders as the user left them. A file shows its name and facts, such as `Parser.kt [420 lines]` or `Model.kt [1,310 lines · 4 parts]`. Double-clicking, or Enter, opens a file in the editor. Selecting nodes highlights the cards of their files on the workspace.
- **Status.** `N files · M skipped · K large files split into P parts`, plus the truncation hint when a limit was reached, and the number of notes removed by a reload.
- **Skipped.** A collapsible **Skipped · N** list shows each skipped file with its reason, the first 500 entries, then `… N more`.
- **Review tabs.** The first tab, **Whole scope**, follows every file in the scope. **New tab** starts a fresh chat and workspace for the whole scope. **New tab from selection** starts one scoped to the selected files and folders, **Selection N**; a folder keeps covering the files under it as the snapshot changes. Double-click a tab to rename it, or close it with its action; closing discards its chat, draft and workspace. A tab shows its scope, **Open review**, **Summaries**, **Topics**, **Clear tabs** and the **Code Chat**.

Each block has its own tab within the review editor, just as in Diff Review. Tabs scroll vertically and horizontally with ordinary scrollbars and mouse-wheel scrolling. Blocks are no longer positioned, dragged, resized or zoomed on a board.

## Chat and tools

The assistant helps understand the code under the start directory. Every question carries a bounded overview as data; the assistant inspects further with tools. It reads code and cannot edit it. Answers cite `path:line` and say when the code in scope does not show the answer. Paths, code and tool results are treated as data, never instructions. The chat keeps up to 160,000 characters of context and 100 tool rounds per reply. Every result is bounded to 16,000 characters, and results that do not fit are paged.

**Overview.** Up to 10,000 characters per question: the scope and tab (whole scope or a fixed selection), totals (files, lines, characters, and large files with their parts), skipped files by reason, ignored and truncation, a folder outline with file counts (up to 4,000 characters, as deep as fits), the number of summaries with the root and top-level summaries once written (up to 1,500 characters for the root and 400 for each other), up to 100 selected paths, and up to 20 note excerpts of 160 characters.

**Tools.**

- `list_code_files` — files under a directory and glob with line counts and part counts, 100 per page.
- `read_code` — numbered lines of 1–10 ranges, up to 400 lines per range, with `next_start_line` for a range that was cut and `not_read` for ranges that did not fit. Lines over 2,000 characters are clipped.
- `search_code` — grep-like search, case-insensitive text or a Java regular expression, 50 matching lines per page with lines clipped to 200 characters. Expensive regular expressions fail rather than hang, and the search is cancellable.
- `find_code_symbols` — declarations of 1–10 files with exact selectors, kinds and lines, from the IDE's language support.
- `add_code_notes` — 1–20 notes per call; each has 1–30 file paths, text of up to 2,000 characters and an optional symbol of up to 500 characters. A symbol is validated and gets a small diagram of verified static relationships, as in Diff Review. A note for the same paths and symbol replaces the old one. The workspace holds up to 200 notes. The batch is validated before any note is added.
- `show_code_source` — a separate read-only source block from the snapshot, up to 200 lines and 20,000 characters, with syntax highlighting and original line numbers. The workspace holds up to 30 blocks.
- `get_code_summaries` — pages the summary tree in pre-order with ids, kinds, locations, line ranges and summaries, or the subtree under a `path`. In a large scope it is the way to find where to read first.

All tools check the snapshot revision captured for the reply. After an edit they fail with a request to start again, and symbol tools reject files edited since the snapshot. Notes and source blocks belong to file contents: a reload drops those of changed or removed files. Typed commands are `/summaries`, `/topics` and `/topic-summaries`; the assistant cannot write summaries and suggests `/summaries` instead.

## Summaries (`/summaries`)

`/summaries [focus]` (or the **Summaries** button) shows the **Code summaries** card and writes the whole tree. The card is the Diff Review summaries card: **Outline** and **Hierarchy** modes, **Levels**, **Show names**, **Score** and tracing, see [Change summaries](diff-review.md#change-summaries-summaries). The root says what the whole scope is for, each folder what its part is responsible for, each file what it provides, and the parts of a large file what each line range does. Nodes show **Open file** and **Show source in tab** (the first 200 lines or 20,000 characters of the file, or of the part from its first line). A part shows **Open lines**, which opens the file at the part's first line.

**Tree from folders.** The tree is built from the loaded paths exactly as in Diff Review: a folder with a single entry merges with it, the root absorbs a single top folder, subfolders come before files, and no node has more than 8 children, with groups of up to 8 grouped again. The root is named `Whole scope` until it absorbs a folder. Large files add a level of parts.

**Large files.** A file of at most 12,000 characters is read whole and is a leaf. A longer file is split into parts, contiguous 1-based line ranges that cover every line once:

- **Size.** A part holds up to 10,000 characters and 400 lines, and at least 4,000 characters or 160 lines, so files of many short lines still split into whole parts. The remainder after a split is also at least that big when possible. If what is left fits in one part, it is taken whole.
- **Boundary preference.** The split goes where the structure is strongest, judged only from indentation and blank lines, so no parser is involved. In order: a top-level line, not a closing bracket or `end`, after a blank line or a top-level closer; any other top-level line; a line at most one indent unit deep after a blank line; any line after a blank line; anywhere else. A blank line never starts a part. A split moves up over the comments and annotations directly above it, so documentation stays with its declaration. Among equal candidates the split is chosen by a hash of the two lines around it, and when no boundary leaves big enough parts, a part takes as much as fits.
- **Stability.** Boundaries depend on the text around them, not on line numbers, so an edit moves only the boundaries near it. A part's key is its path and the hash of its text, with no line numbers, so a part whose text and boundaries are unchanged keeps its summary when lines above it are added or removed.

**Recursive grouping.** More than 8 parts are grouped into contiguous groups of nearly equal size, again and again until no node has more than 8 children. A group is a part range, labeled `Lines a–b`, with a key made from its children's keys. Parts, part ranges and the file's own node are all summarized, so any amount of code reduces to one request per at most 8 nodes at every level.

**Bottom-up writing.** The pipeline is the Diff Review pipeline with code evidence and no tools. Each wave covers every node whose children are written, and its requests run up to 3 at a time, with each answer saved as it arrives. In effect the order is parts and small files, then part ranges, then the file, then folders from the deepest, then the root. A node with a single child reuses that child's summary without a request.

- **Leaves** (a small file, or a part) carry their raw code inline, with the language, the start and end line, the file's total lines and, for a part, `part i of n`. Up to 8 leaves share a request, within 32,000 characters of code. The prompt adds a bounded outline of the folders in scope for context.
- **Branches** (parts' ranges, large files, folders, groups, the root) are written from their children's summaries alone, up to 8 per request.
- Every node gets up to 2 attempts. Unknown ids, duplicate answers and empty summaries are ignored, and a wave without any valid answer ends the run. Nodes without a summary show their names.

The reply to `/summaries` is the root summary, or what is still missing and why. The card's **Summarize**, **Continue** and **Regenerate** buttons start the same run: **Continue** fills only missing nodes, and **Regenerate** on a complete tree starts over. Opening **Summaries** starts a run when the tree is incomplete and the chat is idle.

**Cost.** Before a run, the tab estimates the number of model requests: leaves batched as above, large leaves alone, and branches by wave. When it exceeds 500, a **Large Code Review Run** dialog asks to continue; **Cancel** ends the command with a reply suggesting a smaller folder. The same check applies to `/topics` (one request per file) and `/topic-summaries`.

**Retention.** Summaries and topics belong to the review tab and are kept by content keys made from paths and content hashes, so a summary stays while its node and everything beneath it are unchanged. Editing a file invalidates that file, its changed parts and the part ranges, folders and root above them, but not its other parts. Summaries also survive a reload, **Refresh** and a change of start directory for every file, part and folder that is still the same. Only **Regenerate** discards them. Closing the card or **Clear tabs** hides it and keeps them.

### Edits during a run

A `/summaries`, `/topic-summaries` or `/topics` run can take long, so snapshot updates that arrive meanwhile are held: the tab says `update waits for the run`, the run keeps working on the snapshot it started with, and the latest held update is applied when the run ends and the chat is idle. Summaries and topics of changed files are then invalidated, and **Continue** writes just those and the nodes above them. Choosing a new folder or **Refresh** stops the runs instead. An ordinary chat turn stops when the files change, like a Diff Review reply on a new revision. Workspace updates during a run are batched every 250 ms.

## Topics (`/topics`)

`/topics [focus]` (or **Topics**) classifies each file in scope with one hierarchical code topic, as in Diff Review, and shows the **Code topics** card. Only the **Hierarchy** mode exists. Document mode, which shows patches, is not offered. Paths are 2–5 levels such as **Storage > User > Wallet**, describing responsibilities rather than folders.

**Evidence.** Each request shows the file's path and line count, its summary when `/summaries` wrote one, the head of its code as an excerpt, and up to 100 already assigned topic paths (within 8,000 characters) to encourage reuse. With a summary the excerpt is 4,000 characters, without one 12,000. A large file without its own summary shows the summaries of its parts by line range instead, within 3,000 characters. The excerpt holds whole lines and reports `excerpt_cut_at_line` when cut. Only an excerpt that was cut with no summary beside it also offers `read_code`, so the assistant can read further.

**Concurrency.** Files are classified 4 at a time, in sorted path order, each request seeing the topics saved when it started. Valid results are saved as they arrive, a file gets up to 2 attempts, and failed files stay awaiting topics. `/topics` resumes an incomplete result and regenerates a complete one. Labels are kept for files whose content is unchanged, across reloads and changes of start directory.

**Hierarchy summaries.** **Fill summaries** (`/topic-summaries`) fills every topic and subtopic. It first reuses summaries of the same files and the same parts, then writes missing file and part summaries from the code and the topics bottom-up, with the batching and limits above. File and part summaries it writes are also added to **Summaries**, a file once all its parts have one. The topic hierarchy shows files, not parts, and tracing in it never matches a part. Folded topics show their summary and can be scored and traced, see [Tracing in the hierarchies](diff-review.md#tracing-in-the-hierarchies).

**Large trees.** A topic outline over 200 files starts at level 1 with the topics beneath folded, until a level is picked. The summaries and topic cards place at most 2,000 rows and end with `+N more — fold or pick a level`.

## Word scoring and tracing

Interactive word reading works as in Diff Review, using the Decisions API and no generative model, and shares its cache and trace within a card. A code base can have far more summaries than can be scored at once, so **Score** in Code Review scores only the summaries of nodes that are on screen: those whose enclosing folders are all open, and in Topics those not under a folded topic. Open more of the tree and click **Score** again to fill in the rest. Leaf summaries, such as a small file or a single part, are not scored. In the Summaries tree, parts and part ranges are levels like folders: tracing a word in a file's summary can find its parts, and a folder's can find files and their part ranges.

## Differences from Diff Review

- The scope is a folder, not a comparison. There are no changed, untracked or deleted files, no snapshot of two sides and no staging.
- There is no **Description** and no **Flows**: no `/description` or `/flows`, no change graph.
- Evidence is source code, not patches: leaves are files or parts of large files, and nothing needs a tool loop, so the summarizer runs without tools.
- Topics have only the hierarchy; there is no Document mode.
- Summaries and topics are kept across reloads and changes of start directory by content keys, rather than discarded with a new comparison. Only **Regenerate** clears them.
- Word scoring covers only the summaries on screen.
- Runs over 500 estimated requests ask for confirmation, and snapshot updates are held during a run.
- The chat tools are `list_code_files`, `read_code`, `search_code`, `find_code_symbols`, `show_code_source`, `add_code_notes` and `get_code_summaries`.

## Shared components

Code Review reuses the Diff Review workspace, summaries, topics and tracing through small abstractions in `src/main/kotlin/dev/insight/diff`:

- [ReviewSource.kt](../src/main/kotlin/dev/insight/diff/ReviewSource.kt) — the file abstraction summaries and topics work on: path, fingerprint and facts. A changed file and a code file both implement it.
- [ReviewVocabulary.kt](../src/main/kotlin/dev/insight/diff/ReviewVocabulary.kt) — workspace wording, buttons, Document mode and visible-only scoring for a diff or a code base
- [ReviewTabSupport.kt](../src/main/kotlin/dev/insight/diff/ReviewTabSupport.kt) — opening a review's workspace editor and the word-tracing launcher, shared by both tabs
- [ChangeSummaryModel.kt](../src/main/kotlin/dev/insight/diff/ChangeSummaryModel.kt) — the part nodes (`chunk`) under a file, recursive grouping and content keys
- [ChangeSummarizer.kt](../src/main/kotlin/dev/insight/diff/ChangeSummarizer.kt) — the batched bottom-up pipeline and its `SummaryEvidence` strategy
- [ChangeTopics.kt](../src/main/kotlin/dev/insight/diff/ChangeTopics.kt) — topic evidence and concurrency settings of the per-file pipeline
- [ChangeTopicSummaries.kt](../src/main/kotlin/dev/insight/diff/ChangeTopicSummaries.kt) — the topic summary tree with parts and sharing with the file summaries
- [NoteCanvas.kt](../src/main/kotlin/dev/insight/diff/NoteCanvas.kt) — the workspace, which takes a vocabulary and the open and show actions for files and parts

The code-specific pieces:

- [CodeSummaryEvidence.kt](../src/main/kotlin/dev/insight/codereview/CodeSummaryEvidence.kt) — code summary prompts, leaf evidence and batching limits
- [CodeTopics.kt](../src/main/kotlin/dev/insight/codereview/CodeTopics.kt) — topic evidence from summaries, parts and the excerpt, and the topic prompt
- [CodeReviewTab.kt](../src/main/kotlin/dev/insight/codereview/CodeReviewTab.kt) — chat routing of `/summaries`, `/topics` and `/topic-summaries`, request estimates and held updates
- [plugin.xml](../src/main/resources/META-INF/plugin.xml) — the **Code Review** tool window, **Open Code Review** in the Tools menu and **Review Code in Directory** in the Project view menu
