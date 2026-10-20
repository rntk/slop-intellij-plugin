package dev.insight.codereview

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Locale
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

internal const val CODE_REVIEW_TOOL_WINDOW = "Code Review"

class CodeReviewToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CodeReviewPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "", false).apply {
            setDisposer(panel)
        })
    }
}

/** Activates the Code Review tool window, creating its panel, and passes the panel to [action]. */
private fun withCodeReviewPanel(project: Project, action: (CodeReviewPanel) -> Unit) {
    val window = ToolWindowManager.getInstance(project).getToolWindow(CODE_REVIEW_TOOL_WINDOW) ?: return
    window.activate {
        window.contentManager.contents.firstNotNullOfOrNull { it.component as? CodeReviewPanel }?.let(action)
    }
}

class OpenCodeReviewAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { project -> withCodeReviewPanel(project) { it.openCanvas(true) } }
    }
}

/** Project View: review the code in one project folder. */
class ReviewDirectoryAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val directory = directory(e)
        e.presentation.isVisible = directory != null
        e.presentation.isEnabled = directory != null && e.project?.let { project ->
            ReadAction.compute<Boolean, RuntimeException> { runCatching { codeScope(project, directory) }.isSuccess }
        } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = directory(e) ?: return
        withCodeReviewPanel(project) { it.setScope(directory) }
    }

    private fun directory(e: AnActionEvent): VirtualFile? {
        if (e.project == null) return null
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return null
        return files.singleOrNull()?.takeIf { it.isDirectory }
    }
}

/** A folder of the file tree: [path] is project-relative. */
private data class FolderEntry(val name: String, val path: String) {
    override fun toString() = name
}

/**
 * The Code Review control panel: the scope folder, its files and skipped files, and review tabs, each with a chat and a
 * canvas. Files are reloaded 1.5 s after a file under the scope changes on disk (saved edits included); unchanged files are
 * reused. Independent from graph analysis.
 */
