package dev.insight

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.types.TypeEvalContext
import dev.insight.callgraph.*
import dev.insight.enrichment.liftCalls
import java.awt.Container
import javax.swing.JLabel

/** Exercises real Python resolution and the schematic's treatment of missing type information. */
class PythonCallOccurrencesTest : BasePlatformTestCase() {
    private fun build(source: String): CallGraphResult {
        myFixture.configureByText("app.py", source.trimIndent())
        return ProjectGraphAnalyzer.build(project)
    }

    private fun CallGraphResult.schematic(id: Int): Schematic = SchematicBuilder.build(this, graph.hierarchy(),
        liftCalls(graph).edges.filter { it.kind == RelationKind.CALL }.groupBy { it.caller }, id)!!

    fun `test unknown members retain their receiver and separate occurrence identities`() {
        val result = build("""
            def answer(llm, prompt):
                llm.complete(prompt)
                return llm.complete(prompt)

            def other(llm):
                return llm.complete("hello")
        """)
        val calls = result.occurrences.filter { it.expression == "llm.complete" }
        assertEquals(3, calls.size)
        assertEquals(3, calls.map { it.unresolvedTarget }.distinct().size)
        assertTrue(calls.all { it.status == ResolutionStatus.UNRESOLVED && it.targets.isEmpty() })
        for (call in calls) {
            val receiver = result.graph.nodes[call.receiverTargets.single()]
            assertEquals(SymbolKind.PARAMETER, receiver.kind)
            assertEquals("llm", receiver.name)
            assertEquals("llm", call.receiver)
            assertTrue(result.graph.nodes[call.unresolvedTarget!!].unresolved)
            assertEquals(call.text, myFixture.file.text.substring(call.startOffset, call.endOffset))
            assertTrue(CallGraphEdge(call.owner, call.unresolvedTarget) in result.graph.edges)
        }
        val answer = result.graph.nodes.single { it.name.startsWith("answer") && it.kind == SymbolKind.FUNCTION }
        val blocks = result.schematic(answer.id).rows.filterIsInstance<BlockRow>()
        assertEquals(2, blocks.size)
        assertEquals(calls.filter { it.scope == answer.id }, blocks.flatMap { it.occurrences })
    }

    fun `test calls through callable parameters continue at the parameter`() {
        val result = build("""
            def run(callback):
                return callback()
        """)
        val call = result.occurrences.single()
        assertEquals(ResolutionStatus.UNRESOLVED, call.status)
        assertNull(call.unresolvedTarget)
        val binding = result.graph.nodes[call.heldBy.single()]
        assertEquals(SymbolKind.PARAMETER, binding.kind)
        assertEquals("callback", binding.name)
        assertEquals(call.heldBy, call.displayTargets)
        assertTrue(CallGraphEdge(call.owner, binding.id) in result.graph.edges)
        assertTrue(call.explanation.contains("callable value"))
        assertTrue(result.graph.nodes.none { it.unresolved })
        val row = result.schematic(call.scope).rows.filterIsInstance<BlockRow>().single()
        assertEquals(listOf(binding.id), row.calls)
    }

    fun `test calls through callable attributes continue at the attribute`() {
        val result = build("""
            from typing import Callable

            class Agent:
                on_done: Callable[[str], None]

                def __init__(self, handler):
                    self.handler = handler

                def run(self):
                    self.on_done("x")
                    self.handler("y")
        """)
        for (expression in listOf("self.on_done", "self.handler")) {
            val call = result.occurrences.single { it.expression == expression }
            val binding = result.graph.nodes[call.heldBy.single()]
            assertEquals(call.toString(), SymbolKind.PROPERTY, binding.kind)
            assertEquals(expression.removePrefix("self."), binding.name)
            assertTrue(binding.inProject && !binding.unresolved)
        }
    }

    fun `test reassigned receivers navigate to their binding rather than the enclosing function`() {
        val result = build("""
            def answer(first, second):
                llm = first
                llm = second
                return llm.complete()
        """)
        val call = result.occurrences.single()
        val receiver = result.graph.nodes[call.receiverTargets.single()]
        assertEquals(SymbolKind.VARIABLE, receiver.kind)
        assertEquals("llm", receiver.name)
        assertEquals(myFixture.file.text.indexOf("llm = first"), result.pointers[receiver.id]!!.element!!.textOffset)
        assertEquals(1, result.graph.nodes.count { it.kind == SymbolKind.VARIABLE && it.name == "llm" })
    }

