package dev.insight.canvas

/** Default number of non-root cards or files loaded per chunk so large graphs stay responsive. */
internal const val CANVAS_LOAD_CHUNK = 60

/** Maximum cards with active `EditorEx` viewers before off-screen viewers begin hibernating. */
internal const val CANVAS_ACTIVE_EDITOR_LIMIT = 80

/** Target number of active `EditorEx` viewers retained after pruning off-screen viewers. */
internal const val CANVAS_ACTIVE_EDITOR_TARGET = 64

/** Synthetic layout id prefix for inline continuation cards (`more:<parentId>`). */
internal const val CANVAS_MORE_PREFIX = "more:"

/** Estimated pixel height per source line at zoom 1 used for hibernated card placeholders. */
internal const val CANVAS_ESTIMATED_LINE_HEIGHT = 18

/**
 * Summary of unloaded cards or pending files attached under a currently loaded [parentId].
 */
internal data class CanvasDeferredBranch(
    val parentId: String,
    val deferredCards: Int,
    val pendingFiles: Int = 0,
    val isDirectoryFiles: Boolean = false,
) {
    val total: Int get() = deferredCards + pendingFiles
    val id: String get() = "$CANVAS_MORE_PREFIX$parentId"

    fun title(): String = buildList {
        if (deferredCards > 0) add("+$deferredCards more ${if (deferredCards == 1) "card" else "cards"}")
        if (pendingFiles > 0) add("+$pendingFiles more ${if (pendingFiles == 1) "file" else "files"}")
    }.joinToString(", ")

    fun actionLabel(chunkSize: Int = CANVAS_LOAD_CHUNK): String =
        if (total <= chunkSize) "Load all $total" else "Load next $chunkSize"
}

/** Bounds of one loaded card on the canvas, in viewport-relative canvas coordinates. */
internal data class CanvasCardBounds(val id: String, val x: Int, val y: Int, val width: Int, val height: Int)

/** Visible canvas viewport rectangle, in the same coordinate space as [CanvasCardBounds]. */
internal data class CanvasViewportRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Editors to instantiate ([materialize]) or release ([hibernate]) for the current viewport. */
internal data class CanvasEditorPlan(val materialize: Set<String>, val hibernate: Set<String>)

/**
 * Selects the initial rooted BFS subset of card ids containing [CANVAS_ROOT] and up to [limit] non-root cards.
 * The returned set is always ancestor-closed.
 */
internal fun selectInitialLoadedCards(cards: List<CanvasCard>, limit: Int = CANVAS_LOAD_CHUNK): LinkedHashSet<String> {
    val loaded = LinkedHashSet<String>()
    loaded += CANVAS_ROOT
    val maxNonRoot = limit.coerceAtLeast(0)
    if (maxNonRoot == 0) return loaded
    val childrenByParent = cards.filter { it.parentId != null }.groupBy { it.parentId!! }
    val queue = ArrayDeque<String>()
    queue.add(CANVAS_ROOT)
    var added = 0
    while (queue.isNotEmpty() && added < maxNonRoot) {
        val parentId = queue.removeFirst()
        for (child in childrenByParent[parentId].orEmpty()) {
            if (added >= maxNonRoot) break
            if (child.id == CANVAS_ROOT) continue
            if (loaded.add(child.id)) {
                added++
                queue.add(child.id)
            }
        }
    }
    return loaded
}

/**
 * Selects up to [limit] unloaded cards in BFS order starting from [branchParentId] (if provided) or across all loaded
 * cards in BFS order from [CANVAS_ROOT]. Parents always precede their children in the returned list.
 */
