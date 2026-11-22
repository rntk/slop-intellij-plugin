package dev.insight.review.ui

import dev.insight.review.RenderedReport
import dev.insight.review.ReportProjectIndex
import dev.insight.review.ReportThemeColors
import dev.insight.review.renderMarkdownReport
import dev.insight.review.renderPlainTextReport
import javax.swing.JEditorPane
import javax.swing.text.html.HTMLDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReportSourceCopyTest {
    private val index = ReportProjectIndex(files = listOf("src/Foo.kt"))
    private val link = "[file label](src/Foo.kt#L10-L20)"

    @Test fun `rendered selection copies original formatting and link targets by offset`() {
        val unresolved = "[missing file](src/Missing.kt#L8)"
        val source = "file label appears here too.\r\n\r\n- Open **$link** and `src/Foo.kt:L22`.\r\n\r\nSee $unresolved."
        val report = renderMarkdownReport(source, index, ReportThemeColors.DEFAULT, mapSource = true)
        val pane = pane(report)

        fun copy(text: String): String? = copy(report, pane, select(pane, text))

        assertEquals("file label", copy("file label"))
        assertEquals("**$link** and", copy("ile label and"))
        assertEquals("- Open **$link** and `src/Foo.kt:L22`.", copy("Open file label and src/Foo.kt:L22."))
        assertEquals(unresolved, copy("missing file"))
    }

    @Test fun `raw selection omits generated line numbers`() {
        val source = "Open  $link\n  then src/Foo.kt:L22"
        val report = renderPlainTextReport(source, index, ReportThemeColors.DEFAULT, mapSource = true)
        val pane = pane(report)
        pane.selectAll()
        assertEquals(source, copy(report, pane, ReportTextSelection.capture(pane)!!))
    }

    @Test fun `display deletions in a pasted report do not change copied offsets or reappear in copied text`() {
        val report = renderMarkdownReport("Keep removed text and $link here.", index, ReportThemeColors.DEFAULT, mapSource = true)
        val original = pane(report)
        val edited = pane(report)
        val edits = ReportTextEdits(edited.document)
        edits.delete(edited.document, select(edited, "removed "))
        edits.delete(edited.document, select(edited, "here"))
        edited.selectAll()
        val selection = ReportTextSelection.capture(edited)!!

        assertEquals("Keep text and $link .", copy(report, original, selection, edits))
        assertNull(copy(report, original, ReportTextSelection(0, 1, "\n")))
    }

    private fun pane(report: RenderedReport) = JEditorPane("text/html", report.html)

    private fun select(pane: JEditorPane, text: String): ReportTextSelection {
        val start = pane.document.getText(0, pane.document.length).indexOf(text)
        require(start >= 0) { "No rendered text matches '$text'" }
        pane.select(start, start + text.length)
        return ReportTextSelection.capture(pane)!!
    }

    private fun copy(report: RenderedReport, pane: JEditorPane, selection: ReportTextSelection, edits: ReportTextEdits? = null): String? =
        reportSourceCopy(pane.document as HTMLDocument, selection, report.sourceMap!!, report.isPlainText, edits)
}
