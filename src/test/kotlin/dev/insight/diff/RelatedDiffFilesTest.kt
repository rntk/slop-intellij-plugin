package dev.insight.diff

import org.junit.Assert.assertEquals
import org.junit.Test

class RelatedDiffFilesTest {
    @Test fun `walks incoming and transitive relations once and keeps parent anchors`() {
        val connections = listOf(
            DiffFileConnection("Caller", "Root", 10, 20),
            DiffFileConnection("Caller", "Leaf", 30, 40),
            DiffFileConnection("Leaf", "Caller", 50, 60),
            DiffFileConnection("Root", "Root", 0, 0),
            DiffFileConnection("Unrelated", "Other", 0, 0),
        )
        assertEquals(listOf(RelatedDiffFile("Caller", "Root", 20), RelatedDiffFile("Leaf", "Caller", 30)),
            relatedDiffFiles("Root", connections))
        assertEquals(emptyList<RelatedDiffFile>(), relatedDiffFiles("Isolated", connections))
    }
}
