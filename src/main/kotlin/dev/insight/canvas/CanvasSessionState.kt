package dev.insight.canvas

import com.intellij.openapi.Disposable
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** What a card shows: text the reader selected, or a symbol they clicked. */
enum class CardKind { SELECTION, SYMBOL }

/** Who first opened a card; revisiting an existing card keeps its original attribution. */
enum class CardOpenedBy { USER, LLM }

/** The foldable parts of a card, top to bottom. */
enum class CardSection { META, SUMMARY, SOURCE, CHAT, INPUT }

/**
 * Where a card was opened in its parent's shown source text (the root file's text for a card without a parent).
 * [startOffset] and [endOffset] (exclusive) are offsets into that text; [line] is the 0-based document line of
 * [startOffset] in the parent's file, so the arrow and the chat can name the place without the parent's text.
 */
data class CardAnchor(val startOffset: Int, val endOffset: Int, val line: Int)

/**
 * A graph symbol as it can be found again in a later session: [kind] is a `SymbolKind` name, [filePath] the path
 * the canvas uses for files (project-relative), [offset] and [line] (0-based) its declaration's place when known, or -1.
 */
data class SymbolRef(val filePath: String, val name: String, val kind: String, val container: String = "", val offset: Int = -1,
    val line: Int = -1)

/** One saved chat message; tool calls are not kept, since a restored chat only needs what was said. */
data class SavedMessage(val role: ChatRole, val text: String)

/** One independent conversation within a card. Ids are scoped to their card. */
data class CanvasChat(val id: String = "chat-1", val title: String = "Chat 1",
    val messages: List<SavedMessage> = emptyList(), val draft: String = "")

/** What has chat tabs and folds like a card: a card, or the root file. */
interface CanvasChatHolder {
    val chats: List<CanvasChat>
    val activeChatId: String
    val folded: Set<CardSection>
    val activeChat: CanvasChat get() = chats.first { it.id == activeChatId }
    val messages: List<SavedMessage> get() = activeChat.messages
    val draft: String get() = activeChat.draft
}

/** The root file's own chats and folded parts: it is shown like a card, but cards opened from it have no parent. */
data class CanvasRootState(
    override val chats: List<CanvasChat> = listOf(CanvasChat()),
    override val activeChatId: String = chats.first().id,
    override val folded: Set<CardSection> = emptySet(),
    val summary: String? = null,
    val summaryError: String? = null,
) : CanvasChatHolder {
    init {
        require(chats.isNotEmpty() && chats.map { it.id }.distinct().size == chats.size)
        require(chats.any { it.id == activeChatId })
    }
}

/** The canvas's zoom and scroll position, in canvas coordinates. */
data class CanvasView(val zoom: Double = 1.0, val x: Int = 0, val y: Int = 0)

/**
 * A card to the right of its parent: a selection or a symbol, with its own summary and chat. [text] is a snapshot of
 * the selected text or the symbol's source from 0-based document line [firstLine] of [filePath], bounded by
 * [CANVAS_CARD_TEXT_LIMIT]; [textTruncated] tells it was cut. [summaryError] is set when the latest summary failed.
 * [folded] sections are collapsed; [chats] hold independent histories and drafts.
 */
data class CanvasCard(
    val id: String,
    val parentId: String?,
    val kind: CardKind,
    val anchor: CardAnchor,
    val symbol: SymbolRef?,
    val filePath: String,
    val firstLine: Int,
    val text: String,
    val textTruncated: Boolean = false,
    val summary: String? = null,
    val summaryError: String? = null,
    override val folded: Set<CardSection> = emptySet(),
    override val chats: List<CanvasChat> = listOf(CanvasChat()),
    override val activeChatId: String = chats.first().id,
    /** Show the containing file, retaining the original opening identity. */
    val wholeFile: Boolean = false,
    val openedBy: CardOpenedBy = CardOpenedBy.USER,
) : CanvasChatHolder {
    init {
        require(chats.isNotEmpty() && chats.map { it.id }.distinct().size == chats.size)
        require(chats.any { it.id == activeChatId })
    }

    /** 0-based document line of the text's last line. */
    val lastLine: Int get() = firstLine + text.count { it == '\n' }

    /** The symbol's name, or a short label for a selection. */
    val title: String get() = if (wholeFile) filePath.substringAfterLast('/') else symbol?.name ?: "Selection"

    /** `path:first-last` with 1-based lines, as cited in chats. */
    val location: String get() = "$filePath:${firstLine + 1}" + if (lastLine > firstLine) "-${lastLine + 1}" else ""
}

