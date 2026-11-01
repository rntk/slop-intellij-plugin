package dev.insight.callgraph

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.insight.graph.CallGraphResult
import dev.insight.enrichment.FunctionSummary
import dev.insight.ui.SourceChainLocation

/** Resolve the live declaration rather than a saved graph line, including after edits in an unsaved document. */
internal fun schematicSourceLocation(result: CallGraphResult, symbol: Int, title: String): SourceChainLocation =
    ReadAction.compute<SourceChainLocation, Throwable> {
        val element = result.pointers.getOrNull(symbol)?.element?.takeIf { it.isValid }
        val file = element?.containingFile?.virtualFile
        val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
        val range = element?.textRange
        val first = if (document != null && range != null)
            document.getLineNumber(range.startOffset.coerceIn(0, document.textLength)) else 0
        val last = if (document != null && range != null)
            document.getLineNumber((range.endOffset - 1).coerceIn(0, document.textLength)) else first
        SourceChainLocation(file?.path ?: result.graph.nodes[symbol].filePath.orEmpty(), title, first, last, file)
    }

/** Both generated parts of a symbol's summary, kept as plain text in the source reader. */
internal fun schematicSourceSummary(summary: FunctionSummary?): String? = summary?.let {
    listOfNotNull(it.label, it.description).filter { text -> text.isNotBlank() }.joinToString("\n\n").takeIf { text -> text.isNotBlank() }
}
