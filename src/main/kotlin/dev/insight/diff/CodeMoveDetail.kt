package dev.insight.diff

import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ui.highlightLines
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/** The code moves chosen in the chart: from [source] to [target], or with no target the source file on its own. */
internal data class MoveSelection(val source: String, val target: String?)

/**
 * The diff of a move's source file above the diff of its destination, each opened at the moved block with the block
 * highlighted, so the code can be compared where it left and where it arrived. With several blocks, one is shown at a time.
 */
internal class CodeMoveDetail(
    private val project: Project?,
    internal val selection: MoveSelection,
    private val source: ReviewFile,
    private val target: ReviewFile?,
    private val blocks: List<MovedBlock>,
    /** Opens the diffs of the given paths in their own tabs. */
    open: (List<String>) -> Unit,
) : JPanel(BorderLayout()), Disposable {
    private val viewers = JPanel(GridLayout(if (target == null) 1 else 2, 1, 0, JBUI.scale(8))).apply { isOpaque = false }
    private val editors = mutableListOf<DiffFileEditor>()
    internal val shown: List<DiffFileEditor> get() = editors.toList()
    internal var block: MovedBlock? = null
        private set

    init {
        isOpaque = false
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                if (blocks.size > 1) add(JComboBox(blocks.toTypedArray()).apply {
                    name = "move-block"
                    font = JBFont.small()
                    renderer = SimpleListCellRenderer.create("") { "Block ${blocks.indexOf(it) + 1} of ${blocks.size} · ${describe(it)}" }
                    toolTipText = "The moved block shown in both diffs"
                    addActionListener { (selectedItem as? MovedBlock)?.let(::show) }
                })
                else add(JLabel(blocks.singleOrNull()?.let(::describe) ?: "Where ${source.path.substringAfterLast('/')}'s code stayed or was removed").apply {
                    font = JBFont.small(); foreground = UIUtil.getContextHelpForeground()
                })
            }, BorderLayout.CENTER)
            add(ActionLink("Open in diff tabs") { open(listOfNotNull(source.path, target?.path)) }.apply {
                name = "move-open-diffs"; font = JBFont.small()
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(viewers, BorderLayout.CENTER)
        show(blocks.firstOrNull())
    }

    private fun show(block: MovedBlock?) {
        if (block == this.block && editors.isNotEmpty()) return
        this.block = block
        editors.forEach(Disposer::dispose)
        editors.clear()
        viewers.removeAll()
        viewers.add(section("Moved from", source, Side.LEFT, block?.fromLines))
        if (target != null) viewers.add(section("Moved to", target, Side.RIGHT, block?.toLines))
        viewers.revalidate()
        viewers.repaint()
    }

    /** One file's diff under a heading, opened at [lines] of [side] with those lines highlighted. */
    private fun section(title: String, file: ReviewFile, side: Side, lines: IntRange?): JPanel {
        val editor = DiffFileEditor(file, project, lines?.let { Pair.create(side, it.first - 1) }).apply {
            name = "move-diff:${file.path}"
        }
        editors += editor
        if (project != null && lines != null) editor.editors(side).forEach { highlightLines(project, it, lines.toList()) }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            add(JLabel("$title · ${file.path}" + (lines?.let { " · lines ${it.first}–${it.last}" } ?: "")).apply {
                font = JBFont.small().asBold()
                border = JBUI.Borders.empty(4, 8)
            }, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
        }
    }

    private fun describe(block: MovedBlock) =
        "${block.lines} lines · ${block.fromLines.first}–${block.fromLines.last} → ${block.toLines.first}–${block.toLines.last}"

    override fun dispose() {
        editors.forEach(Disposer::dispose)
        editors.clear()
    }
}
