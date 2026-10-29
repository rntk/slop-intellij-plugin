package dev.insight

import dev.insight.callgraph.*
import dev.insight.entrypoints.EntryPointLens
import dev.insight.graph.*
import org.junit.Assert.*
import org.junit.Test

class ManualFlowEntryPointTest {
    private val node = CallGraphNode(0, "run(String)", "Worker", "src/Worker.kt:12", 0, true,
        filePath = "/project/src/Worker.kt")

    @Test fun `manual choice matches the exact function after graph ids change`() {
        val entry = node.manualFlowEntryPoint("/project")!!
        assertEquals("src/Worker.kt", entry.path)
        assertEquals(12, entry.line)
        assertEquals("Worker.run(String)", entry.title)
        val rebuilt = node.copy(id = 1)
        val overload = node.copy(name = "run(Int)", location = "src/Worker.kt:20")
        val graph = CallGraph(listOf(overload, rebuilt), emptySet(), false)
        assertEquals(listOf(rebuilt), EntryPointLens.targets(graph, entry, "/project", exact = true))
    }

    @Test fun `picker excludes external symbols and nodes without usable source coordinates`() {
        assertNull(node.copy(inProject = false).manualFlowEntryPoint("/project"))
        assertNull(node.copy(kind = SymbolKind.FILE).manualFlowEntryPoint("/project"))
        assertNull(node.copy(filePath = null).manualFlowEntryPoint("/project"))
        assertNull(node.copy(location = "unknown").manualFlowEntryPoint("/project"))
        assertNull(node.copy(location = "Worker.kt:0").manualFlowEntryPoint("/project"))
        assertNull(node.copy(filePath = "/other/Worker.kt").manualFlowEntryPoint("/project"))
        assertNull(node.manualFlowEntryPoint(null))
    }
}
