package dev.insight.canvas

import com.intellij.psi.codeStyle.NameUtil
import dev.insight.ui.CardCandidate
import dev.insight.ui.searchCards

/** Match only the root file's name, keeping the store's newest-first order. */
internal fun filterCanvasSessions(entries: List<CanvasSessionEntry>, query: String): List<CanvasSessionEntry> {
    val pattern = query.trim()
    if (pattern.isEmpty()) return entries
    val matcher = NameUtil.buildMatcher("*$pattern", NameUtil.MatchingCaseSensitivity.NONE)
    return entries.filter { matcher.matches(it.rootPath.replace('\\', '/').substringAfterLast('/')) }
}

/** The cards matching [query], best first, as [searchCards] ranks their [canvasCardCandidate]s. */
internal fun searchCanvasCards(cards: List<CanvasCard>, query: String): List<CanvasCard> {
    val byId = cards.associateBy { it.id }
    return searchCards(cards.map(::canvasCardCandidate), query).mapNotNull { byId[it.key] }
}

/** A card as the card search offers it: its name, then its container and file name, then its summary and text. */
internal fun canvasCardCandidate(card: CanvasCard): CardCandidate<String> = CardCandidate(
    card.id,
    canvasCardName(card),
    listOfNotNull(cardKindLabel(card), card.symbol?.container?.takeIf { it.isNotBlank() }, card.location).joinToString(" · "),
    listOfNotNull(card.symbol?.container, card.filePath.substringAfterLast('/')),
    listOfNotNull(card.summary, card.text, card.filePath).joinToString("\n"),
)

/** A card's name for search and lists: its title, or a selection's first non-blank line. */
internal fun canvasCardName(card: CanvasCard): String =
    if (card.kind == CardKind.SELECTION && !card.wholeFile) card.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        ?.let { if (it.length > CARD_NAME_LIMIT) it.take(CARD_NAME_LIMIT - 1) + "…" else it } ?: card.title
    else card.title

private fun cardKindLabel(card: CanvasCard): String = when {
    card.wholeFile -> "File"
    card.kind == CardKind.SELECTION -> "Selection"
    else -> card.symbol?.kind?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Symbol"
}

private const val CARD_NAME_LIMIT = 80
