package dev.insight.review.wall

import dev.insight.graph.SymbolKind
import dev.insight.links.SymbolCandidate
import dev.insight.links.SymbolMention
import dev.insight.review.ReportLink
import dev.insight.review.ReportTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Point

class WallTextLinksTest {
    private fun diffBlock(path: String, vararg lines: WallLine, more: List<WallLine> = emptyList()) =
        WallBlock(path, path, "", lines.toList(), more.size, more)

    @Test
    fun `wallTargetRow prefers exact new lines, then old lines, then nearest row`() {
        val lines = listOf(
            WallLine(WallLine.Kind.HUNK, "@@ -10,3 +20,3 @@"),
            WallLine(WallLine.Kind.REMOVED, "-oldA", old = 10),
            WallLine(WallLine.Kind.CONTEXT, " same", old = 11, new = 20),
            WallLine(WallLine.Kind.ADDED, "+newB", new = 21),
            WallLine(WallLine.Kind.ADDED, "+newC", new = 22),
        )
        val newHit = wallTargetRow(lines, 21..22)!!
        assertEquals(3, newHit.rowIndex)
        assertTrue(newHit.exact)
        assertEquals(setOf(3, 4), newHit.highlightedRows)

        val oldHit = wallTargetRow(lines, 10..10)!!
        assertEquals(1, oldHit.rowIndex)
        assertTrue(oldHit.exact)
        assertEquals(setOf(1), oldHit.highlightedRows)

        val nearest = wallTargetRow(lines, 99..105)!!
        assertEquals(4, nearest.rowIndex)
        assertFalse(nearest.exact)
        assertTrue(nearest.highlightedRows.isEmpty())

        assertNull(wallTargetRow(lines, null))
        assertNull(wallTargetRow(emptyList(), 1..1))
    }

    @Test
    fun `wallTextMove matches exact, normalized, and renamed paths or requests adding`() {
        val blocks = listOf(
            diffBlock("src/NewName.kt", WallLine.source(1, "a"), WallLine.source(2, "b")),
            diffBlock("src/Other.kt", WallLine.source(5, "c")),
        )
        val exact = wallTextMove("src/NewName.kt", 2..2, blocks) as WallTextMove.Scroll
        assertEquals("src/NewName.kt", exact.path)
        assertEquals(1, exact.row?.rowIndex)

        val normalized = wallTextMove("./src\\Other.kt", null, blocks) as WallTextMove.Scroll
        assertEquals("src/Other.kt", normalized.path)
        assertNull(normalized.row)

        val renamed = wallTextMove("src/OldName.kt", 1..1, blocks, mapOf("src/OldName.kt" to "src/NewName.kt")) as WallTextMove.Scroll
        assertEquals("src/NewName.kt", renamed.path)
        assertEquals(0, renamed.row?.rowIndex)

        val missing = wallTextMove("./src/Missing.kt", 12..14, blocks) as WallTextMove.AddAndScroll
        assertEquals("src/Missing.kt", missing.path)
        assertEquals(12..14, missing.lines)
    }

    @Test
    fun `context files, preferred file, and candidate ranking respect source and wall context`() {
        assertEquals(
            listOf("src/A.kt", "src/B.kt", "src/C.kt"),
            wallContextFiles("src/A.kt", listOf("src/B.kt", "src/A.kt"), listOf("src/C.kt", "src/B.kt")),
        )
        val candidates = listOf("other/Helper.kt", "scope/Helper.kt", "wall/Helper.kt", "src/Helper.kt")
        assertEquals("src/Helper.kt", wallPreferredFile(candidates, "src/Helper.kt", setOf("wall/Helper.kt"), setOf("scope/Helper.kt")))
        assertEquals("wall/Helper.kt", wallPreferredFile(candidates, "src/Main.kt", setOf("wall/Helper.kt"), setOf("scope/Helper.kt")))
        assertNull(wallPreferredFile(listOf("w1/H.kt", "w2/H.kt"), "src/Main.kt", setOf("w1/H.kt", "w2/H.kt")))
        assertEquals("scope/Helper.kt", wallPreferredFile(listOf("o/H.kt", "scope/Helper.kt"), null, emptySet(), setOf("scope/Helper.kt")))

        assertEquals(
            listOf("src/Helper.kt", "wall/Helper.kt", "scope/Helper.kt", "other/Helper.kt"),
            wallRankFiles(candidates, "src/Helper.kt", setOf("wall/Helper.kt"), setOf("scope/Helper.kt")),
        )
    }

