package dev.insight.diff

import com.google.gson.JsonParser
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.review.ReviewSummarizer
import dev.insight.review.ReviewSummaryOptions
import dev.insight.review.ReviewSummaries
import dev.insight.review.SummarySource.Rows
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
}
