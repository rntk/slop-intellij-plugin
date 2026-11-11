package dev.insight.review.wall

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.diff.ReviewFile
import dev.insight.diff.diffsWallHighlighter
import dev.insight.diff.wallBlock

class WallSyntaxPlatformTest : BasePlatformTestCase() {
    fun testKnownFileTypesAreLexedAndPlainTextIsNot() {
        val side = WallSyntax.side(project, "src/A.java", "class A {\n\t/* note */\n}\n", 2)!!
        assertEquals(listOf("class A {", "    /* note */"), side.lines)
        assertTrue("keyword styled", side.spans[0].any { it.from == 0 && it.to == 5 })
        assertTrue("comment styled", side.spans[1].any { it.from == 4 && it.to == 14 })
        assertNull(WallSyntax.side(project, "notes.txt", "class A", 1))
        assertNull(WallSyntax.side(project, "src/A.java", null, 1))
    }

    fun testDiffBlocksAreColouredFromBothWholeSides() {
        val before = "class A {\n    /* one\n       two */\n    int a = 1;\n}\n"
        val after = "class A {\n    /* one\n       two */\n    int a = 2;\n}\n"
        val patch = "@@ -3,3 +3,3 @@\n        two */\n-    int a = 1;\n+    int a = 2;\n }\n"
        val file = ReviewFile("A.java", "/p/A.java", null, "modified", patch, "f", before, after)
        val block = wallBlock(file)
        val rows = diffsWallHighlighter(project) { file }.prepare(block)!!.invoke()!!
        assertNull(rows[0])
        // The comment's end is coloured though the hunk does not show where it opened.
        assertTrue(rows[1]!!.any { it.from == 1 && it.to == 14 })
        assertTrue(rows[2]!!.any { it.from == 5 && it.to == 8 })
        assertTrue(rows[3]!!.any { it.from == 5 && it.to == 8 })
    }
}
