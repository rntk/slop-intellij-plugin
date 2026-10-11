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
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.dashboard.openDashboard
import dev.insight.ui.heading
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
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
    private val stop = JButton("Stop")
    private val clear = JButton("New chat")
    private val status = JLabel()
    private val subscription: Disposable

    init {
        border = JBUI.Borders.empty(12)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(heading("Insight Chat"), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
                add(JButton("Open dashboard").apply { addActionListener { openDashboard(project) } })
                add(clear)
                add(JButton("LLM settings").apply {
                    addActionListener {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, ChatSettingsConfigurable::class.java)
                        render()
                    }
                })
            }, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 8)).apply {
            add(status, BorderLayout.NORTH)
            add(JBScrollPane(input), BorderLayout.CENTER)
            add(JPanel(GridLayout(1, 2, JBUI.scale(8), 0)).apply {
                add(stop)
                add(send)
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        send.addActionListener { submit() }
        stop.addActionListener { service.stop() }
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
            if (message.role == ChatMessage.TOOL) {
                // Tool calls are compact progress lines rather than full messages.
                transcript.add(JBLabel("Tool · ${message.text}", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER).apply {
                    border = JBUI.Borders.empty(2, 8)
                    alignmentX = LEFT_ALIGNMENT
                })
                return@forEach
            }
            transcript.add(JPanel(BorderLayout(0, 6)).apply {
                border = JBUI.Borders.empty(8)
                add(heading(if (message.role == ChatMessage.USER) "You" else "Insight", 13f), BorderLayout.NORTH)
                add(JBTextArea(message.text).apply {
                    isEditable = false
                    lineWrap = true
                    wrapStyleWord = true
                    isOpaque = false
                    columns = 24
                    if (message.role == ChatMessage.NOTICE) foreground = UIUtil.getContextHelpForeground()
                }, BorderLayout.CENTER)
                alignmentX = LEFT_ALIGNMENT
            })
        }
        send.isEnabled = !service.busy
        stop.isEnabled = service.busy
        clear.isEnabled = !service.busy
        status.text = if (service.busy) "Preparing response…" else "${service.backendLabel} · Ctrl/⌘+Enter to send"
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
