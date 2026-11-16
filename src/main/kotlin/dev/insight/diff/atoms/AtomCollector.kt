package dev.insight.diff.atoms

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import dev.insight.callgraph.CallGraphAnalyzer
import dev.insight.callgraph.CallGraphLanguageSupport
import dev.insight.diff.ReviewFile
import dev.insight.diff.SemanticMetrics
import dev.insight.diff.sideDeclarations
import dev.insight.graph.SymbolKind
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

internal val ATOM_LOG = Logger.getInstance("dev.insight.diff.atoms.AtomCollector")

/** Atoms of a snapshot and the references between them, read from PSI. */
internal data class AtomFacts(val atoms: List<ChangeAtom>, val references: List<AtomReference>)

/** Parses both sides of every file, slices atoms and resolves references between them. Runs in a read action; checks for cancellation. */
internal fun collectAtomFacts(project: Project, files: List<ReviewFile>): AtomFacts {
    val sliced = files.map { ProgressManager.checkCanceled(); sliceFile(project, it) }
    val atoms = sliced.flatMap { it.atoms }
    val resolved = afterReferences(sliced.mapNotNull { it.after }, atoms.associateBy { it.id })
    return AtomFacts(atoms, resolved + removedNameReferences(atoms, files.associate { it.path to it.patch }, resolved))
}

/** The clustered change map of a snapshot from code alone. Runs in a read action; checks for cancellation. */
internal fun changeMap(project: Project, files: List<ReviewFile>, options: ClusterOptions = ClusterOptions()): ChangeMap {
    val facts = collectAtomFacts(project, files)
    return clusterChanges(relate(facts.atoms, facts.references, dev.insight.diff.codeMoves(files), files.associate { it.path to it.patch }, options), options)
}

/**
 * The parsed after side of a file with code analysis. [owners] holds every declaration of [psi] with the atom that owns
 * its code: its own, the outermost wholly added declaration around it (which absorbs it), or null for unchanged code.
 * [live] tells that [psi] is the project's own file, so resolution lands in it; otherwise it is a copy of [file]'s text.
 */
internal class AfterSide(
    val file: ReviewFile, val psi: PsiFile, val live: Boolean, val owners: IdentityHashMap<PsiElement, String?>, val loose: String?,
    /** 1-based after lines of each atom's `+` rows, which make a reference there a changed site. */
    val added: Map<String, Set<Int>>,
)

private class SlicedFile(val atoms: List<ChangeAtom>, val after: AfterSide?)

/** Atoms of one file; the same guards as [SemanticMetrics.file] decide whether it gets code analysis or is one file atom. */
private fun sliceFile(project: Project, file: ReviewFile): SlicedFile {
    val whole = SlicedFile(sliceAtoms(file, null, null), null)
    val before = file.before ?: return whole
    val after = file.after ?: return whole
    if (before.length > SemanticMetrics.MAX_TEXT || after.length > SemanticMetrics.MAX_TEXT) return whole
    val type = FileTypeManager.getInstance().getFileTypeByFileName(file.path.substringAfterLast('/')) as? LanguageFileType ?: return whole
    return try {
        val live = if (file.status == "deleted" || after.isEmpty()) null else
            liveFile(project, file.absolutePath)?.takeIf { it.text == after && CallGraphAnalyzer.isInProject(project, it) }
        fun parse(text: String) = PsiFileFactory.getInstance(project).createFileFromText(file.path.substringAfterLast('/'), type, text)
        val afterPsi = live ?: after.takeIf { it.isNotEmpty() }?.let(::parse)
        val beforePsi = before.takeIf { it.isNotEmpty() }?.let(::parse)
        val supports = CallGraphLanguageSupport.EP_NAME.extensionList
        if (listOfNotNull(afterPsi, beforePsi).none { psi -> supports.any { it.supportsFile(psi) } }) return whole
        val afterFound = afterPsi?.let(::sideDeclarations).orEmpty()
        val atoms = sliceAtoms(file, beforePsi?.let(::sideDeclarations).orEmpty().map { it.second }, afterFound.map { it.second })
        SlicedFile(atoms, afterPsi?.let { afterSide(file, it, live != null, afterFound, atoms) })
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        if (e is ControlFlowException || e is CancellationException) throw e
        ATOM_LOG.warn("Change atoms: could not parse ${file.path}", e)
        whole
    }
}

