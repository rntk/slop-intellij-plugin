package dev.insight.diff

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiWhiteSpace
import dev.insight.callgraph.CallGraphAnalyzer
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.CallSiteAnalyzer
import dev.insight.callgraph.ControlText
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.SymbolKind
import java.util.BitSet
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

private val LOG = logger<SemanticMetrics>()

/**
 * Counts [Metric]s on whole parsed sides of review files. Constructs are recognized language-agnostically, like
 * [CallSiteAnalyzer]: by the words of PSI class names (`PsiIfStatement`, `KtWhenEntry`, `PyExceptPart`…) and leaf texts.
 *
 * [FileMetrics.declarations] keeps only functions and classes that were added, removed, or whose own text or metrics
 * changed; untouched declarations are left out. An empty side (added or deleted file) has an empty vector.
 */
internal class SemanticMetrics(private val project: Project) {
    private val supports get() = CallGraphLanguageSupport.EP_NAME.extensionList

    fun changes(files: List<ReviewFile>): SemanticChanges = SemanticChanges(files.map { ProgressManager.checkCanceled(); file(it) })

    fun file(file: ReviewFile): FileMetrics {
        fun skipped(reason: String) = FileMetrics(file.path, file.status, null, null, emptyList(), reason)
        val before = file.before
        val after = file.after
        if (before == null || after == null) return skipped("Content unavailable (binary, unreadable or too large)")
        if (before.length > MAX_TEXT || after.length > MAX_TEXT) return skipped("Not analyzed: over the 1 MB size limit")
        val type = FileTypeManager.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/')) as? LanguageFileType
            ?: return skipped("Not source code")
        val live = if (file.status == "deleted" || after.isEmpty()) null else
            LocalFileSystem.getInstance().findFileByPath(file.absolutePath)?.let { PsiManager.getInstance(project).findFile(it) }
                ?.takeIf { it.text == after && CallGraphAnalyzer.isInProject(project, it) }
        val afterPsi = live ?: after.takeIf { it.isNotEmpty() }?.let { parse(file.path, type, it) }
        val beforePsi = before.takeIf { it.isNotEmpty() }?.let { parse(file.path, type, it) }
        if (listOfNotNull(afterPsi, beforePsi).none { psi -> supports.any { it.supportsFile(psi) } })
            return skipped("No code analysis for this language")
        val directory = file.absolutePath.substringBeforeLast('/', "")
        val old = beforePsi?.let { Side(it, directory) }
        val new = afterPsi?.let { Side(it, directory) }
        val declarations = mutableListOf<DeclarationMetrics>()
        for (kind in listOf(SymbolKind.CLASS, SymbolKind.FUNCTION)) {
            for ((left, right) in matchDeclarations(old?.declarations(kind).orEmpty(), new?.declarations(kind).orEmpty())) {
                val beforeVector = left?.let { old!!.own(it) }
                val afterVector = right?.let { new!!.own(it) }
                if (left != null && right != null && left.text == right.text && beforeVector == afterVector) continue
                val shown = (right ?: left)!!
                declarations += DeclarationMetrics(file.path, shown.qualifiedName, kind, shown.lines.first, beforeVector, afterVector)
            }
        }
        declarations.sortBy { it.line }
        return FileMetrics(file.path, file.status, old?.vector.orEmpty(), new?.vector.orEmpty(), declarations)
    }

    private fun parse(path: String, type: LanguageFileType, text: String): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText(path.substringAfterLast('/'), type, text)

    /** One parsed side: its declarations and the vectors counted in a single walk. */
    private inner class Side(file: PsiFile, directory: String) {
        private val found = sideDeclarations(file)
            .filter { it.second.kind == SymbolKind.FUNCTION || it.second.kind == SymbolKind.CLASS }
        private val elements = IdentityHashMap<SideDeclaration, PsiElement>().apply { found.forEach { put(it.second, it.first) } }
        private val counter = Counter(file, directory, found.mapTo(java.util.Collections.newSetFromMap(IdentityHashMap())) { it.first })
        val vector: MetricVector = counter.count()

        fun declarations(kind: SymbolKind) = found.map { it.second }.filter { it.kind == kind }
        fun own(declaration: SideDeclaration): MetricVector = counter.own[elements[declaration]].orEmpty()
    }

