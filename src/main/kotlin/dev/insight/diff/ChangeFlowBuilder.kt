package dev.insight.diff

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import dev.insight.callgraph.CallGraphAnalyzer
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.CallSiteAnalyzer
import dev.insight.callgraph.ControlFrame
import dev.insight.callgraph.ControlKind
import dev.insight.callgraph.DiffService
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.SymbolKind
import java.nio.file.Path

/**
 * Builds the [ChangeGraph] of a review snapshot from PSI. Run in a smart read action.
 *
 * Both sides of each file are parsed from the snapshot text, so added, modified and deleted functions are found for
 * any comparison. Calls, exits, conditions and callers are resolved only where the after side is the project's current
 * source; elsewhere they are reported as unavailable rather than approximated.
 */
internal class ChangeFlowBuilder(private val project: Project, private val snapshot: ReviewSnapshot) {
    private val supports get() = CallGraphLanguageSupport.EP_NAME.extensionList
    private val base = project.basePath

    /** A changed function before ids are assigned; [element] is its live after-side PSI, when available. */
    private class Pending(
        val file: ReviewFile,
        val before: SideDeclaration?,
        val after: SideDeclaration?,
        val status: ChangeStatus,
        val trivial: TrivialChange?,
        val signature: Boolean,
        val added: Int,
        val removed: Int,
        val test: Boolean,
        val element: PsiElement?,
        val hunk: Pair<List<HunkLine>, Boolean>,
        /** Shared by every function of the file: changed after-side lines and line numbers of the after text. */
        val changedAfter: Set<Int>,
        val index: LineIndex,
    )

    private class Walked(val steps: List<RawStep>, val truncated: Boolean)
    private class RawStep(val line: Int, val text: String, val exit: Boolean, val conditions: List<String>, val target: PsiElement?, val changed: Boolean)

    fun build(): ChangeGraph {
        val files = mutableListOf<ChangeFile>()
        val pending = mutableListOf<Pending>()
        var truncated = false
        for (file in snapshot.files) {
            ProgressManager.checkCanceled()
            if (pending.size >= MAX_CHANGED) { truncated = true; files += ChangeFile(file.path, file.status, "Not analyzed: over the $MAX_CHANGED function limit"); continue }
            files += analyzeFile(file, pending)
        }
        if (pending.size > MAX_CHANGED) { truncated = true; pending.subList(MAX_CHANGED, pending.size).clear() }

        // Ids: changed functions in file and line order, then the unchanged functions that connect them.
        val ordered = pending.sortedWith(compareBy({ it.file.path }, { (it.after ?: it.before)!!.lines.first }))
        val keyOf = HashMap<Pair<String, Int>, Int>()
        ordered.forEachIndexed { id, change -> change.element?.let { key(it) }?.let { keyOf[it] = id } }

        val walked = HashMap<Int, Walked>()
        ordered.forEachIndexed { id, change ->
            val element = change.element ?: return@forEachIndexed
            walked[id] = walk(element, change.index, change.changedAfter)
        }

        // Bridges: unchanged in-project functions that a changed function calls and that call a changed function.
        val bridges = linkedMapOf<Pair<String, Int>, Pair<PsiElement, Walked>>()
        val candidates = linkedMapOf<Pair<String, Int>, PsiElement>()
        walked.values.forEach { walk -> walk.steps.forEach { step ->
            val target = step.target ?: return@forEach
            val key = key(target) ?: return@forEach
            if (key !in keyOf) candidates.putIfAbsent(key, target)
        } }
        for ((key, target) in candidates.entries.take(MAX_BRIDGE_CANDIDATES)) {
            ProgressManager.checkCanceled()
            val text = target.containingFile?.text ?: continue
            val walk = walk(target, LineIndex(text), emptySet())
            if (walk.steps.any { step -> step.target?.let(::key)?.let(keyOf::containsKey) == true }) bridges[key] = target to walk
        }
        val contextIds = bridges.keys.withIndex().associate { (index, key) -> key to ordered.size + index }
        fun id(element: PsiElement?): Int? = element?.let(::key)?.let { keyOf[it] ?: contextIds[it] }

        val edges = linkedSetOf<ChangeEdge>()
        val nodes = mutableListOf<ChangeNode>()
        // Reference searches are the expensive part; the largest changes get them first.
        val searched = ordered.indices.filter { ordered[it].element != null }
            .sortedByDescending { ordered[it].added + ordered[it].removed }.take(MAX_CALLER_SEARCHES).toHashSet()
        ordered.forEachIndexed { id, change ->
            val declaration = (change.after ?: change.before)!!
            val walk = walked[id]
            walk?.steps?.forEach { step -> id(step.target)?.takeIf { it != id }?.let { edges += ChangeEdge(id, it) } }
            val (callers, callersTruncated) = change.element?.takeIf { id in searched }?.let { callers(it) { key -> key in keyOf || key in contextIds } }
                ?: (null to false)
            nodes += ChangeNode(
                id, change.file.path, change.file.absolutePath, declaration.name, declaration.qualifiedName, change.status,
                declaration.header, declaration.lines.first, change.trivial, change.signature, change.added, change.removed,
                change.test, change.element?.let { ProjectGraphAnalyzer.source(it).textOffset },
                walk?.steps?.map { step(it, ::id) }, callers.orEmpty(), callersTruncated, callers != null, change.hunk.first, change.hunk.second,
                stepsTruncated = walk?.truncated == true,
            )
        }
        bridges.entries.forEachIndexed { index, (_, value) ->
            val (element, walk) = value
            val id = ordered.size + index
            walk.steps.forEach { step -> id(step.target)?.takeIf { it != id && it < ordered.size }?.let { edges += ChangeEdge(id, it) } }
            val file = element.containingFile
            val absolute = file?.virtualFile?.path.orEmpty()
            val text = file?.text.orEmpty()
            nodes += ChangeNode(
                id, relative(absolute), absolute, (element as? PsiNamedElement)?.name ?: "?", qualifiedName(element),
                ChangeStatus.CONTEXT, header(element), LineIndex(text).line(element.textRange.startOffset),
                test = isTest(file), offset = ProjectGraphAnalyzer.source(element).textOffset, steps = walk.steps.map { step(it, ::id) },
                stepsTruncated = walk.truncated,
            )
        }
        val edgeList = edges.toList()
        return ChangeGraph(nodes, edgeList, clusterChanges(nodes, edgeList), files, truncated)
    }

