package dev.insight.links

import dev.insight.graph.SymbolKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolCandidatesTest {
    private fun candidate(path: String, line: Int = 1, container: String? = null, kind: SymbolKind = SymbolKind.FUNCTION, name: String = "run") =
        SymbolCandidate(name, kind, container, path, "/app/$path", line)

    @Test
    fun `candidates join by file in rank order, each file's declarations in line order`() {
        val lateA = candidate("src/A.kt", line = 40, container = "Late")
        val b = candidate("src/b/B.kt", line = 3, kind = SymbolKind.PROPERTY)
        val earlyA = candidate("src/A.kt", line = 5, container = "Early")
        val files = symbolCandidateFiles(listOf(lateA, b, earlyA))
        assertEquals(listOf("src/A.kt", "src/b/B.kt"), files.map { it.path })
        assertEquals(listOf(earlyA, lateA), files[0].candidates)
        assertEquals("the most likely declaration is scrolled to first", lateA, files[0].best)
        assertEquals("A.kt (2) · Function · src", symbolCandidateFileLabel(files[0]))
        assertEquals("B.kt:3 · Property · src/b", symbolCandidateFileLabel(files[1]))
        assertEquals("Files declaring run (2)", symbolCandidateFilesTitle("run", files.size))
        assertEquals("Line 5 · Function in Early", symbolCandidateSiteLabel(earlyA))
    }

    @Test
    fun `symbolCandidateFileFor picks preferred declaration while keeping all overloads in line order`() {
        val earlyA = candidate("src/A.kt", line = 5, container = "Early")
        val lateA = candidate("src/A.kt", line = 40, container = "Late")
        val other = candidate("src/B.kt", line = 10, container = "Other")
        val file = symbolCandidateFileFor(listOf(earlyA, other, lateA), lateA)
        assertEquals("src/A.kt", file.path)
        assertEquals(lateA, file.best)
        assertEquals(listOf(earlyA, lateA), file.candidates)
    }

    @Test
    fun `qualifier keeps only declarations in that owner`() {
        val a = candidate("src/a/Worker.kt", container = "dev.a.Worker")
        val b = candidate("src/b/Runner.kt", container = "dev.b.Runner")
        val ranked = rankSymbolCandidates(listOf(a, b), SymbolMention(0, 10, "run", listOf("Runner")))
        assertEquals(listOf(b), ranked)
    }

    @Test
    fun `unmatched qualifier keeps every declaration`() {
        val a = candidate("src/a/Worker.kt", container = "Worker")
        val b = candidate("src/b/Runner.kt", container = "Runner")
        assertEquals(2, rankSymbolCandidates(listOf(a, b), SymbolMention(0, 10, "run", listOf("Other"))).size)
    }

    @Test
    fun `files the text referred to rank first, then their directory, then review scope`() {
        val far = candidate("src/z/Far.kt")
        val scoped = candidate("src/y/Scoped.kt")
        val sibling = candidate("src/x/Sibling.kt")
        val mentioned = candidate("src/x/Mentioned.kt")
        val ranked = rankSymbolCandidates(
            listOf(far, scoped, sibling, mentioned),
            SymbolMention(0, 3, "run"),
            SymbolHints(files = listOf("src/x/Mentioned.kt"), scopeFiles = setOf("src/y/Scoped.kt")),
        )
        assertEquals(listOf(mentioned, sibling, scoped, far), ranked)
    }

    @Test
    fun `tests rank after sources and duplicates collapse`() {
        val test = candidate("src/test/kotlin/RunTest.kt")
        val main = candidate("src/main/kotlin/Run.kt")
        val ranked = rankSymbolCandidates(listOf(test, main, main.copy()), SymbolMention(0, 3, "run"))
        assertEquals(listOf(main, test), ranked)
    }

    @Test
    fun `calls prefer functions and capitalized names prefer classes`() {
        val property = candidate("a/A.kt", kind = SymbolKind.PROPERTY, name = "Item")
        val type = candidate("b/B.kt", kind = SymbolKind.CLASS, name = "Item")
        assertEquals(type, rankSymbolCandidates(listOf(property, type), SymbolMention(0, 4, "Item")).first())
        val function = candidate("b/B.kt", kind = SymbolKind.FUNCTION, name = "item")
        val field = candidate("a/A.kt", kind = SymbolKind.PROPERTY, name = "item")
        assertEquals(function, rankSymbolCandidates(listOf(field, function), SymbolMention(0, 6, "item", call = true)).first())
    }


    @Test
    fun `declarations sharing a line stay distinct by column`() {
        val first = candidate("src/A.kt", line = 3).copy(column = 5)
        val second = first.copy(column = 30)
        assertEquals(listOf(first, second), rankSymbolCandidates(listOf(first, second, second.copy()), SymbolMention(0, 3, "run")))
    }

    @Test
    fun `the limit applies after the qualifier and context rank`() {
        val others = (1..300).map { candidate("src/o/Other$it.kt", container = "Other$it") }
        val worker = candidate("src/w/Worker.kt", container = "Worker")
        val near = candidate("src/x/Near.kt", container = "Near")
        assertEquals(listOf(worker), rankSymbolCandidates(others + worker, SymbolMention(0, 10, "run", listOf("Worker")), limit = 200))
        val ranked = rankSymbolCandidates(others + near, SymbolMention(0, 3, "run"), SymbolHints(files = listOf("src/x/Near.kt")), limit = 200)
        assertEquals(200, ranked.size)
        assertEquals(near, ranked.first())
    }
    @Test
    fun labels() {
        val c = candidate("src/main/kotlin/dev/Run.kt", line = 12, container = "Runner")
        assertEquals("Function in Runner", symbolCandidateDetail(c))
        assertEquals("Run.kt:12 · src/main/kotlin/dev", symbolCandidateLocation(c))
        assertEquals("Declarations of run (3)", symbolCandidatesTitle("run", 3))
    }
}
