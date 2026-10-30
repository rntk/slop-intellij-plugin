package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewVocabulary
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/** File navigation and generation commands supplied by the canvas hosting the file view. */
internal data class FileViewCanvasActions(
    val openFileAt: (String, Int?) -> Unit,
    val showFileLines: (String, IntRange) -> Unit,
    val close: () -> Unit,
    val summarize: () -> Unit,
    val generateTopics: () -> Unit,
)

/** Builds the file view and retains its reading position while that file's analysis changes. */
internal class FileViewCanvasCard(
    private val vocabulary: ReviewVocabulary,
    private val actions: FileViewCanvasActions,
    private val project: Project? = null,
) {
    private var filePath: String? = null
    /** The canvas also updates this position when zooming around the pointer. */
    var scroll = 0

    fun update(content: FileViewCard?) {
        val path = content?.file?.path
        if (path != filePath) scroll = 0
        filePath = path
    }

    /**
     * One file block by block, each beside the topics and summaries of the files its calls end in; zoom, selection and new
     * topics or summaries rebuild only this card, in place.
     */
    fun card(
        content: FileViewCard,
        documentZoom: Double,
        controls: JComponent,
        installWheelZoom: (JScrollPane) -> Unit,
    ): JPanel {
        update(content)
        val structure = content.structure
        val width = DocumentColumnsPanel.defaultWidth(FileStructurePanel.railDepth(content), documentZoom)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel(listOfNotNull("File view · ${content.file.path}", structure?.let {
                "${it.blocks.size} ${if (it.blocks.size == 1) "block" else "blocks"} · reaches ${files(content.reached().size)}"
            }).joinToString(" · ")).apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Blocks, calls and reached files come from resolving the source: each call is followed through the call graph " +
                    "to the next project file, following local helpers. Hover a source line or topic card for its neighbors' summaries. " +
                    "Calls are static, not runtime order. Topics and summaries are the assistant's interpretation."
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, scaled(4), 0)).apply {
                isOpaque = false
                if (structure != null) add(controls)
                if (content.missingTopics > 0) add(JButton("Classify").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun
                    toolTipText = "${files(content.missingTopics)} reached from this file have no topic yet (/topics)"
                    addActionListener { actions.generateTopics() }
                })
                if (content.missingSummaries > 0) add(JButton("Summarize").apply {
                    font = scaledFont(JBFont.small())
                    cursor = Cursor.getDefaultCursor()
                    isEnabled = content.canRun
                    toolTipText = "${files(content.missingSummaries)} reached from this file have no summary yet (/summaries)"
                    addActionListener { actions.summarize() }
                })
                add(InplaceButton(IconButton("Close file view", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { actions.close() }
                    .apply { cursor = Cursor.getDefaultCursor() })
            }, BorderLayout.WEST)
        }
        val inner = width - scaled(2 * PADDING + 4) - scaled(16)
        val body = JPanel(VerticalLayout(scaled(8))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            val status = when {
                content.error != null -> content.error
                structure == null -> "Following the calls of ${content.file.path}…"
                structure.truncated -> "Some local helper chains reached the analysis limit; neighboring calls may be incomplete."
                else -> null
            }
            status?.let { add(JLabel(it).apply { name = "file-view-status"; font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground() }) }
            if (structure != null) add(FileStructurePanel(content, structure, inner, documentZoom,
                FileStructureActions(openFile = actions.openFileAt, showLines = actions.showFileLines), vocabulary, project))
        }
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder() }
        card.add(header, BorderLayout.NORTH)
        card.add(JBScrollPane(body).apply {
            border = null; isOpaque = false; viewport.isOpaque = false
            // Several neighboring topics may scroll sideways; leave room for that scroll bar.
            val sideways = if (body.preferredSize.width > width) horizontalScrollBar.preferredSize.height else 0
            preferredSize = Dimension(width, minOf(body.preferredSize.height + sideways, scaled(MAX_BODY_HEIGHT)))
            verticalScrollBar.unitIncrement = scaled(16)
            horizontalScrollBar.unitIncrement = scaled(16)
            if (structure != null) installWheelZoom(this)
            // The viewport has no size yet; restore the reading position after layout.
            val restore = scroll
            verticalScrollBar.addAdjustmentListener { scroll = it.value }
            SwingUtilities.invokeLater { verticalScrollBar.value = restore }
        }, BorderLayout.CENTER)
        return card
    }

    private fun cardBorder() =
        JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))

    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"
    private fun scaled(value: Int) = zoomScaled(value, 1.0)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(1.0)

    private companion object {
        const val PADDING = 12
        const val MAX_BODY_HEIGHT = 900
    }
}
