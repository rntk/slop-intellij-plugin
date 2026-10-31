package dev.insight.canvas

import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import dev.insight.graph.CallGraphNode
import dev.insight.graph.SymbolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class CanvasCardTextTest {
    private val symbol = CanvasCard("c1", null, CardKind.SYMBOL, CardAnchor(0, 4, 2), SymbolRef("src/Store.kt", "save(Int)", "FUNCTION", "Store"),
        "src/Store.kt", 9, "fun save(x: Int) {\n  write(x)\n}")
    private val selection = CanvasCard("c2", "c1", CardKind.SELECTION, CardAnchor(5, 10, 10), null, "src/Store.kt", 10, "write(x)")

    private fun node(kind: SymbolKind = SymbolKind.FUNCTION, inProject: Boolean = true, path: String? = "/p/src/Store.kt",
        unresolved: Boolean = false) = CallGraphNode(1, "save(Int)", "Store", "Store.kt:12", 1, inProject, kind, path, unresolved = unresolved)

    @Test
    fun `headers name the kind, container and place`() {
        assertEquals("Function", canvasKindLabel(symbol))
        assertEquals("Selection", canvasKindLabel(selection))
        assertEquals("function in Store · src/Store.kt:10-12", canvasCardMeta(symbol))
        assertEquals("selection · src/Store.kt:11", canvasCardMeta(selection))
        assertEquals("function · no source", canvasCardMeta(symbol.copy(filePath = "", text = "", symbol = symbol.symbol!!.copy(container = ""))))
        assertEquals("Source · 3 lines", canvasSourceTitle(symbol))
        assertEquals("Selected text · 1 line (cut)", canvasSourceTitle(selection.copy(textTruncated = true)))
        assertEquals("Source · 0 lines", canvasSourceTitle(symbol.copy(text = "")))
    }

    @Test
    fun `targets outside the project are labelled and noted`() {
        val relative: (String?) -> String? = { it?.removePrefix("/p/") }
        assertEquals("save(Int) · function in Store · src/Store.kt:12", canvasTargetLabel(node(), relative))
        assertEquals("save(Int) · function in Store · external", canvasTargetLabel(node(inProject = false), relative))
        assertEquals("save(Int) · function in Store · unresolved", canvasTargetLabel(node(unresolved = true, path = null), relative))
        assertNull(canvasTargetNote(node()))
        assertEquals(CanvasTargetNote.EXTERNAL, canvasTargetNote(node(path = null)))
        assertEquals(CanvasTargetNote.UNRESOLVED, canvasTargetNote(node(unresolved = true)))
    }

    @Test
    fun `the transcript keeps the conversation and shortens tool calls to one line`() {
        val lines = canvasTranscript(listOf(
            ChatMessage(ChatRole.NOTICE, "Ask about save."),
            ChatMessage(ChatRole.USER, " What does it do? "),
            ChatMessage(ChatRole.TOOL, "read_file(path=" + "x".repeat(200) + ")\nmore"),
            ChatMessage(ChatRole.ASSISTANT, "It saves.\n\nSee src/Store.kt:10."),
            ChatMessage(ChatRole.ASSISTANT, "  "),
        ))
        assertEquals(listOf(CanvasChatLineKind.NOTICE, CanvasChatLineKind.USER, CanvasChatLineKind.TOOL, CanvasChatLineKind.ASSISTANT),
            lines.map { it.kind })
        assertEquals("What does it do?", lines[1].text)
        assertEquals(CANVAS_TOOL_LINE, lines[2].text.length)
        assertTrue(lines[2].text.endsWith("…"))
        assertEquals("It saves.\n\nSee src/Store.kt:10.", lines[3].text)
        assertEquals("Insight", lines[3].label)
    }

    @Test
    fun `saved sessions are listed with their size and time`() {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("Main.kt · 1 card · Jan 2 03:04", canvasSessionLabel(CanvasSessionEntry("a", "src/Main.kt", "Main.kt",
                java.time.Instant.parse("2024-01-02T03:04:00Z").toEpochMilli(), 1), Locale.US))
            assertTrue(canvasSessionLabel(CanvasSessionEntry("a", "src/Main.kt", "Main.kt", 0, 3), Locale.US).contains("3 cards"))
        } finally {
            TimeZone.setDefault(zone)
        }
    }
}
