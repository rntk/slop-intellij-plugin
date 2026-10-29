package dev.insight.callgraph

import com.google.gson.JsonParser
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphEdge
import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallGraphResult
import dev.insight.graph.CallSite
import dev.insight.graph.FlowRegionEvidence

class ScopedChartContextTest : BasePlatformTestCase() {
    fun `test scoped source carries exact location full hash and bounded excerpt`() {
        val file = myFixture.configureByText("Service.java", "class Service {\n void save() {\n" +
            "  int value = 0;\n" + "  value++;\n".repeat(600) + " }\n}\n") as PsiJavaFile
        val method = file.classes.single().methods.single()
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer<PsiElement>(method)
        val node = CallGraphNode(0, "save", "Service", "Service.java:2", 0, true, filePath = file.virtualFile.path)
        val result = CallGraphResult(CallGraph(listOf(node), emptySet(), false), listOf(pointer))
        val captured = scopedChartContext(project, result, 4, FlowChatTarget("function", 0, listOf(0), "Save"))
        val json = JsonParser.parseString(captured).asJsonObject
        assertEquals(4, json["captured_revision"].asInt)
        val source = json["sources"].asJsonArray.single().asJsonObject
        assertEquals("Service.java", source["file_name"].asString)
        assertEquals(2, source["start_line"].asInt)
        assertEquals(method.textRange.startOffset, source["declaration_start_offset"].asInt)
        assertEquals(method.text.take(4000), source["source"].asString)
        assertTrue(source["source_truncated"].asBoolean)
        assertEquals(64, source["source_hash"].asString.length)
        assertTrue(source["source_available"].asBoolean)
    }

    fun `test missing source and repeated call occurrences remain explicit`() {
        val graph = CallGraph((0..1).map { CallGraphNode(it, "function$it", "Service", "Service.java", 0, true) },
            setOf(CallGraphEdge(0, 1)), false)
        val region = FlowRegionEvidence(0, listOf(CallSite(1, 10, text = "load(1)"), CallSite(1, 30, text = "load(2)")))
        val captured = scopedChartContext(project, CallGraphResult(graph, listOf(null, null)), 7,
            FlowChatTarget("region", 0, listOf(1), "Load", region))
        val json = JsonParser.parseString(captured).asJsonObject
        assertEquals(2, json["sources"].asJsonArray.size())
        assertFalse(json["sources"].asJsonArray.first().asJsonObject["source_available"].asBoolean)
        assertEquals(listOf(10, 30), json["call_occurrences"].asJsonArray.map { it.asJsonObject["file_offset"].asInt })
        assertEquals(listOf("load(1)", "load(2)"), json["call_occurrences"].asJsonArray.map { it.asJsonObject["text"].asString })
        assertEquals(1, json["static_calls"].asJsonArray.size())
    }

    fun `test snippets are read from the declaration of the function and rejected outside it`() {
        val file = myFixture.configureByText("Orders.java", "class Orders {\n  int count;\n  void save(int id) {\n" +
            "    if (id > 0) {\n\t  db.insert(id);\n    }\n  }\n}\n") as PsiJavaFile
        val method = file.classes.single().methods.single()
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer<PsiElement>(method)
        val node = CallGraphNode(0, "save", "Orders", "Orders.java:3", 0, true, filePath = file.virtualFile.path)
        val result = CallGraphResult(CallGraph(listOf(node), emptySet(), false), listOf(pointer))
        val declaration = declarationSource(project, result, 0)!!
        assertEquals(3, declaration.firstLine)
        assertEquals(7, declaration.lastLine)
        val scene = ChartScene(nodes = listOf(SceneNode(0, SceneRole.FOCUS)), snippets = listOf(SceneSnippet(0, 4, 5, "the write")))
        val snippet = captureSnippets(scene) { declarationSource(project, result, it) }.snippets.single()
        assertEquals(listOf("    if (id > 0) {", "\t  db.insert(id);"), snippet.lines)
        assertTrue(snippet.path!!.endsWith("Orders.java"))
        assertEquals(64, snippet.hash!!.length)
        for (range in listOf(2 to 3, 7 to 8)) {
            val error = org.junit.Assert.assertThrows(dev.insight.agent.ToolInputException::class.java) {
                captureSnippets(scene.copy(snippets = listOf(SceneSnippet(0, range.first, range.second)))) { declarationSource(project, result, it) }
            }
            assertTrue(error.message!!, error.message!!.contains(":3-7"))
        }
        assertNull(declarationSource(project, CallGraphResult(result.graph, listOf(null)), 0))
    }
}
