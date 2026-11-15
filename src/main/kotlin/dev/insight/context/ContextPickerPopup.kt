package dev.insight.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.LineNumberConverter
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.InsightBundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.DefaultListSelectionModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Shows the context picker at [at]: the [candidates] scopes (selecting [defaultIndex] first) with a syntax-highlighted preview of
 * the selected one, and the actions that apply to it. Enter or a click on an action closes the popup, then performs the action
 * for the selected scope. [owner] is the component the picker was opened over.
 */
fun showContextPicker(project: Project, candidates: List<CodeContext>, defaultIndex: Int, origin: ContextOrigin, data: ContextData,
    owner: Component, at: RelativePoint): JBPopup {
    val lifetime = Disposer.newDisposable("insight-context-picker")
    lateinit var popup: JBPopup
    val picker = buildContextPicker(project, candidates, defaultIndex, origin, data, owner, lifetime) { action, request ->
        popup.closeOk(null)
        // After the popup is gone, so the action's own windows take focus from the editor and not from a closing popup.
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) action.perform(request) }, project.disposed)
    }
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(picker.component, picker.scopes)
        .setTitle(InsightBundle.message("context.picker.title"))
        .setRequestFocus(true)
        .setFocusable(true)
        .setMovable(true)
        .setResizable(true)
        .setCancelOnClickOutside(true)
        .setCancelKeyEnabled(true)
        .createPopup()
    Disposer.register(popup, lifetime)
    popup.show(at)
    return popup
}

/**
 * Builds the picker's panel without showing it. [run] receives the action to perform and its request when the user chooses one;
 * the views and the preview editor are released with [parent].
 */
internal fun buildContextPicker(project: Project, candidates: List<CodeContext>, defaultIndex: Int, origin: ContextOrigin,
    data: ContextData, owner: Component?, parent: Disposable, run: (ContextAction, ContextRequest) -> Unit): ContextPicker =
    ContextPicker(project, candidates, defaultIndex, origin, data, owner, parent, run)

