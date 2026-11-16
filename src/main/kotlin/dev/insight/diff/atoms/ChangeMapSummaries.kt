package dev.insight.diff.atoms

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.insight.agent.ChatTool
import dev.insight.diff.DiffSummaryEvidence
import dev.insight.diff.ReviewFile
import dev.insight.diff.diffEvidenceKind
import dev.insight.diff.patchRows
import dev.insight.llm.CODE_AS_DATA
import dev.insight.llm.CompletionFn
import dev.insight.llm.clipLine
import dev.insight.prompts.PromptTemplates
import dev.insight.review.BRANCH_CITATION_GUIDANCE
import dev.insight.review.LEAF_CITATION_GUIDANCE
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SUMMARY_JSON_SHAPE
import dev.insight.review.SUMMARY_SEGMENT_GUIDANCE
import dev.insight.review.SUMMARY_WRITING_GUIDANCE
import dev.insight.review.SummaryEvidence
import dev.insight.review.SummaryEvidencePage
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.balancedGroups
import dev.insight.review.reviewFingerprint
import dev.insight.review.summaryFocusLine

internal const val CHANGE_MAP_ROOT_IDENTITY = "change-map-root"
internal const val STANDALONE_CLUSTER_IDENTITY = "cluster:standalone"

/** Identity prefixes of the nodes of a change-map tree; the rest is the cluster or atom id. */
internal const val CLUSTER_IDENTITY_PREFIX = "cluster:"
internal const val ATOM_IDENTITY_PREFIX = "atom:"
private const val GROUP_IDENTITY_PREFIX = "change-group:"
private const val STANDALONE_LABEL = "Standalone changes"

/**
 * The summary tree of a change map: the root, a branch per chain in map order with its atoms in chain order, and one
 * branch holding the standalone changes. Atoms are the leaves, so a file may appear under several branches.
 * A branch's key leaves out its title, so a retitled chain keeps its summary while the atoms beneath it are unchanged.
 */
internal fun buildChangeMapTree(map: ChangeMap, files: List<ReviewFile>): ReviewSummaryTree {
    val byPath = files.distinctBy { it.path }.associateBy { it.path }
    val nodes = mutableListOf<SummaryNode>()

    fun leaf(atom: ChangeAtom, parent: Int): Int? {
        val file = byPath[atom.path] ?: return null
        val id = nodes.size
        val whole = atom.kind == AtomKind.FILE
        nodes += SummaryNode(id, if (whole) SummaryKind.FILE else SummaryKind.CHUNK, atom.path,
            if (whole) atom.path.substringAfterLast('/') else if (atom.kind == AtomKind.LOOSE) "top-level code" else atom.name,
            parent, emptyList(), listOf(atom.path), reviewFingerprint("atom", atom.id, atom.fingerprint), ATOM_IDENTITY_PREFIX + atom.id,
            file, lines = if (whole || atom.kind == AtomKind.LOOSE) null else atom.afterLines ?: atom.beforeLines)
        return id
    }

    /** More than [ReviewSummaryTree.MAX_CHILDREN] children are grouped like the other builders do; groups come after their members and are renumbered below. */
    fun add(kind: SummaryKind, path: String, label: String, identity: String, parent: Int?, keepEmpty: Boolean = false,
            entries: (Int) -> List<Int?>): Int? {
        val id = nodes.size
        nodes += SummaryNode(id, kind, path, label, parent, emptyList(), emptyList(), "", identity, isTopicBranch = true)
        val members = entries(id).filterNotNull()
        if (members.isEmpty() && !keepEmpty) {
            nodes.removeAt(id)
            return null
        }
        val children = balancedGroups(members, ReviewSummaryTree.MAX_CHILDREN) { group ->
            val groupId = nodes.size
            val files = group.flatMap { nodes[it].files }.distinct()
            nodes += SummaryNode(groupId, SummaryKind.GROUP, path, "${nodes[group.first()].label} … ${nodes[group.last()].label}", id, group, files,
                reviewFingerprint("group", *group.map { nodes[it].key }.toTypedArray()),
                "$GROUP_IDENTITY_PREFIX$identity:${nodes[group.first()].identity}:${nodes[group.last()].identity}", isTopicBranch = true)
            group.forEach { nodes[it] = nodes[it].copy(parent = groupId) }
            groupId
        }
        nodes[id] = nodes[id].copy(children = children, files = children.flatMap { nodes[it].files }.distinct(),
            key = reviewFingerprint(identity, *children.map { nodes[it].key }.toTypedArray()))
        return id
    }

    add(SummaryKind.ROOT, "", "All changes", CHANGE_MAP_ROOT_IDENTITY, null, keepEmpty = true) { root ->
        map.chains.map { cluster ->
            val title = map.title(cluster)
            add(SummaryKind.DIRECTORY, title, title, CLUSTER_IDENTITY_PREFIX + cluster.id, root) { branch ->
                map.atoms(cluster).map { leaf(it, branch) }
            }
        } + listOf(map.standalone.takeIf { it.isNotEmpty() }?.let { standalone ->
            add(SummaryKind.DIRECTORY, STANDALONE_LABEL, STANDALONE_LABEL, STANDALONE_CLUSTER_IDENTITY, root) { branch ->
                standalone.flatMap { map.atoms(it) }.map { leaf(it, branch) }
            }
        })
    }
    // Groups were added after their members; restore the summary tree's pre-order contract.
    val order = mutableListOf<Int>()
    fun visit(id: Int) { order += id; nodes[id].children.forEach(::visit) }
    visit(0)
    val ids = order.withIndex().associate { it.value to it.index }
    return ReviewSummaryTree(order.map { old ->
        nodes[old].copy(id = ids.getValue(old), parent = nodes[old].parent?.let(ids::getValue), children = nodes[old].children.map(ids::getValue))
    })
}

