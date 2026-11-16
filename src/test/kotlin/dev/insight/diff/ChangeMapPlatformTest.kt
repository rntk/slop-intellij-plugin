package dev.insight.diff

import com.google.gson.JsonParser
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import dev.insight.agent.ChatRole
import dev.insight.decisions.DecisionAnswer
import dev.insight.decisions.DecisionResponse
import dev.insight.decisions.DecisionUsage
import dev.insight.diff.atoms.ChangeMap
import dev.insight.diff.atoms.ChangeMapWorkflow
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmRole
import dev.insight.review.CanvasTabKey
import dev.insight.review.ReviewPipeline
import dev.insight.review.isDone
import dev.insight.review.reviewJson
import dev.insight.review.ui.tileStats
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChangeMapPlatformTest : FileEditorManagerTestCase() {
    private val untitled = ChangeMapFixture.map.let { map -> map.copy(clusters = map.clusters.map { it.copy(title = null) }) }
    private val requests = CopyOnWriteArrayList<CompletionRequest>()

    /** Titles every chain "Chain <id>"; summarizes a leaf citing its first changed row and a branch citing its first child. */
    private fun complete(request: CompletionRequest): Completion {
        requests += request
        val user = JsonParser.parseString(request.messages.last { it.role == LlmRole.USER }.content).asJsonObject
        if (request.messages.first().content == ChangeMapWorkflow.TITLES_SYSTEM)
            return Completion(reviewJson.toJson(mapOf("titles" to user["required_ids"].asJsonArray.map { mapOf("id" to it.asInt, "title" to "Chain ${it.asInt}") })))
        return Completion(reviewJson.toJson(mapOf("summaries" to user["nodes"].asJsonArray.map { it.asJsonObject }.map { node ->
            val label = node["symbol"]?.asString ?: node["topic_path"]?.asString ?: node["location"].asString
            val from = if (node.has("children")) "C1" else node["patch"].asString.lines().first { it.contains("|+") || it.contains("|-") }.substringBefore('|')
            mapOf("id" to node["id"].asInt, "segments" to listOf(mapOf("text" to "Summary of ${node["kind"].asString} $label.", "from" to listOf(from))))
        })))
    }

    private fun waitIdle(tab: DiffReviewTab) =
        PlatformTestUtil.waitWithEventsDispatching("The change map turn should finish", { !tab.session.busy }, 20 /* seconds */)

    private fun reply(tab: DiffReviewTab) = tab.session.messages.last { it.role != ChatRole.USER }.text

    fun `test change map builds titles judges and summarizes through the chat and a second run regenerates`() {
        val built = CopyOnWriteArrayList<List<String>>()
        val decided = AtomicInteger()
        val tab = DiffReviewTab(project, null, { emptyList() }, {}, complete = ::complete,
            changeMapBuilder = { files -> built += files.map { it.path }; untitled },
            decide = { decided.incrementAndGet(); DecisionResponse("fake", mapOf(ChangeMapWorkflow.ATTACH_QUESTION to
                DecisionAnswer.Choice(ChangeMapWorkflow.NO_ATTACH, mapOf(ChangeMapWorkflow.NO_ATTACH to 1.0), 1.0)), DecisionUsage(1, 1)) })
        try {
            tab.update(ReviewSnapshot(ChangeMapFixture.files(), emptyList()))
            assertTrue(tileStats(tab, "view-change-map").isEmpty())
            assertFalse(tab.state.isDone(ReviewPipeline.CHANGE_MAP))

            tab.session.send("/change-map the new API")
            waitIdle(tab)
            val map = tab.state.changeMap!!
            assertEquals(listOf(ChangeMapFixture.files().map { it.path }), built)
            assertEquals("The standalone Zed.zap is asked whether it joins Make things", 1, decided.get())
            assertEquals(listOf("Chain 1", "Chain 2"), map.chains.map { it.title })
            val summaries = tab.state.changeMapSummaries
            assertTrue(summaries.complete)
            assertEquals("Summary of chunk Foo.make.", summaries.text(summaries.tree.nodes.single { it.identity == "atom:${ChangeMapFixture.origin.id}" }.id))
            assertTrue(tab.state.isDone(ReviewPipeline.CHANGE_MAP))
            assertTrue(requests.any { "the new API" in it.messages.last().content })
            assertTrue(reply(tab), reply(tab).startsWith("Summary of root"))
            assertTrue(reply(tab).contains("[2 logical changes · 2 standalone · 7 declarations · ${summaries.total} summaries ·"))
            assertEquals(listOf("2 chains", "7 atoms", "${summaries.total} / ${summaries.total} summaries written"), tileStats(tab, "view-change-map"))
            assertTrue(tab.state.changeMapVisible)
            assertTrue(CanvasTabKey.ChangeMap in tab.canvas.pageKeys)

            tab.session.send("/change-map")
            waitIdle(tab)
            assertEquals("A complete map is built again", 2, built.size)
            assertNotSame(map, tab.state.changeMap)
            assertTrue(tab.state.changeMapSummaries.complete)
        } finally { Disposer.dispose(tab) }
    }

    fun `test a diff that moves while the map is built fails the turn and stores nothing`() {
        lateinit var tab: DiffReviewTab
        tab = DiffReviewTab(project, null, { emptyList() }, {}, complete = ::complete, changeMapBuilder = { files ->
            // The state moves without the tab stopping the turn, as a refresh racing the build would.
            withContext(Dispatchers.EDT) { tab.state.update(ReviewSnapshot(files.map { it.copy(fingerprint = "moved") }, emptyList())) }
            untitled
        })
        try {
            tab.update(ReviewSnapshot(ChangeMapFixture.files(), emptyList()))
            tab.session.send("/change-map")
            waitIdle(tab)
            assertNull(tab.state.changeMap)
            assertTrue(tab.session.messages.joinToString("\n") { it.text }, tab.session.messages.any { "The diff changed" in it.text })
        } finally { Disposer.dispose(tab) }
    }

    fun `test an empty diff has nothing to map and a map without atoms says so`() {
        val tab = DiffReviewTab(project, null, { emptyList() }, {}, complete = ::complete,
            changeMapBuilder = { ChangeMap(EMPTY_CHANGE_MAP.graph, emptyList()) })
        try {
            tab.update(ReviewSnapshot(emptyList(), emptyList()))
            tab.session.send("/change-map")
            waitIdle(tab)
            assertEquals("There are no changed files to map.", reply(tab))
            tab.update(ReviewSnapshot(listOf(ChangeMapFixture.files().first()), emptyList()))
            tab.session.send("/change-map")
            waitIdle(tab)
            assertEquals("No changed declarations found.", reply(tab))
            assertTrue(tab.state.isDone(ReviewPipeline.CHANGE_MAP))
            assertEquals(listOf("0 chains", "0 atoms"), tileStats(tab, "view-change-map"))
        } finally { Disposer.dispose(tab) }
    }
}
