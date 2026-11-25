package dev.insight.review

import kotlin.math.max

/** Whether the Tree board's right rail shows code Topics or change Groups. */
internal enum class TreeRailMode(val title: String, val singular: String, val plural: String) {
    TOPICS("Topics", "topic", "topics"),
    GROUPING("Groups", "group", "groups"),
}

/** One Topic or Group card in the Tree board's right rail. */
internal data class TreeRailCard(
    val key: String,
    val name: String,
    val fullPath: String,
    val family: String?,
    val level: Int,
    val files: Set<String>,
    val summary: String?,
    val placeholder: Boolean = false,
)

/** One Topic or Group card placement in the Tree board's right rail, aligned to visible tree rows. */
internal data class TreeRailPlacement(
    val card: TreeRailCard,
    val column: Int,
    val firstRow: Int,
    val lastRow: Int,
    val fileCountInSpan: Int,
)

/**
 * Right-rail topic/group cards and per-file topic mappings for the Tree board, scoped to the files currently
 * represented in the chart.
 */
internal data class TreeRailModel(
    val mode: TreeRailMode,
    val depth: Int,
    val visibleDepth: Int,
    val cards: List<TreeRailCard>,
    val fileFamilies: Map<String, String>,
    val fileLabels: Map<String, String>,
) {
    private val cardsByKey: Map<String, TreeRailCard> = cards.associateBy { it.key }

    fun cardByKey(key: String?): TreeRailCard? = key?.let(cardsByKey::get)

    fun matchingCardKeys(filePath: String): Set<String> =
        cards.asSequence().filter { filePath in it.files }.mapTo(LinkedHashSet()) { it.key }

    fun matchingCardKeysForFiles(paths: Collection<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val pathSet = paths as? Set<String> ?: paths.toSet()
        return cards.asSequence()
            .filter { card -> card.files.any { it in pathSet } }
            .mapTo(LinkedHashSet()) { it.key }
    }
}

/**
 * Builds a [TreeRailModel] from [topics] and [topicSummaries] (falling back to exact-file-set summaries in
 * [sharedSummaries]) restricted to [chartFiles]. When [maxLevel] is non-null, cards deeper than `maxLevel` are folded.
 */
internal fun buildTreeRailModel(
    mode: TreeRailMode,
    topics: ReviewTopics?,
    topicSummaries: ReviewSummaries?,
    sharedSummaries: ReviewSummaries,
    chartFiles: Set<String>,
    maxLevel: Int? = null,
): TreeRailModel {
    if (topics == null || chartFiles.isEmpty()) {
        return TreeRailModel(mode, 0, 0, emptyList(), emptyMap(), emptyMap())
    }
    val scopedFiles = topics.files.filter { it.path in chartFiles }
    val scopedLabels = topics.labels.filterKeys { it in chartFiles }
    if (scopedLabels.isEmpty()) {
        return TreeRailModel(mode, 0, 0, emptyList(), emptyMap(), emptyMap())
    }
    val scopedTopics = ReviewTopics(scopedFiles, scopedLabels)
    val outline = topicOutline(scopedTopics)
    val depth = outline.depth
    val effectiveMaxLevel = if (depth <= 0) 0 else maxLevel?.coerceIn(0, depth - 1) ?: (depth - 1)
    val visibleDepth = if (depth <= 0) 0 else effectiveMaxLevel + 1

    val reading = topicReadingSummaries(
        topicSummaries ?: ReviewSummaries(buildTopicSummaryTree(scopedTopics)),
        sharedSummaries,
    )
    val topicTexts = reading.tree.nodes.mapNotNull { node ->
        reading.text(node.id)?.let { node.identity to it }
    }.toMap()
    val sharedByFiles = sharedSummaries.tree.nodes
        .filter { it.kind != SummaryKind.CHUNK }
        .mapNotNull { node -> sharedSummaries.text(node.id)?.let { node.files.toSet() to it } }
        .toMap()

    val cards = mutableListOf<TreeRailCard>()
    fun walk(entries: List<TopicEntry>) {
        for (entry in entries) {
            val node = (entry as? TopicEntry.Topic)?.node ?: continue
            if (node.level > effectiveMaxLevel) continue
            val files = (node.first..node.last).mapTo(LinkedHashSet()) { outline.files[it].path }
            val summary = topicTexts["topic:${node.identity}"] ?: sharedByFiles[files]
            cards += TreeRailCard(
                key = "${node.level}:${node.identity}",
                name = node.name,
                fullPath = node.fullPath,
                family = node.path.firstOrNull(),
                level = node.level,
                files = files,
                summary = summary,
            )
            if (node.level < effectiveMaxLevel) {
                walk(outline.children(node))
            }
        }
    }
    walk(outline.children(null))

    outline.pending?.let { pending ->
        val pendingFiles = pending.mapTo(LinkedHashSet()) { outline.files[it].path }
        val name = if (mode == TreeRailMode.GROUPING) "Awaiting group" else "Awaiting topic"
        val command = if (mode == TreeRailMode.GROUPING) "/grouping" else "/topics"
        cards += TreeRailCard(
            key = "pending",
            name = name,
            fullPath = "Run $command to classify these files",
            family = null,
            level = effectiveMaxLevel,
            files = pendingFiles,
            summary = null,
            placeholder = true,
        )
    }

    val fileFamilies = scopedLabels.mapValues { (_, label) -> label.substringBefore(" > ") }
    return TreeRailModel(mode, depth, visibleDepth, cards, fileFamilies, scopedLabels)
}

