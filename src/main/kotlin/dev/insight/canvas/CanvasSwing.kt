package dev.insight.canvas

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.LayoutManager
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Stacks visible children top to bottom at the container's full inner width, each as tall as it wants at that width,
 * [gap] pixels apart (read on every layout, so it follows the zoom). Children whose height depends on their width,
 * such as wrapping text, are sized to the width before they are measured.
 */
internal class StackLayout(private val gap: () -> Int = { 0 }) : LayoutManager {
    override fun addLayoutComponent(name: String?, comp: Component?) = Unit
    override fun removeLayoutComponent(comp: Component?) = Unit
    override fun minimumLayoutSize(parent: Container): Dimension = preferredLayoutSize(parent)

    override fun preferredLayoutSize(parent: Container): Dimension {
        val insets = parent.insets
        val visible = parent.components.filter { it.isVisible }
        val width = if (parent.width > 0) parent.width - insets.left - insets.right
            else visible.maxOfOrNull { it.preferredSize.width } ?: 0
        var height = 0
        visible.forEachIndexed { index, child ->
            height += measure(child, width) + if (index > 0) gap() else 0
        }
        return Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom)
    }

    override fun layoutContainer(parent: Container) {
        val insets = parent.insets
        val width = maxOf(parent.width - insets.left - insets.right, 0)
        var y = insets.top
        parent.components.filter { it.isVisible }.forEach { child ->
            val height = measure(child, width)
            child.setBounds(insets.left, y, width, height)
            y += height + gap()
        }
    }

    private fun measure(child: Component, width: Int): Int {
        if (width > 0 && child.width != width) child.setSize(width, maxOf(child.height, 1))
        return child.preferredSize.height
    }
}

/** Read-only wrapping text that can still be selected and copied. */
internal fun wrappedText(text: String): JTextArea = JTextArea(text).apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    border = JBUI.Borders.empty()
    font = UIUtil.getLabelFont()
    foreground = UIUtil.getLabelForeground()
}

/** Runs [action] on a plain left click on this component, with a hand cursor. */
internal fun <T : JComponent> T.onLeftClick(action: (MouseEvent) -> Unit): T = apply {
    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(e)) action(e)
        }
    })
}
