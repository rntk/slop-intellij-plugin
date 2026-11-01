package dev.insight.canvas

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.insight.agent.ChatTool
import dev.insight.agent.ToolInputException
import dev.insight.agent.SEARCH_TOOLS
import dev.insight.agent.ToolSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasToolsTest {
    private val rootText = """
        fun main() {
            val app = App()
            app.run()
            log("done")
        }
    """.trimIndent()

    private fun at(text: String, word: String, line: Int): IntRange {
        val start = text.split('\n').take(line - 1).sumOf { it.length + 1 } + text.split('\n')[line - 1].indexOf(word)
        return start until start + word.length
    }

    private val targets = listOf(
        CanvasTarget(1, "App", "App · class · src/App.kt:1", "src/App.kt", 1),
        CanvasTarget(2, "run()", "run() · function in App · src/App.kt:2", "src/App.kt", 2),
        CanvasTarget(3, "log(String)", "log(String) · function · src/Log.kt:1", "src/Log.kt", 1),
        CanvasTarget(4, "log(Int)", "log(Int) · function · src/Log.kt:2", "src/Log.kt", 2),
        CanvasTarget(5, "save()", "save() · function in Store · src/Store.kt:4", "src/Store.kt", 4),
    ).associateBy { it.id }

    private val texts = mapOf(1 to (0 to "class App {\n    fun run() { store.save() }\n}"), 2 to (1 to "    fun run() { store.save() }"),
        3 to (0 to "fun log(s: String) {}"), 4 to (1 to "fun log(i: Int) {}"), 5 to (3 to "    fun save() {}"))

    private inner class FakeCanvas : CanvasActions {
        val model = CanvasSessionModel(CanvasSession.new("src/Main.kt", now = 0), clock = { 0 },
            ids = generateSequence(1) { it + 1 }.map { "c$it" }.iterator()::next)
        val sources = mutableMapOf<String?, CanvasSource>(null to CanvasSource(rootText, listOf(
            CanvasLink(at(rootText, "App", 2), listOf(1), reference = true),
            CanvasLink(at(rootText, "run", 3), listOf(2)),
            CanvasLink(at(rootText, "log", 4), listOf(3, 4)),
        ), 0, "src/Main.kt", null))
        val shown = mutableListOf<String>()

        override val session get() = model.session
        override fun source(cardId: String?) = sources[cardId]
        override fun target(id: Int) = targets[id]
        override suspend fun declarations(path: String, name: String) =
            targets.values.filter { it.path == path && it.name.substringBefore('(') == name }

        override suspend fun openSymbol(parentId: String?, start: Int, end: Int, target: Int): CanvasCard {
            val parent = sources.getValue(parentId)
            val info = targets.getValue(target)
            val (line, text) = texts.getValue(target)
            val ref = SymbolRef(info.path!!, info.name, "FUNCTION", offset = target)
            val anchor = cardAnchor(parent.text, start, end, parent.firstLine)
            model.findOpen(parentId, CardKind.SYMBOL, anchor, ref)?.let { shown += it.id; return it }
            return model.open(parentId, CardKind.SYMBOL, anchor, info.path, line, text, ref).also {
                sources[it.id] = CanvasSource(text, emptyList(), line, info.path, null, target)
                shown += it.id
            }
        }

        override suspend fun openSelection(parentId: String?, start: Int, end: Int): CanvasCard {
            val parent = sources.getValue(parentId)
            val anchor = cardAnchor(parent.text, start, end, parent.firstLine)
            return model.open(parentId, CardKind.SELECTION, anchor, parent.path, anchor.line, parent.text.substring(start, end)).also {
                sources[it.id] = CanvasSource(it.text, emptyList(), it.firstLine, it.filePath, null)
            }
        }

        override suspend fun openFile(parentId: String?, start: Int, end: Int, path: String): CanvasCard {
            if (path != "src/Log.kt") throw ToolInputException("No project file or directory at '$path'.")
            val parent = sources.getValue(parentId)
            val text = "fun log(s: String) {}\nfun log(i: Int) {}"
            return model.open(parentId, CardKind.SYMBOL, cardAnchor(parent.text, start, end, parent.firstLine), path, 0, text,
                SymbolRef(path, "Log.kt", "FILE"), wholeFile = true).also { sources[it.id] = CanvasSource(text, emptyList(), 0, path, null) }
        }

        override suspend fun show(cardId: String) {
            shown += cardId
        }
    }

    private fun args(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
    private fun List<ChatTool>.call(name: String, json: String = "{}") = runBlocking { first { it.definition.name == name }.call(args(json)) }
    private fun List<ChatTool>.fails(name: String, json: String) =
        assertThrows(ToolInputException::class.java) { call(name, json) }.message!!

    @Test
    fun `open_symbol clicks a name in the root file and reopening it shows the open card`() {
        val canvas = FakeCanvas()
        val first = canvas.model.open(null, CardKind.SYMBOL, CardAnchor(0, 3, 0), "src/Main.kt", 0, rootText, SymbolRef("src/Main.kt", "main", "FUNCTION"))
        canvas.sources[first.id] = canvas.sources.getValue(null)
        val tools = canvasTools(canvas, first.id)

        val opened = tools.call("open_symbol", """{"card":"root","name":"run"}""")
        val card = canvas.model.cards.last()
        assertEquals(null, card.parentId)
        assertEquals(2, card.anchor.line)
        assertEquals("""
            Opened card ${card.id} on the canvas: function run() at src/App.kt:2, from the root file line 3.
            Source (lines 2-2):
            2:     fun run() { store.save() }
        """.trimIndent(), opened)
        assertTrue(tools.call("open_symbol", """{"card":"root","name":"run","line":3}""").startsWith("Card ${card.id} was already open"))
        assertEquals(listOf(card.id, card.id), canvas.shown)

        assertTrue(tools.fails("open_symbol", """{"card":"root","name":"run","line":2}""").contains("list_symbols"))
        assertTrue(tools.fails("open_symbol", """{"card":"nope","name":"run"}""").contains("No card 'nope'"))
    }

    @Test
    fun `the root file's chat takes the root file as its current card`() {
        val canvas = FakeCanvas()
        val tools = canvasTools(canvas, null)
        assertTrue(tools.call("read_card").startsWith("Root file src/Main.kt\n"))
        tools.call("open_symbol", """{"name":"run"}""")
        val card = canvas.model.cards.single()
        assertNull(card.parentId)
        tools.call("open_declaration", """{"path":"src/Store.kt","name":"save"}""")
        assertEquals("Cards it opens attach to the root file", listOf(null, null), canvas.model.cards.map { it.parentId })
        val overview = tools.call("canvas_overview")
        assertTrue(overview, overview.startsWith("Root file: src/Main.kt [root] (current card)\n"))
        assertFalse(overview, overview.contains("on the current chain"))
    }

    @Test
    fun `a name leading to several declarations asks for a target, and list_symbols numbers them`() {
        val canvas = FakeCanvas()
        val main = canvas.model.open(null, CardKind.SELECTION, CardAnchor(0, 3, 0), "src/Main.kt", 0, "fun")
        canvas.sources[main.id] = canvas.sources.getValue(null)
        val tools = canvasTools(canvas, main.id)
        val error = tools.fails("open_symbol", """{"name":"log"}""")
        assertTrue(error, error.contains("1) log(String)") && error.contains("2) log(Int)"))
        tools.call("open_symbol", """{"name":"log","target":2}""")
        assertEquals("src/Log.kt", canvas.model.cards.last().filePath)
        assertEquals(1, canvas.model.cards.last().firstLine)

        assertEquals("""
            Symbols in card ${main.id}, lines 1-5:
            2: App → App · class · src/App.kt:1
            3: run → run() · function in App · src/App.kt:2
            4: log → 1) log(String) · function · src/Log.kt:1; 2) log(Int) · function · src/Log.kt:2
        """.trimIndent(), tools.call("list_symbols"))
        assertEquals("Symbols in the root file, lines 3-3:\n3: run → run() · function in App · src/App.kt:2",
            tools.call("list_symbols", """{"card":"root","start_line":3,"end_line":3}"""))
        assertTrue(tools.call("list_symbols", """{"name":"zzz"}""").startsWith("No clickable symbols matching 'zzz'"))
    }

    @Test
    fun `open_selection opens whole lines without their indentation`() {
        val canvas = FakeCanvas()
        val main = canvas.model.open(null, CardKind.SELECTION, CardAnchor(0, 3, 0), "src/Main.kt", 0, "fun")
        canvas.sources[main.id] = canvas.sources.getValue(null)
        val tools = canvasTools(canvas, main.id)
        tools.call("open_selection", """{"start_line":2,"end_line":3}""")
        val selection = canvas.model.cards.last()
        assertEquals("val app = App()\n    app.run()", selection.text)
        assertEquals(1, selection.anchor.line)
        assertEquals(main.id, selection.parentId)
        assertTrue(tools.fails("open_selection", """{"start_line":9}""").contains("within 1-5"))
    }

    @Test
    fun `open_declaration and open_file reach outside the chain, attached to the current card`() {
        val canvas = FakeCanvas()
        runBlocking { canvas.openSymbol(null, at(rootText, "run", 3).first, at(rootText, "run", 3).last + 1, 2) }
        val run = canvas.model.cards.single()
        val tools = canvasTools(canvas, run.id)

        val opened = tools.call("open_declaration", """{"path":"src/Store.kt","name":"save"}""")
        val save = canvas.model.cards.last()
        assertEquals(run.id, save.parentId)
        assertEquals("The arrow starts where the name is used", "    fun run() { store.".length, save.anchor.startOffset)
        assertTrue(opened, opened.startsWith("Opened card ${save.id} on the canvas: function save() at src/Store.kt:4"))
        assertTrue(tools.fails("open_declaration", """{"path":"src/Log.kt","name":"log"}""").contains("more than once"))
        tools.call("open_declaration", """{"path":"src/Log.kt","name":"log","line":2,"from_card":"root","from_line":4}""")
        val log = canvas.model.cards.last()
        assertEquals(null, log.parentId)
        assertEquals(3, log.anchor.line)
        assertEquals(1, log.firstLine)
        assertTrue(tools.fails("open_declaration", """{"path":"src/Log.kt","name":"nothing"}""").contains("search_text"))

        tools.call("open_file", """{"path":"src/Log.kt"}""")
        val file = canvas.model.cards.last()
        assertTrue(file.wholeFile)
        assertEquals(run.id, file.parentId)
        assertTrue(tools.fails("open_file", """{"path":"../secret"}""").contains("No project file"))
    }

    @Test
    fun `opening tools share a per-reply allowance while reading tools do not count`() {
        val canvas = FakeCanvas()
        val main = canvas.model.open(null, CardKind.SELECTION, CardAnchor(0, 3, 0), "src/Main.kt", 0, "fun")
        canvas.sources[main.id] = canvas.sources.getValue(null)
        val tools = canvasTools(canvas, main.id, maxOpened = 1)
        tools.call("open_symbol", """{"name":"run"}""")
        tools.call("canvas_overview")
        tools.call("show_card", """{"card":"current"}""")
        assertTrue(tools.fails("open_symbol", """{"name":"App"}""").contains("opened 1 cards"))
        canvasTools(canvas, main.id, maxOpened = 1).call("open_symbol", """{"name":"App"}""")
        assertEquals("A fresh reply gets a fresh allowance", 3, canvas.model.cards.size)
    }

    @Test
    fun `a card's chat starts with the walking tools and finds the others with search_tools`() {
        val canvas = FakeCanvas()
        val main = canvas.model.open(null, CardKind.SELECTION, CardAnchor(0, 3, 0), "src/Main.kt", 0, "fun")
        val registry = canvasToolRegistry(canvas, main.id)
        assertEquals(canvasTools(canvas, main.id).map { it.definition.name }, registry.tools.map { it.definition.name })
        val tools = ToolSet(registry.select(*CANVAS_CHAT_TOOLS.toTypedArray()), registry)
        assertEquals(setOf("read_card", "list_symbols", "open_symbol", SEARCH_TOOLS), tools.names)
        val found = runBlocking { tools[SEARCH_TOOLS]!!.call(args("""{"query":"open a card for a whole file"}""")) }
        assertTrue(found, found.contains("- open_file:"))
        assertEquals(setOf("read_card", "list_symbols", "open_symbol", SEARCH_TOOLS, "open_file"), tools.names)

        val shown = runBlocking { tools[SEARCH_TOOLS]!!.call(args("""{"query":"show_card"}""")) }
        assertTrue(shown, shown.startsWith("Loaded 1 tool;"))
        assertEquals(setOf("read_card", "list_symbols", "open_symbol", SEARCH_TOOLS, "open_file", "show_card"), tools.names)
    }

    @Test
    fun `canvas_overview shows every chain and marks the current one, and read_card reads any card`() {
        val canvas = FakeCanvas()
        val run = runBlocking { canvas.openSymbol(null, at(rootText, "run", 3).first, at(rootText, "run", 3).last + 1, 2) }
        canvas.model.setSummary(run.id, "Runs the app.")
        val other = runBlocking { canvas.openSymbol(null, at(rootText, "App", 2).first, at(rootText, "App", 2).last + 1, 1) }
        val tools = canvasTools(canvas, run.id)
        assertEquals("""
            Root file: src/Main.kt [root]
            - run() [card ${run.id}]: function run() at src/App.kt:2, from line 3 (current card)
              Summary: Runs the app.
            - App [card ${other.id}]: function App at src/App.kt:1-3, from line 2
        """.trimIndent(), tools.call("canvas_overview"))

        val read = tools.call("read_card", """{"card":"${other.id}","start_line":2,"end_line":2}""")
        assertEquals("""
            Card ${other.id}: function App at src/App.kt:1-3
            Opened from: the root file, line 2
            Source (lines 2-2):
            2:     fun run() { store.save() }
            [truncated; use read_file path src/App.kt from line 3]
        """.trimIndent(), read)
        assertTrue(tools.call("read_card", """{"card":"root"}""").contains("4:     log(\"done\")"))
        assertTrue(tools.fails("show_card", """{"card":"root"}""").contains("always shown"))
        assertFalse(canvas.shown.contains("root"))
        assertNull(canvas.model.card("root"))
    }
}
