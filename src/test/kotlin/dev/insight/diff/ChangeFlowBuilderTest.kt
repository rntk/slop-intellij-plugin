package dev.insight.diff

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import dev.insight.chat.ToolInputException
import kotlinx.coroutines.*
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel

class ChangeFlowBuilderTest : BasePlatformTestCase() {
    override fun createTempDirTestFixture() = IdeaTestFixtureFactory.getFixtureFactory().createTempDirTestFixture()

    override fun setUp() {
        super.setUp()
        PsiTestUtil.addSourceRoot(module, myFixture.tempDirFixture.findOrCreateDir(""))
    }

    private val before = """
        class Shop {
            void checkout() { validate(); total(); }
            void validate() { }
            int total() { return 1; }
            void legacy() { }
            void relay() { audit(); }
            void audit() { }
            void log() { }
        }
    """.trimIndent() + "\n"

    private val after = """
        class Shop {
            void checkout() {
                validate();
                if (vip()) {
                    discount();
                }
                total();
            }
            void validate() { }
            int total() {   return 1;   }
            void relay() { audit(); }
            void audit() { log(); }
            void log() { }
            boolean vip() { return true; }
            void discount() { relay(); }
        }
    """.trimIndent() + "\n"

    private fun snapshot(historical: Boolean = false): ReviewSnapshot {
        val shop = myFixture.addFileToProject("Shop.java", after)
        val test = myFixture.addFileToProject("ShopTest.java", "class ShopTest { void testCheckout() { new Shop().checkout(); } }\n")
        myFixture.addFileToProject("Client.java", "class Client { void run(Shop s) { s.checkout(); } }\n")
        // A historical after side differs from the project's current source.
        val shown = if (historical) after.replace("return true;", "return false;") else after
        return ReviewSnapshot(listOf(
            ReviewFile("Shop.java", shop.virtualFile.path, null, "modified", "patch", "1", before, shown),
            ReviewFile("ShopTest.java", test.virtualFile.path, null, "added", "patch", "2", "", test.text),
            ReviewFile("notes.txt", "/missing/notes.txt", null, "modified", "patch", "3", "a\n", "b\n"),
            ReviewFile("image.png", "/missing/image.png", null, "modified", "[binary]", "4", null, null),
        ), emptyList())
    }

    private fun build(snapshot: ReviewSnapshot): ChangeGraph =
        ApplicationManager.getApplication().runReadAction<ChangeGraph> { ChangeFlowBuilder(project, snapshot).build() }

    fun testComparesBothSidesAndResolvesCallsBridgesCallersAndTests() {
        val graph = build(snapshot())
        fun node(name: String) = graph.nodes.single { it.name == name }
        assertEquals(ChangeStatus.MODIFIED, node("checkout").status)
        assertNull(node("checkout").trivial)
        assertEquals(TrivialChange.FORMATTING, node("total").trivial)
        assertEquals(ChangeStatus.DELETED, node("legacy").status)
        assertEquals(ChangeStatus.ADDED, node("vip").status)
        assertEquals(ChangeStatus.ADDED, node("discount").status)
        assertEquals(ChangeStatus.MODIFIED, node("audit").status)
        assertEquals("Shop.checkout", node("checkout").qualifiedName)
        assertTrue(graph.nodes.none { it.name == "validate" || it.name == "log" })
        // relay is unchanged but connects discount to audit.
        assertEquals(ChangeStatus.CONTEXT, node("relay").status)
        assertTrue(ChangeEdge(node("discount").id, node("relay").id) in graph.edges)
        assertTrue(ChangeEdge(node("relay").id, node("audit").id) in graph.edges)
        val test = node("testCheckout")
        assertTrue(test.test)
        assertTrue(ChangeEdge(test.id, node("checkout").id) in graph.edges)

        val steps = node("checkout").steps!!
        assertEquals(listOf("validate", "vip", "discount", "total"), steps.map { it.callee?.substringAfterLast('.') })
        val discount = steps.single { it.callee == "Shop.discount" }
        assertEquals(node("discount").id, discount.target)
        assertTrue(discount.changed)
        assertEquals(listOf("if vip()"), discount.conditions)
        assertNull("Unchanged callees outside the graph are steps, not nodes", steps.first().target)
        assertEquals(listOf(false), node("relay").steps!!.map { it.changed })
        assertEquals(node("audit").id, node("relay").steps!!.single().target)
        assertTrue(steps.single { it.callee == "Shop.vip" }.conditions.isEmpty())
        assertEquals(listOf("Client.run"), node("checkout").callers.map { it.qualifiedName })
        assertFalse(node("checkout").callers.single().test)
        assertTrue(node("checkout").added > 0 && node("checkout").removed > 0)
        assertTrue(node("checkout").hunk.any { it.kind == '+' && it.text.contains("discount()") })
        assertTrue(node("checkout").hunk.any { it.kind == '-' && it.text.contains("total(); }") })
        assertTrue(node("legacy").hunk.all { it.kind == '-' })
        assertNull(node("legacy").steps)
        assertNotNull(node("checkout").offset)

        val flow = graph.cluster(node("checkout").id)!!
        assertEquals(setOf("checkout", "total", "vip", "discount", "relay", "audit", "testCheckout"), flow.members.map { graph.nodes[it].name }.toSet())
        assertEquals("Flow from Shop.checkout", flow.title)
        assertEquals("Shop.java", graph.cluster(node("legacy").id)!!.file)
        val files = graph.files.associateBy { it.path }
        assertTrue(files.getValue("Shop.java").calls)
        assertNull(files.getValue("Shop.java").analysis)
        assertNotNull(files.getValue("notes.txt").analysis)
        assertTrue(files.getValue("image.png").analysis!!.startsWith("Content unavailable"))
    }

