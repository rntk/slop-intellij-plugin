package dev.insight.enrichment

import dev.insight.callgraph.CallGraph
import dev.insight.callgraph.RelationKind
import dev.insight.callgraph.SymbolKind
import kotlin.math.ln
import kotlin.random.Random

/** Graph-only metrics over in-project CALL edges (self calls excluded). */
object StructuralAnalysis {
    /** Graphs larger than this use [SAMPLED_SOURCES] seeded-random sources for betweenness instead of all nodes. */
    const val EXACT_LIMIT = 2000
    const val SAMPLED_SOURCES = 300
    private const val SEED = 42

    fun metrics(graph: CallGraph): Map<Int, StructuralMetrics> {
        val ids = graph.nodes.filter { it.inProject }.map { it.id }.distinct().sorted()
        val index = HashMap<Int, Int>(ids.size * 2).apply { ids.forEachIndexed { i, id -> put(id, i) } }
        val successors = Array(ids.size) { sortedSetOf<Int>() }
        val predecessors = IntArray(ids.size)
        for (edge in graph.edges) {
            if (edge.kind != RelationKind.CALL || edge.caller == edge.callee) continue
            val from = index[edge.caller] ?: continue
            val to = index[edge.callee] ?: continue
            if (successors[from].add(to)) predecessors[to]++
        }
        val adjacency = Array(ids.size) { successors[it].toIntArray() }
        val betweenness = betweenness(adjacency)
        val max = betweenness.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return ids.indices.associate { i ->
            ids[i] to StructuralMetrics(predecessors[i], adjacency[i].size, betweenness[i] / max, predecessors[i] == 0)
        }
    }

    /** Brandes' algorithm on an unweighted directed graph; unnormalized, from all or sampled sources. */
    private fun betweenness(adjacency: Array<IntArray>): DoubleArray {
        val n = adjacency.size
        val result = DoubleArray(n)
        val sources = if (n <= EXACT_LIMIT) (0 until n).toList() else (0 until n).shuffled(Random(SEED)).take(SAMPLED_SOURCES)
        val sigma = DoubleArray(n)
        val distance = IntArray(n)
        val delta = DoubleArray(n)
        val order = IntArray(n)
        for (source in sources) {
            sigma.fill(0.0); distance.fill(-1); delta.fill(0.0)
            sigma[source] = 1.0
            distance[source] = 0
            order[0] = source
            var head = 0
            var tail = 1
            while (head < tail) {
                val v = order[head++]
                for (w in adjacency[v]) {
                    if (distance[w] < 0) { distance[w] = distance[v] + 1; order[tail++] = w }
                    if (distance[w] == distance[v] + 1) sigma[w] += sigma[v]
                }
            }
            // Visiting in reverse BFS order; predecessors of w are the v with an edge v→w one level closer.
            for (k in tail - 1 downTo 0) {
                val v = order[k]
                for (w in adjacency[v]) {
                    if (distance[w] == distance[v] + 1) delta[v] += sigma[v] / sigma[w] * (1 + delta[w])
                }
                if (v != source) result[v] += delta[v]
            }
        }
        return result
    }
}

/** Deterministic, model-free tags derived from names, paths, annotations, signatures and imports. */
object HeuristicRules {
    private val testDirs = listOf("/test/", "/tests/", "/__tests__/", "/testing/", "/testdata/", "/spec/", "/androidTest/")
    private val testFiles = listOf(
        Regex(".*Tests?\\.(kt|kts|java|scala|groovy)"), Regex(".*_test\\.(go|py)"), Regex("test_.*\\.py"),
        Regex(".*\\.(spec|test)\\.(ts|tsx|js|jsx|mjs)"), Regex("conftest\\.py"),
    )
    private val testName = Regex("test([A-Z0-9_].*)?")
    private val testAnnotations = setOf(
        "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate", "Before", "After", "BeforeClass",
        "AfterClass", "BeforeEach", "AfterEach", "BeforeAll", "AfterAll", "pytest.fixture", "fixture",
    )

    private val routeAnnotations = setOf(
        "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping",
        "GET", "POST", "PUT", "DELETE", "PATCH", "Path",
    )
    /** Decorators such as `app.route`, `router.get`, `bp.post`, `api.websocket`. */
    private val routeDecorator = Regex("\\w+\\.(route|get|post|put|delete|patch|head|options|api_route|websocket)")
    /** Go `net/http` handler signature. */
    private val goHandler = Regex("http\\.ResponseWriter")

