package dev.insight.review

/**
 * One declaration of a parsed file. [kind] is the most specific [RadarSymbolKind] (an interface rather than a class);
 * [symbols] counts every lens inside it, itself included, so a class's functions include its methods.
 */
data class OutlineDeclaration(
    val kind: RadarSymbolKind,
    val name: String,
    /** 1-based lines, inclusive. */
    val lines: IntRange,
    /** UTF-8 bytes of the declaration's text. */
    val bytes: Int,
    val symbols: RadarSymbols,
    val children: List<OutlineDeclaration> = emptyList(),
)

/** The declarations of a parsed file: types, callables, and the properties and variables outside function bodies. */
data class SourceOutline(val declarations: List<OutlineDeclaration>) {
    companion object {
        val EMPTY = SourceOutline(emptyList())
    }
}

/** A file's counts and outline from one PSI walk. */
data class SourceStructure(val symbols: RadarSymbols, val outline: SourceOutline)

/**
 * Outlines parsed on demand, since an outline per file in scope would hold every declaration in memory. Implementations
 * parse the captured text, never the live file, so outlines match the counts.
 */
interface SourceOutlines {
    /** The outline already parsed for [file]; [SourceOutline.EMPTY] when parsing found nothing or failed. */
    fun cached(file: ReviewSource): SourceOutline?

    /** Parses [file] in the background, then calls [done] on the UI thread; at once when already cached. */
    fun load(file: ReviewSource, done: () -> Unit)
}