    fun testHistoricalAfterSideIsClassifiedButCallsStayUnresolved() {
        val graph = build(snapshot(historical = true))
        val vip = graph.nodes.single { it.name == "vip" }
        assertEquals(ChangeStatus.ADDED, vip.status)
        assertNull(vip.steps)
        assertNull(vip.offset)
        assertTrue(graph.nodes.filter { it.path == "Shop.java" }.all { it.steps == null && it.callers.isEmpty() })
        assertFalse(graph.files.single { it.path == "Shop.java" }.calls)
        assertTrue(graph.edges.none { graph.nodes[it.from].path == "Shop.java" })
        assertEquals("Shop.java", graph.cluster(vip.id)!!.file)
    }

    private var exampleIndex = 0

    private fun javaChange(before: String, after: String): ChangeGraph {
        val path = "example${exampleIndex++}/Example.java"
        val file = myFixture.addFileToProject(path, after)
        return build(ReviewSnapshot(listOf(ReviewFile(path, file.virtualFile.path, null,
            "modified", "patch", "1", before, after)), emptyList()))
    }

    fun testLiteralWhitespaceAndTokenBoundaryChangesAreNotTrivial() {
        for ((old, new) in listOf("\"a b\"" to "\"a  b\"", "\"a + b\"" to "\"a+b\"",
            "x + ++x" to "x++ + x")) {
            val graph = javaChange("class Example { Object value(int x) { return $old; } }",
                "class Example { Object value(int x) { return $new; } }")
            assertNull("Literal contents and token boundaries are significant: $old -> $new", graph.changed.single().trivial)
        }
    }

    fun testCommentOnlyEditsUsePsiEvidence() {
        val graph = javaChange("class Example { int value() { /* old */ return 1; } }",
            "class Example { int value() { /* new */ return 1; } }")
        assertEquals(TrivialChange.COMMENTS, graph.changed.single().trivial)
    }

    fun testOutlineLimitsAreExplicitAndExactLimitIsComplete() {
        for (count in listOf(ChangeFlowBuilder.MAX_STEPS, ChangeFlowBuilder.MAX_STEPS + 1)) {
            val graph = javaChange("class Example { void run() {} void helper() {} }",
                "class Example { void run() { ${"helper();".repeat(count)} } void helper() {} }")
            val node = graph.changed.single()
            assertEquals(ChangeFlowBuilder.MAX_STEPS, node.steps!!.size)
            assertEquals(count > ChangeFlowBuilder.MAX_STEPS, node.stepsTruncated)
            val flows = ChangeFlows(graph)
            val page = JsonParser.parseString(changeFlowsJson(flows, 0)).asJsonObject
            assertEquals(node.stepsTruncated, page.has("outlines_truncated"))
            val detail = JsonParser.parseString(changeNodesJson(flows, listOf(node.id))).asJsonObject["nodes"].asJsonArray.single().asJsonObject
            assertTrue("The tool's 60-step limit is explicit too", detail["steps_truncated"].asBoolean)
        }
    }