/** The atom of a leaf of a change-map tree, or null for another node. */
internal fun ChangeMap.atomOf(node: SummaryNode): ChangeAtom? =
    node.identity.takeIf { it.startsWith(ATOM_IDENTITY_PREFIX) }?.removePrefix(ATOM_IDENTITY_PREFIX)?.let(graph::atom)

/** The cluster of a chain branch of a change-map tree, or null for another node. */
internal fun ChangeMap.clusterOf(node: SummaryNode): ChangeCluster? =
    node.identity.takeIf { it.startsWith(CLUSTER_IDENTITY_PREFIX) && it != STANDALONE_CLUSTER_IDENTITY }?.removePrefix(CLUSTER_IDENTITY_PREFIX)?.let(::cluster)

/** Summarizes a change-map tree: leaves from their atom's patch rows and relations, chains from their steps in order. */
internal fun changeMapSummarizer(
    complete: CompletionFn,
    readTool: ChatTool?,
    map: ChangeMap,
    files: List<ReviewFile>,
    options: ReviewSummaryOptions = ReviewSummaryOptions(),
    maxContextChars: Int = 64_000,
): ReviewSummarizer = ReviewSummarizer(complete, readTool, options, AtomSummaryEvidence(map, files), maxContextChars)

private const val MAX_RELATIONS = 8
private const val MAX_NAMED_DEPENDENTS = 3

/** How the cause end of a relation reads about its dependents: "is newly used by …". */
private val AtomRelation.passive: String get() = when (this) {
    AtomRelation.SIGNATURE_USE -> "has its changed signature adapted to by"
    AtomRelation.NEW_SYMBOL_USE -> "is newly used by"
    AtomRelation.REMOVED_SYMBOL_USE -> "was removed and is no longer used by"
    AtomRelation.OVERRIDE -> "is overridden by"
    AtomRelation.MOVE -> "is the source of code moved to"
    AtomRelation.TEST_OF -> "is tested by"
    AtomRelation.REPEATED_EDIT -> "has its edit repeated by"
    AtomRelation.USE -> "has its use changed by"
    AtomRelation.SAME_EDIT -> "gets the same edit as"
    AtomRelation.CALL -> "is called by"
    AtomRelation.SAME_OWNER -> "shares an owner with"
}

/**
 * What code knows of [atom]'s links to other changes as readable sentences, at most [limit]: those inside its cluster first,
 * strongest relation first, then those across clusters. An atom that others depend on is described once per relation.
 */
internal fun ChangeMap.relationSentences(atom: ChangeAtom, limit: Int = MAX_RELATIONS): List<String> {
    val own = clusterOf(atom.id)?.atoms?.toSet().orEmpty()
    val touching = graph.edges.filter { (it.from == atom.id) != (it.to == atom.id) }
    fun sentences(edges: List<AtomEdge>, across: Boolean): List<String> {
        val suffix = if (across) " (in another change)" else ""
        val uses = edges.filter { it.to == atom.id }.sortedBy { it.relation.strength.ordinal }.mapNotNull { edge ->
            graph.atom(edge.from)?.let { "${edge.relation.label} ${it.reference()}$suffix" }
        }
        val dependents = edges.filter { it.from == atom.id }.groupBy { it.relation }.entries.sortedBy { it.key.strength.ordinal }.mapNotNull { (relation, group) ->
            val named = group.mapNotNull { graph.atom(it.to) }.distinctBy { it.id }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val list = named.take(MAX_NAMED_DEPENDENTS).joinToString(", ") { it.reference() } + if (named.size > MAX_NAMED_DEPENDENTS) ", …" else ""
            if (named.size == 1) "${relation.passive} $list$suffix" else "${relation.passive} ${named.size} changed atoms: $list$suffix"
        }
        return uses + dependents
    }
    val (inside, across) = touching.partition { (if (it.from == atom.id) it.to else it.from) in own }
    return (sentences(inside, false) + sentences(across, true)).distinct().take(limit)
}

