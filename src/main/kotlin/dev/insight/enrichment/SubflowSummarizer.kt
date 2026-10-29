package dev.insight.enrichment

import com.google.gson.JsonObject
import dev.insight.callgraph.FlowRegionEvidence
import dev.insight.callgraph.FlowRegionPlan
import dev.insight.callgraph.validateRegionPlans
import dev.insight.llm.CompletionFn

/** Missing keys retain structural fallbacks. Empty lists deliberately leave a block ungrouped. */
data class SubflowSummaries(
    val plans: Map<String, List<FlowRegionPlan>>,
    val failedBlocks: Int = 0,
    val rejectedGroups: Int = 0,
)

/** LLMs can only label consecutive subsets of precomputed blocks. They never emit edges or control structures. */
class SubflowSummarizer(
    private val complete: CompletionFn,
    private val cache: EnrichmentCache,
) {
    /**
     * Callees with a [known] function summary are described by it and their signature instead of a source excerpt:
     * the request is far smaller, and the summary already rests on that source.
     */
    suspend fun summarize(candidates: List<FlowRegionEvidence>, facts: Map<Int, SymbolFacts>,
        known: Map<Int, FunctionSummary> = emptyMap()): SubflowSummaries {
        val plans = linkedMapOf<String, List<FlowRegionPlan>>()
        var failed = 0
        var rejected = 0
        for (batch in candidates.chunked(4)) {
            // Missing source cannot support a semantic interpretation; retain the structural view.
            val grounded = batch.filter { candidate -> facts[candidate.owner] != null && candidate.sites.all { facts[it.callee] != null } }
            failed += batch.size - grounded.size
            if (grounded.isEmpty()) continue
            // Source and content hashes accompany summaries so changes invalidate cached interpretations.
            val user = JsonObject().apply {
                add("blocks", com.google.gson.JsonArray().apply {
                    grounded.forEachIndexed { index, candidate -> add(JsonObject().apply {
                        addProperty("id", index + 1)
                        add("owner", evidence(facts[candidate.owner], 3000))
                        add("context", com.google.gson.JsonArray().apply {
                            candidate.sites.first().frames.forEach { add("${it.kind}: ${it.label}") }
                        })
                        add("calls", com.google.gson.JsonArray().apply {
                            candidate.sites.forEachIndexed { slot, site -> add(JsonObject().apply {
                                addProperty("id", slot + 1)
                                addProperty("sourceOffset", site.offset)
                                addProperty("callExcerpt", site.text)
                                add("source", known[site.callee]?.let { summarized(facts[site.callee], it) } ?: evidence(facts[site.callee], 1500))
                            }) }
                        })
                    }) }
                })
            }.toString()
            val answer = cachedJson(cache, "subflows-v1", complete, SYSTEM, user, SCHEMA, 4000) {}
            if (answer == null) { failed += grounded.size; continue }
            val decoded = decode(answer, grounded)
            plans += decoded.plans
            failed += decoded.failedBlocks
            rejected += decoded.rejectedGroups
        }
        cache.flush()
        return SubflowSummaries(plans, failed, rejected)
    }

    private fun summarized(facts: SymbolFacts?, summary: FunctionSummary) = JsonObject().apply {
        facts?.let { addProperty("key", it.key); addProperty("hash", it.sourceHash); addProperty("signature", it.signature) }
        addProperty("summary", summary.label)
        summary.description?.let { addProperty("description", it) }
        summary.role?.let { addProperty("role", it.id) }
    }

    private fun evidence(facts: SymbolFacts?, chars: Int) = JsonObject().apply {
        if (facts == null) { addProperty("availability", "source unavailable"); return@apply }
        addProperty("key", facts.key)
        addProperty("hash", facts.sourceHash)
        addProperty("path", facts.path)
        addProperty("signature", facts.signature)
        addProperty("sourceExcerpt", facts.code.take(chars))
        addProperty("partial", facts.codeTruncated || facts.code.length > chars)
        addProperty("doc", facts.doc)
    }

    companion object {
        private val SYSTEM = """
            $CODE_AS_DATA
            Describe small operations within statically extracted call sequences. Each block is already bounded to
            one function and one control branch. Group only consecutive call occurrence ids within the SAME block,
            with at least two calls in a group. Groups must not overlap. Leave unrelated calls ungrouped.
            Use a short action label such as "Store user data" and a brief description supported by the source.
            Return every block, with groups: [] if there is no coherent operation. Do not invent calls, ordering,
            branches, persistence guarantees, error handling or runtime dispatch. Source excerpts may be incomplete;
            be conservative. The ids in evidence must exactly match the member call ids, in the same order.
            Your text is an interpretation, not proof of behavior. Return only JSON matching the schema.
        """.trimIndent()
        private val SCHEMA = JsonSchemas.obj("blocks" to JsonSchemas.array(JsonSchemas.obj(
            "id" to JsonSchemas.integer(),
            "groups" to JsonSchemas.array(JsonSchemas.obj(
                "members" to JsonSchemas.array(JsonSchemas.integer()),
                "evidence" to JsonSchemas.array(JsonSchemas.integer()),
                "label" to JsonSchemas.string(),
                "description" to JsonSchemas.string(),
            )),
        )))

        /** Strict integer and shape checks also apply to cached or unconstrained model output. */
        internal fun decode(answer: JsonObject, candidates: List<FlowRegionEvidence>): SubflowSummaries {
            val result = linkedMapOf<String, List<FlowRegionPlan>>()
            var rejected = 0
            val blocks = answer.get("blocks")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return SubflowSummaries(emptyMap(), candidates.size)
            val seen = hashSetOf<Int>()
            for (element in blocks) {
                val block = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val id = integer(block.get("id")) ?: continue
                val candidate = candidates.getOrNull(id - 1) ?: continue
                if (!seen.add(id)) { result.remove(candidate.key); continue }
                val groups = block.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val proposed = groups.mapNotNull { group ->
                    val parsed = runCatching {
                        val obj = group.asJsonObject
                        val members = obj.getAsJsonArray("members").map { integer(it) ?: error("Invalid member") }
                        val evidence = obj.getAsJsonArray("evidence").map { integer(it) ?: error("Invalid evidence") }
                        require(members == evidence && members.size in 2..FlowRegionEvidence.MAX_REGION_CALLS)
                        val sites = members.map { candidate.sites.getOrNull(it - 1) ?: error("Unknown occurrence") }
                        FlowRegionPlan(FlowRegionEvidence(candidate.owner, sites),
                            text(obj, "label")?.clipLine(70) ?: error("Missing label"),
                            text(obj, "description")?.clipLine(240), inferred = true)
                    }.getOrNull()
                    if (parsed == null) rejected++
                    parsed
                }
                val valid = validateRegionPlans(candidate, proposed)
                rejected += proposed.size - valid.size
                // Malformed groups are not interpreted as a deliberate request to remove fallback grouping.
                if (groups.isEmpty || valid.isNotEmpty()) result[candidate.key] = valid
            }
            return SubflowSummaries(result, candidates.size - result.size, rejected)
        }

        private fun integer(value: com.google.gson.JsonElement?): Int? =
            value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.toString()?.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()

        private fun text(obj: JsonObject, key: String): String? = obj.get(key)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    }
}
