package dev.insight.callgraph

import com.intellij.lang.LanguageBraceMatching
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import dev.insight.graph.RelationKind

/** Read action only. Never mix live IDE facts or tool results with stale schematic lines. */
internal fun schematicGapSource(project: Project, schematic: Schematic, row: CodeRow): GapSource {
    val psi = checkNotNull(PsiManager.getInstance(project).findFile(schematic.file)) { "Source is unavailable" }
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(psi)) { "Source is unavailable" }
    val lines = document.text.split('\n')
    check(lines.drop(schematic.firstLine).take(schematic.lines.size) == schematic.lines) {
        "Source changed; reopen the schematic before summarizing gaps"
    }
    val start = document.getLineStartOffset(row.first)
    val end = document.getLineEndOffset(row.last)
    val selected = TextRange(start, end)
    val path = schematic.file.path.removePrefix(project.basePath?.trimEnd('/')?.plus('/') ?: "")
    // Classify before resolving references. Unknown tokens, comments and errors remain eligible.
    if (structuralGap(psi, selected)) return GapSource(path, lines, "", skipSummary = true)

    val facts = linkedSetOf("Language: ${psi.language.displayName}")
    fun describe(element: PsiElement): String? {
        val name = (element as? PsiNamedElement)?.name ?: return null
        val kind = ProjectGraphAnalyzer.kind(element) ?: return null
        if (element is PsiFile) return null
        return "${kind.name.lowercase()} ${name.take(160)}"
    }
    // Ancestors above the file (e.g. its directory) have no text range; they are never the owner.
    val owner = generateSequence(psi.findElementAt(start)) { it.parent }
        .firstOrNull { it.textRange?.contains(selected) == true && describe(it) != null }
    owner?.let { facts += "Enclosing: ${describe(it)}" }
    var visited = 0
    psi.accept(object : PsiRecursiveElementWalkingVisitor() {
        override fun visitElement(element: PsiElement) {
            ProgressManager.checkCanceled()
            val range = element.textRange
            if (range == null) {
                // Synthetic elements can have no range but still hold ranged children.
                super.visitElement(element)
                return
            }
            if (range.endOffset <= start || range.startOffset >= end) return
            if (++visited > 2000 || facts.size >= 24) return
            // Imports are already readable in the excerpt; resolving their package chains adds little.
            if (element is PsiComment || ProjectGraphAnalyzer.relationKind(element) == RelationKind.IMPORT) return
            // Ancestors supply ownership only, not their unrelated names or references.
            if (selected.contains(range)) describe(element)?.let { facts += "Declared: $it" }
            for (reference in element.references.take(8)) {
                val referenceRange = reference.rangeInElement.shiftRight(range.startOffset)
                if (!selected.intersectsStrict(referenceRange)) continue
                for (target in resolveTargets(reference).take(2)) {
                    val description = describe(target) ?: continue
                    val targetFile = target.containingFile?.virtualFile
                    val location = targetFile?.let {
                        val base = project.basePath?.trimEnd('/')?.plus('/')
                        if (base != null && it.path.startsWith(base)) it.path.removePrefix(base) else it.name
                    }
                    facts += "Reference ${reference.canonicalText.take(120)}: $description" +
                        (location?.let { " ($it)" } ?: "")
                    if (facts.size >= 24) break
                }
                if (facts.size >= 24) break
            }
            super.visitElement(element)
        }
    })
    return GapSource(path, lines, facts.joinToString("\n"))
}

/** Only whitespace and complete closing structural tokens, confirmed by the language plugin. */
internal fun structuralGap(file: PsiFile, range: TextRange): Boolean {
    var offset = range.startOffset
    while (offset < range.endOffset) {
        ProgressManager.checkCanceled()
        val leaf = file.findElementAt(offset) ?: return false
        val leafRange = leaf.textRange ?: return false
        if (leafRange.endOffset <= offset) return false
        if (leaf !is PsiWhiteSpace) {
            if (!range.contains(leafRange) || generateSequence(leaf) { it.parent }.any { it is PsiErrorElement }) return false
            val pairs = LanguageBraceMatching.INSTANCE.forLanguage(leaf.language)?.pairs ?: return false
            if (pairs.none { it.isStructural && it.rightBraceType == leaf.node.elementType }) return false
        }
        offset = leafRange.endOffset
    }
    return true
}
