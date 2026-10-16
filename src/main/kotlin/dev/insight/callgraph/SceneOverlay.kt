package dev.insight.callgraph

import java.awt.Dimension
import java.awt.Rectangle

/** One box of group [group] (index in the scene) around a contiguous cluster of its cards, label band included. */
internal data class GroupBox(val group: Int, val bounds: Rectangle)
internal data class ExternalBox(val external: SceneExternal, val bounds: Rectangle)
/** A snippet callout beside [card]; [left] when it stands left of the card. */
internal data class SnippetBox(val snippet: SceneSnippet, val bounds: Rectangle, val card: Rectangle, val left: Boolean)

/** Scene elements drawn over the chart layout, which the scene does not control. Not interactive, so clicks pass through. */
internal class SceneOverlay(val groups: List<GroupBox>, val externals: List<ExternalBox>, val snippets: List<SnippetBox>) {
    /** Snippets and externals, which link label chips avoid. */
    val solid: List<Rectangle> get() = snippets.map { it.bounds } + externals.map { it.bounds }
    val bounds: Rectangle? get() = (groups.map { it.bounds } + solid).reduceOrNull { a, b -> a.union(b) }
}

/**
 * Card bounds of [members] merged into contiguous clusters: one per run of members in a column (lane) that no other
 * card or subflow lies between, so a group spread over columns or far apart gets several boxes, never one that covers
 * unrelated steps.
 */
internal fun groupClusters(geometry: FlowGeometry, members: Collection<Int>): List<Rectangle> {
    val set = members.toSet()
    val others = geometry.shapes.mapNotNull { shape -> when (shape) {
        is FlowCardBox -> shape.bounds.takeIf { shape.step.node !in set }
        is FlowRegionBox -> shape.bounds
        else -> null
    } }
    fun lane(r: Rectangle) = geometry.lanes.indexOfFirst { r.centerX.toInt() in it }
    return set.mapNotNull { geometry.cards[it]?.bounds }.groupBy(::lane).toSortedMap().map { (lane, column) ->
        // Steps alternate around the spine, so any other step of the column within the merged height breaks the run.
        val between = others.filter { lane(it) == lane }
        val clusters = mutableListOf<Rectangle>()
        for (card in column.sortedBy { it.y }) {
            val merged = clusters.lastOrNull()?.union(card)
            if (merged != null && between.none { it.y < merged.y + merged.height && it.y + it.height > merged.y })
                clusters[clusters.lastIndex] = merged
            else clusters += Rectangle(card)
        }
        clusters
    }.flatten()
}

/** [candidate] moved down past whatever of [occupied] it overlaps, until it is free (bounded). */
internal fun freeSpot(candidate: Rectangle, occupied: List<Rectangle>, gap: Int): Rectangle {
    val spot = Rectangle(candidate)
    repeat(200) {
        val hit = occupied.filter { it.intersects(spot) }.maxOfOrNull { it.maxY.toInt() } ?: return spot
        spot.y = hit + gap
    }
    return spot
}

/**
 * Places the groups, snippets and externals of [scene] around the laid-out cards: group boxes from clustered member
 * cards, snippet callouts beside their card on its outer side, externals right of their rightmost connected card.
 * Snippets and externals are stacked greedily downwards so they overlap neither cards nor each other.
 */
internal fun layoutSceneOverlay(scene: ChartScene, geometry: FlowGeometry, gap: Int, pad: Int, labelHeight: Int,
    snippetSize: (SceneSnippet) -> Dimension, externalSize: (SceneExternal) -> Dimension): SceneOverlay {
    val groups = scene.groups.flatMapIndexed { i, group ->
        groupClusters(geometry, group.nodes).map { GroupBox(i, Rectangle(it.x - pad, it.y - pad - labelHeight, it.width + pad * 2, it.height + pad * 2 + labelHeight)) }
    }
    val occupied = chartObstacles(geometry).toMutableList()
    val snippets = scene.snippets.mapNotNull { snippet ->
        val card = geometry.cards[snippet.node] ?: return@mapNotNull null
        val size = snippetSize(snippet)
        // The outer side first, away from the spine; the other side when it lands closer to the card.
        val sides = listOf(card.leftOfSpine, !card.leftOfSpine).mapNotNull { left ->
            val x = if (left) card.bounds.x - gap * 2 - size.width else card.bounds.x + card.bounds.width + gap * 2
            if (x < gap) null else left to freeSpot(Rectangle(x, card.bounds.y, size.width, size.height), occupied, gap)
        }
        val (left, spot) = sides.minBy { (_, spot) -> spot.y - card.bounds.y }
        occupied += spot
        SnippetBox(snippet, spot, card.bounds, left)
    }
    val externals = scene.externals.mapNotNull { external ->
        val cards = external.connected.mapNotNull { geometry.cards[it.node]?.bounds }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val size = externalSize(external)
        val y = cards.map { it.centerY }.average().toInt() - size.height / 2
        val spot = freeSpot(Rectangle(cards.maxOf { it.x + it.width } + gap * 3, y, size.width, size.height), occupied, gap)
        occupied += spot
        ExternalBox(external, spot)
    }
    return SceneOverlay(groups, externals, snippets)
}

/** What scene elements placed over the chart avoid: cards, subflows, decisions, pills and column banners. */
internal fun chartObstacles(geometry: FlowGeometry): List<Rectangle> = geometry.shapes.mapNotNull { shape -> when (shape) {
    is FlowCardBox -> shape.bounds
    is FlowRegionBox -> shape.bounds
    is FlowDecisionBox -> shape.bounds
    is FlowPill -> shape.bounds
    is FlowBanner -> shape.bounds
    else -> null
} }
