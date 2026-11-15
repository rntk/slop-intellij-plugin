package dev.insight.context

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.insight.agent.SourceSide
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/** The picker panel built without a popup: scopes, a preview that follows them, and actions filtered per scope. */
class ContextPickerPopupTest : BasePlatformTestCase() {
    private val text = "class A {\n  void run() {\n    int x = 1;\n  }\n}\n"
    private val candidates = sourceScopes("src/A.java", null, text, 3, null,
        listOf(SymbolSpan(ContextSymbol("run", "A.run", "function"), 2..4)), null)

    private class FakeAction(override val id: String, override val title: String, override val group: String,
        private val available: (ContextRequest) -> Boolean = { true }) : ContextAction {
        val performed = mutableListOf<ContextRequest>()
        override fun isAvailable(request: ContextRequest) = available(request)
        override fun perform(request: ContextRequest) { performed += request }
    }

    private val explore = FakeAction("test.explore", "Explore symbol", "Explore") { it.context.kind == ContextScopeKind.SYMBOL }
    private val copy = FakeAction("test.copy", "Copy it", "Copy")

    private val ran = mutableListOf<Pair<ContextAction, ContextRequest>>()

    private fun picker(data: ContextData = ContextData()): ContextPicker {
        ContextActionRegistry.getInstance().register(explore, testRootDisposable)
        ContextActionRegistry.getInstance().register(copy, testRootDisposable)
        return buildContextPicker(project, candidates, defaultScopeIndex(candidates), ContextOrigin.EDITOR, data, null,
            testRootDisposable) { action, request -> ran += action to request }
    }

    private fun ContextPicker.fakeIds() = listedActions.map { it.id }.filter { it.startsWith("test.") }

    /** Selects the row of [action] in the action list, which also lists the registered built-in actions. */
    private fun ContextPicker.select(action: ContextAction) {
        actions.selectedIndex = (0 until actions.model.size).first { (actions.model.getElementAt(it) as? PickerRow.Item)?.action === action }
    }

    private fun press(component: JComponent, stroke: String) =
        component.getActionForKeyStroke(KeyStroke.getKeyStroke(stroke))!!.actionPerformed(ActionEvent(component, 0, null))

    private fun descendants(root: Container): List<Component> = root.components.flatMap {
        listOf(it) + ((it as? Container)?.let(::descendants) ?: emptyList())
    }

