package dev.insight.diff

import com.intellij.openapi.project.Project
import dev.insight.callgraph.SymbolKind

/** What a semantic metric measures; [group] orders the rows of the metrics table. */
internal enum class MetricGroup(val label: String) {
    STRUCTURE("Structure"), CONTROL_FLOW("Control flow"), COMPLEXITY("Complexity"), DATA("Data"), HYGIENE("Hygiene"), DEPENDENCIES("Dependencies"), API("API surface")
}

/**
 * A value counted from parsed source. Syntax-only metrics are exact on both sides; [approximate] metrics resolve
 * references, which the non-physical before side can only do against today's project.
 * [construct] metrics count one source construct (`if`, `for`, `try`…) and are shown in the construct cloud by [label].
 * Max-like metrics ([summed] false) aggregate by maximum instead of sum.
 */
internal enum class Metric(
    val group: MetricGroup,
    val label: String,
    val description: String,
    val construct: Boolean = false,
    val summed: Boolean = true,
    val approximate: Boolean = false,
) {
    // Structure
    CLASSES(MetricGroup.STRUCTURE, "classes", "Classes, interfaces, objects and enums"),
    FUNCTIONS(MetricGroup.STRUCTURE, "functions", "Functions, methods and constructors"),
    LAMBDAS(MetricGroup.STRUCTURE, "lambdas", "Lambdas, closures and anonymous functions", construct = true),
    PROPERTIES(MetricGroup.STRUCTURE, "properties", "Properties and fields declared in classes or files"),
    PARAMETERS(MetricGroup.STRUCTURE, "parameters", "Parameters of functions and lambdas"),
    STATEMENTS(MetricGroup.STRUCTURE, "statements", "Statements and expressions at statement level"),
    CODE_LINES(MetricGroup.STRUCTURE, "code lines", "Lines with code, excluding blank and comment-only lines"),
    COMMENT_LINES(MetricGroup.STRUCTURE, "comment lines", "Lines with comments"),
    // Control flow
    BRANCHES(MetricGroup.CONTROL_FLOW, "if", "if / else if branches, ternaries and elvis operators", construct = true),
    CASES(MetricGroup.CONTROL_FLOW, "case", "when / switch / match cases", construct = true),
    LOOPS(MetricGroup.CONTROL_FLOW, "loop", "for, while and do-while loops", construct = true),
    CATCHES(MetricGroup.CONTROL_FLOW, "catch", "catch / except clauses", construct = true),
    THROWS(MetricGroup.CONTROL_FLOW, "throw", "throw / raise statements", construct = true),
    RETURNS(MetricGroup.CONTROL_FLOW, "return", "return statements", construct = true),
    JUMPS(MetricGroup.CONTROL_FLOW, "break/continue", "break and continue", construct = true),
    LOGICAL_OPERATORS(MetricGroup.CONTROL_FLOW, "&& ||", "Short-circuit logical operators", construct = true),
    // Complexity
    CYCLOMATIC(MetricGroup.COMPLEXITY, "cyclomatic complexity", "McCabe: 1 + decisions + logical operators, per function"),
    COGNITIVE(MetricGroup.COMPLEXITY, "cognitive complexity", "Sonar-style: decisions weighted by nesting, per function"),
    MAX_NESTING(MetricGroup.COMPLEXITY, "max nesting", "Deepest nesting of control structures", summed = false),
    MAX_PARAMETERS(MetricGroup.COMPLEXITY, "max parameters", "Most parameters of one function", summed = false),
    MAX_FUNCTION_LINES(MetricGroup.COMPLEXITY, "longest function", "Lines of the longest function", summed = false),
    // Data
    MUTABLE_VARIABLES(MetricGroup.DATA, "var", "Mutable local variables (var, let, plain assignment declarations)", construct = true),
    IMMUTABLE_VARIABLES(MetricGroup.DATA, "val", "Immutable local variables (val, final, const)", construct = true),
    CONSTANTS(MetricGroup.DATA, "constants", "const / static final / UPPER_CASE constants"),
    NUMBER_LITERALS(MetricGroup.DATA, "number literals", "Numeric literals other than 0 and 1", construct = true),
    STRING_LITERALS(MetricGroup.DATA, "string literals", "String literals", construct = true),
    NULL_ASSERTIONS(MetricGroup.DATA, "!!", "Non-null assertions and unsafe casts", construct = true),
    // Hygiene
    TODOS(MetricGroup.HYGIENE, "TODO", "TODO / FIXME / HACK comments", construct = true),
    SUPPRESSIONS(MetricGroup.HYGIENE, "suppressions", "@Suppress, @SuppressWarnings, noinspection, noqa, eslint-disable", construct = true),
    DOCUMENTED(MetricGroup.HYGIENE, "documented declarations", "Declarations with a doc comment"),
    // Dependencies (resolved)
    IMPORTS(MetricGroup.DEPENDENCIES, "imports", "Import statements"),
    FAN_OUT(MetricGroup.DEPENDENCIES, "fan-out", "Distinct functions called, summed over functions", approximate = true),
    EXTERNAL_CALLS(MetricGroup.DEPENDENCIES, "external calls", "Calls resolving outside the project (libraries, SDK)", approximate = true),
    PACKAGES_USED(MetricGroup.DEPENDENCIES, "packages used", "Distinct packages or directories of resolved call targets", approximate = true),
    // API surface
    PUBLIC_DECLARATIONS(MetricGroup.API, "public declarations", "Declarations visible outside their file or module"),
    OVERRIDES(MetricGroup.API, "overrides", "Functions overriding or implementing a super declaration", approximate = true),
}

