package dev.insight.callgraph

import com.intellij.util.ui.JBUI
import dev.insight.enrichment.Flow
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList
import javax.swing.JComboBox
import javax.swing.JComponent
import kotlin.math.round

/** Each panel owns its zoom selection while using the same presets and canvas synchronization. */
internal fun zoomControl(getZoom: () -> Double, setZoom: (Double) -> Unit, canvas: JComponent) =
    JComboBox(arrayOf("25%", "50%", "75%", "100%", "150%")).apply {
        selectedItem = "${round(getZoom() * 100).toInt()}%"
        var updating = false
        addActionListener {
            if (!updating) setZoom(selectedItem.toString().removeSuffix("%").toDouble() / 100)
        }
        canvas.addPropertyChangeListener("zoom") { event ->
            updating = true
            try { model.setSelectedItem("${round(event.newValue as Double * 100).toInt()}%") }
            finally { updating = false }
        }
    }

internal class FlowChoice(val flow: Flow?) {
    override fun toString() = flow?.name ?: "(none)"
}

/** Both panels use the same flow names, descriptions and bounded selector width. */
internal fun flowChoiceControl() = object : JComboBox<FlowChoice>() {
    override fun getPreferredSize() = super.getPreferredSize().also {
        it.width = it.width.coerceIn(JBUI.scale(120), JBUI.scale(280))
    }
}.apply {
    renderer = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, selected: Boolean, focused: Boolean,
        ): Component = super.getListCellRendererComponent(list, value, index, selected, focused).also {
            (it as JComponent).toolTipText = (value as? FlowChoice)?.flow?.description
        }
    }
}
