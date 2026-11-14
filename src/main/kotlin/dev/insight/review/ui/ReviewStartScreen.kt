package dev.insight.review.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.review.ReviewCanvasFile
import dev.insight.review.ReviewCanvasOwner
import dev.insight.review.ReviewPipeline
import dev.insight.review.ReviewVocabulary
import dev.insight.review.openReviewCanvas
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagLayout
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingConstants
import kotlin.math.max
import kotlin.math.min

/** The wording of a review's Start page: what is chosen there, and the titles and hints of its source and files steps. */
internal class ReviewStartText(
    val heading: String,
    val hint: String,
    val source: String,
    val sourceHint: String,
    val files: String,
    val filesHint: String,
)

/**
 * A review panel's Start page in the editor area, where the code or diff to review is chosen and its files are browsed before
 * the summaries and the other views. One per panel, shared by its review tabs. The page reads as three numbered steps:
 * [controls] choose the source, [files] list what it holds and fill the rest of the page, and the last step, pinned to the
 * bottom, picks the [pipelines] to generate and [proceed]s to the hub to run them in the selected tab.
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
    /** The page's primary action: a default-styled button, so it stands out from the source step's Apply or Load. */
    internal val continueButton = object : JButton() {
        override fun getPreferredSize(): Dimension = super.getPreferredSize().let { size ->
            Dimension(max(size.width + JBUI.scale(16), JBUI.scale(MIN_BUTTON_WIDTH)), size.height)
        }
    }.apply {
        name = "review-start-continue"
        icon = AllIcons.Actions.Execute
        font = JBFont.label().asBold()
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        addActionListener { proceed(selectedPipelines) }
    }
    /** What the primary action does with the current choice, beside it. */
    private val continueSummary = wrappedText("", small = true)

    init { updateContinue() }

    private fun pipelinesChanged() {
        preferences.setValue(pipelinesKey, ReviewPipeline.format(selectedPipelines), "")
        updateContinue()
    }

    private fun updateContinue() {
        val selected = selectedPipelines
        continueButton.text = if (selected.isEmpty()) "Start review" else "Start review and generate"
        continueButton.toolTipText = if (selected.isEmpty()) "Go on to the review views of the current source"
            else "Go on to the review views and generate in the selected review tab, one after another: " + selected.joinToString(", ") { it.label }
        continueSummary.text = when {
            pipelineBoxes.isEmpty() -> "Opens the review views of the current source."
            selected.isEmpty() -> "Optional: check what to generate right away. Indented steps fill in the summaries of the step above."
            else -> "Generates " + selected.joinToString(" → ") { it.label } +
                " in the selected review tab, one after another; finished steps are skipped."
        }
    }

    /**
     * The checkboxes, a column per pipeline with the pipelines that fill in its result indented beneath it, so their order
     * and dependence read at a glance; null when the page offers none.
     */
    private fun pipelineColumns(): JComponent? = if (pipelineBoxes.isEmpty()) null else
        JPanel(HorizontalLayout(0, SwingConstants.TOP)).apply {
            isOpaque = false
            val offered = pipelineBoxes.keys
            offered.filter { it.follows == null || it.follows !in offered }.forEachIndexed { index, root ->
                add(JPanel(VerticalLayout(JBUI.scale(2))).apply {
                    isOpaque = false
                    if (index > 0) border = JBUI.Borders.emptyLeft(COLUMN_GAP)
                    add(pipelineBoxes.getValue(root))
                    offered.filter { it.follows == root }.forEach { child ->
                        add(JPanel(BorderLayout()).apply {
                            isOpaque = false
                            border = JBUI.Borders.emptyLeft(CHILD_INDENT)
                            add(pipelineBoxes.getValue(child))
                        })
                    }
                })
            }
        }

    /**
     * The last step: what to generate beside the primary action, under a line on what the action will do. Kept compact, as
     * it is pinned below the files and takes height from them.
     */
    private fun startSection(): JComponent {
        val columns = pipelineColumns()
        val content = JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
            isOpaque = false
            columns?.let { add(it, BorderLayout.CENTER) }
            // Centered against the checkboxes beside it.
            add(JPanel(GridBagLayout()).apply { isOpaque = false; add(continueButton) }, BorderLayout.EAST)
        }
        return StartSection(3, if (columns == null) "Start the review" else "Generate and start", continueSummary, content)
    }

    /** The page shown in the editor; internal for tests. */
    internal val page: JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(20, 28)
        val header = JPanel(VerticalLayout(JBUI.scale(SECTION_GAP))).apply {
            isOpaque = false
            add(JPanel(VerticalLayout(JBUI.scale(4))).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(2)
                add(JLabel(wording.heading).apply { font = JBFont.label().asBold().biggerOn(4f) })
                add(wrappedText(wording.hint, small = false))
            })
            add(StartSection(1, wording.source, wrappedText(wording.sourceHint, small = true), controls))
        }
        // As wide as the page allows, up to MAX_WIDTH, and as tall: the fields neither stretch across a wide editor nor overflow
        // a narrow one; the files take the remaining height, and the start step stays in view at the bottom.
        add(object : JPanel(BorderLayout(0, JBUI.scale(SECTION_GAP))) {
            override fun getPreferredSize(): Dimension = super.getPreferredSize().let { size ->
                val room = parent?.let { it.width - it.insets.left - it.insets.right }?.takeIf { it > 0 } ?: JBUI.scale(MAX_WIDTH)
                Dimension(room.coerceAtMost(JBUI.scale(MAX_WIDTH)), size.height)
            }
        }.apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(StartSection(2, wording.files, wrappedText(wording.filesHint, small = true), files), BorderLayout.CENTER)
            add(startSection(), BorderLayout.SOUTH)
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
        const val MIN_BUTTON_WIDTH = 200
        const val SECTION_GAP = 12
        const val COLUMN_GAP = 32
        /** About a checkbox's box and gap, so an indented label starts under its parent's label. */
        const val CHILD_INDENT = 22
    }
}

