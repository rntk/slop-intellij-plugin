package dev.insight.diff.atoms

import dev.insight.callgraph.LineChange
import dev.insight.diff.ReviewFile
import dev.insight.diff.SideDeclaration
import dev.insight.diff.patchRows
import dev.insight.diff.reviewPatch
import dev.insight.graph.SymbolKind
import org.junit.Assert.*
import org.junit.Test

/** A file of the given texts with the patch of a line diff, as the review would show it. */
internal fun atomTestFile(path: String, before: String, after: String, status: String = "modified"): ReviewFile {
    val patch = reviewPatch(before, after, atomTestChanges(atomTestLines(before), atomTestLines(after)))
    return ReviewFile(path, "/$path", null, status, patch, path, before, after)
}

internal fun atomTestLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }

/** Changed regions between two line lists from a longest common subsequence, in the zero-based half-open form of the IDE comparator. */
private fun atomTestChanges(old: List<String>, new: List<String>): List<LineChange> {
    val common = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) for (j in new.indices.reversed()) {
        common[i][j] = if (old[i] == new[j]) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
    }
    val changes = mutableListOf<LineChange>()
    var i = 0
    var j = 0
    fun same() = i < old.size && j < new.size && old[i] == new[j]
    while (i < old.size || j < new.size) {
        if (same()) { i++; j++; continue }
        val startOld = i
        val startNew = j
        while ((i < old.size || j < new.size) && !same()) if (j >= new.size || (i < old.size && common[i + 1][j] >= common[i][j + 1])) i++ else j++
        changes += LineChange(startOld, i, startNew, j)
    }
    return changes
}

/** The declaration starting at the first line that begins with [start], to the end of its braces (or that one line). */
internal fun atomTestDeclaration(source: String, name: String, kind: SymbolKind, start: String): SideDeclaration {
    val lines = atomTestLines(source)
    val first = lines.indexOfFirst { it.trim().startsWith(start) }
    require(first >= 0) { "No line starts with $start" }
    var depth = 0
    var opened = false
    var last = first
    for (index in first until lines.size) {
        lines[index].forEach { if (it == '{') { depth++; opened = true } else if (it == '}') depth-- }
        last = index
        if (!opened || depth <= 0) break
    }
    return SideDeclaration(name, kind, start, lines.subList(first, last + 1).joinToString("\n"), (first + 1)..(last + 1))
}

class AtomSlicingTest {
    private val fn = SymbolKind.FUNCTION
    private fun declare(source: String, name: String, start: String, kind: SymbolKind = fn) = atomTestDeclaration(source, name, kind, start)

    /** The patch text of the rows of [atom]. */
    private fun texts(file: ReviewFile, atom: ChangeAtom): List<String> = file.patch.removeSuffix("\n").split('\n').let { lines -> atom.rows.flatMap { it }.map { lines[it - 1] } }

    /** Every changed row of the patch is owned by exactly one atom, and each atom's rows are sorted, disjoint and stay out of hunk headers. */
    private fun assertCovers(file: ReviewFile, atoms: List<ChangeAtom>) {
        val lines = file.patch.removeSuffix("\n").split('\n')
        val changed = lines.indices.filter { lines[it].startsWith('+') || lines[it].startsWith('-') }.map { it + 1 }
        assertEquals(changed.sorted(), atoms.flatMap { it.changedRows }.sorted())
        for (atom in atoms) {
            val rows = atom.rows.flatMap { it }
            assertEquals(rows.sorted(), rows)
            assertEquals(rows.distinct(), rows)
            atom.rows.zipWithNext().forEach { (a, b) -> assertTrue("ranges must not touch: ${atom.rows}", a.last + 1 < b.first) }
            assertTrue(rows.none { lines[it - 1].startsWith("@@") })
            assertTrue(atom.changedRows.all { it in rows })
            assertEquals(atom.changedRows.count { lines[it - 1].startsWith('+') }, atom.added)
            assertEquals(atom.changedRows.count { lines[it - 1].startsWith('-') }, atom.removed)
        }
        assertEquals(atoms.map { it.id }.distinct().size, atoms.size)
        assertEquals(atoms.sortedBy { it.rows.first().first }.map { it.id }, atoms.map { it.id })
    }

    private val service = """
        class A {
            fun a() {
                one()
            }
            fun b() {
                two()
            }
        }
    """.trimIndent() + "\n"

