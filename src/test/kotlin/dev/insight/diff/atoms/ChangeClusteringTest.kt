package dev.insight.diff.atoms

import dev.insight.diff.ReviewFile
import dev.insight.diff.SideDeclaration
import dev.insight.diff.codeMoves
import dev.insight.graph.SymbolKind
import dev.insight.review.reviewFingerprint
import org.junit.Assert.*
import org.junit.Test

class ChangeClusteringTest {
    private fun atom(name: String, path: String = "$name.kt", status: AtomStatus = AtomStatus.MODIFIED, signature: Boolean = false, evidence: String = "implementation",
                     row: Int = 1, lines: Int = 1, kind: AtomKind = AtomKind.FUNCTION) = ChangeAtom(
        id = "$path#$name", path = path, name = name, kind = kind, status = status, header = "", signatureChanged = signature, rows = listOf(row until row + lines),
        changedRows = (row until row + lines).toSet(), beforeLines = null, afterLines = null, added = lines, removed = 0, evidenceKind = evidence, fingerprint = name,
    )

    private fun edge(from: ChangeAtom, to: ChangeAtom, relation: AtomRelation) = AtomEdge(from.id, to.id, relation)
    private fun names(ids: Iterable<String>) = ids.map { it.substringAfter('#') }
    private fun ChangeMap.names() = clusters.map { names(it.atoms) }
    private fun ChangeMap.byFirst(name: String) = clusters.single { cluster -> cluster.atoms.any { it.endsWith("#$name") } }
    private fun relation(user: ChangeAtom, declaration: ChangeAtom, kind: ReferenceKind = ReferenceKind.CALL, siteChanged: Boolean = false, before: Boolean = false): AtomRelation? =
        relate(listOf(declaration, user), listOf(AtomReference(user.id, declaration.id, kind, siteChanged, before)), codeMoves(emptyList()), emptyMap()).edges.singleOrNull()?.relation

    @Test fun `references become edges from the declaration to its user by the status of the declaration`() {
        val user = atom("user")
        val modified = atom("d")
        val added = atom("d", status = AtomStatus.ADDED)
        val removed = atom("d", status = AtomStatus.REMOVED)
        val reshaped = atom("d", signature = true)
        assertEquals(AtomRelation.NEW_SYMBOL_USE, relation(user, added, siteChanged = true))
        assertEquals(AtomRelation.CALL, relation(user, added))
        assertEquals(AtomRelation.REMOVED_SYMBOL_USE, relation(user, removed, siteChanged = true, before = true))
        assertEquals(AtomRelation.CALL, relation(user, removed, before = true))
        assertEquals(AtomRelation.SIGNATURE_USE, relation(user, reshaped, siteChanged = true))
        assertEquals(AtomRelation.CALL, relation(user, reshaped))
        assertEquals(AtomRelation.USE, relation(user, modified, siteChanged = true))
        assertEquals(AtomRelation.CALL, relation(user, modified))
        val edges = relate(listOf(modified, user), listOf(AtomReference(user.id, modified.id, ReferenceKind.CALL, true)), codeMoves(emptyList()), emptyMap()).edges
        assertEquals(listOf(AtomEdge(modified.id, user.id, AtomRelation.USE)), edges)
    }

    @Test fun `tests of changed code are strong and tests of unchanged behavior only use it`() {
        val test = atom("t", path = "src/test/FooTest.kt", evidence = "test")
        assertEquals(AtomRelation.USE, relation(test, atom("d")))
        assertEquals(AtomRelation.TEST_OF, relation(test, atom("d"), siteChanged = true))
        assertEquals(AtomRelation.TEST_OF, relation(test, atom("d", status = AtomStatus.ADDED)))
        assertEquals(AtomRelation.TEST_OF, relation(test, atom("d", signature = true)))
        val other = atom("o", path = "src/test/OtherTest.kt", evidence = "test")
        assertEquals(AtomRelation.CALL, relation(test, other))
    }