/**
 * A saved exploration of one file: [rootPath] is the project-relative path of the opened file, [cards] in the order
 * they were opened (parents always before their children), [view] the canvas position, and [root] the root file's
 * own chats. Times are epoch millis.
 */
data class CanvasSession(
    val id: String,
    val rootPath: String,
    val title: String,
    val created: Long,
    val updated: Long,
    val cards: List<CanvasCard> = emptyList(),
    val view: CanvasView = CanvasView(),
    val root: CanvasRootState = CanvasRootState(),
) {
    fun card(id: String): CanvasCard? = cards.firstOrNull { it.id == id }

    /** The chats of [cardId], or the root file's for null; null when the card is unknown. */
    fun chatHolder(cardId: String?): CanvasChatHolder? = if (cardId == null) root else card(cardId)

    /** Cards opened from [parentId], or from the root file when it is null, in opening order. */
    fun children(parentId: String?): List<CanvasCard> = cards.filter { it.parentId == parentId }

    /** The card [cardId] and its ancestors, root-first; empty when the card is unknown. */
    fun chain(cardId: String): List<CanvasCard> {
        val byId = cards.associateBy { it.id }
        val chain = mutableListOf<CanvasCard>()
        var next = byId[cardId]
        while (next != null && chain.none { it.id == next!!.id }) {
            chain += next
            next = next.parentId?.let(byId::get)
        }
        return chain.asReversed()
    }

    /** [cardId] and every card opened from it, directly or not. */
    fun subtree(cardId: String): Set<String> {
        val ids = linkedSetOf(cardId)
        var grew = true
        while (grew) grew = cards.filter { it.parentId in ids }.fold(false) { added, card -> ids.add(card.id) || added }
        return ids.filterTo(linkedSetOf()) { id -> cards.any { it.id == id } }
    }

    companion object {
        /** A new, empty session of [rootPath]. */
        fun new(rootPath: String, title: String = rootPath.substringAfterLast('/'), now: Long = System.currentTimeMillis(),
            id: String = newCanvasId()) = CanvasSession(id, rootPath, title, now, now)
    }
}

/** Bounds a card's text snapshot, so one huge selection cannot bloat the session file or the chat context. */
const val CANVAS_CARD_TEXT_LIMIT = 40_000

/** [text] cut at a line end within [limit] characters, and whether it was cut. */
fun boundCardText(text: String, limit: Int = CANVAS_CARD_TEXT_LIMIT): Pair<String, Boolean> {
    if (text.length <= limit) return text to false
    val end = text.lastIndexOf('\n', limit).takeIf { it > 0 } ?: limit
    return text.substring(0, end) to true
}

/** A short random id, unique enough for one project's sessions and cards. */
fun newCanvasId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

/** The messages of a chat worth restoring: what the user, the model and the app said, without tool calls. */
fun savedMessages(messages: List<ChatMessage>): List<SavedMessage> =
    messages.filter { it.role != ChatRole.TOOL && it.text.isNotBlank() }.map { SavedMessage(it.role, it.text) }

/** Saved messages as chat messages, for seeding a restored `ChatSession`. */
fun List<SavedMessage>.toChatMessages(): List<ChatMessage> = map { ChatMessage(it.role, it.text) }

/** What changed in a [CanvasSessionModel]; views redraw only what they need. */
sealed interface CanvasChange {
    data class CardOpened(val card: CanvasCard) : CanvasChange
    /** [ids] are the removed card and its whole subtree. */
    data class CardsRemoved(val ids: Set<String>) : CanvasChange
    /** Summary, folding, chat or draft of [card] changed; [card] is the new state. */
    data class CardChanged(val card: CanvasCard) : CanvasChange
    /** The root file's chats, drafts or folding changed; [root] is the new state. */
    data class RootChanged(val root: CanvasRootState) : CanvasChange
    data class ViewChanged(val view: CanvasView) : CanvasChange
    data class TitleChanged(val title: String) : CanvasChange
}

/**
 * The live state of one canvas session. Mutations and listeners run on one thread (the EDT); [session] is an
 * immutable snapshot that any thread may read, for saving or building chat context in the background.
 */
class CanvasSessionModel(initial: CanvasSession, private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = ::newCanvasId) {
    @Volatile var session: CanvasSession = initial
        private set
    private val listeners = CopyOnWriteArrayList<(CanvasChange) -> Unit>()

