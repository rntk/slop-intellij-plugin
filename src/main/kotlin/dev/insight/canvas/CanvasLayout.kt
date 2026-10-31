package dev.insight.canvas

/**
 * A card to place: opened from [parentId] (null: the root file), with its anchor [anchorY] pixels below the top of
 * its parent (the root file's view for a null parent), and its own [width] and [height].
 */
data class CanvasLayoutItem(val id: String, val parentId: String?, val anchorY: Int, val width: Int, val height: Int)

/** The top-left corner of a placed card, in canvas coordinates. */
data class CanvasPlacement(val x: Int, val y: Int)

/**
 * Spacing of [canvasLayout]: the root file's view at ([left], [top]); [arrowGap] between columns, leaving room for
 * arrows; [gap] between cards of a column; a card's top sits [anchorLift] above its anchor when there is room.
 */
data class CanvasLayoutOptions(val left: Int = 0, val top: Int = 0, val arrowGap: Int = 48, val gap: Int = 12, val anchorLift: Int = 8)

/**
 * Places cards in columns by depth to the right of the root file's view of [rootWidth]: cards opened from the root in
 * the first column, their children in the next, and so on. A column is as wide as its widest card. Within a column,
 * cards follow their parents' order and then their anchors, each as near its anchor as the cards above allow, so arrows
 * stay short and never cross within a column. A card whose parent is missing is treated as opened from the root.
 */
fun canvasLayout(items: List<CanvasLayoutItem>, rootWidth: Int, options: CanvasLayoutOptions = CanvasLayoutOptions()): Map<String, CanvasPlacement> {
    val byId = items.associateBy { it.id }
    fun parent(item: CanvasLayoutItem) = item.parentId?.takeIf { it != item.id && it in byId }
    val depths = HashMap<String, Int>()
    fun depth(item: CanvasLayoutItem): Int {
        depths[item.id]?.let { return it }
        // Walk up iteratively; a cycle is cut where it closes, treating that card as opened from the root.
        val path = mutableListOf(item)
        val seen = hashSetOf(item.id)
        var base = 0
        while (true) {
            val up = parent(path.last())?.let(byId::get) ?: break
            val known = depths[up.id]
            if (known != null) { base = known; break }
            if (!seen.add(up.id)) break
            path += up
        }
        path.asReversed().forEachIndexed { index, it -> depths[it.id] = base + index + 1 }
        return depths.getValue(item.id)
    }
    val columns = items.groupBy(::depth).toSortedMap()

    val placed = HashMap<String, CanvasPlacement>()
    /** Order of each placed card within its column, so children follow their parents. */
    val order = HashMap<String, Int>()
    var x = options.left + rootWidth + options.arrowGap
    for ((_, column) in columns) {
        fun parentTop(item: CanvasLayoutItem) = parent(item)?.let { placed[it]?.y } ?: options.top
        val sorted = column.withIndex().sortedWith(compareBy(
            { (_, item) -> parent(item)?.let(order::get) ?: -1 },
            { (_, item) -> parentTop(item) + item.anchorY },
            { (index, _) -> index },
        )).map { it.value }
        var bottom: Int? = null
        sorted.forEachIndexed { index, item ->
            val wanted = parentTop(item) + item.anchorY - options.anchorLift
            val y = bottom?.let { maxOf(wanted, it + options.gap) } ?: maxOf(wanted, options.top)
            placed[item.id] = CanvasPlacement(x, y)
            order[item.id] = index
            bottom = y + item.height
        }
        x += column.maxOf { it.width } + options.arrowGap
    }
    return placed
}
