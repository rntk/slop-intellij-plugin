package dev.insight.callgraph

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
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

/** Resolve every candidate, keeping cancellation visible and isolating failures in individual references. */
internal fun resolveTargets(reference: PsiReference): List<PsiElement> = try {
    if (reference is PsiPolyVariantReference) reference.multiResolve(false).mapNotNull { it.element }.distinct()
    else listOfNotNull(reference.resolve())
} catch (e: ProcessCanceledException) {
    throw e
} catch (e: Exception) {
    if (e is CancellationException || e is ControlFlowException) throw e
    Logger.getInstance("dev.insight.callgraph.CallOccurrences")
        .warn("Call graph reference resolution failed (${reference.javaClass.name})", e)
    emptyList()
}
