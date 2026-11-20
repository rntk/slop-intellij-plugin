package dev.insight.diff

import dev.insight.canvas.ConnectedFile
import dev.insight.canvas.FileConnection
import org.junit.Assert.assertEquals
import org.junit.Test

class RelatedDiffFilesTest {
    @Test fun `walks incoming and transitive relations once and keeps parent anchors`() {
        val connections = listOf(
            FileConnection("Caller", "Root", 10, 20),
            FileConnection("Caller", "Leaf", 30, 40),
            FileConnection("Leaf", "Caller", 50, 60),
            FileConnection("Root", "Root", 0, 0),
            FileConnection("Unrelated", "Other", 0, 0),
        )
        assertEquals(listOf(ConnectedFile("Caller", "Root", 20), ConnectedFile("Leaf", "Caller", 30)),
            relatedDiffFiles("Root", connections))
        assertEquals(emptyList<ConnectedFile>(), relatedDiffFiles("Isolated", connections))
    }
}
