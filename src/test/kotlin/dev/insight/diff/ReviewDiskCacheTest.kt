package dev.insight.diff

import com.google.gson.JsonPrimitive
import dev.insight.codereview.CodeFile
import dev.insight.codereview.CodeReviewState
import dev.insight.codereview.CodeScope
import dev.insight.codereview.CodeSnapshot
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ReviewDiskCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val directory get() = temp.root.toPath().resolve(".insight/review-cache")
    private fun cache() = ReviewDiskCache(directory)
    private fun snapshot(text: String = "fun a() = 1") = CodeSnapshot(CodeScope("", "/repo"), listOf(CodeFile("a.kt", "/repo/a.kt", text)))
    private fun code(snapshot: CodeSnapshot = snapshot()) = CodeReviewState(cache = cache()).apply { update(snapshot) }
    private fun fill(current: () -> ChangeSummaries, save: (Map<Int, String>) -> Unit) {
        while (!current().complete) save(current().ready().associateWith { "Generated summary $it" })
    }

    @Test fun `focused code chat keeps the chosen version over cached summaries until generated in this tab`() {
        val snapshot = snapshot().let { it.copy(files = it.files + CodeFile("b.kt", "/repo/b.kt", "fun b() = 2")) }
        val original = code(snapshot)
        fill({ original.summaries }) { original.summarize(original.revision, it) }
        val chosen = original.summaries.summarize(mapOf(0 to "Chosen version"))
        val focus = chatFocus(chosen.tree.root, chosen)
        val chat = code(snapshot)
        fun current() = focus.current(snapshot.byPath.keys, chat.summaries, chat.topicSummaries)
        assertEquals("Chosen version", current().context.single().text)
        val file = chat.summaries.tree.nodes.first { it.kind == SummaryKind.FILE }
        chat.summarize(chat.revision, mapOf(file.id to "New file summary"))
        assertEquals("Chosen version", current().context.single().text)
        // Writing even the same text as the cache is a deliberate replacement of the chosen version.
        val cachedRoot = original.summaries.text(0)!!
        chat.summarize(chat.revision, mapOf(0 to cachedRoot))
        assertEquals(cachedRoot, current().context.single().text)
        chat.update(snapshot.copy(files = snapshot.files + CodeFile("c.kt", "/repo/c.kt", "fun c() = 3")))
        val fileFocus = chatFocus(chosen.tree[file.id], chosen)
        assertEquals("New file summary", fileFocus.current(chat.snapshot.byPath.keys, chat.summaries, chat.topicSummaries).context.last().text)
    }

    @Test fun `focused diff chat keeps chosen topic and file summaries over restored and seeded text`() {
        val files = listOf("a.kt", "b.kt").map { ReviewFile(it, "/repo/$it", null, "modified", "patch", "hash") }
        val snapshot = ReviewSnapshot(files, emptyList())
        fun load() = DiffReviewState(cache = cache()).apply { update(snapshot) }
        val original = load()
        fill({ original.summaries }) { original.summarize(original.revision, it) }
        files.forEach { original.assignTopic(original.revision, it.path, "Application > Values") }
        val reading = topicReadingSummaries(original.topicSummaries, original.summaries)
        val chosen = reading.summarize(reading.tree.nodes.associate { it.id to "Chosen ${it.identity}" }, reuseSingleChildren = false)
        val node = chosen.tree.nodes.first { it.kind == SummaryKind.FILE }
        val focus = chatFocus(node, chosen)
        val chat = load()
        fun current() = focus.current(files.mapTo(hashSetOf()) { it.path }, chat.summaries, chat.topicSummaries)
        assertEquals(focus.context, current().context)
        chat.startTopicSummaries(chat.revision)
        assertEquals(focus.context, current().context)
        val restored = load()
        assertEquals(focus.context, focus.current(files.mapTo(hashSetOf()) { it.path }, restored.summaries, restored.topicSummaries).context)
        val file = chat.summaries.tree.nodes.single { it.path == node.path }
        chat.summarize(chat.revision, mapOf(file.id to "New file summary"))
        assertEquals(focus.context.dropLast(1).map { it.text } + "New file summary", current().context.map { it.text })
        val topic = chat.topicSummaries.tree.nodes.single { it.identity == "topic:application > values" }
        chat.summarizeTopics(chat.revision, mapOf(topic.id to "New topic summary"))
        assertEquals(listOf("New topic summary", "New file summary"), current().context.map { it.text })
    }

    @Test fun `code summaries topics and filled hierarchy survive fresh cache and state instances`() {
        val state = code()
        fill({ state.summaries }) { state.summarize(state.revision, it) }
        state.assignTopic(state.revision, "a.kt", "Application > Values")
        state.startTopicSummaries(state.revision)
        fill({ state.topicSummaries }) { state.summarizeTopics(state.revision, it) }
        val restored = code()
        assertTrue(restored.summaries.complete)
        assertEquals(state.summaries.text(0), restored.summaries.text(0))
        assertEquals(state.topics.labels, restored.topics.labels)
        assertTrue(restored.topicSummaries.complete)
        assertEquals(state.topicSummaries.text(0), restored.topicSummaries.text(0))
        assertTrue(restored.notes.isEmpty())
        assertTrue(restored.sources.isEmpty())
    }

    @Test fun `same length edit misses its entries while the previous snapshot stays restorable`() {
        val state = code()
        fill({ state.summaries }) { state.summarize(state.revision, it) }
        state.assignTopic(state.revision, "a.kt", "Application > Values")
        val changed = code(snapshot("fun a() = 2"))
        assertEquals(0, changed.summaries.count)
        assertTrue(changed.topics.labels.isEmpty())
        assertEquals(0, changed.topicSummaries.count)
        fill({ changed.summaries }) { changed.summarize(changed.revision, it.mapValues { "Updated summary" }) }
        assertEquals("Updated summary", code(snapshot("fun a() = 2")).summaries.text(0))
        // Reverting the edit finds the older entries again, since a slot keeps the results of earlier snapshots.
        assertEquals(state.summaries.text(0), code().summaries.text(0))
    }

    @Test fun `partial progress is restored and regeneration clears the disk result`() {
        val next = snapshot().let { it.copy(files = it.files + CodeFile("b.kt", "/repo/b.kt", "fun b() = 2")) }
        val state = code(next)
        val id = state.summaries.ready().first()
        state.summarize(state.revision, mapOf(id to "First file"))
        assertEquals("First file", code(next).summaries.text(id))
        assertFalse(code(next).summaries.complete)
        state.clearSummaries()
        assertEquals(0, code(next).summaries.count)
    }

    @Test fun `diff results survive restart and moved refs but changed evidence invalidates them`() {
        val file = ReviewFile("a.kt", "/repo/a.kt", null, "modified", "-1\n+2", "hash1")
        val snapshot = ReviewSnapshot(listOf(file), emptyList())
        fun load(next: ReviewSnapshot = snapshot) = DiffReviewState(cache = cache()).apply { update(next) }
        val state = load()
        fill({ state.summaries }) { state.summarize(state.revision, it) }
        state.assignTopic(state.revision, "a.kt", "Application > Values")
        state.startTopicSummaries(state.revision)
        fill({ state.topicSummaries }) { state.summarizeTopics(state.revision, it) }
        // The diff panel first gives a new tab its empty loading snapshot.
        val loading = load(ReviewSnapshot(emptyList(), emptyList()))
        loading.update(snapshot)
        assertTrue(loading.summaries.complete)
        assertTrue(load().summaries.complete)
        assertTrue(load().topics.complete)
        assertTrue(load().topicSummaries.complete)
        // Same request and evidence: the diff is identical, so a moved ref keeps the results, as it does in a live review.
        assertTrue(load(snapshot.copy(comparison = ReviewComparison(baseRevision = "new-head"))).summaries.complete)
        assertEquals(0, load(snapshot.copy(comparison = ReviewComparison(ReviewDiffRequest(ReviewDiffMode.REVISIONS, "/repo", "main", "dev")))).summaries.count)
        assertEquals(0, load(snapshot.copy(files = listOf(file.copy(fingerprint = "hash2")))).summaries.count)
    }

    @Test fun `scope and project caches are isolated`() {
        val state = code()
        fill({ state.summaries }) { state.summarize(state.revision, it) }
        val scoped = CodeReviewState(setOf("a.kt"), cache()).apply { update(snapshot()) }
        assertEquals(0, scoped.summaries.count)
        val other = CodeReviewState(cache = ReviewDiskCache(temp.root.toPath().resolve("other"))).apply { update(snapshot()) }
        assertEquals(0, other.summaries.count)
    }

    @Test fun `corrupt unsupported and unwritable cache entries are harmless misses`() {
        val disk = cache()
        disk.write("test", "slot", "hash", JsonPrimitive("saved"))
        assertEquals("saved", cache().read("test", "slot", "hash")?.asString)
        assertNull(disk.read("test", "slot", "new-hash"))
        val file = Files.walk(directory).use { paths -> paths.filter { it.toString().endsWith(".json") }.findFirst().get() }
        Files.writeString(file, "{broken")
        assertNull(disk.read("test", "slot", "hash"))
        Files.writeString(file, "{\"version\":999,\"fingerprint\":\"hash\",\"value\":\"old\"}")
        assertNull(disk.read("test", "slot", "hash"))
        val blocked = temp.newFile("blocked").toPath()
        ReviewDiskCache(blocked).write("test", "slot", "hash", JsonPrimitive("ignored"))
        assertNull(ReviewDiskCache(blocked).read("test", "slot", "hash"))
    }

    @Test fun `scores survive tracer restart and changed summary text requires new judgments`() = runBlocking {
        val files = listOf("a.kt", "b.kt").map { ReviewFile(it, "/repo/$it", null, "modified", "patch", "hash") }
        val tree = buildSummaryTree(files)
        var summaries = ChangeSummaries(tree)
        fill({ summaries }) { summaries = summaries.summarize(it.mapValues { "Adds account token validation" }) }
        var requests = 0
        val evaluate: suspend (DecisionRequest) -> DecisionResponse = { request ->
            requests++
            DecisionResponse("test", request.questions.mapValues { DecisionAnswer.Noul(0.75) }, DecisionUsage(1, 1))
        }
        val tracer = SummaryTracer(evaluate, cache = cache())
        val context = tracer.score(summaries, 0, 1)
        tracer.search(summaries, 0, context)
        val initial = requests
        assertTrue(initial > 0)
        val restarted = SummaryTracer(evaluate, cache = cache())
        val restored = restarted.score(summaries, 0, 1)
        assertEquals(context, restored)
        restarted.search(summaries, 0, restored)
        assertEquals(initial, requests)
        val changed = summaries.summarize(mapOf(0 to "Adds account email validation"))
        restarted.score(changed, 0, 1)
        assertTrue(requests > initial)
    }

    @Test fun `one changed file misses only its own entries and those of every node above it`() {
        fun file(path: String, fingerprint: String = "1") = ReviewFile(path, "/repo/$path", null, "modified", "patch $path", fingerprint)
        val files = listOf(file("app/a.kt"), file("app/b.kt"), file("lib/c.kt"))
        fun load(next: List<ReviewFile>) = DiffReviewState(cache = cache()).apply { update(ReviewSnapshot(next, emptyList())) }
        val state = load(files)
        fill({ state.summaries }) { state.summarize(state.revision, it) }
        state.assignTopic(state.revision, "app/a.kt", "Core > Auth")
        state.assignTopic(state.revision, "app/b.kt", "Core > Auth")
        state.assignTopic(state.revision, "lib/c.kt", "Docs > Guide")
        state.startTopicSummaries(state.revision)
        fill({ state.topicSummaries }) { state.summarizeTopics(state.revision, it) }
        fun ChangeSummaries.texts() = tree.nodes.mapNotNull { node -> text(node.id)?.let { node.identity to it } }.toMap()
        // Exactly the nodes without the changed file beneath them keep their saved text: never a folder, topic or root above it.
        fun unaffected(summaries: ChangeSummaries) = summaries.texts().filterKeys { identity ->
            "app/a.kt" !in summaries.tree.nodes.single { it.identity == identity }.files
        }

        val changed = load(listOf(file("app/a.kt", "2")) + files.drop(1))
        assertEquals(unaffected(state.summaries), changed.summaries.texts())
        assertEquals(setOf("file:app/b.kt", "directory:lib", "file:lib/c.kt"), changed.summaries.texts().keys)
        assertEquals(mapOf("app/b.kt" to "Core > Auth", "lib/c.kt" to "Docs > Guide"), changed.topics.labels)
        assertEquals(unaffected(state.topicSummaries), changed.topicSummaries.texts())
        assertTrue("topic:docs > guide" in changed.topicSummaries.texts())
        assertFalse("topic:core > auth" in changed.topicSummaries.texts())
        assertFalse("topic-root" in changed.topicSummaries.texts())
    }

    @Test fun `regenerate also drops the saved summaries of earlier snapshots`() {
        val state = code()
        fill({ state.summaries }) { state.summarize(state.revision, it) }
        val edited = code(snapshot("fun a() = 2"))
        fill({ edited.summaries }) { edited.summarize(edited.revision, it) }
        edited.clearSummaries()
        assertEquals(0, code(snapshot("fun a() = 2")).summaries.count)
        assertEquals(0, code().summaries.count)
    }

    @Test fun `tabs of one scope keep each other's saved results`() {
        val shared = cache()
        val first = CodeReviewState(cache = shared).apply { update(snapshot()) }
        val second = CodeReviewState(cache = shared).apply { update(snapshot()) }
        fill({ first.summaries }) { first.summarize(first.revision, it) }
        second.assignTopic(second.revision, "a.kt", "Application > Values")
        val restored = code()
        assertTrue(restored.summaries.complete)
        assertEquals(mapOf("a.kt" to "Application > Values"), restored.topics.labels)
    }

    @Test fun `a grown candidate list asks only for the new questions`() = runBlocking {
        val files = listOf("a.kt", "b.kt", "c.kt").map { ReviewFile(it, "/repo/$it", null, "modified", "patch", "hash") }
        val tree = buildSummaryTree(files)
        var questions = 0
        val evaluate: suspend (DecisionRequest) -> DecisionResponse = { request ->
            questions += request.questions.size
            DecisionResponse("test", request.questions.mapValues { DecisionAnswer.Noul(0.5) }, DecisionUsage(1, 1))
        }
        val candidates = tree.nodes.drop(1).map { it to "Validates ${it.path}" }
        SummaryTracer(evaluate, cache = cache()).rank("token validation", "token", tree.root, "Adds validation", candidates.take(2))
        assertEquals(2, questions)
        val scores = SummaryTracer(evaluate, cache = cache()).rank("token validation", "token", tree.root, "Adds validation", candidates)
        assertEquals(3, questions)
        assertEquals(listOf(0.5, 0.5, 0.5), scores)
        SummaryTracer(evaluate, cache = cache()).rank("token validation", "token", tree.root, "Adds validation", candidates.map { (node, text) -> node to "$text again" })
        assertEquals(6, questions)
    }

    @Test fun `stale code and diff tabs persist only the targeted review marks`() {
        for (mode in listOf("code", "diff")) {
            val directory = temp.root.toPath().resolve(mode)
            val shared = ReviewDiskCache(directory)
            val codeSnapshot = snapshot().let { it.copy(files = it.files + CodeFile("b.kt", "/repo/b.kt", "fun b() = 2")) }
            val diffSnapshot = ReviewSnapshot(listOf("a.kt", "b.kt").map {
                ReviewFile(it, "/repo/$it", null, "modified", "patch", "hash")
            }, emptyList())
            fun tab(cache: ReviewDiskCache = shared): Pair<ReviewedFiles, (String, Boolean) -> Unit> =
                if (mode == "code") {
                    val state = CodeReviewState(cache = cache).apply { update(codeSnapshot) }
                    state.reviewed to { path, value -> state.setReviewed(state.summaries.tree.nodes.single { it.path == path }, value) }
                } else {
                    val state = DiffReviewState(cache = cache).apply { update(diffSnapshot) }
                    state.reviewed to { path, value -> state.setReviewed(state.summaries.tree.nodes.single { it.path == path }, value) }
                }
            val sources: List<ReviewSource> = if (mode == "code") codeSnapshot.files else diffSnapshot.files
            val a = ReviewedFiles.key(sources[0])
            val b = ReviewedFiles.key(sources[1])
            fun assertSaved(vararg keys: String) = assertEquals(mode, keys.toSet(), tab(ReviewDiskCache(directory)).first.keys)

            val first = tab().second
            val second = tab().second
            first("a.kt", true)
            second("b.kt", true)
            assertSaved(a, b)

            val stale = tab().second
            first("a.kt", false)
            stale("b.kt", false)
            assertSaved()

            // Explicit marks still apply when a stale tab already considers the file reviewed.
            stale("a.kt", true)
            assertSaved(a)
            // Explicit unmarks still apply when this tab already considers the file unreviewed.
            first("a.kt", false)
            assertSaved()
        }
    }
}
