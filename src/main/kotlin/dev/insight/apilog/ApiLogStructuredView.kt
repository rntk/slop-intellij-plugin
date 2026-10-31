package dev.insight.apilog

import com.google.gson.JsonElement
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.Scrollable

/** Native, theme-aware foldable blocks. Children are built on first expansion. */
internal class ApiLogStructuredView : JPanel(BorderLayout()) {
    private val content = column()

    init {
        add(JBScrollPane(content), BorderLayout.CENTER)
    }

    fun show(value: JsonElement?) {
        content.removeAll()
        if (value != null) {
            val record = ApiLogValue("Exchange", value)
            record.children().forEach { content.add(block(it)) }
        }
        content.revalidate()
        content.repaint()
    }

    private fun block(value: ApiLogValue): JPanel = object : JPanel(BorderLayout()) {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(3, 8)
        if (value.expandable) {
            val toggle = JToggleButton("▶ ${value.title}").apply {
                horizontalAlignment = JToggleButton.LEFT
                isFocusPainted = true
            }
            add(toggle, BorderLayout.NORTH)
            var children: JPanel? = null
            toggle.addActionListener {
                toggle.text = (if (toggle.isSelected) "▼ " else "▶ ") + value.title
                if (toggle.isSelected && children == null) {
                    children = column().apply {
                        value.children().forEach { add(block(it)) }
                        if (componentCount == 0) add(JBLabel("(empty)"))
                    }
                    add(children, BorderLayout.CENTER)
                }
                children?.isVisible = toggle.isSelected
                revalidate()
                repaint()
            }
        } else {
            add(JBLabel(value.key), BorderLayout.NORTH)
            add(JBTextArea(value.text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(4)
                caretPosition = 0
            }, BorderLayout.CENTER)
        }
    }

    private fun column() = object : JPanel(), Scrollable {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
        override fun getScrollableUnitIncrement(rect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(20)
        override fun getScrollableBlockIncrement(rect: Rectangle, orientation: Int, direction: Int) = rect.height
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
    }
}
