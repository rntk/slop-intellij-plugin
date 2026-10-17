package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class DiffReviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DiffReviewPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false).apply {
            setDisposer(panel)
        })
    }
}

class OpenDiffReviewAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { ToolWindowManager.getInstance(it).getToolWindow("Diff Review")?.activate(null) }
    }
}

/** Independent from graph analysis: the review is available even before a project graph is built. */
internal class DiffReviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val state = DiffReviewState()
    private val included = linkedSetOf<String>()
    private var disposed = false
    private var refreshJob: Job? = null
    private val tree = Tree(DefaultMutableTreeNode("Changes"))
    private val untracked = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val status = JLabel("Loading local changes…")
    private val tabs = JBTabsFactory.createTabs(project, this).apply {
        presentation.isHideTabs = false
        presentation.setSingleRow(true).setSupportsCompression(false).setTabLabelActionsAutoHide(false)
    }
    private val reviewTabs = mutableListOf<DiffReviewTab>()
    private var nextTab = 1
    private val newSelectedTab = JButton("New tab from selection").apply {
        toolTipText = "Start a fresh chat and canvas scoped to the selected files or folders"
        addActionListener { selectedPaths().toSet().takeIf { it.isNotEmpty() }?.let { addTab(it) } }
    }
    private val refreshTimer = Timer(700) { refresh() }.apply { isRepeats = false }

    init {
        border = JBUI.Borders.empty(6)
        val files = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JLabel("Current changes · double-click to view diff"), BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        val untrackedBlock = JPanel(BorderLayout()).apply {
            add(JLabel("Untracked · include in review").apply {
                toolTipText = "Selection only includes a file in this review; it does not stage it in version control."
            }, BorderLayout.NORTH)
            add(JBScrollPane(untracked), BorderLayout.CENTER)
        }
        val fileLists = JSplitPane(JSplitPane.VERTICAL_SPLIT, files, untrackedBlock).apply {
            resizeWeight = 0.7; setDividerLocation(0.7)
        }
        val left = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            minimumSize = Dimension(180, 120)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JButton("Refresh").apply { addActionListener {
                    invalidateDiff()
                    ChangeListManager.getInstance(project).invokeAfterUpdate(true) { if (!disposed) refresh() }
                } })
                add(newSelectedTab)
            }, BorderLayout.NORTH)
            add(fileLists, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val right = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("New tab").apply {
                    toolTipText = "Start a fresh chat and canvas for all current changes"
                    addActionListener { addTab() }
                })
            }, BorderLayout.NORTH)
            add(tabs.component, BorderLayout.CENTER)
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
            resizeWeight = 0.25; setDividerLocation(300)
        }, BorderLayout.CENTER)
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                (newSelection?.component as? DiffReviewTab)?.highlight(selectedPaths().toSet())
            }
        }, this)
        tabs.addTabMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.clickCount != 2) return
                val info = tabs.findInfo(e) ?: return
                val renamed = Messages.showInputDialog(project, "Tab name:", "Rename Review Tab",
                    null, info.text, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
                info.setText(renamed)
                info.setTooltipText("$renamed · Double-click to rename")
            }
        })
        addTab()
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): java.awt.Component {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus)
                val file = (value as? DefaultMutableTreeNode)?.userObject as? ReviewFile
                if (file != null) {
                    text = "${file.path.substringAfterLast('/')}  [${file.status}]"
                    toolTipText = file.previousPath?.let { "$it → ${file.path}" } ?: file.path
                }
                return this
            }
        }
        tree.addTreeSelectionListener {
            (tabs.selectedInfo?.component as? DiffReviewTab)?.highlight(selectedPaths().toSet())
            updateControls()
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) selectedFile()?.let { showDiff(it.path) } }
        })
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "showDiff")
        tree.actionMap.put("showDiff", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                selectedFile()?.let { showDiff(it.path) } ?: tree.selectionPath?.let {
                    if (tree.isExpanded(it)) tree.collapsePath(it) else tree.expandPath(it)
                }
            }
        })
        project.messageBus.connect(this).apply {
            subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
                override fun changeListUpdateDone() { scheduleRefresh() }
            })
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val paths = state.snapshot.files.mapTo(hashSetOf()) { it.absolutePath } + included
                    val affected = events.mapTo(hashSetOf()) { it.path }.intersect(paths)
                    if (affected.isNotEmpty()) scheduleRefresh(affected)
                }
            })
        }
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val path = FileDocumentManager.getInstance().getFile(event.document)?.path ?: return
                if (path in included || state.snapshot.files.any { it.absolutePath == path }) scheduleRefresh(setOf(path))
            }
        }, this)
        refresh()
    }

    private fun scheduleRefresh(affectedPaths: Set<String>? = null) {
        if (SwingUtilities.isEventDispatchThread()) {
            if (!disposed) { affectedPaths?.let(::invalidateDiff); refreshTimer.restart() }
        } else SwingUtilities.invokeLater { if (!disposed) { affectedPaths?.let(::invalidateDiff); refreshTimer.restart() } }
    }

    private fun invalidateDiff(affectedPaths: Set<String>? = null) {
        state.refreshing = true
        reviewTabs.forEach { it.invalidate(stop = true, affectedPaths) }
        refreshJob?.cancel()
        status.text = "Refreshing local changes…"
        updateControls()
    }

    private fun refresh() {
        if (disposed) return
        refreshTimer.stop()
        refreshJob?.cancel()
        state.refreshing = true
        // Known edits already blocked affected tabs. Other refreshes compare scoped revisions on update.
        status.text = "Refreshing local changes…"
        updateControls()
        val selected = included.toSet()
        refreshJob = scope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { DiffReviewLoader(project).load(selected) }
                state.update(snapshot)
                val removed = reviewTabs.sumOf { it.update(snapshot) }
                included.retainAll(snapshot.untracked.mapTo(hashSetOf()) { it.absolutePath })
                renderFiles()
                status.text = "${snapshot.files.size} files · ${snapshot.untracked.count { it.absolutePath !in included }} untracked outside review"
                if (removed > 0) status.text += " · $removed outdated notes removed"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Could not load changes: ${e.message ?: e.javaClass.simpleName}"
                // Affected tabs stay blocked until a successful refresh.
            } finally { updateControls() }
        }
    }

    private fun updateControls() {
        newSelectedTab.isEnabled = !state.refreshing && selectedPaths().isNotEmpty()
    }

    private fun addTab(paths: Set<String>? = null) {
        val number = nextTab++
        val name = if (number == 1) "All changes" else if (paths == null) "Review $number" else "Selection $number"
        val tab = DiffReviewTab(project, paths, ::selectedPaths, ::showDiff)
        Disposer.register(this, tab)
        reviewTabs += tab
        tab.update(state.snapshot)
        if (state.refreshing) tab.invalidate(stop = false)
        val info = TabInfo(tab).setText(name).setTooltipText("$name · Double-click to rename")
        info.setTabLabelActions(DefaultActionGroup(object : DumbAwareAction(
            "Close tab", "Discard this tab's chat history, draft and canvas", AllIcons.Actions.Close,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) { closeTab(info) }
        }), "Insight.DiffReviewTabs")
        tabs.addTab(info)
        tabs.select(info, false)
    }

    private fun closeTab(info: TabInfo) {
        val tab = info.component as DiffReviewTab
        if (!reviewTabs.remove(tab)) return
        Disposer.dispose(tab)
        tabs.removeTab(info)
        if (reviewTabs.isEmpty()) addTab()
    }

    private fun selectedFile(): ReviewFile? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ReviewFile

    private fun selectedPaths(): List<String> = tree.selectionPaths.orEmpty().flatMap { path ->
        val node = path.lastPathComponent as DefaultMutableTreeNode
        node.depthFirstEnumeration().toList().mapNotNull { (it as DefaultMutableTreeNode).userObject as? ReviewFile }.map { it.path }
    }.distinct()

    private fun renderFiles() {
        val selection = selectedPaths().toSet()
        val root = DefaultMutableTreeNode("Changes")
        val folders = mutableMapOf("" to root)
        val selectedNodes = mutableListOf<TreePath>()
        for (file in state.snapshot.files) {
            var parent = root
            var prefix = ""
            for (folder in file.path.split('/').dropLast(1)) {
                prefix += "$folder/"
                val owner = parent
                parent = folders.getOrPut(prefix) { DefaultMutableTreeNode(folder).also(owner::add) }
            }
            val node = DefaultMutableTreeNode(file)
            parent.add(node)
            if (file.path in selection) selectedNodes += TreePath(node.path)
        }
        tree.model = DefaultTreeModel(root)
        var row = 0
        while (row < tree.rowCount) tree.expandRow(row++)
        tree.selectionPaths = selectedNodes.toTypedArray()
        untracked.removeAll()
        if (state.snapshot.untracked.isEmpty()) untracked.add(JLabel("No untracked files"))
        for (file in state.snapshot.untracked) untracked.add(JCheckBox(file.path, file.absolutePath in included).apply {
            toolTipText = file.path
            addActionListener {
                if (isSelected) included += file.absolutePath else included -= file.absolutePath
                invalidateDiff(setOf(file.path, file.absolutePath)); refresh()
            }
        })
        untracked.revalidate(); untracked.repaint()
    }

    private fun showDiff(path: String) {
        val file = state.snapshot.files.firstOrNull { it.path == path } ?: return
        val factory = DiffContentFactory.getInstance()
        val request = if (file.before != null && file.after != null) SimpleDiffRequest(path,
            factory.create(project, file.before), factory.create(project, file.after),
            file.previousPath ?: "$path · before", "$path · working tree")
        else SimpleDiffRequest(path, factory.create(project, ""), factory.create(project, file.patch), "Before", "Content unavailable")
        request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        DiffManager.getInstance().showDiff(project, request)
    }

    override fun dispose() {
        disposed = true
        refreshTimer.stop()
        scope.cancel()
    }
}