internal fun selectNextLoadedCards(
    cards: List<CanvasCard>,
    loadedIds: Set<String>,
    limit: Int = CANVAS_LOAD_CHUNK,
    branchParentId: String? = null,
): List<CanvasCard> {
    if (limit <= 0) return emptyList()
    val childrenByParent = cards.filter { it.parentId != null }.groupBy { it.parentId!! }
    val seen = HashSet(loadedIds)
    val queue = ArrayDeque<CanvasCard>()
    if (branchParentId != null) {
        if (branchParentId !in loadedIds) return emptyList()
        for (child in childrenByParent[branchParentId].orEmpty()) {
            if (seen.add(child.id)) queue.add(child)
        }
    } else {
        val loadedQueue = ArrayDeque<String>()
        val visitedLoaded = hashSetOf<String>()
        if (CANVAS_ROOT in loadedIds && visitedLoaded.add(CANVAS_ROOT)) loadedQueue.add(CANVAS_ROOT)
        val loadedOrder = mutableListOf<String>()
        while (loadedQueue.isNotEmpty()) {
            val id = loadedQueue.removeFirst()
            loadedOrder += id
            for (child in childrenByParent[id].orEmpty()) {
                if (child.id in loadedIds && visitedLoaded.add(child.id)) loadedQueue.add(child.id)
            }
        }
        for (card in cards) {
            if (card.id in loadedIds && visitedLoaded.add(card.id)) loadedOrder += card.id
        }
        for (parentId in loadedOrder) {
            for (child in childrenByParent[parentId].orEmpty()) {
                if (seen.add(child.id)) queue.add(child)
            }
        }
    }
    val picked = mutableListOf<CanvasCard>()
    while (queue.isNotEmpty() && picked.size < limit) {
        val card = queue.removeFirst()
        picked += card
        for (child in childrenByParent[card.id].orEmpty()) {
            if (seen.add(child.id)) queue.add(child)
        }
    }
    return picked
}

/**
 * Returns the unloaded ancestor chain leading to [targetId] in root-first order, or empty when [targetId] is already
 * loaded, unknown, or disconnected from any loaded card or [CANVAS_ROOT].
 */
internal fun selectChainToLoad(cards: List<CanvasCard>, loadedIds: Set<String>, targetId: String): List<CanvasCard> {
    if (targetId in loadedIds) return emptyList()
    val byId = cards.associateBy { it.id }
    val chain = mutableListOf<CanvasCard>()
    val seen = hashSetOf<String>()
    var cur = byId[targetId] ?: return emptyList()
    while (cur.id !in loadedIds) {
        if (!seen.add(cur.id)) return emptyList()
        chain += cur
        val parentId = cur.parentId
        if (parentId == null) {
            if (cur.id == CANVAS_ROOT) break else return emptyList()
        }
        cur = byId[parentId] ?: return emptyList()
    }
    return chain.asReversed()
}

/**
 * Groups all unloaded cards and pending files under their deepest loaded ancestor so continuation stubs sit where
 * each branch was cut.
 */
