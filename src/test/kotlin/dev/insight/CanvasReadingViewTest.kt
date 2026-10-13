package dev.insight

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.callgraph.CanvasReadingView
import dev.insight.enrichment.*
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import javax.swing.JScrollPane

class CanvasReadingViewTest : BasePlatformTestCase() {
    private fun data() = CanvasData(CanvasDocument(listOf(
        CanvasLine("=== first ===", 0, "/src/Service.kt", 12, true),
        CanvasLine("fun first() { second() }", 0, "/src/Service.kt", 12),
        CanvasLine("=== second ===", 1, "/src/Service.kt", 24, true),
        CanvasLine("fun second() {}", 1, "/src/Service.kt", 24),
    )), listOf(
        CanvasTopic("Service>Entry>Handle request", listOf(CanvasRange(0, 1)), "Calls second."),
        CanvasTopic("Service>Worker>Finish request", listOf(CanvasRange(2, 3)), "Finishes the operation."),
    ))

    fun `test selection opens exact source in either reading mode`() {
        val opened = mutableListOf<CanvasLine>()
        val view = CanvasReadingView { opened += it }
        JScrollPane(view)
        view.data = data()
        view.selectTopic(1)
        view.actionMap.get("open").actionPerformed(ActionEvent(view, 0, "open"))
        assertEquals(24, opened.single().fileLine)
        assertEquals("/src/Service.kt", opened.single().filePath)
        view.summaryMode = true
        view.selectTopic(0)
        view.actionMap.get("open").actionPerformed(ActionEvent(view, 0, "open"))
        assertEquals(12, opened.last().fileLine)
        assertEquals(0, view.selectedTopic)
    }

    fun `test hierarchy modes zoom and disjoint ranges paint without losing topics`() {
        val view = CanvasReadingView { }
        JScrollPane(view)
        val value = data()
        view.data = value.copy(topics = listOf(
            CanvasTopic("Service>Shared", listOf(CanvasRange(0, 0), CanvasRange(3, 3)), "Shared concern."),
            CanvasTopic("Service>Middle", listOf(CanvasRange(1, 2)), "Middle concern."),
        ))
        for (mode in listOf(false, true)) for (depth in listOf(1, 3)) {
            view.summaryMode = mode
            view.depth = depth
            view.zoom = 1.5
            view.setSize(view.preferredSize)
            val bitmap = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = bitmap.createGraphics()
            try { view.paint(graphics) } finally { graphics.dispose() }
            view.selectTopic(99)
            assertEquals(1, view.selectedTopic)
            assertTrue(view.preferredSize.width > 500)
        }
        view.data = null
        view.setSize(view.preferredSize)
        val graphics = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_ARGB).createGraphics()
        try { view.paint(graphics) } finally { graphics.dispose() }
    }
}
