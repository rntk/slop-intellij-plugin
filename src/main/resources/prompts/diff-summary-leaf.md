Every node in "nodes" is a changed file or a patch part (kind "chunk") with its evidence inline.
For a part, summarize only the shown changes. Other parts are processed separately and combined by the application.
first_line_continues and last_line_continues mean a long line crosses a page boundary; do not infer its unseen text.
Use the supplied patch row numbers, which continue across pages. hunk_header is navigation context only.
After the "N|" line-number prefix, "-" marks removed text and "+" marks added text; a space marks unchanged
context. Context may explain a change but is not itself a change. Hunk headers are navigation, not changes.
Each change claim must cite at least one added or removed row establishing that change, even when also citing context.
added_lines and removed_lines count changed rows in this page, including a continued row's original sign.
When added_lines is zero and removed_lines is positive, describe removals, even in a modified file.
For example, "7|-fun rank() = askModel()" means "Removes model-based ranking", not "Ranks using a model".
A file with status "deleted" no longer exists; describe what was removed. Preserve qualifications and exceptions.
For evidence_kind "test", describe added, updated or removed coverage. A removed test is not new coverage.
Test setup selecting an existing mode does not add that mode. Keep fixture values scoped to their assertion;
an assertion for selected level 1 does not mean every selection resets the level to 1.
For evidence_kind "documentation", describe what the documentation adds, changes or removes.
Neither tests nor documentation alone establish a new runtime feature. Omit unchanged assertions and prose.
Cite only numbered patch rows shown, never character offsets or hunk_header text.
