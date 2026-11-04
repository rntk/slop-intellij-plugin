package dev.insight.canvas

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.insight.ui.zoomScaled
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
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
    /** What the cards' chats may do on this canvas. */
    val actions: CanvasActions
    val sourceListener: CanvasSourcePane.Listener
    /** Unified patches by absolute file path, for files opened from a diff; empty otherwise. */
    val diffs: Map<String, String>
    val zoom: Double
    /** Shows [component] in [base] (its own font by default) at the current zoom, now and after each zoom change. */
    fun <T : JComponent> zoomFont(component: T, base: Font = component.font): T
    /** The card's size may have changed: lay out the canvas again. */
    fun cardResized()
    fun close(cardId: String)
    fun expandToFile(cardId: String)
    fun openSideBySide(cardId: String)
    fun attachFile(cardId: String?)
    fun navigate(source: CanvasSource?, path: String, line: Int)
}

/**
 * One card on the canvas: a header with the card's kind, name and place, then its summary, source (with a Diff tab for
 * a file opened from a diff), chat history and chat input, each folded with its toggle as saved in the card. The card's
 * chat keeps its history in the model.
 */
internal class CanvasCardView(private val host: CanvasCardHost, initial: CanvasCard) : JPanel(StackLayout { zoomScaled(4, host.zoom) }), Disposable {
    val id = initial.id
    var card: CanvasCard = initial
        private set
    private var rendered = false
    var source: CanvasSource? = host.controller.source(id)
        private set
    /** The card's source viewer; null when it has no source text. */
    var sourcePane: CanvasSourcePane? = source?.takeIf { it.text.isNotEmpty() }?.let { CanvasSourcePane(host.project, id, it, host.sourceListener) }
        private set
    /** Whether the card is drawn emphasised, after it was revealed. */
    var highlighted = false
        set(value) {
            field = value
            applyZoom()
            repaint()
        }

