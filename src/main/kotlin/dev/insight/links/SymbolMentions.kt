package dev.insight.links

/**
 * Declared names that text may link to. Membership must be cheap, because every code-shaped word in a text is checked.
 * The set only decides what becomes a link; the declarations behind a name are looked up when the link is followed.
 */
fun interface SymbolNames {
    operator fun contains(name: String): Boolean

    companion object {
        val NONE = SymbolNames { false }

        fun of(names: Collection<String>): SymbolNames {
            if (names.isEmpty()) return NONE
            val set = names.toHashSet()
            return SymbolNames { it in set }
        }
    }
}

/**
 * A code symbol named in text, spanning `[start, end)`. [name] is the declared name to look up; [qualifier] holds the owners
 * written before it (`Foo.bar` names `bar` qualified by `Foo`); [call] is set when the name is written as a call, `bar()`.
 */
data class SymbolMention(
    val start: Int,
    val end: Int,
    val name: String,
    val qualifier: List<String> = emptyList(),
    val call: Boolean = false,
)

/**
 * Matches a symbol mention starting exactly at [pos] in [text], or returns null.
 *
 * Inside code ([code], such as a Markdown code span) any known name links. In prose a name links only when it is written
 * the way code is: camelCase, PascalCase with more than one word, snake_case, a call (`run()`), or qualified (`Foo.bar`),
 * so ordinary words that happen to name a declaration stay plain. A qualified mention links up to the last known part:
 * in `Foo.bar` with only `Foo` known, `Foo` links alone.
 */
fun matchSymbolMention(text: String, pos: Int, names: SymbolNames, code: Boolean): SymbolMention? {
    if (pos >= text.length || !isIdentifierStart(text[pos])) return null
    if (pos > 0 && (isIdentifierPart(text[pos - 1]) || text[pos - 1] in LEFT_FORBIDDEN)) return null
    val parts = mutableListOf<IntRange>()
    var cursor = pos
    while (true) {
        val start = cursor
        if (cursor >= text.length || !isIdentifierStart(text[cursor])) break
        while (cursor < text.length && isIdentifierPart(text[cursor])) cursor++
        parts += start until cursor
        val separator = SEPARATORS.firstOrNull { text.startsWith(it, cursor) } ?: break
        val next = cursor + separator.length
        if (next >= text.length || !isIdentifierStart(text[next])) break
        cursor = next
    }
    if (parts.isEmpty()) return null
    val segments = parts.map { text.substring(it) }
    val lastKnown = segments.indices.lastOrNull { segments[it] in names && segments[it].length >= MIN_NAME_LENGTH } ?: return null
    val nameRange = parts[lastKnown]
    val name = segments[lastKnown]
    var end = nameRange.last + 1
    val whole = lastKnown == parts.lastIndex
    if (whole && end < text.length && (isIdentifierPart(text[end]) || text[end] in RIGHT_FORBIDDEN)) return null
    if (whole && text.startsWith("-", end) && end + 1 < text.length && text[end + 1].isLetter()) return null
    val call = whole && text.startsWith("(", end)
    if (call) {
        val close = callEnd(text, end, code)
        if (close != null) end = close
    }
    val qualifier = segments.subList(0, lastKnown)
    if (!code && !call && qualifier.isEmpty() && !isCodeShaped(name)) return null
    if (!code && !call && qualifier.isNotEmpty() && segments.take(lastKnown + 1).none { isCodeShaped(it) || it[0].isUpperCase() }) return null
    return SymbolMention(pos, end, name, qualifier, call)
}

/** Every [matchSymbolMention] in [text], left to right and without overlaps. */
fun findSymbolMentions(text: String, names: SymbolNames, code: Boolean): List<SymbolMention> {
    val found = mutableListOf<SymbolMention>()
    var pos = 0
    while (pos < text.length) {
        val mention = matchSymbolMention(text, pos, names, code)
        if (mention != null) {
            found += mention
            pos = mention.end
        } else {
            pos++
            while (pos < text.length && isIdentifierPart(text[pos]) && isIdentifierPart(text[pos - 1])) pos++
        }
    }
    return found
}

/** Whether [name] is written the way code is rather than as a word: camelCase, PascalCase with two words, or snake_case. */
fun isCodeShaped(name: String): Boolean {
    if (name.length < MIN_PROSE_LENGTH) return false
    if (!name.any(Char::isLetter)) return false
    if ('_' in name || '$' in name) return true
    return (1 until name.length).any { name[it].isUpperCase() && name[it - 1].isLowerCase() }
}

private fun callEnd(text: String, open: Int, code: Boolean): Int? {
    // `name()` in prose; inside code a call with arguments links only its name, so the arguments can link on their own.
    var i = open + 1
    while (i < text.length && text[i] == ' ') i++
    if (i < text.length && text[i] == ')') return i + 1
    if (!code && text.startsWith("...)", i)) return i + 4
    return null
}

private fun isIdentifierStart(c: Char) = c.isLetter() || c == '_' || c == '$'
private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

private const val MIN_NAME_LENGTH = 2
private const val MIN_PROSE_LENGTH = 3
private val SEPARATORS = listOf("::", ".", "#")
private val LEFT_FORBIDDEN = setOf('.', '/', '\\', '@', '-', '#', ':')
private val RIGHT_FORBIDDEN = setOf('/', '\\', '@')