/** The picker's [component] and the parts tests and the popup reach into. */
internal class ContextPicker(
    private val project: Project,
    private val candidates: List<CodeContext>,
    defaultIndex: Int,
    private val origin: ContextOrigin,
    private val data: ContextData,
    private val owner: Component?,
    parent: Disposable,
    private val run: (ContextAction, ContextRequest) -> Unit,
) {
    private val scopeModel = DefaultListModel<CodeContext>().apply { candidates.forEach(::addElement) }
    private val actionModel = DefaultListModel<PickerRow>()

    val scopes: JBList<CodeContext> = JBList(scopeModel).apply {
        name = "insight-context-scopes"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        focusTraversalKeysEnabled = false
        cellRenderer = object : ColoredListCellRenderer<CodeContext>() {
            override fun customizeCellRenderer(list: JList<out CodeContext>, value: CodeContext, index: Int, selected: Boolean,
                focused: Boolean) {
                append(value.label)
                val detail = scopeDetail(value, { InsightBundle.message("context.picker.detail.lines", it) },
                    { InsightBundle.message("context.picker.detail.files", it) })
                if (detail.isNotEmpty()) append("  $detail", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    val actions: JBList<PickerRow> = JBList(actionModel).apply {
        name = "insight-context-actions"
        focusTraversalKeysEnabled = false
        // Group headers and the empty note are labels; only actions can be selected.
        selectionModel = object : DefaultListSelectionModel() {
            init { selectionMode = ListSelectionModel.SINGLE_SELECTION }
            override fun setSelectionInterval(index0: Int, index1: Int) {
                if (index1 in 0 until actionModel.size() && actionModel.getElementAt(index1) is PickerRow.Item) super.setSelectionInterval(index0, index1)
            }
        }
        cellRenderer = object : ColoredListCellRenderer<PickerRow>() {
            override fun customizeCellRenderer(list: JList<out PickerRow>, value: PickerRow, index: Int, selected: Boolean,
                focused: Boolean) {
                when (value) {
                    is PickerRow.Header -> {
                        append(value.group, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_SMALLER, UIUtil.getContextHelpForeground()))
                        ipad = JBUI.insets(if (index == 0) 0 else 4, 4, 0, 0)
                    }
                    is PickerRow.Item -> {
                        icon = value.action.icon
                        append(value.action.title)
                        ipad = JBUI.insetsLeft(12)
                    }
                    PickerRow.Empty -> {
                        append(InsightBundle.message("context.picker.no.actions"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        ipad = JBUI.insetsLeft(4)
                    }
                }
            }
        }
    }

    private var previewLine = 1
    private val document = EditorFactory.getInstance().createDocument("")
    val preview: EditorEx = (EditorFactory.getInstance().createViewer(document, project) as EditorEx).apply {
        component.name = "insight-context-preview"
        settings.apply {
            isLineNumbersShown = false
            isFoldingOutlineShown = false
            isAutoCodeFoldingEnabled = false
            isLineMarkerAreaShown = false
            isIndentGuidesShown = false
            isRightMarginShown = false
            isCaretRowShown = false
            isUseSoftWraps = false
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isAdditionalPageAtBottom = false
        }
        setBorder(null)
        contentComponent.isFocusable = false
        gutter.setLineNumberConverter(object : LineNumberConverter {
            override fun convert(editor: Editor, lineNumber: Int) = previewLine + lineNumber - 1
            override fun getMaxLineNumber(editor: Editor) = previewLine + editor.document.lineCount - 1
        })
    }

    val component: JPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
        name = "insight-context-picker"
        border = JBUI.Borders.empty(6)
        val scopePane = JBScrollPane(scopes).apply {
            preferredSize = Dimension(JBUI.scale(230), JBUI.scale(210))
            border = BorderFactory.createLineBorder(JBColor.border())
        }
        val previewPane = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(210))
            border = BorderFactory.createLineBorder(JBColor.border())
            add(preview.component, BorderLayout.CENTER)
        }
        val top = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(scopePane, BorderLayout.WEST)
            add(previewPane, BorderLayout.CENTER)
        }
        val actionPane = JBScrollPane(actions).apply {
            preferredSize = Dimension(JBUI.scale(716), JBUI.scale(120))
            border = BorderFactory.createLineBorder(JBColor.border())
        }
        add(top, BorderLayout.CENTER)
        add(actionPane, BorderLayout.SOUTH)
        toolTipText = InsightBundle.message("context.picker.hint")
    }

    val selectedContext: CodeContext? get() = scopes.selectedValue
    val selectedAction: ContextAction? get() = (actions.selectedValue as? PickerRow.Item)?.action

    /** The actions listed for the selected scope, in menu order. */
    val listedActions: List<ContextAction> get() = (0 until actionModel.size()).mapNotNull { (actionModel.getElementAt(it) as? PickerRow.Item)?.action }

    init {
        Disposer.register(parent) { if (!preview.isDisposed) EditorFactory.getInstance().releaseEditor(preview) }
        scopes.addListSelectionListener { if (!it.valueIsAdjusting) showSelectedScope() }
        installKeys()
        actions.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val index = actions.locationToIndex(e.point)
                if (index < 0 || actions.getCellBounds(index, index)?.contains(e.point) != true) return
                if (actionModel.getElementAt(index) !is PickerRow.Item) return
                actions.selectedIndex = index
                runSelected()
            }
        })
        if (candidates.isNotEmpty()) scopes.selectedIndex = defaultIndex.coerceIn(0, candidates.lastIndex)
        else showSelectedScope()
    }

    /** Selects the scope at [index] and lists its actions. */
    fun selectScope(index: Int) {
        scopes.selectedIndex = index
    }

    /** Performs the selected action for the selected scope through the popup's [run]; false when there is none. */
    fun runSelected(): Boolean {
        val action = selectedAction ?: return false
        val context = selectedContext ?: return false
        run(action, requestFor(context))
        return true
    }

    fun focusActions() {
        if (selectedAction == null) selectFirstAction()
        actions.requestFocusInWindow()
    }

    fun focusScopes() {
        scopes.requestFocusInWindow()
    }

    private fun requestFor(context: CodeContext) = ContextRequest(project, context, candidates, origin, data, owner)

    private fun showSelectedScope() {
        val context = selectedContext
        showPreview(context)
        val previous = selectedAction?.id
        actions.clearSelection()
        actionModel.clear()
        val available = if (context == null) emptyList() else ContextActionRegistry.getInstance().actions(requestFor(context))
        actionModel.addAll(actionRows(arrangeActions(available)))
        val again = previous?.let { id -> (0 until actionModel.size()).firstOrNull { (actionModel.getElementAt(it) as? PickerRow.Item)?.action?.id == id } }
        if (again != null) actions.selectedIndex = again else selectFirstAction()
    }

    private fun selectFirstAction() {
        val first = (0 until actionModel.size()).firstOrNull { actionModel.getElementAt(it) is PickerRow.Item } ?: return
        actions.selectedIndex = first
        actions.ensureIndexIsVisible(0)
    }

    private fun showPreview(context: CodeContext?) {
        previewLine = context?.let(::previewFirstLine) ?: 1
        preview.settings.isLineNumbersShown = context != null && previewFirstLine(context) != null
        WriteAction.run<RuntimeException> { document.setText(context?.text.orEmpty()) }
        val type = FileTypeManager.getInstance().getFileTypeByFileName(context?.let(::previewFileName) ?: "context.txt")
        preview.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, type)
        preview.caretModel.moveToOffset(0)
        preview.scrollingModel.scrollTo(preview.offsetToLogicalPosition(0), ScrollType.RELATIVE)
        preview.component.revalidate()
        preview.component.repaint()
    }

    private fun moveAction(delta: Int) {
        var index = actions.selectedIndex
        do index += delta while (index in 0 until actionModel.size() && actionModel.getElementAt(index) !is PickerRow.Item)
        if (index !in 0 until actionModel.size()) return
        actions.selectedIndex = index
        // The header above the first action scrolls into view with it.
        actions.ensureIndexIsVisible(if ((0 until index).none { actionModel.getElementAt(it) is PickerRow.Item }) 0 else index)
    }

    private fun installKeys() {
        scopes.bindKey("TAB") { focusActions() }
        scopes.bindKey("RIGHT") { focusActions() }
        scopes.bindKey("ENTER") { runSelected() }
        actions.bindKey("LEFT") { focusScopes() }
        actions.bindKey("shift TAB") { focusScopes() }
        actions.bindKey("TAB") { focusScopes() }
        actions.bindKey("ENTER") { runSelected() }
        actions.bindKey("UP") { moveAction(-1) }
        actions.bindKey("DOWN") { moveAction(1) }
    }
}

private fun JComponent.bindKey(stroke: String, action: () -> Unit) {
    val key = KeyStroke.getKeyStroke(stroke) ?: error("Unknown key stroke $stroke")
    val name = "insight-context-key-$stroke"
    getInputMap(JComponent.WHEN_FOCUSED).put(key, name)
    actionMap.put(name, object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent?) = action()
    })
}
