package dev.insight.review

/**
 * Split is the default view of the Summaries tab: it keeps a foldable file tree on the left and each entry's optionally
 * wrapped summary beside its row. The outline reads the tree top-down as indented summaries; the hierarchy reads the
 * folders left to right; the document reads every file in tree order with its summary and the folder rail, as topics do.
 * Explorer shows one page of siblings at a time. Diff shows the selected file in the IDE’s native diff viewer beside the
 * file tree. The radar that compares folders and files lives on the Charts page instead.
 */
internal enum class ReviewSummariesMode { SPLIT, HIERARCHY, OUTLINE, EXPLORER, TREE, DIFF, DOCUMENT }

