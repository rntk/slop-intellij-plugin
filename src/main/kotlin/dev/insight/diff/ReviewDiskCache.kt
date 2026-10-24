package dev.insight.diff

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Review marks and generated results keyed by evidence. A damaged or unwritable cache never prevents a review. */
class ReviewDiskCache(private val directory: Path) {
    /** Results per slot, shared by every tab of the scope so one tab's save never drops another's entries. */
    private val results = HashMap<String, Map<String, LinkedHashMap<String, String>>>()

    fun read(category: String, slot: String, fingerprint: String): JsonElement? = runCatching {
        val root = JsonParser.parseString(Files.readString(file(category, slot))).asJsonObject
        if (root["version"].asInt == VERSION && root["fingerprint"].asString == fingerprint) root["value"] else null
    }.getOrNull()

    fun write(category: String, slot: String, fingerprint: String, value: JsonElement) {
        runCatching {
            val file = file(category, slot)
            Files.createDirectories(file.parent)
            // Keep generated results out of Git in every project, without editing its ignore rules.
            val ignore = directory.resolve(".gitignore")
            if (!Files.exists(ignore)) runCatching {
                Files.writeString(ignore, "*\n", java.nio.file.StandardOpenOption.CREATE_NEW)
            }
            val temp = Files.createTempFile(file.parent, "review-", ".tmp")
            try {
                val root = JsonObject().apply {
                    addProperty("version", VERSION)
                    addProperty("fingerprint", fingerprint)
                    add("value", value)
                }
                Files.writeString(temp, reviewJson.toJson(root))
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    /** Text entries by content key, oldest first. Callers synchronize on this cache while they read or change them. */
    internal fun results(slot: String): Map<String, LinkedHashMap<String, String>> = results.getOrPut(slot) {
        val saved = read(RESULTS, slot, slot)?.takeIf { it.isJsonObject }?.asJsonObject
        RESULT_KINDS.associateWith { name ->
            LinkedHashMap<String, String>().apply {
                saved?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
                    if (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) put(key, value.asString)
                }
            }
        }
    }

    internal fun writeResults(slot: String) {
        val entries = results[slot] ?: return
        write(RESULTS, slot, slot, JsonObject().apply {
            entries.forEach { (name, texts) -> add(name, JsonObject().apply { texts.forEach { (key, text) -> addProperty(key, text) } }) }
        })
    }

    private fun file(category: String, slot: String) = directory.resolve(reviewFingerprint(category)).resolve("${reviewFingerprint(slot)}.json")

    companion object {
        // Bump when generation rules or the stored representation change.
        private const val VERSION = 2
        const val DIRECTORY = ".insight"
        private const val RESULTS = "results"
        internal const val SUMMARIES = "summaries"
        internal const val TOPICS = "topics"
        internal const val FILLED = "filled"
        internal const val REVIEWED = "reviewed"
        private val RESULT_KINDS = listOf(SUMMARIES, TOPICS, FILLED, REVIEWED)
        private val projects = ConcurrentHashMap<Path, ReviewDiskCache>()
        fun forProject(project: Project): ReviewDiskCache? = project.basePath?.let {
            projects.computeIfAbsent(Path.of(it, DIRECTORY, "review-cache"), ::ReviewDiskCache)
        }
    }
}

/**
 * The saved results of one review scope, merged across snapshots. Every entry is keyed by its evidence, never by the whole
 * snapshot: a summary by its node key, which covers the files and parts beneath the node, and a topic by its file's path and
 * fingerprint. A changed file therefore misses only its own entries and those of the folders, groups and topics above it.
 */
internal class ReviewCacheSession(private val disk: ReviewDiskCache?, private val mode: String, private val paths: Set<String>?) {
    private var slot = ""

    fun load(context: Any) {
        if (disk != null) slot = reviewJson.toJson(listOf(mode, paths?.sorted(), context))
    }

    /** Fills nodes without text from saved entries with the same key. A parent is restored only with all its children. */
    fun restoreSummaries(name: String, current: ChangeSummaries): ChangeSummaries {
        val saved = saved(name) ?: return current
        val restored = current.tree.summarizable.filter { current.text(it.id) == null && it.key.isNotEmpty() }
            .mapNotNull { node -> saved[node.key]?.let { node.id to it } }.toMap(HashMap())
        // Pre-order reversed visits children first, so a dropped child also drops every ancestor restored above it.
        for (node in current.tree.nodes.asReversed()) {
            if (node.id in restored && node.children.any { it !in restored && current.text(it) == null }) restored.remove(node.id)
        }
        if (restored.isEmpty()) return current
        thisLogger().debug("Restored ${restored.size} of ${current.tree.summarizable.size} $name from the review cache")
        return ChangeSummaries(current.tree, current.tree.nodes.mapNotNull { node -> current.text(node.id)?.let { node.id to it } }.toMap() + restored)
    }

    fun restoreReviewed(current: ReviewedFiles, sources: List<ReviewSource>) {
        current.restore(sources, saved(ReviewDiskCache.REVIEWED)?.keys ?: current.keys)
    }

    /** Apply only this operation so stale tabs cannot discard or resurrect unrelated marks. */
    fun saveReviewed(keys: List<String>, reviewed: Boolean) {
        if (disk == null || slot.isEmpty()) return
        synchronized(disk) {
            val saved = disk.results(slot).getValue(ReviewDiskCache.REVIEWED)
            keys.forEach { if (reviewed) saved[it] = "true" else saved.remove(it) }
            disk.writeResults(slot)
        }
    }

    fun restoreTopics(current: ChangeTopics): ChangeTopics {
        val saved = saved(ReviewDiskCache.TOPICS) ?: return current
        var restored = current
        for (file in current.files) {
            if (file.path in restored.labels) continue
            val label = saved[topicKey(file)]?.let(::normalizeTopic) ?: continue
            restored = runCatching { restored.assign(file.path, label) }.getOrDefault(restored)
        }
        return restored
    }

    /** Regenerate: drops the kinds' entries of every snapshot, since parents elsewhere were condensed from the old texts. */
    fun forget(vararg names: String) {
        if (disk == null || slot.isEmpty()) return
        synchronized(disk) { disk.results(slot).let { results -> names.forEach { results[it]?.clear() } } }
    }

    fun save(summaries: ChangeSummaries, topics: ChangeTopics, filled: ChangeSummaries) {
        if (disk == null || slot.isEmpty()) return
        fun texts(value: ChangeSummaries) = value.tree.nodes.mapNotNull { node -> value.text(node.id)?.takeIf { node.key.isNotEmpty() }?.let { node.key to it } }
        synchronized(disk) {
            val results = disk.results(slot)
            merge(results.getValue(ReviewDiskCache.SUMMARIES), texts(summaries))
            merge(results.getValue(ReviewDiskCache.TOPICS), topics.files.mapNotNull { file -> topics.labels[file.path]?.let { topicKey(file) to it } })
            merge(results.getValue(ReviewDiskCache.FILLED), texts(filled))
            disk.writeResults(slot)
        }
    }

    private fun saved(name: String): Map<String, String>? {
        if (disk == null || slot.isEmpty()) return null
        return synchronized(disk) { HashMap(disk.results(slot).getValue(name)) }
    }

    /** The current entries become the newest; the oldest entries of other snapshots go first once the slot is full. */
    private fun merge(saved: LinkedHashMap<String, String>, current: List<Pair<String, String>>) {
        current.forEach { (key, text) -> saved.remove(key); saved[key] = text }
        val iterator = saved.entries.iterator()
        var excess = saved.size - maxOf(MAX_ENTRIES, current.size)
        while (excess-- > 0) { iterator.next(); iterator.remove() }
    }

    private fun topicKey(file: ReviewSource) = reviewFingerprint("topic", file.path, file.fingerprint)

    companion object {
        /** Per kind and slot; enough for several snapshots of a large scope, while keeping each save small. */
        const val MAX_ENTRIES = 4_000
    }
}
