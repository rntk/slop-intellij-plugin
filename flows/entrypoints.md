# Entrypoints flow

The entrypoints feature lives in `src/main/kotlin/dev/insight/entrypoints`. It finds where a project's execution starts, so a newcomer can begin reading from the places that actually run rather than from an arbitrary file.

At a high level: a search is requested from the UI, a project-level service prepares a view of the project's files together with any available call-graph hints (`EntryPointHints`), and one of two independent finders judges the likely starting points. The results are presented as a navigable list that opens each entry point in the editor. Earlier results may be reused where the underlying files are unchanged.

The two finders run side by side in a split panel, each with its own search, cancel and cache clearing:

- **LLM API** (`EntryPointFinder`): a model explores the project with `read_file`, `list_files` and `search_text` under read budgets.
- **Decisions API** (`DecisionsEntryPointFinder`): rules extract candidates, which the Decisions classifier scores; no generative model is involved.

Entry points for further exploration:

- [EntryPointService.kt](../src/main/kotlin/dev/insight/entrypoints/EntryPointService.kt) — search orchestration
- [EntryPointsPanel.kt](../src/main/kotlin/dev/insight/entrypoints/EntryPointsPanel.kt) — UI entry point
- [EntryPointFinder.kt](../src/main/kotlin/dev/insight/entrypoints/EntryPointFinder.kt) — LLM API finder
- [DecisionsEntryPointFinder.kt](../src/main/kotlin/dev/insight/entrypoints/DecisionsEntryPointFinder.kt) — Decisions API finder
- [EntryPointHints.kt](../src/main/kotlin/dev/insight/entrypoints/EntryPointHints.kt) — call-graph candidates
