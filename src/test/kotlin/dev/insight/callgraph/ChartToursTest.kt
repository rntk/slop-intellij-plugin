package dev.insight.callgraph

import com.google.gson.JsonParser
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.chat.ToolInputException
import javax.swing.JMenu
import javax.swing.JMenuItem

class ChartToursTest : BasePlatformTestCase() {
    private fun node(id: Int, name: String, container: String, path: String, line: Int) =
        CallGraphNode(id, name, container, "${path.substringAfterLast('/')}:$line", 0, true, filePath = path)

    private val old = CallGraph(listOf(
        node(0, "handle", "app.Api", "/p/src/Api.kt", 3),
        node(1, "load", "app.Repo", "/p/src/Repo.kt", 5),
        node(2, "save", "app.Repo", "/p/src/Repo.kt", 12),
        node(3, "audit", "app.Audit", "/p/src/Audit.kt", 2),
        node(4, "render", "app.View", "/p/src/View.kt", 7),
    ), setOf(CallGraphEdge(0, 1), CallGraphEdge(0, 2), CallGraphEdge(2, 3)), false)

    /** The rebuild: IDs shuffled, audit deleted, View.kt moved to ui/View.kt. */
    private val rebuilt = CallGraph(listOf(
        node(0, "render", "app.View", "/p/src/ui/View.kt", 7),
        node(1, "padding", "app.Other", "/p/src/Other.kt", 1),
        node(2, "save", "app.Repo", "/p/src/Repo.kt", 14),
        node(3, "handle", "app.Api", "/p/src/Api.kt", 3),
        node(4, "load", "app.Repo", "/p/src/Repo.kt", 5),
    ), setOf(CallGraphEdge(3, 4), CallGraphEdge(3, 2)), false)

    private fun canvas(graph: CallGraph) = FlowChartCanvas({}, {}).apply {
        show(FlowSource(graph.edges.groupBy { it.caller }, graph.edges.groupBy { it.callee }.mapValues { (_, edges) -> edges.map { it.caller } }) {
            FlowCard(graph.nodes[it].name)
        })
    }

    private val repoSource = listOf("fun save(order: Order) {", "    val sql = \"insert ```x```\"", "    db.run(sql)", "}")

    /** A three-beat story on [old]: overview, persistence (with a snippet and a note) and an audit-only beat. */
    private fun story(): ChartStory {
        val canvas = canvas(old)
        val beats = listOf(
            StoryBeat("Requests enter at the API", ChartScene("Overview", listOf(SceneNode(0, SceneRole.FOCUS, "1"), SceneNode(4, SceneRole.SINK, "2")),
                listOf(SceneLink(0, 1, SceneLinkKind.CALL)), groups = listOf(SceneGroup("Web", listOf(0, 4), SceneGroupKind.MODULE)))),
            StoryBeat("Orders are saved", captureSnippets(ChartScene("Persist", listOf(SceneNode(2, SceneRole.FOCUS, "3")),
                links = listOf(SceneLink(2, 3, SceneLinkKind.CALL, "audits")), notes = listOf(SceneNote(3, "Writes the audit log, Audit.kt:2")),
                externals = listOf(SceneExternal("db", ExternalKind.DATABASE, "Orders DB", listOf(ExternalLink(2, ExternalDirection.OUT)))),
                snippets = listOf(SceneSnippet(2, 13, 14, "The write")))) { id ->
                if (id == 2) DeclarationSource("src/Repo.kt", 12, repoSource) else null }),
            StoryBeat("Auditing", ChartScene(nodes = listOf(SceneNode(3, SceneRole.FOCUS)))),
        )
        presentChartStory(old, canvas, 1, 1, "Order flow", beats)
        return canvas.story!!
    }

    fun `test tours save functions by stable identity and survive a JSON round trip`() {
        val tour = savedTour("Orders", story(), savedAt = 42)
        assertEquals(listOf("app.Api.handle", "app.View.render", "app.Repo.load", "app.Repo.save", "app.Audit.audit"), tour.functions.map { it.qualified })
        assertEquals(stableKey(old.nodes[2]), tour.functions[3].key)
        assertEquals(listOf(3), tour.beats[1].scene.nodes.filter { it.role == SceneRole.FOCUS }.map { it.node })
        val snippet = tour.beats[1].scene.snippets.single()
        assertEquals(listOf("    val sql = \"insert ```x```\"", "    db.run(sql)"), snippet.lines)
        val json = TourJson.write(listOf(tour))
        assertEquals(listOf(tour), TourJson.read(json))
        // A damaged tour is skipped without losing the others.
        val damaged = JsonParser.parseString(json).asJsonObject.apply {
            getAsJsonArray("tours").add(JsonParser.parseString("""{"name":"broken","functions":[],"beats":[{"caption":"x","scene":{"nodes":[{"fn":3}]}}]}"""))
        }.toString()
        assertEquals(listOf("Orders"), TourJson.read(damaged).map { it.name })
        assertTrue(TourJson.read("not json").isEmpty())
        assertTrue(TourJson.read(null).isEmpty())
    }