    @Test fun `patch line numbers follow hunks and skip headers and markers`() {
        val rows = patchLineNumbers("@@ -3,2 +3,3 @@\n ctx\n-gone\n+new1\n+new2\n[After: no newline at end of file]\n")
        assertEquals(listOf(
            PatchRow(2, ' ', 3, 3, 1, " ctx"), PatchRow(3, '-', 4, null, 1, "-gone"), PatchRow(4, '+', null, 4, 1, "+new1"), PatchRow(5, '+', null, 5, 1, "+new2"),
        ), rows)
        assertTrue(patchLineNumbers("").isEmpty())
    }

    @Test fun `a modified method inside a class is the method's atom and the class is not one`() {
        val after = service.replace("one()", "uno()")
        val file = atomTestFile("src/A.kt", service, after)
        val declarations = { source: String -> listOf(declare(source, "A", "class A", SymbolKind.CLASS), declare(source, "A.a", "fun a()"), declare(source, "A.b", "fun b()")) }
        val atoms = sliceAtoms(file, declarations(service), declarations(after))
        val atom = atoms.single()
        assertEquals("src/A.kt#A.a", atom.id)
        assertEquals("A.a", atom.name)
        assertEquals(AtomKind.FUNCTION, atom.kind)
        assertEquals(AtomStatus.MODIFIED, atom.status)
        assertEquals("fun a()", atom.header)
        assertEquals(1, atom.added); assertEquals(1, atom.removed)
        assertEquals(2..4, atom.beforeLines); assertEquals(2..4, atom.afterLines)
        assertFalse(atom.signatureChanged)
        assertEquals("implementation", atom.evidenceKind)
        // The class line and the next method are context outside the method, so they are dropped.
        assertEquals(listOf("     fun a() {", "-        one()", "+        uno()", "     }"), texts(file, atom))
        assertCovers(file, atoms)
    }

    @Test fun `an added class absorbs its methods`() {
        val after = "class B {\n    fun m() {\n        a()\n    }\n    fun n() {\n        b()\n    }\n}\n"
        val file = atomTestFile("B.kt", "", after, "added")
        val atoms = sliceAtoms(file, emptyList(), listOf(declare(after, "B", "class B", SymbolKind.CLASS), declare(after, "B.m", "fun m()"), declare(after, "B.n", "fun n()")))
        val atom = atoms.single()
        assertEquals("B.kt#B", atom.id)
        assertEquals(AtomKind.CLASS, atom.kind)
        assertEquals(AtomStatus.ADDED, atom.status)
        assertEquals(8, atom.added); assertEquals(0, atom.removed)
        assertEquals(listOf(2..9), atom.rows)
        assertNull(atom.beforeLines); assertEquals(1..8, atom.afterLines)
        assertCovers(file, atoms)
    }

    @Test fun `an added method with a nested helper is one atom`() {
        val before = "class A {\n    fun a() {\n        one()\n    }\n}\n"
        val after = "class A {\n    fun a() {\n        one()\n    }\n    fun m() {\n        fun helper() {\n            x()\n        }\n        helper()\n    }\n}\n"
        val file = atomTestFile("A.kt", before, after)
        fun declarations(source: String) = buildList {
            add(declare(source, "A", "class A", SymbolKind.CLASS)); add(declare(source, "A.a", "fun a()"))
            if ("fun m()" in source) { add(declare(source, "A.m", "fun m()")); add(declare(source, "A.m.helper", "fun helper()")) }
        }
        val atoms = sliceAtoms(file, declarations(before), declarations(after))
        assertEquals(listOf("A.kt#A.m"), atoms.map { it.id })
        assertEquals(AtomStatus.ADDED, atoms.single().status)
        assertEquals(6, atoms.single().added)
        assertCovers(file, atoms)
    }

    @Test fun `a removed function is a removed atom with the before header`() {
        val before = "fun f() {\n    one()\n}\n\nfun g() {\n    two()\n}\n"
        val after = "fun g() {\n    two()\n}\n"
        val file = atomTestFile("util.kt", before, after)
        val atoms = sliceAtoms(file, listOf(declare(before, "f", "fun f()"), declare(before, "g", "fun g()")), listOf(declare(after, "g", "fun g()")))
        // The blank line between the functions went with f; alone it says nothing, so it follows f.
        assertEquals(listOf("util.kt#f"), atoms.map { it.id })
        val atom = atoms.single { it.name == "f" }
        assertEquals(AtomStatus.REMOVED, atom.status)
        assertEquals("fun f()", atom.header)
        assertEquals(1..3, atom.beforeLines); assertNull(atom.afterLines)
        assertEquals(4, atom.removed); assertEquals(0, atom.added)
        assertCovers(file, atoms)
    }

