package dev.insight.canvas

import kotlin.math.abs

/**
 * Where a restored symbol card belongs in its parent's current [parentText] (from document line [parentFirstLine]):
 * the link to [target] nearest the saved [anchor], or the saved anchor kept within the text when no link leads there.
 */
fun restoredAnchor(parentText: String, parentFirstLine: Int, parentLinks: List<CanvasLink>, target: Int, anchor: CardAnchor): CardAnchor {
    val link = parentLinks.filter { target in it.targets }.minByOrNull { abs(it.range.first - anchor.startOffset) }
        ?: return cardAnchor(parentText, anchor.startOffset, anchor.endOffset, parentFirstLine)
    return cardAnchor(parentText, link.range.first, link.range.last + 1, parentFirstLine)
}

/**
 * Links of a restored selection [card] over its saved text: those of its parent's current [parentText] within the
 * anchor, when that slice still reads as the saved text; otherwise none, and the selection is reported as changed.
 */
fun restoredSelectionLinks(parentText: String, parentLinks: List<CanvasLink>, card: CanvasCard): Pair<List<CanvasLink>, Boolean> {
    val start = card.anchor.startOffset
    val end = card.anchor.endOffset
    val same = start in 0..end && end <= parentText.length && parentText.substring(start, end).startsWith(card.text) && card.text.isNotEmpty()
    return if (same) linksInSlice(parentLinks, start, start + card.text.length) to false else emptyList<CanvasLink>() to true
}

/** Whether the [fresh] source of a restored card differs from its saved, bounded text. */
fun sourceChanged(card: CanvasCard, fresh: String): Boolean = boundCardText(fresh).first != card.text

/** Link targets of [links] in first-seen order: the candidates a restored child card is looked up among first. */
fun linkTargets(links: List<CanvasLink>): List<Int> = links.flatMap { it.targets }.distinct()
