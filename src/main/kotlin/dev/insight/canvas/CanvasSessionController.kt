package dev.insight.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.insight.callgraph.ProgressiveSchematic
import dev.insight.graph.CallGraphNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * What one source pane shows: [text] from 0-based document line [firstLine] of project-relative [path], with [links]
 * as offsets into it. [file] colours and navigates it when known; [node] is the graph symbol it is the source of, and
 * [notice] a short note for the reader (external, not found, changed).
 */
class CanvasSource(
    val text: String,
    links: List<CanvasLink>,
    val firstLine: Int,
    val path: String,
    val file: VirtualFile?,
    val node: Int? = null,
    val notice: String? = null,
) {
    /** Links within the text, so a bounded snapshot never points past its end. */
    val links: List<CanvasLink> = links.filter { it.range.first >= 0 && it.range.last < text.length }
}

/**
 * The IDE side of one canvas session, without any Swing: builds the root file's source and each card's, opens cards
 * in [model] from links and selections, and resolves a saved session's symbols again. [exploration] is the graph of
 * [rootFile], whose FILE symbol is [rootNode]. Model mutations happen on the EDT; graph work runs in the background.
 */
internal class CanvasSessionController(
    private val project: Project,
    val rootFile: VirtualFile,
    private val exploration: ProgressiveSchematic,
    private val rootNode: Int,
    val model: CanvasSessionModel,
) : Disposable {
    private val sources = ConcurrentHashMap<String, CanvasSource>()
    /** The source of the card being opened; the model's listener takes it once the card has an id. */
    private var opening: CanvasSource? = null
    @Volatile var root: CanvasSource? = null
        private set

    // Subscribed first, so views listening to the model find each new card's source.
    private val subscription = model.subscribe { change ->
        when (change) {
            is CanvasChange.CardOpened -> opening?.let { sources[change.card.id] = it }.also { opening = null }
            is CanvasChange.CardsRemoved -> change.ids.forEach(sources::remove)
            else -> Unit
        }
    }

    fun node(id: Int): CallGraphNode? = exploration.result.graph.nodes.getOrNull(id)

    val rootName: String get() = node(rootNode)?.name ?: rootFile.name

    /** The source shown for [cardId], or the root file's for null; null while it is not built. */
    fun source(cardId: String?): CanvasSource? = if (cardId == null) root else sources[cardId]

    /** The project-relative form of an absolute [path]; other paths stay as they are. */
    fun relative(path: String?): String? = path?.let { relativePath(project, it) }

    /** The file of a project-relative or absolute [path], if it exists. */
    fun findFile(path: String): VirtualFile? {
        if (path.isEmpty()) return null
        if (path == model.session.rootPath) return rootFile
        val base = project.basePath
        val absolute = if (base != null && !path.startsWith("/")) "$base/$path" else path
        return LocalFileSystem.getInstance().findFileByPath(absolute)
    }

    /** Builds the root file's source; throws with a short message when it cannot be read. */
    suspend fun loadRoot(): CanvasSource {
        val schematic = withContext(Dispatchers.Default) { exploration.schematic(rootNode, whole = true) }
            ?: error("The source of ${rootName} is no longer available. Reopen the canvas session.")
        return CanvasSource(canvasText(schematic), canvasLinks(schematic), schematic.firstLine, model.session.rootPath,
            schematic.file, rootNode).also { root = it }
    }

    /**
     * Opens the card of graph symbol [target] from link [start] until [end] of [parentId]'s source (the root file for
     * null), or returns the card already open there. Targets outside the project open a card without source.
     */
    suspend fun openSymbol(parentId: String?, start: Int, end: Int, target: Int): CanvasCard {
        val parent = source(parentId) ?: error("The card to open from is not shown.")
        val node = node(target) ?: error("Unknown symbol #$target.")
        val anchor = cardAnchor(parent.text, start, end, parent.firstLine)
        val built = withContext(Dispatchers.Default) { symbolSource(node) }
        return withContext(Dispatchers.EDT) {
            model.findOpen(parentId, CardKind.SYMBOL, anchor, built.second)?.let { return@withContext it }
            checkNotNull(source(parentId)) { "The card to open from was closed." }
            open(built.first) { model.open(parentId, CardKind.SYMBOL, anchor, it.path, it.firstLine, it.text, built.second) }
        }
    }

    /** Opens a card of [start] until [end] of [parentId]'s source, or the one already open there; null for blank text. EDT. */
    fun openSelection(parentId: String?, start: Int, end: Int): CanvasCard? {
        val parent = source(parentId) ?: return null
        val from = start.coerceIn(0, parent.text.length)
        val to = end.coerceIn(from, parent.text.length)
        val text = parent.text.substring(from, to)
        if (text.isBlank()) return null
        val anchor = cardAnchor(parent.text, from, to, parent.firstLine)
        model.findOpen(parentId, CardKind.SELECTION, anchor, null)?.let { return it }
        val source = CanvasSource(text, linksInSlice(parent.links, from, to), anchor.line, parent.path, parent.file)
        return open(source) { model.open(parentId, CardKind.SELECTION, anchor, it.path, it.firstLine, it.text) }
    }

    /**
     * Resolves the cards of a saved session again, root-first, once [loadRoot] is done: a symbol card is looked up
     * among its parent's link targets, then the whole graph, and shows its current source (refreshing the saved
     * snapshot) or, when it is gone, its saved text. Selections keep their text. [onProgress] reports each card.
     */
    suspend fun restore(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val cards = model.cards
        cards.forEachIndexed { index, card ->
            withContext(Dispatchers.EDT) { onProgress(index + 1, cards.size) }
            val parent = source(card.parentId)
            val restored = try {
                when {
                    parent == null -> saved(card, CANVAS_SYMBOL_MISSING)
                    card.kind == CardKind.SELECTION -> {
                        val (links, changed) = restoredSelectionLinks(parent.text, parent.links, card)
                        CanvasSource(card.text, links, card.firstLine, card.filePath, findFile(card.filePath),
                            notice = CANVAS_SELECTION_CHANGED.takeIf { changed })
                    }
                    else -> restoreSymbol(card, parent)
                }
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Could not restore canvas card ${card.title} of ${model.session.rootPath}", e)
                saved(card, "Could not resolve this card again: ${e.message ?: e.javaClass.simpleName}.")
            }
            sources[card.id] = restored
        }
    }

    private suspend fun restoreSymbol(card: CanvasCard, parent: CanvasSource): CanvasSource {
        val ref = card.symbol ?: return saved(card, CANVAS_SYMBOL_MISSING)
        val nodes = exploration.result.graph.nodes
        val linked = linkTargets(parent.links).mapNotNull(::node)
        val found = find(ref, linked) ?: find(ref, nodes)
            ?: return saved(card, if (card.text.isEmpty()) CanvasTargetNote.EXTERNAL.notice else CANVAS_SYMBOL_MISSING)
        if (canvasTargetNote(found) != null) return saved(card, canvasTargetNote(found)!!.notice)
        val (fresh, _) = withContext(Dispatchers.Default) { symbolSource(found) }
        if (fresh.notice != null) return saved(card, fresh.notice)
        val anchor = restoredAnchor(parent.text, parent.firstLine, parent.links, found.id, card.anchor)
        val changed = sourceChanged(card, fresh.text)
        if (changed || anchor != card.anchor || fresh.firstLine != card.firstLine) withContext(Dispatchers.EDT) {
            model.restoreSnapshot(card.id, anchor, fresh.firstLine, fresh.text)
        }
        return CanvasSource(fresh.text, fresh.links, fresh.firstLine, fresh.path, fresh.file, found.id,
            CANVAS_SOURCE_CHANGED.takeIf { changed })
    }

    private suspend fun find(ref: SymbolRef, candidates: List<CallGraphNode>): CallGraphNode? {
        if (candidates.isEmpty()) return null
        val offsets = offsets(candidates.filter { it.name.substringBefore('(') == ref.name.substringBefore('(') })
        return restoreSymbol(ref, candidates, { relative(it.filePath) }, { offsets[it.id] })
    }

    private suspend fun offsets(nodes: List<CallGraphNode>): Map<Int, Int> = readAction {
        val pointers = exploration.result.pointers
        nodes.mapNotNull { node -> pointers.getOrNull(node.id)?.element?.takeIf { it.isValid }?.textOffset?.let { node.id to it } }.toMap()
    }

    /** The source of [node] and the reference a card saves for it. Off the EDT. */
    private suspend fun symbolSource(node: CallGraphNode): Pair<CanvasSource, SymbolRef> {
        val path = relative(node.filePath).orEmpty()
        val offset = offsets(listOf(node))[node.id] ?: -1
        val note = canvasTargetNote(node)
        val schematic = if (note == null) exploration.schematic(node.id, whole = true) else null
        val line = schematic?.firstLine ?: nodeLine(node) ?: 0
        val ref = SymbolRef(path, node.name, node.kind.name, node.container, offset, nodeLine(node) ?: line)
        val source = if (schematic == null) {
            CanvasSource("", emptyList(), line, path, null, node.id,
                note?.notice ?: "The source of ${node.name} is no longer available.")
        } else CanvasSource(canvasText(schematic), canvasLinks(schematic), schematic.firstLine,
            relative(schematic.file.path) ?: path, schematic.file, node.id)
        return source to ref
    }

    private fun saved(card: CanvasCard, notice: String) =
        CanvasSource(card.text, emptyList(), card.firstLine, card.filePath, findFile(card.filePath), notice = notice)

    private fun open(source: CanvasSource, open: (CanvasSource) -> CanvasCard): CanvasCard {
        opening = source
        try {
            return open(source)
        } finally {
            opening = null
        }
    }

    override fun dispose() {
        subscription.dispose()
        sources.clear()
    }

    private companion object {
        val LOG = logger<CanvasSessionController>()
    }
}

/** [path] relative to the project's base directory, or unchanged when it lies elsewhere. */
internal fun relativePath(project: Project, path: String): String =
    project.basePath?.let { base -> path.removePrefix("${base.trimEnd('/')}/") } ?: path