    @Test fun `overrides are strong when the base or the override is new or reshaped`() {
        val user = atom("impl")
        assertEquals(AtomRelation.OVERRIDE, relation(user, atom("base", status = AtomStatus.ADDED), ReferenceKind.OVERRIDE))
        assertEquals(AtomRelation.OVERRIDE, relation(user, atom("base", status = AtomStatus.REMOVED), ReferenceKind.OVERRIDE))
        assertEquals(AtomRelation.OVERRIDE, relation(user, atom("base", signature = true), ReferenceKind.OVERRIDE))
        assertEquals(AtomRelation.OVERRIDE, relation(atom("impl", status = AtomStatus.ADDED), atom("base"), ReferenceKind.OVERRIDE))
        assertEquals(AtomRelation.CALL, relation(user, atom("base"), ReferenceKind.OVERRIDE))
    }

    @Test fun `self and unknown references make no edge`() {
        val a = atom("a")
        val unknown = AtomReference("nowhere#x", a.id, ReferenceKind.CALL, true)
        assertTrue(relate(listOf(a), listOf(AtomReference(a.id, a.id, ReferenceKind.CALL, true), unknown, unknown.copy(from = a.id, to = "nowhere#y")), codeMoves(emptyList()), emptyMap()).edges.isEmpty())
    }

    @Test fun `one strongest edge is kept per pair and opposite edges stay only when both are strong`() {
        val a = atom("a", status = AtomStatus.ADDED)
        val b = atom("b", status = AtomStatus.ADDED)
        fun ref(user: ChangeAtom, declaration: ChangeAtom, changed: Boolean) = AtomReference(user.id, declaration.id, ReferenceKind.CALL, changed)
        // b uses a twice: once changed, once not; the changed use is the strong one.
        val twice = relate(listOf(a, b), listOf(ref(b, a, false), ref(b, a, true)), codeMoves(emptyList()), emptyMap())
        assertEquals(listOf(edge(a, b, AtomRelation.NEW_SYMBOL_USE)), twice.edges)
        // Both strong: both directions stay.
        val both = relate(listOf(a, b), listOf(ref(b, a, true), ref(a, b, true)), codeMoves(emptyList()), emptyMap())
        assertEquals(listOf(edge(a, b, AtomRelation.NEW_SYMBOL_USE), edge(b, a, AtomRelation.NEW_SYMBOL_USE)), both.edges)
        // One strong, one weak: only the strong one stays.
        val mixed = relate(listOf(a, b), listOf(ref(b, a, true), ref(a, b, false)), codeMoves(emptyList()), emptyMap())
        assertEquals(listOf(edge(a, b, AtomRelation.NEW_SYMBOL_USE)), mixed.edges)
        // Two weak: the earlier atom's edge stays.
        val x = atom("x")
        val y = atom("y")
        val weak = relate(listOf(x, y), listOf(ref(y, x, false), ref(x, y, false)), codeMoves(emptyList()), emptyMap())
        assertEquals(listOf(edge(x, y, AtomRelation.CALL)), weak.edges)
    }

    @Test fun `a moved block links the atom that lost the code to the atom that received it`() {
        val validate = """
            fun validate(order: Order): List<String> {
                val errors = mutableListOf<String>()
                if (order.items.isEmpty()) errors += "no items"
                if (order.total < 0) errors += "negative total"
                return errors
            }
        """.trimIndent() + "\n"
        val keep = "fun keep() = 1\n"
        val old = atomTestFile("Old.kt", validate + keep, keep)
        val new = atomTestFile("New.kt", "", validate, "added")
        fun declarations(source: String) = listOf("validate", "keep").filter { "fun $it" in source }.map { SideDeclaration(it, SymbolKind.FUNCTION, "fun $it", "", atomTestDeclaration(source, it, SymbolKind.FUNCTION, "fun $it").lines) }
        val atoms = sliceAtoms(old, declarations(validate + keep), declarations(keep)) + sliceAtoms(new, emptyList(), declarations(validate))
        val graph = relate(atoms, emptyList(), codeMoves(listOf(old, new)), mapOf(old.path to old.patch, new.path to new.patch))
        assertEquals(listOf(AtomEdge("Old.kt#validate", "New.kt#validate", AtomRelation.MOVE)), graph.edges)
    }

