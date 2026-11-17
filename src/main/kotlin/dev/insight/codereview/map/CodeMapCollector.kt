package dev.insight.codereview.map

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiWhiteSpace
import dev.insight.agent.findSourceFile
import dev.insight.callgraph.CallGraphAnalyzer
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.callgraph.navigationReferences
import dev.insight.callgraph.resolveTargets
import dev.insight.codereview.CodeFile
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import dev.insight.review.reviewFingerprint
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

private val LOG = Logger.getInstance("dev.insight.codereview.map.CodeMapCollector")

/** Units of a scope and the references between them, read from PSI. [analyzed] of [files] were read; the rest were over the limit. */
internal data class CodeMapFacts(val units: List<CodeUnit>, val references: List<CodeReference>, val files: Int, val analyzed: Int)

/** Files read beyond this many are left out of the map; the map says so. */
internal const val CODE_MAP_MAX_FILES = 3_000
/** Resolutions per file; a huge generated file would otherwise dominate the run. */
private const val MAX_RESOLVED = 5_000
/** Larger files are mapped as whole files without code analysis. */
private const val MAX_ANALYZED_CHARS = 400_000

/**
 * Reads units from the project's PSI of every file in [files], up to [maxFiles], and resolves references between them with
 * the call-graph language supports. Documentation is matched by the names it mentions. Runs in a read action; checks for
 * cancellation.
 */
internal fun collectCodeMapFacts(project: Project, files: List<CodeFile>, maxFiles: Int = CODE_MAP_MAX_FILES): CodeMapFacts {
    val read = files.take(maxFiles)
    val sliced = read.map { ProgressManager.checkCanceled(); sliceUnits(project, it) }
    val units = sliced.flatMap { it.units }
    val resolver = OwnerResolver(sliced)
    val found = LinkedHashMap<Key, Int>()
    for (file in sliced) {
        val psi = file.psi ?: continue
        try {
            walk(file, psi, resolver) { from, to, kind -> if (to != null && to.unit != from.unit) found.merge(Key(from, to, kind), 1, Int::plus) }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            if (e is ControlFlowException || e is CancellationException) throw e
            LOG.warn("Code map: reference resolution failed in ${file.file.path}", e)
        }
    }
    val references = found.map { (key, count) -> CodeReference(key.from.unit, key.to.unit, key.kind, key.from.member, key.to.member, count) }
    val docs = sliced.filter { it.units.singleOrNull()?.evidenceKind == "documentation" }.map { it.units.single() to it.file.text }
    return CodeMapFacts(units, references + documentationMentions(docs, units), files.size, read.size)
}

/** The clustered code map of [files] from code alone. Runs in a read action; checks for cancellation. */
internal fun codeMap(project: Project, files: List<CodeFile>, options: CodeMapOptions = CodeMapOptions()): CodeMap {
    val facts = collectCodeMapFacts(project, files)
    return clusterCode(relateCode(facts.units, facts.references, options), facts.files, facts.analyzed)
}

/** Which unit, and which member of it, owns a piece of code. */
private data class Owner(val unit: String, val member: String?)

private data class Key(val from: Owner, val to: Owner, val kind: CodeReferenceKind)

/**
 * One file cut into units. [owners] maps every declaration of [psi] to the unit and member that own its code; code outside
 * them belongs to [loose], the file's top-level unit, when it has one.
 */
private class SlicedFile(val file: CodeFile, val units: List<CodeUnit>, val psi: PsiFile?, val owners: IdentityHashMap<PsiElement, Owner>, val loose: Owner?)

private fun wholeFile(file: CodeFile) = SlicedFile(file, listOf(CodeUnit(file.path, file.path, file.path.substringAfterLast('/'), UnitKind.FILE, null, "",
    emptyList(), file.lineCount, codeEvidenceKind(file.path), reviewFingerprint("unit", file.fingerprint))), null, IdentityHashMap(), null)