internal fun deferredBranches(
    cards: List<CanvasCard>,
    loadedIds: Set<String>,
    pendingByParent: Map<String, Int> = emptyMap(),
    directoryPending: Int = 0,
): List<CanvasDeferredBranch> {
    val byId = cards.associateBy { it.id }
    val deferredCounts = LinkedHashMap<String, Int>()
    val ancestorCache = HashMap<String, String?>(cards.size)
    val path = ArrayList<String>()
    val inPath = HashSet<String>()
    fun loadedAncestor(card: CanvasCard): String? {
        if (card.id in ancestorCache) return ancestorCache[card.id]
        path.clear()
        inPath.clear()
        var curId: String? = card.id
        var resolved: String? = null
        while (curId != null) {
            if (curId in ancestorCache) {
                resolved = ancestorCache[curId]
                break
            }
            if (!inPath.add(curId)) {
                resolved = null
                break
            }
            path += curId
            val parentId = byId[curId]?.parentId ?: break
            if (parentId in loadedIds) {
                resolved = parentId
                break
            }
            curId = parentId
        }
        for (id in path) ancestorCache[id] = resolved
        return resolved
    }
    for (card in cards) {
        if (card.id in loadedIds) continue
        val ancestor = loadedAncestor(card) ?: continue
        deferredCounts[ancestor] = (deferredCounts[ancestor] ?: 0) + 1
    }
    val pendingCounts = LinkedHashMap<String, Int>()
    for ((parentId, count) in pendingByParent) {
        if (count <= 0) continue
        val target = if (parentId in loadedIds) parentId else CANVAS_ROOT
        if (target in loadedIds) {
            pendingCounts[target] = (pendingCounts[target] ?: 0) + count
        }
    }
    if (directoryPending > 0 && CANVAS_ROOT in loadedIds) {
        pendingCounts[CANVAS_ROOT] = (pendingCounts[CANVAS_ROOT] ?: 0) + directoryPending
    }
    val rootPendingNotDir = pendingByParent[CANVAS_ROOT] ?: 0
    val branches = mutableListOf<CanvasDeferredBranch>()
    val emitted = hashSetOf<String>()
    fun buildBranch(parentId: String): CanvasDeferredBranch? {
        val deferred = deferredCounts[parentId] ?: 0
        val pending = pendingCounts[parentId] ?: 0
        if (deferred + pending <= 0) return null
        val isDirFiles = parentId == CANVAS_ROOT && directoryPending > 0 && deferred == 0 && rootPendingNotDir == 0
        return CanvasDeferredBranch(parentId, deferred, pending, isDirFiles)
    }
    for (card in cards) {
        if (card.id !in loadedIds) continue
        buildBranch(card.id)?.let {
            emitted += card.id
            branches += it
        }
    }
    for (parentId in deferredCounts.keys + pendingCounts.keys) {
        if (parentId !in emitted && parentId in loadedIds) {
            buildBranch(parentId)?.let {
                emitted += parentId
                branches += it
            }
        }
    }
    return branches
}

/** Approximate source viewer height at zoom 1 before an `EditorEx` has been constructed or while hibernated. */
internal fun estimateBaseSourceHeight(text: String, maxHeight: Int = CARD_SOURCE_MAX_HEIGHT): Int {
    val lines = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
    return (lines * CANVAS_ESTIMATED_LINE_HEIGHT + 16).coerceIn(48, maxHeight)
}

/**
 * Decides which card viewers to materialize and which off-screen viewers to hibernate, keeping [pinned] cards active,
 * enforcing [limit] even on extreme zoom-out, and pruning with hysteresis down to [target].
 */
