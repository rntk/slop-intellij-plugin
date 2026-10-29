package dev.insight.diff

import com.intellij.diff.DiffManager
import dev.insight.core.describeFailure
import dev.insight.core.runCatchingReported
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsMappingListener
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlinx.coroutines.*

private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance("dev.insight.diff.DiffReviewPanel")

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
        e.project?.let { project ->
            ToolWindowManager.getInstance(project).getToolWindow("Diff Review")?.let { window ->
                window.activate {
                    (window.contentManager.selectedContent?.component as? DiffReviewPanel)?.openCanvas(true)
                }
            }
        }
    }
}

/** Independent from graph analysis: the review is available even before a project graph is built. */
internal class DiffReviewPanel(
    private val project: Project,
    startupReady: Boolean = ApplicationManager.getApplication().isUnitTestMode ||
        StartupManager.getInstance(project).postStartupActivityPassed(),
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private val state = DiffReviewState()
    private val included = linkedSetOf<String>()
    private var disposed = false
    private var refreshJob: Job? = null
    private var repositoriesJob: Job? = null
    private var request = ReviewDiffRequest()
    private val tree = Tree(DefaultMutableTreeNode("Changes"))
    private val untracked = UntrackedFilesTree { file, selected ->
        if (selected) included += file.absolutePath else included -= file.absolutePath
        invalidateDiff(setOf(file.path, file.absolutePath)); refresh()
    }
    private val untrackedBlock = JPanel(BorderLayout())
    private val status = JLabel("Loading diff…")
    private val sourceControls = DiffReviewSourceControls(onRefresh = { refreshDiff() }) { draft ->
        try {
            request = draft.validated()
            invalidateDiff()
            refresh()
        } catch (e: IllegalArgumentException) {
            LOG.debug("Invalid diff review source", e)
            status.text = e.message
        }
    }
    private val newSelectedTab = JButton("New tab from selection").apply {
        toolTipText = "Start a fresh chat and canvas scoped to the selected files or folders"
        addActionListener { selectedPaths().toSet().takeIf { it.isNotEmpty() }?.let { tabs.add(it) } }
    }
    private val refreshTimer = Timer(700) { refresh() }.apply { isRepeats = false }
    /** Source controls, files and review tab controls; the selected review tab's chat has its own page. */
    private val main = JPanel(BorderLayout(0, JBUI.scale(6)))
    private val pages = ReviewSidePages(main)
    private val tabs = ReviewTabsController(project, this, "Diff Review", "All changes", "Insight.DiffReviewTabs", pages,
        startupReady, ::selectedPaths, create = { paths, focus, toChat ->
            DiffReviewTab(project, paths, ::selectedPaths, ::showDiff, focus = focus, toChat = toChat)
        }) { tab ->
        tab.update(state.snapshot)
        if (state.refreshing) tab.invalidate(stop = false)
    }

    init {
        border = JBUI.Borders.empty(6)
        val files = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                add(JLabel("Changed files · double-click to view diff"), BorderLayout.NORTH)
                add(newSelectedTab, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }
        untrackedBlock.apply {
            add(JLabel("Untracked · include in review").apply {
                toolTipText = "Selection only includes a file in this review; it does not stage it in version control."
            }, BorderLayout.NORTH)
            add(JBScrollPane(untracked), BorderLayout.CENTER)
        }
        val fileLists = JSplitPane(JSplitPane.VERTICAL_SPLIT, files, untrackedBlock).apply {
            resizeWeight = 0.7; setDividerLocation(0.7)
        }
        val filesPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            minimumSize = JBUI.size(280, 120)
            add(sourceControls, BorderLayout.NORTH)
            add(fileLists, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val reviewPanel = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(6)
                add(JButton("New tab").apply {
                    toolTipText = "Start a fresh chat and canvas for all files in the selected diff"
                    addActionListener { tabs.add() }
                }, BorderLayout.WEST)
            }, BorderLayout.NORTH)
            add(tabs.component, BorderLayout.CENTER)
        }
        main.add(filesPanel, BorderLayout.CENTER)
        main.add(reviewPanel, BorderLayout.SOUTH)
        add(pages, BorderLayout.CENTER)
        tabs.add()
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
            tabs.selected?.highlight(selectedPaths().toSet())
            updateControls()
        }
        tree.openFilesOnActivate("showDiff") { selectedFile()?.let { showDiff(it.path); true } ?: false }
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(
            tabs.toChatAction("Chat about the selection in a new tab, with its summaries and diff in context",
                enabled = { !state.refreshing }, entries = ::selectedEntries) {
                status.text = "The selection is not in this tab's changes. Choose the All changes tab or a tab that includes it."
            },
        ), "Insight.DiffReviewFiles")
        project.messageBus.connect(this).apply {
            subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, object : VcsMappingListener {
                override fun directoryMappingChanged() {
                    scope.launch { if (!disposed) loadRepositories() }
                }
            })
            subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
                override fun changeListUpdateDone() { if (request.mode.usesWorkingTree) scheduleRefresh() }
            })
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val paths = state.snapshot.files.mapTo(hashSetOf()) { it.absolutePath } + included
                    val affected = events.mapTo(hashSetOf()) { it.path }.intersect(paths)
                    if (request.mode.usesWorkingTree && affected.isNotEmpty()) scheduleRefresh(affected)
                }
            })
        }
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val path = FileDocumentManager.getInstance().getFile(event.document)?.path ?: return
                if (request.mode.usesWorkingTree && (path in included || state.snapshot.files.any { it.absolutePath == path }))
                    scheduleRefresh(setOf(path))
            }
        }, this)
        loadRepositories()
        refresh()
    }

    private fun refreshDiff() {
        loadRepositories()
        invalidateDiff()
        if (request.mode.usesWorkingTree)
            ChangeListManager.getInstance(project).invokeAfterUpdate(true) { if (!disposed) refresh() }
        else refresh()
    }

    private fun loadRepositories() {
        repositoriesJob?.cancel()
        repositoriesJob = scope.launch {
            try {
                runCatchingReported(LOG, "Repository discovery failed") {
                    val repositories = withBackgroundProgress(project, "Finding review repositories") {
                        withContext(Dispatchers.IO) {
                            ReviewDiffProvider.EP_NAME.extensionList.flatMap { it.repositories(project) }.distinctBy { it.path }
                        }
                    }
                    sourceControls.setRepositories(repositories, preferredReviewRepository(repositories,
                        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path, project.basePath))
                }.onFailure {
                    sourceControls.setRepositoryError(describeFailure(it))
                }
            } catch (e: CancellationException) {
                ensureActive()
                sourceControls.setRepositoryError("Repository discovery cancelled. Refresh to retry.")
                throw e
            }
        }
    }

    private fun scheduleRefresh(affectedPaths: Set<String>? = null) {
        if (SwingUtilities.isEventDispatchThread()) {
            if (!disposed) { affectedPaths?.let(::invalidateDiff); refreshTimer.restart() }
        } else SwingUtilities.invokeLater { if (!disposed) { affectedPaths?.let(::invalidateDiff); refreshTimer.restart() } }
    }

    private fun invalidateDiff(affectedPaths: Set<String>? = null) {
        state.refreshing = true
        tabs.all.forEach { it.invalidate(stop = true, affectedPaths) }
        refreshJob?.cancel()
        status.text = "Loading ${request.mode.title.lowercase()}…"
        updateControls()
    }

    private fun refresh() {
        if (disposed) return
        refreshTimer.stop()
        refreshJob?.cancel()
        state.refreshing = true
        // Known edits already blocked affected tabs. Other refreshes compare scoped revisions on update.
        status.text = "Loading ${request.mode.title.lowercase()}…"
        updateControls()
        val selected = included.toSet()
        val selectedRequest = request
        refreshJob = scope.launch {
            try {
                runCatchingReported(LOG, "Could not load diff") {
                    val snapshot = withBackgroundProgress(project, "Loading diff review: ${selectedRequest.mode.title}") {
                        withContext(Dispatchers.IO) { DiffReviewLoader(project).load(selected, selectedRequest) }
                    }
                    state.update(snapshot)
                    val removed = tabs.all.sumOf { it.update(snapshot) }
                    if (selectedRequest.mode.usesWorkingTree)
                        included.retainAll(snapshot.untracked.mapTo(hashSetOf()) { it.absolutePath })
                    renderFiles()
                    status.text = "${snapshot.files.size} files" + if (selectedRequest.mode.usesWorkingTree)
                        " · ${snapshot.untracked.count { it.absolutePath !in included }} untracked outside review" else " · ${snapshot.comparison.summary}"
                    if (removed > 0) status.text += " · $removed outdated notes removed"
                }.onFailure {
                    status.text = "Could not load diff: ${describeFailure(it)}"
                    // Affected tabs stay blocked until a successful refresh.
                }
            } catch (e: CancellationException) {
                ensureActive()
                status.text = "Diff loading cancelled. Refresh to retry."
                throw e
            } finally { status.toolTipText = status.text; updateControls() }
        }
    }

    private fun updateControls() {
        newSelectedTab.isEnabled = !state.refreshing && selectedPaths().isNotEmpty()
    }

    fun openCanvas(focus: Boolean = false) { tabs.openCanvas(focus) }

    private fun selectedFile(): ReviewFile? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ReviewFile

    /** The selected files and folders by path; folder nodes hold only their name, so the path is read from the tree. */
    private fun selectedEntries(): List<String> = tree.selectionPaths.orEmpty().mapNotNull { path ->
        when (val entry = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
            is ReviewFile -> entry.path
            is String -> path.path.drop(1).joinToString("/") { (it as DefaultMutableTreeNode).userObject.toString() }
            else -> null
        }
    }.distinct()

    private fun selectedPaths(): List<String> = tree.selectedFilePaths<ReviewFile>()

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
        untrackedBlock.isVisible = request.mode.usesWorkingTree
        untracked.showFiles(state.snapshot.untracked, included)
    }

    private fun showDiff(path: String) {
        val file = state.snapshot.files.firstOrNull { it.path == path } ?: return
        DiffManager.getInstance().showDiff(project, createDiffRequest(project, file))
    }

    override fun dispose() {
        disposed = true
        refreshTimer.stop()
        scope.cancel()
    }
}
