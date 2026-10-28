package dev.insight.entrypoints

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonParser
import dev.insight.decisions.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Identifies the failing batch while keeping the original HTTP response available in the cause. */
class EntryPointDecisionException(
    val path: String,
    val lines: List<Int>,
    val questionCount: Int,
    val stateChars: Int,
    cause: DecisionApiException,
) : IOException(buildString {
    append("Decisions API returned HTTP ${cause.statusCode} for $path:${lines.joinToString(",")}")
    append(" ($questionCount questions, $stateChars state characters)")
    // Only surface a structured server message, never dump a raw response or request into the UI.
    val detail = runCatching {
        val root = JsonParser.parseString(cause.responseBody).asJsonObject
        val error = root.get("error")
        val value = if (error?.isJsonObject == true) error.asJsonObject.get("message")
            else root.get("detail") ?: error
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.onFailure {
        if (it is com.intellij.openapi.diagnostic.ControlFlowException || it is java.util.concurrent.CancellationException) throw it
        com.intellij.openapi.diagnostic.Logger.getInstance(EntryPointDecisionException::class.java).debug("Decisions API error body is not structured JSON", it)
    }.getOrNull()
    detail?.trim()?.takeIf { it.isNotEmpty() }?.let { append(": ${it.take(300)}") }
}, cause)

/**
 * Selects real source locations with typed judgments; no generative LLM is involved.
 * Code finds declaration and registration candidates; the model only judges them.
 *
 * [threshold] and [maxRequestBytes] are starting points, not validated values: tune the threshold on labeled
 * projects, and the byte budget against the server's context. [concurrency] bounds files evaluated in parallel.
 */
class DecisionsEntryPointFinder(
    private val evaluate: suspend (DecisionRequest) -> DecisionResponse,
    private val snapshot: ProjectSnapshot,
    private val read: suspend (String) -> String?,
    private val maxFiles: Int = 200,
    private val threshold: Double = 0.7,
    private val maxRequestBytes: Int = MAX_REQUEST_BYTES,
    private val concurrency: Int = 4,
    private val cache: EntryPointDecisionCache = EntryPointDecisionCache(),
) {
    init {
        require(maxFiles > 0); require(threshold in 0.0..1.0)
        require(maxRequestBytes > 0); require(concurrency > 0)
    }

    private class Candidate(val line: Int, val name: String, val code: String) {
        val annotations = mutableListOf<String>()
        var hint: String? = null
    }

    private class FileScan(
        val readable: Boolean,
        val entries: List<EntryPoint> = emptyList(),
        val requests: Int = 0,
        val candidates: Int = 0,
        val skipped: Int = 0,
        val staleHints: Int = 0,
        val cacheHits: Int = 0,
    )

    suspend fun find(hints: List<EntryPointHint>, progress: EntryPointProgress = EntryPointProgress.NONE): EntryPointResult {
        progress.report("Listing project files…")
        val eligible = snapshot.files().filter { supported(it) && !EntryPointHints.isTest(it) }
        val hinted = hints.mapTo(hashSetOf()) { it.path }
        // Hinted files first, then manifests (they also supply registration evidence for source files).
        val paths = eligible.sortedWith(compareBy<String> { it !in hinted }.thenBy { !isManifest(it) }.thenBy { it }).take(maxFiles)
        val manifestTexts = linkedMapOf<String, String?>()
        for (path in paths.filter(::isManifest)) {
            currentCoroutineContext().ensureActive()
            manifestTexts[path] = read(path)
        }
        val manifestLines = manifestTexts.mapNotNull { (path, text) -> text?.let { path to it.lines() } }.toMap()
        val started = AtomicInteger()
        val permits = Semaphore(concurrency)
        val scans = coroutineScope {
            paths.map { path ->
                async {
                    permits.withPermit {
                        currentCoroutineContext().ensureActive()
                        progress.report("Decisions API: reading $path (${started.incrementAndGet()}/${paths.size})…")
                        val text = if (path in manifestTexts) manifestTexts[path] else read(path)
                        if (text == null) FileScan(readable = false)
                        else scan(path, text, hints.filter { it.path == path }, registrations(path, text, manifestLines), progress)
                    }
                }
            }.awaitAll()
        }
        val filesRead = paths.filterIndexed { index, _ -> scans[index].readable }
        val skipped = scans.sumOf { it.skipped }
        val stale = scans.sumOf { it.staleHints }
        val requests = scans.sumOf { it.requests }
        val cacheHits = scans.sumOf { it.cacheHits }
        return EntryPointResult(scans.flatMap { it.entries }.sortedWith(compareBy<EntryPoint> { it.path }.thenBy { it.line }),
            "Evaluated ${scans.sumOf { it.candidates }} source candidates; showing entry probability ≥ ${(threshold * 100).toInt()}%. " +
                "Scanned ${filesRead.size} of ${eligible.size} supported non-test files. " +
                "${eligible.size - paths.size} files outside the $maxFiles-file limit; ${paths.size - filesRead.size} unreadable" +
                (if (skipped > 0) "; $skipped candidates too large to evaluate" else "") +
                (if (stale > 0) "; $stale graph hints no longer match the source" else "") + ". " +
                "Candidate extraction may miss dynamic registrations.",
            filesRead, paths.filterIndexed { index, _ -> scans[index].cacheHits > 0 && scans[index].requests == 0 },
            requests, requests == 0 && cacheHits > 0)
    }

    private suspend fun scan(path: String, text: String, hints: List<EntryPointHint>,
        registrations: List<String>, progress: EntryPointProgress): FileScan {
        val lines = text.lines()
        val candidates = candidates(path, lines)
        var stale = 0
        for (hint in hints) {
            val line = hintLine(hint, lines)
            if (line == null) { stale++; continue }
            val candidate = candidates.getOrPut(line) { Candidate(line, simpleName(hint.symbol), lines[line - 1].trim()) }
            candidate.hint = hint.reason
        }
        val entries = mutableListOf<EntryPoint>()
        var requests = 0
        var evaluated = 0
        var skipped = 0
        var cacheHits = 0
        // Bound model work as well as wire size: every candidate adds two questions and a full kind rubric.
        val batches = candidates.values.asSequence().map { candidate ->
            val batch = listOf(candidate)
            batch to boundedRequest(path, lines, registrations, batch)
        }
        for ((batch, request) in batches) {
            currentCoroutineContext().ensureActive()
            if (request == null) { skipped += batch.size; continue }
            val key = cache.key(request)
            val cached = cache.response(key)
            val response = cached ?: run {
                progress.report("Decisions API: evaluating ${batch.size} candidates in $path…")
                try {
                    evaluate(request)
                } catch (e: DecisionApiException) {
                    throw EntryPointDecisionException(path, batch.map { it.line }, request.questions.size, request.state.toString().length, e)
                }
            }
            if (cached == null) requests++ else cacheHits++
            evaluated += batch.size
            batch.forEachIndexed { index, candidate ->
                // The client already validates that every question has an answer of the requested type.
                val probability = (response.answers.getValue("entry_$index") as DecisionAnswer.Noul).noul
                if (probability < threshold) return@forEachIndexed
                val kind = response.answers.getValue("kind_$index") as DecisionAnswer.Choice
                val kindNote = if (kind.confidence < KIND_CONFIDENCE) "; category uncertain (${(kind.confidence * 100).toInt()}% confidence)" else ""
                entries += EntryPoint(path, candidate.line, candidate.name, EntryPointKind.fromWire(kind.choice),
                    candidate.code.take(120), "Decisions API entry probability: ${(probability * 100).toInt()}%$kindNote", probability)
            }
            if (cached == null) cache.putResponse(key, response)
        }
        return FileScan(true, entries, requests, evaluated, skipped, stale, cacheHits)
    }

    /** One candidate per line; registration annotations are folded into the declaration they annotate. */
    private fun candidates(path: String, lines: List<String>): MutableMap<Int, Candidate> {
        val found = sortedMapOf<Int, Candidate>()
        val name = path.substringAfterLast('/')
        MANIFEST_PATTERNS[manifestKind(name)]?.let { pattern ->
            lines.forEachIndexed { index, line ->
                if (pattern.containsMatchIn(line)) found[index + 1] = Candidate(index + 1, line.trim().take(80), line.trim())
            }
            return found
        }
        if (lines.firstOrNull()?.startsWith("#!") == true) found[1] = Candidate(1, name, lines[0].trim())
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (isComment(trimmed)) return@forEachIndexed
            val declared = declarationName(line)
            if (isAnnotation(trimmed) && declared == null) {
                // An annotation on its own line registers the declaration below it, not a separate entry point.
                if (!REGISTRATION.containsMatchIn(trimmed)) return@forEachIndexed
                val target = annotated(lines, index)
                val candidate = found.getOrPut(target + 1) {
                    Candidate(target + 1, declarationName(lines[target]) ?: lines[target].trim().take(80), lines[target].trim())
                }
                if (target != index) candidate.annotations += trimmed
            } else if (declared != null || REGISTRATION.containsMatchIn(line)) {
                found.getOrPut(index + 1) { Candidate(index + 1, declared ?: trimmed.take(80), trimmed) }
            }
        }
        return found
    }

    /** The first code line after [annotation], skipping further annotations, comments and blank lines. */
    private fun annotated(lines: List<String>, annotation: Int): Int {
        for (next in annotation + 1..minOf(lines.lastIndex, annotation + 6)) {
            val trimmed = lines[next].trim()
            if (trimmed.isEmpty() || isComment(trimmed) || (isAnnotation(trimmed) && declarationName(trimmed) == null)) continue
            return next
        }
        return annotation
    }

    /** The current line of a graph hint: its recorded line if the symbol is still there, else the nearest declaration of it. */
    private fun hintLine(hint: EntryPointHint, lines: List<String>): Int? {
        val name = simpleName(hint.symbol).takeIf { it.isNotEmpty() } ?: return null
        val pattern = token(name)
        hint.line?.takeIf { it in 1..lines.size && pattern.containsMatchIn(lines[it - 1]) }?.let { return it }
        val declarations = lines.indices.filter { declarationName(lines[it]) == name }.map { it + 1 }
        return hint.line?.let { line -> declarations.minByOrNull { abs(it - line) } } ?: declarations.firstOrNull()
    }

    /** Manifest lines elsewhere in the project that name [path], so registration in another file counts as evidence. */
    private fun registrations(path: String, text: String, manifests: Map<String, List<String>>): List<String> {
        if (isManifest(path)) return emptyList()
        val patterns = referenceNames(path, text).map(::token)
        if (patterns.isEmpty()) return emptyList()
        return manifests.asSequence().flatMap { (manifest, lines) ->
            lines.asSequence().withIndex()
                .filter { (_, line) -> patterns.any { it.containsMatchIn(line) } }
                .map { (index, line) -> "$manifest:${index + 1}: ${line.trim()}" }
        }.take(MAX_REGISTRATIONS).toList()
    }

    /** How manifests refer to a file: its file name, JVM class name, or Python module path. */
    private fun referenceNames(path: String, text: String): Set<String> {
        val fileName = path.substringAfterLast('/')
        val stem = fileName.substringBeforeLast('.')
        val names = linkedSetOf(fileName)
        when (fileName.substringAfterLast('.').lowercase()) {
            "kt", "java" -> {
                val pkg = PACKAGE.find(text)?.groupValues?.get(1)
                if (pkg != null) names += listOf("$pkg.$stem", "$pkg.${stem}Kt") else names += stem
            }
            "py" -> {
                val segments = path.removeSuffix(".py").split('/').filter { it.isNotEmpty() && it != "__init__" }
                for (size in 2..segments.size) names += segments.takeLast(size).joinToString(".")
                if (stem != "__init__") names += "$stem:"
            }
            "js", "jsx", "mjs", "cjs", "ts", "tsx" -> path.substringBeforeLast('.').split('/').let { segments ->
                if (segments.size >= 2) names += segments.takeLast(2).joinToString("/")
            }
        }
        return names
    }

    private fun request(path: String, lines: List<String>, registrations: List<String>,
        batch: List<Candidate>, sourceBudget: Int): DecisionRequest {
        val field = minOf(200, sourceBudget / 8)
        // Candidate text stays in state; instructions are fixed text that only refers to it.
        val state = JsonObject().apply {
            addProperty("path", path.takeUtf8(300))
            add("candidates", JsonArray().apply {
                batch.forEach { candidate ->
                    add(JsonObject().apply {
                        addProperty("line", candidate.line)
                        addProperty("code", candidate.code.takeUtf8(field))
                        if (candidate.annotations.isNotEmpty()) addProperty("annotations", candidate.annotations.joinToString("\n").takeUtf8(field))
                        candidate.hint?.let { addProperty("graph_hint", it.takeUtf8(field)) }
                    })
                }
            })
            addProperty("source", excerpt(lines, batch.map { it.line }, sourceBudget))
            add("registrations", JsonArray().apply { fit(registrations, minOf(600, sourceBudget / 4), field).forEach(::add) })
        }
        val questions = linkedMapOf<String, DecisionQuestion>()
        batch.indices.forEach { index ->
            questions["entry_$index"] = DecisionQuestion.Noul(
                "Is `candidates[$index]` in `path` a place where something outside the application's own code starts " +
                    "execution: process start, framework or platform callback, request or event handler, scheduled job, " +
                    "plugin or extension registration, or build, run or container launch task? Use its `code`, " +
                    "`annotations` and `graph_hint`, the numbered `source` lines, and the manifest `registrations` as evidence.",
                ENTRY_CRITERIA,
            )
            questions["kind_$index"] = DecisionQuestion.Choice(
                "Assuming `candidates[$index]` in `path` is an application entry point, which kind of entry point is it?",
                KIND_CRITERIA,
            )
        }
        return DecisionRequest(state, questions)
    }

    /**
     * Budget the serialized request, including questions and criteria, rather than just source text.
     * Each request judges one candidate. Trim nearby source until it fits; a candidate that cannot fit even
     * with minimal source is returned without a request and reported as skipped.
     */
    private fun boundedRequest(path: String, lines: List<String>, registrations: List<String>,
        batch: List<Candidate>): DecisionRequest? {
        var sourceBudget = 1200
        while (true) {
            val request = request(path, lines, registrations, batch, sourceBudget)
            if (DecisionJson.encode(request).toByteArray(Charsets.UTF_8).size <= maxRequestBytes) return request
            if (sourceBudget <= MIN_SOURCE_BUDGET) return null
            sourceBudget = (sourceBudget / 2).coerceAtLeast(MIN_SOURCE_BUDGET)
        }
    }

    /** Include every candidate line first, then nearby evidence until the shared source budget is full. */
    private fun excerpt(lines: List<String>, targets: List<Int>, budget: Int): String {
        val selected = sortedMapOf<Int, String>()
        var remaining = budget
        fun include(line: Int) {
            if (line !in 1..lines.size || line in selected) return
            val text = "$line: " + lines[line - 1].takeUtf8(200)
            val size = text.toByteArray(Charsets.UTF_8).size + 1
            if (size <= remaining) { selected[line] = text; remaining -= size }
        }
        targets.forEach(::include)
        for (distance in 1..12) targets.forEach { include(it - distance); include(it + distance) }
        return selected.values.joinToString("\n")
    }

    private fun fit(values: List<String>, budget: Int, each: Int): List<String> {
        var remaining = budget
        return values.map { it.takeUtf8(each) }.takeWhile { value ->
            val size = value.toByteArray(Charsets.UTF_8).size + 3
            (size <= remaining).also { if (it) remaining -= size }
        }
    }

    private fun String.takeUtf8(maxBytes: Int): String = buildString {
        var used = 0
        var offset = 0
        while (offset < this@takeUtf8.length) {
            val point = this@takeUtf8.codePointAt(offset)
            val bytes = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
            if (used + bytes > maxBytes) break
            appendCodePoint(point)
            used += bytes
            offset += Character.charCount(point)
        }
    }

    private fun supported(path: String) = isManifest(path) || path.substringAfterLast('.').lowercase() in EXTENSIONS
    private fun isManifest(path: String) = manifestKind(path.substringAfterLast('/')) != null
    private fun isAnnotation(trimmed: String) = trimmed.startsWith("@") || trimmed.startsWith("#[")
    private fun isComment(trimmed: String) = trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") ||
        (trimmed.startsWith("#") && !trimmed.startsWith("#[") && !trimmed.startsWith("#!"))

    companion object {
        const val MAX_REQUEST_BYTES = 4000
        /** Below this, a Choice distribution this flat is shown as an uncertain category. */
        const val KIND_CONFIDENCE = 0.5
        private const val MIN_SOURCE_BUDGET = 256
        private const val MAX_REGISTRATIONS = 8
        private val EXTENSIONS = setOf("kt", "kts", "java", "py", "go", "js", "jsx", "mjs", "cjs", "ts", "tsx", "rs", "sh")

        private val ENTRY_CRITERIA = mapOf<Boolean, JsonElement>(
            true to JsonPrimitive("A runtime, OS, framework, platform, scheduler, build tool or container starts execution here, " +
                "shown by its signature, annotations, a registration call, or a manifest entry that names it."),
            false to JsonPrimitive("Code the application calls itself: helpers, internal methods, data declarations or " +
                "configuration, with nothing showing that an outside caller invokes it."),
        )

        private val KIND_CRITERIA: Map<String, JsonElement?> = linkedMapOf(
            EntryPointKind.APPLICATION.name to "Program start: a main function or application class the runtime launches.",
            EntryPointKind.SERVER.name to "Starts a long-running server or listener that binds a port; not the per-request handlers it later calls.",
            EntryPointKind.CLI.name to "A named command or subcommand a user runs from a terminal with arguments.",
            EntryPointKind.SCRIPT.name to "A file run directly as a script (shebang or `__main__` block) rather than a named command.",
            EntryPointKind.PLUGIN.name to "Loaded by a host application through plugin or extension registration: IDE extensions, services, tool windows, Android components.",
            EntryPointKind.HANDLER.name to "Called per request, event or message: HTTP routes, message consumers, serverless functions, UI actions, event listeners.",
            EntryPointKind.JOB.name to "Runs on a schedule or timer, or as a background task-queue worker.",
            EntryPointKind.BUILD.name to "A build or package-manager task that launches code: npm scripts, Gradle or Maven tasks, Makefile targets.",
            EntryPointKind.CONTAINER.name to "A container or process-manager start command: Dockerfile ENTRYPOINT or CMD, compose or Procfile commands.",
            EntryPointKind.OTHER.name to "An entry point that fits none of the other categories.",
        ).mapValues { JsonPrimitive(it.value) }

        private val KEYWORD_DECLARATION = Regex(
            "\\b(?:fun|func|fn|def|function)\\s*\\*?\\s+(?:\\([^)]*\\)\\s*)?(?:<[^>]*>\\s*)?(?:[\\w$]+\\.)?([A-Za-z_$][\\w$]*)")
        private val VARIABLE_FUNCTION = Regex(
            "\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s+)?(?:function\\b|\\([^)]*\\)\\s*=>|[A-Za-z_$][\\w$]*\\s*=>)")
        /** Java and TypeScript style methods: at least one modifier or type before the name, so calls do not match. */
        private val METHOD_DECLARATION = Regex(
            "^\\s*((?:[\\w$<>\\[\\],.?]+\\s+)+)([A-Za-z_$][\\w$]*)\\s*\\([^;]*\\)\\s*(?:throws\\s+[\\w$.,\\s]+)?\\{?\\s*$")
        private val LEADING_ANNOTATIONS = Regex("^\\s*(?:(?:@[\\w.]+|#\\[[^]]*])(?:\\([^)]*\\))?\\s*)+")
        private val NOT_NAMES =setOf("if", "for", "while", "switch", "catch", "when", "synchronized", "return", "else", "try",
            "do", "with", "elif", "foreach", "super", "this", "interface", "class", "object", "require", "check", "assert")
        private val NOT_MODIFIERS = setOf("return", "throw", "new", "await", "yield", "else", "case", "go", "defer", "typeof",
            "delete", "in", "of", "val", "var", "let", "const", "import", "package")

        private val REGISTRATION = Regex(listOf(
            "@(?:[\\w.]+\\.)?(?:GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping|MessageMapping|" +
                "Scheduled|EventListener|KafkaListener|RabbitListener|JmsListener|SqsListener|SpringBootApplication|" +
                "WebServlet|WebListener|Path|GET|POST|PUT|DELETE|PATCH|Command|Subcommand|QuarkusMain)\\b",
            "@(?:\\w+\\.)+(?:route|get|post|put|delete|patch|websocket|command|group|task|on_event|listener|handler|callback|" +
                "cron|scheduled_job|shared_task)\\b",
            "@(?:shared_task|task|click\\.command)\\b",
            "#\\[(?:\\w+::)?(?:main|get|post|put|delete|launch)\\b",
            "\\b(?:listen|createServer|ListenAndServe|serve_forever|runApp)\\s*\\(",
            "\\b(?:app|server|router)\\.(?:get|post|put|delete|patch|use|all|route)\\s*\\(\\s*['\"`]",
            "__name__\\s*==\\s*['\"]__main__['\"]",
            "\\bexports\\.handler\\b|\\bmodule\\.exports\\s*=",
        ).joinToString("|"))

        private val PACKAGE = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE)

        private val XML_REGISTRATION = Regex(
            "<\\w[\\w.-]*\\s[^>]*\\b(?:implementation|implementationClass|serviceImplementation|factoryClass|instance|class|android:name)\\s*=" +
                "|<(?:servlet-class|listener-class|filter-class|mainClass|main-class|start-class)>")
        private val MANIFEST_PATTERNS = mapOf(
            "package.json" to Regex("\"(?:main|bin|module|exports|start|dev|serve|scripts)\"\\s*:"),
            "gradle" to Regex("\\b(?:mainClass|mainClassName|Main-Class)\\b|\\bapplication\\s*\\{|\\btasks\\.register\\b|\\btask\\s+\\w+"),
            "xml" to XML_REGISTRATION,
            "Dockerfile" to Regex("^\\s*(?:ENTRYPOINT|CMD)\\b"),
            "Makefile" to Regex("^[A-Za-z0-9_.-]+\\s*:(?!=)"),
            "Procfile" to Regex("^[\\w-]+\\s*:"),
            "Cargo.toml" to Regex("^\\s*\\[\\[bin]]"),
            "pyproject.toml" to Regex("^\\s*\\[(?:project\\.scripts|project\\.gui-scripts|tool\\.poetry\\.scripts|project\\.entry-points[^]]*)]" +
                "|^\\s*[\\w.-]+\\s*=\\s*[\"'][\\w.]+:[\\w.]+[\"']"),
            "yaml" to Regex("^\\s*(?:command|entrypoint|handler)\\s*:"),
        )

        private fun manifestKind(fileName: String): String? = when (fileName) {
            "package.json", "Dockerfile", "Makefile", "Procfile", "Cargo.toml", "pyproject.toml" -> fileName
            "build.gradle", "build.gradle.kts" -> "gradle"
            "pom.xml", "plugin.xml", "web.xml", "AndroidManifest.xml" -> "xml"
            "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml", "serverless.yml", "serverless.yaml" -> "yaml"
            else -> null
        }

        /** The declared function or method name on [line], or null if the line declares none. */
        private fun declarationName(raw: String): String? {
            val line = raw.replaceFirst(LEADING_ANNOTATIONS, "")
            (KEYWORD_DECLARATION.find(line) ?: VARIABLE_FUNCTION.find(line))?.groupValues?.last()
                ?.takeUnless { it in NOT_NAMES }?.let { return it }
            val method = METHOD_DECLARATION.find(line) ?: return null
            val modifiers = method.groupValues[1].trim().split(Regex("\\s+"))
            val name = method.groupValues[2]
            if (name in NOT_NAMES || modifiers.any { it in NOT_MODIFIERS }) return null
            return name
        }

        private fun simpleName(symbol: String) = symbol.substringBefore('(').substringAfterLast('.').trim()

        /** Matches [name] as a whole identifier, path or module reference. */
        private fun token(name: String): Regex {
            val end = if (name.last().isLetterOrDigit() || name.last() == '_' || name.last() == '$') "(?![\\w$])" else ""
            return Regex("(?<![\\w$.])${Regex.escape(name)}$end")
        }
    }
}
