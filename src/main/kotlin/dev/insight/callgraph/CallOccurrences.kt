package dev.insight.callgraph

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import dev.insight.graph.CallOperation
import kotlinx.coroutines.CancellationException

/** Read-action-only result from a language adapter. An empty target list still describes a real source occurrence. */
data class CallResolution(
    val callee: PsiElement,
    val targets: List<PsiElement>,
    val receiver: PsiElement? = null,
    val operation: CallOperation = CallOperation.INVOKE,
    val receiverType: String? = null,
    /** The receiver type is unknown; [targets] were matched by member name, as the editor's Go to Declaration does. */
    val byName: Boolean = false,
    /** Name matches too generic to present as targets ([byName] caps them); the user may pick one to continue. */
    val choices: List<PsiElement> = emptyList(),
)

/** Safe range of [reference] within its element, or null when a PSI implementation reports an invalid range. */
internal fun referenceRange(reference: PsiReference): TextRange? = try {
    reference.rangeInElement.shiftRight(reference.element.textRange.startOffset)
} catch (_: IllegalArgumentException) {
    null
}

/** Resolve every candidate, keeping cancellation visible and isolating failures in individual references. */
internal fun resolveTargets(reference: PsiReference): List<PsiElement> = try {
    val raw = if (reference is PsiPolyVariantReference) reference.multiResolve(false).mapNotNull { it.element }
    else listOfNotNull(reference.resolve())
    val supports = CallGraphLanguageSupport.EP_NAME.extensionList
    raw.map { target -> supports.firstNotNullOfOrNull { it.unwrapImport(target) } ?: target }.distinct()
} catch (e: ProcessCanceledException) {
    throw e
} catch (e: Exception) {
    if (e is CancellationException || e is ControlFlowException) throw e
    Logger.getInstance("dev.insight.callgraph.CallOccurrences")
        .warn("Call graph reference resolution failed (${reference.javaClass.name})", e)
    emptyList()
}
