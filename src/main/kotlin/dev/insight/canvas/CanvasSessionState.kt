package dev.insight.canvas

import com.intellij.openapi.Disposable
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a card shows: text the reader selected, a symbol they clicked, an attached file, or the session's [ROOT] file or
 * directory, the card every session starts with.
 */
enum class CardKind { SELECTION, SYMBOL, ATTACHMENT, ROOT }

/** The id of a session's root card, which is also the handle chats name it by. */
const val CANVAS_ROOT = "root"

/** A linked local file, with a bounded image snapshot when it can be previewed. Text uses the card's snapshot. */
data class CanvasAttachment(val mediaType: String, val size: Long, val imageBase64: String? = null)

/** Who first opened a card; revisiting an existing card keeps its original attribution. */
enum class CardOpenedBy { USER, LLM, DIRECTORY }

/** The foldable parts of a card, top to bottom. */
enum class CardSection { META, SUMMARY, USAGES, SOURCE, CHAT, INPUT }

/**
 * Where a card was opened in its parent's shown source text. [startOffset] and [endOffset] (exclusive) are offsets
 * into that text; [line] is the 0-based document line of [startOffset] in the parent's file, so the arrow and the chat
 * can name the place without the parent's text.
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

/** What has chat tabs and folds: a card. */
interface CanvasChatHolder {
    val chats: List<CanvasChat>
    val activeChatId: String
    val folded: Set<CardSection>
    /** Summaries of the code between the source's symbols, written on request. */
    val gapSummaries: List<GapSummary>
    val activeChat: CanvasChat get() = chats.first { it.id == activeChatId }
    val messages: List<SavedMessage> get() = activeChat.messages
    val draft: String get() = activeChat.draft
}

/** The canvas's zoom and scroll position, in canvas coordinates. */
data class CanvasView(val zoom: Double = 1.0, val x: Int = 0, val y: Int = 0)

/**
 * A card to the right of its parent: a selection or a symbol, with its own summary and chat. [text] is a snapshot of
 * the selected text or the symbol's source from 0-based document line [firstLine] of [filePath], bounded by
 * [CANVAS_CARD_TEXT_LIMIT]; [textTruncated] tells it was cut. [summaryError] is set when the latest summary failed.
 * [folded] sections are collapsed; [chats] hold independent histories and drafts. [usages] lists the sites, within its
 * file, of the usage search the card was opened from. [size] is the size the reader resized the card to, null when it
 * follows its content. The [CardKind.ROOT] card (see [canvasRootCard]) has no parent and keeps no snapshot: its source
 * is read from the root file whenever the session is shown.
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
    val attachment: CanvasAttachment? = null,
    override val gapSummaries: List<GapSummary> = emptyList(),
    val usages: CardUsages? = null,
    val size: CardSize? = null,
) : CanvasChatHolder {
    init {
        require(chats.isNotEmpty() && chats.map { it.id }.distinct().size == chats.size)
        require(chats.any { it.id == activeChatId })
    }

    /** 0-based document line of the text's last line. */
    val lastLine: Int get() = firstLine + text.count { it == '\n' }

    /** The symbol's name, or a short label for a selection; the file name for files. */
    val title: String get() = if (wholeFile || kind == CardKind.ATTACHMENT || kind == CardKind.ROOT) filePath.substringAfterLast('/')
        else symbol?.name ?: "Selection"

    /** `path:first-last` with 1-based lines, as cited in chats; the root card's path alone. */
    val location: String get() = if (kind == CardKind.ROOT) filePath
        else "$filePath:${firstLine + 1}" + if (lastLine > firstLine) "-${lastLine + 1}" else ""

    /** Whether this is the root card of a directory session, which lists the directory's files. */
    internal val isDirectoryRoot: Boolean get() = kind == CardKind.ROOT && symbol?.kind == ROOT_DIRECTORY

    /** `file` or `directory`, for the root card. */
    internal val rootKind: String get() = if (isDirectoryRoot) "directory" else "file"

    /** A starting file of a directory session, which the root card only lists: it hangs from no line of the root. */
    internal val isDirectorySeed: Boolean get() = parentId == CANVAS_ROOT && openedBy == CardOpenedBy.DIRECTORY
}

private const val ROOT_DIRECTORY = "DIRECTORY"

