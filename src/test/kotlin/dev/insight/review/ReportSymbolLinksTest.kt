package dev.insight.review

import dev.insight.links.SymbolNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSymbolLinksTest {
    private val index = ReportProjectIndex(
        files = listOf("src/Foo.kt", "src/render.kt", "src/main/Worker.kt"),
        reviewScopeFiles = setOf("src/Foo.kt"),
    )
    private val symbols = SymbolNames.of(listOf("render", "scanSegment", "Foo", "Worker", "Foo"))

    private fun render(md: String) = renderMarkdownReport(md, index, ReportThemeColors.DEFAULT, mapSource = false, symbols = symbols)

    @Test
    fun `symbols link in code spans and code-shaped prose`() {
        val rendered = render("Call `render` then scanSegment, but render as a word stays plain.")
        val symbolLinks = rendered.links.filter { it.kind == ReportTargetKind.SYMBOL }
        assertEquals(listOf("render", "scanSegment"), symbolLinks.map { it.displayText })
        assertTrue(rendered.html.contains("class=\"rl-sym\""))
    }

    @Test
    fun `file paths win over symbols of the same name`() {
        val rendered = render("Open `src/Foo.kt` and `Foo.kt`; the class `Foo` is separate.")
        assertEquals(
            listOf(ReportTargetKind.FILE, ReportTargetKind.FILE, ReportTargetKind.SYMBOL),
            rendered.links.map { it.kind },
        )
    }

    @Test
    fun `symbol links carry the files mentioned before them`() {
        val rendered = render("- `src/main/Worker.kt` uses `scanSegment()`")
        val link = rendered.links.single { it.kind == ReportTargetKind.SYMBOL }
        assertEquals("scanSegment()", link.displayText)
        assertTrue(link.symbol!!.call)
        assertEquals("src/main/Worker.kt", link.symbolHints!!.files.first())
        assertEquals(setOf("src/Foo.kt"), link.symbolHints!!.scopeFiles)
    }


    @Test
    fun `symbol links carry a bounded file history, most recent first`() {
        val files = (1..200).map { "src/f$it.kt" }
        val many = ReportProjectIndex(files = files)
        val md = files.joinToString("\n\n") { "## Heading\n\n`$it` then `render`" }
        val links = renderMarkdownReport(md, many, ReportThemeColors.DEFAULT, mapSource = false, symbols = symbols)
            .links.filter { it.kind == ReportTargetKind.SYMBOL }
        assertEquals(200, links.size)
        val last = links.last().symbolHints!!.files
        assertTrue(last.size <= 32)
        assertEquals(listOf("src/f200.kt", "src/f199.kt"), last.take(2))
    }
    @Test
    fun `contextual line references still bind to files`() {
        val rendered = render("`src/Foo.kt` at L12 calls `render`")
        assertEquals(listOf(ReportTargetKind.FILE, ReportTargetKind.FILE, ReportTargetKind.SYMBOL), rendered.links.map { it.kind })
        assertEquals(12..12, rendered.links[1].lines)
    }

    @Test
    fun `plain text reports link symbols too`() {
        val rendered = renderPlainTextReport("at Worker.scanSegment(Worker.kt:12)", index, ReportThemeColors.DEFAULT, mapSource = false, symbols = symbols)
        assertTrue(rendered.links.any { it.kind == ReportTargetKind.SYMBOL && it.symbol?.name == "scanSegment" })
    }

    @Test
    fun `without symbol names nothing new links`() {
        val rendered = renderMarkdownReport("Call `render` then scanSegment.", index)
        assertTrue(rendered.links.none { it.kind == ReportTargetKind.SYMBOL })
    }

    @Test
    fun `embedded text links the same and keeps the surrounding background`() {
        val text = "See `src/Foo.kt` and `render`."
        val page = render(text)
        val embedded = renderMarkdownReport(text, index, ReportThemeColors.DEFAULT, mapSource = false, symbols = symbols, embedded = true)
        assertEquals(page.links.map { it.kind to it.displayText }, embedded.links.map { it.kind to it.displayText })
        val body = Regex("body \\{[^}]*}").find(embedded.html)!!.value
        assertTrue(body, "margin: 0;" in body && "background-color" !in body)
        assertTrue("background-color" in Regex("body \\{[^}]*}").find(page.html)!!.value)
    }
}
