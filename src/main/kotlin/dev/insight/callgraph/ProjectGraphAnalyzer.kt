package dev.insight.callgraph

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*

/** Builds a complete snapshot of supported project source, including disconnected declarations. Read action only. */
object ProjectGraphAnalyzer {
    private val supports get() = CallGraphLanguageSupport.EP_NAME.extensionList

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

    private fun kind(element: PsiElement): SymbolKind? = if (element is PsiFile) SymbolKind.FILE
        else supports.firstNotNullOfOrNull { it.symbolKind(element) }

    private fun source(element: PsiElement): PsiElement =
        supports.firstNotNullOfOrNull { it.unwrap(element) } ?: element.navigationElement

    private fun key(element: PsiElement): Triple<String, Int, SymbolKind>? {
        val target = source(element)
        return target.containingFile?.virtualFile?.path?.let { Triple(it, target.textOffset, kind(target) ?: return null) }
    }

    /** References are attributed to the nearest declaration, including property initializers and local functions. */
    fun relations(file: PsiFile, emit: (PsiElement, PsiElement, RelationKind) -> Unit) {
        fun owner(element: PsiElement?): PsiElement {
            var current = element
            while (current != null && current !is PsiFile) {
                if (kind(current) != null) return current
                current = current.parent
            }
            return file
        }
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                ProgressManager.checkCanceled()
                if (element is PsiComment) return
                if (element !== file && kind(element) != null) emit(owner(element.parent), element, RelationKind.CONTAINS)
                var context: PsiElement? = element
                var relation: RelationKind? = null
                while (context != null && context !== file && relation == null) {
                    relation = supports.firstNotNullOfOrNull { it.relationKind(context!!) }
                    context = context.parent
                }
                val from = if (relation == RelationKind.IMPORT) file else owner(element)
                val call = supports.firstNotNullOfOrNull { it.resolveCall(element) }
                if (call != null) emit(from, source(call), relation ?: RelationKind.CALL)
                for (reference in element.references) {
                    val target = reference.resolve() ?: continue
                    // Method references and calls carry CALL edges; other symbol uses carry REFERENCE edges.
                    emit(from, source(target), relation ?: if (call != null) RelationKind.CALL else RelationKind.REFERENCE)
                }
                super.visitElement(element)
            }
        })
    }

    fun build(project: Project): CallGraphResult {
        val manager = PsiManager.getInstance(project)
        val files = mutableListOf<PsiFile>()
        ProjectFileIndex.getInstance(project).iterateContent { virtualFile ->
            ProgressManager.checkCanceled()
            // Each language support decides which parsed files it covers; binary files are never parsed.
            if (!virtualFile.isDirectory && !virtualFile.fileType.isBinary) {
                manager.findFile(virtualFile)?.takeIf { file -> supports.any { it.supportsFile(file) } }?.let(files::add)
            }
            true
        }
        val elements = files.sortedBy { it.virtualFile.path }.flatMap(::declarations)
        val ids = elements.mapIndexedNotNull { id, element -> key(element)?.let { it to id } }.toMap()
        val filesByDirectory = files.groupBy({ it.virtualFile.parent?.path }) { key(it)?.let(ids::get) }
            .mapValues { (_, fileIds) -> fileIds.filterNotNull() }
        val edges = linkedSetOf<CallGraphEdge>()
        fun targetId(element: PsiElement): Int? {
            val target = source(element)
            key(target)?.let(ids::get)?.let { return it }
            // Implicit constructors may have no declaration node; use their source class.
            var parent = target.parent
            while (parent != null && parent !is PsiFile) {
                key(parent)?.let(ids::get)?.let { return it }
                parent = parent.parent
            }
            return null
        }
        files.forEach { file ->
            relations(file) { from, to, relation ->
                val fromId = key(from)?.let(ids::get)
                // Package imports (for example Go) resolve to a directory: link every graph file directly inside it.
                val toIds = if (to is PsiDirectory) filesByDirectory[to.virtualFile.path].orEmpty() else listOfNotNull(targetId(to))
                if (fromId != null) toIds.forEach { edges += CallGraphEdge(fromId, it, relation) }
            }
        }
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
            // Some languages present files as module names (Python shows `util` for util.py); always show the file name.
            if (element is PsiFile) node.copy(name = element.name) else node
        }
        return CallGraphResult(CallGraph(nodes, edges, false, projectGraph = true),
            elements.map { pointers.createSmartPsiElementPointer(it) })
    }
}
