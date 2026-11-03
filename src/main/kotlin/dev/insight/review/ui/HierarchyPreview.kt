package dev.insight.review.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.insight.diff.DiffFileEditor
import dev.insight.diff.ReviewFile
import dev.insight.review.ReviewSource
import dev.insight.review.ReviewSourceBlock
import dev.insight.review.ReviewSourceRange
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/** Keeps evidence beside the chart, with an independent reading position and one viewer per file. */
internal class HierarchyPreviewPanel(
    chart: HierarchyColumnsPanel,
    left: JComponent,
    private val project: Project?,
    initialPaths: List<String> = emptyList(),
    private val remember: (List<String>) -> Unit = {},
    private val createViewer: ((ReviewSource) -> JComponent)? = null,
) : JPanel(BorderLayout()), Disposable {
    private val blocks = JPanel(VerticalLayout(JBUI.scale(8))).apply {
        name = "hierarchy-preview-blocks"
        isOpaque = false
        border = JBUI.Borders.empty(8)
    }
    private val editors = mutableListOf<Disposable>()
    private var paths: List<String>? = null
    private var disposed = false

    init {
        name = "hierarchy-with-preview"
        val scroll = JBScrollPane(blocks).apply {
            name = "hierarchy-preview-scroll"
            preferredSize = Dimension(JBUI.scale(640), left.preferredSize.height)
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, scroll).apply {
            name = "hierarchy-preview-split"
            resizeWeight = 0.6
            border = null
            left.minimumSize = Dimension(JBUI.scale(240), 0)
            scroll.minimumSize = Dimension(JBUI.scale(240), 0)
            preferredSize = Dimension(left.preferredSize.width + JBUI.scale(640), left.preferredSize.height)
        })
        for ((component, files) in chart.previewScopes) {
            val listener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = showFiles(files)
                override fun mousePressed(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) showFiles(files) }
                override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) showFiles(files) }
            }
            fun attach(child: Component) {
                val existing = child.mouseListeners
                existing.forEach(child::removeMouseListener)
                child.addMouseListener(listener)
                existing.forEach(child::addMouseListener)
                if (child is Container) child.components.forEach(::attach)
            }
            attach(component)
        }
        val sources = chart.previewScopes.values.flatten().associateBy { it.path }
        showFiles(initialPaths.mapNotNull(sources::get))
    }

    internal fun showFiles(files: List<ReviewSource>) {
        if (disposed) return
        val unique = files.distinctBy { it.path }
        val next = unique.map { it.path }
        if (paths == next) return
        paths = next
        remember(next)
        releaseEditors()
        blocks.removeAll()
        if (unique.isEmpty()) blocks.add(JLabel("Hover or click a topic or summary to preview its files."))
        for (file in unique) {
            val viewer = createViewer?.invoke(file) ?: if (file is ReviewFile) {
                DiffFileEditor(file, project).apply { resizeFor(JBUI.scale(600), 1.0) }
            } else {
                val text = file.text.orEmpty()
                DiffSourceEditor(ReviewSourceBlock(ReviewSourceRange(file.path, 1, maxOf(1, text.lines().size)),
                    text, fullText = text, absolutePath = file.absolutePath), project).apply { resizeFor(JBUI.scale(600), 1.0) }
            }
            (viewer as? Disposable)?.let(editors::add)
            blocks.add(JPanel(BorderLayout()).apply {
                name = "hierarchy-preview:${file.path}"
                border = JBUI.Borders.customLine(com.intellij.ui.JBColor.border())
                add(JLabel(file.path).apply { border = JBUI.Borders.empty(6); toolTipText = file.path }, BorderLayout.NORTH)
                add(viewer, BorderLayout.CENTER)
            })
        }
        blocks.revalidate()
        blocks.repaint()
    }

    private fun releaseEditors() { editors.forEach(Disposer::dispose); editors.clear() }
    override fun dispose() { disposed = true; releaseEditors() }
}

internal fun findHierarchyPanel(component: Component): HierarchyColumnsPanel? =
    component as? HierarchyColumnsPanel ?: (component as? Container)?.components?.firstNotNullOfOrNull(::findHierarchyPanel)