    fun testPanelListsScopesWithTheDefaultSelectedAndItsPreview() {
        val picker = picker()
        assertEquals("insight-context-picker", picker.component.name)
        val names = descendants(picker.component).mapNotNull { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("insight-context-scopes", "insight-context-actions", "insight-context-preview")))
        assertEquals(candidates, (0 until picker.scopes.model.size).map { picker.scopes.model.getElementAt(it) })
        assertEquals(ContextScopeKind.SYMBOL, picker.selectedContext!!.kind)
        assertEquals(candidates[1].text, picker.preview.document.text)
        assertTrue(picker.preview.isViewer)
    }

    fun testScopeChangeRefiltersActionsAndPreview() {
        val picker = picker()
        assertEquals(listOf("test.explore", "test.copy"), picker.fakeIds())
        picker.selectScope(0)
        assertEquals(ContextScopeKind.LINE, picker.selectedContext!!.kind)
        assertEquals(candidates[0].text, picker.preview.document.text)
        assertEquals(listOf("test.copy"), picker.fakeIds())
        picker.selectScope(candidates.lastIndex)
        assertEquals(text, picker.preview.document.text)
        picker.select(copy)
        assertEquals("test.copy", picker.selectedAction!!.id)
        picker.selectScope(1)
        assertEquals("The chosen action stays chosen when it is still listed", "test.copy", picker.selectedAction!!.id)
    }

    fun testActionsAreGroupedUnderHeadersThatCannotBeSelected() {
        val picker = picker()
        val rows = (0 until picker.actions.model.size).map { picker.actions.model.getElementAt(it) }
        val fakeRows = rows.filter { it is PickerRow.Header && it.group in setOf("Explore", "Copy") || it is PickerRow.Item && it.action.id.startsWith("test.") }
        assertEquals(listOf("Explore", "test.explore", "Copy", "test.copy"), fakeRows.map { if (it is PickerRow.Header) it.group else (it as PickerRow.Item).action.id })
        picker.actions.selectedIndex = rows.indexOfFirst { it is PickerRow.Header }
        assertTrue("A header stays unselected", picker.actions.selectedValue is PickerRow.Item)
    }

    fun testArrowKeysInTheActionListSkipHeaders() {
        val picker = picker()
        val first = picker.selectedAction!!
        press(picker.actions, "DOWN")
        assertNotSame(first, picker.selectedAction)
        assertTrue(picker.actions.selectedValue is PickerRow.Item)
        press(picker.actions, "UP")
        assertSame(first, picker.selectedAction)
    }

    fun testEnterRunsTheSelectedActionWithTheSelectedContext() {
        val data = ContextData().put(ContextKeys.OFFSET, 7)
        val picker = picker(data)
        picker.actions.selectedIndex = (0 until picker.actions.model.size).first {
            (picker.actions.model.getElementAt(it) as? PickerRow.Item)?.action === explore
        }
        press(picker.scopes, "ENTER")
        val (action, request) = ran.single()
        assertSame(explore, action)
        assertSame(candidates[1], request.context)
        assertEquals(candidates, request.candidates)
        assertEquals(ContextOrigin.EDITOR, request.origin)
        assertEquals(7, request.data[ContextKeys.OFFSET])
        assertEquals(SourceSide.CURRENT, request.context.side)
        assertEquals("The picker closes before performing; running only hands the action over", emptyList<ContextRequest>(), explore.performed)
    }

    fun testEnterInTheActionListAndAClickRun() {
        val picker = picker()
        picker.selectScope(0)
        picker.select(copy)
        press(picker.actions, "ENTER")
        assertSame(copy, ran.single().first)
        assertSame(candidates[0], ran.single().second.context)
        ran.clear()
        picker.actions.setSize(300, 200)
        val index = (0 until picker.actions.model.size).first { (picker.actions.model.getElementAt(it) as? PickerRow.Item)?.action === copy }
        val at = picker.actions.getCellBounds(index, index).let { java.awt.Point(it.x + 5, it.y + it.height / 2) }
        picker.actions.dispatchEvent(MouseEvent(picker.actions, MouseEvent.MOUSE_CLICKED, 0L, 0, at.x, at.y, 1, false, MouseEvent.BUTTON1))
        assertSame(copy, ran.single().first)
    }

    fun testNoSelectedActionRunsNothing() {
        val picker = buildContextPicker(project, emptyList(), 0, ContextOrigin.OTHER, ContextData(), null, testRootDisposable) { a, r -> ran += a to r }
        assertNull(picker.selectedContext)
        assertFalse(picker.runSelected())
        assertTrue(ran.isEmpty())
        assertEquals("", picker.preview.document.text)
    }

    fun testPatchPreviewUsesTheDiffFileType() {
        val patch = CodeContext(ContextScopeKind.HUNK, "Hunk", "a.kt", null, 1..2, SourceSide.AFTER, "@@ -1 +1 @@\n-a\n+b\n", isPatch = true)
        val picker = buildContextPicker(project, listOf(patch), 0, ContextOrigin.IDE_DIFF, ContextData(), null, testRootDisposable) { _, _ -> }
        assertEquals(patch.text, picker.preview.document.text)
        assertEquals("context.diff", previewFileName(patch))
        assertFalse("Patch rows are not numbered source lines", picker.preview.settings.isLineNumbersShown)
    }

    fun testPreviewEditorIsReleasedWithTheParent() {
        val parent = com.intellij.openapi.util.Disposer.newDisposable()
        val picker = buildContextPicker(project, candidates, 0, ContextOrigin.EDITOR, ContextData(), null, parent) { _, _ -> }
        assertFalse(picker.preview.isDisposed)
        com.intellij.openapi.util.Disposer.dispose(parent)
        assertTrue(picker.preview.isDisposed)
    }
}
