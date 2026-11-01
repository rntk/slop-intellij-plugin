package dev.insight.callgraph

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.ui.components.ActionLink
import dev.insight.graph.SymbolKind
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.event.MouseEvent
import javax.swing.JCheckBox
import javax.swing.JLabel

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
                descendants(panel).any { it.name == "schematic-linked-usage-card" && it is Container && descendants(it).any { child -> child is SchematicSourceEditor } }
            }, 10)
            val callerCard = descendants(panel).single { it.name == "schematic-linked-usage-card" } as Container
            assertTrue("The result card is a sibling of the source card", callerCard.parent.components.any {
                it !== callerCard && it is Container && javax.swing.SwingUtilities.isDescendingFrom(incoming, it)
            })
            assertFalse("A linked card must not be nested in its source card", javax.swing.SwingUtilities.isDescendingFrom(callerCard, incoming))
            val callerSource = descendants(callerCard).filterIsInstance<SchematicSourceEditor>().single()
            assertTrue(callerSource.editor.document.text.contains("Api.work()"))
            val header = (callerCard.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) as Container
            assertTrue("Usage links open the standard summary header", descendants(header).filterIsInstance<JLabel>().any {
                it.text.orEmpty().contains("callable-title") && it.text.contains("run")
            })
            assertTrue("Usage cards have the standard actions", links(header).map { it.text }.containsAll(
                listOf("Unpin", "Source", "Compact", "Show whole file", "Find usages", "Close")
            ))
            val fold = descendants(header).filterIsInstance<JLabel>().single { it.text == "▾" }
            fun toggleCaller() {
                val event = MouseEvent(fold, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1)
                fold.mouseListeners.forEach { it.mouseClicked(event) }
            }
            toggleCaller()
            assertEquals("The usage card folds like a symbol card", "▸", fold.text)
            assertTrue(generateSequence<Component>(callerSource) { it.parent }.takeWhile { it !== callerCard }.any { !it.isVisible })
            toggleCaller()
            assertEquals("▾", fold.text)
            assertSame("Unfolding reuses the loaded source", callerSource,
                descendants(callerCard).filterIsInstance<SchematicSourceEditor>().single())
            assertTrue("The caller's symbols remain explorable", links(callerCard).any { it.text == "Find usages" })
            links(callerCard).last { it.text == "Find usages" }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("The current call site should be excluded", {
                descendants(callerCard).filterIsInstance<javax.swing.JLabel>().any {
                    it.text == "No project usages found."
                }
            }, 20)
            val trail = descendants(panel).single { it.name == "schematic-breadcrumbs" } as Container
            assertTrue(links(trail).any { it.text.startsWith("Used by run") })

            links().first { it.text == "Hide usages" }.doClick()
            assertFalse(incoming.parent.isVisible)
            links().first { it.text == "Show usages" }.doClick()
            assertSame(callerSource, descendants(callerCard).filterIsInstance<SchematicSourceEditor>().single())
            val sourceToggle = descendants(panel).filterIsInstance<JCheckBox>().single { it.name == "schematic-native-editor" }
            sourceToggle.doClick()
            assertSame(incoming, descendants(panel).single { it.name == "schematic-usage-branch" })
            sourceToggle.doClick()
            assertTrue(descendants(callerCard).any { it is SchematicSourceEditor })

            links(incoming).first { it.text.startsWith("Call: ") }.doClick()
            PlatformTestUtil.waitWithEventsDispatching("Usage location should open a linked file", {
                descendants(panel).count { it.name == "schematic-linked-usage-card" } == 2 &&
                    descendants(panel).filterIsInstance<SchematicSourceEditor>().size == 3
            }, 10)
            assertTrue(descendants(panel).filterIsInstance<SchematicSourceEditor>().any {
                it.editor.document.text.contains("class Client")
            })
            links(incoming).first { it.text.startsWith("Call: ") }.doClick()
            assertEquals("Repeated clicks reuse the linked file", 2,
                descendants(panel).count { it.name == "schematic-linked-usage-card" })
        } finally {
            Disposer.dispose(editor)
        }
    }
}