/**
 * The size the reader resized a card to, in canvas units at zoom 1. A null [width] or [height] is automatic: the card
 * keeps its usual width, or the height of its content. [height] is the card's whole height, which its source or diff
 * viewer absorbs (see [canvasViewerMaxHeight]).
 */
data class CardSize(val width: Int? = null, val height: Int? = null) {
    /** Within the bounds a card can be resized to; null when both dimensions are automatic. */
    fun bounded(): CardSize? = CardSize(width?.coerceIn(CARD_MIN_WIDTH, CARD_MAX_WIDTH), height?.coerceIn(CARD_MIN_HEIGHT, CARD_MAX_HEIGHT))
        .takeIf { it.width != null || it.height != null }
}

/** The narrowest a card can be resized to at zoom 1: room for its header and chat. */
const val CARD_MIN_WIDTH = 320
const val CARD_MAX_WIDTH = 4_000
/** The bounds of a resized card's height at zoom 1; its content may still keep it taller than [CARD_MIN_HEIGHT]. */
const val CARD_MIN_HEIGHT = 120
const val CARD_MAX_HEIGHT = 8_000

/**
 * The root card of a session of [rootPath], a file or a [directory]: the card a session starts from, with chats and a
 * summary like any card, but without a parent or a saved snapshot of its source.
 */
fun canvasRootCard(rootPath: String, directory: Boolean = false): CanvasCard = CanvasCard(CANVAS_ROOT, null, CardKind.ROOT,
    CardAnchor(0, 0, 0), SymbolRef(rootPath, rootPath.substringAfterLast('/'), if (directory) ROOT_DIRECTORY else "FILE", line = 0),
    rootPath, 0, "", wholeFile = true)

/**
 * A file scheduled to be opened on demand in a later chunk (for linked files or diff-connected components).
 * [filePath] and [parentPath] are project-relative canvas paths, matching [CanvasCard.filePath] and [CanvasSession.rootPath].
 */
data class CanvasPendingFile(val filePath: String, val parentPath: String? = null, val offset: Int = 0, val line: Int = 0)

/**
 * A saved exploration of one file or directory: [rootPath] is the project-relative path of what was opened, [cards] in
 * the order they were opened (parents always before their children), starting with the [CardKind.ROOT] card of
 * [rootPath], and [view] the canvas position. [directoryInitialized] tells a directory's starting files were opened,
 * [directoryCursor] holds the last loaded relative path when more directory files remain to load, and [pendingFiles]
 * holds remaining linked or diff-connected files deferred past the initial chunk. Times are epoch millis.
 */
