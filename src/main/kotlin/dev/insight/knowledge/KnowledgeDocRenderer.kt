package dev.insight.knowledge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.llm.CompletionFn
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmMessage
import kotlinx.coroutines.CancellationException

/**
 * What to write a doc about: a [title] (the doc heading becomes "# <title> flow"), the [areas] whose claims it covers
 * (nested; `""` is everything) and the [existingPath] of the doc to update, if any. New docs go to `flows/<slug>.md`.
 */
data class DocTarget(val title: String, val areas: Set<String>, val existingPath: String? = null) {
    constructor(title: String, area: String, existingPath: String? = null) : this(title, setOf(area), existingPath)

    val docPath: String get() = existingPath ?: "flows/${slug(title)}.md"

    companion object {
        fun slug(title: String): String =
            title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "knowledge" }
    }
}

/** Bounds for one rendering: few entry-point links, a modest number of claims and paragraphs. */
data class DocRenderLimits(
    val entryPoints: Int = 5,
    val maxClaims: Int = 12,
    val maxParagraphs: Int = 4,
    /** Append `<!-- c-... -->` with the cited claim ids to each paragraph. */
    val embedCitations: Boolean = true,
)

enum class ExclusionReason { DETAIL, VOLATILE, OVER_LIMIT }

/** A claim left out of the doc, with why. It stays in the ledger. */
data class ExcludedClaim(val claimId: String, val reason: ExclusionReason)

/**
 * A proposed doc. [usedClaimIds] are the selected claims the text cites; [unplaced] are selected claims that nothing cited
 * (they are listed in a final "Not placed" section of [markdown]); [excluded] were left out as detail, volatile or over the limit.
 */
data class RenderedDoc(
    val path: String,
    val markdown: String,
    val usedClaimIds: List<String>,
    val unplaced: List<String>,
    val excluded: List<ExcludedClaim>,
    val warnings: List<String>,
)

/**
 * Renders the active claims of a target as a short flows-style doc. Selection, entry-point choice, citation checks and
 * Markdown assembly are deterministic; [complete], when present, only words the paragraphs, strictly from the selected claims.
 * Every selected claim ends up cited or in the "Not placed" section. Never writes files.
 */
