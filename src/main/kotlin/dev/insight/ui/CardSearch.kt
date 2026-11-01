package dev.insight.ui

import com.intellij.psi.codeStyle.NameUtil

/**
 * A card a canvas offers to its card search, identified by [key]. [name] is matched first, then [context] (such as its
 * container and file name), then [text] (such as its summary and source); [detail] is shown beside the name.
 */
internal data class CardCandidate<K>(val key: K, val name: String, val detail: String = "", val context: List<String> = emptyList(),
    val text: String = "")

/**
 * The [cards] matching [query], best first: names starting with it (camel humps, word starts), then names containing it,
 * then cards whose context matches, then cards whose text holds every typed word. An empty query keeps every card in order.
 */
internal fun <K> searchCards(cards: List<CardCandidate<K>>, query: String): List<CardCandidate<K>> {
    val pattern = query.trim()
    if (pattern.isEmpty()) return cards
    val anywhere = NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    val start = NameUtil.buildMatcher(pattern, NameUtil.MatchingCaseSensitivity.NONE)
    val words = pattern.lowercase().split(Regex("""\s+"""))
    fun rank(card: CardCandidate<K>): Int? = when {
        start.matches(card.name) -> 0
        anywhere.matches(card.name) -> 1
        card.context.any(anywhere::matches) -> 2
        card.text.lowercase().let { text -> words.all { it in text } } -> 3
        else -> null
    }
    return cards.mapNotNull { card -> rank(card)?.let { card to it } }
        .sortedWith(compareBy<Pair<CardCandidate<K>, Int>> { it.second }
            .thenByDescending { (card, rank) -> if (rank <= 1) anywhere.matchingDegree(card.name) else 0 })
        .map { it.first }
}