/** What a summary request shows of a change-map leaf and branch; a whole file with no atom reads like a diff file. */
internal class AtomSummaryEvidence(private val map: ChangeMap, files: List<ReviewFile>) : SummaryEvidence {
    private val byPath = files.distinctBy { it.path }.associateBy { it.path }
    private val diff = DiffSummaryEvidence()
    private val present = byPath.keys

    private fun linesOf(atom: ChangeAtom): List<String> = patchLines(byPath[atom.path]?.patch.orEmpty())
    private fun rowsOf(atom: ChangeAtom): List<AtomRow> = atomRows(atom, linesOf(atom))

    override fun size(node: SummaryNode): Int =
        map.atomOf(node)?.let { atom -> rowsOf(atom).sumOf { it.text.length + 1 } } ?: diff.size(node)

    override fun rows(node: SummaryNode): IntRange =
        map.atomOf(node)?.let { atom -> if (atom.rows.isEmpty()) IntRange.EMPTY else atom.rows.first().first..atom.rows.last().last } ?: diff.rows(node)

    override fun changedRows(node: SummaryNode): Set<Int>? = map.atomOf(node)?.changedRows ?: diff.changedRows(node)

    /** Path hints, as for diffs: a leaf has its atom's, a branch the one kind of every file beneath it, else "mixed". */
    override fun evidenceKind(node: SummaryNode): String =
        map.atomOf(node)?.evidenceKind ?: node.files.map(::diffEvidenceKind).distinct().singleOrNull() ?: "mixed"

    override fun describe(node: SummaryNode, item: JsonObject, limit: Int) {
        val atom = map.atomOf(node) ?: return diff.describe(node, item, limit)
        val lines = linesOf(atom)
        val rows = atomRows(atom, lines)
        val shown = rowsWithin(rows, limit)
        item.addAtomContext(atom, byPath[atom.path], shown, lines)
        item.addProperty("lines", "+${atom.added} -${atom.removed}")
        item.addProperty("patch", renderRows(shown, numbered = true))
        if (shown.size < rows.size) {
            // The read tool continues at a character offset of the file's patch, where the first omitted row starts.
            val patch = byPath.getValue(atom.path).patch
            item.addProperty("patch_cut_at", lines.take(rows[shown.size].number - 1).sumOf { it.length + 1 })
            item.addProperty("patch_length", patch.length)
            item.addProperty("patch_lines", patchRows(patch))
        }
        map.relationSentences(atom).takeIf { it.isNotEmpty() }?.let { relations -> item.add("relations", JsonArray().apply { relations.forEach(::add) }) }
    }

    /** An atom larger than [limit] is cut into pages of whole rows, each cited by its original row numbers. */
    override fun pages(node: SummaryNode, limit: Int): List<SummaryEvidencePage>? {
        val atom = map.atomOf(node) ?: return diff.pages(node, limit)
        if (size(node) <= limit) return null
        require(limit > 0) { "Patch page size must be positive" }
        val lines = linesOf(atom)
        val rowLimit = (limit - 1).coerceAtLeast(1)
        val rows = rowsOf(atom).map { it.copy(text = it.text.take(rowLimit)) }
        val pages = mutableListOf<List<AtomRow>>()
        var page = mutableListOf<AtomRow>()
        var used = 0
        for (row in rows) {
            if (page.isNotEmpty() && used + row.text.length + 1 > limit) { pages += page; page = mutableListOf(); used = 0 }
            page += row
            used += row.text.length + 1
        }
        if (page.isNotEmpty()) pages += page
        return pages.mapNotNull { rowsOfPage ->
            val changed = rowsOfPage.mapNotNullTo(mutableSetOf()) { it.number.takeIf { number -> number in atom.changedRows } }
            // A page of unchanged context cannot establish a change of its own.
            if (changed.isEmpty() && atom.changedRows.isNotEmpty()) return@mapNotNull null
            val item = JsonObject().apply {
                addAtomContext(atom, byPath[atom.path], rowsOfPage, lines)
                addProperty("first_line", rowsOfPage.first().number)
                addProperty("last_line", rowsOfPage.last().number)
                addProperty("patch", renderRows(rowsOfPage, numbered = true))
            }
            SummaryEvidencePage(item, rowsOfPage.first().number..rowsOfPage.last().number, changed)
        }
    }