    @Test fun `top-level changes are one loose atom that keeps its free context`() {
        val before = "val one = a.One\n\nfun f() {\n    x()\n}\n"
        val after = "val one = a.Two\n\nfun f() {\n    y()\n}\n"
        val file = atomTestFile("f.kt", before, after)
        val atoms = sliceAtoms(file, listOf(declare(before, "f", "fun f()")), listOf(declare(after, "f", "fun f()")))
        assertEquals(listOf("f.kt#<top-level>", "f.kt#f"), atoms.map { it.id })
        val loose = atoms.first()
        assertEquals(AtomKind.LOOSE, loose.kind)
        assertEquals("top-level code", loose.name)
        assertEquals("", loose.header)
        assertNull(loose.beforeLines); assertNull(loose.afterLines)
        assertEquals(listOf("-val one = a.One", "+val one = a.Two", " "), texts(file, loose))
        assertEquals(listOf(" fun f() {", "-    x()", "+    y()", " }"), texts(file, atoms.last()))
        assertCovers(file, atoms)
    }

    @Test fun `overloads get numbered ids in source order`() {
        val before = "class A {\n    fun save(a: Int) {\n        one()\n    }\n    fun save(b: String) {\n        two()\n    }\n}\n"
        val after = before.replace("one()", "uno()").replace("two()", "dos()")
        val file = atomTestFile("A.kt", before, after)
        fun declarations(source: String) = listOf(declare(source, "A", "class A", SymbolKind.CLASS), declare(source, "A.save", "fun save(a: Int)"), declare(source, "A.save", "fun save(b: String)"))
        val atoms = sliceAtoms(file, declarations(before), declarations(after))
        assertEquals(listOf("A.kt#A.save", "A.kt#A.save~2"), atoms.map { it.id })
        assertEquals(listOf("fun save(a: Int)", "fun save(b: String)"), atoms.map { it.header })
        assertCovers(file, atoms)
    }

    @Test fun `a changed header marks the signature as changed and reads the after header`() {
        val before = "fun f(a: Int) {\n    use(a)\n}\n"
        val after = "fun f(a: Int, b: Int) {\n    use(a)\n}\n"
        val file = atomTestFile("f.kt", before, after)
        val atom = sliceAtoms(file, listOf(declare(before, "f", "fun f(a: Int)")), listOf(declare(after, "f", "fun f(a: Int, b: Int)"))).single()
        assertTrue(atom.signatureChanged)
        assertEquals("fun f(a: Int, b: Int)", atom.header)
        assertEquals(AtomStatus.MODIFIED, atom.status)
    }

    @Test fun `context rows join the atom that contains them and the others are dropped`() {
        val before = "fun a() {\n    one()\n}\nfun b() {\n    two()\n}\n"
        val after = before.replace("one()", "uno()").replace("two()", "dos()")
        val file = atomTestFile("ab.kt", before, after)
        val atoms = sliceAtoms(file, listOf(declare(before, "a", "fun a()"), declare(before, "b", "fun b()")), listOf(declare(after, "a", "fun a()"), declare(after, "b", "fun b()")))
        assertEquals(listOf(" fun a() {", "-    one()", "+    uno()", " }"), texts(file, atoms[0]))
        assertEquals(listOf(" fun b() {", "-    two()", "+    dos()", " }"), texts(file, atoms[1]))
        assertCovers(file, atoms)
    }

    @Test fun `a property is its own atom`() {
        val before = "class A {\n    val limit = 1\n    fun f() {\n        x()\n    }\n}\n"
        val after = before.replace("limit = 1", "limit = 2")
        val file = atomTestFile("A.kt", before, after)
        fun declarations(source: String) = listOf(declare(source, "A", "class A", SymbolKind.CLASS), declare(source, "A.limit", "val limit", SymbolKind.PROPERTY), declare(source, "A.f", "fun f()"))
        val atom = sliceAtoms(file, declarations(before), declarations(after)).single()
        assertEquals(AtomKind.PROPERTY, atom.kind)
        assertEquals("A.kt#A.limit", atom.id)
    }

    @Test fun `a file without code analysis is one file atom`() {
        val file = atomTestFile("docs/guide.md", "# Title\nold\n", "# Title\nnew\nmore\n")
        val atom = sliceAtoms(file, null, null).single()
        assertEquals("docs/guide.md", atom.id)
        assertEquals("guide.md", atom.name)
        assertEquals(AtomKind.FILE, atom.kind)
        assertEquals(AtomStatus.MODIFIED, atom.status)
        assertEquals("documentation", atom.evidenceKind)
        assertEquals(listOf(1..patchRows(file.patch)), atom.rows)
        assertEquals(setOf(3, 4, 5), atom.changedRows)
        assertEquals(2, atom.added); assertEquals(1, atom.removed)
        assertNull(atom.beforeLines); assertNull(atom.afterLines)
    }

