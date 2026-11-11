package dev.insight.review.wall

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.agent.ChatBackend
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import dev.insight.chat.ChatBackendFactory
import dev.insight.chat.ChatSession
import kotlinx.coroutines.CoroutineScope
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

/**
 * A wall's chats, by file path, and under [WALL_CANVAS_CHAT] the wall's own chat. Each file keeps its own independent
 * conversations, oldest first, and which one is shown; they outlive the wall's layouts and scopes, so a file's chats are there
 * whenever its block is. The client's [backend] decides what a chat knows and can read. EDT.
 */
internal class WallChats(
    private val project: Project,
    private val scope: CoroutineScope,
    /** A backend for one turn of a chat about the file at path. */
    private val backend: (String) -> ChatBackend,
    /** The notice a new chat about the file at path opens with. */
    private val welcome: (String) -> String,
    /** What a chat under the key is about, as its progress title names it; the file's name by default. */
    private val subject: (String) -> String = { it.substringAfterLast('/') },
) : Disposable {
    private class Thread(val sessions: MutableList<ChatSession>, var active: Int)
    private val threads = mutableMapOf<String, Thread>()
    private val subscriptions = IdentityHashMap<ChatSession, Disposable>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var disposed = false

    /** The conversations about [path], oldest first; the first is started on demand. */
    fun sessions(path: String): List<ChatSession> = thread(path).sessions.toList()

    /** The conversation shown for [path]. */
    fun active(path: String): ChatSession = thread(path).let { it.sessions[it.active] }

    fun activeIndex(path: String): Int = thread(path).active

    /** Whether [path] has a conversation with a question in it, or one that is answering. */
    fun hasConversation(path: String): Boolean =
        threads[path]?.sessions?.any { session -> session.busy || session.messages.any { it.role == ChatRole.USER } } == true

    /** Whether a reply about [path] is running. */
    fun busy(path: String): Boolean = threads[path]?.sessions?.any { it.busy } == true

    /** Starts and shows a new conversation about [path]; an unused shown one is kept rather than piling up empty chats. */
    fun newChat(path: String): ChatSession {
        val thread = thread(path)
        val shown = thread.sessions[thread.active]
        if (!shown.busy && shown.messages.none { it.role == ChatRole.USER }) return shown
        thread.sessions += create(path)
        thread.active = thread.sessions.lastIndex
        changed(path)
        return thread.sessions[thread.active]
    }

    fun select(path: String, index: Int) {
        val thread = thread(path)
        if (index !in thread.sessions.indices || index == thread.active) return
        thread.active = index
        changed(path)
    }

    /** Deletes the shown conversation about [path], stopping its reply; the last one is replaced by a fresh chat. */
    fun deleteActive(path: String) {
        val thread = thread(path)
        release(thread.sessions.removeAt(thread.active))
        if (thread.sessions.isEmpty()) thread.sessions += create(path)
        thread.active = thread.active.coerceAtMost(thread.sessions.lastIndex)
        changed(path)
    }

    /** Stops every running reply, as when the change under them is replaced. */
    fun stop() = threads.values.flatMap { it.sessions }.filter { it.busy }.forEach { it.stop() }

    /** Calls [listener] with the path whose chats changed: a message, a reply starting or ending, or a chat added or removed. */
    fun subscribe(listener: (String) -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    private fun thread(path: String): Thread {
        check(!disposed) { "The wall's chats are disposed" }
        return threads.getOrPut(path) { Thread(mutableListOf(create(path)), 0) }
    }

    private fun create(path: String): ChatSession {
        val name = subject(path)
        val session = object : ChatSession(project, scope, backendFactory = ChatBackendFactory { _ -> backend(path) }, welcome = welcome(path)) {
            override fun replyProgressTitle(text: String) = "Answering about $name"
        }
        subscriptions[session] = session.subscribe { changed(path) }
        return session
    }

    private fun release(session: ChatSession) {
        subscriptions.remove(session)?.dispose()
        session.discard()
    }

    private fun changed(path: String) = listeners.forEach { it(path) }

    override fun dispose() {
        disposed = true
        threads.values.flatMap { it.sessions }.forEach(::release)
        threads.clear()
        listeners.clear()
    }
}

/** A chat window's accessible title, context tooltip, input placeholder, and action tips. */
internal data class WallChatText(
    val title: String,
    val titleTooltip: String,
    val placeholder: String,
    val historyTooltip: String,
    val newChatTooltip: String,
)

/**
 * The chat window a wall block unfolds, or the wall's own chat about all its files: the key's conversations to pick from, the
 * shown one's transcript and an input. Enter sends, Shift+Enter breaks the line and Escape folds the window; each conversation
 * keeps its own draft.
 */
internal class WallChatPanel(
    /** The key of the conversations in [chats]: a file's path, or [WALL_CANVAS_CHAT]. */
    private val path: String,
    private val chats: WallChats,
    private val words: WallChatText,
    /** The prefix of the window's component names, as the wall's [WallLabels.id]. */
    id: String,
    /** Folds the window. */
    private val onClose: () -> Unit,
    /** Brings the window in front of the blocks it overlaps. */
    private val onRaise: () -> Unit = {},
) : JPanel(BorderLayout(0, JBUI.scale(6))), Disposable {
    private val history = ComboBox<ChatSession>().apply {
        name = "$id-chat-history"
        toolTipText = words.historyTooltip
        renderer = SimpleListCellRenderer.create("") { session ->
            val count = session.messages.count { it.role == ChatRole.USER }
            wallChatTitle(session.messages) + if (count > 1) " · $count questions" else ""
        }
        isSwingPopup = true
        minimumSize = Dimension(0, preferredSize.height)
        accessibleContext.accessibleName = "Chat history"
    }
    private val transcript = WidthTracking().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private val scroll = JBScrollPane(transcript).apply {
        border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 1, 0)
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    val input = JBTextArea(3, 0).apply {
        name = "$id-chat-input"
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        emptyText.text = words.placeholder
        border = JBUI.Borders.empty(4)
    }
    private val send = JButton("Send").apply { isFocusable = false }
    private val stop = JButton("Stop").apply { isFocusable = false }
    private val delete = iconButton(AllIcons.Actions.GC, "Delete the shown chat and stop its reply") { chats.deleteActive(path) }.apply {
        name = "$id-delete-chat"
    }
    private val status = JBLabel("", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER).apply {
        name = "$id-chat-provider"
        minimumSize = Dimension(0, preferredSize.height)
    }
    /** Each conversation's unsent text, kept while another one is shown. */
    private val drafts = IdentityHashMap<ChatSession, String>()
    private var shown: ChatSession? = null
    private var syncing = false
    private val subscription = chats.subscribe { changed -> if (changed == path) render() }

    init {
        name = "$id-chat:$path"
        isOpaque = false
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8, 10)
        getAccessibleContext().accessibleName = words.title
        toolTipText = words.titleTooltip
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(history, BorderLayout.CENTER)
            add(JPanel(GridLayout(1, 0, JBUI.scale(2), 0)).apply {
                isOpaque = false
                add(iconButton(AllIcons.General.Add, words.newChatTooltip) { chats.newChat(path); input.requestFocusInWindow() }.apply {
                    name = "$id-new-chat"
                    accessibleContext.accessibleName = "New chat"
                })
                add(delete)
                add(iconButton(AllIcons.Actions.Close, "Fold the chat (Esc)") { onClose() }.apply {
                    name = "$id-hide-chat"
                    accessibleContext.accessibleName = "Hide chat"
                })
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
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
        history.addActionListener { if (!syncing && history.selectedIndex >= 0) chats.select(path, history.selectedIndex) }
        send.addActionListener { submit() }
        stop.addActionListener { chats.active(path).stop() }
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "wall-chat-send")
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"), "wall-chat-hide")
        input.actionMap.put("wall-chat-send", object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = submit() })
        input.actionMap.put("wall-chat-hide", object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) = onClose() })
        input.addFocusListener(object : FocusAdapter() { override fun focusGained(e: FocusEvent) = onRaise() })
        // The window takes its own presses, so they neither pan the wall nor reach the blocks under it.
        addMouseListener(object : MouseAdapter() { override fun mousePressed(e: MouseEvent) = onRaise() })
        render()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            g2.color = background
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    private fun iconButton(icon: Icon, tooltip: String, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tooltip
        accessibleContext.accessibleName = tooltip
        putClientProperty("JButton.buttonType", "toolBarButton")
        preferredSize = JBUI.size(24, 24)
        addActionListener { action() }
    }

    /** Asks the shown chat [text]. */
    fun send(text: String) = chats.active(path).send(text)

    private fun submit() {
        val session = chats.active(path)
        val text = input.text
        if (text.isBlank() || session.busy) return
        input.text = ""
        drafts.remove(session)
        session.send(text)
    }

    private fun render() {
        val sessions = chats.sessions(path)
        val active = chats.active(path)
        syncing = true
        try {
            if ((0 until history.itemCount).map(history::getItemAt) != sessions) history.model = DefaultComboBoxModel(sessions.toTypedArray())
            history.selectedIndex = chats.activeIndex(path)
            history.repaint()
        } finally {
            syncing = false
        }
        if (shown !== active) {
            shown?.let { previous -> if (sessions.any { it === previous }) drafts[previous] = input.text else drafts.remove(previous) }
            input.text = drafts.remove(active).orEmpty()
            shown = active
        }
        transcript.removeAll()
        active.messages.forEach { message ->
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
        val busy = active.busy
        send.isEnabled = !busy
        stop.isEnabled = busy
        delete.isEnabled = !(sessions.size == 1 && active.messages.none { it.role == ChatRole.USER })
        status.text = if (busy) "Answering…" else active.backendLabel
        status.toolTipText = active.backendLabel
        status.accessibleContext.accessibleName = status.text
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

/** The key of the wall's own chat about all its files, in the [WallChats] that hold it; never a file path. */
internal const val WALL_CANVAS_CHAT = "\u0000wall"

/** A chat window's width on the wall, which stays at screen size whatever the zoom so it is always readable. */
internal fun wallChatWidth(): Int = JBUI.scale(400)

/** A chat window's height beside a block painted [blockHeight] tall: the block's, kept between a usable minimum and a cap. */
internal fun wallChatHeight(blockHeight: Int): Int = blockHeight.coerceIn(JBUI.scale(340), JBUI.scale(560))

/** The wall's own chat window's height when [room] is free in the shown part of the wall: as tall as it allows, within bounds. */
internal fun wallCanvasChatHeight(room: Int): Int = room.coerceIn(JBUI.scale(340), JBUI.scale(640))

/** A conversation's name in a chat's history: its first question on one line, clipped; "New chat" before one is asked. */
internal fun wallChatTitle(messages: List<ChatMessage>, max: Int = 36): String {
    val first = messages.firstOrNull { it.role == ChatRole.USER }?.text?.replace(Regex("\\s+"), " ")?.trim() ?: return "New chat"
    return if (first.length <= max) first else first.take(max - 1).trimEnd() + "…"
}
