package dev.insight.enrichment

import com.google.gson.JsonParser
import dev.insight.callgraph.CallSite
import dev.insight.callgraph.FlowRegionEvidence
import dev.insight.callgraph.SymbolKind
import dev.insight.llm.Completion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubflowSummarizerTest {
    private val candidate = FlowRegionEvidence(0, listOf(CallSite(1, 10), CallSite(2, 20), CallSite(1, 30)))
    private fun json(groups: String) = JsonParser.parseString("""{"blocks":[{"id":1,"groups":$groups}]}""").asJsonObject
    private fun group(members: String, evidence: String = members) =
        """{"members":$members,"evidence":$evidence,"label":"Store data","description":"Writes a user record."}"""

    @Test fun `labels reference exact ordered source occurrences`() {
        val output = SubflowSummarizer.decode(json("[${group("[1,2]")}]"), listOf(candidate))
        val plan = output.plans.getValue(candidate.key).single()
        assertEquals(candidate.sites.take(2), plan.evidence.sites)
        assertTrue(plan.inferred)
        assertEquals("Store data", plan.title)
        assertEquals(0, output.rejectedGroups)
    }

    @Test fun `hallucinated reordered nonconsecutive and inconsistent evidence falls back`() {
        for ((members, evidence) in listOf("[1,9]" to "[1,9]", "[2,1]" to "[2,1]", "[1,3]" to "[1,3]",
            "[1,2]" to "[2,3]", "[1,1]" to "[1,1]", "[1.0,2]" to "[1.0,2]")) {
            val output = SubflowSummarizer.decode(json("[${group(members, evidence)}]"), listOf(candidate))
            assertTrue(output.plans.isEmpty())
            assertEquals(1, output.failedBlocks)
            assertEquals(1, output.rejectedGroups)
        }
    }

    @Test fun `overlap is rejected and explicit incoherence remains ungrouped`() {
        val output = SubflowSummarizer.decode(json("[${group("[1,2]")},${group("[2,3]")}]"), listOf(candidate))
        assertEquals(1, output.plans.getValue(candidate.key).size)
        assertEquals(1, output.rejectedGroups)
        assertEquals(emptyList<Any>(), SubflowSummarizer.decode(json("[]"), listOf(candidate)).plans[candidate.key])
        val unknown = JsonParser.parseString("""{"blocks":[{"id":9,"groups":[]}]}""").asJsonObject
        assertTrue(SubflowSummarizer.decode(unknown, listOf(candidate)).plans.isEmpty())
    }

    private fun facts(hash: String = "h") = (0..2).associateWith { id ->
        SymbolFacts(id, "f$id", SymbolKind.FUNCTION, "f$id", "UserStore", "Store.kt", "kotlin", "fun f$id()",
            "write(user)", contentHash = "excerpt", sourceHash = hash)
    }

    @Test fun `cache reuses evidence but full source edits invalidate it`() = runBlocking {
        val cache = InMemoryEnrichmentCache()
        var requests = 0
        val summarizer = SubflowSummarizer({ request ->
            requests++
            assertTrue(request.messages.last().content.contains("sourceExcerpt"))
            Completion(json("[${group("[1,2]")}]").toString())
        }, cache)
        assertEquals(1, summarizer.summarize(listOf(candidate), facts()).plans.size)
        summarizer.summarize(listOf(candidate), facts())
        assertEquals(1, requests)
        summarizer.summarize(listOf(candidate), facts("changed beyond excerpt"))
        assertEquals(2, requests)
    }

    @Test fun `network failure falls back and cancellation propagates`() = runBlocking {
        val failed = SubflowSummarizer({ throw java.io.IOException("offline") }, InMemoryEnrichmentCache())
            .summarize(listOf(candidate), facts())
        assertTrue(failed.plans.isEmpty())
        assertEquals(1, failed.failedBlocks)
        try {
            SubflowSummarizer({ throw CancellationException("stop") }, InMemoryEnrichmentCache())
                .summarize(listOf(candidate), facts())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun `missing source never asks the model to guess`() = runBlocking {
        val output = SubflowSummarizer({ error("No request should be sent") }, InMemoryEnrichmentCache())
            .summarize(listOf(candidate), facts().filterKeys { it != 2 })
        assertTrue(output.plans.isEmpty())
        assertEquals(1, output.failedBlocks)
    }
}
