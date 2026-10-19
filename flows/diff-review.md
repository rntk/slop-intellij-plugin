# Diff review flow

The Diff Review feature lives in `src/main/kotlin/dev/insight/diff`. It is a standalone, read-only workspace for reviewing code changes with AI assistance, independent of the application graph. **Tools → Open Diff Review** opens the **Diff Review** tool window and the selected review's canvas in a regular IDE editor tab.

The tool window is the control panel: comparison controls and file trees sit above the review tabs, commands, zoom controls and chat. Each review has its own full-editor canvas, with no surrounding controls or chat taking up canvas space. Selecting a review opens or focuses its canvas; selecting a canvas editor selects the matching review controls. Other editor tabs continue to work normally, and **Open canvas** returns to the selected review's canvas.

Closing a canvas editor preserves the review's chat, draft, cards, positions, sizes and zoom; reopen it from the control panel. Closing a review in the control panel discards that session and closes its canvas editor. Canvas editors can be moved between editor groups or windows; duplicating the same canvas into a split is disabled because its Swing view has one owner.

At a high level: the workspace loads an immutable snapshot of a selected comparison (such as local uncommitted changes or a Git revision comparison) and presents the changed files alongside one or more review tabs, each optionally scoped to a subset of files. Within a tab, the user chats with the assistant, which inspects the snapshot via tools and places visual review artifacts onto an adjacent canvas—including file or symbol explanatory notes, static relationship diagrams, source code excerpts, and embedded file diffs. All canvas items are tied to the underlying snapshot and are automatically invalidated if the compared changes move on.

Entry points for further exploration:

- [DiffReviewEditor.kt](../src/main/kotlin/dev/insight/diff/DiffReviewEditor.kt) — native IDE canvas editor tab and view lifecycle
- [DiffReviewPanel.kt](../src/main/kotlin/dev/insight/diff/DiffReviewPanel.kt) — tool window control panel, review selection, diff source selection, and snapshot loading
- [DiffReviewTab.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTab.kt) — per-tab review session, chat integration, and canvas coordination
- [DiffReviewTools.kt](../src/main/kotlin/dev/insight/diff/DiffReviewTools.kt) — assistant tools for inspecting diffs and updating the canvas

Canvas blocks can be moved by their headers and resized by dragging their bottom-right grip. Resizing changes the block's width and height while keeping the current font size and canvas zoom: wider blocks reflow text, taller blocks show more content, and smaller blocks scroll. Each block keeps its size and position through chat updates, card interactions and view changes until it is removed from the canvas.

Untracked working-tree files appear in a separate **Untracked · include in review** checkbox tree. Expand folders to browse files, or check a folder to include all files beneath it. Mixed folders show a partial check, and refreshes retain collapsed folders. Inclusion affects this review only; it does not stage files in Git.

## Change flows (`/flows`)

`/flows [focus]` (or the **Flows** button) maps the change for a reader who does not know the code: it opens on a few named changes and drills down to the exact lines. Code builds the structure. The assistant is only an annotator: it classifies and labels existing ids and cannot add, remove or regroup anything.

**Graph from source.** Before the model is asked anything, `ChangeFlowBuilder` builds a `ChangeGraph` from the snapshot in a smart read action and draws it as a **Change flows** card. The structural view is complete and usable even when the model fails or is never configured.

- Both sides of every changed file are parsed from snapshot text with the IDE's language support. Functions are matched by qualified name and header first, so overloads keep their partners, then by name, which reads as a changed signature. Each function is then **added**, **modified** or **deleted**. A modified function is also flagged when its signature changed, or when it differs in *whitespace only* or *comments only*. Nested functions and classes count for themselves, not their owner. Changed lines per function come from the same line comparison as the patch. Changed classes and properties, and lines outside functions, are listed per file.
- Calls are resolved only where the after side is the project's current source, the same rule as symbol notes. There, each changed function gets an outline: in-project calls and certain exits in source order, with enclosing `if`/`when`/loop/`catch` conditions from the call-site analyzer. Lines the diff changed are marked Δ. A reference search lists up to six unchanged callers, tests included. For historical revisions, and for deleted functions, calls are reported as unresolved rather than approximated.
- **Clusters** are changed functions connected by resolved calls, including through one unchanged *bridge* function (changed → unchanged → changed), which is kept as context. Unconnected changes are grouped per file. Code-only clusters come first, largest first, then test-only and whitespace/comment-only clusters. Structural titles say what a cluster is made of ("Flow from Shop.checkout", "Changes in Shop.java"), never what it is for.

**Interpretation.** The `/flows` turn carries the first page of the graph and the inlined patches. The assistant reads nodes with `read_change_nodes` (literal diff lines, outline, callers) and calls `annotate_change_flows`. It provides:

- per changed function: a one-sentence summary, an `intent` (`new-behavior`, `behavior-change`, `fix`, `refactor`, `cleanup`, `wiring`, `test`) and an `importance` (`key`, `supporting`, `minor`), plus an optional *attention* note on what a reviewer should verify;
- optional cluster titles;
- 1–12 **themes**, each grouping whole clusters, and optionally related files outside clusters such as docs or configuration, into a change a reader would name. Themes come most important first.