    /** Counts owned by one function, class or the file's top-level code. */
    private class Scope(val element: PsiElement, val function: Boolean, val public: Boolean, val iface: Boolean) {
        val counts = IntArray(Metric.entries.size)
        val code = BitSet()
        val comments = BitSet()
        val callees = HashSet<PsiElement>()
        val packages = HashSet<String>()
        var decisions = 0
        var cognitive = 0
        var depth = 0
        var control = 0
        var maxControl = 0
        var lambdas = 0
        var parameters = 0
        var lines = 0

        fun add(metric: Metric, value: Int = 1) { counts[metric.ordinal] += value }

        fun vector(): MetricVector {
            val result = linkedMapOf<Metric, Int>()
            fun put(metric: Metric, value: Int) { if (value != 0) result[metric] = value }
            for (metric in Metric.entries) put(metric, counts[metric.ordinal])
            put(Metric.CODE_LINES, code.cardinality())
            put(Metric.COMMENT_LINES, comments.cardinality())
            put(Metric.CYCLOMATIC, if (function) 1 + decisions else decisions)
            put(Metric.COGNITIVE, cognitive)
            put(Metric.MAX_NESTING, maxControl)
            if (function) {
                put(Metric.MAX_PARAMETERS, parameters)
                put(Metric.MAX_FUNCTION_LINES, lines)
            }
            put(Metric.FAN_OUT, callees.size)
            put(Metric.PACKAGES_USED, packages.size)
            return result.toSortedMap(compareBy { it.ordinal })
        }
    }

    /** A pushed state, undone when the walk leaves [element]. */
    private class Frame(val element: PsiElement, val scope: Boolean, val nest: Boolean, val control: Boolean, val lambda: Boolean)

