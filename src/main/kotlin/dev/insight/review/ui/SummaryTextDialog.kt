package dev.insight.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScreenUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.insight.diff.DiffFileEditor
import dev.insight.diff.ReviewFile
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import dev.insight.review.ReviewSummaryTree
import dev.insight.review.SummaryKind
import dev.insight.review.SummaryNode
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.text.AbstractDocument
import javax.swing.text.LabelView
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledEditorKit
import javax.swing.text.ViewFactory

/**
 * A compact reader that keeps the original paragraphs instead of the split view's flattened line. A file or part also
 * shows the source or diff it was written from beneath the text, so the summary can be read against the code in place.
 */
internal class SummaryTextDialog(
    private val parent: Component,
    private val node: SummaryNode,
    summary: String,
    baseFont: Font,
    state: ChangeSummariesViewState,
    actions: ChangeSummaryActions,
    tree: ReviewSummaryTree? = null,
    entryMenu: (() -> JPopupMenu)? = null,
    private val project: Project? = null,
) : DialogWrapper(parent, false) {
    private val text = object : JTextPane() {
        override fun getScrollableTracksViewportWidth(): Boolean = true
    }.apply {
        name = "summary-reader-text"
        isEditable = false
        editorKit = object : StyledEditorKit() {
            override fun getViewFactory(): ViewFactory {
                val standard = super.getViewFactory()
                return ViewFactory { element ->
                    if (element.name == AbstractDocument.ContentElementName) object : LabelView(element) {
                        // Let a long path or identifier break across lines instead of extending beyond the viewport.
                        override fun getMinimumSpan(axis: Int): Float = if (axis == X_AXIS) 0f else super.getMinimumSpan(axis)
                    } else standard.create(element)
                }
            }
        }
        font = baseFont.deriveFont(Font.PLAIN)
        border = JBUI.Borders.empty(12)
        // JTextPane handles LF paragraphs; retain the source's paragraph boundaries for every newline convention.
        this.text = summary.replace("\r\n", "\n").replace('\r', '\n').replace('\u2028', '\n').replace('\u2029', '\n')
        styledDocument.setParagraphAttributes(0, document.length, SimpleAttributeSet().apply {
            StyleConstants.setLineSpacing(this, 0.15f)
            StyleConstants.setSpaceAbove(this, JBUI.scale(3).toFloat())
            StyleConstants.setSpaceBelow(this, JBUI.scale(5).toFloat())
        }, false)
        caretPosition = 0
        actions.review?.decorate(this, node)
        // Only install offset-based actions when newline normalization has not changed character positions.
        if (state.traced(node) && this.text == summary) installWordTracing(this, node.identity, summary, state,
            actions.trace, actions.searchSelection, actions.searchTab.takeIf { node.children.isNotEmpty() },
            actions.follow, entryMenu = entryMenu)
        else componentPopupMenu = entryMenu?.invoke()
    }

    private val summaryScroll = JBScrollPane(text).apply {
        name = "summary-reader-scroll"
        border = null
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        preferredSize = Dimension(JBUI.scale(600), JBUI.scale(360))
        minimumSize = Dimension(JBUI.scale(280), JBUI.scale(160))
        verticalScrollBar.unitIncrement = text.getFontMetrics(text.font).height
    }

    init {
        title = "Summary · ${summaryNodeTitle(node, tree)}"
        setCancelButtonText("Close")
        init()
    }

    /** The summary alone for an entry without a single file, such as a folder; otherwise the summary above its source or diff. */
    public override fun createCenterPanel(): JComponent {
        val viewer = sourceViewer(node, project) ?: return summaryScroll
        (viewer as? Disposable)?.let { Disposer.register(disposable, it) }
        // A diff viewer reports a large minimum size; without a cap it would squeeze the summary out of the splitter.
        val source = object : JPanel(BorderLayout()) {
            override fun getMinimumSize(): Dimension = Dimension(JBUI.scale(280), JBUI.scale(120))
        }.apply {
            name = "summary-reader-source"
            border = JBUI.Borders.customLineTop(JBColor.border())
            add(JLabel(node.location).apply {
                border = JBUI.Borders.empty(4, 8)
                toolTipText = node.location
            }, BorderLayout.NORTH)
            add(viewer, BorderLayout.CENTER)
        }
        return JBSplitter(true, 0.35f).apply {
            setHonorComponentsMinimumSize(true)
            name = "summary-reader-split"
            firstComponent = summaryScroll
            secondComponent = source
            preferredSize = Dimension(JBUI.scale(820), JBUI.scale(680))
        }
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)
    override fun getPreferredFocusedComponent(): JComponent = text
    override fun getInitialLocation(): Point {
        val screen = ScreenUtil.getScreenRectangle(parent)
        return Point(screen.x + (screen.width - size.width) / 2, screen.y + (screen.height - size.height) / 2)
    }
}

/**
 * The viewer for what [node]'s summary was written from: the native diff of a changed file, or the file's text, limited to
 * the part's lines for a part of a large file. Folders, groups and entries without a file have none.
 */
internal fun sourceViewer(node: SummaryNode, project: Project?): JComponent? {
    val file = node.file ?: return null
    if (node.kind != SummaryKind.FILE && node.kind != SummaryKind.CHUNK) return null
    if (file is ReviewFile) return DiffFileEditor(file, project).apply { resizeFor(JBUI.scale(800), 1.0) }
    val text = file.text ?: return null
    val lines = text.lines()
    val first = (node.lines?.first ?: 1).coerceIn(1, maxOf(1, lines.size))
    val last = (node.lines?.last ?: lines.size).coerceIn(first, maxOf(first, lines.size))
    val part = lines.subList(first - 1, minOf(last, lines.size)).joinToString("\n")
    return DiffSourceEditor(ReviewSourceBlock(ReviewSourceRange(file.path, first, last), part,
        fullText = text, absolutePath = file.absolutePath), project).apply { resizeFor(JBUI.scale(800), 1.0) }
}
