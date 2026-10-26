package dev.insight.diff

import dev.insight.codereview.CodeFile
import dev.insight.codereview.CodeReviewState
import dev.insight.codereview.CodeScope
import dev.insight.codereview.CodeSnapshot
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import dev.insight.review.ReviewDiskCache
import dev.insight.review.ReviewSummaries
import dev.insight.review.ReviewedFiles
import dev.insight.review.buildSummaryTree
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SummaryReviewTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file(path: String, hash: String = "1") = ReviewFile(path, "/repo/$path", null, "modified", "patch", hash)
    private fun disk() = ReviewDiskCache(temp.root.toPath().resolve("cache"))
    private fun words(text: String, indices: Set<Int>?) = SummaryWord.split(text).filter { it.index in indices.orEmpty() }.map { it.text }

    private class Decisions(val judge: (DecisionRequest, String) -> Double) {
        val requests = mutableListOf<DecisionRequest>()
        val evaluate: suspend (DecisionRequest) -> DecisionResponse = { request ->
            requests += request
            DecisionResponse("test", request.questions.mapValues { (_, question) ->
                assertTrue(question is DecisionQuestion.Noul)
                DecisionAnswer.Noul(judge(request, question.instructions.asString))
            }, DecisionUsage(1, 1))
        }
    }

    @Test fun `shared actions stay bright until every contributing child is reviewed`() = runBlocking {
        val files = listOf(file("a.kt"), file("b.kt"))
        val tree = buildSummaryTree(files)
        val text = "Add caching and retries."
        val summaries = ReviewSummaries(tree, mapOf(0 to text, tree.root.children[0] to "Add caching", tree.root.children[1] to "Add retries"))
        val fake = Decisions { request, question ->
            val child = request.state.asJsonObject["child_summary"].asString
            if (question.contains("(Add)") || question.contains("(caching)") && child.contains("caching") ||
                question.contains("(retries)") && child.contains("retries")) 0.95 else 0.05
        }
        val linker = SummaryReviewLinker(fake.evaluate, disk())
        val first = setOf(ReviewedFiles.key(files[0]))
        val dim = linker.dimming(summaries, first)
        assertEquals(listOf("caching"), words(text, dim[0]))
        assertEquals(listOf("Add", "caching"), words(summaries.text(tree.root.children[0])!!, dim[tree.root.children[0]]))
        val calls = fake.requests.size
        assertTrue(calls > 0)
        assertTrue(linker.dimming(summaries, emptySet())[0].isNullOrEmpty())
        assertEquals(dim, linker.dimming(summaries, first))
        assertEquals(calls, fake.requests.size)
        assertEquals(listOf("Add", "retries"), words(summaries.text(tree.root.children[1])!!,
            linker.dimming(summaries, files.map(ReviewedFiles::key).toSet())[tree.root.children[1]]))
        assertEquals(SummaryWord.split(text).map { it.text }, words(text,
            linker.dimming(summaries, files.map(ReviewedFiles::key).toSet())[0]))
        assertEquals(calls, fake.requests.size)
        // A fresh linker and fresh disk cache reuse the associations after reopening the review.
        assertEquals(dim, SummaryReviewLinker({ error("Cache should satisfy this request") }, disk()).dimming(summaries, first))
    }

    @Test fun `partial folders propagate through child word links and paraphrases`() = runBlocking {
        val files = listOf(file("app/a.kt"), file("app/b.kt"), file("docs/readme.md"))
        val tree = buildSummaryTree(files)
        val app = tree.nodes.single { it.path == "app" }
        val texts = tree.nodes.associate { node -> node.id to when (node.path) {
            "app/a.kt" -> "Caching"
            "app/b.kt" -> "Retries"
            "app" -> "Caching and retries"
            "docs/readme.md" -> "Documentation"
            else -> "Performance and resilience"
        } }
        val fake = Decisions { request, question ->
            val parent = request.state.asJsonObject["parent_summary"].asString
            val child = request.state.asJsonObject["child_summary"].asString
            if (question.startsWith("Does child word")) {
                if (question.contains("(Caching)") && question.contains("(Performance)") ||
                    question.contains("(retries)") && question.contains("(resilience)")) 0.9 else 0.1
            } else if (parent.contains("Performance")) {
                if (child.contains("Caching")) 0.9 else 0.1
            } else {
                if (question.contains("(Caching)") && child.contains("Caching") ||
                    question.contains("(retries)") && child.contains("Retries")) 0.9 else 0.1
            }
        }
        val summaries = ReviewSummaries(tree, texts)
        val dim = SummaryReviewLinker(fake.evaluate).dimming(summaries, setOf(ReviewedFiles.key(files[0])))
        assertEquals(listOf("Caching"), words(texts.getValue(app.id), dim[app.id]))
        assertEquals(listOf("Performance"), words(texts.getValue(0), dim[0]))
        assertTrue(fake.requests.any { it.questions.values.any { q -> q.instructions.asString.startsWith("Does child word") } })
    }

    @Test fun `uncertain unrelated or missing children cannot dim a partial parent`() = runBlocking {
        val files = listOf(file("a.kt"), file("b.kt"))
        val tree = buildSummaryTree(files)
        val a = tree.root.children[0]
        val b = tree.root.children[1]
        val summaries = ReviewSummaries(tree, mapOf(0 to "Caching", a to "Cache", b to "Retries"))
        val marked = setOf(ReviewedFiles.key(files[0]))
        for (uncertain in listOf(0.0, 0.5)) {
            val fake = Decisions { _, _ -> uncertain }
            assertTrue(SummaryReviewLinker(fake.evaluate).dimming(summaries, marked)[0].isNullOrEmpty())
        }
        val noCalls = SummaryReviewLinker({ error("Incomplete evidence must remain bright") })
        assertTrue(noCalls.dimming(ReviewSummaries(tree, mapOf(0 to "Caching", a to "Cache")), marked)[0].isNullOrEmpty())
    }

    @Test fun `changed text invalidates cached links and repeated word occurrences have distinct questions`() = runBlocking {
        val files = listOf(file("a.kt"), file("b.kt"))
        val tree = buildSummaryTree(files)
        val fake = Decisions { _, question -> if (question.contains("parent word 1")) 0.9 else 0.1 }
        val linker = SummaryReviewLinker(fake.evaluate, disk())
        val summaries = ReviewSummaries(tree, mapOf(0 to "Add caching; add retries", tree.root.children[0] to "Cache", tree.root.children[1] to "Retry"))
        linker.dimming(summaries, setOf(ReviewedFiles.key(files[0])))
        val questions = fake.requests.flatMap { it.questions.values }.map { it.instructions.asString }
        assertTrue(questions.any { it.contains("parent word 1 (Add)") })
        assertTrue(questions.any { it.contains("parent word 3 (add)") })
        val count = fake.requests.size
        linker.dimming(summaries.summarize(mapOf(0 to "Add caching; improve retries")), setOf(ReviewedFiles.key(files[0])))
        assertTrue(fake.requests.size > count)
    }

    @Test fun `failed or malformed decisions are errors and never cached as negative links`() = runBlocking {
        val files = listOf(file("a.kt"), file("b.kt"))
        val tree = buildSummaryTree(files)
        val summaries = ReviewSummaries(tree, mapOf(0 to "Caching", tree.root.children[0] to "Cache", tree.root.children[1] to "Retry"))
        val marked = setOf(ReviewedFiles.key(files[0]))
        val linker = SummaryReviewLinker({ DecisionResponse("test", emptyMap(), DecisionUsage(0, 0)) }, disk())
        try { linker.dimming(summaries, marked); fail("Missing judgments must fail") } catch (_: IllegalArgumentException) { }
        val fake = Decisions { _, _ -> 0.9 }
        SummaryReviewLinker(fake.evaluate, disk()).dimming(summaries, marked)
        assertTrue(fake.requests.isNotEmpty())
    }

    @Test fun `directory marks persist and a changed diff clears only that file`() {
        val files = listOf(file("src/a.kt"), file("src/b.kt"), file("doc.md"))
        val snapshot = ReviewSnapshot(files, emptyList())
        fun state(next: ReviewSnapshot = snapshot) = DiffReviewState(cache = disk()).apply { update(next) }
        val original = state()
        val directory = original.summaries.tree.nodes.single { it.path == "src" }
        original.setReviewed(directory, true)
        assertTrue(original.reviewed.isReviewed(directory))
        assertFalse(original.reviewed.isReviewed(original.summaries.tree.root))
        val restored = state()
        assertTrue(restored.reviewed.isReviewed(directory))
        restored.update(snapshot.copy(files = listOf(files[0].copy(fingerprint = "2"), files[1], files[2])))
        val tree = restored.summaries.tree
        assertFalse(restored.reviewed.isReviewed(tree.nodes.single { it.path == "src/a.kt" }))
        assertTrue(restored.reviewed.isReviewed(tree.nodes.single { it.path == "src/b.kt" }))
        assertFalse(restored.reviewed.isReviewed(tree.nodes.single { it.path == "src" }))
        restored.setReviewed(tree.nodes.single { it.path == "src" }, false)
        // Unmarking the new evidence leaves the old version's mark intact.
        assertEquals(setOf(ReviewedFiles.key(files[0])), state().reviewed.keys)
    }

    @Test fun `source reviews keep marks across regeneration but invalidate edited evidence`() {
        val files = listOf(CodeFile("a.kt", "/repo/a.kt", "val a = 1"), CodeFile("b.kt", "/repo/b.kt", "val b = 1"))
        val snapshot = CodeSnapshot(CodeScope("", "/repo"), files)
        fun state() = CodeReviewState(cache = disk()).apply { update(snapshot) }
        val original = state()
        original.setReviewed(original.summaries.tree.root, true)
        original.clearSummaries()
        val restored = state()
        assertTrue(restored.reviewed.isReviewed(restored.summaries.tree.root))
        restored.update(snapshot.copy(files = listOf(CodeFile("a.kt", "/repo/a.kt", "val a = 2"), files[1])))
        assertFalse(restored.reviewed.isReviewed(restored.summaries.tree.root))
        assertTrue(restored.reviewed.isReviewed(restored.summaries.tree.nodes.single { it.path == "b.kt" }))
        restored.setReviewed(restored.summaries.tree.root, false)
        assertEquals(setOf(ReviewedFiles.key(files[0])), state().reviewed.keys)
    }

    @Test fun `switching diff comparisons isolates review marks`() {
        val snapshot = ReviewSnapshot(listOf(file("a.kt")), emptyList())
        for (cache in listOf(null, disk())) {
            val state = DiffReviewState(cache = cache).apply { update(snapshot) }
            state.setReviewed(state.summaries.tree.root, true)
            state.update(snapshot.copy(comparison = ReviewComparison(ReviewDiffRequest(ReviewDiffMode.REVISIONS, "/repo", "main", "dev"))))
            assertTrue(state.reviewed.keys.isEmpty())
            state.update(snapshot)
            if (cache != null) assertTrue(state.reviewed.isReviewed(state.summaries.tree.root))
        }
    }
}
