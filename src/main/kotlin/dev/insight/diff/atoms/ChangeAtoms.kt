package dev.insight.diff.atoms

/** What a change atom is cut from: a declaration, the code of a file outside its declarations, or a whole file without code analysis. */
enum class AtomKind { FUNCTION, CLASS, PROPERTY, LOOSE, FILE }

enum class AtomStatus { ADDED, REMOVED, MODIFIED }

/**
 * The unit of a change map: one changed declaration matched across both sides, the changed top-level code of a file
 * ([AtomKind.LOOSE], at most one per file), or a whole file that has no code analysis ([AtomKind.FILE]). A file may
 * hold several atoms, and every changed row of its patch belongs to exactly one of them.
 */
data class ChangeAtom(
    /** Unique in a change map and stable while the path and name are: `path#Qualified.name`, `path#<top-level>` or `path`. */
    val id: String,
    val path: String,
    /** The qualified name of a declaration, "top-level code", or the file name. */
    val name: String,
    val kind: AtomKind,
    val status: AtomStatus,
    /** The declaration header on the after side, or the before side of a removed one; empty for loose code and files. */
    val header: String,
    /** A modified declaration whose header differs between the sides. */
    val signatureChanged: Boolean,
    /** 1-based rows of the file's patch owned by this atom, sorted and disjoint: its changed rows and the context between them. */
    val rows: List<IntRange>,
    /** Rows of [rows] that start with `+` or `-`. */
    val changedRows: Set<Int>,
    /** 1-based inclusive lines of the declaration on each side; null where it does not exist, and for loose code and files. */
    val beforeLines: IntRange?,
    val afterLines: IntRange?,
    val added: Int,
    val removed: Int,
    /** "implementation", "test" or "documentation", from the path. */
    val evidenceKind: String,
    /** Equal while the atom's rows hold the same text, so its summary can be kept. */
    val fingerprint: String,
) {
    val isDeclaration: Boolean get() = kind != AtomKind.LOOSE && kind != AtomKind.FILE
}

enum class ReferenceKind { CALL, TYPE, OVERRIDE, REFERENCE }

/**
 * A fact read from code: atom [from] refers to the declaration of atom [to]. [siteChanged] tells whether the referring
 * line itself is a changed line; [before] marks a reference found only on the before side (in removed code).
 */
data class AtomReference(val from: String, val to: String, val kind: ReferenceKind, val siteChanged: Boolean, val before: Boolean = false)

enum class EdgeStrength { STRONG, MEDIUM, WEAK }

/** Why two atoms belong together. Strong relations cluster by code alone; medium ones are judged; weak ones only suggest. */
enum class AtomRelation(val strength: EdgeStrength, val label: String) {
    SIGNATURE_USE(EdgeStrength.STRONG, "adapts to the changed signature of"),
    NEW_SYMBOL_USE(EdgeStrength.STRONG, "uses the new"),
    REMOVED_SYMBOL_USE(EdgeStrength.STRONG, "drops its use of the removed"),
    OVERRIDE(EdgeStrength.STRONG, "overrides"),
    MOVE(EdgeStrength.STRONG, "receives code moved from"),
    TEST_OF(EdgeStrength.STRONG, "tests"),
    REPEATED_EDIT(EdgeStrength.STRONG, "repeats the edit of"),
    USE(EdgeStrength.MEDIUM, "changes its use of"),
    SAME_EDIT(EdgeStrength.MEDIUM, "makes the same edit as"),
    CALL(EdgeStrength.WEAK, "calls"),
    SAME_OWNER(EdgeStrength.WEAK, "shares an owner with"),
}

/** Directed from the cause to its dependent: [to] `relation.label` [from] reads as a sentence, such as "B uses the new A". */
data class AtomEdge(val from: String, val to: String, val relation: AtomRelation)

/** Atoms and the typed relations between them; built by code, never by a model. */
data class ChangeGraph(val atoms: List<ChangeAtom>, val edges: List<AtomEdge>) {
    private val byId: Map<String, ChangeAtom> by lazy { atoms.associateBy { it.id } }
    fun atom(id: String): ChangeAtom? = byId[id]
}

enum class AtomRole { ORIGIN, PROPAGATION, TEST, DOCUMENTATION, STANDALONE }

/** An atom's place in its cluster's chain: [depth] hops from an origin along cause-to-dependent edges. */
data class ClusterStep(val atom: String, val role: AtomRole, val depth: Int)

/**
 * One logical change: atoms that belong together, ordered as a chain from origins through dependents to tests and
 * documentation. A cluster of one atom is a standalone change. A [foundation] is a widely used change kept on its own
 * so it does not glue unrelated changes together.
 */
data class ChangeCluster(
    /** Stable while the member atoms are the same. */
    val id: String,
    val steps: List<ClusterStep>,
    /** Written by a model, or null until then; views fall back to [ChangeMap.defaultTitle]. */
    val title: String? = null,
    val foundation: Boolean = false,
) {
    val atoms: List<String> get() = steps.map { it.atom }
    val standalone: Boolean get() = steps.size == 1
}

/** Two clusters that code could not decide on: they are linked by [edges] that are not strong. */
data class MergeCandidate(val a: String, val b: String, val edges: List<AtomEdge>)

/** A change as clusters of atoms. Membership and order come from [graph]; only titles and merge judgments are a model's. */
data class ChangeMap(val graph: ChangeGraph, val clusters: List<ChangeCluster>) {
    private val byId: Map<String, ChangeCluster> by lazy { clusters.associateBy { it.id } }
    private val owner: Map<String, ChangeCluster> by lazy { clusters.flatMap { cluster -> cluster.atoms.map { it to cluster } }.toMap() }
    fun cluster(id: String): ChangeCluster? = byId[id]
    fun clusterOf(atom: String): ChangeCluster? = owner[atom]
    fun atoms(cluster: ChangeCluster): List<ChangeAtom> = cluster.atoms.mapNotNull(graph::atom)
    /** Clusters of several atoms, which read as chains. */
    val chains: List<ChangeCluster> get() = clusters.filter { !it.standalone }
    val standalone: List<ChangeCluster> get() = clusters.filter { it.standalone }
    /** Edges whose ends are both in [cluster]. */
    fun edgesWithin(cluster: ChangeCluster): List<AtomEdge> = cluster.atoms.toSet().let { ids -> graph.edges.filter { it.from in ids && it.to in ids } }
    /** Edges leaving or entering [cluster], which link it to other clusters. */
    fun edgesAcross(cluster: ChangeCluster): List<AtomEdge> = cluster.atoms.toSet().let { ids -> graph.edges.filter { (it.from in ids) != (it.to in ids) } }
    fun withTitles(titles: Map<String, String>): ChangeMap =
        copy(clusters = clusters.map { cluster -> titles[cluster.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { cluster.copy(title = it) } ?: cluster })
    /** A title from code alone: the first origin's name, and how many other atoms follow it. */
    fun defaultTitle(cluster: ChangeCluster): String {
        val first = graph.atom(cluster.atoms.first())?.name ?: return "Change"
        return if (cluster.standalone) first else "$first and ${cluster.steps.size - 1} related"
    }
    fun title(cluster: ChangeCluster): String = cluster.title ?: defaultTitle(cluster)
}