    fun `test typed receivers imported stubs factories and aliases use IDE inference`() {
        myFixture.addFileToProject("client.pyi", """
            class Client:
                def complete(self, prompt: str) -> str: ...

            def make_client() -> Client: ...
        """.trimIndent())
        val result = build("""
            from client import Client, make_client

            def typed(llm: Client):
                return llm.complete("typed")

            def inferred():
                llm = make_client()
                alias = llm
                return alias.complete("alias")
        """)
        val calls = result.occurrences.filter { it.expression.endsWith(".complete") }
        assertEquals(2, calls.size)
        for (call in calls) {
            assertEquals(call.toString(), ResolutionStatus.RESOLVED, call.status)
            val target = result.graph.nodes[call.targets.single()]
            assertTrue(target.name.startsWith("complete"))
            assertTrue(target.filePath!!.endsWith("client.pyi"))
        }
        val alias = calls.single { it.receiver == "alias" }
        assertEquals("alias", result.graph.nodes[alias.receiverTargets.single()].name)
    }

    fun `test union receiver keeps every candidate`() {
        val result = build("""
            class First:
                def complete(self):
                    return "first"

            class Second:
                def complete(self):
                    return "second"

            def answer(llm: First | Second):
                return llm.complete()
        """)
        val call = result.occurrences.single { it.expression == "llm.complete" }
        assertEquals(call.toString(), ResolutionStatus.AMBIGUOUS, call.status)
        assertEquals(2, call.targets.size)
        assertTrue(call.targets.all { result.graph.nodes[it].name.startsWith("complete") })
        val row = result.schematic(call.scope).rows.filterIsInstance<BlockRow>().single()
        assertEquals(call.targets, row.calls)
        assertEquals(listOf(call), row.occurrences)
    }

    fun `test receivers of unknown type fall back to name matches like Go to Declaration`() {
        myFixture.addFileToProject("llms.py", """
            class LocalLlm:
                def complete(self, user_prompt, system_prompt):
                    return "done"
        """.trimIndent())
        // `llm` comes from an unresolvable factory, so its type is unknown; the closure reads it as a free variable.
        val result = build("""
            from vendor import make_llm

            llm = make_llm()

            def cached_complete(system_prompt, user_prompt):
                return llm.complete(user_prompt=user_prompt, system_prompt=system_prompt)
        """)
        val call = result.occurrences.single { it.expression == "llm.complete" }
        assertTrue(call.toString(), call.byName)
        val target = result.graph.nodes[call.targets.single()]
        assertTrue(target.name.startsWith("complete"))
        assertTrue(target.filePath!!.endsWith("llms.py"))
        assertTrue(target.inProject && !target.unresolved)
        assertTrue(call.explanation.contains("by member name"))
        assertTrue(CallGraphEdge(call.owner, call.targets.single()) in result.graph.edges)
    }

    fun `test name matches that are too generic become choices rather than targets`() {
        myFixture.addFileToProject("many.py", (1..5).joinToString("\n\n") { "class C$it:\n    def run(self):\n        return $it" })
        val result = build("""
            def go(job):
                return job.run()
        """)
        val call = result.occurrences.single { it.expression == "job.run" }
        assertEquals(ResolutionStatus.UNRESOLVED, call.status)
        assertFalse(call.byName)
        assertEquals(5, call.choices.size)
        assertTrue(call.choices.all { result.graph.nodes[it].name.startsWith("run") && result.graph.nodes[it].inProject })
        // Choices are not established calls: the call keeps its unresolved card and gains no edges to them.
        assertTrue(result.graph.nodes[call.unresolvedTarget!!].unresolved)
        assertEquals(listOf(call.unresolvedTarget), call.displayTargets)
        assertTrue(call.choices.none { CallGraphEdge(call.owner, it) in result.graph.edges })
        assertTrue(call.explanation.contains("Pick one"))
    }