    private fun editFile(name: String, call: String, replacement: String): Pair<ChangeAtom, ReviewFile> {
        val before = "fun $name() {\n    $call\n}\n"
        val after = "fun $name() {\n    $replacement\n}\n"
        val file = atomTestFile("$name.kt", before, after)
        fun declaration() = listOf(SideDeclaration(name, SymbolKind.FUNCTION, "fun $name()", "", 1..3))
        return sliceAtoms(file, declaration(), declaration()).single() to file
    }

    private fun relateEdits(edits: List<Pair<ChangeAtom, ReviewFile>>, options: ClusterOptions = ClusterOptions()) =
        relate(edits.map { it.first }, emptyList(), codeMoves(emptyList()), edits.associate { it.second.path to it.second.patch }, options)

    @Test fun `the same small edit in many atoms is a star from the first one`() {
        val edits = (1..4).map { editFile("f$it", "oldName(x)", "newName(x)") }
        val graph = relateEdits(edits)
        val ids = edits.map { it.first.id }
        assertEquals(ids.drop(1).map { AtomEdge(ids[0], it, AtomRelation.REPEATED_EDIT) }, graph.edges)
    }

    @Test fun `two atoms with the same edit are a same edit and other edits stay apart`() {
        val edits = listOf(editFile("f1", "oldName(x)", "newName(x)"), editFile("f2", "oldName(y)", "newName(y)"), editFile("f3", "other(x)", "another(x)"))
        val ids = edits.map { it.first.id }
        assertEquals(listOf(AtomEdge(ids[0], ids[1], AtomRelation.SAME_EDIT)), relateEdits(edits).edges)
        assertEquals(listOf(AtomEdge(ids[0], ids[1], AtomRelation.REPEATED_EDIT)), relateEdits(edits, ClusterOptions(repeatedEdit = 2)).edges)
    }

    @Test fun `large edits are not shared edits`() {
        val big = "a1 + b2 + c3 + d4 + e5 + f6 + g7"
        val edits = listOf(editFile("f1", big, "x"), editFile("f2", big, "x"))
        assertTrue(relateEdits(edits).edges.isEmpty())
    }

    @Test fun `added declarations and documentation files are not shared edits`() {
        val added = (1..2).map { atom("n$it", status = AtomStatus.ADDED) }
        assertTrue(relate(added, emptyList(), codeMoves(emptyList()), emptyMap()).edges.isEmpty())
        val docs = listOf("a", "b").map { name ->
            val file = atomTestFile("docs/$name.md", "use oldName\n", "use newName\n")
            sliceAtoms(file, null, null).single() to file
        }
        assertTrue(relate(docs.map { it.first }, emptyList(), codeMoves(emptyList()), docs.associate { it.second.path to it.second.patch }).edges.isEmpty())
    }

    @Test fun `members of one owner in a file are chained in source order`() {
        val a = atom("A.one", path = "A.kt", row = 2)
        val b = atom("A.two", path = "A.kt", row = 6)
        val c = atom("A.three", path = "A.kt", row = 9)
        val inner = atom("A.Inner.x", path = "A.kt", row = 12)
        val otherFile = atom("A.four", path = "B.kt", row = 1)
        val top = atom("top", path = "A.kt", row = 15)
        val graph = relate(listOf(a, b, c, inner, otherFile, top), emptyList(), codeMoves(emptyList()), emptyMap())
        assertEquals(listOf(edge(a, b, AtomRelation.SAME_OWNER), edge(b, c, AtomRelation.SAME_OWNER)), graph.edges)
    }

    @Test fun `edges come out in atom order`() {
        val atoms = listOf("a", "b", "c").map { atom(it, status = AtomStatus.ADDED) }
        val refs = listOf(atoms[2] to atoms[0], atoms[1] to atoms[0], atoms[2] to atoms[1]).map { (user, d) -> AtomReference(user.id, d.id, ReferenceKind.CALL, true) }
        val edges = relate(atoms, refs, codeMoves(emptyList()), emptyMap()).edges
        assertEquals(listOf("a" to "b", "a" to "c", "b" to "c"), edges.map { names(listOf(it.from)).single() to names(listOf(it.to)).single() })
    }

