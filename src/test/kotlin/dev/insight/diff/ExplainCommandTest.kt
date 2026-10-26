package dev.insight.diff

import dev.insight.review.ExplainTarget
import dev.insight.review.ReviewSummaries
import dev.insight.review.SummaryLevel
import dev.insight.review.buildSummaryTree
import dev.insight.review.chainTo
import dev.insight.review.explainCommand
import dev.insight.review.explainRequest
import dev.insight.review.isExplainRequest
import dev.insight.review.textOf
import dev.insight.review.typedExplainTarget
import org.junit.Assert.*
import org.junit.Test

class ExplainCommandTest {
    private fun file(path: String) = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", "1")
    private val paths = listOf("src/app/Cache.kt", "src/app/Store.kt", "docs/README.md")
    private val tree = buildSummaryTree(paths.map(::file))
    private val summaries = ReviewSummaries(tree).summarize(tree.nodes.associate { it.id to "Summary of ${it.identity}." })

    @Test fun `only the explain command is an explain request`() {
        assertTrue(isExplainRequest("/explain"))
        assertTrue(isExplainRequest(" /explain src/app why"))
        assertTrue(isExplainRequest("/explain\nwhy is it cached"))
        assertFalse(isExplainRequest("/explainer"))
        assertFalse(isExplainRequest("explain this"))
    }

    @Test fun `the command keeps the location on its first line and the instructions below`() {
        assertEquals("/explain src/app/", explainCommand("src/app/", "  "))
        assertEquals("/explain src/app/\nWhy a cache?\nFocus on errors.", explainCommand("src/app/", " Why a cache?\nFocus on errors. "))
    }

    @Test fun `a typed folder explains the files beneath it with its summary and the rest as instructions`() {
        val target = typedExplainTarget("/explain src/app/ why is the store separate?", summaries, paths)
        assertEquals("src/app/", target.label)
        assertEquals(listOf("src/app/Cache.kt", "src/app/Store.kt"), target.files)
        assertEquals(listOf("Summary of root.", "Summary of directory:src.", "Summary of directory:src/app."), target.context.map { it.text })
        assertEquals("why is the store separate?", target.instructions)
        assertNull(target.lines)
    }

    @Test fun `a typed file with a line range explains those lines`() {
        val target = typedExplainTarget("/explain ./src/app/Cache.kt:10-24", summaries, paths)
        assertEquals(listOf("src/app/Cache.kt"), target.files)
        assertEquals(10..24, target.lines)
        assertEquals("Summary of file:src/app/Cache.kt.", target.context.last().text)
        assertEquals("", target.instructions)
    }

    @Test fun `no path, a dot or a word that names nothing in scope explains the whole scope`() {
        listOf("/explain", "/explain .").forEach { command ->
            val target = typedExplainTarget(command, summaries, paths)
            assertEquals(paths, target.files)
            assertEquals(listOf("Summary of root."), target.context.map { it.text })
            assertEquals("", target.instructions)
        }
        val question = typedExplainTarget("/explain how does caching work", summaries, paths)
        assertEquals(paths, question.files)
        assertEquals("how does caching work", question.instructions)
        assertEquals("", typedExplainTarget("/explain . ", summaries, paths).instructions)
        assertEquals("focus on errors", typedExplainTarget("/explain . focus on errors", summaries, paths).instructions)
    }

    @Test fun `a link target is the summary entry with what the dialog asked`() {
        val node = tree.nodes.single { it.location == "src/app/Store.kt" }
        assertEquals("Summary of file:src/app/Store.kt.", summaries.textOf(node))
        // A version of the tree that is not summarized yet has no text for the entry.
        assertNull(ReviewSummaries(tree).textOf(node))
    }

    @Test fun `the context chains the summaries from the root down to the entry`() {
        val node = tree.nodes.single { it.location == "src/app/Store.kt" }
        assertEquals(listOf("All changes", "src/", "src/app/", "src/app/Store.kt"), summaries.chainTo(node).map { it.label })
        assertEquals(listOf("Summary of root.", "Summary of directory:src.", "Summary of directory:src/app.", "Summary of file:src/app/Store.kt."),
            summaries.chainTo(node).map { it.text })
        // Missing levels are skipped, and a level that repeats the one below adds nothing.
        val folder = tree.nodes.single { it.location == "src/app/" }
        val partial = ReviewSummaries(tree).summarize(tree.nodes.filter { it.id != tree.root.id }
            .associate { it.id to if (it.id == folder.id) "Same." else "Summary of ${it.identity}." }, reuseSingleChildren = false)
        assertEquals(listOf("src/", "src/app/", "src/app/Store.kt"), partial.chainTo(node).map { it.label })
        val repeated = summaries.summarize(mapOf(node.id to "Same.", folder.id to "Same."), reuseSingleChildren = false)
        assertEquals(listOf("All changes", "src/", "src/app/Store.kt"), repeated.chainTo(node).map { it.label })
        assertEquals(emptyList<SummaryLevel>(), ReviewSummaries(tree).chainTo(node))
    }

    @Test fun `the request asks what, how and why, with the context chain and the user's instructions first`() {
        val target = ExplainTarget("src/app/", listOf("src/app/Cache.kt", "src/app/Store.kt"),
            context = listOf(SummaryLevel("All changes", "Adds offline mode."), SummaryLevel("src/app/", "Caches reads.")),
            instructions = "Why is the store separate?")
        val request = explainRequest(target, diff = true, chart = """{"revision":3,"functions":[]}""", chartNotice = null)
        assertTrue(request.startsWith("Explain src/app/ (2 files):\n- src/app/Cache.kt\n- src/app/Store.kt"))
        assertTrue(request.contains("1. All changes: Adds offline mode.\n2. src/app/ (this entry): Caches reads."))
        assertTrue(request.indexOf("Adds offline mode.") < request.indexOf("Why is the store separate?"))
        assertTrue(request.indexOf("Why is the store separate?") < request.indexOf("What: "))
        assertTrue(request.contains("How: ") && request.contains("Why: "))
        assertTrue(request.contains("say so instead of guessing"))
        assertTrue(request.contains("read_diff_files"))
        assertTrue(request.contains("add_diff_notes"))
        assertTrue(request.contains("present_story"))
        assertTrue(request.contains("""{"revision":3,"functions":[]}"""))
        val code = explainRequest(target.copy(lines = 5..9, context = emptyList(), instructions = ""), diff = false, chart = null, chartNotice = "the graph is building.")
        assertTrue(code.startsWith("Explain src/app/, lines 5-9"))
        assertTrue(code.contains("read_code"))
        assertTrue(code.contains("add_code_notes"))
        assertFalse(code.contains("present_story"))
        assertTrue(code.contains("No diagrams on the Flow Chart: the graph is building."))
        assertFalse(code.contains("The user asks"))
        assertFalse(code.contains("Context, from"))
    }

    @Test fun `long scopes list a bounded number of files`() {
        val many = (1..100).map { "src/F$it.kt" }
        val request = explainRequest(ExplainTarget("src/", many), diff = false, chart = null, chartNotice = null)
        assertTrue(request.contains("- src/F40.kt\n- … and 60 more"))
        assertFalse(request.contains("src/F41.kt"))
    }
}
