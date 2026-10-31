package dev.insight.canvas

import com.intellij.testFramework.LightVirtualFile
import dev.insight.callgraph.BlockRow
import dev.insight.callgraph.CodeRow
import dev.insight.callgraph.Schematic
import dev.insight.callgraph.SchematicLink
import dev.insight.callgraph.SchematicMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasLinksTest {
    private val lines = listOf(
        "class App {",                       // 10, offsets 0..10
        "    fun run() {",                   // 11, offsets 12..26
        "        store.save(load())",        // 12, offsets 28..53
        "    }",                             // 13
        "}",                                 // 14
    )
    private val rows = listOf(
        CodeRow(10, 10),
        BlockRow(11, 13, 11, declared = 1, calls = emptyList()),
        CodeRow(14, 14),
    )

    @Test
    fun `whole-row links cover the trimmed headline and placed links their columns`() {
        val split = listOf(CodeRow(10, 11), BlockRow(12, 12, 12, null, listOf(2, 3, 4), links = listOf(
            SchematicLink(listOf(2), 14..17), SchematicLink(listOf(3), 19..22), SchematicLink(listOf(5), 8..12, reference = true),
            SchematicLink(listOf(4), null), SchematicLink(emptyList(), 0..1))), CodeRow(13, 14))
        val text = lines.joinToString("\n")
        val links = canvasLinks(10, lines, split)
        assertEquals(listOf("store", "store.save(load())", "save", "load"), links.map { text.substring(it.range.first, it.range.last + 1) })
        assertEquals(CanvasLink(28 + 8..28 + 12, listOf(5), reference = true), links.first())
        assertEquals(listOf(2), linkAt(links, 28 + 15)!!.targets)
        assertEquals(listOf(4), linkAt(links, 28 + 24)!!.targets)
        assertNull(linkAt(links, 3))

        val declared = canvasLinks(10, lines, rows).single()
        assertEquals("fun run() {", text.substring(declared.range.first, declared.range.last + 1))
        assertEquals(listOf(1), declared.targets)
    }

    @Test
    fun `mentions link names in folded bodies without doubling placed row links`() {
        val split = listOf(CodeRow(10, 10), BlockRow(11, 13, 11, declared = 1, calls = emptyList(),
            links = listOf(SchematicLink(listOf(1), 8..10))), CodeRow(14, 14))
        val mentions = listOf(
            SchematicMention(1, line = 11, columns = 8..10, reference = false),
            SchematicMention(7, line = 12, columns = 8..12),
            SchematicMention(8, line = 12, columns = 8..12),
            SchematicMention(2, line = 12, columns = 14..17, reference = false),
            SchematicMention(9, line = 30, columns = 0..2),
        )
        val text = lines.joinToString("\n")
        val links = canvasLinks(10, lines, split, mentions)
        assertEquals(listOf("run", "store", "save"), links.map { text.substring(it.range.first, it.range.last + 1) })
        assertEquals("Names at the same place share a link", CanvasLink(28 + 8..28 + 12, listOf(7, 8), reference = true), links[1])
        assertEquals(CanvasLink(28 + 14..28 + 17, listOf(2), reference = false), links[2])

        val whole = canvasLinks(10, lines, rows, listOf(SchematicMention(7, line = 11, columns = 8..10)))
        assertEquals("A name inside a whole-row link wins at its place", listOf(7), linkAt(whole, 12 + 9)!!.targets)
        assertEquals(listOf(1), linkAt(whole, 12 + 4)!!.targets)
    }

    @Test
    fun `a schematic's links point into its joined lines`() {
        val schematic = Schematic(1, LightVirtualFile("App.kt", lines.joinToString("\n")), 10, lines, rows)
        assertEquals(canvasText(schematic).indexOf("fun run"), canvasLinks(schematic).single().range.first)
    }

    @Test
    fun `slices keep and re-base the links inside them`() {
        val links = listOf(CanvasLink(2..4, listOf(1)), CanvasLink(8..12, listOf(2)), CanvasLink(20..22, listOf(3)))
        assertEquals(listOf(CanvasLink(0..2, listOf(1)), CanvasLink(6..7, listOf(2))), linksInSlice(links, 2, 10))
    }

    @Test
    fun `anchors know their document line`() {
        val text = lines.joinToString("\n")
        assertEquals(CardAnchor(36, 41, 12), cardAnchor(text, 36, 41, 10))
        assertEquals(CardAnchor(text.length, text.length, 14), cardAnchor(text, 999, 1_000, 10))
    }
}
