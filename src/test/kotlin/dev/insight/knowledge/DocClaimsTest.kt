package dev.insight.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocClaimsTest {
    private val sample = """
        # Diff walkthrough flow

        The Walkthrough is a view, e.g. a step chain. Each step links hunks (see WalkModel.kt for the model).
        Steps use `Foo. Bar` as a code span and the [guide](../docs/a.b.md) as a link. They end here!
        Is it clear? Yes it is.

        ## Running

        ```kotlin
        val x = 1. Not prose. At all.
        ```

        - A bullet sentence. And another one.
        - i.e. a lowercase start, and U.S. rules apply.

        | col | col |
        |-----|-----|
        | Table. Cell. | x |

        <!-- Hidden. Comment. -->

        ---

        Entry points for further exploration:

        - [WalkModel.kt](../src/main/kotlin/dev/insight/diff/walk/WalkModel.kt) — hunks, links. And statuses.
        - [WalkTools.kt](../src/main/kotlin/dev/insight/diff/walk/WalkTools.kt) — the tools

        Trailing paragraph after the list.
    """.trimIndent()

    private val lines = sample.lines()
    private fun lineOf(snippet: String) = lines.indexOfFirst { snippet in it } + 1

    private val claims = parseDocClaims(sample)
    private fun texts() = claims.map { it.text }

    @Test fun `splits prose paragraphs into sentences and skips headings fences tables comments and rules`() {
        val prose = claims.filter { it.kind == DocClaimKind.PROSE }.map { it.text }
        assertEquals(
            listOf(
                "The Walkthrough is a view, e.g. a step chain.",
                "Each step links hunks (see WalkModel.kt for the model).",
                "Steps use `Foo. Bar` as a code span and the [guide](../docs/a.b.md) as a link.",
                "They end here!",
                "Is it clear?",
                "Yes it is.",
                "A bullet sentence.",
                "And another one.",
                "i.e. a lowercase start, and U.S. rules apply.",
                "Trailing paragraph after the list.",
            ),
            prose,
        )
        assertTrue(texts().none { it.startsWith("#") || "Not prose" in it || "Table" in it || "Hidden" in it })
    }

    @Test fun `keeps source line ranges across wrapped paragraphs`() {
        val two = claims.single { it.text.startsWith("Each step links") }
        assertEquals(lineOf("The Walkthrough is a view"), two.startLine)
        assertEquals(two.startLine, two.endLine)
        val wrapped = claims.single { it.text.startsWith("Steps use") }
        assertEquals(lineOf("Steps use"), wrapped.startLine)
        val end = claims.single { it.text == "They end here!" }
        assertEquals(lineOf("Steps use"), end.startLine)
        assertEquals(lineOf("Is it clear?"), claims.single { it.text == "Is it clear?" }.startLine)
        assertEquals(lineOf("A bullet sentence"), claims.single { it.text == "And another one." }.startLine)
    }

    @Test fun `a sentence wrapped over two lines spans both`() {
        val parsed = parseDocClaims("Intro\n\nThis sentence starts here\nand ends on the next line. Next one.\n")
        val wrapped = parsed.single { it.text.startsWith("This sentence") }
        assertEquals("This sentence starts here and ends on the next line.", wrapped.text)
        assertEquals(3, wrapped.startLine)
        assertEquals(4, wrapped.endLine)
        assertEquals(4, parsed.single { it.text == "Next one." }.startLine)
    }

    @Test fun `inline and multiline comments are excluded while prose and line ranges survive`() {
        val parsed = parseDocClaims("First sentence. <!-- c-1 --> Next sentence.\nWrapped <!-- hidden\nstill hidden --> sentence.\n\nEntry points:\n\n- [A](a) <!-- c-2 --> — description\n")
        assertEquals(listOf("First sentence.", "Next sentence.", "Wrapped sentence.", "[A](a) — description"), parsed.map { it.text })
        assertEquals(1, parsed[1].startLine)
        assertEquals(2, parsed[2].startLine)
        assertEquals(3, parsed[2].endLine)
        assertEquals(7, parsed[3].startLine)
        assertEquals(DocClaimKind.ENTRY_POINT, parsed[3].kind)
        assertEquals("Use `<!-- literal -->` here.", parseDocClaims("Use `<!-- literal -->` here.").single().text)
    }

    @Test fun `entry point bullets become one claim each and keep their links`() {
        val entries = claims.filter { it.kind == DocClaimKind.ENTRY_POINT }
        assertEquals(2, entries.size)
        assertTrue(entries[0].text.startsWith("[WalkModel.kt](../src/main/kotlin/dev/insight/diff/walk/WalkModel.kt)"))
        assertTrue("And statuses." in entries[0].text)
        assertEquals(lineOf("[WalkModel.kt]"), entries[0].startLine)
        assertEquals(lineOf("[WalkTools.kt]"), entries[1].endLine)
        assertFalse(texts().any { it.startsWith("Entry points") })
    }

    @Test fun `entry mode ends with the next paragraph or heading`() {
        val parsed = parseDocClaims("Entry points:\n\n- [A.kt](a) — one\n\nPlain text.\n\n- Later bullet.\n")
        assertEquals(listOf(DocClaimKind.ENTRY_POINT, DocClaimKind.PROSE, DocClaimKind.PROSE), parsed.map { it.kind })
        val byHeading = parseDocClaims("## Entry points\n\n- [A.kt](a) — one\n")
        assertEquals(DocClaimKind.ENTRY_POINT, byHeading.single().kind)
        assertEquals("Entry points", byHeading.single().section)
    }

    @Test fun `sections follow headings`() {
        assertEquals("Diff walkthrough flow", claims.first().section)
        assertEquals("Running", claims.single { it.text == "A bullet sentence." }.section)
        assertNull(parseDocClaims("Before any heading.").single().section)
    }

    @Test fun `file names versions and links do not end sentences`() {
        assertEquals(
            listOf("See WalkModel.kt and v1.2.3 in the docs.", "Then `a. B` and [x.y](p/q.md) work."),
            splitSentences("See WalkModel.kt and v1.2.3 in the docs. Then `a. B` and [x.y](p/q.md) work."),
        )
        assertEquals(listOf("Use a, b, etc.", "Then stop."), splitSentences("Use a, b, etc. Then stop."))
        assertEquals(listOf("Use a, b, etc. and more."), splitSentences("Use a, b, etc. and more."))
        assertEquals(listOf("Wait...", "Then go?!", "Done."), splitSentences("Wait... Then go?! Done."))
    }

    @Test fun `flow area is the flow name for flows docs else root`() {
        assertEquals("diff-walkthrough", flowAreaOf("flows/diff-walkthrough.md"))
        assertEquals("", flowAreaOf("README.md"))
        assertEquals("", flowAreaOf("docs/guide.md"))
    }

    @Test fun `empty or heading only docs have no claims`() {
        assertTrue(parseDocClaims("").isEmpty())
        assertTrue(parseDocClaims("# Title\n\n## Sub\n").isEmpty())
    }
}