data class CanvasSession(
    val id: String,
    val rootPath: String,
    val title: String,
    val created: Long,
    val updated: Long,
    val cards: List<CanvasCard> = listOf(canvasRootCard(rootPath)),
    val view: CanvasView = CanvasView(),
    val directoryInitialized: Boolean = false,
    val directoryCursor: String? = null,
    val pendingFiles: List<CanvasPendingFile> = emptyList(),
) {
    init {
        require(cards.firstOrNull()?.let { it.id == CANVAS_ROOT && it.kind == CardKind.ROOT } == true) { "A session starts with its root card" }
        require(cards.count { it.kind == CardKind.ROOT } == 1) { "A session has one root card" }
    }

    /** The card of the opened file or directory. */
    val root: CanvasCard get() = cards.first()

    internal val rootKind: String get() = root.rootKind

    fun card(id: String): CanvasCard? = cards.firstOrNull { it.id == id }

    /** Cards opened from [parentId], in opening order. */
    fun children(parentId: String): List<CanvasCard> = cards.filter { it.parentId == parentId }

    /** The card [cardId] and its ancestors, starting with the root card; empty when the card is unknown. */
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
        /** A new session of [rootPath], a file or a [directory], with only its root card. */
        fun new(rootPath: String, title: String = rootPath.substringAfterLast('/'), now: Long = System.currentTimeMillis(),
            id: String = newCanvasId(), directory: Boolean = false) =
            CanvasSession(id, rootPath, title, now, now, listOf(canvasRootCard(rootPath, directory)))
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
    /** Multiple cards appended at once (for example, the next chunk of directory or pending files). */
    data class CardsOpened(val cards: List<CanvasCard>) : CanvasChange
    /** [ids] are the removed card and its whole subtree. */
    data class CardsRemoved(val ids: Set<String>) : CanvasChange
    /** Summary, folding, size, chat or draft of [card] changed; [card] is the new state. */
    data class CardChanged(val card: CanvasCard) : CanvasChange
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
    fun children(parentId: String): List<CanvasCard> = session.children(parentId)
    fun chain(cardId: String): List<CanvasCard> = session.chain(cardId)

    /**
     * Opens a card from [parentId] at [anchor], or returns the card already open there for the same [kind] and
     * [symbol]. [text] is bounded by [CANVAS_CARD_TEXT_LIMIT]; a [wholeFile] card shows its whole file.
     */
    fun open(parentId: String, kind: CardKind, anchor: CardAnchor, filePath: String, firstLine: Int, text: String,
        symbol: SymbolRef? = null, wholeFile: Boolean = false, openedBy: CardOpenedBy = CardOpenedBy.USER): CanvasCard {
        require(card(parentId) != null) { "Unknown parent card $parentId" }
        require(kind != CardKind.SYMBOL || symbol != null) { "A symbol card needs its symbol" }
        require(kind != CardKind.ROOT) { "A session has one root card" }
        findOpen(parentId, kind, anchor, symbol)?.let { return it }
        val (bounded, truncated) = boundCardText(text)
        val card = CanvasCard(ids(), parentId, kind, anchor, symbol, filePath, firstLine, bounded, truncated,
            wholeFile = wholeFile, openedBy = openedBy)
        session = session.copy(cards = session.cards + card, updated = clock())
        notify(CanvasChange.CardOpened(card))
        return card
    }

    /** The card open from [parentId] at the same anchor range for the same symbol, if any. */
    fun findOpen(parentId: String, kind: CardKind, anchor: CardAnchor, symbol: SymbolRef?): CanvasCard? = cards.firstOrNull {
        it.parentId == parentId && it.kind == kind && it.anchor.startOffset == anchor.startOffset &&
            it.anchor.endOffset == anchor.endOffset && sameSymbol(it.symbol, symbol)
    }

    /** Removes [cardId] and every card opened from it; returns the removed ids, empty when the card is unknown or the root. */
    fun remove(cardId: String): Set<String> {
        if (cardId == CANVAS_ROOT) return emptySet()
        val removed = session.subtree(cardId)
        if (removed.isEmpty()) return removed
        session = session.copy(cards = session.cards.filter { it.id !in removed }, updated = clock())
        notify(CanvasChange.CardsRemoved(removed))
        return removed
    }

    /** Attaches a file to a card, independently of the source language and available symbols. */
    fun attach(parentId: String, filePath: String, text: String, attachment: CanvasAttachment, truncated: Boolean = false): CanvasCard {
        val parent = requireNotNull(card(parentId)) { "Unknown parent card $parentId" }
        cards.firstOrNull { it.parentId == parentId && it.kind == CardKind.ATTACHMENT && it.filePath == filePath }?.let { return it }
        val (bounded, cut) = boundCardText(text)
        val card = CanvasCard(ids(), parentId, CardKind.ATTACHMENT, CardAnchor(0, 0, parent.firstLine),
            null, filePath, 0, bounded, truncated || cut, attachment = attachment)
        session = session.copy(cards = session.cards + card, updated = clock())
        notify(CanvasChange.CardOpened(card))
        return card
    }

    /** A finished summary, or with [error] a failed one; null [summary] and [error] reset it so it can be requested again. */
    fun setSummary(cardId: String, summary: String?, error: String? = null) {
        val text = summary?.takeIf { it.isNotBlank() }
        update(cardId) { it.copy(summary = text, summaryError = error) }
    }

    /** Lists [usages] on [cardId], replacing the list it had; null or no sites removes it. */
    fun setUsages(cardId: String, usages: CardUsages?) {
        update(cardId) { it.copy(usages = usages?.takeIf { found -> found.sites.isNotEmpty() }) }
    }

    /** Adds [summary] to [cardId]'s gap summaries, replacing any of overlapping lines. */
    fun addGapSummary(cardId: String, summary: GapSummary) {
        update(cardId) { it.copy(gapSummaries = withGapSummary(it.gapSummaries, summary)) }
    }

    /** Removes every gap summary of [cardId]. */
    fun clearGapSummaries(cardId: String) {
        update(cardId) { it.copy(gapSummaries = emptyList()) }
    }

    /** Resizes [cardId] to [size], bounded by [CardSize.bounded]; null (or both dimensions null) makes it automatic again. */
    fun resize(cardId: String, size: CardSize?) {
        update(cardId) { it.copy(size = size?.bounded()) }
    }

    /** Folds or unfolds [section] of [cardId]. */
    fun setFolded(cardId: String, section: CardSection, folded: Boolean) {
        update(cardId) { it.copy(folded = if (folded) it.folded + section else it.folded - section) }
    }

    /** Folds or unfolds [section]; returns whether it is folded now, or null when the card is unknown. */
    fun toggleFolded(cardId: String, section: CardSection): Boolean? {
        val card = card(cardId) ?: return null
        val folded = section !in card.folded
        setFolded(cardId, section, folded)
        return folded
    }

    /** Starts and selects a new chat of [cardId]; null when the card is unknown. */
    fun addChat(cardId: String): CanvasChat? {
        val card = card(cardId) ?: return null
        val number = generateSequence(1) { it + 1 }.first { n -> card.chats.none { it.title == "Chat $n" } }
        val chat = CanvasChat(ids(), "Chat $number")
        updateChats(cardId) { chats, _ -> chats + chat to chat.id }
        return chat
    }

    fun selectChat(cardId: String, chatId: String) = updateChats(cardId) { chats, active ->
        chats to if (chats.any { it.id == chatId }) chatId else active
    }

    /** Keep one conversation; select an adjacent tab when closing the selected one. */
    fun removeChat(cardId: String, chatId: String) = updateChats(cardId) { chats, active ->
        val index = chats.indexOfFirst { it.id == chatId }
        if (index < 0 || chats.size == 1) chats to active else {
            val remaining = chats.filter { it.id != chatId }
            remaining to if (active == chatId) remaining[index.coerceAtMost(remaining.lastIndex)].id else active
        }
    }

    fun setMessages(cardId: String, messages: List<SavedMessage>, chatId: String? = null) = updateChat(cardId, chatId) {
        it.copy(messages = messages)
    }

    fun setDraft(cardId: String, draft: String, chatId: String? = null) = updateChat(cardId, chatId) { it.copy(draft = draft) }

    private fun updateChat(cardId: String, chatId: String?, change: (CanvasChat) -> CanvasChat) = updateChats(cardId) { chats, active ->
        chats.map { if (it.id == (chatId ?: active)) change(it) else it } to active
    }

    /** Applies [change] to the chats and selected chat id of [cardId]. */
    private fun updateChats(cardId: String, change: (List<CanvasChat>, String) -> Pair<List<CanvasChat>, String>) {
        update(cardId) { card ->
            change(card.chats, card.activeChatId).let { (chats, active) -> card.copy(chats = chats, activeChatId = active) }
        }
    }

    /**
     * Replaces a restored card's snapshot with the current code: its [anchor] in the parent's shown text, and [text]
     * from document line [firstLine], bounded like [open]. Summary and chat stay.
     */
    fun restoreSnapshot(cardId: String, anchor: CardAnchor, firstLine: Int, text: String) = update(cardId) {
        if (it.kind == CardKind.ROOT) return@update it
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

    /** Opens a directory's starting files, [cards] opened from the root card, all at once; the root lists the directory. */
    internal fun initializeDirectory(cards: List<CanvasCard>, directoryCursor: String? = null) {
        check(!session.directoryInitialized && session.cards.size == 1)
        val root = session.root.copy(symbol = canvasRootCard(session.rootPath, directory = true).symbol)
        session = session.copy(cards = listOf(root) + cards, directoryInitialized = true, directoryCursor = directoryCursor,
            updated = clock())
    }

    /** Appends [cards] at once (e.g. a directory or pending-file batch) and updates [directoryCursor] and [pendingFiles]. */
    internal fun appendCards(
        cards: List<CanvasCard>,
        directoryCursor: String? = session.directoryCursor,
        pendingFiles: List<CanvasPendingFile> = session.pendingFiles,
    ) {
        val existing = session.cards.mapTo(hashSetOf()) { it.id }
        val fresh = cards.filter { it.id !in existing }
        if (fresh.isEmpty() && directoryCursor == session.directoryCursor && pendingFiles == session.pendingFiles) return
        session = session.copy(
            cards = session.cards + fresh,
            directoryCursor = directoryCursor,
            pendingFiles = pendingFiles,
            updated = clock(),
        )
        if (fresh.isNotEmpty()) notify(CanvasChange.CardsOpened(fresh))
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
