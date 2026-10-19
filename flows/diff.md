# Diff flow

## Diff Review panel

The independent **Diff Review** tool window is implemented in `src/main/kotlin/dev/insight/diff`.
It works without constructing the application graph. Open it from **Tools → Open Diff Review**.

- `DiffReviewLoader.kt` loads the IDE's local changes and untracked file list on IO. Tracked changes are included automatically; untracked files enter the review only through the user's checkboxes, without staging. Cached documents supply unsaved content. The immutable snapshot holds before/after text and a unified patch with three context lines, including deletions and rename paths. Binary, unavailable or million-character-plus sides are listed with an explicit content-unavailable message.
- `DiffReviewModel.kt` owns snapshot revisions and per-file/group notes. A note validates every path before changing state and upserts by its sorted path set. A refresh drops a note if any target was removed or its evidence fingerprint changed; unchanged notes remain.
- `DiffReviewPanel.kt` shows a changed-file hierarchy, a separate untracked block, movable note cards and bottom chat. Double-click/Enter on a file or clicking a card's file path opens the native IDE diff viewer. VCS updates, included-document edits and included-file VFS changes debounce a refresh off the EDT. Tools are blocked while refreshing; explicit refresh and included-file edits cancel replies immediately, while routine VCS updates cancel only when included evidence changes. Disposal cancels panel work and subscriptions. Notes, file inclusion and card positions are session-local.
- `DiffReviewTools.kt` gives the LLM `get_current_diff` (50-file pages; defaults such as unselected files are omitted), `read_diff_files` (1–10 files per call within a 10,000-character result, with per-file `next_offset` for long patches) and `add_diff_notes` (1–20 notes per call, validated as a batch). Each question carries the first page of the snapshot, with whole patches inlined up to 24,000 characters, so small diffs need no read calls. JSON is written without HTML escaping so code stays readable. `/description`, `description` and the **Description** button ask it to cover every file with brief notes; tree selection narrows explicit selected-file requests. After a description answer, code checks coverage: files without a note are sent back to the model up to twice, and any still missing are listed under the reply. Each reply captures one snapshot, and tools reject calls once the diff changes; the model never echoes a revision. The shared tool runner provides cancellation, a 100-round cap and a 160,000-character context window that evicts down to 70% at once so the request prefix stays cacheable. LLM settings and the chat UI are shared with Insight Chat; no file editing or VCS write tools are available.

Notes are model interpretations, labelled on the canvas. There is no commit selection, staged-only mode, persistence or automatic test execution in this initial panel.

