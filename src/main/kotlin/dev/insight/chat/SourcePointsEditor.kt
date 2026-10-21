package dev.insight.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.beans.PropertyChangeListener
import javax.swing.*

internal data class SourcePointView(val point: SourcePoint, val text: String)

/** Validate before opening the UI, and keep a private document of the exact inspected source. */
internal fun sourcePointView(point: SourcePoint, source: String): SourcePointView {
    val text = StringUtil.convertLineSeparators(source)
    val count = text.count { it == '\n' } + 1
    if (point.line < 1 || point.endLine < point.line || point.endLine > count)
        throw ToolInputException("${point.path} has $count lines; use a range within the file.")
    return SourcePointView(point, text)
}

/** One conversation's source locations. All access is on the EDT; closing the view retains its locations. */
internal class SourcePoints(private val project: Project, title: String) : Disposable {
    val file = SourcePointsFile(this, title)
    val points = mutableListOf<SourcePointView>()
    val listeners = mutableListOf<() -> Unit>()
    var disposed = false
        private set

    fun show(point: SourcePoint, source: String): String {
        if (disposed) throw ToolInputException("This conversation is closed")
        val view = sourcePointView(point, source)
        points += view
        listeners.toList().forEach { it() }
        FileEditorManager.getInstance(project).openFile(file, false)
        return "Showing ${point.path}:${point.line}" +
            (if (point.endLine == point.line) "" else "-${point.endLine}") +
            (point.side?.let { " ($it)" } ?: "") + " in side-by-side column ${points.size}."
    }

    fun clear() {
        FileEditorManager.getInstance(project).closeFile(file)
        points.clear()
    }

    override fun dispose() {
        disposed = true
        clear()
        listeners.clear()
    }
}

class SourcePointsFile internal constructor(internal val source: SourcePoints, title: String) : LightVirtualFile("Source: $title") {
    init {
        isWritable = false
        putUserData(FileEditorManagerKeys.FORBID_PREVIEW_TAB, true)
        putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, true)
    }
}

class SourcePointsEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is SourcePointsFile && !file.source.disposed
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = SourcePointsEditor(project, file as SourcePointsFile)
    override fun getEditorTypeId() = "insight-source-points"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

internal class SourcePointsEditor(private val project: Project, private val file: SourcePointsFile) : UserDataHolderBase(), FileEditor {
    private val columnWidth = JBUI.scale(520)
    private val columns = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(rect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(40)
        override fun getScrollableBlockIncrement(rect: Rectangle, orientation: Int, direction: Int) = columnWidth
        override fun getScrollableTracksViewportWidth() = false
        override fun getScrollableTracksViewportHeight() = true
    }.apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
    private val scroll = JBScrollPane(columns)
    private val panes = mutableListOf<Column>()
    private var disposed = false
    private val changed: () -> Unit = { refresh() }

    init {
        file.source.listeners += changed
        scroll.viewport.addChangeListener { updateVisible() }
        refresh()
    }

    private fun refresh() {
        if (disposed) return
        file.source.points.drop(panes.size).forEach { view ->
            val pane = Column(view)
            panes += pane
            columns.add(pane)
        }
        columns.revalidate()
        columns.repaint()
        updateVisible()
        SwingUtilities.invokeLater {
            if (!disposed && panes.isNotEmpty()) {
                columns.scrollRectToVisible(Rectangle((panes.size - 1) * columnWidth, 0, columnWidth, 1))
                updateVisible()
            }
        }
    }

    /** Bound native editor resources to the visible columns and one neighbor on either side. */
    private fun updateVisible() {
        if (disposed) return
        val left = scroll.viewport.viewPosition.x
        val right = left + maxOf(scroll.viewport.width, columnWidth)
        WriteIntentReadAction.run {
            panes.forEachIndexed { index, pane ->
                if ((index + 1) * columnWidth >= left - columnWidth && index * columnWidth <= right + columnWidth) pane.load() else pane.release()
            }
        }
    }

    private inner class Column(val view: SourcePointView) : JPanel(BorderLayout()) {
        var editor: EditorEx? = null
        private val body = JPanel(BorderLayout())
        private var verticalOffset: Int? = null
        private var horizontalOffset = 0

        init {
            preferredSize = Dimension(columnWidth, JBUI.scale(500))
            minimumSize = Dimension(columnWidth, 0)
            maximumSize = Dimension(columnWidth, Int.MAX_VALUE)
            border = JBUI.Borders.empty(6)
            val point = view.point
            val range = if (point.endLine == point.line) "${point.line}" else "${point.line}-${point.endLine}"
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(JLabel("${point.path}:$range${point.side?.let { " ($it)" } ?: ""}").apply {
                    toolTipText = text
                }, BorderLayout.NORTH)
                point.comment?.let { comment ->
                    add(JBScrollPane(JTextArea(comment, 3, 40).apply {
                        isEditable = false
                        lineWrap = true
                        wrapStyleWord = true
                        caretPosition = 0
                    }), BorderLayout.CENTER)
                }
            }, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

        fun load() {
            if (editor != null) return
            val factory = EditorFactory.getInstance()
            val viewer = factory.createViewer(factory.createDocument(view.text), project) as EditorEx
            editor = viewer
            viewer.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, view.point.path)
            viewer.settings.apply {
                isLineNumbersShown = true
                isFoldingOutlineShown = false
                isUseSoftWraps = false
            }
            val start = viewer.document.getLineStartOffset(view.point.line - 1)
            val end = viewer.document.getLineEndOffset(view.point.endLine - 1)
            viewer.markupModel.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1,
                TextAttributes().apply { backgroundColor = JBColor(Color(235, 240, 255), Color(47, 57, 77)) },
                HighlighterTargetArea.LINES_IN_RANGE)
            viewer.caretModel.moveToOffset(start)
            body.add(viewer.component, BorderLayout.CENTER)
            body.revalidate()
            SwingUtilities.invokeLater {
                if (!disposed && editor === viewer) {
                    viewer.scrollingModel.disableAnimation()
                    val saved = verticalOffset
                    if (saved == null) viewer.scrollingModel.scrollToCaret(ScrollType.CENTER)
                    else viewer.scrollingModel.scrollVertically(saved)
                    viewer.scrollingModel.scrollHorizontally(horizontalOffset)
                }
            }
        }

        fun release() {
            val viewer = editor ?: return
            verticalOffset = viewer.scrollingModel.verticalScrollOffset
            horizontalOffset = viewer.scrollingModel.horizontalScrollOffset
            editor = null
            body.removeAll()
            EditorFactory.getInstance().releaseEditor(viewer)
        }
    }

    override fun getComponent(): JComponent = scroll
    override fun getPreferredFocusedComponent(): JComponent = scroll
    override fun getName() = "Source locations"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = !disposed && !file.source.disposed
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() {
        disposed = true
        file.source.listeners -= changed
        WriteIntentReadAction.run { panes.forEach { it.release() } }
    }
}