    private val metaToggle = canvasFoldToggle(host, id, CardSection.META)
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
    private val summary = CanvasFoldSection(host, id, CardSection.SUMMARY, "Summary", JPanel(StackLayout { scaled(2) }).apply {
        isOpaque = false
        add(summaryText)
        add(summaryStatus)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(summaryRetry) })
    })

    private val sourceBody = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(sourcePane
        ?: host.zoomFont(JLabel(when (initial.kind) {
            CardKind.SYMBOL -> "No source to show."
            CardKind.ATTACHMENT -> "Use Open file to view this attachment in the IDE."
            CardKind.SELECTION -> "No text."
        }).apply {
            foreground = UIUtil.getContextHelpForeground()
        }))
    }
    /** The changed hunks within the card's source; null when it shows no change from a diff. */
    var diffPane: CanvasDiffPane? = null
        private set
    private val sourceSection = CanvasSourceSection(host, id, canvasSourceTitle(initial), sourceBody)
    private val titleLabel = host.zoomFont(CanvasTitleLabel(card.title, kindIcon(card)),
        UIUtil.getLabelFont().deriveFont(Font.BOLD))
    private val openedByLabel = host.zoomFont(JLabel("LLM").apply {
        name = "canvas-opened-by-llm"
        toolTipText = "Opened by the LLM through chat tools"
        foreground = ACCENT
        isVisible = card.openedBy == CardOpenedBy.LLM
    }, JBUI.Fonts.smallFont().deriveFont(Font.BOLD))
    private val expand = host.zoomFont(ActionLink("Expand to file") { host.expandToFile(id) }.apply {
        toolTipText = "Show the whole file here, with links and this card's chat"
    })

    // The history section is created first, so the chat view can name it as messages arrive.
    private val historyBody = JPanel(BorderLayout()).apply { isOpaque = false }
    private val history = CanvasFoldSection(host, id, CardSection.CHAT, "Chat", historyBody)
    private val chat = CanvasChatView(host, id, initial, { canvasWelcome(card) },
        "Ask about this card… (Enter sends, Shift+Enter for a new line)") { history.title.text = it }
    val input get() = chat.input
    private val inputSection = CanvasFoldSection(host, id, CardSection.INPUT, "Ask", chat.ask)

    init {
        isOpaque = true
        background = UIUtil.getPanelBackground()
        name = "canvas-card-$id"
        historyBody.add(chat.history)
        add(header())
        add(meta)
        add(summary)
        add(sourceSection)
        add(history)
        add(inputSection)
        update(initial)
        applyZoom()
        requestSummary(force = false)
    }

    private fun header(): JComponent = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(metaToggle, BorderLayout.WEST)
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.CENTER)
            add(openedByLabel, BorderLayout.EAST)
        }, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(host.zoomFont(ActionLink("Attach file") { host.attachFile(id) }.apply { name = "canvas-attach-file" }))
            add(expand)
            add(host.zoomFont(ActionLink("Side by side") { host.openSideBySide(id) }.apply {
                toolTipText = "Open the chain from the root file to this card in a new tab"
            }))
            add(host.zoomFont(ActionLink(if (card.kind == CardKind.ATTACHMENT) "Open file" else "Source") { host.navigate(source, card.filePath, card.firstLine) }.apply {
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
        val draftOnly = rendered && card.activeChatId == next.activeChatId &&
            card.copy(chats = next.chats) == next && card.chats.size == next.chats.size &&
            card.chats.zip(next.chats).all { (a, b) -> a.copy(draft = b.draft) == b }
        card = next
        chat.update(next)
        if (draftOnly) return
        val fresh = host.controller.source(id)
        val sourceChanged = fresh !== source
        if (sourceChanged) {
            summaryJob?.cancel()
            summaryJob = null
            sourcePane?.let(Disposer::dispose)
            source = fresh
            sourcePane = fresh?.let { CanvasSourcePane(host.project, id, it, host.sourceListener) }
            sourceBody.removeAll()
            sourcePane?.let { sourceBody.add(it) }
            sourcePane?.applyZoom(host.zoom)
            notice.text = fresh?.notice.orEmpty()
            notice.isVisible = !fresh?.notice.isNullOrBlank()
        }
        if (sourceChanged || !rendered) showDiff(next)
        if ((sourceChanged || !rendered) && next.kind == CardKind.ATTACHMENT && next.attachment?.imageBase64 != null) {
            sourceBody.removeAll()
            sourceBody.add(CanvasImagePreview(next.attachment.imageBase64) { host.zoom })
        }
        rendered = true
        titleLabel.text = next.title
        openedByLabel.isVisible = next.openedBy == CardOpenedBy.LLM
        titleLabel.icon = if (next.wholeFile) AllIcons.FileTypes.Any_type else kindIcon(next)
        titleLabel.toolTipText = "${canvasKindLabel(next)} ${next.title}"
        expand.isVisible = next.kind != CardKind.ATTACHMENT && !next.wholeFile && source?.file != null && !source?.text.isNullOrEmpty() && next.symbol?.kind != "FILE"
        val shown = if (next.wholeFile && fresh != null) next.copy(text = fresh.text, textTruncated = false) else next
        metaLabel.text = "${canvasKindLabel(next)} · ${canvasCardMeta(shown)}"
        metaLabel.toolTipText = metaLabel.text
        meta.isVisible = CardSection.META !in next.folded
        metaToggle.text = canvasFoldGlyph(CardSection.META !in next.folded)
        listOf(summary, sourceSection, history, inputSection).forEach { it.fold(it.section in next.folded) }
        sourcePane?.isVisible = CardSection.SOURCE !in next.folded
        sourceSection.title.text = canvasSourceTitle(shown)
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
        if (sourceChanged) requestSummary(force = false)
    }

    /** Offers the changed hunks within the card's source in a Diff tab, with no tab for a file not opened from a diff. */
    private fun showDiff(card: CanvasCard) {
        diffPane?.let(Disposer::dispose)
        val rows = canvasSourceDiff(host.diffs, source, card.wholeFile)
        diffPane = rows.takeIf { it.isNotEmpty() }?.let { CanvasDiffPane(host.project, card.filePath, it, host.sourceListener::wheel) }
        diffPane?.applyZoom(host.zoom)
        sourceSection.setDiff(diffPane, canvasDiffTitle(rows))
    }

    /** Asks for the summary when the card has none yet, or again when [force]d; the result is saved in the model. */
    fun requestSummary(force: Boolean) {
        val current = host.model.card(id) ?: return
        if (current.kind == CardKind.ATTACHMENT && !force) return
        if (summaryJob?.isActive == true || current.text.isBlank()) return
        if (!force && (current.summary != null || current.summaryError != null)) return
        val input = CanvasSummaryInput.of(current, host.model.session.rootPath, host.model.chain(id), language())
        val requestedSource = source
        summaryJob = host.scope.launch(Dispatchers.EDT) {
            try {
                val text = withContext(Dispatchers.IO) { CanvasSummarizer(host.services.complete(), tools = host.services.summaryTools(), maxContextChars = host.services.maxContextChars()).summarize(input) }
                host.model.setSummary(id, text)
            } catch (e: CanvasSummaryException) {
                LOG.info("Canvas card summary failed: ${e.message}", e.cause)
                host.model.setSummary(id, null, e.message)
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Canvas card summary failed", e)
                host.model.setSummary(id, null, "The summary failed: ${e.message ?: e.javaClass.simpleName}. See the IDE log.")
            } finally {
                if (source === requestedSource) {
                    summaryJob = null
                    host.model.card(id)?.let(::update)
                }
            }
        }
        update(current)
    }

    private fun language(): String? = source?.file?.fileType?.displayName?.takeIf { it.isNotBlank() }

    /** Asks the card's selected chat [text]. */
    internal fun send(text: String) = chat.send(text)

    /** Sizes beyond fonts, at the current zoom. */
    fun applyZoom() {
        val accent = if (highlighted) ACCENT else JBColor.border()
        border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(accent, if (highlighted) scaled(2) else scaled(1)),
            pixel(scaled(8)))
        sourcePane?.applyZoom(host.zoom)
        diffPane?.applyZoom(host.zoom)
    }

    /** The y of [anchor]'s line in this card, or the header's middle when the source is folded or shows its diff. */
    fun anchorY(anchor: CardAnchor): Int {
        val pane = sourcePane?.takeIf { sourceSection.body.isVisible && !sourceSection.showsDiff }
            ?: return headerMiddle()
        val y = pane.anchorY(anchor.startOffset)
        return javax.swing.SwingUtilities.convertPoint(pane, 0, y, this).y
    }

    /** The middle of the header, where arrows end. */
    fun headerMiddle(): Int = (components.firstOrNull()?.let { it.y + it.height / 2 } ?: scaled(12))

    override fun dispose() {
        summaryJob?.cancel()
        Disposer.dispose(chat)
        sourcePane?.let(Disposer::dispose)
        diffPane?.let(Disposer::dispose)
    }

    private fun scaled(value: Int) = zoomScaled(value, host.zoom)

    private companion object {
        val LOG = logger<CanvasCardView>()
        val NOTICE = JBColor(Color(160, 95, 20), Color(214, 165, 90))
        val ACCENT = JBColor(Color(76, 112, 220), Color(110, 150, 240))

        @Suppress("UseDPIAwareBorders")
        fun pixel(value: Int) = javax.swing.border.EmptyBorder(value, value, value, value)

        fun kindIcon(card: CanvasCard): Icon = if (card.kind == CardKind.ATTACHMENT) AllIcons.FileTypes.Any_type else when (card.symbol?.kind?.takeIf { card.kind == CardKind.SYMBOL }) {
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
