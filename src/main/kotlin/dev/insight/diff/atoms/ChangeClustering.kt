package dev.insight.diff.atoms

import dev.insight.diff.CodeMoves
import dev.insight.review.reviewFingerprint

data class ClusterOptions(
    /** A declaration with strong edges to at least this many dependents is a hub and never glues components together. */
    val hubDegree: Int = 6,
    /** At least this many atoms sharing one small edit make it a repeated edit, which clusters them. */
    val repeatedEdit: Int = 3,
)

/** Turns code facts into typed edges: references by the status of their target, moved blocks, and shared small edits. */
internal fun relate(atoms: List<ChangeAtom>, references: List<AtomReference>, moves: CodeMoves, patches: Map<String, String>,
                    options: ClusterOptions = ClusterOptions()): ChangeGraph {
    val byId = atoms.associateBy { it.id }
    val edges = mutableListOf<AtomEdge>()
    for (reference in references) {
        val user = byId[reference.from] ?: continue
        val declaration = byId[reference.to] ?: continue
        if (user.id != declaration.id) edges += AtomEdge(declaration.id, user.id, referenceRelation(user, declaration, reference))
    }
    val numbered = HashMap<String, List<PatchRow>>()
    fun owner(path: String, lines: IntRange, marker: Char): ChangeAtom? {
        val rows = numbered.getOrPut(path) { patchLineNumbers(patches[path].orEmpty()) }
            .filter { it.marker == marker && (if (marker == '-') it.old else it.new) in lines }.map { it.row }
        val candidates = atoms.filter { it.path == path }
        return candidates.maxByOrNull { atom -> rows.count { it in atom.changedRows } }?.takeIf { atom -> rows.any { it in atom.changedRows } }
    }
    for (block in moves.blocks) {
        val source = owner(block.from, block.fromLines, '-') ?: continue
        val target = owner(block.to, block.toLines, '+') ?: continue
        if (source.id != target.id) edges += AtomEdge(source.id, target.id, AtomRelation.MOVE)
    }
    edges += sharedEdits(atoms, patches, options) + sameOwner(atoms)
    return ChangeGraph(atoms, strongestEdges(atoms, edges))
}

private fun referenceRelation(user: ChangeAtom, declaration: ChangeAtom, reference: AtomReference): AtomRelation = when {
    user.evidenceKind == "test" && declaration.evidenceKind != "test" ->
        if (reference.siteChanged || declaration.status != AtomStatus.MODIFIED || declaration.signatureChanged) AtomRelation.TEST_OF else AtomRelation.USE
    reference.kind == ReferenceKind.OVERRIDE ->
        if (declaration.status != AtomStatus.MODIFIED || declaration.signatureChanged || user.status == AtomStatus.ADDED) AtomRelation.OVERRIDE else AtomRelation.CALL
    declaration.status == AtomStatus.ADDED && reference.siteChanged -> AtomRelation.NEW_SYMBOL_USE
    declaration.status == AtomStatus.REMOVED -> if (reference.siteChanged) AtomRelation.REMOVED_SYMBOL_USE else AtomRelation.CALL
    declaration.signatureChanged && reference.siteChanged -> AtomRelation.SIGNATURE_USE
    reference.siteChanged -> AtomRelation.USE
    else -> AtomRelation.CALL
}

/**
 * Atoms that make the same small edit (a renamed identifier, an added argument): the words only the removed lines have
 * and the words only the added lines have. A star from the first atom keeps the edge count linear in a sweeping edit.
 */
private fun sharedEdits(atoms: List<ChangeAtom>, patches: Map<String, String>, options: ClusterOptions): List<AtomEdge> {
    val rowsOf = HashMap<String, Map<Int, String>>()
    val groups = atoms.filter { it.status == AtomStatus.MODIFIED && !(it.kind == AtomKind.FILE && it.evidenceKind == "documentation") }.mapNotNull { atom ->
        val lines = rowsOf.getOrPut(atom.path) { patches[atom.path].orEmpty().removeSuffix("\n").split('\n').withIndex().associate { (i, line) -> i + 1 to line } }
        val removed = atom.changedRows.sorted().mapNotNull { lines[it] }.filter { it.startsWith('-') }.flatMap { words(it) }
        val added = atom.changedRows.sorted().mapNotNull { lines[it] }.filter { it.startsWith('+') }.flatMap { words(it) }
        val gone = difference(removed, added)
        val new = difference(added, removed)
        if ((gone + new).none { it.length > 2 && it.lowercase() !in COMMON_WORDS } || gone.size > MAX_EDIT_WORDS || new.size > MAX_EDIT_WORDS) null else (gone to new) to atom
    }.groupBy({ it.first }, { it.second }).values.filter { it.size >= 2 }
    return groups.flatMap { group ->
        val relation = if (group.size >= options.repeatedEdit) AtomRelation.REPEATED_EDIT else AtomRelation.SAME_EDIT
        group.drop(1).map { AtomEdge(group.first().id, it.id, relation) }
    }
}

private fun words(line: String): List<String> = WORD.findAll(line.substring(1)).map { it.value }.toList()

