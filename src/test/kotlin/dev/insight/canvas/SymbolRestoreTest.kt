package dev.insight.canvas

import dev.insight.graph.CallGraphNode
import dev.insight.graph.SymbolKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SymbolRestoreTest {
    private fun node(id: Int, name: String, line: Int, container: String = "Store", kind: SymbolKind = SymbolKind.FUNCTION,
        path: String = "/p/src/Store.kt", unresolved: Boolean = false) =
        CallGraphNode(id, name, container, "Store.kt:${line + 1}", 1, true, kind, path, unresolved = unresolved)

    private val relative: (CallGraphNode) -> String? = { it.filePath?.removePrefix("/p/") }

    @Test
    fun `the same file, kind and name nearest the saved line wins`() {
        val ref = SymbolRef("src/Store.kt", "save", "FUNCTION", "Store", line = 40)
        val nodes = listOf(node(1, "save", 10), node(2, "save", 44), node(3, "save", 41, kind = SymbolKind.PROPERTY),
            node(4, "save", 40, path = "/p/src/Other.kt"), node(5, "save", 40, unresolved = true), node(6, "load", 40))
        assertEquals(2, restoreSymbol(ref, nodes, relative)?.id)
        assertEquals(1, restoreSymbol(ref.copy(offset = 100), nodes, relative, offset = { if (it.id == 1) 90 else 900 })?.id)
        assertEquals(1, restoreSymbol(ref, listOf(node(1, "save", 10), node(2, "save", 40, container = "Other")), relative)?.id)
    }

    @Test
    fun `a renamed signature still matches by name and nothing else is guessed`() {
        val ref = SymbolRef("src/Store.kt", "save(Int)", "FUNCTION", "Store", line = 3)
        assertEquals(7, restoreSymbol(ref, listOf(node(7, "save(Long)", 5)), relative)?.id)
        assertNull(restoreSymbol(ref, listOf(node(8, "load(Int)", 3)), relative))
    }

    @Test
    fun `refs are built from nodes`() {
        assertEquals(SymbolRef("src/Store.kt", "save", "FUNCTION", "Store", 12, 3),
            symbolRef(node(1, "save", 3), 12, 3, path = "src/Store.kt"))
        assertNull(symbolRef(CallGraphNode(1, "x", "", "", 0, false), 0, 0))
    }
}
