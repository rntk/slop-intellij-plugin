package dev.insight.board

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.agent.ChatTool
import dev.insight.agent.SEARCH_TOOLS
import dev.insight.agent.ToolInputException
import dev.insight.agent.ToolSet
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallGraphResult
import dev.insight.graph.SymbolKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardToolsTest {
    /** In-memory sources: after-side [files], [before] texts and patches, and a tiny call graph. */
    private class FakeBoardSources(override val hasComparison: Boolean = true) : BoardSources {
        val files = mutableMapOf(
            "src/App.kt" to "fun main() {\n    App().run()\n}\nclass App {\n    fun run() = Store().save()\n}",
            "src/Store.kt" to (1..120).joinToString("\n") { "line $it" },
        )
        val before = mapOf("src/App.kt" to "fun main() {\n    println()\n}")
        val patches = mapOf("src/App.kt" to "@@ -1,3 +1,3 @@\n fun main() {\n-    println()\n+    App().run()\n }\n@@ -10,1 +10,2 @@\n x\n+y\n")
        val reads = mutableListOf<BoardAnchor>()
        val graph = BoardGraphSources(CallGraphResult(CallGraph(listOf(
            CallGraphNode(0, "main()", "", "App.kt:1", 0, true, SymbolKind.FUNCTION, "/p/src/App.kt"),
            CallGraphNode(1, "run()", "App", "App.kt:5", 1, true, SymbolKind.FUNCTION, "/p/src/App.kt"),
            CallGraphNode(2, "save()", "Store", "Store.kt:4", 2, true, SymbolKind.FUNCTION, "/p/src/Store.kt"),
            CallGraphNode(3, "save()", "Cache", "Cache.kt:2", 2, true, SymbolKind.FUNCTION, "/p/src/Cache.kt"),
            CallGraphNode(4, "log()", "", "Log.kt:1", 1, true, SymbolKind.FUNCTION, "/p/src/Log.kt"),
        ), setOf(CallGraphEdge(0, 1), CallGraphEdge(1, 2)), false), emptyList()), null, "/p")

        override suspend fun read(anchor: BoardAnchor): ResolvedSource {
            reads += anchor
            val text = (if (anchor.side == BoardSide.BEFORE) before[anchor.path] else files[anchor.path])
                ?: throw ToolInputException("No file '${anchor.path}'.")
            return sourceExcerpt(anchor, text)
        }

        override suspend fun diff(path: String, first: Int?, last: Int?): ResolvedSource =
            patchExcerpt(path, patches[path] ?: throw ToolInputException("$path has no diff."), first, last)

        override suspend fun symbols(name: String, path: String?) = graph.symbols(name, path)
        override suspend fun symbol(node: Int) = graph.symbol(node)
        override suspend fun chain(from: Int, to: Int) = graph.chain(from, to)
        override suspend fun callees(node: Int, limit: Int) = graph.callees(node, limit)
    }

    private val sources = FakeBoardSources()
    private var counter = 0
    private val model = BoardModel(Board.new("Explainer", now = 0, id = "board1"), clock = { 0 }, ids = { "${++counter}" })
    private var edits = 0
    private fun tools(maxAdds: Int = BoardLimits.ADDS_PER_REPLY, source: BoardSources = sources) =
        boardTools(model, source, { change -> edits++; change() }, maxAdds)

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    private fun List<ChatTool>.call(name: String, json: String = "{}") = runBlocking { first { it.definition.name == name }.call(args(json)) }
    private fun List<ChatTool>.fails(name: String, json: String) =
        assertThrows(ToolInputException::class.java) { call(name, json) }.message!!

    @Test
    fun `add_code reads the anchor through the sources and reports what it read`() {
        val tools = tools()
        val reply = tools.call("add_code", """{"path":"src/App.kt","first_line":4,"last_line":6,"title":"App",
            "annotations":[{"line":5,"text":"Saves","severity":"warning"}]}""")
        val block = model.board.blocks.single() as CodeBlock
        assertEquals("c1", block.id)
        assertEquals(BoardAnchor("src/App.kt", 4, 6), block.anchor)
        assertEquals("class App {\n    fun run() = Store().save()\n}", block.text)
        assertEquals(listOf(Annotation(5, "Saves", Severity.WARNING)), block.annotations)
        assertEquals(CodeKind.SOURCE, block.kind)
        assertEquals(BlockWidth.WIDE, block.width)
        assertEquals("Added code block [c1] showing src/App.kt:4-6 (3 lines), starting: class App {\nWith 1 annotation(s).", reply)
        assertEquals(1, edits)
    }

    @Test
    fun `add_code clamps long ranges and the file end with notices`() {
        val tools = tools()
        val long = tools.call("add_code", """{"path":"src/Store.kt","first_line":"1","last_line":200}""")
        assertEquals(BoardAnchor("src/Store.kt", 1, BoardLimits.MAX_CODE_LINES), (model.board.blocks.single() as CodeBlock).anchor)
        assertTrue(long, long.contains("Notice: src/Store.kt:1-200 is longer than a code block shows; it shows 1-80."))

        val end = tools.call("add_code", """{"path":"src/App.kt","first_line":5,"last_line":30}""")
        assertTrue(end, end.contains("src/App.kt:5-6 (2 lines)"))
        assertTrue(end, end.contains("Notice: src/App.kt ends at line 6"))
    }

    @Test
    fun `add_code rejects annotations outside the lines read, bad paths and lines, without adding`() {
        val tools = tools()
        assertEquals("Annotation 2: line 9 is outside the lines shown, src/App.kt:4-6; annotate lines 4-6.",
            tools.fails("add_code", """{"path":"src/App.kt","first_line":4,"last_line":20,
                "annotations":[{"line":4,"text":"ok"},{"line":9,"text":"past the end"}]}"""))
        assertEquals("No file 'src/Nope.kt'.", tools.fails("add_code", """{"path":"src/Nope.kt","first_line":1,"last_line":2}"""))
        assertEquals("src/App.kt has only 6 lines; line 40 is past its end.",
            tools.fails("add_code", """{"path":"src/App.kt","first_line":40,"last_line":41}"""))
        assertEquals("lines must be 1-based with first_line not after last_line",
            tools.fails("add_code", """{"path":"src/App.kt","first_line":3,"last_line":2}"""))
        assertEquals("severity must be one of info, warning, error",
            tools.fails("add_code", """{"path":"src/App.kt","first_line":1,"last_line":2,"annotations":[{"line":1,"text":"x","severity":"fatal"}]}"""))
        assertTrue(model.board.blocks.isEmpty())
        assertEquals(0, edits)
    }

    @Test
    fun `before side reads the comparison and is refused outside one`() {
        tools().call("add_code", """{"path":"src/App.kt","first_line":2,"last_line":2,"side":"before"}""")
        val block = model.board.blocks.single() as CodeBlock
        assertEquals(BoardAnchor("src/App.kt", 2, 2, BoardSide.BEFORE), block.anchor)
        assertEquals("    println()", block.text)

        val project = tools(source = FakeBoardSources(hasComparison = false))
        assertTrue(project.fails("add_code", """{"path":"src/App.kt","first_line":2,"last_line":2,"side":"before"}""")
            .contains("needs a comparison"))
        assertFalse(project.any { it.definition.name == "add_diff" })
        assertFalse(project.first { it.definition.name == "add_code" }.definition.parameters.getAsJsonObject("properties").has("side"))
    }

    @Test
    fun `add_diff shows the hunks touching after-side lines`() {
        val tools = tools()
        val reply = tools.call("add_diff", """{"path":"src/App.kt","first_line":11,"annotations":[{"line":11,"text":"new"}]}""")
        val block = model.board.blocks.single() as CodeBlock
        assertEquals(CodeKind.DIFF, block.kind)
        assertEquals("@@ -10,1 +10,2 @@\n x\n+y", block.text)
        assertEquals(BoardAnchor("src/App.kt", 10, 11), block.anchor)
        assertEquals("Added diff block [d1] showing 3 patch rows of src/App.kt:10-11, starting: @@ -10,1 +10,2 @@", reply)
        assertEquals("No hunk of src/App.kt touches after-side lines 5-6; its hunks cover after-side lines 1-3, 10-11.",
            tools.fails("add_diff", """{"path":"src/App.kt","first_line":5,"last_line":6}"""))
        assertEquals("src/Store.kt has no diff.", tools.fails("add_diff", """{"path":"src/Store.kt"}"""))
    }

    @Test
    fun `adds share one allowance per reply and failed adds cost nothing`() {
        val tools = tools(maxAdds = 2)
        tools.call("add_heading", """{"text":"Overview"}""")
        tools.fails("add_text", """{"text":"x","width":"huge"}""")
        tools.call("add_text", """{"text":"Two"}""")
        val message = tools.fails("add_code", """{"path":"src/App.kt","first_line":1,"last_line":1}""")
        assertTrue(message, message.startsWith("This reply has added 2 blocks, the most one reply may add."))
        assertEquals(2, model.board.blocks.size)
        // Arrows and edits are not limited.
        tools.call("connect", """{"from":"h1","to":"t2"}""")
        tools.call("update_block", """{"id":"t2","text":"Changed"}""")
        assertTrue(tools.call("board_overview").endsWith("This reply may add 0 more."))
    }

    @Test
    fun `heading and text blocks keep the model's prose with tone and width`() {
        val tools = tools()
        assertEquals("Added heading [h1]: How saving works", tools.call("add_heading", """{"text":" How saving works ","level":2}"""))
        assertEquals("Added insight callout [t2] before [h1]: Why", tools.call("add_text",
            """{"text":"Because.\n\nAnd so.","title":"Why","tone":"INSIGHT","width":"full","before":"[h1]"}"""))
        assertEquals(listOf("t2", "h1"), model.board.blocks.map { it.id })
        assertEquals(TextBlock("t2", "Because.\n\nAnd so.", "Why", Tone.INSIGHT, BlockWidth.FULL), model.block("t2"))
        assertEquals(HeadingBlock("h1", "How saving works", 2), model.block("h1"))
        assertEquals("text has 2001 characters; keep it within ${BoardLimits.MAX_TEXT_CHARS} or split it.",
            tools.fails("add_text", """{"text":"${"x".repeat(2001)}"}"""))
        assertEquals("width must be one of narrow, wide, full", tools.fails("add_text", """{"text":"x","width":"huge"}"""))
        assertEquals("level must be 1 to 3", tools.fails("add_heading", """{"text":"x","level":7}"""))
        assertEquals("No block 'zz' is on the board.", tools.fails("add_text", """{"text":"x","before":"zz"}"""))
    }

    @Test
    fun `steps and tables link anchors checked against the sources`() {
        val tools = tools()
        val reply = tools.call("add_steps", """{"title":"Flow","steps":[{"text":"Start"},{"text":"Run","path":"src/App.kt","first_line":5,"last_line":99}]}""")
        val steps = model.board.blocks.single() as StepsBlock
        assertEquals(listOf(Step("Start"), Step("Run", BoardAnchor("src/App.kt", 5, 6))), steps.steps)
        assertEquals("Added steps block [st1] with 2 steps, linking src/App.kt:5-6.", reply)
        assertEquals("Step 2: first_line is required with a path", tools.fails("add_steps",
            """{"steps":[{"text":"a"},{"text":"b","path":"src/App.kt"}]}"""))
        assertEquals("No file 'x.kt'.", tools.fails("add_steps", """{"steps":[{"text":"b","path":"x.kt","first_line":1}]}"""))

        tools.call("add_table", """{"columns":["Name","Where"],"rows":[["run",{"text":"here","path":"src/App.kt","first_line":5}]]}""")
        val table = model.board.blocks.last() as TableBlock
        assertEquals(listOf(listOf(Cell("run"), Cell("here", BoardAnchor("src/App.kt", 5, 5)))), table.rows)
        assertEquals("Row 1: has 1 cells; every row needs 2, like the columns.",
            tools.fails("add_table", """{"columns":["a","b"],"rows":[["only"]]}"""))
        assertEquals("columns must contain 1 to 6 headers", tools.fails("add_table", """{"columns":[],"rows":[["a"]]}"""))
    }

    @Test
    fun `add_symbol resolves names, lists ambiguous candidates and accepts node ids`() {
        val tools = tools()
        val ambiguous = tools.fails("add_symbol", """{"name":"save"}""")
        assertEquals("'save' matches 2 call graph symbols; pass node with one of: node 2: Store.save (function · Store.kt:4 · " +
            "src/Store.kt:4-4); node 3: Cache.save (function · Cache.kt:2 · src/Cache.kt:2-2).", ambiguous)
        assertEquals("Added symbol block [s1] for node 2: Store.save (function · Store.kt:4 · src/Store.kt:4-4).",
            tools.call("add_symbol", """{"name":"save()","path":"src/Store.kt"}"""))
        assertEquals(SymbolBlock("s1", 2, "Store.save", "function · Store.kt:4", null, BoardAnchor("src/Store.kt", 4, 4)), model.block("s1"))
        tools.call("add_symbol", """{"node":"3","summary":"Caches it"}""")
        assertEquals("Caches it", (model.board.blocks.last() as SymbolBlock).summary)
        tools.call("add_symbol", """{"name":"Store.save"}""")
        assertEquals(2, (model.board.blocks.last() as SymbolBlock).node)
        assertTrue(tools.fails("add_symbol", """{"name":"missing"}""").startsWith("The call graph has no symbol named 'missing'."))
        assertEquals("The call graph has no node 99.", tools.fails("add_symbol", """{"node":99}"""))
        assertEquals("Pass name or node.", tools.fails("add_symbol", "{}"))
    }

    @Test
    fun `add_chain finds the shortest call path between named or numbered ends`() {
        val tools = tools()
        assertEquals("Added chain block [ch1] of 3 steps: main → App.run → Store.save.",
            tools.call("add_chain", """{"from_name":"main","to_name":"save","to_path":"src/Store.kt","title":"Saving"}"""))
        val chain = model.board.blocks.single() as ChainBlock
        assertEquals(listOf(0, 1, 2), chain.steps.map { it.node })
        assertEquals(BoardAnchor("src/App.kt", 5, 5), chain.steps[1].anchor)
        assertEquals("Saving", chain.title)
        assertTrue(chain.complete)
        assertTrue(tools.fails("add_chain", """{"from_node":0,"to_node":4}""").startsWith("No call path from main to log"))
        assertTrue(tools.fails("add_chain", """{"from_name":"main","to_name":"save"}""").startsWith("'save' matches 2"))
        assertEquals("from and to are the same symbol; use add_symbol for one symbol.", tools.fails("add_chain", """{"from_node":1,"to_name":"run"}"""))
    }

    @Test
    fun `groups take their first block's place and model errors become tool errors`() {
        val tools = tools()
        tools.call("add_heading", """{"text":"Top"}""")
        tools.call("add_text", """{"text":"A"}""")
        tools.call("add_text", """{"text":"B"}""")
        assertEquals("Added group [g4] of [t2], [t3]: Pair.", tools.call("add_group", """{"children":["t2","[t3]"],"title":"Pair","tone":"note"}"""))
        assertEquals(listOf("h1", "g4", "t2", "t3"), model.board.blocks.map { it.id })
        assertEquals(listOf("h1", "g4"), model.board.topLevel.map { it.id })
        assertEquals("Groups cannot be nested; 'g4' is a group.", tools.fails("add_group", """{"children":["g4"]}"""))
        assertEquals("Block 't2' is already in group 'g4'.", tools.fails("add_group", """{"children":["t2"]}"""))
        assertEquals("children must contain 1 to 12 block ids", tools.fails("add_group", """{"children":[]}"""))
    }

    @Test
    fun `connect and disconnect draw and remove arrows`() {
        val tools = tools()
        tools.call("add_text", """{"text":"Cause"}""")
        tools.call("add_text", """{"text":"Effect"}""")
        assertEquals("Connected [t1] → [t2] (causes).", tools.call("connect", """{"from":"t1","to":"t2","label":"causes"}"""))
        assertEquals(listOf(BoardEdge("t1", "t2", "causes")), model.board.edges)
        assertEquals("Blocks 't1' and 't2' are already connected.", tools.fails("connect", """{"from":"t1","to":"t2"}"""))
        assertEquals("An arrow needs two different blocks.", tools.fails("connect", """{"from":"t1","to":"t1"}"""))
        assertEquals("Removed the arrow [t1] → [t2].", tools.call("disconnect", """{"from":"t1","to":"t2"}"""))
        assertEquals("No arrow from 't1' to 't2'.", tools.fails("disconnect", """{"from":"t1","to":"t2"}"""))
    }

    @Test
    fun `update_block changes model-authored fields only`() {
        val tools = tools()
        tools.call("add_text", """{"text":"A","title":"Old","tone":"warning"}""")
        tools.call("add_code", """{"path":"src/App.kt","first_line":1,"last_line":3}""")
        assertEquals("Updated text block [t1]: text, title, tone, width.",
            tools.call("update_block", """{"id":"t1","text":"B","title":"","tone":"none","width":"wide"}"""))
        assertEquals(TextBlock("t1", "B", null, null, BlockWidth.WIDE), model.block("t1"))

        tools.call("update_block", """{"id":"c2","title":"Main","annotations":[{"line":2,"text":"Calls run"}]}""")
        val code = model.block("c2") as CodeBlock
        assertEquals("Main", code.title)
        assertEquals(listOf(Annotation(2, "Calls run")), code.annotations)
        assertEquals("fun main() {\n    App().run()\n}", code.text)
        assertTrue(tools.fails("update_block", """{"id":"c2","text":"fake source"}""")
            .startsWith("A code block cannot change text; it can change title, annotations, width."))
        assertEquals("Annotation line 9 is outside the lines shown, src/App.kt:1-3; annotate lines 1-3.",
            tools.fails("update_block", """{"id":"c2","annotations":[{"line":9,"text":"x"}]}"""))
        tools.call("update_block", """{"id":"c2","annotations":[]}""")
        assertTrue((model.block("c2") as CodeBlock).annotations.isEmpty())
        assertEquals("Pass at least one field to change: text, title, tone, width.", tools.fails("update_block", """{"id":"t1"}"""))
        assertEquals("No block 'x' is on the board; board_overview lists the ids.", tools.fails("update_block", """{"id":"x","text":"y"}"""))
    }

    @Test
    fun `move, remove and set_title edit the board`() {
        val tools = tools()
        tools.call("add_text", """{"text":"A"}""")
        tools.call("add_text", """{"text":"B"}""")
        assertEquals("Moved [t2] before [t1].", tools.call("move_block", """{"id":"t2","before":"t1"}"""))
        assertEquals(listOf("t2", "t1"), model.board.blocks.map { it.id })
        assertEquals("Moved [t2] to the end.", tools.call("move_block", """{"id":"t2"}"""))
        assertEquals("Removed text block [t1].", tools.call("remove_block", """{"id":"t1"}"""))
        assertEquals("No block 't1' is on the board.", tools.fails("remove_block", """{"id":"t1"}"""))
        assertEquals("The board is titled: Saving", tools.call("set_title", """{"title":" Saving "}"""))
        assertEquals("Saving", model.board.title)
        assertEquals(7, edits) // a failed edit runs inside edit too
    }

    @Test
    fun `edit wraps every mutation and reads stay outside it`() {
        val log = mutableListOf<String>()
        val tools = boardTools(model, sources, { change -> log += "edit"; change(); log += "done" })
        tools.call("board_overview")
        assertTrue(log.isEmpty())
        model.subscribe { log += "change" }
        tools.call("add_text", """{"text":"A"}""")
        assertEquals(listOf("edit", "change", "done"), log)
    }

    @Test
    fun `board_overview and the chat context describe the board and what still fits`() {
        val tools = tools()
        tools.call("add_heading", """{"text":"Overview"}""")
        val overview = tools.call("board_overview")
        assertTrue(overview, overview.startsWith("Explainer"))
        assertTrue(overview, overview.contains("[h1]"))
        assertTrue(overview, overview.endsWith("1 of 80 blocks used; 79 more fit, and one reply may add up to 12. This reply may add 11 more."))
        val context = boardChatContext(model)
        assertTrue(context, context.contains("[h1]"))
        assertTrue(context, context.endsWith("1 of 80 blocks used; 79 more fit, and one reply may add up to 12."))
    }

    @Test
    fun `registry groups the tools by topic and a turn starts with the chat tools`() {
        val registry = boardToolRegistry(model, sources)
        assertEquals(tools().map { it.definition.name }, registry.tools.map { it.definition.name })
        assertEquals(listOf("reading the board", "adding evidence: code, diffs, symbols and call chains",
            "adding explanation: headings, prose, steps, tables, groups and arrows", "editing blocks"), registry.topics())
        assertEquals(listOf("board_overview", "add_code", "add_diff", "add_symbol", "add_chain", "add_heading", "add_text", "add_steps",
            "add_table", "add_group", "connect", "disconnect", "set_title", "update_block", "move_block", "remove_block"),
            registry.tools.map { it.definition.name })
        val set = ToolSet(registry.select(*BOARD_CHAT_TOOLS.toTypedArray()), registry)
        assertEquals(BOARD_CHAT_TOOLS.toSet() + SEARCH_TOOLS, set.names)
        val loaded = runBlocking { set[SEARCH_TOOLS]!!.call(args("""{"query":"call chain"}""")) }
        assertTrue(loaded, loaded.contains("add_chain"))
        assertTrue(set["add_chain"] != null)
    }

    @Test
    fun `system prompt composes the shared policy with the board policy for the source kind`() {
        val comparison = boardChatSystemPrompt(hasComparison = true)
        val project = boardChatSystemPrompt(hasComparison = false)
        assertTrue(comparison.startsWith(dev.insight.chat.visualCodeAssistantPrompt))
        assertTrue(comparison, comparison.contains("You build and refine an explainer board"))
        assertTrue(comparison.contains("add_diff shows the patch"))
        assertTrue(project.contains("there is no before side and no diff"))
        assertFalse(project.contains("{{"))
    }
}
