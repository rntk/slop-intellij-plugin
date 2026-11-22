package dev.insight.canvas

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import dev.insight.callgraph.DeclarationSpan
import dev.insight.callgraph.ExploredGraph
import dev.insight.callgraph.GapSource
import dev.insight.callgraph.GapUnit
import dev.insight.callgraph.ProgressiveSchematic
import dev.insight.callgraph.Schematic
import dev.insight.callgraph.StaleSchematicException
import dev.insight.callgraph.schematicGapSource
import dev.insight.enrichment.liftCalls
import dev.insight.graph.CallGraph
import dev.insight.graph.CallGraphNode
import dev.insight.graph.CallGraphResult
import dev.insight.graph.CallOccurrence
import dev.insight.graph.RelationKind
import dev.insight.graph.SymbolKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * What one source pane shows: [text] from 0-based document line [firstLine] of project-relative [path], with [links]
 * as offsets into it. [file] colours and navigates it when known; [node] is the graph symbol it is the source of, and
 * [notice] a short note for the reader (external, not found, changed). [gaps] come from the schematic it was read from.
 */
class CanvasSource(
    val text: String,
    links: List<CanvasLink>,
    val firstLine: Int,
    val path: String,
    val file: VirtualFile?,
    val node: Int? = null,
    val notice: String? = null,
    /** Code between the source's symbols that can be summarized on request, in document lines. */
    val gaps: List<GapUnit> = emptyList(),
    val summarySpan: DeclarationSpan? = null,
) {
    /** Links within the text, so a bounded snapshot never points past its end. */
    val links: List<CanvasLink> = links.filter { it.range.first >= 0 && it.range.last < text.length }
}

/**
 * One project reference to a symbol found by [CanvasSessionController.findUsages]: [owner] is the enclosing
 * declaration in the graph (or FILE when at top level), [filePath] its absolute path, [path] project-relative, and
 * [line] 1-based.
 */
data class CanvasUsage(
    val owner: Int,
    val ownerName: String,
    val ownerLabel: String,
    val filePath: String,
    val path: String,
    val offset: Int,
    val line: Int,
    val kind: RelationKind,
)

data class CanvasUsageSearch(
    val target: Int,
    val targetName: String,
    val usages: List<CanvasUsage>,
    val truncated: Boolean,
)

/**
 * The IDE side of one canvas session, without any Swing: builds the root file's source and each card's, opens cards
 * in [model] from links and selections, and resolves a saved session's symbols again. [exploration] is the graph of
 * [rootFile], whose FILE symbol is [rootNode] when the root is a file. [reopen] reads it again for [refresh]. Model mutations happen on the
 * EDT; graph work runs in the background.
 */