/** Words of [a] left after taking away one occurrence for each occurrence in [b]. */
private fun difference(a: List<String>, b: List<String>): Set<String> {
    val counts = b.groupingBy { it }.eachCount().toMutableMap()
    return a.filterTo(linkedSetOf()) { word -> counts[word]?.takeIf { it > 0 }?.let { counts[word] = it - 1; false } ?: true }
}

/** Declarations of one file that share an owner, chained in source order, so a class's members cluster without all pairs. */
private fun sameOwner(atoms: List<ChangeAtom>): List<AtomEdge> = atoms.filter { it.isDeclaration }.groupBy { it.path }.values.flatMap { file ->
    file.sortedBy { it.rows.first().first }.groupBy { it.name.substringBeforeLast('.', "") }.filterKeys { it.isNotEmpty() }.values
        .flatMap { members -> members.zipWithNext { a, b -> AtomEdge(a.id, b.id, AtomRelation.SAME_OWNER) } }
}

/** One edge per ordered pair, the strongest; of two opposite edges only strong ones both stay, else the stronger. */
private fun strongestEdges(atoms: List<ChangeAtom>, edges: List<AtomEdge>): List<AtomEdge> {
    val index = atoms.withIndex().associate { (i, atom) -> atom.id to i }
    val rank = compareBy<AtomEdge>({ it.relation.strength }, { it.relation })
    val kept = edges.groupBy { it.from to it.to }.mapValues { (_, same) -> same.minWith(rank) }
    val result = kept.values.filter { edge ->
        val opposite = kept[edge.to to edge.from] ?: return@filter true
        if (edge.relation.strength == EdgeStrength.STRONG && opposite.relation.strength == EdgeStrength.STRONG) return@filter true
        val order = rank.compare(edge, opposite)
        if (order != 0) order < 0 else index.getValue(edge.from) < index.getValue(edge.to)
    }
    return result.sortedWith(compareBy({ index.getValue(it.from) }, { index.getValue(it.to) }, { it.relation }))
}

private const val MAX_EDIT_WORDS = 6
/** Words too common to tell one edit from another: an edit made only of them, such as an added `return null`, is no signature. */
private val COMMON_WORDS = setOf("return", "null", "none", "nil", "val", "var", "let", "const", "final", "if", "else", "true", "false", "this", "self",
    "new", "void", "int", "string", "public", "private", "fun", "def", "function", "import", "from", "override", "static", "for", "while", "try", "catch")
private val WORD = Regex("""[\p{L}_][\p{L}\p{N}_]*|\p{N}+""")

/** Clusters atoms over strong edges, keeps hubs from gluing components, and orders each cluster as a chain. */
internal fun clusterChanges(graph: ChangeGraph, options: ClusterOptions = ClusterOptions()): ChangeMap {
    val atoms = graph.atoms
    val index = atoms.withIndex().associate { (i, atom) -> atom.id to i }
    val strong = graph.edges.filter { it.relation.strength == EdgeStrength.STRONG && it.from in index && it.to in index }
    val dependents = strong.groupBy({ index.getValue(it.from) }, { index.getValue(it.to) }).mapValues { it.value.toSortedSet() }
    val hubs = dependents.filterValues { it.size >= options.hubDegree }.keys.sorted()
    val parent = IntArray(atoms.size) { it }
    fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }; return r }
    fun union(a: Int, b: Int) { val x = find(a); val y = find(b); if (x != y) parent[maxOf(x, y)] = minOf(x, y) }
    for (edge in strong) if (index.getValue(edge.from) !in hubs) union(index.getValue(edge.from), index.getValue(edge.to))
    val foundations = mutableSetOf<Int>()
    for (hub in hubs) {
        val sizes = atoms.indices.groupingBy { find(it) }.eachCount()
        val (singles, others) = dependents.getValue(hub).filter { it != hub }.partition { sizes.getValue(find(it)) == 1 }
        singles.forEach { union(hub, it) }
        val components = others.map { find(it) }.toSet()
        if (components.size == 1) union(hub, components.first()) else if (components.size > 1) foundations += hub
    }
    val flagged = foundations.map { find(it) }.toSet()
    val clusters = atoms.indices.groupBy { find(it) }.map { (root, members) -> chainCluster(graph, index, members.map { atoms[it] }, root in flagged) }
    return ChangeMap(graph, ordered(graph, clusters))
}

