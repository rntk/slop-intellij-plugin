package dev.insight.review.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import dev.insight.review.CanvasTabKey

/** One entry of a [ReviewViewSelector]: the canvas page it opens, its [label] and what the page shows. */
internal class ReviewViewOption(val key: CanvasTabKey, val label: String, val tooltip: String) {
    override fun toString() = label
}

/**
 * Switches between the mutually exclusive views of a review in one dropdown. Choosing an entry, even the shown one, calls
 * [open]; the selection also follows the page the canvas shows, without calling it. Entries for which [isViewEnabled] is
 * false are greyed and cannot be chosen.
 */
internal class ReviewViewSelector(
    val options: List<ReviewViewOption>,
    private val open: (CanvasTabKey) -> Unit,
) : ComboBox<ReviewViewOption>(options.toTypedArray()) {
    var isViewEnabled: (CanvasTabKey) -> Boolean = { true }
    private var shown: CanvasTabKey = options.first().key
    private var following = false

    init {
        name = "review-view-selector"
        renderer = SimpleListCellRenderer.create<ReviewViewOption> { label, option, _ ->
            label.text = option.label
            label.isEnabled = isViewEnabled(option.key)
        }
        updateTooltip()
        addActionListener {
            if (following) return@addActionListener
            val option = selectedItem as? ReviewViewOption ?: return@addActionListener
            if (isViewEnabled(option.key)) { shown = option.key; updateTooltip(); open(option.key) } else show(shown)
        }
    }

    /** Selects the entry of [key] without opening it; keys that are not views leave the selection as it is. */
    fun show(key: CanvasTabKey) {
        val option = options.firstOrNull { it.key == key } ?: return
        shown = key
        following = true
        try { selectedItem = option } finally { following = false }
        updateTooltip()
    }

    private fun updateTooltip() {
        toolTipText = (selectedItem as? ReviewViewOption)?.tooltip
    }
}
