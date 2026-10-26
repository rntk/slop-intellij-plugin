package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.diff.DiffManager
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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsMappingListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.PopupHandler
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
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
    private var readyToOpenCanvas = startupReady
    private var selectingFromCanvas = false
    private var chatActivations = 0
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
            status.text = e.message
        }
    }
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
    /** Source controls, files and review tab controls; the selected review tab's chat has its own page. */
    private val main = JPanel(BorderLayout(0, JBUI.scale(6)))
    private val pages = ReviewSidePages(main)

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
                    addActionListener { addTab() }
                }, BorderLayout.WEST)
            }, BorderLayout.NORTH)
            add(tabs.component, BorderLayout.CENTER)
        }
        main.add(filesPanel, BorderLayout.CENTER)
        main.add(reviewPanel, BorderLayout.SOUTH)
        add(pages, BorderLayout.CENTER)
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                showSelectedChat()
                (newSelection?.component as? DiffReviewTab)?.let {
                    it.highlight(selectedPaths().toSet())
                    if (!selectingFromCanvas && readyToOpenCanvas) it.openCanvas()
                }
            }
        }, this)
        tabs.addTabMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val info = (e.component as? TabLabel
                    ?: SwingUtilities.getAncestorOfClass(TabLabel::class.java, e.component) as? TabLabel)?.info
                    ?: tabs.findInfo(e) ?: return
                if (e.clickCount == 1) {
                    if (e.component !is InplaceButton && SwingUtilities.getAncestorOfClass(InplaceButton::class.java, e.component) == null) {
                        (info.component as? DiffReviewTab)?.openCanvas(true)
                    }
                    return
                }
                if (e.clickCount != 2) return
                val renamed = Messages.showInputDialog(project, "Tab name:", "Rename Review Tab",
                    null, info.text, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
                info.setText(renamed)
                info.setTooltipText("$renamed · Double-click to rename")
                showSelectedChat()
                (info.component as DiffReviewTab).canvasFile.let { file ->
                    file.renameReview(renamed)
                    FileEditorManager.getInstance(project).updateFilePresentation(file)
                }
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
            selectedTab()?.highlight(selectedPaths().toSet())
            updateControls()
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) selectedFile()?.let { showDiff(it.path) } }
        })
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(
            object : DumbAwareAction("To Chat", "Chat about the selection in a new tab, with its summaries and diff in context", AllIcons.Toolwindows.ToolWindowMessages) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = !state.refreshing && selectedEntries().isNotEmpty() }
                override fun actionPerformed(e: AnActionEvent) {
                    val tab = selectedTab() ?: return
                    tab.focusOn(selectedEntries())?.let { openChat(it, tab.state.scopePaths) } ?: run {
                        status.text = "The selection is not in this tab's changes. Choose the All changes tab or a tab that includes it."
                    }
                }
            },
        ), "Insight.DiffReviewFiles")
        tree.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "showDiff")
        tree.actionMap.put("showDiff", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                selectedFile()?.let { showDiff(it.path) } ?: tree.selectionPath?.let {
                    if (tree.isExpanded(it)) tree.collapsePath(it) else tree.expandPath(it)
                }
            }
        })
        project.messageBus.connect(this).apply {
            subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == "Diff Review" && readyToOpenCanvas && !disposed &&
                        !selectingFromCanvas && chatActivations == 0) openCanvas()
                }
            })
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
        if (!readyToOpenCanvas) {
            StartupManager.getInstance(project).runAfterOpened {
                val open = Runnable {
                    if (!disposed) {
                        readyToOpenCanvas = true
                        openCanvas()
                    }
                }
                if (SwingUtilities.isEventDispatchThread()) open.run() else SwingUtilities.invokeLater(open)
            }
        }
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
                val repositories = withBackgroundProgress(project, "Finding review repositories") {
                    withContext(Dispatchers.IO) {
                        ReviewDiffProvider.EP_NAME.extensionList.flatMap { it.repositories(project) }.distinctBy { it.path }
                    }
                }
                sourceControls.setRepositories(repositories, preferredReviewRepository(repositories,
                    FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path, project.basePath))
            } catch (e: CancellationException) {
                ensureActive()
                sourceControls.setRepositoryError("Repository discovery cancelled. Refresh to retry.")
                throw e
            } catch (e: Exception) {
                sourceControls.setRepositoryError(e.message ?: e.javaClass.simpleName)
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
        reviewTabs.forEach { it.invalidate(stop = true, affectedPaths) }
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
                val snapshot = withBackgroundProgress(project, "Loading diff review: ${selectedRequest.mode.title}") {
                    withContext(Dispatchers.IO) { DiffReviewLoader(project).load(selected, selectedRequest) }
                }
                state.update(snapshot)
                val removed = reviewTabs.sumOf { it.update(snapshot) }
                if (selectedRequest.mode.usesWorkingTree)
                    included.retainAll(snapshot.untracked.mapTo(hashSetOf()) { it.absolutePath })
                renderFiles()
                status.text = "${snapshot.files.size} files" + if (selectedRequest.mode.usesWorkingTree)
                    " · ${snapshot.untracked.count { it.absolutePath !in included }} untracked outside review" else " · ${snapshot.comparison.summary}"
                if (removed > 0) status.text += " · $removed outdated notes removed"
            } catch (e: CancellationException) {
                ensureActive()
                status.text = "Diff loading cancelled. Refresh to retry."
                throw e
            } catch (e: Exception) {
                status.text = "Could not load diff: ${e.message ?: e.javaClass.simpleName}"
                status.toolTipText = status.text
                // Affected tabs stay blocked until a successful refresh.
            } finally { status.toolTipText = status.text; updateControls() }
        }
    }

    private fun updateControls() {
        newSelectedTab.isEnabled = !state.refreshing && selectedPaths().isNotEmpty()
    }

    /**
     * Opens a new tab with the scope [paths] whose chat is focused on [focus], and shows its chat. The canvas stays
     * where it is, so To Chat from a summary does not replace the view it was chosen from.
     */
    private fun openChat(focus: ChatFocus, paths: Set<String>?) {
        if (disposed) return
        selectingFromCanvas = true
        try { addTab(paths, focus) } finally { selectingFromCanvas = false }
        ToolWindowManager.getInstance(project).getToolWindow("Diff Review")?.let { window ->
            // Activation may show the window asynchronously, after the tab-selection guard has been released.
            chatActivations++
            try { window.activate { chatActivations-- } }
            catch (e: Exception) { chatActivations--; throw e }
        }
        pages.selectChat()
    }

    private fun addTab(paths: Set<String>? = null, focus: ChatFocus? = null) {
        val number = nextTab++
        val name = when {
            focus != null -> chatFocusTabName(focus)
            number == 1 -> "All changes"
            paths == null -> "Review $number"
            else -> "Selection $number"
        }
        val tab = DiffReviewTab(project, paths, ::selectedPaths, ::showDiff, focus = focus, toChat = { openChat(it, paths) })
        Disposer.register(this, tab)
        reviewTabs += tab
        tab.update(state.snapshot)
        if (state.refreshing) tab.invalidate(stop = false)
        val info = TabInfo(tab).setText(name).setTooltipText("$name · Double-click to rename")
        tab.canvasFile.renameReview(name)
        tab.onCanvasSelected = {
            if (!disposed && tab in reviewTabs && tabs.selectedInfo !== info) {
                selectingFromCanvas = true
                try { tabs.select(info, false) } finally { selectingFromCanvas = false }
            }
        }
        info.setTabLabelActions(DefaultActionGroup(object : DumbAwareAction(
            "Close Tab", "Discard this tab's chat history, draft and canvas", AllIcons.Actions.Close,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) { closeTab(info) }
        }), "Insight.DiffReviewTabs")
        tabs.addTab(info)
        tabs.select(info, false)
        showSelectedChat()
    }

    private fun closeTab(info: TabInfo) {
        val tab = info.component as DiffReviewTab
        if (!reviewTabs.remove(tab)) return
        Disposer.dispose(tab)
        tabs.removeTab(info)
        if (reviewTabs.isEmpty()) addTab()
        showSelectedChat()
    }

    private fun selectedTab(): DiffReviewTab? = tabs.selectedInfo?.component as? DiffReviewTab

    private fun showSelectedChat() { pages.showChat(selectedTab()?.chat, tabs.selectedInfo?.text.orEmpty()) }

    fun openCanvas(focus: Boolean = false) { selectedTab()?.openCanvas(focus) }

    private fun selectedFile(): ReviewFile? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? ReviewFile

    /** The selected files and folders by path; folder nodes hold only their name, so the path is read from the tree. */
    private fun selectedEntries(): List<String> = tree.selectionPaths.orEmpty().mapNotNull { path ->
        when (val entry = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
            is ReviewFile -> entry.path
            is String -> path.path.drop(1).joinToString("/") { (it as DefaultMutableTreeNode).userObject.toString() }
            else -> null
        }
    }.distinct()

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
