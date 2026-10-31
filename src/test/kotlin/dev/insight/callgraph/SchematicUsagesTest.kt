package dev.insight.callgraph

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.graph.SymbolKind
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox

class SchematicUsagesTest : LightJavaCodeInsightFixtureTestCase() {
    private fun descendants(component: Container): List<Component> = component.components.flatMap {
        listOf(it) + if (it is Container) descendants(it) else emptyList()
    }

    fun testCallerUnfoldsInTheSameCanvasAndKeepsItsExplorationAcrossViewChanges() {
        val file = myFixture.configureByText("Api.java", "class Api { static void work() {} }")
        myFixture.addFileToProject("Client.java", "class Client { void run() { Api.work(); } }")
        val initial = ProjectGraphAnalyzer.buildScope(project, file, declarationsOnly = true)
        val root = initial.graph.nodes.single { it.kind == SymbolKind.FILE }.id
        val schematicFile = SchematicFile(initial, root)
        val editor = SchematicEditorProvider().createEditor(project, schematicFile)
        try {
            val panel = editor.component as SchematicPanel
            fun links(component: Container = panel) = descendants(component).filterIsInstance<ActionLink>()
            PlatformTestUtil.waitWithEventsDispatching("File should load", {
                links().any { it.text == "Find usages" }
            }, 10)
            val originalSource = descendants(panel).filterIsInstance<SchematicSourceEditor>().single()
            links().single { it.text == "Find usages" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Indexed caller should appear", {
                links().any { it.text.startsWith("Explore run") }
            }, 20)
            assertNotNull(schematicFile.exploration)
            assertSame(originalSource, descendants(panel).filterIsInstance<SchematicSourceEditor>().single())
            val incoming = descendants(panel).single { it.name == "schematic-usage-branch" } as Container
            assertTrue(links(incoming).any { it.text.startsWith("Call: ") && it.text.contains("Client.java:1") })
            links(incoming).first { it.text.startsWith("Explore run") }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Caller should unfold", {
                descendants(incoming).any { it is SchematicSourceEditor }
            }, 10)
            val callerSource = descendants(incoming).filterIsInstance<SchematicSourceEditor>().single()
            assertTrue(callerSource.editor.document.text.contains("Api.work()"))
            assertTrue("The caller's symbols remain explorable", links(incoming).any { it.text == "Find usages" })
            val trail = descendants(panel).single { it.name == "schematic-breadcrumbs" } as Container
            assertTrue(links(trail).any { it.text.startsWith("Used by run") })

            links().first { it.text == "Hide usages" }.doClick()
            assertFalse(incoming.parent.isVisible)
            links().first { it.text == "Show usages" }.doClick()
            assertSame(callerSource, descendants(incoming).filterIsInstance<SchematicSourceEditor>().single())
            val sourceToggle = descendants(panel).filterIsInstance<JCheckBox>().single { it.name == "schematic-native-editor" }
            sourceToggle.doClick()
            assertSame(incoming, descendants(panel).single { it.name == "schematic-usage-branch" })
            sourceToggle.doClick()
            assertTrue(descendants(incoming).any { it is SchematicSourceEditor })
        } finally {
            Disposer.dispose(editor)
        }
    }
}
