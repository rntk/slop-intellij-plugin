package dev.insight.review.ui

/** The tiles of [tab]'s views, without the source and chat tiles. */
private fun viewTiles(tab: ReviewTab<*, *>): List<ReviewTile> = tab.tileGrid.tiles.filter { it.id != SOURCE_TILE && it.id != CHAT_TILE }

/** Clicks the tile of the view named [label] in [tab] as a user would, so its open action runs; a disabled tile does nothing. */
internal fun chooseView(tab: ReviewTab<*, *>, label: String) {
    tab.tileGrid.click(viewTiles(tab).single { it.title == label }.id)
}

/** The titles of [tab]'s view tiles, in order. */
internal fun viewLabels(tab: ReviewTab<*, *>): List<String> = viewTiles(tab).map { it.title }

/** The title of the selected view tile: the view whose page the editor shows. */
internal fun shownView(tab: ReviewTab<*, *>): String? = tab.tileGrid.selectedId?.let { id -> viewTiles(tab).singleOrNull { it.id == id }?.title }

/** Whether every view tile of [tab] can be opened. */
internal fun viewsEnabled(tab: ReviewTab<*, *>): Boolean = viewTiles(tab).all { tab.tileGrid.isTileEnabled(it.id) }

/** The stats on [tab]'s tile [id] after an immediate refresh, as "value label" texts. */
internal fun tileStats(tab: ReviewTab<*, *>, id: String): List<String> {
    tab.refreshTiles()
    return statTexts(tab.tileGrid.tileComponents.getValue(id))
}

private fun statTexts(tile: java.awt.Container): List<String> {
    val labels = generateSequence(listOf<java.awt.Component>(tile)) { level ->
        level.filterIsInstance<java.awt.Container>().flatMap { it.components.toList() }.takeIf { it.isNotEmpty() }
    }.flatten().filterIsInstance<javax.swing.JLabel>().toList()
    val values = labels.filter { it.name == "review-tile-stat-value" }
    val names = labels.filter { it.name == "review-tile-stat-label" }
    return values.zip(names) { value, name -> "${value.text} ${name.text}" }
}
