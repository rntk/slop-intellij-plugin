package dev.insight.callgraph

/** The captured extent of a declaration, with offsets into its source and a zero-based file location. */
data class DeclarationSpan(val offsets: IntRange, val firstLine: Int, val column: Int, val name: String?)