/**
 * Computes hierarchical, row-aligned rail card placements from [model] against the current visible [board] rows.
 * Deepest/leaf cards sit in column `0` (innermost, next to the tree) and broadest cards sit in column
 * `visibleDepth - 1` (outermost), matching the Document view layout.
 */
internal fun computeTreeRailPlacements(
    model: TreeRailModel,
    board: ReviewTreeBoard,
    filesInDir: (String) -> Collection<String>,
): List<TreeRailPlacement> {
    if (model.cards.isEmpty() || board.nodes.isEmpty() || model.visibleDepth <= 0) return emptyList()

    val cardsByLevelAndFile = Array(model.visibleDepth) { HashMap<String, TreeRailCard>() }
    for (card in model.cards) {
        if (card.level !in 0 until model.visibleDepth) continue
        val levelMap = cardsByLevelAndFile[card.level]
        for (path in card.files) {
            levelMap[path] = card
        }
    }

    val placements = mutableListOf<TreeRailPlacement>()
    for (level in 0 until model.visibleDepth) {
        val column = model.visibleDepth - 1 - level
        val fileToCard = cardsByLevelAndFile[level]
        var runCard: TreeRailCard? = null
        var runFirstRow = -1
        var runLastRow = -1
        var runFiles = 0

        fun flush() {
            val active = runCard ?: return
            placements += TreeRailPlacement(
                card = active,
                column = column,
                firstRow = runFirstRow,
                lastRow = runLastRow,
                fileCountInSpan = runFiles,
            )
            runCard = null
            runFirstRow = -1
            runLastRow = -1
            runFiles = 0
        }

        for (node in board.nodes) {
            when {
                node.entry.kind == ReviewLineChart.Kind.FILE -> {
                    val card = fileToCard[node.entry.path]
                    if (card == null) {
                        flush()
                    } else if (card.key == runCard?.key) {
                        runLastRow = node.index
                        runFiles += 1
                    } else {
                        flush()
                        runCard = card
                        runFirstRow = node.index
                        runLastRow = node.index
                        runFiles = 1
                    }
                }
                node.entry.directory && node.collapsed -> {
                    val dirFiles = filesInDir(node.entry.path)
                    var singleCard: TreeRailCard? = null
                    var uniform = dirFiles.isNotEmpty()
                    for (path in dirFiles) {
                        val card = fileToCard[path]
                        if (card == null) {
                            uniform = false
                            break
                        }
                        if (singleCard == null) {
                            singleCard = card
                        } else if (singleCard.key != card.key) {
                            uniform = false
                            break
                        }
                    }
                    if (uniform && singleCard != null) {
                        if (singleCard.key == runCard?.key) {
                            runLastRow = node.index
                            runFiles += dirFiles.size
                        } else {
                            flush()
                            runCard = singleCard
                            runFirstRow = node.index
                            runLastRow = node.index
                            runFiles = dirFiles.size
                        }
                    } else {
                        flush()
                    }
                }
                // Expanded directory headers are bridged when inside a matching run and ignored outside.
            }
        }
        flush()
    }
    return placements
}

/**
 * Screen scale for the Tree canvas's cards and floating summary at [zoom]: they follow the canvas when it zooms in
 * and keep their on-screen size when it zooms out, so titles and summaries stay readable at any zoom.
 */
internal fun treeCanvasCardScale(zoom: Double): Double = max(1.0, zoom)

/**
 * Screen-space left edges of the rail columns, starting at [startX] with [baseWidths] and [baseGap] at zoom 1,
 * all scaled by [scale]. Column `0` sits innermost, next to the tree.
 */
internal fun treeRailColumnLefts(startX: Double, baseWidths: List<Int>, baseGap: Int, scale: Double): List<Double> {
    var x = startX
    return baseWidths.map { width ->
        x.also { x += (width + baseGap) * scale }
    }
}

/**
 * Base (zoom 1) width of one rail column: wide enough for its widest [contentWidths] plus [padding], within
 * [minWidth]..[maxWidth]. Empty columns use [minWidth].
 */
internal fun treeRailColumnWidth(contentWidths: Collection<Int>, padding: Int, minWidth: Int, maxWidth: Int): Int =
    ((contentWidths.maxOrNull() ?: 0) + padding).coerceIn(minWidth, maxOf(minWidth, maxWidth))

/**
 * Screen top of a floating card of [height] anchored at [anchorTop]..[anchorBottom] in a view of [viewHeight],
 * kept [margin] inside the view while the anchor stays visible; null once the anchor leaves the view.
 */
internal fun floatingCardTop(anchorTop: Double, anchorBottom: Double, height: Double, viewHeight: Double, margin: Double): Double? {
    if (viewHeight <= 0.0 || anchorBottom <= 0.0 || anchorTop >= viewHeight) return null
    val lowest = viewHeight - margin - height
    return anchorTop.coerceAtMost(lowest).coerceAtLeast(margin)
}