class KnowledgeDocRenderer(
    private val complete: CompletionFn?,
    private val classifier: ClaimClassifier,
    private val limits: DocRenderLimits = DocRenderLimits(),
) {
    suspend fun render(state: KnowledgeState, target: DocTarget): RenderedDoc {
        val warnings = mutableListOf<String>()
        val candidates = state.active().filter { claim -> target.areas.any { inArea(claim.area, it) } }
        val classes = classifier.classify(candidates)
        val excluded = mutableListOf<ExcludedClaim>()
        val usable = ArrayList<Claim>()
        for (claim in candidates) {
            val cls = classes[claim.id]
            when {
                cls?.detail == true -> excluded += ExcludedClaim(claim.id, ExclusionReason.DETAIL)
                cls?.volatile == true -> excluded += ExcludedClaim(claim.id, ExclusionReason.VOLATILE)
                else -> usable += claim
            }
        }
        val ranked = usable.sortedWith(
            compareByDescending<Claim> { levelRank(it.level) }
                .thenByDescending { classes[it.id]?.stability ?: it.stability ?: 0.0 }
                .thenByDescending { it.anchors.distinct().size }
                .thenBy { it.id })
        val selected = ranked.take(limits.maxClaims)
        ranked.drop(limits.maxClaims).forEach { excluded += ExcludedClaim(it.id, ExclusionReason.OVER_LIMIT) }
        if (selected.isEmpty()) warnings += "No claims selected for '${target.title}'"

        val docPath = target.docPath
        val entries = entryPoints(selected, classes, docPath)
        val selectedIds = selected.map { it.id }.toSet()
        var paragraphs: List<DocParagraph>? = null
        var descriptions: Map<String, String> = emptyMap()
        if (complete != null && selected.isNotEmpty()) {
            try {
                val draft = askModel(complete, target, selected, entries, selectedIds)
                warnings += draft.warnings
                if (draft.paragraphs.isEmpty()) warnings += "Model returned no usable paragraphs; used deterministic rendering"
                else { paragraphs = draft.paragraphs.take(limits.maxParagraphs); descriptions = draft.descriptions }
                if (draft.paragraphs.size > limits.maxParagraphs) warnings += "Dropped paragraphs beyond the limit of ${limits.maxParagraphs}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warnings += "Model call failed (${e.message ?: e::class.simpleName}); used deterministic rendering"
            }
        }
        val finalParagraphs = paragraphs ?: deterministic(selected)
        val used = finalParagraphs.flatMap { it.claimIds }.toSet()
        val usedIds = selected.map { it.id }.filter { it in used }
        val unplaced = selected.map { it.id }.filter { it !in used }
        val markdown = markdown(target, finalParagraphs, entries, descriptions, selected.filter { it.id in unplaced })
        return RenderedDoc(docPath, markdown, usedIds, unplaced, excluded, warnings)
    }

    // ---- Selection helpers ----

    private fun inArea(claimArea: String, area: String): Boolean {
        val base = area.trim('/')
        return base.isEmpty() || claimArea.trim('/') == base || claimArea.trim('/').startsWith("$base/")
    }

    private fun levelRank(level: ClaimLevel) = when (level) {
        ClaimLevel.FLOW -> 3
        ClaimLevel.MODULE -> 2
        ClaimLevel.FILE -> 1
        ClaimLevel.SYMBOL -> 0
    }

    // ---- Entry points ----

    private data class EntryPoint(val path: String, val directory: Boolean, val link: String) {
        val label: String get() = path.substringAfterLast('/') + if (directory) "/" else ""
    }

    private fun entryPoints(selected: List<Claim>, classes: Map<String, ClaimClass>, docPath: String): List<EntryPoint> {
        if (limits.entryPoints <= 0) return emptyList()
        class Votes(var claims: Int = 0, var stability: Double = 0.0, var file: Boolean = false)
        val votes = LinkedHashMap<String, Votes>()
        for (claim in selected) {
            val paths = LinkedHashMap<String, Boolean>() // path -> is file
            claim.anchors.forEach { anchor ->
                when (anchor.kind) {
                    AnchorKind.FILE -> cleanPath(anchor.ref)?.let { paths[it] = true }
                    AnchorKind.DIRECTORY -> cleanPath(anchor.ref)?.let { paths.putIfAbsent(it, false) }
                    else -> Unit
                }
            }
            claim.citations.forEach { cite -> cleanPath(cite.path)?.let { paths[it] = true } }
            paths.forEach { (path, isFile) ->
                val v = votes.getOrPut(path) { Votes() }
                v.claims++
                v.stability += classes[claim.id]?.stability ?: claim.stability ?: 0.0
                if (isFile) v.file = true
            }
        }
        return votes.entries
            .sortedWith(compareByDescending<Map.Entry<String, Votes>> { it.value.claims }
                .thenByDescending { it.value.stability }
                .thenByDescending { it.value.file }
                .thenBy { it.key })
            .take(limits.entryPoints)
            .map { (path, v) -> EntryPoint(path, !v.file, relativeLink(docPath, path)) }
    }

    private fun cleanPath(ref: String): String? {
        val path = ref.trim().removePrefix("./").trim('/')
        if (path.isEmpty() || "://" in path || ref.trim().startsWith("/") || path.split('/').any { it == ".." || it == "." }) return null
        return path
    }

    // ---- Paragraphs ----

    private data class DocParagraph(val text: String, val claimIds: List<String>)

    private fun sentence(text: String): String {
        val trimmed = text.trim().replace(Regex("\\s+"), " ")
        return if (trimmed.last() in ".!?") trimmed else "$trimmed."
    }

    /** Claims grouped by area and level tier; the most general group comes first. Surplus groups merge into the last paragraph. */
    private fun deterministic(selected: List<Claim>): List<DocParagraph> {
        val groups = selected.groupBy { it.area to (levelRank(it.level) >= 2) }.entries
            .sortedWith(compareByDescending<Map.Entry<Pair<String, Boolean>, List<Claim>>> { e -> e.value.maxOf { levelRank(it.level) } }
                .thenBy { it.key.first })
            .map { it.value }
        val merged = if (groups.size <= limits.maxParagraphs) groups
        else groups.take(limits.maxParagraphs - 1) + listOf(groups.drop(limits.maxParagraphs - 1).flatten())
        return merged.filter { it.isNotEmpty() }.map { claims ->
            DocParagraph(claims.joinToString(" ") { sentence(it.text) }, claims.map { it.id })
        }
    }

    // ---- Model path ----

    private data class ModelDraft(
        val paragraphs: List<DocParagraph>,
        val descriptions: Map<String, String>,
        val warnings: List<String>,
    )

    private suspend fun askModel(
        complete: CompletionFn,
        target: DocTarget,
        selected: List<Claim>,
        entries: List<EntryPoint>,
        selectedIds: Set<String>,
    ): ModelDraft {
        val system = """
            You write a short, high-level documentation page about one part of a codebase, for a newcomer or a code agent.
            Use ONLY the facts in the numbered claims you are given. Do not add facts, implementation details, numbers, limits, defaults or version numbers.
            Write 1 to ${limits.maxParagraphs} short paragraphs of plain prose (one to three sentences each), abstract enough to stay valid when small parts of the code change.
            Every claim id you rely on must be listed in the claimIds of the paragraph that uses it; try to use every claim. Never put ids in the text.
            For each candidate entry point you may add a description of at most six words.
            Reply with JSON only: {"paragraphs":[{"text":"...","claimIds":["..."]}],"entryPoints":[{"path":"...","description":"..."}]}
        """.trimIndent()
        val user = buildString {
            appendLine("Topic: ${target.title}")
            appendLine()
            appendLine("Claims:")
            selected.forEach { appendLine("- [${it.id}] ${it.text.trim().replace(Regex("\\s+"), " ")}") }
            if (entries.isNotEmpty()) {
                appendLine()
                appendLine("Candidate entry points:")
                entries.forEach { appendLine("- ${it.path}") }
            }
        }
        val completion = complete(
            CompletionRequest(
                messages = listOf(LlmMessage.system(system), LlmMessage.user(user)),
                temperature = 0.2,
                responseFormat = JsonObject().apply { addProperty("type", "json_object") },
            ))
        return parseDraft(completion.content, selectedIds, entries.map { it.path }.toSet())
    }

    private fun parseDraft(content: String, known: Set<String>, entryPaths: Set<String>): ModelDraft {
        val warnings = mutableListOf<String>()
        val raw = mutableListOf<Pair<String, List<String>>>()
        val descriptions = LinkedHashMap<String, String>()
        val json = parseJson(content)
        if (json != null) {
            json.getAsJsonArray("paragraphs")?.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val text = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val ids = obj.getAsJsonArray("claimIds")?.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }.orEmpty()
                raw += text to (ids + commentIds(text))
            }
            json.getAsJsonArray("entryPoints")?.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val path = obj.get("path")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.trim('/') ?: return@forEach
                val desc = obj.get("description")?.takeIf { it.isJsonPrimitive }?.asString?.let(::stripComments)?.trim().orEmpty()
                if (path in entryPaths && desc.isNotEmpty() && desc.length <= MAX_DESCRIPTION) descriptions[path] = desc.trimEnd('.')
            }
        } else {
            warnings += "Model reply was not JSON; read paragraphs and citation comments from the text"
            content.split(Regex("\\n\\s*\\n")).map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("- ") && !it.startsWith("* ") && !it.startsWith("Entry points") }
                .forEach { raw += it to commentIds(it) }
        }
        val paragraphs = ArrayList<DocParagraph>()
        for ((text, ids) in raw) {
            val clean = stripComments(text).trim().replace(Regex("\\s+"), " ")
            val valid = ids.distinct().filter { it in known }
            ids.distinct().filter { it !in known }.forEach { warnings += "Dropped citation of unknown claim id '$it'" }
            when {
                clean.isEmpty() -> Unit
                valid.isEmpty() -> warnings += "Dropped a paragraph without valid citations"
                else -> paragraphs += DocParagraph(sentence(clean), valid)
            }
        }
        return ModelDraft(paragraphs, descriptions, warnings)
    }

    private fun parseJson(content: String): JsonObject? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JsonParser.parseString(content.substring(start, end + 1)).takeIf { it.isJsonObject }?.asJsonObject
                ?.takeIf { it.get("paragraphs") is JsonArray }
        } catch (_: Exception) {
            null
        }
    }

    private fun commentIds(text: String): List<String> =
        COMMENT.findAll(text).flatMap { it.groupValues[1].split(Regex("[\\s,;]+")) }.filter { it.isNotBlank() }.toList()

    private fun stripComments(text: String) = COMMENT.replace(text, " ")

    // ---- Markdown ----

    private fun markdown(
        target: DocTarget,
        paragraphs: List<DocParagraph>,
        entries: List<EntryPoint>,
        descriptions: Map<String, String>,
        unplaced: List<Claim>,
    ): String = buildString {
        val name = target.title.trim()
        appendLine(if (name.endsWith("flow", ignoreCase = true)) "# $name" else "# $name flow")
        paragraphs.forEach { p ->
            appendLine()
            appendLine(if (limits.embedCitations) "${p.text} <!-- ${p.claimIds.joinToString(" ")} -->" else p.text)
        }
        if (entries.isNotEmpty()) {
            appendLine()
            appendLine("Entry points for further exploration:")
            appendLine()
            entries.forEach { e ->
                val desc = descriptions[e.path]?.let { " — $it" }.orEmpty()
                appendLine("- [${e.label}](${e.link})$desc")
            }
        }
        if (unplaced.isNotEmpty()) {
            appendLine()
            appendLine("## Not placed")
            appendLine()
            unplaced.forEach { appendLine("- ${it.text.trim().replace(Regex("\\s+"), " ")}") }
        }
    }

    companion object {
        private val COMMENT = Regex("<!--\\s*(.*?)\\s*-->", RegexOption.DOT_MATCHES_ALL)
        private const val MAX_DESCRIPTION = 80

        /** Relative Markdown link from the doc at [docPath] to the project-relative [target]. */
        fun relativeLink(docPath: String, target: String): String {
            val docDir = docPath.trim('/').split('/').dropLast(1)
            val parts = target.trim('/').split('/')
            var common = 0
            while (common < docDir.size && common < parts.size - 1 && docDir[common] == parts[common]) common++
            val up = List(docDir.size - common) { ".." }
            return (up + parts.drop(common)).joinToString("/").ifEmpty { "." }
        }
    }
}