    private fun step(raw: RawStep, id: (PsiElement?) -> Int?) = ChangeStep(
        raw.line, raw.text, raw.exit, raw.conditions, id(raw.target), raw.target?.let(::qualifiedName), raw.changed,
    )

    private fun analyzeFile(file: ReviewFile, pending: MutableList<Pending>): ChangeFile {
        val before = file.before
        val after = file.after
        if (before == null || after == null) return ChangeFile(file.path, file.status, "Content unavailable (binary, unreadable or too large)")
        val type = FileTypeManager.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/')) as? LanguageFileType
            ?: return ChangeFile(file.path, file.status, "Not source code")
        val live = if (file.status == "deleted" || after.isEmpty()) null else
            LocalFileSystem.getInstance().findFileByPath(file.absolutePath)?.let { PsiManager.getInstance(project).findFile(it) }
                ?.takeIf { it.text == after && CallGraphAnalyzer.isInProject(project, it) }
        val afterPsi = live ?: after.takeIf { it.isNotEmpty() }?.let { parse(file.path, type, it) }
        val beforePsi = before.takeIf { it.isNotEmpty() }?.let { parse(file.path, type, it) }
        if (listOfNotNull(afterPsi, beforePsi).none { psi -> supports.any { it.supportsFile(psi) } })
            return ChangeFile(file.path, file.status, "No code analysis for this language")
        val beforeDeclarations = beforePsi?.let(::declarations).orEmpty()
        val afterDeclarations = afterPsi?.let(::declarations).orEmpty()
        val changes = DiffService.lineChanges(before, after)
        val lines = ChangedLines(changes)
        val beforeLines = sourceLines(before)
        val afterLines = sourceLines(after)
        val test = isTest(live) || TEST_PATH.containsMatchIn(file.path)
        val described = mutableListOf<String>()
        for (kind in listOf(SymbolKind.CLASS, SymbolKind.PROPERTY)) {
            for ((old, new) in matchDeclarations(beforeDeclarations.filter { it.second.kind == kind }.map { it.second },
                afterDeclarations.filter { it.second.kind == kind }.map { it.second })) {
                val (status, trivial, _) = classify(old, new) ?: continue
                // A class's own text changes whenever its members do; only its arrival and removal are worth a line.
                if (kind == SymbolKind.CLASS && status == ChangeStatus.MODIFIED) continue
                if (trivial != null) continue
                described += "${status.id} ${kind.name.lowercase()} ${(new ?: old)!!.qualifiedName}"
            }
        }
        val elements = afterDeclarations.associate { it.second to it.first }
        val index = LineIndex(after)
        for ((old, new) in matchDeclarations(beforeDeclarations.filter { it.second.kind == SymbolKind.FUNCTION }.map { it.second },
            afterDeclarations.filter { it.second.kind == SymbolKind.FUNCTION }.map { it.second })) {
            val (status, trivial, signature) = classify(old, new) ?: continue
            pending += Pending(file, old, new, status, trivial, signature,
                lines.count(lines.after, new?.lines, new?.nested.orEmpty()), lines.count(lines.before, old?.lines, old?.nested.orEmpty()),
                test, new?.let { elements[it] }?.takeIf { live != null },
                declarationHunk(beforeLines, afterLines, changes, old?.lines, new?.lines), lines.after, index)
        }
        // Lines outside every function: imports, fields, class headers and top-level statements.
        fun outside(changed: Set<Int>, declarations: List<Pair<PsiElement, SideDeclaration>>): Int {
            val functions = declarations.filter { it.second.kind == SymbolKind.FUNCTION }.map { it.second.lines }
            return changed.count { line -> functions.none { line + 1 in it } }
        }
        return ChangeFile(file.path, file.status, null, live != null,
            outside(lines.after, afterDeclarations), outside(lines.before, beforeDeclarations), described.take(20))
    }