    private val listenerMethods = setOf(
        "actionPerformed", "mouseClicked", "mousePressed", "mouseReleased", "mouseMoved", "mouseDragged",
        "mouseWheelMoved", "keyPressed", "keyReleased", "keyTyped", "focusGained", "focusLost", "propertyChange",
        "stateChanged", "valueChanged", "itemStateChanged", "componentResized", "windowClosing", "onEvent",
        "handleEvent", "onMessage", "onReceive", "onClick", "createToolWindowContent", "runActivity",
    )
    private val listenerAnnotations = setOf("EventListener", "Subscribe", "KafkaListener", "RabbitListener", "JmsListener", "OnEvent")
    private val listenerSupertypes = setOf(
        "AnAction", "DumbAwareAction", "ToggleAction", "ActionListener", "MouseListener", "MouseAdapter",
        "MouseMotionListener", "KeyListener", "KeyAdapter", "FocusListener", "EventListener", "PropertyChangeListener",
        "ApplicationListener", "ToolWindowFactory", "StartupActivity", "ProjectActivity", "BulkFileListener",
        "DocumentListener", "ChangeListener", "ListSelectionListener",
    )
    private val supertypeClause = Regex("(?:\\bextends\\b|\\bimplements\\b|:)([^{=]*)")
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Import prefixes per concern; a prefix matches itself and anything below it (`.` or `/` separated). */
    private val importConcerns = mapOf(
        "storage" to listOf(
            "java.sql", "javax.sql", "java.nio.file", "java.io.File", "java.io.FileInputStream", "java.io.FileOutputStream",
            "java.io.FileReader", "java.io.FileWriter", "java.io.RandomAccessFile", "jakarta.persistence", "javax.persistence",
            "org.hibernate", "androidx.room", "org.jetbrains.exposed", "sqlite3", "sqlalchemy", "shelve", "database/sql",
            "gorm.io", "fs", "node:fs", "fs/promises",
        ),
        "network" to listOf(
            "java.net.http", "java.net.URL", "java.net.Socket", "java.net.ServerSocket", "java.net.HttpURLConnection",
            "okhttp3", "retrofit2", "io.ktor", "io.grpc", "org.apache.http", "requests", "httpx", "aiohttp", "urllib.request",
            "socket", "grpc", "net/http", "net", "axios", "node-fetch", "http", "https",
        ),
        "serialization" to listOf(
            "com.google.gson", "kotlinx.serialization", "com.fasterxml.jackson", "com.squareup.moshi", "org.yaml.snakeyaml",
            "javax.xml", "com.google.protobuf", "json", "yaml", "pickle", "xml.etree", "encoding/json", "encoding/xml",
            "google.golang.org/protobuf",
        ),
        "logging" to listOf(
            "org.slf4j", "java.util.logging", "org.apache.logging.log4j", "org.apache.log4j", "mu.KotlinLogging",
            "io.github.oshai.kotlinlogging", "com.intellij.openapi.diagnostic", "logging", "loguru", "log", "log/slog",
            "go.uber.org/zap", "github.com/sirupsen/logrus", "winston", "pino",
        ),
        "concurrency" to listOf(
            "kotlinx.coroutines", "java.util.concurrent", "threading", "asyncio", "concurrent.futures", "multiprocessing",
            "sync", "sync/atomic", "worker_threads",
        ),
    )

    fun tags(facts: SymbolFacts): Set<String> = buildSet {
        val annotations = facts.annotations.flatMapTo(hashSetOf()) { listOf(it, it.substringAfterLast('.')) }
        val name = simpleName(facts.name)
        if (isTest(facts, name, annotations)) add("test")
        if (facts.kind == SymbolKind.FUNCTION && name == "main") add("entry:main")
        if (annotations.any { it in routeAnnotations } || facts.annotations.any(routeDecorator::matches) ||
            goHandler.containsMatchIn(facts.signature)) add("entry:route")
        if (isListener(facts, name, annotations)) add("entry:listener")
        concernImports(facts).keys.forEach { add("concern:$it") }
    }

    /** The imports that give [facts] each concern: imported by its file and mentioned by its code. */
    fun concernImports(facts: SymbolFacts): Map<String, List<String>> {
        val text = facts.signature + "\n" + facts.code
        val imports = facts.imports.flatMap(::normalize)
        return importConcerns.mapValues { (_, prefixes) ->
            imports.filter { import ->
                prefixes.any { import == it || import.startsWith("$it.") || import.startsWith("$it/") } && mentions(text, import)
            }.distinct()
        }.filterValues { it.isNotEmpty() }
    }

