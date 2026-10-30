package dev.insight.review.ui

/** Picks the view named [label] in [tab]'s dropdown as a user would, so its open action runs. */
internal fun chooseView(tab: ReviewTab<*, *>, label: String) {
    tab.viewSelector.selectedItem = tab.viewSelector.options.single { it.label == label }
}

internal fun viewLabels(tab: ReviewTab<*, *>): List<String> = tab.viewSelector.options.map { it.label }

internal fun shownView(tab: ReviewTab<*, *>): String? = tab.viewSelector.selectedItem?.toString()
