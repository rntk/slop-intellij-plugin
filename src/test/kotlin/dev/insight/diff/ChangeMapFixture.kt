package dev.insight.diff

import dev.insight.diff.atoms.AtomEdge
import dev.insight.diff.atoms.AtomKind
import dev.insight.diff.atoms.AtomRelation
import dev.insight.diff.atoms.AtomRole
import dev.insight.diff.atoms.AtomStatus
import dev.insight.diff.atoms.ChangeAtom
import dev.insight.diff.atoms.ChangeCluster
import dev.insight.diff.atoms.ChangeGraph
import dev.insight.diff.atoms.ChangeMap
import dev.insight.diff.atoms.ClusterStep

/**
 * A hand-made change map over seven files: chain k1 (Foo.make, then Bar.use, then its test), the foundation chain k2,
 * and two standalone changes, one of which calls Foo.make. Every patch has rows 3 and 9 removed and 4 and 10 added.
 */
internal object ChangeMapFixture {
    fun patch(path: String, rows: Int = 14, changed: Set<Int> = setOf(3, 4, 9, 10)) = (1..rows).joinToString("\n") { row ->
        when {
            row == 1 -> "@@ -1,$rows +1,$rows @@"
            row in changed -> if (row % 2 == 1) "-old $row $path" else "+new $row $path"
            else -> " context $row"
        }
    } + "\n"

    fun atom(name: String, path: String = "src/$name.kt", kind: AtomKind = AtomKind.FUNCTION, status: AtomStatus = AtomStatus.MODIFIED,
             rows: List<IntRange> = listOf(2..5, 8..11), changed: Set<Int> = setOf(3, 4, 9, 10), evidenceKind: String = "implementation",
             signatureChanged: Boolean = false, fingerprint: String = "fp-$name") =
        ChangeAtom(if (kind == AtomKind.FILE) path else "$path#$name", path, name, kind, status, if (kind == AtomKind.FILE) "" else "fun $name()",
            signatureChanged, rows, changed, if (kind == AtomKind.FILE) null else 10..19, if (kind == AtomKind.FILE) null else 10..20,
            changed.count { it % 2 == 0 }, changed.count { it % 2 == 1 }, evidenceKind, fingerprint)

    val origin = atom("Foo.make", "src/Foo.kt", signatureChanged = true)
    val user = atom("Bar.use", "src/Bar.kt")
    val test = atom("BarTest.uses", "src/test/BarTest.kt", evidenceKind = "test")
    val helper = atom("Util.helper", "src/Util.kt")
    val other = atom("Other.other", "src/Other.kt")
    val lone = atom("Zed.zap", "src/Zed.kt")
    val readme = atom("README.md", "README.md", AtomKind.FILE, rows = listOf(1..4), changed = setOf(2, 3), evidenceKind = "documentation")

    val graph = ChangeGraph(listOf(origin, user, test, helper, other, lone, readme), listOf(
        AtomEdge(origin.id, user.id, AtomRelation.NEW_SYMBOL_USE), AtomEdge(user.id, test.id, AtomRelation.TEST_OF),
        AtomEdge(origin.id, test.id, AtomRelation.USE), AtomEdge(origin.id, lone.id, AtomRelation.CALL),
        AtomEdge(helper.id, other.id, AtomRelation.SIGNATURE_USE)))

    val map = ChangeMap(graph, listOf(
        ChangeCluster("k1", listOf(ClusterStep(origin.id, AtomRole.ORIGIN, 0), ClusterStep(user.id, AtomRole.PROPAGATION, 1),
            ClusterStep(test.id, AtomRole.TEST, 2)), title = "Make things"),
        ChangeCluster("k2", listOf(ClusterStep(helper.id, AtomRole.ORIGIN, 0), ClusterStep(other.id, AtomRole.PROPAGATION, 1)), foundation = true),
        ChangeCluster("s1", listOf(ClusterStep(lone.id, AtomRole.STANDALONE, 0))),
        ChangeCluster("s2", listOf(ClusterStep(readme.id, AtomRole.STANDALONE, 0))),
    ))

    fun files(): List<ReviewFile> = graph.atoms.map { atom ->
        val patch = if (atom.kind == AtomKind.FILE) patch(atom.path, 5, setOf(2, 3)) else patch(atom.path)
        ReviewFile(atom.path, "/repo/${atom.path}", null, "modified", patch, "v-${atom.path}")
    }
}
