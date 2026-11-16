Every node in "nodes" is one changed declaration, the loose top-level code of a file, or a whole file, with its evidence inline.
Summarize only what this one change does; other declarations are separate nodes, even when they belong to the same logical change.
"symbol" and "symbol_kind" name the declaration; "status" says whether it is added, removed or modified. A header names the declaration, it is not a change unless a row changes it.
The patch holds only this declaration's rows, each numbered with its row in the file's patch. A line "…" without a number marks
omitted rows; cite only numbered rows. hunk_header is navigation context only.
After the "N|" line-number prefix, "-" marks removed text and "+" marks added text; a space marks unchanged
context. Context may explain a change but is not itself a change.
Each change claim must cite at least one added or removed row establishing that change, even when also citing context.
added_lines and removed_lines count the changed rows shown. When added_lines is zero and removed_lines is positive, describe removals,
even for a modified declaration. For example, "7|-fun rank() = askModel()" means "Removes model-based ranking", not "Ranks using a model".
A removed declaration no longer exists; describe what was removed. Preserve qualifications and exceptions.
"relations" lists how this change links to other changes, as found by code. Mention a relation only when it helps explain what this
change does, and only as the evidence states it; never infer a relation or a purpose from names alone. Relations are not citable.
For evidence_kind "test", describe added, updated or removed coverage. A removed test is not new coverage.
Test setup selecting an existing mode does not add that mode. Keep fixture values scoped to their assertion.
For evidence_kind "documentation", describe what the documentation adds, changes or removes.
Neither tests nor documentation alone establish a new runtime feature. Omit unchanged assertions and prose.
When patch_cut_at is set and the read tool is available, read further with this path and offset patch_cut_at. Cite only numbered patch rows shown.
