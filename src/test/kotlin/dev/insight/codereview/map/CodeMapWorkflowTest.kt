package dev.insight.codereview.map

import com.google.gson.JsonParser
import dev.insight.codereview.map.CodeMapFixture.file
import dev.insight.codereview.map.CodeMapFixture.unit
import dev.insight.codereview.map.CodeMapFixture.use
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionQuestion
import dev.insight.decisions.DecisionRequest
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import dev.insight.llm.Completion
import dev.insight.llm.CompletionFn
import dev.insight.llm.LlmRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CodeMapWorkflowTest {
    private val a = unit("src/A.kt", "A")
    private val aOwn = unit("src/AOwn.kt", "AOwn")
    private val b = unit("src/B.kt", "B")
    private val bOwn = unit("src/BOwn.kt", "BOwn")
    private val lonely = unit("src/Lonely.kt", "Lonely")
    /** Flows [a, aOwn] and [b, bOwn] linked by a medium edge from aOwn to b, and a standalone unit that also uses b. */
    private val map = clusterCode(relateCode(listOf(a, aOwn, b, bOwn, lonely),
        listOf(use(a, aOwn), use(b, bOwn), use(aOwn, b), use(lonely, b))))
    private val files = listOf(a, aOwn, b, bOwn, lonely).map { file(it.path) }

    private val titles: CompletionFn = { request ->
        val user = JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content).asJsonObject
        val ids = user.getAsJsonArray("required_ids").map { it.asInt }
        Completion("""{"titles":[${ids.joinToString(",") { """{"id":$it,"title":"Flow $it."}""" }}]}""")
    }

    private fun response(vararg answers: Pair<String, DecisionAnswer>) = DecisionResponse("jev", mapOf(*answers), DecisionUsage(1, 1))

    @Test
    fun `accepted judgments merge flows and attach standalone units, then every flow is titled`() = runBlocking {
        val asked = mutableListOf<DecisionRequest>()
        val run = CodeMapWorkflow(titles, { request ->
            asked += request
            when (val question = request.questions.values.single()) {
                is DecisionQuestion.Noul -> response(CodeMapWorkflow.MERGE_QUESTION to DecisionAnswer.Noul(0.9))
                is DecisionQuestion.Choice -> response(CodeMapWorkflow.ATTACH_QUESTION to DecisionAnswer.Choice("c1", emptyMap(), 0.8))
                    .also { assertTrue(CodeMapWorkflow.NO_ATTACH in question.criteria) }
                else -> error("unexpected $question")
            }
        }).run(map, files)
        assertEquals(1, run.merged)
        assertEquals(1, run.attached)
        assertNull(run.error)
        assertEquals(listOf(setOf(a.id, aOwn.id, b.id, bOwn.id, lonely.id)), run.map.flows.map { it.units.toSet() })
        assertEquals("Flow 1", run.map.flows.single().title)
        assertEquals(3, run.requests)
        val state = asked.first().state.asJsonObject
        assertTrue(state.getAsJsonObject("first_group").getAsJsonArray("units").first().asJsonObject.get("source").asString.startsWith("line 1"))
    }

    @Test
    fun `a failing Decisions API leaves the flows as code built them and still titles them`() = runBlocking {
        val run = CodeMapWorkflow(titles, { throw IOException("down") }).run(map, files)
        assertEquals(map.flows.map { it.units }, run.map.flows.map { it.units })
        assertTrue(run.error!!, run.error!!.startsWith("Decisions API unavailable: down; flows were left as code built them."))
        assertEquals(listOf("Flow 1", "Flow 2"), run.map.chains.map { it.title })
    }

    @Test
    fun `without judgments a failing title model names flows from their code`() = runBlocking {
        val run = CodeMapWorkflow({ throw IOException("no model") }, null).run(map, files)
        assertTrue(run.error!!, run.error!!.startsWith("Titles unavailable"))
        assertEquals(listOf("A and 1 related", "B and 1 related"), run.map.chains.map(run.map::title))
    }

    @Test
    fun `titles are cleaned and unknown or repeated ids dropped`() {
        val answer = JsonParser.parseString("""{"titles":[{"id":1,"title":"\"Upload retry.\""},{"id":2,"title":"x"},{"id":2,"title":"y"},{"id":9,"title":"z"}]}""").asJsonObject
        assertEquals(mapOf(1 to "Upload retry"), decodeFlowTitles(answer, 3))
    }
}
