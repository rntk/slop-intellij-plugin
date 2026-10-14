package dev.insight.callgraph

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.insight.ui.heading
import dev.insight.entrypoints.EntryPointService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

class CallGraphFile(val root: SmartPsiElementPointer<PsiElement>, name: String) : LightVirtualFile("Call Graph: $name")

fun openCallGraph(project: Project, function: PsiElement, name: String) {
    val manager = FileEditorManager.getInstance(project)
    val pointers = SmartPointerManager.getInstance(project)
    val root = pointers.createSmartPsiElementPointer(function)
    val file = manager.openFiles.filterIsInstance<CallGraphFile>().firstOrNull { pointers.pointToTheSameElement(it.root, root) }
        ?: CallGraphFile(root, name)
    manager.openFile(file, true)
}

/** Shows the call graph of the function at the caret, the function referenced at the caret, or the selected declaration. */
class ShowCallGraphAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val function = target(e) ?: return
        openCallGraph(project, function, CallGraphAnalyzer.describe(project, function, 0, 0).name)
    }

    private fun target(e: AnActionEvent): PsiElement? {
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let(CallGraphAnalyzer::findFunction)?.let { return it }
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = e.getData(CommonDataKeys.CARET)?.offset ?: return null
        return CallGraphAnalyzer.findFunction(file.findElementAt(offset))
    }
}

class CallGraphEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is CallGraphFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = CallGraphEditor(project, file as CallGraphFile)
    override fun getEditorTypeId() = "insight-call-graph"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class CallGraphEditor(project: Project, private val file: CallGraphFile) : UserDataHolderBase(), FileEditor {
    private val panel = CallGraphPanel(project, file.root)
    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.direction
    override fun getName() = "Call Graph"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = panel.dispose()
}

private class CallGraphPanel(private val project: Project, private var root: SmartPsiElementPointer<PsiElement>) :
    JPanel(BorderLayout(0, JBUI.scale(12))) {
    val direction = JComboBox(CallDirection.entries.toTypedArray())
    private val depth = JComboBox(arrayOf(1, 2, 3, 4, 5)).apply { selectedItem = 2 }
    private val libraries = JBCheckBox("Library calls")
    private val refresh = JButton("Refresh")
    private val focus = JButton("Focus selected").apply { isEnabled = false }
    private val exploreFlow = JButton("Explore flow").apply {
        toolTipText = "Explore the selected function, or the root, as compact expandable subflows"
    }
    private val title = heading("Call graph", 22f)
    private val status = JLabel(" ")
    private var result: CallGraphResult? = null
    private val canvas = CallGraphCanvas(onSelect = ::selectionChanged, onOpen = ::navigate)
    private var job: Job? = null
    private val entryPoints = project.service<EntryPointService>()
    private val entryPointJob = project.service<CallGraphService>().scope.launch(Dispatchers.EDT) {
        entryPoints.entryPoints.collect { decorateEntryPoints() }
    }

    init {
        border = JBUI.Borders.empty(20)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(title, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
                add(direction); add(JLabel("Depth")); add(depth); add(libraries); add(refresh); add(focus); add(exploreFlow)
            }, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.customLine(JBColor.border()) }, BorderLayout.CENTER)
        add(JLabel("Callers are on the left, callees on the right. ▶ Start marks an entry point. Double-click a node to open its source; " +
            "dashed nodes are library code. Drag or use arrows to pan; scroll to zoom."), BorderLayout.SOUTH)
        listOf(direction, depth).forEach { it.addActionListener { rebuild() } }
        libraries.addActionListener { rebuild() }
        refresh.addActionListener { rebuild() }
        focus.addActionListener {
            canvas.selected?.let { result?.pointers?.getOrNull(it) }?.let { root = it; rebuild() }
        }
        exploreFlow.addActionListener {
            val pointer = canvas.selected?.let { result?.pointers?.getOrNull(it) } ?: root
            openFlowChart(project, pointer)
        }
        rebuild()
    }

    private fun rebuild() {
        job?.cancel()
        val request = CallGraphRequest(direction.selectedItem as CallDirection, depth.selectedItem as Int, libraries.isSelected)
        val service = project.service<CallGraphService>()
        status.text = "Searching…"
        job = service.scope.launch(Dispatchers.EDT) {
            try {
                val built = service.build(root, request)
                result = built
                canvas.show(built?.graph)
                decorateEntryPoints()
                status.text = built?.let(::summary) ?: "The function no longer exists."
                built?.graph?.root?.let { title.text = "${it.name}  ${it.container}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                thisLogger().warn("Call graph failed", e)
                status.text = "The call graph could not be built: ${e.message}"
            }
        }
    }

    private fun summary(result: CallGraphResult): String {
        val graph = result.graph
        val limit = if (graph.truncated) " · limited to ${graph.nodes.size} functions" else ""
        return "${graph.nodes.size} functions · ${graph.edges.size} calls$limit"
    }

    private fun selectionChanged(id: Int?) {
        focus.isEnabled = id != null && id != 0 && result?.graph?.nodes?.getOrNull(id)?.inProject == true
        exploreFlow.isEnabled = id == null || result?.graph?.nodes?.getOrNull(id)?.inProject == true
    }

    private fun navigate(id: Int) {
        (result?.pointers?.getOrNull(id)?.element?.navigationElement as? Navigatable)?.takeIf { it.canNavigate() }?.navigate(true)
    }

    private fun decorateEntryPoints() {
        canvas.decorate(result?.graph?.let {
            EntryPointLens.decorate(GraphDecorations.NONE, it, entryPoints.entryPoints.value, project.basePath)
        } ?: GraphDecorations.NONE)
    }

    fun dispose() { job?.cancel(); entryPointJob.cancel() }
}
