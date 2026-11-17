# Code map flow

The code map reads a code scope as flows rather than folders. Its unit is a class with its members, the top-level code of a file, or a whole file without code analysis; members are listed when a step is drilled into. Code resolves references between units and types them by how widely the used unit is used: a unit with one user belongs to it, a unit with a few users is linked for a model to judge, and a unit used by many is shared. Inheritance groups a subclass with its base unless the base is shared, each test tests the unit its name names or else the one it uses most, and documentation joins the flow whose files and classes it mentions most.

A flow is ordered from its roots, the code nothing else in the flow uses, through the code they use to tests and documentation. A root used by nothing in the scope marks an entry flow, listed first. Shared units are foundations: their users never join them, and each flow lists the shared code it uses in a Shared section linked to the foundation, which lists the flows that share it. Roots come from the graph alone; entry points from registrations or framework conventions are left for a later version.

Models only judge ambiguous merges and attachments, title the flows and write summaries; they never add, drop or move units. The view lists flows beside the selected one, with the same disclosure as the change map: sections by role (roots, uses, tests, docs), fan-outs of at least three units that follow one unit by one relation, and collapsed steps showing the first sentence of their summary. An expanded step lists the unit's members with the units that use them, each opening at its line. A run continues missing summaries, asks before a large summary run, and a run over a complete map builds it again. Maps and their summaries live in memory for the tab; at most 3,000 files are analyzed.

Entry points for further exploration:

- [CodeMapModel.kt](../src/main/kotlin/dev/insight/codereview/map/CodeMapModel.kt) — units, relations, flows and the map
- [CodeMapClustering.kt](../src/main/kotlin/dev/insight/codereview/map/CodeMapClustering.kt) — typed edges, shared units and flow order
- [CodeMapCollector.kt](../src/main/kotlin/dev/insight/codereview/map/CodeMapCollector.kt) — units and references from PSI
- [CodeMapWorkflow.kt](../src/main/kotlin/dev/insight/codereview/map/CodeMapWorkflow.kt) — judgments and titles
- [CodeMapTurn.kt](../src/main/kotlin/dev/insight/codereview/map/CodeMapTurn.kt) — the `/code-map` command of a code review tab
- [CodeMapPresentation.kt](../src/main/kotlin/dev/insight/codereview/map/CodeMapPresentation.kt) — what the view shows
