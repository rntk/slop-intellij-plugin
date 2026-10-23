package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.callgraph.SymbolKind
import java.awt.Container
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel

class SemanticChangesViewTest : BasePlatformTestCase() {
    private fun file(path: String) = ReviewFile(path, "/$path", null, "modified", "", path)

    private val modified = DeclarationMetrics(
        "a.kt", "A.run", SymbolKind.FUNCTION, 5,
        mapOf(Metric.CYCLOMATIC to 3, Metric.BRANCHES to 2, Metric.LOOPS to 1, Metric.CODE_LINES to 10),
        mapOf(Metric.CYCLOMATIC to 6, Metric.BRANCHES to 5, Metric.CODE_LINES to 12),
    )
    private val added = DeclarationMetrics("a.kt", "A.fresh", SymbolKind.FUNCTION, 20, null, mapOf(Metric.CYCLOMATIC to 2, Metric.BRANCHES to 1, Metric.CODE_LINES to 4))
    private val removed = DeclarationMetrics("a.kt", "A.gone", SymbolKind.FUNCTION, 30, mapOf(Metric.CYCLOMATIC to 4, Metric.CODE_LINES to 6), null)
    private val owner = DeclarationMetrics("a.kt", "A", SymbolKind.CLASS, 1, mapOf(Metric.CYCLOMATIC to 1), mapOf(Metric.CYCLOMATIC to 9))

    private val changes = SemanticChanges(listOf(
        FileMetrics(
            "a.kt", "modified",
            mapOf(Metric.BRANCHES to 2, Metric.LOOPS to 1, Metric.CYCLOMATIC to 3, Metric.CODE_LINES to 20, Metric.COMMENT_LINES to 0),
            mapOf(Metric.BRANCHES to 5, Metric.CYCLOMATIC to 6, Metric.CODE_LINES to 22, Metric.COMMENT_LINES to 0),
            listOf(owner, modified, added, removed),
        ),
        FileMetrics("b.kt", "added", null, mapOf(Metric.TODOS to 2, Metric.CODE_LINES to 3), emptyList()),
        FileMetrics("logo.png", "modified", null, null, emptyList(), skipped = "binary file"),
    ))

    fun testConstructChangesSumDeclarationDeltasAndFallBackToFileDeltas() {
        val words = constructChanges(changes)
        assertEquals(listOf(
            WordChange("if", 0, 4, setOf("a.kt")),
            WordChange("TODO", 0, 2, setOf("b.kt")),
            WordChange("loop", 1, 0, setOf("a.kt")),
        ), words.words)
        assertEquals(1, words.removedWords)
        assertEquals(6, words.addedWords)
        assertTrue("unchanged and non-construct metrics are omitted", words.words.none { it.word == "cyclomatic complexity" || it.word == "return" })
        assertTrue(constructChanges(SemanticChanges(emptyList())).words.isEmpty())
    }

