package dev.insight.diff

import java.awt.Dimension
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.Document
import javax.swing.text.JTextComponent
import javax.swing.text.View
import kotlin.math.ceil

/**
 * Measures at the intended wrap width without repeatedly changing the live view's layout during painting. A null wrap width
 * measures the text unwrapped, on as few lines as its own line breaks allow.
 */
internal class WrappedTextMeasurement(private val component: JTextComponent, private val wrapWidth: Int?) {
    private var measuredSize: Dimension? = null
    private val listener = object : DocumentListener {
        override fun insertUpdate(event: DocumentEvent) { measuredSize = null }
        override fun removeUpdate(event: DocumentEvent) { measuredSize = null }
        override fun changedUpdate(event: DocumentEvent) { measuredSize = null }
    }

    init {
        component.document.addDocumentListener(listener)
        component.addPropertyChangeListener { event ->
            when (event.propertyName) {
                "document" -> {
                    (event.oldValue as? Document)?.removeDocumentListener(listener)
                    (event.newValue as? Document)?.addDocumentListener(listener)
                    measuredSize = null
                }
                "font", "border", "margin", "UI", "graphicsConfiguration", "componentOrientation",
                "lineWrap", "wrapStyleWord", "tabSize" -> measuredSize = null
            }
        }
    }

    fun preferredSize(): Dimension? {
        measuredSize?.let { return Dimension(it) }
        val insets = component.insets
        val root = component.ui?.getRootView(component) ?: return null
        // Painting uses the allocated width, which can differ from wrapWidth. Remeasuring on every preferred-size
        // query makes the view alternate between the two widths and schedule another layout/repaint indefinitely.
        // Unwrapped text keeps a pixel of slack so rounding never pushes its last word onto a second line.
        val width = wrapWidth ?: (ceil(root.getPreferredSpan(View.X_AXIS).toDouble()).toInt() + insets.left + insets.right + 1)
        root.setSize((width - insets.left - insets.right).coerceAtLeast(1).toFloat(), Int.MAX_VALUE.toFloat())
        val measured = Dimension(width, ceil(root.getPreferredSpan(View.Y_AXIS).toDouble()).toInt() + insets.top + insets.bottom)
        measuredSize = measured
        return Dimension(measured)
    }
}
