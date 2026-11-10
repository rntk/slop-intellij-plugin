package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewCanvasFile
import dev.insight.review.ReviewCanvasOwner
import dev.insight.review.ReviewVocabulary
import dev.insight.review.openReviewCanvas
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/** The wording of a review's Start page: what is chosen there and the button that returns to it. */
internal class ReviewStartText(
    val heading: String,
    val hint: String,
    /** The button that returns to the Start page: "Change scope" | "Change diff". */
    val change: String,
    val changeTooltip: String,
)

/**
 * A review panel's Start page in the editor area, where the code or diff to review is chosen and its files are browsed before
 * the summaries and the other views. One per panel, shared by its review tabs: [controls] choose the source, [files] list what
 * it holds and fill the rest of the page, [proceed] continues to the selected tab's views, and [bar] makes the row that names
 * the current source and returns here, for the side panel and every view page.
 */
internal class ReviewStartScreen(
    private val project: Project,
    vocabulary: ReviewVocabulary,
    private val wording: ReviewStartText,
    controls: JComponent,
    files: JComponent,
    private val proceed: () -> Unit,
) : Disposable {
    private var disposed = false
    private var source = ""
    private var sourceTooltip: String? = null
    private val sourceLabels = mutableListOf<JLabel>()
    internal val continueButton = JButton("Continue to review").apply {
        name = "review-start-continue"
        toolTipText = "Open the selected review tab's summaries and other views on the current source"
        addActionListener { proceed() }
    }

    /** The page shown in the editor; internal for tests. */
    internal val page: JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(20, 28)
        val header = JPanel(VerticalLayout(JBUI.scale(10))).apply {
            border = JBUI.Borders.emptyBottom(12)
            add(JLabel(wording.heading).apply { font = JBFont.label().asBold().biggerOn(4f) })
            add(JTextArea(wording.hint).apply {
                isEditable = false; isFocusable = false; lineWrap = true; wrapStyleWord = true
                isOpaque = false; border = null; font = JBFont.label(); foreground = UIUtil.getContextHelpForeground()
            })
            add(controls)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(continueButton) })
        }
        // As wide as the page allows, up to MAX_WIDTH, and as tall: the fields neither stretch across a wide editor nor overflow
        // a narrow one, and the files below take the remaining height.
        add(object : JPanel(BorderLayout()) {
            override fun getPreferredSize(): Dimension = super.getPreferredSize().let { size ->
                val room = parent?.let { it.width - it.insets.left - it.insets.right }?.takeIf { it > 0 } ?: JBUI.scale(MAX_WIDTH)
                Dimension(room.coerceAtMost(JBUI.scale(MAX_WIDTH)), size.height)
            }
        }.apply {
            add(header, BorderLayout.NORTH)
            add(files, BorderLayout.CENTER)
        }, BorderLayout.WEST)
    }

    val file = ReviewCanvasFile(object : ReviewCanvasOwner {
        override val canvas: JComponent get() = page
        override val isDisposed: Boolean get() = disposed
        override val canvasTitle: String = vocabulary.canvasTitle
        override fun canvasSelected() = Unit
    }).apply { renameReview("Start") }

    /** Shows the Start page; [focus] moves the keyboard focus to it. */
    fun open(focus: Boolean = true) {
        if (!disposed) openReviewCanvas(project, file, focus)
    }

    /** Names the applied source, such as the scope folder or the compared revisions, in every [bar]. */
    fun showSource(text: String, tooltip: String? = null) {
        source = text
        sourceTooltip = tooltip
        sourceLabels.forEach { it.text = text; it.toolTipText = tooltip ?: text }
    }

    /** A row naming the current source with a button back to this page; it stops following the source with [parent]. */
    fun bar(parent: Disposable): JComponent {
        val label = JLabel(source).apply { toolTipText = sourceTooltip ?: source; minimumSize = Dimension(0, minimumSize.height) }
        sourceLabels += label
        Disposer.register(parent) { sourceLabels -= label }
        return JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            name = "review-start-bar"
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(2, 6, 4, 2))
            add(label, BorderLayout.CENTER)
            add(JButton(wording.change, AllIcons.Actions.Back).apply {
                name = "review-start-open"
                toolTipText = wording.changeTooltip
                addActionListener { open(true) }
            }, BorderLayout.EAST)
        }
    }

    override fun dispose() {
        disposed = true
        sourceLabels.clear()
        FileEditorManager.getInstance(project).closeFile(file)
        file.setValid(false)
    }

    private companion object {
        const val MAX_WIDTH = 960
    }
}