    fun testCanvasShowsCardInItsOwnTabWithThreeModesAndKeepsTheMode() {
        val opened = mutableListOf<String>()
        val directories = mutableListOf<List<String>>()
        val files = listOf(file("a.kt"), file("b.kt"))
        val canvas = NoteCanvas({ opened += it }, {}, openDirectoryDiff = { directories += it.map { f -> f.path } }).apply { setSize(1200, 800) }
        try {
            canvas.showNotes(emptyList(), wordFiles = files, semantic = SemanticResult.Ready(changes))
            assertEquals(1, canvas.tabs.tabCount)
            assertEquals("Semantic changes", canvas.tabs.getTitleAt(0))
            val modes = descendants(canvas).filterIsInstance<AbstractButton>().filter { it.name?.startsWith("semantic-mode:") == true }
            assertEquals(listOf("Metrics", "Complexity", "Constructs"), modes.map { it.text })
            assertEquals("1 file skipped", descendants(canvas).filterIsInstance<JLabel>().single { it.name == "semantic-skipped" }.text)
            assertTrue(descendants(canvas).filterIsInstance<JLabel>().single { it.name == "semantic-skipped" }.toolTipText.contains("logo.png"))

            val table = descendants(canvas).filterIsInstance<SemanticMetricsTable>().single()
            fun click(metric: Metric) {
                val index = table.rows().indexOfFirst { it.metric == metric }
                val bounds = table.rowBounds(index)
                table.dispatchEvent(MouseEvent(table, MouseEvent.MOUSE_CLICKED, 0, 0, bounds.centerX.toInt(), bounds.centerY.toInt(), 1, false, MouseEvent.BUTTON1))
            }
            click(Metric.TODOS)
            assertEquals(listOf("b.kt"), opened)
            click(Metric.CODE_LINES)
            assertEquals(listOf(listOf("a.kt", "b.kt")), directories)

            modes[1].doClick()
            assertTrue(descendants(canvas).none { it is SemanticMetricsTable })
            assertEquals(Metric.CYCLOMATIC, descendants(canvas).filterIsInstance<SemanticComplexityChart>().single().metric)
            @Suppress("UNCHECKED_CAST")
            val selector = descendants(canvas).filterIsInstance<JComboBox<*>>().single { it.name == "semantic-metric" } as JComboBox<Metric>
            assertEquals(listOf(Metric.CYCLOMATIC, Metric.COGNITIVE, Metric.MAX_NESTING, Metric.CODE_LINES, Metric.PARAMETERS, Metric.FAN_OUT),
                (0 until selector.itemCount).map { selector.getItemAt(it) })
            selector.selectedItem = Metric.CODE_LINES
            assertEquals(Metric.CODE_LINES, descendants(canvas).filterIsInstance<SemanticComplexityChart>().single().metric)

            descendants(canvas).filterIsInstance<AbstractButton>().single { it.name == "semantic-mode:CONSTRUCTS" }.doClick()
            assertEquals(listOf("if", "TODO", "loop"), descendants(canvas).filterIsInstance<WordChangeChart>().single().changes.words.map { it.word })
            canvas.showNotes(emptyList(), wordFiles = files, semantic = SemanticResult.Ready(changes))
            assertEquals("the mode survives a rebuild", 1, descendants(canvas).filterIsInstance<WordChangeChart>().size)
        } finally { canvas.dispose() }
    }

    fun testCardShowsCountingAndErrorPlaceholders() {
        val canvas = NoteCanvas({}, {}).apply { setSize(1200, 800) }
        try {
            canvas.showNotes(emptyList(), semantic = SemanticResult.Counting)
            assertEquals("Counting…", descendants(canvas).filterIsInstance<JLabel>().single { it.name == "semantic-counting" }.text)
            canvas.showNotes(emptyList(), semantic = SemanticResult.Failed("boom"))
            assertEquals("Could not count semantic changes: boom", descendants(canvas).filterIsInstance<JLabel>().single { it.name == "semantic-error" }.text)
            assertEquals("Semantic changes", canvas.tabs.getTitleAt(0))
            canvas.showNotes(emptyList())
            assertTrue(descendants(canvas).none { it.name == "semantic-error" })
        } finally { canvas.dispose() }
    }

