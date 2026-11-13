package dev.insight.review.ui

import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentZoomGeometryTest {
    private val screen = Rectangle2D.Double(10.0, 20.0, 400.0, 300.0)
    private fun view(x: Double, y: Double, zoom: Double) =
        DocumentZoomView(Rectangle2D.Double(x, y, screen.width / zoom, screen.height / zoom), screen)

    @Test
    fun `transition ends exactly at both views and changes scale geometrically`() {
        val from = view(0.0, 0.0, 0.25)
        val to = view(300.0, 900.0, 1.0)
        assertEquals(from, documentZoomViewAt(from, to, 0.0))
        assertEquals(to.content.x, documentZoomViewAt(from, to, 1.0).content.x, 1e-9)
        assertEquals(to.zoom, documentZoomViewAt(from, to, 1.0).zoom, 1e-9)
        assertEquals(0.5, documentZoomViewAt(from, to, 0.5).zoom, 1e-9)
    }

    @Test
    fun `content under a zoom anchor stays still during the transition`() {
        val from = view(100.0, 200.0, 1.0)
        val anchor = Point2D.Double(110.0, 120.0)
        val to = documentZoomAround(from, anchor, 2.0)
        fun contentAt(v: DocumentZoomView) = Point2D.Double(
            v.content.x + (anchor.x - v.screen.x) / v.zoom, v.content.y + (anchor.y - v.screen.y) / v.zoom)
        val expected = contentAt(from)
        listOf(0.25, 0.5, 0.9).forEach { t ->
            val actual = contentAt(documentZoomViewAt(from, to, t))
            assertEquals(expected.x, actual.x, 1e-9)
            assertEquals(expected.y, actual.y, 1e-9)
        }
    }

    @Test
    fun `snapshot transform places its content where the current view shows it`() {
        val snapshot = view(50.0, 80.0, 0.5)
        val current = view(60.0, 100.0, 1.0)
        // Snapshot pixel (40, 60) shows content (50 + 80, 80 + 120) = (130, 200).
        val mapped = documentZoomTransform(snapshot, current).transform(Point2D.Double(40.0, 60.0), null)
        assertEquals(screen.x + 130 - 60, mapped.x, 1e-9)
        assertEquals(screen.y + 200 - 100, mapped.y, 1e-9)
    }

    @Test
    fun `rebased snapshot stays where it was drawn`() {
        val snapshot = DocumentZoomSnapshot(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), 1.0,
            view(50.0, 80.0, 0.5))
        val shown = view(60.0, 100.0, 1.0)
        val into = view(400.0, 900.0, 2.5)
        val rebased = documentZoomRebase(snapshot, shown, into)
        val pixel = Point2D.Double(40.0, 60.0)
        val expected = documentZoomTransform(snapshot.view, shown).transform(pixel, null)
        val actual = documentZoomTransform(rebased.view, into).transform(pixel, null)
        assertEquals(expected.x, actual.x, 1e-9)
        assertEquals(expected.y, actual.y, 1e-9)
    }

    @Test
    fun `band anchors keep a point's place in its row when rows grow unevenly`() {
        val before = listOf(0 to 100, 110 to 150, 160 to 400)
        val after = listOf(0 to 300, 330 to 340, 370 to 1000)
        val anchor = documentBandAnchor(before, 130.0)!!
        assertEquals(DocumentBandAnchor(1, 0.4), anchor)
        // The second row now starts at 330 and, with its gap, spans to 370.
        assertEquals(346.0, resolveDocumentBandAnchor(anchor, after)!!, 1e-9)
        assertEquals(1000.0, resolveDocumentBandAnchor(documentBandAnchor(before, 400.0)!!, after)!!, 1e-9)
        assertEquals(null, documentBandAnchor(before, 401.0))
        assertEquals(null, resolveDocumentBandAnchor(anchor, after.take(1)))
    }
}