    /** Graph names of Java and Kotlin functions carry their parameter types, such as `main(String[])`. */
    private fun simpleName(name: String) = name.substringBefore('(').trim()

    private fun isTest(facts: SymbolFacts, name: String, annotations: Set<String>): Boolean {
        val path = facts.path?.let { "/$it" }
        return path != null && (testDirs.any { it in path } || testFiles.any { it.matches(path.substringAfterLast('/')) }) ||
            testName.matches(name) ||
            annotations.any { it in testAnnotations } ||
            facts.annotations.any { it.startsWith("pytest.") }
    }

    private fun isListener(facts: SymbolFacts, name: String, annotations: Set<String>): Boolean = when {
        annotations.any { it in listenerAnnotations } -> true
        facts.kind == SymbolKind.FUNCTION ->
            name in listenerMethods && "override" in facts.signature.lowercase() ||
                name in listenerMethods && "Override" in facts.annotations
        facts.kind == SymbolKind.CLASS -> supertypeClause.findAll(facts.signature)
            .any { clause -> identifier.findAll(clause.groupValues[1]).any { it.value in listenerSupertypes } }
        else -> false
    }

    private val fromImport = Regex("\\s+import\\s+")

    /**
     * Reduces `import x.y as z`, `from a import b, c` (stored by [SymbolFactsExtractor] as `a import b, c`) and
     * quoted module names to dotted or slashed paths, one per imported name.
     */
    private fun normalize(raw: String): List<String> {
        val text = raw.trim().removePrefix("import ").removePrefix("static ").removePrefix("from ").trim().trim('"', '\'', ';')
        val parts = text.split(fromImport, limit = 2)
        if (parts.size == 1) return listOf(text.substringBefore(" as ").trim())
        val module = parts[0].trim()
        return parts[1].trim().removePrefix("(").removeSuffix(")").split(',')
            .map { it.substringBefore(" as ").trim() }.filter { it.isNotEmpty() }
            .map { "$module.$it" }
    }

    /** True when [text] mentions the import's simple name (or alias), so a file-level import is attributed to this symbol. */
    private fun mentions(text: String, import: String): Boolean {
        val simple = import.substringAfterLast('/').substringAfterLast('.').takeIf { it.isNotBlank() && it != "*" } ?: return false
        return Regex("(?<![A-Za-z0-9_])${Regex.escape(simple)}(?![A-Za-z0-9_])").containsMatchIn(text)
    }
}

/** Weights of [visibility]; penalties are fractions (0..1) removed from the positive score. */
data class VisibilityWeights(
    val importance: Double,
    val betweenness: Double,
    val fanIn: Double,
    val utilityPenalty: Double,
    val testPenalty: Double,
) {
    companion object {
        val DEFAULT = VisibilityWeights(importance = 0.6, betweenness = 0.25, fanIn = 0.15, utilityPenalty = 0.5, testPenalty = 0.8)
    }
}

private val FAN_IN_SATURATION = ln(1.0 + 20)

/**
 * How prominently a symbol should be shown, 0..1:
 * `positive = weighted mean of (importance.normalized, betweenness, min(1, ln(1+fanIn)/ln(21)))` over the components
 * that are known (0.5 when none are), then `positive × (1 − utilityPenalty·utility) × (1 − testPenalty·test)`, where
 * `test` is 1 for the `test` heuristic tag, otherwise the model's test concern probability, and missing values are 0.
 */
fun visibility(
    annotation: NodeAnnotation?,
    metrics: StructuralMetrics?,
    heuristics: Heuristics?,
    weights: VisibilityWeights = VisibilityWeights.DEFAULT,
): Double {
    val components = listOfNotNull(
        annotation?.importance?.let { weights.importance to it.normalized },
        metrics?.let { weights.betweenness to it.betweenness },
        metrics?.let { weights.fanIn to (ln(1.0 + it.fanIn) / FAN_IN_SATURATION).coerceAtMost(1.0) },
    ).filter { it.first > 0 }
    val totalWeight = components.sumOf { it.first }
    val positive = if (totalWeight == 0.0) 0.5 else components.sumOf { it.first * it.second.coerceIn(0.0, 1.0) } / totalWeight
    val utility = annotation?.utility ?: 0.0
    val test = if (heuristics?.tags?.contains("test") == true) 1.0 else annotation?.concerns?.get("test") ?: 0.0
    val result = positive *
        (1 - weights.utilityPenalty.coerceIn(0.0, 1.0) * utility) *
        (1 - weights.testPenalty.coerceIn(0.0, 1.0) * test)
    return result.coerceIn(0.0, 1.0)
}