    fun `test playing on a rebuilt graph skips missing functions and empty beats with notices`() {
        val tour = TourJson.read(TourJson.write(listOf(savedTour("Orders", story())))).single()
        val resolved = resolveTour(tour, rebuilt)
        assertEquals(listOf("app.Audit.audit"), resolved.unresolved.map { it.qualified })
        assertEquals(listOf("app.View.render"), resolved.relocated.map { it.qualified })
        assertEquals(2, resolved.beats.size)
        assertTrue(resolved.skippedBeats.single(), resolved.skippedBeats.single().contains("Auditing"))
        assertEquals(listOf(3, 0, 4), resolved.beats[0].scene.nodes.map { it.node })
        assertEquals(listOf(SceneLink(3, 4, SceneLinkKind.CALL)), resolved.beats[0].scene.links)
        assertEquals(listOf(3, 0), resolved.beats[0].scene.groups.single().nodes)
        assertTrue(resolved.beats[1].scene.links.isEmpty())
        assertTrue(resolved.beats[1].scene.notes.isEmpty())
        assertTrue(resolved.dropped.any { it.contains("link Repo.save -> Audit.audit") })
        assertTrue(resolved.dropped.any { it.contains("note on Audit.audit") })
        // save moved down a line: the saved range is re-read from the current declaration.
        val recaptured = recaptureTourSnippets(resolved) { id -> if (id == 2) DeclarationSource("src/Repo.kt", 13, repoSource) else null }
        assertEquals(listOf("fun save(order: Order) {", "    val sql = \"insert ```x```\""), recaptured.beats[1].scene.snippets.single().lines)
        assertTrue(recaptured.dropped.any { it.contains("text changed") })
        val outside = recaptureTourSnippets(resolved) { DeclarationSource("src/Repo.kt", 14, repoSource) }
        assertTrue(outside.beats[1].scene.snippets.isEmpty())
        assertTrue(outside.dropped.any { it.contains("are outside function") })
        val gone = recaptureTourSnippets(resolved) { null }
        assertTrue(gone.beats[1].scene.snippets.isEmpty())
        assertTrue(gone.dropped.any { it.contains("is not available") })

        val canvas = canvas(rebuilt)
        var notice: String? = null
        val json = JsonParser.parseString(playChartTour(rebuilt, canvas, 7, gone, { if (it == 2) setOf("storage") else emptySet() }) { notice = it })
            .asJsonObject
        assertEquals(3, json["beats_saved"].asInt)
        assertEquals(2, json["beats_total"].asInt)
        assertEquals(listOf("app.Audit.audit"), json["unresolved_functions"].asJsonArray.map { it.asJsonObject["qualified_name"].asString })
        assertEquals(1, json["skipped_beats"].asJsonArray.size())
        assertEquals("Order flow", canvas.story!!.title)
        assertEquals(setOf(3, 0, 4), canvas.scene.roles.keys)
        assertNotNull(canvas.chart!!.step(3))
        assertTrue(canvas.stepStory(1))
        assertTrue(canvas.scene.externals.single().verified)
        assertTrue(notice!!, notice!!.contains("Missing functions skipped: app.Audit.audit") && notice!!.contains("Auditing"))

        val nothing = tour.copy(beats = listOf(tour.beats[2]))
        val error = org.junit.Assert.assertThrows(ToolInputException::class.java) { playChartTour(rebuilt, canvas, 7, resolveTour(nothing, rebuilt)) }
        assertTrue(error.message!!, error.message!!.contains("no beat left") && error.message!!.contains("app.Audit.audit"))
        assertEquals(2, canvas.story!!.beats.size)
    }

    fun `test externals no longer grounded and call links no longer static are dropped`() {
        val tour = savedTour("Orders", story())
        val resolved = resolveTour(tour.copy(beats = listOf(tour.beats[1])), rebuilt.copy(edges = emptySet())) { emptySet() }
        assertTrue(resolved.beats.single().scene.externals.isEmpty())
        assertTrue(resolved.dropped.any { it.contains("Orders DB") && it.contains("storage") })
        val first = resolveTour(tour.copy(beats = listOf(tour.beats[0])), rebuilt.copy(edges = emptySet()))
        assertTrue(first.beats.single().scene.links.isEmpty())
        assertTrue(first.dropped.single(), first.dropped.single().contains("no longer a static call"))
    }

