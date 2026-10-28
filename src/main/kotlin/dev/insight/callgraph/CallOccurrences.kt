package dev.insight.callgraph

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference

enum class CallOperation { INVOKE, CALLABLE_REFERENCE }
enum class ResolutionStatus { RESOLVED, AMBIGUOUS, UNRESOLVED }

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

/** PSI-free source evidence. [targets] are candidates, never a claim about the concrete runtime implementation. */
data class CallOccurrence(
    val owner: Int,
    /** Enclosing function, class or file; initializers of locals keep their enclosing execution scope. */
    val scope: Int,
    val filePath: String,
    val startOffset: Int,
    val endOffset: Int,
    val expression: String,
    val text: String,
    val operation: CallOperation,
    val targets: List<Int>,
    /** A location-specific boundary node when no target declaration is available. */
    val unresolvedTarget: Int? = null,
    val receiver: String? = null,
    val receiverTargets: List<Int> = emptyList(),
    /** Bindings named by the callee, for example an alias or a callable parameter. */
    val bindings: List<Int> = emptyList(),
    val frames: List<ControlFrame> = emptyList(),
    val receiverType: String? = null,
    val unresolvedReason: String? = null,
    /** See [CallResolution.byName]: candidates are plausible, not inferred. */
    val byName: Boolean = false,
    /** With no [targets], the callable bindings this call goes through; the function they hold is assigned elsewhere. */
    val heldBy: List<Int> = emptyList(),
    /** With no [targets], declarations sharing the member name, project first; none is established as the callee. */
    val choices: List<Int> = emptyList(),
) {
    val status: ResolutionStatus get() = when (targets.size) {
        0 -> ResolutionStatus.UNRESOLVED
        1 -> ResolutionStatus.RESOLVED
        else -> ResolutionStatus.AMBIGUOUS
    }
    val displayTargets: List<Int> get() = targets.ifEmpty { heldBy.ifEmpty { listOfNotNull(unresolvedTarget) } }
    val explanation: String get() = if (byName && targets.isNotEmpty())
        "The receiver's type is unknown; the IDE matched ${targets.size} declaration(s) by member name, as Go to Declaration does. " +
            "The runtime target may differ."
    else if (targets.isEmpty() && choices.isNotEmpty())
        "The receiver's type is unknown and ${choices.size} declarations share this member name. Pick one to continue; the runtime target may be none of them."
    else if (targets.isEmpty() && heldBy.isNotEmpty())
        "Calls a callable value; the function it holds is assigned elsewhere. Follow the binding to see where it is set."
    else when (status) {
        ResolutionStatus.UNRESOLVED -> unresolvedReason ?: "Implementation unknown: the IDE did not provide a target declaration."
        ResolutionStatus.AMBIGUOUS -> "The IDE returned ${targets.size} candidate declarations; runtime dispatch may vary."
        ResolutionStatus.RESOLVED -> "The IDE resolved a target declaration; runtime dispatch may vary."
    }
}
