package dev.insight.board.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.insight.board.Board
import dev.insight.board.BoardEntry
import dev.insight.board.BoardModel
import dev.insight.board.BoardOrigin
import dev.insight.board.BoardSources
import dev.insight.board.BoardStore
import dev.insight.board.boardEntryLabel
import dev.insight.board.boardOriginKey
import dev.insight.core.describeFailure
import dev.insight.core.runCatchingReported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

private val LOG = logger<BoardLauncher>()

/**
 * A review tab's way into explainer boards: a menu of a new board and the saved boards of the tab's origin, and the
 * count of those for the tab's tile. Sources are built off the EDT under a background progress, the same way for new
 * and reopened boards. Runs its work in the tab's [scope] (EDT); [countChanged] asks the tab to refresh its tiles.
 */
internal class BoardLauncher(private val project: Project, private val scope: CoroutineScope, private val countChanged: () -> Unit) {
    private val store = BoardStore.of(project)
    private var count: Pair<String, Int>? = null
    private var countedAt = 0L
    private var counting = false

    /**
     * The number of saved boards of [origin] from the last count, or null before one; a missing or stale count is
     * recounted off the EDT and reported through [countChanged]. EDT; reads nothing itself.
     */
    fun savedCount(origin: BoardOrigin): Int? {
        val key = boardOriginKey(origin)
        val known = count?.takeIf { it.first == key }?.second
        if (!counting && (known == null || System.currentTimeMillis() - countedAt > RECOUNT_MS)) {
            counting = true
            scope.launch {
                try {
                    remember(key, withContext(Dispatchers.IO) { store?.list(key)?.size ?: 0 })
                } finally {
                    counting = false
                }
            }
        }
        return known
    }

    /**
     * Shows the board menu of [origin] below [anchor]: New board, then its saved boards newest first. Without saved
     * boards, or without an anchor on screen, a new board opens directly. [sources] builds the board's sources; it is
     * called off the EDT.
     */
    fun show(anchor: JComponent?, origin: BoardOrigin, sources: suspend () -> BoardSources) {
        scope.launch {
            val key = boardOriginKey(origin)
            val entries = withContext(Dispatchers.IO) { store?.list(key).orEmpty() }
            remember(key, entries.size)
            if (entries.isEmpty() || anchor == null || !anchor.isShowing) {
                open(origin, null, sources)
                return@launch
            }
            menu(entries, origin, sources).show(anchor, 0, anchor.height)
        }
    }

    private fun menu(entries: List<BoardEntry>, origin: BoardOrigin, sources: suspend () -> BoardSources) = JPopupMenu().apply {
        add(JMenuItem("New board").apply {
            toolTipText = "Start an empty board and ask its chat to explain"
            addActionListener { open(origin, null, sources) }
        })
        addSeparator()
        entries.take(MAX_LISTED).forEach { entry ->
            add(JMenuItem(boardEntryLabel(entry)).apply {
                toolTipText = "Reopen this board with its chat"
                addActionListener { open(origin, entry.id, sources) }
            })
        }
    }

    /** Opens the saved board [id], or a new board of [origin] when null; a board already open is only brought forward. */
    private fun open(origin: BoardOrigin, id: String?, sources: suspend () -> BoardSources) {
        val manager = FileEditorManager.getInstance(project)
        if (id != null) manager.openFiles.filterIsInstance<BoardFile>().firstOrNull { it.model.board.id == id }?.let {
            manager.openFile(it, true)
            return
        }
        scope.launch {
            runCatchingReported(LOG, "Opening an explainer board failed") {
                val (saved, built) = withBackgroundProgress(project, "Opening explainer board") {
                    withContext(Dispatchers.Default) {
                        val saved = id?.let { withContext(Dispatchers.IO) { store?.load(it) } }
                        if (id != null && saved == null) null to null else saved to sources()
                    }
                }
                if (built == null) {
                    notify("The saved board could not be read; see the IDE log.")
                    return@runCatchingReported
                }
                val board = saved?.board ?: Board.new("Explainer: ${origin.title}".take(MAX_TITLE), origin)
                manager.openFile(BoardFile(project, BoardModel(board), built, board.origin ?: origin, saved?.chat.orEmpty(), store,
                    saved != null), true)
            }.onFailure { notify("Could not open the board: ${describeFailure(it)}") }
        }
    }

    private fun remember(key: String, saved: Int) {
        val changed = count != key to saved
        count = key to saved
        countedAt = System.currentTimeMillis()
        if (changed) countChanged()
    }

    private fun notify(text: String) {
        if (project.isDisposed) return
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Explainer board", text, NotificationType.WARNING).notify(project)
    }

    private companion object {
        const val RECOUNT_MS = 10_000L
        const val MAX_LISTED = 30
        const val MAX_TITLE = 120
    }
}