internal class CanvasSessionController(
    private val project: Project,
    val rootFile: VirtualFile,
    exploration: ProgressiveSchematic,
    rootNode: Int?,
    val model: CanvasSessionModel,
    private val reopen: suspend () -> Pair<ProgressiveSchematic, Int?>? = { openExploration(project, rootFile) },
) : Disposable {
    // Replaced together by refresh: symbol ids belong to one exploration.
    @Volatile private var exploration: ProgressiveSchematic = exploration
    @Volatile private var rootNode: Int? = rootNode
    private val sources = ConcurrentHashMap<String, CanvasSource>()
    /** The source of the card being opened; the model's listener takes it once the card has an id. */
    private var opening: CanvasSource? = null
    private val pendingDirectory = mutableListOf<VirtualFile>()

    val pendingDirectoryCount: Int get() = pendingDirectory.size
    val pendingLinkedFiles: List<CanvasPendingFile> get() = model.session.pendingFiles

    // Subscribed first, so views listening to the model find each new card's source.
    private val subscription = model.subscribe { change ->
        when (change) {
            is CanvasChange.CardOpened -> opening?.let { sources[change.card.id] = it }.also { opening = null }
            is CanvasChange.CardsRemoved -> change.ids.forEach(sources::remove)
            else -> Unit
        }
    }

    fun node(id: Int): CallGraphNode? = exploration.result.graph.nodes.getOrNull(id)

    private fun directoryRoot(files: List<VirtualFile>): CanvasSource {
        val shown = if (files.size > DIRECTORY_ROOT_LIST_LIMIT) files.take(DIRECTORY_ROOT_LIST_LIMIT) else files
        val prefix = rootFile.path.trimEnd('/') + "/"
        val listing = buildString {
            append(shown.joinToString("\n") { it.path.removePrefix(prefix) })
            if (files.size > DIRECTORY_ROOT_LIST_LIMIT) {
                append("\n… and ${files.size - DIRECTORY_ROOT_LIST_LIMIT} more files")
            }
        }
        val notice = if (files.size > DIRECTORY_ROOT_LIST_LIMIT) {
            "Showing the first $DIRECTORY_ROOT_LIST_LIMIT of ${files.size} files in this directory."
        } else null
        return CanvasSource(listing, emptyList(), 0, model.session.rootPath, null, notice = notice)
    }

    private fun syncPendingDirectory(files: List<VirtualFile>) {
        pendingDirectory.clear()
        val cursor = model.session.directoryCursor ?: return
        if (!model.session.directoryInitialized) return
        val openPaths = openFilePaths()
        pendingDirectory += files.filter { file ->
            val rel = relativePath(project, file.path)
            rel > cursor && rel !in openPaths
        }
    }

    private fun openFilePaths(): Set<String> = model.cards
        .filter { it.kind != CardKind.ROOT && (it.wholeFile || it.kind == CardKind.ATTACHMENT) }
        .mapTo(hashSetOf()) { it.filePath }

    val rootName: String get() = rootNode?.let(::node)?.name ?: rootFile.name

    /** The source shown for [cardId]; null while it is not built. The root card's is read from [rootFile], never saved. */
    fun source(cardId: String): CanvasSource? = sources[cardId]

    /** The root file's source, once [loadRoot] built it. */
    val root: CanvasSource? get() = sources[CANVAS_ROOT]

    /** The project-relative form of an absolute [path]; other paths stay as they are. */
    fun relative(path: String?): String? = path?.let { relativePath(project, it) }

    private fun normalizePath(path: String): String = relative(path) ?: path

    /** The file of a project-relative or absolute [path], if it exists. */
    fun findFile(path: String): VirtualFile? {
        if (path.isEmpty()) return null
        if (path == model.session.rootPath || path == rootFile.path) return rootFile
        val base = project.basePath
        val absolute = if (base != null && !OSAgnosticPathUtil.isAbsolute(path)) "$base/$path" else path
        return rootFile.fileSystem.findFileByPath(absolute) ?: LocalFileSystem.getInstance().findFileByPath(absolute)
    }

    /** Builds the root file's source; throws with a short message when it cannot be read. */
    suspend fun loadRoot(): CanvasSource {
        if (rootFile.isDirectory) {
            val files = directoryCanvasFiles(project, rootFile)
            withContext(Dispatchers.EDT) { syncPendingDirectory(files) }
            return directoryRoot(files).also { sources[CANVAS_ROOT] = it }
        }
        val id = checkNotNull(rootNode)
        val schematic = withContext(Dispatchers.Default) { exploration.schematic(id, whole = true) }
            ?: error("The source of ${rootName} is no longer available. Reopen the canvas session.")
        return schematicSource(schematic, model.session.rootPath, id).also { sources[CANVAS_ROOT] = it }
    }

    /**
     * Reads the code again after it changed: a fresh exploration of [rootFile] replaces the current one, then every
     * card (or the [cardIds] subset when specified) is resolved again as [restore] does, keeping the cards, their chats
     * and layout. Symbol ids of the previous exploration mean nothing afterwards. Throws, leaving everything as it was,
     * when the root file cannot be read.
     */
    suspend fun refresh(cardIds: Set<String>? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): CanvasSource {
        val (next, nextRoot) = withContext(Dispatchers.Default) { reopen() }
            ?: error("The source of $rootName is no longer available.")
        val fresh = if (rootFile.isDirectory) {
            val files = directoryCanvasFiles(project, rootFile)
            withContext(Dispatchers.EDT) { syncPendingDirectory(files) }
            directoryRoot(files)
        } else {
            val id = checkNotNull(nextRoot)
            val schematic = withContext(Dispatchers.Default) { next.schematic(id, whole = true) }
                ?: error("The source of $rootName is no longer available.")
            schematicSource(schematic, model.session.rootPath, id)
        }
        exploration = next
        rootNode = nextRoot
        lifted = null
        sources.clear()
        sources[CANVAS_ROOT] = fresh
        restore(cardIds, onProgress)
        return fresh
    }

    /** Seed the directory in one model update, so interruption cannot save a partial set of cards. */
    suspend fun initializeDirectory(limit: Int = CANVAS_LOAD_CHUNK, onProgress: (Int, Int) -> Unit) {
        val files = directoryCanvasFiles(project, rootFile)
        val batch = if (limit > 0) files.take(limit) else emptyList()
        val remaining = files.drop(batch.size)
        val prepared = buildDirectoryCards(project, batch, onProgress)
        val cursor = if (remaining.isNotEmpty() && batch.isNotEmpty()) relativePath(project, batch.last().path) else null
        withContext(Dispatchers.EDT) {
            exploration = prepared.exploration
            lifted = null
            sources.putAll(prepared.sources)
            pendingDirectory.clear()
            pendingDirectory.addAll(remaining)
            model.initializeDirectory(prepared.cards, cursor)
        }
    }

    /** Loads up to [limit] more pending directory files and appends them to the session. */
    suspend fun loadMoreDirectoryFiles(
        limit: Int = CANVAS_LOAD_CHUNK,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<CanvasCard> {
        if (limit <= 0) return emptyList()
        data class Snapshot(
            val consumed: Int,
            val batch: List<VirtualFile>,
            val existingCards: Map<String, CanvasCard>,
            val existingSources: Map<String, CanvasSource>,
        )
        val snapshot = withContext(Dispatchers.EDT) {
            val open = openFilePaths()
            val picked = mutableListOf<VirtualFile>()
            var consumed = 0
            for (file in pendingDirectory) {
                if (picked.size >= limit) break
                consumed++
                if (!file.isValid || file.isDirectory) continue
                if (relativePath(project, file.path) in open) continue
                picked += file
            }
            val cardsByAbs = linkedMapOf<String, CanvasCard>()
            val sourcesById = linkedMapOf<String, CanvasSource>()
            for (card in model.cards) {
                if (card.kind == CardKind.ROOT || (!card.wholeFile && card.kind != CardKind.ATTACHMENT)) continue
                val src = sources[card.id] ?: continue
                val abs = src.file?.path ?: findFile(card.filePath)?.path ?: continue
                if (card.wholeFile || abs !in cardsByAbs) {
                    cardsByAbs[abs] = card
                    sourcesById[card.id] = src
                }
            }
            Snapshot(consumed, picked.toList(), cardsByAbs, sourcesById)
        }
        if (snapshot.consumed == 0) return emptyList()
        val (builtCards, builtSources) = if (snapshot.batch.isNotEmpty()) {
            buildDirectoryBatch(project, exploration, snapshot.existingCards, snapshot.existingSources, snapshot.batch, onProgress)
        } else {
            emptyList<CanvasCard>() to emptyMap()
        }
        return withContext(Dispatchers.EDT) {
            val dropCount = minOf(snapshot.consumed, pendingDirectory.size)
            var lastConsumed: VirtualFile? = null
            repeat(dropCount) { lastConsumed = pendingDirectory.removeAt(0) }
            val nextCursor = if (pendingDirectory.isEmpty()) null
                else lastConsumed?.let { relativePath(project, it.path) } ?: model.session.directoryCursor
            val finalCards = reconcileBatchParents(model.cards.mapTo(hashSetOf()) { it.id }, builtCards)
            lifted = null
            sources.putAll(builtSources)
            model.appendCards(finalCards, nextCursor)
            finalCards
        }
    }

    /** Computes pending file counts grouped by their deepest ancestor currently in [loadedIds]. EDT. */
    fun pendingLinkedByParent(loadedIds: Set<String>): Map<String, Int> {
        val pending = model.session.pendingFiles
        if (pending.isEmpty()) return emptyMap()
        val loadedByPath = loadedCardsByPath(loadedIds)
        val pendingParent = pending.associate { normalizePath(it.filePath) to it.parentPath?.let(::normalizePath) }
        val resolved = HashMap<String, String>(pending.size)
        val counts = LinkedHashMap<String, Int>()
        for (entry in pending) {
            val ancestor = resolveLoadedAncestor(entry, loadedByPath, pendingParent, resolved)
            counts[ancestor] = (counts[ancestor] ?: 0) + 1
        }
        return counts
    }

    /** Loads up to [limit] pending linked/diff files (optionally narrowed to [branchParentId]). */
    suspend fun loadMorePendingFiles(
        limit: Int = CANVAS_LOAD_CHUNK,
        branchParentId: String? = null,
        loadedIds: Set<String> = emptySet(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): PendingLoadResult {
        if (limit <= 0) return PendingLoadResult(emptyList())
        data class Snapshot(
            val selected: List<CanvasPendingFile>,
            val pendingParents: Map<String, String?>,
            val openPaths: Set<String>,
            val loadedByPath: Map<String, Pair<CanvasCard, CanvasSource?>>,
            val rootCardAndSource: Pair<CanvasCard, CanvasSource?>,
        )
        val snapshot = withContext(Dispatchers.EDT) {
            val pending = model.session.pendingFiles
            if (pending.isEmpty()) return@withContext null
            val activeIds = loadedIds.ifEmpty { model.cards.mapTo(hashSetOf()) { it.id } }
            val loadedMap = loadedCardsByPath(activeIds)
            val pendingParents = pending.associate { normalizePath(it.filePath) to it.parentPath?.let(::normalizePath) }
            val resolved = HashMap<String, String>(pending.size)
            val selected = if (branchParentId == null) pending.take(limit)
            else pending.filter { resolveLoadedAncestor(it, loadedMap, pendingParents, resolved) == branchParentId }.take(limit)
            if (selected.isEmpty()) return@withContext null
            val loadedCards = linkedMapOf<String, Pair<CanvasCard, CanvasSource?>>()
            val rootPair = model.session.root to sources[CANVAS_ROOT]
            loadedCards[normalizePath(model.session.rootPath)] = rootPair
            loadedCards[normalizePath(rootFile.path)] = rootPair
            for (card in model.cards) {
                if (card.id !in activeIds || card.kind == CardKind.ROOT || (!card.wholeFile && card.kind != CardKind.ATTACHMENT)) continue
                val src = sources[card.id]
                val path = normalizePath(src?.file?.path ?: card.filePath)
                if (card.wholeFile || path !in loadedCards) {
                    loadedCards[path] = card to src
                }
            }
            val openPaths = openFilePaths().mapTo(hashSetOf(), ::normalizePath).apply {
                add(normalizePath(model.session.rootPath))
                add(normalizePath(rootFile.path))
            }
            Snapshot(selected.toList(), pendingParents, openPaths, loadedCards, rootPair)
        } ?: return PendingLoadResult(emptyList())

        val createdCards = mutableListOf<CanvasCard>()
        val createdSources = LinkedHashMap<String, CanvasSource>()
        val createdByPath = HashMap<String, Pair<CanvasCard, CanvasSource>>()
        val consumed = HashSet<CanvasPendingFile>()
        val unavailable = mutableListOf<String>()
        var retryableError: String? = null
        withContext(Dispatchers.Default) {
            for ((index, entry) in snapshot.selected.withIndex()) {
                withContext(Dispatchers.EDT) { onProgress(index + 1, snapshot.selected.size) }
                val entryPath = normalizePath(entry.filePath)
                if (entryPath in snapshot.openPaths) {
                    consumed += entry
                    continue
                }
                val file = findFile(entry.filePath)
                if (file == null || !file.isValid || file.isDirectory) {
                    consumed += entry
                    unavailable += entryPath
                    continue
                }
                val schematic = try {
                    exploration.fileSchematic(file)
                } catch (e: Throwable) {
                    if (e is CancellationException || e is ControlFlowException) throw e
                    if (e is StaleSchematicException) {
                        LOG.info("Pending file ${entry.filePath} requires canvas refresh", e)
                    } else {
                        LOG.warn("Pending file ${entry.filePath} could not be analyzed", e)
                    }
                    retryableError = e.message ?: e.javaClass.simpleName
                    break
                }
                if (schematic == null) {
                    consumed += entry
                    unavailable += entryPath
                    continue
                }
                var parentCardAndSource: Pair<CanvasCard, CanvasSource?>? = null
                var p = entry.parentPath?.let(::normalizePath)
                val seen = hashSetOf(entryPath)
                while (p != null) {
                    val found = createdByPath[p] ?: snapshot.loadedByPath[p]
                    if (found != null) {
                        parentCardAndSource = found
                        break
                    }
                    if (!seen.add(p)) break
                    p = snapshot.pendingParents[p]
                }
                val (parentCard, parentSource) = parentCardAndSource ?: snapshot.rootCardAndSource
                val path = relative(file.path).orEmpty()
                val ref = SymbolRef(path, node(schematic.scope)?.name ?: file.name, SymbolKind.FILE.name, line = 0)
                val shown = schematicSource(schematic, path, schematic.scope)
                val anchor = parentSource?.let { fileConnectionAnchor(it, file.path, entry.offset, ::node) }
                    ?: CardAnchor(0, 0, 0)
                val (bounded, truncated) = boundCardText(shown.text)
                val card = CanvasCard(newCanvasId(), parentCard.id, CardKind.SYMBOL, anchor, ref, path, shown.firstLine,
                    bounded, truncated, wholeFile = true)
                createdCards += card
                createdSources[card.id] = shown
                createdByPath[entryPath] = card to shown
                consumed += entry
            }
        }
        return withContext(Dispatchers.EDT) {
            if (consumed.isEmpty()) {
                return@withContext PendingLoadResult(emptyList(), unavailable, retryableError)
            }
            val remaining = model.session.pendingFiles.filterNot { it in consumed }
            val finalCards = reconcileBatchParents(model.cards.mapTo(hashSetOf()) { it.id }, createdCards)
            lifted = null
            sources.putAll(createdSources)
            model.appendCards(finalCards, pendingFiles = remaining)
            PendingLoadResult(finalCards, unavailable, retryableError)
        }
    }

    private fun loadedCardsByPath(loadedIds: Set<String>): Map<String, String> {
        val loadedByPath = HashMap<String, String>()
        loadedByPath[normalizePath(model.session.rootPath)] = CANVAS_ROOT
        loadedByPath[normalizePath(rootFile.path)] = CANVAS_ROOT
        for (card in model.cards) {
            if (card.id !in loadedIds || card.kind == CardKind.ROOT || (!card.wholeFile && card.kind != CardKind.ATTACHMENT)) continue
            val path = normalizePath(sources[card.id]?.file?.path ?: card.filePath)
            if (card.wholeFile || path !in loadedByPath) {
                loadedByPath[path] = card.id
            }
        }
        return loadedByPath
    }

    private fun resolveLoadedAncestor(
        entry: CanvasPendingFile,
        loadedByPath: Map<String, String>,
        pendingParents: Map<String, String?>,
        cache: MutableMap<String, String> = HashMap(),
    ): String {
        val entryPath = normalizePath(entry.filePath)
        cache[entryPath]?.let { return it }
        val path = ArrayList<String>()
        val seen = hashSetOf(entryPath)
        var p = entry.parentPath?.let(::normalizePath)
        var resolved = CANVAS_ROOT
        while (p != null) {
            val loaded = loadedByPath[p]
            if (loaded != null) {
                resolved = loaded
                break
            }
            val cached = cache[p]
            if (cached != null) {
                resolved = cached
                break
            }
            if (!seen.add(p)) break
            path += p
            p = pendingParents[p]
        }
        cache[entryPath] = resolved
        for (node in path) cache[node] = resolved
        return resolved
    }

    /**
     * Opens the card of graph symbol [target] from link [start] until [end] of [parentId]'s source,
     * or returns the card already open there. Targets outside the project open a card without source.
     */
    suspend fun openSymbol(parentId: String, start: Int, end: Int, target: Int,
        openedBy: CardOpenedBy = CardOpenedBy.USER): CanvasCard {
        val parent = source(parentId) ?: error("The card to open from is not shown.")
        val node = node(target) ?: error("Unknown symbol #$target.")
        val anchor = cardAnchor(parent.text, start, end, parent.firstLine)
        val built = withContext(Dispatchers.Default) { symbolSource(node) }
        return withContext(Dispatchers.EDT) {
            model.findOpen(parentId, CardKind.SYMBOL, anchor, built.second)?.let { return@withContext it }
            checkNotNull(source(parentId)) { "The card to open from was closed." }
            open(built.first) { model.open(parentId, CardKind.SYMBOL, anchor, it.path, it.firstLine, it.text, built.second,
                openedBy = openedBy) }
        }
    }

    /** Opens a card of [start] until [end] of [parentId]'s source, or the one already open there; null for blank text. EDT. */
    fun openSelection(parentId: String, start: Int, end: Int, openedBy: CardOpenedBy = CardOpenedBy.USER): CanvasCard? {
        val parent = source(parentId) ?: return null
        val from = start.coerceIn(0, parent.text.length)
        val to = end.coerceIn(from, parent.text.length)
        val text = parent.text.substring(from, to)
        if (text.isBlank()) return null
        val anchor = cardAnchor(parent.text, from, to, parent.firstLine)
        model.findOpen(parentId, CardKind.SELECTION, anchor, null)?.let { return it }
        val source = CanvasSource(text, linksInSlice(parent.links, from, to), anchor.line, parent.path, parent.file)
        return open(source) { model.open(parentId, CardKind.SELECTION, anchor, it.path, it.firstLine, it.text, openedBy = openedBy) }
    }

    /** Attaches any local file, including files outside the project and languages absent from the graph. */
    suspend fun attachFile(parentId: String, file: VirtualFile): CanvasCard {
        require(!file.isDirectory) { "Choose a file, not a directory." }
        val content = withContext(Dispatchers.IO) {
            val mediaType = java.nio.file.Files.probeContentType(java.nio.file.Path.of(file.path)) ?: "application/octet-stream"
            file.inputStream.use { readCanvasAttachment(it, file.length, mediaType, file.fileType.isBinary, file.charset) }
        }
        val path = relative(file.path).orEmpty()
        val shown = CanvasSource(content.text, emptyList(), 0, path, file, notice = attachmentNotice(content.attachment))
        return withContext(Dispatchers.EDT) {
            open(shown) { model.attach(parentId, path, content.text, content.attachment, content.truncated) }
        }
    }

    /**
     * Opens a card showing all of [file], anchored at [start] until [end] of [parentId]'s source,
     * or returns the card of that file already open there. For files the chain does not lead to. [built] is the file's
     * whole-file schematic when the caller already has it.
     */
    suspend fun openFile(parentId: String, start: Int, end: Int, file: VirtualFile,
        openedBy: CardOpenedBy = CardOpenedBy.USER, built: Schematic? = null): CanvasCard {
        val parent = source(parentId) ?: error("The card to open from is not shown.")
        val anchor = cardAnchor(parent.text, start, end, parent.firstLine)
        val path = relative(file.path).orEmpty()
        val schematic = built ?: withContext(Dispatchers.Default) { exploration.fileSchematic(file) }
            ?: error("The file $path cannot be shown on the canvas; its language may not be supported.")
        val ref = SymbolRef(path, node(schematic.scope)?.name ?: file.name, SymbolKind.FILE.name, line = 0)
        val shown = schematicSource(schematic, path, schematic.scope)
        return withContext(Dispatchers.EDT) {
            model.findOpen(parentId, CardKind.SYMBOL, anchor, ref)?.let { return@withContext it }
            checkNotNull(source(parentId)) { "The card to open from was closed." }
            open(shown) { model.open(parentId, CardKind.SYMBOL, anchor, path, it.firstLine, it.text, ref, wholeFile = true,
                openedBy = openedBy) }
        }
    }

    /**
     * Opens all of [file] from [start] until [end] of [parentId]'s source like [openFile], or attaches it like [attachFile]
     * when it is binary or in a language the graph cannot read, so any local file can be shown on the canvas.
     */
    suspend fun openOrAttachFile(parentId: String, start: Int, end: Int, file: VirtualFile): CanvasCard {
        val schematic = if (file.fileType.isBinary) null else withContext(Dispatchers.Default) { exploration.fileSchematic(file) }
        return if (schematic != null) openFile(parentId, start, end, file, built = schematic) else attachFile(parentId, file)
    }

    /** The resolved symbols named [name] (without parameters) declared in [file], once the file is in the graph. */
    suspend fun declarations(file: VirtualFile, name: String): List<CallGraphNode> {
        withContext(Dispatchers.Default) { exploration.fileSchematic(file) } ?: return emptyList()
        return exploration.result.graph.nodes.filter {
            it.filePath == file.path && it.kind != SymbolKind.FILE && canvasTargetNote(it) == null && it.name.substringBefore('(') == name
        }
    }

    /** Graph symbol [id] as a card's chat names it. */
    fun target(id: Int): CanvasTarget? = node(id)?.let { node ->
        CanvasTarget(node.id, node.name, canvasTargetLabel(node, ::relative), relative(node.filePath), nodeLine(node)?.plus(1),
            canvasTargetNote(node) == null)
    }

    /** Whether [node] is a project symbol whose usages can be searched. */
    fun canFindUsages(node: Int?): Boolean {
        val target = node?.let(::node) ?: return false
        return !target.unresolved && target.kind != SymbolKind.FILE
    }

    /**
     * Searches project files for references to graph symbol [target], adding the declarations of referencing files to
     * the exploration without resolving their bodies. References whose enclosing declaration is [target] itself are
     * left out.
     */
    suspend fun findUsages(target: Int, limit: Int = 100): CanvasUsageSearch {
        val targetNode = node(target) ?: error("Unknown symbol #$target.")
        val found = withBackgroundProgress(project, "Finding usages of ${targetNode.name}") {
            withContext(Dispatchers.Default) { exploration.findUsages(target, limit) }
        }
        val usages = found.usages.filterNot { it.owner == target }.mapNotNull { usage ->
            val ownerNode = node(usage.owner) ?: return@mapNotNull null
            val relative = relative(usage.filePath).orEmpty()
            CanvasUsage(
                owner = usage.owner,
                ownerName = ownerNode.name,
                ownerLabel = canvasTargetLabel(ownerNode, ::relative),
                filePath = usage.filePath,
                path = relative,
                offset = usage.offset,
                line = usage.line,
                kind = usage.kind,
            )
        }
        return CanvasUsageSearch(target, targetNode.name, usages, found.truncated)
    }

    /** Where a card opened from [cardId]'s usage list anchors in [cardId]'s source: its declared name or first line. */
    fun usageAnchor(cardId: String): IntRange {
        val source = source(cardId) ?: return 0 until 0
        val name = source.node?.let(::node)?.name?.substringBefore('(')?.substringBefore('<')?.substringBefore(':')?.trim()?.takeIf { it.isNotBlank() }
        val match = name?.let { Regex("\\b${Regex.escape(it)}\\b").find(source.text) }
        if (match != null) return match.range
        val firstLine = source.text.lineSequence().firstOrNull().orEmpty()
        val start = firstLine.indexOfFirst { !it.isWhitespace() }
        return if (start < 0) 0 until firstLine.length else start..firstLine.indexOfLast { !it.isWhitespace() }
    }

    /** Replaces an excerpt with its full file and links, keeping the card's identity, chat and children. */
    suspend fun expandToFile(cardId: String) {
        val card = model.card(cardId) ?: return
        if (card.wholeFile) return
        val previous = source(cardId) ?: error("The card's source is not shown.")
        val fresh = withContext(Dispatchers.Default) { fileSource(card) }
            ?: error("The file ${card.filePath} is no longer available.")
        // A selection may start within a line, so locate its exact text from that document line.
        val lineOffset = fresh.text.lineSequence().take(previous.firstLine).sumOf { it.length + 1 }
        val offset = fresh.text.indexOf(previous.text, lineOffset).takeIf { it >= 0 }
            ?: error("Source changed. Refresh the canvas to expand this card.")
        val anchors = withContext(Dispatchers.Default) {
            val candidates = linkTargets(fresh.links).mapNotNull(::node)
            model.children(cardId).mapNotNull { child ->
                val ref = child.symbol ?: return@mapNotNull null
                val target = find(ref, candidates) ?: return@mapNotNull null
                val shifted = child.anchor.copy(startOffset = child.anchor.startOffset + offset,
                    endOffset = child.anchor.endOffset + offset)
                child.id to restoredAnchor(fresh.text, fresh.firstLine, fresh.links, target.id, shifted)
            }.toMap()
        }
        withContext(Dispatchers.EDT) {
            val current = model.card(cardId) ?: return@withContext
            if (current.wholeFile) return@withContext
            sources[cardId] = fresh
            model.expandToFile(cardId, fresh.text, offset, anchors)
        }
    }

    private suspend fun fileSource(card: CanvasCard): CanvasSource? {
        val file = findFile(card.filePath) ?: return null
        val schematic = exploration.fileSchematic(file) ?: return null
        return schematicSource(schematic, card.filePath, schematic.scope)
    }

    /**
     * Resolves the cards of a saved session again, root-first, once [loadRoot] is done: a symbol card is looked up
     * among its parent's link targets, then the whole graph, and shows its current source (refreshing the saved
     * snapshot) or, when it is gone, its saved text. Selections keep their text. When [cardIds] is non-null, only
     * those cards are restored. [onProgress] reports each card.
     */
    suspend fun restore(cardIds: Set<String>? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        // The root card's source is read with the root file.
        val cards = model.cards.filter { it.kind != CardKind.ROOT && (cardIds == null || it.id in cardIds) }
        restoreCards(cards, onProgress)
    }

    /**
     * Resolves [cards] (in root-first order) that do not yet have a live source in [sources].
     */
    suspend fun restoreCards(cards: List<CanvasCard>, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val pending = cards.filter { it.kind != CardKind.ROOT && !sources.containsKey(it.id) }
        pending.forEachIndexed { index, card ->
            withContext(Dispatchers.EDT) { onProgress(index + 1, pending.size) }
            val parent = card.parentId?.let(::source)
            val restored = try {
                when {
                    card.kind == CardKind.ATTACHMENT -> saved(card,
                        if (findFile(card.filePath) == null) "Linked file not found; showing the saved attachment snapshot."
                        else attachmentNotice(card.attachment))
                    card.wholeFile -> {
                        val fresh = withContext(Dispatchers.Default) { fileSource(card) }
                        if (fresh == null) saved(card, "File not found; showing the saved source without links.")
                        else {
                            val changed = sourceChanged(card, fresh.text)
                            withContext(Dispatchers.EDT) { model.restoreSnapshot(card.id, card.anchor, fresh.firstLine, fresh.text) }
                            CanvasSource(fresh.text, fresh.links, fresh.firstLine, fresh.path, fresh.file, fresh.node,
                                CANVAS_SOURCE_CHANGED.takeIf { changed }, fresh.gaps)
                        }
                    }
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
        // A card opened at a declaration its parent does not link to is found by reading the symbol's file.
        val found = find(ref, linked) ?: find(ref, nodes) ?: findInFile(ref)
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
            CANVAS_SOURCE_CHANGED.takeIf { changed }, fresh.gaps, fresh.summarySpan)
    }

    private suspend fun find(ref: SymbolRef, candidates: List<CallGraphNode>): CallGraphNode? {
        if (candidates.isEmpty()) return null
        val offsets = offsets(candidates.filter { it.name.substringBefore('(') == ref.name.substringBefore('(') })
        return restoreSymbol(ref, candidates, { relative(it.filePath) }, { offsets[it.id] })
    }

    private suspend fun findInFile(ref: SymbolRef): CallGraphNode? {
        val file = findFile(ref.filePath)?.takeIf { it.isValid && !it.isDirectory } ?: return null
        // Finding one saved card must not resolve every other function in its file.
        withContext(Dispatchers.Default) { exploration.fileDeclarations(file) } ?: return null
        return find(ref, exploration.result.graph.nodes.filter { it.filePath == file.path })
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
        } else schematicSource(schematic, relative(schematic.file.path) ?: path, node.id)
        return source to ref
    }

    /**
     * What the gap summary of [unit] in [cardId]'s source is written from: the file as it is
     * now, with the IDE's facts about the gap. Throws when the file changed since the source was read.
     */
    suspend fun gapSource(cardId: String, unit: GapUnit): GapSource {
        val source = source(cardId) ?: error("The source is not shown.")
        val file = source.file?.takeIf { it.isValid } ?: error("The file ${source.path} is no longer available.")
        return smartReadAction(project) { schematicGapSource(project, file, source.firstLine, source.text.split('\n'), unit) }
    }

    /** The summary of [id] shown by a card of that symbol, or the root file's for its FILE symbol. */
    private fun summaryOf(id: Int): String? =
        if (id == rootNode) model.session.root.summary
        else model.cards.firstOrNull { it.summary != null && !it.wholeFile && sources[it.id]?.node == id }?.summary

    @Volatile private var lifted: Pair<CallGraphResult, CallGraph>? = null

    /** The graph explored so far with calls from locals lifted to their function, computed once per snapshot. */
    private fun liftedGraph(): Pair<CallGraph, List<CallOccurrence>> {
        val result = exploration.result
        val graph = lifted?.takeIf { it.first === result }?.second ?: liftCalls(result.graph).also { lifted = result to it }
        return graph to result.occurrences
    }

    private val explored = ExploredGraph(::liftedGraph, ::summaryOf, { relative(it) ?: it }) { exploration.expand(it) }

    /** The explored graph as the chats' graph tools query it. */
    val graph: CanvasGraph = object : CanvasGraph {
        override fun findSymbols(query: String, kind: SymbolKind?, limit: Int) = explored.find(query, kind, limit)
        override suspend fun inspect(id: Int): String = withContext(Dispatchers.Default) {
            explored.load(id)
            explored.inspect(id)
        }
        override fun callPaths(from: Int?, roots: List<Int>, target: Int, maxDepth: Int) = explored.paths(from, roots, target, maxDepth)
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
        const val DIRECTORY_ROOT_LIST_LIMIT = 2_000
    }
}

/** A fresh exploration with a FILE symbol for a file root. Off the EDT. */
internal suspend fun openExploration(project: Project, file: VirtualFile): Pair<ProgressiveSchematic, Int?>? {
    if (file.isValid && file.isDirectory) return ProgressiveSchematic.empty(project) to null
    val exploration = ProgressiveSchematic.open(project, file) ?: return null
    val root = exploration.result.graph.nodes.firstOrNull { it.kind == SymbolKind.FILE && it.filePath == file.path } ?: return null
    return exploration to root.id
}

/** [path] relative to the project's base directory, or unchanged when it lies elsewhere. */
internal fun relativePath(project: Project, path: String): String =
    project.basePath?.let { base -> path.removePrefix("${base.trimEnd('/')}/") } ?: path

internal fun schematicSource(schematic: Schematic, path: String, node: Int) = CanvasSource(canvasText(schematic),
    canvasLinks(schematic), schematic.firstLine, path, schematic.file, node, gaps = schematic.gaps,
    summarySpan = schematic.summarySpan)

internal fun attachmentNotice(attachment: CanvasAttachment?): String =
    if (attachment?.imageBase64 != null) "Image snapshot. Open file to view the original in the IDE."
    else "Linked attachment. Open file to view the original in the IDE."