    private fun parse(path: String, type: LanguageFileType, text: String): PsiFile? =
        PsiFileFactory.getInstance(project).createFileFromText(path.substringAfterLast('/'), type, text)

    /** Named functions, classes and properties of one parsed side, in source order. */
    private fun declarations(file: PsiFile): List<Pair<PsiElement, SideDeclaration>> {
        val index = LineIndex(file.text)
        val kinds = setOf(SymbolKind.FUNCTION, SymbolKind.CLASS, SymbolKind.PROPERTY)
        return ProjectGraphAnalyzer.declarations(file).mapNotNull { element ->
            ProgressManager.checkCanceled()
            val kind = ProjectGraphAnalyzer.kind(element)?.takeIf { it in kinds } ?: return@mapNotNull null
            if (element is PsiFile) return@mapNotNull null
            val name = (element as? PsiNamedElement)?.name ?: return@mapNotNull null
            val start = element.textRange.startOffset
            val nested = nestedDeclarations(element)
            val own = without(element, nested.map { it.textRange })
            val comments = PsiTreeUtil.collectElementsOfType(element, PsiComment::class.java)
                .filter { comment -> nested.none { PsiTreeUtil.isAncestor(it, comment, false) } }.map { it.textRange }
            val code = without(element, nested.map { it.textRange } + comments)
            element to SideDeclaration(name, qualifiedName(element), kind, declarationHeader(code), own,
                tokens(element, nested, comments = false).joinToString("") { "${it.length}:$it" },
                index.line(start)..index.line((element.textRange.endOffset - 1).coerceAtLeast(start)), element.textOffset,
                nested.map { index.line(it.textRange.startOffset)..index.line((it.textRange.endOffset - 1).coerceAtLeast(it.textRange.startOffset)) },
                formattingTokens = tokens(element, nested, comments = true))
        }
    }

