package dev.insight.links

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.insight.graph.SymbolKind

class ProjectSymbolsTest : LightJavaCodeInsightFixtureTestCase() {
    fun `test names and declarations come from project sources`() {
        myFixture.addFileToProject("a/Worker.java", """
            package a;
            public class Worker {
                int retryCount;
                public void runJob() {}
            }
        """.trimIndent())
        myFixture.addFileToProject("b/Runner.java", """
            package b;
            public class Runner {
                public void runJob() {}
            }
        """.trimIndent())

        val names = ProjectSymbols.namesNow(project, filter = null)
        assertTrue("Worker" in names)
        assertTrue("runJob" in names)
        assertTrue("retryCount" in names)

        val found = ProjectSymbols.findNow(project, "runJob")
        assertEquals(2, found.size)
        assertTrue(found.all { it.kind == SymbolKind.FUNCTION })
        assertEquals(setOf(3, 4), found.map { it.line }.toSet())

        val ranked = rankSymbolCandidates(found, SymbolMention(0, 13, "runJob", listOf("Runner"), call = true))
        assertEquals(1, ranked.size)
        assertTrue(ranked.single().absolutePath.endsWith("b/Runner.java"))
    }
}
