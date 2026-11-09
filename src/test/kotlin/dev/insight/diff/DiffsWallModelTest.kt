package dev.insight.diff

import dev.insight.review.wall.WallLine
import dev.insight.review.wall.WallMetrics
import dev.insight.review.wall.wallBlockHeight
import dev.insight.review.wall.wallBlockWidth
import org.junit.Assert.assertEquals
import org.junit.Test

class DiffsWallModelTest {
    private fun file(path: String, patch: String, previousPath: String? = null) =
        ReviewFile(path, "/$path", previousPath, "modified", patch, path)

    private val metrics = WallMetrics(charWidth = 8, lineHeight = 16, headerHeight = 24, padding = 4, gap = 20)

    @Test
    fun linesKeepTheirMarkersAndNumberBothSides() {
        val lines = wallLines("@@ -3,3 +3,3 @@ fun a()\n same\n-old\n+new\n\tend\n[After: no newline at end of file]\n")
        assertEquals(listOf(WallLine.Kind.HUNK, WallLine.Kind.CONTEXT, WallLine.Kind.REMOVED, WallLine.Kind.ADDED,
            WallLine.Kind.CONTEXT, WallLine.Kind.NOTE), lines.map { it.kind })
        assertEquals("@@ -3,3 +3,3 @@ fun a()", lines[0].text)
        assertEquals(WallLine(WallLine.Kind.CONTEXT, " same", 3, 3), lines[1])
        assertEquals(WallLine(WallLine.Kind.REMOVED, "-old", old = 4), lines[2])
        assertEquals(WallLine(WallLine.Kind.ADDED, "+new", new = 4), lines[3])
        // A tab-led line is context; tabs are expanded so monospace columns line up.
        assertEquals(WallLine(WallLine.Kind.CONTEXT, "    end", 5, 5), lines[4])
    }

    @Test
    fun textBeforeTheFirstHunkIsANote() {
        val lines = wallLines("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n")
        assertEquals(listOf(WallLine.Kind.NOTE, WallLine.Kind.NOTE, WallLine.Kind.NOTE, WallLine.Kind.HUNK,
            WallLine.Kind.REMOVED, WallLine.Kind.ADDED), lines.map { it.kind })
    }

    @Test
    fun blockCountsChangesTitlesRenamesAndCapsLines() {
        val patch = "@@ -1,2 +1,3 @@\n-a\n+b\n+c\n d\n"
        val block = wallBlock(file("src/New.kt", patch, previousPath = "src/Old.kt"), maxLines = 3)
        assertEquals("src/New.kt", block.path)
        assertEquals("src/Old.kt → src/New.kt", block.title)
        assertEquals("+2 −1", block.stats)
        assertEquals(3, block.lines.size)
        assertEquals(2, block.hiddenLines)
        assertEquals("src/A.kt", wallBlock(file("src/A.kt", patch, previousPath = "src/A.kt")).title)
        assertEquals(0, wallBlock(file("src/A.kt", patch)).hiddenLines)
    }

    @Test
    fun blocksSizeByTheirDiffRows() {
        val patch = "@@ -1 +1 @@\n-a\n+b\n"
        assertEquals(24 + 3 * 16 + 8, wallBlockHeight(wallBlock(file("a", patch)), metrics))
        assertEquals(24 + 3 * 16 + 8, wallBlockHeight(wallBlock(file("a", patch), maxLines = 2), metrics))
        assertEquals(24 + 16 + 8, wallBlockHeight(wallBlock(file("a", "")), metrics))
        assertEquals(80 * 8 + 8, wallBlockWidth(listOf(wallBlock(file("b", "@@ -1 +1 @@\n+" + "x".repeat(79) + "\n"))), metrics))
    }

    @Test
    fun contentMakesOneBlockPerFileInOrder() {
        val files = listOf(file("b.kt", "@@ -1 +1 @@\n-a\n+b\n"), file("a.kt", ""))
        val wall = DiffsWallContent(dev.insight.review.wall.WallScope(null, "All changes"), files, mapOf("a.kt" to "Sum")).toWall()
        assertEquals(listOf("b.kt", "a.kt"), wall.blocks.map { it.path })
        assertEquals(mapOf("a.kt" to "Sum"), wall.summaries)
    }
}