private fun afterSide(file: ReviewFile, psi: PsiFile, live: Boolean, found: List<Pair<PsiElement, dev.insight.diff.SideDeclaration>>,
                      atoms: List<ChangeAtom>): AfterSide {
    val byId = atoms.associateBy { it.id }
    // Overloads share a name, so the lines tell them apart; equal keys are taken in source order, as sliceAtoms numbers them.
    val pending = atoms.filter { it.isDeclaration && it.afterLines != null }.groupBy { Triple<String, AtomKind?, IntRange?>(it.name, it.kind, it.afterLines) }
        .mapValues { ArrayDeque(it.value) }
    val owners = IdentityHashMap<PsiElement, String?>()
    for ((element, declaration) in found) {
        val own = pending[Triple<String, AtomKind?, IntRange?>(declaration.qualifiedName, atomKind(declaration.kind), declaration.lines)]?.removeFirstOrNull()?.id
        val parent = generateSequence(element.parent) { it.parent }.takeWhile { it !is PsiFile }.firstOrNull { owners.containsKey(it) }
        val absorbing = parent?.let { owners[it] }?.takeIf { byId[it]?.status == AtomStatus.ADDED }
        owners[element] = absorbing ?: own
    }
    val newLine = patchLineNumbers(file.patch).filter { it.marker == '+' }.associate { it.row to it.new!! }
    val added = atoms.associate { atom -> atom.id to atom.changedRows.mapNotNullTo(HashSet()) { newLine[it] } }
    return AfterSide(file, psi, live, owners, atoms.firstOrNull { it.kind == AtomKind.LOOSE }?.id, added)
}

private fun atomKind(kind: SymbolKind): AtomKind? = when (kind) {
    SymbolKind.FUNCTION -> AtomKind.FUNCTION
    SymbolKind.CLASS -> AtomKind.CLASS
    SymbolKind.PROPERTY -> AtomKind.PROPERTY
    else -> null
}

/**
 * The project's file at [path]: on the local file system, or on the file system of a content root (temporary files in
 * tests), so the after side can be the live PSI that resolution returns elements of.
 */
private fun liveFile(project: Project, path: String): PsiFile? {
    val local = LocalFileSystem.getInstance()
    // The temporary file system of tests is a LocalFileSystem subclass, so only the instance itself is already searched.
    val virtual = local.findFileByPath(path)
        ?: ProjectRootManager.getInstance(project).contentRoots.map { it.fileSystem }.distinct().filter { it !== local }
            .firstNotNullOfOrNull { it.findFileByPath(path) }
    return virtual?.takeIf { it.isValid && !it.isDirectory }?.let { PsiManager.getInstance(project).findFile(it) }
}

/**
 * Uses of declarations that no longer exist, which no resolution can find: an atom whose removed rows name a removed
 * declaration (or one whose signature changed) as a whole identifier dropped that use. Conservative because names are
 * not resolution: only names of at least four characters that a single atom of the snapshot declares count, and for a
 * changed signature only pairs that resolution on the after side did not already link. Pure.
 */
internal fun removedNameReferences(atoms: List<ChangeAtom>, patches: Map<String, String>, found: Collection<AtomReference> = emptyList()): List<AtomReference> {
    fun simple(atom: ChangeAtom) = atom.name.substringAfterLast('.')
    val counts = atoms.filter { it.isDeclaration }.groupingBy(::simple).eachCount()
    val targets = atoms.filter { atom ->
        atom.isDeclaration && (atom.status == AtomStatus.REMOVED || (atom.status == AtomStatus.MODIFIED && atom.signatureChanged)) &&
            simple(atom).length >= 4 && counts[simple(atom)] == 1
    }.associateBy(::simple)
    if (targets.isEmpty()) return emptyList()
    val linked = found.mapTo(HashSet()) { it.from to it.to }
    val lines = HashMap<String, List<String>>()
    val result = mutableListOf<AtomReference>()
    for (atom in atoms) {
        val patch = lines.getOrPut(atom.path) { patches[atom.path].orEmpty().removeSuffix("\n").split('\n') }
        val names = atom.changedRows.sorted().mapNotNull { patch.getOrNull(it - 1) }.filter { it.startsWith('-') }
            .flatMapTo(LinkedHashSet()) { line -> IDENTIFIER.findAll(line, 1).map { it.value } }
        for (name in names) {
            val target = targets[name]?.takeIf { it.id != atom.id } ?: continue
            if (target.status == AtomStatus.MODIFIED && (atom.id to target.id) in linked) continue
            result += AtomReference(atom.id, target.id, ReferenceKind.REFERENCE, siteChanged = true, before = true)
        }
    }
    return result
}

private val IDENTIFIER = Regex("""[\p{L}_][\p{L}\p{N}_$]*""")
