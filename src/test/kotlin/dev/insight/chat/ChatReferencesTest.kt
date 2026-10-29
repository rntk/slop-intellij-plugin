package dev.insight.chat

import org.junit.Assert.*
import org.junit.Test

class ChatReferencesTest {
    @Test fun `references become links and everything else is escaped`() {
        val names = mapOf(1 to "loadData", 2 to "save<T>")
        fun html(text: String, revision: Int? = 4) = chatReferenceHtml(text, revision) { names[it] }
        assertEquals(listOf("See ", ChatReference(1, null), " then ", ChatReference(2, "Save it"), "."),
            parseChatReferences("See [[node:1]] then [[node:2| Save it ]]."))
        assertEquals("""See <a href="node:4:1">loadData</a> then <a href="node:4:2">Save &amp; exit</a>""",
            html("See [[node:1]] then [[node:2|Save & exit]]"))
        assertEquals("""<a href="node:4:2">save&lt;T&gt;</a>""", html("[[node:2]]"))
        assertEquals("&lt;b&gt;bold&lt;/b&gt; &lt;a href=&quot;x&quot;&gt;", html("""<b>bold</b> <a href="x">""").replace("&#39;", "'"))
        assertEquals("""<a href="node:4:1">&lt;img src=x&gt;</a>""", html("[[node:1|<img src=x>]]"))
        for (malformed in listOf("[[node:x]]", "[[node:]]", "[[node:1|a]b]]", "[node:1]", "[[node:-1]]", "[[node:1234567890]]"))
            assertFalse(malformed, html(malformed).contains("<a"))
        assertEquals("Unknown and #9", html("[[node:9|Unknown]] and [[node:9]]"))
        assertEquals("loadData", html("[[node:1|loadData]]", revision = null))
        assertEquals("line<br>next &nbsp; x", html("line\nnext   x"))
        assertEquals(4 to 12, parseChatReferenceLink("node:4:12"))
        assertNull(parseChatReferenceLink("http://example.com"))
        assertNull(parseChatReferenceLink(null))

        val beatTitles = mapOf(2 to "Validation")
        fun beatHtml(text: String, revision: Int? = 4) = chatReferenceHtml(text, revision, beatTitle = { beatTitles[it] }) { names[it] }
        assertEquals(listOf("Stage: ", ChatBeatReference(1, null), " and ", ChatBeatReference(2, "Validation"), "."),
            parseChatReferences("Stage: [[beat:1]] and [[beat:2|Validation]]."))
        assertEquals("""See <a href="beat:4:2">Validation</a> and <a href="beat:4:2">Stage 2</a>""",
            beatHtml("See [[beat:2]] and [[beat:2|Stage 2]]"))
        assertEquals("Unknown and Beat 9", beatHtml("[[beat:9|Unknown]] and [[beat:9]]"))
        assertEquals(4 to 2, parseChatBeatLink("beat:4:2"))
        assertNull(parseChatBeatLink("beat:4:0"))
        assertNull(parseChatBeatLink("node:4:2"))
        assertNull(parseChatBeatLink(null))
        for (malformed in listOf("[[beat:0]]", "[[beat:100]]", "[[beat:-1]]", "[[beat:x]]")) {
            assertFalse(malformed, beatHtml(malformed).contains("<a"))
            assertTrue(malformed, beatHtml(malformed).contains(malformed))
        }
    }
}
