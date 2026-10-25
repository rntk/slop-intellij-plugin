package dev.insight.diff

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScreenUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPopupMenu
import javax.swing.ScrollPaneConstants
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.AbstractDocument
import javax.swing.text.LabelView
import javax.swing.text.StyledEditorKit
import javax.swing.text.View
import javax.swing.text.ViewFactory

/** A compact reader that keeps the original paragraphs instead of the split view's flattened line. */
internal class SummaryTextDialog(
    private val parent: Component,
    node: SummaryNode,
    summary: String,
    baseFont: Font,
    state: ChangeSummariesViewState,
    actions: ChangeSummaryActions,
    entryMenu: (() -> JPopupMenu)? = null,
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
                        override fun getMinimumSpan(axis: Int): Float = if (axis == View.X_AXIS) 0f else super.getMinimumSpan(axis)
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
            actions.score, actions.follow, entryMenu = entryMenu)
        else componentPopupMenu = entryMenu?.invoke()
    }

    init {
        title = "Summary · ${summaryNodeTitle(node)}"
        setCancelButtonText("Close")
        init()
    }

    public override fun createCenterPanel(): JComponent = JBScrollPane(text).apply {
        name = "summary-reader-scroll"
        border = null
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        preferredSize = Dimension(JBUI.scale(600), JBUI.scale(360))
        minimumSize = Dimension(JBUI.scale(280), JBUI.scale(160))
        verticalScrollBar.unitIncrement = text.getFontMetrics(text.font).height
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)
    override fun getPreferredFocusedComponent(): JComponent = text
    override fun getInitialLocation(): Point {
        val screen = ScreenUtil.getScreenRectangle(parent)
        return Point(screen.x + (screen.width - size.width) / 2, screen.y + (screen.height - size.height) / 2)
    }
}
