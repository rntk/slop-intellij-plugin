package dev.insight.diff

import com.google.gson.JsonParser
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import dev.insight.llm.LlmApiException
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.ReviewSummaries
import dev.insight.review.SummarySource.Rows
import dev.insight.review.SummarySource.Claim
import dev.insight.review.Summary
import dev.insight.review.SummaryKind
import dev.insight.review.buildSummaryTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SummaryRequestRegressionTest {
    private fun decode(json: String, count: Int = 1) = ReviewSummarizer.decode(
        JsonParser.parseString(json).asJsonObject, List(count) { ReviewSummarizer.Citations.Lines(1..138) })

    @Test fun `offsets reversed ranges and mixed invalid citations are rejected instead of clipped`() {
        for (sources in listOf("\"35-5982\"", "\"3-2\"", "\"1\",\"999\"", "\"999999999999999999999\"")) {
            val decoded = decode("""{"summaries":[{"id":1,"segments":[{"text":"Removes tests.","from":[$sources]}]}]}""")
            assertTrue(sources, decoded.summaries.isEmpty())
            assertTrue(sources, decoded.problems.getValue(1).contains("Invalid citations"))
        }
    }

    @Test fun `bare leaf output explains the missing envelope`() {
        val decoded = decode("""{"id":1,"segments":[{"text":"Adds retries.","from":["1"]}]}""")
        assertTrue(decoded.summaries.isEmpty())
        assertTrue(decoded.problems.getValue(1).contains("Missing summaries array"))
    }

    @Test fun `children returned as nodes reject the answer even when the first id is valid`() {
        val decoded = decode("""{"summaries":[{"id":1,"segments":[{"text":"Parent.","from":["1"]}]},
            {"id":2,"segments":[{"text":"Child.","from":["1"]}]}]}""")
        assertTrue(decoded.summaries.isEmpty())
        assertTrue(decoded.problems.getValue(1).contains("children are evidence"))
        val schema = ReviewSummarizer.schema(1).getAsJsonObject("properties").getAsJsonObject("summaries")
        assertEquals(1, schema["minItems"].asInt)
        assertEquals(1, schema["maxItems"].asInt)
        assertEquals("[1]", schema.getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("id")["enum"].toString())
    }

    @Test fun `late patch changes survive bounded reductions and keep original line citations`() = runBlocking {
        val patch = (1..12).joinToString("\n", postfix = "\n") { "+change$it" }
        val file = ReviewFile("a.kt", "/repo/a.kt", null, "modified", patch, "1")
        val tree = buildSummaryTree(listOf(file))
        val requests = mutableListOf<CompletionRequest>()
        val seen = mutableListOf<Int>()
        val complete: suspend (CompletionRequest) -> Completion = { request ->
            requests += request
            val input = JsonParser.parseString(request.messages.last().content).asJsonObject
            assertEquals("[1]", input["required_output_ids"].toString())
            val node = input["nodes"].asJsonArray.single().asJsonObject
            val source = if (node.has("patch")) {
                val rows = node["patch"].asString.lines().map { it.substringBefore('|').toInt() }
                seen += rows
                rows.last().toString()
            } else {
                val children = node["children"].asJsonArray
                assertTrue(children.size() <= 2)
                children.last().asJsonObject["claims"].asJsonArray.last().asJsonObject["cite"].asString
            }
            Completion("""{"summaries":[{"id":1,"segments":[{"text":"Describes the final change.","from":["$source"]}]}]}""")
        }
        var saved = ReviewSummaries(tree)
        val result = diffSummarizer(complete, null, ReviewSummaryOptions(patchChars = 24, foldersPerRequest = 2))
            .run(saved, "", { saved.summarize(it).also { next -> saved = next } })
        assertTrue(result.error, result.summaries.complete)
        assertEquals((1..12).toList(), seen)
        assertEquals(listOf(Rows(12, 12)), result.summaries.summary(tree.nodes.single { it.path == "a.kt" }.id)!!.segments.single().sources)
        assertTrue(requests.all { it.responseFormat != null && it.tools.isEmpty() })
    }

    @Test fun `unchanged documentation cannot establish a change and rejection explains the retry`() = runBlocking {
        val file = ReviewFile("flows/summaries.md", "/repo/flows/summaries.md", null, "modified",
            "@@ -1,2 +1,3 @@\n+Choosing a level folds deeper folders.\n Readers can mark files reviewed.\n", "1")
        val tree = buildSummaryTree(listOf(file))
        var saved = ReviewSummaries(tree)
        val requests = mutableListOf<CompletionRequest>()
        val result = diffSummarizer({ request ->
            requests += request
            val node = JsonParser.parseString(request.messages.last().content).asJsonObject["nodes"].asJsonArray.single().asJsonObject
            assertEquals("documentation", node["evidence_kind"].asString)
            assertEquals(1, node["added_lines"].asInt)
            if (requests.size == 1) {
                Completion("""{"summaries":[{"id":1,"segments":[{"text":"Allows marking files reviewed.","from":["3"]}]}]}""")
            } else {
                assertTrue(node["previous_answer_rejected"].asString.contains("only unchanged context"))
                Completion("""{"summaries":[{"id":1,"segments":[{"text":"Documents level selection.","from":["2-3"]}]}]}""")
            }
        }, null).run(saved, "", { saved.summarize(it).also { next -> saved = next } })
        assertTrue(result.error, result.summaries.complete)
        assertEquals(2, requests.size)
        assertEquals("Documents level selection.", result.summaries.text(tree.root.id))
    }

    @Test fun `removed test pages retain provenance counts and enclosing hunk across long line splits`() {
        val patch = "@@ -1,3 +1 @@ fun oldRankingTest()\n-" + "old assertion ".repeat(20) + "\n context\n+new assertion\n"
        val file = ReviewFile("src/test/kotlin/RankingTest.kt", "/repo/RankingTest.kt", null, "modified", patch, "1")
        val node = buildSummaryTree(listOf(file)).nodes.single { it.kind == SummaryKind.FILE }
        val pages = DiffSummaryEvidence().pages(node, 65)!!
        assertTrue(pages.size > 2)
        val removalPages = pages.filter { it.changedRows == setOf(2) }
        assertTrue(removalPages.size > 1)
        assertTrue(removalPages.any { it.evidence["first_line_continues"].asBoolean })
        for (page in removalPages) {
            assertEquals("test", page.evidence["evidence_kind"].asString)
            assertEquals(0, page.evidence["added_lines"].asInt)
            assertEquals(1, page.evidence["removed_lines"].asInt)
            assertEquals("@@ -1,3 +1 @@ fun oldRankingTest()", page.evidence["hunk_header"].asString)
        }
        assertTrue(pages.any { 4 in it.changedRows.orEmpty() })
    }

    @Test fun `context only pages are skipped without dropping late changes`() {
        val patch = "@@ -1,30 +1,32 @@\n+first\n" + " context\n".repeat(30) + "+last\n"
        val file = ReviewFile("a.kt", "/repo/a.kt", null, "modified", patch, "1")
        val node = buildSummaryTree(listOf(file)).nodes.single { it.kind == SummaryKind.FILE }
        val pages = DiffSummaryEvidence().pages(node, 40)!!
        assertEquals(2, pages.size)
        assertEquals(setOf(2, 33), pages.flatMap { it.changedRows.orEmpty() }.toSet())
        assertTrue(pages.all { it.evidence["hunk_header"].asString == "@@ -1,30 +1,32 @@" })
    }

    @Test fun `page starting in trailing context uses the hunk header of its first change`() {
        val patch = "@@ -1,2 +1,2 @@ fun firstFunctionName()\n+one\n trailing\n@@ -20,2 +20,2 @@ fun second()\n+two\n"
        val file = ReviewFile("a.kt", "/repo/a.kt", null, "modified", patch, "1")
        val node = buildSummaryTree(listOf(file)).nodes.single { it.kind == SummaryKind.FILE }
        val pages = DiffSummaryEvidence().pages(node, 46)!!
        assertEquals(2, pages.size)
        assertEquals("@@ -1,2 +1,2 @@ fun firstFunctionName()", pages[0].evidence["hunk_header"].asString)
        assertEquals(3, pages[1].rows.first)
        assertEquals(setOf(5), pages[1].changedRows)
        assertEquals("@@ -20,2 +20,2 @@ fun second()", pages[1].evidence["hunk_header"].asString)
    }

    @Test fun `truncated branch batch is split before increasing its token budget`() = runBlocking {
        val files = (1..4).flatMap { folder -> listOf("a", "b").map { name ->
            ReviewFile("group$folder/$name.kt", "/repo/group$folder/$name.kt", null, "modified", "+new\n", "1")
        } }
        val tree = buildSummaryTree(files)
        var saved = ReviewSummaries(tree).summarize(tree.nodes.filter { it.kind == SummaryKind.FILE }
            .associate { it.id to Summary.plain("Adds ${it.path}.") })
        val sizes = mutableListOf<Int>()
        val budgets = mutableListOf<Int?>()
        val result = diffSummarizer({ request ->
            val nodes = JsonParser.parseString(request.messages.last().content).asJsonObject["nodes"].asJsonArray
            sizes += nodes.size()
            budgets += request.maxTokens
            if (sizes.size == 1) Completion("", finishReason = FinishReason.TRUNCATED)
            else Completion(nodes.joinToString(prefix = "{\"summaries\":[", postfix = "]}") { value ->
                val node = value.asJsonObject
                """{"id":${node["id"]},"segments":[{"text":"Updates ${node["location"].asString}.","from":["C1.S1"]}]}"""
            })
        }, null).run(saved, "", { saved.summarize(it).also { next -> saved = next } })
        assertTrue(result.error, result.summaries.complete)
        assertEquals(listOf(4, 2, 2, 1), sizes)
        assertTrue(budgets.toString(), budgets.all { it == 10_000 })
    }

    @Test fun `identical child summaries are reused with citations to every child`() = runBlocking {
        val files = listOf("a.kt", "b.kt").map { ReviewFile(it, "/repo/$it", null, "modified", "+retry()\n", "1") }
        val tree = buildSummaryTree(files)
        var saved = ReviewSummaries(tree)
        var requests = 0
        val result = diffSummarizer({ request ->
            requests++
            val nodes = JsonParser.parseString(request.messages.last().content).asJsonObject["nodes"].asJsonArray
            Completion(nodes.joinToString(prefix = "{\"summaries\":[", postfix = "]}") { value ->
                """{"id":${value.asJsonObject["id"]},"segments":[{"text":"Adds retries.","from":["1"]}]}"""
            })
        }, null).run(saved, "", { saved.summarize(it).also { next -> saved = next } })
        assertTrue(result.summaries.complete)
        assertEquals(1, requests)
        assertEquals("Adds retries.", result.summaries.text(tree.root.id))
        assertEquals(listOf(Claim(0, 0), Claim(1, 0)), result.summaries.summary(tree.root.id)!!.segments.single().sources)
    }

    @Test fun `reusable branch waves larger than the per call limit are chunked`() = runBlocking {
        val files = (1..90).map { ReviewFile("dir$it/a.kt", "/repo/dir$it/a.kt", null, "modified", "+retry()\n", "1") }
        val tree = buildSummaryTree(files)
        var saved = ReviewSummaries(tree)
        tree.nodes.filter { it.kind == SummaryKind.FILE }.chunked(ReviewSummaries.MAX_PER_CALL).forEach { batch ->
            saved = saved.summarize(batch.associate { it.id to Summary.plain("Adds retries.") }, reuseSingleChildren = false)
        }
        val result = diffSummarizer({ error("Expected no model requests") }, null).run(saved, "", {
            saved.summarize(it, reuseSingleChildren = false).also { next -> saved = next }
        })
        assertTrue(result.summaries.complete)
        assertEquals(0, result.requests)
        assertEquals("Adds retries.", result.summaries.text(tree.root.id))
    }

    @Test fun `successful split halves stay saved when a later half fails`() = runBlocking {
        val files = listOf("a/one.kt", "a/two.kt", "b/one.kt", "b/two.kt").map {
            ReviewFile(it, "/repo/$it", null, "modified", "+new\n", "1")
        }
        val tree = buildSummaryTree(files)
        var saved = ReviewSummaries(tree).summarize(tree.nodes.filter { it.kind == SummaryKind.FILE }
            .associate { it.id to Summary.plain("Adds ${it.path}.") })
        var requests = 0
        try {
            diffSummarizer({ _ ->
                when (++requests) {
                    1 -> Completion("", finishReason = FinishReason.TRUNCATED)
                    2 -> Completion("""{"summaries":[{"id":1,"segments":[{"text":"Updates a.","from":["C1.S1"]}]}]}""")
                    else -> throw LlmApiException(401, "{}", null)
                }
            }, null).run(saved, "", { saved.summarize(it).also { next -> saved = next } })
            fail("Expected the server error")
        } catch (_: LlmApiException) { }
        assertEquals(3, requests)
        assertEquals("Updates a.", saved.text(tree.nodes.single { it.path == "a" }.id))
        assertNull(saved.text(tree.nodes.single { it.path == "b" }.id))
    }
}
