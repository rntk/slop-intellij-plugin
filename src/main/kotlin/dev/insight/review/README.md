# Shared review core

This package owns evidence-independent review state: sources, notes, symbol lookup,
summary trees and generation, topics, persistence, explanation targets, chat focus,
and the editor wrapper that hosts a review canvas.

Captured review contexts share revision checks and canvas mutations here. Tabs supply
change callbacks to preserve their redraw policy, and keep evidence reads and PSI note
resolution in their adapters. The command registry shares recognition and progress titles;
evidence generation, request confirmation and snapshot holding remain with each tab.

Both `dev.insight.diff` and `dev.insight.codereview` depend on this package.
Keep this package independent of those callers. Summary and topic generators take
evidence providers explicitly; they must not cast a source to either caller's model.

`dev.insight.review.ui` holds the shared Swing presentation: the review tab base and
tabs controller, side pages, the canvas with its summaries, topics, File view, note,
source and symbol-flow cards, tracing, and PSI/progress helpers. It depends on this
package, never the reverse, and on neither caller nor `dev.insight.chat`.

Boundaries the callers implement:

- `PatchSource`: a source whose evidence is a patch. Shared views paint, trace and open
  it; diff's `ReviewFile` implements it.
- `ui.CanvasExtension`: a review's own canvas pages and the summaries Diff mode. Diff
  supplies word, overview and metric cards, diff snapshot viewers and native diffs;
  code review has none.
- `agent.ChatConversation`: what a review tab follows of its chat. `ChatSession`
  implements it; each tab supplies its chat panel, input and source points.

Diff-specific evidence adapters and convenience factories (`diffSummarizer`,
`diffTopicGenerator`, and `diffSymbols`) live in `dev.insight.diff`. VCS snapshots,
patches, before/after source resolution, comparison selection, native diff viewers,
word charts and metrics also remain there. Code scope, chunking, and
dependency analysis live in `dev.insight.codereview`.

The existing editor type ID and cache formats/keys are preserved when types move.
