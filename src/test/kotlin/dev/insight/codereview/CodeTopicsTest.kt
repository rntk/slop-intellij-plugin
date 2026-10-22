package dev.insight.codereview

import com.google.gson.JsonParser
import dev.insight.diff.ChangeSummaries
import dev.insight.diff.ChangeTopicGenerator
import dev.insight.diff.ChangeTopics
import dev.insight.diff.SummaryKind
import dev.insight.llm.Completion
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodeTopicsTest {
    private val small = codeFile("src/app/Main.kt", "fun main() = run()\n")
    private val big = codeFile("src/lib/Big.kt", largeSource())
    private val snapshot = codeSnapshot(small, big)
    private val topics = ChangeTopics(snapshot.files)

    @Test fun `without summaries a file shows a bounded excerpt with cut fields`() {
        val evidence = codeTopicEvidence(snapshot) { ChangeSummaries(codeSummaryTree(snapshot.files)) }
        val whole = evidence(small, topics)
        assertEquals(mapOf("path" to small.path, "lines" to 1, "excerpt" to "fun main() = run()"), whole.fields)
        assertFalse(whole.cut)

        val cut = evidence(big, topics)
        val excerpt = cut.fields["excerpt"] as String
        assertTrue(excerpt.length <= CODE_TOPIC_EXCERPT)
        val next = cut.fields["excerpt_cut_at_line"] as Int
        assertEquals(big.lines(1 until next), excerpt)
        assertNull(cut.fields["summary"])
        assertNull(cut.fields["parts"])
        assertTrue("A cut excerpt without a summary may be read further", cut.cut)
    }

    @Test fun `written summaries replace most of the excerpt`() {
        var summaries = ChangeSummaries(codeSummaryTree(snapshot.files))
        val evidence = codeTopicEvidence(snapshot) { summaries }
        // Only the parts are written: they describe the file.
        val parts = summaries.tree.nodes.filter { it.kind == SummaryKind.CHUNK && it.children.isEmpty() }
        summaries = summaries.summarize(parts.associate { it.id to "Part ${it.location} " + "detail ".repeat(100) })
        val partial = evidence(big, topics)
        @Suppress("UNCHECKED_CAST") val shown = partial.fields["parts"] as List<String>
        assertTrue(shown.first().startsWith("1-"))
        assertTrue(shown.sumOf { it.length } <= CODE_TOPIC_PARTS_CHARS)
        assertTrue(shown.size < parts.size)
        assertTrue(partial.cut)

        summaries = fillAll(summaries)
        val full = evidence(big, topics)
        assertEquals("file ${big.path}", full.fields["summary"])
        assertNull(full.fields["parts"])
        assertTrue((full.fields["excerpt"] as String).length <= CODE_TOPIC_EXCERPT_WITH_SUMMARY)
        assertNotNull(full.fields["excerpt_cut_at_line"])
        assertFalse("A summary covers the whole file", full.cut)
        assertEquals(listOf("path", "lines", "summary", "excerpt", "excerpt_cut_at_line"), full.fields.keys.toList())
    }

    @Test fun `the generator classifies every file four at a time with the code prompt`() = runBlocking {
        val files = (1..24).map { codeFile("src/f$it/F$it.kt", "class F$it\n") }
        val snapshot = codeSnapshot(*files.toTypedArray())
        var current = ChangeTopics(snapshot.files)
        var inFlight = 0
        var peak = 0
        val systems = mutableSetOf<String>()
        val generator = ChangeTopicGenerator({ request ->
            synchronized(systems) { inFlight++; peak = maxOf(peak, inFlight); systems += request.messages.first().content }
            val input = JsonParser.parseString(request.messages.last().content).asJsonObject
            assertEquals(listOf("focus", "existing_topics", "path"), input.keySet().take(3))
            assertTrue(input.has("excerpt") && input.has("existing_topics"))
            delay(5)
            synchronized(systems) { inFlight-- }
            Completion("""{"topic":"Core>${input["path"].asString.substringAfterLast('/').substringBefore('.')}"}""")
        }, null, system = CODE_TOPIC_SYSTEM,
            evidence = codeTopicEvidence(snapshot) { ChangeSummaries(codeSummaryTree(snapshot.files)) },
            concurrency = CODE_TOPIC_CONCURRENCY)
        val run = generator.run(current, "", { path, label -> current.assign(path, label).also { current = it } })
        assertTrue(run.topics.complete)
        assertEquals(24, run.requests)
        assertEquals(files.map { it.path }.toSet(), run.topics.labels.keys)
        assertTrue(peak in 2..CODE_TOPIC_CONCURRENCY)
        assertEquals(setOf(CODE_TOPIC_SYSTEM), systems)
        assertTrue(CODE_TOPIC_SYSTEM.contains("excerpt_cut_at_line"))
        assertFalse(CODE_TOPIC_SYSTEM.contains("patch"))
    }
}
