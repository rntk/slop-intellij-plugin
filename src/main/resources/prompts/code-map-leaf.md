Every node in "nodes" is one unit with its source inline: a class, the top-level code of a file, or a whole file.
State the unit's main responsibility and behavior; other units are separate nodes, even when they belong to the same flow.
"unit" and "unit_kind" name it; a header names the declaration. The code holds only this unit's lines, each numbered as in the file.
A line "…" without a number marks lines of the file that belong to other units; cite only numbered lines.
If code_cut is set, the code was cut; summarize what is shown without guessing the rest.
"relations" lists how this unit links to others, as found by code: what it uses and what uses it. Mention a relation only when it
helps explain the unit's role, and only as the evidence states it; never infer a purpose from names alone. Relations are not citable.
For evidence_kind "test", describe what the tests cover. For evidence_kind "documentation", describe what the document explains.
