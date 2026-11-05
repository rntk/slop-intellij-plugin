package dev.insight.callgraph

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphResult
import dev.insight.graph.CallOccurrence
import dev.insight.graph.CallOperation
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind

/** Builds a complete snapshot of supported project source, including disconnected declarations. Read action only. */
object ProjectGraphAnalyzer {
    private val supports get() = CallGraphLanguageSupport.EP_NAME.extensionList

    /** Candidates kept for a call the user resolves by picking one; see [CallOccurrence.choices]. */
    private const val MAX_CHOICES = 40

    fun declarations(file: PsiFile): List<PsiElement> {
        if (supports.none { it.supportsFile(file) }) return emptyList()
        val elements = mutableListOf<PsiElement>(file)
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (kind(element) != null && element !is PsiFile) elements += element
                super.visitElement(element)
            }
        })
        return elements
    }

    internal fun kind(element: PsiElement): SymbolKind? = if (element is PsiFile) SymbolKind.FILE
        else supports.firstNotNullOfOrNull { it.symbolKind(element) }

    internal fun relationKind(element: PsiElement): RelationKind? = supports.firstNotNullOfOrNull { it.relationKind(element) }

    internal fun source(element: PsiElement): PsiElement =
        supports.firstNotNullOfOrNull { it.unwrap(element) } ?: element.navigationElement

    internal fun key(element: PsiElement): Triple<String, Int, SymbolKind>? {
        val target = source(element)
        return target.containingFile?.virtualFile?.path?.let { Triple(it, target.textOffset, kind(target) ?: return null) }
    }

    /** References are attributed to the nearest declaration, including property initializers and local functions. */
    fun relations(file: PsiFile,
        occurrence: (PsiElement, PsiElement, CallResolution) -> Unit = { _, _, _ -> },
        scope: PsiElement = file,
        foldFunctions: Boolean = false,
        emit: (PsiElement, PsiElement, RelationKind) -> Unit) =
        relations(file, occurrence, scope, foldFunctions, null, emit)

    internal fun relations(file: PsiFile,
        occurrence: (PsiElement, PsiElement, CallResolution) -> Unit = { _, _, _ -> },
        scope: PsiElement = file,
        foldFunctions: Boolean = false,
        lineRange: TextRange?,
        emit: (PsiElement, PsiElement, RelationKind) -> Unit) {
        fun owner(element: PsiElement?): PsiElement {
            var current = element
            while (current != null && current !is PsiFile) {
                if (kind(current) != null) return current
                current = current.parent
            }
            return file
        }
        scope.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is PsiComment) return
                if (lineRange != null &&
                    (element.textRange.endOffset <= lineRange.startOffset || element.textRange.startOffset >= lineRange.endOffset)) return
                if (foldFunctions && element !== scope && kind(element) == SymbolKind.FUNCTION) {
                    headlineReferences(element) { target -> emit(element, source(target), RelationKind.REFERENCE) }
                    return
                }
                val inRange = lineRange == null || element.textOffset in lineRange.startOffset..lineRange.endOffset
                if (inRange && element !== file && kind(element) != null) emit(owner(element.parent), element, RelationKind.CONTAINS)
                if (inRange) {
                    for (method in supports.flatMap { it.superMethods(element) }) {
                        emit(element, source(method), RelationKind.INHERITS)
                    }
                }
                var context: PsiElement? = element
                var relation: RelationKind? = null
                while (context != null && context !== file && relation == null) {
                    relation = supports.firstNotNullOfOrNull { it.relationKind(context) }
                    context = context.parent
                }
                val from = if (relation == RelationKind.IMPORT) file else owner(element)
                val call = if (lineRange == null || element.textRange.startOffset in lineRange.startOffset until lineRange.endOffset)
                    supports.firstNotNullOfOrNull { it.callSite(element) } else null
                if (call != null) {
                    occurrence(from, element, call)
                    val callKind = if (call.operation == CallOperation.INVOKE) RelationKind.CALL else RelationKind.REFERENCE
                    call.targets.forEach { emit(from, source(it), relation ?: callKind) }
                }
                if (inRange && relation == RelationKind.IMPORT && element !is PsiFile) {
                    supports.firstNotNullOfOrNull { it.unwrapImport(element) }?.let { imported ->
                        emit(from, source(imported), RelationKind.IMPORT)
                    }
                }
                for (reference in element.navigationReferences()) {
                    if (lineRange != null) {
                        val range = referenceRange(reference) ?: continue
                        if (range.startOffset < lineRange.startOffset || range.endOffset > lineRange.endOffset) continue
                    }
                    for (target in resolveTargets(reference)) emit(from, source(target), relation ?: RelationKind.REFERENCE)
                }
                super.visitElement(element)
            }
        })
    }

    /** Only the line displayed for a folded declaration; never resolve calls or visit hidden body lines. */
    private fun headlineReferences(declaration: PsiElement, emit: (PsiElement) -> Unit) {
        val document = PsiDocumentManager.getInstance(declaration.project).getDocument(declaration.containingFile) ?: return
        val line = document.getLineNumber(declaration.textOffset.coerceIn(0, document.textLength))
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        declaration.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is PsiComment || element.textRange.endOffset <= start || element.textRange.startOffset >= end) return
                for (reference in element.navigationReferences()) {
                    val range = referenceRange(reference) ?: continue
                    if (range.startOffset < start || range.endOffset > end) continue
                    resolveTargets(reference).filter {
                        kind(source(it)) in setOf(SymbolKind.CLASS, SymbolKind.PROPERTY, SymbolKind.SYMBOL)
                    }.forEach(emit)
                }
                super.visitElement(element)
            }
        })
    }

    fun build(project: Project, progress: (String) -> Unit = {}): CallGraphResult = buildFiles(project, null, progress = progress)

    /**
     * A file-local snapshot; target bodies are never resolved, and neither are functions folded inside [scope] unless
     * [foldFunctions] is false. Read action only.
     */
    internal fun buildScope(project: Project, scope: PsiElement, declarationsOnly: Boolean = false,
        foldFunctions: Boolean = true): CallGraphResult {
        val source = explorationSource(scope)
        val lineRange = if (!declarationsOnly && !foldFunctions && source !is PsiFile) shownLineRange(source) else null
        val analyzed = if (lineRange != null) shownContainer(source, lineRange) else source
        return buildFiles(project, listOf(scope.containingFile), analyzed, declarationsOnly, foldFunctions, lineRange)
    }

    private fun shownLineRange(element: PsiElement): TextRange? {
        val file = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val start = element.textRange.startOffset.coerceIn(0, document.textLength)
        val end = (element.textRange.endOffset - 1).coerceIn(start, document.textLength)
        val first = document.getLineNumber(start)
        val last = document.getLineNumber(end)
        return TextRange(document.getLineStartOffset(first), document.getLineEndOffset(last))
    }

    internal fun shownContainer(element: PsiElement, lineRange: TextRange): PsiElement {
        val file = element.containingFile ?: return element
        val chars = file.viewProvider.contents
        var start = lineRange.startOffset.coerceIn(0, chars.length)
        var end = lineRange.endOffset.coerceIn(start, chars.length)
        while (start < end && chars[start].isWhitespace()) start++
        while (end > start && chars[end - 1].isWhitespace()) end--
        var container = element
        while (container !is PsiFile && container.parent != null &&
            (container.textRange.startOffset > start || container.textRange.endOffset < end)) {
            container = container.parent
        }
        return container
    }

    private fun buildFiles(project: Project, selectedFiles: List<PsiFile>?, scope: PsiElement? = null,
        declarationsOnly: Boolean = false, foldFunctions: Boolean = true, lineRange: TextRange? = null,
        progress: (String) -> Unit = {}): CallGraphResult {
        progress("Finding supported project files")
        val manager = PsiManager.getInstance(project)
        val files = mutableListOf<PsiFile>()
        if (selectedFiles != null) files.addAll(selectedFiles.filter { file -> supports.any { it.supportsFile(file) } })
        else ProjectFileIndex.getInstance(project).iterateContent { virtualFile ->
            ProgressManager.checkCanceled()
            // Each language support decides which parsed files it covers; binary files are never parsed.
            if (!virtualFile.isDirectory && !virtualFile.fileType.isBinary) {
                manager.findFile(virtualFile)?.takeIf { file -> supports.any { it.supportsFile(file) } }?.let(files::add)
            }
            true
        }
        val elements = files.sortedBy { it.virtualFile.path }.flatMap { file ->
            progress("Reading declarations: ${file.virtualFile.path}")
            declarations(file)
        }.toMutableList()
        val ids = elements.mapIndexedNotNull { id, element -> key(element)?.let { it to id } }.toMap().toMutableMap()
        val filesByDirectory = files.groupBy({ it.virtualFile.parent?.path }) { key(it)?.let(ids::get) }
            .mapValues { (_, fileIds) -> fileIds.filterNotNull() }
        val edges = linkedSetOf<CallGraphEdge>()
        val occurrences = mutableListOf<CallOccurrence>()
        val unresolved = mutableMapOf<Int, String>()
        fun targetId(element: PsiElement, boundary: Boolean = false): Int? {
            val target = source(element)
            key(target)?.let(ids::get)?.let { return it }
            // Implicit constructors may have no declaration node; use their source class.
            if (!boundary && kind(target) == null) {
                var parent = target.parent
                while (parent != null && parent !is PsiFile) {
                    key(parent)?.let(ids::get)?.let { return it }
                    parent = parent.parent
                }
            }
            if (!boundary || !target.isValid) return null
            val path = target.containingFile?.virtualFile?.path ?: return null
            val targetKey = Triple(path, target.textOffset, kind(target) ?: SymbolKind.SYMBOL)
            ids[targetKey]?.let { return it }
            val id = elements.size
            elements += target
            ids[targetKey] = id
            // Retain the declaration's owners, without scanning sibling declarations or library bodies.
            if (target !is PsiFile) generateSequence(target.parent) { it.parent }.firstOrNull { kind(it) != null }?.let { parent ->
                targetId(parent, boundary = true)?.let { edges += CallGraphEdge(it, id, RelationKind.CONTAINS) }
            }
            return id
        }
        // Containment is available before any branch needs reference resolution.
        if (selectedFiles != null) elements.toList().forEach { element ->
            if (element !is PsiFile) {
                val parent = generateSequence(element.parent) { it.parent }.firstOrNull { kind(it) != null }
                val from = parent?.let(::key)?.let(ids::get)
                val to = key(element)?.let(ids::get)
                if (from != null && to != null) edges += CallGraphEdge(from, to, RelationKind.CONTAINS)
            }
        }
        if (!declarationsOnly) files.forEachIndexed { index, file ->
            progress("Resolving references (${index + 1}/${files.size}): ${file.virtualFile.path}")
            relations(file, occurrence = { from, site, resolution ->
                val owner = key(from)?.let(ids::get) ?: return@relations
                val scopeElement = generateSequence(site.parent) { it.parent }.firstOrNull {
                    it is PsiFile || kind(it) in setOf(SymbolKind.FUNCTION, SymbolKind.CLASS)
                } ?: file
                val scope = key(scopeElement)?.let(ids::get) ?: owner
                val targets = resolution.targets.mapNotNull { targetId(it, boundary = true) }.distinct()
                fun bindings(expression: PsiElement?) = expression?.navigationReferences().orEmpty()
                    .flatMap(::resolveTargets).mapNotNull { targetId(it, boundary = true) }.distinct()
                val receivers = bindings(resolution.receiver)
                val bindings = bindings(resolution.callee)
                // A call through a callable value (parameter, property, variable) continues at that binding, like Go to Declaration.
                val held = if (targets.isEmpty()) bindings.filter { elements[it] !is PsiFile } else emptyList()
                // Too generic to link, but the user may pick one; project declarations first, bounded so names like `get` stay cheap.
                val choices = if (targets.isEmpty()) resolution.choices.sortedByDescending { CallGraphAnalyzer.isInProject(project, it) }
                    .take(MAX_CHOICES).mapNotNull { targetId(it, boundary = true) }.distinct() else emptyList()
                val missing = if (targets.isEmpty() && held.isEmpty()) elements.size.also { id ->
                    elements += site
                    unresolved[id] = resolution.callee.text.take(200)
                    edges += CallGraphEdge(owner, id, RelationKind.CONTAINS)
                } else null
                val record = CallOccurrence(owner, scope, file.virtualFile.path, site.textRange.startOffset,
                    site.textRange.endOffset, resolution.callee.text.take(200), site.text.take(600), resolution.operation,
                    targets, missing, resolution.receiver?.text?.take(200), receivers, bindings,
                    CallSiteAnalyzer.frames(site, scopeElement), resolution.receiverType,
                    if (targets.isEmpty() && resolution.targets.isNotEmpty())
                        "Implementation unknown: the IDE returned candidates without navigable source declarations." else null,
                    resolution.byName, held, choices)
                occurrences += record
                val relation = if (resolution.operation == CallOperation.INVOKE) RelationKind.CALL else RelationKind.REFERENCE
                record.displayTargets.forEach { edges += CallGraphEdge(owner, it, relation) }
            }, scope = scope ?: file, foldFunctions = selectedFiles != null && foldFunctions, lineRange = lineRange) { from, to, relation ->
                val fromId = key(from)?.let(ids::get)
                // Package imports (for example Go) resolve to a directory: link every graph file directly inside it.
                val toIds = if (to is PsiDirectory) filesByDirectory[to.virtualFile.path].orEmpty()
                    else listOfNotNull(targetId(to, boundary = selectedFiles != null))
                if (fromId != null) toIds.forEach { edges += CallGraphEdge(fromId, it, relation) }
            }
        }
        progress("Finalizing graph: ${elements.size} symbols")
        val calls = edges.filter { it.kind == RelationKind.CALL }.mapTo(hashSetOf()) { it.caller to it.callee }
        edges.removeIf { it.kind == RelationKind.REFERENCE && (it.caller to it.callee) in calls }
        val pointers = SmartPointerManager.getInstance(project)
        val nodes = elements.mapIndexed { id, element ->
            val type = kind(element) ?: SymbolKind.SYMBOL
            var level = 0
            var parent = element.parent
            while (parent != null) {
                if (kind(parent) != null) level++
                parent = parent.parent
            }
            val node = CallGraphAnalyzer.describe(project, element, id, level).copy(kind = type, filePath = element.containingFile?.virtualFile?.path)
            unresolved[id]?.let { return@mapIndexed node.copy(name = it, kind = SymbolKind.SYMBOL, inProject = false, unresolved = true) }
            // Some languages present files as module names (Python shows `util` for util.py); always show the file name.
            if (element is PsiFile) node.copy(name = element.name)
            else if (!node.inProject) node.copy(signature = element.text.take(400).substringBefore('\n').trim()) else node
        }
        return CallGraphResult(CallGraph(nodes, edges, false, projectGraph = true),
            elements.map { pointers.createSmartPsiElementPointer(it) }, occurrences)
    }
}
