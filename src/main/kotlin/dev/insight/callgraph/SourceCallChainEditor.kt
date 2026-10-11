package dev.insight.callgraph

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.beans.PropertyChangeListener
import javax.swing.*

class SourceCallChainFile(val result: CallGraphResult, val chain: SourceCallChain, val rootName: String) :
    LightVirtualFile("Side by side: $rootName")

fun openSourceCallChain(project: Project, result: CallGraphResult, chain: SourceCallChain, name: String) {
    FileEditorManager.getInstance(project).openFile(SourceCallChainFile(result, chain, name), true)
}

class SourceCallChainEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is SourceCallChainFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = SourceCallChainEditor(project, file as SourceCallChainFile)
    override fun getEditorTypeId() = "insight-source-call-chain"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class SourceCallChainEditor(project: Project, private val file: SourceCallChainFile) : UserDataHolderBase(), FileEditor {
    private val panel = SourceCallChainPanel(project, file)
    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel.choice
    override fun getName() = "Side by side"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = panel.dispose()
}

/** Full source documents, using the IDE's editors, highlighters, colour scheme and line numbering. */
internal class SourceCallChainPanel(private val project: Project, private val file: SourceCallChainFile) : JPanel(BorderLayout()) {
    private val chain = file.chain
    private val numbers = chain.nodes.mapIndexed { index, id -> id to index + 1 }.toMap()
    private val calls = chain.edges.groupBy { it.caller }
    val choice = JComboBox(chain.nodes.map { "${numbers.getValue(it)}. ${file.result.graph.nodes[it].name}" }.toTypedArray())
    private val columnWidth = JBUI.scale(520)
    private val columns = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(40)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = columnWidth
        override fun getScrollableTracksViewportWidth() = false
        override fun getScrollableTracksViewportHeight() = true
    }.apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
    private val scroll = JBScrollPane(columns)
    private val panes = chain.nodes.map(::SourceColumn)
    private var disposed = false

    init {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JLabel("${file.rootName} · ${chain.nodes.size} source columns · ${chain.edges.size} calls"), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Go to function:")); add(choice)
                add(JButton("Previous").apply { addActionListener { move(-1) } })
                add(JButton("Next").apply { addActionListener { move(1) } })
            }, BorderLayout.CENTER)
            add(JLabel("Follow Calls → links to trace branches and cycles. Highlighted code is the function for each column."), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        if (panes.isEmpty()) add(JLabel("No functions or outgoing calls were found in this node."), BorderLayout.CENTER)
        else {
            panes.forEach(columns::add)
            add(scroll, BorderLayout.CENTER)
        }
        add(JLabel("Source uses current file contents. Call relationships are a graph snapshot; reopen from Application Graph after edits."), BorderLayout.SOUTH)
        choice.addActionListener { chain.nodes.getOrNull(choice.selectedIndex)?.let(::reveal) }
        scroll.viewport.addChangeListener { updateVisibleEditors() }
        // Only nearby columns own editors, even for large graphs. More are loaded while scrolling.
        updateVisibleEditors()
    }

    private fun move(delta: Int) {
        if (chain.nodes.isNotEmpty()) choice.selectedIndex = (choice.selectedIndex + delta).coerceIn(0, chain.nodes.lastIndex)
    }

    private fun reveal(id: Int) {
        val index = chain.nodes.indexOf(id)
        if (index < 0) return
        columns.scrollRectToVisible(Rectangle(index * columnWidth, 0, columnWidth, columns.height.coerceAtLeast(1)))
        updateVisibleEditors()
        panes[index].focusFunction()
        if (choice.selectedIndex != index) choice.selectedIndex = index
    }

    private fun updateVisibleEditors() {
        if (disposed) return
        val left = scroll.viewport.viewPosition.x
        val right = left + scroll.viewport.extentSize.width.coerceAtLeast(columnWidth)
        panes.forEachIndexed { index, pane ->
            if ((index + 1) * columnWidth >= left - columnWidth && index * columnWidth <= right + columnWidth) pane.load()
            else pane.release()
        }
    }

    fun dispose() {
        disposed = true
        panes.forEach { it.release() }
    }

    private inner class SourceColumn(private val id: Int) : JPanel(BorderLayout(0, JBUI.scale(4))) {
        private val node = file.result.graph.nodes[id]
        private var editor: Editor? = null
        private var loaded = false
        private var verticalOffset: Int? = null
        private var horizontalOffset = 0
        private val body = JPanel(BorderLayout()).apply { add(JLabel("Source loads when this column is visible.")) }

        init {
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(6))
            preferredSize = Dimension(columnWidth, JBUI.scale(600))
            minimumSize = Dimension(columnWidth, 0)
            maximumSize = Dimension(columnWidth, Int.MAX_VALUE)
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(JLabel("${numbers.getValue(id)}. ${node.name} · call depth ${chain.depths.getValue(id)}"), BorderLayout.NORTH)
                add(JLabel(node.location).apply { toolTipText = node.filePath }, BorderLayout.CENTER)
                add(JButton("Open source").apply {
                    addActionListener {
                        source()?.let { (virtualFile, offset, _) -> OpenFileDescriptor(project, virtualFile, offset).navigate(true) }
                    }
                }, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
            val links = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
            val outgoing = calls[id].orEmpty()
            if (outgoing.isEmpty()) links.add(JLabel("No outgoing project calls"))
            outgoing.forEach { edge ->
                val target = file.result.graph.nodes[edge.callee]
                links.add(JButton("Calls → ${numbers.getValue(edge.callee)}. ${target.name}").apply {
                    horizontalAlignment = SwingConstants.LEFT
                    toolTipText = target.location
                    addActionListener { reveal(edge.callee) }
                })
            }
            add(JBScrollPane(links).apply { preferredSize = Dimension(columnWidth, JBUI.scale(100)) }, BorderLayout.SOUTH)
        }

        private fun source(): Triple<VirtualFile, Int, Int>? {
            val element = file.result.pointers.getOrNull(id)?.element?.takeIf { it.isValid } ?: return null
            val virtualFile = element.containingFile?.virtualFile ?: return null
            val range = element.textRange ?: return null
            return Triple(virtualFile, range.startOffset, range.endOffset)
        }

        fun load() {
            if (loaded) return
            loaded = true
            val source = source()
            val document = source?.first?.let { FileDocumentManager.getInstance().getDocument(it) }
            body.removeAll()
            if (source == null || document == null) {
                body.add(JLabel("Source is no longer available. Reopen from Application Graph."))
            } else {
                val viewer = EditorFactory.getInstance().createViewer(document, project) as EditorEx
                editor = viewer
                viewer.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, source.first)
                viewer.settings.apply {
                    isLineNumbersShown = true
                    isFoldingOutlineShown = true
                    isUseSoftWraps = false
                }
                val start = source.second.coerceIn(0, document.textLength)
                val end = source.third.coerceIn(start, document.textLength)
                viewer.markupModel.addRangeHighlighter(start, end, HighlighterLayer.SELECTION - 1,
                    TextAttributes().apply { backgroundColor = JBColor(Color(235, 240, 255), Color(47, 57, 77)) },
                    HighlighterTargetArea.LINES_IN_RANGE)
                body.add(viewer.component, BorderLayout.CENTER)
                viewer.caretModel.moveToOffset(start)
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
            body.revalidate()
            body.repaint()
        }

        fun focusFunction() {
            val viewer = editor ?: return
            val offset = source()?.second ?: return
            viewer.caretModel.moveToOffset(offset.coerceIn(0, viewer.document.textLength))
            viewer.scrollingModel.scrollToCaret(ScrollType.CENTER)
        }

        fun release() {
            val viewer = editor ?: return
            verticalOffset = viewer.scrollingModel.verticalScrollOffset
            horizontalOffset = viewer.scrollingModel.horizontalScrollOffset
            editor = null
            loaded = false
            body.removeAll()
            body.add(JLabel("Source loads when this column is visible."))
            EditorFactory.getInstance().releaseEditor(viewer)
        }
    }
}
