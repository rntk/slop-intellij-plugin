package dev.insight.canvas

import com.intellij.openapi.Disposable
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
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
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The chat tabs of card [cardId], or of the root file for null: each tab's transcript, draft and running reply, and
 * the input. The transcript fills [section], whose header gains a + for a new tab and whose title counts the messages;
 * the section shows only once there is a conversation, so a fresh card offers just the [ask] input. Tabs show only
 * when there are several. Histories and drafts are kept in the model; replies keep running when the tab changes.
 */
internal class CanvasChatView(
    private val host: CanvasCardHost,
    private val cardId: String?,
    initial: CanvasChatHolder,
    private val welcome: () -> String,
    placeholder: String,
    private val section: CanvasFoldSection,
) : Disposable {
    private var holder: CanvasChatHolder = initial
    private val conversations = linkedMapOf<String, Pair<ChatSession, Disposable>>()
    private val chat: ChatSession get() = conversation(holder.activeChat)
    private var syncing = false
    /** One underlined chip per chat, like the Source and Diff tabs; the selected chat's chip also offers ×. */
    private val tabs = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
        isOpaque = false
        name = "canvas-chat-tabs"
    }
    private val closeChat = canvasGlyphButton(host, "×", "Close the selected conversation and stop its reply", "canvas-close-chat") {
        host.model.removeChat(cardId, holder.activeChatId)
    }
    private val transcript = JPanel(StackLayout { scaled(8) }).apply { isOpaque = false }
    private val busy = host.zoomFont(JLabel("Working…", AnimatedIcon.Default(), JLabel.LEFT).apply {
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    })

    /** The body of the Chat section: the tabs, when there are several, then the selected tab's transcript. */
    val history: JComponent = JPanel(StackLayout { scaled(6) }).apply {
        isOpaque = false
        add(tabs)
        add(transcript)
        add(busy)
    }

    val input = host.zoomFont(object : JBTextArea(initial.draft, 1, 0) {
        // False while the superclass constructor sets the first font, before the placeholder exists.
        private var built = true

        // The placeholder follows the zoom with the text.
        override fun setFont(font: Font?) {
            super.setFont(font)
            if (built && font != null) emptyText.setFont(font)
        }
    }.apply {
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        emptyText.text = placeholder
        toolTipText = "Enter sends · Shift+Enter adds a line. ${CANVAS_WELCOME_TOOLS.trim()}"
        border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(4, 6))
    })
    /** Sends the draft, or stops the running reply. */
    private val sendOrStop = host.zoomFont(ActionLink("Send") { if (chat.busy) chat.stop() else submit() }.apply {
        name = "canvas-send"
    })

    /** The input, with Send (Stop while a reply runs) beside it. */
    val ask: JComponent = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(input, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(sendOrStop, BorderLayout.SOUTH)
        }, BorderLayout.EAST)
    }

    init {
        section.body.let { it as JPanel }.add(history)
        section.addToHeader(canvasGlyphButton(host, "+", "New chat", "canvas-new-chat") { host.model.addChat(cardId) })
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

    /** Whether a reply runs in any of the tabs. */
    val replying: Boolean get() = conversations.values.any { it.first.busy }

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
            tabs.removeAll()
            holder.chats.forEach { saved ->
                val selected = saved.id == holder.activeChatId
                tabs.add(host.zoomFont(JLabel(saved.title).apply {
                    name = "canvas-chat-tab"
                    foreground = if (selected) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
                    border = if (selected) JBUI.Borders.customLineBottom(TAB_UNDERLINE) else JBUI.Borders.emptyBottom(1)
                }, UIUtil.getLabelFont().deriveFont(Font.BOLD)).onLeftClick { if (!selected) host.model.selectChat(cardId, saved.id) })
                if (selected) tabs.add(closeChat)
            }
            tabs.isVisible = holder.chats.size > 1
            if (input.text != holder.draft) input.text = holder.draft
        } finally {
            syncing = false
        }
        render()
    }

    private fun render() {
        transcript.removeAll()
        // The welcome notice repeats what the card shows; the input's placeholder and tooltip say it instead.
        val lines = canvasTranscript(chat.messages.drop(1))
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
                        foreground = if (line.kind == CanvasChatLineKind.USER) USER else UIUtil.getContextHelpForeground()
                    }, JBUI.Fonts.smallFont().deriveFont(Font.BOLD)))
                    add(host.zoomFont(wrappedText(line.text)))
                }
            })
        }
        val count = lines.count { it.kind == CanvasChatLineKind.USER || it.kind == CanvasChatLineKind.ASSISTANT }
        section.title.text = if (count == 0) "Chat" else "Chat · $count ${if (count == 1) "message" else "messages"}"
        section.isVisible = lines.isNotEmpty() || chat.busy || holder.chats.size > 1
        transcript.isVisible = lines.isNotEmpty()
        busy.isVisible = chat.busy
        sendOrStop.text = if (chat.busy) "Stop" else "Send"
        sendOrStop.toolTipText = if (chat.busy) "Stop the reply" else "Send (Enter)"
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
        val TAB_UNDERLINE = JBColor(Color(76, 112, 220), Color(110, 150, 240))
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

    /** Adds [component] after the title, e.g. a small action for this part. */
    fun addToHeader(component: JComponent) {
        header.add(component)
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
    private var source: JComponent) : CanvasFoldSection(host, cardId, CardSection.SOURCE, title,
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

    /** Shows [component] as the source from now on, with [diff] (titled [tabTitle]) in the Diff tab as [setDiff] does. */
    fun setSource(component: JComponent, diff: JComponent?, tabTitle: String) {
        source = component
        setDiff(diff, tabTitle)
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
