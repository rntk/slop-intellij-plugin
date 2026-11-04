package dev.insight.canvas

import dev.insight.ui.zoomScaled
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JComponent

/** A bounded snapshot painted at canvas zoom; the original opens through the card's Open file link. */
internal class CanvasImagePreview(base64: String, private val zoom: () -> Double) : JComponent() {
    private val image = runCatching {
        ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(base64)))
    }.getOrNull()

    init { name = "canvas-image-preview"; toolTipText = "Image snapshot; Open file shows the original in the IDE" }

    override fun getPreferredSize(): Dimension {
        val bitmap = image ?: return Dimension(zoomScaled(200, zoom()), zoomScaled(30, zoom()))
        val scale = minOf(1.0, 520.0 / bitmap.width, 420.0 / bitmap.height) * zoom()
        return Dimension((bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val bitmap = image ?: return
        val graphics = g.create() as Graphics2D
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val scale = minOf(width.toDouble() / bitmap.width, height.toDouble() / bitmap.height)
            graphics.drawImage(bitmap, 0, 0, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), null)
        } finally { graphics.dispose() }
    }
}