    override fun leafSystem(tree: ReviewSummaryTree, focus: String): String =
        "$SYSTEM_PREFIX\n" + PromptTemplates.load("diff-change-map-leaf.md") + "\n" + LEAF_CITATION_GUIDANCE + summaryFocusLine(focus)

    override fun branchSystem(focus: String): String =
        "$SYSTEM_PREFIX\n" + PromptTemplates.load("diff-change-map-branch.md") + "\n" + BRANCH_CITATION_GUIDANCE + summaryFocusLine(focus)

    override fun describeBranch(node: SummaryNode, item: JsonObject) {
        when {
            node.identity == CHANGE_MAP_ROOT_IDENTITY -> {
                fun shown(clusters: List<ChangeCluster>) = clusters.count { cluster -> cluster.atoms.any { map.graph.atom(it)?.path in present } }
                item.addProperty("change_chains", shown(map.chains))
                item.addProperty("standalone_changes", map.standalone.sumOf { map.atoms(it).count { atom -> atom.path in present } })
            }
            node.identity == STANDALONE_CLUSTER_IDENTITY ->
                item.addProperty("note", "Its children are unrelated standalone changes; no relation links them, so do not look for a common purpose.")
            else -> map.clusterOf(node)?.let { cluster ->
                item.addProperty("change_title", map.title(cluster))
                item.add("chain", chain(cluster))
            }
        }
    }

    /** The steps of [cluster] in reading order; each names the relation that links it to an earlier step, when code found one. */
    private fun chain(cluster: ChangeCluster): JsonArray {
        val steps = cluster.steps.filter { step -> map.graph.atom(step.atom)?.path in present }
        val position = steps.withIndex().associate { it.value.atom to it.index + 1 }
        val inside = map.edgesWithin(cluster)
        return JsonArray().apply {
            steps.forEachIndexed { index, step ->
                val atom = map.graph.atom(step.atom) ?: return@forEachIndexed
                val link = inside.filter { it.to == step.atom && (position[it.from] ?: Int.MAX_VALUE) <= index }
                    .minByOrNull { it.relation.strength.ordinal }
                add(JsonObject().apply {
                    addProperty("step", index + 1)
                    addProperty("role", step.role.name.lowercase())
                    addProperty("symbol", atom.name)
                    addProperty("path", atom.path)
                    link?.let { edge -> map.graph.atom(edge.from)?.let { cause ->
                        addProperty("relation", "${edge.relation.label} ${cause.reference()} (step ${position.getValue(edge.from)})")
                    } }
                })
            }
        }
    }

    private companion object {
        val SYSTEM_PREFIX = PromptTemplates.render(
            "diff-change-map-prefix.md", mapOf("CODE_AS_DATA" to CODE_AS_DATA, "SUMMARY_JSON_SHAPE" to SUMMARY_JSON_SHAPE),
        ) + "\n" + SUMMARY_WRITING_GUIDANCE + "\n" + SUMMARY_SEGMENT_GUIDANCE
    }
}

/** Facts about the atom, then counts and the nearest hunk header for the [shown] rows, as a diff page carries them. */
private fun JsonObject.addAtomContext(atom: ChangeAtom, file: ReviewFile?, shown: List<AtomRow>, lines: List<String>) {
    addProperty("path", atom.path)
    addProperty("status", atom.status.name.lowercase())
    file?.previousPath?.let { addProperty("previous_path", it) }
    addProperty("symbol", atom.name)
    addProperty("symbol_kind", atom.kind.name.lowercase())
    atom.header.takeIf { it.isNotEmpty() }?.let { addProperty("header", it.clipLine(400)) }
    addProperty("added_lines", shown.count { it.text.startsWith('+') })
    addProperty("removed_lines", shown.count { it.text.startsWith('-') })
    val from = shown.firstOrNull { it.number in atom.changedRows }?.number ?: shown.firstOrNull()?.number ?: return
    (from - 1 downTo 0).firstOrNull { lines.getOrNull(it)?.startsWith("@@") == true }?.let { addProperty("hunk_header", lines[it].take(400)) }
}