internal class CodeReviewPanel(
    private val project: Project,
    startupReady: Boolean = ApplicationManager.getApplication().isUnitTestMode ||
        StartupManager.getInstance(project).postStartupActivityPassed(),
) : JPanel(BorderLayout()), Disposable {
    private val coroutines = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    private var disposed = false
    private var readyToOpenCanvas = startupReady
    private var selectingFromCanvas = false
    private var refreshJob: Job? = null
    /** The applied folder; null until one is valid. */
    internal var scope: CodeScope? = null
        private set
    /** The files of [scope]; null until the first load. */
    internal var snapshot: CodeSnapshot? = null
        private set
    /** The absolute path of the scope folder, read by the file listener on any thread. */
    @Volatile private var scopeRoot: String? = null
    private val tree = Tree(DefaultMutableTreeNode("Files"))
    private val skippedModel = DefaultListModel<String>()
    private val skippedList = JBList(skippedModel).apply { emptyText.text = "No skipped files" }
    private val skippedScroll = JBScrollPane(skippedList).apply {
        preferredSize = JBUI.size(200, 120)
        isVisible = false
    }
    private val skippedToggle = JButton().apply {
        isBorderPainted = false
        isContentAreaFilled = false
        horizontalAlignment = SwingConstants.LEFT
        toolTipText = "Files left out of the review, with the reason"
        addActionListener { skippedScroll.isVisible = !skippedScroll.isVisible; updateSkippedToggle(); revalidate() }
    }
    private val skippedBlock = JPanel(BorderLayout()).apply {
        add(skippedToggle, BorderLayout.NORTH)
        add(skippedScroll, BorderLayout.CENTER)
        isVisible = false
    }
    internal val status = JLabel("Choose a folder to review")
    private val scopeControls = CodeScopeControls(project, onError = { message -> status.text = message; status.toolTipText = message }) { scope ->
        setScope(scope)
    }
    private val tabs = JBTabsFactory.createTabs(project, this).apply {
        presentation.isHideTabs = false
        presentation.setSingleRow(true).setSupportsCompression(false).setTabLabelActionsAutoHide(false)
    }
    private val reviewTabs = mutableListOf<CodeReviewTab>()
    private var nextTab = 1
    private val newSelectedTab = JButton("New tab from selection").apply {
        toolTipText = "Start a fresh chat and canvas scoped to the selected files or folders"
        addActionListener { selectedEntries().toSet().takeIf { it.isNotEmpty() }?.let { addTab(it) } }
    }
    private val refreshTimer = Timer(REFRESH_DELAY_MS) { refresh(stopTabs = false) }.apply { isRepeats = false }

    init {
        border = JBUI.Borders.empty(6)
        val files = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                add(JLabel("Files in scope · double-click to open"), BorderLayout.NORTH)
                add(newSelectedTab, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(skippedBlock, BorderLayout.SOUTH)
        }
        val filesPanel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            minimumSize = JBUI.size(280, 120)
            add(scopeControls, BorderLayout.NORTH)
            add(files, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
        val reviewPanel = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(6)
                add(JButton("New tab").apply {
                    toolTipText = "Start a fresh chat and canvas for the whole scope"
                    addActionListener { addTab() }
                }, BorderLayout.WEST)
            }, BorderLayout.NORTH)
            add(tabs.component, BorderLayout.CENTER)
        }
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, filesPanel, reviewPanel).apply {
            resizeWeight = 0.4; setDividerLocation(JBUI.scale(300))
        }, BorderLayout.CENTER)
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                (newSelection?.component as? CodeReviewTab)?.let {
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
                        (info.component as? CodeReviewTab)?.openCanvas(true)
                    }
                    return
                }
                if (e.clickCount != 2) return
                val renamed = Messages.showInputDialog(project, "Tab name:", "Rename Review Tab",
                    null, info.text, null)?.trim()?.takeIf { it.isNotEmpty() } ?: return
                info.setText(renamed)
                info.setTooltipText("$renamed · Double-click to rename")
                (info.component as CodeReviewTab).canvasFile.let { file ->
                    file.renameReview(renamed)
                    FileEditorManager.getInstance(project).updateFilePresentation(file)
                }
            }
        })
        addTab()
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.emptyText.text = "No files"
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(tree: JTree?, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): java.awt.Component {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus)
                when (val entry = (value as? DefaultMutableTreeNode)?.userObject) {
                    is CodeFile -> {
                        val name = entry.path.substringAfterLast('/')
                        text = "$name  [${entry.facts.joinToString(" · ")}]"
                        icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Any_type
                        toolTipText = entry.path
                    }
                    is FolderEntry -> {
                        icon = AllIcons.Nodes.Folder
                        toolTipText = entry.path
                    }
                }
                return this
            }
        }
        tree.addTreeSelectionListener {
            selectedTab()?.highlight(selectedPaths().toSet())
            updateControls()
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) selectedFile()?.let { openFile(it.path, null) } }
        })
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "openFile")
        tree.actionMap.put("openFile", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                selectedFile()?.let { openFile(it.path, null) } ?: tree.selectionPath?.let {
                    if (tree.isExpanded(it)) tree.collapsePath(it) else tree.expandPath(it)
                }
            }
        })
        project.messageBus.connect(this).apply {
            subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                override fun toolWindowShown(toolWindow: ToolWindow) {
                    if (toolWindow.id == CODE_REVIEW_TOOL_WINDOW && readyToOpenCanvas && !disposed) openCanvas()
                }
            })
            // Edits are picked up when saved: the file changes on disk under the scope.
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val root = scopeRoot ?: return
                    if (events.any { affects(root, it) }) scheduleRefresh()
                }
            })
        }
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
        updateSkipped(null)
        // Loading is bounded by the scope limits and runs in the background; summaries start only on request.
        try {
            setScope(ReadAction.compute<CodeScope, RuntimeException> { codeScope(project, "") })
        } catch (e: IllegalArgumentException) {
            status.text = "Choose a folder to review: ${e.message}"
        }
    }

    /** Reviews [directory]; an invalid folder is reported in the status line. */
    fun setScope(directory: VirtualFile) {
        try {
            setScope(ReadAction.compute<CodeScope, RuntimeException> { codeScope(project, directory) })
        } catch (e: IllegalArgumentException) {
            status.text = e.message ?: "Choose a project folder."
            status.toolTipText = status.text
        }
    }

    /** Applies [scope] and loads it; applying the current scope again reloads it. Running reviews stop. */
    fun setScope(scope: CodeScope) {
        if (disposed) return
        this.scope = scope
        scopeRoot = scope.absolutePath.trimEnd('/')
        scopeControls.show(scope)
        refresh(stopTabs = true)
    }

    private fun affects(root: String, event: VFileEvent): Boolean {
        val path = event.path
        if (path != root && !path.startsWith("$root/")) return false
        if ("/.idea/" in "${path.removePrefix(root)}/") return false
        val file = event.file ?: return true
        if (!file.isValid) return true
        // Excluded folders (build output) are not content; their changes need no reload.
        return ProjectFileIndex.getInstance(project).isInContent(file)
    }

    private fun scheduleRefresh() {
        if (SwingUtilities.isEventDispatchThread()) { if (!disposed) refreshTimer.restart() }
        else SwingUtilities.invokeLater { if (!disposed) refreshTimer.restart() }
    }

    /**
     * Loads the scope in the background, reusing unchanged files of the current snapshot. [stopTabs] stops the tabs' replies
     * and runs (a new scope or an explicit refresh); a reload after edits leaves runs going, and their tabs apply it afterwards.
     */
    private fun refresh(stopTabs: Boolean) {
        if (disposed) return
        refreshTimer.stop()
        val scope = scope ?: return
        refreshJob?.cancel()
        if (stopTabs) reviewTabs.forEach { it.invalidate(stop = true) }
        status.text = "Loading ${scope.displayName}…"
        status.toolTipText = scope.absolutePath
        val previous = snapshot
        refreshJob = coroutines.launch(start = CoroutineStart.LAZY) {
            try {
                val loaded = CodeReviewLoader(project).load(scope, previous)
                snapshot = loaded
                val removed = reviewTabs.sumOf { it.update(loaded) }
                renderFiles(loaded)
                updateSkipped(loaded)
                status.text = codeStatusText(loaded) + if (removed > 0) " · $removed outdated notes removed" else ""
                status.toolTipText = "${loaded.scope.absolutePath}\n${status.text}" +
                    if (loaded.ignoredCount > 0) "\n${loaded.ignoredCount} ignored files and folders were not read." else ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Could not load ${scope.displayName}: ${e.message ?: e.javaClass.simpleName}"
                status.toolTipText = status.text
            } finally {
                if (refreshJob === coroutineContext[Job]) {
                    refreshJob = null
                    updateControls()
                }
            }
        }
        refreshJob?.start()
        updateControls()
    }

    private fun updateControls() {
        newSelectedTab.isEnabled = snapshot != null && refreshJob?.isActive != true && selectedEntries().isNotEmpty()
    }

    private fun addTab(paths: Set<String>? = null) {
        val number = nextTab++
        val name = if (number == 1) "Whole scope" else if (paths == null) "Review $number" else "Selection $number"
        val tab = CodeReviewTab(project, paths, ::selectedPaths, ::openFile)
        Disposer.register(this, tab)
        reviewTabs += tab
        snapshot?.let { tab.update(it) }
        if (snapshot == null || refreshJob?.isActive == true) tab.invalidate(stop = false)
        val info = TabInfo(tab).setText(name).setTooltipText("$name · Double-click to rename")
        tab.canvasFile.renameReview(name)
        tab.onCanvasSelected = {
            if (!disposed && tab in reviewTabs && tabs.selectedInfo !== info) {
                selectingFromCanvas = true
                try { tabs.select(info, false) } finally { selectingFromCanvas = false }
            }
        }
        info.setTabLabelActions(DefaultActionGroup(object : DumbAwareAction(
            "Close tab", "Discard this tab's chat history, draft and canvas", AllIcons.Actions.Close,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) { closeTab(info) }
        }), "Insight.CodeReviewTabs")
        tabs.addTab(info)
        tabs.select(info, false)
    }

    private fun closeTab(info: TabInfo) {
        val tab = info.component as CodeReviewTab
        if (!reviewTabs.remove(tab)) return
        Disposer.dispose(tab)
        tabs.removeTab(info)
        if (reviewTabs.isEmpty()) addTab()
    }

    private fun selectedTab(): CodeReviewTab? = tabs.selectedInfo?.component as? CodeReviewTab

    fun openCanvas(focus: Boolean = false) { selectedTab()?.openCanvas(focus) }

    private fun selectedFile(): CodeFile? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? CodeFile

    /** Every file beneath the selected nodes. */
    internal fun selectedPaths(): List<String> = tree.selectionPaths.orEmpty().flatMap { path ->
        val node = path.lastPathComponent as DefaultMutableTreeNode
        node.depthFirstEnumeration().toList().mapNotNull { (it as DefaultMutableTreeNode).userObject as? CodeFile }.map { it.path }
    }.distinct()

    /** The selected files and folders, as a new tab's fixed scope. */
    private fun selectedEntries(): List<String> = tree.selectionPaths.orEmpty().mapNotNull { path ->
        when (val entry = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
            is CodeFile -> entry.path
            is FolderEntry -> entry.path
            else -> null
        }
    }.distinct()

    /** The project-relative paths of the files listed in the tree. */
    internal fun listedFiles(): List<String> = (tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList()
        .mapNotNull { ((it as DefaultMutableTreeNode).userObject as? CodeFile)?.path }.sorted()

    /** Folders, then files, by path. Only the first level opens at first; a reload keeps folders as the user left them. */
    private fun renderFiles(snapshot: CodeSnapshot) {
        val oldRoot = tree.model.root as DefaultMutableTreeNode
        val oldFolders = oldRoot.depthFirstEnumeration().toList().mapNotNull { node ->
            ((node as DefaultMutableTreeNode).userObject as? FolderEntry)?.let { it.path to node }
        }.toMap()
        val expanded = oldFolders.filterValues { tree.isExpanded(TreePath(it.path)) }.keys
        val selection = tree.selectionPaths.orEmpty().mapNotNull { path ->
            when (val entry = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
                is CodeFile -> entry.path
                is FolderEntry -> "${entry.path}/"
                else -> null
            }
        }.toSet()
        val root = DefaultMutableTreeNode("Files")
        val folders = HashMap<String, DefaultMutableTreeNode>()
        folders[""] = root
        val base = snapshot.scope.path
        val fileNodes = mutableListOf<Pair<DefaultMutableTreeNode, DefaultMutableTreeNode>>()
        for (file in snapshot.files) {
            // Paths below the scope folder; the folder itself is the invisible root.
            val inBase = base.isNotEmpty() && file.path.startsWith("$base/")
            val relative = if (inBase) file.path.removePrefix("$base/") else file.path
            val prefix = if (inBase) "$base/" else ""
            var parent = root
            var folder = ""
            for (name in relative.split('/').dropLast(1)) {
                folder = if (folder.isEmpty()) name else "$folder/$name"
                val owner = parent
                parent = folders.getOrPut(folder) { DefaultMutableTreeNode(FolderEntry(name, prefix + folder)).also { fileNodes += owner to it } }
            }
            fileNodes += parent to DefaultMutableTreeNode(file)
        }
        // Folders before files in each folder, both in path order.
        val children = fileNodes.groupBy({ it.first }, { it.second })
        for ((parent, nodes) in children) {
            nodes.sortedWith(compareBy<DefaultMutableTreeNode> { it.userObject is CodeFile }.thenBy { it.userObject.toString() })
                .forEach(parent::add)
        }
        tree.model = DefaultTreeModel(root)
        val selected = mutableListOf<TreePath>()
        // Parents come first, so a folder opens only inside an open parent (expanding a path would open its parents too).
        for (node in root.preorderEnumeration().toList()) {
            node as DefaultMutableTreeNode
            when (val entry = node.userObject) {
                is FolderEntry -> {
                    val parent = node.parent as DefaultMutableTreeNode
                    val open = if (entry.path in oldFolders) entry.path in expanded else parent === root
                    if (open && (parent === root || tree.isExpanded(TreePath(parent.path)))) tree.expandPath(TreePath(node.path))
                    if ("${entry.path}/" in selection) selected += TreePath(node.path)
                }
                is CodeFile -> if (entry.path in selection) selected += TreePath(node.path)
            }
        }
        tree.selectionPaths = selected.toTypedArray()
    }

    private fun updateSkipped(snapshot: CodeSnapshot?) {
        skippedModel.clear()
        val skipped = snapshot?.skipped.orEmpty()
        val total = skippedCount(snapshot)
        skipped.take(MAX_LISTED_SKIPPED).forEach { skippedModel.addElement("${it.path} — ${it.reason.label}") }
        if (total > skippedModel.size()) skippedModel.addElement("… ${total - skippedModel.size()} more")
        skippedBlock.isVisible = total > 0
        updateSkippedToggle()
    }

    private fun updateSkippedToggle() {
        val total = skippedCount(snapshot)
        skippedToggle.text = "Skipped · ${String.format(Locale.US, "%,d", total)}"
        skippedToggle.icon = if (skippedScroll.isVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
    }

    private fun openFile(path: String, line: Int?) {
        val known = snapshot?.byPath?.get(path)
        val file = findSourceFile(project, path, known?.absolutePath) ?: return
        val descriptor = if (line != null && line > 0) OpenFileDescriptor(project, file, line - 1, 0) else OpenFileDescriptor(project, file)
        descriptor.navigate(true)
    }

    override fun dispose() {
        disposed = true
        refreshTimer.stop()
        coroutines.cancel()
    }

    private companion object {
        const val REFRESH_DELAY_MS = 1_500
        const val MAX_LISTED_SKIPPED = 500
    }
}

/** Skipped files, counting every file over the limits rather than only the listed ones. */
private fun skippedCount(snapshot: CodeSnapshot?): Int {
    if (snapshot == null) return 0
    val listedOverLimit = snapshot.skipped.count { it.reason == SkipReason.LIMIT }
    return snapshot.skipped.size - listedOverLimit + snapshot.overLimit
}

/** "N files · M skipped · K large files split into P parts", and a hint when the scope was cut at its limits. */
internal fun codeStatusText(snapshot: CodeSnapshot): String {
    fun count(value: Number) = String.format(Locale.US, "%,d", value)
    val files = snapshot.files.size
    var text = "${count(files)} ${if (files == 1) "file" else "files"} · ${count(skippedCount(snapshot))} skipped"
    if (snapshot.chunkedFiles > 0) text += " · ${count(snapshot.chunkedFiles)} large ${if (snapshot.chunkedFiles == 1) "file" else "files"} " +
        "split into ${count(snapshot.partCount)} parts"
    if (snapshot.truncated) text += " · Stopped at the scope limit: ${count(snapshot.overLimit)} files not loaded. Choose a subdirectory."
    return text
}
