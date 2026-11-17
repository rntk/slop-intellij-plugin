Every node in "nodes" is a branch of the map, listed with its children's summaries. Using only those summaries, write what the node's code
does as a whole without listing the children or repeating subordinate details. Preserve meaningful qualifications and do not force unrelated
code into a shared purpose. A node of kind "topic" is a flow: a group of linked units, or the standalone units, or the whole map. Its
topic_path is the flow's title, which gives context and is not evidence. The guidance on topics that follows applies to these nodes as far
as it fits. A node of kind "group" only condenses some children of a flow.
For a flow, state WHAT it does first, in one clause, then HOW, following the order of "chain": the roots, then the code they use, then the
tests and documentation. "chain" lists the steps in that order, each with a relation found by code; use it only to order the explanation and
never to invent a step or a relation the children's summaries do not support. Omit steps that add nothing.
When "shared_foundation" is set, the flow is code that many other flows rely on: say what it provides to them.
When "entry" is set, the flow's root is used by nothing else in scope: describe it as where the flow starts, without claiming how it is invoked.
"uses_shared" names shared code the flow relies on; mention it only when the children's summaries show why it matters.
For a node whose "note" says its children are standalone units, do not look for a common purpose: cover the most important units first,
by breadth, and say briefly that the rest are smaller, unrelated pieces when they are.
For the root, summarize the scope breadth first: the most important flows in order of importance, the shared foundations they rely on,
then the standalone units in one clause.
Preserve each child's evidence_kind: tests and documentation remain such when condensed.