    @Test fun `strong edges make chains and weaker ones leave changes standalone`() {
        val (a, b, c, d, e) = listOf("A", "B", "C", "D", "E").map { atom(it, lines = if (it in "AB") 5 else 1) }
        val f = atom("F")
        val graph = ChangeGraph(listOf(a, b, c, d, e, f), listOf(edge(a, b, AtomRelation.SIGNATURE_USE), edge(b, c, AtomRelation.USE), edge(e, f, AtomRelation.NEW_SYMBOL_USE), edge(d, e, AtomRelation.CALL)))
        val map = clusterChanges(graph)
        assertEquals(listOf(listOf("A", "B"), listOf("E", "F"), listOf("C"), listOf("D")), map.names())
        assertEquals(reviewFingerprint(a.id, b.id), map.clusters[0].id)
        assertFalse(map.clusters.any { it.foundation })
        assertEquals(listOf(AtomRole.STANDALONE), map.clusters[2].steps.map { it.role })
    }

    @Test fun `a hub over separate components stays a foundation and does not glue them`() {
        val hub = atom("H", status = AtomStatus.ADDED)
        val d = (1..5).map { atom("D$it") }
        val edges = d.map { edge(hub, it, AtomRelation.NEW_SYMBOL_USE) } + edge(d[0], d[1], AtomRelation.SIGNATURE_USE) + edge(d[2], d[3], AtomRelation.SIGNATURE_USE)
        val map = clusterChanges(ChangeGraph(listOf(hub) + d, edges), ClusterOptions(hubDegree = 4))
        assertEquals(setOf(listOf("D1", "D2"), listOf("D3", "D4"), listOf("H", "D5")), map.names().toSet())
        assertTrue(map.byFirst("H").foundation)
        assertFalse(map.byFirst("D1").foundation)
        assertEquals(listOf(AtomRole.ORIGIN, AtomRole.PROPAGATION), map.byFirst("H").steps.map { it.role })
    }

    @Test fun `a hub whose dependents are one component joins it with the singletons`() {
        val hub = atom("H", status = AtomStatus.ADDED)
        val d = (1..5).map { atom("D$it") }
        val edges = d.map { edge(hub, it, AtomRelation.NEW_SYMBOL_USE) } + edge(d[0], d[1], AtomRelation.SIGNATURE_USE) + edge(d[1], d[2], AtomRelation.SIGNATURE_USE)
        val map = clusterChanges(ChangeGraph(listOf(hub) + d, edges), ClusterOptions(hubDegree = 4))
        assertEquals(1, map.clusters.size)
        assertEquals(6, map.clusters.single().steps.size)
        assertFalse(map.clusters.single().foundation)
    }

    @Test fun `a hub of singletons is one cluster and a below-threshold atom glues as usual`() {
        val hub = atom("H", status = AtomStatus.ADDED)
        val d = (1..3).map { atom("D$it") }
        val map = clusterChanges(ChangeGraph(listOf(hub) + d, d.map { edge(hub, it, AtomRelation.NEW_SYMBOL_USE) }), ClusterOptions(hubDegree = 3))
        assertEquals(listOf(listOf("H", "D1", "D2", "D3")), map.names())
        assertFalse(map.clusters.single().foundation)
        val glued = clusterChanges(ChangeGraph(listOf(hub) + d, listOf(edge(hub, d[0], AtomRelation.NEW_SYMBOL_USE), edge(hub, d[1], AtomRelation.NEW_SYMBOL_USE), edge(d[2], d[0], AtomRelation.USE))), ClusterOptions(hubDegree = 3))
        assertEquals(listOf(listOf("H", "D1", "D2"), listOf("D3")), glued.names())
    }

