package dev.insight.codereview.map

/**
 * What a code-map unit is cut from: a top-level class (with its members), the top-level functions and properties of a
 * file, or a whole file without code analysis.
 */
enum class UnitKind { CLASS, TOP_LEVEL, FILE }

/** A declaration inside a unit, listed when the unit is drilled into: a member of a class or a top-level function. */
data class CodeMember(
    /** `path#Owner.name`; overloads share it. */
    val id: String,
    /** The qualified name below the file: `Owner.name`. */
    val name: String,
    /** "function", "class" or "property". */
    val kind: String,
    /** 1-based inclusive lines. */
    val lines: IntRange,
    val header: String,
)

/**
 * The unit of a code map: a class, the top-level code of a file, or a file without code analysis. Units are what flows
 * are made of; their [members] are shown when a step is drilled into.
 */
data class CodeUnit(
    /** Unique in a map and stable while the path and name are: `path#Qualified.Name`, `path#<top-level>` or `path`. */
    val id: String,
    val path: String,
    /** The class's qualified name, "top-level code", or the file name. */
    val name: String,
    val kind: UnitKind,
    /** 1-based inclusive lines of a class or of the span of a file's top-level declarations; null for a whole file. */
    val lines: IntRange?,
    /** The declaration header of a class; empty otherwise. */
    val header: String,
    val members: List<CodeMember>,
    /** Lines the unit spans, for sizes and ordering. */
    val lineCount: Int,
    /** "implementation", "test" or "documentation", from the path. */
    val evidenceKind: String,
    /** Equal while the unit's text is, so its summary can be kept. */
    val fingerprint: String,
) {
    val simpleName: String get() = when (kind) {
        UnitKind.CLASS -> name.substringAfterLast('.')
        else -> path.substringAfterLast('/').substringBefore('.')
    }
}

enum class CodeReferenceKind { CALL, TYPE, INHERITS, OVERRIDE, REFERENCE, MENTION }

/**
 * A fact read from code: unit [from] refers [count] times to a declaration of unit [to]. [fromMember] and [toMember] are
 * the member ids at each end when the reference starts or lands inside one.
 */
data class CodeReference(
    val from: String,
    val to: String,
    val kind: CodeReferenceKind,
    val fromMember: String? = null,
    val toMember: String? = null,
    val count: Int = 1,
)

enum class LinkStrength { STRONG, MEDIUM, WEAK }

/**
 * Why a unit belongs with another, read from the user's side: "A [label] B" for an edge from A to B. Strong relations
 * group by code alone; medium ones are judged by a model; weak ones only link flows. [passive] says it of several
 * users at once, for a fan-out: "4 used only by CodeReviewTab".
 */
enum class CodeRelation(val strength: LinkStrength, val label: String, val passive: String) {
    EXTENDS(LinkStrength.STRONG, "extends", "extended by"),
    OVERRIDES(LinkStrength.STRONG, "overrides members of", "with members overridden by"),
    TESTS(LinkStrength.STRONG, "tests", "tested by"),
    SOLE_USE(LinkStrength.STRONG, "is the only user of", "used only by"),
    USES(LinkStrength.MEDIUM, "uses", "used by"),
    DOCUMENTS(LinkStrength.MEDIUM, "documents", "documented by"),
    SHARED_USE(LinkStrength.WEAK, "uses the shared", "shared with"),
    ALSO_TESTS(LinkStrength.WEAK, "also exercises", "also exercised by"),
}

/** Directed from the user to what it uses: "[from] [relation.label] [to]", such as "CodeReviewTab uses CodeReviewState". */
data class CodeEdge(val from: String, val to: String, val relation: CodeRelation)

/**
 * Units and the typed relations between them, built by code. [fanIn] counts the distinct implementation units that use
 * each unit; [shared] are those used so widely that they never glue flows together: they are foundations other flows share.
 */
data class CodeGraph(
    val units: List<CodeUnit>,
    val edges: List<CodeEdge>,
    val fanIn: Map<String, Int> = emptyMap(),
    val shared: Set<String> = emptySet(),
    /** Member-level references, for drilling into a unit; never used to cluster. */
    val references: List<CodeReference> = emptyList(),
) {
    private val byId: Map<String, CodeUnit> by lazy { units.associateBy { it.id } }
    fun unit(id: String): CodeUnit? = byId[id]
}

/** A unit's place in its flow: roots start the flow, the rest is used by them, then tests and documentation. */
enum class FlowRole { ROOT, FOLLOWS, TEST, DOCUMENTATION, STANDALONE }

