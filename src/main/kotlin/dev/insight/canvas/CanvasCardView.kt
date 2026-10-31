package dev.insight.canvas

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a card needs from the canvas it sits on. */
internal interface CanvasCardHost {
    val project: Project
    val model: CanvasSessionModel
    val controller: CanvasSessionController
    val scope: CoroutineScope
    val services: CanvasSessionServices
    val sourceListener: CanvasSourcePane.Listener
    val zoom: Double
    /** Shows [component] in [base] (its own font by default) at the current zoom, now and after each zoom change. */
    fun <T : JComponent> zoomFont(component: T, base: Font = component.font): T
    /** The card's size may have changed: lay out the canvas again. */
    fun cardResized()
    fun close(cardId: String)
    fun navigate(source: CanvasSource?, path: String, line: Int)
}

/**
 * One card on the canvas: a header with the card's kind, name and place, then its summary, source, chat history and
 * chat input, each folded with its toggle as saved in the card. The card's chat keeps its history in the model.
 */
internal class CanvasCardView(private val host: CanvasCardHost, initial: CanvasCard) : JPanel(StackLayout { zoomScaled(4, host.zoom) }), Disposable {
    val id = initial.id
    var card: CanvasCard = initial
        private set
    private var rendered = false
    val source: CanvasSource? = host.controller.source(id)
    /** The card's source viewer; null when it has no source text. */
    val sourcePane: CanvasSourcePane? = source?.takeIf { it.text.isNotEmpty() }?.let { CanvasSourcePane(host.project, id, it, host.sourceListener) }
    /** Whether the card is drawn emphasised, after it was revealed. */
    var highlighted = false
        set(value) {
            field = value
            applyZoom()
            repaint()
        }

    private val metaToggle = toggle(CardSection.META)
    private val metaLabel = host.zoomFont(JLabel().apply { foreground = UIUtil.getContextHelpForeground() })
    private val notice = host.zoomFont(wrappedText(source?.notice.orEmpty()).apply {
        foreground = NOTICE
        isVisible = !source?.notice.isNullOrBlank()
    })
    private val meta = JPanel(StackLayout { scaled(2) }).apply {
        isOpaque = false
        add(metaLabel)
        add(notice)
    }

