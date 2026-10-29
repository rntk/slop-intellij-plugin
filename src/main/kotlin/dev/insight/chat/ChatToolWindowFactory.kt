package dev.insight.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.dashboard.openDashboard
import dev.insight.ui.heading
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.JTabbedPane
import javax.swing.event.HyperlinkEvent

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        content.preferredFocusableComponent = panel.input
        toolWindow.contentManager.addContent(content)
    }
}

class ChatPanel(
    project: Project,
    private val service: ChatSession = project.service<ChatService>(),
    private val chartMode: Boolean = false,
    private val scopeLabel: String? = null,
    private val title: String? = null,
    private val showDashboard: Boolean = !chartMode,
    showFileTrace: Boolean = false,
    /** Show tool calls as one line each, without their results; for small floating chats. */
    private val compactTools: Boolean = false,
) : JPanel(BorderLayout(0, JBUI.scale(12))), Disposable {
    // Chart replies are wrapping HTML panes, so the chart transcript follows the viewport width.
    private val transcript = (if (chartMode) WidthTrackingPanel() else JPanel()).apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    /** The chart reference under the mouse, cleared before the transcript is rebuilt. */
    private var hovered: Pair<Int, Int>? = null
    private val scroll = JBScrollPane(transcript)
    private val fileTrace = if (showFileTrace) FileTracePanel(project) else null
    val input = JBTextArea(3, 24).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask a question… (@ to insert a file path)"
    }
    private val filePicker = ChatFilePicker(project, input)
    private val attachFile = JButton("Add file…").apply {
        toolTipText = "Find a project file and insert its relative path (or type @)"
        addActionListener { filePicker.open() }
    }
    private val send = JButton("Send")
    private val stop = JButton("Stop")
    private val clear = JButton("New chat")
    private val undoChart = JButton("Undo chart changes").apply {
        toolTipText = "Restore the chart (steps, expansion, scene and selection) to before the last reply changed it"
    }
    private val status = JLabel()
    private val subscription: Disposable

    init {
        border = JBUI.Borders.empty(12)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(JPanel(BorderLayout(0, 4)).apply {
                add(heading(title ?: if (chartMode) "Chart Chat" else "Insight Chat"), BorderLayout.NORTH)
                scopeLabel?.let { label -> add(JLabel(label).apply { toolTipText = label }, BorderLayout.SOUTH) }
            }, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
                if (showDashboard) add(JButton("Open dashboard").apply { addActionListener { openDashboard(project) } })
                add(clear)
                if (chartMode) add(undoChart)
                add(JButton("LLM settings").apply {
                    addActionListener {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, ChatSettingsConfigurable::class.java)
                        render()
                    }
                })
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(fileTrace?.let { report -> JTabbedPane().apply {
            addTab("Chat", scroll)
            addTab("Files", report)
            setToolTipTextAt(1, "Files read, percentage of content read, and clickable line ranges")
            addChangeListener { if (selectedComponent === report) report.render(service.fileTraces) }
        } } ?: scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(status, BorderLayout.NORTH)
            add(JPanel(BorderLayout(0, 4)).apply {
                add(filePicker, BorderLayout.NORTH)
                add(JBScrollPane(input), BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(attachFile) }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
            add(JPanel(GridLayout(1, 2, JBUI.scale(8), 0)).apply {
                add(stop)
                add(send)
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        send.addActionListener { submit() }
        stop.addActionListener { service.stop() }
        clear.addActionListener { service.clear() }
        undoChart.addActionListener { service.restoreChart() }
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl ENTER"), "send")
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("meta ENTER"), "send")
        input.actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = submit()
        })
        subscription = service.subscribe { render() }
        render()
    }

    private fun submit() {
        val text = input.text
        if (text.isBlank() || service.busy) return
        filePicker.close()
        input.text = ""
        service.send(text)
    }

    private fun render() {
        fileTrace?.takeIf { it.isShowing || service.fileTraces.isEmpty() }?.render(service.fileTraces)
        hovered?.let { (revision, _) -> service.chartReferences?.hoverReference(revision, null) }
        hovered = null
        transcript.removeAll()
        val messages = service.messages
        val lastReply = messages.indexOfLast { it.role == ChatMessage.ASSISTANT }
        messages.forEachIndexed { index, message ->
            if (message.role == ChatMessage.TOOL) {
                transcript.add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    border = JBUI.Borders.empty(4, 8)
                    alignmentX = LEFT_ALIGNMENT
                    add(JBLabel("Tool · ${message.text}", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER).apply {
                        toolTipText = message.toolCall?.let { "${it.name}(${it.arguments})" }
                    }, BorderLayout.NORTH)
                    message.toolCall?.takeUnless { compactTools }?.let { event ->
                        add(JBScrollPane(JBTextArea(event.result).apply {
                            isEditable = false
                            lineWrap = true
                            wrapStyleWord = true
                            columns = 24
                            rows = event.result.lineSequence().count().coerceIn(1, 6)
                            caretPosition = 0
                        }), BorderLayout.CENTER)
                    }
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                })
                return@forEachIndexed
            }
            val chart = service.chartReferences?.takeIf { chartMode && message.role == ChatMessage.ASSISTANT }
            transcript.add(JPanel(BorderLayout(0, 6)).apply {
                border = JBUI.Borders.empty(8)
                add(heading(if (message.role == ChatMessage.USER) "You" else "Insight", 13f), BorderLayout.NORTH)
                add(if (chart != null) replyPane(message, chart) else JBTextArea(message.text).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    isOpaque = false
                    columns = 24
                    if (message.role == ChatMessage.NOTICE) foreground = UIUtil.getContextHelpForeground()
                }, BorderLayout.CENTER)
                alignmentX = LEFT_ALIGNMENT
            })
            if (index == lastReply && !service.busy && service.followups.isNotEmpty()) transcript.add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(0, 8, 8, 8)
                alignmentX = LEFT_ALIGNMENT
                service.followups.forEach { question ->
                    add(JButton(question).apply {
                        font = JBUI.Fonts.smallFont()
                        toolTipText = "Ask: $question"
                        alignmentX = LEFT_ALIGNMENT
                        addActionListener { if (!service.busy) service.send(question) }
                    })
                }
            })
        }
        send.isEnabled = !service.busy
        stop.isEnabled = service.busy
        clear.isEnabled = !service.busy
        undoChart.isEnabled = service.chartRestorable
        status.text = if (service.busy) "Preparing response…" else "${service.backendLabel} · Ctrl/⌘+Enter to send"
        transcript.revalidate()
        transcript.repaint()
        SwingUtilities.invokeLater {
            if (disposed) return@invokeLater
            // HTML panes know their wrapped height only once they have a width.
            if (chartMode) { transcript.components.forEach { it.invalidate() }; scroll.validate() }
            scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
        }
    }

    /**
     * An assistant reply with [[node:ID|Label]] references as links: everything else is escaped text. Clicking a reference
     * selects its step, hovering emphasises it; references are resolved against the reply's graph revision.
     */
    internal fun replyPane(message: ChatMessage, chart: ChartReferences): JEditorPane {
        val body = chatReferenceHtml(
            message.text,
            message.revision,
            beatTitle = { beat -> message.revision?.let { chart.beatTitle(it, beat, message.storyId) } },
        ) { node -> message.revision?.let { chart.referenceName(it, node) } }
        return JEditorPane().apply {
            editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
            text = "<html><head><style>a { color: ${ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED)}; " +
                "background-color: ${ColorUtil.toHtmlColor(referenceFill)}; text-decoration: none; }</style></head><body>$body</body></html>"
            addHyperlinkListener { event ->
                parseChatBeatLink(event.description)?.let { (revision, beat) ->
                    if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                        service.openBeat(revision, beat, message.storyId)
                    }
                    return@addHyperlinkListener
                }
                val (revision, node) = parseChatReferenceLink(event.description) ?: return@addHyperlinkListener
                when (event.eventType) {
                    HyperlinkEvent.EventType.ACTIVATED -> service.openReference(revision, node)
                    HyperlinkEvent.EventType.ENTERED -> { hovered = revision to node; chart.hoverReference(revision, node) }
                    HyperlinkEvent.EventType.EXITED -> { hovered = null; chart.hoverReference(revision, null) }
                    else -> Unit
                }
            }
        }
    }

    private var disposed = false
    override fun dispose() {
        disposed = true
        filePicker.dispose()
        hovered?.let { (revision, _) -> service.chartReferences?.hoverReference(revision, null) }
        subscription.dispose()
        transcript.removeAll()
        input.text = ""
    }

    /** Lays out its rows at the viewport width, so wrapping replies never scroll sideways. */
    private class WidthTrackingPanel : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
            if (orientation == javax.swing.SwingConstants.VERTICAL) visible.height else visible.width
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private companion object {
        val referenceFill = JBColor(Color(0xE8F0FE), Color(0x2E3A4F))
    }
}