`/flows` adds a source-built change-flow graph (changed functions, resolved calls, clusters and outlines) to the canvas. The model only annotates it; see [Change flows](diff-review.md#change-flows-flows).

## Application graph changes overlay

The diff feature lives in `src/main/kotlin/dev/insight/callgraph`. It maps a VCS change onto the application graph, keeps the affected neighbourhood (changed symbols plus their callers and callees) and highlights it in the **Application graph** tab. It ports the Python reference flow (`slop-code-graph/diff.py`, `src/diff/diff.py`) but uses IDE tooling instead of running `git diff` and parsing its text.

At a high level: the panel (or the **Show Changes in Application Graph** Tools menu action) asks `DiffService` for a diff of the current graph. The service collects `Change`s, compares each file's before and after content line by line, turns the changed lines into new-side hunks, maps the hunks onto symbol line ranges, then walks call edges from the changed symbols. The panel decorates the displayed graph with the result, or filters the graph down to it.

Entry points for further exploration:

- [DiffModel.kt](../src/main/kotlin/dev/insight/callgraph/DiffModel.kt): PSI-free model (`FileDiff`, `DiffResult`), hunks (`hunksOf`), `mapChangesToNodes`, `keepInnermost`, `pruneToChangedNeighborhood`, `changedOnlyNodes` and the `decorateDiff` overlay
- [DiffService.kt](../src/main/kotlin/dev/insight/callgraph/DiffService.kt): change collection, content loading, line comparison and node line ranges, plus the `DiffBaseProvider` extension point
- [git/GitDiffBaseProvider.kt](../src/main/kotlin/dev/insight/callgraph/git/GitDiffBaseProvider.kt): comparison with a named Git revision, registered only by `META-INF/diff-git.xml` when the Git plugin is present
- [ProjectGraphToolWindow.kt](../src/main/kotlin/dev/insight/callgraph/ProjectGraphToolWindow.kt): the **Changes** row, rendering and `ShowChangesInGraphAction`

## Steps

1. **Changes.** By default these are the local changes against HEAD from `ChangeListManager.allChanges`. This works with any VCS and needs no extra dependency. As with `git diff HEAD`, unversioned files are not included. When a revision is typed into the **vs** field, the optional `DiffBaseProvider` for Git calls `GitChangeUtils.getDiffWithWorkingDir(project, root, ref, null, false, true)` for every repository (unlike `getDiffWithWorkingTree`, it throws for an unknown revision so the panel can report it). Python's `--staged` mode has no counterpart.
2. **Hunks.** For each change, the before-revision text is compared with the after text using `ComparisonManager.compareLines`. When the file is open, the after text is the document, so unsaved edits count. Changed regions become 1-based inclusive new-side ranges. A pure deletion becomes a single anchor line, `max(start, 1)`, matching git's `+N,0` header. Like `git diff` without `-U0`, every range is widened by three context lines, clamped to the file, and touching ranges are merged. Added files are one hunk over the whole file. Deleted files are listed with their removed line count but never map onto a symbol. Renames keep the new path. Binary or unreadable content is listed without hunks. Files too large to compare count as entirely changed.
3. **Mapping.** Symbol line ranges come from each node's PSI text range and its document. Only files in the change are read, and this happens in a smart read action. A symbol is changed when its range intersects a hunk (`start <= hi && end >= lo`). Locals (variables, parameters) are skipped. A container that has a changed member is dropped (`keepInnermost`), so editing a method marks the method and not also its class and file.
4. **Neighbourhood.** This is an exact port of `prune_to_changed_neighborhood`. A multi-source breadth-first search runs over CALL edges: callers up to *hops*, callees up to *hops*, and the two are combined. **All** means unlimited and 0 keeps only the seeds. The walks are directional, so another callee of a caller of the change (a sibling) is not kept. Calls are first lifted from locals to their enclosing declaration (`liftCalls`), as enrichment does. With no changed symbol, every node is kept, and "Changed only" shows the full graph.

## UI

The **Changes** row of the Application graph tab (a collapsible section, collapsed by default and revealed by **Show Changes in Application Graph**) has these controls:

- **Off / Highlight / Changed only**
- **vs** with a revision (empty means HEAD; it is disabled without the Git plugin)
- **Hops** (1, 2, 3, All)
- **Refresh changes**
- a summary of files, +added −removed lines, changed symbols and neighbourhood size

- **Highlight** draws on top of the enrichment lens without replacing it:
  - A shown node holding a changed symbol is drawn strong with a `Δ` badge. A folded group shows `Δn`, and a file shows its `+added −removed` counts. It gets an amber stripe unless the lens already colours it.
  - Callers and callees keep normal emphasis.
  - Everything else is faint.
  - The legend gains a "Δ changed vs …" entry.
- **Changed only** uses the same `GraphHierarchy.view(only = …)` filter as search Filter mode. It shows the neighbourhood plus the classes and files that contain it, and it combines with a search filter by intersection. Enter or **Show in full graph** on a group switches back to Highlight.

The diff is recomputed in four cases: on **Refresh changes**, when the mode, base or hops change, after every graph rebuild (each PSI change triggers one), and one second after the IDE's change list updates when comparing with HEAD. A diff computed for an earlier graph is ignored.

## Limits

- The Flow Chart and Canvas tabs do not show changes yet.
- `--staged`, per-file LLM summaries and the tree map sized by churn from the Python flow are not ported.
- Files edited but not yet picked up by the change list (unsaved, before the change list refreshes) are only seen once the IDE marks them changed.
- In a project with several Git repositories, the revision must exist in all of them.