    fun `test markdown export lists beats functions links notes and fences snippets safely`() {
        val markdown = tourMarkdown(savedTour("Orders", story()), "/p")
        assertTrue(markdown, markdown.startsWith("# Order flow\n\n## 1. Requests enter at the API\n"))
        assertTrue(markdown, markdown.contains("_Overview_"))
        assertTrue(markdown, markdown.contains("- `Api.handle` — focus, step 1 — src/Api.kt:3\n"))
        assertTrue(markdown, markdown.contains("- `View.render` — sink, step 2 — src/View.kt:7\n"))
        assertTrue(markdown, markdown.contains("Links:\n- `Api.handle` → `Repo.load` (call)\n"))
        assertTrue(markdown, markdown.contains("- `Repo.save` → `Audit.audit` (call): audits\n"))
        assertTrue(markdown, markdown.contains("Notes:\n- `Audit.audit`: Writes the audit log, Audit.kt:2\n"))
        assertTrue(markdown, markdown.contains("**The write** (src/Repo.kt:13-14)\n\n````kt\n    val sql = \"insert ```x```\"\n    db.run(sql)\n````\n"))
        assertTrue(markdown, markdown.contains("## 3. Auditing\n"))
        val odd = tourMarkdown(SavedTour("t", "T", listOf(TourFunction("we`ird", "", null)), listOf(StoryBeat("c", ChartScene(nodes = listOf(SceneNode(0, SceneRole.FOCUS))))), 0))
        assertTrue(odd, odd.contains("- `` we`ird `` — focus\n"))
    }

    fun `test project store saves renames and deletes tours`() {
        val store = project.service<ChartTourStore>()
        store.tours.forEach { store.delete(it.name) }
        val tour = savedTour("Orders", story())
        store.put(tour)
        store.put(tour.copy(title = "Replaced"))
        assertEquals(listOf("Replaced"), store.tours.map { it.title })
        assertEquals("Orders", store.find("orders")?.name)
        store.put(tour.copy(name = "Other"))
        assertFalse(store.rename("Orders", "other"))
        assertTrue(store.rename("Orders", "Checkout"))
        assertEquals(listOf("Checkout", "Other"), store.tours.map { it.name }.sorted())
        assertTrue(store.state.json!!.contains("\"version\":1"))
        val listed = JsonParser.parseString(tourListData(store.tours)).asJsonObject
        assertEquals(2, listed["total"].asInt)
        store.delete("Other")
        store.delete("Checkout")
        assertTrue(store.tours.isEmpty())
    }

    fun `test panel saves the open story and replays it from Tours after a rebuild with a notice`() {
        val store = project.service<ChartTourStore>()
        store.tours.forEach { store.delete(it.name) }
        val panel = FlowChartPanel(project, {}, {}, {}, {}, {}, { false })
        try {
            panel.showResult(CallGraphResult(old, List(old.nodes.size) { null }))
            presentChartStory(old, panel.flowCanvas, 1, 1, "Order flow", listOf(
                StoryBeat("Entry", ChartScene(nodes = listOf(SceneNode(0, SceneRole.FOCUS)))),
                StoryBeat("Audit", ChartScene(nodes = listOf(SceneNode(3, SceneRole.FOCUS)))),
                StoryBeat("Save", ChartScene(nodes = listOf(SceneNode(2, SceneRole.FOCUS), SceneNode(3, SceneRole.CONTEXT))))))
            assertTrue(panel.storyBar.isVisible)
            assertNotNull(panel.saveTour("Orders"))
            panel.showResult(CallGraphResult(rebuilt, List(rebuilt.nodes.size) { null }))
            assertNull(panel.flowCanvas.story)
            val menu = panel.toursMenu().components.filterIsInstance<JMenu>().single()
            assertTrue(menu.text.startsWith("Orders"))
            menu.menuComponents.filterIsInstance<JMenuItem>().single { it.text == "Play" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Tour should play", { panel.flowCanvas.story != null }, 5000)
            assertEquals(listOf("Entry", "Save"), panel.flowCanvas.story!!.beats.map { it.caption })
            assertTrue(panel.storyNotice.isVisible)
            assertTrue(panel.storyNotice.text, panel.storyNotice.text.contains("app.Audit.audit") && panel.storyNotice.text.contains("Audit"))
            panel.flowCanvas.stepStory(1)
            assertTrue(panel.storyNotice.isVisible)
            panel.flowCanvas.closeStory()
            assertFalse(panel.storyNotice.isVisible)
        } finally {
            panel.dispose()
            store.tours.forEach { store.delete(it.name) }
        }
    }
}
