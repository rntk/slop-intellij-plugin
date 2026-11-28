package dev.insight.review

import dev.insight.graph.RelationKind
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmProtocolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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

    @Test fun `blank link replies fail`() = runBlocking {
        try {
            TreeBriefer({ Completion(" - \n") }, emptyList()).link("Client.kt", "Api.kt", TreeConnectionSite(1, 1))
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

    @Test fun `briefs only links to files that pass the filter`() = runBlocking {
        val connections = TreeFileConnections(
            incoming = mapOf("Client.kt" to TreeConnectionSite(1, 1), "Hidden.kt" to TreeConnectionSite(2, 2)),
            outgoing = mapOf("Model.kt" to TreeConnectionSite(3, 3), "Folded.kt" to TreeConnectionSite(4, 4)),
        )
        val calls = mutableListOf<Pair<String, String>>()
        val updates = mutableListOf<TreeBriefs>()
        val done = briefTreeLinks("Api.kt", connections,
            briefFile = { "serves it" },
            briefLink = { from, to, _ -> calls += from to to; "uses it" },
            linked = { it == "Client.kt" || it == "Model.kt" },
            onUpdate = { updates += it },
        )
        assertEquals(setOf("Client.kt" to "Api.kt", "Api.kt" to "Model.kt"), calls.toSet())
        assertEquals(3, updates.first().total)
        assertEquals(setOf("Client.kt"), done.incoming.keys)
        assertEquals(setOf("Model.kt"), done.outgoing.keys)
        assertFalse(done.running)
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

    @Test fun `briefs written only from link windows count just their links`() {
        val briefs = TreeBriefs("Api.kt", file = null, incoming = mapOf("A.kt" to TreeBrief.Ready("uses it")))
        assertFalse(briefs.requested)
        assertEquals(1, briefs.total)
        assertFalse(briefs.running)
        assertNull(briefs.withoutPending().file)
        assertTrue(TreeBriefs.pending("Api.kt").requested)
    }

    @Test fun `callers asking at once share one call and its result`() = runBlocking {
        val calls = SharedCalls<TreeConnectionSite, String>(this)
        val site = TreeConnectionSite(1, 1)
        val reply = CompletableDeferred<String>()
        var started = 0
        val first = async { calls.await(site) { started++; reply.await() } }
        val second = async { calls.await(site) { started++; "unused" } }
        yield()
        reply.complete("uses it")
        assertEquals("uses it", first.await())
        assertEquals("uses it", second.await())
        assertEquals(1, started)
        // A finished call is not reused: the next caller starts anew.
        assertEquals("again", calls.await(site) { "again" })
    }

    @Test fun `a shared call outlives a cancelled caller and stops with the last one`() = runBlocking {
        val calls = SharedCalls<TreeConnectionSite, String>(this)
        val site = TreeConnectionSite(1, 1)
        val reply = CompletableDeferred<String>()
        var cancelled = false
        val call: suspend () -> String = {
            try { reply.await() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
        }
        val window = async { calls.await(site, call) }
        val tree = async { calls.await(site, call) }
        yield()
        window.cancel()
        yield()
        assertFalse(cancelled)
        reply.complete("uses it")
        assertEquals("uses it", tree.await())

        val lone = async { calls.await(TreeConnectionSite(2, 2)) { try { awaitCancellation() } finally { cancelled = true } } }
        yield()
        lone.cancel()
        lone.join()
        yield()
        assertTrue(cancelled)
    }

    @Test fun `occurrence brief sends only that occurrence with numbered snippets`() = runBlocking {
        var request: CompletionRequest? = null
        val site = TreeConnectionSite(12, 4, listOf(
            TreeConnectionOccurrence(RelationKind.IMPORT, 2, null),
            TreeConnectionOccurrence(RelationKind.CALL, 12, 4,
                CodeSnippet(11, 12, listOf("fun run() {", "  service.execute()", "}")),
                CodeSnippet(4, 4, listOf("fun execute() = work()"))),
        ), totalOccurrences = 7)
        val briefer = TreeBriefer({ request = it; Completion("- runs `Service.execute`\nfor the job") }, emptyList())

        assertEquals("runs `Service.execute` for the job", briefer.occurrence("src/A.kt", "src/Service.kt", site, 1))
        assertTrue(request!!.messages.first().content.contains("this occurrence", ignoreCase = true))
        val user = request!!.messages.last().content
        assertTrue(user.contains("Source file: src/A.kt"))
        assertTrue(user.contains("Occurrence 2 of 7"))
        assertTrue(user.contains("CALL: src/A.kt:12 → src/Service.kt:4"))
        assertTrue(user.contains("11| fun run() {\n12|   service.execute()\n13| }"))
        assertTrue(user.contains("4| fun execute() = work()"))
        assertFalse(user.contains("IMPORT"))
    }

    @Test fun `link evidence lists every occurrence and the total`() {
        val site = TreeConnectionSite(12, 4, listOf(
            TreeConnectionOccurrence(RelationKind.CALL, 12, 4, CodeSnippet(12, 12, listOf("service.execute()")), null),
            TreeConnectionOccurrence(RelationKind.IMPORT, 2, null),
        ), totalOccurrences = 7)
        val evidence = connectionEvidence("src/A.kt", "src/Service.kt", site)
        assertTrue(evidence.contains("Target file: src/Service.kt"))
        assertTrue(evidence.contains("CALL: src/A.kt:12 → src/Service.kt:4"))
        assertTrue(evidence.contains("IMPORT: src/A.kt:2 → src/Service.kt"))
        assertTrue(evidence.contains("Total resolved occurrences: 7"))
        assertTrue(evidence.contains("12| service.execute()"))
    }

    @Test fun `connection briefs reuse stored briefs and write only the missing ones`() = runBlocking {
        val site = TreeConnectionSite(1, 1, List(3) { TreeConnectionOccurrence(RelationKind.CALL, it + 1, 1) })
            .apply { brief = "uses the target"; occurrenceBriefs[1] = "second" }
        val written = mutableListOf<Int>()
        val updates = mutableListOf<TreeConnectionBriefs>()
        val done = briefTreeConnection(site, 3,
            briefLink = { error("the link brief is stored already") },
            briefOccurrence = { index ->
                written += index
                if (index == 2) error("model failed")
                "occurrence $index".also { site.occurrenceBriefs[index] = it }
            },
            onUpdate = { updates += it },
        )

        assertEquals(listOf(0, 2), written.sorted())
        assertEquals(TreeConnectionBriefs(TreeBrief.Ready("uses the target"),
            listOf(TreeBrief.Pending, TreeBrief.Ready("second"), TreeBrief.Pending)), updates.first())
        assertEquals(TreeBrief.Ready("uses the target"), done.link)
        assertEquals(listOf(TreeBrief.Ready("occurrence 0"), TreeBrief.Ready("second"), TreeBrief.Failed), done.occurrences)
        assertFalse(done.running)
        assertFalse(done.complete)
    }

    @Test fun `a single occurrence link needs only the link brief`() = runBlocking {
        val site = TreeConnectionSite(1, 1, listOf(TreeConnectionOccurrence(RelationKind.CALL, 1, 1)))
        val done = briefTreeConnection(site, 1,
            briefLink = { "calls it".also { site.brief = it } },
            briefOccurrence = { error("no occurrence briefs for a single occurrence") },
            onUpdate = {},
        )
        assertEquals(TreeConnectionBriefs(TreeBrief.Ready("calls it")), done)
        assertTrue(done.complete)
        assertEquals(done, TreeConnectionBriefs.stored(site, 1))
    }
}
