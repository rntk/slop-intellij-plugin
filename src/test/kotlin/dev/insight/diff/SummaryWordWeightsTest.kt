package dev.insight.diff

import dev.insight.codereview.CodeFile
import dev.insight.review.ReviewSummaries
import dev.insight.review.SourceChunk
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import org.junit.Assert.*
import org.junit.Test
import dev.insight.review.ui.SummaryWordWeights
import dev.insight.review.ui.cloudHtml

class SummaryWordWeightsTest {
    private fun file(path: String, patch: String = "@@ -1 +1 @@\n-old\n+new\n") = ReviewFile(path, "/repo/$path", null, "modified", patch, "1")

    @Test fun `each parent counts only direct children including repetitions and ignoring case`() {
        val tree = buildSummaryTree(listOf(
            file("app/a.kt", "@@ -1,2 +1,2 @@\n-old old\n+new\n"),
            file("app/b.kt"),
            file("docs/c.md"),
            file("docs/d.md"),
        ))
        val app = tree.nodes.single { it.path == "app" }
        val docs = tree.nodes.single { it.path == "docs" }
        val summaries = ReviewSummaries(tree, mapOf(
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
        assertEquals(setOf("old", "new"), weights[app.children[0]].keys)
        assertTrue(weights[app.children[0]].getValue("old") > weights[app.children[0]].getValue("new"))
        val changedSibling = ReviewSummaries(tree, tree.nodes.mapNotNull { node -> summaries.text(node.id)?.let {
            node.id to if (node.id == docs.children[0]) "cache ".repeat(100) else it
        } }.toMap())
        assertEquals(weights[app.id], SummaryWordWeights(changedSibling)[app.id])
    }

    @Test fun `missing children contribute nothing and a new summary version has its own counts`() {
        val tree = buildSummaryTree(listOf(file("a.kt"), file("b.kt")))
        val original = ReviewSummaries(tree, mapOf(tree.root.children.first() to "Café café read_diff_files `View.kt`."))
        val weights = SummaryWordWeights(original)[0]
        assertEquals(setOf("café", "read_diff_files", "view.kt"), weights.keys)
        assertTrue(weights.getValue("café") > weights.getValue("read_diff_files"))
        assertEquals(weights["read_diff_files"], weights["view.kt"])
        assertEquals(setOf("rewritten"), SummaryWordWeights(ReviewSummaries(tree,
            mapOf(tree.root.children.last() to "Rewritten")))[0].keys)
        assertTrue(SummaryWordWeights(ReviewSummaries(tree))[0].isEmpty())
    }

    @Test fun `files with parts count their part summaries and part leaves count their own source lines`() {
        val source = (1..20).joinToString("\n") { line ->
            if (line <= 10) "fun parseToken() = token" else "fun cacheValue() = value"
        } + "\n"
        val codeFile = CodeFile("large.kt", "/repo/large.kt", source)
        val tree = buildSummaryTree(listOf(codeFile), chunks = {
            listOf(SourceChunk(0, 1..10, "a"), SourceChunk(1, 11..20, "b"))
        })
        val file = tree.nodes.single { it.kind == SummaryKind.FILE }
        val summaries = ReviewSummaries(tree, mapOf(file.id to "Parsing", file.children[0] to "Parsing parsing", file.children[1] to "Parsing"))
        val weights = SummaryWordWeights(summaries)
        assertEquals(setOf("parsing"), weights[file.id].keys)
        assertTrue(weights[file.id].getValue("parsing") > weights[tree.root.id].getValue("parsing"))
        assertTrue(weights[tree.root.id].getValue("parsing") > 0.0)
        assertEquals(setOf("fun", "parsetoken", "token"), weights[file.children[0]].keys)
        assertEquals(setOf("fun", "cachevalue", "value"), weights[file.children[1]].keys)
    }

    @Test fun `leaf files count diff hunks for diffs and the whole file for source reviews`() {
        val diffFile = ReviewFile(
            "diff.kt", "/repo/diff.kt", null, "modified",
            "diff --git a/diff.kt b/diff.kt\n--- a/diff.kt\n+++ b/diff.kt\n@@ -1,2 +1,2 @@\n contextLine\n-oldToken\n+newToken newToken\n",
            "1",
            before = "unseenBefore\ncontextLine\noldToken\n",
            after = "unseenAfter\ncontextLine\nnewToken newToken\n",
        )
        val codeFile = CodeFile("code.kt", "/repo/code.kt", "class TokenCache {\n    val token = 1\n    val other = token\n}\n")
        val tree = buildSummaryTree(listOf(diffFile, codeFile))
        val diffNode = tree.nodes.single { it.path == "diff.kt" }
        val codeNode = tree.nodes.single { it.path == "code.kt" }
        val weights = SummaryWordWeights(ReviewSummaries(tree))
        assertEquals(setOf("contextline", "oldtoken", "newtoken"), weights[diffNode.id].keys)
        assertTrue(weights[diffNode.id].getValue("newtoken") > weights[diffNode.id].getValue("oldtoken"))
        assertFalse("diff" in weights[diffNode.id])
        assertFalse("unseenafter" in weights[diffNode.id])
        assertEquals(setOf("class", "tokencache", "val", "token", "1", "other"), weights[codeNode.id].keys)
        assertTrue(weights[codeNode.id].getValue("token") > weights[codeNode.id].getValue("tokencache"))
    }

    @Test fun `words spread across every child have less weight than equally frequent distinctive words`() {
        val tree = buildSummaryTree(listOf(file("a.kt"), file("b.kt"), file("c.kt")))
        val (a, b, c) = tree.root.children
        val weights = SummaryWordWeights(ReviewSummaries(tree, mapOf(
            a to "shared focused focused focused", b to "shared", c to "shared",
        )))[0]
        assertTrue(weights.getValue("focused") > weights.getValue("shared"))
        assertTrue(weights.getValue("shared") > 0.0)
    }

    @Test fun `filler words neither grow nor dilute the emphasis of meaningful words`() {
        val tree = buildSummaryTree(listOf(file("a.kt"), file("b.kt")))
        val (a, b) = tree.root.children
        val baseline = SummaryWordWeights(ReviewSummaries(tree, mapOf(a to "Token cache", b to "token")))[0]
        val withFiller = SummaryWordWeights(ReviewSummaries(tree, mapOf(
            a to "Token cache " + "AND the or ".repeat(100), b to "token with and",
        )))[0]
        assertEquals(baseline, withFiller)
        assertTrue(SummaryWordWeights(ReviewSummaries(tree, mapOf(a to "And the or", b to "with")))[0].isEmpty())
    }

    @Test fun `cloud html sizes weighted words only and escapes the rest`() {
        val html = cloudHtml("Cache <b>token</b> & the end", mapOf("cache" to 1.0, "token" to 0.5), 10f)!!
        assertEquals("<span style='font-size:18pt'>Cache</span> &lt;b&gt;<span style='font-size:14pt'>token</span>&lt;/b&gt; &amp; the end", html)
    }

    @Test fun `cloud html is absent when no word has a weight`() {
        assertNull(cloudHtml("nothing weighted here", mapOf("other" to 1.0), 10f))
        assertNull(cloudHtml("anything", emptyMap(), 10f))
    }
}