private val UNIT_KINDS = setOf(SymbolKind.CLASS, SymbolKind.FUNCTION, SymbolKind.PROPERTY)

private fun sliceUnits(project: Project, file: CodeFile): SlicedFile {
    if (codeEvidenceKind(file.path) == "documentation" || file.text.length > MAX_ANALYZED_CHARS) return wholeFile(file)
    return try {
        val virtual = findSourceFile(project, file.path, file.absolutePath) ?: return wholeFile(file)
        val psi = PsiManager.getInstance(project).findFile(virtual) ?: return wholeFile(file)
        if (CallGraphLanguageSupport.EP_NAME.extensionList.none { it.supportsFile(psi) } || !CallGraphAnalyzer.isInProject(project, psi)) return wholeFile(file)
        slice(file, psi)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        if (e is ControlFlowException || e is CancellationException) throw e
        LOG.warn("Code map: could not read ${file.path}", e)
        wholeFile(file)
    }
}

private fun slice(file: CodeFile, psi: PsiFile): SlicedFile {
    val lines = LineStarts(psi.text)
    val declarations = ProjectGraphAnalyzer.declarations(psi).filter { element ->
        element !is PsiFile && element is PsiNamedElement && element.name != null && ProjectGraphAnalyzer.kind(element) in UNIT_KINDS
    }
    val known = IdentityHashMap<PsiElement, Boolean>().apply { declarations.forEach { put(it, true) } }
    fun declarationParent(element: PsiElement): PsiElement? = generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }.firstOrNull { known.containsKey(it) }
    fun range(element: PsiElement): IntRange {
        val start = element.textRange.startOffset
        return lines.line(start)..lines.line((element.textRange.endOffset - 1).coerceAtLeast(start))
    }
    val top = declarations.filter { declarationParent(it) == null }
    val classes = top.filter { ProjectGraphAnalyzer.kind(it) == SymbolKind.CLASS }
    val topLevel = top.filter { ProjectGraphAnalyzer.kind(it) != SymbolKind.CLASS }
    val looseId = "${file.path}#<top-level>"
    val owners = IdentityHashMap<PsiElement, Owner>()
    val units = mutableListOf<CodeUnit>()
    val usedIds = HashSet<String>()
    fun unique(id: String): String = if (usedIds.add(id)) id else generateSequence(2) { it + 1 }.map { "$id~$it" }.first { usedIds.add(it) }
    // Every declaration is owned by its outermost declaration's unit, and by the member directly beneath that unit.
    val members = HashMap<PsiElement, MutableList<CodeMember>>()
    for (element in declarations) {
        val chain = generateSequence(element) { declarationParent(it) }.toList().asReversed()
        val root = chain.first()
        val memberElement = if (ProjectGraphAnalyzer.kind(root) == SymbolKind.CLASS) chain.getOrNull(1) else root
        val unitKey = if (ProjectGraphAnalyzer.kind(root) == SymbolKind.CLASS) root else null
        if (memberElement === element) {
            val name = chain.mapNotNull { (it as? PsiNamedElement)?.name }.joinToString(".")
            members.getOrPut(unitKey ?: psi) { mutableListOf() } += CodeMember("${file.path}#$name", name, kindName(element), range(element), header(element))
        }
        owners[element] = Owner(unitKey?.let { "pending" } ?: looseId, memberElement?.let { "${file.path}#" + memberName(it, declarationParent(it)) })
    }
    val unitIds = IdentityHashMap<PsiElement, String>()
    for (element in classes) {
        val name = (element as PsiNamedElement).name!!
        val id = unique("${file.path}#$name")
        unitIds[element] = id
        val lines = range(element)
        units += CodeUnit(id, file.path, name, UnitKind.CLASS, lines, header(element), members[element].orEmpty(), lines.count(),
            codeEvidenceKind(file.path), reviewFingerprint("unit", id, element.text))
    }
    val hasTopLevel = topLevel.isNotEmpty() || classes.isEmpty()
    if (hasTopLevel) {
        val ranges = topLevel.map(::range)
        val span = ranges.takeIf { it.isNotEmpty() }?.let { it.minOf { r -> r.first }..it.maxOf { r -> r.last } }
        usedIds += looseId
        units.add(0, CodeUnit(looseId, file.path, "top-level code", UnitKind.TOP_LEVEL, span.takeIf { classes.isNotEmpty() }, "",
            members[psi].orEmpty(), if (classes.isEmpty()) file.lineCount else ranges.sumOf { it.count() }, codeEvidenceKind(file.path),
            reviewFingerprint("unit", looseId, if (classes.isEmpty()) psi.text else topLevel.joinToString("\n") { it.text })))
    }
    // Owners waiting for their class's id, now that ids are unique.
    for (element in declarations) {
        val root = generateSequence(element) { declarationParent(it) }.last()
        unitIds[root]?.let { id -> owners[element] = owners.getValue(element).copy(unit = id) }
    }
    units.sortBy { it.lines?.first ?: 0 }
    return SlicedFile(file, units, psi, owners, if (hasTopLevel) Owner(looseId, null) else null)
}

