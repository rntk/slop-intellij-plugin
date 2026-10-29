package dev.insight.diff

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.graph.SymbolKind

class SemanticMetricsTest : BasePlatformTestCase() {
    private fun file(path: String, before: String?, after: String?, status: String = "modified") =
        ReviewFile(path, "/missing/$path", null, status, "patch", path, before, after)

    private fun count(vararg files: ReviewFile): SemanticChanges =
        ApplicationManager.getApplication().runReadAction<SemanticChanges> { semanticChanges(project, files.toList()) }

    private fun assertMetrics(expected: Map<Metric, Int?>, actual: MetricVector?, what: String) {
        val shown = expected.keys.associateWith { actual?.get(it) }
        assertEquals("$what: $actual", expected, shown)
    }

    private val kotlinBefore = """
        package demo

        import kotlin.math.max

        class Calc {
            fun add(a: Int, b: Int): Int = a + b

            fun old() = 1
        }
    """.trimIndent() + "\n"

    private val kotlinAfter = """
        package demo

        import kotlin.math.max
        import kotlin.math.min

        /** Calculates. */
        class Calc {
            fun add(a: Int, b: Int): Int = a + b

            // TODO tidy up
            @Suppress("UNUSED")
            fun grade(score: Int, bonus: Int?): String {
                val total = score + (bonus ?: 0)
                var label = "none"
                if (total > 90 && score > 50) {
                    label = "A"
                } else if (total > 42) {
                    for (i in 0 until 3) {
                        if (i == 2) break
                    }
                    label = "B"
                } else {
                    label = "C"
                }
                when (total) {
                    1 -> return "one"
                    2, 3 -> return "few"
                    else -> {}
                }
                try {
                    listOf(1).map { it * 2 }
                } catch (e: Exception) {
                    throw IllegalStateException(e)
                }
                return label + bonus!!
            }
        }
    """.trimIndent() + "\n"

    fun testCountsKotlinDeclarationsAndKeepsOnlyChangedOnes() {
        val result = count(file("src/Calc.kt", kotlinBefore, kotlinAfter)).files.single()
        assertNull(result.skipped)
        val byName = result.declarations.associateBy { it.qualifiedName }
        assertEquals("unchanged add is dropped", setOf("Calc", "Calc.grade", "Calc.old"), byName.keys)
        val grade = byName.getValue("Calc.grade")
        assertNull(grade.before)
        assertEquals(SymbolKind.FUNCTION, grade.kind)
        assertEquals("the preceding comment belongs to the function", 10, grade.line)
        assertMetrics(mapOf(
            Metric.FUNCTIONS to 1, Metric.BRANCHES to 4, Metric.LOGICAL_OPERATORS to 1, Metric.LOOPS to 1, Metric.CASES to 2,
            Metric.CATCHES to 1, Metric.THROWS to 1, Metric.RETURNS to 3, Metric.JUMPS to 1, Metric.LAMBDAS to 1,
            Metric.CYCLOMATIC to 10, Metric.COGNITIVE to 12, Metric.MAX_NESTING to 3, Metric.PARAMETERS to 2, Metric.MAX_PARAMETERS to 2,
            Metric.MUTABLE_VARIABLES to 1, Metric.IMMUTABLE_VARIABLES to 1, Metric.NUMBER_LITERALS to 8, Metric.STRING_LITERALS to 7,
            Metric.NULL_ASSERTIONS to 1, Metric.PUBLIC_DECLARATIONS to 1, Metric.MAX_FUNCTION_LINES to 27,
        ), grade.after, "grade")
        assertNull("own values exclude nested functions", byName.getValue("Calc").after!![Metric.BRANCHES])
        assertEquals(1, byName.getValue("Calc").value(Metric.DOCUMENTED, true))
        assertEquals(0, byName.getValue("Calc").value(Metric.DOCUMENTED, false))
        assertNull(byName.getValue("Calc.old").after)

        assertMetrics(mapOf(Metric.IMPORTS to 1, Metric.FUNCTIONS to 2, Metric.CLASSES to 1, Metric.CYCLOMATIC to 2,
            Metric.PUBLIC_DECLARATIONS to 3, Metric.TODOS to null, Metric.BRANCHES to null), result.before, "before")
        assertMetrics(mapOf(Metric.IMPORTS to 2, Metric.FUNCTIONS to 2, Metric.CLASSES to 1, Metric.CYCLOMATIC to 11,
            Metric.PUBLIC_DECLARATIONS to 3, Metric.TODOS to 1, Metric.SUPPRESSIONS to 1, Metric.COMMENT_LINES to 2,
            Metric.MAX_NESTING to 3, Metric.DOCUMENTED to 1), result.after, "after")
        assertEquals(kotlinAfter.lines().count { it.isNotBlank() && !it.trimStart().startsWith("/") }, result.after!![Metric.CODE_LINES])
        assertEquals(9, result.delta(Metric.CYCLOMATIC))
    }

