package dev.insight.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

val accent = JBColor(Color(91, 91, 214), Color(156, 156, 255))

fun heading(text: String, size: Float = 20f) = JLabel(text).apply {
    font = font.deriveFont(Font.BOLD, size)
}

fun card(title: String, content: JComponent): JPanel = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
    border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(16))
    add(heading(title, 15f), BorderLayout.NORTH)
    add(content, BorderLayout.CENTER)
}
