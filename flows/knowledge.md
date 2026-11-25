# Knowledge flow

Codebase knowledge is kept as atomic claims, each stating one thing about the code and anchored to what it is about: a symbol, a file, a directory, a call edge or an entry point. Docs are renderings of claims rather than the source of truth. Claims record who wrote them and change status over their life (live, suspect, retired, superseded) instead of disappearing.

All changes go through one append-only ledger, kept in the repository so it can be reviewed like any other change. A ratchet guard judges every proposed operation. Knowledge leaves the live set only through explicit operations backed by evidence, and rewrites, merges and splits must keep the original meaning, which is checked with the Decisions API. Automated work may only mark human-written claims suspect or refresh their anchors; only the user can retire or restore them. Rejected operations are recorded too, so the ledger shows what was refused and why.

Anchors are resolved and fingerprinted through IDE APIs, with comments and formatting ignored, so only the areas whose code actually changed are re-verified. The drift check turns the statements of hand-written docs, such as the flow docs, into claims and checks active claims against the code. It flags broken references, stale statements and statements too detailed for a doc, and may suggest patches, but it never edits files.

Distillation builds claims for a scope. Bounded parallel workers, one per area, read evidence and only propose. A single organizer submits every proposal, removes duplicates, flags conflicts, lifts ideas shared by child areas to their parent, and ends with a ratchet audit of the run. Rendering composes a short flow-style doc from a target's stable claims; claims that are too detailed or volatile stay out, and any selected claim the text does not cite is listed in a "Not placed" section rather than dropped.

In Code Review, a Knowledge view lets readers check docs, distill a scope, browse claims and the ledger, and preview rendered drafts.

Entry points for further exploration:

- [KnowledgeModel.kt](../src/main/kotlin/dev/insight/knowledge/KnowledgeModel.kt) — claims, anchors, origins and statuses
- [RatchetGuard.kt](../src/main/kotlin/dev/insight/knowledge/RatchetGuard.kt) — what may leave the live set and how meaning is checked
- [DriftCheckWorkflow.kt](../src/main/kotlin/dev/insight/knowledge/DriftCheckWorkflow.kt) — doc sync and drift findings
- [DistillationWorkflow.kt](../src/main/kotlin/dev/insight/knowledge/DistillationWorkflow.kt) — per-area workers, the organizer and the ratchet audit
- [KnowledgeDocRenderer.kt](../src/main/kotlin/dev/insight/knowledge/KnowledgeDocRenderer.kt) — composing drafts from claims
