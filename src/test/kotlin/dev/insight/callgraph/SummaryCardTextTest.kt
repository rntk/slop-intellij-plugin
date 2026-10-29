package dev.insight.callgraph

import dev.insight.enrichment.FunctionRole
import dev.insight.enrichment.FunctionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryCardTextTest {
    private fun node(id: Int, name: String, kind: SymbolKind = SymbolKind.FUNCTION, inProject: Boolean = true,
        unresolved: Boolean = false, signature: String? = null) =
        CallGraphNode(id, name, "Example", "Example.kt:${id + 1}", 0, inProject, kind, unresolved = unresolved, signature = signature)

    private fun call(targets: List<Int>, operation: CallOperation = CallOperation.INVOKE, byName: Boolean = false,
        heldBy: List<Int> = emptyList()) =
        CallOccurrence(0, 0, "/src/Example.kt", 0, 1, "run()", "run()", operation, targets, byName = byName, heldBy = heldBy)

    @Test
    fun `a summary shows its label, role and description under an escaped title`() {
        val text = SummaryCardText.render(node(1, "load<T>"),
            FunctionSummary("Loads & caches", "Reads <config>", FunctionRole.CORE, single = false), listOf(call(listOf(1))))
        assertEquals("<div class='callable-title'><code>load&lt;T&gt;</code> <font color='gray'>function · Example.kt</font></div>" +
            "<b>Loads &amp; caches</b> <font color='gray'>· core</font><br>Reads &lt;config&gt;", text)
    }

    @Test
    fun `without a summary the card reports where summarizing stands`() {
        val function = node(1, "run")
        assertTrue(SummaryCardText.render(function, null, emptyList()).contains("Pin or unfold to summarize"))
        assertTrue(SummaryCardText.render(function, null, emptyList(), progress = SummaryProgress.PENDING).contains("Summarizing…"))
        assertTrue(SummaryCardText.render(function, null, emptyList(), progress = SummaryProgress.UNAVAILABLE).contains("No summary:"))
    }

    @Test
    fun `boundaries explain themselves before any summary`() {
        val summary = FunctionSummary("Ignored", null, null, single = true)
        assertTrue(SummaryCardText.render(node(1, "println", inProject = false, signature = "fun println(x: Any?)"), summary, emptyList())
            .contains("External declaration. Open Source to inspect it.</font><br><code>fun println(x: Any?)</code>"))
        assertTrue(SummaryCardText.render(node(2, "run", unresolved = true), summary, emptyList())
            .contains("Implementation unknown. Inspect the call site"))
        val picked = SummaryCardText.render(node(2, "run", unresolved = true), summary, emptyList(), choices = 3, chosen = node(4, "Task.run"))
        assertTrue(picked.contains("3 declarations share this name"))
        assertTrue(picked.contains("Following your pick: <code>Task.run</code> · Example.kt:5"))
    }

    @Test
    fun `a whole file names the symbol it was expanded from`() {
        val text = SummaryCardText.render(node(5, "Example.kt", SymbolKind.FILE), null, emptyList(), expandedFrom = node(1, "run"))
        assertTrue(text.contains("<font color='gray'>file · Example.kt</font>"))
        assertTrue(text.contains("Whole file expanded from <code>run</code>."))
    }

    @Test
    fun `other declarations show their excerpt`() {
        val text = SummaryCardText.render(node(1, "limit", SymbolKind.PROPERTY), null, emptyList(), excerpt = "val limit = a < b")
        assertTrue(text.contains("<code>val limit = a &lt; b</code><br><font color='gray'>Unfold to inspect its declaration.</font>"))
    }

    @Test
    fun `evidence explains the weakest link to the target`() {
        fun evidence(vararg occurrences: CallOccurrence, reference: Boolean = false) =
            SummaryCardText.render(node(1, "run"), null, occurrences.toList(), reference = reference)
                .substringAfter("summarize this function.</font>")
        assertEquals("<br><font color='gray'>Named on this line, not called.</font>", evidence(call(listOf(1), byName = true), reference = true))
        assertTrue(evidence(call(listOf(1), byName = true)).contains("Matched by name"))
        assertTrue(evidence(call(emptyList(), heldBy = listOf(1))).contains("Callable value"))
        assertFalse(evidence(call(emptyList(), heldBy = listOf(7))).contains("Callable value"))
        assertTrue(evidence(call(listOf(1, 2))).contains("several candidates"))
        assertTrue(evidence(call(listOf(1), CallOperation.CALLABLE_REFERENCE)).contains("Callable reference"))
        assertEquals("", evidence(call(listOf(1))))
    }
}
