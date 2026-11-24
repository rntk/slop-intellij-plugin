package dev.insight.review

import dev.insight.codereview.CodeFile
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class ReviewCircleChartTest {
    private fun file(path: String, lines: Int) = CodeFile(path, "/repo/$path", "x\n".repeat(lines))

    private fun chart(vararg files: CodeFile, metric: LineChartMetric = LineChartMetric.LINES) =
        ReviewLineChart(buildSummaryTree(files.toList()), metric)

    @Test fun `circles nest the shown directory's entries, with file areas following the metric`() {
        val chart = chart(file("src/a.kt", 9), file("src/nested/b.kt", 4), file("README.md", 1))
        val root = packCircles(chart, chart.root)!!
        assertEquals(0.0, root.x, 1e-9)
        assertEquals(0.0, root.y, 1e-9)
        assertEquals(listOf("src", "README.md"), root.children.map { it.entry.path })
        val src = root.children.first()
        assertEquals(listOf("src/a.kt", "src/nested"), src.children.map { it.entry.path })
        val a = src.children.first()
        val b = src.children.last().children.single()
        assertEquals(sqrt(9.0) / sqrt(4.0), a.r / b.r, 1e-9)
        assertEquals(listOf(0, 1, 2, 3), root.flatten().map { it.depth }.distinct().sorted())
    }

    @Test fun `siblings never overlap and stay inside their parent`() {
        val files = (1..40).map { file("src/d${it % 5}/f$it.kt", 1 + it * 7 % 23) }.toTypedArray()
        val chart = chart(*files)
        val root = packCircles(chart, chart.root)!!
        fun check(parent: PackedCircle) {
            for (child in parent.children) {
                assertTrue("${child.entry.path} leaves ${parent.entry.path}",
                    hypot(child.x - parent.x, child.y - parent.y) + child.r <= parent.r + 1e-6)
                check(child)
            }
            for ((i, first) in parent.children.withIndex()) for (second in parent.children.drop(i + 1)) {
                assertTrue("${first.entry.path} overlaps ${second.entry.path}",
                    hypot(first.x - second.x, first.y - second.y) >= first.r + second.r - 1e-6)
            }
        }
        check(root)
    }

    @Test fun `hit finds the deepest circle and depth limits nesting`() {
        val chart = chart(file("a/b/c/d/deep.kt", 5), file("top.kt", 5))
        val root = packCircles(chart, chart.root, maxDepth = 2)!!
        assertEquals(2, root.flatten().maxOf { it.depth })
        val top = root.children.single { it.entry.path == "top.kt" }
        assertEquals("top.kt", root.hit(top.x, top.y)?.entry?.path)
        assertNull(root.hit(root.r * 2, 0.0))
        val inner = packCircles(chart, chart.directory("a/b"))!!
        assertEquals("a/b", inner.entry.path)
        assertEquals(listOf("a/b/c"), inner.children.map { it.entry.path })
    }

    @Test fun `empty entries take no space and an empty directory has no circles`() {
        val chart = chart(file("src/a.kt", 3), file("src/empty.kt", 0))
        val src = packCircles(chart, chart.root)!!
        assertEquals(listOf("src/a.kt"), src.flatten().filterNot { it.entry.directory }.map { it.entry.path })
        val empty = chart(file("empty.kt", 0))
        assertNull(packCircles(empty, empty.root))
    }
}