/** Read-only wrapping text in the context-help color. */
private fun wrappedText(text: String, small: Boolean) = JTextArea(text).apply {
    isEditable = false; isFocusable = false; lineWrap = true; wrapStyleWord = true
    isOpaque = false; border = null
    font = if (small) JBFont.small() else JBFont.label()
    foreground = UIUtil.getContextHelpForeground()
}

/**
 * A numbered step of the Start page: a rounded card in the hub tiles' colors, with the step's badge, title and hint above
 * its [content]. The plain panels inside are made transparent so the card shows through.
 */
private class StartSection(step: Int, title: String, hint: JComponent?, content: JComponent) : JPanel(BorderLayout(0, JBUI.scale(8))) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(10, 14, 12, 14)
        add(JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            isOpaque = false
            add(JPanel(BorderLayout()).apply { isOpaque = false; add(StepBadge(step), BorderLayout.NORTH) }, BorderLayout.WEST)
            add(JPanel(VerticalLayout(JBUI.scale(2))).apply {
                isOpaque = false
                add(JLabel(title).apply { font = JBFont.label().asBold().biggerOn(1f) })
                hint?.let(::add)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(content.also(::clearPanelBackgrounds), BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val arc = JBUI.scale(12).toFloat()
            g2.color = CARD_BACKGROUND
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            g2.color = JBColor.border()
            g2.stroke = BasicStroke(1f)
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val CARD_BACKGROUND: Color = JBColor.namedColor("ReviewTile.background", JBColor(0xFFFFFF, 0x313438))

        /** Plain layout panels paint the window background; only they are cleared, so trees and fields keep theirs. */
        fun clearPanelBackgrounds(component: JComponent) {
            if (component is JPanel) component.isOpaque = false
            component.components.filterIsInstance<JComponent>().forEach(::clearPanelBackgrounds)
        }
    }
}

/** A step number in a small filled circle, in the color of the page's primary button. */
private class StepBadge(private val step: Int) : JComponent() {
    init { font = JBFont.small().asBold() }

    override fun getPreferredSize(): Dimension = getFontMetrics(font).height.let { Dimension(it + JBUI.scale(6), it + JBUI.scale(6)) }
    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val size = min(width, height)
            val top = (height - size) / 2
            g2.color = BADGE_BACKGROUND
            g2.fillOval(0, top, size, size)
            g2.color = BADGE_FOREGROUND
            g2.font = font
            val metrics = g2.fontMetrics
            val text = step.toString()
            g2.drawString(text, (size - metrics.stringWidth(text)) / 2, top + (size - metrics.height) / 2 + metrics.ascent)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val BADGE_BACKGROUND: Color = JBColor.namedColor("Button.default.startBackground", JBColor(0x3574F0, 0x3574F0))
        val BADGE_FOREGROUND: Color = JBColor.namedColor("Button.default.foreground", JBColor(0xFFFFFF, 0xFFFFFF))
    }
}