/** [depth] is the number of hops from a root along user-to-used edges. */
data class FlowStep(val unit: String, val role: FlowRole, val depth: Int)

/**
 * One flow of the code: units that belong together, ordered from roots (code nothing else in the flow uses) through the
 * code they use to tests and documentation. A [foundation] holds a widely used unit; [shared] lists the foundation units
 * of other flows this one uses, which is how one unit belongs to several flows. An [entry] flow starts at a unit nothing
 * in the scope uses: a likely entry point.
 */
data class CodeFlow(
    /** Stable while the member units are the same. */
    val id: String,
    val steps: List<FlowStep>,
    /** Written by a model, or null until then; views fall back to [CodeMap.defaultTitle]. */
    val title: String? = null,
    val foundation: Boolean = false,
    val entry: Boolean = false,
    val shared: List<String> = emptyList(),
) {
    val units: List<String> get() = steps.map { it.unit }
    val standalone: Boolean get() = steps.size == 1 && !foundation
}

/** Two flows that code could not decide on: they are linked by [edges] that are not strong. */
data class FlowMergeCandidate(val a: String, val b: String, val edges: List<CodeEdge>)

/**
 * The code of a scope as flows of units. Membership and order come from [graph]; only titles and merge judgments are a
 * model's. [analyzed] counts the files read, of [files] in scope.
 */
data class CodeMap(val graph: CodeGraph, val flows: List<CodeFlow>, val files: Int = 0, val analyzed: Int = files) {
    private val byId: Map<String, CodeFlow> by lazy { flows.associateBy { it.id } }
    private val owner: Map<String, CodeFlow> by lazy { flows.flatMap { flow -> flow.units.map { it to flow } }.toMap() }
    fun flow(id: String): CodeFlow? = byId[id]
    /** The flow a unit is a member of; a shared unit is listed by other flows too, see [CodeFlow.shared]. */
    fun flowOf(unit: String): CodeFlow? = owner[unit]
    fun units(flow: CodeFlow): List<CodeUnit> = flow.units.mapNotNull(graph::unit)
    /** Flows of several units, and foundations, which read as flows. */
    val chains: List<CodeFlow> get() = flows.filter { !it.standalone }
    val standalone: List<CodeFlow> get() = flows.filter { it.standalone }
    fun edgesWithin(flow: CodeFlow): List<CodeEdge> = flow.units.toSet().let { ids -> graph.edges.filter { it.from in ids && it.to in ids } }
    fun edgesAcross(flow: CodeFlow): List<CodeEdge> = flow.units.toSet().let { ids -> graph.edges.filter { (it.from in ids) != (it.to in ids) } }
    /** Flows that list a unit of [flow] as shared. */
    fun sharers(flow: CodeFlow): List<CodeFlow> = flow.units.toSet().let { ids -> flows.filter { other -> other.id != flow.id && other.shared.any { it in ids } } }
    fun withTitles(titles: Map<String, String>): CodeMap =
        copy(flows = flows.map { flow -> titles[flow.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { flow.copy(title = it) } ?: flow })
    /** A title from code alone: the first root's name, and how many other units follow it. */
    fun defaultTitle(flow: CodeFlow): String {
        val first = graph.unit(flow.units.first())?.let { if (it.kind == UnitKind.CLASS) it.simpleName else it.path.substringAfterLast('/') } ?: return "Flow"
        return if (flow.steps.size == 1) first else "$first and ${flow.steps.size - 1} related"
    }
    fun title(flow: CodeFlow): String = flow.title ?: defaultTitle(flow)
}

/** Path hints describe the evidence, not proof of a runtime role. */
internal fun codeEvidenceKind(path: String): String {
    val name = path.substringAfterLast('/').lowercase()
    val stem = name.substringBeforeLast('.')
    return when {
        path.split('/').dropLast(1).any { it.lowercase() in TEST_FOLDERS } || stem.endsWith("test") || stem.endsWith("tests") ||
            name.startsWith("test_") || name.contains(".test.") || name.contains(".spec.") || stem.endsWith("_spec") -> "test"
        name.substringAfterLast('.') in DOC_EXTENSIONS -> "documentation"
        else -> "implementation"
    }
}

private val TEST_FOLDERS = setOf("test", "tests", "testfixtures", "androidtest", "__tests__", "spec")
private val DOC_EXTENSIONS = setOf("md", "rst", "adoc", "markdown")
