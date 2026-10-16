package dev.insight.callgraph

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.chat.ToolInputException
import org.junit.Assert.assertThrows

class FlowChartChatTest : BasePlatformTestCase() {
    private val graph = CallGraph(
        (0..9).map { CallGraphNode(it, "function$it", "Example", "Example.kt:${it + 1}", 0, true, filePath = "/project/Example.kt") },
        setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2)), false,
    )

    private fun canvas() = FlowChartCanvas({}, {}).apply {
        show(FlowSource(graph.edges.groupBy { it.caller }, graph.edges.groupBy { it.callee }.mapValues { (_, edges) -> edges.map { it.caller } }) {
            FlowCard(graph.nodes[it].name)
        })
    }

    fun `test details expose hidden relationships and expansion reports progress`() {
        val details = JsonParser.parseString(chartNodeDetails(graph, 1, 1, 1, 0)).asJsonObject
        assertEquals(2, details["relationships_total"].asInt)
        assertEquals(setOf("caller", "callee"), details["relationships"].asJsonArray.map { it.asJsonObject["direction"].asString }.toSet())
        assertThrows(ToolInputException::class.java) { chartNodeDetails(graph, 2, 1, 1, 0) }
        val canvas = canvas()
        applyChartChatAction(graph, canvas, 1, "add_chart_nodes", 1, listOf(0))
        val result = JsonParser.parseString(applyChartChatAction(graph, canvas, 1, "expand_chart_calls", 1, listOf(0))).asJsonObject
        assertEquals(0, result["added_node_ids"].asJsonArray.size())
    }

    fun `test details omit library and non-function neighbours before pagination`() {
        val graph = graph.copy(
            nodes = graph.nodes.map {
                when (it.id) {
                    3 -> it.copy(inProject = false)
                    4 -> it.copy(kind = SymbolKind.PROPERTY)
                    else -> it
                }
            },
            edges = linkedSetOf(CallGraphEdge(1, 3), CallGraphEdge(4, 1),
                CallGraphEdge(1, 5, RelationKind.REFERENCE)) + graph.edges,
        )
        val details = JsonParser.parseString(chartNodeDetails(graph, 1, 1, 1, 0)).asJsonObject
        assertEquals(2, details["relationships_total"].asInt)
        assertEquals(listOf(0, 2), details["relationships"].asJsonArray.map {
            it.asJsonObject["node"].asJsonObject["node_id"].asInt
        })
        val next = JsonParser.parseString(chartNodeDetails(graph, 1, 1, 1, 1)).asJsonObject
        assertEquals(2, next["relationships_total"].asInt)
        assertEquals(2, next["relationships"].asJsonArray.single().asJsonObject["node"].asJsonObject["node_id"].asInt)
        assertFalse(next.has("next_offset"))
    }

    fun `test actions reject stale IDs and invalid batches without changing the chart`() {
        val canvas = canvas()
        assertThrows(ToolInputException::class.java) { applyChartChatAction(graph, canvas, 2, "add_chart_nodes", 1, listOf(0)) }
        assertThrows(ToolInputException::class.java) { applyChartChatAction(graph, canvas, 2, "add_chart_nodes", 2, listOf(0, 99)) }
        assertTrue(canvas.board.starts.isEmpty())
        assertThrows(ToolInputException::class.java) { applyChartChatAction(graph, canvas, 2, "expand_chart_calls", 2, listOf(0)) }
        assertThrows(ToolInputException::class.java) { applyChartChatAction(graph, canvas, 2, "highlight_chart_nodes", 2, listOf(0)) }
        assertTrue(canvas.board.openCalls.isEmpty())
    }

    fun `test chart changes are idempotent and search preserves the scene`() {
        val canvas = canvas()
        fun apply(action: String, vararg nodes: Int) = applyChartChatAction(graph, canvas, 1, action, 1, nodes.toList())
        apply("add_chart_nodes", 0)
        apply("add_chart_nodes", 0)
        assertEquals(1, canvas.board.starts.size)
        apply("expand_chart_calls", 1)
        apply("expand_chart_calls", 1)
        assertNotNull(canvas.chart?.step(2))
        apply("expand_chart_callers", 1)
        apply("expand_chart_callers", 1)
        assertTrue(1 in canvas.board.openCallers)
        applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(2, SceneRole.CONTEXT), SceneNode(1, SceneRole.FOCUS))))
        canvas.markMatches(setOf(0))
        assertEquals(setOf(1, 2), canvas.scene.roles.keys)
        assertEquals(1, canvas.selectedNode)
        clearChartScene(canvas, 1, 1)
        assertTrue(canvas.scene.isEmpty)
        assertEquals(setOf(0), canvas.searchMatches)
        applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(1, SceneRole.FOCUS))))
        canvas.clear()
        assertTrue(canvas.scene.isEmpty)
    }

    fun `test added functions continue visible steps and only unconnected ones become columns`() {
        val graph = CallGraph(
            (0..6).map { CallGraphNode(it, "function$it", "Example", "Example.kt:${it + 1}", 0, true, filePath = "/project/Example.kt") },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(3, 0), CallGraphEdge(4, 5)), false,
        )
        val canvas = FlowChartCanvas({}, {}).apply {
            show(FlowSource(graph.edges.groupBy { it.caller }, graph.edges.groupBy { it.callee }.mapValues { (_, edges) -> edges.map { it.caller } }) {
                FlowCard(graph.nodes[it].name)
            })
        }
        fun add(vararg nodes: Int) = JsonParser.parseString(applyChartChatAction(graph, canvas, 1, "add_chart_nodes", 1, nodes.toList()))
            .asJsonObject["placements"].asJsonArray.map { it.asJsonObject }
        add(0)
        val placed = add(2, 3, 6, 5, 4)
        assertEquals(listOf(0, 6, 4), canvas.board.starts.map { it.node })
        assertEquals(listOf("in_calls_of", "in_callers_of", "new_column", "revealed_by_batch", "new_column"),
            placed.map { it["placement"].asString })
        assertEquals(1, placed[0]["via_node_id"].asInt)
        assertEquals(0, placed[1]["via_node_id"].asInt)
        assertTrue(1 in canvas.board.openCalls && 0 in canvas.board.openCallers)
        assertEquals(2, canvas.selectedNode)
        assertEquals("already_visible", add(1).single()["placement"].asString)
        assertEquals(3, canvas.board.starts.size)
    }

    fun `test paginated state exposes selection expansions and actual calls`() {
        val canvas = canvas()
        (0..9).forEach { canvas.add(FlowStart(it)) }
        canvas.showScene(ChartScene(nodes = listOf(SceneNode(9, SceneRole.PROBLEM, "1"))))
        val json = JsonParser.parseString(chartChatState(7, graph, canvas, 0)).asJsonObject
        assertEquals(7, json["revision"].asInt)
        assertEquals(9, json["selected_node"].asInt)
        assertEquals(10, json["steps_total"].asInt)
        assertEquals(8, json["next_offset"].asInt)
        assertEquals(8, json["steps"].asJsonArray.size())
        assertEquals(2, json["calls"].asJsonArray.size())
        val next = JsonParser.parseString(chartChatState(7, graph, canvas, 8)).asJsonObject
        assertEquals(2, next["steps"].asJsonArray.size())
        assertEquals("problem", next["steps"].asJsonArray.last().asJsonObject["scene_role"].asString)
        assertEquals("1", next["steps"].asJsonArray.last().asJsonObject["badge"].asString)
    }

    fun `test scenes validate as a whole before changing the chart`() {
        val canvas = canvas()
        fun scene(vararg links: SceneLink, nodes: List<Int> = listOf(0)) =
            ChartScene(nodes = nodes.map { SceneNode(it, SceneRole.FOCUS) }, links = links.toList())
        assertThrows(ToolInputException::class.java) { applyChartScene(graph, canvas, 2, 1, scene()) }
        assertThrows(ToolInputException::class.java) { applyChartScene(graph, canvas, 1, 1, scene(nodes = listOf(0, 99))) }
        val unbacked = org.junit.Assert.assertThrows(ToolInputException::class.java) {
            applyChartScene(graph, canvas, 1, 1, scene(SceneLink(0, 2, SceneLinkKind.CALL)))
        }
        assertTrue(unbacked.message!!.contains("kind data/event/callback/async"))
        val reversed = org.junit.Assert.assertThrows(ToolInputException::class.java) {
            applyChartScene(graph, canvas, 1, 1, scene(SceneLink(1, 0, SceneLinkKind.CALL)))
        }
        assertTrue(reversed.message!!.contains("swap from and to"))
        val big = CallGraph((0..40).map { CallGraphNode(it, "f$it", "Example", "Example.kt:$it", 0, true) }, emptySet(), false)
        assertThrows(ToolInputException::class.java) {
            applyChartScene(big, canvas, 1, 1, ChartScene(nodes = (0..30).map { SceneNode(it, SceneRole.CONTEXT) }))
        }
        val property = graph.copy(nodes = graph.nodes.map { if (it.id == 3) it.copy(kind = SymbolKind.PROPERTY) else it })
        assertThrows(ToolInputException::class.java) { applyChartScene(property, canvas, 1, 1, scene(nodes = listOf(3))) }
        assertTrue(canvas.board.starts.isEmpty())
        assertTrue(canvas.scene.isEmpty)
    }

    fun `test scene places hidden functions reports roles and replaces the previous scene`() {
        val canvas = canvas()
        var checkpoints = 0
        val result = JsonParser.parseString(applyChartScene(graph, canvas, 1, 1, ChartScene("Request path",
            listOf(SceneNode(2, SceneRole.SINK, "2"), SceneNode(0, SceneRole.FOCUS, "1")),
            listOf(SceneLink(0, 1, SceneLinkKind.CALL), SceneLink(1, 2, SceneLinkKind.DATA, "payload")),
            listOf(SceneNote(2, "Writes Example.kt:3")), dimOthers = true)) { checkpoints++ }).asJsonObject
        assertEquals(1, checkpoints)
        assertEquals(listOf(1), result["implicit_context_nodes"].asJsonArray.map { it.asInt })
        assertEquals(setOf(0, 1, 2), canvas.chart!!.steps.map { it.node }.toSet())
        assertEquals(0, result["not_visible"].asJsonArray.size())
        assertEquals(listOf("static in-project call", EXPLANATION_EVIDENCE),
            result["links"].asJsonArray.map { it.asJsonObject["evidence"].asString })
        assertEquals(0, canvas.selectedNode)
        val state = JsonParser.parseString(chartChatState(1, graph, canvas, 0)).asJsonObject
        val scene = state["scene"].asJsonObject
        assertEquals("Request path", scene["title"].asString)
        assertTrue(scene["dim_others"].asBoolean)
        assertEquals(mapOf(2 to "sink", 0 to "focus", 1 to "context"), scene["nodes"].asJsonArray.associate {
            it.asJsonObject["node_id"].asInt to it.asJsonObject["role"].asString })
        assertEquals(listOf("call", "data"), scene["links"].asJsonArray.map { it.asJsonObject["kind"].asString })
        assertTrue(scene["links"].asJsonArray.all { it.asJsonObject["visible"].asBoolean })
        val steps = state["steps"].asJsonArray.map { it.asJsonObject }.associateBy { it["node_id"].asInt }
        assertEquals("Writes Example.kt:3", steps.getValue(2)["assistant_note"].asString)
        assertEquals("2", steps.getValue(2)["badge"].asString)
        val noted = canvas.geometry!!.cards.getValue(2).bounds.height
        canvas.size = canvas.preferredSize
        val image = java.awt.image.BufferedImage(canvas.width, canvas.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { g -> try { canvas.paint(g) } finally { g.dispose() } }

        val next = JsonParser.parseString(applyChartScene(graph, canvas, 1, 1,
            ChartScene(nodes = listOf(SceneNode(1, SceneRole.PROBLEM))))).asJsonObject
        assertTrue(next["replaced_previous_scene"].asBoolean)
        assertEquals(setOf(1), canvas.scene.roles.keys)
        assertTrue(canvas.scene.links.isEmpty() && canvas.scene.notes.isEmpty())
        assertTrue(canvas.geometry!!.cards.getValue(2).bounds.height < noted)
        assertEquals(1, canvas.selectedNode)

        applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(0, SceneRole.FOCUS)),
            links = listOf(SceneLink(0, 1, SceneLinkKind.CALL)), notes = listOf(SceneNote(0, "note"))))
        canvas.remap { it }
        assertEquals(setOf(0, 1), canvas.scene.roles.keys)
        assertTrue(canvas.scene.links.isEmpty() && canvas.scene.notes.isEmpty())
    }

    fun `test restore point undoes only the latest reply`() {
        val canvas = canvas()
        val restore = ChartRestorePoint(canvas)
        applyChartChatAction(graph, canvas, 1, "add_chart_nodes", 1, listOf(0), restore::checkpoint)
        assertFalse(restore.available)
        restore.begin()
        assertThrows(ToolInputException::class.java) { applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(99, SceneRole.FOCUS))), beforeChange = restore::checkpoint) }
        assertFalse(restore.available)
        val before = canvas.board
        applyChartChatAction(graph, canvas, 1, "add_chart_nodes", 1, listOf(9), restore::checkpoint)
        applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(2, SceneRole.FOCUS))), beforeChange = restore::checkpoint)
        assertTrue(restore.available)
        assertTrue(restore.restore())
        assertEquals(before, canvas.board)
        assertTrue(canvas.scene.isEmpty)
        assertNull(canvas.chart!!.step(9))
        assertFalse(restore.restore())
        restore.begin()
        clearChartScene(canvas, 1, 1, restore::checkpoint)
        assertFalse(restore.available)
    }

    fun `test trace path finds shortest static paths and reverse hints`() {
        val graph = CallGraph((0..7).map { CallGraphNode(it, "f$it", "Example", "Example.kt:$it", 0, true) },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(0, 3), CallGraphEdge(3, 2), CallGraphEdge(0, 4),
                CallGraphEdge(4, 5), CallGraphEdge(5, 2), CallGraphEdge(6, 0), CallGraphEdge(2, 0, RelationKind.REFERENCE),
                CallGraphEdge(1, 1)), false)
        fun trace(from: Int, to: Int, paths: Int = 3, depth: Int = 8) =
            JsonParser.parseString(chartTracePath(graph, 1, 1, from, to, paths, depth, mapOf(0 to 1))).asJsonObject
        fun ids(json: com.google.gson.JsonObject) = json["paths"].asJsonArray.map { path ->
            path.asJsonObject["nodes"].asJsonArray.map { it.asJsonObject["node_id"].asInt } }
        assertEquals(listOf(listOf(0, 1, 2), listOf(0, 3, 2), listOf(0, 4, 5, 2)), ids(trace(0, 2)))
        assertEquals(listOf(listOf(0, 1, 2), listOf(0, 3, 2)), ids(trace(0, 2, paths = 2)))
        assertEquals(2, ids(trace(0, 2, depth = 2)).size)
        assertEquals(listOf(listOf(6, 0, 1, 2)), ids(trace(6, 2, paths = 1)))
        val back = trace(2, 0)
        assertEquals(0, back["paths_total"].asInt)
        assertEquals(listOf(0, 1, 2), back["reverse_path"].asJsonObject["node_ids"].asJsonArray.map { it.asInt })
        assertEquals(listOf(1, 2), trace(0, 2)["hidden_node_ids"].asJsonArray.map { it.asInt }.take(2))
        assertFalse(trace(0, 7).has("reverse_path"))
        assertThrows(ToolInputException::class.java) { chartTracePath(graph, 2, 1, 0, 2, 3, 8) }
        assertThrows(ToolInputException::class.java) { chartTracePath(graph, 1, 1, 2, 2, 3, 8) }
        assertThrows(ToolInputException::class.java) { chartTracePath(graph, 1, 1, 0, 42, 3, 8) }
    }

    fun `test reachability reports entry points callers and rule effects`() {
        val graph = CallGraph((0..6).map { CallGraphNode(it, "f$it", "Example", "Example.kt:$it", 0, it != 5) },
            setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2), CallGraphEdge(3, 2), CallGraphEdge(4, 3), CallGraphEdge(5, 2),
                CallGraphEdge(2, 6)), false)
        val json = JsonParser.parseString(chartReachability(graph, 1, 1, 2, { it == 0 || it == 4 },
            { if (it == 2) setOf("storage") to setOf("storage", "network") else null })).asJsonObject
        assertEquals(2, json["direct_callers"].asInt)
        assertEquals(1, json["direct_callees"].asInt)
        assertEquals(4, json["transitive_callers"].asInt)
        assertEquals(1, json["transitive_callees"].asInt)
        assertEquals("available", json["entry_point_info"].asString)
        assertFalse(json["is_entry_point"].asBoolean)
        val entries = json["reaching_entry_points"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf(0, 4), entries.map { it["node_id"].asInt })
        assertEquals(listOf(0, 1, 2), entries[0]["path"].asJsonArray.map { it.asInt })
        assertEquals(listOf("network", "storage"), json["effects"].asJsonObject["reached_via_calls"].asJsonArray.map { it.asString })
        val unknown = JsonParser.parseString(chartReachability(graph, 1, 1, 2, null, null)).asJsonObject
        assertTrue(unknown["entry_point_info"].asString.startsWith("unavailable"))
        assertTrue(unknown["effects"].asString.startsWith("unavailable"))
        assertThrows(ToolInputException::class.java) { chartReachability(graph, 1, 1, 5, null, null) }
    }

    private fun beat(caption: String, vararg nodes: Int, links: List<SceneLink> = emptyList()) =
        StoryBeat(caption, ChartScene(caption, nodes.map { SceneNode(it, SceneRole.FOCUS, "$it") }, links, dimOthers = true))

    fun `test story validates every beat and one bad beat rejects the whole story`() {
        val canvas = canvas()
        applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(0, SceneRole.SOURCE))))
        val before = canvas.saveState()
        var checkpoints = 0
        for (beats in listOf(listOf(beat("only", 0)), List(9) { beat("many", 0) },
            listOf(beat("ok", 0), beat("unknown", 99)), listOf(beat("ok", 0), beat("unbacked", 0, links = listOf(SceneLink(0, 2, SceneLinkKind.CALL)))))) {
            val error = org.junit.Assert.assertThrows(ToolInputException::class.java) { presentChartStory(graph, canvas, 1, 1, "Tour", beats) { checkpoints++ } }
            if (beats.size == 2) assertTrue(error.message!!, error.message!!.startsWith("beats[1].scene: ") && error.message!!.contains("No beat was shown"))
        }
        assertThrows(ToolInputException::class.java) { presentChartStory(graph, canvas, 2, 1, "Tour", listOf(beat("a", 0), beat("b", 1))) }
        assertEquals(0, checkpoints)
        assertEquals(before, canvas.saveState())
        assertNull(canvas.story)
    }

    fun `test stepping beats applies their scenes and placements and state reports the story`() {
        val canvas = canvas()
        canvas.add(FlowStart(0))
        canvas.toggleCalls(0)
        assertNull(canvas.chart!!.step(1))
        var changes = 0
        canvas.onStoryChange = { changes++ }
        val result = JsonParser.parseString(presentChartStory(graph, canvas, 1, 1, "Request path",
            listOf(beat("Entry", 0), beat("Load", 1, links = listOf(SceneLink(0, 1, SceneLinkKind.CALL))), beat("Store", 2)))).asJsonObject
        assertEquals(3, result["beats_total"].asInt)
        assertEquals(1, changes)
        assertEquals(0, canvas.story!!.index)
        assertEquals(setOf(0), canvas.scene.roles.keys)
        assertNull(canvas.chart!!.step(1))
        assertFalse(canvas.stepStory(-1))
        assertTrue(canvas.stepStory(1))
        assertEquals(2, changes)
        assertEquals("Load", canvas.scene.title)
        assertEquals(setOf(1, 0), canvas.scene.roles.keys)
        assertEquals(SceneRole.CONTEXT, canvas.scene.roles.getValue(0).role)
        assertNotNull(canvas.chart!!.step(1))
        assertEquals(1, canvas.selectedNode)
        // Page Down steps only while a story is open.
        val pageDown = canvas.actionMap[canvas.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)[javax.swing.KeyStroke.getKeyStroke("PAGE_DOWN")]]
        assertTrue(pageDown.isEnabled)
        pageDown.actionPerformed(null)
        assertEquals(2, canvas.story!!.index)
        assertNotNull(canvas.chart!!.step(2))
        assertEquals(setOf(2), canvas.scene.roles.keys)
        assertFalse(canvas.stepStory(1))
        val story = JsonParser.parseString(chartChatState(1, graph, canvas, 0)).asJsonObject["story"].asJsonObject
        assertEquals("Request path", story["title"].asString)
        assertEquals(3, story["current_beat"].asInt)
        assertEquals(3, story["beats_total"].asInt)
        assertEquals(listOf("Entry", "Load", "Store"), story["captions"].asJsonArray.map { it.asString })
        canvas.size = canvas.preferredSize
        val image = java.awt.image.BufferedImage(canvas.width, canvas.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { g -> try { canvas.paint(g) } finally { g.dispose() } }
        canvas.closeStory()
        assertNull(canvas.story)
        assertFalse(pageDown.isEnabled)
        assertEquals(setOf(2), canvas.scene.roles.keys)
        assertFalse(JsonParser.parseString(chartChatState(1, graph, canvas, 0)).asJsonObject.has("story"))
    }

    fun `test story closes on show scene clear scene rebuild and clear and undo restores the pre-story chart`() {
        val canvas = canvas()
        fun present(checkpoint: () -> Unit = {}) = presentChartStory(graph, canvas, 1, 1, "Tour", listOf(beat("a", 0), beat("b", 1), beat("c", 2)), beforeChange = checkpoint)
        present()
        assertTrue(JsonParser.parseString(applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(1, SceneRole.FOCUS)))))
            .asJsonObject["closed_story"].asBoolean)
        assertNull(canvas.story)
        present()
        clearChartScene(canvas, 1, 1)
        assertNull(canvas.story)
        assertTrue(canvas.scene.isEmpty)
        present()
        canvas.remap { it }
        assertNull(canvas.story)
        present()
        canvas.clear()
        assertNull(canvas.story)

        canvas.add(FlowStart(5))
        val before = canvas.saveState()
        val restore = ChartRestorePoint(canvas)
        restore.begin()
        present(restore::checkpoint)
        canvas.stepStory(1)
        canvas.stepStory(1)
        assertTrue(restore.restore())
        assertEquals(before, canvas.saveState())
        assertNull(canvas.story)
        assertTrue(canvas.scene.isEmpty)
    }

    fun `test each chat session restores only its own latest reply`() {
        val canvas = canvas()
        var revision = 1
        val group = ChartRestorePoint.Group { revision }
        val main = ChartRestorePoint(canvas, group)
        val scoped = ChartRestorePoint(canvas, group)
        applyChartChatAction(graph, canvas, 1, "add_chart_nodes", 1, listOf(0))
        main.begin()
        applyChartChatAction(graph, canvas, 1, "add_chart_nodes", 1, listOf(5), main::checkpoint)
        val afterMain = canvas.board
        scoped.begin()
        applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(7, SceneRole.FOCUS))), beforeChange = scoped::checkpoint)
        // Undo in the main chat would revert the scoped chat's reply too, so it is not offered.
        assertFalse(main.available)
        assertFalse(main.restore())
        assertNotNull(canvas.chart!!.step(7))
        assertTrue(scoped.available)
        assertTrue(scoped.restore())
        assertEquals(afterMain, canvas.board)
        assertTrue(canvas.scene.isEmpty)
        assertFalse(main.available)

        main.begin()
        applyChartChatAction(graph, canvas, 1, "add_chart_nodes", 1, listOf(8), main::checkpoint)
        scoped.begin()
        assertTrue(main.available)
        revision = 2
        assertFalse(main.available)
        assertFalse(main.restore())
        assertNotNull(canvas.chart!!.step(8))
    }

    fun `test references select steps place hidden ones and refuse stale revisions`() {
        val canvas = canvas()
        canvas.add(FlowStart(0))
        canvas.toggleCalls(0)
        canvas.selectNode(0)
        assertEquals("function3", chartReferenceName(graph, 2, 2, 3))
        assertNull(chartReferenceName(graph, 2, 1, 3))
        assertNull(chartReferenceName(graph, 2, 2, 99))
        assertNull(chartReferenceName(null, 2, 2, 3))
        val stale = openChartReference(graph, canvas, 2, 1, 1)
        assertTrue(stale!!, stale.contains("rebuilt"))
        assertEquals(0, canvas.selectedNode)
        assertNull(canvas.chart!!.step(1))
        assertNotNull(openChartReference(graph, canvas, 2, 2, 99))
        assertEquals(0, canvas.selectedNode)
        assertNull(openChartReference(graph, canvas, 2, 2, 1))
        assertEquals(1, canvas.selectedNode)
        assertEquals(listOf(0), canvas.board.starts.map { it.node })
        assertNull(openChartReference(graph, canvas, 2, 2, 0))
        assertEquals(0, canvas.selectedNode)
        canvas.hover(1)
        assertEquals(1, canvas.hoveredNode)
        val saved = canvas.saveState()
        canvas.hover(null)
        assertNull(canvas.hoveredNode)
        canvas.hover(1)
        canvas.remap { it }
        assertNull(canvas.hoveredNode)
        assertEquals(saved.board, canvas.board)
    }

    fun `test groups validate their functions count toward the cap and are reported in state`() {
        val canvas = canvas()
        assertThrows(ToolInputException::class.java) {
            applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(0, SceneRole.FOCUS)), groups = listOf(SceneGroup("Load", listOf(1, 99)))))
        }
        val big = CallGraph((0..40).map { CallGraphNode(it, "f$it", "Example", "Example.kt:$it", 0, true) }, emptySet(), false)
        val capped = org.junit.Assert.assertThrows(ToolInputException::class.java) {
            applyChartScene(big, canvas, 1, 1, ChartScene(nodes = (0..20).map { SceneNode(it, SceneRole.CONTEXT) },
                groups = listOf(SceneGroup("Rest", (21..30).toList()))))
        }
        assertTrue(capped.message!!, capped.message!!.contains("group"))
        assertTrue(canvas.board.starts.isEmpty())
        val result = JsonParser.parseString(applyChartScene(graph, canvas, 1, 1, ChartScene(nodes = listOf(SceneNode(0, SceneRole.FOCUS)),
            groups = listOf(SceneGroup("Intake", listOf(0, 1), SceneGroupKind.STAGE), SceneGroup("Store", listOf(2)))))).asJsonObject
        assertEquals(listOf(1, 2), result["implicit_context_nodes"].asJsonArray.map { it.asInt })
        assertEquals(2, result["applied"].asJsonObject["groups"].asInt)
        val groups = JsonParser.parseString(chartChatState(1, graph, canvas, 0)).asJsonObject["scene"].asJsonObject["groups"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("Intake", "Store"), groups.map { it["label"].asString })
        assertEquals("stage", groups[0]["kind"].asString)
        assertFalse(groups[1].has("kind"))
        assertEquals(listOf(0, 1), groups[0]["visible_node_ids"].asJsonArray.map { it.asInt })
        canvas.remap { if (it == 1) null else it }
        assertEquals(listOf(listOf(0), listOf(2)), canvas.scene.groups.map { it.nodes })
    }

    fun `test database and network externals are grounded in rule effects when they are known`() {
        val canvas = canvas()
        val effects = mapOf(1 to setOf("storage"), 2 to setOf("network", "throws"))
        fun scene(kind: ExternalKind, vararg nodes: Int) = ChartScene(nodes = listOf(SceneNode(0, SceneRole.FOCUS)),
            externals = listOf(SceneExternal("ext", kind, "System", nodes.map { ExternalLink(it, ExternalDirection.OUT) })))
        val rejected = org.junit.Assert.assertThrows(ToolInputException::class.java) {
            applyChartScene(graph, canvas, 1, 1, scene(ExternalKind.DATABASE, 1, 2), effects = { effects[it].orEmpty() })
        }
        assertTrue(rejected.message!!, rejected.message!!.contains("function 2") && rejected.message!!.contains("storage") &&
            rejected.message!!.contains("suggest_externals"))
        assertTrue(canvas.board.starts.isEmpty())
        assertThrows(ToolInputException::class.java) {
            applyChartScene(graph, canvas, 1, 1, scene(ExternalKind.NETWORK, 1), effects = { effects[it].orEmpty() })
        }
        fun evidence(json: String) = JsonParser.parseString(json).asJsonObject["externals"].asJsonArray.single().asJsonObject["evidence"].asString
        assertEquals(EXTERNAL_VERIFIED, evidence(applyChartScene(graph, canvas, 1, 1, scene(ExternalKind.DATABASE, 1), effects = { effects[it].orEmpty() })))
        assertTrue(canvas.scene.externals.single().verified)
        assertEquals(setOf(0, 1), canvas.scene.roles.keys)
        // Unknown effects: accepted and marked as an explanation; other kinds are always explanations.
        assertEquals(EXTERNAL_UNVERIFIED, evidence(applyChartScene(graph, canvas, 1, 1, scene(ExternalKind.DATABASE, 2))))
        assertEquals(EXTERNAL_UNVERIFIED, evidence(applyChartScene(graph, canvas, 1, 1, scene(ExternalKind.DATABASE, 2), effects = { null })))
        assertEquals(EXTERNAL_UNVERIFIED, evidence(applyChartScene(graph, canvas, 1, 1, scene(ExternalKind.QUEUE, 2), effects = { effects[it].orEmpty() })))
        val state = JsonParser.parseString(chartChatState(1, graph, canvas, 0)).asJsonObject["scene"].asJsonObject["externals"].asJsonArray.single().asJsonObject
        assertEquals("queue", state["kind"].asString)
        assertEquals(EXTERNAL_UNVERIFIED, state["evidence"].asString)
        assertEquals("out", state["connected"].asJsonArray.single().asJsonObject["direction"].asString)
    }

    fun `test suggest externals reports effects imports and the callees that carry reached effects`() {
        val effects = mapOf(0 to RuleEffects(emptySet(), setOf("storage")), 1 to RuleEffects(emptySet(), setOf("storage")),
            2 to RuleEffects(setOf("storage", "throws"), setOf("storage"), mapOf("storage" to listOf("java.sql.Connection"))))
        val json = JsonParser.parseString(chartExternalEffects(graph, 1, 1, listOf(0, 2, 5), { if (it == 5) null else effects[it] ?: RuleEffects(emptySet(), emptySet()) }))
            .asJsonObject["nodes"].asJsonArray.map { it.asJsonObject }
        val storage = json[0]["effects"].asJsonObject["storage"].asJsonObject
        assertTrue(storage["present"].asBoolean)
        assertFalse(storage["own"].asBoolean)
        assertEquals(listOf(2), storage["via_node_ids"].asJsonArray.map { it.asInt })
        val own = json[1]["effects"].asJsonObject
        assertEquals(listOf("java.sql.Connection"), own["storage"].asJsonObject["imports"].asJsonArray.map { it.asString })
        assertTrue(own["throws"].asBoolean)
        assertFalse(own["network"].asJsonObject["present"].asBoolean)
        assertTrue(json[1]["grounds_externals"].asJsonArray.single().asString.startsWith("database"))
        assertTrue(json[2]["effects"].asString.startsWith("unavailable"))
        assertThrows(ToolInputException::class.java) { chartExternalEffects(graph, 1, 1, listOf(99), { null }) }
    }

    fun `test snippets must be captured inside the declaration and stories validate the new fields per beat`() {
        val canvas = canvas()
        val declaration = DeclarationSource("src/Example.kt", 10, listOf("fun function2() {", "    if (ready)", "        save()", "}"))
        val scene = ChartScene(nodes = listOf(SceneNode(2, SceneRole.SINK)), snippets = listOf(SceneSnippet(2, 11, 12, "the write")))
        assertThrows(ToolInputException::class.java) { applyChartScene(graph, canvas, 1, 1, scene) }
        for (range in listOf(9 to 10, 12 to 14)) {
            val error = org.junit.Assert.assertThrows(ToolInputException::class.java) {
                captureSnippets(scene.copy(snippets = listOf(SceneSnippet(2, range.first, range.second)))) { declaration }
            }
            assertTrue(error.message!!, error.message!!.contains("src/Example.kt:10-13"))
        }
        assertThrows(ToolInputException::class.java) { captureSnippets(scene) { null } }
        val captured = captureSnippets(scene) { declaration }
        assertEquals(listOf("    if (ready)", "        save()"), captured.snippets.single().lines)
        assertEquals("src/Example.kt", captured.snippets.single().path)
        val result = JsonParser.parseString(applyChartScene(graph, canvas, 1, 1, captured)).asJsonObject["snippets"].asJsonArray.single().asJsonObject
        assertEquals("    if (ready)\n        save()", result["text"].asString)
        assertEquals(64, result["content_hash"].asString.length)
        val state = JsonParser.parseString(chartChatState(1, graph, canvas, 0)).asJsonObject["scene"].asJsonObject["snippets"].asJsonArray.single().asJsonObject
        assertEquals(11, state["start_line"].asInt)
        assertTrue(state["visible"].asBoolean)

        val grouped = StoryBeat("Stages", ChartScene(nodes = listOf(SceneNode(0, SceneRole.FOCUS)), groups = listOf(SceneGroup("All", listOf(0, 1)))))
        val stored = StoryBeat("Store", captured.copy(externals = listOf(SceneExternal("db", ExternalKind.DATABASE, "DB", listOf(ExternalLink(2, ExternalDirection.OUT))))))
        val error = org.junit.Assert.assertThrows(ToolInputException::class.java) {
            presentChartStory(graph, canvas, 1, 1, "Tour", listOf(grouped, stored), effects = { emptySet() })
        }
        assertTrue(error.message!!, error.message!!.startsWith("beats[1].scene: externals[0]"))
        assertNull(canvas.story)
        presentChartStory(graph, canvas, 1, 1, "Tour", listOf(grouped, stored), effects = { if (it == 2) setOf("storage") else emptySet() })
        assertEquals(1, canvas.scene.groups.size)
        assertTrue(canvas.stepStory(1))
        assertTrue(canvas.scene.externals.single().verified)
        assertEquals(1, canvas.scene.snippets.size)
        canvas.size = canvas.preferredSize
        val image = java.awt.image.BufferedImage(canvas.width, canvas.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().let { g -> try { canvas.paint(g) } finally { g.dispose() } }
    }
}
