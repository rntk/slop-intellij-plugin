package dev.insight.enrichment

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Human-readable JSON of an [Enrichment], keyed by symbol rather than graph id, for inspection and for
 * experimenting with visualizations outside the IDE. Probabilities are rounded; concerns below [concernFloor] are left out.
 */
object EnrichmentReport {
    fun toJson(input: EnrichmentInput, enrichment: Enrichment, concernFloor: Double = 0.5): String {
        val graph = input.graph
        fun name(id: Int) = input.facts[id]?.let { listOf(it.container, it.name).filter(String::isNotBlank).joinToString(".") }
            ?: graph.nodes.getOrNull(id)?.name ?: "#$id"
        val root = JsonObject()
        root.add("stats", gson.toJsonTree(enrichment.stats))
        root.add("domains", JsonArray().apply { enrichment.domains.forEach { add(gson.toJsonTree(it)) } })
        root.add("groupLabels", JsonObject().apply {
            enrichment.groupLabels.toSortedMap().forEach { (path, label) ->
                add(path, JsonObject().apply { addProperty("label", label.label); label.summary?.let { addProperty("summary", it) } })
            }
        })
        root.add("flows", JsonArray().apply {
            enrichment.flows.forEach { flow ->
                add(JsonObject().apply {
                    addProperty("id", flow.id)
                    addProperty("name", flow.name)
                    flow.description?.let { addProperty("description", it) }
                    addProperty("entry", name(flow.entry))
                    add("nodes", JsonArray().apply { flow.nodes.forEach { add(name(it)) } })
                })
            }
        })
        root.add("symbols", JsonArray().apply {
            input.facts.values.sortedBy { it.key }.forEach { facts ->
                val id = facts.id
                val annotation = enrichment.nodes[id]
                val tags = enrichment.heuristics[id]?.tags.orEmpty()
                if (annotation == null && tags.isEmpty() && id !in enrichment.summaries) return@forEach
                add(JsonObject().apply {
                    addProperty("key", facts.key)
                    addProperty("name", name(id))
                    addProperty("kind", facts.kind.name.lowercase())
                    annotation?.let { a ->
                        add("concerns", JsonObject().apply {
                            a.concerns.filterValues { it >= concernFloor }.entries.sortedByDescending { it.value }
                                .forEach { (concern, p) -> addProperty(concern, p.round()) }
                        })
                        a.phase?.let { addProperty("phase", "${it.choice} (${it.confidence.round()})") }
                        a.layer?.let { addProperty("layer", "${it.choice} (${it.confidence.round()})") }
                        a.domain?.let { addProperty("domain", "${it.choice} (${it.confidence.round()})") }
                        a.importance?.let { addProperty("importance", it.score.round()) }
                        a.entryPoint?.let { addProperty("entryPoint", it.round()) }
                        a.utility?.let { addProperty("utility", it.round()) }
                    }
                    enrichment.summaries[id]?.let { add("summary", FunctionSummarizer.encode(it)) }
                    enrichment.effects[id]?.let { e ->
                        add("effects", JsonObject().apply {
                            add("own", JsonArray().apply { e.own.sorted().forEach(::add) })
                            add("reached", JsonArray().apply { e.reached.sorted().forEach(::add) })
                        })
                    }
                    enrichment.visibility[id]?.let { addProperty("visibility", it.round()) }
                    enrichment.metrics[id]?.let { addProperty("fanIn", it.fanIn); addProperty("fanOut", it.fanOut) }
                    if (tags.isNotEmpty()) add("rules", JsonArray().apply { tags.sorted().forEach(::add) })
                    val calls = enrichment.edges.filterKeys { it.caller == id }
                    if (calls.isNotEmpty()) add("calls", JsonObject().apply {
                        calls.entries.sortedBy { name(it.key.callee) }.forEach { (edge, e) ->
                            addProperty(name(edge.callee), "continues=${e.continuesOperation.round()} error=${e.errorPath.round()}")
                        }
                    })
                })
            }
        })
        return gson.toJson(root)
    }

    private fun Double.round() = Math.round(this * 100) / 100.0
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
}
