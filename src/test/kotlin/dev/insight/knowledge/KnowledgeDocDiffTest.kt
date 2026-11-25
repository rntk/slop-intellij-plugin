package dev.insight.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeDocDiffTest {
    @Test fun identicalTextsGiveEmptyDiff() {
        assertEquals("", KnowledgeDocDiff.unified("a\nb\n", "a\r\nb\n"))
    }

    @Test fun changedLineShowsRemovalAndAdditionWithContext() {
        val diff = KnowledgeDocDiff.unified("one\ntwo\nthree\n", "one\n2\nthree\n", "flows/x.md")
        assertEquals(
            "--- a/flows/x.md\n+++ b/flows/x.md\n@@ -1,3 +1,3 @@\n one\n-two\n+2\n three\n", diff)
    }

    @Test fun missingDocIsAllAdditions() {
        val diff = KnowledgeDocDiff.unified(null, "# T\n\ntext\n", "flows/x.md")
        assertEquals("--- /dev/null\n+++ b/flows/x.md\n@@ -0,0 +1,3 @@\n+# T\n+\n+text\n", diff)
    }

    @Test fun distantChangesFormSeparateHunks() {
        val old = (1..20).joinToString("\n") { "l$it" }
        val new = old.replace("l2\n", "L2\n").replace("l19", "L19")
        val diff = KnowledgeDocDiff.unified(old, new, context = 1)
        assertEquals(2, diff.lines().count { it.startsWith("@@") })
        assertTrue(diff.contains("-l19\n+L19"))
        assertTrue(diff.contains("-l2\n+L2"))
    }

    @Test fun insertedAndDeletedLines() {
        val diff = KnowledgeDocDiff.unified("a\nb\nc\n", "a\nc\nd\n", context = 0)
        assertTrue(diff.contains("-b\n"))
        assertTrue(diff.contains("+d\n"))
        assertTrue(!diff.contains(" a\n"))
    }
}
