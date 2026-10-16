package dev.insight.chat

import com.google.gson.JsonParser
import dev.insight.callgraph.ChartScene
import dev.insight.callgraph.ExternalDirection
import dev.insight.callgraph.ExternalKind
import dev.insight.callgraph.ExternalLink
import dev.insight.callgraph.SceneExternal
import dev.insight.callgraph.SceneGroup
import dev.insight.callgraph.SceneGroupKind
import dev.insight.callgraph.SceneSnippet
import dev.insight.callgraph.SceneLink
import dev.insight.callgraph.SceneLinkKind
import dev.insight.callgraph.SceneNode
import dev.insight.callgraph.SceneNote
import dev.insight.callgraph.SceneRole
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.FinishReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChartToolsTest {
    private class Context : ChartChatContext {
        override var revision = 1
        val actions = mutableListOf<Triple<String, Int, List<Int>>>()
        val scenes = mutableListOf<ChartScene>()
        val traces = mutableListOf<List<Int>>()
        val stories = mutableListOf<Pair<String, List<dev.insight.callgraph.StoryBeat>>>()
        override suspend fun presentStory(revision: Int, title: String, beats: List<dev.insight.callgraph.StoryBeat>): String {
            stories += title to beats
            return "ok"
        }
        override suspend fun showScene(revision: Int, scene: ChartScene): String { scenes += scene; return "ok" }
        override suspend fun tracePath(revision: Int, from: Int, to: Int, maxPaths: Int, maxDepth: Int): String {
            traces += listOf(revision, from, to, maxPaths, maxDepth)
            return "ok"
        }
        override suspend fun state(offset: Int) = "revision=$revision offset=$offset"
        val played = mutableListOf<String>()
        override suspend fun projectMap(offset: Int) = "map offset=$offset"
        override suspend fun listTours() = "tours"
        override suspend fun playTour(name: String): String { played += name; return "played" }
        override suspend fun find(query: String, offset: Int) = "$query offset=$offset"
        override suspend fun apply(action: String, revision: Int, nodes: List<Int>): String {
            if (revision != this.revision) throw ToolInputException("Stale revision")
            actions += Triple(action, revision, nodes)
            return "ok"
        }
    }

    @Test fun `validates full batches and requires revision before dispatch`() = runBlocking {
        val context = Context()
        val tool = chartTools(context).single { it.definition.name == "add_chart_nodes" }
        for (json in listOf(
            """{"node_ids":[1]}""", """{"revision":1,"node_ids":[1,-2]}""",
            """{"revision":1,"node_ids":[1,{}]}""", """{"revision":1,"node_ids":[]}""",
            """{"revision":1,"node_ids":[1.5]}""", """{"revision":0,"node_ids":[1]}""",
            """{"revision":1,"node_ids":[${(1..21).joinToString()}]}""",
        )) {
            try { tool.call(JsonParser.parseString(json).asJsonObject); fail(json) }
            catch (_: ToolInputException) { }
        }
        assertTrue(context.actions.isEmpty())
        tool.call(JsonParser.parseString("""{"revision":1,"node_ids":[2,2,3]}""").asJsonObject)
        assertEquals(listOf(Triple("add_chart_nodes", 1, listOf(2, 3))), context.actions)
    }

    @Test fun `scene and analysis tools replace imperative annotation tools`() = runBlocking {
        val context = Context()
        val tools = chartTools(context).associateBy { it.definition.name }
        assertEquals(setOf("get_chart_state", "find_chart_nodes", "get_chart_node_details", "show_scene", "clear_scene",
            "trace_path", "reachability", "suggest_externals", "present_story", "suggest_followups", "get_project_map", "list_tours", "play_tour", "add_chart_nodes", "expand_chart_calls", "expand_chart_callers"), tools.keys)
        assertEquals("revision=1 offset=20", tools.getValue("get_chart_state").call(JsonParser.parseString("""{"offset":20}""").asJsonObject))
        assertEquals("save offset=0", tools.getValue("find_chart_nodes").call(JsonParser.parseString("""{"query":"save"}""").asJsonObject))
        tools.getValue("trace_path").call(JsonParser.parseString("""{"revision":1,"from":2,"to":3}""").asJsonObject)
        tools.getValue("trace_path").call(JsonParser.parseString("""{"revision":1,"from":2,"to":3,"max_paths":9,"max_depth":40}""").asJsonObject)
        assertEquals(listOf(listOf(1, 2, 3, 3, 8), listOf(1, 2, 3, 5, 12)), context.traces)
    }

    @Test fun `show scene validates structure and bounds before dispatch`() = runBlocking {
        val context = Context()
        val tool = chartTools(context).single { it.definition.name == "show_scene" }
        val many = (0..30).joinToString { """{"id":$it}""" }
        for (json in listOf(
            """{"revision":1}""", """{"revision":1,"nodes":[]}""", """{"nodes":[{"id":1}]}""",
            """{"revision":1,"nodes":[{"id":1,"role":"hero"}]}""", """{"revision":1,"nodes":[{"id":-1}]}""",
            """{"revision":1,"nodes":[$many]}""", """{"revision":1,"nodes":[{"id":1},{"id":1,"role":"sink"}]}""",
            """{"revision":1,"nodes":[{"id":1,"badge":"12345"}]}""",
            """{"revision":1,"nodes":[{"id":1}],"links":[{"from":1,"to":1,"kind":"data"}]}""",
            """{"revision":1,"nodes":[{"id":1}],"links":[{"from":1,"to":2}]}""",
            """{"revision":1,"nodes":[{"id":1}],"links":[{"from":1,"to":2,"kind":"data","label":"${"x".repeat(61)}"}]}""",
            """{"revision":1,"nodes":[{"id":1}],"notes":[{"node":1,"text":" "}]}""",
            """{"revision":1,"nodes":[{"id":1}],"notes":[{"node":1,"text":"a"},{"node":1,"text":"b"}]}""",
            """{"revision":1,"nodes":[{"id":1}],"dim_others":"yes"}""",
            """{"revision":1,"nodes":[{"id":1}],"title":"${"x".repeat(121)}"}""",
        )) {
            try { tool.call(JsonParser.parseString(json).asJsonObject); fail(json) }
            catch (_: ToolInputException) { }
        }
        assertTrue(context.scenes.isEmpty())
        tool.call(JsonParser.parseString("""{"revision":1,"title":" Save path ","nodes":[{"id":1,"badge":"1"},{"id":2,"role":"SINK"}],
            "links":[{"from":1,"to":2,"kind":"call"},{"from":1,"to":2,"kind":"call"},{"from":2,"to":3,"kind":"async","label":""}],
            "notes":[{"node":2,"text":"Writes rows, Repo.kt:12"}]}""").asJsonObject)
        assertEquals(ChartScene("Save path", listOf(SceneNode(1, SceneRole.FOCUS, "1"), SceneNode(2, SceneRole.SINK)),
            listOf(SceneLink(1, 2, SceneLinkKind.CALL), SceneLink(2, 3, SceneLinkKind.ASYNC)),
            listOf(SceneNote(2, "Writes rows, Repo.kt:12")), dimOthers = true), context.scenes.single())
    }

    @Test fun `system prompt is a project-neutral guide with evidence rules`() {
        val prompt = chartChatSystemPrompt("demo")
        for (text in listOf("show_scene", "trace_path", "reachability", "badges", "path:line", "untrusted", "do not write",
            "present_story", "suggest_followups", "[[node:ID|Name]]", "only markup", "groups", "externals", "suggest_externals", "snippets", "get_project_map", "list_tours", "play_tour", "save"))
            assertTrue(text, prompt.contains(text))
        for (text in listOf("ChatSession.send", "ToolChatRunner", "highlight_chart_nodes", "set_chart_note"))
            assertFalse(text, prompt.contains(text))
    }

    @Test fun `fresh chart state is supplied on every turn without altering saved history`() = runBlocking {
        val context = Context()
        val requests = mutableListOf<CompletionRequest>()
        val backend = LlmChatBackend({ request ->
            requests += request
            Completion("Answer", finishReason = FinishReason.COMPLETE)
        }, chartTools(context), chartChatSystemPrompt("test"), context = context::snapshot)
        val history = listOf(ChatMessage(ChatMessage.USER, "Explain selection"))
        backend.reply(history)
        context.revision = 2
        backend.reply(history + ChatMessage(ChatMessage.ASSISTANT, "Answer") + ChatMessage(ChatMessage.USER, "Now?"))
        assertTrue(requests[0].messages.last().content.orEmpty().contains("revision=1"))
        assertTrue(requests[1].messages.last().content.orEmpty().contains("revision=2"))
        assertFalse(requests[1].messages.dropLast(1).any { it.content.orEmpty().contains("revision=1") })
        assertEquals("Explain selection", history.single().text)
    }

    @Test fun `present story validates every beat before dispatch and names the bad beat`() = runBlocking {
        val context = Context()
        val tool = chartTools(context).single { it.definition.name == "present_story" }
        fun beat(scene: String, caption: String = "Stage") = """{"caption":"$caption","scene":$scene}"""
        val good = beat("""{"title":"Input","nodes":[{"id":1,"badge":"1"}]}""")
        val bad = mapOf(
            """{"revision":1,"title":"Tour","beats":[$good]}""" to "2 to 8 beats",
            """{"revision":1,"title":"Tour","beats":[${List(9) { good }.joinToString()}]}""" to "2 to 8 beats",
            """{"revision":1,"beats":[$good,$good]}""" to "title",
            """{"revision":1,"title":"Tour","beats":[$good,${beat("""{"nodes":[{"id":1,"role":"hero"}]}""")}]}""" to "beats[1].scene: nodes[0].role",
            """{"revision":1,"title":"Tour","beats":[$good,${beat("""{"nodes":[]}""")}]}""" to "beats[1].scene",
            """{"revision":1,"title":"Tour","beats":[$good,${beat("""{"nodes":[{"id":2}]}""", "x".repeat(301))}]}""" to "beats[1].caption",
            """{"revision":1,"title":"Tour","beats":[$good,${beat("""{"nodes":[{"id":2}]}""", " ")}]}""" to "beats[1].caption",
            """{"revision":1,"title":"Tour","beats":[$good,{"caption":"x"}]}""" to "beats[1].scene",
        )
        for ((json, message) in bad) {
            val error = try { tool.call(JsonParser.parseString(json).asJsonObject); null } catch (e: ToolInputException) { e }
            assertNotNull(json, error)
            assertTrue("${error!!.message} for $json", error.message!!.contains(message))
        }
        assertTrue(context.stories.isEmpty())
        tool.call(JsonParser.parseString("""{"revision":1,"title":" Tour ","beats":[$good,${beat("""{"nodes":[{"id":2,"role":"sink"}],"dim_others":false}""", "Output")}]}""").asJsonObject)
        val (title, beats) = context.stories.single()
        assertEquals("Tour", title)
        assertEquals(listOf("Stage", "Output"), beats.map { it.caption })
        assertEquals(ChartScene("Input", listOf(SceneNode(1, SceneRole.FOCUS, "1")), dimOthers = true), beats[0].scene)
        assertEquals(ChartScene(nodes = listOf(SceneNode(2, SceneRole.SINK)), dimOthers = false), beats[1].scene)
    }

    @Test fun `followups are bounded short questions and do not touch the chart`() = runBlocking {
        val context = Context()
        val received = mutableListOf<List<String>>()
        val tool = chartTools(context) { received += it }.single { it.definition.name == "suggest_followups" }
        for (json in listOf("""{}""", """{"questions":[]}""", """{"questions":"why?"}""", """{"questions":["a","b","c","d"]}""",
            """{"questions":["${"x".repeat(81)}"]}""", """{"questions":[" "]}""", """{"questions":[3]}""")) {
            try { tool.call(JsonParser.parseString(json).asJsonObject); fail(json) }
            catch (_: ToolInputException) { }
        }
        assertTrue(received.isEmpty())
        tool.call(JsonParser.parseString("""{"questions":["  Where is it   stored? ","Who calls it?","Who calls it?"]}""").asJsonObject)
        assertEquals(listOf(listOf("Where is it stored?", "Who calls it?")), received)
        assertTrue(context.actions.isEmpty() && context.scenes.isEmpty() && context.stories.isEmpty())
    }

    @Test fun `references become links and everything else is escaped`() {
        val names = mapOf(1 to "loadData", 2 to "save<T>")
        fun html(text: String, revision: Int? = 4) = chatReferenceHtml(text, revision) { names[it] }
        assertEquals(listOf("See ", ChatReference(1, null), " then ", ChatReference(2, "Save it"), "."),
            parseChatReferences("See [[node:1]] then [[node:2| Save it ]]."))
        assertEquals("""See <a href="node:4:1">loadData</a> then <a href="node:4:2">Save &amp; exit</a>""",
            html("See [[node:1]] then [[node:2|Save & exit]]"))
        assertEquals("""<a href="node:4:2">save&lt;T&gt;</a>""", html("[[node:2]]"))
        assertEquals("&lt;b&gt;bold&lt;/b&gt; &lt;a href=&quot;x&quot;&gt;", html("""<b>bold</b> <a href="x">""").replace("&#39;", "'"))
        assertEquals("""<a href="node:4:1">&lt;img src=x&gt;</a>""", html("[[node:1|<img src=x>]]"))
        for (malformed in listOf("[[node:x]]", "[[node:]]", "[[node:1|a]b]]", "[node:1]", "[[node:-1]]", "[[node:1234567890]]"))
            assertFalse(malformed, html(malformed).contains("<a"))
        assertEquals("Unknown and #9", html("[[node:9|Unknown]] and [[node:9]]"))
        assertEquals("loadData", html("[[node:1|loadData]]", revision = null))
        assertEquals("line<br>next &nbsp; x", html("line\nnext   x"))
        assertEquals(4 to 12, parseChatReferenceLink("node:4:12"))
        assertNull(parseChatReferenceLink("http://example.com"))
        assertNull(parseChatReferenceLink(null))
    }

    @Test fun `scene groups externals and snippets are parsed and bounded before dispatch`() = runBlocking {
        val context = Context()
        val tool = chartTools(context).single { it.definition.name == "show_scene" }
        fun scene(extra: String) = """{"revision":1,"nodes":[{"id":1}],$extra}"""
        val external = """{"id":"orders-db","kind":"database","label":"Orders","connected":[{"node":1,"direction":"out"}]}"""
        val bad = mapOf(
            scene(""""groups":[{"label":"A","nodes":[1,2]},{"label":"B","nodes":[2]}]""") to "two groups",
            scene(""""groups":[${List(9) { """{"label":"G$it","nodes":[$it]}""" }.joinToString()}]""") to "at most 8 groups",
            scene(""""groups":[{"label":"A","nodes":[]}]""") to "groups[0].nodes",
            scene(""""groups":[{"label":"${"x".repeat(41)}","nodes":[1]}]""") to "groups[0].label",
            scene(""""groups":[{"label":"A","nodes":[1],"kind":"tier"}]""") to "groups[0].kind",
            scene(""""externals":[${external.replace("orders-db", "Orders DB")}]""") to "slug",
            scene(""""externals":[$external,$external]""") to "used twice",
            scene(""""externals":[${external.replace("database", "cloud")}]""") to "externals[0].kind",
            scene(""""externals":[${external.replace("\"out\"", "\"sideways\"")}]""") to "connected[0].direction",
            scene(""""externals":[${external.replace("""[{"node":1,"direction":"out"}]""", "[]")}]""") to "1 to 6",
            scene(""""externals":[${List(7) { external.replace("orders-db", "e$it") }.joinToString()}]""") to "at most 6 externals",
            scene(""""snippets":[{"node":1,"start_line":5,"end_line":17}]""") to "at most 12",
            scene(""""snippets":[{"node":1,"start_line":5,"end_line":4}]""") to "start_line <= end_line",
            scene(""""snippets":[{"node":1,"start_line":0,"end_line":4}]""") to "start_line",
            scene(""""snippets":[${List(4) { """{"node":1,"start_line":1,"end_line":1}""" }.joinToString()}]""") to "at most 3 snippets",
        )
        for ((json, message) in bad) {
            val error = try { tool.call(JsonParser.parseString(json).asJsonObject); null } catch (e: ToolInputException) { e }
            assertNotNull(json, error)
            assertTrue("${error!!.message} for $json", error.message!!.contains(message))
        }
        assertTrue(context.scenes.isEmpty())
        tool.call(JsonParser.parseString(scene(""""groups":[{"label":" Intake ","nodes":[1,2,2],"kind":"stage"}],
            "externals":[{"id":"orders-db","kind":"DATABASE","label":"Orders","connected":[{"node":2,"direction":"both","label":"rows"}]}],
            "snippets":[{"node":2,"start_line":12,"end_line":23,"caption":"the write"}]""")).asJsonObject)
        val parsed = context.scenes.single()
        assertEquals(listOf(SceneGroup("Intake", listOf(1, 2), SceneGroupKind.STAGE)), parsed.groups)
        assertEquals(listOf(SceneExternal("orders-db", ExternalKind.DATABASE, "Orders", listOf(ExternalLink(2, ExternalDirection.BOTH, "rows")))), parsed.externals)
        assertEquals(listOf(SceneSnippet(2, 12, 23, "the write")), parsed.snippets)

        val story = chartTools(context).single { it.definition.name == "present_story" }
        val beat = """{"caption":"Stage","scene":{"nodes":[{"id":1}],"groups":[{"label":"A","nodes":[1]}]}}"""
        val error = try {
            story.call(JsonParser.parseString("""{"revision":1,"title":"Tour","beats":[$beat,{"caption":"Bad","scene":{"nodes":[{"id":1}],"snippets":[{"node":1,"start_line":3,"end_line":1}]}}]}""").asJsonObject); null
        } catch (e: ToolInputException) { e }
        assertTrue(error!!.message!!, error.message!!.startsWith("beats[1].scene: snippets[0]"))
        story.call(JsonParser.parseString("""{"revision":1,"title":"Tour","beats":[$beat,$beat]}""").asJsonObject)
        assertEquals(listOf("A", "A"), context.stories.single().second.map { it.scene.groups.single().label })
    }

    @Test fun `suggest externals bounds its node list before dispatch`() = runBlocking {
        val requested = mutableListOf<List<Int>>()
        val context = object : ChartChatContext {
            override suspend fun state(offset: Int) = ""
            override suspend fun find(query: String, offset: Int) = ""
            override suspend fun apply(action: String, revision: Int, nodes: List<Int>) = ""
            override suspend fun suggestExternals(revision: Int, nodes: List<Int>): String { requested += nodes; return "ok" }
        }
        val tool = chartTools(context).single { it.definition.name == "suggest_externals" }
        for (json in listOf("""{"revision":1}""", """{"revision":1,"node_ids":[]}""", """{"revision":1,"node_ids":[-1]}""",
            """{"revision":1,"node_ids":[${(0..30).joinToString()}]}""")) {
            try { tool.call(JsonParser.parseString(json).asJsonObject); fail(json) } catch (_: ToolInputException) { }
        }
        assertEquals("ok", tool.call(JsonParser.parseString("""{"revision":1,"node_ids":[3,3,4]}""").asJsonObject))
        assertEquals(listOf(listOf(3, 4)), requested)
    }

    @Test fun `project map and tour tools validate input before dispatch`() = runBlocking {
        val context = Context()
        val tools = chartTools(context).associateBy { it.definition.name }
        assertEquals("map offset=0", tools.getValue("get_project_map").call(JsonParser.parseString("{}").asJsonObject))
        assertEquals("map offset=10", tools.getValue("get_project_map").call(JsonParser.parseString("""{"offset":10}""").asJsonObject))
        assertEquals("tours", tools.getValue("list_tours").call(JsonParser.parseString("{}").asJsonObject))
        for (json in listOf("{}", """{"name":""}""", """{"name":"  "}""", """{"name":3}""", """{"name":["a"]}""")) {
            try { tools.getValue("play_tour").call(JsonParser.parseString(json).asJsonObject); fail(json) }
            catch (e: ToolInputException) { assertTrue(e.message!!.contains("list_tours")) }
        }
        try { tools.getValue("get_project_map").call(JsonParser.parseString("""{"offset":-1}""").asJsonObject); fail() }
        catch (_: ToolInputException) { }
        assertEquals("played", tools.getValue("play_tour").call(JsonParser.parseString("""{"name":" Orders "}""").asJsonObject))
        assertEquals(listOf("Orders"), context.played)
        val schema = tools.getValue("play_tour").definition.parameters
        assertEquals(listOf("name"), schema["required"].asJsonArray.map { it.asString })
        assertEquals(0, tools.getValue("list_tours").definition.parameters["properties"].asJsonObject.size())
    }
}
