package dev.insight.board.ui

import com.intellij.openapi.Disposable
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.agent.ChatRole
import dev.insight.chat.ChatSession
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * The board editor's chat beside the board, modeled on the wall's chat window: the transcript of [session] (tool calls
 * as short lines), an input that sends on Enter (Shift+Enter breaks the line) and Stop while a reply runs. EDT only.
 */
internal class BoardChatPanel(private val session: ChatSession) : JPanel(BorderLayout(0, JBUI.scale(6))), Disposable {
    private val transcript = WidthTracking().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private val scroll = JBScrollPane(transcript).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    val input = JBTextArea(3, 0).apply {
        name = "board-chat-input"
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        emptyText.text = "Ask for a board, or about a block…"
        border = JBUI.Borders.empty(4)
    }
    private val send = JButton("Send").apply { isFocusable = false }
    private val stop = JButton("Stop").apply { isFocusable = false }
    private val status = JBLabel("", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER).apply {
        minimumSize = Dimension(0, preferredSize.height)
    }
    private val subscription = session.subscribe { render() }

    init {
        name = "board-chat"
        border = JBUI.Borders.empty(6, 8)
        add(JBLabel("Board chat").apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(JBScrollPane(input).apply { border = JBUI.Borders.customLine(JBColor.border(), 1) }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(status, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                    isOpaque = false
                    add(stop)
                    add(send)
                }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        send.addActionListener { submit() }
        stop.addActionListener { session.stop() }
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "board-chat-send")
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        input.actionMap.put("board-chat-send", object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = submit() })
        render()
    }

    /** Leaves [text] in the input with the caret at its end and focuses it, for the reader to finish the question. */
    fun draft(text: String) {
        input.text = text
        input.caretPosition = input.document.length
        input.requestFocusInWindow()
    }

    private fun submit() {
        val text = input.text
        if (text.isBlank() || session.busy) return
        input.text = ""
        session.send(text)
    }

    private fun render() {
        transcript.removeAll()
        session.messages.forEach { message ->
            transcript.add(when (message.role) {
                ChatRole.TOOL -> JBLabel("Tool · ${message.text}", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER).apply {
                    border = JBUI.Borders.empty(2, 4)
                    toolTipText = message.toolCall?.let { "${it.name}(${it.arguments})" }
                }
                ChatRole.NOTICE -> text(message.text).apply { foreground = UIUtil.getContextHelpForeground() }
                else -> JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(4)
                    add(JBLabel(if (message.role == ChatRole.USER) "You" else "Insight").apply {
                        font = font.deriveFont(Font.BOLD)
                        if (message.role == ChatRole.USER) foreground = USER
                    }, BorderLayout.NORTH)
                    add(text(message.text), BorderLayout.CENTER)
                }
            }.apply { alignmentX = LEFT_ALIGNMENT })
        }
        val busy = session.busy
        send.isEnabled = !busy
        stop.isEnabled = busy
        status.text = if (busy) "Composing…" else session.backendLabel
        status.toolTipText = session.backendLabel
        transcript.revalidate()
        transcript.repaint()
        SwingUtilities.invokeLater { scroll.verticalScrollBar.let { it.value = it.maximum } }
    }

    private fun text(value: String) = JBTextArea(value).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = UIUtil.getLabelFont()
        border = JBUI.Borders.empty(2, 4)
    }

    override fun dispose() {
        subscription.dispose()
        transcript.removeAll()
    }

    /** Lays out its rows at the viewport width, so wrapped replies never scroll sideways. */
    private class WidthTracking : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    private companion object {
        val USER = JBColor(0x325ABE, 0x78A0F0)
    }
}
