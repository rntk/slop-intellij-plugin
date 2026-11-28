package dev.insight.review.ui

import java.awt.Component
import java.awt.Font
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel

/**
 * A pill drop-down (`View  Document ▾`) for switching between views of the same content. Choosing a different entry
 * calls [onSelect]; the entries' tooltips show on the drop-down and on each of its rows.
 */
internal fun <T : Any> viewSelector(
    name: String,
    modes: List<T>,
    selected: T,
    gap: Int,
    font: Font,
    label: (T) -> String,
    tooltip: (T) -> String,
    onSelect: (T) -> Unit,
): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, gap, 0)).apply {
    isOpaque = false
    val selector = PillComboBox<Any>(DefaultComboBoxModel(modes.toTypedArray<Any>()), name = name, prefix = "View").apply {
        this.font = font
        selectedItem = selected
        accessibleContext.accessibleName = "View"
        toolTipText = tooltip(selected)
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, hasFocus: Boolean): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, hasFocus) as JLabel
                @Suppress("UNCHECKED_CAST")
                (value as? T)?.let { component.text = label(it); component.toolTipText = tooltip(it) }
                return component
            }
        }
        addActionListener {
            @Suppress("UNCHECKED_CAST")
            val mode = selectedItem as? T ?: return@addActionListener
            if (mode != selected) onSelect(mode)
        }
    }
    add(selector)
}
