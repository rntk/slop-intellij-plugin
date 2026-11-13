package dev.insight.board

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import dev.insight.agent.ChatMessage
import dev.insight.agent.ChatRole
import kotlinx.coroutines.CancellationException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/*
 * Saved explainer boards: one JSON file per board, the [BoardJson] form with the board chat's questions and answers
 * beside it under "chats" (which the codec ignores). Swing-free and PSI-free; every store call does file IO.
 */

private val LOG = logger<BoardStore>()

/** A board and the questions and answers of its chat, as saved together. */
data class SavedBoard(val board: Board, val chat: List<ChatMessage> = emptyList())

/** A saved board as listed for the reader to pick one, without its blocks. */
data class BoardEntry(val id: String, val title: String, val origin: BoardOrigin?, val created: Long, val updated: Long, val blocks: Int)

/** What boards of one comparison or scope share: [BoardStore.list] filters by it. */
fun boardOriginKey(origin: BoardOrigin): String = "${origin.kind}:${origin.title}"

/**
 * Boards saved as `<id>.json` in [directory]. Call off the EDT. A damaged, unreadable or newer-version file is skipped
 * and logged, a failed write is logged; nothing throws to callers.
 */
class BoardStore(private val directory: Path) {
    /** Saved boards, newest first; only those whose origin has [originKey] (see [boardOriginKey]) when it is set. */
    fun list(originKey: String? = null): List<BoardEntry> {
        val files = runCatching { if (Files.isDirectory(directory)) directory.listDirectoryEntries("*$SUFFIX") else emptyList() }
            .onFailure { rethrowCancellation(it); LOG.warn("Could not list boards in $directory", it) }.getOrDefault(emptyList())
        return files.filter { it.isRegularFile() }.mapNotNull { file -> read(file)?.board }
            .filter { originKey == null || it.origin?.let(::boardOriginKey) == originKey }
            .map { BoardEntry(it.id, it.title, it.origin, it.created, it.updated, it.blocks.size) }
            .sortedWith(compareByDescending<BoardEntry> { it.updated }.thenBy { it.id })
    }

    /** The saved board [id] with its chat, or null when it is missing or unreadable. */
    fun load(id: String): SavedBoard? = file(id)?.let(::read)

    /** Saves [board] and [chat] (only its USER and ASSISTANT messages) atomically; returns whether it was written. */
    fun save(board: Board, chat: List<ChatMessage> = emptyList()): Boolean = synchronized(this) {
        val file = file(board.id) ?: return false
        runCatching {
            Files.createDirectories(directory)
            // Boards hold the reader's chats; keep them out of Git without editing the project's ignore rules.
            val ignore = directory.resolve(".gitignore")
            if (!Files.exists(ignore)) runCatching { Files.writeString(ignore, "*\n", StandardOpenOption.CREATE_NEW) }
                .onFailure { rethrowCancellation(it); LOG.debug("Could not create $ignore", it) }
            val temp = Files.createTempFile(directory, "board-", ".tmp")
            try {
                Files.writeString(temp, encodeBoardFile(SavedBoard(board, chat)))
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    LOG.debug("Atomic move not supported; falling back to a plain move", e)
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temp)
            }
        }.onFailure { rethrowCancellation(it); LOG.warn("Could not save board ${board.id}", it) }.isSuccess
    }

    /** Deletes the saved board [id]; returns whether a file was removed. */
    fun delete(id: String): Boolean = synchronized(this) {
        val file = file(id) ?: return false
        runCatching { Files.deleteIfExists(file) }
            .onFailure { rethrowCancellation(it); LOG.warn("Could not delete board $id", it) }.getOrDefault(false)
    }

    /** Ids come from [newBoardId]; anything else could name a file outside the directory. */
    private fun file(id: String): Path? = if (ID.matches(id)) directory.resolve("$id$SUFFIX") else {
        LOG.warn("Ignoring board with invalid id '$id'")
        null
    }

    private fun read(file: Path): SavedBoard? = runCatching {
        decodeBoardFile(Files.readString(file)).also { if (it == null) LOG.info("Skipping damaged or newer board $file") }
    }.onFailure {
        rethrowCancellation(it)
        if (it is NoSuchFileException) LOG.debug("No board at $file") else LOG.warn("Could not read board $file", it)
    }.getOrNull()

    companion object {
        private const val SUFFIX = ".json"
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")

        /** Below the project's `.insight` directory, beside the canvas sessions and review cache. */
        const val DIRECTORY = "boards"

        /** The store of [project], or null when it has no base directory. */
        fun of(project: Project): BoardStore? = project.basePath?.let { BoardStore(Path.of(it, ".insight", DIRECTORY)) }
    }
}

private val gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

/** [saved] as a board file: the [BoardJson] object with a "chats" array of one chat's USER and ASSISTANT messages. */
internal fun encodeBoardFile(saved: SavedBoard): String {
    val root = BoardJson.encode(saved.board)
    val messages = JsonArray()
    saved.chat.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }.forEach { message ->
        messages.add(JsonObject().apply {
            addProperty("role", message.role.name.lowercase())
            addProperty("text", message.text)
        })
    }
    root.add("chats", JsonArray().apply { if (!messages.isEmpty) add(JsonObject().apply { add("messages", messages) }) })
    return gson.toJson(root)
}

/**
 * The board and chat of a board file; null when it is not one, or was written by a newer version. Messages of unknown
 * roles or without text are dropped; only the first chat is read.
 */
internal fun decodeBoardFile(text: String): SavedBoard? {
    val root = runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val version = root.get("version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
    if (version != null && version > BoardJson.VERSION) return null
    val board = BoardJson.decode(root) ?: return null
    val chat = runCatching {
        val first = root.get("chats")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        first?.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray.orEmpty().mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val role = when (obj.get("role")?.takeIf { it.isJsonPrimitive }?.asString?.lowercase()) {
                "user" -> ChatRole.USER
                "assistant" -> ChatRole.ASSISTANT
                else -> return@mapNotNull null
            }
            val body = obj.get("text")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return@mapNotNull null
            ChatMessage(role, body)
        }
    }.getOrDefault(emptyList())
    return SavedBoard(board, chat)
}

private fun JsonArray?.orEmpty(): List<com.google.gson.JsonElement> = this?.toList().orEmpty()

private fun rethrowCancellation(e: Throwable) {
    if (e is CancellationException || e is ProcessCanceledException) throw e
}
