package dev.insight.enrichment

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * [EnrichmentCache] persisted as one JSON file. Loaded on first access, written by [flush] via a temp file and an
 * atomic move. Values are converted by hand instead of by reflection, so missing or null JSON fields get the
 * declared defaults (Gson would bypass Kotlin default values) and one damaged entry never discards the rest.
 */
class FileEnrichmentCache(private val file: Path) : EnrichmentCache {
    private class State {
        val nodes = ConcurrentHashMap<String, NodeAnnotation>()
        val edges = ConcurrentHashMap<String, EdgeAnnotation>()
        val texts = ConcurrentHashMap<String, String>()
    }

    private val state by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { load() }
    @Volatile private var dirty = false
    private val writeLock = Any()

    override fun node(key: String) = state.nodes[key]
    override fun putNode(key: String, value: NodeAnnotation) { state.nodes[key] = value; dirty = true }
    override fun edge(key: String) = state.edges[key]
    override fun putEdge(key: String, value: EdgeAnnotation) { state.edges[key] = value; dirty = true }
    override fun text(key: String) = state.texts[key]
    override fun putText(key: String, value: String) { state.texts[key] = value; dirty = true }

    override fun flush() = synchronized(writeLock) { writeIfDirty() }

    private fun writeIfDirty() {
        if (!dirty) return
        dirty = false
        val current = state
        val root = JsonObject().apply {
            addProperty("version", VERSION)
            add("nodes", JsonObject().also { o -> current.nodes.forEach { (k, v) -> o.add(k, nodeJson(v)) } })
            add("edges", JsonObject().also { o -> current.edges.forEach { (k, v) -> o.add(k, edgeJson(v)) } })
            add("texts", JsonObject().also { o -> current.texts.forEach { (k, v) -> o.addProperty(k, v) } })
        }
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            val temp = Files.createTempFile(file.toAbsolutePath().parent, file.fileName.toString(), ".tmp")
            try {
                Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8)
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        } catch (e: java.io.IOException) {
            dirty = true
            LOG.warn("Cannot write enrichment cache $file", e)
        }
    }

    private fun load(): State {
        val loaded = State()
        if (!Files.isRegularFile(file)) return loaded
        try {
            val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).asJsonObject
            if (root["version"]?.asInt != VERSION) {
                LOG.info("Ignoring enrichment cache $file with format version ${root["version"]}")
                return loaded
            }
            fun <T> read(name: String, parse: (JsonElement) -> T?, into: MutableMap<String, T>) {
                for ((key, value) in root.getAsJsonObject(name).entrySet()) {
                    try {
                        parse(value)?.let { into[key] = it }
                    } catch (e: RuntimeException) {
                        LOG.debug("Skipping unreadable enrichment cache entry $key", e)
                    }
                }
            }
            read("nodes", ::nodeValue, loaded.nodes)
            read("edges", ::edgeValue, loaded.edges)
            read("texts", { it.asString }, loaded.texts)
        } catch (e: Exception) {
            LOG.warn("Ignoring unreadable enrichment cache $file", e)
            return State()
        }
        return loaded
    }

    private fun nodeJson(node: NodeAnnotation) = JsonObject().apply {
        add("concerns", JsonObject().also { o -> node.concerns.forEach { (k, v) -> o.addProperty(k, finite(v)) } })
        node.phase?.let { add("phase", choiceJson(it)) }
        node.layer?.let { add("layer", choiceJson(it)) }
        node.importance?.let { add("importance", JsonObject().apply {
            addProperty("score", finite(it.score)); addProperty("max", it.max); addProperty("confidence", finite(it.confidence))
        }) }
        node.entryPoint?.let { addProperty("entryPoint", finite(it)) }
        node.utility?.let { addProperty("utility", finite(it)) }
        node.domain?.let { add("domain", choiceJson(it)) }
    }

    private fun choiceJson(value: ChoiceValue) = JsonObject().apply {
        addProperty("choice", value.choice)
        add("probabilities", JsonObject().also { o -> value.probabilities.forEach { (k, v) -> o.addProperty(k, finite(v)) } })
        addProperty("confidence", finite(value.confidence))
    }

    private fun edgeJson(edge: EdgeAnnotation) = JsonObject().apply {
        addProperty("errorPath", finite(edge.errorPath))
        addProperty("continuesOperation", finite(edge.continuesOperation))
    }

    private fun nodeValue(element: JsonElement): NodeAnnotation {
        val o = element.asJsonObject
        return NodeAnnotation(
            concerns = o.obj("concerns")?.let(::doubles).orEmpty(),
            phase = o.obj("phase")?.let(::choice),
            layer = o.obj("layer")?.let(::choice),
            importance = o.obj("importance")?.let { ScoreValue(it.double("score") ?: 0.0, it["max"].asInt, it.double("confidence") ?: 0.0) },
            entryPoint = o.double("entryPoint"),
            utility = o.double("utility"),
            domain = o.obj("domain")?.let(::choice),
        )
    }

    private fun choice(o: JsonObject) = ChoiceValue(o["choice"].asString, o.obj("probabilities")?.let(::doubles).orEmpty(), o.double("confidence") ?: 0.0)

    private fun edgeValue(element: JsonElement): EdgeAnnotation {
        val o = element.asJsonObject
        return EdgeAnnotation(o.double("errorPath") ?: 0.0, o.double("continuesOperation") ?: 0.0)
    }

    private fun doubles(o: JsonObject): Map<String, Double> =
        o.entrySet().filter { !it.value.isJsonNull }.associate { it.key to it.value.asDouble }

    private fun JsonObject.obj(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.double(name: String): Double? = get(name)?.takeIf { it !is JsonNull && it.isJsonPrimitive }?.asDouble

    /** JSON has no NaN or infinity. */
    private fun finite(value: Double) = if (value.isFinite()) value else 0.0

    companion object {
        /** Bump when the file layout or the meaning of stored values changes; older files are then discarded. */
        const val VERSION = 1
        private val LOG = logger<FileEnrichmentCache>()
        private val GSON = GsonBuilder().setPrettyPrinting().create()
        private val instances = ConcurrentHashMap<Path, FileEnrichmentCache>()

        /** One shared instance per project, stored in the IDE system directory. */
        fun forProject(project: Project): FileEnrichmentCache {
            val path = Path.of(PathManager.getSystemPath(), "insight-enrichment", "${project.locationHash}.json")
            return instances.computeIfAbsent(path, ::FileEnrichmentCache)
        }
    }
}