    private inner class Counter(private val file: PsiFile, private val directory: String, private val declared: Set<PsiElement>) {
        private val index = LineIndex(file.text)
        private val language = file.language.id
        private val scopes = ArrayDeque<Scope>()
        private val all = mutableListOf<Scope>()
        private val frames = ArrayDeque<Frame>()
        private var resolved = 0
        val own = IdentityHashMap<PsiElement, MetricVector>()

        fun count(): MetricVector {
            val top = Scope(file, function = false, public = true, iface = false)
            scopes += top
            all += top
            file.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    ProgressManager.checkCanceled()
                    when {
                        element is PsiComment -> comment(element)
                        element is PsiWhiteSpace -> {}
                        element is PsiFile -> super.visitElement(element)
                        element.firstChild == null -> leaf(element)
                        else -> { composite(element); super.visitElement(element) }
                    }
                }

                override fun elementFinished(element: PsiElement) {
                    while (frames.lastOrNull()?.element === element) pop(frames.removeLast())
                }
            })
            val vectors = all.associateWith { it.vector() }
            for ((scope, vector) in vectors) if (scope.element !== file) own[scope.element] = vector
            val result = linkedMapOf<Metric, Int>()
            for (vector in vectors.values) for ((metric, value) in vector) {
                result[metric] = if (metric.summed) (result[metric] ?: 0) + value else maxOf(result[metric] ?: 0, value)
            }
            fun union(metric: Metric, value: Int) { if (value == 0) result.remove(metric) else result[metric] = value }
            union(Metric.CODE_LINES, BitSet().apply { all.forEach { or(it.code) } }.cardinality())
            union(Metric.COMMENT_LINES, BitSet().apply { all.forEach { or(it.comments) } }.cardinality())
            union(Metric.PACKAGES_USED, all.flatMapTo(HashSet()) { it.packages }.size)
            return result.toSortedMap(compareBy { it.ordinal })
        }

        private val scope get() = scopes.last()

        private fun pop(frame: Frame) {
            if (frame.scope) { scopes.removeLast(); return }
            if (frame.nest) scope.depth--
            if (frame.control) scope.control--
            if (frame.lambda) scope.lambdas--
        }

        private fun push(element: PsiElement, nest: Boolean = false, control: Boolean = false, lambda: Boolean = false) {
            if (nest) scope.depth++
            if (control) { scope.control++; scope.maxControl = maxOf(scope.maxControl, scope.control) }
            if (lambda) scope.lambdas++
            frames += Frame(element, false, nest, control, lambda)
        }

        /** A structure that breaks linear flow: cognitive +1 plus nesting, and nests what it contains. */
        private fun structure(element: PsiElement) {
            scope.cognitive += 1 + scope.depth
            push(element, nest = true, control = true)
        }

        private fun lines(element: PsiElement, into: BitSet) {
            val range = element.textRange
            into.set(index.line(range.startOffset), index.line((range.endOffset - 1).coerceAtLeast(range.startOffset)) + 1)
        }

        private fun comment(element: PsiComment) {
            lines(element, scope.comments)
            val text = element.text
            if (TODO.containsMatchIn(text)) scope.add(Metric.TODOS)
            if (SUPPRESSION_COMMENT.containsMatchIn(text)) scope.add(Metric.SUPPRESSIONS)
        }

        private fun leaf(element: PsiElement) {
            val text = element.text
            if (text.isBlank()) return
            lines(element, scope.code)
            val parent = element.parent ?: return
            when (text) {
                "&&", "||" -> logical(element, text)
                "and", "or" -> if ("Binary" in words(parent)) logical(element, text)
                "?:" -> if (words(parent).any { it == "Operation" || it == "Binary" }) {
                    scope.add(Metric.BRANCHES); scope.decisions++; scope.cognitive++
                }
                "!!" -> scope.add(Metric.NULL_ASSERTIONS)
                "as" -> if (ancestors(element).any { words(it).containsAll(listOf("Binary", "Type")) }) scope.add(Metric.NULL_ASSERTIONS)
                "else" -> elseKeyword(element)
                "elif" -> if ("If" in words(parent)) { scope.add(Metric.BRANCHES); scope.decisions++; scope.cognitive++ }
                else -> if (text[0].isDigit() || text[0] == '.') number(element, text)
            }
        }

        private fun number(element: PsiElement, text: String) {
            if (!NUMBER.matches(text) || ZERO_OR_ONE.matches(text.lowercase().replace("_", ""))) return
            if (ancestors(element).any { words(it).any { word -> word == "String" || word == "Template" || word == "Doc" } }) return
            scope.add(Metric.NUMBER_LITERALS)
        }

        /** Short-circuit operators; for cognitive complexity a run of the same operator counts once. */
        private fun logical(element: PsiElement, text: String) {
            scope.add(Metric.LOGICAL_OPERATORS)
            scope.decisions++
            val owner = element.parent.let { if ("Operation" in words(it)) it.parent else it } ?: return
            val operators = operators(owner)
            val previous = operators.getOrNull(operators.indexOfFirst { it.first === element || it.first === element.parent } - 1)
            val continued = previous?.second == text || owner.parent?.let { operators(it).any { op -> op.second == text } } == true
            if (!continued) scope.cognitive++
        }

        private fun operators(binary: PsiElement): List<Pair<PsiElement, String>> = children(binary).mapNotNull { child ->
            val text = child.text
            if (text in LOGICAL && (child.firstChild == null || ("Operation" in words(child) && text != "and" && text != "or"))) child to text else null
        }

        /** `else` adds a branch to an `if` for cognitive complexity unless an `else if` follows, which counts itself. */
        private fun elseKeyword(element: PsiElement) {
            val parent = element.parent ?: return
            val owner = if ("Else" in words(parent)) parent.parent else parent
            if (owner == null || !isIf(owner)) return
            var next = meaningful(element.nextSibling, forward = true)
            if (next != null && !isIf(next)) next = children(next).singleOrNull() ?: next
            if (next == null || !isIf(next)) scope.cognitive++
        }

        private fun composite(element: PsiElement) {
            if (element in declared) { declaration(element); return }
            val words = words(element)
            val kind = ProjectGraphAnalyzer.kind(element)
            if (kind == SymbolKind.PROPERTY) property(element)
            if (kind == SymbolKind.VARIABLE) variable(element, words)
            if (kind == SymbolKind.PARAMETER || kind == SymbolKind.PROPERTY) parameter(element, words)
            if (isStatement(element, words, kind)) scope.add(Metric.STATEMENTS)
            imports(element)
            if ("Annotation" in words && words.none { it in ANNOTATION_PARTS } && SUPPRESS.containsMatchIn(element.text)) scope.add(Metric.SUPPRESSIONS)
            if (words.containsAll(listOf("Type", "Cast")) || words.containsAll(listOf("Type", "Assertion")) ||
                words.containsAll(listOf("Not", "Null")) || ("As" in words && "Expression" in words)) scope.add(Metric.NULL_ASSERTIONS)
            if (isString(element, words)) scope.add(Metric.STRING_LITERALS)
            calls(element)
            control(element, words, kind)
        }

        private fun control(element: PsiElement, words: Set<String>, kind: SymbolKind?) {
            val part = words.any { it in PARTS }
            when {
                isIf(element) -> {
                    scope.add(Metric.BRANCHES); scope.decisions++
                    if (isElseIf(element)) scope.cognitive++ else structure(element)
                }
                "Conditional" in words && !part && children(element).any { it.firstChild == null && (it.text == "?" || it.text == "if") } -> { scope.add(Metric.BRANCHES); scope.decisions++; structure(element) }
                words.any { it in SWITCHES } && !part && words.none { it in NOT_CONSTRUCT } -> structure(element)
                isCase(words) -> if (!isDefault(element)) { scope.add(Metric.CASES); scope.decisions++ }
                words.any { it in LOOPS } && !part && words.none { it in NOT_CONSTRUCT } -> { scope.add(Metric.LOOPS); scope.decisions++; structure(element) }
                ("Catch" in words || "Except" in words) && words.none { it in NOT_CATCH } -> { scope.add(Metric.CATCHES); scope.decisions++; structure(element) }
                words.any { it == "Throw" || it == "Raise" } && !part && words.none { it in NOT_CONSTRUCT } -> scope.add(Metric.THROWS)
                "Return" in words && !part && words.none { it in NOT_CONSTRUCT } -> scope.add(Metric.RETURNS)
                words.any { it == "Break" || it == "Continue" } && !part -> scope.add(Metric.JUMPS)
                kind != SymbolKind.FUNCTION && words.none { it in NOT_LAMBDA } &&
                    ("Lambda" in words || "Closure" in words || ("Function" in words && ("Lit" in words || "Expression" in words))) -> {
                    scope.add(Metric.LAMBDAS)
                    push(element, nest = true, lambda = true)
                }
            }
        }

        private fun declaration(element: PsiElement) {
            val function = ProjectGraphAnalyzer.kind(element) == SymbolKind.FUNCTION
            val outer = scope
            val modifiers = modifiers(element)
            val public = !outer.function && (outer.element === file || outer.public) && visible(element, modifiers, outer)
            val next = Scope(element, function, public, "interface" in leaves(element) || "@interface" in leaves(element))
            if (public) next.add(Metric.PUBLIC_DECLARATIONS)
            next.add(if (function) Metric.FUNCTIONS else Metric.CLASSES)
            if (documented(element)) next.add(Metric.DOCUMENTED)
            if (function) {
                val range = element.textRange
                next.lines = index.line((range.endOffset - 1).coerceAtLeast(range.startOffset)) - index.line(range.startOffset) + 1
                if (overrides(element, modifiers)) next.add(Metric.OVERRIDES)
            }
            scopes += next
            all += next
            frames += Frame(element, true, false, false, false)
        }

        private fun property(element: PsiElement) {
            scope.add(Metric.PROPERTIES)
            val modifiers = modifiers(element)
            val owner = scopes.lastOrNull { !it.function } ?: scope
            if ((owner.element === file || owner.public) && visible(element, modifiers, owner)) scope.add(Metric.PUBLIC_DECLARATIONS)
            if (documented(element)) scope.add(Metric.DOCUMENTED)
            if (isConstant(element, modifiers)) scope.add(Metric.CONSTANTS)
        }

        private fun variable(element: PsiElement, words: Set<String>) {
            val keywords = modifiers(element) + leaves(element) + (element.parent?.takeUnless { it is PsiFile || words(it).any { w -> w in CONTAINERS } }
                ?.let(::leaves).orEmpty())
            val immutable = "Const" in words || keywords.any { it in IMMUTABLE }
            scope.add(if (immutable) Metric.IMMUTABLE_VARIABLES else Metric.MUTABLE_VARIABLES)
            if (isConstant(element, modifiers(element))) scope.add(Metric.CONSTANTS)
        }

        private fun parameter(element: PsiElement, words: Set<String>) {
            if (words.none { it == "Parameter" || it == "Param" } || words.any { it == "Type" || it == "Star" || it == "Slash" }) return
            val owners = ancestors(element).map(::words).toList()
            if (owners.none { it.any { word -> word == "Parameter" || word == "Parameters" } } || owners.any { "Catch" in it || "Except" in it }) return
            scope.add(Metric.PARAMETERS)
            if (scope.function && scope.lambdas == 0) scope.parameters++
        }

        /** Resolved calls; resolution must not fail the count, so whatever does not resolve is left out. */
        private fun calls(element: PsiElement) {
            if (resolved >= MAX_RESOLVED) return
            val target = safely { supports.firstNotNullOfOrNull { it.resolveCall(element) }?.let(ProjectGraphAnalyzer::source) } ?: return
            resolved++
            scope.callees += target
            val targetFile = safely { target.containingFile }
            val local = targetFile == null || targetFile === file || targetFile.originalFile === file
            if (!local && safely { CallGraphAnalyzer.isInProject(project, target) } != true) scope.add(Metric.EXTERNAL_CALLS)
            val pkg = if (local) directory else
                safely { targetFile.containingDirectory?.virtualFile?.path ?: targetFile.virtualFile?.parent?.path }
            pkg?.let(scope.packages::add)
        }


        private fun imports(element: PsiElement) {
            if (!isImport(element)) return
            if (children(element).any { child -> isImport(child) || children(child).any(::isImport) }) return
            scope.add(Metric.IMPORTS)
        }

        private fun isImport(element: PsiElement): Boolean {
            val name = element.javaClass.simpleName
            return "Import" in name && IMPORT_KINDS.any { it in name } && "List" !in name && "Specifier" !in name
        }

        private fun isStatement(element: PsiElement, words: Set<String>, kind: SymbolKind?): Boolean {
            if ("Statement" in words) {
                if (words.any { it in NOT_STATEMENT }) return false
                if ("Simple" in words && element.parent?.let { "Block" in words(it) } != true) return false
                val parent = element.parent
                // Wrappers such as Go's simple statement around an assignment count once.
                return !(parent != null && "Statement" in words(parent) && parent.textRange == element.textRange)
            }
            if (kind == SymbolKind.FUNCTION || kind == SymbolKind.CLASS) return false
            return element.parent?.let { "Block" in words(it) } == true
        }

        private fun isString(element: PsiElement, words: Set<String>): Boolean {
            if (words.any { it == "Entry" || it == "Part" || it == "Element" }) return false
            if ("String" in words && ("Literal" in words || "Template" in words)) return true
            return "Literal" in words && "String" !in words && element.text.firstOrNull().let { it == '"' || it == '\'' || it == '`' }
        }

        private fun isIf(element: PsiElement): Boolean = words(element).let { "If" in it && it.none { word -> word in PARTS } }

        private fun isElseIf(element: PsiElement): Boolean {
            fun afterElse(e: PsiElement) = meaningful(e.prevSibling, forward = false)?.let { it.firstChild == null && it.text == "else" } == true
            if (afterElse(element)) return true
            val parent = element.parent ?: return false
            return meaningful(element.prevSibling, forward = false) == null && afterElse(parent)
        }

        private fun isCase(words: Set<String>): Boolean {
            if (words.none { it in PARTS } || words.any { it in NOT_CASE }) return false
            return "Case" in words || ("Comm" in words && "Clause" in words) ||
                (words.any { it in SWITCHES } && words.any { it == "Entry" || it == "Label" || it == "Labeled" || it == "Rule" })
        }

        private fun isDefault(element: PsiElement): Boolean {
            val text = element.text.trimStart()
            return ControlText.startsWithWord(text, "default") || ControlText.startsWithWord(text, "else") ||
                (ControlText.startsWithWord(text, "case") && ControlText.header(text).label == "_")
        }

        private fun isConstant(element: PsiElement, modifiers: Set<String>): Boolean {
            if ("const" in modifiers || ("static" in modifiers && "final" in modifiers) || "Const" in words(element)) return true
            val name = (element as? PsiNamedElement)?.name ?: return false
            return !scope.function && name.length > 1 && CONSTANT_NAME.matches(name)
        }

        private fun visible(element: PsiElement, modifiers: Set<String>, owner: Scope): Boolean {
            val name = (element as? PsiNamedElement)?.name ?: return false
            return when {
                language == "JAVA" -> "public" in modifiers || "protected" in modifiers || owner.iface
                language == "Python" -> !name.startsWith("_") || (name.startsWith("__") && name.endsWith("__"))
                language == "go" -> name.first().isUpperCase()
                JS.containsMatchIn(language) -> if (owner.element === file)
                    "export" in modifiers || element.parent?.text?.trimStart()?.startsWith("export") == true
                    else "private" !in modifiers && !name.startsWith("#")
                else -> "private" !in modifiers && "internal" !in modifiers
            }
        }

        private fun overrides(element: PsiElement, modifiers: Set<String>): Boolean {
            if ("override" in modifiers) return true
            if (children(element).any { child -> "Modifier" in words(child) && child.text.contains("@Override") }) return true
            if (language == "kotlin") return false
            return safely { supports.any { it.superMethods(element).isNotEmpty() } } == true
        }

        /** A doc comment leading or preceding the declaration, a Go comment right above it, or a Python docstring. */
        private fun documented(element: PsiElement): Boolean {
            val first = generateSequence(element.firstChild) { it.nextSibling }.firstOrNull { it !is PsiWhiteSpace }
            if (first is PsiComment && first.text.startsWith("/**")) return true
            var previous = element.prevSibling
            if (previous is PsiWhiteSpace && previous.text.count { it == '\n' } <= 1) previous = previous.prevSibling
            if (previous is PsiComment && (previous.text.startsWith("/**") || (language == "go" && previous.text.startsWith("//")))) return true
            val body = children(element).firstOrNull { words(it).containsAll(listOf("Statement", "List")) } ?: return false
            return children(body).firstOrNull()?.text?.let(DOCSTRING::containsMatchIn) == true
        }

        /** Leaf texts of [element]'s modifier or attribute list. */
        private fun modifiers(element: PsiElement): Set<String> = children(element)
            .filter { child -> words(child).any { it == "Modifier" || it == "Attribute" } }
            .flatMapTo(HashSet()) { list -> children(list).filter { it.firstChild == null }.map { it.text } }

        private fun leaves(element: PsiElement): Set<String> = children(element).filter { it.firstChild == null }.mapTo(HashSet()) { it.text }

        private fun children(element: PsiElement): List<PsiElement> =
            generateSequence(element.firstChild) { it.nextSibling }.filter { it !is PsiWhiteSpace && it !is PsiComment }.toList()

        private fun meaningful(start: PsiElement?, forward: Boolean): PsiElement? =
            generateSequence(start) { if (forward) it.nextSibling else it.prevSibling }.firstOrNull { it !is PsiWhiteSpace && it !is PsiComment }

        private fun ancestors(element: PsiElement) = generateSequence(element.parent) { it.parent }.take(2)
    }

    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        if (e is ControlFlowException || e is CancellationException || e is VirtualMachineError) throw e
        // Expected for constructs a language plugin cannot resolve; this runs per call site, so a warning would flood the log.
        LOG.debug("Semantic metric calculation failed", e)
        null
    }

    companion object {
        const val MAX_TEXT = 1_000_000
        /** Resolved calls per side; resolution dominates the cost on large files. */
        const val MAX_RESOLVED = 5_000

        private val WORDS = ConcurrentHashMap<Class<*>, Set<String>>()
        private fun words(element: PsiElement): Set<String> = WORDS.getOrPut(element.javaClass) { CallSiteAnalyzer.words(element) }

        /** Words of parts of a construct rather than the construct (as in [CallSiteAnalyzer]). */
        private val PARTS = setOf("Part", "Clause", "Entry", "Label", "Labeled", "Rule", "Condition", "Body", "Section", "Block", "List", "Node")
        private val SWITCHES = setOf("Switch", "When", "Match", "Select")
        private val LOOPS = setOf("For", "Foreach", "While", "Loop")
        private val NOT_CONSTRUCT = setOf("List", "Type", "Value", "Subject", "Pattern", "Guard", "Range", "Header")
        private val NOT_CASE = setOf("List", "Pattern", "Element", "Guard", "Body", "Block")
        private val NOT_CATCH = setOf("List", "Parameter", "Type", "Try")
        private val NOT_LAMBDA = setOf("Argument", "List", "Parameter", "Body", "Type", "Literal")
        private val NOT_STATEMENT = setOf("Block", "List", "Label", "Labeled", "Empty", "Else", "Import", "Package")
        private val ANNOTATION_PARTS = setOf("List", "Target", "Use", "Site", "Parameter", "Method", "Name", "Value", "Argument")
        private val CONTAINERS = setOf("Block", "Body", "Class", "File", "List")
        private val IMPORT_KINDS = listOf("Directive", "Statement", "Spec", "Declaration")
        private val IMMUTABLE = setOf("val", "const", "final", "readonly")
        private val LOGICAL = setOf("&&", "||", "and", "or")
        private val JS = Regex("JavaScript|TypeScript|ECMAScript|JSX|Flow")
        private val TODO = Regex("\\b(TODO|FIXME|HACK)\\b")
        private val SUPPRESS = Regex("^@(\\w+:)?Suppress(Warnings)?\\b")
        private val SUPPRESSION_COMMENT = Regex("noinspection|noqa|eslint-disable|nolint|@ts-ignore|@ts-expect-error|pylint:\\s*disable|type:\\s*ignore")
        private val NUMBER = Regex("(0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\\d[\\d_]*(\\.\\d[\\d_]*)?([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?)[a-zA-Z]{0,3}")
        private val ZERO_OR_ONE = Regex("(0x|0b|0o)?0*1?(\\.0*)?[lfdu]*")
        private val CONSTANT_NAME = Regex("[A-Z][A-Z0-9_]*")
        private val DOCSTRING = Regex("^[rRbBuUfF]{0,2}[\"']")
    }
}