    /** Compare leaves rather than source strings: whitespace inside a literal is behavior. */
    private fun tokens(element: PsiElement, nested: List<PsiElement>, comments: Boolean): List<String> {
        val result = mutableListOf<String>()
        element.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(child: PsiElement) {
                if (child in nested) return
                if (child is PsiComment) {
                    if (comments) result += child.text
                    return
                }
                // Only Java is whitespace insensitive here. Other languages retain whitespace
                // conservatively because indentation and newlines can change execution.
                if (child is PsiWhiteSpace && element.language.id == "JAVA") return
                if (child.firstChild == null) result += child.text
                else super.visitElement(child)
            }
        })
        return result
    }

    private fun header(element: PsiElement): String =
        declarationHeader(without(element, nestedDeclarations(element).map { it.textRange } +
            PsiTreeUtil.collectElementsOfType(element, PsiComment::class.java).map { it.textRange }))

    /** Outermost functions and classes inside [element]. */
    private fun nestedDeclarations(element: PsiElement): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        element.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(child: PsiElement) {
                if (child !== element && ProjectGraphAnalyzer.kind(child).let { it == SymbolKind.FUNCTION || it == SymbolKind.CLASS } &&
                    child.textRange.length > 0) { result += child; return }
                super.visitElement(child)
            }
        })
        return result
    }

    private fun without(element: PsiElement, ranges: List<com.intellij.openapi.util.TextRange>): String {
        val text = element.text
        val start = element.textRange.startOffset
        val builder = StringBuilder()
        var position = 0
        for (range in ranges.sortedBy { it.startOffset }) {
            val from = (range.startOffset - start).coerceIn(position, text.length)
            builder.append(text, position, from)
            position = (range.endOffset - start).coerceIn(from, text.length)
        }
        return builder.append(text, position, text.length).toString()
    }

    /** Owners that are functions or classes, then the name: `Outer.Inner.method`. */
    private fun qualifiedName(element: PsiElement): String {
        val owners = generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }
            .filter { ProjectGraphAnalyzer.kind(it).let { kind -> kind == SymbolKind.CLASS || kind == SymbolKind.FUNCTION } }
            .mapNotNull { (it as? PsiNamedElement)?.name }.toList().asReversed()
        return (owners + ((element as? PsiNamedElement)?.name ?: "?")).joinToString(".")
    }

    /** In-project calls and certain exits of [function] in source order; calls inside nested functions and classes are theirs. */
    private fun walk(function: PsiElement, index: LineIndex, changed: Set<Int>): Walked {
        val steps = mutableListOf<RawStep>()
        function.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is PsiComment) return
                if (element !== function && ProjectGraphAnalyzer.kind(element).let { it == SymbolKind.FUNCTION || it == SymbolKind.CLASS }) return
                if (steps.size > MAX_STEPS) return
                val line = index.line(element.textRange.startOffset)
                val target = supports.firstNotNullOfOrNull { it.resolveCall(element) }?.let(ProjectGraphAnalyzer::source)
                    ?.takeIf { ProjectGraphAnalyzer.kind(it) == SymbolKind.FUNCTION && CallGraphAnalyzer.isInProject(project, it) }
                if (target != null) {
                    steps += RawStep(line, clip(element.text), false, conditions(CallSiteAnalyzer.frames(element, function)), target, (line - 1) in changed)
                } else CallSiteAnalyzer.exit(element, function)?.let { exit ->
                    steps += RawStep(line, exit.text, true, conditions(exit.frames), null, (line - 1) in changed)
                }
                super.visitElement(element)
            }
        })
        return Walked(steps.take(MAX_STEPS), steps.size > MAX_STEPS)
    }

    private fun conditions(frames: List<ControlFrame>): List<String> = frames.map { frame ->
        when (frame.kind) {
            ControlKind.BRANCH -> if (frame.otherwise) "else" else "if ${frame.label}"
            ControlKind.CASE -> if (frame.otherwise) "else (${frame.subject ?: "default"})" else listOfNotNull(frame.subject, frame.label).joinToString(" → ")
            ControlKind.LOOP -> frame.label
            ControlKind.ERROR -> "catch ${frame.label}"
        }
    }

    /** Unchanged functions referencing [element], excluding graph nodes, whose calls are edges already. */
    private fun callers(element: PsiElement, inGraph: (Pair<String, Int>) -> Boolean): Pair<List<ChangeCaller>, Boolean> {
        val found = CallGraphAnalyzer.callers(element, MAX_CALLERS + 1)
        val callers = found.asSequence().filter { caller -> key(caller)?.let(inGraph) != true }.take(MAX_CALLERS).map { caller ->
            val file = caller.containingFile
            val absolute = file?.virtualFile?.path
            ChangeCaller(qualifiedName(caller), absolute?.let(::relative) ?: "?", LineIndex(file?.text.orEmpty()).line(caller.textOffset),
                isTest(file), absolute, ProjectGraphAnalyzer.source(caller).textOffset)
        }.toList()
        return callers to (found.size > MAX_CALLERS)
    }

    private fun key(element: PsiElement): Pair<String, Int>? {
        val source = ProjectGraphAnalyzer.source(element)
        return source.containingFile?.virtualFile?.path?.let { it to source.textOffset }
    }

    private fun isTest(file: PsiFile?): Boolean {
        val virtual = file?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInTestSourceContent(virtual) || TEST_PATH.containsMatchIn(relative(virtual.path))
    }

    private fun relative(path: String): String {
        snapshot.files.firstOrNull { it.absolutePath == path }?.let { return it.path }
        return if (base != null && Path.of(path).startsWith(Path.of(base))) Path.of(base).relativize(Path.of(path)).toString().replace('\\', '/') else path
    }

    private fun clip(text: String): String = text.trim().replace(Regex("\\s+"), " ").let { if (it.length <= 120) it else it.take(119) + "…" }

    /** One-based line numbers of offsets in a text. */
    internal class LineIndex(text: CharSequence) {
        private val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
        fun line(offset: Int): Int {
            val found = starts.binarySearch(offset)
            return (if (found >= 0) found else -found - 2) + 1
        }
    }

    companion object {
        const val MAX_CHANGED = 400
        const val MAX_STEPS = 80
        const val MAX_CALLERS = 6
        const val MAX_CALLER_SEARCHES = 150
        const val MAX_BRIDGE_CANDIDATES = 200
        /** Test sources by convention when the project model does not mark them, as for historical revisions. */
        val TEST_PATH = Regex("(^|/)(test|tests|__tests__|spec)/|(Test|Tests|Spec|IT)\\.\\w+$|(^|/)test_[^/]+\\.py$|_test\\.(go|py)$|\\.(test|spec)\\.[jt]sx?$")
    }
}
