package dev.insight.review.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.JBFont
import dev.insight.review.ReportTargetKind
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu

class SummaryTextLinksPlatformTest : BasePlatformTestCase() {
    private fun summaryPane(text: String) = WrappedSummaryText(text, 400, JBFont.label(), emptyMap())

    private fun install(area: WrappedSummaryText, text: String, sourcePath: String? = null) {
        val navigator = summaryTextNavigator(project, sourcePath, listOfNotNull(sourcePath), listOf("src/A.kt", "src/B.kt"),
            isDiffReview = false) { _, _, _ -> null }
        installSummaryTextLinks(area, text, project, SummaryTextLinkContext(navigator))
        // Panes in a window follow the project's files; a headless pane needs its peer for that.
        JPanel().add(area)
        area.addNotify()
    }

    private fun waitForLinks(area: WrappedSummaryText, predicate: (List<dev.insight.review.LineLinkMatch>) -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching("Summary links should resolve", {
            summaryTextLinks(area)?.links?.invoke()?.let(predicate) == true
        }, 10)
    }

    fun `test summary text links files, lines and symbols without changing the text`() {
        myFixture.addFileToProject("src/A.kt", "fun alpha() = 1\nfun second() = 2\n")
        myFixture.addFileToProject("src/B.kt", "fun computeHelper(x: Int) = x + 1\n")
        val text = "The guard at L2 returns early, then calls `computeHelper` from src/B.kt."
        val area = summaryPane(text)
        install(area, text, sourcePath = "src/A.kt")
        waitForLinks(area) { links ->
            links.any { it.link.kind == ReportTargetKind.SYMBOL } && links.any { it.link.primaryPath == "src/B.kt" }
        }
        assertEquals(text, area.text)
        val links = summaryTextLinks(area)!!.links()
        assertEquals("computeHelper", text.substring(links.first { it.link.kind == ReportTargetKind.SYMBOL }.let { it.start until it.end }))
        val line = links.single { it.link.contextual }
        assertEquals("src/A.kt", line.link.primaryPath)
        assertEquals(2..2, line.link.lines)

        summaryTextLinks(area)!!.follow(links.first { it.link.primaryPath == "src/B.kt" && !it.link.contextual }, false)
        PlatformTestUtil.waitWithEventsDispatching("The linked file should open", {
            FileEditorManager.getInstance(project).selectedFiles.any { it.path.endsWith("src/B.kt") }
        }, 10)
    }

    fun `test linked summary menu lists the link's and the entry's items`() {
        myFixture.addFileToProject("src/B.kt", "fun beta() = 2\n")
        val text = "Moves the check into src/B.kt."
        val area = summaryPane(text)
        install(area, text)
        waitForLinks(area) { links -> links.any { it.link.primaryPath == "src/B.kt" } }
        installSummaryEntryMenu(area) { JPopupMenu().apply { add(JMenuItem("Open diff").apply { name = "entry" }) } }
        // The link's menu includes the entry's items, so the entry menu is not shown on its own.
        assertNull(area.componentPopupMenu)

        area.caretPosition = text.indexOf("src/B.kt") + 2
        val menu = JPopupMenu()
        assertTrue(summaryTextLinks(area)!!.addItems(menu, null))
        val names = menu.components.mapNotNull { it.name }
        assertTrue(names.toString(), "text-link-action:Context" in names && "text-link-action:Open" in names && "text-link-action:CopyPath" in names)

        area.select(0, text.length)
        val selectionMenu = JPopupMenu()
        summaryTextLinks(area)!!.addItems(selectionMenu, null)
        assertEquals("summary-selection-action:Context", selectionMenu.components.first().name)
    }
}
