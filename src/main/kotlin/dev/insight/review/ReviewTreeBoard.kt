package dev.insight.review

import kotlin.math.max
import kotlin.math.min

/**
 * One visible row in the whole-project tree board.
 *
 * [ancestorHasNextSibling] has length `max(0, depth - 1)`; element `col` is true when the ancestor at
 * `depth == col + 1` has a sibling below it, so a vertical trunk line passes through column `col` on this row.
 */
internal data class TreeBoardNode(
    val entry: ReviewLineChart.Entry,
    val depth: Int,
    val index: Int,
    val parentIndex: Int?,
    val isLastChild: Boolean,
    val ancestorHasNextSibling: List<Boolean>,
    val collapsible: Boolean,
    val collapsed: Boolean,
    val directChildCount: Int,
)

/**
 * The active or hovered branch of a [ReviewTreeBoard]: the path from root to [targetIndex] plus any visible
 * descendants beneath [targetIndex] when it is an expanded directory.
 */
internal data class TreeBoardHighlight(
    val targetIndex: Int,
    val ancestors: Set<Int>,
    val descendants: Set<Int>,
    val lastHighlightedChildRow: Map<Int, Int>,
) {
    fun contains(index: Int): Boolean = index in ancestors || index in descendants

    /** Whether the vertical trunk passing through [ancestorColumn] on [row] belongs to the highlighted branch. */
    fun verticalLineHighlighted(row: TreeBoardNode, ancestorColumn: Int, nodes: List<TreeBoardNode>): Boolean {
        if (ancestorColumn !in 0 until row.depth) return false
        var current: TreeBoardNode? = row
        while (current != null && current.depth > ancestorColumn) {
            current = current.parentIndex?.let(nodes::getOrNull)
        }
        val ancestorIdx = current?.index ?: return false
        return lastHighlightedChildRow[ancestorIdx]?.let { row.index < it } == true
    }

    /** Whether the elbow/tee connector entering [row] from its parent belongs to the highlighted branch. */
    fun connectorHighlighted(row: TreeBoardNode): Boolean =
        row.parentIndex?.let { parent -> contains(row.index) && contains(parent) } == true
}

/**
 * Flattened visible tree for the Charts Tree board view. Directories start expanded unless their path is in
 * [collapsed]; files remain leaves (no PSI declaration children).
 */
internal class ReviewTreeBoard(
    private val chart: ReviewLineChart,
    collapsed: Set<String> = emptySet(),
) {
    val nodes: List<TreeBoardNode>
    private val indexByPath: Map<String, Int>

    init {
        val built = mutableListOf<TreeBoardNode>()
        fun childEntries(entry: ReviewLineChart.Entry): List<ReviewLineChart.Entry> =
            if (!entry.directory) emptyList()
            else chart.children(entry).filter {
                it.kind == ReviewLineChart.Kind.DIRECTORY || it.kind == ReviewLineChart.Kind.FILE
            }

        fun visit(
            entry: ReviewLineChart.Entry,
            depth: Int,
            parentIndex: Int?,
            isLastChild: Boolean,
            ancestorHasNextSibling: List<Boolean>,
        ) {
            val children = childEntries(entry)
            val collapsible = entry.directory && children.isNotEmpty()
            val isCollapsed = collapsible && entry.path in collapsed
            val index = built.size
            built += TreeBoardNode(
                entry = entry,
                depth = depth,
                index = index,
                parentIndex = parentIndex,
                isLastChild = isLastChild,
                ancestorHasNextSibling = ancestorHasNextSibling,
                collapsible = collapsible,
                collapsed = isCollapsed,
                directChildCount = children.size,
            )
            if (!isCollapsed && children.isNotEmpty()) {
                val childAncestors = if (depth == 0) emptyList() else ancestorHasNextSibling + !isLastChild
                children.forEachIndexed { childPos, child ->
                    visit(
                        entry = child,
                        depth = depth + 1,
                        parentIndex = index,
                        isLastChild = childPos == children.lastIndex,
                        ancestorHasNextSibling = childAncestors,
                    )
                }
            }
        }

        visit(
            entry = chart.root,
            depth = 0,
            parentIndex = null,
            isLastChild = true,
            ancestorHasNextSibling = emptyList(),
        )
        nodes = built
        indexByPath = built.associate { it.entry.path to it.index }
    }

    fun indexOf(path: String?): Int = path?.let { indexByPath[it] } ?: -1

    fun highlight(index: Int): TreeBoardHighlight? {
        val target = nodes.getOrNull(index) ?: return null
        val ancestors = HashSet<Int>()
        var current: TreeBoardNode? = target
        while (current != null) {
            ancestors += current.index
            current = current.parentIndex?.let(nodes::getOrNull)
        }
        val descendants = HashSet<Int>()
        if (target.entry.directory && !target.collapsed) {
            var next = index + 1
            while (next < nodes.size && nodes[next].depth > target.depth) {
                descendants += next
                next++
            }
        }
        val all = ancestors + descendants
        val lastChild = HashMap<Int, Int>()
        for (idx in all) {
            val parent = nodes[idx].parentIndex ?: continue
            if (parent in all) {
                lastChild[parent] = max(lastChild[parent] ?: -1, idx)
            }
        }
        return TreeBoardHighlight(index, ancestors, descendants, lastChild)
    }

    fun firstChildIndex(index: Int): Int? =
        nodes.getOrNull(index + 1)?.takeIf { it.parentIndex == index }?.index

    /** All non-root directory paths in [chart] that have at least one visible child, including inside collapsed branches. */
    fun allCollapsiblePaths(): Set<String> {
        val paths = LinkedHashSet<String>()
        fun collect(entry: ReviewLineChart.Entry, isRoot: Boolean) {
            if (!entry.directory) return
            val children = chart.children(entry).filter {
                it.kind == ReviewLineChart.Kind.DIRECTORY || it.kind == ReviewLineChart.Kind.FILE
            }
            if (!isRoot && children.isNotEmpty()) paths += entry.path
            children.forEach { collect(it, isRoot = false) }
        }
        collect(chart.root, isRoot = true)
        return paths
    }

    fun cursorAfterCollapse(currentPath: String?, collapsedPath: String): String? {
        if (currentPath == null || currentPath == collapsedPath) return currentPath
        val beneath = if (collapsedPath.isEmpty()) true else ReviewLineChart.isBeneath(currentPath, collapsedPath)
        return if (beneath) collapsedPath else currentPath
    }
}

