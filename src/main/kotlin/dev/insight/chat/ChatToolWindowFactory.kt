package dev.insight.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import dev.insight.dashboard.openDashboard
import dev.insight.ui.heading
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        content.preferredFocusableComponent = panel.input
        toolWindow.contentManager.addContent(content)
    }
}

private class ChatPanel(project: Project) : JPanel(BorderLayout(0, JBUI.scale(12))), Disposable {
    private val service = project.service<ChatService>()
    private val transcript = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val scroll = JBScrollPane(transcript)
    val input = JBTextArea(3, 24).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Ask a question…"
    }
    private val send = JButton("Send")
    private val clear = JButton("New chat")
    private val status = JLabel("Local demo · no AI provider connected")
    private val subscription: Disposable

    init {
        border = JBUI.Borders.empty(12)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(heading("Insight Chat"), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
                add(JButton("Open dashboard").apply { addActionListener { openDashboard(project) } })
                add(clear)
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(status, BorderLayout.NORTH)
            add(JBScrollPane(input), BorderLayout.CENTER)
            add(send, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        send.addActionListener { submit() }
        clear.addActionListener { service.clear() }
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl ENTER"), "send")
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("meta ENTER"), "send")
        input.actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = submit()
        })
        subscription = service.subscribe { render() }
        render()
    }

    private fun submit() {
        val text = input.text
        if (text.isBlank() || service.busy) return
        input.text = ""
        service.send(text)
    }

    private fun render() {
        transcript.removeAll()
        service.messages.forEach { message ->
            transcript.add(JPanel(BorderLayout(0, 6)).apply {
                border = JBUI.Borders.empty(8)
                add(heading(if (message.role == "user") "You" else "Insight · demo", 13f), BorderLayout.NORTH)
                add(JBTextArea(message.text).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    isOpaque = false
                    columns = 24
                }, BorderLayout.CENTER)
                alignmentX = LEFT_ALIGNMENT
            })
        }
        send.isEnabled = !service.busy
        clear.isEnabled = !service.busy
        status.text = if (service.busy) "Preparing response…" else "Local demo · Ctrl/⌘+Enter to send"
        transcript.revalidate()
        transcript.repaint()
        SwingUtilities.invokeLater { if (!disposed) scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum }
    }

    private var disposed = false
    override fun dispose() {
        disposed = true
        subscription.dispose()
    }
}
