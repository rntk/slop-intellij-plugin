package dev.insight.enrichment

import com.google.gson.JsonObject
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class GroupLabelResult(val labels: Map<String, GroupLabel>, val requests: Int, val failures: Int)

/**
 * Names files and directories for the overview with the generative model. Files are labeled from an outline of
 * their top-level declarations, then directories bottom-up from their children's labels. Both are keyed by
 * project-relative path. Directories are those [dev.insight.callgraph.GraphHierarchy] shows: below the deepest
 * directory shared by all files, skipping directories without files that hold only one subdirectory.
 * A failed batch leaves its groups unlabeled.
 */
class GroupLabeler(
    private val complete: suspend (CompletionRequest) -> Completion,
    private val cache: EnrichmentCache,
    private val concurrency: Int = 2,
    private val batchSize: Int = 15,
) {
    suspend fun label(input: EnrichmentInput, progress: EnrichmentProgress = EnrichmentProgress.NONE): GroupLabelResult {
        val requests = AtomicInteger()
        val failures = AtomicInteger()
        val labels = ConcurrentHashMap<String, GroupLabel>()
        val outlines = fileOutlines(input)
        val directories = directories(outlines.keys)
        val total = outlines.size + directories.size
        val done = AtomicInteger()

        forEachConcurrently(outlines.entries.chunked(batchSize), concurrency) { batch ->
            val items = batch.map { (path, outline) -> path to outline }
            labels += ask(FILE_TASK, items, FILE_ITEM, requests, failures)
            progress.report("Labeling files", done.addAndGet(batch.size), total)
        }
        // Deepest first, so every directory sees its subdirectories' labels.
        directories.entries.groupBy { it.key.count { c -> c == '/' } }.toSortedMap(reverseOrder()).values.forEach { level ->
            forEachConcurrently(level.chunked(batchSize), concurrency) { batch ->
                val items = batch.map { (path, children) -> path to directoryOutline(path, children, directories.keys, labels) }
                labels += ask(DIRECTORY_TASK, items, DIRECTORY_ITEM, requests, failures)
                progress.report("Labeling directories", done.addAndGet(batch.size), total)
            }
        }
        return GroupLabelResult(labels.toMap(), requests.get(), failures.get())
    }

    private suspend fun ask(
        task: String, items: List<Pair<String, String>>, item: String,
        requests: AtomicInteger, failures: AtomicInteger,
    ): Map<String, GroupLabel> {
        val user = buildString {
            items.forEachIndexed { index, (_, outline) -> append("[").append(index + 1).append("] ").append(outline).append("\n\n") }
        }
        val system = "$CODE_AS_DATA\n\n$task\n\nFor every numbered $item return {\"id\": its number, \"label\": a 2-5 word noun phrase " +
            "naming what it does in this project, \"summary\": one short sentence}. Be specific (\"Call graph layout\", not " +
            "\"Utility functions\"); do not repeat the file or directory name. Answer only with JSON {\"labels\": [...]}. Think briefly."
        val answer = cachedJson(cache, "labels", complete, system, user, schema, maxTokens = 3000 + 150 * items.size) {
            requests.incrementAndGet()
        }
        if (answer == null) {
            failures.incrementAndGet()
            return emptyMap()
        }
        return answer.getAsJsonArray("labels")?.mapNotNull { element ->
            val entry = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val path = entry.jsonText("id")?.toDoubleOrNull()?.toInt()?.let { items.getOrNull(it - 1)?.first }
            val label = entry.jsonText("label")?.trim('"', '.', ' ')?.clipLine(60)
            if (path == null || label.isNullOrEmpty()) null
            else path to GroupLabel(path, label, entry.jsonText("summary")?.clipLine(240))
        }.orEmpty().toMap()
    }

    /** Path → outline of each file: its path and the signatures of its top-level declarations, with members of classes. */
    private fun fileOutlines(input: EnrichmentInput): Map<String, String> {
        val parent = HashMap<Int, Int>()
        input.graph.edges.forEach { if (it.kind == RelationKind.CONTAINS && it.caller != it.callee) parent.putIfAbsent(it.callee, it.caller) }
        val children = parent.entries.groupBy({ it.value }, { it.key })
        val byPath = input.facts.values.filter { it.path != null }.groupBy { it.path!! }
        val outlines = sortedMapOf<String, String>()
        for ((path, facts) in byPath) {
            val file = facts.firstOrNull { it.kind == SymbolKind.FILE } ?: continue
            val declarations = facts.filter { it.kind in OUTLINED }.sortedBy { it.id }
            val topLevel = declarations.filter { parent[it.id] == null || parent[it.id] == file.id }
            outlines[path] = buildString {
                append(path)
                for (declaration in topLevel) {
                    if (length > MAX_OUTLINE) { append("\n- …"); break }
                    append("\n- ").append(declaration.signature.ifBlank { declaration.name }.clipLine(160))
                    val members = children[declaration.id].orEmpty().mapNotNull { input.facts[it] }
                        .filter { it.kind == SymbolKind.FUNCTION || it.kind == SymbolKind.PROPERTY }.sortedBy { it.id }
                    if (members.isNotEmpty()) append(" { ").append(members.joinToString(", ") { it.name }.clipLine(200)).append(" }")
                }
            }
        }
        return outlines
    }

    /** Shown directory → its direct children (files and shown directories), by project-relative path. */
    private fun directories(files: Collection<String>): Map<String, List<String>> {
        val parents = files.associateWith { it.substringBeforeLast('/', "") }
        val shared = parents.values.map { it.split('/') }.reduceOrNull { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }
            ?.joinToString("/").orEmpty()
        val all = sortedSetOf<String>()
        parents.values.forEach { var dir = it; while (dir.length > shared.length) { all += dir; dir = dir.substringBeforeLast('/', "") } }
        val subdirectories = all.groupBy { it.substringBeforeLast('/', "") }
        val filesIn = files.groupBy { it.substringBeforeLast('/', "") }
        val shown = all.filterTo(sortedSetOf()) { filesIn[it] != null || subdirectories[it].orEmpty().size != 1 }
        // Each shown directory lists its files and its nearest shown subdirectories.
        fun nearestShown(dir: String): List<String> =
            if (dir in shown) listOf(dir) else subdirectories[dir].orEmpty().flatMap(::nearestShown)
        return shown.associateWith { dir -> subdirectories[dir].orEmpty().flatMap(::nearestShown) + filesIn[dir].orEmpty() }
    }

    private fun directoryOutline(path: String, children: List<String>, directories: Set<String>, labels: Map<String, GroupLabel>) = buildString {
        append(path).append('/')
        for (child in children) {
            if (length > MAX_OUTLINE) { append("\n- …"); break }
            val name = child.removePrefix("$path/") + if (child in directories) "/" else ""
            append("\n- ").append(name)
            labels[child]?.let { append(": ").append(it.label); it.summary?.let { s -> append(" — ").append(s) } }
        }
    }

    private companion object {
        val OUTLINED = setOf(SymbolKind.CLASS, SymbolKind.FUNCTION, SymbolKind.PROPERTY)
        const val MAX_OUTLINE = 1500
        const val FILE_TASK = "Label each source file below from its path and an outline of its top-level declarations."
        const val DIRECTORY_TASK = "Label each directory below from its files and subdirectories, which are already labeled."
        const val FILE_ITEM = "file"
        const val DIRECTORY_ITEM = "directory"
        val schema: JsonObject = JsonSchemas.obj("labels" to JsonSchemas.array(JsonSchemas.obj(
            "id" to JsonSchemas.integer(), "label" to JsonSchemas.string(), "summary" to JsonSchemas.string())))
    }
}
