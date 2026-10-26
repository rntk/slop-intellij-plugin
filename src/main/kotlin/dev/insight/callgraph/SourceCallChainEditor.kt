package dev.insight.callgraph

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
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
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import com.intellij.openapi.ui.ComboBox
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
    override fun getName() = "Side by Side"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = panel.dispose()
}

/**
 * Full source documents, using the IDE's editors, highlighters, colour scheme and line numbering.
 * Opens with a few callers and called functions; more are added on request, here or in a new tab.
 */
internal class SourceCallChainPanel(private val project: Project, private val file: SourceCallChainFile) : JPanel(BorderLayout()) {
    private val graph = file.result.graph
    private val index = CallIndex(graph)
    private val hierarchy by lazy { graph.hierarchy() }
    private val nodesByPath by lazy { graph.nodes.groupBy({ it.filePath }, { it.id }) }
    /** The shown columns; grows and shrinks as functions are opened and closed. */
    private var chain = file.chain
    private var numbers = emptyMap<Int, Int>()
    private var calls = emptyMap<Int, List<CallGraphEdge>>()
    private var calledBy = emptyMap<Int, List<CallGraphEdge>>()
    /** The first column of the selected symbol itself; its callers are to the left. */
    private val start = chain.nodes.indexOfFirst { chain.depths[it] == 0 }.coerceAtLeast(0)
    val choice = ComboBox<String>()
    /** True while the function picker is refilled, so it does not navigate. */
    private var syncing = false
    private val summary = JLabel()
    private val columnWidth = JBUI.scale(520)
    private val columns = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(40)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = columnWidth
        override fun getScrollableTracksViewportWidth() = false
        override fun getScrollableTracksViewportHeight() = true
    }.apply { layout = BoxLayout(this, BoxLayout.X_AXIS) }
    private val scroll = JBScrollPane(columns)
    private val panes = hashMapOf<Int, SourceColumn>()
    private var disposed = false
    /** Viewport changes are ignored until the view first scrolls to [start], so callers are not loaded first. */
    private var anchored = start == 0

    init {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(summary, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Go to function:")); add(choice)
                add(JButton("Previous").apply { addActionListener { move(-1) } })
                add(JButton("Next").apply { addActionListener { move(1) } })
            }, BorderLayout.CENTER)
            add(JLabel("<html>Callers are on the left, called functions on the right. Follow ← Called by and Calls → links, or show more with + links. " +
                "In the source, Ctrl/Cmd+click a function to show it here, or right-click it to show its callers or calls here or in a new tab.</html>"),
                BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        rebuild()
        if (panes.isEmpty()) add(JLabel("No functions or calls were found in this node."), BorderLayout.CENTER)
        else add(scroll, BorderLayout.CENTER)
        add(JLabel("Source uses current file contents. Call relationships are a graph snapshot; reopen from Application Graph after edits."), BorderLayout.SOUTH)
        if (panes.isNotEmpty()) choice.selectedIndex = start
        choice.addActionListener { if (!syncing) chain.nodes.getOrNull(choice.selectedIndex)?.let(::reveal) }
        scroll.viewport.addChangeListener { if (anchored) updateVisibleEditors() }
        // Open on the selected symbol: scroll past its callers once the viewport has a size.
        if (!anchored) scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (scroll.viewport.width <= 0) return
                scroll.viewport.removeComponentListener(this)
                anchored = true
                reveal(chain.nodes[start])
            }
        })
        // Only nearby columns own editors. More are loaded while scrolling.
        updateVisibleEditors(start * columnWidth)
    }

    /** Lays out the columns of [chain], reusing existing columns and their editors. */
    private fun rebuild() {
        numbers = chain.nodes.mapIndexed { index, id -> id to index + 1 }.toMap()
        calls = chain.edges.groupBy { it.caller }
        calledBy = chain.edges.groupBy { it.callee }
        columns.removeAll()
        chain.nodes.forEach { id -> columns.add(panes.getOrPut(id) { SourceColumn(id) }.apply { refresh() }) }
        val callers = chain.depths.values.count { it < 0 }
        summary.text = "${file.rootName} · $callers callers · ${chain.nodes.size} source columns · ${chain.edges.size} calls"
        syncing = true
        choice.model = DefaultComboBoxModel(chain.nodes.map { "${numbers.getValue(it)}. ${graph.nodes[it].name}" }.toTypedArray())
        syncing = false
        columns.revalidate()
        columns.repaint()
    }

    /** Replaces the shown columns with [next] and scrolls to [focus]. */
    private fun show(next: SourceCallChain, focus: Int) {
        if (disposed || next == chain) return reveal(focus)
        val shown = next.nodes.toHashSet()
        val removed = chain.nodes.filter { it !in shown }
        chain = next
        WriteIntentReadAction.run { removed.forEach { panes.remove(it)?.release() } }
        rebuild()
        // Lay out the new columns now so the scroll target is where the column will be.
        scroll.validate()
        reveal(focus)
    }

    private fun expand(id: Int, direction: CallDirection) = show(chain.expand(index, id, direction), id)

    /** Shows [id] next to the column [near] if needed, then adds its callers or called functions. */
    private fun showAround(id: Int, near: Int, direction: CallDirection) = show(chain.add(index, id, near).expand(index, id, direction), id)

    private fun close(id: Int) {
        val at = chain.nodes.indexOf(id)
        val next = chain.remove(index, id)
        next.nodes.getOrNull(at.coerceAtMost(next.nodes.lastIndex))?.let { show(next, it) }
    }

    private fun openTab(id: Int, direction: CallDirection) {
        val name = graph.nodes[id].name
        val title = when (direction) {
            CallDirection.BOTH -> name
            CallDirection.CALLERS -> "callers of $name"
            CallDirection.CALLEES -> "calls from $name"
        }
        openSourceCallChain(project, file.result, hierarchy.sourceCallChain(id, direction = direction, index = index), title)
    }

    private fun move(delta: Int) {
        if (chain.nodes.isNotEmpty()) choice.selectedIndex = (choice.selectedIndex + delta).coerceIn(0, chain.nodes.lastIndex)
    }

    private fun reveal(id: Int) {
        val index = chain.nodes.indexOf(id)
        if (index < 0) return
        columns.scrollRectToVisible(Rectangle(index * columnWidth, 0, columnWidth, columns.height.coerceAtLeast(1)))
        updateVisibleEditors()
        panes[id]?.focusFunction()
        if (choice.selectedIndex != index) choice.selectedIndex = index
    }

    private fun updateVisibleEditors(left: Int = scroll.viewport.viewPosition.x) {
        if (disposed) return
        val right = left + scroll.viewport.extentSize.width.coerceAtLeast(columnWidth)
        // Viewport and resize events are plain AWT events without an implicit lock; editors need one to be created and released.
        WriteIntentReadAction.run { chain.nodes.forEachIndexed { index, id ->
            val pane = panes[id] ?: return@forEachIndexed
            if ((index + 1) * columnWidth >= left - columnWidth && index * columnWidth <= right + columnWidth) pane.load()
            else pane.release()
        } }
    }

    /** The application graph symbol referenced at [offset], or else the one declared around it. */
    private fun symbolAt(document: Document, offset: Int): Int? = ReadAction.compute<Int?, Throwable> {
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return@compute null
        val reference = psi.findReferenceAt(offset)
        // A reference to library code or a local variable is not a graph symbol; do not fall back to the enclosing one.
        if (reference != null) reference.resolve()?.let { graphNode(it, enclosing = false) }
        else psi.findElementAt(offset)?.let { graphNode(it, enclosing = true) }
    }

    private fun graphNode(element: PsiElement, enclosing: Boolean): Int? {
        var current: PsiElement? = ProjectGraphAnalyzer.source(element)
        while (current != null && current !is PsiFile) {
            ProjectGraphAnalyzer.key(current)?.let { key ->
                nodesByPath[key.first].orEmpty().firstOrNull { id ->
                    file.result.pointers.getOrNull(id)?.element?.let(ProjectGraphAnalyzer::key) == key
                }?.let { return it }
                if (!enclosing) return null
            }
            current = current.parent
        }
        return null
    }

    private fun symbolMenu(target: Int?, near: Int): JPopupMenu = JPopupMenu().apply {
        fun item(text: String, enabled: Boolean = true, action: () -> Unit) =
            add(JMenuItem(text).apply { isEnabled = enabled; addActionListener { action() } })
        if (target == null) {
            item("No application graph function here", enabled = false) {}
            return@apply
        }
        val name = graph.nodes[target].name
        val hasCallers = index.callers(target).isNotEmpty()
        val hasCallees = index.callees(target).isNotEmpty()
        val number = numbers[target]
        if (number != null) item("Go to $number. $name") { reveal(target) }
        else item("Show $name here") { show(chain.add(index, target, near), target) }
        item("Show callers of $name here", hasCallers) { showAround(target, near, CallDirection.CALLERS) }
        item("Show functions $name calls here", hasCallees) { showAround(target, near, CallDirection.CALLEES) }
        addSeparator()
        item("Open $name side by side in new tab") { openTab(target, CallDirection.BOTH) }
        item("Open callers of $name in new tab", hasCallers) { openTab(target, CallDirection.CALLERS) }
        item("Open functions $name calls in new tab", hasCallees) { openTab(target, CallDirection.CALLEES) }
    }

    fun dispose() {
        disposed = true
        panes.values.forEach { it.release() }
    }

    private inner class SourceColumn(private val id: Int) : JPanel(BorderLayout(0, JBUI.scale(4))) {
        private val node = graph.nodes[id]
        private var editor: Editor? = null
        private var loaded = false
        private var verticalOffset: Int? = null
        private var horizontalOffset = 0
        private val title = JLabel()
        private val closeButton = JButton("Close").apply { toolTipText = "Remove this column"; addActionListener { close(id) } }
        private val links = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        private val body = JPanel(BorderLayout()).apply { add(JLabel("Source loads when this column is visible.")) }

        init {
            preferredSize = Dimension(columnWidth, JBUI.scale(600))
            minimumSize = Dimension(columnWidth, 0)
            maximumSize = Dimension(columnWidth, Int.MAX_VALUE)
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                add(title, BorderLayout.NORTH)
                add(JLabel(node.location).apply { toolTipText = node.filePath }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                    add(JButton("Open source").apply {
                        addActionListener {
                            source()?.let { (virtualFile, offset, _) -> OpenFileDescriptor(project, virtualFile, offset).navigate(true) }
                        }
                    })
                    add(JButton("New tab").apply {
                        toolTipText = "Open ${node.name} side by side in a new tab"
                        addActionListener { openTab(id, CallDirection.BOTH) }
                    })
                    add(closeButton)
                }, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
            add(JBScrollPane(links).apply { preferredSize = Dimension(columnWidth, JBUI.scale(100)) }, BorderLayout.SOUTH)
        }

        /** Updates the number, role and links, which change as columns are added and closed. */
        fun refresh() {
            val depth = chain.depths.getValue(id)
            val line = if (depth == 0) JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 2)
                else JBUI.Borders.customLine(JBColor.border())
            border = JBUI.Borders.compound(line, JBUI.Borders.empty(6))
            val role = when {
                depth < 0 -> "caller depth ${-depth}"
                depth == 0 -> "selected"
                else -> "call depth $depth"
            }
            title.text = "${numbers.getValue(id)}. ${node.name} · $role"
            closeButton.isEnabled = chain.nodes.size > 1
            links.removeAll()
            calledBy[id].orEmpty().forEach { link("← Called by ", it.caller) }
            more(chain.hidden(index, id, CallDirection.CALLERS), "callers", CallDirection.CALLERS)
            val outgoing = calls[id].orEmpty()
            val hiddenCallees = chain.hidden(index, id, CallDirection.CALLEES)
            if (outgoing.isEmpty() && hiddenCallees.isEmpty()) links.add(JLabel("No outgoing project calls"))
            outgoing.forEach { link("Calls → ", it.callee) }
            more(hiddenCallees, "called functions", CallDirection.CALLEES)
            links.revalidate()
            links.repaint()
        }

        private fun link(prefix: String, target: Int) {
            val other = graph.nodes[target]
            links.add(JButton("$prefix${numbers.getValue(target)}. ${other.name}").apply {
                horizontalAlignment = SwingConstants.LEFT
                toolTipText = other.location
                addActionListener { reveal(target) }
            })
        }

        private fun more(hidden: List<Int>, what: String, direction: CallDirection) {
            if (hidden.isEmpty()) return
            val count = minOf(SIDE_BY_SIDE_LIMIT, hidden.size)
            val text = if (count == hidden.size) "+ Show $count more $what" else "+ Show $count of ${hidden.size} more $what"
            links.add(JButton(text).apply {
                horizontalAlignment = SwingConstants.LEFT
                toolTipText = "<html>${hidden.take(20).joinToString("<br>") { graph.nodes[it].name }}${if (hidden.size > 20) "<br>…" else ""}</html>"
                addActionListener { expand(id, direction) }
            })
        }

        private fun source(): Triple<VirtualFile, Int, Int>? = ReadAction.compute<Triple<VirtualFile, Int, Int>?, Throwable> {
            val element = file.result.pointers.getOrNull(id)?.element?.takeIf { it.isValid } ?: return@compute null
            val virtualFile = element.containingFile?.virtualFile ?: return@compute null
            val range = element.textRange ?: return@compute null
            Triple(virtualFile, range.startOffset, range.endOffset)
        }

        fun load() {
            if (loaded) return
            loaded = true
            val source = source()
            val document = source?.first?.let { ReadAction.compute<Document?, Throwable> { FileDocumentManager.getInstance().getDocument(it) } }
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
                installSymbolActions(viewer)
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

        /** Right-click offers the symbol's callers and calls, here or in a new tab; Ctrl/Cmd+click shows it next to this column. */
        private fun installSymbolActions(viewer: EditorEx) {
            viewer.installPopupHandler { event ->
                val mouse = event.mouseEvent
                symbolMenu(symbolAt(viewer.document, event.offset), id).show(mouse.component, mouse.x, mouse.y)
                true
            }
            viewer.addEditorMouseListener(object : EditorMouseListener {
                override fun mouseClicked(event: EditorMouseEvent) {
                    val mouse = event.mouseEvent
                    if (mouse.button != MouseEvent.BUTTON1 || (mouse.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK)) == 0) return
                    val target = symbolAt(viewer.document, event.offset) ?: return
                    event.consume()
                    show(chain.add(index, target, id), target)
                }
            })
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