    @Test fun `file atoms take their status from the file and exclude marker rows from changes`() {
        val added = atomTestFile("a.txt", "", "x\ny", "untracked")
        val atom = sliceAtoms(added, null, null).single()
        assertEquals(AtomStatus.ADDED, atom.status)
        assertEquals(setOf(2, 3), atom.changedRows)
        assertEquals(listOf(1..4), atom.rows)
        assertEquals(AtomStatus.REMOVED, sliceAtoms(atomTestFile("a.txt", "x\n", "", "deleted"), null, null).single().status)
        assertTrue(sliceAtoms(added.copy(patch = ""), null, null).isEmpty())
        assertTrue(sliceAtoms(added.copy(patch = "@@ -1,1 +1,1 @@\n same\n"), null, null).isEmpty())
    }

    @Test fun `fingerprints change with the text of the rows`() {
        val before = "fun f() {\n    one()\n}\n"
        fun atom(body: String): ChangeAtom {
            val after = before.replace("one()", body)
            return sliceAtoms(atomTestFile("f.kt", before, after), listOf(declare(before, "f", "fun f()")), listOf(declare(after, "f", "fun f()"))).single()
        }
        assertEquals(atom("two()").fingerprint, atom("two()").fingerprint)
        assertNotEquals(atom("two()").fingerprint, atom("three()").fingerprint)
    }

    @Test fun `every changed row of a mixed file belongs to exactly one atom`() {
        val before = """
            import a.B
            import a.C

            val LIMIT = 10

            class Service {
                val name = "x"

                fun load(id: Int): String {
                    val v = repo.find(id)
                    return v
                }

                fun drop(id: Int) {
                    repo.remove(id)
                }

                fun keep() {
                    log("keep")
                }
            }

            fun helper() {
                println("old")
            }

            fun gone() {
                println("gone")
            }
        """.trimIndent() + "\n"
        val after = """
            import a.B
            import a.D

            val LIMIT = 20

            class Service {
                val name = "y"

                fun load(id: Int): String {
                    val v = repo.get(id)
                    return v
                }

                fun keep() {
                    log("keep")
                }

                fun add(x: Int) {
                    repo.add(x)
                }
            }

            fun helper() {
                println("new")
            }

            class Extra {
                fun run() {
                    go()
                }
            }
        """.trimIndent() + "\n"
        fun declarations(source: String) = listOf(
            "LIMIT" to ("val LIMIT" to SymbolKind.PROPERTY), "Service" to ("class Service" to SymbolKind.CLASS), "Service.name" to ("val name" to SymbolKind.PROPERTY),
            "Service.load" to ("fun load" to fn), "Service.drop" to ("fun drop" to fn), "Service.keep" to ("fun keep" to fn), "Service.add" to ("fun add" to fn),
            "helper" to ("fun helper" to fn), "gone" to ("fun gone" to fn), "Extra" to ("class Extra" to SymbolKind.CLASS), "Extra.run" to ("fun run" to fn),
        ).filter { (_, start) -> source.lines().any { it.trim().startsWith(start.first) } }.map { (name, start) -> declare(source, name, start.first, start.second) }
        val file = atomTestFile("Service.kt", before, after)
        val atoms = sliceAtoms(file, declarations(before), declarations(after))
        val byName = atoms.associateBy { it.name }
        // Blank rows and the changed import are no atoms of their own: they follow the code beside them.
        assertEquals(setOf("LIMIT", "Service.name", "Service.load", "Service.drop", "Service.add", "helper", "gone", "Extra"), byName.keys)
        assertEquals(AtomStatus.REMOVED, byName.getValue("Service.drop").status)
        assertEquals(AtomStatus.REMOVED, byName.getValue("gone").status)
        assertEquals(AtomStatus.ADDED, byName.getValue("Service.add").status)
        assertEquals(AtomStatus.ADDED, byName.getValue("Extra").status)
        assertEquals(AtomStatus.MODIFIED, byName.getValue("Service.load").status)
        assertCovers(file, atoms)
    }

    @Test fun `changed imports follow the largest atom of their file`() {
        val before = "import a.One\n\nfun f() {\n    x()\n}\n"
        val after = "import a.Two\n\nfun f() {\n    y()\n}\n"
        val file = atomTestFile("f.kt", before, after)
        val atoms = sliceAtoms(file, listOf(declare(before, "f", "fun f()")), listOf(declare(after, "f", "fun f()")))
        assertEquals(listOf("f.kt#f"), atoms.map { it.id })
        assertEquals(2, atoms.single().added)
        assertCovers(file, atoms)
    }
}