The whole batch is validated before anything changes. Validation rejects unknown or unchanged ids, out-of-vocabulary values, a cluster or file in two themes, files outside the diff, and over-long text. A theme batch replaces all themes. Code checks coverage: the 60 largest non-trivial changed functions need an annotation and every cluster needs a theme. Missing work is sent back to the model up to twice, and anything still missing is listed under the reply and keeps its structural title. `get_change_flows` pages the graph. All three tools stay available after the turn, so later questions can refine the labels.

**Reading the card.** There are four levels:

1. **Overview:** counts of new, modified, deleted and trivial functions, unresolved files and interpretation coverage, then the themes. Each theme shows its function, file and line counts, its summary, and the counts of its intents, attention flags and changed tests. The first theme starts open.
2. **Theme:** the call tree of each cluster from its entry functions. Repeated callees and cycles are drawn once and then referenced (↺). Tests appear in their own lane. Whitespace/comment-only edits and functions classified `minor` fold into "+N minor or connecting functions", together with bridges that lead only to them.
3. **Function:** the header, a link to the source (or to the native diff for deleted and historical code), the signature flag and attention note, callers in the diff and unchanged callers outside it, and the outline. Long runs of unchanged calls fold. Clicking a call into another graph function opens that function, its theme and its cluster.
4. **Code:** **Show changed lines** displays the function's literal before/after lines from the snapshot.

Provenance is visual: regular text, badges, arrows and counts come from source analysis, and *italic* text is the assistant's interpretation. Arrows are statically resolved calls, not runtime order. The graph belongs to one snapshot revision. Any snapshot change, **Clear canvas** or the card's **×** discards it. Expansion state survives re-rendering and resets for a new graph.

Limits: up to 400 changed functions per diff (the rest are reported as not analyzed), 80 outline steps per function, caller searches for the 150 largest changes, and bridge checks for 200 unchanged callees. Library calls and statements that are not calls are visible only in the changed lines. Renames read as a deletion plus an addition.

Outline truncation is shown in the overview and function details and exposed to the assistant, including when a tool page shows only the first 60 steps. Missing connections beyond the analysis limit must not be read as proof that no call exists. Trivial-change classification compares PSI leaves, preserving string contents and token boundaries. Only Java ignores inter-token whitespace; other languages retain it conservatively because newlines and indentation can affect behavior.

- [ChangeFlowBuilder.kt](../src/main/kotlin/dev/insight/diff/ChangeFlowBuilder.kt) — PSI analysis: side comparison, outlines, callers and bridges
- [ChangeFlowModel.kt](../src/main/kotlin/dev/insight/diff/ChangeFlowModel.kt) — PSI-free graph, matching, classification, declaration diffs, clustering, annotation validation and coverage
- [ChangeFlowTools.kt](../src/main/kotlin/dev/insight/diff/ChangeFlowTools.kt) — model tools, size-bounded graph pages and the `/flows` turn prompt
- [ChangeFlowsView.kt](../src/main/kotlin/dev/insight/diff/ChangeFlowsView.kt) — progressive-disclosure card and its expansion state

## Change summaries (`/summaries`)

The changed-files panel has two tabs: **Files** and **Summaries**. **Summaries** shows the selected review tab's changes as a tree of summaries instead of names. The root says what the whole change does, each folder what its part contributes, and each file what changed in it, so reading from the top down tells how the task was solved. As with `/flows`, code builds the structure and the assistant writes only the text.

**Tree from paths.** `buildSummaryTree` builds the tree from the tab's changed paths. A folder with a single entry merges with it (`main/kotlin`, or `docs/README.md`), and the root absorbs a single top folder, so no level just repeats the one below it. Subfolders come before files. A folder with more than 8 entries is split into contiguous groups, and groups are grouped again until no node has more than 8 children. A large folder is then summarized as a summary of group summaries.

**Bottom-up writing.** `/summaries [focus]` (typed in the chat, or started by the tab) runs a deterministic pipeline. Code batches changed files with their patches, then summarizes folders and groups from their children’s summaries, up to the root. Long patches get a separate request with `read_diff_files` available for additional context. Each request covers only its assigned nodes and returns JSON summaries. The assistant is asked for brief, concise plain text, with no character limit or fixed sentence count. Unknown ids, duplicate answers and empty summaries are ignored; valid results are saved incrementally. Parents wait for their children, and a parent with one child reuses that summary. `get_change_summaries` pages the resulting tree. Incomplete runs preserve saved summaries for a later continuation.

**Lifetime.** Summaries belong to the review tab and are not canvas cards, so **Clear canvas** keeps them. When the snapshot changes, a summary is kept only if its node and everything beneath it are unchanged. Editing a file therefore invalidates that file and the folders above it. A new comparison request discards every summary. `/summaries` on an incomplete tree fills in only the missing nodes. On a complete tree it starts over.