/** Counted metric values; a metric that does not occur is absent rather than 0. */
internal typealias MetricVector = Map<Metric, Int>

/**
 * One declaration on both sides, matched by qualified name; [before] is null for an added and [after] for a removed one.
 * Values are the declaration's own: code of nested functions and classes counts for those.
 * [line] is 1-based on the after side, or on the before side for removed declarations.
 */
internal data class DeclarationMetrics(
    val path: String,
    val qualifiedName: String,
    val kind: SymbolKind,
    val line: Int,
    val before: MetricVector?,
    val after: MetricVector?,
) {
    fun value(metric: Metric, side: Boolean): Int? = (if (side) after else before)?.let { it[metric] ?: 0 }
    fun delta(metric: Metric): Int = (after?.get(metric) ?: 0) - (before?.get(metric) ?: 0)
}

/** A whole file on both sides; [skipped] explains files without metrics (binary, not code, no PSI for the language). */
internal data class FileMetrics(
    val path: String,
    val status: String,
    val before: MetricVector?,
    val after: MetricVector?,
    val declarations: List<DeclarationMetrics>,
    val skipped: String? = null,
) {
    fun delta(metric: Metric): Int = (after?.get(metric) ?: 0) - (before?.get(metric) ?: 0)
}

/** Semantic metrics of a review snapshot, counted by code from both sides of every file. */
internal data class SemanticChanges(val files: List<FileMetrics>) {
    val before: MetricVector by lazy { total { it.before } }
    val after: MetricVector by lazy { total { it.after } }
    fun delta(metric: Metric): Int = (after[metric] ?: 0) - (before[metric] ?: 0)
    /** Declarations of every file with metrics on at least one side. */
    val declarations: List<DeclarationMetrics> get() = files.flatMap { it.declarations }
    /** Files whose value of [metric] differs between the sides. */
    fun filesChanging(metric: Metric): List<FileMetrics> = files.filter { it.skipped == null && it.delta(metric) != 0 }

    private fun total(side: (FileMetrics) -> MetricVector?): MetricVector {
        val result = linkedMapOf<Metric, Int>()
        for (file in files) for ((metric, value) in side(file).orEmpty()) {
            result[metric] = if (metric.summed) (result[metric] ?: 0) + value else maxOf(result[metric] ?: 0, value)
        }
        return result
    }
}

/**
 * Parses both sides of every file and counts [Metric]s per file and per declaration.
 * Runs in a background read action; checks for cancellation.
 */
internal fun semanticChanges(project: Project, files: List<ReviewFile>): SemanticChanges = SemanticMetrics(project).changes(files)
