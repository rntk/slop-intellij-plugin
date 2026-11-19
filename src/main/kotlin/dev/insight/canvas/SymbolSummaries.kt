package dev.insight.canvas

import com.google.gson.JsonPrimitive
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.insight.callgraph.DeclarationSpan
import dev.insight.core.Hashing
import dev.insight.llm.CompletionFn
import dev.insight.review.ReviewDiskCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun DeclarationSpan.summarySource(path: String, text: String): SymbolSummarySource? {
    if (offsets.isEmpty() || offsets.first < 0 || offsets.last >= text.length) return null
    return SymbolSummarySource(path, firstLine, column, name, text.substring(offsets).trimEnd('\n'))
}

internal data class SymbolSummarySource(
    val path: String,
    val firstLine: Int,
    val column: Int,
    val name: String?,
    val text: String,
) {
    internal val input = CanvasSummaryInput(CardKind.SYMBOL, name, path, firstLine, text, symbolKind = "declaration")
    internal val key by lazy {
        Hashing.sha256(column.toString(), text, *canvasSummaryMessages(input, sourceOnly = true).map { it.content }.toTypedArray())
    }
}

/** Project-local answers for declaration summaries shared by Canvas sessions and the Charts preview. */
@Service(Service.Level.PROJECT)
internal class SymbolSummaryCache(project: Project) {
    private val disk = ReviewDiskCache.forProject(project)
    private val entries = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>) = size > 256
    }
    private val locks = Array(32) { Mutex() }

    fun checked(key: String): Boolean = synchronized(entries) { key in entries }
    fun peek(key: String): String? = synchronized(entries) { entries[key] }

    private fun remember(key: String, text: String?) { synchronized(entries) { entries[key] = text } }
    private fun lock(key: String) = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    /** Read or generate on a background dispatcher so disk access stays off the EDT. */
    suspend fun read(key: String): String? = lock(key).withLock { load(key) }

    suspend fun summarize(
        source: SymbolSummarySource,
        complete: () -> CompletionFn,
        refresh: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): String = lock(source.key).withLock {
        if (!refresh) load(source.key)?.let { return@withLock it }
        val text = CanvasSummarizer({ request -> complete()(request) }).summarizeDeclaration(source.input, onProgress)
        remember(source.key, text)
        disk?.write(CATEGORY, source.key, source.key, JsonPrimitive(text))
        text
    }

    private fun load(key: String): String? {
        synchronized(entries) { if (key in entries) return entries[key] }
        val value = (disk?.read(CATEGORY, key, key) as? JsonPrimitive)
            ?.takeIf { it.isString }?.asString?.takeIf { it.isNotBlank() }
        remember(key, value)
        return value
    }

    private companion object {
        const val CATEGORY = "symbol-summaries"
    }
}