internal fun selectActiveEditorCards(
    cards: List<CanvasCardBounds>,
    viewport: CanvasViewportRect,
    materialized: Set<String>,
    pinned: Set<String>,
    limit: Int = CANVAS_ACTIVE_EDITOR_LIMIT,
    target: Int = CANVAS_ACTIVE_EDITOR_TARGET,
    prefetchFactor: Double = 1.5,
): CanvasEditorPlan {
    val knownIds = cards.mapTo(hashSetOf()) { it.id }
    val activePinned = pinned.filterTo(linkedSetOf()) { it in knownIds }
    val current = materialized.filterTo(linkedSetOf()) { it in knownIds }
    if (cards.size <= limit) {
        return CanvasEditorPlan(materialize = knownIds - current, hibernate = emptySet())
    }

    val cx = viewport.x + viewport.width / 2.0
    val cy = viewport.y + viewport.height / 2.0
    fun distSq(card: CanvasCardBounds): Double {
        val dx = (card.x + card.width / 2.0) - cx
        val dy = (card.y + card.height / 2.0) - cy
        return dx * dx + dy * dy
    }

    fun intersects(card: CanvasCardBounds, left: Long, top: Long, right: Long, bottom: Long): Boolean {
        if (right <= left || bottom <= top || card.width <= 0 || card.height <= 0) return false
        val cardRight = card.x.toLong() + card.width
        val cardBottom = card.y.toLong() + card.height
        return card.x.toLong() < right && cardRight > left && card.y.toLong() < bottom && cardBottom > top
    }

    val vLeft = viewport.x.toLong()
    val vTop = viewport.y.toLong()
    val vRight = vLeft + viewport.width.coerceAtLeast(0)
    val vBottom = vTop + viewport.height.coerceAtLeast(0)

    val padX = (viewport.width.coerceAtLeast(0).toDouble() * prefetchFactor).toLong()
    val padY = (viewport.height.coerceAtLeast(0).toDouble() * prefetchFactor).toLong()
    val pLeft = vLeft - padX
    val pTop = vTop - padY
    val pRight = vRight + padX
    val pBottom = vBottom + padY

    val visible = cards.asSequence()
        .filter { it.id !in activePinned && intersects(it, vLeft, vTop, vRight, vBottom) }
        .sortedBy(::distSq)
        .toList()
    val visibleIds = visible.mapTo(hashSetOf()) { it.id }
    val prefetch = cards.asSequence()
        .filter { it.id !in activePinned && it.id !in visibleIds && intersects(it, pLeft, pTop, pRight, pBottom) }
        .sortedBy(::distSq)
        .toList()

    val maxKeep = maxOf(limit, activePinned.size)
    val desired = LinkedHashSet<String>()
    desired.addAll(activePinned)
    for (card in visible) {
        if (desired.size >= maxKeep) break
        desired.add(card.id)
    }
    for (card in prefetch) {
        if (desired.size >= limit) break
        desired.add(card.id)
    }

    val toMaterialize = desired.filterNotTo(linkedSetOf()) { it in current }
    val afterCount = current.size + toMaterialize.size
    if (afterCount <= limit) {
        return CanvasEditorPlan(materialize = toMaterialize, hibernate = emptySet())
    }

    val keepLimit = maxOf(target.coerceAtMost(limit), desired.size).coerceAtMost(maxKeep)
    val keep = LinkedHashSet(desired)
    val byId = cards.associateBy { it.id }
    current.asSequence()
        .filter { it !in keep }
        .sortedBy { byId[it]?.let(::distSq) ?: Double.MAX_VALUE }
        .forEach { id ->
            if (keep.size < keepLimit) keep.add(id)
        }

    val toHibernate = current.filterNotTo(linkedSetOf()) { it in keep || it in activePinned }
    return CanvasEditorPlan(materialize = toMaterialize, hibernate = toHibernate)
}

/** Reconciles [cards] (ordered parent-before-child) against currently open [existingIds], reparenting orphaned roots to [CANVAS_ROOT]. */
internal fun reconcileBatchParents(existingIds: Set<String>, cards: List<CanvasCard>): List<CanvasCard> {
    val acceptedIds = HashSet(existingIds)
    return cards.map { card ->
        val parentId = if (card.parentId != null && card.parentId in acceptedIds) card.parentId else CANVAS_ROOT
        acceptedIds += card.id
        if (parentId == card.parentId) card else card.copy(parentId = parentId, anchor = CardAnchor(0, 0, 0))
    }
}

/** Outcome of loading one batch of pending linked/diff files. */
internal data class PendingLoadResult(
    val cards: List<CanvasCard>,
    val unavailable: List<String> = emptyList(),
    val error: String? = null,
)

/** Formats a concise status-bar notice for [result], or null when all files loaded cleanly. */
internal fun formatPendingLoadNotice(result: PendingLoadResult): String? = when {
    result.error != null && result.unavailable.isNotEmpty() ->
        "Could not load more: ${result.error} · Unavailable: ${result.unavailable.joinToString(", ")}"
    result.error != null -> "Could not load more: ${result.error}"
    result.unavailable.isNotEmpty() -> "Not shown: ${result.unavailable.joinToString(", ")}"
    else -> null
}

/** Start offset of 0-based document [line] within [text] starting at [firstLine], or null when out of range. */
internal fun sourceLineOffset(text: String, firstLine: Int, line: Int): Int? {
    if (text.isEmpty()) return null
    val index = line - firstLine
    if (index < 0) return null
    var pos = 0
    var current = 0
    while (current < index) {
        val next = text.indexOf('\n', pos)
        if (next < 0) return null
        pos = next + 1
        current++
    }
    return pos
}