    fun testCountsJavaAndAggregatesFiles() {
        val before = """
            class Shop {
                int total(int[] prices) {
                    int sum = 0;
                    for (int p : prices) sum += p;
                    return sum;
                }
            }
        """.trimIndent() + "\n"
        val after = """
            import java.util.List;

            public class Shop {
                public static final int LIMIT = 100;

                int total(int[] prices) {
                    int sum = 0;
                    for (int p : prices) sum += p;
                    return sum;
                }

                /** Shown name. */
                @Override
                public String toString() {
                    return "Shop";
                }

                public int discount(int amount, boolean vip, String code) {
                    final int base = amount > LIMIT ? 10 : 5;
                    switch (code) {
                        case "A": return base * 2;
                        case "B":
                        case "C": return base;
                        default: break;
                    }
                    while (vip && amount > 0 || code == null) {
                        amount--;
                    }
                    return Math.max(base, amount);
                }
            }
        """.trimIndent() + "\n"
        val changes = count(
            file("Shop.java", before, after),
            file("Added.java", "", "class Added { void run() { if (true) run(); } }\n", "added"),
            file("image.png", null, null),
            file("big.java", "", "class Big {}\n" + "//\n".repeat(400_000), "added"),
        )
        val shop = changes.files.first { it.path == "Shop.java" }
        val byName = shop.declarations.associateBy { it.qualifiedName }
        assertEquals("unchanged total is dropped", setOf("Shop", "Shop.toString", "Shop.discount"), byName.keys)
        assertMetrics(mapOf(
            Metric.BRANCHES to 1, Metric.CASES to 3, Metric.LOOPS to 1, Metric.LOGICAL_OPERATORS to 2, Metric.RETURNS to 3, Metric.JUMPS to 1,
            Metric.CYCLOMATIC to 8, Metric.COGNITIVE to 5, Metric.MAX_NESTING to 1, Metric.PARAMETERS to 3, Metric.MAX_PARAMETERS to 3,
            Metric.IMMUTABLE_VARIABLES to 1, Metric.MUTABLE_VARIABLES to null, Metric.NUMBER_LITERALS to 3, Metric.STRING_LITERALS to 3,
            Metric.PUBLIC_DECLARATIONS to 1,
        ), byName.getValue("Shop.discount").after, "discount")
        assertMetrics(mapOf(Metric.OVERRIDES to 1, Metric.DOCUMENTED to 1, Metric.RETURNS to 1, Metric.CYCLOMATIC to 1),
            byName.getValue("Shop.toString").after, "toString")
        assertMetrics(mapOf(Metric.CONSTANTS to 1, Metric.PROPERTIES to 1, Metric.PUBLIC_DECLARATIONS to 2), byName.getValue("Shop").after, "Shop")
        assertMetrics(mapOf(Metric.LOOPS to 1, Metric.MUTABLE_VARIABLES to 1, Metric.PUBLIC_DECLARATIONS to null, Metric.CYCLOMATIC to 2,
            Metric.STATEMENTS to 4, Metric.IMPORTS to null), shop.before, "before")
        assertEquals(1, shop.after!![Metric.IMPORTS])

        val added = changes.files.first { it.path == "Added.java" }
        assertEquals(emptyMap<Metric, Int>(), added.before)
        assertEquals(listOf("Added", "Added.run"), added.declarations.map { it.qualifiedName })
        assertEquals("Content unavailable (binary, unreadable or too large)", changes.files.first { it.path == "image.png" }.skipped)
        assertEquals("Not analyzed: over the 1 MB size limit", changes.files.first { it.path == "big.java" }.skipped)

        assertEquals(shop.after[Metric.CYCLOMATIC]!! + 2, changes.after[Metric.CYCLOMATIC])
        assertEquals(3, changes.after[Metric.MAX_PARAMETERS])
        assertEquals(listOf("Shop.java", "Added.java"), changes.filesChanging(Metric.BRANCHES).map { it.path })
    }