    @Test fun `a chain runs from its origin through dependents to tests and documentation`() {
        val origin = atom("O", status = AtomStatus.ADDED, path = "src/O.kt")
        val first = atom("P1", path = "src/P1.kt")
        val second = atom("P2", path = "src/P2.kt")
        val test = atom("T", path = "src/test/OTest.kt", evidence = "test")
        val doc = atom("Doc", path = "README.md", evidence = "documentation", kind = AtomKind.FILE)
        val graph = ChangeGraph(listOf(doc, test, second, first, origin), listOf(
            edge(origin, doc, AtomRelation.NEW_SYMBOL_USE), edge(origin, test, AtomRelation.TEST_OF), edge(origin, first, AtomRelation.NEW_SYMBOL_USE),
            edge(first, second, AtomRelation.SIGNATURE_USE),
        ))
        val cluster = clusterChanges(graph).clusters.single()
        assertEquals(listOf("O", "P1", "T", "Doc", "P2"), names(cluster.atoms))
        assertEquals(listOf(0, 1, 1, 1, 2), cluster.steps.map { it.depth })
        assertEquals(listOf(AtomRole.ORIGIN, AtomRole.PROPAGATION, AtomRole.TEST, AtomRole.DOCUMENTATION, AtomRole.PROPAGATION), cluster.steps.map { it.role })
    }

    @Test fun `medium edges shorten a chain but weak edges do not`() {
        val (a, b, c) = listOf("A", "B", "C").map { atom(it) }
        val graph = ChangeGraph(listOf(a, b, c), listOf(
            edge(a, b, AtomRelation.SIGNATURE_USE), edge(b, c, AtomRelation.SIGNATURE_USE), edge(a, c, AtomRelation.USE), edge(c, a, AtomRelation.CALL),
        ))
        val cluster = clusterChanges(graph).clusters.single()
        assertEquals(listOf("A", "B", "C"), names(cluster.atoms))
        assertEquals(listOf(0, 1, 1), cluster.steps.map { it.depth })
    }

    @Test fun `a cycle takes an added or reshaped atom as its origin and else the first`() {
        val a = atom("A")
        val b = atom("B", status = AtomStatus.ADDED)
        val cycle = listOf(edge(a, b, AtomRelation.SIGNATURE_USE), edge(b, a, AtomRelation.SIGNATURE_USE))
        val added = clusterChanges(ChangeGraph(listOf(a, b), cycle)).clusters.single()
        assertEquals(listOf("B", "A"), names(added.atoms))
        assertEquals(AtomRole.ORIGIN, added.steps.first().role)
        val plain = clusterChanges(ChangeGraph(listOf(a, atom("B")), cycle)).clusters.single()
        assertEquals(listOf("A", "B"), names(plain.atoms))
        val reshaped = clusterChanges(ChangeGraph(listOf(a, atom("B", signature = true)), cycle)).clusters.single()
        assertEquals(listOf("B", "A"), names(reshaped.atoms))
    }

    @Test fun `chains come first by size and standalone changes by file and position`() {
        val small = listOf(atom("S1", lines = 1), atom("S2", lines = 1))
        val big = listOf(atom("B1", lines = 4), atom("B2", lines = 4))
        val z = atom("Z", path = "z.kt", row = 1)
        val y2 = atom("Y2", path = "y.kt", row = 9)
        val y1 = atom("Y1", path = "y.kt", row = 2)
        val graph = ChangeGraph(listOf(z, y2) + small + big + y1, listOf(edge(small[0], small[1], AtomRelation.SIGNATURE_USE), edge(big[0], big[1], AtomRelation.SIGNATURE_USE)))
        assertEquals(listOf(listOf("B1", "B2"), listOf("S1", "S2"), listOf("Y1"), listOf("Y2"), listOf("Z")), clusterChanges(graph).names())
    }

    private fun twoChains(): Triple<ChangeMap, List<ChangeAtom>, ChangeAtom> {
        val x = listOf(atom("X1", lines = 5), atom("X2", lines = 5))
        val y = listOf(atom("Y1", lines = 3), atom("Y2", lines = 3))
        val s = atom("S", path = "s.kt")
        val t = atom("T", path = "t.kt")
        val graph = ChangeGraph(x + y + s + t, listOf(
            edge(x[0], x[1], AtomRelation.SIGNATURE_USE), edge(y[0], y[1], AtomRelation.SIGNATURE_USE),
            edge(x[0], y[0], AtomRelation.USE), edge(x[1], y[0], AtomRelation.USE), edge(x[0], s, AtomRelation.USE), edge(y[1], s, AtomRelation.USE), edge(y[0], s, AtomRelation.USE),
            edge(x[1], t, AtomRelation.CALL), edge(t, y[1], AtomRelation.SAME_OWNER), edge(s, t, AtomRelation.USE),
        ))
        return Triple(clusterChanges(graph), x + y, s)
    }

