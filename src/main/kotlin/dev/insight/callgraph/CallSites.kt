package dev.insight.callgraph

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiWhiteSpace
import dev.insight.graph.CallGraphResult
import dev.insight.graph.CallOperation
import dev.insight.graph.CallSite
import dev.insight.graph.ControlFrame
import dev.insight.graph.ControlKind
import dev.insight.graph.ControlText
import dev.insight.graph.ExitSite
import dev.insight.graph.SourceSite
import dev.insight.graph.SymbolKind
import dev.insight.graph.sourceOrder

/**
 * Finds call sites and the conditions, loops and error handlers around them. Language-agnostic: control structures are
 * recognized by the words of PSI class names (`PsiIfStatement`, `KtWhenEntry`, `PyExceptPart`, `GoForStatement`…)
 * and their labels are read from source text, so no optional language plugin is referenced.
 */
object CallSiteAnalyzer {
    private val supports get() = CallGraphLanguageSupport.EP_NAME.extensionList

    /**
     * Read action only. Call sites in each of [callers] whose callee is one of [callees], and the exits ending branches
     * ([exit]), in [sourceOrder]. Calls inside nested functions and classes belong to those and are left out.
     */
    fun collect(result: CallGraphResult, callers: Collection<Int>, callees: Collection<Int>): Map<Int, List<SourceSite>> {
        // Project snapshots already contain source locations and resolution evidence. Never resolve these calls again.
        if (result.graph.projectGraph) return collectSnapshot(result, callers, callees.toSet())
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
        val sites = LinkedHashMap<Int, List<SourceSite>>()
        for (id in callers.distinct()) {
            val function = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid } ?: continue
            val found = mutableListOf<SourceSite>()
            function.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    ProgressManager.checkCanceled()
                    if (element is PsiComment) return
                    if (element !== function && ProjectGraphAnalyzer.kind(element).let { it == SymbolKind.FUNCTION || it == SymbolKind.CLASS }) return
                    val callees = supports.firstNotNullOfOrNull { it.callSite(element) }?.targets.orEmpty().mapNotNull(::target).distinct()
                    for (callee in callees) if (callee != id) found += CallSite(callee, element.textRange.endOffset, frames(element, function),
                        element.text.take(600), element.textRange.startOffset)
                    exit(element, function)?.let(found::add)
                    super.visitElement(element)
                }
            })
            sites[id] = found.sortedWith(sourceOrder)
        }
        return sites
    }

    private fun collectSnapshot(result: CallGraphResult, callers: Collection<Int>, callees: Set<Int>): Map<Int, List<SourceSite>> {
        val byScope = result.occurrences.filter { it.operation == CallOperation.INVOKE }.groupBy { it.scope }
        return callers.distinct().associateWith { id ->
            val found = byScope[id].orEmpty().flatMap { occurrence ->
                occurrence.displayTargets.filter { it in callees && it != id }.map { callee ->
                    CallSite(callee, occurrence.endOffset, occurrence.frames, occurrence.text, occurrence.startOffset)
                }
            }.toMutableList<SourceSite>()
            val function = result.pointers.getOrNull(id)?.element?.takeIf { it.isValid }
            function?.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    ProgressManager.checkCanceled()
                    if (element is PsiComment) return
                    if (element !== function && ProjectGraphAnalyzer.kind(element).let { it == SymbolKind.FUNCTION || it == SymbolKind.CLASS }) return
                    exit(element, function)?.let(found::add)
                    super.visitElement(element)
                }
            })
            found.sortedWith(sourceOrder)
        }
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
     * [element] as an exit when it is a `return`, `throw` or `raise` ending a branch of an `if`, a case of a
     * `when`/`switch`/`match` or a `catch`/`except`: that branch then certainly leaves [function]. Anything less certain
     * is left out, so the chart never ends a path that may go on: an exit inside an expression (`x ?: return`), followed
     * by other statements, ending a loop body or a `try` block, or leaving a lambda rather than [function].
     */
    internal fun exit(element: PsiElement, function: PsiElement): ExitSite? {
        if (words(element).none { it in EXITS }) return null
        val text = element.text.trim()
        val keyword = text.takeWhile { it.isLetter() }
        if (keyword !in EXIT_KEYWORDS || text.startsWith("return@")) return null
        val path = generateSequence(element.parent) { it.parent }.takeWhile { it !== function && it !is PsiFile }.toList()
        if (path.lastOrNull()?.parent !== function || path.any { parent -> words(parent).any { it in LAMBDAS } }) return null
        // Up through blocks, where the exit must be the last statement, to the branch or case it ends.
        var child = element
        for (parent in path) {
            val words = words(parent)
            if (words.none { it in PARTS }) {
                if ("If" in words || words.any { it in SWITCHES }) break
                if ("Try" in words && words(child).any { it == "Catch" || it == "Except" }) break
                return null
            }
            if (lastStatement(parent) !== child) return null
            if (words.any { it in CASES }) break
            child = parent
        }
        val frames = frames(element, function)
        if (frames.lastOrNull()?.kind.let { it == null || it == ControlKind.LOOP }) return null
        return ExitSite(element.textRange.endOffset, frames, keyword, ControlText.clip(text))
    }

    /** The last child of [element] other than whitespace, comments and closing `}` or `;`. */
    private fun lastStatement(element: PsiElement): PsiElement? = generateSequence(element.lastChild) { it.prevSibling }.firstOrNull { child ->
        child !is PsiWhiteSpace && child !is PsiComment && !(child.firstChild == null && child.text.trim().let { it.isEmpty() || it == "}" || it == ";" })
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
    internal fun words(element: PsiElement): Set<String> = element.javaClass.simpleName.removeSuffix("Impl").split(CAMEL).toSet()

    /** Words of elements that are parts of a construct rather than the construct (`PyIfPart`, `KtWhenEntry`, `…ForControlStructureBody`). */
    private val PARTS = setOf("Part", "Clause", "Entry", "Label", "Labeled", "Rule", "Condition", "Body", "Section", "Block", "List", "Node")
    private val SWITCHES = setOf("Switch", "When", "Match", "Select")
    private val CASES = setOf("Case", "Entry", "Clause", "Rule")
    private val LOOPS = setOf("For", "Foreach", "While", "Loop")
    private val EXITS = setOf("Return", "Throw", "Raise")
    private val EXIT_KEYWORDS = setOf("return", "throw", "raise")
    /** Words of lambdas and function literals, whose `return` leaves them rather than the enclosing function. */
    private val LAMBDAS = setOf("Lambda", "Function", "Closure")
}