/** A cluster of [members] with its chain: depth is the distance from origins, then implementation before tests before documentation. */
private fun chainCluster(graph: ChangeGraph, index: Map<String, Int>, members: List<ChangeAtom>, foundation: Boolean, title: String? = null): ChangeCluster {
    val sorted = members.sortedBy { index.getValue(it.id) }
    val ids = sorted.map { it.id }.toSet()
    val edges = graph.edges.filter { it.relation.strength != EdgeStrength.WEAK && it.from in ids && it.to in ids && it.from != it.to }
    val next = edges.groupBy({ it.from }, { it.to })
    val incoming = edges.map { it.to }.toSet()
    val depth = HashMap<String, Int>()
    fun walk(roots: List<String>) {
        val queue = ArrayDeque<String>()
        roots.forEach { depth[it] = 0; queue += it }
        while (queue.isNotEmpty()) {
            val atom = queue.removeFirst()
            for (to in next[atom].orEmpty()) if (to !in depth) { depth[to] = depth.getValue(atom) + 1; queue += to }
        }
    }
    walk(sorted.filter { it.id !in incoming }.map { it.id })
    // A cycle leaves no root: the origin is the atom that introduced or reshaped a declaration, else the first one.
    while (depth.size < sorted.size) {
        val rest = sorted.filter { it.id !in depth }
        walk(listOf((rest.firstOrNull { it.status == AtomStatus.ADDED || it.signatureChanged } ?: rest.first()).id))
    }
    val steps = sorted.sortedWith(compareBy({ depth.getValue(it.id) }, { EVIDENCE_ORDER.indexOf(it.evidenceKind) }, { it.path }, { it.rows.first().first }, { index.getValue(it.id) })).map { atom ->
        val role = when {
            sorted.size == 1 -> AtomRole.STANDALONE
            atom.evidenceKind == "test" -> AtomRole.TEST
            atom.evidenceKind == "documentation" -> AtomRole.DOCUMENTATION
            depth.getValue(atom.id) == 0 -> AtomRole.ORIGIN
            else -> AtomRole.PROPAGATION
        }
        ClusterStep(atom.id, role, depth.getValue(atom.id))
    }
    return ChangeCluster(reviewFingerprint(*ids.sorted().toTypedArray()), steps, title, foundation)
}

private val EVIDENCE_ORDER = listOf("implementation", "test", "documentation")

/** Chains first, the largest change first; then standalone changes in file order. */
private fun ordered(graph: ChangeGraph, clusters: List<ChangeCluster>): List<ChangeCluster> {
    fun lines(cluster: ChangeCluster) = cluster.atoms.sumOf { graph.atom(it)?.let { atom -> atom.added + atom.removed } ?: 0 }
    val (chains, single) = clusters.partition { !it.standalone }
    return chains.sortedWith(compareBy<ChangeCluster>({ -lines(it) }, { it.id })) +
        single.sortedWith(compareBy<ChangeCluster>({ graph.atom(it.atoms.first())?.path.orEmpty() }, { graph.atom(it.atoms.first())?.rows?.first()?.first ?: 0 }, { it.id }))
}

/** Cluster pairs linked only by medium edges, most linked first; a model judges them. */
internal fun mergeCandidates(map: ChangeMap): List<MergeCandidate> =
    map.graph.edges.filter { it.relation.strength == EdgeStrength.MEDIUM }.mapNotNull { edge ->
        val a = map.clusterOf(edge.from)?.id ?: return@mapNotNull null
        val b = map.clusterOf(edge.to)?.id ?: return@mapNotNull null
        if (a == b) null else (if (a < b) a to b else b to a) to edge
    }.groupBy({ it.first }, { it.second }).map { (pair, edges) -> MergeCandidate(pair.first, pair.second, edges) }
        .sortedWith(compareBy({ -it.edges.size }, { it.a }, { it.b }))

/** Chain clusters a standalone cluster is linked to by any edge, most linked first; a model may attach it to one. */
internal fun attachCandidates(map: ChangeMap, standalone: ChangeCluster): List<ChangeCluster> {
    val own = standalone.atoms.toSet()
    val counts = map.graph.edges.filter { (it.from in own) != (it.to in own) }.mapNotNull { map.clusterOf(if (it.from in own) it.to else it.from) }
        .filter { !it.standalone }.groupingBy { it.id }.eachCount()
    val position = map.clusters.withIndex().associate { (i, cluster) -> cluster.id to i }
    return counts.keys.sortedWith(compareBy({ -counts.getValue(it) }, { position.getValue(it) })).map { map.cluster(it)!! }
}

/** The map with each of [groups] (cluster ids) merged into one cluster with a recomputed chain; titles of merged clusters are dropped. */
internal fun ChangeMap.merged(groups: List<Set<String>>): ChangeMap {
    val parent = HashMap<String, String>()
    fun find(x: String): String = parent[x]?.takeIf { it != x }?.let(::find) ?: x
    val touched = mutableSetOf<String>()
    for (group in groups) {
        val known = group.filter { cluster(it) != null }
        if (known.size < 2) continue
        touched += known
        known.drop(1).forEach { parent[find(it)] = find(known.first()) }
    }
    val merged = clusters.map { it.id }.filter { it in touched }.groupBy { find(it) }.values
    if (merged.isEmpty()) return this
    val index = graph.atoms.withIndex().associate { (i, atom) -> atom.id to i }
    val combined = merged.map { ids ->
        val parts = ids.map { cluster(it)!! }
        chainCluster(graph, index, parts.flatMap { atoms(it) }, parts.all { it.foundation })
    }
    return copy(clusters = ordered(graph, clusters.filter { it.id !in touched } + combined))
}