    @Test fun `merge candidates are cluster pairs linked by medium edges, most linked first`() {
        val (map, _, _) = twoChains()
        val x = map.byFirst("X1").id
        val y = map.byFirst("Y1").id
        val s = map.byFirst("S").id
        val t = map.byFirst("T").id
        val candidates = mergeCandidates(map)
        assertEquals(listOf(2, 2, 1, 1), candidates.map { it.edges.size })
        assertEquals(setOf(setOf(x, y), setOf(y, s)), candidates.take(2).map { setOf(it.a, it.b) }.toSet())
        assertEquals(setOf(setOf(x, s), setOf(s, t)), candidates.drop(2).map { setOf(it.a, it.b) }.toSet())
        assertEquals(candidates.take(2).sortedWith(compareBy({ it.a }, { it.b })), candidates.take(2))
        assertTrue(candidates.all { it.edges.all { edge -> edge.relation.strength == EdgeStrength.MEDIUM } })
        assertTrue(candidates.all { it.a < it.b })
        assertEquals(4, candidates.size)
        assertEquals(candidates, mergeCandidates(map))
    }

    @Test fun `attach candidates are the chains a standalone change is linked to by any edge, most linked first`() {
        val (map, _, _) = twoChains()
        assertEquals(listOf(map.byFirst("Y1"), map.byFirst("X1")), attachCandidates(map, map.byFirst("S")))
        // T links to X and to Y by weak edges, and to S by a medium one; S is not a chain, so it is no candidate.
        assertEquals(listOf(map.byFirst("X1"), map.byFirst("Y1")), attachCandidates(map, map.byFirst("T")))
    }

    @Test fun `merging clusters recomputes the chain and drops titles of merged clusters only`() {
        val (map, _, _) = twoChains()
        val titled = map.withTitles(map.clusters.associate { it.id to "title ${names(it.atoms).first()}" })
        val x = titled.byFirst("X1")
        val y = titled.byFirst("Y1")
        val merged = titled.merged(listOf(setOf(x.id, y.id), setOf("unknown", titled.byFirst("S").id), setOf(titled.byFirst("T").id)))
        assertEquals(3, merged.clusters.size)
        val combined = merged.clusters.first()
        assertEquals(listOf("X1", "X2", "Y1", "Y2").sorted(), names(combined.atoms).sorted())
        assertEquals(reviewFingerprint(*combined.atoms.sorted().toTypedArray()), combined.id)
        assertNull(combined.title)
        assertEquals("X1", names(combined.atoms).first())
        assertEquals(AtomRole.ORIGIN, combined.steps.first().role)
        assertEquals(listOf("title S", "title T"), merged.clusters.drop(1).map { it.title })
        assertSame(titled, titled.merged(emptyList()))
    }

    @Test fun `overlapping merge groups union and foundation survives only when all merged are foundations`() {
        val atoms = ('A'..'F').map { atom(it.toString()) }
        fun cluster(vararg members: ChangeAtom, foundation: Boolean) =
            ChangeCluster(reviewFingerprint(*members.map { it.id }.sorted().toTypedArray()), members.mapIndexed { i, m -> ClusterStep(m.id, AtomRole.PROPAGATION, i) }, "t", foundation)
        val first = cluster(atoms[0], atoms[1], foundation = true)
        val second = cluster(atoms[2], atoms[3], foundation = true)
        val third = cluster(atoms[4], atoms[5], foundation = false)
        val map = ChangeMap(ChangeGraph(atoms, emptyList()), listOf(first, second, third))
        val both = map.merged(listOf(setOf(first.id, second.id)))
        assertTrue(both.clusters.first().foundation)
        val all = map.merged(listOf(setOf(first.id, second.id), setOf(second.id, third.id)))
        assertEquals(1, all.clusters.size)
        assertEquals(6, all.clusters.single().atoms.size)
        assertFalse(all.clusters.single().foundation)
        assertEquals(second, map.merged(listOf(setOf(first.id))).clusters[1])
    }
}
