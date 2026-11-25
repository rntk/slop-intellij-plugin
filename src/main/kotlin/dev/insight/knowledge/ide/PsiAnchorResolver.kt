package dev.insight.knowledge.ide

import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.insight.callgraph.CallGraphAnalyzer
import dev.insight.callgraph.ProjectGraphAnalyzer
import dev.insight.core.Hashing
import dev.insight.graph.SymbolKind
import dev.insight.knowledge.Anchor
import dev.insight.knowledge.AnchorKind
import dev.insight.knowledge.AnchorResolver
import dev.insight.knowledge.AnchorState

/**
 * Resolves anchors with PSI and the VFS, and fingerprints what they point at. Safe to call from any dispatcher: every read is
 * a (smart) read action, so the blocks are re-run if a write intervenes.
 *
 * Fingerprints are structural, so edits that change no meaning do not flag drift:
 *  - FILE: hash of the sorted declaration signatures (nested types fold in their members), ignoring comments, formatting and
 *    function bodies. A file without declarations (Markdown, XML, ...) hashes its whitespace-normalized text.
 *  - DIRECTORY: hash of the sorted direct entries, each as its name plus, for a file, its FILE fingerprint (at most
 *    [MAX_DIRECTORY_ENTRIES] entries; subdirectories contribute their names only).
 *  - SYMBOL / ENTRYPOINT: hash of the declaration's signature and, for a function or property, its body tokens; for a class,
 *    of its signature and its sorted member signatures. Comments, doc comments and formatting are ignored.
 *  - CALL_EDGE: hash of both endpoints' signatures, present while the caller still calls the callee.
 *
 * Symbol refs are matched by qualifier (see [SymbolRef]). A declaration with the right name and owner types but another
 * package, or another file than a path-pinned ref names, resolves as [AnchorState.Moved]; the best-ranked candidate wins an
 * ambiguous ref. CALL_EDGE endpoints must be functions; calls to constructors are not edges.
 */
class PsiAnchorResolver(private val project: Project) : AnchorResolver {
    override suspend fun resolve(anchor: Anchor): AnchorState = when (anchor.kind) {
        AnchorKind.FILE -> readAction { resolveFileNow(anchor.ref) }
        AnchorKind.DIRECTORY -> readAction { resolveDirectoryNow(anchor.ref) }
        AnchorKind.SYMBOL, AnchorKind.ENTRYPOINT -> smartReadAction(project) { resolveSymbolNow(anchor.ref) }
        AnchorKind.CALL_EDGE -> smartReadAction(project) { resolveCallEdgeNow(anchor.ref) }
    }

    internal fun resolveFileNow(ref: String): AnchorState {
        val file = ProjectPaths.find(project, ref)?.takeIf { !it.isDirectory } ?: return AnchorState.Gone
        return AnchorState.Present(fileFingerprint(file))
    }

    internal fun resolveDirectoryNow(ref: String): AnchorState {
        val directory = ProjectPaths.find(project, ref)?.takeIf { it.isDirectory } ?: return AnchorState.Gone
        val entries = directory.children.filter { ProjectPaths.contains(project, it) }.sortedBy { it.name }.take(MAX_DIRECTORY_ENTRIES).map { child ->
            when {
                child.isDirectory -> child.name + "/"
                else -> child.name + ":" + fileFingerprint(child)
            }
        }
        return AnchorState.Present(Hashing.sha256("dir", *entries.toTypedArray()))
    }

    internal fun resolveSymbolNow(ref: String): AnchorState {
        val parsed = SymbolRef.parse(ref) ?: return AnchorState.Gone
        val best = SymbolLookup.find(project, parsed).firstOrNull() ?: return AnchorState.Gone
        val fingerprint = symbolFingerprint(best.element, best.kind)
        if (best.match == RefMatch.EXACT) return AnchorState.Present(fingerprint)
        return AnchorState.Moved(if (parsed.path != null) best.pinnedRef() else best.canonicalRef(), fingerprint)
    }

    internal fun resolveCallEdgeNow(ref: String): AnchorState {
        val edge = CallEdgeRef.parse(ref) ?: return AnchorState.Gone
        val callers = functions(edge.caller)
        val callees = functions(edge.callee)
        if (callers.isEmpty() || callees.isEmpty()) return AnchorState.Gone
        val calleeKeys = callees.associateBy { ProjectGraphAnalyzer.key(it.element) }
        for (caller in callers) {
            val target = CallGraphAnalyzer.callees(caller.element, includeLibraries = false)
                .firstNotNullOfOrNull { calleeKeys[ProjectGraphAnalyzer.key(it)] } ?: continue
            val fingerprint = Hashing.sha256("edge", Declarations.shape(caller.element, false).signatureKey, Declarations.shape(target.element, false).signatureKey)
            return AnchorState.Present(fingerprint)
        }
        return AnchorState.Gone
    }

    private fun functions(ref: String): List<FoundSymbol> {
        val parsed = SymbolRef.parse(ref) ?: return emptyList()
        return SymbolLookup.find(project, parsed).filter { it.kind == SymbolKind.FUNCTION && CallGraphAnalyzer.isFunction(ProjectGraphAnalyzer.source(it.element)) }
    }

    private fun fileFingerprint(file: VirtualFile): String {
        if (file.length > MAX_FINGERPRINTED_BYTES || file.fileType.isBinary) return Hashing.sha256("file-size", file.length.toString())
        val psi = PsiManager.getInstance(project).findFile(file)
        return if (psi != null) psiFileFingerprint(psi) else Hashing.sha256("file-bytes", file.length.toString())
    }

    private fun psiFileFingerprint(file: PsiFile): String {
        val keys = Declarations.outlineKeys(file)
        if (keys.isNotEmpty()) return Hashing.sha256("file-outline", *keys.toTypedArray())
        return Hashing.sha256("file-text", file.text.replace(WHITESPACE, " ").trim())
    }

    companion object {
        private const val MAX_DIRECTORY_ENTRIES = 200
        private const val MAX_FINGERPRINTED_BYTES = 1_000_000L
        private val WHITESPACE = Regex("\\s+")

        /** The structural fingerprint of one declaration; see the class comment. Call inside a read action. */
        internal fun symbolFingerprint(element: PsiElement, kind: SymbolKind): String =
            if (kind == SymbolKind.CLASS) {
                Hashing.sha256("class", Declarations.shape(element, withBody = false).signatureKey, *Declarations.outlineKeys(element).toTypedArray())
            } else {
                val shape = Declarations.shape(element, withBody = true)
                Hashing.sha256(kind.name.lowercase(), shape.signatureKey, shape.bodyKey.orEmpty())
            }
    }
}
