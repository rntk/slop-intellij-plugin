package dev.insight.canvas

import dev.insight.llm.LlmImage
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.Base64
import javax.imageio.ImageIO

internal data class CanvasAttachmentContent(val text: String, val attachment: CanvasAttachment, val truncated: Boolean = false)

/** Reads bounded snapshots off the EDT. Any file can be linked, even when no inline preview is available. */
internal fun readCanvasAttachment(input: InputStream, size: Long, mediaType: String, binary: Boolean,
    charset: Charset = Charsets.UTF_8): CanvasAttachmentContent {
    val metadata = CanvasAttachment(mediaType, size)
    if (mediaType.startsWith("image/")) {
        // Inspect dimensions before decoding, then subsample so a large screenshot cannot allocate a huge bitmap.
        try {
            ImageIO.createImageInputStream(input).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                    ?: return CanvasAttachmentContent("", metadata)
                try {
                    reader.input = stream
                    val longest = maxOf(reader.getWidth(0), reader.getHeight(0))
                    val sample = ((longest + 1599) / 1600).coerceAtLeast(1)
                    val params = reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                    val decoded = reader.read(0, params)
                    val image = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_RGB)
                    image.createGraphics().let { graphics ->
                        try {
                            graphics.color = java.awt.Color.WHITE
                            graphics.fillRect(0, 0, image.width, image.height)
                            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                            graphics.drawImage(decoded, 0, 0, null)
                        } finally { graphics.dispose() }
                    }
                    val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
                    return CanvasAttachmentContent("", metadata.copy(imageBase64 = Base64.getEncoder().encodeToString(bytes)))
                } finally { reader.dispose() }
            }
        } catch (_: IOException) {
            return CanvasAttachmentContent("", metadata)
        } catch (_: IllegalArgumentException) {
            return CanvasAttachmentContent("", metadata)
        }
    }
    if (binary) return CanvasAttachmentContent("", metadata)
    val text = input.reader(charset).readTextBounded(CANVAS_CARD_TEXT_LIMIT + 1)
    // Unknown file types sometimes default to text. Do not feed binary payloads to the source viewer or the model.
    if ('\u0000' in text) return CanvasAttachmentContent("", metadata)
    val (bounded, cut) = boundCardText(text)
    return CanvasAttachmentContent(bounded, metadata, cut)
}

private fun java.io.Reader.readTextBounded(limit: Int): String {
    val buffer = CharArray(limit)
    var count = 0
    while (count < limit) {
        val read = read(buffer, count, limit - count)
        if (read < 0) break
        count += read
    }
    return String(buffer, 0, count)
}

/** Attachment cards relevant to this chat: its chain, plus attachments linked to any step of that chain. */
internal fun canvasContextAttachments(session: CanvasSession, cardId: String?): List<CanvasCard> {
    val chain = cardId?.let(session::chain).orEmpty()
    val parents = chain.mapTo(mutableSetOf<String?>(null)) { it.id }
    return (chain.filter { it.kind == CardKind.ATTACHMENT } +
        session.cards.filter { it.kind == CardKind.ATTACHMENT && it.parentId in parents }).distinctBy { it.id }
}

/** Only snapshots explicitly attached to the current chain are sent; other branches stay out of the request. */
internal fun canvasContextImages(session: CanvasSession, cardId: String?): List<LlmImage> =
    canvasContextAttachments(session, cardId).sortedBy { if (it.id == cardId) 0 else 1 }.mapNotNull { card ->
        card.attachment?.imageBase64?.let { LlmImage("image/png", it) }
    }.take(4)

internal fun canvasAttachmentContext(session: CanvasSession, cardId: String?): String = buildString {
    var remaining = 16_000
    val images = canvasContextAttachments(session, cardId).sortedBy { if (it.id == cardId) 0 else 1 }
        .filter { it.attachment?.imageBase64 != null }.take(4).map { it.id }
    for (card in canvasContextAttachments(session, cardId)) {
        append("\nAttachment [card ${card.id}] linked to ${card.parentId ?: CANVAS_ROOT}: ${card.filePath}\n")
        card.attachment?.let { append("Type: ${it.mediaType}; size: ${it.size} bytes.\n") }
        when {
            card.attachment?.imageBase64 != null -> {
                val index = images.indexOf(card.id)
                if (index >= 0) append("Image ${index + 1} accompanying this question.\n")
                else append("Image omitted from this question's four-image limit; ask in its own attachment card to inspect it.\n")
            }
            card.text.isNotEmpty() -> {
                val text = card.text.take(remaining)
                append(text).append('\n')
                remaining -= text.length
                if (text.length < card.text.length || card.textTruncated) append("[Attachment text truncated; read_card shows its saved text.]\n")
            }
            else -> append("No inline content is available. Open the linked file in the IDE to inspect it.\n")
        }
    }
}
