package dev.insight.codereview

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.Processor
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.SymbolKind
import dev.insight.diff.FileBlock
import dev.insight.diff.FileStructure
import dev.insight.review.ReviewDiskCache

/**
 * Splits one snapshot file into blocks and maps each call line to the next files in the call graph.
 *
 * Blocks are the file's outermost functions and properties, top to bottom; adjacent properties form one block, and the lines
 * between declarations (imports, class headers) form their own. Each call stops at the first other project file, so the
 * view explains the next layer behind the source being read. Calls to helpers in the viewed file are followed until they
 * cross that boundary. Interface methods in the viewed file also follow their project implementations. Run in a smart read
 * action.
 */
internal class CodeFileStructureBuilder(private val project: Project, snapshot: CodeSnapshot) {
    private val supports = CallGraphLanguageSupport.EP_NAME.extensionList
    private val index = ProjectFileIndex.getInstance(project)
    private val roots = codeScopeRoots(project)
    private val scopePaths = snapshot.files.associate { it.absolutePath to it.path }
    private val memo = HashMap<Triple<String, Int, SymbolKind>, Set<VirtualFile>>()
    private val active = HashSet<Triple<String, Int, SymbolKind>>()
    private var visits = 0
    private var truncated = false

    fun build(file: CodeFile): FileStructure {
        val virtual = findSourceFile(project, file.path, file.absolutePath) ?: throw IllegalStateException("${file.path} no longer exists.")
        val psi = PsiManager.getInstance(project).findFile(virtual) ?: throw IllegalStateException("${file.path} cannot be parsed.")
        if (supports.none { it.supportsFile(psi) })
            throw IllegalStateException("File view follows calls in Java, Kotlin, Python, JavaScript/TypeScript, Go and Rust; ${file.path.substringAfterLast('/')} is not one of them.")
        val text = psi.viewProvider.document?.text ?: psi.text
        if (text != file.text) throw IllegalStateException("${file.path} differs from the review snapshot. Save it, or wait for the review to reload it.")
        if (file.lineCount == 0) return FileStructure(file.path, file.fingerprint, emptyList())

        val declarations = ProjectGraphAnalyzer.declarations(psi).filter { it !is PsiFile }
        val ranges = blockRanges(file, declarations)
        val calls = List(ranges.size) { linkedMapOf<Int, MutableMap<Triple<String, Int, SymbolKind>, PsiElement>>() }
        psi.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is PsiComment) return
                callee(element)?.let { target ->
                    val line = line(file, element.textOffset)
                    val block = ranges.indexOfFirst { line in it.first }
                    if (block >= 0) ProjectGraphAnalyzer.key(target)?.let {
                        calls[block].getOrPut(line) { linkedMapOf() }.putIfAbsent(it, target)
                    }
                }
                super.visitElement(element)
            }
        })
        val blocks = ranges.mapIndexed { i, (lines, title) ->
            val lineReaches = calls[i].mapValues { (_, targets) ->
                targets.values.flatMapTo(linkedSetOf()) { reach(it, virtual, 0) }.map(::reviewPath).distinct()
            }.filterValues { it.isNotEmpty() }
            FileBlock(lines, title, lineReaches.values.flatten().distinct(), lineReaches)
        }
        return FileStructure(file.path, file.fingerprint, blocks, truncated)
    }

    /** Line ranges with their titles, covering every line of [file] in order. */
    private fun blockRanges(file: CodeFile, declarations: List<PsiElement>): List<Pair<IntRange, String?>> {
        val units = declarations.filter { element ->
            kind(element) in UNITS && generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }.none { kind(it) in UNITS }
        }.map { Declaration(lines(file, it), kind(it) == SymbolKind.PROPERTY, title(it)) }.sortedBy { it.lines.first }
        // Overlapping units (several on one line) and adjacent properties read as one block.
        val merged = mutableListOf<Declaration>()
        for (unit in units) {
            val last = merged.lastOrNull()
            val between = last?.let { (it.lines.last + 1 until unit.lines.first).all { line -> file.lines(line..line).isBlank() } }
            if (last != null && (unit.lines.first <= last.lines.last || (last.property && unit.property && between == true))) {
                merged[merged.size - 1] = Declaration(last.lines.first..maxOf(last.lines.last, unit.lines.last), last.property && unit.property,
                    if (last.property && unit.property) listOfNotNull(last.title, unit.title).joinToString(", ") else last.title)
            } else merged += unit
        }
        // Classes start a new block between declarations; a class header joins the declaration that follows it.
        val starts = declarations.filter { kind(it) == SymbolKind.CLASS }.mapTo(sortedSetOf()) { lines(file, it).first }
        val result = mutableListOf<Pair<IntRange, String?>>()
        /** Adds the lines between declarations; returns the first line of a class header that the next declaration takes. */
        fun gap(range: IntRange, beforeDeclaration: Boolean): Int? {
            if (range.isEmpty()) return null
            var from = range.first
            for (cut in starts.filter { it > range.first && it <= range.last } + (range.last + 1)) {
                val part = from until cut
                from = cut
                if (beforeDeclaration && cut == range.last + 1 && part.first in starts) return part.first
                val text = file.lines(part)
                // Closing braces and blank lines belong to the block above.
                if (result.isNotEmpty() && text.lines().all { it.isBlank() || it.trim().all { c -> c in CLOSING } }) {
                    val (lines, title) = result.removeAt(result.size - 1)
                    result += lines.first..part.last to title
                } else result += part to null
            }
            return null
        }
        var next = 1
        for (unit in merged) {
            if (unit.lines.last < next) continue
            val header = gap(next until unit.lines.first, beforeDeclaration = true)
            result += (header ?: maxOf(unit.lines.first, next))..unit.lines.last to unit.title
            next = unit.lines.last + 1
        }
        gap(next..file.lineCount, beforeDeclaration = false)
        return result
    }

    private class Declaration(val lines: IntRange, val property: Boolean, val title: String?)

    /** The first other files reached from [target], following helpers within [viewedFile]. */
    private fun reach(target: PsiElement, viewedFile: VirtualFile, depth: Int): Set<VirtualFile> {
        ProgressManager.checkCanceled()
        val file = target.containingFile?.virtualFile ?: return emptySet()
        if (!followed(file)) return emptySet()
        if (file != viewedFile) return setOf(file)
        if (kind(target) != SymbolKind.FUNCTION) return emptySet()
        val key = ProjectGraphAnalyzer.key(target) ?: return emptySet()
        memo[key]?.let { return it }
        // A recursive call adds nothing beyond the chain that is already being followed.
        if (key in active) return emptySet()
        if (depth >= MAX_DEPTH || visits >= MAX_VISITS) { truncated = true; return emptySet() }
        visits++
        active += key
        try {
            val callees = callees(target).filter { it.containingFile?.virtualFile?.let(::followed) == true }
            val next = callees.ifEmpty { implementations(target) }
            val result = next.flatMapTo(linkedSetOf()) { reach(it, viewedFile, depth + 1) }
            // Only cache a complete traversal: inner results may omit recursive ancestors still being explored.
            if (active.size == 1 && !truncated) memo[key] = result
            return result
        } finally {
            active -= key
        }
    }

    /** The distinct functions called inside [declaration], in source order. */
    private fun callees(declaration: PsiElement): List<PsiElement> {
        val found = LinkedHashMap<Triple<String, Int, SymbolKind>, PsiElement>()
        declaration.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is PsiComment) return
                callee(element)?.let { target -> ProjectGraphAnalyzer.key(target)?.let { found.putIfAbsent(it, target) } }
                super.visitElement(element)
            }
        })
        return found.values.toList()
    }

    private fun callee(element: PsiElement): PsiElement? =
        supports.firstNotNullOfOrNull { it.resolveCall(element) }?.let(ProjectGraphAnalyzer::source)

    /** Project implementations of an interface or abstract function; empty when the language has no definitions search. */
    private fun implementations(function: PsiElement): List<PsiElement> = try {
        val found = mutableListOf<PsiElement>()
        DefinitionsScopedSearch.search(function, GlobalSearchScope.projectScope(project)).forEach(Processor { definition ->
            val source = ProjectGraphAnalyzer.source(definition)
            if (source != function && source.containingFile?.virtualFile?.let(::followed) == true) found += source
            found.size < MAX_IMPLEMENTATIONS
        })
        found
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private fun followed(file: VirtualFile) = file.isValid && !file.isDirectory && index.isInContent(file) &&
        "/.idea/" !in file.path && "/${ReviewDiskCache.DIRECTORY}/" !in file.path

    /** A scope path for a file in the snapshot, else its project-relative path. */
    private fun reviewPath(file: VirtualFile): String =
        scopePaths[file.path] ?: roots.firstNotNullOfOrNull { VfsUtilCore.getRelativePath(file, it) } ?: file.path

    private fun kind(element: PsiElement) = ProjectGraphAnalyzer.kind(element)

    private fun title(element: PsiElement): String? {
        val name = (element as? PsiNamedElement)?.name ?: return null
        return if (kind(element) == SymbolKind.FUNCTION) "$name()" else name
    }

    private fun lines(file: CodeFile, element: PsiElement): IntRange {
        val range = element.textRange
        return line(file, range.startOffset)..line(file, (range.endOffset - 1).coerceAtLeast(range.startOffset))
    }

    /** The 1-based line of [offset] in [file]. */
    private fun line(file: CodeFile, offset: Int): Int {
        val starts = file.lineStarts
        val found = starts.binarySearch(offset.coerceIn(0, file.text.length))
        return (if (found >= 0) found else -found - 2).coerceIn(0, starts.size - 1) + 1
    }

    private companion object {
        val UNITS = setOf(SymbolKind.FUNCTION, SymbolKind.PROPERTY)
        const val CLOSING = "})];,"
        /** Limit local helper traversal without substituting the viewed file for a neighboring file. */
        const val MAX_DEPTH = 24
        /** Functions followed per file, over all of its blocks. */
        const val MAX_VISITS = 5_000
        const val MAX_IMPLEMENTATIONS = 8
    }
}
