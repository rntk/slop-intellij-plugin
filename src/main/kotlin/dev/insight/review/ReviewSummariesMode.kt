package dev.insight.review

/**
 * The outline reads the tree top-down as indented summaries; the hierarchy reads the folders left to right; the document reads
 * every file in tree order with its summary and the folder rail, as topics do. Explorer shows one page of siblings at a time.
 * Split keeps a foldable file tree on the left and each entry's optionally wrapped summary beside its row.
 * Diff shows the selected file in the IDE’s native diff viewer beside the file tree.
 * Radar compares how many lines the folders or files at the picked level hold.
 */
internal enum class ReviewSummariesMode { HIERARCHY, OUTLINE, EXPLORER, SPLIT, TREE, DIFF, RADAR, DOCUMENT }

