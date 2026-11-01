package dev.insight.canvas

import dev.insight.ui.SourceChainLocation

/** Root to the selected card; each parent points to the source that opened the next step. */
internal fun canvasSourceChain(session: CanvasSession, cardId: String): List<SourceChainLocation> {
    val chain = session.chain(cardId)
    if (chain.isEmpty()) return emptyList()
    return listOf(SourceChainLocation(session.rootPath, session.rootPath.substringAfterLast('/'), chain.first().anchor.line,
        summary = session.root.summary)) +
        chain.mapIndexed { index, card ->
            val next = chain.getOrNull(index + 1)
            val first = next?.anchor?.line ?: if (card.wholeFile) card.symbol?.line?.takeIf { it >= 0 } ?: card.firstLine else card.firstLine
            SourceChainLocation(card.filePath, card.title, first, if (next == null && !card.wholeFile) card.lastLine else first,
                summary = card.summary)
        }
}
