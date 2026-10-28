package dev.insight.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/** One HTTP attempt: the raw request and the response (or failure) it got. Retries are separate exchanges. */
data class ApiExchange(
    val id: Long,
    val api: String,
    val startedAt: Instant,
    val method: String,
    val url: String,
    val attempt: Int,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val durationMs: Long? = null,
    val status: Int? = null,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String? = null,
    /** Transport failure, cancellation, or a response the client could not use. */
    val error: String? = null,
) {
    val pending get() = durationMs == null
    val failed get() = error != null || (status != null && status !in 200..299)
}

/**
 * Records raw API traffic for debugging: request and response bodies, paired per HTTP attempt.
 * Keeps the newest [maxEntries] in memory and appends completed exchanges to [file] as JSON lines,
 * reloading them on construction. Credentials in `Authorization`, `Proxy-Authorization`, `x-api-key` and `api-key` headers are never recorded.
 * Thread-safe; listeners run on the recording thread.
 */
class ApiTrafficLog(val api: String, val file: Path? = null, private val maxEntries: Int = 500) {
    private val entries = ArrayList<ApiExchange>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val ids = AtomicLong()
    private var persisted = 0
    private var clearedThroughId = 0L
    /** Whether the last write failed, so an unwritable log is reported once rather than on every exchange. */
    private var writeFailed = false

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        file?.let(::load)
    }

    fun entries(): List<ApiExchange> = synchronized(this) { entries.toList() }

    /** Records an in-flight request; complete it with [ApiCall.respond] or [ApiCall.fail]. */
    fun begin(request: HttpRequest, body: String, attempt: Int): ApiCall {
        val exchange = synchronized(this) {
            val exchange = ApiExchange(ids.incrementAndGet(), api, Instant.now(), request.method(), request.uri().toString(),
                attempt, redact(request.headers()), body)
            entries += exchange
            if (entries.size > maxEntries) entries.subList(0, entries.size - maxEntries).clear()
            exchange
        }
        notifyListeners()
        return ApiCall(this, exchange)
    }

    fun clear() {
        synchronized(this) {
            clearedThroughId = ids.get()
            entries.clear()
            file?.let { try { Files.deleteIfExists(it) } catch (e: IOException) { LOG.warn("Could not delete API traffic log $it", e) } }
            persisted = 0
        }
        notifyListeners()
    }

    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    internal fun complete(exchange: ApiExchange) {
        synchronized(this) {
            // Calls started before Clear must not recreate deleted history, even if already evicted.
            if (exchange.id <= clearedThroughId) return
            val index = entries.indexOfFirst { it.id == exchange.id }
            if (index >= 0) entries[index] = exchange
            file?.let { append(it, exchange) }
        }
        notifyListeners()
    }

    private fun notifyListeners() = listeners.forEach { it() }

    private fun append(path: Path, exchange: ApiExchange) {
        try {
            Files.createDirectories(path.toAbsolutePath().parent)
            // Compact the file once it holds twice the retained history, so it never grows without bound.
            if (persisted >= maxEntries * 2) {
                val completed = entries.filterNot { it.pending }
                Files.write(path, completed.map(::toJsonLine))
                persisted = completed.size
            } else {
                Files.writeString(path, toJsonLine(exchange) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                persisted++
            }
            writeFailed = false
        } catch (e: IOException) {
            // Debug logging must never break an API call.
            if (writeFailed) LOG.debug("Could not write API traffic log $path", e)
            else LOG.warn("Could not write API traffic log $path; further failures are logged at debug level", e)
            writeFailed = true
        }
    }

    private fun load(path: Path) {
        if (!Files.isRegularFile(path)) return
        val lines = try {
            Files.readAllLines(path)
        } catch (e: IOException) {
            LOG.warn("Could not read API traffic log $path", e)
            emptyList()
        }
        val loaded = lines.mapNotNull { line ->
            try {
                fromJson(JsonParser.parseString(line).asJsonObject)
            } catch (e: RuntimeException) {
                LOG.debug("Skipping unreadable API traffic log line in $path", e)
                null
            }
        }
        entries += loaded.sortedBy { it.id }.takeLast(maxEntries)
        persisted = lines.size
        ids.set(loaded.maxOfOrNull { it.id } ?: 0)
    }

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ApiTrafficLog::class.java)
        private val gson = GsonBuilder().disableHtmlEscaping().create()
        private val prettyGson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().serializeNulls().create()

        private fun redact(headers: HttpHeaders): Map<String, String> = headers.map().mapValues { (name, values) ->
            // Keep the scheme ("Bearer") for debugging; a value without one is redacted whole.
            if (name.equals("Authorization", true) || name.equals("Proxy-Authorization", true))
                values.joinToString(", ") { if (' ' in it.trim()) it.trim().substringBefore(' ') + " <redacted>" else "<redacted>" }
            // Anthropic sends its key in x-api-key; Azure-style gateways use api-key.
            else if (name.equals("x-api-key", true) || name.equals("api-key", true)) "<redacted>"
            else values.joinToString(", ")
        }

        /** Pretty-prints JSON bodies; anything else is returned unchanged. */
        fun pretty(body: String?): String {
            if (body.isNullOrBlank()) return body.orEmpty()
            return runCatching { prettyGson.toJson(JsonParser.parseString(body)) }.getOrDefault(body)
        }

        /** A self-contained JSON record of [exchange], with JSON bodies embedded as JSON. */
        fun toJson(exchange: ApiExchange): JsonObject = JsonObject().apply {
            addProperty("id", exchange.id)
            addProperty("api", exchange.api)
            addProperty("startedAt", exchange.startedAt.toString())
            exchange.durationMs?.let { addProperty("durationMs", it) }
            add("request", JsonObject().apply {
                addProperty("method", exchange.method)
                addProperty("url", exchange.url)
                addProperty("attempt", exchange.attempt)
                add("headers", gson.toJsonTree(exchange.requestHeaders))
                add("body", bodyJson(exchange.requestBody))
            })
            if (exchange.status != null || exchange.responseBody != null) add("response", JsonObject().apply {
                exchange.status?.let { addProperty("status", it) }
                add("headers", gson.toJsonTree(exchange.responseHeaders))
                exchange.responseBody?.let { add("body", bodyJson(it)) }
            })
            exchange.error?.let { addProperty("error", it) }
        }

        fun toPrettyJson(exchanges: List<ApiExchange>): String =
            prettyGson.toJson(com.google.gson.JsonArray().apply { exchanges.forEach { add(toJson(it)) } })

        /** A readable request/response transcript, convenient to paste into an issue or a coding agent. */
        fun toText(exchange: ApiExchange): String = buildString {
            appendLine("### ${exchange.api} #${exchange.id} — ${exchange.startedAt}" +
                (exchange.durationMs?.let { " (${it} ms)" } ?: " (pending)"))
            appendLine()
            appendLine("REQUEST ${exchange.method} ${exchange.url}" + if (exchange.attempt > 0) " (retry ${exchange.attempt})" else "")
            exchange.requestHeaders.forEach { (name, value) -> appendLine("$name: $value") }
            appendLine("```json")
            appendLine(pretty(exchange.requestBody))
            appendLine("```")
            appendLine()
            appendLine("RESPONSE " + (exchange.status?.toString() ?: "—"))
            exchange.responseHeaders.forEach { (name, value) -> appendLine("$name: $value") }
            exchange.responseBody?.let {
                appendLine("```json")
                appendLine(pretty(it))
                appendLine("```")
            }
            exchange.error?.let { appendLine("ERROR: $it") }
        }

        private fun bodyJson(body: String) = runCatching { JsonParser.parseString(body) }
            .getOrNull()?.takeIf { it.isJsonObject || it.isJsonArray } ?: com.google.gson.JsonPrimitive(body)

        private fun toJsonLine(exchange: ApiExchange) = gson.toJson(toJson(exchange))

        private fun fromJson(json: JsonObject): ApiExchange {
            val request = json.getAsJsonObject("request")
            val response = json.getAsJsonObject("response")
            fun JsonObject.headers() = getAsJsonObject("headers")?.entrySet()?.associate { it.key to it.value.asString }.orEmpty()
            fun JsonObject.body() = get("body")?.let { if (it.isJsonPrimitive) it.asString else gson.toJson(it) }
            return ApiExchange(
                id = json["id"].asLong,
                api = json["api"].asString,
                startedAt = Instant.parse(json["startedAt"].asString),
                method = request["method"].asString,
                url = request["url"].asString,
                attempt = request["attempt"]?.asInt ?: 0,
                requestHeaders = request.headers(),
                requestBody = request.body().orEmpty(),
                durationMs = json["durationMs"]?.asLong ?: 0,
                status = response?.get("status")?.asInt,
                responseHeaders = response?.headers().orEmpty(),
                responseBody = response?.body(),
                error = json["error"]?.asString,
            )
        }
    }
}

/** An in-flight exchange started by [ApiTrafficLog.begin]. */
class ApiCall internal constructor(private val log: ApiTrafficLog, private val exchange: ApiExchange) {
    /** Records the response; [error] marks a response the client rejected (for example, malformed JSON). */
    fun respond(response: HttpResponse<String>, error: Throwable? = null) = log.complete(exchange.copy(
        durationMs = elapsed(),
        status = response.statusCode(),
        responseHeaders = response.headers().map().mapValues { it.value.joinToString(", ") },
        responseBody = response.body(),
        error = error?.let(::describe),
    ))

    fun fail(error: Throwable) = log.complete(exchange.copy(durationMs = elapsed(), error = describe(error)))

    private fun elapsed() = java.time.Duration.between(exchange.startedAt, Instant.now()).toMillis()

    private fun describe(error: Throwable) =
        if (error is CancellationException) "Cancelled" else "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
}
