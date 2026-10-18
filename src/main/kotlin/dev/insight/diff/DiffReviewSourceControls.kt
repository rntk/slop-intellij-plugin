package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import javax.swing.*

/** Draft selection is applied explicitly, so typing a ref never starts intermediate reviews. */
internal class DiffReviewSourceControls(
    onRefresh: () -> Unit = {},
    onApply: (ReviewDiffRequest) -> Unit,
) : JPanel(GridBagLayout()) {
    private val mode: JComboBox<ReviewDiffMode> = JComboBox(ReviewDiffMode.entries.toTypedArray()).apply { name = "diffMode" }
    private val repository = JComboBox<ReviewRepository>().apply { name = "diffRepository" }
    private val base = JComboBox(arrayOf("HEAD", "HEAD^")).apply { isEditable = true; name = "diffBase" }
    private val target = JComboBox(arrayOf("HEAD", "HEAD^")).apply { isEditable = true; name = "diffTarget" }
    private val help = JLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var repositoryError: String? = null
    private val repositoryLabel = JLabel("Repository")
    private val baseLabel = JLabel("Base")
    private val targetLabel = JLabel("Target")
    private val applyButton: JButton = JButton("Apply diff").apply {
        addActionListener {
            onApply(ReviewDiffRequest(mode.selectedItem as ReviewDiffMode,
                (repository.selectedItem as? ReviewRepository)?.path,
                base.editor.item?.toString().orEmpty(), target.editor.item?.toString().orEmpty()))
        }
    }

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.emptyBottom(12))
        val labels = listOf(JLabel("Diff"), repositoryLabel, baseLabel, targetLabel)
        val fields = listOf(mode, repository, base, target)
        // Keep the field column stable when repository/ref rows are hidden.
        val labelWidth = labels.maxOf { it.preferredSize.width }
        val fieldHeight = fields.maxOf { it.preferredSize.height }
        labels.zip(fields).forEachIndexed { index, (label, field) ->
            label.labelFor = field
            label.preferredSize = Dimension(labelWidth, label.preferredSize.height)
            label.minimumSize = label.preferredSize
            add(label, GridBagConstraints().apply {
                gridx = 0; gridy = index
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(0, 0, 6, 8)
            })
            field.preferredSize = Dimension(field.preferredSize.width, fieldHeight)
            field.minimumSize = Dimension(0, fieldHeight)
            add(field, GridBagConstraints().apply {
                gridx = 1; gridy = index; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insetsBottom(6)
            })
        }
        help.minimumSize = Dimension(0, help.getFontMetrics(help.font).height)
        add(help, GridBagConstraints().apply {
            gridx = 0; gridy = 4; gridwidth = 2; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 0, 8, 0)
        })
        add(JPanel(GridLayout(1, 2, JBUI.scale(8), 0)).apply {
            add(applyButton)
            add(JButton("Refresh").apply { addActionListener { onRefresh() } })
        }, GridBagConstraints().apply {
            gridx = 0; gridy = 5; gridwidth = 2; weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
        })
        base.toolTipText = "Choose a branch or type a tag, commit hash or revision such as HEAD~3"
        target.toolTipText = base.toolTipText
        repository.addActionListener { updateRefs() }
        mode.addActionListener { updateFields() }
        updateFields()
    }

    fun setRepositories(repositories: List<ReviewRepository>, preferredPath: String? = null) {
        val selected = (repository.selectedItem as? ReviewRepository)?.path
        repositoryError = null
        repository.model = DefaultComboBoxModel(repositories.toTypedArray())
        (repositories.firstOrNull { it.path == selected } ?: repositories.firstOrNull { it.path == preferredPath })
            ?.let { repository.selectedItem = it }
        updateRefs()
        updateFields()
    }

    fun setRepositoryError(message: String) {
        repositoryError = "Could not load repositories: $message"
        updateFields()
    }

    private fun updateRefs() {
        val refs = (repository.selectedItem as? ReviewRepository)?.refs ?: listOf("HEAD", "HEAD^")
        for (combo in listOf(base, target)) {
            val selected = combo.editor.item?.toString() ?: "HEAD"
            combo.model = DefaultComboBoxModel(refs.toTypedArray())
            combo.selectedItem = selected
        }
    }

    private fun updateFields() {
        val selected = mode.selectedItem as ReviewDiffMode
        listOf(repositoryLabel, repository, baseLabel, base).forEach {
            it.isVisible = selected != ReviewDiffMode.LOCAL
        }
        targetLabel.isVisible = !selected.usesWorkingTree
        target.isVisible = !selected.usesWorkingTree
        help.text = when {
            selected != ReviewDiffMode.LOCAL && repositoryError != null -> repositoryError
            selected != ReviewDiffMode.LOCAL && repository.itemCount == 0 -> "No Git repositories available"
            selected == ReviewDiffMode.LOCAL -> "Tracked changes + chosen untracked files"
            selected == ReviewDiffMode.WORKING_TREE -> "Base → current working tree (includes unsaved edits)"
            selected == ReviewDiffMode.REVISIONS -> "Base → target · tip-to-tip comparison"
            else -> "Common ancestor of base and target → target"
        }
        help.toolTipText = help.text
        applyButton.isEnabled = selected == ReviewDiffMode.LOCAL || repository.itemCount > 0
        revalidate()
        repaint()
    }
}
