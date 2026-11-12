package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewSource
import java.awt.event.MouseEvent

class DocumentSummariesTest : BasePlatformTestCase() {
    fun testFullSummaryAppearsOnlyWhileFileIsHoveredAndCanExceedSectionHeight() {
        val file = CodeFile("Entry.kt", "/repo/Entry.kt", "fun run() {}")
        val panel = object : DocumentColumnsPanel(1.0) {
            init {
                layoutDocument(listOf(file), mapOf(file.path to "A complete summary.\n".repeat(300)),
                    emptyList(), 0, 1200)
            }
            override fun openFile(file: ReviewSource) {}
            override fun showFile(file: ReviewSource) {}
            override fun isSelected(item: RailItem) = false
            override fun select(item: RailItem) {}
        }
        val section = panel.components.single { it.name == "file:${file.path}" }
        val summary = panel.components.single { it.name == "summary:${file.path}" }
        val idleHeight = panel.preferredSize.height
        assertFalse(summary.isVisible)
        section.dispatchEvent(MouseEvent(section, MouseEvent.MOUSE_ENTERED, 0, 0, 5, 5, 0, false))
        assertTrue(summary.isVisible)
        assertTrue(summary.height > section.height)
        assertTrue(panel.preferredSize.height > idleHeight)
        section.dispatchEvent(MouseEvent(section, MouseEvent.MOUSE_EXITED, 0, 0, -5, -5, 0, false))
        assertFalse(summary.isVisible)
        assertEquals(idleHeight, panel.preferredSize.height)
    }
}
