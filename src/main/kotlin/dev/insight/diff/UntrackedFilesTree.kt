package dev.insight.diff

import com.intellij.icons.AllIcons
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import javax.swing.JTree
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** Checkboxes opt files into the review; folder checkboxes apply to their descendants. */
internal class UntrackedFilesTree(private val onInclude: (UntrackedFile, Boolean) -> Unit) : CheckboxTree(
    object : CheckboxTreeCellRenderer() {
        override fun customizeRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = value as CheckedTreeNode
            val file = node.userObject as? UntrackedFile
            textRenderer.append(file?.path?.substringAfterLast('/') ?: node.userObject.toString())
            textRenderer.icon = if (file == null) AllIcons.Nodes.Folder else AllIcons.FileTypes.Any_type
            toolTipText = file?.path
        }
    }, CheckedTreeNode("Untracked"),
) {
    init {
        name = "Untracked files"
        isRootVisible = false
        showsRootHandles = true
        emptyText.text = "No untracked files"
    }

    override fun onNodeStateChanged(node: CheckedTreeNode) {
        val file = node.userObject as? UntrackedFile ?: return
        onInclude(file, node.isChecked)
    }

    fun showFiles(files: List<UntrackedFile>, included: Set<String>) {
        fun folderPaths(root: CheckedTreeNode) = root.depthFirstEnumeration().toList()
            .filterIsInstance<CheckedTreeNode>().filter { it.userObject !is UntrackedFile }
            .associateBy { node -> node.path.drop(1).joinToString("/") { (it as CheckedTreeNode).userObject.toString() } }
        val oldFolders = folderPaths(model.root as CheckedTreeNode)
        val expanded = oldFolders.filterValues { isExpanded(TreePath(it.path)) }.keys
        val root = CheckedTreeNode("Untracked")
        val folders = mutableMapOf("" to root)
        for (file in files.sortedBy { it.path }) {
            var parent = root
            var prefix = ""
            for (folder in file.path.split('/').dropLast(1)) {
                prefix += "$folder/"
                val owner = parent
                parent = folders.getOrPut(prefix) { CheckedTreeNode(folder).also(owner::add) }
            }
            parent.add(CheckedTreeNode(file).apply { isChecked = file.absolutePath in included })
        }
        // The native renderer shows a mixed checkbox when descendants differ.
        for (node in root.depthFirstEnumeration().toList().filterIsInstance<CheckedTreeNode>()) {
            if (node.childCount > 0) node.isChecked = node.children().toList().any { (it as CheckedTreeNode).isChecked }
        }
        model = DefaultTreeModel(root)
        for ((path, node) in folderPaths(root).entries.sortedBy { it.key.count { c -> c == '/' } }) {
            if ((path !in oldFolders || path in expanded) &&
                (node === root || node.parent === root || isExpanded(TreePath((node.parent as CheckedTreeNode).path)))) {
                expandPath(TreePath(node.path))
            }
        }
    }
}
