package dev.insight.diff.atoms

import dev.insight.diff.ReviewFile
import dev.insight.graph.SymbolKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RemovedNameReferencesTest {
    private val fn = SymbolKind.FUNCTION

    private class Snapshot(val files: List<ReviewFile>, val atoms: List<ChangeAtom>) {
        val patches = files.associate { it.path to it.patch }
        fun id(name: String) = atoms.single { it.name == name }.id
    }

    /** Files sliced with their functions, declared as (qualified name, line start) on each side. */
    private fun snapshot(vararg files: Triple<String, Pair<String, String>, List<Pair<String, String>>>): Snapshot {
        val reviews = files.map { (path, texts, _) -> atomTestFile(path, texts.first, texts.second) }
        val atoms = files.zip(reviews).flatMap { (spec, review) ->
            val (before, after) = spec.second
            fun side(source: String) = spec.third.filter { (_, start) -> atomTestLines(source).any { it.trim().startsWith(start) } }
                .map { (name, start) -> atomTestDeclaration(source, name, fn, start).let { it.copy(header = it.text.lines().first().trim()) } }
            sliceAtoms(review, side(before), side(after))
        }
        return Snapshot(reviews, atoms)
    }

    private val lib = Triple("Lib.kt", "fun helperGone() {\n}\nfun go() {\n}\nfun keep() {\n}\n" to "fun keep() {\n}\n",
        listOf("helperGone" to "fun helperGone", "go" to "fun go", "keep" to "fun keep"))

    @Test
    fun `a removed call of a removed function links the caller to it`() {
        val app = Triple("App.kt", "fun main() {\n    helperGone()\n    go()\n    run()\n}\n" to "fun main() {\n    run()\n}\n", listOf("main" to "fun main"))
        val s = snapshot(lib, app)
        // `go` is too short to tell from other words; the removed function's own rows do not refer to it.
        assertEquals(listOf(AtomReference(s.id("main"), s.id("helperGone"), ReferenceKind.REFERENCE, siteChanged = true, before = true)),
            removedNameReferences(s.atoms, s.patches))
    }

    @Test
    fun `only whole identifiers match and names declared by several atoms are skipped`() {
        val app = Triple("App.kt", "fun main() {\n    helperGoneToo()\n    x.helperGone_2\n}\n" to "fun main() {\n}\n", listOf("main" to "fun main"))
        assertEquals(emptyList<AtomReference>(), snapshot(lib, app).let { removedNameReferences(it.atoms, it.patches) })

        val twice = Triple("Other.kt", "fun main() {\n    helperGone()\n}\nfun helperGone() {\n}\n" to "fun main() {\n}\n",
            listOf("main" to "fun main", "Other.helperGone" to "fun helperGone"))
        assertEquals(emptyList<AtomReference>(), snapshot(lib, twice).let { removedNameReferences(it.atoms, it.patches) })
    }

    @Test
    fun `a changed signature is linked only where resolution found nothing`() {
        val api = Triple("Api.kt", "fun format(x: Int) {\n}\nfun plain() {\n    1\n}\n" to "fun format(x: Int, y: Int) {\n}\nfun plain() {\n    2\n}\n",
            listOf("format" to "fun format", "plain" to "fun plain"))
        val app = Triple("App.kt", "fun main() {\n    format(1); plain()\n}\nfun side() {\n    format(2)\n}\n" to
            "fun main() {\n    format(1, 2); plain()\n}\nfun side() {\n    format(2, 3)\n}\n", listOf("main" to "fun main", "side" to "fun side"))
        val s = snapshot(api, app)
        val resolved = AtomReference(s.id("main"), s.id("format"), ReferenceKind.CALL, siteChanged = true)
        // `plain` changed only its body, so its name in removed rows is no evidence.
        assertEquals(listOf(AtomReference(s.id("side"), s.id("format"), ReferenceKind.REFERENCE, siteChanged = true, before = true)),
            removedNameReferences(s.atoms, s.patches, listOf(resolved)))
    }
}
