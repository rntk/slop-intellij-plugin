package dev.insight.diff

import org.junit.Assert.*
import org.junit.Test

class SummaryWordWeightsTest {
    private fun file(path: String) = ReviewFile(path, "/repo/$path", null, "modified", "-old\n+new\n", "1")

    @Test fun `each parent counts only direct children including repetitions and ignoring case`() {
        val tree = buildSummaryTree(listOf(file("app/a.kt"), file("app/b.kt"), file("docs/c.md"), file("docs/d.md")))
        val app = tree.nodes.single { it.path == "app" }
        val docs = tree.nodes.single { it.path == "docs" }
        val summaries = ChangeSummaries(tree, mapOf(
            tree.root.id to "ParentOnly parentOnly token",
            app.id to "Token cache",
            docs.id to "Docs",
            app.children[0] to "TOKEN, token; cache!",
            app.children[1] to "token cache cache",
            docs.children[0] to "token ".repeat(50),
            docs.children[1] to "docs",
        ))
        val weights = SummaryWordWeights(summaries)
        assertEquals(setOf("token", "cache"), weights[app.id].keys)
        assertEquals(weights[app.id]["token"], weights[app.id]["cache"])
        assertEquals(setOf("token", "cache", "docs"), weights[tree.root.id].keys)
        assertEquals(1, weights[tree.root.id].values.toSet().size)
        assertTrue(weights[docs.id].getValue("token") > weights[app.id].getValue("token"))
        assertTrue(weights[app.children[0]].isEmpty())
        val changedSibling = ChangeSummaries(tree, tree.nodes.mapNotNull { node -> summaries.text(node.id)?.let {
            node.id to if (node.id == docs.children[0]) "cache ".repeat(100) else it
        } }.toMap())
        assertEquals(weights[app.id], SummaryWordWeights(changedSibling)[app.id])
    }

    @Test fun `missing children contribute nothing and a new summary version has its own counts`() {
        val tree = buildSummaryTree(listOf(file("a.kt"), file("b.kt")))
        val original = ChangeSummaries(tree, mapOf(tree.root.children.first() to "Café café read_diff_files `View.kt`."))
        val weights = SummaryWordWeights(original)[0]
        assertEquals(setOf("café", "read_diff_files", "view.kt"), weights.keys)
        assertTrue(weights.getValue("café") > weights.getValue("read_diff_files"))
        assertEquals(weights["read_diff_files"], weights["view.kt"])
        assertEquals(setOf("rewritten"), SummaryWordWeights(ChangeSummaries(tree,
            mapOf(tree.root.children.last() to "Rewritten")))[0].keys)
        assertTrue(SummaryWordWeights(ChangeSummaries(tree))[0].isEmpty())
    }

    @Test fun `files with parts count their part summaries and part leaves have no counts`() {
        val tree = buildSummaryTree(listOf(file("large.kt")), chunks = {
            listOf(SourceChunk(0, 1..10, "a"), SourceChunk(1, 11..20, "b"))
        })
        val file = tree.nodes.single { it.kind == SummaryKind.FILE }
        val summaries = ChangeSummaries(tree, mapOf(file.id to "Parsing", file.children[0] to "Parsing parsing", file.children[1] to "Parsing"))
        val weights = SummaryWordWeights(summaries)
        assertEquals(setOf("parsing"), weights[file.id].keys)
        assertTrue(weights[file.id].getValue("parsing") > weights[tree.root.id].getValue("parsing"))
        assertTrue(weights[tree.root.id].getValue("parsing") > 0.0)
        assertTrue(file.children.all { weights[it].isEmpty() })
    }

    @Test fun `words spread across every child have less weight than equally frequent distinctive words`() {
        val tree = buildSummaryTree(listOf(file("a.kt"), file("b.kt"), file("c.kt")))
        val (a, b, c) = tree.root.children
        val weights = SummaryWordWeights(ChangeSummaries(tree, mapOf(
            a to "shared focused focused focused", b to "shared", c to "shared",
        )))[0]
        assertTrue(weights.getValue("focused") > weights.getValue("shared"))
        assertTrue(weights.getValue("shared") > 0.0)
    }

    @Test fun `filler words neither grow nor dilute the emphasis of meaningful words`() {
        val tree = buildSummaryTree(listOf(file("a.kt"), file("b.kt")))
        val (a, b) = tree.root.children
        val baseline = SummaryWordWeights(ChangeSummaries(tree, mapOf(a to "Token cache", b to "token")))[0]
        val withFiller = SummaryWordWeights(ChangeSummaries(tree, mapOf(
            a to "Token cache " + "AND the or ".repeat(100), b to "token with and",
        )))[0]
        assertEquals(baseline, withFiller)
        assertTrue(SummaryWordWeights(ChangeSummaries(tree, mapOf(a to "And the or", b to "with")))[0].isEmpty())
    }
}
