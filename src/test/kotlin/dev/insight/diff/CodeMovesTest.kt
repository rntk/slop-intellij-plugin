package dev.insight.diff

import dev.insight.callgraph.LineChange
import junit.framework.TestCase
import java.awt.image.BufferedImage

class CodeMovesTest : TestCase() {
    private val validate = """
        fun validate(order: Order): List<String> {
            val errors = mutableListOf<String>()
            if (order.items.isEmpty()) errors += "no items"
            if (order.total < 0) errors += "negative total"
            return errors
        }
    """.trimIndent()
    private val map = """
        fun toDto(order: Order): OrderDto {
            val lines = order.items.map { LineDto(it.sku, it.count) }
            return OrderDto(order.id, lines, order.total)
        }
    """.trimIndent()
    private val keep = """
        class OrderService(private val repository: Repository) {
            fun save(order: Order) = repository.save(order)
        }
    """.trimIndent()

    /** A file whose zero-based lines [removed] were deleted. */
    private fun modified(path: String, before: String, removed: IntRange): ReviewFile {
        val old = before.lines()
        val after = (old.take(removed.first) + old.drop(removed.last + 1)).joinToString("\n") + "\n"
        return changed(path, before + "\n", after, listOf(LineChange(removed.first, removed.last + 1, removed.first, removed.first)))
    }

    private fun changed(path: String, before: String, after: String, changes: List<LineChange>) =
        ReviewFile(path, "/$path", null, "modified", reviewPatch(before, after, changes), path, before, after)

    private fun added(path: String, text: String, status: String = "added") = ReviewFile(path, "/$path", null, status,
        reviewPatch("", text + "\n", listOf(LineChange(0, 0, 0, text.lines().size))), path, "", text + "\n")

    private fun deleted(path: String, text: String) = ReviewFile(path, "/$path", null, "deleted",
        reviewPatch(text + "\n", "", listOf(LineChange(0, text.lines().size, 0, 0))), path, text + "\n", "")

    fun testASplitFileReportsEachDestinationWithItsShare() {
        val before = listOf(keep, validate, map).joinToString("\n")
        val source = modified("src/OrderService.kt", before, 3 until 3 + validate.lines().size + map.lines().size)
        val moves = codeMoves(listOf(source, added("src/OrderValidator.kt", "package app\n\n$validate"), added("src/OrderMapper.kt", map)))
        val file = moves.sources.single()
        assertEquals("src/OrderService.kt", file.path)
        assertEquals(listOf("src/OrderValidator.kt", "src/OrderMapper.kt"), file.targets.map { it.path })
        // Closing braces and the package line are not significant.
        assertEquals(listOf(5, 3), file.targets.map { it.lines })
        assertEquals(10, file.lines)
        assertEquals(file.removed, file.moved)
        assertEquals(0, file.deleted)
        assertEquals(2, file.stayed)
        assertEquals(0.5, file.share(file.targets.first().lines))
        val block = file.targets.first().blocks.single()
        assertEquals(4..8, block.fromLines)
        assertEquals(3..7, block.toLines)
        assertEquals(listOf("src/OrderMapper.kt", "src/OrderValidator.kt"), moves.destinations)
    }

    fun testReindentedCodeStillMatchesAndADeletedFileCanBeTheSource() {
        val nested = "object Holder {\n" + validate.lines().joinToString("\n") { "    $it" } + "\n}"
        val moves = codeMoves(listOf(deleted("a/Old.kt", validate), added("b/New.kt", nested)))
        val file = moves.sources.single()
        assertEquals("a/Old.kt", file.path)
        assertEquals(1.0, file.share(file.moved))
        assertEquals(2..6, file.targets.single().blocks.single().toLines)
    }

    fun testAnEditedLineInsideAMovedBlockKeepsOneBlock() {
        val edited = validate.replace("negative total", "total below zero")
        val file = codeMoves(listOf(deleted("Old.kt", validate), added("New.kt", edited))).sources.single()
        val block = file.targets.single().blocks.single()
        assertEquals(1..5, block.fromLines)
        assertEquals(4, block.lines)
        assertEquals(1, file.deleted)
    }

    fun testBoilerplateShortRunsAndMovesWithinOneFileAreIgnored() {
        val boilerplate = "import a.b.C\nimport a.b.D\n\n}\n}\n"
        assertTrue(codeMoves(listOf(deleted("A.kt", boilerplate), added("B.kt", boilerplate))).sources.isEmpty())
        assertTrue(codeMoves(listOf(deleted("A.kt", "return x\nreturn y"), added("B.kt", "return x\nreturn y"))).sources.isEmpty())
        val within = changed("A.kt", "$validate\n$keep\n", "$keep\n$validate\n", listOf(LineChange(0, 6, 0, 0), LineChange(9, 9, 3, 9)))
        assertEquals(5, patchSides(within.patch).added.size)
        assertTrue(codeMoves(listOf(within)).sources.isEmpty())
    }

    fun testEachAddedLineIsClaimedOnce() {
        val moves = codeMoves(listOf(deleted("A.kt", validate), deleted("B.kt", validate), added("C.kt", validate, "untracked")))
        assertEquals(listOf("A.kt"), moves.sources.map { it.path })
    }

    fun testPatchSidesNumberLinesOnTheirOwnSide() {
        val sides = patchSides("@@ -3,3 +3,4 @@\n context line\n-removed line\n+added line one\n+  added   line two\n context end\n")
        assertEquals(listOf(PatchLine(4, "removed line")), sides.removed)
        assertEquals(listOf(PatchLine(4, "added line one"), PatchLine(5, "added line two")), sides.added)
    }

    fun testChartModesPlaceEveryFileAndPaint() {
        val before = listOf(keep, validate, map).joinToString("\n")
        val moves = codeMoves(listOf(modified("src/OrderService.kt", before, 3 until 3 + validate.lines().size + map.lines().size),
            added("src/OrderValidator.kt", validate), added("src/OrderMapper.kt", map)))
        val opened = mutableListOf<List<String>>()
        val focused = mutableListOf<String>()
        val all = CodeMoveChart(moves, null, 900, { opened += it }, { focused += it }).apply { setSize(preferredSize) }
        assertEquals(1, all.nodes.count { it.left })
        assertEquals(2, all.links.size)
        assertEquals("src/OrderService.kt", all.nodes.single { it.left }.source)
        val fan = CodeMoveChart(moves, "src/OrderService.kt", 900, { opened += it }, { focused += it }).apply { setSize(preferredSize) }
        assertEquals(listOf("OrderValidator.kt", "OrderMapper.kt", "Stayed"), fan.nodes.filterNot { it.left }.map { it.label.substringAfter("  ") })
        assertTrue(fan.nodes.filterNot { it.left }.first().label.startsWith("50%"))
        listOf(all, fan).forEach { chart ->
            val graphics = BufferedImage(chart.width, chart.height, BufferedImage.TYPE_INT_ARGB).createGraphics()
            try { chart.paint(graphics) } finally { graphics.dispose() }
        }
    }
}
