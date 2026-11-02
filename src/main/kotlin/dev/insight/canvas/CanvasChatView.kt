package dev.insight.canvas

import com.intellij.openapi.Disposable
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.chat.ChatBackendFactory
import dev.insight.chat.ChatSession
import dev.insight.ui.zoomScaled
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The chat tabs of card [cardId], or of the root file for null: each tab's transcript, draft and running reply, and
 * the input. [history] and [ask] are the bodies of the Chat and Ask sections; [onTitle] receives the Chat section's
 * title as messages arrive. Histories and drafts are kept in the model; replies keep running when the tab changes.
 */
internal class CanvasChatView(
    private val host: CanvasCardHost,
    private val cardId: String?,
    initial: CanvasChatHolder,
    private val welcome: () -> String,
    placeholder: String,
    private val onTitle: (String) -> Unit,
) : Disposable {
    private var holder: CanvasChatHolder = initial
    private val conversations = linkedMapOf<String, Pair<ChatSession, Disposable>>()
    private val chat: ChatSession get() = conversation(holder.activeChat)
    private var syncing = false
    // Wrapped, not scrolled: Darcula's scroll layout recurses until the stack overflows when the selected tab is wider
    // than the strip, as on a zoomed-out card whose tab insets do not shrink with the zoom.
    private val tabs = host.zoomFont(JTabbedPane(JTabbedPane.TOP, JTabbedPane.WRAP_TAB_LAYOUT))
    private val body = JPanel(StackLayout { scaled(4) }).apply { isOpaque = false }
    private val closeChat = host.zoomFont(ActionLink("Close chat") { host.model.removeChat(cardId, holder.activeChatId) }.apply {
        name = "canvas-close-chat"
        toolTipText = "Close the selected conversation and stop its reply"
    })
    private val transcript = JPanel(StackLayout { scaled(6) }).apply { isOpaque = false }
    private val busy = host.zoomFont(JLabel("Working…", AnimatedIcon.Default(), JLabel.LEFT).apply {
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    })

    val history: JComponent = JPanel(StackLayout { scaled(4) }).apply {
        isOpaque = false
        add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(host.zoomFont(ActionLink("New chat") { host.model.addChat(cardId) }.apply { name = "canvas-new-chat" }))
            add(closeChat)
        })
        add(tabs)
    }

    val input = host.zoomFont(JBTextArea(initial.draft, 2, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        emptyText.text = placeholder
        border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(4))
    })
    private val send = host.zoomFont(JButton("Send").apply { isFocusable = false })
    private val stop = host.zoomFont(JButton("Stop").apply { isFocusable = false })

    val ask: JComponent = JPanel(StackLayout { scaled(4) }).apply {
        isOpaque = false
        add(input)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(stop)
            add(send)
        })
    }

    init {
        body.add(transcript)
        body.add(busy)
        tabs.addChangeListener {
            if (!syncing && tabs.selectedIndex >= 0) host.model.selectChat(cardId, holder.chats[tabs.selectedIndex].id)
        }
        send.addActionListener { submit() }
        stop.addActionListener { chat.stop() }
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "canvas-send")
        input.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        input.actionMap.put("canvas-send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = submit()
        })
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private var height = input.preferredSize.height
            private fun changed() {
                if (!syncing) host.model.setDraft(cardId, input.text, holder.activeChatId)
                // Typing within a line keeps the size; lay out the canvas only when the input grows or shrinks.
                val next = input.preferredSize.height
                if (next != height) {
                    height = next
                    host.cardResized()
                }
            }
        })
        sync()
    }

    /**
     * Shows [next], the holder's state in the model; returns false when only a draft the input already shows changed,
     * so nothing needs to be drawn again.
     */
    fun update(next: CanvasChatHolder): Boolean {
        val draftOnly = holder.activeChatId == next.activeChatId && holder.chats.size == next.chats.size &&
            holder.chats.zip(next.chats).all { (a, b) -> a.copy(draft = b.draft) == b }
        holder = next
        if (draftOnly) return false
        sync()
        return true
    }

    /** Asks the selected chat [text]. */
    fun send(text: String) = chat.send(text)

    private fun submit() {
        val text = input.text
        if (text.isBlank() || chat.busy) return
        input.text = ""
        send(text)
    }

    private fun conversation(saved: CanvasChat): ChatSession = conversations.getOrPut(saved.id) {
        val session = ChatSession(host.project, host.scope,
            backendFactory = ChatBackendFactory { _ -> host.services.chatBackend(host.actions, cardId) },
            welcome = welcome(), restored = saved.messages.toChatMessages())
        session to session.subscribe {
            host.model.setMessages(cardId, savedMessages(session.messages), saved.id)
            if (holder.activeChatId == saved.id) render()
        }
    }.first

    private fun sync() {
        val removed = conversations.keys.filter { key -> holder.chats.none { it.id == key } }
        removed.forEach { key -> conversations.remove(key)?.let { (session, subscription) ->
            subscription.dispose()
            session.discard()
        } }
        syncing = true
        try {
            closeChat.isEnabled = holder.chats.size > 1
            val ids = holder.chats.map { it.id }
            val shownIds = (0 until tabs.tabCount).map { tabs.getComponentAt(it).name }
            if (shownIds != ids) {
                tabs.removeAll()
                holder.chats.forEach { saved ->
                    tabs.addTab(saved.title, JPanel(BorderLayout()).apply { isOpaque = false; name = saved.id })
                }
            }
            tabs.selectedIndex = holder.chats.indexOfFirst { it.id == holder.activeChatId }
            (tabs.selectedComponent as JPanel).add(body, BorderLayout.CENTER)
            if (input.text != holder.draft) input.text = holder.draft
        } finally {
            syncing = false
        }
        render()
    }

    private fun render() {
        transcript.removeAll()
        val lines = canvasTranscript(chat.messages)
        for (line in lines) {
            transcript.add(when (line.kind) {
                CanvasChatLineKind.TOOL -> host.zoomFont(JLabel("${line.label} · ${line.text}").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    toolTipText = line.text
                }, JBUI.Fonts.smallFont())
                CanvasChatLineKind.NOTICE -> host.zoomFont(wrappedText(line.text).apply { foreground = UIUtil.getContextHelpForeground() })
                else -> JPanel(StackLayout { scaled(2) }).apply {
                    isOpaque = false
                    add(host.zoomFont(JLabel(line.label).apply {
                        foreground = if (line.kind == CanvasChatLineKind.USER) USER else UIUtil.getLabelForeground()
                    }, UIUtil.getLabelFont().deriveFont(Font.BOLD)))
                    add(host.zoomFont(wrappedText(line.text)))
                }
            })
        }
        val count = lines.count { it.kind == CanvasChatLineKind.USER || it.kind == CanvasChatLineKind.ASSISTANT }
        onTitle(if (count == 0) "Chat" else "Chat · $count ${if (count == 1) "message" else "messages"}")
        busy.isVisible = chat.busy
        send.isEnabled = !chat.busy
        stop.isEnabled = chat.busy
        transcript.revalidate()
        history.revalidate()
        history.repaint()
        host.cardResized()
    }

    override fun dispose() {
        conversations.values.forEach { (session, subscription) ->
            subscription.dispose()
            session.discard()
        }
        conversations.clear()
    }

    private fun scaled(value: Int) = zoomScaled(value, host.zoom)

    private companion object {
        val USER = JBColor(Color(50, 90, 190), Color(120, 160, 240))
    }
}

