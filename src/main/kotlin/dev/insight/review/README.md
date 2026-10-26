# Shared review core

This package owns evidence-independent review state: sources, notes, symbol lookup,
summary trees and generation, topics, persistence, explanation targets, chat focus,
and the editor wrapper that hosts a review canvas.

Both `dev.insight.diff` and `dev.insight.codereview` depend on this package.
Keep this package independent of those callers. Summary and topic generators take
evidence providers explicitly; they must not cast a source to either caller's model.

Diff-specific evidence adapters and convenience factories (`diffSummarizer`,
`diffTopicGenerator`, and `diffSymbols`) live in `dev.insight.diff`. VCS snapshots,
patches, before/after source resolution, comparison selection, change flows, and
metrics also remain there. Code scope, chunking, and dependency analysis live in
`dev.insight.codereview`.

The combined canvas renderer, summary views, and tracing launchers still live in
`dev.insight.diff`. They mix shared presentation with diff cards and metrics, so
their eventual extraction requires a separate presentation boundary. The neutral
editor owner exposes only a Swing component and does not depend on that renderer.

The existing editor type ID and cache formats/keys are preserved when types move.
