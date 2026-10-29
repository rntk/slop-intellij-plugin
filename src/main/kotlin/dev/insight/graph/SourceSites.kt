package dev.insight.graph

/** [BRANCH]: `if` and `?:`; [CASE]: `when`, `switch`, `match` and `select`; [ERROR]: `catch` and `except`. */
enum class ControlKind { BRANCH, CASE, LOOP, ERROR }

/**
 * A control structure enclosing a call. [construct] and [branch] are source offsets: calls inside the same
 * `if`/`when`/`switch`/`try`/loop share [construct], and calls in the same branch of it share [branch]. Branches of
 * one construct are ordered by [branch]. An `else if` chain is one construct.
 */
data class ControlFrame(
    val kind: ControlKind,
    val construct: Int,
    val branch: Int,
    /** Condition, case, loop header or caught exception, shortened for display; empty for `else` and `default`. */
    val label: String,
    /** True for `else`, `default` and `case _` branches. */
    val otherwise: Boolean = false,
    /** What a `when`, `switch` or `match` examines, or the first condition of an `if` chain. */
    val subject: String? = null,
)

/** Something in a function body inside [frames], listed outermost first; [offset] is where it ends in the source. */
sealed interface SourceSite {
    val offset: Int
    val frames: List<ControlFrame>
}

/**
 * A call of [callee] (a graph id). Ordering by [offset] puts nested and chained calls (`a(b())`, `b().a()`) in
 * evaluation order.
 */
data class CallSite(val callee: Int, override val offset: Int, override val frames: List<ControlFrame> = emptyList(),
    /** Bounded source at this occurrence, including arguments; useful when the owner's excerpt is truncated. */
    val text: String? = null,
    val startOffset: Int? = null) : SourceSite

/**
 * A `return`, `throw` or `raise` ([keyword]) ending the innermost branch of [frames]: that branch leaves the function
 * instead of continuing below its decision. [text] is the statement, shortened for display.
 */
data class ExitSite(override val offset: Int, override val frames: List<ControlFrame>, val keyword: String, val text: String) : SourceSite

/** Source order; an exit comes after the calls it ends with, such as `return f()`. */
val sourceOrder: Comparator<SourceSite> = compareBy<SourceSite> { it.offset }.thenBy { it is ExitSite }

/** PSI-free reading of control-structure headers such as `if (a) {`, `elif b:`, `case X ->` or `for x in xs:`. */
internal object ControlText {
    const val MAX_LABEL = 60
    private val KEYWORDS = setOf("if", "elif", "else", "while", "for", "foreach", "switch", "when", "match", "select",
        "catch", "except", "do", "case", "default", "await")

    /**
     * [keyword] is the first leading keyword, [label] the condition or pattern after the keywords (without enclosing
     * parentheses), [raw] the header from its start, and [end] the offset in the text where the header ends.
     */
    data class Header(val keyword: String?, val label: String, val raw: String, val end: Int)

    fun header(text: String): Header {
        var i = skipSpace(text, 0)
        val begin = i
        var keyword: String? = null
        while (true) {
            var j = i
            while (j < text.length && (text[j].isLetter() || text[j] == '_')) j++
            val word = text.substring(i, j)
            if (word !in KEYWORDS || (j < text.length && text[j].isLetterOrDigit())) break
            if (keyword == null) keyword = word
            i = skipSpace(text, j)
        }
        if (i < text.length && text[i] == '(') {
            val close = matching(text, i)
            return Header(keyword, clip(text.substring(i + 1, close.coerceAtLeast(i + 1))), clip(text.substring(begin, close + 1)), close + 1)
        }
        var depth = 0
        var j = i
        loop@ while (j < text.length) {
            val c = text[j]
            when {
                c == '"' || c == '\'' || c == '`' -> { j = skipString(text, j); continue@loop }
                c == '(' || c == '[' -> depth++
                c == ')' || c == ']' -> depth--
                depth > 0 -> {}
                c == '{' || c == '\n' -> break@loop
                c == ':' && (j + 1 == text.length || text[j + 1].isWhitespace()) -> break@loop
                c == '-' && text.startsWith("->", j) -> break@loop
            }
            j++
        }
        val label = text.substring(i, j).let { if (keyword == "if" || keyword == "elif") it.substringAfterLast("; ") else it }
        return Header(keyword, clip(label), clip(text.substring(begin, j)), j)
    }

    fun startsWithWord(text: String, word: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith(word) && (trimmed.length == word.length || !trimmed[word.length].let { it.isLetterOrDigit() || it == '_' })
    }

    /** Collapses whitespace and shortens to [MAX_LABEL] characters. */
    fun clip(text: String): String {
        val single = text.trim().replace(Regex("\\s+"), " ")
        return if (single.length <= MAX_LABEL) single else single.take(MAX_LABEL - 1).trimEnd() + "…"
    }

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Offset of the parenthesis closing the one at [open], or the last offset when it is unbalanced. */
    private fun matching(text: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '"', '\'', '`' -> { i = skipString(text, i); continue }
                '(' -> depth++
                ')' -> if (--depth == 0) return i
            }
            i++
        }
        return text.length - 1
    }

    /** Offset after the string literal starting at [from]; strings end at their quote or the end of the line. */
    private fun skipString(text: String, from: Int): Int {
        val quote = text[from]
        var i = from + 1
        while (i < text.length && text[i] != quote && text[i] != '\n') i += if (text[i] == '\\') 2 else 1
        return (i + 1).coerceAtMost(text.length)
    }
}