    val cards: List<CanvasCard> get() = session.cards
    fun card(id: String): CanvasCard? = session.card(id)
    fun children(parentId: String?): List<CanvasCard> = session.children(parentId)
    fun chain(cardId: String): List<CanvasCard> = session.chain(cardId)

    /**
     * Opens a card from [parentId] (null: the root file) at [anchor], or returns the card already open there for the
     * same [kind] and [symbol]. [text] is bounded by [CANVAS_CARD_TEXT_LIMIT]; a [wholeFile] card shows its whole file.
     */
    fun open(parentId: String?, kind: CardKind, anchor: CardAnchor, filePath: String, firstLine: Int, text: String,
        symbol: SymbolRef? = null, wholeFile: Boolean = false, openedBy: CardOpenedBy = CardOpenedBy.USER): CanvasCard {
        require(parentId == null || card(parentId) != null) { "Unknown parent card $parentId" }
        require(kind != CardKind.SYMBOL || symbol != null) { "A symbol card needs its symbol" }
        findOpen(parentId, kind, anchor, symbol)?.let { return it }
        val (bounded, truncated) = boundCardText(text)
        val card = CanvasCard(ids(), parentId, kind, anchor, symbol, filePath, firstLine, bounded, truncated,
            wholeFile = wholeFile, openedBy = openedBy)
        session = session.copy(cards = session.cards + card, updated = clock())
        notify(CanvasChange.CardOpened(card))
        return card
    }

    /** The card open from [parentId] at the same anchor range for the same symbol, if any. */
    fun findOpen(parentId: String?, kind: CardKind, anchor: CardAnchor, symbol: SymbolRef?): CanvasCard? = cards.firstOrNull {
        it.parentId == parentId && it.kind == kind && it.anchor.startOffset == anchor.startOffset &&
            it.anchor.endOffset == anchor.endOffset && sameSymbol(it.symbol, symbol)
    }

    /** Removes [cardId] and every card opened from it; returns the removed ids, empty when the card is unknown. */
    fun remove(cardId: String): Set<String> {
        val removed = session.subtree(cardId)
        if (removed.isEmpty()) return removed
        session = session.copy(cards = session.cards.filter { it.id !in removed }, updated = clock())
        notify(CanvasChange.CardsRemoved(removed))
        return removed
    }

    /** A finished summary, or with [error] a failed one; null [summary] and [error] reset it so it can be requested again. */
    fun setSummary(cardId: String?, summary: String?, error: String? = null) {
        val text = summary?.takeIf { it.isNotBlank() }
        if (cardId == null) updateRoot { it.copy(summary = text, summaryError = error) }
        else update(cardId) { it.copy(summary = text, summaryError = error) }
    }

    /** Folds or unfolds [section] of [cardId], or of the root file for null. */
    fun setFolded(cardId: String?, section: CardSection, folded: Boolean) {
        fun Set<CardSection>.with() = if (folded) this + section else this - section
        if (cardId == null) updateRoot { it.copy(folded = it.folded.with()) } else update(cardId) { it.copy(folded = it.folded.with()) }
    }

    /** Folds or unfolds [section]; returns whether it is folded now, or null when the card is unknown. */
    fun toggleFolded(cardId: String?, section: CardSection): Boolean? {
        val holder = session.chatHolder(cardId) ?: return null
        val folded = section !in holder.folded
        setFolded(cardId, section, folded)
        return folded
    }

    /** Starts and selects a new chat of [cardId], or of the root file for null; null when the card is unknown. */
    fun addChat(cardId: String?): CanvasChat? {
        val holder = session.chatHolder(cardId) ?: return null
        val number = generateSequence(1) { it + 1 }.first { n -> holder.chats.none { it.title == "Chat $n" } }
        val chat = CanvasChat(ids(), "Chat $number")
        updateChats(cardId) { chats, _ -> chats + chat to chat.id }
        return chat
    }

    fun selectChat(cardId: String?, chatId: String) = updateChats(cardId) { chats, active ->
        chats to if (chats.any { it.id == chatId }) chatId else active
    }

    /** Keep one conversation; select an adjacent tab when closing the selected one. */
    fun removeChat(cardId: String?, chatId: String) = updateChats(cardId) { chats, active ->
        val index = chats.indexOfFirst { it.id == chatId }
        if (index < 0 || chats.size == 1) chats to active else {
            val remaining = chats.filter { it.id != chatId }
            remaining to if (active == chatId) remaining[index.coerceAtMost(remaining.lastIndex)].id else active
        }
    }

