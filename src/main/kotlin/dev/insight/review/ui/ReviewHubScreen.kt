package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewCanvasFile
import dev.insight.review.ReviewCanvasOwner
import dev.insight.review.ReviewVocabulary
import dev.insight.review.openReviewCanvas
import dev.insight.ui.EdgeTab
import dev.insight.ui.EdgeTabHost
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * A review panel's hub in the editor area, "Diff Review: Views": the review tabs strip, where each tab shows its scope and
 * the tiles of its views (see [ReviewTab]), with a New tab button above it. The tool window's stripe button and Tools → Open
 * Diff/Code Review open this page; the side panel itself holds only the selected tab's chat.
 *
 * The hub also owns the way back to it: [button] makes the floating button that names the current source (see [showSource])
 * and returns here, for the Start page and every view page.
 */
internal class ReviewHubScreen(
    private val project: Project,
    private val vocabulary: ReviewVocabulary,
    /** Adds a review tab for the whole source. */
    newTab: () -> Unit,
) : Disposable {
    private var disposed = false
    private var source = ""
    private var sourceTooltip: String? = null
    private val sourceButtons = mutableListOf<EdgeTab>()
    private val tabsHost = JPanel(BorderLayout())

    internal val newTabButton = JButton("New tab").apply {
        name = "review-hub-new-tab"
        toolTipText = vocabulary.newTabTooltip
        addActionListener { newTab() }
    }

    /** The page shown in the editor; internal for tests. */
    internal val page: JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(20, 28)
        add(JPanel(VerticalLayout(JBUI.scale(10))).apply {
            border = JBUI.Borders.emptyBottom(12)
            add(JLabel(vocabulary.hubHeading).apply { font = JBFont.label().asBold().biggerOn(4f) })
            add(JTextArea(vocabulary.hubHint).apply {
                isEditable = false; isFocusable = false; lineWrap = true; wrapStyleWord = true
                isOpaque = false; border = null; font = JBFont.label(); foreground = UIUtil.getContextHelpForeground()
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(newTabButton) })
        }, BorderLayout.NORTH)
        add(tabsHost, BorderLayout.CENTER)
    }

    val file = ReviewCanvasFile(object : ReviewCanvasOwner {
        override val canvas: JComponent get() = page
        override val isDisposed: Boolean get() = disposed
        override val canvasTitle: String = vocabulary.canvasTitle
        override fun canvasSelected() = Unit
    }).apply { renameReview("Views") }

    /** Puts the review tabs strip, [tabs], on the page; it has one parent, so the panel hands it over once. */
    fun showTabs(tabs: JComponent) {
        tabsHost.removeAll()
        tabsHost.add(tabs, BorderLayout.CENTER)
        tabsHost.revalidate()
    }

    /** Shows the hub; [focus] moves the keyboard focus to it. */
    fun open(focus: Boolean = true) {
        if (!disposed) openReviewCanvas(project, file, focus)
    }

    /** Names the applied source, such as the scope folder or the compared revisions, in every [button]. */
    fun showSource(text: String, tooltip: String? = null) {
        source = text
        sourceTooltip = tooltip
        sourceButtons.forEach(::describe)
    }

    /**
     * A button floating on the edge of a page that names the current source and returns to the hub; it stops following the
     * source with [parent]. See [floating] for a page that hosts it.
     */
    fun button(parent: Disposable): EdgeTab {
        val button = EdgeTab(source, AllIcons.Actions.Back).apply {
            name = "review-hub-open"
            addActionListener { open(true) }
        }
        describe(button)
        sourceButtons += button
        Disposer.register(parent) { sourceButtons -= button }
        return button
    }

    /** [content] with the [button] back to the hub floating on its edge, off its controls. */
    fun floating(content: JComponent, parent: Disposable): JComponent = EdgeTabHost(content, button(parent))

    private fun describe(button: EdgeTab) {
        button.text = source
        val lines = (sourceTooltip ?: source).lines().map(HtmlChunk::text) + HtmlChunk.text(vocabulary.backToHub)
        button.toolTipText = HtmlChunk.html().children(lines.flatMapIndexed { index, line ->
            if (index == 0) listOf(line.bold()) else listOf(HtmlChunk.br(), line)
        }).toString()
        button.accessibleContext.accessibleName = "${vocabulary.backToHub}: $source"
        button.revalidate()
        button.parent?.let { (it as? EdgeTabHost)?.place() }
    }

    override fun dispose() {
        disposed = true
        sourceButtons.clear()
        FileEditorManager.getInstance(project).closeFile(file)
        file.setValid(false)
    }
}
