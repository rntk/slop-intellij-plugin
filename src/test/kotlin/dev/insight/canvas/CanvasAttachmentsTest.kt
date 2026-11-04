package dev.insight.canvas

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

class CanvasAttachmentsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `attachments are linked to their owner and round trip with snapshots and chats`() {
        var id = 0
        val model = CanvasSessionModel(CanvasSession.new("Main.kt", id = "session"), ids = { "c${++id}" })
        val owner = model.open(null, CardKind.SELECTION, CardAnchor(1, 4, 2), "Main.kt", 2, "run()")
        val metadata = CanvasAttachment("image/png", 123, "image-snapshot")
        val attachment = model.attach(owner.id, "/outside/screenshot.png", "", metadata)
        val document = model.attach(null, "/outside/notes.md", "Documentation", CanvasAttachment("text/markdown", 13))
        model.setDraft(attachment.id, "Explain this screenshot")
        assertEquals("screenshot.png", attachment.title)
        assertEquals(owner.id, attachment.parentId)
        assertEquals(attachment.id, model.attach(owner.id, attachment.filePath, "", metadata).id)
        assertEquals(3, model.cards.size)
        val store = CanvasSessionStore(folder.root.toPath())
        assertTrue(store.save(model.session))
        assertEquals(model.session, store.load("session"))
        assertEquals(setOf(owner.id, attachment.id), model.remove(owner.id))
        assertEquals(listOf(document), model.cards)
    }

    @Test fun `chat includes attachments on its chain and excludes unrelated branches`() {
        var id = 0
        val model = CanvasSessionModel(CanvasSession.new("Main.kt"), ids = { "c${++id}" })
        val first = model.open(null, CardKind.SELECTION, CardAnchor(0, 1, 0), "Main.kt", 0, "a")
        val second = model.open(null, CardKind.SELECTION, CardAnchor(2, 3, 1), "Main.kt", 1, "b")
        val leaf = model.open(first.id, CardKind.SELECTION, CardAnchor(0, 1, 0), "Main.kt", 0, "a")
        model.attach(null, "root.md", "Root documentation", CanvasAttachment("text/markdown", 1))
        val image = model.attach(first.id, "screen.png", "", CanvasAttachment("image/png", 1, "snapshot"))
        model.attach(second.id, "private.md", "Unrelated documentation", CanvasAttachment("text/markdown", 1))
        val context = canvasChainContext(model.session, leaf.id)
        assertTrue(context, context.contains("Root documentation"))
        assertTrue(context, context.contains("screen.png"))
        assertFalse(context, context.contains("Unrelated documentation"))
        assertFalse(canvasChainContext(model.session, null).contains("screen.png"))
        assertEquals("snapshot", canvasContextImages(model.session, leaf.id).single().base64)
        assertEquals("snapshot", canvasContextImages(model.session, image.id).single().base64)
        assertTrue(canvasContextImages(model.session, second.id).isEmpty())
    }

    @Test fun `text snapshots are bounded and binary files do not become source`() {
        val text = "A".repeat(CANVAS_CARD_TEXT_LIMIT + 100)
        val content = readCanvasAttachment(ByteArrayInputStream(text.toByteArray()), text.length.toLong(), "text/plain", false)
        assertEquals(CANVAS_CARD_TEXT_LIMIT, content.text.length)
        assertTrue(content.truncated)
        val binary = byteArrayOf(1, 2, 0, 4)
        assertEquals("", readCanvasAttachment(ByteArrayInputStream(binary), 4, "application/pdf", true).text)
        assertEquals("", readCanvasAttachment(ByteArrayInputStream(binary), 4, "application/octet-stream", false).text)
    }

    @Test fun `images have bounded snapshots usable after the original disappears`() {
        val original = BufferedImage(3200, 800, BufferedImage.TYPE_INT_RGB)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(original, "png", it) }.toByteArray()
        val content = readCanvasAttachment(ByteArrayInputStream(bytes), bytes.size.toLong(), "image/png", true)
        val restored = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(content.attachment.imageBase64)))
        assertEquals(1600, restored.width)
        assertEquals(400, restored.height)
        assertEquals("", content.text)
    }
}
