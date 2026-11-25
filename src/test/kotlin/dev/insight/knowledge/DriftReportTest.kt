package dev.insight.knowledge

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftReportTest {
    private val finding = DriftFinding(
        DriftKind.STALE, "c-1", "flows/demo.md", 12, 14, "The demo parses input.",
        listOf(Anchor(AnchorKind.FILE, "src/Parser.kt", "fp")), "The code no longer supports this statement (accuracy 0.10).",
    )
    private val patch = DocPatch("flows/demo.md", 12, 12, "parses", "reads", "renamed")

    @Test fun `markdown groups by doc and links doc lines`() {
        val report = DriftReport(
            listOf(
                finding,
                finding.copy(kind = DriftKind.BROKEN_ANCHOR, claimId = "c-2", startLine = 20, endLine = 20, reason = "Anchor no longer exists: src/Gone.kt"),
                finding.copy(kind = DriftKind.OK, claimId = "c-3", startLine = 30, endLine = 30),
                finding.copy(kind = DriftKind.UNANCHORED, claimId = "c-4", doc = "flows/other.md", startLine = 5, endLine = 5, anchors = emptyList()),
            ),
            listOf(patch), listOf("Decisions are not configured."),
            DriftCounts(docs = 2, claims = 4, added = 4, byKind = mapOf(DriftKind.STALE to 1, DriftKind.OK to 1)),
        )
        val md = report.toMarkdown()
        assertTrue(md, md.contains("## flows/demo.md"))
        assertTrue(md.contains("## flows/other.md"))
        assertTrue(md.contains("- flows/demo.md:12-14"))
        assertTrue(md.contains("- flows/demo.md:20\n"))
        assertTrue(md.contains("- flows/other.md:5\n"))
        assertTrue(md.contains("Statement: The demo parses input."))
        assertTrue(md.contains("Anchors: file src/Parser.kt"))
        assertTrue(md.contains("Reason: Anchor no longer exists: src/Gone.kt"))
        assertTrue(md.contains("### Suggested edits (1)"))
        assertTrue(md.contains("- New: reads"))
        assertTrue(md.contains("## Warnings"))
        assertFalse("OK findings are only counted", md.contains("flows/demo.md:30"))
        assertTrue(md.indexOf("Broken anchor") < md.indexOf("### Stale"))
    }

    @Test fun `an empty report says no drift`() {
        assertTrue(DriftReport(emptyList(), emptyList(), emptyList(), DriftCounts()).toMarkdown().contains("No drift found."))
    }

    private val lines = (1..20).map { "line $it has the old words in it." }
    private val allowed = listOf(10..12)

    private fun parse(json: String) = parseDocPatches(JsonParser.parseString(json), "d.md", lines, allowed)

    @Test fun `valid patches are read`() {
        val parsed = parse("""{"patches":[{"start_line":11,"end_line":11,"old":"has the old words","new":"has the new words","reason":"r"}]}""")
        assertEquals(emptyList<String>(), parsed.problems)
        assertEquals(DocPatch("d.md", 11, 11, "has the old words", "has the new words", "r"), parsed.patches.single())
    }

    @Test fun `camel case keys, a bare array and an empty new text are accepted`() {
        val parsed = parse("""[{"startLine":"10","endLine":10,"old":"line 10 has","new":"","reason":"drop"}]""")
        assertEquals(1, parsed.patches.size)
        assertEquals("", parsed.patches.single().new)
    }

    @Test fun `invalid entries are dropped with a problem each`() {
        val parsed = parse("""
            {"patches":[
              "oops",
              {"start_line":1,"end_line":1,"old":"line 1","new":"x"},
              {"start_line":11,"end_line":11,"old":"not in the line","new":"x"},
              {"start_line":99,"end_line":99,"old":"x","new":"y"},
              {"start_line":12,"end_line":10,"old":"x","new":"y"},
              {"start_line":10,"old":"","new":"y"},
              {"start_line":10,"old":"line 10","new":"line 10"},
              {"end_line":"abc","old":"line 10","new":"y"},
              {"start_line":10,"end_line":10,"old":"line 10 has","new":"line 10 holds","reason":"ok"}
            ]}""")
        assertEquals(1, parsed.patches.size)
        assertEquals(8, parsed.problems.size)
    }

    @Test fun `answers without a list give a problem and no patches`() {
        for (json in listOf("{}", """{"patches":"none"}""", "42", "null")) {
            val parsed = parse(json)
            assertTrue(parsed.patches.isEmpty())
            assertEquals(1, parsed.problems.size)
        }
        assertTrue(parseDocPatches(null, "d.md", lines, allowed).patches.isEmpty())
    }
}