**Reading the tab.** Opening **Summaries** starts `/summaries` once per snapshot revision, if the tree is incomplete and the chat is idle. Switching review tabs while it is open does the same for the new tab. After a stopped or failed run, use the **Summarize**/**Continue**/**Regenerate** button. Nodes without a summary show their name in italics. Selecting a node shows its real name, kind, repository path, absolute path and full summary below the tree. A selection works like one in **Files**: it highlights the canvas, feeds **New tab from selection** with every file beneath it, and double-clicking a file opens its diff. Small trees (30 nodes or fewer) open fully, and larger ones open at the top level. Expansion survives updates.

**Interactive reading.** A parent summary condenses its children's, so a high-level summary can pack many references. Tick **Interactive** in the summaries card header and rest the pointer on a word of any summary for about half a second. The tool then traces that word to the summaries it came from. It uses the Decisions API and no generative model:

1. **Narrow the context.** Each other meaningful word in the summary gets a Noul: is it part of the pointed word's own context? First preserve the complete phrase, including its modifiers and complements; then include its action and the feature or scope that identifies it. Other phrases about the same thing can contribute context, but adjacent list items and broad topical associations do not. For example, pointing at *validation* in "Adds account recovery, including token validation and email templates" keeps *token validation* and the account recovery context, excluding *email templates*. In "deleted views: canvas, hierarchy, summaries", the context of *hierarchy* is *deleted views*. Filler words are neither asked about nor traceable.
2. **Build the query.** The summary is cut down to the pointed word and its context. Text between kept words separated only by filler words is preserved, and each skipped run becomes the separator after the last kept word: "deleted views: hierarchy".
3. **Search on click.** Scoring stops here and highlights the pointed word and its context immediately. Only clicking one of these highlighted words starts the search, using the already built query. Each summary up to two levels below the pointed one (children and grandchildren) gets a Noul: does it describe the specific thing named by the complete phrase and its context? Matching only the hovered word is insufficient. The pointed summary is included as context. A single child that repeats its parent's summary does not count as a level. Deeper levels are not searched. To go further, trace a word in a match.

The pointed word is marked strongly and its context lightly. Before a search, the status shows the query and invites a click on a highlight. Clicking an unhighlighted word only scores its context. Moving between highlighted words preserves the current query. During a search the highlights stay visible; when it finishes, the status shows how many searched summaries are related (probability ≥ 0.5). Related rows are tinted and labeled with their probability, the levels leading to them open, and the best match is selected and scrolled into view. Moving to another unhighlighted word cancels the work in flight after the hover delay. A trace is dropped when its summary changes, and unticking **Interactive** clears it. Questions on the same state share one request, up to 32 questions each, and requests run in parallel. Judgments are cached by their inputs for the life of the review tab, so retracing a word is free. Both thresholds are untuned starting points.

- [ChangeSummaryModel.kt](../src/main/kotlin/dev/insight/diff/ChangeSummaryModel.kt) — tree from paths, merging and grouping, bottom-up validation and retention across snapshots
- [ChangeSummaryTools.kt](../src/main/kotlin/dev/insight/diff/ChangeSummaryTools.kt) — size-bounded pages of the summary tree
- [ChangeSummarizer.kt](../src/main/kotlin/dev/insight/diff/ChangeSummarizer.kt) — batched bottom-up pipeline and summary prompts
- [ChangeSummariesView.kt](../src/main/kotlin/dev/insight/diff/ChangeSummariesView.kt) — the Summaries tab: wrapped summary tree and details of the selection
- [ChangeSummariesCard.kt](../src/main/kotlin/dev/insight/diff/ChangeSummariesCard.kt) — the summaries canvas card, its view state and interactive word tracing
- [ChangeSummaryTrace.kt](../src/main/kotlin/dev/insight/diff/ChangeSummaryTrace.kt) — Decisions API tracing: word context, narrowed query and ranking of the two levels below

## Change topics (`/topics`)

`/topics [focus]` (or **Topics**) classifies each changed file with one hierarchical code topic and lists the unique paths on a movable, resizable canvas card. Each topic lists its files; clicking a file opens its diff. Paths describe code responsibilities and domain capabilities, for example **Storage > User > Wallet** or **Security > User > Auth**, rather than news categories or repository folders. Labels are model interpretation.

The pipeline adapts the example's fixed-section topic labeling: file boundaries are the sections, and code visits every missing file in sorted path order. Each request carries one file's patch, status, previous path, optional focus and up to 100 already assigned topic paths to encourage vocabulary reuse. Long patches are bounded and can be read further with `read_diff_files`. Responses must contain a valid path with 2–5 nonempty levels; invalid answers get up to two attempts per file. Progress and valid results are saved incrementally. Failed files remain explicitly awaiting topics; later files still run. Identical paths (ignoring case and spacing) share one list entry.

Topics belong to the review tab and snapshot. Unchanged files retain their labels across refreshes; changed or removed files lose them, and a new comparison discards them all. Captured revision checks reject stale writes. Closing the card or clearing the canvas hides it while preserving results. `/topics` resumes an incomplete result and regenerates a complete one. Reopening a completed card with **Topics** does not rerun generation.

- [ChangeTopics.kt](../src/main/kotlin/dev/insight/diff/ChangeTopics.kt) — topic model, code-domain prompt, deterministic per-file pipeline and validation
