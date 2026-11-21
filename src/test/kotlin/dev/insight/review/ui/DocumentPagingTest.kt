package dev.insight.review.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.codereview.CodeFile
import dev.insight.diff.ReviewFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewTopics
import dev.insight.review.buildSummaryTree
import java.awt.Component
import java.awt.Container

class DocumentPagingTest : BasePlatformTestCase() {
    private val page = DocumentColumnsPanel.DOCUMENT_PAGE_FILES

    private fun diffs(count: Int) = List(count) { ReviewFile("src/f$it.kt", "/repo/src/f$it.kt", null, "modified", "@@ -1,1 +1,1 @@\n-a\n+b\n", "1") }

    private fun descendants(container: Container): List<Component> =
        container.components.flatMap { listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList()) }

    private fun sections(panel: DocumentColumnsPanel) = panel.components.count { it.name?.startsWith("file:") == true }
    private fun moreLinks(panel: DocumentColumnsPanel) = descendants(panel).filterIsInstance<ActionLink>().filter { it.name == "document-more" }

    fun testTopicsDocumentBuildsOnePageAndItsLinkLoadsTheRestAcrossRebuilds() {
        val files = diffs(page + 1)
        val topics = files.fold(ReviewTopics(files)) { result, file -> result.assign(file.path, "Product>Feature") }
        var card = TopicsCard(topics, ReviewSummaries(buildSummaryTree(files)), canRun = false)
        val state = ChangeTopicsViewState(mode = ChangeTopicsMode.DOCUMENT)
        lateinit var panel: ChangeTopicsPanel
        fun build() { panel = ChangeTopicsPanel(card, state, 1800, 1.0, ChangeTopicActions({}, {}, refresh = { build() })) }
        build()
        assertEquals(page, sections(panel))
        val link = moreLinks(panel).single()
        assertEquals("Show next 1 file", link.text)
        // A topic spanning every file reaches only down to the last built section, above the link.
        val rail = panel.components.first { it.name?.startsWith("topic:") == true }
        val bottom = panel.components.filter { it.name?.startsWith("file:") == true }.maxOf { it.y + it.height }
        assertEquals(bottom, rail.y + rail.height)
        assertTrue(link.parent.y >= rail.y + rail.height)

        link.doClick()
        assertEquals(page + 1, sections(panel))
        assertTrue(moreLinks(panel).isEmpty())
        // A rebuild for new summaries keeps the files loaded so far.
        card = TopicsCard(topics, ReviewSummaries(buildSummaryTree(files)), canRun = false)
        build()
        assertEquals(page + 1, sections(panel))
        // A new search starts again at one page.
        state.summaryFilter = "src"
        build()
        assertEquals(page, sections(panel))
        assertEquals(1, moreLinks(panel).size)
    }

    fun testSmallDocumentsHaveNoMoreLink() {
        val files = diffs(3)
        val topics = files.fold(ReviewTopics(files)) { result, file -> result.assign(file.path, "Product>Feature") }
        val panel = ChangeTopicsPanel(TopicsCard(topics, ReviewSummaries(buildSummaryTree(files)), canRun = false),
            ChangeTopicsViewState(mode = ChangeTopicsMode.DOCUMENT), 1800, 1.0, ChangeTopicActions({}, {}))
        assertEquals(3, sections(panel))
        assertTrue(moreLinks(panel).isEmpty())
        assertTrue(panel.components.none { it.name == "document-more-files" })
    }

    fun testSummariesDocumentPagesAndRevealsAFileBeyondThePage() {
        val files = List(page + 5) { CodeFile("pkg/f$it.kt", "/repo/pkg/f$it.kt", "fun f$it() {}\n") }
        val card = SummariesCard(ReviewSummaries(buildSummaryTree(files)), running = false, canRun = true)
        val state = ChangeSummariesViewState().apply { mode = dev.insight.review.ReviewSummariesMode.DOCUMENT }
        lateinit var panel: ChangeSummaryDocumentPanel
        fun build() { panel = ChangeSummaryDocumentPanel(card, state, 1800, 1.0, ChangeSummaryActions({}, {}, {}, {}, refresh = { build() })) }
        build()
        assertEquals(page, sections(panel))
        // Path cards are built only for the files shown.
        assertEquals(page, panel.components.count { it.name?.startsWith("node:file:") == true })
        moreLinks(panel).single().doClick()
        assertEquals(page + 5, sections(panel))
        assertTrue(moreLinks(panel).isEmpty())

        val revealing = ChangeSummariesViewState().apply { mode = dev.insight.review.ReviewSummariesMode.DOCUMENT }
        val hidden = panel.files.last().path
        revealing.reveal = "file:$hidden"
        val revealed = ChangeSummaryDocumentPanel(card, revealing, 1800, 1.0, ChangeSummaryActions({}, {}, {}, {}))
        assertNotNull(revealed.components.singleOrNull { it.name == "file:$hidden" })
        assertEquals(2 * page, revealing.documentFiles)
    }

    fun testHugePatchParsesOnlyThePaintedLines() {
        val lines = 100_000
        val view = PatchView("@@ -1,0 +1,$lines @@\n" + "+x\n".repeat(lines), 400, 1.0)
        assertEquals(PatchView.MAX_LINES, view.lineCount)
        assertEquals(lines + 1 - PatchView.MAX_LINES, view.hiddenLines)
        val unterminated = PatchView("@@ -1,0 +1,$lines @@\n" + "+x\n".repeat(lines - 1) + "+x", 400, 1.0)
        assertEquals(lines + 1 - PatchView.MAX_LINES, unterminated.hiddenLines)
        assertEquals(0, PatchView("@@ -1,0 +1,1 @@\n" + "+x\n".repeat(PatchView.MAX_LINES - 1), 400, 1.0).hiddenLines)
    }

    fun testLongSourceIsAsWideAsItsLongestLine() {
        val long = "\tval longest = " + "x".repeat(300)
        val huge = SourceTextView("short\n".repeat(5_000) + long + "\n" + "short\n".repeat(10), 0, 1.0, digits = 6)
        val alone = SourceTextView(long + "\n", 0, 1.0, digits = 6)
        assertEquals(alone.preferredSize.width, huge.preferredSize.width)
        assertEquals(5_011, huge.lineCount)
    }
}
