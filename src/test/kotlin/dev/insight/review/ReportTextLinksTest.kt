package dev.insight.review

import dev.insight.links.SymbolNames
import dev.insight.review.ui.summaryPreferredFile
import dev.insight.review.ui.summaryRankFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTextLinksTest {
    private val index = ReportProjectIndex(
        files = listOf("src/Foo.kt", "src/main/Worker.kt", "test/Worker.kt"),
        reviewScopeFiles = setOf("src/Foo.kt"),
    )
    private val symbols = SymbolNames.of(listOf("render", "scanSegment"))

    private fun spans(text: String, defaultFile: String? = null) =
        findReportTextLinks(text, index, symbols, defaultFile).map { text.substring(it.start, it.end) to it.link.kind }

    @Test
    fun `links are spans of the plain text itself`() {
        val text = "Updates src/Foo.kt:12 so `scanSegment` calls render, see https://example.com/x."
        assertEquals(
            listOf(
                "src/Foo.kt:12" to ReportTargetKind.FILE,
                "scanSegment" to ReportTargetKind.SYMBOL,
                "https://example.com/x" to ReportTargetKind.EXTERNAL_URL,
            ),
            spans(text),
        )
    }

    @Test
    fun `file links keep their line ranges`() {
        val link = findReportTextLinks("See `src/Foo.kt` lines 10-20.", index, symbols).first().link
        assertEquals(listOf("src/Foo.kt"), link.candidates)
        val contextual = findReportTextLinks("See `src/Foo.kt` at L10-L20.", index, symbols).map { it.link }
        assertTrue(contextual.any { it.lines == 10..20 && it.candidates == listOf("src/Foo.kt") })
    }

    @Test
    fun `bare line references bind to the default file`() {
        val links = findReportTextLinks("The guard at L42 returns early.", index, symbols, defaultFile = "src/Foo.kt").map { it.link }
        assertEquals(listOf("src/Foo.kt"), links.single().candidates)
        assertEquals(42..42, links.single().lines)
    }

    @Test
    fun `offsets hold across lines and unmatched backticks`() {
        val text = "First line with a ` stray tick.\nThen `src/Foo.kt` and src/main/Worker.kt."
        assertEquals(listOf("src/Foo.kt", "src/main/Worker.kt"), spans(text).map { it.first })
    }

    @Test
    fun `plain prose and blank text have no links`() {
        assertEquals(emptyList<Any>(), spans("Nothing to see here, render as a word stays plain."))
        assertEquals(emptyList<Any>(), spans("   "))
    }

    @Test
    fun `ambiguous files prefer the summary's own entry, then the review scope`() {
        val candidates = listOf("src/main/Worker.kt", "test/Worker.kt")
        assertEquals("test/Worker.kt", summaryPreferredFile(candidates, "test/Worker.kt", emptySet(), emptySet()))
        assertEquals("src/main/Worker.kt", summaryPreferredFile(candidates, null, setOf("src/main/Worker.kt"), emptySet()))
        assertEquals("test/Worker.kt", summaryPreferredFile(candidates, null, emptySet(), setOf("test/Worker.kt")))
        assertNull(summaryPreferredFile(candidates, null, emptySet(), candidates.toSet()))
        assertEquals(listOf("test/Worker.kt", "src/main/Worker.kt"),
            summaryRankFiles(candidates, null, setOf("test/Worker.kt"), emptySet()))
    }
}