private fun memberName(element: PsiElement, parent: PsiElement?): String =
    listOfNotNull((parent as? PsiNamedElement)?.name, (element as? PsiNamedElement)?.name).joinToString(".")

private fun kindName(element: PsiElement): String = when (ProjectGraphAnalyzer.kind(element)) {
    SymbolKind.CLASS -> "class"
    SymbolKind.PROPERTY -> "property"
    else -> "function"
}

/** The declaration's first line that is not an annotation or comment, cut at its body. */
internal fun header(element: PsiElement): String = declarationHeaderOf(element.text)

internal fun declarationHeaderOf(text: String): String {
    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("@") && !it.startsWith("//") && !it.startsWith("/*") && !it.startsWith("*") && !it.startsWith("#") }
        ?: return ""
    val cut = line.substringBefore('{').trimEnd().removeSuffix("=").trimEnd()
    return if (cut.length <= 160) cut else cut.take(159) + "…"
}

/** Walks one file, attributing each reference to the unit and member that own the referring code. */
private fun walk(sliced: SlicedFile, psi: PsiFile, resolver: OwnerResolver, add: (Owner, Owner?, CodeReferenceKind) -> Unit) {
    val supports = CallGraphLanguageSupport.EP_NAME.extensionList
    var resolved = 0
    val callees = java.util.Collections.newSetFromMap(IdentityHashMap<PsiElement, Boolean>())
    val owners = ArrayDeque<Pair<PsiElement?, Owner?>>().apply { add(null to sliced.loose) }
    val inherits = ArrayDeque<PsiElement>()

    fun resolve(element: PsiElement, owner: Owner) {
        val call = safely { supports.firstNotNullOfOrNull { it.callSite(element) } }
        if (call != null) {
            resolved++
            callees += call.callee
            val kind = if (inherits.isNotEmpty()) CodeReferenceKind.INHERITS else CodeReferenceKind.CALL
            for (target in call.targets) add(owner, resolver.owner(target), kind)
        }
        if (callees.remove(element)) return
        val references = safely { element.navigationReferences() }.orEmpty()
        if (references.isEmpty()) return
        resolved += references.size
        for (reference in references) for (target in resolveTargets(reference)) {
            val kind = when {
                inherits.isNotEmpty() -> CodeReferenceKind.INHERITS
                safely { ProjectGraphAnalyzer.kind(ProjectGraphAnalyzer.source(target)) } == SymbolKind.CLASS -> CodeReferenceKind.TYPE
                else -> CodeReferenceKind.REFERENCE
            }
            add(owner, resolver.owner(target), kind)
        }
    }

    psi.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            ProgressManager.checkCanceled()
            if (element is PsiComment || element is PsiWhiteSpace) return
            val relation = if (element is PsiFile) null else ProjectGraphAnalyzer.relationKind(element)
            if (relation == RelationKind.IMPORT) return
            sliced.owners[element]?.let { owner ->
                owners += element to owner
                if (resolved < MAX_RESOLVED && CallGraphAnalyzer.isFunction(element)) {
                    resolved++
                    for (method in safely { supports.flatMap { it.superMethods(element) } }.orEmpty()) add(owner, resolver.owner(method), CodeReferenceKind.OVERRIDE)
                }
            }
            if (relation == RelationKind.INHERITS) inherits += element
            val owner = owners.last().second
            if (owner != null && resolved < MAX_RESOLVED) resolve(element, owner)
            super.visitElement(element)
        }

        override fun elementFinished(element: PsiElement) {
            if (owners.last().first === element) owners.removeLast()
            if (inherits.lastOrNull() === element) inherits.removeLast()
        }
    })
}

