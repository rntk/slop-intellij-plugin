package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.WeakHashMap
import javax.swing.*
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * The selected review tab's summary tree: each file, folder and group reads as its summary, so the top levels tell the
 * change and deeper levels how it was done. The real name and path of the selected node are shown below the tree.
 */
internal class ChangeSummariesView(
    private val showDiff: (String) -> Unit,
    private val selectionChanged: () -> Unit,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    private var owner: DiffReviewTab? = null
    private var shown: ChangeSummaries? = null
    private var busy = false
    /** Per tab: the expanded nodes and every node the reader has seen, so new nodes open by default and seen ones keep their state. */
    private val expansion = WeakHashMap<DiffReviewTab, Pair<Set<String>, Set<String>>>()
    private val status = JLabel()
    private val action = JButton("Summarize").apply { addActionListener { owner?.summarize() } }
    val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode())).apply {
        name = "Change summaries"
        isRootVisible = true
        showsRootHandles = true
        // Wrapped summaries have different heights.
        rowHeight = 0
        cellRenderer = Renderer()
    }
    private val details = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.empty(4, 2)
    }

    init {
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(status, BorderLayout.CENTER)
            add(action, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        val scroll = JBScrollPane(tree).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            private var width = -1
            override fun componentResized(e: ComponentEvent) {
                if (scroll.viewport.width != width) { width = scroll.viewport.width; TreeUtil.invalidateCacheAndRepaint(tree.ui) }
            }
        })
        add(JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, JBScrollPane(details).apply { border = JBUI.Borders.empty() }).apply {
            resizeWeight = 0.8; border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)
        tree.addTreeSelectionListener { renderDetails(); selectionChanged() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) selectedFile()?.let(showDiff) }
        })
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "showDiff")
        tree.actionMap.put("showDiff", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                selectedFile()?.let(showDiff) ?: tree.selectionPath?.let { if (tree.isExpanded(it)) tree.collapsePath(it) else tree.expandPath(it) }
            }
        })
        renderDetails()
    }

    /** Shows [tab]'s current summaries; cheap when nothing changed, so it can follow every chat update. */
    fun show(tab: DiffReviewTab?) {
        if (tab !== owner) {
            owner?.let { expansion[it] = viewState() }
            owner = tab
            shown = null
        }
        val summaries = tab?.state?.summaries
        busy = tab?.session?.busy == true
        val previous = shown
        shown = summaries
        when {
            summaries == null -> tree.model = DefaultTreeModel(DefaultMutableTreeNode())
            previous?.tree === summaries.tree -> {
                // Only text changed: keep expansion, selection and scroll position.
                val model = tree.model as DefaultTreeModel
                (model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList().forEach { model.nodeChanged(it) }
            }
            else -> rebuild(summaries, previous?.let { viewState() } ?: tab?.let(expansion::get))
        }
        TreeUtil.invalidateCacheAndRepaint(tree.ui)
        renderStatus(tab, summaries)
        renderDetails()
    }

    fun selectedPaths(): List<String> = selectedNodes().flatMap { it.files }.distinct()

    private fun selectedNodes(): List<SummaryNode> =
        tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? SummaryNode }

    private fun selectedFile(): String? = selectedNodes().singleOrNull()?.takeIf { it.kind == SummaryKind.FILE }?.path

    private fun viewState(): Pair<Set<String>, Set<String>> = Pair(
        TreeUtil.collectExpandedUserObjects(tree).mapNotNullTo(hashSetOf()) { (it as? SummaryNode)?.identity },
        shownIdentities(),
    )

    private fun shownIdentities(): Set<String> =
        ((tree.model.root as? DefaultMutableTreeNode)?.depthFirstEnumeration()?.toList().orEmpty())
            .mapNotNullTo(hashSetOf()) { ((it as DefaultMutableTreeNode).userObject as? SummaryNode)?.identity }

    private fun rebuild(summaries: ChangeSummaries, previous: Pair<Set<String>, Set<String>>?) {
        val selected = selectedNodes().mapTo(hashSetOf()) { it.identity }
        val nodes = summaries.tree.nodes.map { DefaultMutableTreeNode(it) }
        summaries.tree.nodes.forEach { node -> node.parent?.let { nodes[it].add(nodes[node.id]) } }
        tree.model = DefaultTreeModel(nodes[0])
        // A small tree opens fully; a large one opens on its top level, and the reader drills down.
        val (expanded, seen) = previous ?: Pair(emptySet(), emptySet())
        val small = summaries.tree.nodes.size <= 30
        for (node in summaries.tree.nodes) {
            val open = node.identity in expanded || node.identity !in seen && (small || node.kind == SummaryKind.ROOT)
            if (open && node.children.isNotEmpty()) tree.expandPath(TreePath(nodes[node.id].path))
        }
        tree.expandPath(TreePath(nodes[0].path))
        tree.selectionPaths = summaries.tree.nodes.filter { it.identity in selected }.map { TreePath(nodes[it.id].path) }.toTypedArray()
    }

    private fun renderStatus(tab: DiffReviewTab?, summaries: ChangeSummaries?) {
        val total = summaries?.total ?: 0
        val count = summaries?.count ?: 0
        status.text = when {
            tab == null || tab.state.refreshing -> "Loading diff…"
            total == 0 -> "No changed files"
            busy && summaries?.complete == false -> "Summarizing… $count of $total"
            busy -> "Chat is busy · $total summaries"
            summaries?.complete == true -> "$total summaries · select one to see its name"
            else -> "$count of $total summarized"
        }
        status.toolTipText = "Summaries are the assistant's interpretation; the tree comes from the changed paths."
        action.text = when {
            total == 0 -> "Summarize"
            summaries?.complete == true -> "Regenerate"
            count > 0 -> "Continue"
            else -> "Summarize"
        }
        action.toolTipText = if (summaries?.complete == true) "Write every summary again (/summaries)"
            else "Summarize files, then folders, up to the whole change (/summaries)"
        action.isEnabled = tab?.canSummarize == true
    }

    private fun renderDetails() {
        val summaries = shown
        val node = selectedNodes().singleOrNull()
        if (summaries == null || node == null) {
            details.text = if (selectedNodes().size > 1) "${selectedPaths().size} files selected"
                else "Select a summary to see its file or folder. Double-click a file to view its diff."
            return
        }
        val files = owner?.state?.snapshot?.files.orEmpty().associateBy { it.path }
        val count = "${node.files.size} file${if (node.files.size == 1) "" else "s"}"
        val title = when (node.kind) {
            SummaryKind.FILE -> "${node.path.substringAfterLast('/')} · ${node.file?.status ?: "changed"} file" +
                (node.file?.previousPath?.let { " · renamed from $it" } ?: "")
            SummaryKind.DIRECTORY -> "${node.path.substringAfterLast('/')}/ · folder · $count"
            SummaryKind.GROUP -> "${node.label} · ${node.children.size} entries of ${node.path.ifEmpty { "." }}/ · $count"
            SummaryKind.ROOT -> "All changes · $count"
        }
        // Directories have no review file of their own; their location follows from any file beneath them.
        val absolute = node.file?.absolutePath ?: files[node.files.firstOrNull()]?.let { file ->
            val base = file.absolutePath.removeSuffix(file.path).removeSuffix("/")
            if (node.path.isEmpty()) "$base/" else "$base/${node.path}/"
        }
        details.text = listOfNotNull(title, node.location, absolute?.takeIf { it != node.location }, "",
            summaries.text(node.id) ?: "No summary yet.").joinToString("\n")
        details.caretPosition = 0
    }

    private inner class Renderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): Component {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus)
            val treeNode = value as? DefaultMutableTreeNode ?: return this
            val node = treeNode.userObject as? SummaryNode ?: return this
            icon = when (node.kind) {
                SummaryKind.FILE -> FileTypeManager.getInstance().getFileTypeByFileName(node.path.substringAfterLast('/')).icon ?: AllIcons.FileTypes.Any_type
                SummaryKind.DIRECTORY -> AllIcons.Nodes.Folder
                SummaryKind.GROUP -> AllIcons.Actions.GroupBy
                SummaryKind.ROOT -> AllIcons.Vcs.Changelist
            }
            val ui = tree.ui as? BasicTreeUI
            val indent = (ui?.leftChildIndent ?: 7) + (ui?.rightChildIndent ?: 11)
            val available = (tree.parent as? JViewport)?.width ?: tree.width
            val width = (available - indent * (treeNode.level + 1) - (icon?.iconWidth ?: 16) - iconTextGap - JBUI.scale(12))
                .coerceAtLeast(JBUI.scale(160))
            val summary = shown?.text(node.id)
            val body = if (summary != null) escape(summary).let { if (node.kind == SummaryKind.ROOT) "<b>$it</b>" else it }
                else "<i>${escape(node.label)}</i> <font color='${ColorUtil.toHtmlColor(JBUI.CurrentTheme.ContextHelp.FOREGROUND)}'>· " +
                    "${if (busy) "summarizing…" else "no summary"}</font>"
            text = "<html><div style='width:${width}px'>$body</div></html>"
            toolTipText = node.location
            return this
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
