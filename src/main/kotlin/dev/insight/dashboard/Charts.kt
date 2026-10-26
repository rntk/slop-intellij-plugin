package dev.insight.dashboard

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.insight.ui.accent
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import javax.swing.JPanel

abstract class Chart : JPanel() {
    init {
        preferredSize = JBUI.size(420, 220)
        minimumSize = Dimension(150, 160)
    }
    final override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val canvas = g.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.font = font
            draw(canvas)
        } finally { canvas.dispose() }
    }
    protected abstract fun draw(g: Graphics2D)
}

class ActivityChart(values: List<Int>) : Chart() {
    var values: List<Int> = values
        set(value) { field = value; repaint() }

    override fun draw(g: Graphics2D) {
        if (values.isEmpty() || width < 100 || height < 80) return
        val left = JBUI.scale(38)
        val top = JBUI.scale(16)
        val plotWidth = width - left - JBUI.scale(18)
        val plotHeight = height - top - JBUI.scale(32)
        val maximum = maxOf(10, ((values.max() + 9) / 10) * 10)
        for (i in 0..4) {
            val y = top + plotHeight * i / 4
            g.color = JBColor.border()
            g.drawLine(left, y, left + plotWidth, y)
            g.color = JBColor.foreground()
            g.drawString("${maximum * (4 - i) / 4}", 2, y + 4)
        }
        val path = Path2D.Double()
        values.forEachIndexed { i, value ->
            val x = left + plotWidth.toDouble() * i / maxOf(1, values.lastIndex)
            val y = top + plotHeight * (1.0 - value.toDouble() / maximum)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        g.color = accent
        g.stroke = BasicStroke(JBUI.scale(3).toFloat())
        g.draw(path)
        g.color = JBColor.foreground()
        g.drawString("${values.size - 1} days ago", left, height - 4)
        g.drawString("Today", width - JBUI.scale(42), height - 4)
    }
}

class CoverageChart : Chart() {
    override fun draw(g: Graphics2D) {
        val rowHeight = (height - 16) / DemoData.modules.size
        DemoData.modules.forEachIndexed { i, module ->
            val y = 8 + rowHeight * i
            val start = JBUI.scale(80)
            val available = maxOf(0, width - start - JBUI.scale(45))
            g.color = JBColor.foreground()
            g.drawString(module.name, 0, y + 16)
            g.color = JBColor.border()
            g.fillRoundRect(start, y, available, 22, 6, 6)
            g.color = accent
            g.fillRoundRect(start, y, available * module.coverage / 100, 22, 6, 6)
            g.color = JBColor.foreground()
            g.drawString("${module.coverage}%", start + available + 6, y + 16)
        }
    }
}

class ModuleGraph : Chart() {
    override fun draw(g: Graphics2D) {
        val nodes = listOf("UI" to (0.2 to 0.25), "Core" to (0.5 to 0.5),
            "Analysis" to (0.8 to 0.25), "Storage" to (0.5 to 0.85))
        val coordinates = nodes.map { (_, p) -> (width * p.first).toInt() to (height * p.second).toInt() }
        g.color = JBColor.border()
        g.stroke = BasicStroke(2f)
        for (i in listOf(0, 2, 3)) {
            g.drawLine(coordinates[1].first, coordinates[1].second, coordinates[i].first, coordinates[i].second)
        }
        nodes.forEachIndexed { i, node ->
            val (x, y) = coordinates[i]
            val nodeWidth = JBUI.scale(76)
            g.color = accent
            g.drawRoundRect(x - nodeWidth / 2, y - 16, nodeWidth, 32, 12, 12)
            g.color = JBColor.foreground()
            g.drawString(node.first, x - g.fontMetrics.stringWidth(node.first) / 2, y + 5)
        }
    }
}