    @Test
    fun `wallPreferredSymbol picks unique wall match while respecting explicit file hints and ambiguity`() {
        fun cand(path: String, line: Int) = SymbolCandidate("run", SymbolKind.FUNCTION, null, path, "/$path", line)
        val blocks = listOf(
            diffBlock("src/A.kt", WallLine.source(10, "fun run()")),
            diffBlock("src/B.kt", WallLine.source(30, "fun run()"), WallLine.source(35, "fun run(x: Int)")),
        )
        // Unique exact hit on source card wins over other wall card.
        val cA = cand("src/A.kt", 10)
        val cB = cand("src/B.kt", 30)
        assertEquals(cA, wallPreferredSymbol(listOf(cB, cA), "src/A.kt", blocks))

        // A shown declaration beats an unshown declaration on the same file.
        assertEquals(cB, wallPreferredSymbol(listOf(cB, cand("src/B.kt", 50)), null, blocks))

        // Two matches on the same wall tier stay ambiguous (returns null so chooser asks).
        assertNull(wallPreferredSymbol(listOf(cand("src/B.kt", 30), cand("src/B.kt", 35)), null, blocks))
        assertNull(wallPreferredSymbol(listOf(cand("src/B.kt", 50), cand("src/B.kt", 60)), null, blocks))

        // Explicit hint file protects against hijacking back to sourcePath.
        val cOutside = cand("src/Helper.kt", 5)
        assertEquals(cOutside, wallPreferredSymbol(listOf(cOutside, cA), "src/A.kt", blocks, listOf("src/Helper.kt")))
        assertNull(wallPreferredSymbol(listOf(cA, cB), "src/A.kt", blocks, listOf("src/Helper.kt")))
        assertNull(wallPreferredSymbol(listOf(cand("src/Helper.kt", 5), cand("src/Helper.kt", 15)), "src/A.kt", blocks, listOf("src/Helper.kt")))

        // Declarations all off the wall defer to chooser.
        assertNull(wallPreferredSymbol(listOf(cand("src/X.kt", 1), cand("src/Y.kt", 2)), "src/A.kt", blocks))
    }

    @Test
    fun `wallNewCardPosition places beside source without overlapping cards or chat windows`() {
        val gap = 24
        val source = WallRect(24, 24, 500, 300)
        // Below source is free.
        assertEquals(Point(24, 348), wallNewCardPosition(source, listOf(source), 500, 200, gap))

        // Below is occupied -> tries right of source.
        val below = WallRect(24, 348, 500, 200)
        assertEquals(Point(548, 24), wallNewCardPosition(source, listOf(source, below), 500, 200, gap))

        // Right of source holds an open chat panel -> jumps right of the chat panel.
        val chat = WallRect(524, 24, 420, 340)
        assertEquals(Point(968, 24), wallNewCardPosition(source, listOf(source, below), 500, 200, gap, avoidedRects = listOf(chat)))

        // Empty canvas falls back to (gap, gap).
        assertEquals(Point(24, 24), wallNewCardPosition(null, emptyList(), 500, 200, gap))
    }

    @Test
    fun `wallAddedSourceBlock and wallExpandBlockForLine load deep target lines`() {
        val text = (1..500).joinToString("\n") { "line $it" } + "\n"
        val defaultBlock = wallAddedSourceBlock("src/Big.kt", text, targetLine = null, badge = "not in diff", maxLines = 400)
        assertEquals("500 lines · not in diff", defaultBlock.stats)
        assertEquals(400, defaultBlock.lines.size)
        assertEquals(100, defaultBlock.hiddenLines)

        val deepBlock = wallAddedSourceBlock("src/Big.kt", text, targetLine = 460, badge = "outside scope", maxLines = 400)
        assertEquals("500 lines · outside scope", deepBlock.stats)
        assertEquals(500, deepBlock.lines.size)
        assertEquals(0, deepBlock.hiddenLines)

        val expanded = wallExpandBlockForLine(defaultBlock, 420)
        assertTrue(expanded.lines.any { it.new == 420 })
        assertTrue(expanded.hiddenLines < defaultBlock.hiddenLines)
        assertSame(defaultBlock, wallExpandBlockForLine(defaultBlock, 999))
    }

    @Test
    fun `wallLinkTooltip reflects wall, diff, scope, external, ambiguous, and symbol states`() {
        val wall = setOf("src/OnWall.kt")
        val scope = setOf("src/OnWall.kt", "src/InScope.kt")

        val onWallTip = wallLinkTooltip(ReportLink(0, "OnWall.kt:L5", ReportTargetKind.FILE, listOf("src/OnWall.kt"), 5..5), wall, scope, isDiffWall = true)
        assertTrue(onWallTip, "on wall · Click to scroll" in onWallTip)

        val inDiffTip = wallLinkTooltip(ReportLink(1, "InScope.kt", ReportTargetKind.FILE, listOf("src/InScope.kt")), wall, scope, isDiffWall = true)
        assertTrue(inDiffTip, "in diff · Click to add card" in inDiffTip)

        val inCodeScopeTip = wallLinkTooltip(ReportLink(2, "InScope.kt", ReportTargetKind.FILE, listOf("src/InScope.kt")), wall, scope, isDiffWall = false)
        assertTrue(inCodeScopeTip, "in scope · Click to add card" in inCodeScopeTip)

        val externalTip = wallLinkTooltip(ReportLink(3, "Ext.kt", ReportTargetKind.FILE, listOf("src/Ext.kt")), wall, scope, isDiffWall = true)
        assertTrue(externalTip, "Click to add card" in externalTip)

        val ambigTip = wallLinkTooltip(ReportLink(4, "X.kt", ReportTargetKind.FILE, listOf("a/X.kt", "b/X.kt")), wall, scope, isDiffWall = true)
        assertTrue(ambigTip, "Matches 2 files" in ambigTip)

        val symTip = wallLinkTooltip(ReportLink(5, "foo()", ReportTargetKind.SYMBOL, symbol = SymbolMention(0, 5, "foo", call = true)), wall, scope, isDiffWall = true)
        assertTrue(symTip, "Click to scroll or add card" in symTip)
    }
}
