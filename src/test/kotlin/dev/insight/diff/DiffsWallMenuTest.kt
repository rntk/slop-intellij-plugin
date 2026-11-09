package dev.insight.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewSummariesMode
import dev.insight.review.ReviewTopics
import dev.insight.review.buildSummaryTree
import dev.insight.review.ui.CanvasState
import dev.insight.review.ui.ChangeSummariesViewState
import dev.insight.review.ui.ChangeSummaryActions
import dev.insight.review.ui.ChangeTopicsMode
import dev.insight.review.ui.NoteCanvas
import dev.insight.review.ui.SummariesCard
import dev.insight.review.ui.SummaryCanvasActions
import dev.insight.review.ui.TopicsCard
import java.awt.Component
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/** Every view of the summaries and topics cards offers "Open on diffs wall" for entries that hold several files. */
class DiffsWallMenuTest : BasePlatformTestCase() {
    private val label = "Open on diffs wall"
    private val files = listOf("src/a.kt", "src/nested/b.kt", "docs/c.kt", "docs/d.kt").map {
        ReviewFile(it, "/repo/$it", null, "modified", "-old\n+new\n", "1")
    }
    private val tree = buildSummaryTree(files)
    private val summaries = ReviewSummaries(tree, tree.nodes.associate { it.id to "Summary of ${it.identity}." })

    private fun descendants(root: Container): List<Component> = root.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }

    private fun layoutTree(component: Container) {
        component.doLayout()
        component.components.filterIsInstance<Container>().forEach(::layoutTree)
    }

    private fun wallItems(canvas: NoteCanvas): List<JMenuItem> = descendants(canvas).filterIsInstance<JComponent>()
        .mapNotNull { it.componentPopupMenu }.distinct()
        .flatMap { popup -> popup.components.filterIsInstance<JMenuItem>().filter { it.text == label } }

    private fun wallItems(popup: JPopupMenu) = popup.components.filterIsInstance<JMenuItem>().filter { it.text == label }

    private fun summariesCanvas(mode: ReviewSummariesMode, wall: ((List<ReviewSource>, String) -> Unit)?) =
        NoteCanvas({}, {}, project = project, summaryActions = SummaryCanvasActions(openOnWall = wall)).apply {
            setSize(1600, 900)
            summariesView.mode = mode
            showNotes(CanvasState(emptyList(), summaries = SummariesCard(summaries, false, true)))
            layoutTree(this)
        }

    fun testEverySummariesViewOffersTheWallForFolders() {
        for (mode in listOf(ReviewSummariesMode.OUTLINE, ReviewSummariesMode.HIERARCHY, ReviewSummariesMode.DOCUMENT,
            ReviewSummariesMode.SPLIT, ReviewSummariesMode.TREE)) {
            val opened = mutableListOf<Pair<Set<String>, String>>()
            val canvas = summariesCanvas(mode) { files, title -> opened += files.map { it.path }.toSet() to title }
            try {
                val items = wallItems(canvas)
                assertTrue("$mode offers the wall", items.isNotEmpty())
                items.forEach { it.doClick() }
                assertEquals("$mode: ${opened.map { it.second }}", items.size, opened.size)
                val src = opened.firstOrNull { it.second == "src" }
                assertEquals("$mode: ${opened.map { it.second }}", setOf("src/a.kt", "src/nested/b.kt"), src?.first)
                assertTrue("$mode keeps the whole change as a title", opened.any { it.first.size == files.size })
            } finally { canvas.dispose() }
        }
    }

    fun testWallIsAbsentWithoutTheCallback() {
        for (mode in listOf(ReviewSummariesMode.OUTLINE, ReviewSummariesMode.HIERARCHY, ReviewSummariesMode.DOCUMENT,
            ReviewSummariesMode.SPLIT, ReviewSummariesMode.TREE)) {
            val canvas = summariesCanvas(mode, null)
            try { assertTrue(mode.toString(), wallItems(canvas).isEmpty()) } finally { canvas.dispose() }
        }
    }

    fun testEntryMenuCarriesTheWallAloneAndBeforeOtherActions() {
        val opened = mutableListOf<Pair<String, String>>()
        val actions = ChangeSummaryActions({}, {}, {}, {}, toChat = { }, openOnWall = { opened += it.identity to diffsTitle(it.path) })
        for (node in tree.nodes) {
            val popup = JPopupMenu()
            actions.addEntryItems(popup, node)
            val wall = wallItems(popup)
            assertEquals(node.identity, if (node.file == null) 1 else 0, wall.size)
            wall.forEach { assertEquals("summary-action:$label", it.name) }
        }
        wallItems(JPopupMenu().also { actions.addEntryItems(it, tree.nodes.single { n -> n.identity == "directory:src" }) }).single().doClick()
        assertEquals(listOf("directory:src" to "src"), opened)
        // The wall alone is enough to install a menu on a component.
        val only = ChangeSummaryActions({}, {}, {}, {}, openOnWall = { })
        val label = javax.swing.JLabel()
        only.installEntryMenu(label, tree.nodes.single { it.identity == "directory:src" })
        assertEquals(1, wallItems(label.componentPopupMenu).size)
        val none = javax.swing.JLabel()
        ChangeSummaryActions({}, {}, {}, {}).installEntryMenu(none, tree.root)
        assertNull(none.componentPopupMenu)
    }

    private fun diffsTitle(path: String) = path

    private fun topicsCanvas(mode: ChangeTopicsMode, wall: ((List<ReviewSource>, String) -> Unit)?, topics: ReviewTopics) =
        NoteCanvas({}, {}, summaryActions = SummaryCanvasActions(openOnWall = wall)).apply {
            setSize(1600, 900)
            topicsView.mode = mode
            showNotes(CanvasState(emptyList(), topics = TopicsCard(topics, summaries, canRun = false)))
            layoutTree(this)
        }

    fun testTopicsOfBothModesOpenOnTheWall() {
        val topics = ReviewTopics(files, mapOf("src/a.kt" to "Storage > Wallet", "src/nested/b.kt" to "Storage > Wallet",
            "docs/c.kt" to "UI", "docs/d.kt" to "UI"))
        for (mode in ChangeTopicsMode.entries) {
            val opened = mutableListOf<Pair<Set<String>, String>>()
            val canvas = topicsCanvas(mode, { files, title -> opened += files.map { it.path }.toSet() to title }, topics)
            try {
                fun menu(name: String) = (descendants(canvas).first { it.name == name } as JComponent).componentPopupMenu
                wallItems(menu("topic:0:storage")).single().doClick()
                wallItems(menu("topic:1:storage > wallet")).single().doClick()
                wallItems(menu("topic:0:ui")).single().doClick()
                assertEquals(mode.toString(), listOf(
                    setOf("src/a.kt", "src/nested/b.kt"), setOf("src/a.kt", "src/nested/b.kt"), setOf("docs/c.kt", "docs/d.kt"),
                ), opened.map { it.first })
                assertEquals(mode.toString(), listOf("Storage", "Storage > Wallet", "UI"), opened.map { it.second })
            } finally { canvas.dispose() }
        }
    }

    fun testTopicsHaveNoWallWithoutTheCallback() {
        val topics = ReviewTopics(files, files.associate { it.path to "Storage" })
        for (mode in ChangeTopicsMode.entries) {
            val canvas = topicsCanvas(mode, null, topics)
            try { assertTrue(mode.toString(), wallItems(canvas).isEmpty()) } finally { canvas.dispose() }
        }
    }
}