    fun setMessages(cardId: String?, messages: List<SavedMessage>, chatId: String? = null) = updateChat(cardId, chatId) {
        it.copy(messages = messages)
    }

    fun setDraft(cardId: String?, draft: String, chatId: String? = null) = updateChat(cardId, chatId) { it.copy(draft = draft) }

    private fun updateChat(cardId: String?, chatId: String?, change: (CanvasChat) -> CanvasChat) = updateChats(cardId) { chats, active ->
        chats.map { if (it.id == (chatId ?: active)) change(it) else it } to active
    }

    /** Applies [change] to the chats and selected chat id of [cardId], or of the root file for null. */
    private fun updateChats(cardId: String?, change: (List<CanvasChat>, String) -> Pair<List<CanvasChat>, String>) {
        if (cardId == null) updateRoot { root ->
            change(root.chats, root.activeChatId).let { (chats, active) -> root.copy(chats = chats, activeChatId = active) }
        } else update(cardId) { card ->
            change(card.chats, card.activeChatId).let { (chats, active) -> card.copy(chats = chats, activeChatId = active) }
        }
    }

    /** Applies [change] to the root file's chats and folding; an unchanged root notifies nobody. */
    private fun updateRoot(change: (CanvasRootState) -> CanvasRootState) {
        val new = change(session.root)
        if (new == session.root) return
        session = session.copy(root = new, updated = clock())
        notify(CanvasChange.RootChanged(new))
    }

    /**
     * Replaces a restored card's snapshot with the current code: its [anchor] in the parent's shown text, and [text]
     * from document line [firstLine], bounded like [open]. Summary and chat stay.
     */
    fun restoreSnapshot(cardId: String, anchor: CardAnchor, firstLine: Int, text: String) = update(cardId) {
        val (bounded, truncated) = boundCardText(text)
        it.copy(anchor = anchor, firstLine = firstLine, text = bounded, textTruncated = truncated)
    }

    /** Expands in place; child anchors move from the excerpt to the same positions in the file. */
    fun expandToFile(cardId: String, text: String, offset: Int, anchors: Map<String, CardAnchor> = emptyMap()) {
        val card = card(cardId) ?: return
        if (card.wholeFile) return
        val (bounded, truncated) = boundCardText(text)
        val changed = children(cardId).associate { child ->
            child.id to child.copy(anchor = anchors[child.id] ?: child.anchor.copy(
                startOffset = child.anchor.startOffset + offset, endOffset = child.anchor.endOffset + offset))
        }
        val expanded = card.copy(wholeFile = true, firstLine = 0, text = bounded, textTruncated = truncated,
            summary = null, summaryError = null, folded = card.folded - CardSection.SOURCE)
        session = session.copy(cards = cards.map { changed[it.id] ?: if (it.id == cardId) expanded else it }, updated = clock())
        notify(CanvasChange.CardChanged(expanded))
        changed.values.forEach { notify(CanvasChange.CardChanged(it)) }
    }

    fun setView(view: CanvasView) {
        if (view == session.view) return
        session = session.copy(view = view, updated = clock())
        notify(CanvasChange.ViewChanged(view))
    }

    fun setTitle(title: String) {
        if (title == session.title || title.isBlank()) return
        session = session.copy(title = title, updated = clock())
        notify(CanvasChange.TitleChanged(title))
    }

    /** Calls [listener] after every change, on the mutating thread, until the returned disposable is disposed. */
    fun subscribe(listener: (CanvasChange) -> Unit): Disposable {
        listeners += listener
        return Disposable { listeners -= listener }
    }

    /** Applies [change] to [cardId]; returns the new card, or null when it is unknown. Unchanged cards notify nobody. */
    private fun update(cardId: String, change: (CanvasCard) -> CanvasCard): CanvasCard? {
        val old = card(cardId) ?: return null
        val new = change(old)
        if (new == old) return old
        session = session.copy(cards = session.cards.map { if (it.id == cardId) new else it }, updated = clock())
        notify(CanvasChange.CardChanged(new))
        return new
    }

    private fun notify(change: CanvasChange) = listeners.forEach { it(change) }

    private fun sameSymbol(a: SymbolRef?, b: SymbolRef?): Boolean =
        a == b || (a != null && b != null && a.filePath == b.filePath && a.name == b.name && a.kind == b.kind && a.offset == b.offset)
}
