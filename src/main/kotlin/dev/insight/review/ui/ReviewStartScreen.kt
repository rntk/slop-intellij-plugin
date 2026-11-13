package dev.insight.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewCanvasFile
import com.intellij.ide.util.PropertiesComponent
import dev.insight.review.ReviewCanvasOwner
import dev.insight.review.ReviewPipeline
import dev.insight.review.ReviewVocabulary
import dev.insight.review.openReviewCanvas
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/** The wording of a review's Start page: what is chosen there. */
internal class ReviewStartText(
    val heading: String,
    val hint: String,
)

/**
 * A review panel's Start page in the editor area, where the code or diff to review is chosen and its files are browsed before
 * the summaries and the other views. One per panel, shared by its review tabs: [controls] choose the source, [files] list what
 * it holds and fill the rest of the page, and [proceed] continues to the hub with the checked [pipelines] to run in the
 * selected tab.
 */
internal class ReviewStartScreen(
    private val project: Project,
    vocabulary: ReviewVocabulary,
    private val wording: ReviewStartText,
    controls: JComponent,
    files: JComponent,
    /** The pipelines that can be scheduled from this page, in their run order. */
    pipelines: List<ReviewPipeline> = emptyList(),
    private val proceed: (Set<ReviewPipeline>) -> Unit,
) : Disposable {
    private var disposed = false
    private val preferences = PropertiesComponent.getInstance(project)
    private val pipelinesKey = "Insight.${vocabulary.canvasTitle.replace(" ", "")}.StartPipelines"
    /** The checkbox of each pipeline; internal for tests. */
    internal val pipelineBoxes: Map<ReviewPipeline, JCheckBox> = ReviewPipeline.parse(preferences.getValue(pipelinesKey)).let { saved ->
        pipelines.associateWith { pipeline ->
            JCheckBox(pipeline.label, pipeline in saved).apply {
                name = "review-start-pipeline-${pipeline.name.lowercase()}"
                isOpaque = false
                toolTipText = pipeline.tooltip
                addActionListener { pipelinesChanged() }
            }
        }
    }
    /** The checked pipelines, in run order. */
    val selectedPipelines: Set<ReviewPipeline> get() = pipelineBoxes.filterValues { it.isSelected }.keys
    internal val continueButton = JButton().apply {
        name = "review-start-continue"
        addActionListener { proceed(selectedPipelines) }
    }

    init { updateContinue() }

    private fun pipelinesChanged() {
        preferences.setValue(pipelinesKey, ReviewPipeline.format(selectedPipelines), "")
        updateContinue()
    }

    private fun updateContinue() {
        val selected = selectedPipelines
        continueButton.text = if (selected.isEmpty()) "Continue to review" else "Continue and generate"
        continueButton.toolTipText = if (selected.isEmpty()) "Go on to the review views of the current source"
            else "Go on to the review views and generate in the selected review tab, one after another: " + selected.joinToString(", ") { it.label }
    }

    /** The checkboxes of the pipelines to schedule, under a short explanation; null when the page offers none. */
    private fun pipelineSection(): JComponent? = if (pipelineBoxes.isEmpty()) null else JPanel(VerticalLayout(JBUI.scale(4))).apply {
        isOpaque = false
        add(JLabel("Generate on Continue").apply { font = JBFont.label().asBold() })
        add(JLabel("Checked pipelines run one after another in the selected review tab; finished ones are skipped.").apply {
            font = JBFont.small(); foreground = UIUtil.getContextHelpForeground()
        })
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0)).apply {
            isOpaque = false
            pipelineBoxes.values.forEach(::add)
        })
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
            pipelineSection()?.let(::add)
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

    override fun dispose() {
        disposed = true
        FileEditorManager.getInstance(project).closeFile(file)
        file.setValid(false)
    }

    private companion object {
        const val MAX_WIDTH = 960
    }
}
