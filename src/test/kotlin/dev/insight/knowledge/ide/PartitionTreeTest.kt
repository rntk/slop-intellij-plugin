package dev.insight.knowledge.ide

import dev.insight.knowledge.KnowledgePartition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionTreeTest {
    @Test fun `one partition per directory with its direct files`() {
        val parts = partitionFiles(listOf("a/b/X.kt", "a/Y.kt", "Z.md", "a/b/W.kt"), 10)
        assertEquals(
            listOf(
                KnowledgePartition("", listOf("Z.md"), null),
                KnowledgePartition("a", listOf("a/Y.kt"), ""),
                KnowledgePartition("a/b", listOf("a/b/W.kt", "a/b/X.kt"), "a"),
            ),
            parts,
        )
    }

    @Test fun `large directories split into balanced chunks sharing the area`() {
        val files = (1..10).map { "d/f%02d.kt".format(it) }
        val parts = partitionFiles(files, 4)
        assertEquals(listOf(4, 3, 3), parts.map { it.files.size })
        assertTrue(parts.all { it.area == "d" && it.parent == "" })
        assertEquals(files, parts.flatMap { it.files })
    }

    @Test fun `exactly full directories stay whole`() {
        assertEquals(1, partitionFiles((1..4).map { "d/$it.kt" }, 4).size)
        assertEquals(2, partitionFiles((1..5).map { "d/$it.kt" }, 4).size)
    }

    @Test fun `duplicates and odd spellings collapse`() {
        val parts = partitionFiles(listOf("./a/X.kt", "a/X.kt", "a\\X.kt", ""), 5)
        assertEquals(listOf("a/X.kt"), parts.single().files)
    }

    @Test fun `empty scope has no partitions`() {
        assertTrue(partitionFiles(emptyList(), 3).isEmpty())
    }

    @Test fun `generated and build directories are excluded`() {
        assertTrue(isExcludedPath("build/classes/X.class"))
        assertTrue(isExcludedPath("app/node_modules/x/index.js"))
        assertTrue(isExcludedPath("generated", isDirectory = true))
        assertFalse(isExcludedPath("src/main/Build.kt"))
        assertFalse(isExcludedPath("build"))
    }
}
