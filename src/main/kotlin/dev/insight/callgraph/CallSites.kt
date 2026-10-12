package dev.insight.callgraph

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor

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

/**
 * A call of [callee] (a graph id) inside [frames], listed outermost first. [offset] is where the call ends in the source,
 * so ordering by it puts nested and chained calls (`a(b())`, `b().a()`) in evaluation order.
 */
data class CallSite(val callee: Int, val offset: Int, val frames: List<ControlFrame> = emptyList())

/**
 * Finds call sites and the conditions, loops and error handlers around them. Language-agnostic: control structures are
 * recognized by the words of PSI class names (`PsiIfStatement`, `KtWhenEntry`, `PyExceptPart`, `GoForStatement`…)
 * and their labels are read from source text, so no optional language plugin is referenced.
 */
object CallSiteAnalyzer {
    private val supports get() = CallGraphLanguageSupport.EP_NAME.extensionList

    /**
     * Read action only. Call sites in each of [callers] whose callee is one of [callees], ordered by [CallSite.offset]. Calls inside
     * nested functions and classes belong to those and are left out.
     */
    fun collect(result: CallGraphResult, callers: Collection<Int>, callees: Collection<Int>): Map<Int, List<CallSite>> {
        val targets = HashMap<Triple<String, Int, SymbolKind>, Int>()
        for (id in callees) {
            val element = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid } ?: continue
            ProjectGraphAnalyzer.key(element)?.let { targets.putIfAbsent(it, id) }
        }
        fun target(call: PsiElement): Int? {
            // Synthetic targets (such as implicit constructors) map to the nearest enclosing declaration.
            var current: PsiElement? = ProjectGraphAnalyzer.source(call)
            while (current != null && current !is PsiFile) {
                ProjectGraphAnalyzer.key(current)?.let { return targets[it] }
                current = current.parent
            }
            return null
        }
        val sites = LinkedHashMap<Int, List<CallSite>>()
        for (id in callers.distinct()) {
            val function = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid } ?: continue
            val found = mutableListOf<CallSite>()
            function.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    ProgressManager.checkCanceled()
                    if (element is PsiComment) return
                    if (element !== function && ProjectGraphAnalyzer.kind(element).let { it == SymbolKind.FUNCTION || it == SymbolKind.CLASS }) return
                    val callee = supports.firstNotNullOfOrNull { it.resolveCall(element) }?.let(::target)
                    if (callee != null && callee != id) found += CallSite(callee, element.textRange.endOffset, frames(element, function))
                    super.visitElement(element)
                }
            })
            sites[id] = found.sortedBy { it.offset }
        }
        return sites
    }

    /** Control structures between [call] and [function], outermost first. */
    internal fun frames(call: PsiElement, function: PsiElement): List<ControlFrame> {
        val inner = mutableListOf<ControlFrame>()
        val path = mutableListOf(call)
        var parent = call.parent
        while (parent != null && parent !== function && parent !is PsiFile) {
            val child = path.last()
            val words = words(parent)
            val start = parent.textRange.startOffset
            val offset = call.textRange.startOffset - start
            when {
                words.any { it in PARTS } -> {}
                "If" in words -> ifFrame(parent, child, offset, inner)?.let(inner::add)
                "Conditional" in words -> conditionalFrame(parent, child)?.let(inner::add)
                words.any { it in SWITCHES } -> switchFrame(parent, path)?.let(inner::add)
                "Try" in words -> if (words(child).any { it == "Catch" || it == "Except" }) {
                    inner += ControlFrame(ControlKind.ERROR, start, child.textRange.startOffset, ControlText.header(child.text).label)
                }
                words.any { it in LOOPS } -> ControlText.header(parent.text).takeIf { offset >= it.end }?.let { header ->
                    val label = if (header.keyword == "do") "repeat while " + ControlText.clip(parent.text.substringAfterLast("while"))
                        else header.raw
                    inner += ControlFrame(ControlKind.LOOP, start, start, label)
                }
            }
            path += parent
            parent = parent.parent
        }
        return inner.asReversed()
    }

    /**
     * Branch of an `if` holding the call, or null when the call is in the condition. An `else if` continues the
     * enclosing chain: the frame already recorded for the inner `if` moves to this construct.
     */
    private fun ifFrame(construct: PsiElement, child: PsiElement, offset: Int, inner: MutableList<ControlFrame>): ControlFrame? {
        val start = construct.textRange.startOffset
        val branch = child.textRange.startOffset
        val words = words(child)
        val first = ControlText.header(construct.text)
        fun frame(branch: Int, label: String, otherwise: Boolean = false) =
            ControlFrame(ControlKind.BRANCH, start, branch, label, otherwise, first.label)
        // Python: if / elif / else parts.
        if ("Part" in words) {
            if ("Else" in words) return frame(branch, "", otherwise = true)
            val header = ControlText.header(child.text)
            return if (offset - (branch - start) < header.end) null else frame(branch, header.label)
        }
        val text = child.text
        val isElse = ControlText.startsWithWord(text, "else") ||
            generateSequence(child.prevSibling) { it.prevSibling }.any { it.firstChild == null && it.text == "else" }
        if (!isElse) return if (offset < first.end) null else frame(start, first.label)
        val rest = text.trimStart().let { if (ControlText.startsWithWord(it, "else")) it.removePrefix("else").trimStart() else it }
        if (!ControlText.startsWithWord(rest, "if")) return frame(branch, "", otherwise = true)
        val nested = branch + text.length - rest.length
        val last = inner.lastOrNull()
        if (last != null && last.kind == ControlKind.BRANCH && last.construct == nested) {
            inner[inner.lastIndex] = last.copy(construct = start, subject = first.label)
            return null
        }
        // The call is in the condition of the `else if`: it runs on that branch.
        return frame(nested, ControlText.header(rest).label)
    }

    /** `c ? a : b` and Python's `a if c else b`. */
    private fun conditionalFrame(construct: PsiElement, child: PsiElement): ControlFrame? {
        val start = construct.textRange.startOffset
        val parts = generateSequence(construct.firstChild) { it.nextSibling }.toList()
        val index = parts.indexOf(child)
        fun token(text: String) = parts.indexOfFirst { it.firstChild == null && it.text == text }
        fun text(from: Int, to: Int) = ControlText.clip(parts.subList(from, to).joinToString("") { it.text })
        val question = token("?")
        val colon = token(":")
        val keyword = token("if")
        val otherwise = token("else")
        val (thenFirst, thenEnd, condition) = when {
            question in 0 until colon -> Triple(question + 1, colon, text(0, question))
            keyword in 0 until otherwise -> Triple(0, keyword, text(keyword + 1, otherwise))
            else -> return null
        }
        return when {
            index in thenFirst until thenEnd -> ControlFrame(ControlKind.BRANCH, start, start, condition, subject = condition)
            index > maxOf(colon, otherwise) -> ControlFrame(ControlKind.BRANCH, start, child.textRange.startOffset, "", true, condition)
            else -> null
        }
    }

    /** The case holding the call, or null when the call is in the subject. [path] runs from the call up to the switch's child. */
    private fun switchFrame(construct: PsiElement, path: List<PsiElement>): ControlFrame? {
        val start = construct.textRange.startOffset
        val subject = ControlText.header(construct.text).label.takeIf { it.isNotEmpty() }
        // A case element directly inside the switch or its body block.
        val case = path.asReversed().take(3).firstOrNull { element -> words(element).any { it in CASES } }
            // Java's `case X:` labels are siblings of the statements they guard.
            ?: path.asReversed().getOrNull(1)?.let { statement ->
                generateSequence(statement.prevSibling) { it.prevSibling }.firstOrNull { label ->
                    "Label" in words(label) || ControlText.startsWithWord(label.text, "case") || ControlText.startsWithWord(label.text, "default")
                }
            }
            ?: return null
        val text = case.text.trimStart()
        val header = ControlText.header(text)
        val otherwise = ControlText.startsWithWord(text, "default") || ControlText.startsWithWord(text, "else") || header.label == "_" ||
            header.label.isEmpty()
        return ControlFrame(ControlKind.CASE, start, case.textRange.startOffset, if (otherwise) "" else header.label, otherwise, subject)
    }

    private val CAMEL = Regex("(?<=[a-z])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
    private fun words(element: PsiElement): Set<String> = element.javaClass.simpleName.removeSuffix("Impl").split(CAMEL).toSet()

    /** Words of elements that are parts of a construct rather than the construct (`PyIfPart`, `KtWhenEntry`, `…ForControlStructureBody`). */
    private val PARTS = setOf("Part", "Clause", "Entry", "Label", "Labeled", "Rule", "Condition", "Body", "Section", "Block", "List", "Node")
    private val SWITCHES = setOf("Switch", "When", "Match", "Select")
    private val CASES = setOf("Case", "Entry", "Clause", "Rule")
    private val LOOPS = setOf("For", "Foreach", "While", "Loop")
}

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