/**
 * Pan/zoom transform for the Tree board canvas.
 */
internal class TreeBoardViewport(
    var panX: Double = 0.0,
    var panY: Double = 0.0,
    zoom: Double = 1.0,
) {
    var zoom: Double = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        private set

    fun worldToScreenX(wx: Double): Double = wx * zoom + panX
    fun worldToScreenY(wy: Double): Double = wy * zoom + panY
    fun screenToWorldX(sx: Double): Double = (sx - panX) / zoom
    fun screenToWorldY(sy: Double): Double = (sy - panY) / zoom

    fun panBy(dx: Double, dy: Double) {
        panX += dx
        panY += dy
    }

    fun zoomAround(screenX: Double, screenY: Double, targetZoom: Double) {
        val nextZoom = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val wx = screenToWorldX(screenX)
        val wy = screenToWorldY(screenY)
        zoom = nextZoom
        panX = screenX - wx * nextZoom
        panY = screenY - wy * nextZoom
    }

    fun reset() {
        zoom = 1.0
        panX = 0.0
        panY = 0.0
    }

    fun fitToBounds(
        contentWidth: Double,
        contentHeight: Double,
        viewWidth: Int,
        viewHeight: Int,
        padding: Double = 24.0,
    ) {
        if (viewWidth <= 0 || viewHeight <= 0 || contentWidth <= 0.0 || contentHeight <= 0.0) {
            reset()
            return
        }
        val availW = (viewWidth - 2 * padding).coerceAtLeast(1.0)
        val availH = (viewHeight - 2 * padding).coerceAtLeast(1.0)
        val fitZoom = min(availW / contentWidth, availH / contentHeight).coerceIn(MIN_ZOOM, 1.5)
        zoom = fitZoom
        val scaledW = contentWidth * fitZoom
        val scaledH = contentHeight * fitZoom
        panX = if (scaledW < viewWidth - 2 * padding) (viewWidth - scaledW) / 2.0 else padding
        panY = if (scaledH < viewHeight - 2 * padding) (viewHeight - scaledH) / 2.0 else padding
    }

    fun ensureRowVisible(
        rowTopWorld: Double,
        rowBottomWorld: Double,
        viewHeight: Int,
        margin: Double = 24.0,
    ) {
        if (viewHeight <= 0) return
        val topScreen = worldToScreenY(rowTopWorld)
        val bottomScreen = worldToScreenY(rowBottomWorld)
        val safeBottom = (viewHeight - margin).coerceAtLeast(margin)
        if (topScreen < margin) {
            panY += margin - topScreen
        } else if (bottomScreen > safeBottom) {
            panY -= bottomScreen - safeBottom
        }
    }

    companion object {
        const val MIN_ZOOM = 0.25
        const val MAX_ZOOM = 3.0
        const val ZOOM_STEP = 1.2
    }
}
