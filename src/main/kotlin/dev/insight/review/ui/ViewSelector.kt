package dev.insight.review.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.FlowLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel

/**
 * A labelled drop-down for switching between views of the same content. Choosing a different entry calls [onSelect];
 * the entries' tooltips show on the drop-down and on each of its rows.
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
    val selector = JComboBox(modes.toTypedArray<Any>()).apply {
        this.name = name
        this.font = font
        cursor = Cursor.getDefaultCursor()
        selectedItem = selected
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
    add(JLabel("View").apply {
        this.font = font; foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.emptyRight(4)
        labelFor = selector
    })
    add(selector)
}