    fun testTabBuildsTheGraphBeforeTheModelAndDiscardsItWhenTheDiffChanges() {
        val snapshot = snapshot()
        val tab = DiffReviewTab(project, null, { emptyList() }, {})
        try {
            tab.update(snapshot)
            val context = tab.captureContext()
            runWithEvents {
                try { context.flows(0); fail("No graph yet") } catch (_: ToolInputException) { }
                val first = JsonParser.parseString(context.buildFlows()).asJsonObject
                assertTrue(first["clusters"].asJsonArray.size() >= 2)
                assertNotNull(tab.state.flows)
                assertTrue(labels(tab).any { it.startsWith("Change flows") })
                val checkout = tab.state.flows!!.graph.nodes.single { it.name == "checkout" }
                val clusters = tab.state.flows!!.graph.clusters.map { it.id }
                try {
                    context.annotateFlows(FlowAnnotationUpdate(nodes = mapOf(checkout.id to
                        NodeAnnotation("Adds a VIP discount.", ChangeIntent.NEW_BEHAVIOR, ChangeImportance.KEY)),
                        themes = listOf(ChangeTheme("A", null, clusters), ChangeTheme("B", null, clusters))))
                    fail("Duplicate cluster membership")
                } catch (_: ToolInputException) { }
                assertTrue(tab.state.flows!!.nodeNotes.isEmpty())
                val result = context.annotateFlows(FlowAnnotationUpdate(nodes = mapOf(checkout.id to
                    NodeAnnotation("Adds a VIP discount.", ChangeIntent.NEW_BEHAVIOR, ChangeImportance.KEY)),
                    themes = listOf(ChangeTheme("VIP discount at checkout", "Checkout now applies a discount for VIPs.", clusters))))
                assertTrue(result, result.contains("Still missing nodes"))
                assertTrue(labels(tab).any { it == "Structural view · not interpreted yet" }.not())
                val detail = JsonParser.parseString(context.readFlowNodes(listOf(checkout.id))).asJsonObject["nodes"].asJsonArray.single().asJsonObject
                assertTrue(detail["steps"].asJsonArray.any { it.asString.contains("[if vip()]") && it.asString.endsWith("Δ") })
                assertNotNull(context.flowsUncovered())
                tab.update(snapshot.copy(files = snapshot.files.map { it.copy(fingerprint = it.fingerprint + "x") }))
                assertNull(tab.state.flows)
                assertNull(context.flowsUncovered())
                try { context.flows(0); fail("Stale turn") } catch (_: ToolInputException) { }
            }
        } finally { Disposer.dispose(tab) }
    }

    fun testFlowsCardDrillsDownFromThemesToOutlineAndDiffLines() {
        val graph = build(snapshot())
        val checkout = graph.nodes.single { it.name == "checkout" }
        val flows = ChangeFlows(graph).annotate(FlowAnnotationUpdate(
            nodes = mapOf(checkout.id to NodeAnnotation("Adds a VIP discount.", ChangeIntent.NEW_BEHAVIOR, ChangeImportance.KEY, "Check the discount guard")),
            themes = listOf(ChangeTheme("VIP discount at checkout", "Checkout applies a discount for VIPs.", listOf(graph.cluster(checkout.id)!!.id)))))
        val canvas = NoteCanvas({}, {}).apply { setSize(1200, 900) }
        try {
            canvas.flowsView.mode = ChangeFlowsMode.OUTLINE
            canvas.showNotes(emptyList(), flows = flows)
            fun texts() = descendants(canvas).mapNotNull {
                (it as? JLabel)?.text ?: (it as? javax.swing.AbstractButton)?.text ?: (it as? javax.swing.text.JTextComponent)?.text
            }
            assertTrue(texts().contains("VIP discount at checkout"))
            assertTrue("The first theme opens", texts().contains("Shop.checkout"))
            assertTrue(texts().contains("Adds a VIP discount."))
            assertFalse("Outline is folded", texts().any { it.startsWith("Outline ·") })
            // Formatting-only total is minor and folds away under its caller.
            assertFalse(texts().contains("Shop.total"))
            canvas.flowsView.toggle(ChangeFlowsViewState.node(checkout.id))
            canvas.showNotes(emptyList(), flows = flows)
            assertTrue(texts().any { it.startsWith("Outline ·") })
            assertTrue(texts().any { it.contains("if vip() › discount()") })
            assertTrue(texts().contains("⚑ Check the discount guard"))
            assertFalse(texts().any { it.startsWith("+") && it.contains("discount();") })
            canvas.flowsView.toggle(ChangeFlowsViewState.diff(checkout.id))
            canvas.showNotes(emptyList(), flows = flows)
            assertTrue(texts().any { it.startsWith("+") && it.contains("discount();") })
            // Following a call to a folded function opens it.
            canvas.flowsView.focus(flows, graph.nodes.single { it.name == "total" }.id)
            canvas.showNotes(emptyList(), flows = flows)
            assertTrue(texts().contains("Shop.total"))
            assertTrue(texts().contains("Whitespace only"))
            // A new graph starts folded again.
            canvas.showNotes(emptyList(), flows = ChangeFlows(graph.copy()))
            assertFalse(texts().any { it.startsWith("Outline ·") })
        } finally { Disposer.dispose(canvas) }
    }

    private fun labels(tab: DiffReviewTab) = descendants(tab.canvas).mapNotNull { (it as? JLabel)?.text }

    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }

    private fun runWithEvents(action: suspend () -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        var failure: Throwable? = null
        val job = scope.launch { try { action() } catch (e: Throwable) { failure = e } }
        try {
            PlatformTestUtil.waitWithEventsDispatching("Flow tools should finish", { job.isCompleted }, 20 /* seconds */)
            failure?.let { throw it }
        } finally { scope.cancel() }
    }
}
