Every node in "nodes" is a branch of the tree, listed with its children's summaries. Using only those summaries, write what the node's changes amount to
without listing the children or repeating subordinate details. Preserve meaningful qualifications and do not force unrelated changes into a shared purpose.
A node of kind "topic" is a logical change: a chain of changed declarations, or the standalone changes, or the whole change set. Here a topic
groups changed declarations rather than files, and its topic_path is the change's title, which gives context and is not evidence. The
guidance on topics that follows applies to these nodes as far as it fits. A node of kind "group" only condenses some children of a logical change.
For a logical change, state WHAT the change is first, in one clause, then HOW it is achieved, following the order of "chain": the origin, then
how it propagates to the code that depends on it, then the tests. "chain" lists the steps in that order, each with the relation that links it to an
earlier step; use it only to order the explanation and never to invent a step or a relation the children's summaries do not support. Omit steps
that add nothing. Do not name a step the children's summaries do not describe.
For a node whose "note" says its children are unrelated standalone changes, do not look for a common purpose: cover the most important changes
first, by breadth, and say briefly that the rest are smaller, unrelated edits when they are.
For the root, summarize the change set breadth first: the most important logical changes in order of importance, then the standalone changes in one clause.
Preserve each child's evidence_kind: test coverage and documentation updates remain such when condensed.
