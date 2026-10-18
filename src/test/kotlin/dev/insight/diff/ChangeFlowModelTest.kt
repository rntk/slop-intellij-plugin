package dev.insight.diff

import com.google.gson.JsonParser
import dev.insight.callgraph.LineChange
import dev.insight.callgraph.SymbolKind
import dev.insight.chat.ToolChatRunner
import dev.insight.chat.ToolInputException
import dev.insight.llm.Completion
import dev.insight.llm.CompletionRequest
import dev.insight.llm.LlmMessage
import dev.insight.llm.LlmRole
import dev.insight.llm.LlmToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChangeFlowModelTest {
    private fun declaration(name: String, header: String = "fun $name()", text: String = "$header { }", lines: IntRange = 1..1) =
        SideDeclaration(name, "A.$name", SymbolKind.FUNCTION, header, text, text.replace(Regex("//.*"), "").replace(Regex("\\s+"), " ").trim(), lines, 0)

    @Test fun `matching keeps overload partners and reads a changed signature as a modification`() {
        val before = listOf(declaration("save", "fun save(a: Int)"), declaration("save", "fun save(b: String)"), declaration("gone"))
        val after = listOf(declaration("save", "fun save(b: String)"), declaration("save", "fun save(a: Long)"), declaration("fresh"))
        val pairs = matchDeclarations(before, after)
        assertTrue(pairs.contains(before[1] to after[0]))
        assertTrue(pairs.contains(before[0] to after[1]))
        assertTrue(pairs.contains(null to after[2]))
        assertTrue(pairs.contains(before[2] to null))
        val (status, trivial, signature) = classify(before[0], after[1])!!
        assertEquals(ChangeStatus.MODIFIED, status)
        assertNull(trivial)
        assertTrue(signature)
        assertNull("Identical text is unchanged", classify(before[1], after[0]))
    }

    @Test fun `classification separates whitespace and comment edits from code changes`() {
        val base = declaration("f", text = "fun f() { return a + b }")
        val tokens = listOf("fun", "f", "(", ")", "{", "return", "a", "+", "b", "}")
        assertEquals(TrivialChange.FORMATTING, classify(base.copy(formattingTokens = tokens),
            declaration("f", text = "fun f() {\n    return a+b\n}").copy(formattingTokens = tokens))!!.second)
        assertEquals(TrivialChange.COMMENTS, classify(base, declaration("f", text = "fun f() { return a + b } // why"))!!.second)
        val real = classify(base, declaration("f", text = "fun f() { return a - b }"))!!
        assertEquals(ChangeStatus.MODIFIED, real.first)
        assertNull(real.second)
        assertFalse(real.third)
        assertEquals(ChangeStatus.ADDED, classify(null, base)!!.first)
        assertEquals(ChangeStatus.DELETED, classify(base, null)!!.first)
    }

    @Test fun `headers skip annotations and stop at bodies without splitting parameter defaults`() {
        assertEquals("fun f(a: Int = 1): String", declarationHeader("@Suppress(\"x\")\nfun f(a: Int = 1): String {\n  return \"\"\n}"))
        assertEquals("fun g()", declarationHeader("fun g() = 42"))
        assertEquals("def run(self, x):", declarationHeader("@decorator\ndef run(self, x):\n    pass"))
        assertEquals("public void go(List<String> xs)", declarationHeader("@Override\npublic void go(List<String> xs) { }"))
        assertEquals("fun h(a: Int)", declarationHeader("fun h(a: Int) { a == 1 }"))
        assertEquals("fun k(): Boolean", declarationHeader("fun k(): Boolean =\n    x >= 1"))
    }

    @Test fun `declaration hunks align both sides and fold long unchanged runs`() {
        val before = (1..20).map { "line $it" }
        val after = before.toMutableList().apply { this[9] = "changed 10"; add(15, "inserted") }
        val changes = listOf(LineChange(9, 10, 9, 10), LineChange(15, 15, 15, 16))
        val (hunk, truncated) = declarationHunk(before, after, changes, 1..20, 1..21)
        assertFalse(truncated)
        assertEquals(listOf('-', '+'), hunk.filter { it.kind == '-' || it.kind == '+' }.take(2).map { it.kind })
        assertEquals("line 10", hunk.first { it.kind == '-' }.text)
        assertEquals(10, hunk.first { it.kind == '-' }.before)
        assertEquals("inserted", hunk.last { it.kind == '+' }.text)
        assertEquals(16, hunk.last { it.kind == '+' }.after)
        assertTrue("Leading context folds", hunk.first().kind == '…')
        assertTrue(hunk.none { it.kind == ' ' && it.text == "line 1" })
        // Lines outside the declaration's range are never shown.
        val (inner, _) = declarationHunk(before, after, changes, 12..20, 12..21)
        assertTrue(inner.none { it.text == "changed 10" || it.text == "line 10" })
        assertEquals("inserted", inner.single { it.kind == '+' }.text)
        val (added, _) = declarationHunk(emptyList(), listOf("a", "b"), listOf(LineChange(0, 0, 0, 2)), null, 1..2)
        assertEquals(listOf('+', '+'), added.map { it.kind })
        assertTrue(declarationHunk(before, after, changes, 1..20, 1..21, limit = 3).second)
        assertEquals(setOf(9, 15), ChangedLines(changes).after)
        assertEquals(1, ChangedLines(changes).count(ChangedLines(changes).after, 10..15, listOf(15..20)))
    }

    private fun node(id: Int, name: String, path: String = "src/a.kt", status: ChangeStatus = ChangeStatus.MODIFIED, test: Boolean = false,
        trivial: TrivialChange? = null, churn: Int = 1) =
        ChangeNode(id, path, "/p/$path", name, name, status, "fun $name()", id + 1, trivial, added = churn, test = test)

    private fun graph(): ChangeGraph {
        // 0 → 1 → (2 unchanged) → 3 ; 4 alone in b.kt ; 5 alone in b.kt ; 6 is a test calling 3 ; 7 whitespace-only in c.kt
        val nodes = listOf(node(0, "entry", churn = 5), node(1, "save", churn = 5), node(2, "bridge", status = ChangeStatus.CONTEXT),
            node(3, "store", churn = 2), node(4, "lonely", "src/b.kt", churn = 30), node(5, "other", "src/b.kt", ChangeStatus.DELETED),
            node(6, "testStore", "src/test/T.kt", test = true), node(7, "pretty", "src/c.kt", trivial = TrivialChange.FORMATTING, churn = 40))
        val edges = listOf(ChangeEdge(0, 1), ChangeEdge(1, 2), ChangeEdge(2, 3), ChangeEdge(6, 3))
        return ChangeGraph(nodes, edges, clusterChanges(nodes, edges), listOf(ChangeFile("src/a.kt", "modified"), ChangeFile("src/b.kt", "modified"),
            ChangeFile("src/c.kt", "modified"), ChangeFile("src/test/T.kt", "modified"), ChangeFile("README.md", "modified", "Not source code")))
    }

    @Test fun `clusters follow resolved calls through one unchanged bridge and keep unconnected changes per file`() {
        val graph = graph()
        val clusters = graph.clusters
        assertEquals(3, clusters.size)
        val flow = clusters.single { 0 in it.members }
        assertEquals(listOf(0, 1, 2, 3, 6), flow.members)
        assertEquals("Flow from entry", flow.title)
        assertNull(flow.file)
        val file = clusters.single { 4 in it.members }
        assertEquals(listOf(4, 5), file.members)
        assertEquals("src/b.kt", file.file)
        assertEquals("Changes in b.kt", file.title)
        // Larger code clusters first; whitespace-only clusters last, whatever their size.
        assertEquals(listOf(4), clusters.first().members.take(1))
        assertEquals(listOf(7), clusters.last().members)
        assertEquals(listOf(0), flowRoots(graph.nodes, graph.edges, flow.members))
        // A bridge that does not connect two changes stays out of every cluster.
        val loose = listOf(node(0, "a"), node(1, "ctx", status = ChangeStatus.CONTEXT))
        assertEquals(listOf(listOf(0)), clusterChanges(loose, listOf(ChangeEdge(0, 1))).map { it.members })
    }

    @Test fun `annotations attach to existing ids only and reject a whole bad batch`() {
        val flows = ChangeFlows(graph())
        val flow = flows.graph.clusters.single { 0 in it.members }.id
        val note = NodeAnnotation("Routes saving through the new store.", ChangeIntent.NEW_BEHAVIOR, ChangeImportance.KEY)
        for (update in listOf(
            FlowAnnotationUpdate(),
            FlowAnnotationUpdate(nodes = mapOf(99 to note)),
            FlowAnnotationUpdate(nodes = mapOf(2 to note)),
            FlowAnnotationUpdate(nodes = mapOf(0 to note.copy(summary = " "))),
            FlowAnnotationUpdate(nodes = mapOf(0 to note.copy(summary = "x".repeat(ChangeFlows.MAX_SUMMARY + 1)))),
            FlowAnnotationUpdate(nodes = mapOf(0 to note), clusters = mapOf(42 to ClusterAnnotation("Nope"))),
            FlowAnnotationUpdate(themes = emptyList()),
            FlowAnnotationUpdate(themes = listOf(ChangeTheme("A", null, listOf(flow)), ChangeTheme("B", null, listOf(flow)))),
            FlowAnnotationUpdate(themes = listOf(ChangeTheme("A", null, listOf(77)))),
            FlowAnnotationUpdate(themes = listOf(ChangeTheme("A", null, emptyList()))),
            FlowAnnotationUpdate(themes = listOf(ChangeTheme("A", null, listOf(flow), listOf("invented.md")))),
        )) {
            try { flows.annotate(update); fail(update.toString()) } catch (_: ToolInputException) { }
        }
        val annotated = flows.annotate(FlowAnnotationUpdate(nodes = mapOf(0 to note),
            themes = listOf(ChangeTheme(" Store saves ", "Adds a store.", listOf(flow), listOf("README.md")))))
        assertEquals(note, annotated.nodeNotes[0])
        assertSame("The graph never changes", flows.graph, annotated.graph)
        val themes = annotated.displayThemes()
        assertEquals("Store saves", themes.first().title)
        assertTrue(themes.first().inferred)
        assertEquals(flows.graph.clusters.size, themes.sumOf { it.clusters.size })
        assertFalse("Unthemed clusters keep structural titles", themes.last().inferred)
        assertTrue("A theme's files leave the other files", annotated.otherFiles().none { it.path == "README.md" })
        assertTrue(flows.otherFiles().any { it.path == "README.md" })
        assertTrue("Files with clustered functions are not other files", flows.otherFiles().none { it.path == "src/a.kt" })
        val coverage = annotated.uncovered()
        assertEquals("Trivial and context nodes need no interpretation", listOf(1, 3, 4, 5, 6), coverage.nodes)
        assertEquals(flows.graph.clusters.map { it.id } - flow, coverage.clusters)
        // A later theme batch replaces the previous themes as a whole.
        val regrouped = annotated.annotate(FlowAnnotationUpdate(themes = listOf(ChangeTheme("All", null, flows.graph.clusters.map { it.id }))))
        assertEquals(listOf("All"), regrouped.themes.map { it.title })
        assertTrue(regrouped.uncovered().clusters.isEmpty())
        assertTrue("README.md returns to other files", regrouped.otherFiles().any { it.path == "README.md" })
    }

    private class Context(var flows: ChangeFlows) : DiffReviewContext {
        override suspend fun state(offset: Int, patchBudget: Int) = "{}"
        override suspend fun read(paths: List<String>, offset: Int) = "{}"
        override suspend fun addNotes(notes: List<DiffNote>) = ""
        override suspend fun uncovered() = emptyList<String>()
        override suspend fun flows(offset: Int) = changeFlowsJson(flows, offset)
        override suspend fun readFlowNodes(ids: List<Int>) = changeNodesJson(flows, ids)
        override suspend fun annotateFlows(update: FlowAnnotationUpdate): String { flows = flows.annotate(update); return "ok" }
        override suspend fun flowsUncovered() = flows.uncovered()
    }

    @Test fun `tool arguments are decoded strictly before any annotation is applied`() = runBlocking {
        val context = Context(ChangeFlows(graph()))
        val annotate = changeFlowTools(context).single { it.definition.name == "annotate_change_flows" }
        val schema = annotate.definition.parameters["properties"].asJsonObject["nodes"].asJsonObject["items"].asJsonObject
        assertEquals(ChangeIntent.entries.map { it.id }, schema["properties"].asJsonObject["intent"].asJsonObject["enum"].asJsonArray.map { it.asString })
        for (args in listOf(
            """{"nodes":[{"id":0,"summary":"S","intent":"invented","importance":"key"}]}""",
            """{"nodes":[{"id":0,"summary":"S","intent":"fix"}]}""",
            """{"nodes":[{"id":-1,"summary":"S","intent":"fix","importance":"key"}]}""",
            """{"nodes":[{"id":1.5,"summary":"S","intent":"fix","importance":"key"}]}""",
            """{"nodes":[{"id":0,"summary":"S","intent":"fix","importance":"key"},{"id":0,"summary":"T","intent":"fix","importance":"key"}]}""",
            """{"nodes":[{"id":0,"summary":"S","intent":"fix","importance":"key"}],"themes":[{"title":"T","clusters":["a"]}]}""",
            """{"themes":[{"title":"T","clusters":[0],"files":[3]}]}""",
            """{"nodes":"all"}""",
        )) {
            try { annotate.call(JsonParser.parseString(args).asJsonObject); fail(args) } catch (_: ToolInputException) { }
        }
        assertTrue(context.flows.nodeNotes.isEmpty())
        annotate.call(JsonParser.parseString("""{"nodes":[{"id":0,"summary":"S","intent":"fix","importance":"minor","attention":"Check nulls"}]}""").asJsonObject)
        assertEquals(NodeAnnotation("S", ChangeIntent.FIX, ChangeImportance.MINOR, "Check nulls"), context.flows.nodeNotes[0])
        val read = changeFlowTools(context).single { it.definition.name == "read_change_nodes" }
        try { read.call(JsonParser.parseString("""{"ids":[100]}""").asJsonObject); fail() } catch (_: ToolInputException) { }
        val detail = JsonParser.parseString(read.call(JsonParser.parseString("""{"ids":[0,5]}""").asJsonObject)).asJsonObject
        assertEquals(listOf(0, 5), detail["nodes"].asJsonArray.map { it.asJsonObject["id"].asInt })
        assertTrue(detail["nodes"].asJsonArray[1].asJsonObject["steps"].asString.startsWith("deleted"))
    }

    @Test fun `graph pages stay within the tool limit and list what is missing`() {
        val nodes = (0 until 300).map { node(it, "function$it", "src/f${it / 10}.kt", churn = it) }
        val graph = ChangeGraph(nodes, emptyList(), clusterChanges(nodes, emptyList()), nodes.map { it.path }.distinct().map { ChangeFile(it, "modified") })
        val flows = ChangeFlows(graph)
        val seen = mutableListOf<Int>()
        var offset: Int? = 0
        var pages = 0
        while (offset != null) {
            val text = changeFlowsJson(flows, offset)
            assertTrue(text.length <= ToolChatRunner.DEFAULT_MAX_TOOL_RESULT_CHARS)
            val page = JsonParser.parseString(text).asJsonObject
            if (offset == 0) {
                assertEquals(30, page["clusters"].asJsonArray.size())
                assertEquals(ChangeFlows.MAX_REQUIRED_NODES, page["missing"].asJsonObject["nodes_count"].asInt)
            } else assertFalse(page.has("clusters"))
            seen += page["nodes"].asJsonArray.map { it.asJsonObject["id"].asInt }
            offset = page["next_offset"]?.asInt
            pages++
        }
        assertEquals((0 until 300).toList(), seen)
        assertTrue(pages > 1)
        try { changeFlowsJson(flows, 301); fail() } catch (_: ToolInputException) { }
    }

    @Test fun `flows command expands for one turn and the coverage check sends missing work back`() = runBlocking {
        assertTrue(isFlowsRequest("/flows") && isFlowsRequest(" Flows ") && isFlowsRequest("/flows error handling"))
        assertFalse(isFlowsRequest("/flowsx") || isFlowsRequest("show flows"))
        assertTrue(flowsRequest("/flows errors", "{}").contains("Focus: errors"))
        assertFalse(flowsRequest("/flows", "{}").contains("Focus"))
        val context = Context(ChangeFlows(graph()))
        val all = context.flows.graph.clusters.map { it.id }
        fun call(name: String, args: String) = Completion("", toolCalls = listOf(LlmToolCall("c$name", name, JsonParser.parseString(args).asJsonObject)))
        val requests = mutableListOf<CompletionRequest>()
        val completions = ArrayDeque(listOf(
            call("annotate_change_flows", """{"nodes":[{"id":0,"summary":"Entry","intent":"new-behavior","importance":"key"}]}"""),
            Completion("Done."),
            call("annotate_change_flows", """{"nodes":[""" + listOf(1, 3, 4, 5, 6).joinToString(",") {
                """{"id":$it,"summary":"S$it","intent":"refactor","importance":"supporting"}""" } +
                """],"themes":[{"title":"Everything","clusters":${all}}]}"""),
            Completion("Mapped 1 theme."),
        ))
        val result = ToolChatRunner({ requests += it; completions.removeFirst() }, changeFlowTools(context),
            followUp = { context.flowsUncovered().takeIf { !it.complete }?.let(::flowsCoverageNote) })
            .run(listOf(LlmMessage.system("test"), LlmMessage.user(flowsRequest("/flows", "{}"))))
        assertEquals("Mapped 1 theme.", result.reply)
        assertTrue(context.flows.uncovered().complete)
        val nudge = requests[2].messages.last()
        assertEquals(LlmRole.USER, nudge.role)
        assertTrue(nudge.content, nudge.content.contains("5 changed nodes still need an annotation (ids 1, 3, 4, 5, 6)"))
        assertTrue(nudge.content.contains("clusters are in no theme"))
    }
}
