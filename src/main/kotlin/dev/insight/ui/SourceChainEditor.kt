package dev.insight.ui

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.beans.PropertyChangeListener
import javax.swing.*

/** One visit in a reading chain. Lines are 0-based and inclusive; repeated files remain separate visits. */
internal data class SourceChainLocation(val path: String, val title: String, val line: Int,
    val endLine: Int = line, val file: VirtualFile? = null, val summary: String? = null)

internal class SourceChainFile(val locations: List<SourceChainLocation>, title: String) :
    LightVirtualFile("Side by side: $title") {
    init {
        isWritable = false
        putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, true)
        putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, true)
    }
}

internal fun openSourceChain(project: Project, locations: List<SourceChainLocation>, title: String) {
    if (locations.isNotEmpty()) FileEditorManager.getInstance(project).openFile(SourceChainFile(locations, title), true)
}

class SourceChainEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is SourceChainFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = SourceChainEditor(project, file as SourceChainFile)
    override fun getEditorTypeId() = "insight-source-chain"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Full current source documents, positioned at each step of a chain without requiring an application graph. */
private class SourceChainEditor(private val project: Project, private val file: SourceChainFile) : UserDataHolderBase(), FileEditor {
    private val columnWidth = JBUI.scale(520)
    private val columns = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(rect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(40)
        override fun getScrollableBlockIncrement(rect: Rectangle, orientation: Int, direction: Int) = columnWidth
        override fun getScrollableTracksViewportWidth() = false
        override fun getScrollableTracksViewportHeight() = true
    }.apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
    private val scroll = JBScrollPane(columns)
    private val panes = file.locations.mapIndexed { index, location -> Column(index, location) }
    private val choice = JComboBox(file.locations.mapIndexed { index, location -> "${index + 1}. ${location.title}" }.toTypedArray())
    private val panel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Go to step:"))
            add(choice)
            add(JButton("Previous").apply { addActionListener { move(-1) } })
            add(JButton("Next").apply { addActionListener { move(1) } })
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }
    private var disposed = false

    init {
        panes.forEach(columns::add)
        choice.addActionListener { reveal(choice.selectedIndex) }
        scroll.viewport.addChangeListener { updateVisible() }
        updateVisible()
    }

    private fun move(delta: Int) {
        if (panes.isNotEmpty()) choice.selectedIndex = (choice.selectedIndex + delta).coerceIn(panes.indices)
    }

    private fun reveal(index: Int) {
        if (disposed || index !in panes.indices) return
        scroll.viewport.viewSize = columns.preferredSize
        columns.scrollRectToVisible(Rectangle(index * columnWidth, 0, columnWidth, 1))
        updateVisible()
        panes[index].focusLocation()
    }

    /** Bound native editor resources even for long chains. Each visit keeps its own scroll position. */
    private fun updateVisible() {
        if (disposed) return
        val left = scroll.viewport.viewPosition.x
        val right = left + maxOf(scroll.viewport.width, columnWidth)
        WriteIntentReadAction.run {
            panes.forEachIndexed { index, pane ->
                if ((index + 1) * columnWidth >= left - columnWidth && index * columnWidth <= right + columnWidth) pane.load()
                else pane.release()
            }
        }
    }

    private fun resolve(location: SourceChainLocation): VirtualFile? = location.file?.takeIf { it.isValid && !it.isDirectory }
        ?: location.path.takeIf { it.isNotBlank() }?.let { path ->
            val absolute = if (path.startsWith("/")) path else project.basePath?.let { "$it/$path" } ?: path
            LocalFileSystem.getInstance().findFileByPath(absolute)?.takeUnless { it.isDirectory }
        }

    private inner class Column(index: Int, private val step: SourceChainLocation) : JPanel(BorderLayout(0, JBUI.scale(4))) {
        private val body = JPanel(BorderLayout())
        private var editor: Editor? = null
        private var loaded = false
        private var verticalOffset: Int? = null
        private var horizontalOffset = 0
        private var focusRequested = false

        init {
            preferredSize = Dimension(columnWidth, JBUI.scale(600))
            minimumSize = Dimension(columnWidth, 0)
            maximumSize = Dimension(columnWidth, Int.MAX_VALUE)
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(6))
            val heading = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(JLabel("${index + 1}. ${step.title}"), BorderLayout.NORTH)
                add(JLabel("${step.path}:${step.line + 1}").apply { toolTipText = text }, BorderLayout.CENTER)
                add(ActionLink("Source") {
                    resolve(step)?.let { target ->
                        OpenFileDescriptor(project, target, maxOf(0, step.line), 0).navigate(true)
                    }
                }, BorderLayout.SOUTH)
            }
            add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                add(heading, BorderLayout.NORTH)
                step.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    add(JBScrollPane(JTextArea(summary, 4, 0).apply {
                        name = "source-chain-summary"
                        isEditable = false
                        lineWrap = true
                        wrapStyleWord = true
                        font = com.intellij.util.ui.UIUtil.getLabelFont()
                        caretPosition = 0
                    }, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER)
                }
            }, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

        fun load() {
            if (loaded) return
            loaded = true
            focusRequested = false
            val target = resolve(step)
            val document = target?.let { ReadAction.compute<Document?, Throwable> { FileDocumentManager.getInstance().getDocument(it) } }
            body.removeAll()
            if (target == null || document == null) {
                body.add(JLabel("Source is no longer available."))
            } else {
                val viewer = EditorFactory.getInstance().createViewer(document, project) as EditorEx
                editor = viewer
                viewer.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, target)
                viewer.settings.apply {
                    isLineNumbersShown = true
                    isFoldingOutlineShown = true
                    isAutoCodeFoldingEnabled = false
                    isUseSoftWraps = false
                }
                if (document.lineCount > 0) {
                    val first = step.line.coerceIn(0, document.lineCount - 1)
                    val last = step.endLine.coerceIn(first, document.lineCount - 1)
                    viewer.markupModel.addRangeHighlighter(document.getLineStartOffset(first), document.getLineEndOffset(last),
                        HighlighterLayer.SELECTION - 1,
                        TextAttributes().apply { backgroundColor = JBColor(Color(235, 240, 255), Color(47, 57, 77)) },
                        HighlighterTargetArea.LINES_IN_RANGE)
                    viewer.caretModel.moveToOffset(document.getLineStartOffset(first))
                }
                body.add(viewer.component, BorderLayout.CENTER)
                SwingUtilities.invokeLater {
                    if (!disposed && editor === viewer) {
                        viewer.scrollingModel.disableAnimation()
                        if (focusRequested) {
                            // Navigation can arrive after loading but before this layout callback.
                            focusLocation()
                        } else {
                            verticalOffset?.let(viewer.scrollingModel::scrollVertically)
                                ?: viewer.scrollingModel.scrollToCaret(ScrollType.CENTER)
                            viewer.scrollingModel.scrollHorizontally(horizontalOffset)
                        }
                    }
                }
            }
            body.revalidate()
            body.repaint()
        }

        fun focusLocation() {
            val viewer = editor ?: return
            focusRequested = true
            val lineCount = viewer.document.lineCount
            if (lineCount == 0) viewer.caretModel.moveToOffset(0)
            else viewer.caretModel.moveToLogicalPosition(LogicalPosition(step.line.coerceIn(0, lineCount - 1), 0))
            viewer.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }

        fun release() {
            editor?.let { viewer ->
                verticalOffset = viewer.scrollingModel.verticalScrollOffset
                horizontalOffset = viewer.scrollingModel.horizontalScrollOffset
                editor = null
                EditorFactory.getInstance().releaseEditor(viewer)
            }
            loaded = false
            body.removeAll()
        }
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = choice
    override fun getName() = "Side by Side"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = !disposed && file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() {
        disposed = true
        WriteIntentReadAction.run { panes.forEach { it.release() } }
    }
}
