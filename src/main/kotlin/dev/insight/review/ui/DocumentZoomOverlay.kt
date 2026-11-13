package dev.insight.review.ui

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Transparency
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import kotlin.math.ceil

/** A bitmap of a document view; the image holds [scale] device pixels per logical pixel of [view].screen. */
internal class DocumentZoomSnapshot(val image: BufferedImage, val scale: Double, val view: DocumentZoomView)

/** Paints [view] of [component] once, so animation frames only transform bitmaps. */
internal fun captureDocumentZoom(component: JComponent, visible: Rectangle, zoom: Double, host: Component, background: Color): DocumentZoomSnapshot? {
    if (visible.isEmpty) return null
    val scale = component.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0 } ?: 1.0
    val image = documentZoomImage(component, visible.width, visible.height, scale)
    val g = image.createGraphics()
    try {
        g.scale(scale, scale)
        g.color = background
        g.fillRect(0, 0, visible.width, visible.height)
        g.translate(-visible.x, -visible.y)
        g.clipRect(visible.x, visible.y, visible.width, visible.height)
        component.paint(g)
    } finally { g.dispose() }
    val origin = SwingUtilities.convertPoint(component, visible.location, host)
    return DocumentZoomSnapshot(image, scale, DocumentZoomView(
        Rectangle2D.Double(visible.x / zoom, visible.y / zoom, visible.width / zoom, visible.height / zoom),
        Rectangle2D.Double(origin.x.toDouble(), origin.y.toDouble(), visible.width.toDouble(), visible.height.toDouble()),
    ))
}

private fun documentZoomImage(component: Component, width: Int, height: Int, scale: Double): BufferedImage {
    val w = ceil(width * scale).toInt().coerceAtLeast(1)
    val h = ceil(height * scale).toInt().coerceAtLeast(1)
    // Compatible opaque images stay accelerated when drawn repeatedly with a transform.
    return component.graphicsConfiguration?.createCompatibleImage(w, h, Transparency.OPAQUE)
        ?: BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
}

/**
 * Covers a document while it zooms. It has no mouse listeners, so input still reaches the document beneath,
 * and it only paints inside the current view so surrounding controls stay visible.
 */
internal class DocumentZoomOverlay(var fill: Color) : JComponent() {
    private var from: DocumentZoomSnapshot? = null
    private var to: DocumentZoomSnapshot? = null
    private var fade = 0.0
    var view: DocumentZoomView? = null
        private set

    init { isOpaque = false }

    fun attach(host: JLayeredPane) {
        if (parent !== host) host.add(this, JLayeredPane.PALETTE_LAYER as Any)
        bounds = Rectangle(0, 0, host.width, host.height)
    }

    fun detach() {
        val host = parent ?: return
        val dirty = dirty()
        host.remove(this)
        dirty?.let { host.repaint(it.x, it.y, it.width, it.height) } ?: host.repaint()
    }

    fun show(from: DocumentZoomSnapshot?, to: DocumentZoomSnapshot?, view: DocumentZoomView, fade: Double) {
        val before = dirty()
        this.from = from; this.to = to; this.view = view; this.fade = fade
        val after = dirty()
        when {
            before == null -> repaint()
            after == null -> repaint(before)
            else -> repaint(before.union(after))
        }
    }

    /** The frame currently on screen, so an interrupted transition can continue from exactly what readers see. */
    fun snapshot(component: Component): DocumentZoomSnapshot? {
        val current = view ?: return null
        val bounds = current.screen.bounds
        if (bounds.isEmpty) return null
        val scale = from?.scale ?: to?.scale ?: 1.0
        val image = documentZoomImage(component, bounds.width, bounds.height, scale)
        val g = image.createGraphics()
        try {
            g.scale(scale, scale)
            g.translate(-current.screen.x, -current.screen.y)
            paintView(g, current)
        } finally { g.dispose() }
        return DocumentZoomSnapshot(image, scale, current)
    }

    override fun paintComponent(g: Graphics) {
        val current = view ?: return
        val g2 = g.create() as Graphics2D
        try { paintView(g2, current) } finally { g2.dispose() }
    }

    private fun paintView(g: Graphics2D, current: DocumentZoomView) {
        g.clip(current.screen)
        g.color = fill
        g.fill(current.screen)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
        from?.let { draw(g, it, current, 1f) }
        to?.let { draw(g, it, current, fade.toFloat().coerceIn(0f, 1f)) }
    }

    private fun draw(g: Graphics2D, snapshot: DocumentZoomSnapshot, current: DocumentZoomView, alpha: Float) {
        if (alpha <= 0f) return
        val transform = documentZoomTransform(snapshot.view, current)
        transform.concatenate(AffineTransform.getScaleInstance(1 / snapshot.scale, 1 / snapshot.scale))
        val composite = g.composite
        if (alpha < 1f) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        g.drawImage(snapshot.image, transform, null)
        g.composite = composite
    }

    private fun dirty(): Rectangle? = view?.screen?.bounds?.let {
        Rectangle(it.x - 1, it.y - 1, it.width + 2, it.height + 2)
    }
}