/** A titled part of card [cardId], or of the root file for null, that folds with its toggle or title as saved in the model. */
internal open class CanvasFoldSection(private val host: CanvasCardHost, private val cardId: String?, val section: CardSection,
    title: String, val body: JComponent) : JPanel(StackLayout { zoomScaled(4, host.zoom) }) {
    private val toggle = canvasFoldToggle(host, cardId, section)
    val title: JLabel = host.zoomFont(JLabel(title).apply { foreground = UIUtil.getContextHelpForeground() },
        UIUtil.getLabelFont().deriveFont(Font.BOLD))
    /** The toggle and title, in one row. */
    protected val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        add(toggle)
        add(this@CanvasFoldSection.title.onLeftClick { titleClicked() })
    }

    init {
        isOpaque = false
        add(header)
        add(body)
    }

    protected open fun titleClicked() {
        host.model.toggleFolded(cardId, section)
    }

    fun fold(folded: Boolean) {
        body.isVisible = !folded
        toggle.text = canvasFoldGlyph(!folded)
    }
}

/**
 * The [CardSection.SOURCE] part of card [cardId], or of the root file for null, showing [source]. Given a diff, its title
 * becomes a tab beside a Diff tab and the section shows one of them at a time, so the card grows by only one. Clicking
 * the shown tab folds the section; clicking the other shows it, unfolded. The source is shown first.
 */
internal class CanvasSourceSection(private val host: CanvasCardHost, private val cardId: String?, title: String,
    private val source: JComponent) : CanvasFoldSection(host, cardId, CardSection.SOURCE, title,
        JPanel(BorderLayout()).apply { isOpaque = false }) {
    val diffTab: JLabel = host.zoomFont(JLabel().apply { isVisible = false }, UIUtil.getLabelFont().deriveFont(Font.BOLD))
        .onLeftClick { if (showsDiff) host.model.toggleFolded(cardId, section) else select(diff = true) }
    private var diff: JComponent? = null
    /** Whether the Diff tab is shown rather than the source. */
    var showsDiff = false
        private set

    init {
        header.add(diffTab)
        showTab()
    }

    /** Offers [component] in a Diff tab titled [tabTitle]; null removes the tab and shows the source. */
    fun setDiff(component: JComponent?, tabTitle: String) {
        diff = component
        diffTab.text = tabTitle
        if (component == null) showsDiff = false
        showTab()
    }

    override fun titleClicked() = if (showsDiff) select(diff = false) else super.titleClicked()

    private fun select(diff: Boolean) {
        showsDiff = diff
        showTab()
        host.model.setFolded(cardId, section, false)
        revalidate()
        repaint()
        host.cardResized()
    }

    private fun showTab() {
        val shown = diff?.takeIf { showsDiff } ?: source
        if ((body as JPanel).components.singleOrNull() !== shown) {
            body.removeAll()
            body.add(shown)
        }
        val tabbed = diff != null
        diffTab.isVisible = tabbed
        listOf(title to !showsDiff, diffTab to showsDiff).forEach { (tab, selected) ->
            tab.foreground = if (tabbed && selected) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
            tab.border = when {
                !tabbed -> null
                selected -> JBUI.Borders.customLineBottom(TAB_UNDERLINE)
                else -> JBUI.Borders.emptyBottom(1)
            }
        }
    }

    private companion object {
        val TAB_UNDERLINE = JBColor(Color(76, 112, 220), Color(110, 150, 240))
    }
}

/** The ▾ toggle that folds [section] of card [cardId], or of the root file for null. */
internal fun canvasFoldToggle(host: CanvasCardHost, cardId: String?, section: CardSection): JLabel =
    host.zoomFont(JLabel(canvasFoldGlyph(true)).apply {
        toolTipText = "Fold or unfold"
        name = "canvas-fold-${section.name.lowercase()}"
    }).onLeftClick { host.model.toggleFolded(cardId, section) }

internal fun canvasFoldGlyph(open: Boolean) = if (open) "▾" else "▸"
