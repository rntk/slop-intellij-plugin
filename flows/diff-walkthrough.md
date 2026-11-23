# Diff walkthrough flow

The Walkthrough is a Diff Review view that tells a change as a sequence of steps. Each step is a chain of diff hunks: a root change, what caused it, and what it leads to, such as a renamed function, its call sites, and the tests that cover it. Links between hunks carry a relation (causes, same edit, tests, documents, related) and a short reason.

Every change is shown. Before a run, the page lists every hunk as pending so the diff can still be browsed. A run never hides a hunk: mechanical siblings, such as the many sites of one rename, may be covered by a single step without being analyzed one by one, and anything the assistant did not reach or link ends up explicitly in a final "Not linked by the assistant" step. The coverage bar counts hunks by status, so it is always clear how much was linked, covered, standalone, or left unlinked.

A run first triages hunks cheaply with the Decisions API (falling back to heuristics) to start from likely roots, then works through seed hunks in independent, bounded turns that record steps and links with dedicated tools. A turn can delegate focused questions to a subagent with read-only tools. Afterwards, decisions verify each link and store a confidence; weak links are kept but drawn as such. Limits and failures are reported as warnings, and a stopped run keeps what it reached.

The page streams the run's updates in place. Its canvas follows the [canvas session](canvas-session.md) conventions: unbounded pan and zoom, diff cards with their link groups as chips that open linked cards beside them, a step chip on each card that leads back to the step showing it (its root and note open on the left, joined by an arrow), arrows labelled with the relation, a trail from the step's root, and gliding moves.

Entry points for further exploration:

- [WalkModel.kt](../src/main/kotlin/dev/insight/diff/walk/WalkModel.kt) — hunks, links, steps, statuses, and the coverage guarantee
- [WalkthroughWorkflow.kt](../src/main/kotlin/dev/insight/diff/walk/WalkthroughWorkflow.kt) — triage, seed turns, limits, and link verification
- [WalkTools.kt](../src/main/kotlin/dev/insight/diff/walk/WalkTools.kt) — the tools the model reads and records with
- [WalkBoard.kt](../src/main/kotlin/dev/insight/diff/walk/ui/WalkBoard.kt) — where opened cards go on the canvas
- [WalkthroughPage.kt](../src/main/kotlin/dev/insight/diff/walk/ui/WalkthroughPage.kt) — the page and its wiring into the review
