package dev.insight.review.ui

import java.awt.Component
import javax.swing.JComboBox

/** Picks an entry of a card's view drop-down by enum name, as a reader would. */
internal fun selectView(components: List<Component>, selector: String, mode: String) {
    val combo = components.filterIsInstance<JComboBox<*>>().single { it.name == selector }
    combo.selectedItem = (0 until combo.itemCount).map { combo.getItemAt(it) as Enum<*> }.single { it.name.equals(mode, ignoreCase = true) }
}

internal fun selectSummariesMode(components: List<Component>, mode: String) = selectView(components, "summaries-mode", mode)

internal fun selectTopicsMode(components: List<Component>, mode: String) = selectView(components, "topics-mode", mode)

