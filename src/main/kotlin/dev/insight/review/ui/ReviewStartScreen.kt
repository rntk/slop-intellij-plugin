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
import com.intellij.ide.util.PropertiesComponent
import dev.insight.review.ReviewCanvasOwner
import dev.insight.review.ReviewPipeline
import dev.insight.review.ReviewVocabulary
import dev.insight.review.openReviewCanvas
import dev.insight.ui.EdgeTab
import dev.insight.ui.EdgeTabHost
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

/** The wording of a review's Start page: what is chosen there and the button that returns to it. */
internal class ReviewStartText(
    val heading: String,
    val hint: String,
    /** What the floating button does, shown in its tooltip: "Change scope" | "Change diff". */
    val change: String,
    val changeTooltip: String,
)

/**
 * A review panel's Start page in the editor area, where the code or diff to review is chosen and its files are browsed before
 * the summaries and the other views. One per panel, shared by its review tabs: [controls] choose the source, [files] list what
 * it holds and fill the rest of the page, [proceed] continues to the selected tab's views with the checked [pipelines] to run
 * there, and [button] makes the floating button that names the current source and returns here, for the side panel and every
 * view page.
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
    private var source = ""
    private var sourceTooltip: String? = null
    private val sourceButtons = mutableListOf<EdgeTab>()
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
        continueButton.toolTipText = if (selected.isEmpty()) "Open the selected review tab's summaries and other views on the current source"
            else "Open the selected review tab's views and generate, one after another: " + selected.joinToString(", ") { it.label }
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

    /** Names the applied source, such as the scope folder or the compared revisions, in every [button]. */
    fun showSource(text: String, tooltip: String? = null) {
        source = text
        sourceTooltip = tooltip
        sourceButtons.forEach(::describe)
    }

    /**
     * A button floating on the edge of a page that names the current source and returns to this page; it stops following the
     * source with [parent]. See [floating] for the page that hosts it.
     */
    fun button(parent: Disposable): EdgeTab {
        val button = EdgeTab(source, AllIcons.Actions.Back).apply {
            name = "review-start-open"
            addActionListener { open(true) }
        }
        describe(button)
        sourceButtons += button
        Disposer.register(parent) { sourceButtons -= button }
        return button
    }

    /** [content] with the [button] back to this page floating on its edge, off its controls. */
    fun floating(content: JComponent, parent: Disposable): JComponent = EdgeTabHost(content, button(parent))

    private fun describe(button: EdgeTab) {
        button.text = source
        val lines = (sourceTooltip ?: source).lines().map(HtmlChunk::text) + HtmlChunk.text("${wording.change}: ${wording.changeTooltip}")
        button.toolTipText = HtmlChunk.html().children(lines.flatMapIndexed { index, line ->
            if (index == 0) listOf(line.bold()) else listOf(HtmlChunk.br(), line)
        }).toString()
        button.accessibleContext.accessibleName = "${wording.change}: $source"
        button.revalidate()
        button.parent?.let { (it as? EdgeTabHost)?.place() }
    }

    override fun dispose() {
        disposed = true
        sourceButtons.clear()
        FileEditorManager.getInstance(project).closeFile(file)
        file.setValid(false)
    }

    private companion object {
        const val MAX_WIDTH = 960
    }
}
