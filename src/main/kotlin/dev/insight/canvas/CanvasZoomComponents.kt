package dev.insight.canvas

import com.intellij.util.ui.JBUI
import dev.insight.ui.zoomScaled
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Insets
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.plaf.basic.BasicTabbedPaneUI

/** Like schematic callable titles, card names stay readable when the surrounding source shrinks. */
internal class CanvasTitleLabel(text: String, icon: Icon?) : JLabel(text, icon, LEFT) {
    override fun setFont(font: Font?) {
        super.setFont(font?.deriveFont(maxOf(font.size2D, JBUI.scale(14).toFloat())))
    }
}

/** Wrapped tabs whose padding scales with their text, without the IDE theme's fixed minimum tab size. */
internal class CanvasChatTabs(private val zoom: () -> Double) : JTabbedPane(TOP, WRAP_TAB_LAYOUT) {
    override fun updateUI() {
        setUI(object : BasicTabbedPaneUI() {
            private fun scaled(value: Int) = zoomScaled(value, zoom())
            override fun getTabInsets(tabPlacement: Int, tabIndex: Int) =
                Insets(scaled(2), scaled(8), scaled(2), scaled(8))
            override fun getSelectedTabPadInsets(tabPlacement: Int) = Insets(0, 0, 0, 0)
            override fun getTabAreaInsets(tabPlacement: Int) = Insets(0, 0, 0, 0)
            override fun getContentBorderInsets(tabPlacement: Int) = Insets(0, 0, 0, 0)
            override fun calculateTabHeight(tabPlacement: Int, tabIndex: Int, fontHeight: Int): Int {
                val insets = getTabInsets(tabPlacement, tabIndex)
                return fontHeight + insets.top + insets.bottom
            }
            override fun calculateTabWidth(tabPlacement: Int, tabIndex: Int, metrics: FontMetrics): Int {
                val insets = getTabInsets(tabPlacement, tabIndex)
                return metrics.stringWidth(getTitleAt(tabIndex)) + insets.left + insets.right
            }
        })
    }
}
