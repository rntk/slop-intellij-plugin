package dev.insight.diff

import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.JPanel

/** A bounded one-hop tree, with one shared trunk instead of crossing graph edges. */
internal class DiffSymbolDiagram(
    evidence: DiffSymbolEvidence,
    width: Int,
    private val zoom: Double,
    open: (DiffSymbol) -> Unit,
) : JPanel(null) {
    private fun scaled(n: Int) = zoomScaled(n, zoom)
    private val row = scaled(28)
    private val inset = scaled(10)
    private val indent = scaled(32)
    private val children = mutableListOf<Pair<Int, Boolean>>()

    init {
        isOpaque = false
        fun node(symbol: DiffSymbol, prefix: String, y: Int, root: Boolean = false) {
            val x = if (root) 0 else indent
            add(JPanel(java.awt.BorderLayout()).apply {
                isOpaque = true
                background = UIUtil.getListBackground()
                border = JBUI.Borders.compound(JBUI.Borders.customLine(if (root) JBUI.CurrentTheme.Focus.focusColor() else JBColor.border()),
                    JBUI.Borders.empty(2, 6))
                if (prefix.isNotEmpty()) add(JLabel(prefix).apply {
                    font = JBFont.small().zoomed(zoom)
                    foreground = UIUtil.getContextHelpForeground()
                }, java.awt.BorderLayout.WEST)
                add(ActionLink(symbol.qualifiedName) { open(symbol) }.apply {
                    font = JBFont.label().zoomed(zoom)
                    toolTipText = "${symbol.kind.name.lowercase()} ${symbol.qualifiedName} — ${symbol.path}:${symbol.line}"
                }, java.awt.BorderLayout.CENTER)
                setBounds(x, y, width - x, row - scaled(4))
            })
        }
        node(evidence.symbol, "", 0, root = true)
        var y = row
        var budget = 6
        evidence.links.forEach { links ->
            val visible = links.symbols.take(budget)
            visible.forEach { symbol ->
                node(symbol, "${links.label}  ", y)
                children += y + (row - scaled(4)) / 2 to links.incoming
                y += row
            }
            budget -= visible.size
            val omitted = links.total - visible.size
            if (omitted > 0) {
                add(JLabel("+$omitted ${links.label.lowercase()} not shown").apply {
                    font = JBFont.small().zoomed(zoom)
                    foreground = UIUtil.getContextHelpForeground()
                    setBounds(indent, y, width - indent, row)
                })
                y += row
            }
        }
        if (evidence.links.isEmpty()) {
            add(JLabel("No resolved in-project links for this symbol").apply {
                font = JBFont.small().zoomed(zoom)
                foreground = UIUtil.getContextHelpForeground()
                setBounds(0, y, width, row)
            })
            y += row
        }
        preferredSize = Dimension(width, y)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = UIUtil.getContextHelpForeground()
            g.stroke = BasicStroke(scaled(1).toFloat())
            children.lastOrNull()?.let { (last, _) -> g.drawLine(inset, row - scaled(4), inset, last) }
            children.forEach { (y, incoming) ->
                g.drawLine(inset, y, indent, y)
                val tip = if (incoming) inset else indent
                val tail = tip + if (incoming) scaled(5) else -scaled(5)
                g.drawLine(tip, y, tail, y - scaled(3))
                g.drawLine(tip, y, tail, y + scaled(3))
            }
        } finally { g.dispose() }
    }
}