    fun testGenericFallbackCountsPythonGoAndJavaScript() {
        val python = """
            import os

            def check(xs, flag=False):
                ${"\"\"\""}Checks things.${"\"\"\""}
                total = 0
                for x in xs:
                    if x > 10 and flag:
                        total += x
                    elif x < 0:
                        raise ValueError("neg")
                    else:
                        continue
                try:
                    os.remove("f")
                except OSError:
                    pass
                return total
        """.trimIndent() + "\n"
        val go = """
            package main

            import "fmt"

            func Run(xs []int) int {
                n := 0
                for _, x := range xs {
                    if x > 1 || x < -1 {
                        n++
                    } else if x == 0 {
                        continue
                    }
                }
                switch n {
                case 1:
                    fmt.Println("one")
                default:
                    return 0
                }
                return n
            }
        """.trimIndent() + "\n"
        val js = """
            import { a } from "./a";

            export function pick(items, flag) {
                const result = [];
                let count = 0;
                for (const item of items) {
                    if (flag && item.ok) {
                        result.push(item);
                    }
                }
                try {
                    items.forEach(i => count++);
                } catch (e) {
                    throw e;
                }
                return flag ? result : [];
            }
        """.trimIndent() + "\n"
        val changes = count(file("check.py", "", python, "added"), file("run.go", "", go, "added"), file("pick.js", "", js, "added"))
        fun function(path: String, name: String) = changes.files.first { it.path == path }.declarations.first { it.qualifiedName == name }.after
        assertMetrics(mapOf(Metric.BRANCHES to 2, Metric.LOOPS to 1, Metric.LOGICAL_OPERATORS to 1, Metric.CATCHES to 1, Metric.THROWS to 1,
            Metric.RETURNS to 1, Metric.JUMPS to 1, Metric.CYCLOMATIC to 6, Metric.COGNITIVE to 7, Metric.MAX_NESTING to 2,
            Metric.DOCUMENTED to 1, Metric.PARAMETERS to 2, Metric.NUMBER_LITERALS to 1, Metric.PUBLIC_DECLARATIONS to 1),
            function("check.py", "check"), "python")
        assertEquals(1, changes.files.first { it.path == "check.py" }.after!![Metric.IMPORTS])
        assertMetrics(mapOf(Metric.BRANCHES to 2, Metric.LOOPS to 1, Metric.LOGICAL_OPERATORS to 1, Metric.CASES to 1, Metric.RETURNS to 2,
            Metric.JUMPS to 1, Metric.CYCLOMATIC to 6, Metric.MAX_NESTING to 2, Metric.PARAMETERS to 1, Metric.PUBLIC_DECLARATIONS to 1),
            function("run.go", "Run"), "go")
        assertMetrics(mapOf(Metric.BRANCHES to 2, Metric.LOOPS to 1, Metric.LOGICAL_OPERATORS to 1, Metric.CATCHES to 1, Metric.THROWS to 1,
            Metric.RETURNS to 1, Metric.LAMBDAS to 1, Metric.CYCLOMATIC to 6, Metric.MUTABLE_VARIABLES to 1, Metric.IMMUTABLE_VARIABLES to 2,
            Metric.PARAMETERS to 3, Metric.MAX_PARAMETERS to 2, Metric.PUBLIC_DECLARATIONS to 1), function("pick.js", "pick"), "javascript")
    }
}