    fun `test picking a choice unfolds that candidate in the card`() {
        myFixture.addFileToProject("many.py", (1..5).joinToString("\n\n") { "class C$it:\n    def run(self):\n        return \"picked $it\"" })
        val result = build("""
            def go(job):
                return job.run()
        """)
        val call = result.occurrences.single()
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, call.scope))
        try {
            fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
                listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
            }
            fun visible(component: java.awt.Component) = generateSequence(component) { it.parent }.all { it.isVisible }
            PlatformTestUtil.waitWithEventsDispatching("Choices should be listed", {
                descendants(editor.component).filterIsInstance<JLabel>().any { it.text.orEmpty().contains("5 declarations share this name") }
            }, 10)
            val choice = descendants(editor.component).filterIsInstance<ActionLink>()
                .filter { it.text.startsWith("run") && visible(it) }
            assertEquals(5, choice.size)
            choice[2].doClick()
            PlatformTestUtil.waitWithEventsDispatching("The picked candidate should unfold", {
                descendants(editor.component).filterIsInstance<javax.swing.JTextArea>().any { visible(it) && it.text.contains("picked 3") }
            }, 10)
            assertTrue(descendants(editor.component).filterIsInstance<JLabel>().any { it.text.orEmpty().contains("Following your pick") })
            assertTrue(descendants(editor.component).filterIsInstance<ActionLink>().any { it.text.startsWith("▸ run") })
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }

    fun `test stub targets navigate to their implementation`() {
        myFixture.addFileToProject("client.py", "class Client:\n    def complete(self, prompt):\n        return prompt\n")
        myFixture.addFileToProject("client.pyi", "class Client:\n    def complete(self, prompt: str) -> str: ...\n")
        val result = build("""
            from client import Client

            def answer(llm: Client):
                return llm.complete("x")
        """)
        val call = result.occurrences.single { it.expression == "llm.complete" }
        assertTrue(result.graph.nodes[call.targets.single()].filePath!!.endsWith("client.py"))
    }

    fun `test cross file inferred receivers match IDE navigation`() {
        myFixture.addFileToProject("client.py", """
            class Client:
                def complete(self):
                    return "done"

            def make_client():
                return Client()
        """.trimIndent())
        myFixture.configureByText("app.py", """
            from client import make_client

            def answer():
                llm = make_client()
                return llm.complete()
        """.trimIndent())
        val site = PsiTreeUtil.findChildrenOfType(
            myFixture.file, PyCallExpression::class.java
        ).single { it.callee?.text == "llm.complete" }
        val context = PyResolveContext.defaultContext(TypeEvalContext.userInitiated(project, myFixture.file))
        val navigation = (site.callee as PyReferenceExpression)
            .getReference(context).multiResolve(false).mapNotNull { it.element }
        assertTrue("IDE navigation should find complete", navigation.any {
            it is PyFunction && it.name == "complete"
        })
        val result = ProjectGraphAnalyzer.build(project)
        val call = result.occurrences.single { it.expression == "llm.complete" }
        assertEquals(call.toString(), ResolutionStatus.RESOLVED, call.status)
        assertEquals("Client", call.receiverType)
        assertTrue(result.pointers[call.targets.single()]!!.element in navigation)
        assertTrue(CallGraphEdge(call.owner, call.targets.single()) in result.graph.edges)
    }

    fun `test long calls keep their exact starting line and nested functions own occurrences`() {
        val argument = "x".repeat(700)
        val result = build("""
            def outer(llm):
                def inner():
                    llm.complete("inner")
                llm.complete(
                    "$argument")
        """)
        val outer = result.graph.nodes.single { it.name.startsWith("outer") && it.kind == SymbolKind.FUNCTION }
        val inner = result.graph.nodes.single { it.name.startsWith("inner") && it.kind == SymbolKind.FUNCTION }
        val schematic = result.schematic(outer.id)
        val rows = schematic.rows.filterIsInstance<BlockRow>()
        assertEquals(2, rows.size)
        assertEquals(inner.id, rows.first().declared)
        val call = rows.last().occurrences.single()
        assertEquals(outer.id, call.scope)
        assertEquals(600, call.text.length)
        assertEquals("    llm.complete(", schematic.text(rows.last().headline))
        assertEquals(inner.id, result.schematic(inner.id).rows.filterIsInstance<BlockRow>().single().occurrences.single().scope)
        val sites = CallSiteAnalyzer.collect(result, listOf(outer.id), listOf(call.unresolvedTarget!!)).getValue(outer.id)
        assertEquals(call.startOffset, (sites.single() as CallSite).startOffset)
    }

    fun `test unknown schematic card explains its receiver without unfolding the call as source`() {
        val result = build("""
            def answer(llm):
                return llm.complete()
        """)
        val call = result.occurrences.single()
        val editor = SchematicEditorProvider().createEditor(project, SchematicFile(result, call.scope))
        try {
            fun descendants(container: Container): List<java.awt.Component> = container.components.flatMap {
                listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
            }
            fun labels() = descendants(editor.component).filterIsInstance<JLabel>().filter { label ->
                generateSequence<java.awt.Component>(label) { it.parent }.all { it.isVisible }
            }
            PlatformTestUtil.waitWithEventsDispatching("Unresolved call should be visible", {
                labels().any { it.text.orEmpty().contains("Implementation unknown") }
            }, 10)
            val cardText = labels().single { it.text.orEmpty().contains("Implementation unknown") }
            assertTrue(cardText.text.contains("llm.complete"))
            assertTrue(cardText.mouseListeners.isEmpty())
            descendants(editor.component).filterIsInstance<ActionLink>().single { it.text == "Explain link" }.doClick()
            assertTrue(descendants(editor.component).filterIsInstance<ActionLink>()
                .any { it.text.startsWith("Receiver llm: llm") && it.isVisible })
            assertFalse(labels().any { it.text.orEmpty().contains("Source of llm.complete is no longer available") })
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }
}
