package dev.insight.diff.atoms

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.diff.ReviewFile
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmRole
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import dev.insight.review.reviewJson
import dev.insight.review.summarize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChangeMapSummariesTest {
    /** A patch of [rows] rows: a hunk header, then alternating removed and added rows at [changed], unchanged context elsewhere. */
    private fun patch(path: String, rows: Int = 14, changed: Set<Int> = setOf(3, 4, 9, 10)) = (1..rows).joinToString("\n") { row ->
        when {
            row == 1 -> "@@ -1,$rows +1,$rows @@"
            row in changed -> if (row % 2 == 1) "-old $row $path" else "+new $row $path"
            else -> " context $row"
        }
    } + "\n"

    private fun atom(
        name: String, path: String = "src/$name.kt", kind: AtomKind = AtomKind.FUNCTION, rows: List<IntRange> = listOf(2..5, 8..11),
        changed: Set<Int> = setOf(3, 4, 9, 10), fingerprint: String = "fp-$name", evidenceKind: String = "implementation",
        after: IntRange? = 10..20, before: IntRange? = 10..19,
    ) = ChangeAtom("$path#$name", path, name, kind, AtomStatus.MODIFIED, if (kind == AtomKind.FILE || kind == AtomKind.LOOSE) "" else "fun $name()", false,
        rows, changed, before, after, changed.count { it % 2 == 0 }, changed.count { it % 2 == 1 }, evidenceKind, fingerprint)

    private fun chain(id: String, vararg atoms: ChangeAtom, title: String? = null) = ChangeCluster(id, atoms.mapIndexed { index, atom ->
        ClusterStep(atom.id, when { index == 0 -> AtomRole.ORIGIN; atom.evidenceKind == "test" -> AtomRole.TEST; else -> AtomRole.PROPAGATION }, index)
    }, title)

    private fun file(path: String, patch: String = patch(path)) = ReviewFile(path, "/repo/$path", null, "modified", patch, "v-$path")

    private val origin = atom("Foo.make", "src/Foo.kt"); private val user = atom("Bar.use", "src/Bar.kt"); private val test = atom("BarTest.uses", "src/test/BarTest.kt", evidenceKind = "test")
    private val loose = atom("top-level code", "src/Util.kt", AtomKind.LOOSE, after = null, before = null)
    private val readme = atom("README.md", "README.md", AtomKind.FILE, rows = listOf(1..4), changed = setOf(2, 3), after = null, before = null, evidenceKind = "documentation")
    private val lone = atom("Zed.zap", "src/Zed.kt"); private val other = atom("Other.other", "src/Other.kt")

    private val graph = ChangeGraph(listOf(origin, user, test, loose, readme, lone, other), listOf(
        AtomEdge(origin.id, user.id, AtomRelation.NEW_SYMBOL_USE), AtomEdge(user.id, test.id, AtomRelation.TEST_OF),
        AtomEdge(origin.id, test.id, AtomRelation.USE), AtomEdge(origin.id, lone.id, AtomRelation.CALL)))
    private val fullMap = ChangeMap(graph, listOf(chain("k1", origin, user, test, title = "Make things"), chain("k2", loose, readme), chain("s1", lone), chain("s2", other)))
    private val allFiles = listOf("src/Foo.kt", "src/Bar.kt", "src/test/BarTest.kt", "src/Util.kt", "README.md", "src/Zed.kt", "src/Other.kt").map { path ->
        file(path, if (path == "README.md") patch(path, 5, setOf(2, 3)) else patch(path))
    }
    private val files = allFiles.filter { it.path != "src/Other.kt" }

    private fun tree(m: ChangeMap = fullMap, f: List<ReviewFile> = allFiles) = buildChangeMapTree(m, f)
    private fun ReviewSummaryTree.byIdentity(identity: String) = nodes.single { it.identity == identity }
    private fun ReviewSummaryTree.labels(node: SummaryNode) = node.children.map { get(it).label }

    @Test fun `tree has the root a branch per chain with leaves in chain order and one standalone branch`() {
        val tree = tree()
        val root = tree.root
        assertEquals(SummaryKind.ROOT, root.kind)
        assertEquals("", root.path)
        assertEquals("All changes", root.label)
        assertEquals(CHANGE_MAP_ROOT_IDENTITY, root.identity)
        assertTrue(root.isTopicBranch)
        assertEquals(listOf("Make things", "top-level code and 1 related", "Standalone changes"), tree.labels(root))

        val k1 = tree.byIdentity("cluster:k1")
        assertEquals(SummaryKind.DIRECTORY, k1.kind)
        assertTrue(k1.isTopicBranch)
        assertEquals("Make things", k1.path)
        assertEquals(listOf("Foo.make", "Bar.use", "BarTest.uses"), tree.labels(k1))
        assertEquals(listOf("src/Foo.kt", "src/Bar.kt", "src/test/BarTest.kt"), k1.files)

        val make = tree[k1.children[0]]
        assertEquals(SummaryKind.CHUNK, make.kind)
        assertEquals("src/Foo.kt", make.path)
        assertEquals("atom:src/Foo.kt#Foo.make", make.identity)
        assertEquals(10..20, make.lines)
        assertEquals(listOf("src/Foo.kt"), make.files)
        assertEquals(files[0], make.file)
        assertFalse(make.isTopicBranch)
        assertEquals("src/Foo.kt:10-20", make.location)

        val k2 = tree.byIdentity("cluster:k2")
        val (top, doc) = k2.children.map(tree::get)
        assertEquals("top-level code" to null, top.label to top.lines)
        assertEquals(SummaryKind.CHUNK, top.kind)
        assertEquals(SummaryKind.FILE, doc.kind)
        assertEquals("README.md", doc.label)
        assertEquals("README.md", doc.path)
        assertNull(doc.lines)

        val standalone = tree.byIdentity(STANDALONE_CLUSTER_IDENTITY)
        assertEquals("Standalone changes", standalone.label)
        assertTrue(standalone.isTopicBranch)
        assertEquals(listOf("Zed.zap", "Other.other"), tree.labels(standalone))
        // Pre-order: parents precede children, and ids are positions.
        tree.nodes.forEachIndexed { index, node -> assertEquals(index, node.id); node.parent?.let { assertTrue(it < node.id) }; assertTrue(node.children.all { it > node.id }) }
        assertEquals(tree.nodes.size, tree.nodes.map { it.identity }.toSet().size)
        assertEquals(7, tree.root.files.size)
    }

    @Test fun `branch files are distinct and an atom whose file is missing is skipped`() {
        val sameFile = atom("Foo.other", "src/Foo.kt", rows = listOf(12..13), changed = setOf(12))
        val m = ChangeMap(ChangeGraph(listOf(origin, sameFile, user), emptyList()), listOf(chain("k1", origin, sameFile, user)))
        val tree = tree(m, files)
        val k1 = tree.byIdentity("cluster:k1")
        assertEquals(listOf("Foo.make", "Foo.other", "Bar.use"), tree.labels(k1))
        assertEquals(listOf("src/Foo.kt", "src/Bar.kt"), k1.files)
        assertEquals(listOf("src/Foo.kt", "src/Bar.kt"), tree.root.files)

        val missing = tree(m, files.filter { it.path != "src/Bar.kt" })
        assertEquals(listOf("Foo.make", "Foo.other"), missing.labels(missing.byIdentity("cluster:k1")))
        // A branch left without leaves disappears; the root stays.
        val none = tree(m, emptyList())
        assertEquals(1, none.nodes.size)
        assertTrue(none.summarizable.isEmpty())
    }

    @Test fun `an empty map is a root without files`() {
        val tree = buildChangeMapTree(ChangeMap(ChangeGraph(emptyList(), emptyList()), emptyList()), emptyList())
        assertEquals(1, tree.nodes.size)
        assertEquals(emptyList<String>(), tree.root.files)
        assertTrue(tree.root.children.isEmpty())
        assertTrue(tree.summarizable.isEmpty())
    }

    @Test fun `more than eight children are grouped and the tree stays pre-ordered`() {
        val many = (1..20).map { atom("m$it", "src/m$it.kt") }
        val singles = (1..10).map { atom("n$it", "src/n$it.kt") }
        val m = ChangeMap(ChangeGraph(many + singles, emptyList()), listOf(chain("big", *many.toTypedArray())) + singles.map { chain(it.id, it) })
        val tree = buildChangeMapTree(m, many.map { file(it.path) } + (1..10).map { file("src/n$it.kt") })
        assertTrue(tree.nodes.all { it.children.size <= ReviewSummaryTree.MAX_CHILDREN })
        val big = tree.byIdentity("cluster:big")
        assertEquals(listOf(7, 7, 6), big.children.map { tree[it].children.size })
        val group = tree[big.children[0]]
        assertEquals(SummaryKind.GROUP, group.kind)
        assertEquals("m1 … m7", group.label)
        assertTrue(group.isTopicBranch)
        assertEquals(big.path, group.path)
        assertEquals(big.id, group.parent)
        assertEquals(big.files, tree[big.children[0]].files + tree[big.children[1]].files + tree[big.children[2]].files)
        assertEquals((1..20).map { "m$it" }, big.children.flatMap { tree[it].children }.map { tree[it].label })
        assertTrue(tree[tree[big.children[0]].children.first()].parent == group.id)
        tree.nodes.forEachIndexed { index, node -> assertEquals(index, node.id); node.parent?.let { assertTrue(it < node.id) } }
        // The standalone atoms are grouped too.
        val standalone = tree.byIdentity(STANDALONE_CLUSTER_IDENTITY)
        assertEquals(2, standalone.children.size)
        assertTrue(standalone.children.all { tree[it].kind == SummaryKind.GROUP })
        assertEquals(tree.nodes.size, tree.nodes.map { it.identity }.toSet().size)
        assertEquals(tree.nodes.size, tree.nodes.map { it.key }.toSet().size)
    }

    @Test fun `keys survive a retitle and change with an atom fingerprint`() {
        val before = tree()
        val retitled = tree(fullMap.withTitles(mapOf("k1" to "A new title")))
        assertEquals("A new title", retitled.byIdentity("cluster:k1").label)
        assertEquals(before.nodes.map { it.key }, retitled.nodes.map { it.key })
        assertEquals(before.nodes.map { it.identity }, retitled.nodes.map { it.identity })

        val changed = fullMap.copy(graph = fullMap.graph.copy(atoms = fullMap.graph.atoms.map { if (it.id == user.id) it.copy(fingerprint = "other") else it }))
        val after = tree(changed)
        fun key(tree: ReviewSummaryTree, identity: String) = tree.byIdentity(identity).key
        assertNotEquals(key(before, "atom:${user.id}"), key(after, "atom:${user.id}"))
        assertNotEquals(key(before, "cluster:k1"), key(after, "cluster:k1"))
        assertNotEquals(key(before, CHANGE_MAP_ROOT_IDENTITY), key(after, CHANGE_MAP_ROOT_IDENTITY))
        assertEquals(key(before, "atom:${origin.id}"), key(after, "atom:${origin.id}"))
        assertEquals(key(before, "cluster:k2"), key(after, "cluster:k2"))
        assertEquals(key(before, STANDALONE_CLUSTER_IDENTITY), key(after, STANDALONE_CLUSTER_IDENTITY))
        // Summaries of untouched nodes are kept when the tree is rebuilt.
        val summaries = ReviewSummaries(before).summarize(before.nodes.filter { it.children.isEmpty() }.associate { it.id to "Summary ${it.label}." })
        val retained = summaries.retainedIn(after)
        assertEquals("Summary Foo.make.", retained.text(after.byIdentity("atom:${origin.id}").id))
        assertNull(retained.text(after.byIdentity("atom:${user.id}").id))
    }

    @Test fun `owners of tree nodes are found through their identity`() {
        val tree = tree()
        assertEquals(user, fullMap.atomOf(tree.byIdentity("atom:${user.id}")))
        assertNull(fullMap.atomOf(tree.root))
        assertEquals("k1", fullMap.clusterOf(tree.byIdentity("cluster:k1"))!!.id)
        assertNull(fullMap.clusterOf(tree.byIdentity(STANDALONE_CLUSTER_IDENTITY)))
    }

    private fun evidence() = AtomSummaryEvidence(fullMap, allFiles)
    private fun leaf(atom: ChangeAtom, tree: ReviewSummaryTree = tree()) = tree.byIdentity("atom:${atom.id}")

    @Test fun `a leaf shows only its rows numbered by their original row with an unnumbered gap`() {
        val evidence = evidence()
        val node = leaf(origin)
        assertEquals(2..11, evidence.rows(node))
        assertEquals(setOf(3, 4, 9, 10), evidence.changedRows(node))
        assertEquals("implementation", evidence.evidenceKind(node))
        val item = JsonObject()
        evidence.describe(node, item, 6_000)
        assertEquals("src/Foo.kt", item["path"].asString)
        assertEquals("modified", item["status"].asString)
        assertEquals("Foo.make", item["symbol"].asString)
        assertEquals("function", item["symbol_kind"].asString)
        assertEquals("fun Foo.make()", item["header"].asString)
        assertEquals("+2 -2", item["lines"].asString)
        assertEquals(2, item["added_lines"].asInt)
        assertEquals(2, item["removed_lines"].asInt)
        assertEquals("@@ -1,14 +1,14 @@", item["hunk_header"].asString)
        val patch = item["patch"].asString.lines()
        assertEquals(listOf("2| context 2", "3|-old 3 src/Foo.kt", "4|+new 4 src/Foo.kt", "5| context 5", "…",
            "8| context 8", "9|-old 9 src/Foo.kt", "10|+new 10 src/Foo.kt", "11| context 11"), patch)
        assertFalse(item.has("patch_cut_at"))
        // Size is the row texts, with a newline each.
        val texts = (listOf(2, 3, 4, 5, 8, 9, 10, 11)).map { files[0].patch.lines()[it - 1] }
        assertEquals(texts.sumOf { it.length + 1 }, evidence.size(node))
    }

    @Test fun `a leaf whose evidence exceeds the limit is cut where the read tool can continue`() {
        val evidence = evidence()
        val node = leaf(origin)
        val item = JsonObject()
        evidence.describe(node, item, 60)
        val shown = item["patch"].asString.lines()
        assertTrue(shown.size < 8)
        assertEquals("2| context 2", shown.first())
        val lines = files[0].patch.lines()
        // The first omitted row is row 8: the atom's rows skip 6 and 7.
        assertEquals(lines.take(7).sumOf { it.length + 1 }, item["patch_cut_at"].asInt)
        assertEquals(files[0].patch.length, item["patch_length"].asInt)
        assertEquals(14, item["patch_lines"].asInt)
    }

    @Test fun `a large atom is split into pages of original rows and pages without changes are dropped`() {
        val evidence = evidence()
        val node = leaf(origin)
        assertNull(evidence.pages(node, 10_000))
        val pages = evidence.pages(node, 40)!!
        assertTrue(pages.size >= 2)
        assertTrue(pages.all { it.changedRows!!.isNotEmpty() })
        val cited = pages.flatMap { it.changedRows!! }
        assertEquals(setOf(3, 4, 9, 10), cited.toSet())
        for (page in pages) {
            val numbers = page.evidence["patch"].asString.lines().filter { it != "…" }.map { it.substringBefore('|').toInt() }
            assertEquals(page.rows.first, numbers.first())
            assertEquals(page.rows.last, numbers.last())
            assertEquals(numbers.first(), page.evidence["first_line"].asInt)
            assertTrue(page.evidence["patch"].asString.length <= 40 + 8 * 4)
            assertEquals("Foo.make", page.evidence["symbol"].asString)
        }
        // A context-only page is not citable evidence of a change.
        assertTrue(pages.none { page -> page.evidence["patch"].asString.lines().none { it.contains("|+") || it.contains("|-") } })
    }

    @Test fun `a whole file without an atom is described like a diff file`() {
        val evidence = evidence()
        val node = SummaryNode(3, SummaryKind.FILE, "src/Foo.kt", "Foo.kt", null, emptyList(), listOf("src/Foo.kt"), "k", "file:src/Foo.kt", files[0])
        assertEquals(1..14, evidence.rows(node))
        assertEquals(files[0].patch.length, evidence.size(node))
        val item = JsonObject()
        evidence.describe(node, item, 6_000)
        assertTrue(item["patch"].asString.startsWith("1|@@ -1,14 +1,14 @@\n2| context 2"))
        assertFalse(item.has("symbol"))
        assertEquals("implementation", evidence.evidenceKind(node))
    }

    @Test fun `relations read as sentences for uses dependents and other changes`() {
        val evidence = evidence()
        val tree = tree()
        fun relations(atom: ChangeAtom) = JsonObject().also { evidence.describe(leaf(atom, tree), it, 6_000) }["relations"]?.asJsonArray?.map { it.asString }
        assertEquals(listOf("is newly used by Bar.use (src/Bar.kt)", "has its use changed by BarTest.uses (src/test/BarTest.kt)",
            "is called by Zed.zap (src/Zed.kt) (in another change)"), relations(origin))
        assertEquals(listOf("uses the new Foo.make (src/Foo.kt)", "is tested by BarTest.uses (src/test/BarTest.kt)"), relations(user))
        assertEquals(listOf("tests Bar.use (src/Bar.kt)", "changes its use of Foo.make (src/Foo.kt)"), relations(test))
        assertEquals(listOf("calls Foo.make (src/Foo.kt) (in another change)"), relations(lone))
        assertNull(relations(readme))

        val many = (1..12).map { atom("d$it", "src/d$it.kt") }
        val hub = ChangeMap(ChangeGraph(listOf(origin) + many, many.map { AtomEdge(origin.id, it.id, AtomRelation.NEW_SYMBOL_USE) } +
            many.map { AtomEdge(origin.id, it.id, AtomRelation.TEST_OF) }), listOf(chain("hub", origin, *many.toTypedArray())))
        val sentences = hub.relationSentences(origin)
        assertEquals(2, sentences.size)
        assertEquals("is newly used by 12 changed atoms: d1 (src/d1.kt), d2 (src/d2.kt), d3 (src/d3.kt), …", sentences[0])
        val across = ChangeMap(hub.graph, many.map { chain(it.id, it) } + chain("o", origin))
        assertEquals(2, across.relationSentences(origin).size)
        val inbound = ChangeMap(ChangeGraph(listOf(origin) + many, many.map { AtomEdge(it.id, origin.id, AtomRelation.USE) }), listOf(chain("x", origin)))
        assertEquals(8, inbound.relationSentences(origin).size)
    }

    @Test fun `the kind of a branch is the single kind beneath it or mixed`() {
        val evidence = evidence()
        val tree = tree()
        assertEquals("mixed", evidence.evidenceKind(tree.byIdentity("cluster:k1")))
        assertEquals("documentation", evidence.evidenceKind(leaf(readme)))
        assertEquals("test", evidence.evidenceKind(leaf(test)))
        assertEquals("implementation", evidence.evidenceKind(tree.byIdentity(STANDALONE_CLUSTER_IDENTITY)))
    }

    @Test fun `a chain branch lists its steps in order with the relation to an earlier step`() {
        val evidence = evidence()
        val tree = tree()
        val item = JsonObject()
        evidence.describeBranch(tree.byIdentity("cluster:k1"), item)
        assertEquals("Make things", item["change_title"].asString)
        val steps = item["chain"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("Foo.make", "Bar.use", "BarTest.uses"), steps.map { it["symbol"].asString })
        assertEquals(listOf(1, 2, 3), steps.map { it["step"].asInt })
        assertEquals(listOf("origin", "propagation", "test"), steps.map { it["role"].asString })
        assertEquals(listOf("src/Foo.kt", "src/Bar.kt", "src/test/BarTest.kt"), steps.map { it["path"].asString })
        assertFalse(steps[0].has("relation"))
        assertEquals("uses the new Foo.make (src/Foo.kt) (step 1)", steps[1]["relation"].asString)
        // The strongest edge from an earlier step: the test of Bar.use, not its weaker use of Foo.make.
        assertEquals("tests Bar.use (src/Bar.kt) (step 2)", steps[2]["relation"].asString)

        val standalone = JsonObject()
        evidence.describeBranch(tree.byIdentity(STANDALONE_CLUSTER_IDENTITY), standalone)
        assertTrue(standalone["note"].asString.contains("unrelated standalone changes"))
        assertFalse(standalone.has("chain"))

        val root = JsonObject()
        evidence.describeBranch(tree.root, root)
        assertEquals(2, root["change_chains"].asInt)
        assertEquals(2, root["standalone_changes"].asInt)

        val many = (1..20).map { atom("m$it", "src/m$it.kt") }
        val big = ChangeMap(ChangeGraph(many, emptyList()), listOf(chain("big", *many.toTypedArray())))
        val bigTree = buildChangeMapTree(big, many.map { file(it.path) })
        val group = JsonObject()
        AtomSummaryEvidence(big, many.map { file(it.path) }).describeBranch(bigTree.nodes.first { it.kind == SummaryKind.GROUP }, group)
        assertEquals(0, group.size())
    }

    @Test fun `instructions reuse the citation and segment guidance and do not contradict the topic guidance`() {
        val evidence = evidence()
        val leafSystem = evidence.leafSystem(tree(), "the cache")
        val branchSystem = evidence.branchSystem("")
        assertTrue(leafSystem.startsWith("The user message holds data"))
        assertTrue(leafSystem.contains("row in the file's patch"))
        assertTrue(leafSystem.contains("Evidence lines start with their number"))
        assertTrue(leafSystem.contains("Return only JSON matching {\"summaries\""))
        assertTrue(leafSystem.endsWith("The user asked to focus on: the cache"))
        assertTrue(branchSystem.contains("WHAT the change is first"))
        assertTrue(branchSystem.contains("Children are evidence, never output nodes"))
        assertFalse(branchSystem.contains("{{"))
        assertFalse(leafSystem.contains("changed file or a patch part"))
    }

    /** Cites the first changed row of a leaf's patch, and "C1" for a branch; the summary text names the node. */
    private class Model {
        val requests = mutableListOf<CompletionRequest>()
        val items = mutableListOf<JsonObject>()
        fun complete(request: CompletionRequest): Completion {
            requests += request
            val nodes = JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content).asJsonObject["nodes"].asJsonArray.map { it.asJsonObject }
            items += nodes
            return Completion(reviewJson.toJson(mapOf("summaries" to nodes.map { node ->
                val label = node["symbol"]?.asString ?: node["topic_path"]?.asString ?: node["location"].asString
                val from = if (node.has("children")) "C1" else node["patch"].asString.lines().first { it.contains("|+") || it.contains("|-") }.substringBefore('|')
                mapOf("id" to node["id"].asInt, "segments" to listOf(mapOf("text" to "Summary of ${node["kind"].asString} $label.", "from" to listOf(from))))
            })))
        }
    }

    @Test fun `the summarizer writes a change map bottom up with chain and atom evidence`() = runBlocking {
        val tree = tree()
        val model = Model()
        var saved = ReviewSummaries(tree)
        val result = changeMapSummarizer(model::complete, null, fullMap, allFiles).run(saved, "", { entries -> saved.summarize(entries).also { saved = it } })
        assertTrue(result.error, result.summaries.complete)
        assertNull(result.error)
        assertEquals(tree.nodes.size, saved.count)
        val topic = model.items.first { it["id"].asInt >= 1 && it.has("change_title") && it["change_title"].asString == "Make things" }
        assertEquals("topic", topic["kind"].asString)
        assertEquals("Make things", topic["topic_path"].asString)
        assertEquals(3, topic["chain"].asJsonArray.size())
        assertEquals(3, topic["children"].asJsonArray.size())
        assertEquals("mixed", topic["evidence_kind"].asString)
        val leaf = model.items.first { it.has("symbol") && it["symbol"].asString == "Bar.use" }
        assertEquals("chunk", leaf["kind"].asString)
        assertEquals("src/Bar.kt:10-20", leaf["location"].asString)
        assertEquals("implementation", leaf["evidence_kind"].asString)
        assertTrue(leaf["relations"].asJsonArray.size() > 0)
        val rootItem = model.items.first { it["kind"].asString == "root" }
        assertEquals(2, rootItem["change_chains"].asInt)
        // Topic branches get the topic guidance after the change-map branch instructions.
        val topicSystem = model.requests.map { it.messages.first().content }.first { "describes an existing topic hierarchy" in it }
        assertTrue(topicSystem.indexOf("WHAT the change is first") < topicSystem.indexOf("describes an existing topic hierarchy"))
        // A leaf citation is a row of its atom, and the saved summary keeps it as a source row.
        val summary = saved.summary(tree.byIdentity("atom:${origin.id}").id)!!
        assertEquals(listOf(dev.insight.review.SummarySource.Rows(3, 3)), summary.segments.single().sources)
        assertEquals("Summary of chunk Foo.make.", saved.text(tree.byIdentity("atom:${origin.id}").id))
    }

    @Test fun `a citation of context between an atom's ranges or outside them is rejected`() {
        val evidence = evidence()
        val node = leaf(origin)
        val allowed = ReviewSummarizer.Citations.Lines(evidence.rows(node), evidence.changedRows(node))
        val answer = JsonParser.parseString("""{"summaries":[{"id":1,"segments":[{"text":"Replaces it.","from":["2"]}]}]}""").asJsonObject
        assertTrue(ReviewSummarizer.decode(answer, listOf(allowed)).problems.getValue(1).contains("cites only unchanged context"))
        val outside = JsonParser.parseString("""{"summaries":[{"id":1,"segments":[{"text":"Replaces it.","from":["12"]}]}]}""").asJsonObject
        assertTrue(ReviewSummarizer.decode(outside, listOf(allowed)).problems.getValue(1).contains("Invalid citations"))
    }

    @Test fun `an oversized atom is summarized from pages with citations of original rows`() = runBlocking {
        val model = Model()
        var saved = ReviewSummaries(tree())
        val result = changeMapSummarizer(model::complete, null, fullMap, allFiles, ReviewSummaryOptions(patchChars = 60))
            .run(saved, "", { entries -> saved.summarize(entries).also { saved = it } })
        assertTrue(result.error, result.summaries.complete)
        assertTrue(model.items.any { it.has("part") })
        val rows = saved.summary(tree().byIdentity("atom:${origin.id}").id)!!.segments.flatMap { it.sources }
        assertTrue(rows.isNotEmpty())
    }
}