/** Maps resolved declarations to the unit and member owning them; declarations outside the scope have none. */
private class OwnerResolver(files: List<SlicedFile>) {
    private val byFile = IdentityHashMap<PsiFile, SlicedFile>().apply { files.forEach { file -> file.psi?.let { put(it, file) } } }

    fun owner(resolved: PsiElement): Owner? = safely {
        val target = ProjectGraphAnalyzer.source(resolved)
        val file = target.containingFile ?: return@safely null
        val sliced = byFile[file] ?: byFile[file.originalFile] ?: return@safely null
        var element: PsiElement? = target
        while (element != null && element !is PsiFile) {
            sliced.owners[element]?.let { return@safely it }
            element = element.parent
        }
        sliced.loose
    }
}

/**
 * Documentation that names a unit: its file name (`CodeMapModel.kt`) or, for a class, its name as a whole word when at
 * least six characters long and declared by a single unit. Pure.
 */
internal fun documentationMentions(docs: List<Pair<CodeUnit, String>>, units: List<CodeUnit>): List<CodeReference> {
    if (docs.isEmpty()) return emptyList()
    val code = units.filter { it.evidenceKind != "documentation" }
    val byFile = code.groupBy { it.path.substringAfterLast('/') }
    val classes = code.filter { it.kind == UnitKind.CLASS && it.simpleName.length >= 6 }.groupBy { it.simpleName }.filterValues { it.size == 1 }
    val result = mutableListOf<CodeReference>()
    for ((doc, text) in docs) {
        val words = WORD.findAll(text).map { it.value }.groupingBy { it }.eachCount()
        val targets = LinkedHashMap<String, Int>()
        for ((name, owners) in byFile) {
            val count = Regex("""(?<![\w.])${Regex.escape(name)}(?![\w])""").findAll(text).count()
            if (count > 0) {
                // A file name stands for its main unit: the class named like the file, else the first one.
                val main = owners.firstOrNull { it.simpleName == name.substringBefore('.') } ?: owners.first()
                targets.merge(main.id, count, Int::plus)
            }
        }
        // A class whose file is named already counts once per mention, not twice.
        for ((name, owners) in classes) if (owners.single().id !in targets) words[name]?.let { targets[owners.single().id] = it }
        targets.forEach { (id, count) -> result += CodeReference(doc.id, id, CodeReferenceKind.MENTION, count = count) }
    }
    return result
}

private val WORD = Regex("""[\p{L}_][\p{L}\p{N}_]*""")

/** 1-based line numbers of offsets in a text. */
private class LineStarts(text: CharSequence) {
    private val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
    fun line(offset: Int): Int {
        val found = starts.binarySearch(offset)
        return (if (found >= 0) found else -found - 2) + 1
    }
}

private inline fun <T> safely(block: () -> T): T? = try {
    block()
} catch (e: Throwable) {
    if (e is ControlFlowException || e is CancellationException || e is VirtualMachineError) throw e
    // Expected for constructs a language plugin cannot resolve; this runs per reference, so a warning would flood the log.
    LOG.debug("Code map reference resolution failed", e)
    null
}
