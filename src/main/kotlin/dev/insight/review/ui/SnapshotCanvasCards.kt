package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewNote
import dev.insight.review.ReviewNoteKey
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewVocabulary
import dev.insight.ui.zoomScaled
import dev.insight.ui.zoomed
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import javax.swing.*

/** Snapshot card presentation and the embedded editors retained while their source remains unchanged. */
internal class SnapshotCanvasCards(
    private val open: (String) -> Unit,
    private val remove: (ReviewNote) -> Unit,
    private val focusNote: (ReviewNote) -> Unit,
    private val sourceActions: SourceCanvasActions,
    private val vocabulary: ReviewVocabulary,
    private val project: Project?,
    private val zoom: Double,
) : Disposable {
    private val editors = mutableMapOf<ReviewNoteKey, DiffSourceEditor>()

    fun update(sources: List<ReviewSourceBlock>) {
        val sourcesByKey = sources.associateBy { it.key }
        editors.entries.removeIf { (key, editor) ->
            (sourcesByKey[key] != editor.block).also { if (it) editor.dispose() }
        }
    }

    fun flowCard(flow: ChangeFlow): JPanel {
        val width = cardWidth(CARD_WIDTH)
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder() }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("Change flow · Verified calls between symbol notes").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                toolTipText = "Steps are the assistant's symbol notes; each arrow is a resolved call in the source snapshot. " +
                    "Order follows the caller's source, not runtime; conditions and intermediate calls are not shown."
            }, BorderLayout.CENTER)
        }
        val body = JPanel(VerticalLayout(scaled(6))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(ChangeFlowDiagram(flow, width - scaled(2 * PADDING + 4), zoom, focusNote))
        }
        card.add(header, BorderLayout.NORTH)
        card.add(scrollBody(body, width, scaled(MAX_BODY_HEIGHT)), BorderLayout.CENTER)
        return card
    }

    /** A diff snapshot of [path] around the viewer [editor] makes for the card's inner width and zoom; [onRemove] takes it off the canvas. */
    fun diffCard(path: String, status: String, onRemove: () -> Unit, editor: (width: Int, zoom: Double) -> JComponent): JPanel {
        val width = cardWidth(720)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(JLabel("Diff snapshot · $status").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove diff block", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                onRemove()
            }.apply { cursor = Cursor.getDefaultCursor() }, BorderLayout.EAST)
        }
        val viewer = editor(width - scaled(4), zoom)
        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder()
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(header, BorderLayout.NORTH)
                add(fileRow(path, showDiffButton = false).apply { border = JBUI.Borders.empty(0, 12, 8, 8) }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(viewer, BorderLayout.CENTER)
        }
        return card
    }

    fun sourceCard(block: ReviewSourceBlock): JPanel {
        val range = block.range
        val width = cardWidth(640)
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 8, 8)
            add(JLabel("Source snapshot · ${range.side.label} · Lines ${range.startLine}–${range.endLine}").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove source block", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
                sourceActions.removeSource(range)
            }, BorderLayout.EAST)
        }
        val editor = editors.getOrPut(block.key) { DiffSourceEditor(block, project) }
        editor.resizeFor(width - scaled(4), zoom)
        val card = JPanel(BorderLayout()).apply {
            background = UIUtil.getListBackground(); border = cardBorder()
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(header, BorderLayout.NORTH)
                add(fileRow(range.path).apply { border = JBUI.Borders.empty(0, 12, 8, 8) }, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
        }
        return card
    }

    fun noteCard(note: ReviewNote): JPanel {
        val width = cardWidth(CARD_WIDTH)
        val card = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground(); border = cardBorder() }
        val header = JPanel(BorderLayout(scaled(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(8, 12, 8, 8))
            add(JLabel("${files(note.paths.size)} · LLM interpretation").apply {
                font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
            }, BorderLayout.CENTER)
            add(InplaceButton(IconButton("Remove note", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { remove(note) }
                .apply { cursor = Cursor.getDefaultCursor() }, BorderLayout.EAST)
        }
        val textWidth = width - scaled(2 * PADDING + 4)
        val body = JPanel(VerticalLayout(scaled(10))).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, PADDING, 12, PADDING)
            add(JPanel(VerticalLayout(scaled(2))).apply {
                isOpaque = false
                note.paths.forEach { add(fileRow(it)) }
            })
            note.evidence?.let { evidence ->
                add(JLabel("Verified symbol · Static links · Source snapshot").apply {
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground()
                    toolTipText = "In-project links resolved from current source when this note was added. Direct links only; no runtime order is implied. Callers are limited to this file."
                })
                add(DiffSymbolDiagram(evidence, textWidth, zoom, sourceActions.openSymbol))
            }
            add(JTextArea(note.text).apply {
                isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true
                font = scaledFont(JBFont.label()); border = null
                // A wrapping text area reports its height for the width it currently has.
                setSize(textWidth, Short.MAX_VALUE.toInt())
            })
        }
        card.add(header, BorderLayout.NORTH)
        val maxBody = scaled(MAX_BODY_HEIGHT)
        card.add(scrollBody(body, width, maxBody), BorderLayout.CENTER)
        return card
    }

    /** File name as a link, its folder dimmed next to it; the full path stays in the tooltip. */
    private fun fileRow(path: String, showDiffButton: Boolean = true): JComponent = JPanel(BorderLayout(scaled(6), 0)).apply {
        isOpaque = false
        toolTipText = vocabulary.openTooltip(path)
        val name = path.substringAfterLast('/')
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(JLabel(FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Any_type))
            add(Box.createHorizontalStrut(scaled(6)))
            add(ActionLink(name) { open(path) }.apply {
                font = scaledFont(JBFont.label()); toolTipText = vocabulary.openTooltip(path)
                minimumSize = Dimension(0, preferredSize.height)
            })
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { folder ->
                add(Box.createHorizontalStrut(scaled(6)))
                add(JLabel(folder).apply {
                    font = scaledFont(JBFont.small()); foreground = UIUtil.getContextHelpForeground(); toolTipText = path
                    minimumSize = Dimension(0, preferredSize.height)
                })
            }
        }, BorderLayout.CENTER)
        val canvasText = vocabulary.showOnCanvas
        if (showDiffButton && canvasText != null) {
            add(InplaceButton(IconButton(canvasText, vocabulary.showOnCanvasIcon, vocabulary.showOnCanvasIcon)) {
                sourceActions.showDiffCard(path)
            }.apply { cursor = Cursor.getDefaultCursor(); toolTipText = canvasText }, BorderLayout.EAST)
        }
    }

    private fun scrollBody(body: JComponent, width: Int, maxHeight: Int) = JBScrollPane(body).apply {
        border = null; isOpaque = false; viewport.isOpaque = false
        preferredSize = Dimension(width, minOf(body.preferredSize.height, maxHeight))
        verticalScrollBar.unitIncrement = scaled(16)
        horizontalScrollBar.unitIncrement = scaled(16)
    }

    private fun cardWidth(default: Int) = scaled(default)

    private fun cardBorder() =
        JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1), JBUI.Borders.empty(1))

    private fun files(count: Int) = if (count == 1) "1 file" else "$count files"
    private fun scaled(value: Int) = zoomScaled(value, zoom)
    private fun scaledFont(font: java.awt.Font) = font.zoomed(zoom)

    override fun dispose() {
        editors.values.forEach { it.dispose() }
        editors.clear()
    }

    private companion object {
        const val CARD_WIDTH = 460
        const val PADDING = 12
        const val MAX_BODY_HEIGHT = 520
    }
}
