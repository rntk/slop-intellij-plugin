package dev.insight.enrichment

import com.google.gson.JsonObject
import dev.insight.graph.SymbolKind
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.CompletionFn
import dev.insight.llm.JsonSchemas
import dev.insight.llm.clipLine
import dev.insight.llm.jsonText

/**
 * Proposes the project's functional areas ([Domain]s) in one generative request, from the group labels when there
 * are any and from file paths and their main declarations otherwise. Returns an empty list when the model fails.
 */
class TaxonomyDiscovery(
    private val complete: CompletionFn,
    private val cache: EnrichmentCache,
) {
    suspend fun discover(input: EnrichmentInput, labels: Map<String, GroupLabel>): List<Domain> {
        val overview = if (labels.isNotEmpty()) labelOverview(input, labels) else structureOverview(input)
        if (overview.isBlank()) return emptyList()
        val answer = cachedJson(cache, "taxonomy", complete, SYSTEM, overview, schema, maxTokens = 4000) {} ?: return emptyList()
        return domains(answer)
    }

    private fun labelOverview(input: EnrichmentInput, labels: Map<String, GroupLabel>) = buildString {
        // Directories first: they summarize the most code per line.
        val files = input.facts.values.mapNotNullTo(hashSetOf()) { it.path }
        val (directories, fileLabels) = labels.values.sortedBy { it.path }.partition { it.path !in files }
        for (label in directories + fileLabels) {
            if (length > MAX_OVERVIEW) { append("…\n"); break }
            append(label.path).append(": ").append(label.label)
            label.summary?.let { append(" — ").append(it) }
            append('\n')
        }
    }

    private fun structureOverview(input: EnrichmentInput) = buildString {
        val byPath = input.facts.values.filter { it.path != null }.groupBy { it.path!! }.toSortedMap()
        for ((path, facts) in byPath) {
            if (length > MAX_OVERVIEW) { append("…\n"); break }
            val names = facts.filter { it.kind == SymbolKind.CLASS || it.kind == SymbolKind.FUNCTION }.sortedBy { it.id }
                .take(12).joinToString(", ") { it.name }
            append(path).append(": ").append(names).append('\n')
        }
    }

    private fun domains(answer: JsonObject): List<Domain> {
        val seen = hashSetOf(Domain.NONE)
        return answer.getAsJsonArray("domains")?.mapNotNull { element ->
            val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = entry.jsonText("name")?.clipLine(60) ?: return@mapNotNull null
            val base = slug(entry.jsonText("id") ?: name).ifEmpty { slug(name) }.ifEmpty { return@mapNotNull null }
            val id = generateSequence(2) { it + 1 }.map { "$base-$it" }.let { sequenceOf(base) + it }.first { seen.add(it) }
            Domain(id, name, entry.jsonText("description")?.clipLine(240) ?: name)
        }.orEmpty().take(MAX_DOMAINS)
    }

    private companion object {
        const val MAX_OVERVIEW = 12_000
        const val MAX_DOMAINS = 12
        val SYSTEM = """
            $CODE_AS_DATA

            Identify the 4-12 functional areas of this project: the features or domain capabilities it provides, such as
            "call graph analysis" or "chat assistant" in an IDE plugin, or "billing" and "user accounts" in a web shop.
            Base them on what this specific project does. Do not list generic technical concerns (logging, storage,
            configuration, error handling, networking, UI toolkit, tests, utilities); those are classified separately.
            Areas must not overlap. For each return {"id": a short lowercase slug such as "call-graph", "name": 2-4 words,
            "description": one sentence saying what belongs to this area and how it differs from the other areas}.
            Answer only with JSON {"domains": [...]}. Think briefly.
        """.trimIndent()
        val schema: JsonObject = JsonSchemas.obj("domains" to JsonSchemas.array(JsonSchemas.obj(
            "id" to JsonSchemas.string(), "name" to JsonSchemas.string(), "description" to JsonSchemas.string())))

        fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).trimEnd('-')
    }
}
