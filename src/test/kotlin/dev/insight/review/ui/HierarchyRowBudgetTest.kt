package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree

/** A huge scope places a bounded number of cards and offers to show more, a page at a time. */
class HierarchyRowBudgetTest : BasePlatformTestCase() {
    private data class Source(override val path: String, override val facts: List<String> = emptyList()) : ReviewSource {
        override val absolutePath: String get() = "/repo/$path"
        override val fingerprint: String get() = "1"
        override val text: String get() = "val answer = 42\n"
    }

    private val page = HierarchyColumnsPanel.MAX_ROWS
    private val files = List(2 * page + 300) { Source("pkg/f${it.toString().padStart(5, '0')}.kt") }
    private var refreshes = 0

    private fun cards(panel: HierarchyColumnsPanel) = panel.components.count {
        val name = it.name.orEmpty()
        name.startsWith("node:") || name.startsWith("file:") || name.startsWith("topic:")
    }

    private fun links(panel: HierarchyColumnsPanel) = panel.components.filter { it.name?.startsWith("more:") == true }

    private fun summaryPanel(state: ChangeSummariesViewState, scope: List<Source> = files): ChangeSummaryHierarchyPanel {
        val tree = buildSummaryTree(scope, maxChildren = scope.size + 1)
        state.update(tree)
        return ChangeSummaryHierarchyPanel(SummariesCard(ReviewSummaries(tree), false, true), state, 1200, 1.0,
            ChangeSummaryActions({}, {}, {}, {}, refresh = { refreshes++ }))
    }

    private fun topicCard(scope: List<Source>, label: (Int) -> String): TopicsCard {
        var topics = ReviewTopics(scope)
        scope.forEachIndexed { index, file -> topics = topics.assign(file.path, label(index)) }
        return TopicsCard(topics, ReviewSummaries(buildSummaryTree(scope, maxChildren = scope.size + 1)), canRun = true)
    }

    private fun topicPanel(state: ChangeTopicsViewState, card: TopicsCard) =
        ChangeTopicHierarchyPanel(card, state, 1200, 1.0, ChangeTopicActions({}, {}, refresh = { refreshes++ }))

    fun testSummaryHierarchyCountsBranchCardsAndShowsMoreAPageAtATime() {
        val state = ChangeSummariesViewState()
        val tree = buildSummaryTree(files, maxChildren = files.size + 1)
        state.update(tree)
        state.selectLevel(ChangeSummariesViewState.branchLevels(tree), tree)
        var panel = summaryPanel(state)
        assertEquals(page, cards(panel))
        assertTrue(panel.components.any { it.name == "node:${tree.root.identity}" })
        var link = links(panel).single() as ActionLink
        assertTrue(link.text, link.text.startsWith("+${files.size - (page - 1)} more"))
        link.doClick()
        assertEquals(1, refreshes)
        // The rebuilt panel finds the grown budget in the view state.
        panel = summaryPanel(state)
        assertEquals(2 * page, cards(panel))
        (links(panel).single() as ActionLink).doClick()
        panel = summaryPanel(state)
        assertEquals(files.size + 1, cards(panel))
        assertTrue(links(panel).isEmpty())
    }

    fun testSummaryBudgetStartsOverWhenTheLevelOrTheSearchChanges() {
        val state = ChangeSummariesViewState()
        val tree = buildSummaryTree(files, maxChildren = files.size + 1)
        state.update(tree)
        state.selectLevel(99, tree)
        summaryPanel(state)
        (links(summaryPanel(state)).single() as ActionLink).doClick()
        assertEquals(2 * page, cards(summaryPanel(state)))
        state.selectLevel(98, tree)
        assertEquals(page, cards(summaryPanel(state)))
        (links(summaryPanel(state)).single() as ActionLink).doClick()
        state.fuzzy = "f0"
        assertEquals(page, cards(summaryPanel(state)))
    }

    fun testSummaryRevealOfAnEntryBeyondTheBudgetExtendsIt() {
        val state = ChangeSummariesViewState()
        val tree = buildSummaryTree(files, maxChildren = files.size + 1)
        state.update(tree)
        state.selectLevel(99, tree)
        val last = tree.nodes.single { it.kind == SummaryKind.FILE && it.path == files.last().path }
        assertFalse(summaryPanel(state).anchors.containsKey(last.identity))
        state.reveal = last.identity
        val panel = summaryPanel(state)
        assertTrue(panel.anchors.containsKey(last.identity))
        assertEquals(files.size + 1, cards(panel))
    }

    fun testSmallSummaryTreesShowNoLink() {
        val state = ChangeSummariesViewState()
        state.update(buildSummaryTree(files.take(20)))
        assertTrue(links(summaryPanel(state, files.take(20))).isEmpty())
    }

    fun testTopicHierarchyCountsTopicCardsAndShowsMoreAPageAtATime() {
        val card = topicCard(files) { "Product>Storage" }
        val state = ChangeTopicsViewState()
        var panel = topicPanel(state, card)
        assertEquals(page, cards(panel))
        assertTrue(panel.components.any { it.name?.startsWith("topic:") == true })
        val link = links(panel).single() as ActionLink
        assertTrue(link.text, link.text.contains("more"))
        link.doClick()
        assertEquals(1, refreshes)
        panel = topicPanel(state, card)
        assertTrue(cards(panel) > page)
        assertTrue(cards(panel) <= 2 * page)
        assertEquals(1, links(panel).size)
    }

    fun testTopicBudgetCoversEveryCardOfADeepTopicTree() {
        // Many small topics: branch cards alone would exceed the cap if only leaf rows counted.
        val card = topicCard(files.take(3000)) { "T${it / 3}>S${it / 3}>U${it / 3}" }
        val panel = topicPanel(ChangeTopicsViewState(), card)
        assertEquals(page, cards(panel))
        assertTrue(links(panel).isNotEmpty())
    }

    fun testTopicRevealOfAFileBeyondTheBudgetExtendsIt() {
        val card = topicCard(files) { "Product>Storage" }
        val state = ChangeTopicsViewState()
        val last = files.last()
        assertFalse(topicPanel(state, card).anchors.containsKey("file:${last.path}"))
        state.reveal = "file:${last.path}"
        assertTrue(topicPanel(state, card).anchors.containsKey("file:${last.path}"))
    }

    fun testSmallTopicTreesShowNoLink() {
        val small = files.take(20)
        assertTrue(links(topicPanel(ChangeTopicsViewState(), topicCard(small) { "Product>Storage" })).isEmpty())
    }
}
