package dev.insight.codereview

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.insight.core.withPhaseProgress
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import dev.insight.InsightBundle
import dev.insight.review.ReviewDiskCache
import dev.insight.agent.findSourceFile
import dev.insight.core.describeFailure
import dev.insight.core.runCatchingReported
import java.awt.BorderLayout
import java.util.Locale
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import dev.insight.review.ui.ReviewSidePages
import dev.insight.review.ui.ReviewTabsController
import dev.insight.review.ui.openFilesOnActivate
import dev.insight.review.ui.selectedFilePaths

private val LOG = logger<CodeReviewPanel>()

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

/** Project View and editor tabs: review the code in one project folder, or one entry file and what it depends on. */
class ReviewDirectoryAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = target(e)
        e.presentation.isVisible = target != null
        if (target != null && !target.isDirectory) {
            e.presentation.text = InsightBundle.message("action.Insight.ReviewDirectory.file.text")
            e.presentation.description = InsightBundle.message("action.Insight.ReviewDirectory.file.description")
        } else {
            e.presentation.text = InsightBundle.message("action.Insight.ReviewDirectory.text")
            e.presentation.description = InsightBundle.message("action.Insight.ReviewDirectory.description")
        }
        e.presentation.isEnabled = target != null && e.project?.let { project ->
            ReadAction.compute<Boolean, RuntimeException> {
                try {
                    // Metadata validation only; file loading and dependency traversal happen after invocation.
                    codeScope(project, target)
                    true
                } catch (e: IllegalArgumentException) {
                    LOG.debug("No code review scope for the target", e)
                    false
                }
            }
        } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = target(e) ?: return
        withCodeReviewPanel(project) { it.setScope(target) }
    }

    private fun target(e: AnActionEvent): VirtualFile? {
        if (e.project == null) return null
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
        return files?.singleOrNull()
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
    private var refreshJob: Job? = null
    /** The applied folder; null until one is valid. */
    internal var scope: CodeScope? = null
        private set
    /** The files of [scope]; null until the first load. */
    internal var snapshot: CodeSnapshot? = null
        private set
    /** The absolute path of the scope folder or entry file, read by the file listener on any thread. */
    @Volatile private var scopeRoot: String? = null
    /** For an entry file, the absolute paths of the files in its snapshot; null for a folder. */
    @Volatile private var watchedFiles: Set<String>? = null
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
    internal val status = JLabel("Choose a folder or an entry file to review")
    private val scopeControls = CodeScopeControls(project, onError = { message -> status.text = message; status.toolTipText = message }) { scope ->
        setScope(scope)
    }
    private val newSelectedTab = JButton("New tab from selection").apply {
        toolTipText = "Start a fresh chat and canvas scoped to the selected files or folders"
        addActionListener { selectedEntries().toSet().takeIf { it.isNotEmpty() }?.let { tabs.add(it) } }
    }
    private val viewFile = JButton("View").apply {
        toolTipText = "Open the selected file in File view, with neighboring topics and summaries on hover"
        addActionListener { viewSelectedFile() }
    }
    private val refreshTimer = Timer(REFRESH_DELAY_MS) { refresh(stopTabs = false) }.apply { isRepeats = false }
    /** Scope controls, files and review tab controls; the selected review tab's chat has its own page. */
    private val main = JPanel(BorderLayout(0, JBUI.scale(6)))
    private val pages = ReviewSidePages(main)
    private val tabs = ReviewTabsController(project, this, CODE_REVIEW_TOOL_WINDOW, "Whole scope", "Insight.CodeReviewTabs", pages,
        startupReady, ::selectedPaths, create = { paths, focus, toChat ->
            CodeReviewTab(project, paths, ::selectedPaths, ::openFile, focus = focus, toChat = toChat)
        }) { tab ->
        snapshot?.let { tab.update(it) }
        if (snapshot == null || refreshJob?.isActive == true) tab.invalidate(stop = false)
    }

    init {
        border = JBUI.Borders.empty(6)
        val files = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                add(JLabel("Files in scope · double-click to open"), BorderLayout.NORTH)
                add(JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
                    add(newSelectedTab, BorderLayout.CENTER)
                    add(viewFile, BorderLayout.EAST)
                }, BorderLayout.CENTER)
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
            tabs.selected?.highlight(selectedPaths().toSet())
            updateControls()
        }
        tree.openFilesOnActivate("openFile") { selectedFile()?.let { openFile(it.path, null); true } ?: false }
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(
            tabs.toChatAction("Chat about the selection in a new tab, with its summaries and source in context",
                enabled = { refreshJob?.isActive != true }, entries = ::selectedEntries) {
                status.text = "The selection is not in this tab's scope. Choose the Whole scope tab or a tab that includes it."
                status.toolTipText = status.text
            },
            object : DumbAwareAction("Open in File View", "Read the file block by block with the topics of the code its calls reach", AllIcons.Actions.Preview) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = canViewSelectedFile() }
                override fun actionPerformed(e: AnActionEvent) { viewSelectedFile() }
            },
            object : DumbAwareAction("Exclude from Scope", "Leave the selected files and folders out of the review", AllIcons.Actions.Cancel) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) { e.presentation.isEnabled = refreshJob?.isActive != true && excludeSelectionPatterns().isNotEmpty() }
                override fun actionPerformed(e: AnActionEvent) { exclude(excludeSelectionPatterns()) }
            },
            object : DumbAwareAction() {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    val type = selectedFileType()
                    e.presentation.isVisible = type != null
                    e.presentation.isEnabled = type != null && refreshJob?.isActive != true
                    if (type != null) e.presentation.text = "Exclude All *.${type.second} Files in ${type.first.ifEmpty { "the Project" }}"
                }
                override fun actionPerformed(e: AnActionEvent) {
                    val (folder, extension) = selectedFileType() ?: return
                    exclude(listOf(CodeExclusions.ofType(folder, extension)))
                }
            },
        ), "Insight.CodeReviewFiles")
        project.messageBus.connect(this).apply {
            // Edits are picked up when saved: the file changes on disk under the scope.
            subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val root = scopeRoot ?: return
                    if (events.any { affects(root, it) }) scheduleRefresh()
                }
            })
        }
        updateSkipped(null)
        // Loading is bounded by the scope limits and runs in the background; summaries start only on request.
        try {
            setScope(ReadAction.compute<CodeScope, RuntimeException> { codeScope(project, "") })
        } catch (e: IllegalArgumentException) {
            LOG.debug("Invalid code review scope", e)
            status.text = "Choose a folder or an entry file to review: ${e.message}"
        }
    }

    /** Reviews the folder or entry file [file] with the current exclusions; an invalid one is reported in the status line. */
    fun setScope(file: VirtualFile) {
        try {
            setScope(ReadAction.compute<CodeScope, RuntimeException> { codeScope(project, file) }.copy(excludes = scope?.excludes.orEmpty()))
        } catch (e: IllegalArgumentException) {
            LOG.debug("Invalid code review scope", e)
            status.text = e.message ?: "Choose a project folder."
            status.toolTipText = status.text
        }
    }

    /** Applies [scope] and loads it; applying the current scope again reloads it. Running reviews stop. */
    fun setScope(scope: CodeScope) {
        if (disposed) return
        this.scope = scope
        scopeRoot = scope.absolutePath.trimEnd('/')
        watchedFiles = if (scope.entry) setOf(scope.absolutePath) else null
        scopeControls.show(scope)
        refresh(stopTabs = true)
    }

    private fun affects(root: String, event: VFileEvent): Boolean {
        val path = event.path
        if ("/${ReviewDiskCache.DIRECTORY}/" in "$path/") return false
        watchedFiles?.let { watched -> return affectsDependencies(watched, event) }
        if (path != root && !path.startsWith("$root/")) return false
        if ("/.idea/" in "${path.removePrefix(root)}/") return false
        scope?.let { scope ->
            // Changes in excluded files and folders need no reload.
            val relative = listOf(scope.path, path.removePrefix(root).trim('/')).filter { it.isNotEmpty() }.joinToString("/")
            if (path != root && scope.exclusions.excludes(relative, from = scope.path)) return false
        }
        val file = event.file ?: return true
        if (!file.isValid) return true
        // Excluded folders (build output) are not content; their changes need no reload.
        return ProjectFileIndex.getInstance(project).isInContent(file)
    }

    /**
     * An entry file's dependencies change when one of its files changes, or when a project file appears, which may resolve
     * a reference that resolved to nothing before.
     */
    private fun affectsDependencies(watched: Set<String>, event: VFileEvent): Boolean {
        if ("/${ReviewDiskCache.DIRECTORY}/" in "${event.path}/") return false
        if (event.path in watched) return true
        if (event !is VFileCreateEvent && event !is VFileMoveEvent && event !is VFileCopyEvent) return false
        if ("/.idea/" in event.path) return false
        val file = event.file ?: return true
        return file.isValid && ProjectFileIndex.getInstance(project).isInContent(file)
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
        if (stopTabs) tabs.all.forEach { it.invalidate(stop = true) }
        status.text = "Loading ${scope.displayName}…"
        status.toolTipText = scope.absolutePath
        val previous = snapshot
        refreshJob = coroutines.launch(start = CoroutineStart.LAZY) {
            try {
                runCatchingReported(LOG, "Could not load ${scope.displayName}") {
                    val loaded = withBackgroundProgress(project, "Loading code review: ${scope.displayName}") {
                        withPhaseProgress("Loading code review…", "files") { onProgress ->
                            CodeReviewLoader(project).load(scope, previous, progress = onProgress)
                        }
                    }
                    snapshot = loaded
                    if (loaded.scope.entry) watchedFiles = loaded.files.mapTo(hashSetOf(loaded.scope.absolutePath)) { it.absolutePath }
                    val removed = tabs.all.sumOf { it.update(loaded) }
                    renderFiles(loaded)
                    updateSkipped(loaded)
                    status.text = codeStatusText(loaded) + if (removed > 0) " · $removed outdated notes removed" else ""
                    status.toolTipText = "${loaded.scope.absolutePath}\n${status.text}" +
                        (if (loaded.ignoredCount > 0) "\n${loaded.ignoredCount} ignored files and folders were not read." else "") +
                        if (loaded.scope.excludes.isNotEmpty()) "\nExcluded: ${CodeExclusions.format(loaded.scope.excludes)}" else ""
                }.onFailure {
                    status.text = "Could not load ${scope.displayName}: ${describeFailure(it)}"
                    status.toolTipText = status.text
                }
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()
                status.text = "Loading ${scope.displayName} cancelled. Click Load to retry."
                throw e
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
        viewFile.isEnabled = canViewSelectedFile()
    }

    /** File view shows one loaded file: a single file is selected and the review is not reloading. */
    private fun canViewSelectedFile(): Boolean =
        snapshot != null && refreshJob?.isActive != true && tree.selectionCount == 1 && selectedFile() != null

    /** Opens the selected file in the current tab's File view; a file outside a tab's fixed selection is reported. */
    private fun viewSelectedFile() {
        val file = selectedFile()?.takeIf { canViewSelectedFile() } ?: return
        val tab = tabs.selected ?: return
        if (!tab.openFileView(file.path)) {
            status.text = "${file.path} is not in this tab's scope. Choose the Whole scope tab or a tab that includes it."
            status.toolTipText = status.text
        }
    }

    fun openCanvas(focus: Boolean = false) { tabs.openCanvas(focus) }

    private fun selectedFile(): CodeFile? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? CodeFile

    /** Every file beneath the selected nodes. */
    internal fun selectedPaths(): List<String> = tree.selectedFilePaths<CodeFile>()

    /** The selected files and folders, as a new tab's fixed scope. */
    private fun selectedEntries(): List<String> = tree.selectionPaths.orEmpty().mapNotNull { path ->
        when (val entry = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
            is CodeFile -> entry.path
            is FolderEntry -> entry.path
            else -> null
        }
    }.distinct()

    /** Patterns for exactly the selected files and folders; an entry file is never excluded from its own scope. */
    internal fun excludeSelectionPatterns(): List<String> = tree.selectionPaths.orEmpty().mapNotNull { path ->
        when (val entry = (path.lastPathComponent as DefaultMutableTreeNode).userObject) {
            is CodeFile -> entry.path.takeUnless { scope?.entry == true && it == scope?.path }?.let { CodeExclusions.exact(it, directory = false) }
            is FolderEntry -> CodeExclusions.exact(entry.path, directory = true)
            else -> null
        }
    }.distinct()

    /** The folder and extension of the single selected file, for excluding every file of that type in its folder. */
    private fun selectedFileType(): Pair<String, String>? {
        val file = selectedFile()?.takeIf { tree.selectionCount == 1 } ?: return null
        val name = file.path.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && name.indexOf('.') > 0 } ?: return null
        return file.path.substringBeforeLast('/', "") to extension
    }

    /** Applies the current scope with [patterns] added to its exclusions. */
    internal fun exclude(patterns: List<String>) {
        val scope = scope ?: return
        val excludes = (scope.excludes + patterns).distinct()
        if (excludes != scope.excludes) setScope(scope.copy(excludes = excludes))
    }

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
        val base = snapshot.baseFolder
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
    if (snapshot.scope.excludes.isNotEmpty()) text += " · ${count(snapshot.excludedCount)} excluded"
    if (snapshot.truncated) text += " · Stopped at the scope limit: ${count(snapshot.overLimit)} files not loaded. " +
        if (snapshot.scope.entry) "Choose an entry file with fewer dependencies." else "Choose a subdirectory."
    return text
}
