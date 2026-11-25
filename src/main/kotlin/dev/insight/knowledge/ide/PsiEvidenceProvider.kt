package dev.insight.knowledge.ide

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.insight.graph.SymbolKind
import dev.insight.knowledge.Anchor
import dev.insight.knowledge.AnchorKind
import dev.insight.knowledge.Evidence
import dev.insight.knowledge.EvidenceProvider

/**
 * Reads bounded excerpts for anchors:
 *  - SYMBOL / ENTRYPOINT: signature, doc comment, and the body (truncated) or, for a class, its member signatures;
 *  - FILE: an outline of the declarations with line numbers (the first lines when it declares nothing);
 *  - DIRECTORY: its entries, each file with a one-line outline;
 *  - CALL_EDGE: the signatures of caller and callee.
 * The texts together stay within `maxChars`, shared fairly: short excerpts stay whole and long ones split what remains.
 * An anchor that no longer resolves yields a one-line note rather than being dropped.
 */
class PsiEvidenceProvider(private val project: Project) : EvidenceProvider {
    override suspend fun evidence(anchors: List<Anchor>, maxChars: Int): List<Evidence> {
        if (anchors.isEmpty() || maxChars <= 0) return emptyList()
        val drafts = smartReadAction(project) { anchors.distinct().map { ProgressManager.checkCanceled(); draft(it) } }
        val budget = allocateBudget(drafts.map { it.text.length }, maxChars)
        return drafts.indices.filter { budget[it] > 0 }.map { drafts[it].copy(text = capText(drafts[it].text, budget[it])) }
    }

    private fun draft(anchor: Anchor): Evidence = when (anchor.kind) {
        AnchorKind.FILE -> file(anchor.ref)
        AnchorKind.DIRECTORY -> directory(anchor.ref)
        AnchorKind.SYMBOL, AnchorKind.ENTRYPOINT -> symbol(anchor.ref)
        AnchorKind.CALL_EDGE -> callEdge(anchor.ref)
    }

    private fun missing(ref: String) = Evidence(ref, "Not found in the current code.")

    private fun symbol(ref: String): Evidence {
        val found = SymbolRef.parse(ref)?.let { SymbolLookup.find(project, it).firstOrNull() } ?: return missing(ref)
        val lines = found.lines(project)
        return Evidence(ref, symbolText(found.element, found.kind), found.path, lines?.first, lines?.last)
    }

    private fun symbolText(element: PsiElement, kind: SymbolKind): String {
        val shape = Declarations.shape(element, withBody = false)
        val out = StringBuilder(shape.header)
        shape.doc?.let { out.append("\n").append(capText(cleanDoc(it), MAX_DOC)) }
        if (kind == SymbolKind.CLASS) {
            val members = Declarations.members(element)
            members.take(MAX_ENTRIES).forEach { out.append("\n  ").append(capText(Declarations.shape(it, false).header, MAX_LINE)) }
            if (members.size > MAX_ENTRIES) out.append("\n  … ${members.size - MAX_ENTRIES} more")
        } else if (shape.bodyStart >= 0) {
            out.append("\n").append(capText(element.text.substring(shape.bodyStart), MAX_BODY))
        }
        return out.toString()
    }

    private fun file(ref: String): Evidence {
        val virtual = ProjectPaths.find(project, ref)?.takeIf { !it.isDirectory } ?: return missing(ref)
        val psi = PsiManager.getInstance(project).findFile(virtual)
        val lineCount = psi?.let { PsiDocumentManager.getInstance(project).getDocument(it)?.lineCount }
        return Evidence(ref, psi?.let(::fileOutline) ?: "(binary or unreadable file)", normalizeProjectPath(ref), 1, lineCount)
    }

    private fun fileOutline(file: PsiFile): String {
        val lines = mutableListOf<String>()
        fun walk(root: PsiElement, indent: String) {
            for (member in Declarations.members(root)) {
                if (lines.size >= MAX_ENTRIES) return
                val line = lineRange(project, member)?.first
                lines += "${line?.let { "$it: " }.orEmpty()}$indent${capText(Declarations.shape(member, false).header, MAX_LINE)}"
                if (Declarations.kindOf(member) == SymbolKind.CLASS) walk(member, "$indent  ")
            }
        }
        walk(file, "")
        if (lines.isEmpty()) return capText(file.text.lines().take(MAX_HEAD_LINES).joinToString("\n"), MAX_BODY)
        return lines.joinToString("\n") + if (lines.size >= MAX_ENTRIES) "\n…" else ""
    }

    private fun directory(ref: String): Evidence {
        val directory = ProjectPaths.find(project, ref)?.takeIf { it.isDirectory } ?: return missing(ref)
        val children = directory.children.filter { !isExcludedPath(it.name, it.isDirectory) }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        val lines = children.take(MAX_ENTRIES).map { child ->
            if (child.isDirectory) child.name + "/" else child.name + oneLine(child).let { if (it.isEmpty()) "" else " — $it" }
        }
        val more = if (children.size > MAX_ENTRIES) "\n… ${children.size - MAX_ENTRIES} more" else ""
        return Evidence(ref, lines.joinToString("\n") + more, normalizeProjectPath(ref))
    }

    /** The first declarations of a file, or its first non-blank line when it declares none. */
    private fun oneLine(file: VirtualFile): String {
        if (file.fileType.isBinary || file.length > MAX_SCANNED_BYTES) return ""
        val psi = PsiManager.getInstance(project).findFile(file) ?: return ""
        val declared = Declarations.members(psi).take(3).map { Declarations.shape(it, false).header }
        val text = declared.joinToString("; ").ifEmpty { psi.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim() }
        return capText(text, MAX_LINE)
    }

    private fun callEdge(ref: String): Evidence {
        val edge = CallEdgeRef.parse(ref) ?: return missing(ref)
        fun endpoint(label: String, symbolRef: String): String {
            val found = SymbolRef.parse(symbolRef)?.let { SymbolLookup.find(project, it).firstOrNull() }
                ?: return "$label: $symbolRef (not found)"
            val lines = found.lines(project)?.let { ":${it.first}" }.orEmpty()
            return "$label: ${Declarations.shape(found.element, false).header} (${found.path}$lines)"
        }
        return Evidence(ref, endpoint("caller", edge.caller) + "\n" + endpoint("callee", edge.callee))
    }

    private fun cleanDoc(doc: String): String =
        doc.lines().joinToString("\n") { it.trim().removePrefix("/**").removePrefix("/*").removeSuffix("*/").trim().removePrefix("*").trim() }.trim()

    private companion object {
        const val MAX_ENTRIES = 60
        const val MAX_LINE = 160
        const val MAX_DOC = 600
        const val MAX_BODY = 2_500
        const val MAX_HEAD_LINES = 40
        const val MAX_SCANNED_BYTES = 300_000L
    }
}
