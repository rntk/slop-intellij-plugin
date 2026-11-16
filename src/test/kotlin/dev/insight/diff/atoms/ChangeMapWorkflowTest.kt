package dev.insight.diff.atoms

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionFn
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import dev.insight.diff.ReviewFile
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmApiException
import dev.insight.llm.LlmRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ChangeMapWorkflowTest {
    private fun patch(path: String) = (1..6).joinToString("\n") { row ->
        when (row) { 1 -> "@@ -1,5 +1,5 @@"; 3 -> "-old $path"; 4 -> "+new $path"; else -> " context $row" }
    } + "\n"

    private fun atom(name: String, path: String = "src/$name.kt", kind: AtomKind = AtomKind.FUNCTION) = ChangeAtom(
        id = "$path#$name", path = path, name = name, kind = kind, status = AtomStatus.MODIFIED, header = "fun $name()", signatureChanged = false,
        rows = listOf(2..5), changedRows = setOf(3, 4), beforeLines = 1..4, afterLines = 1..4, added = 1, removed = 1,
        evidenceKind = "implementation", fingerprint = "fp-$name")

    private fun cluster(id: String, vararg atoms: ChangeAtom) = ChangeCluster(id, atoms.mapIndexed { index, atom ->
        ClusterStep(atom.id, if (index == 0) AtomRole.ORIGIN else AtomRole.PROPAGATION, index)
    })

    private val a = atom("a"); private val b = atom("b"); private val c = atom("c"); private val d = atom("d")
    private val e = atom("e"); private val f = atom("f")
    private val files = listOf(a, b, c, d, e, f).map { ReviewFile(it.path, "/repo/${it.path}", null, "modified", patch(it.path), "v") }

    /** c1 = [a, b], c2 = [c, d], c3 = [e] and c4 = [f], with a medium edge between the chains and a weak one to the standalone ones. */
    private val base = ChangeMap(
        ChangeGraph(listOf(a, b, c, d, e, f), listOf(
            AtomEdge(a.id, b.id, AtomRelation.NEW_SYMBOL_USE), AtomEdge(c.id, d.id, AtomRelation.TEST_OF),
            AtomEdge(b.id, c.id, AtomRelation.USE), AtomEdge(a.id, e.id, AtomRelation.CALL))),
        listOf(cluster("c1", a, b), cluster("c2", c, d), cluster("c3", e), cluster("c4", f)))

    /** Merges the groups by concatenating their steps into a cluster named after its members, without any real clustering. */
    private fun fakeMerge(map: ChangeMap, groups: List<Set<String>>): ChangeMap {
        val merged = groups.map { group -> ChangeCluster("m:" + group.joinToString("+"), group.flatMap { map.cluster(it)!!.steps }) }
        val used = groups.flatten().toSet()
        return map.copy(clusters = merged + map.clusters.filter { it.id !in used })
    }

    private class Recorder {
        val groups = mutableListOf<List<Set<String>>>()
        val decisions = mutableListOf<DecisionRequest>()
        val completions = mutableListOf<CompletionRequest>()
    }

    private fun workflow(
        recorder: Recorder,
        decide: (suspend (DecisionRequest) -> DecisionResponse)?,
        complete: (CompletionRequest) -> String = { request -> titles(request) },
        options: ChangeMapOptions = ChangeMapOptions(concurrency = 1),
        merges: List<MergeCandidate> = emptyList(),
        attach: (ChangeCluster) -> List<ChangeCluster> = { emptyList() },
        map: ChangeMap = base,
    ) = ChangeMapWorkflow(
        { request -> recorder.completions += request; Completion(complete(request)) },
        decide?.let { inner -> val port: DecisionFn = { request -> recorder.decisions += request; inner(request) }; port },
        options,
        mergeCandidatesOf = { merges },
        attachCandidatesOf = { _, cluster -> attach(cluster) },
        mergeClusters = { m, groups -> recorder.groups += groups; fakeMerge(m, groups) },
    ) to map

    private fun response(vararg answers: Pair<String, DecisionAnswer>) = DecisionResponse("jev", mapOf(*answers), DecisionUsage(1, 1))
    private fun noul(value: Double) = response("same_change" to DecisionAnswer.Noul(value))
    private fun choice(option: String, confidence: Double) =
        response("attach_to" to DecisionAnswer.Choice(option, mapOf(option to confidence), confidence))

    private fun userJson(request: CompletionRequest): JsonObject =
        JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content).asJsonObject

    private fun ids(request: CompletionRequest): List<Int> = userJson(request)["required_ids"].asJsonArray.map { it.asInt }

    /** Titles every requested id as "Title <id>" unless [skip] leaves it out. */
    private fun titles(request: CompletionRequest, skip: (Int) -> Boolean = { false }): String =
        """{"titles":[${ids(request).filterNot(skip).joinToString(",") { """{"id":$it,"title":"Title $it"}""" }}]}"""

    private fun run(pair: Pair<ChangeMapWorkflow, ChangeMap>, focus: String = "", progress: MutableList<Pair<Int, Int>>? = null) = runBlocking {
        pair.first.run(pair.second, files, focus) { done, total -> progress?.add(done to total) }
    }

    @Test fun `a merge judgment above the threshold merges the pair into one group and the chains are then titled`() {
        val recorder = Recorder()
        val result = run(workflow(recorder, { noul(0.85) }, merges = listOf(MergeCandidate("c1", "c2", listOf(AtomEdge(b.id, c.id, AtomRelation.USE))))))
        assertEquals(listOf(listOf(setOf("c1", "c2"))), recorder.groups)
        assertEquals(1, result.merged)
        assertEquals(0, result.attached)
        assertNull(result.error)
        assertEquals(listOf("m:c1+c2", "c3", "c4"), result.map.clusters.map { it.id })
        assertEquals("Title 1", result.map.cluster("m:c1+c2")!!.title)
        // One judgment and one title request; the standalone clusters were not asked about.
        assertEquals(2, result.requests)
        assertEquals(setOf("same_change"), recorder.decisions.single().questions.keys)
    }

    @Test fun `a judgment below the threshold leaves the clusters alone and a threshold is inclusive`() {
        val below = Recorder()
        val kept = run(workflow(below, { noul(0.69) }, merges = listOf(MergeCandidate("c1", "c2", emptyList()))))
        assertTrue(below.groups.isEmpty())
        assertEquals(0, kept.merged)
        assertEquals(listOf("c1", "c2", "c3", "c4"), kept.map.clusters.map { it.id })
        val exact = Recorder()
        assertEquals(1, run(workflow(exact, { noul(0.7) }, merges = listOf(MergeCandidate("c1", "c2", emptyList())))).merged)
    }

    @Test fun `accepted pairs that share a cluster merge transitively in one call`() {
        val recorder = Recorder()
        val chain3 = base.copy(clusters = listOf(cluster("c1", a), cluster("c2", b, c), cluster("c3", d, e), cluster("c4", f)))
        val pairs = listOf(MergeCandidate("c1", "c2", emptyList()), MergeCandidate("c3", "c2", emptyList()), MergeCandidate("c4", "c1", emptyList()))
        // c4 is rejected; the others link c1-c2-c3.
        val result = run(workflow(recorder, { request ->
            noul(if ("src/f.kt" in request.state.toString()) 0.1 else 0.9)
        }, merges = pairs, map = chain3))
        assertEquals(listOf(listOf(setOf("c1", "c2", "c3"))), recorder.groups)
        assertEquals(2, result.merged)
        assertEquals(3, recorder.decisions.size)
    }

    @Test fun `only maxMergeQuestions candidates are asked`() {
        val recorder = Recorder()
        val pairs = listOf(MergeCandidate("c1", "c2", emptyList()), MergeCandidate("c1", "c3", emptyList()), MergeCandidate("c2", "c3", emptyList()))
        run(workflow(recorder, { noul(0.1) }, merges = pairs, options = ChangeMapOptions(concurrency = 1, maxMergeQuestions = 2)))
        assertEquals(2, recorder.decisions.size)
    }

    @Test fun `standalone changes attach to the chosen cluster when confident and stay when none or unsure`() {
        val recorder = Recorder()
        val answers = mapOf("src/e.kt#e" to choice("c2", 0.9), "src/f.kt#f" to choice("none", 0.99))
        val result = run(workflow(recorder, { request ->
            val atomId = request.state.asJsonObject["change"].asJsonObject["atoms"].asJsonArray[0].asJsonObject.let { "${it["path"].asString}#${it["name"].asString}" }
            answers.getValue(atomId)
        }, attach = { listOf(base.cluster("c1")!!, base.cluster("c2")!!) }))
        // c3 chose the second option, which is c2; c4 chose none.
        assertEquals(listOf(listOf(setOf("c2", "c3"))), recorder.groups)
        assertEquals(1, result.attached)
        assertEquals(0, result.merged)
        val question = recorder.decisions.first().questions.getValue("attach_to") as DecisionQuestion.Choice
        assertEquals(listOf("c1", "c2", "none"), question.criteria.keys.toList())
        assertEquals(2, recorder.decisions.size)

        val unsure = Recorder()
        val low = run(workflow(unsure, { choice("c1", 0.59) }, attach = { listOf(base.cluster("c1")!!) }))
        assertTrue(unsure.groups.isEmpty())
        assertEquals(0, low.attached)
        val sure = Recorder()
        run(workflow(sure, { choice("c1", 0.6) }, attach = { listOf(base.cluster("c1")!!) }))
        assertEquals(listOf(listOf(setOf("c1", "c3", "c4"))), sure.groups)
        // An option the question never offered attaches nothing.
        val unknown = Recorder()
        run(workflow(unknown, { choice("c9", 1.0) }, attach = { listOf(base.cluster("c1")!!) }))
        assertTrue(unknown.groups.isEmpty())
    }

    @Test fun `attach questions are capped by candidates and by maxAttachQuestions`() {
        val recorder = Recorder()
        val many = (1..6).map { cluster("x$it", atom("x$it")) }
        run(workflow(recorder, { choice("none", 1.0) }, attach = { many }, options = ChangeMapOptions(concurrency = 1, maxAttachQuestions = 1)))
        assertEquals(1, recorder.decisions.size)
        val question = recorder.decisions.single().questions.getValue("attach_to") as DecisionQuestion.Choice
        assertEquals(listOf("c1", "c2", "c3", "c4", "none"), question.criteria.keys.toList())
    }

    @Test fun `without a decision port the clusters stay as code built them and only titles are requested`() {
        val recorder = Recorder()
        val result = run(workflow(recorder, null, merges = listOf(MergeCandidate("c1", "c2", emptyList())), attach = { listOf(base.cluster("c1")!!) }))
        assertTrue(recorder.groups.isEmpty())
        assertEquals(1, result.requests)
        assertEquals(listOf("c1", "c2", "c3", "c4"), result.map.clusters.map { it.id })
        assertEquals(listOf("Title 1", "Title 2", null, null), result.map.clusters.map { it.title })
        assertNull(result.error)
    }

    @Test fun `a failing decision stops the judgments keeps what was decided and still titles`() {
        val recorder = Recorder()
        val pairs = listOf(MergeCandidate("c1", "c2", emptyList()), MergeCandidate("c3", "c4", emptyList()), MergeCandidate("c2", "c3", emptyList()))
        var calls = 0
        val result = run(workflow(recorder, { if (calls++ == 1) throw IOException("connection refused") else noul(0.9) }, merges = pairs,
            attach = { listOf(base.cluster("c1")!!) }))
        assertEquals(2, recorder.decisions.size)
        assertEquals(listOf(listOf(setOf("c1", "c2"))), recorder.groups)
        assertEquals(1, result.merged)
        assertTrue(result.error!!, result.error!!.startsWith("Decisions API unavailable: connection refused"))
        assertEquals(listOf("m:c1+c2"), result.map.chains.map { it.id })
        // The attach phase was not started, and the chain was still titled.
        assertEquals("Title 1", result.map.chains.single().title)
        assertEquals(3, result.requests)
    }

    @Test fun `when nothing was decided the error says the clusters are as code built them`() {
        val recorder = Recorder()
        val result = run(workflow(recorder, { throw IOException("HTTP 503") }, merges = listOf(MergeCandidate("c1", "c2", emptyList()))))
        assertTrue(result.error!!.contains("clusters were left as code built them"))
        assertEquals(listOf("c1", "c2", "c3", "c4"), result.map.clusters.map { it.id })
        assertEquals(2, result.requests)
    }

    @Test fun `cancellation during a decision or a title request ends the run`() {
        for (cancelDecision in listOf(true, false)) {
            val recorder = Recorder()
            try {
                runBlocking {
                    val outer = coroutineContext.job
                    val (flow, map) = workflow(recorder, { if (cancelDecision) { outer.cancel(); throw CancellationException("stop") } else noul(0.1) },
                        complete = { outer.cancel(); throw CancellationException("stop") }, merges = listOf(MergeCandidate("c1", "c2", emptyList())))
                    flow.run(map, files)
                }
                fail("Cancellation swallowed")
            } catch (_: CancellationException) { }
            // A cancelled decision phase does not go on to titles.
            if (cancelDecision) assertTrue(recorder.completions.isEmpty())
        }
    }

    @Test fun `titles are batched and a missing id is asked for once more`() {
        val recorder = Recorder()
        val chains = (1..5).map { cluster("k$it", atom("p$it"), atom("q$it")) }
        val map = ChangeMap(ChangeGraph((1..5).flatMap { listOf(atom("p$it"), atom("q$it")) }, emptyList()), chains)
        val result = run(workflow(recorder, null, options = ChangeMapOptions(concurrency = 1, titlesPerRequest = 2), map = map, complete = { request ->
            // The first batch drops its second id the first time it is asked.
            val first = recorder.completions.size == 1
            titles(request) { first && it == 2 }
        }))
        // Batches of 2, 2 and 1, plus the retry of the one missing id.
        assertEquals(4, result.requests)
        assertEquals(listOf(2, 1, 2, 1), recorder.completions.map { ids(it).size })
        assertEquals(listOf(1), ids(recorder.completions[1]))
        assertEquals(listOf("Title 1", "Title 1", "Title 1", "Title 2", "Title 1"), result.map.clusters.map { it.title })
    }

    @Test fun `ids that stay missing keep a null title and invalid entries are dropped`() {
        val recorder = Recorder()
        val result = run(workflow(recorder, null, complete = { request ->
            if (ids(request).size == 2) """{"titles":[{"id":1,"title":" \"Replace retries.\" "},{"id":2,"title":"  "},{"id":7,"title":"Unknown"},{"id":2,"title":"Twice"},{"id":2,"title":"Again"}]}""" else """{"titles":[{"id":1,"title":"  "}]}"""
        }))
        assertEquals("Replace retries", result.map.cluster("c1")!!.title)
        assertNull(result.map.cluster("c2")!!.title)
        // Id 2 was asked for again, still unusable, and left to the default title.
        assertEquals(2, result.requests)
        assertEquals("c and 1 related", result.map.title(result.map.cluster("c2")!!))
        assertNull(result.error)
    }

    @Test fun `a failing title request sets the error and returns the map`() {
        val recorder = Recorder()
        val result = run(workflow(recorder, null, complete = { throw IOException("model offline") }))
        assertTrue(result.error!!, result.error!!.startsWith("Titles unavailable: "))
        assertEquals(base.clusters, result.map.clusters)
        // The batch is asked once per attempt of the JSON helper, each counted.
        assertEquals(recorder.completions.size, result.requests)
        assertTrue(result.requests >= 1)
    }

    @Test fun `titled chains are not asked again and standalone clusters are never titled`() {
        val recorder = Recorder()
        val titled = base.copy(clusters = base.clusters.map { if (it.id == "c1") it.copy(title = "Existing") else it })
        val result = run(workflow(recorder, null, map = titled))
        assertEquals(1, result.requests)
        assertEquals(listOf(1), ids(recorder.completions.single()))
        assertEquals("Existing", result.map.cluster("c1")!!.title)
        assertEquals("Title 1", result.map.cluster("c2")!!.title)
        val none = Recorder()
        assertEquals(0, run(workflow(none, null, map = base.copy(clusters = base.standalone))).requests)
    }

    @Test fun `title requests carry the focus the change atoms and relations as data`() {
        val recorder = Recorder()
        run(workflow(recorder, null), focus = "  the retry rework ")
        val request = recorder.completions.single()
        val user = userJson(request)
        assertEquals("the retry rework", user["focus"].asString)
        val first = user["changes"].asJsonArray[0].asJsonObject
        assertEquals(1, first["id"].asInt)
        assertEquals(listOf("a", "b"), first["atoms"].asJsonArray.map { it.asJsonObject["name"].asString })
        assertEquals(listOf("origin", "propagation"), first["atoms"].asJsonArray.map { it.asJsonObject["role"].asString })
        assertTrue(first["atoms"].asJsonArray[0].asJsonObject["patch"].asString.contains("+new src/a.kt"))
        assertEquals("b (src/b.kt) uses the new a (src/a.kt)", first["relations"].asJsonArray.single().asString)
        val system = request.messages.first().content
        assertTrue(system.contains("at most 60 characters"))
        assertTrue(system.startsWith("The user message holds data"))
        assertFalse(request.messages.last().content.contains("{{"))
    }

    @Test fun `progress counts judgments and title requests and ends complete`() {
        val recorder = Recorder()
        val progress = mutableListOf<Pair<Int, Int>>()
        run(workflow(recorder, { request -> if ("same_change" in request.questions) noul(0.1) else choice("none", 1.0) },
            merges = listOf(MergeCandidate("c1", "c2", emptyList())), attach = { listOf(base.cluster("c1")!!) }), progress = progress)
        // 1 merge judgment, 2 attach judgments, 1 title batch.
        assertEquals(4, progress.last().second)
        assertEquals(4, progress.last().first)
        assertEquals(progress.map { it.first }, progress.map { it.first }.sorted())
        assertTrue(progress.all { (done, total) -> done <= total })
    }

    @Test fun `a stopped phase removes the judgments it never asked from the total`() {
        val recorder = Recorder()
        val progress = mutableListOf<Pair<Int, Int>>()
        run(workflow(recorder, { throw IOException("down") }, merges = (1..3).map { MergeCandidate("c1", "c2", emptyList()) }), progress = progress)
        assertEquals(progress.last().first, progress.last().second)
    }

    @Test fun `merge state describes both groups with patches and links as sentences`() {
        val candidate = MergeCandidate("c1", "c2", listOf(AtomEdge(b.id, c.id, AtomRelation.USE)))
        val request = mergeRequest(base, candidate, files.associate { it.path to it.patch }, atomChars = 12)
        val state = request.state.asJsonObject
        val first = state["first_group"].asJsonObject["atoms"].asJsonArray[0].asJsonObject
        assertEquals("a", first["name"].asString)
        assertEquals("origin", first["role"].asString)
        assertEquals("function", first["kind"].asString)
        assertEquals("modified", first["status"].asString)
        assertEquals(1, first["added"].asInt)
        // Clipped to atomChars with a mark that rows are missing.
        assertTrue(first["patch"].asString.endsWith("\n…"))
        assertEquals(listOf("c (src/c.kt) changes its use of b (src/b.kt)"), state["links"].asJsonArray.map { it.asString })
        val question = request.questions.getValue("same_change") as DecisionQuestion.Noul
        assertEquals(setOf(true, false), question.criteria!!.keys)
        assertTrue(question.instructions.asString.contains("one logical change"))
    }

    @Test fun `merge groups are connected sets in map order and attach groups gather standalone clusters per candidate`() {
        val pairs = listOf(MergeCandidate("c3", "c2", emptyList()), MergeCandidate("c2", "c1", emptyList()), MergeCandidate("c4", "zz", emptyList()))
        assertEquals(listOf(listOf("c1", "c2", "c3")), mergeGroups(base, pairs).map { it.toList() })
        assertTrue(mergeGroups(base, emptyList()).isEmpty())
        assertEquals(listOf(setOf("c1", "c3", "c4"), setOf("c2", "x")), attachGroups(listOf("c1" to "c3", "c2" to "x", "c1" to "c4")))
    }

    @Test fun `titles are cleaned and decoded defensively`() {
        assertEquals("Retry uploads", cleanTitle("  'Retry\n uploads.'  "))
        assertEquals(60, cleanTitle("x".repeat(100))!!.length)
        assertNull(cleanTitle(" ."))
        val answer = JsonParser.parseString("""{"titles":[{"id":1,"title":"One"},{"id":"2","title":"Two"},{"id":3.5,"title":"Half"},{"id":4},{"id":9,"title":"Far"}]}""").asJsonObject
        assertEquals(mapOf(1 to "One"), decodeTitles(answer, 4))
        assertTrue(decodeTitles(JsonObject(), 3).isEmpty())
        assertTrue(decodeTitles(JsonParser.parseString("""{"titles":"none"}""").asJsonObject, 3).isEmpty())
    }
}
