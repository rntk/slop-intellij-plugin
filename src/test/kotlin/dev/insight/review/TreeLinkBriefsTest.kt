package dev.insight.review

import dev.insight.graph.RelationKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmProtocolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TreeLinkBriefsTest {
    @Test fun `replies are flattened into one line with inline code`() {
        assertEquals("Calls `Store.save` to persist drafts and reloads them.",
            oneLineBrief("- Calls `Store.save` to persist drafts\n\n* and **reloads** them."))
        assertEquals("Parses settings", oneLineBrief("  \"Parses   settings\"  "))
        assertEquals("Reads files", oneLineBrief("1. Reads files"))
        val long = oneLineBrief("word ".repeat(100), maxChars = 40)
        assertEquals(40, long.length)
        assertTrue(long.endsWith("…"))
        assertFalse(long.contains('\n'))
    }

    @Test fun `link brief sends the connection evidence and returns one line`() = runBlocking {
        var request: CompletionRequest? = null
        val site = TreeConnectionSite(5, 2, listOf(TreeConnectionOccurrence(RelationKind.CALL, 5, 2,
            CodeSnippet(5, 5, listOf("api.run()")), CodeSnippet(2, 2, listOf("fun run() {}")))))
        val briefer = TreeBriefer({ request = it; Completion("calls `Api.run`\nto start the service") }, emptyList())

        assertEquals("calls `Api.run` to start the service", briefer.link("src/Client.kt", "src/Api.kt", site))
        val system = request!!.messages.first().content
        assertTrue(system.contains("one line", ignoreCase = true))
        val user = request!!.messages.last().content
        assertTrue(user.contains("Source file: src/Client.kt"))
        assertTrue(user.contains("CALL: src/Client.kt:5 → src/Api.kt:2"))
        assertTrue(user.contains("5| api.run()"))
    }

    @Test fun `file brief names the file and blank replies fail`() = runBlocking {
        var request: CompletionRequest? = null
        assertEquals("Runs the service", TreeBriefer({ request = it; Completion("Runs the service") }, emptyList()).file("src/Api.kt"))
        assertTrue(request!!.messages.last().content.contains("src/Api.kt"))
        try {
            TreeBriefer({ Completion(" - \n") }, emptyList()).file("src/Api.kt")
            fail("Expected a protocol error")
        } catch (expected: LlmProtocolException) {
            assertTrue(expected.message!!.contains("empty"))
        }
    }

    @Test fun `briefs every link and the file, reporting progress and isolating failures`() = runBlocking {
        val incoming = TreeConnectionSite(1, 1)
        val outgoing = TreeConnectionSite(2, 2)
        val broken = TreeConnectionSite(3, 3)
        val connections = TreeFileConnections(
            incoming = mapOf("Client.kt" to incoming),
            outgoing = mapOf("Model.kt" to outgoing, "Broken.kt" to broken),
        )
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, String>>()
        val updates = mutableListOf<TreeBriefs>()
        val result = async {
            briefTreeLinks("Api.kt", connections,
                briefFile = { path -> release.await(); "serves $path" },
                briefLink = { from, to, site ->
                    calls += from to to
                    if (site === broken) error("model failed")
                    "$from uses $to"
                },
                parallelism = 2,
                onUpdate = { updates += it },
            )
        }
        while (updates.size < 4) yield()
        val beforeFile = updates.last()
        assertEquals(TreeBrief.Pending, beforeFile.file)
        assertTrue(beforeFile.running)
        release.complete(Unit)
        val done = result.await()

        assertEquals(TreeBriefs.pending("Api.kt", connections), updates.first())
        assertEquals(TreeBrief.Ready("serves Api.kt"), done.file)
        assertEquals(TreeBrief.Ready("Client.kt uses Api.kt"), done.incoming["Client.kt"])
        assertEquals(TreeBrief.Ready("Api.kt uses Model.kt"), done.outgoing["Model.kt"])
        assertEquals(TreeBrief.Failed, done.outgoing["Broken.kt"])
        assertEquals(setOf("Client.kt" to "Api.kt", "Api.kt" to "Model.kt", "Api.kt" to "Broken.kt"), calls.toSet())
        assertEquals(4, done.total)
        assertEquals(4, done.done)
        assertEquals(1, done.failed)
        assertFalse(done.running)
        assertEquals(done, updates.last())
    }

    @Test fun `cancelled runs keep finished briefs`() {
        val briefs = TreeBriefs("Api.kt", TreeBrief.Pending,
            incoming = mapOf("A.kt" to TreeBrief.Ready("uses it"), "B.kt" to TreeBrief.Pending),
            outgoing = mapOf("C.kt" to TreeBrief.Failed))
        val kept = briefs.withoutPending()
        assertEquals(TreeBrief.Failed, kept.file)
        assertEquals(mapOf("A.kt" to TreeBrief.Ready("uses it")), kept.incoming)
        assertEquals(mapOf("C.kt" to TreeBrief.Failed), kept.outgoing)
        assertFalse(kept.running)
    }
}
