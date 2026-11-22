package dev.insight.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolMentionsTest {
    private val names = SymbolNames.of(listOf("scanSegment", "ReportLinkCollector", "render", "MAX_LINE", "run", "Foo", "id", "parse_line", "\$store"))

    private fun prose(text: String) = findSymbolMentions(text, names, code = false)
    private fun code(text: String) = findSymbolMentions(text, names, code = true)

    @Test
    fun `prose links code-shaped names only`() {
        val found = prose("Then scanSegment calls render and ReportLinkCollector checks MAX_LINE and parse_line.")
        assertEquals(listOf("scanSegment", "ReportLinkCollector", "MAX_LINE", "parse_line"), found.map { it.name })
    }

    @Test
    fun `prose links a plain word written as a call`() {
        val found = prose("It calls render() first.")
        assertEquals(1, found.size)
        assertEquals("render", found[0].name)
        assertTrue(found[0].call)
        assertEquals("render()", "It calls render() first.".substring(found[0].start, found[0].end))
    }

    @Test
    fun `qualified mention keeps its owners`() {
        val text = "See ReportLinkCollector.scanSegment() for details."
        val mention = prose(text).single()
        assertEquals("scanSegment", mention.name)
        assertEquals(listOf("ReportLinkCollector"), mention.qualifier)
        assertEquals("ReportLinkCollector.scanSegment()", text.substring(mention.start, mention.end))
    }

    @Test
    fun `qualified prose mention with a capitalized owner links`() {
        val mention = prose("Foo.run handles it").single()
        assertEquals("run", mention.name)
        assertEquals(listOf("Foo"), mention.qualifier)
    }

    @Test
    fun `qualified mention links up to its last known part`() {
        val text = "ReportLinkCollector.unknownThing"
        val mention = prose(text).single()
        assertEquals("ReportLinkCollector", mention.name)
        assertEquals("ReportLinkCollector", text.substring(mention.start, mention.end))
    }

    @Test
    fun `code links any known name and keeps call arguments separate`() {
        val found = code("render(id, run)")
        assertEquals(listOf("render", "id", "run"), found.map { it.name })
        assertTrue(found[0].call)
        assertEquals(6, found[0].end)
    }

    @Test
    fun `names inside other words paths or kebab case do not link`() {
        assertTrue(prose("prescanSegment and src/scanSegment and scanSegment-like and @scanSegment").isEmpty())
        assertTrue(code("xrender render_x").isEmpty())
    }

    @Test
    fun `unknown names do not link`() {
        assertTrue(prose("someOtherName and other_name()").isEmpty())
        assertNull(matchSymbolMention("unknownName", 0, names, code = true))
    }

    @Test
    fun `dollar names link in code`() {
        assertEquals(listOf("\$store"), code("use \$store here").map { it.name })
    }

    @Test
    fun `code shape`() {
        assertTrue(isCodeShaped("scanSegment"))
        assertTrue(isCodeShaped("ReportLink"))
        assertTrue(isCodeShaped("MAX_LINE"))
        assertTrue(isCodeShaped("_private"))
        assertFalse(isCodeShaped("Report"))
        assertFalse(isCodeShaped("render"))
        assertFalse(isCodeShaped("URL"))
        assertFalse(isCodeShaped("ab"))
    }
}
