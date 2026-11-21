package dev.insight.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRendererTest {
    private val index = ReportProjectIndex(
        files = listOf("src/Foo.kt", "src/Bar.kt", "docs/notes.md"),
        reviewScopeFiles = setOf("src/Foo.kt"),
    )

    @Test fun `escapes dangerous HTML in prose, headings, code spans, and tables`() {
        val md = """
            # Title <script>alert(1)</script>

            Prose with <img src=x onerror=alert(1)> & `src/Foo.kt` <tag>.

            | Col & 1 | Col "2" |
            | --- | ---: |
            | `<b>` | `src/Bar.kt:L12` |
        """.trimIndent()
        val rendered = renderMarkdownReport(md, index)
        assertFalse(rendered.html.contains("<script>"))
        assertFalse(rendered.html.contains("<img"))
        assertTrue(rendered.html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(rendered.html.contains("&lt;img src=x onerror=alert(1)&gt; &amp;"))
        assertTrue(rendered.html.contains("<code>&lt;b&gt;</code>"))
        assertTrue(rendered.html.contains("<th align=\"right\">Col &quot;2&quot;</th>"))
        assertEquals(2, rendered.links.size)
    }

    @Test fun `renders markdown headings, task lists, blockquotes, fenced code, and explicit links`() {
        val md = """
            ## Summary

            - [ ] Fix [Foo](src/Foo.kt#L10-L20)
            - [x] Check **bold `src/Bar.kt:5`** and `snake_case_name`

            > Note about `src/Foo.kt`
        """.trimIndent()
        val rendered = renderMarkdownReport(md, index)
        assertTrue(rendered.html.contains("<h2>Summary</h2>"))
        assertTrue(rendered.html.contains("&#9744; Fix <a class=\"rl\" href=\"report-link:0\">Foo</a>"))
        assertTrue(rendered.html.contains("&#9745; Check <b>bold <code><a class=\"rl\" href=\"report-link:1\">src/Bar.kt:5</a></code></b>"))
        assertTrue(rendered.html.contains("<code>snake_case_name</code>"))
        assertFalse(rendered.html.contains("<i>case</i>"))
        assertTrue(rendered.html.contains("<blockquote>Note about"))

        assertEquals(3, rendered.links.size)
        assertEquals(10..20, rendered.links[0].lines)
        assertEquals("src/Foo.kt", rendered.links[0].primaryPath)

        // Referenced files aggregated and sorted with review-scope files first
        assertEquals(
            listOf(
                ReferencedFile("src/Foo.kt", mentions = 2, inReviewScope = true),
                ReferencedFile("src/Bar.kt", mentions = 1, inReviewScope = false),
            ),
            rendered.referencedFiles,
        )
    }

    @Test fun `renders plain text with line numbers and smart contextual links`() {
        val text = """
            Error in src/Foo.kt:L15:
              see L22-L25 for details
        """.trimIndent()
        val rendered = renderPlainTextReport(text, index)
        assertTrue(rendered.html.contains("<pre class=\"raw-text\">"))
        assertTrue(rendered.html.contains("<span class=\"ln\"> 1  </span>"))
        assertEquals(2, rendered.links.size)
        assertEquals("src/Foo.kt", rendered.links[0].primaryPath)
        assertEquals(15..15, rendered.links[0].lines)
        assertEquals("src/Foo.kt", rendered.links[1].primaryPath)
        assertEquals(22..25, rendered.links[1].lines)
        assertTrue(rendered.links[1].contextual)
    }

    @Test fun `truncates overly large reports cleanly`() {
        val longText = (1..200).joinToString("\n") { "Line $it in `src/Foo.kt`" }
        val rendered = renderMarkdownReport(longText, index, maxChars = 250)
        assertTrue(rendered.truncated)
        assertTrue(rendered.html.contains("Report truncated at 250 characters."))
    }

    @Test fun `markdown detection and href parsing handle edge cases`() {
        assertTrue(isMarkdownFileName("REPORT.MD"))
        assertFalse(isMarkdownFileName("output.log"))
        assertTrue(looksLikeMarkdown("# Heading\n\n- [ ] task"))
        assertFalse(looksLikeMarkdown("plain log line src/Foo.kt:12 ERROR"))
        assertEquals(42, parseReportLinkHref("report-link:42"))
        assertEquals(null, parseReportLinkHref("https://example.com"))
    }

    @Test fun `does not pad short table rows to header width and bounds total table cells`() {
        val wideHeader = (1..500).joinToString("|", prefix = "|", postfix = "|") { "C$it" }
        val wideSep = (1..500).joinToString("|", prefix = "|", postfix = "|") { "---" }
        val shortRows = (1..500).joinToString("\n") { "| r$it |" }
        val sparseMd = "$wideHeader\n$wideSep\n$shortRows"
        assertTrue(sparseMd.length < 50_000)

        val sparseRendered = renderMarkdownReport(sparseMd, index)
        assertFalse(sparseRendered.truncated)
        val tdCount = Regex("<td[ >]").findAll(sparseRendered.html).count()
        assertEquals(500, tdCount)
        assertTrue(sparseRendered.html.length < 50_000)

        val capped = renderMarkdownReport(sparseMd, index, maxTableCells = 50)
        assertTrue(capped.truncated)
        val cappedTotalCells = Regex("<t[hd][ >]").findAll(capped.html).count()
        assertEquals(50, cappedTotalCells)
        assertTrue(capped.html.contains("</tbody></table>"))
        assertTrue(capped.html.contains("Table truncated at 50 cells."))

        // Also verify default MAX_TABLE_CELLS bounds a multi-table input well under MAX_REPORT_CHARS.
        val mediumHeader = (1..400).joinToString("|", prefix = "|", postfix = "|") { "H$it" }
        val mediumSep = (1..400).joinToString("|", prefix = "|", postfix = "|") { "---" }
        val denseRows = (1..60).joinToString("\n") { (1..400).joinToString("|", prefix = "|", postfix = "|") { "v" } }
        val overBudgetMd = "$mediumHeader\n$mediumSep\n$denseRows"
        assertTrue(overBudgetMd.length < MAX_REPORT_CHARS)

        val overBudgetRendered = renderMarkdownReport(overBudgetMd, index)
        assertTrue(overBudgetRendered.truncated)
        val totalRenderedCells = Regex("<t[hd][ >]").findAll(overBudgetRendered.html).count()
        assertEquals(MAX_TABLE_CELLS, totalRenderedCells)
        assertTrue(overBudgetRendered.html.contains("</tbody></table>"))
        assertTrue(overBudgetRendered.html.contains("Table truncated at 20,000 cells."))
    }
}
