package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Font
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.RepaintManager
import javax.swing.border.EmptyBorder
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.JTextComponent
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class SummaryTextLayoutTest : BasePlatformTestCase() {
    fun testEverySummaryLayoutStopsRequestingWorkWhenIdle() {
        val previous = RepaintManager.currentManager(null)
        val manager = SummaryRepaints()
        RepaintManager.setCurrentManager(manager)
        try {
            val tree = buildSummaryTree(listOf("src/a.kt", "src/b.kt", "docs/readme.md").map { path ->
                ReviewFile(path, "/repo/$path", null, "modified", "@@ -1 +1 @@\n-old\n+new\n", "1")
            })
            val summaries = ChangeSummaries(tree).summarize(tree.nodes.associate {
                it.id to "Summary words for ${it.label} that wrap onto several lines. ".repeat(10)
            })
            for (mode in ChangeSummariesMode.entries.filter { it != ChangeSummariesMode.DIFF }) for (cloud in listOf(false, true)) {
                val canvas = NoteCanvas({}, {}).apply {
                    setSize(1200, 700)
                    summariesView.mode = mode
                    summariesView.tagsCloud = cloud
                    summariesView.update(tree)
                    if (mode == ChangeSummariesMode.HIERARCHY) summariesView.selectLevel(0, tree)
                    if (mode == ChangeSummariesMode.DOCUMENT) summariesView.selected = tree.root.identity
                }
                val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                fun layoutAndPaint() { layoutTree(canvas); canvas.paint(graphics) }
                try {
                    canvas.showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
                    repeat(3) { layoutAndPaint() }
                    manager.reset()
                    repeat(10) { layoutAndPaint() }
                    assertEquals("$mode (cloud=$cloud) must stop requesting layout when idle", 0, manager.invalidations)
                    assertEquals("$mode (cloud=$cloud) must stop requesting repaints when idle", 0, manager.repaints)
                } finally { graphics.dispose(); canvas.dispose() }
            }
        } finally { RepaintManager.setCurrentManager(previous) }
    }

    fun testSummaryMeasurementUpdatesAfterContentAndAppearanceChanges() {
        val summary = WrappedSummaryText("Short summary.", 240, Font(Font.DIALOG, Font.PLAIN, 14), emptyMap())
        val short = summary.preferredSize
        summary.text = "Long summary words that need more space. ".repeat(20)
        val long = summary.preferredSize
        assertTrue(long.height > short.height)
        summary.font = summary.font.deriveFont(22f)
        val large = summary.preferredSize
        assertTrue(large.height > long.height)
        summary.border = EmptyBorder(10, 0, 10, 0)
        assertEquals(large.height + 20, summary.preferredSize.height)
        val beforeStyle = summary.preferredSize
        val attributes = SimpleAttributeSet().apply { StyleConstants.setFontSize(this, 32) }
        summary.styledDocument.setCharacterAttributes(0, summary.document.length, attributes, false)
        assertTrue(summary.preferredSize.height > beforeStyle.height)
        summary.document = DefaultStyledDocument().apply { insertString(0, "Short again.", null) }
        assertTrue(summary.preferredSize.height < long.height)
    }

    fun testMeasuringAndPaintingSummaryTextSettlesAtTheAllocatedWidth() {
        val previous = RepaintManager.currentManager(null)
        val manager = SummaryRepaints()
        RepaintManager.setCurrentManager(manager)
        try {
            for (weights in listOf(emptyMap(), mapOf("summary" to 1.0, "words" to 0.5))) {
                for (allocatedWidth in listOf(420, 421, 680)) {
                    val summary = WrappedSummaryText("Summary words that wrap onto several lines. ".repeat(20),
                        420, Font(Font.DIALOG, Font.PLAIN, 14), weights)
                    summary.border = EmptyBorder(8, 6, 8, 6)
                    val parent = JPanel(BorderLayout()).apply { add(summary) }
                    val preferred = summary.preferredSize
                    parent.setSize(allocatedWidth, preferred.height)
                    parent.doLayout()
                    val image = BufferedImage(allocatedWidth, preferred.height, BufferedImage.TYPE_INT_ARGB)
                    val graphics = image.createGraphics()
                    fun measureAndPaint() {
                        assertEquals(preferred, summary.preferredSize)
                        summary.paint(graphics)
                        summary.modelToView2D(summary.text.length / 2)
                    }
                    try {
                        repeat(3) { measureAndPaint() }
                        manager.reset()
                        repeat(10) { measureAndPaint() }
                        assertEquals("An idle summary must stop requesting layout at width $allocatedWidth (cloud=${weights.isNotEmpty()})",
                            0, manager.invalidations)
                        assertEquals("An idle summary must stop scheduling repaints at width $allocatedWidth (cloud=${weights.isNotEmpty()})",
                            0, manager.repaints)
                    } finally { graphics.dispose() }
                }
            }
        } finally { RepaintManager.setCurrentManager(previous) }
    }

    private class SummaryRepaints : RepaintManager() {
        var invalidations = 0
        var repaints = 0
        fun reset() { invalidations = 0; repaints = 0 }

        override fun addInvalidComponent(component: JComponent) {
            if (component is JTextComponent) invalidations++
            super.addInvalidComponent(component)
        }

        override fun addDirtyRegion(component: JComponent, x: Int, y: Int, width: Int, height: Int) {
            if (component is JTextComponent) repaints++
            super.addDirtyRegion(component, x, y, width, height)
        }
    }

    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }
}