    fun testTableHidesZeroRowsAndShowsSignedDeltas() {
        val table = SemanticMetricsTable(changes, 900) {}.apply { setSize(preferredSize) }
        table.paint(BufferedImage(table.width, table.height, BufferedImage.TYPE_INT_ARGB).createGraphics())
        val rows = table.rows()
        assertEquals(listOf("Structure", "Control flow", "Complexity", "Hygiene"), rows.filter { it.metric == null }.map { it.group.label })
        val metrics = rows.filter { it.metric != null }
        assertEquals(listOf("code lines", "if", "loop", "cyclomatic complexity", "TODO"), metrics.map { it.metric!!.label })
        assertEquals(listOf("+5", "+3", "−1", "+3", "+2"), metrics.map { it.deltaText })
        assertEquals(listOf(20 to 25, 2 to 5, 1 to 0, 3 to 6, 0 to 2), metrics.map { it.before to it.after })
        assertTrue("comment lines are 0 on both sides", rows.none { it.metric == Metric.COMMENT_LINES })
        val tooltip = table.getToolTipText(MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, 10, table.rowBounds(rows.indexOfFirst { it.metric == Metric.BRANCHES }).centerY.toInt(), 0, false))!!
        assertTrue(tooltip, tooltip.contains(Metric.BRANCHES.description))
        assertNull(table.getToolTipText(MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, 10, table.rowBounds(0).centerY.toInt(), 0, false)))
        assertEquals("0", signed(0))
        assertEquals("−2", signed(-2))
    }

    fun testApproximateMetricsAreMarkedInTheTooltip() {
        val fanOut = SemanticChanges(listOf(FileMetrics("a.kt", "modified", mapOf(Metric.FAN_OUT to 1), mapOf(Metric.FAN_OUT to 4), emptyList())))
        val table = SemanticMetricsTable(fanOut, 900) {}
        val y = table.rowBounds(1).centerY.toInt()
        assertTrue(table.getToolTipText(MouseEvent(table, MouseEvent.MOUSE_MOVED, 0, 0, 10, y, 0, false))!!.contains("≈"))
    }

    fun testScatterPlacesAddedFunctionsOnXZeroAndRemovedOnYZeroAndHitTestsPoints() {
        val opened = mutableListOf<DeclarationMetrics>()
        val chart = SemanticComplexityChart(changes, Metric.CYCLOMATIC, 900, 500) { opened += it }.apply { setSize(preferredSize) }
        chart.paint(BufferedImage(chart.width, chart.height, BufferedImage.TYPE_INT_ARGB).createGraphics())
        val points = chart.placedPoints().associateBy { it.declaration.qualifiedName }
        assertEquals("only functions are plotted", setOf("A.run", "A.fresh", "A.gone"), points.keys)
        assertEquals(3 to 6, points.getValue("A.run").let { it.before to it.after })
        assertTrue(points.getValue("A.fresh").added && points.getValue("A.fresh").before == 0 && points.getValue("A.fresh").after == 2)
        assertTrue(points.getValue("A.gone").removed && points.getValue("A.gone").after == 0 && points.getValue("A.gone").before == 4)
        val left = points.values.minOf { it.center.x }
        val bottom = points.values.maxOf { it.center.y }
        assertEquals(left, points.getValue("A.fresh").center.x)
        assertEquals(bottom, points.getValue("A.gone").center.y)
        assertTrue(points.getValue("A.run").center.y < points.getValue("A.gone").center.y)
        assertTrue("larger functions get larger points", points.getValue("A.run").radius > points.getValue("A.fresh").radius)

        val gone = points.getValue("A.gone")
        assertSame(gone, chart.at(gone.center))
        assertNull(chart.at(java.awt.Point(2, 2)))
        val click = MouseEvent(chart, MouseEvent.MOUSE_CLICKED, 0, 0, gone.center.x, gone.center.y, 1, false, MouseEvent.BUTTON1)
        chart.dispatchEvent(click)
        assertEquals(listOf(removed), opened)
        val tooltip = chart.getToolTipText(click)!!
        assertTrue(tooltip, tooltip.contains("A.gone") && tooltip.contains("a.kt:30") && tooltip.contains("4 → –"))
    }

    fun testScatterSeparatesFunctionsWithTheSameValuesDeterministically() {
        val twins = (1..6).map { DeclarationMetrics("a.kt", "T.f$it", SymbolKind.FUNCTION, it, mapOf(Metric.CYCLOMATIC to 2), mapOf(Metric.CYCLOMATIC to 5)) }
        val data = SemanticChanges(listOf(FileMetrics("a.kt", "modified", null, null, twins)))
        fun centers() = SemanticComplexityChart(data, Metric.CYCLOMATIC, 900, 500) {}.placedPoints().map { it.declaration.qualifiedName to it.center }
        assertEquals(6, centers().map { it.second }.toSet().size)
        assertEquals(centers(), centers())
    }

    fun testScatterShowsEmptyStateWithoutFunctions() {
        val data = SemanticChanges(listOf(FileMetrics("a.kt", "modified", null, null, listOf(owner))))
        val canvas = NoteCanvas({}, {}).apply { setSize(1200, 800) }
        try {
            canvas.showNotes(emptyList(), semantic = SemanticResult.Ready(data))
            descendants(canvas).filterIsInstance<AbstractButton>().single { it.name == "semantic-mode:COMPLEXITY" }.doClick()
            assertTrue(descendants(canvas).none { it is SemanticComplexityChart })
            assertTrue(descendants(canvas).filterIsInstance<JLabel>().any { it.text == "No functions in the changed files." })
        } finally { canvas.dispose() }
    }

    private fun descendants(component: Container): List<java.awt.Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }
}