    private val summaryText = host.zoomFont(wrappedText(""))
    private val summaryStatus = host.zoomFont(JLabel().apply { foreground = UIUtil.getContextHelpForeground() })
    private val summaryRetry = host.zoomFont(ActionLink("Retry") { requestSummary(force = true) })
    private var summaryJob: Job? = null
    private val summary = Section(CardSection.SUMMARY, "Summary", JPanel(StackLayout { scaled(2) }).apply {
        isOpaque = false
        add(summaryText)
        add(summaryStatus)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(summaryRetry) })
    })

    private val sourceSection = Section(CardSection.SOURCE, canvasSourceTitle(initial), sourcePane
        ?: host.zoomFont(JLabel(if (initial.kind == CardKind.SYMBOL) "No source to show." else "No text.").apply {
            foreground = UIUtil.getContextHelpForeground()
        }))

    private val chat: ChatSession = ChatSession(host.project, host.scope,
        backendFactory = ChatBackendFactory { _ -> host.services.chatBackend(host.model, id) },
        welcome = canvasWelcome(initial), restored = initial.messages.toChatMessages())
    private val transcript = JPanel(StackLayout { scaled(6) }).apply { isOpaque = false }
    private val busy = host.zoomFont(JLabel("Working…", AnimatedIcon.Default(), JLabel.LEFT).apply {
        foreground = UIUtil.getContextHelpForeground()
        isVisible = false
    })
    private val history = Section(CardSection.CHAT, "Chat", JPanel(StackLayout { scaled(4) }).apply {
        isOpaque = false
        add(transcript)
        add(busy)
    })

    val input = host.zoomFont(JBTextArea(initial.draft, 2, 0).apply {
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        emptyText.text = "Ask about this card… (Enter sends, Shift+Enter for a new line)"
        border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(JBColor.border()), JBUI.Borders.empty(4))
    })
    private val send = host.zoomFont(JButton("Send").apply { isFocusable = false })
    private val stop = host.zoomFont(JButton("Stop").apply { isFocusable = false })
    private val inputSection = Section(CardSection.INPUT, "Ask", JPanel(StackLayout { scaled(4) }).apply {
        isOpaque = false
        add(input)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(stop)
            add(send)
        })
    })
    private val chatSubscription: Disposable

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        name = "canvas-card-$id"
        add(header())
        add(meta)
        add(summary)
        add(sourceSection)
        add(history)
        add(inputSection)

        send.addActionListener { submit() }
        stop.addActionListener { chat.stop() }
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "canvas-send")
        input.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        input.actionMap.put("canvas-send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = submit()
        })
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private var height = input.preferredSize.height
            private fun changed() {
                host.model.setDraft(id, input.text)
                // Typing within a line keeps the card's size; lay out the canvas only when the input grows or shrinks.
                val next = input.preferredSize.height
                if (next != height) {
                    height = next
                    host.cardResized()
                }
            }
        })
        chatSubscription = chat.subscribe {
            host.model.setMessages(id, savedMessages(chat.messages))
            renderChat()
        }
        renderChat()
        update(initial)
        applyZoom()
        requestSummary(force = false)
    }

    private fun header(): JComponent = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(metaToggle, BorderLayout.WEST)
        add(host.zoomFont(JLabel(card.title, kindIcon(card), JLabel.LEFT).apply {
            toolTipText = "${canvasKindLabel(card)} ${card.title}"
        }, UIUtil.getLabelFont().deriveFont(Font.BOLD)), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(host.zoomFont(ActionLink("Source") { host.navigate(source, card.filePath, card.firstLine) }.apply {
                toolTipText = "Open ${card.location} in the editor"
                isEnabled = card.filePath.isNotEmpty()
            }))
            add(host.zoomFont(ActionLink("Close") { host.close(id) }.apply {
                toolTipText = "Close this card and the cards opened from it"
            }))
        }, BorderLayout.EAST)
    }

    /** Shows [next], the card's state in the model. */
    fun update(next: CanvasCard) {
        // The input already shows a typed draft; keystrokes need no card refresh or canvas layout.
        val draftOnly = rendered && card.copy(draft = next.draft) == next
        card = next
        if (draftOnly) return
        rendered = true
        metaLabel.text = "${canvasKindLabel(next)} · ${canvasCardMeta(next)}"
        metaLabel.toolTipText = metaLabel.text
        meta.isVisible = CardSection.META !in next.folded
        metaToggle.text = glyph(CardSection.META !in next.folded)
        listOf(summary, sourceSection, history, inputSection).forEach { it.fold(it.section in next.folded) }
        sourceSection.title.text = canvasSourceTitle(next)
        val pending = summaryJob?.isActive == true
        summaryText.text = next.summary.orEmpty()
        summaryText.isVisible = next.summary != null
        summaryStatus.text = when {
            pending -> "Summarizing…"
            next.summaryError != null -> next.summaryError
            next.summary == null && next.text.isBlank() -> "No source to summarize."
            else -> ""
        }
        summaryStatus.foreground = if (next.summaryError != null && !pending) NOTICE else UIUtil.getContextHelpForeground()
        summaryStatus.isVisible = summaryStatus.text.isNotEmpty()
        summaryRetry.text = if (next.summary != null) "Summarize again" else "Retry"
        summaryRetry.parent?.isVisible = !pending && next.text.isNotBlank() && (next.summaryError != null || next.summary != null)
        revalidate()
        repaint()
        host.cardResized()
    }

    /** Asks for the summary when the card has none yet, or again when [force]d; the result is saved in the model. */
    fun requestSummary(force: Boolean) {
        val current = host.model.card(id) ?: return
        if (summaryJob?.isActive == true || current.text.isBlank()) return
        if (!force && (current.summary != null || current.summaryError != null)) return
        val input = CanvasSummaryInput.of(current, host.model.session.rootPath, host.model.chain(id), language())
        summaryJob = host.scope.launch(Dispatchers.EDT) {
            try {
                val text = withContext(Dispatchers.IO) { CanvasSummarizer({ host.services.complete()(it) }).summarize(input) }
                host.model.setSummary(id, text)
            } catch (e: CanvasSummaryException) {
                LOG.info("Canvas card summary failed: ${e.message}", e.cause)
                host.model.setSummary(id, null, e.message)
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Canvas card summary failed", e)
                host.model.setSummary(id, null, "The summary failed: ${e.message ?: e.javaClass.simpleName}. See the IDE log.")
            } finally {
                summaryJob = null
                host.model.card(id)?.let(::update)
            }
        }
        update(current)
    }

    private fun language(): String? = source?.file?.fileType?.displayName?.takeIf { it.isNotBlank() }

    private fun submit() {
        val text = input.text
        if (text.isBlank() || chat.busy) return
        input.text = ""
        send(text)
    }

    /** Asks the card's chat [text]. */
    internal fun send(text: String) = chat.send(text)

    private fun renderChat() {
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
        history.title.text = if (count == 0) "Chat" else "Chat · $count ${if (count == 1) "message" else "messages"}"
        busy.isVisible = chat.busy
        send.isEnabled = !chat.busy
        stop.isEnabled = chat.busy
        transcript.revalidate()
        revalidate()
        repaint()
        host.cardResized()
    }

    /** Sizes beyond fonts, at the current zoom. */
    fun applyZoom() {
        val accent = if (highlighted) ACCENT else JBColor.border()
        border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent, if (highlighted) scaled(2) else scaled(1)),
            pixel(scaled(8)))
        sourcePane?.applyZoom(host.zoom)
    }

    /** The y of [anchor]'s line in this card, or the header's middle when the source is folded. */
    fun anchorY(anchor: CardAnchor): Int {
        val pane = sourcePane?.takeIf { sourceSection.body.isVisible }
            ?: return headerMiddle()
        val y = pane.anchorY(anchor.startOffset)
        return javax.swing.SwingUtilities.convertPoint(pane, 0, y, this).y
    }

    /** The middle of the header, where arrows end. */
    fun headerMiddle(): Int = (components.firstOrNull()?.let { it.y + it.height / 2 } ?: scaled(12))

    override fun dispose() {
        summaryJob?.cancel()
        chatSubscription.dispose()
        chat.discard()
        sourcePane?.let(Disposer::dispose)
    }

    private fun toggle(section: CardSection): JLabel = host.zoomFont(JLabel(glyph(true)).apply {
        toolTipText = "Fold or unfold"
        name = "canvas-fold-${section.name.lowercase()}"
    }).onLeftClick { host.model.toggleFolded(id, section) }

    /** A titled, foldable part of the card. */
    private inner class Section(val section: CardSection, title: String, val body: JComponent) : JPanel(StackLayout { scaled(4) }) {
        private val toggle = toggle(section)
        val title: JLabel = host.zoomFont(JLabel(title).apply { foreground = UIUtil.getContextHelpForeground() },
            UIUtil.getLabelFont().deriveFont(Font.BOLD))

        init {
            isOpaque = false
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(toggle)
                add(this@Section.title.onLeftClick { host.model.toggleFolded(id, section) })
            })
            add(body)
        }

        fun fold(folded: Boolean) {
            body.isVisible = !folded
            toggle.text = glyph(!folded)
        }
    }

    private fun scaled(value: Int) = zoomScaled(value, host.zoom)

    private companion object {
        val LOG = logger<CanvasCardView>()
        val NOTICE = JBColor(Color(160, 95, 20), Color(214, 165, 90))
        val USER = JBColor(Color(50, 90, 190), Color(120, 160, 240))
        val ACCENT = JBColor(Color(76, 112, 220), Color(110, 150, 240))

        fun glyph(open: Boolean) = if (open) "▾" else "▸"

        @Suppress("UseDPIAwareBorders")
        fun pixel(value: Int) = javax.swing.border.EmptyBorder(value, value, value, value)

        fun kindIcon(card: CanvasCard): Icon = when (card.symbol?.kind?.takeIf { card.kind == CardKind.SYMBOL }) {
            null -> AllIcons.Actions.Selectall
            "CLASS" -> AllIcons.Nodes.Class
            "FUNCTION" -> AllIcons.Nodes.Method
            "PROPERTY" -> AllIcons.Nodes.Property
            "VARIABLE", "PARAMETER" -> AllIcons.Nodes.Variable
            "FILE" -> AllIcons.FileTypes.Any_type
            else -> AllIcons.Nodes.Unknown
        }
    }
}
