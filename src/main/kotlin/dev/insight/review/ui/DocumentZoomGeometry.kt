package dev.insight.review.ui

import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.abs
import kotlin.math.pow

/**
 * The visible part of a zoomed document: [content] is in 100% document coordinates and [screen]
 * is where that area appears in the overlay host. Transitions interpolate these instead of re-laying out Swing.
 */
internal data class DocumentZoomView(val content: Rectangle2D.Double, val screen: Rectangle2D.Double) {
    val zoom: Double get() = screen.width / content.width
}

internal fun easeDocumentZoom(progress: Double): Double = 1 - (1 - progress.coerceIn(0.0, 1.0)).pow(3)

/**
 * Scale changes geometrically, so equal times feel like equal zoom steps. Position follows the change in visible
 * size, which keeps the content under a fixed zoom anchor still for the whole transition.
 */
internal fun documentZoomViewAt(from: DocumentZoomView, to: DocumentZoomView, t: Double): DocumentZoomView {
    val screen = Rectangle2D.Double(
        lerp(from.screen.x, to.screen.x, t), lerp(from.screen.y, to.screen.y, t),
        lerp(from.screen.width, to.screen.width, t), lerp(from.screen.height, to.screen.height, t),
    )
    val z0 = from.zoom
    val z1 = to.zoom
    val z = if (abs(z1 / z0 - 1) < 1e-9) lerp(z0, z1, t) else z0 * (z1 / z0).pow(t)
    val u = if (abs(1 / z1 - 1 / z0) < 1e-9) t else (1 / z - 1 / z0) / (1 / z1 - 1 / z0)
    return DocumentZoomView(
        Rectangle2D.Double(lerp(from.content.x, to.content.x, u), lerp(from.content.y, to.content.y, u),
            screen.width / z, screen.height / z),
        screen,
    )
}

/** Zooms around [anchor] in host coordinates, keeping the content under it in place. */
internal fun documentZoomAround(view: DocumentZoomView, anchor: Point2D, zoom: Double): DocumentZoomView {
    val px = anchor.x - view.screen.x
    val py = anchor.y - view.screen.y
    val x = view.content.x + px / view.zoom - px / zoom
    val y = view.content.y + py / view.zoom - py / zoom
    return DocumentZoomView(Rectangle2D.Double(x, y, view.screen.width / zoom, view.screen.height / zoom), view.screen)
}

/** Maps logical pixels of a snapshot taken at [snapshot] into host coordinates while showing [current]. */
internal fun documentZoomTransform(snapshot: DocumentZoomView, current: DocumentZoomView): AffineTransform {
    val kx = current.screen.width / current.content.width
    val ky = current.screen.height / current.content.height
    return AffineTransform().apply {
        translate(current.screen.x + (snapshot.content.x - current.content.x) * kx,
            current.screen.y + (snapshot.content.y - current.content.y) * ky)
        scale(snapshot.content.width / snapshot.screen.width * kx, snapshot.content.height / snapshot.screen.height * ky)
    }
}

/**
 * Re-expresses [snapshot], currently drawn at [shown], in the coordinates of [into] so it stays exactly where it is
 * on screen. A rebuild at a new zoom changes what document coordinates mean; rebasing lets the old bitmap
 * fade out in place while the new one fades in.
 */
internal fun documentZoomRebase(snapshot: DocumentZoomSnapshot, shown: DocumentZoomView, into: DocumentZoomView): DocumentZoomSnapshot {
    val sx = shown.screen.width / shown.content.width
    val sy = shown.screen.height / shown.content.height
    val ix = into.screen.width / into.content.width
    val iy = into.screen.height / into.content.height
    val c = snapshot.view.content
    val content = Rectangle2D.Double(
        into.content.x + (shown.screen.x - into.screen.x + (c.x - shown.content.x) * sx) / ix,
        into.content.y + (shown.screen.y - into.screen.y + (c.y - shown.content.y) * sy) / iy,
        c.width * sx / ix, c.height * sy / iy,
    )
    return DocumentZoomSnapshot(snapshot.image, snapshot.scale, DocumentZoomView(content, snapshot.view.screen))
}

/**
 * A position inside one of a layout's consecutive bands (file rows, or summary, diff and rail columns).
 * Bands grow at different rates when the zoom changes, so a point keeps its band and its fraction of that band
 * instead of being scaled uniformly.
 */
internal data class DocumentBandAnchor(val band: Int, val fraction: Double)

/** Each band owns the gap after it, so every point from the first band's start to the last band's end has a band. */
internal fun documentBandAnchor(bands: List<Pair<Int, Int>>, at: Double): DocumentBandAnchor? {
    if (bands.isEmpty() || at < bands.first().first || at > bands.last().second) return null
    val band = bands.indexOfLast { it.first <= at }
    val (start, end) = bandSpan(bands, band)
    return DocumentBandAnchor(band, if (end > start) (at - start) / (end - start) else 0.0)
}

internal fun resolveDocumentBandAnchor(anchor: DocumentBandAnchor, bands: List<Pair<Int, Int>>): Double? {
    if (anchor.band !in bands.indices) return null
    val (start, end) = bandSpan(bands, anchor.band)
    return start + anchor.fraction * (end - start)
}

private fun bandSpan(bands: List<Pair<Int, Int>>, band: Int): Pair<Double, Double> =
    bands[band].first.toDouble() to (bands.getOrNull(band + 1)?.first ?: bands[band].second).toDouble()

private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
