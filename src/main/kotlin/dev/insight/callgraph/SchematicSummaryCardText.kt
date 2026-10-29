package dev.insight.callgraph

import dev.insight.enrichment.FunctionSummary

/** How far the summary of a card's callee has come; it only matters while no summary is known. */
internal enum class SummaryProgress { NOT_REQUESTED, PENDING, UNAVAILABLE }

/** The HTML body of a schematic summary card, kept apart from Swing so its wording can be tested. */
internal object SummaryCardText {
    /**
     * [node] is the scope the card shows: the callee, or the containing file expanded from [expandedFrom].
     * [target] is the callee of [occurrences]; [choices] counts declarations sharing an unknown callee's name, and
     * [chosen] is the one the reader picked. [excerpt] is the first line of a non-function declaration.
     */
    fun render(
        node: CallGraphNode,
        summary: FunctionSummary?,
        occurrences: List<CallOccurrence>,
        target: Int = node.id,
        progress: SummaryProgress = SummaryProgress.NOT_REQUESTED,
        expandedFrom: CallGraphNode? = null,
        choices: Int = 0,
        chosen: CallGraphNode? = null,
        excerpt: String? = null,
        reference: Boolean = false,
    ): String {
        val name = escape(node.name.take(80))
        val kind = node.kind.name.lowercase()
        val file = node.location.takeIf { ':' in it }?.substringBefore(':')?.let { " · ${escape(it)}" }.orEmpty()
        val body = when {
            expandedFrom != null -> "<font color='gray'>Whole file expanded from <code>${escape(expandedFrom.name)}</code>. " +
                "Unfold a declaration to continue exploring.</font>"
            node.unresolved && choices > 0 -> "<font color='gray'>Implementation unknown: $choices declarations share this " +
                "name. Pick one to continue; the IDE did not establish any of them.</font>" + chosen?.let {
                    "<br>Following your pick: <code>${escape(it.name.take(80))}</code> · ${escape(it.location)}"
                }.orEmpty()
            node.unresolved -> "<font color='gray'>Implementation unknown. Inspect the call site or receiver.</font>"
            !node.inProject -> "<font color='gray'>External declaration. Open Source to inspect it.</font>" +
                node.signature?.let { "<br><code>${escape(it)}</code>" }.orEmpty()
            summary != null -> "<b>${escape(summary.label)}</b>" + (summary.role?.let { " <font color='gray'>· ${it.id}</font>" } ?: "") +
                (summary.description?.let { "<br>${escape(it)}" } ?: "")
            node.kind != SymbolKind.FUNCTION -> excerpt?.let { "<code>${escape(it)}</code><br>" }.orEmpty() +
                "<font color='gray'>Unfold to inspect its declaration.</font>"
            progress == SummaryProgress.PENDING -> "<font color='gray'>Summarizing…</font>"
            progress == SummaryProgress.UNAVAILABLE -> "<font color='gray'>No summary: test code, unavailable source, or the request failed.</font>"
            else -> "<font color='gray'>Pin or unfold to summarize this function.</font>"
        }
        val evidence = when {
            reference -> "<br><font color='gray'>Named on this line, not called.</font>"
            occurrences.any { it.byName } -> "<br><font color='gray'>Matched by name: the receiver's type is unknown.</font>"
            occurrences.any { it.targets.isEmpty() && target in it.heldBy } ->
                "<br><font color='gray'>Callable value: the function it holds is assigned elsewhere.</font>"
            occurrences.any { it.status == ResolutionStatus.AMBIGUOUS } -> "<br><font color='gray'>Possible target; several candidates.</font>"
            occurrences.any { it.operation == CallOperation.CALLABLE_REFERENCE } -> "<br><font color='gray'>Callable reference; invocation is not established here.</font>"
            else -> ""
        }
        return "<div class='callable-title'><code>$name</code> <font color='gray'>$kind$file</font></div>$body$evidence"
    }

    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
